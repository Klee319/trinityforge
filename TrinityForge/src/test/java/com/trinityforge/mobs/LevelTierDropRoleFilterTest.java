package com.trinityforge.mobs;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code add-drops} の {@code roles:} フィルタ（2026-08-02 柱7）。
 *
 * <p>{@code mobs:} / {@code mob-ids:} と同じ「未指定なら全部に適用」の後方互換規約を守ることが要点。
 * ここが逆になると、既存の全 add-drops が「職業を選んでいない人には落ちない」に化ける。
 */
class LevelTierDropRoleFilterTest {

    private static LevelTierDropEntry drop(Set<String> roles) {
        return LevelTierDropEntry.ofMaterial(Material.BONE, 1.0, 1, 1, MobTargetFilter.EMPTY, roles);
    }

    @Test
    @DisplayName("roles: 未指定は職業を問わない(未選択のプレイヤーにも落ちる)")
    void emptyRolesMatchesEveryone() {
        LevelTierDropEntry entry = drop(Set.of());
        assertTrue(entry.allowsRoles(Set.of()));
        assertTrue(entry.allowsRoles(null));
        assertTrue(entry.allowsRoles(Set.of("miner")));
    }

    @Test
    @DisplayName("roles: 指定ありは、いずれかの枠が一致したときだけ通す")
    void rolesNarrowToTheListedRoles() {
        LevelTierDropEntry entry = drop(Set.of("miner", "digger"));
        assertTrue(entry.allowsRoles(Set.of("miner")));
        assertTrue(entry.allowsRoles(Set.of("tank", "digger")), "補助職の枠で一致しても通す");
        assertFalse(entry.allowsRoles(Set.of("tank")));
        assertFalse(entry.allowsRoles(Set.of()), "職業未選択なら落ちない");
        assertFalse(entry.allowsRoles(null));
    }

    @Test
    @DisplayName("職業IDの大文字小文字・前後空白は無視する(yml の書き方で黙って外れない)")
    void roleMatchingIsCaseInsensitive() {
        LevelTierDropEntry entry = drop(Set.of("miner"));
        assertTrue(entry.allowsRoles(Set.of("MINER")));
        assertTrue(entry.allowsRoles(Set.of("  Miner ")));
    }

    @Test
    @DisplayName("roles を渡さない既存の生成経路は空集合になる(後方互換)")
    void legacyFactoriesProduceNoRoleFilter() {
        assertTrue(LevelTierDropEntry.ofMaterial(Material.BONE, 1.0, 1, 1, MobTargetFilter.EMPTY)
                .roles().isEmpty());
        assertTrue(LevelTierDropEntry.ofCatalog("x", 1.0, 1, 1, MobTargetFilter.EMPTY)
                .roles().isEmpty());
    }
}
