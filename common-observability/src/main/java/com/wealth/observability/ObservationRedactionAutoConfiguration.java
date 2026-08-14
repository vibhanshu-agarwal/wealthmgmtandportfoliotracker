package com.wealth.observability;

import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
public class ObservationRedactionAutoConfiguration {

    @Bean
    ObservationFilter redactingObservationFilter() {
        return new RedactingObservationFilter();
    }

    @Bean
    GlobalObservationConvention<?> httpRouteTemplatingObservationConvention() {
        return new HttpRouteTemplatingObservationConvention();
    }
}
