package com.trinityforge.mobs;

import java.util.Locale;

/**
 * The single canonical spelling for an EliteMobsカスタムボスのモブid, shared by every place that keys a
 * mob profile: {@code combat/mob-profiles.yml} (written by {@code /trinityforge importmobs}),
 * {@code combat/mob-overrides.yml} (operator-authored overlay keys, 2026-07-26 M1 レビュー指摘), the
 * {@code MOB_PROFILE_ID} PDC stamp ({@code TrinityForgeSpawnListener#stamp}, fork side), and
 * {@code ConfigManager#resolveRuntimeProfile}'s lookup.
 *
 * <p>MagmaCore's {@code CustomConfigFields#getFilename()} always returns the id WITH its {@code .yml}
 * extension, so a bare id (e.g. hand-typed in {@code mob-overrides.yml}) and a filename-derived id
 * (e.g. {@code "boss.yml"}) would otherwise never match. This is the exact failure mode documented in
 * project memory as "getFilename()は必ず.yml付き" — an id mismatch here makes an override silently
 * unreachable (never an error, just a lookup miss), so every caller MUST route through this one method
 * rather than re-deriving its own normalization.
 *
 * <p>Any remaining {@code .} becomes {@code _}, matching {@code EliteMobsImporter#sanitizeId} (a dot is
 * a path separator in a YAML config key, so the importer cannot store an id containing one verbatim).
 */
public final class MobIdNormalizer {

    private MobIdNormalizer() {
    }

    /**
     * Strips a trailing {@code .yml}/{@code .yaml} (case-insensitive) and turns any remaining {@code .}
     * into {@code _}. {@code null} in, {@code null} out (callers that need to guard absence do so before
     * calling this, mirroring the previous {@code ConfigManager#normalizeMobId} contract).
     */
    public static String normalize(String mobId) {
        if (mobId == null) {
            return null;
        }
        String lower = mobId.toLowerCase(Locale.ROOT);
        String stripped = mobId;
        if (lower.endsWith(".yml")) {
            stripped = mobId.substring(0, mobId.length() - 4);
        } else if (lower.endsWith(".yaml")) {
            stripped = mobId.substring(0, mobId.length() - 5);
        }
        return stripped.replace('.', '_');
    }
}
