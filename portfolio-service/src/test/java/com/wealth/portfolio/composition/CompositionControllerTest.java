package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.PortfolioRepository;
import com.wealth.portfolio.PortfolioResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP contract for production {@link CompositionController} (B1 Tasks 7.1–7.2). Real controller,
 * real Jackson/advice path; adapter mocked.
 */
@ExtendWith(MockitoExtension.class)
class CompositionControllerTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PATH = "/api/portfolio/holdings";
    private static final UUID PORTFOLIO_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant CREATED = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-02T00:00:00Z");

    @Mock private CompositionWriteService writeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new CompositionController(writeService))
                        .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
                        .build();
    }

    @Test
    void existingPortfolioReplaceReturns200WithQuantityString() throws Exception {
        PortfolioResponse response =
                new PortfolioResponse(
                        PORTFOLIO_ID,
                        USER_ID,
                        CREATED,
                        UPDATED,
                        8L,
                        List.of(
                                new PortfolioResponse.HoldingResponse(
                                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                                        "AAPL",
                                        new BigDecimal("0.75000000"))));
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenReturn(new CompositionWriteService.Outcome(response, false));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"expectedVersion":7,"holdings":[{"ticker":"AAPL","quantity":"0.75000000"}]}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PORTFOLIO_ID.toString()))
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.holdings[0].quantity").value("0.75000000"));

        ArgumentCaptor<CompositionHoldingsRequest> captor =
                ArgumentCaptor.forClass(CompositionHoldingsRequest.class);
        verify(writeService).replace(eq(USER_ID), captor.capture());
        CompositionHoldingsRequest request = captor.getValue();
        assertThat(request.expectedVersion()).isEqualTo(7L);
        assertThat(request.holdings()).hasSize(1);
        assertThat(request.holdings().getFirst().ticker()).isEqualTo("AAPL");
        assertThat(request.holdings().getFirst().quantity())
                .isEqualByComparingTo(new BigDecimal("0.75000000"));
    }

    @Test
    void firstCreationReturns201() throws Exception {
        PortfolioResponse response =
                new PortfolioResponse(PORTFOLIO_ID, USER_ID, CREATED, UPDATED, 1L, List.of());
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenReturn(new CompositionWriteService.Outcome(response, true));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":0,\"holdings\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void existingEmptyNoOpReturns200Not201() throws Exception {
        PortfolioResponse response =
                new PortfolioResponse(PORTFOLIO_ID, USER_ID, CREATED, UPDATED, 3L, List.of());
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenReturn(new CompositionWriteService.Outcome(response, false));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":3,\"holdings\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void missingGatewayIdentityReturns400WithoutAdapterCall() throws Exception {
        mockMvc.perform(
                        put(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":0,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Required header 'X-User-Id' is missing"));

        verify(writeService, never()).replace(any(), any());
    }

    @Test
    void bodyAndQueryIdentityCannotRedirectAuthenticatedTarget() throws Exception {
        String spoofed = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        PortfolioResponse response =
                new PortfolioResponse(PORTFOLIO_ID, USER_ID, CREATED, UPDATED, 1L, List.of());
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenReturn(new CompositionWriteService.Outcome(response, false));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .queryParam("userId", spoofed)
                                .queryParam("portfolioId", PORTFOLIO_ID.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"expectedVersion":1,"userId":"%s","portfolioId":"%s","holdings":[]}
                                        """
                                                .formatted(spoofed, PORTFOLIO_ID)))
                .andExpect(status().isOk());

        verify(writeService, times(1)).replace(eq(USER_ID), any(CompositionHoldingsRequest.class));
        verify(writeService, never()).replace(eq(spoofed), any());
    }

    @Test
    void portfolioIdUrlVariantDoesNotInvokeReplacementPath() throws Exception {
        mockMvc.perform(
                        put("/api/portfolio/" + PORTFOLIO_ID + "/holdings")
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":0,\"holdings\":[]}"))
                .andExpect(status().isNotFound());

        verify(writeService, never()).replace(any(), any());
    }

    @ParameterizedTest(name = "valid expectedVersion {0} reaches adapter")
    @ValueSource(longs = {0L, 1L, 42L, Long.MAX_VALUE})
    void validExpectedVersionsReachAdapterUnchanged(long expectedVersion) throws Exception {
        PortfolioResponse response =
                new PortfolioResponse(PORTFOLIO_ID, USER_ID, CREATED, UPDATED, expectedVersion, List.of());
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenReturn(new CompositionWriteService.Outcome(response, false));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expectedVersion\":"
                                                + expectedVersion
                                                + ",\"holdings\":[]}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CompositionHoldingsRequest> captor =
                ArgumentCaptor.forClass(CompositionHoldingsRequest.class);
        verify(writeService).replace(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(expectedVersion);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopeFailureCases")
    void envelopeFailuresReturnContractCodeWithoutAdapterCall(String name, String body, String error)
            throws Exception {
        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(error));

        verify(writeService, never()).replace(any(), any());
    }

    static Stream<Arguments> envelopeFailureCases() {
        return Stream.of(
                Arguments.of("missing_version", "{\"holdings\":[]}", "missing_version"),
                Arguments.of(
                        "null_version",
                        "{\"expectedVersion\":null,\"holdings\":[]}",
                        "invalid_version"),
                Arguments.of(
                        "fractional_version",
                        "{\"expectedVersion\":7.9,\"holdings\":[]}",
                        "invalid_version"),
                Arguments.of(
                        "string_version",
                        "{\"expectedVersion\":\"7\",\"holdings\":[]}",
                        "invalid_version"),
                Arguments.of(
                        "boolean_version",
                        "{\"expectedVersion\":true,\"holdings\":[]}",
                        "invalid_version"),
                Arguments.of(
                        "negative_version",
                        "{\"expectedVersion\":-1,\"holdings\":[]}",
                        "invalid_version"),
                Arguments.of(
                        "overflow_version",
                        "{\"expectedVersion\":9223372036854775808,\"holdings\":[]}",
                        "invalid_version"),
                Arguments.of("malformed_json", "{", "malformed_request"),
                Arguments.of("json_null_body", "null", "malformed_request"),
                Arguments.of(
                        "quantity_not_string",
                        "{\"expectedVersion\":0,\"holdings\":[{\"ticker\":\"AAPL\",\"quantity\":1.5}]}",
                        "quantity_not_string"),
                Arguments.of(
                        "missing_holdings",
                        "{\"expectedVersion\":0}",
                        "malformed_request"),
                Arguments.of(
                        "null_ticker",
                        "{\"expectedVersion\":0,\"holdings\":[{\"ticker\":null,\"quantity\":\"1.00000000\"}]}",
                        "malformed_request"),
                Arguments.of(
                        "null_holding_element",
                        "{\"expectedVersion\":0,\"holdings\":[null]}",
                        "malformed_request"),
                Arguments.of(
                        "mixed_null_holding_element",
                        "{\"expectedVersion\":0,\"holdings\":[{\"ticker\":\"AAPL\",\"quantity\":\"1.00000000\"},null]}",
                        "malformed_request"));
    }

    @Test
    void absentBodyMapsToMalformedRequestWithoutAdapterCall() throws Exception {
        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));

        verify(writeService, never()).replace(any(), any());
    }

    @Test
    void versionConflictPropagates409Envelope() throws Exception {
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenThrow(new PortfolioVersionConflictException(7L));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":2,\"holdings\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("portfolio_version_conflict"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.currentVersion").value(7));
    }

    @Test
    void quantityOutOfDomainPropagates400WithTickers() throws Exception {
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenThrow(new QuantityOutOfDomainException(List.of("AAPL", "MSFT")));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"expectedVersion":1,"holdings":[{"ticker":"AAPL","quantity":"0.00000000"}]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("quantity_out_of_domain"))
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.tickers[0]").value("AAPL"))
                .andExpect(jsonPath("$.tickers[1]").value("MSFT"));
    }

    @Test
    void duplicateTickerPropagates400WithTickers() throws Exception {
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenThrow(new DuplicateTickerException(List.of("AAPL")));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"expectedVersion":1,"holdings":[
                                          {"ticker":"AAPL","quantity":"1.00000000"},
                                          {"ticker":"AAPL","quantity":"2.00000000"}
                                        ]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("duplicate_ticker"))
                .andExpect(jsonPath("$.tickers[0]").value("AAPL"));
    }

    @Test
    void unsupportedAssetsPropagates422WithCatalogFields() throws Exception {
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenThrow(new UnsupportedAssetsException(List.of("FOO", "BAR"), "cat-v1"));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"expectedVersion":1,"holdings":[{"ticker":"FOO","quantity":"1.00000000"}]}
                                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("unsupported_asset"))
                .andExpect(jsonPath("$.catalogVersion").value("cat-v1"))
                .andExpect(jsonPath("$.ticker").value("FOO"))
                .andExpect(jsonPath("$.tickers[0]").value("FOO"))
                .andExpect(jsonPath("$.tickers[1]").value("BAR"));
    }

    @Test
    void lifecycleNotPermittedPropagates422WithCatalogFields() throws Exception {
        when(writeService.replace(eq(USER_ID), any(CompositionHoldingsRequest.class)))
                .thenThrow(new LifecycleNotPermittedException(List.of("TATAMOTORS.NS"), "cat-v1"));

        mockMvc.perform(
                        put(PATH)
                                .header("X-User-Id", USER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"expectedVersion":1,"holdings":[{"ticker":"TATAMOTORS.NS","quantity":"1.00000000"}]}
                                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("lifecycle_not_permitted"))
                .andExpect(jsonPath("$.ticker").value("TATAMOTORS.NS"))
                .andExpect(jsonPath("$.catalogVersion").value("cat-v1"));
    }
}
