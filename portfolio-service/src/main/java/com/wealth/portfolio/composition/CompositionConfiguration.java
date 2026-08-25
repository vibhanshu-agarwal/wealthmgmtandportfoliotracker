package com.wealth.portfolio.composition;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CompositionConfiguration {

    @Bean
    Clock compositionClock() {
        return Clock.systemUTC();
    }
}
