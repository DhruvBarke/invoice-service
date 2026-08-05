package com.sg.domaininterface.port.out;

import java.util.Map;

/**
 * SPI implemented by the consuming application to supply the fee-type referential to the
 * {@link FeeTypeMatcher}. The mapper module never depends on any repository or cache type; the
 * application wires an implementation (typically a lambda over its repository) at composition time.
 *
 * <p>Ported from {@code com.sg.domaininterface.spi.FeeTypeProvider}; interface unchanged apart
 * from the package rename.
 *
 * <p><b>Contract:</b> implementations MUST return the <em>same</em> {@code Map} instance for as
 * long as the underlying data is unchanged. {@link FeeTypeMatcher} uses reference identity (not
 * {@code equals}) to decide whether its precomputed index is still valid. Returning a
 * freshly-built {@code HashMap} on every call forces a full index rebuild per invocation and
 * destroys the performance of the matcher.
 */
@FunctionalInterface
public interface FeeTypeProvider {

  /** @return {@code Map<feeId, feeType>} — never {@code null}. */
  Map<String, String> getFeeTypeMap();
}
