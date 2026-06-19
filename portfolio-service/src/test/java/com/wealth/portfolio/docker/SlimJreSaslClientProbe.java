package com.wealth.portfolio.docker;

import java.util.Map;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;

/**
 * Minimal SASL PLAIN client probe used to verify {@code java.security.sasl} is reachable from a
 * limited module set (simulating the slim jlink JRE).
 */
public final class SlimJreSaslClientProbe {

    private SlimJreSaslClientProbe() {}

    public static void main(String[] args) throws Exception {
        CallbackHandler handler =
                callbacks -> {
                    for (Callback callback : callbacks) {
                        if (callback instanceof NameCallback nameCallback) {
                            nameCallback.setName("probe-user");
                        } else if (callback instanceof PasswordCallback passwordCallback) {
                            passwordCallback.setPassword("probe-pass".toCharArray());
                        } else {
                            throw new UnsupportedCallbackException(callback);
                        }
                    }
                };

        SaslClient client =
                Sasl.createSaslClient(
                        new String[] {"PLAIN"},
                        "probe-user",
                        "kafka",
                        "broker",
                        Map.of(),
                        handler);

        if (client == null) {
            throw new IllegalStateException("Sasl.createSaslClient returned null (java.security.sasl missing?)");
        }

        System.out.println("SASL_CLIENT_OK");
    }
}
