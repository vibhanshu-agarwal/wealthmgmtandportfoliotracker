package com.wealth.market;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class MarketDataRefreshJobTest {

    @Test
    void refreshAllTrackedTickersDelegatesToRefreshService() {
        MarketDataRefreshService refreshService = mock(MarketDataRefreshService.class);
        MarketDataRefreshJob job = new MarketDataRefreshJob(refreshService);

        job.refreshAllTrackedTickers();

        verify(refreshService).refresh();
        verifyNoMoreInteractions(refreshService);
    }
}
