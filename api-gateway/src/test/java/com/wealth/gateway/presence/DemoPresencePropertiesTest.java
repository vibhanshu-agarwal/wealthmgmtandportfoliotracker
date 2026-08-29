package com.wealth.gateway.presence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoPresencePropertiesTest {

    @Test
    void defaultBinding_is150Seconds() {
        DemoPresenceProperties properties = bind(Map.of("app.demo-presence.ttl", "150s"));

        assertThat(properties.ttl()).isEqualTo(Duration.ofSeconds(150));
    }

    @Test
    void rejectsZeroDuration() {
        assertThatThrownBy(() -> bind(Map.of("app.demo-presence.ttl", "0s")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsNegativeDuration() {
        assertThatThrownBy(() -> bind(Map.of("app.demo-presence.ttl", "-5s")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsFractionalSecondDuration() {
        assertThatThrownBy(() -> bind(Map.of("app.demo-presence.ttl", "500ms")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("whole number of seconds");
    }

    private static DemoPresenceProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        try {
            return binder.bind("app.demo-presence", DemoPresenceProperties.class).get();
        } catch (BindException ex) {
            throw new RuntimeException(ex);
        }
    }
}
