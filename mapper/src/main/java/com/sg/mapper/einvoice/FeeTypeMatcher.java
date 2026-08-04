package com.sg.mapper.einvoice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a fee-type token into a {@code (feeId, feeType)} pair drawn from the referential
 * {@code Map<feeId, feeType>} supplied by a {@link FeeTypeProvider}.
 *
 * <p>Ported from the original {@code com.sg.domaininterface.mapper.einvoice.FeeTypeMatcher}
 * (abstract MapStruct {@code @Mapper} class). Two shape changes vs the source:
 *
 * <ul>
 *   <li><b>@Mapper annotation removed.</b> Now a plain {@code final} class with a constructor-
 *       injected {@link FeeTypeProvider}. No MapStruct processor, no Spring bean formation.</li>
 *   <li><b>Marker parsing lifted out.</b> The original coupled the {@code _MARK_} literal into
 *       the extractor. Business-marker parsing now lives in
 *       {@code invoice-service-registration/EInvoiceMarkerParser}; this class only takes a
 *       pre-extracted fee-type spelling. Kept an {@link #extractFeeType(String)} helper for
 *       legacy callers that still hand in the composed {@code "<siren>_MARK_<FEETYPE>"}
 *       string.</li>
 * </ul>
 *
 * <h2>Matching strategy</h2>
 * Token-set (Jaccard) matching, not character-based. The raw fee type and each referential
 * value are split on {@code _ - space . /}, upper-cased, and compared as sets. Consequences:
 *
 * <ul>
 *   <li>Separator variants converge: {@code CUSTODY_FEE} == {@code CUSTODY-FEE}.</li>
 *   <li>Ordering variants converge: {@code CUSTODY_FEE} == {@code FEE_CUSTODY}.</li>
 *   <li>Case variants converge: referential {@code Custody} matches raw {@code CUSTODY}.</li>
 *   <li>Partial inputs resolve when unambiguous: {@code PRINCIPAL} → {@code BROKERAGE_PRINCIPAL}
 *       because {@code BROKERAGE_AGENCY} shares no token with it.</li>
 *   <li>Genuinely ambiguous inputs fail rather than guess: a bare {@code BROKERAGE} ties at 0.5
 *       against both {@code BROKERAGE_PRINCIPAL} and {@code BROKERAGE_AGENCY} → unresolved.</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * All tokenisation of the referential happens once, when the index is built. The index is
 * rebuilt only when {@link FeeTypeProvider} hands back a different {@code Map} instance
 * (reference-identity guard, not {@code equals}). Per-call cost in steady state is one
 * {@code ConcurrentHashMap} lookup.
 */
public final class FeeTypeMatcher {

  private static final String MARK = "_MARK_";

  /** Upper bound on distinct extracted fee-type spellings held per index. */
  private static final int MAX_CACHE_ENTRIES = 10_000;

  private final FeeTypeProvider feeTypeProvider;

  /** Rebuilt only when the provider returns a different map instance. */
  private volatile FeeTypeIndex index;

  public FeeTypeMatcher(FeeTypeProvider feeTypeProvider) {
    this.feeTypeProvider = Objects.requireNonNull(feeTypeProvider, "feeTypeProvider");
  }

  /** Result carrier: the referential key and value that were matched. */
  public record FeeTypeMatch(String feeId, String feeType) {}

  // ------------------------------------------------------------------
  // Public API
  // ------------------------------------------------------------------

  /**
   * Resolves the fee code, throwing when it cannot be resolved.
   *
   * @throws FeeTypeResolutionException if the code is malformed, matches no referential entry,
   *         or is too ambiguous to resolve to exactly one.
   */
  public FeeTypeMatch resolve(String feeType) {
    Resolution r = resolveInternal(feeType);
    if (r.error() != null) {
      throw new FeeTypeResolutionException(r.error());
    }
    return r.match();
  }

  /**
   * Resolves the fee code, returning {@code null} when it cannot be resolved. Preferred when
   * a failure should not abort the caller — pair with {@link #explainFailure(String)} for a
   * warn-level log.
   */
  public FeeTypeMatch resolveOrNull(String feeType) {
    if (feeType == null || feeType.isBlank()) {
      return null;
    }
    return resolveInternal(feeType).match();
  }

  /** Convenience accessor — {@code null} when unresolved. */
  public String toFeeId(String feeType) {
    FeeTypeMatch m = resolveOrNull(feeType);
    return m == null ? null : m.feeId();
  }

  /** Convenience accessor — {@code null} when unresolved. */
  public String toFeeType(String feeType) {
    FeeTypeMatch m = resolveOrNull(feeType);
    return m == null ? null : m.feeType();
  }

  /**
   * Diagnostic accessor: the failure reason for a code that does not resolve, or {@code null}
   * if it resolves. Useful for logging without paying for an exception.
   */
  public String explainFailure(String feeType) {
    if (feeType == null || feeType.isBlank()) {
      return "Fee type is null/blank";
    }
    return resolveInternal(feeType).error();
  }

  /**
   * Legacy convenience: given the full composed marker string
   * {@code "<siren>_MARK_<FEETYPE>"}, extract the trailing fee-type portion and resolve it.
   * New callers should not need this: {@link EInvoiceMappingAdapter} parses the marker with
   * {@code EInvoiceMarkerParser} (in the domain, since the marker is a domain concept) and
   * calls {@link #resolveOrNull} with the extracted token.
   */
  public FeeTypeMatch resolveFromMarker(String composedMarker) {
    if (composedMarker == null || composedMarker.isBlank()) return null;
    try {
      return resolveOrNull(extractFeeType(composedMarker));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  // ------------------------------------------------------------------
  // Resolution pipeline
  // ------------------------------------------------------------------

  /** Either {@code match} is non-null, or {@code error} is non-null. */
  private record Resolution(FeeTypeMatch match, String error) {}

  private Resolution resolveInternal(String feeType) {
    FeeTypeIndex idx = index();
    Resolution cached = idx.cache.get(feeType);
    if (cached != null) {
      return cached;
    }
    Resolution computed = idx.match(feeType);
    if (idx.cache.size() < MAX_CACHE_ENTRIES) {
      idx.cache.putIfAbsent(feeType, computed);
    }
    return computed;
  }

  /**
   * Extracts the fee-type portion of a legacy composed marker string
   * {@code "<siren>_MARK_<FEETYPE>"}. Primary strategy: locate the literal {@code _MARK_}
   * separator (preserves underscores inside the tail, e.g. {@code BROKERAGE_PRINCIPAL}).
   * Fallback: "everything after the second underscore".
   */
  static String extractFeeType(String raw) {
    int mark = raw.indexOf(MARK);
    if (mark >= 0) {
      String tail = raw.substring(mark + MARK.length());
      if (tail.isBlank()) {
        throw new IllegalArgumentException("Empty fee type in fee code: " + raw);
      }
      return tail;
    }
    int first = raw.indexOf('_');
    int second = (first < 0) ? -1 : raw.indexOf('_', first + 1);
    if (second < 0 || second == raw.length() - 1) {
      throw new IllegalArgumentException("Malformed fee code: " + raw);
    }
    return raw.substring(second + 1);
  }

  private FeeTypeIndex index() {
    Map<String, String> src = feeTypeProvider.getFeeTypeMap();
    if (src == null) {
      throw new FeeTypeResolutionException("FeeTypeProvider returned a null map");
    }
    FeeTypeIndex local = index;
    if (local == null || local.source != src) {
      local = new FeeTypeIndex(src);
      // Benign race: two threads may build concurrently on referential refresh. Both indexes
      // are correct; the loser is simply discarded.
      index = local;
    }
    return local;
  }

  // ------------------------------------------------------------------
  // Precomputed index over the referential
  // ------------------------------------------------------------------

  private static final class FeeTypeIndex {

    /** Identity guard — the exact map instance this index was built from. */
    private final Map<String, String> source;

    private final String[] feeIds;
    private final String[] feeTypes;
    private final int[] tokenCounts;

    /** token → ids of referential entries containing that token. */
    private final Map<String, int[]> postings;

    /** sorted-token key → id, for the O(1) exact-match fast path. */
    private final Map<String, Integer> canonicalExact;

    /** Canonical keys held by more than one referential entry. */
    private final Set<String> canonicalAmbiguous;

    /** Memoised resolutions, keyed on extracted fee type. */
    private final ConcurrentHashMap<String, Resolution> cache = new ConcurrentHashMap<>();

    FeeTypeIndex(Map<String, String> source) {
      this.source = source;

      int n = source.size();
      this.feeIds = new String[n];
      this.feeTypes = new String[n];
      this.tokenCounts = new int[n];
      this.canonicalExact = new HashMap<>(Math.max(16, n * 2));
      this.canonicalAmbiguous = new HashSet<>();

      Map<String, List<Integer>> tmpPostings = new HashMap<>(Math.max(16, n * 4));

      int i = 0;
      for (Map.Entry<String, String> e : source.entrySet()) {
        feeIds[i] = e.getKey();
        feeTypes[i] = e.getValue();

        Set<String> tokens = tokenize(e.getValue() == null ? "" : e.getValue());
        tokenCounts[i] = tokens.size();

        for (String t : tokens) {
          tmpPostings.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }

        if (!tokens.isEmpty()) {
          String canon = canonicalKey(tokens);
          if (canonicalExact.putIfAbsent(canon, i) != null) {
            canonicalAmbiguous.add(canon);
          }
        }
        i++;
      }

      this.postings = new HashMap<>(Math.max(16, tmpPostings.size() * 2));
      for (Map.Entry<String, List<Integer>> e : tmpPostings.entrySet()) {
        List<Integer> ids = e.getValue();
        int[] arr = new int[ids.size()];
        for (int k = 0; k < arr.length; k++) {
          arr[k] = ids.get(k);
        }
        this.postings.put(e.getKey(), arr);
      }
    }

    Resolution match(String feeTypePart) {
      Set<String> inputTokens = tokenize(feeTypePart);
      if (inputTokens.isEmpty()) {
        return new Resolution(null, "No usable tokens in fee type: " + feeTypePart);
      }

      // Fast path: identical token set → Jaccard 1.0, cannot be beaten.
      String canon = canonicalKey(inputTokens);
      if (canonicalAmbiguous.contains(canon)) {
        return new Resolution(null,
            "Ambiguous fee type '" + feeTypePart
                + "': the referential holds multiple entries with identical tokens "
                + inputTokens);
      }
      Integer exact = canonicalExact.get(canon);
      if (exact != null) {
        int id = exact;
        return new Resolution(new FeeTypeMatch(feeIds[id], feeTypes[id]), null);
      }

      // Scoring path: only entries sharing ≥1 token can score above zero.
      int n = feeIds.length;
      int[] intersection = new int[n];
      int[] touched = new int[n];
      int touchedCount = 0;

      for (String token : inputTokens) {
        int[] posting = postings.get(token);
        if (posting == null) continue;
        for (int id : posting) {
          if (intersection[id]++ == 0) {
            touched[touchedCount++] = id;
          }
        }
      }

      if (touchedCount == 0) {
        return new Resolution(null, "No fee type match found for: " + feeTypePart);
      }

      int inputSize = inputTokens.size();
      double bestScore = -1d;
      int bestId = -1;
      int tieCount = 0;

      for (int k = 0; k < touchedCount; k++) {
        int id = touched[k];
        int shared = intersection[id];
        double score = (double) shared / (inputSize + tokenCounts[id] - shared);

        if (score > bestScore) {
          bestScore = score;
          bestId = id;
          tieCount = 1;
        } else if (score == bestScore) {
          tieCount++;
        }
      }

      if (tieCount > 1) {
        return new Resolution(null,
            "Ambiguous fee type '" + feeTypePart + "': " + tieCount
                + " referential entries tied at score " + bestScore
                + " — the code is not specific enough to disambiguate");
      }
      return new Resolution(new FeeTypeMatch(feeIds[bestId], feeTypes[bestId]), null);
    }
  }

  // ------------------------------------------------------------------
  // Tokenising
  // ------------------------------------------------------------------

  /**
   * Splits on {@code _ - space . /} into an upper-cased token set. Single pass, no regex.
   *
   * <p><b>On noise tokens.</b> An earlier version filtered a {@code NOISE_TOKENS} set here so a
   * word like {@code FEE} could be ignored when it never distinguishes two referential entries.
   * The set shipped empty, which made the filter unreachable — and an unreachable filter is a
   * claim the code cannot back up. If a noise word does appear in production codes, reinstate
   * the filter together with the entries that justify it, and extend this class's tests to pin
   * the collapse it is meant to cause.
   */
  static Set<String> tokenize(String s) {
    Set<String> tokens = new HashSet<>(4);
    int start = 0;
    int len = s.length();

    for (int i = 0; i <= len; i++) {
      char c = (i < len) ? s.charAt(i) : '_'; // sentinel flushes final token
      if (c == '_' || c == '-' || c == ' ' || c == '.' || c == '/') {
        if (i > start) {
          tokens.add(s.substring(start, i).toUpperCase(Locale.ROOT));
        }
        start = i + 1;
      }
    }
    return tokens;
  }

  /** Order-independent key for a token set. */
  private static String canonicalKey(Set<String> tokens) {
    if (tokens.size() == 1) {
      return tokens.iterator().next();
    }
    String[] arr = tokens.toArray(new String[0]);
    Arrays.sort(arr);
    return String.join(" ", arr);
  }

  // ------------------------------------------------------------------

  /** Thrown by {@link #resolve(String)} when a fee code cannot be resolved. */
  public static class FeeTypeResolutionException extends RuntimeException {
    public FeeTypeResolutionException(String message) {
      super(message);
    }
  }
}
