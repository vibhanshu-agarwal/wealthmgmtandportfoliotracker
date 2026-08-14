package com.wealth.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

/**
 * Rewrites observation {@link KeyValues} before stop: drops deny-set keys, strips
 * query strings from retained URI-shaped values, and templates identifier-bearing
 * HTTP path keys.
 */
public final class RedactingObservationFilter implements ObservationFilter {

    @Override
    public Observation.Context map(Observation.Context context) {
        rewrite(context, true);
        rewrite(context, false);
        return context;
    }

    private static void rewrite(Observation.Context context, boolean lowCardinality) {
        KeyValues current = lowCardinality
                ? context.getLowCardinalityKeyValues()
                : context.getHighCardinalityKeyValues();
        for (KeyValue kv : current.stream().toList()) {
            if (AttributeDenySet.isDenied(kv.getKey())) {
                remove(context, kv.getKey(), lowCardinality);
                continue;
            }
            String rewritten = stripQueryIfUri(kv.getValue());
            if (HttpRouteTemplater.isPathKey(kv.getKey())) {
                rewritten = HttpRouteTemplater.template(rewritten);
            }
            if (!rewritten.equals(kv.getValue())) {
                remove(context, kv.getKey(), lowCardinality);
                add(context, KeyValue.of(kv.getKey(), rewritten), lowCardinality);
            }
        }
    }

    private static String stripQueryIfUri(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int queryAt = value.indexOf('?');
        if (queryAt <= 0) {
            return value;
        }
        String beforeQuery = value.substring(0, queryAt);
        if (beforeQuery.contains("://") || beforeQuery.startsWith("/")) {
            return beforeQuery;
        }
        return value;
    }

    private static void remove(Observation.Context context, String key, boolean lowCardinality) {
        if (lowCardinality) {
            context.removeLowCardinalityKeyValue(key);
        } else {
            context.removeHighCardinalityKeyValue(key);
        }
    }

    private static void add(Observation.Context context, KeyValue keyValue, boolean lowCardinality) {
        if (lowCardinality) {
            context.addLowCardinalityKeyValue(keyValue);
        } else {
            context.addHighCardinalityKeyValue(keyValue);
        }
    }
}
