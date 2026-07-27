package com.trinityforge.config;

import org.bukkit.plugin.Plugin;

/**
 * A config unit that can be (re)loaded from disk. Implemented by both flat-schema domains
 * ({@link ConfigDomain}) and open-ended table loaders (stat-roll, combat-level), so
 * {@link ConfigManager} can drive every config through one uniform list instead of a
 * special-cased path per loader type.
 */
@FunctionalInterface
public interface LoadableConfig {

    /** Loads (or reloads) this config. Returns {@code true} when it loaded without issues. */
    boolean load(Plugin plugin);
}
