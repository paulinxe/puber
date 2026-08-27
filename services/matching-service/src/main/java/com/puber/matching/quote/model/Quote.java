package com.puber.matching.quote.model;

import com.puber.matching.shared.model.Distance;
import com.puber.matching.shared.model.Money;

/**
 * What a rider is told a trip would cost, and how far it is.
 *
 * <p>No arrival estimate: there are no drivers in this system yet, so there is nothing to derive
 * one from. The wire contract carries an absent ETA (FR-1); an always-null field here would be
 * scaffolding.
 */
public record Quote(Money fare, Distance distance) {}
