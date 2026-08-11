package com.wealth.gateway.auth;

/** The Credential_Store could not be reached (503) — never reveals whether the email exists. */
public class CredentialStoreUnavailableException extends RuntimeException {
    public CredentialStoreUnavailableException(Throwable cause) {
        super(cause);
    }
}
