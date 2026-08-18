package com.wealth.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class CatalogVersion {

    private CatalogVersion() {}

    static String compute(List<ManifestEntry> entries) {
        List<ManifestEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(ManifestEntry::ticker));
        StringBuilder payload = new StringBuilder();
        for (ManifestEntry entry : sorted) {
            if (!payload.isEmpty()) {
                payload.append('\n');
            }
            payload.append(serializeEntry(entry));
        }
        return sha256Hex16(payload.toString());
    }

    private static String serializeEntry(ManifestEntry entry) {
        List<String> aliases = new ArrayList<>(entry.aliases() == null ? List.of() : entry.aliases());
        aliases.sort(String::compareTo);
        return String.join(
                "|",
                entry.ticker(),
                entry.name(),
                String.join(",", aliases),
                entry.assetClass(),
                entry.quoteCurrency(),
                entry.lifecycleStatus().name(),
                entry.basePrice().stripTrailingZeros().toPlainString());
    }

    private static String sha256Hex16(String payload) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
