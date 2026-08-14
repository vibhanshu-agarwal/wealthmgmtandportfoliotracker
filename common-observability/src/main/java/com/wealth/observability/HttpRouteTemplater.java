package com.wealth.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;

import java.util.Set;
import java.util.regex.Pattern;

final class HttpRouteTemplater {

    static final Set<String> PATH_KEYS = Set.of("http.route", "url.path", "http.target");

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Pattern NUMERIC = Pattern.compile("^[0-9]+$");

    private HttpRouteTemplater() {
    }

    static boolean isPathKey(String key) {
        return key != null && PATH_KEYS.contains(key);
    }

    static boolean needsTemplating(Observation.Context context) {
        return hasIdentifierPath(context.getLowCardinalityKeyValues())
                || hasIdentifierPath(context.getHighCardinalityKeyValues());
    }

    private static boolean hasIdentifierPath(Iterable<KeyValue> keyValues) {
        for (KeyValue kv : keyValues) {
            if (isPathKey(kv.getKey()) && containsIdentifierSegment(kv.getValue())) {
                return true;
            }
        }
        return false;
    }

    static boolean containsIdentifierSegment(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return !path.equals(template(path));
    }

    static String template(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String withoutQuery = path;
        int queryAt = path.indexOf('?');
        if (queryAt >= 0) {
            withoutQuery = path.substring(0, queryAt);
        }
        String[] segments = withoutQuery.split("/", -1);
        boolean changed = queryAt >= 0;
        for (int i = 0; i < segments.length; i++) {
            if (isIdentifier(segments[i])) {
                segments[i] = "{id}";
                changed = true;
            }
        }
        return changed ? String.join("/", segments) : path;
    }

    private static boolean isIdentifier(String segment) {
        return UUID.matcher(segment).matches() || NUMERIC.matcher(segment).matches();
    }
}
