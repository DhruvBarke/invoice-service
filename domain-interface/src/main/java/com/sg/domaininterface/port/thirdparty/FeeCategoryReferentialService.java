package com.sg.domaininterface.port.thirdparty;

import java.util.Map;

/**
 * The fee-type referential the marker's fee token is matched against.
 *
 * <p>A third-party port. This used to be read straight from a {@code t_fee_type} table, which
 * meant the invoice service held its own copy of a referential someone else owns — and a copy is
 * only ever as current as the last time somebody remembered to refresh it. It comes over the
 * referential API now, like the party and document data.
 */
public interface FeeCategoryReferentialService {

  /**
   * The whole fee-type referential, as {@code feeId -> feeType}.
   *
   * <p>Loaded whole rather than queried per fee type because the matcher scores a token against
   * every candidate to find the closest — it needs the full set, not a lookup. The result is
   * small (hundreds of entries) and is expected to be cached by the caller for a TTL window; see
   * {@code CachingFeeTypeProvider}.
   *
   * @return the referential, in its own order. Order matters: the matcher's tie-breaking is
   *         positional, so a reordering would silently change which of two equally-good
   *         candidates wins.
   * @throws ReferentialUnavailableException when the referential could not be reached. Never an
   *         empty map on failure — an empty referential resolves nothing, which would refuse
   *         every invoice in flight and look like a data problem rather than an outage.
   */
  Map<String, String> findAllFeeTypes();
}
