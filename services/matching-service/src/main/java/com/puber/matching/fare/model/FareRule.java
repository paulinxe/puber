package com.puber.matching.fare.model;

import com.puber.matching.shared.model.Money;
import java.math.BigDecimal;

/**
 * The price list: one row of {@code fare_rules}, mirroring its columns exactly.
 *
 * <p>The mixed types are the convention showing through, not an oversight. Each field is typed as
 * what it actually is:
 *
 * <ul>
 *   <li>{@code baseFare} is an <em>amount</em> -- 250 is 2.50 -- so it is {@link Money}, the same
 *       type the finished fare comes back as.
 *   <li>{@code perKmRate} and {@code perMinuteRate} are deliberately <em>not</em> {@code Money}.
 *       They are money <em>per unit</em>: 120 is 1.20 per kilometre, 25 is 0.25 per minute. Typing
 *       them as an amount would make adding one to {@code baseFare} look reasonable, and adding a
 *       rate to an amount is meaningless. They stay {@code long} minor units, read from {@code
 *       BIGINT}.
 *   <li>{@code surgeMultiplier} is a coefficient, not an amount, so it is a {@code BigDecimal} read
 *       from {@code DECIMAL(4,2)}. Expressing it as an integer count of hundredths would put a
 *       second and different scaling convention in the same row.
 * </ul>
 */
public record FareRule(
        Money baseFare, long perKmRate, long perMinuteRate, BigDecimal surgeMultiplier) {}
