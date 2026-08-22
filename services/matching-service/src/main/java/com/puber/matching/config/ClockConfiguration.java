package com.puber.matching.config;

import com.puber.matching.shared.strategy.Clock;
import com.puber.matching.shared.strategy.SystemClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one place that names the real {@link Clock}, so nothing else has to know it exists. */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return new SystemClock();
    }
}
