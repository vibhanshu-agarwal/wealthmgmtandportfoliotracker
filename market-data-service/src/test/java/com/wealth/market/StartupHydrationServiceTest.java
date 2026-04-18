package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StartupHydrationServiceTest {

    private final AssetPriceRepository assetPriceRepository = mock(AssetPriceRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mock(KafkaTemplate.class);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final StartupHydrationService service =
            new StartupHydrationService(assetPriceRepository, kafkaTemplate, meterRegistry);

    private final ApplicationArguments args = new DefaultApplicationArguments(new String[0]);

    @Test
    void publishesEventsOnlyForAssetsWithNonNullPrice() {
        AssetPrice withPrice1 = new AssetPrice("AAPL", BigDecimal.TEN);
        AssetPrice withPrice2 = new AssetPrice("MSFT", BigDecimal.ONE);
        AssetPrice withoutPrice = new AssetPrice("NEW-ONLY-TICKER", null);

        when(assetPriceRepository.findAll()).thenReturn(List.of(withPrice1, withPrice2, withoutPrice));

        service.run(args);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<PriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(PriceUpdatedEvent.class);

        verify(kafkaTemplate, times(2)).send(eq("market-prices"), anyString(), eventCaptor.capture());

        List<PriceUpdatedEvent> events = eventCaptor.getAllValues();
        assertThat(events)
                .extracting(PriceUpdatedEvent::ticker)
                .containsExactlyInAnyOrder("AAPL", "MSFT");

        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void doesNothingWhenNoAssetsPresent() {
        when(assetPriceRepository.findAll()).thenReturn(List.of());

        service.run(args);

        verifyNoInteractions(kafkaTemplate);
    }
}

