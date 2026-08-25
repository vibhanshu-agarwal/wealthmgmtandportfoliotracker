package com.wealth.portfolio.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wealth.catalog.UnsupportedAssetException;
import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate error-contract suite ({@code *ErrorContractTest}) for Wave 4c / future R-C. Proves
 * envelope reachability, precedence, aggregation, and Spec A singular body preservation via the
 * test-only boundary harness — production {@code PUT /api/portfolio/holdings} remains Wave 7.
 */
@ExtendWith(MockitoExtension.class)
class CompositionErrorContractTest {

    @Mock PortfolioRepository portfolioRepository;
    @Mock HoldingReplacementService replacementService;
    @Mock CompositionCatalogValidator catalogValidator;
    @Mock TuplePreparer preparer;

    private MockMvc mockMvc;
    private GlobalExceptionHandler handler;
    private AtomicBoolean replaceInvoked;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(portfolioRepository);
        replaceInvoked = new AtomicBoolean(false);
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new StatefulBoundaryProbeController(
                                        replacementService, replaceInvoked))
                        .setControllerAdvice(handler)
                        .build();
    }

    @Test
    void malformedJsonMapsToMalformedRequest() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void missingExpectedVersionMapsToMissingVersion() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_version"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void explicitNullExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":null,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void floatExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":7.9,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void stringExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":\"7\",\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void booleanExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":true,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void negativeExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":-1,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void overflowExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expectedVersion\":9223372036854775808,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void quantityAsJsonNumberAgainstStaleVersionMapsToQuantityNotStringBeforeStatefulWork()
            throws Exception {
        // Observable seams that would be hit if decode succeeded against a stale expectedVersion.
        // Envelope failure must win before repository lookup / replace / CAS.
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"expectedVersion":3,"holdings":[{"ticker":"AAPL","quantity":1.5}]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("quantity_not_string"));

        assertThat(replaceInvoked.get()).isFalse();
        verify(replacementService, never())
                .replace(anyString(), anyLong(), anyList(), any(TuplePreparer.class));
        verify(portfolioRepository, never()).findByUserId(anyString());
        verify(catalogValidator, never()).validate(anyList(), anyList());
        verify(preparer, never()).materialise(anyList(), anyList());
    }

    @Test
    void quantityAsJsonNumberMapsToQuantityNotString() throws Exception {
        mockMvc.perform(
                        put("/__test__/composition-boundary")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"expectedVersion":0,"holdings":[{"ticker":"AAPL","quantity":1.5}]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("quantity_not_string"));
        assertStatefulSeamsUntouched();
    }

    @Test
    void versionConflictMapsTo409WithCurrentVersion() {
        ResponseEntity<ContractError> response =
                handler.handlePortfolioVersionConflict(new PortfolioVersionConflictException(7L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(
                        body -> {
                            assertThat(body.error())
                                    .isEqualTo(ContractErrorCode.portfolio_version_conflict);
                            assertThat(body.currentVersion()).isEqualTo(7L);
                        });
    }

    @Test
    void duplicateTickerMapsTo400WithTickers() {
        ResponseEntity<ContractError> response =
                handler.handleDuplicateTicker(new DuplicateTickerException(List.of("AAPL", "MSFT")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(
                        body -> {
                            assertThat(body.error()).isEqualTo(ContractErrorCode.duplicate_ticker);
                            assertThat(body.ticker()).isEqualTo("AAPL");
                            assertThat(body.tickers()).containsExactly("AAPL", "MSFT");
                        });
    }

    @Test
    void quantityOutOfDomainMapsTo400WithTickers() {
        ResponseEntity<ContractError> response =
                handler.handleQuantityOutOfDomain(
                        new QuantityOutOfDomainException(List.of("AAPL", "MSFT")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(
                        body -> {
                            assertThat(body.error())
                                    .isEqualTo(ContractErrorCode.quantity_out_of_domain);
                            assertThat(body.ticker()).isEqualTo("AAPL");
                            assertThat(body.tickers()).containsExactly("AAPL", "MSFT");
                        });
    }

    @Test
    void unsupportedAssetsMapsTo422WithTickerAndTickers() {
        ResponseEntity<ContractError> response =
                handler.handleUnsupportedAssets(
                        new UnsupportedAssetsException(List.of("FOO", "BAR"), "cat-v1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(
                        body -> {
                            assertThat(body.error())
                                    .isEqualTo(ContractErrorCode.unsupported_asset);
                            assertThat(body.catalogVersion()).isEqualTo("cat-v1");
                            assertThat(body.ticker()).isEqualTo("FOO");
                            assertThat(body.tickers()).containsExactly("FOO", "BAR");
                        });
    }

    @Test
    void lifecycleNotPermittedMapsTo422WithTickerAndTickers() {
        ResponseEntity<ContractError> response =
                handler.handleLifecycleNotPermitted(
                        new LifecycleNotPermittedException(List.of("TATAMOTORS.NS"), "cat-v1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .isNotNull()
                .satisfies(
                        body -> {
                            assertThat(body.error())
                                    .isEqualTo(ContractErrorCode.lifecycle_not_permitted);
                            assertThat(body.ticker()).isEqualTo("TATAMOTORS.NS");
                            assertThat(body.tickers()).containsExactly("TATAMOTORS.NS");
                            assertThat(body.catalogVersion()).isEqualTo("cat-v1");
                        });
    }

    @Test
    void singularSpecAUnsupportedAssetBodyPreservedByteForByte() {
        UnsupportedAssetException ex = new UnsupportedAssetException("FAKE", "c3dcb95e4e09212a");

        ResponseEntity<Map<String, Object>> response = handler.handleUnsupportedAsset(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .containsOnlyKeys("error", "ticker", "catalogVersion")
                .containsEntry("error", "unsupported_asset")
                .containsEntry("ticker", "FAKE")
                .containsEntry("catalogVersion", "c3dcb95e4e09212a");
    }

    @Test
    void uniquenessRaceConflictReReadsCurrentVersionByUserId() {
        String userId = "user-race";
        Portfolio winner = new Portfolio(userId);
        ReflectionTestUtils.setField(winner, "version", 3L);
        when(portfolioRepository.findByUserId(userId)).thenReturn(List.of(winner));

        ResponseEntity<ContractError> response =
                handler.handlePortfolioVersionConflict(
                        PortfolioVersionConflictException.unresolvedForUser(userId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().currentVersion()).isEqualTo(3L);
    }

    private void assertStatefulSeamsUntouched() {
        assertThat(replaceInvoked.get()).isFalse();
        verify(replacementService, never())
                .replace(anyString(), anyLong(), anyList(), any(TuplePreparer.class));
        verify(portfolioRepository, never()).findByUserId(anyString());
        verify(catalogValidator, never()).validate(anyList(), anyList());
        verify(preparer, never()).materialise(anyList(), anyList());
    }

    /**
     * Test-only probe that would invoke the application operation if envelope decoding succeeds.
     * Envelope failures must never reach {@link HoldingReplacementService#replace}.
     */
    @RestController
    @RequestMapping("/__test__/composition-boundary")
    static class StatefulBoundaryProbeController {
        private final HoldingReplacementService replacementService;
        private final AtomicBoolean replaceInvoked;

        StatefulBoundaryProbeController(
                HoldingReplacementService replacementService, AtomicBoolean replaceInvoked) {
            this.replacementService = replacementService;
            this.replaceInvoked = replaceInvoked;
        }

        @PutMapping
        ResponseEntity<Void> probe(@Valid @RequestBody CompositionHoldingsRequest request) {
            replaceInvoked.set(true);
            List<RawIntent> intent =
                    request.holdings().stream()
                            .map(h -> new RawIntent(h.ticker(), h.quantity()))
                            .toList();
            replacementService.replace(
                    "probe-user",
                    request.expectedVersion(),
                    intent,
                    (i, locked) -> List.of());
            return ResponseEntity.ok().build();
        }
    }
}
