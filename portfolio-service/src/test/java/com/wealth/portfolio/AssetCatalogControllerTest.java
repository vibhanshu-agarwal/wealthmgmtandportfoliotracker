package com.wealth.portfolio;

import com.wealth.catalog.CatalogEntry;
import com.wealth.catalog.LifecycleStatus;
import com.wealth.catalog.SupportedCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AssetCatalogControllerTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String CATALOG_VERSION = "abc123catalog";

    @Mock
    SupportedCatalog catalog;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssetCatalogController(catalog))
                .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
                .build();
    }

    @Test
    void returnsFullCatalogIncludingDeprecatedWithoutPrices() throws Exception {
        when(catalog.version()).thenReturn(CATALOG_VERSION);
        when(catalog.all()).thenReturn(List.of(
                new CatalogEntry("AAPL", "Apple", List.of("apple"), "EQUITY", "USD", LifecycleStatus.ACTIVE),
                new CatalogEntry(
                        "TATAMOTORS.NS",
                        "Tata Motors",
                        List.of(),
                        "EQUITY",
                        "INR",
                        LifecycleStatus.DEPRECATED)));

        mockMvc.perform(get("/api/assets").header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogVersion").value(CATALOG_VERSION))
                .andExpect(jsonPath("$.assets.length()").value(2))
                .andExpect(jsonPath("$.assets[*].ticker", hasItem("TATAMOTORS.NS")))
                .andExpect(jsonPath("$.assets[0].name").value("Apple"))
                .andExpect(jsonPath("$.assets[0].aliases[0]").value("apple"))
                .andExpect(jsonPath("$.assets[0].assetClass").value("EQUITY"))
                .andExpect(jsonPath("$.assets[0].quoteCurrency").value("USD"))
                .andExpect(jsonPath("$.assets[0].lifecycleStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.assets[1].lifecycleStatus").value("DEPRECATED"))
                .andExpect(jsonPath("$.assets[0].basePrice").doesNotExist())
                .andExpect(jsonPath("$.assets[0].currentPrice").doesNotExist())
                .andExpect(jsonPath("$.assets[0].price").doesNotExist())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + CATALOG_VERSION + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
    }

    @Test
    void missingAuthHeaderReturns400() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Required header 'X-User-Id' is missing"));
    }

    @Test
    void matchingStrongIfNoneMatchReturns304() throws Exception {
        when(catalog.version()).thenReturn(CATALOG_VERSION);

        mockMvc.perform(get("/api/assets")
                        .header("X-User-Id", USER_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"" + CATALOG_VERSION + "\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + CATALOG_VERSION + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
    }

    @Test
    void matchingWeakIfNoneMatchReturns304() throws Exception {
        when(catalog.version()).thenReturn(CATALOG_VERSION);

        mockMvc.perform(get("/api/assets")
                        .header("X-User-Id", USER_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, "W/\"" + CATALOG_VERSION + "\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + CATALOG_VERSION + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
    }

    @Test
    void matchingValidatorInListReturns304() throws Exception {
        when(catalog.version()).thenReturn(CATALOG_VERSION);

        mockMvc.perform(get("/api/assets")
                        .header("X-User-Id", USER_ID)
                        .header(HttpHeaders.IF_NONE_MATCH,
                                "\"other-tag\", W/\"" + CATALOG_VERSION + "\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + CATALOG_VERSION + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
    }

    @Test
    void starIfNoneMatchReturns304() throws Exception {
        when(catalog.version()).thenReturn(CATALOG_VERSION);

        mockMvc.perform(get("/api/assets")
                        .header("X-User-Id", USER_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + CATALOG_VERSION + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
    }
}
