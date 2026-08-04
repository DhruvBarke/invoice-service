package com.sg.caching;

import com.sg.domaininterface.model.party.Flow;
import com.sg.domaininterface.model.party.KeySpace;
import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.referential.PartySearchRequest;
import com.sg.domaininterface.port.thirdparty.PartyReferentialService;
import com.sg.domaininterface.port.out.ResponseGuard;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Outbound-flow cache: resolves parties by BDR id.
 *
 * <p>Elementary and golden ids share one key space — see {@link KeySpace#BDR_ID}.
 */
public final class OutboundPartyRegistrationCache implements AutoCloseable {

    private final ReferentialCacheCore core;
    private final ExecutorService maintenance;

    public OutboundPartyRegistrationCache(PartyReferentialService referential, CacheConfig config,
                                          ResponseGuard guard) {
        Objects.requireNonNull(referential, "referential");
        this.maintenance = Executors.newVirtualThreadPerTaskExecutor();
        this.core = new ReferentialCacheCore(KeySpace.BDR_ID, Flow.OUTBOUND, config, guard,
                maintenance, id -> referential.search(PartySearchRequest.byGoldenBdrId(id)), PartyRegistrationDetails::goldenBdrId);
    }

    public Optional<PartyRegistrationDetails> findByBdrId(String bdrId) {
        List<PartyRegistrationDetails> all = core.lookup(bdrId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    public void invalidate(String bdrId) {
        core.invalidate(bdrId);
    }

    public void invalidateAll() {
        core.invalidateAll();
    }

    public CacheStats stats() {
        return core.stats();
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
}
