package com.sg.domaininterface.port.thirdparty;

import com.sg.domaininterface.model.provider.SsiDetails;
import java.util.List;

/**
 * The standing settlement instructions SG holds for a provider.
 *
 * <p>Scoped by all four of provider, currency, entity and fee category because settlement is
 * agreed at that granularity: the same provider is paid into different accounts for different
 * currencies, and a match found by ignoring one of them is not a match anyone agreed to.
 */
@FunctionalInterface
public interface SsiReferentialService {

  /**
   * @return every instruction on file for this combination, in the referential's order. Empty when
   *         there are none — a provider with no agreed account is a real and reportable state,
   *         not a failure.
   * @throws ReferentialUnavailableException when the referential could not be reached. The
   *         distinction matters more here than almost anywhere: an outage collapsed into an empty
   *         list reads as "no account is on file", which is what stops a payment.
   */
  List<SsiDetails> find(String providerId, String currency, String sgEntity, String feeCategory);
}
