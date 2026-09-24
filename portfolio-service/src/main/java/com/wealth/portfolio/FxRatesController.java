package com.wealth.portfolio;

import com.wealth.portfolio.fx.FxProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.wealth.portfolio.PortfolioConstants.X_USER_ID_HEADER;

/**
 * Rehearsal defect #4 — rates into the base currency for the Asset Picker's display-only
 * estimates, from the same shared {@link FxRateProvider} the holdings valuation uses
 * (dashboard-data-accuracy Requirement 5).
 *
 * <pre>
 * GET /api/portfolio/fx-rates?currencies=INR,JPY
 * X-User-Id: &lt;uuid&gt;
 *
 * 200 OK  — {"baseCurrency": "USD", "rates": {"INR": 0.0104…, "JPY": null}}
 * 400     — X-User-Id missing, or more than {@value #MAX_CURRENCIES} currencies requested
 * </pre>
 *
 * <p>A rate is {@code null} when it cannot be resolved (provider has none, or the code is not a
 * well-formed ISO 4217 code); it is exactly 1 only when the currency is the base currency, and
 * is never substituted with 1 otherwise. The provider supplies no rate timestamp, so none is
 * reported. Read-only; no state changes.
 */
@RestController
@RequestMapping("/api/portfolio")
public class FxRatesController {

    static final int MAX_CURRENCIES = 64;
    private static final Pattern ISO_CODE = Pattern.compile("[A-Z]{3}");

    private final FxRateProvider fxRateProvider;
    private final FxProperties fxProperties;

    public FxRatesController(FxRateProvider fxRateProvider, FxProperties fxProperties) {
        this.fxRateProvider = fxRateProvider;
        this.fxProperties = fxProperties;
    }

    public record FxRatesDto(String baseCurrency, Map<String, BigDecimal> rates) {}

    @GetMapping("/fx-rates")
    public ResponseEntity<FxRatesDto> getRates(
            @RequestHeader(X_USER_ID_HEADER) String userId,
            @RequestParam(name = "currencies", defaultValue = "") String currencies) {
        List<String> requested = Arrays.stream(currencies.split(","))
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .distinct()
                .toList();
        if (requested.size() > MAX_CURRENCIES) {
            return ResponseEntity.badRequest().build();
        }

        String base = fxProperties.baseCurrency();
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (String currency : requested) {
            rates.put(currency, rateOrNull(currency, base));
        }
        return ResponseEntity.ok(new FxRatesDto(base, rates));
    }

    private BigDecimal rateOrNull(String currency, String base) {
        if (!ISO_CODE.matcher(currency).matches()) {
            return null;
        }
        if (currency.equals(base)) {
            return BigDecimal.ONE;
        }
        try {
            return fxRateProvider.getRate(currency, base);
        } catch (FxRateUnavailableException e) {
            return null;
        }
    }
}
