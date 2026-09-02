package com.wealth.portfolio.seed;

import com.wealth.portfolio.Portfolio;
import com.wealth.portfolio.PortfolioRepository;

import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.composition.PortfolioVersionConflictException;
import com.wealth.portfolio.seed.PortfolioSeedService.SeedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for the internal seed endpoint's request envelope and response body.
 *
 * <p>The endpoint is reachable in production and is invoked there on a schedule. Its response
 * previously carried {@code marketPricesUpserted}, a count of rows the seeder upserted into the
 * global {@code market_prices} table — a write that overwrote live refreshed prices for every
 * user. The field is asserted <em>absent</em> rather than zero: a caller that still reads it
 * should fail loudly rather than silently observe a plausible-looking {@code 0}.
 *
 * <p>Task 6.1 additionally requires the caller's observed {@code expectedVersion} on every
 * request. An envelope that omits it, or carries a non-integer, must be rejected before the
 * service is consulted — the seed must never invent a version or read one of its own.
 *
 * <p>The former {@code seedUnsupportedAssetReturns422Contract} case is gone with the pre-pass it
 * exercised: the seed no longer walks the catalog before the version boundary, so the service
 * cannot raise {@code UnsupportedAssetException} on this path. The 422 mapping itself is still
 * covered by the composition contract tests; what this class now pins in its place is the
 * Requirement 7 conflict envelope, which is the failure this endpoint can actually produce.
 *
 * <p>This is the fast counterpart to {@code PortfolioSeedServiceIT}, which proves against a real
 * database that neither price table is modified. Here we only pin the wire contract.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioSeedControllerTest {

    private static final String KEY = "test-internal-key";
    private static final String PATH = "/api/internal/portfolio/seed";
    /** The compiled-in target. No request input may select a different user. */
    private static final String E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e";

    @Mock private PortfolioSeedService seedService;
    @Mock private PortfolioRepository portfolioRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // A successful seed is the default so a rejection test that wrongly reaches the service
        // fails on the status/interaction assertion rather than on an incidental NPE from an
        // unstubbed mock. Lenient: the rejection cases must never consume this stub.
        lenient().when(seedService.seed(anyString(), anyLong()))
                .thenReturn(new SeedResult(UUID.randomUUID(), 160));

        mockMvc =
                MockMvcBuilders.standaloneSetup(new PortfolioSeedController(seedService))
                        .addFilter(new InternalApiKeyFilter(KEY))
                        .setControllerAdvice(new GlobalExceptionHandler(portfolioRepository))
                        .build();
    }

    // ---------------------------------------------------------------- envelope rejection

    @Test
    void missingVersionNeverReachesSeedService() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_version"));

        verifyNoInteractions(seedService);
    }

    @Test
    void legacyBodyUserIdWithoutVersionIsStillMissingVersion() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"00000000-0000-0000-0000-00000000beef\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_version"));

        verifyNoInteractions(seedService);
    }

    @Test
    void absentBodyIsMalformedRequest() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));

        verifyNoInteractions(seedService);
    }

    @Test
    void malformedJsonIsMalformedRequest() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));

        verifyNoInteractions(seedService);
    }

    @Test
    void topLevelNullBodyIsMalformedRequest() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));

        verifyNoInteractions(seedService);
    }

    /**
     * Each non-integer form is a separate case: no coercion path may quietly succeed. An
     * explicit JSON {@code null} is invalid rather than missing — the property was supplied.
     */
    @ParameterizedTest(name = "expectedVersion={0} is invalid_version")
    @ValueSource(strings = {"null", "7.9", "\"7\"", "true", "-1", "9223372036854775808"})
    void nonIntegerOrOutOfDomainVersionIsInvalidVersion(String rawValue) throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + rawValue + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));

        verifyNoInteractions(seedService);
    }

    // ---------------------------------------------------------------- forwarding and identity

    /**
     * Genuine integer zero is the Absent_Aggregate precondition, not a stand-in for "absent
     * input". It must arrive at the service as {@code 0}.
     */
    @ParameterizedTest(name = "expectedVersion {0} is forwarded to the fixed E2E target")
    @ValueSource(longs = {0L, 1L, 42L, Long.MAX_VALUE})
    void validVersionIsForwardedExactly(long expectedVersion) throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(E2E_USER_ID));

        verify(seedService, times(1)).seed(E2E_USER_ID, expectedVersion);
        verifyNoMoreInteractions(seedService);
    }

    @Test
    void spoofedIdentityCannotRedirectTheSeed() throws Exception {
        String attacker = "00000000-0000-0000-0000-00000000beef";

        mockMvc.perform(post(PATH + "?userId=" + attacker)
                        .header("X-Internal-Api-Key", KEY)
                        .header("X-User-Id", attacker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3,\"userId\":\"" + attacker + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(E2E_USER_ID));

        verify(seedService, times(1)).seed(E2E_USER_ID, 3L);
        verifyNoMoreInteractions(seedService);
    }

    // ---------------------------------------------------------------- conflict envelope

    @Test
    void knownConflictReturnsRequirement7Envelope() throws Exception {
        when(seedService.seed(anyString(), anyLong()))
                .thenThrow(new PortfolioVersionConflictException(11L));

        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("portfolio_version_conflict"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.currentVersion").value(11));

        verify(seedService, times(1)).seed(E2E_USER_ID, 4L);
        verifyNoMoreInteractions(seedService);
    }

    /**
     * Failed-CAS conflicts carry no version until the losing transaction has rolled back; the
     * handler re-reads once to report it. That re-read must not become a retry of the write.
     */
    @Test
    void postRollbackResolvedConflictReportsCommittedVersion() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        Portfolio committed = new Portfolio(E2E_USER_ID);
        ReflectionTestUtils.setField(committed, "version", 12L);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(committed));
        when(seedService.seed(anyString(), anyLong()))
                .thenThrow(PortfolioVersionConflictException.unresolvedForPortfolio(portfolioId));

        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("portfolio_version_conflict"))
                .andExpect(jsonPath("$.currentVersion").value(12));

        verify(seedService, times(1)).seed(E2E_USER_ID, 4L);
        verifyNoMoreInteractions(seedService);
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void missingApiKeyRejectedBeforeVersionDecoding() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_internal_api_key"));

        verifyNoInteractions(seedService);
    }

    @Test
    void wrongApiKeyRejectedBeforeVersionDecoding() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Internal-Api-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_internal_api_key"));

        verifyNoInteractions(seedService);
    }

    // ---------------------------------------------------------------- preserved response shape

    @Test
    void seedResponse_carriesHoldingsOnly_andNeverAMarketDataCount() {
        UUID portfolioId = UUID.randomUUID();
        when(seedService.seed(eq(E2E_USER_ID), eq(2L)))
                .thenReturn(new SeedResult(portfolioId, 160));

        ResponseEntity<Map<String, Object>> response =
                new PortfolioSeedController(seedService).seed(new PortfolioSeedRequest(2L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as("seed response must expose exactly these keys — no market-data count")
                .containsOnlyKeys("userId", "portfolioId", "holdingsInserted");

        assertThat(body)
                .as("marketPricesUpserted must be absent, not zero: portfolio-service must "
                        + "never write market data, so there is no count to report")
                .doesNotContainKey("marketPricesUpserted");

        assertThat(body.get("userId")).isEqualTo(E2E_USER_ID);
        assertThat(body.get("portfolioId")).isEqualTo(portfolioId.toString());
        assertThat(body.get("holdingsInserted")).isEqualTo(160);
    }
}
