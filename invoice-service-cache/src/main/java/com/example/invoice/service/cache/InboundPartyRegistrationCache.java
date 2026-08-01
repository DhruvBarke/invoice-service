package com.example.invoice.service.cache;

import com.example.invoice.service.domain.model.Flow;
import com.example.invoice.service.domain.model.KeySpace;
import com.example.invoice.service.domain.model.PartyRegistrationDetails;
import com.example.invoice.service.domain.model.RegistrationType;
import com.example.invoice.service.domain.port.out.ReferentialGateway;
import com.example.invoice.service.domain.port.out.ResponseGuard;
import com.example.invoice.service.domain.rule.GoldenRecordSelector;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Inbound-flow cache: resolves parties by registration number.
 *
 * <p>Two independent cores, one per key space. SIREN and SIRET are never cross-populated — see the
 * module documentation for why — which also keeps their failure domains separate.
 *
 * <p>Duplicate collapsing is delegated to {@link GoldenRecordSelector}: which record wins is a
 * domain decision, not a caching one.
 */
public final class InboundPartyRegistrationCache implements AutoCloseable {

    private final ReferentialCacheCore sirenCore;
    private final ReferentialCacheCore siretCore;
    private final ExecutorService maintenance;

    public InboundPartyRegistrationCache(ReferentialGateway gateway, CacheConfig config,
                                         ResponseGuard guard) {
        this(gateway, config, config, guard);
    }

    /** Separate configs let SIRET be sized differently — it is usually the higher-volume key. */
    public InboundPartyRegistrationCache(ReferentialGateway gateway, CacheConfig sirenConfig,
                                         CacheConfig siretConfig, ResponseGuard guard) {
        Objects.requireNonNull(gateway, "gateway");
        // Virtual threads: refresh-ahead is I/O-bound and sweeps are short bursts.
        this.maintenance = Executors.newVirtualThreadPerTaskExecutor();

        this.sirenCore = new ReferentialCacheCore(KeySpace.SIREN, Flow.INBOUND, sirenConfig, guard,
                maintenance,
                key -> gateway.searchByRegistration(key, RegistrationType.SIREN),
                PartyRegistrationDetails::siren);

        this.siretCore = new ReferentialCacheCore(KeySpace.SIRET, Flow.INBOUND, siretConfig, guard,
                maintenance,
                key -> gateway.searchByRegistration(key, RegistrationType.SIRET),
                PartyRegistrationDetails::siret);
    }

    public Optional<PartyRegistrationDetails> findBySiren(String siren) {
        return GoldenRecordSelector.select(sirenCore.lookup(siren));
    }

    public Optional<PartyRegistrationDetails> findBySiret(String siret) {
        return GoldenRecordSelector.select(siretCore.lookup(siret));
    }

    public List<PartyRegistrationDetails> findAllBySiret(String siret) {
        return siretCore.lookup(siret);
    }

    public void invalidate(KeySpace keySpace, String registrationId) {
        core(keySpace).invalidate(registrationId);
    }

    public void invalidateAll() {
        sirenCore.invalidateAll();
        siretCore.invalidateAll();
    }

    /** @return one entry per key space, reported separately rather than summed. */
    public List<CacheStats> stats() {
        return List.of(sirenCore.stats(), siretCore.stats());
    }

    @Override
    public void close() {
        maintenance.shutdownNow();
        try {
            maintenance.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ReferentialCacheCore core(KeySpace keySpace) {
        return keySpace == KeySpace.SIREN ? sirenCore : siretCore;
    }
}
