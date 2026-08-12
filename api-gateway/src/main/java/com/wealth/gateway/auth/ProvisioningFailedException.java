package com.wealth.gateway.auth;

/** The Provisioning_Transaction failed and rolled back for a reason other than duplicate/validation. */
public class ProvisioningFailedException extends RuntimeException {
    public ProvisioningFailedException(Throwable cause) {
        super(cause);
    }
}
