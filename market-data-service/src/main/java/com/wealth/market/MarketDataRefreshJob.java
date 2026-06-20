package com.wealth.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "market-data.refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
class MarketDataRefreshJob {

    private final MarketDataRefreshService refreshService;

    MarketDataRefreshJob(MarketDataRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @Scheduled(cron = "${market-data.refresh.cron:0 0 */1 * * *}")
    void refreshAllTrackedTickers() {
        refreshService.refresh();
    }
}
