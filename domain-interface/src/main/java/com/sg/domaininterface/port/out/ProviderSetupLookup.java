package com.sg.domaininterface.port.out;

import com.sg.domaininterface.model.provider.ProviderSetup;
import java.util.Optional;

/**
 * Whether a provider is activated for payment and for accounting.
 *
 * <p>A {@code port.out} rather than a {@code port.thirdparty}: the manual registration path reads
 * this from a table in this service's own schema, not from a referential API. The distinction is
 * not cosmetic — a local read cannot be unavailable in the way a remote one can, so callers do not
 * need the retryable/not-retryable machinery the referential ports carry.
 */
@FunctionalInterface
public interface ProviderSetupLookup {

  /**
   * @param providerMnemo    the provider's mnemonic
   * @param feeCategory      the fee category name, as stored on the payable
   * @param sgEntityMnemonic the SG entity's mnemonic
   * @return the setup row, or empty when this combination has none. Empty is a normal answer: a
   *         provider not yet onboarded for a fee category simply has no row.
   */
  Optional<ProviderSetup> find(String providerMnemo, String feeCategory, String sgEntityMnemonic);
}
