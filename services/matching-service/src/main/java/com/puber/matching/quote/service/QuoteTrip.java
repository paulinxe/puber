package com.puber.matching.quote.service;

import com.puber.matching.fare.repository.FareRuleRepository;
import com.puber.matching.fare.service.CalculateFare;
import com.puber.matching.quote.model.Quote;
import com.puber.matching.shared.model.Coordinates;
import com.puber.matching.shared.model.Distance;
import org.springframework.stereotype.Service;

@Service
public class QuoteTrip {

    private final FareRuleRepository fareRules;

    public QuoteTrip(FareRuleRepository fareRules) {
        this.fareRules = fareRules;
    }

    public Quote execute(Coordinates pickup, Coordinates dropoff) {
        Distance distance = pickup.distanceTo(dropoff);
        return new Quote(CalculateFare.calculate(fareRules.priceList(), distance), distance);
    }
}
