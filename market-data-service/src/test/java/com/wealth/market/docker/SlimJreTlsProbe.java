package com.wealth.market.docker;

/** Minimal HTTPS client used to verify outbound TLS from the slim JRE inside Docker. */
public final class SlimJreTlsProbe {

    private SlimJreTlsProbe() {}

    public static void main(String[] args) throws Exception {
        var connection = (javax.net.ssl.HttpsURLConnection) new java.net.URL("https://example.com").openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 500) {
            throw new IllegalStateException("Unexpected HTTPS status: " + status);
        }
        System.out.println("TLS_OK");
    }
}
