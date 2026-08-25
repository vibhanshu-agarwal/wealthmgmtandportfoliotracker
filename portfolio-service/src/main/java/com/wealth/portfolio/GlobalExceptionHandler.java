package com.wealth.portfolio;

import com.wealth.catalog.UnsupportedAssetException;
import com.wealth.portfolio.composition.ContractError;
import com.wealth.portfolio.composition.ContractErrorCode;
import com.wealth.portfolio.composition.ContractTokenException;
import com.wealth.portfolio.composition.DuplicateTickerException;
import com.wealth.portfolio.composition.LifecycleNotPermittedException;
import com.wealth.portfolio.composition.PortfolioVersionConflictException;
import com.wealth.portfolio.composition.QuantityOutOfDomainException;
import com.wealth.portfolio.composition.UnsupportedAssetsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final PortfolioRepository portfolioRepository;

    public GlobalExceptionHandler(PortfolioRepository portfolioRepository) {
        this.portfolioRepository = portfolioRepository;
    }

    /**
     * Handles missing required request headers (e.g. X-User-Id not present).
     * This indicates the request bypassed the API Gateway — return 400 with a clear message.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(
            MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(
                Map.of("error", "Required header '" + ex.getHeaderName() + "' is missing"));
    }

    /**
     * Handles requests for a user that does not exist in the users table.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUserNotFound(
            UserNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Handles FX rate lookup failures.
     * Returns 503 with a retryable flag so clients know the failure is transient.
     */
    @ExceptionHandler(FxRateUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleFxRateUnavailable(
            FxRateUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "FX rate unavailable: %s → %s".formatted(
                        ex.getFromCurrency(), ex.getToCurrency()),
                "retryable", true
        ));
    }

    @ExceptionHandler(UnsupportedAssetException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedAsset(UnsupportedAssetException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "unsupported_asset",
                "ticker", ex.ticker(),
                "catalogVersion", ex.catalogVersion()));
    }

    @ExceptionHandler(PortfolioVersionConflictException.class)
    public ResponseEntity<ContractError> handlePortfolioVersionConflict(
            PortfolioVersionConflictException ex) {
        long currentVersion = resolveCurrentVersion(ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ContractError.versionConflict(ex.getMessage(), currentVersion));
    }

    @ExceptionHandler(QuantityOutOfDomainException.class)
    public ResponseEntity<ContractError> handleQuantityOutOfDomain(QuantityOutOfDomainException ex) {
        return ResponseEntity.badRequest().body(
                ContractError.withTickers(ex.code(), ex.getMessage(), ex.tickers()));
    }

    @ExceptionHandler(DuplicateTickerException.class)
    public ResponseEntity<ContractError> handleDuplicateTicker(DuplicateTickerException ex) {
        return ResponseEntity.badRequest().body(
                ContractError.withTickers(ex.code(), ex.getMessage(), ex.tickers()));
    }

    @ExceptionHandler(UnsupportedAssetsException.class)
    public ResponseEntity<ContractError> handleUnsupportedAssets(UnsupportedAssetsException ex) {
        return ResponseEntity.unprocessableEntity().body(
                ContractError.catalogRejection(
                        ex.code(),
                        ex.getMessage(),
                        ex.catalogVersion(),
                        ex.firstTicker(),
                        ex.tickers()));
    }

    @ExceptionHandler(LifecycleNotPermittedException.class)
    public ResponseEntity<ContractError> handleLifecycleNotPermitted(LifecycleNotPermittedException ex) {
        return ResponseEntity.unprocessableEntity().body(
                ContractError.catalogRejection(
                        ex.code(),
                        ex.getMessage(),
                        ex.catalogVersion(),
                        ex.firstTicker(),
                        ex.tickers()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ContractError> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        ContractTokenException token = findContractToken(ex);
        if (token != null) {
            return ResponseEntity.badRequest().body(ContractError.of(token.code(), token.getMessage()));
        }
        return ResponseEntity.badRequest().body(
                ContractError.of(ContractErrorCode.malformed_request, "Malformed request body"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ContractError> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        boolean missingVersion = ex.getBindingResult().getFieldErrors().stream()
                .anyMatch(error -> "expectedVersion".equals(error.getField()));
        if (missingVersion) {
            return ResponseEntity.badRequest().body(
                    ContractError.of(ContractErrorCode.missing_version, "expectedVersion is required"));
        }
        return ResponseEntity.badRequest().body(
                ContractError.of(ContractErrorCode.malformed_request, "Request validation failed"));
    }

    /**
     * D5: known version is returned as-is; unresolved uniqueness-race / failed-CAS paths re-read
     * the committed row after rollback using the lookup identity carried on the exception.
     */
    private long resolveCurrentVersion(PortfolioVersionConflictException ex) {
        if (ex.currentVersion().isPresent()) {
            return ex.currentVersion().getAsLong();
        }
        if (ex.lookupUserId().isPresent()) {
            List<Portfolio> found = portfolioRepository.findByUserId(ex.lookupUserId().get());
            if (!found.isEmpty()) {
                return found.getFirst().getVersion();
            }
            return 0L;
        }
        if (ex.lookupPortfolioId().isPresent()) {
            return portfolioRepository
                    .findById(ex.lookupPortfolioId().get())
                    .map(Portfolio::getVersion)
                    .orElse(0L);
        }
        throw new IllegalStateException(
                "portfolio_version_conflict without currentVersion or lookup identity");
    }

    private static ContractTokenException findContractToken(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ContractTokenException token) {
                return token;
            }
            current = current.getCause();
        }
        return null;
    }
}
