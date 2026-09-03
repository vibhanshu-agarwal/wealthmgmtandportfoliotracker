package com.wealth.portfolio.composition;

import com.wealth.portfolio.GlobalExceptionHandler;
import com.wealth.portfolio.PortfolioRepository;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves envelope decoding via a test-only probe endpoint. Production
 * {@code PUT /api/portfolio/holdings} remains Wave 7 and unreachable.
 */
class CompositionEnvelopeBoundaryTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BoundaryProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(mock(PortfolioRepository.class)))
                .build();
    }

    @Test
    void malformedJsonMapsToMalformedRequest() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));
    }

    @Test
    void floatExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":7.9,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
    }

    @Test
    void stringExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":\"7\",\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
    }

    @Test
    void booleanExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":true,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
    }

    @Test
    void negativeExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":-1,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
    }

    @Test
    void integerBeyondLongRangeMapsToInvalidVersion() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":9223372036854775808,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
    }

    @Test
    void explicitNullExpectedVersionMapsToInvalidVersion() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":null,\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_version"));
    }

    @Test
    void missingExpectedVersionMapsToMissingVersion() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_version"));
    }

    @Test
    void quantityAsJsonNumberMapsToQuantityNotString() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"holdings":[{"ticker":"AAPL","quantity":1.5}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("quantity_not_string"));
    }

    @Test
    void missingQuantityIsAcceptedAtEnvelopeBoundary() throws Exception {
        // Required quantity is QuantityDomain's job after the version check — not @NotNull.
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"holdings":[{"ticker":"AAPL"}]}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void nullHoldingElementMapsToMalformedRequest() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"holdings\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));
    }

    @Test
    void mixedNullHoldingElementMapsToMalformedRequest() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"holdings":[{"ticker":"AAPL","quantity":"1.00000000"},null]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));
    }

    @Test
    void validEnvelopeDecodes() throws Exception {
        mockMvc.perform(put("/__test__/composition-boundary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"holdings":[{"ticker":"AAPL","quantity":"1.50000000"}]}
                                """))
                .andExpect(status().isOk());
    }

    @RestController
    @RequestMapping("/__test__/composition-boundary")
    static class BoundaryProbeController {
        @PutMapping
        ResponseEntity<Void> probe(@Valid @RequestBody CompositionHoldingsRequest request) {
            return ResponseEntity.ok().build();
        }
    }
}
