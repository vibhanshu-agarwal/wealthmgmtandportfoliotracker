package com.wealth.insight.catalog;

import com.wealth.catalog.CatalogLoadFailedException;
import com.wealth.catalog.SupportedCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class InsightCatalogConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InsightCatalogConfiguration.class);
    private static final String FALLBACK_SERVICE_NAME = "insight-service";

    private final Environment environment;

    InsightCatalogConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    SupportedCatalog supportedCatalog() {
        String serviceName =
                environment.getProperty("spring.application.name", FALLBACK_SERVICE_NAME);
        try {
            return SupportedCatalog.load();
        } catch (CatalogLoadFailedException e) {
            log.error(
                    "catalog_load_failed resource={} service={} violations={}",
                    e.resourcePath(),
                    serviceName,
                    e.violations());
            throw e;
        }
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> catalogLoadedLogger(SupportedCatalog catalog) {
        boolean rejectUnsupportedEvents =
                environment.getProperty("app.catalog.reject-unsupported-events", Boolean.class, false);
        boolean enforceHoldingInvariant =
                environment.getProperty("app.catalog.enforce-holding-invariant", Boolean.class, false);
        return event ->
                log.info(
                        "catalog_loaded version={} entries={} active={} rejectUnsupportedEvents={} enforceHoldingInvariant={}",
                        catalog.version(),
                        catalog.all().size(),
                        catalog.active().size(),
                        rejectUnsupportedEvents,
                        enforceHoldingInvariant);
    }
}
