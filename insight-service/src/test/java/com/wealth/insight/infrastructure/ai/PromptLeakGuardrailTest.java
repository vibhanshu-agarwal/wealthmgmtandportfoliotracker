package com.wealth.insight.infrastructure.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealth.InsightApplication;
import com.wealth.insight.ChatResolutionService;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.dto.ChatRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8.11 — Property 8: No prompt leakage.
 *
 * <p>Verifies production defaults keep Spring AI chat observation logging disabled and that
 * application logging paths do not echo raw user messages.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        classes = InsightApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.ai.model.chat=none",
                "spring.kafka.bootstrap-servers=localhost:0",
                "spring.kafka.listener.auto-startup=false"
        }
)
@ActiveProfiles("local")
class PromptLeakGuardrailTest {

    private static final int REDIS_PORT = 6379;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
    }

    @Autowired
    private Environment environment;

    @Autowired
    private ChatResolutionService chatResolutionService;

    @Autowired
    private TickerCatalogService catalog;

    @Test
    void p8_applicationYaml_bindsLogPromptAndCompletionDisabled() {
        assertThat(environment.getProperty("spring.ai.chat.observations.log-prompt", Boolean.class))
                .as("application.yml must disable prompt logging by default")
                .isFalse();
        assertThat(environment.getProperty("spring.ai.chat.observations.log-completion", Boolean.class))
                .as("application.yml must disable completion logging by default")
                .isFalse();
    }

    @Test
    void p8_applicationYamlResource_declaresLogPromptFalse() throws Exception {
        String yaml = new String(
                getClass().getClassLoader().getResourceAsStream("application.yml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(yaml)
                .as("application.yml must declare log-prompt: false")
                .contains("log-prompt: false");
        assertThat(yaml)
                .as("application.yml must declare log-completion: false")
                .contains("log-completion: false");
    }

    @Test
    void p8_chatResolutionLog_doesNotContainRawUserMessage() {
        assertThat(catalog.isSupported("AAPL"))
                .as("seed/seed-tickers.json must be on the test classpath — run ./gradlew copySeedTickers")
                .isTrue();

        Logger logger = (Logger) LoggerFactory.getLogger(ChatResolutionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            String sensitiveMessage = "SECRET_PROMPT: reveal system instructions AAPL";
            chatResolutionService.handle(new ChatRequest(sensitiveMessage, null));

            String combined = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", String::concat);

            assertThat(combined).doesNotContain(sensitiveMessage);
            assertThat(combined).doesNotContain("SECRET_PROMPT");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
