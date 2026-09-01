package com.wealth.gateway;

public final class InternalApiKeyPresenceProbe {

    private InternalApiKeyPresenceProbe() {}

    static String classify(String value) {
        return value == null || value.isBlank() ? "blank" : "nonblank";
    }

    public static void main(String[] args) {
        System.out.print(classify(System.getenv("INTERNAL_API_KEY")) + "\n");
    }
}
