package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link LocalMarketDataSeeder} is NOT present in the application context
 * when the {@code aws} profile is active (belt-and-suspenders guard alongside
 * {@code market.seed.enabled=false} in {@code application-aws.yml}).
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("aws")
@Testcontainers
class LocalMarketDataSeederAwsProfileIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @MockitoBean
    @SuppressWarnings("unused")
    KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

    @Autowired
    ApplicationContext applicationContext;

    // -------------------------------------------------------------------------
    // 7.3 — seeder bean is absent under aws profile
    // -------------------------------------------------------------------------
    @Test
    void seeder_doesNotActivate_underAwsProfile() {
        assertThat(applicationContext.containsBean("localMarketDataSeeder")).isFalse();
    }
}
