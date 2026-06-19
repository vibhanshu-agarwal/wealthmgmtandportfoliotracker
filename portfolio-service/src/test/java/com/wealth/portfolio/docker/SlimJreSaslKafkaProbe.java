package com.wealth.portfolio.docker;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;

/**
 * Verifies the slim JRE inside the container can create a SASL client and reach the broker port
 * on the shared Docker network ({@code kafka:9092}).
 */
public final class SlimJreSaslKafkaProbe {

    private SlimJreSaslKafkaProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: SlimJreSaslKafkaProbe <host:port>");
        }

        String[] hostPort = args[0].split(":");
        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        CallbackHandler handler =
                callbacks -> {
                    for (Callback callback : callbacks) {
                        if (callback instanceof NameCallback nameCallback) {
                            nameCallback.setName("admin");
                        } else if (callback instanceof PasswordCallback passwordCallback) {
                            passwordCallback.setPassword("admin".toCharArray());
                        } else {
                            throw new UnsupportedCallbackException(callback);
                        }
                    }
                };

        SaslClient client =
                Sasl.createSaslClient(
                        new String[] {"PLAIN"},
                        "admin",
                        "kafka",
                        "broker",
                        Map.of(),
                        handler);
        if (client == null) {
            throw new IllegalStateException("Sasl.createSaslClient returned null");
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10_000);
        }

        System.out.println("KAFKA_SASL_OK");
    }
}
