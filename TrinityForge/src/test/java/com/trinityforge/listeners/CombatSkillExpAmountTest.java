package com.trinityforge.listeners;

import com.trinityforge.config.domains.SkillExpConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-07-26 EXP調整タスク2: {@link CombatListener#combatSkillExpAmount} の純粋ロジック検証。
 * 「既定config では現行挙動(固定exp-per-hit)と一致する」ことと、damage_scaledモード有効時の
 * 与ダメージ比例・モブレベル係数の計算を確認する。fakePlugin パターンは
 * {@code SkillExpConfigTest} と同じもの(同一クラスの重複回避のため独自コピーを持つ — テストのみの
 * 小さな重複であり、プロダクションコードの共有には当たらない)。
 */
class CombatSkillExpAmountTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CombatSkillExpAmountTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static SkillExpConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, SkillExpConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        SkillExpConfig config = new SkillExpConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    /** 既定config(mode:flat)では、ダメージ/モブレベルに関わらず常にexp-per-hit固定値。 */
    @Test
    void defaultFlatModeMatchesLegacyFixedAmountRegardlessOfDamageOrLevel(@TempDir File tempDir)
            throws IOException {
        SkillExpConfig config = loaded(tempDir, "combat:\n  exp-per-hit: 1.0\n");

        assertEquals(1.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 3.0, 0));
        assertEquals(1.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 500.0, 999));
        assertEquals(1.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 0.0, 0));
    }

    @Test
    void defaultFlatModeHonorsPerSkillOverride(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  exp-per-hit: 1.0
                  by-skill:
                    HEAVY_WEAPONS: 3.5
                """);
        assertEquals(3.5, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 100.0, 50));
    }

    @Test
    void damageScaledModeIsProportionalToDamage(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  mode: damage_scaled
                  damage-scale: 0.5
                  mob-level-scale: 0.0
                """);
        // damage-scale=0.5, mob-level-scale=0 → exp = damage * 0.5
        assertEquals(5.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 10.0, 0));
        assertEquals(25.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 50.0, 0));
    }

    @Test
    void damageScaledModeAppliesMobLevelMultiplier(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  mode: damage_scaled
                  damage-scale: 1.0
                  mob-level-scale: 0.02
                """);
        // damage=10, level=50 → base=10, levelMultiplier = 1 + 50*0.02 = 2.0 → 20.0
        assertEquals(20.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 10.0, 50), 1e-9);
        // level=0 → levelMultiplier = 1.0 → unchanged base
        assertEquals(10.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 10.0, 0), 1e-9);
    }

    @Test
    void damageScaledModeNeverGoesNegativeOnZeroOrNegativeDamage(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  mode: damage_scaled
                  damage-scale: 0.5
                  mob-level-scale: 0.02
                """);
        assertEquals(0.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", -5.0, 10), 1e-9);
        assertEquals(0.0, CombatListener.combatSkillExpAmount(config, "HEAVY_WEAPONS", 0.0, 10), 1e-9);
    }
}
