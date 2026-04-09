package com.wealth.market;

import java.util.List;

/**
 * Root deserialization target for the JSON fixture file.
 *
 * <p>Wrapping the array in a root object keeps the schema extensible — metadata fields
 * (e.g. {@code version}, {@code description}) can be added at the root level in future
 * without breaking the asset list.
 */
public record MarketSeedFixture(
        List<SeedAsset> assets
) {}
