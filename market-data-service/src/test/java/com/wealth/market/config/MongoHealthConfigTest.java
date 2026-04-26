package com.wealth.market.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoHealthConfigTest {

    @Test
    void mongoHealthIndicator_usesDatabasePing() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenReturn(new Document("ok", 1));

        var health = new MongoHealthConfig().mongoHealthIndicator(mongoTemplate).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("check", "ping");
        verify(mongoTemplate).executeCommand("{ ping: 1 }");
    }

    @Test
    void mongoHealthIndicator_reportsDownWhenPingFails() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenThrow(new IllegalStateException("boom"));

        var health = new MongoHealthConfig().mongoHealthIndicator(mongoTemplate).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("check", "ping");
    }
}