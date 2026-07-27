package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.stats.AttributeProjection;
import org.bukkit.plugin.Plugin;

/**
 * Stat-to-vanilla-Attribute mapping table (COMBAT_SYSTEM_SPEC section 5 / ADDON_INTEGRATION_SPEC
 * section 1.2). This used to be loaded from {@code stats/attribute-map.yml} at runtime; the mapping
 * has since been decided as fixed production combat behaviour rather than an operator-tunable knob,
 * so the table is now hardcoded in {@link AttributeProjection#defaults()} (values unchanged from the
 * former file's defaults) and this class is kept only as a thin, still-{@link LoadableConfig}
 * pass-through so {@link com.trinityforge.config.ConfigManager} and {@code ItemAssembler}'s
 * constructor/wiring do not need to change shape.
 *
 * <p>{@link #load(Plugin)} is now a no-op (no file to read, nothing can fail) and always reports
 * success; {@code /trinityforge reload} still calls it harmlessly through the existing reload list.
 */
public final class AttributeMappingConfig implements LoadableConfig {

    private final AttributeProjection projection = AttributeProjection.defaults();

    public AttributeProjection projection() {
        return projection;
    }

    /** No-op: the mapping is hardcoded now, so there is nothing to (re)load from disk. */
    @Override
    public boolean load(Plugin plugin) {
        return true;
    }
}
