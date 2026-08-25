package com.wealth.portfolio.composition;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Stable composition-contract error envelope. The machine-code field is {@code error}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContractError(
        ContractErrorCode error,
        String message,
        Long currentVersion,
        String ticker,
        List<String> tickers,
        String catalogVersion
) {

    public static ContractError of(ContractErrorCode error, String message) {
        return new ContractError(error, message, null, null, null, null);
    }

    public static ContractError versionConflict(String message, long currentVersion) {
        return new ContractError(
                ContractErrorCode.portfolio_version_conflict, message, currentVersion, null, null, null);
    }

    public static ContractError withTickers(
            ContractErrorCode error, String message, List<String> tickers) {
        return new ContractError(error, message, null, null, List.copyOf(tickers), null);
    }

    public static ContractError catalogRejection(
            ContractErrorCode error,
            String message,
            String catalogVersion,
            String ticker,
            List<String> tickers) {
        return new ContractError(
                error, message, null, ticker, List.copyOf(tickers), catalogVersion);
    }
}
