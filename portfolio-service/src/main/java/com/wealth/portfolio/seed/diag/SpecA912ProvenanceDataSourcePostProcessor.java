package com.wealth.portfolio.seed.diag;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Activates pooled-session provenance wrapping only when diagnostics is enabled and demo seeding
 * is disabled.
 */
@Component
final class SpecA912ProvenanceDataSourcePostProcessor implements BeanPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(SpecA912ProvenanceDataSourcePostProcessor.class);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!"dataSource".equals(beanName) || !(bean instanceof DataSource dataSource)) {
            return bean;
        }
        if (bean instanceof SpecA912ProvenanceDataSource) {
            return bean;
        }

        boolean diagnostics =
                Boolean.parseBoolean(environment.getProperty("APP_DEMO_TX_DIAGNOSTICS", "false"));
        boolean demoSeed =
                Boolean.parseBoolean(environment.getProperty("app.demo.seed-on-startup", "false"));

        if (diagnostics && demoSeed) {
            log.warn(
                    "event=spec_a912_pool_session_provenance_rejected reason=APP_DEMO_TX_DIAGNOSTICS and seed-on-startup both enabled");
            return bean;
        }
        if (!diagnostics || demoSeed) {
            return bean;
        }

        return new SpecA912ProvenanceDataSource(
                dataSource, SpecA912PooledSessionProvenance.logging());
    }
}
