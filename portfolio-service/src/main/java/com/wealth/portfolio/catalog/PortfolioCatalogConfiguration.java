package com.wealth.portfolio.catalog;

import com.wealth.catalog.CatalogLoadFailedException;
import com.wealth.catalog.SeedCatalogView;
import com.wealth.catalog.SupportedCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class PortfolioCatalogConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PortfolioCatalogConfiguration.class);
    private static final String FALLBACK_SERVICE_NAME = "portfolio-service";

    private final Environment environment;

    PortfolioCatalogConfiguration(Environment environment) {
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
    SeedCatalogView seedCatalogView(SupportedCatalog catalog) {
        return catalog.seedView();
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> catalogLoadedLogger(SupportedCatalog catalog) {
        return event ->
                log.info(
                        "catalog_loaded version={} entries={} active={} rejectUnsupportedEvents=false enforceHoldingInvariant=false",
                        catalog.version(),
                        catalog.all().size(),
                        catalog.active().size());
    }
}

