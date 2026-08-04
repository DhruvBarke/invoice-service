package com.sg.domaininterface.port.thirdparty;

import com.sg.domaininterface.model.party.PartyRegistrationDetails;
import com.sg.domaininterface.model.referential.PartySearchRequest;
import java.util.List;

/**
 * Party registration data, from the referential.
 *
 * <p>A third-party port: the application declares it, {@code third-parties} implements it over
 * the referential's HTTP API, and nothing in between knows that HTTP is involved.
 *
 * <p><b>Separate from {@link com.sg.domaininterface.port.out.PartyRegistrationLookup}, on
 * purpose.</b> That port is what the application asks — "give me this party" — and the caching
 * adapter answers it. This one is where the answer comes from when the cache has nothing. Two
 * ports because they fail differently and are configured differently: a cache miss is routine,
 * a referential timeout is not, and only one of the two is worth retrying.
 *
 * <p>Takes a {@link PartySearchRequest} rather than a string, because the referential is queried
 * on any of several criteria and the populated ones decide the URL. A method per criterion would
 * mean a new port method, a new adapter method and a new test every time the referential learns
 * to filter on something else.
 */
public interface PartyReferentialService {

  /**
   * Every party matching the criteria.
   *
   * @param request the criteria; never null, and never entirely empty — see
   *                {@link PartySearchRequest}
   * @return the matches, in the referential's own order. Empty when nothing matched, which is an
   *         answer rather than a failure: a party that is not registered is a real and common
   *         state, and the caller decides what it means.
   * @throws com.sg.domaininterface.port.out.PartyRegistrationUnavailableException when the
   *         referential could not be reached or answered with something unusable. Distinct from
   *         an empty result precisely so the caller can tell "no such party" from "we do not
   *         currently know", which lead to different outcomes for an invoice.
   */
  List<PartyRegistrationDetails> search(PartySearchRequest request);
}
