package com.puber.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.puber.matching.shared.strategy.Clock;
import com.puber.matching.shared.strategy.SystemClock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The clock the running service actually holds is the real one.
 *
 * <p>Every unit test would stay green with no {@code Clock} bean wired at all, because they each
 * build their own. This is the only test that says anything about the running service.
 */
@SpringBootTest
class ClockWiringIntegrationTest {

    @Autowired private ApplicationContext context;

    @Autowired private Clock clock;

    @Test
    @DisplayName("AC4: the running context exposes exactly one Clock, and it is SystemClock")
    void the_running_context_is_wired_with_the_real_clock() {
        Map<String, Clock> clocks = context.getBeansOfType(Clock.class);

        assertEquals(
                1,
                clocks.size(),
                () ->
                        "the service must hold exactly one Clock -- two would mean two time"
                                + " semantics in one process: "
                                + clocks.keySet());
        assertInstanceOf(
                SystemClock.class,
                clocks.values().iterator().next(),
                "the running service is wired with something other than the real clock");
    }

    @Test
    @DisplayName("AC4: the injected Clock reads real time")
    void the_injected_clock_reads_real_time() {
        Instant before = Instant.now();
        Instant reading = clock.wallClockNow();
        Instant after = Instant.now();

        assertFalse(reading.isBefore(before), () -> reading + " precedes " + before);
        assertFalse(reading.isAfter(after), () -> reading + " follows " + after);
    }
}
