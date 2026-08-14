package com.wealth.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;

/**
 * Records HTTP route values as normalized templates rather than concrete paths
 * carrying identifiers. Does not replace Spring HTTP conventions unless the
 * context already carries identifier-bearing {@code http.route}, {@code url.path},
 * or {@code http.target} values.
 */
public final class HttpRouteTemplatingObservationConvention
        implements GlobalObservationConvention<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context != null && HttpRouteTemplater.needsTemplating(context);
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(Observation.Context context) {
        return templatePathKeys(context.getLowCardinalityKeyValues());
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(Observation.Context context) {
        return templatePathKeys(context.getHighCardinalityKeyValues());
    }

    private static KeyValues templatePathKeys(KeyValues source) {
        KeyValues result = KeyValues.empty();
        for (KeyValue kv : source) {
            if (HttpRouteTemplater.isPathKey(kv.getKey())) {
                result = result.and(KeyValue.of(kv.getKey(), HttpRouteTemplater.template(kv.getValue())));
            }
        }
        return result;
    }
}
