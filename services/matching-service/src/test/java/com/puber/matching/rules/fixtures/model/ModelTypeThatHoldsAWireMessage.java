package com.puber.matching.rules.fixtures.model;

import com.puber.contracts.quote.v1.GetQuoteResponse;

/**
 * A domain type holding a generated contract message.
 *
 * <p>The generated stubs are {@code com.puber.contracts..}, so the rule's {@code com.puber..}
 * allowance readmitted them and a domain record could carry a wire message unchallenged. Story 1.4
 * proved that hole by planting and closed it with an exclusion -- this fixture is what keeps the
 * exclusion honest, because deleting it from the rule leaves no production type violating it and
 * therefore nothing red.
 */
public final class ModelTypeThatHoldsAWireMessage {

    public long fareOf(GetQuoteResponse response) {
        return response.getFareMinorUnits();
    }
}
