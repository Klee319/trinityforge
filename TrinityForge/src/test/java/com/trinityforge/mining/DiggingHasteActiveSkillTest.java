package com.trinityforge.mining;

import com.trinityforge.active.ActivationResult;
import com.trinityforge.active.ActiveContext;
import com.trinityforge.config.domains.DiggingGimmickConfig;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiggingHasteActiveSkill}: シャベル専用の独立ハステアクティブ(2026-08-18 W-59)。
 * {@link com.trinityforge.mining.HasteActiveSkillTest} と対称の検証(id/gate/target-skill metadata、
 * tier解決CT、HASTEポーション付与)に加え、{@link HasteActiveSkill}とのCTグループ共有を検証する。
 */
class DiggingHasteActiveSkillTest {

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void metadataMatchesFeatureVocabularyAndTargetSkill() {
        DiggingHasteActiveSkill skill = new DiggingHasteActiveSkill(new DiggingGimmickConfig());
        assertEquals("haste-active-digging", skill.id());
        assertEquals("haste-active-digging", skill.gateEffectId());
        assertEquals(Set.of("DIGGING"), skill.targetSkills());
        // 2026-08-18 第2波: CTバケツの共有は撤回し、id 単位の独立CT + 持ち替えで効果強制終了へ移した。
        assertEquals(skill.id(), skill.cooldownGroup());
        assertTrue(skill.toolBound(), "持ち替えで効果を終了する設定が外れている");
    }

    @Test
    void cooldownMillisIsTierInvariantAndUsesTheGlobalScalarOnly() throws Exception {
        // mining側(HasteActiveSkillTest)と対称: tier行にamplifier/duration-ticksしか無くても、
        // cooldownMillis(tier)はどのtierでも同じ、常にグローバルscalarを返す。
        DiggingGimmickConfig gimmickConfig = loadedDiggingGimmickConfig("""
                haste-active-digging:
                  cooldown-ticks: 900
                  tiers:
                    1: { amplifier: 1, duration-ticks: 100 }
                    3: { amplifier: 2, duration-ticks: 160 }
                """);
        DiggingHasteActiveSkill skill = new DiggingHasteActiveSkill(gimmickConfig);
        assertEquals(900L * 50L, skill.cooldownMillis(1));
        assertEquals(900L * 50L, skill.cooldownMillis(3));
        assertEquals(900L * 50L, skill.cooldownMillis(99), "an unresolved tier also falls back to the same global scalar");
    }

    @Test
    void activateGrantsHasteAtTierResolvedAmplifierAndDuration() throws Exception {
        DiggingGimmickConfig gimmickConfig = loadedDiggingGimmickConfig("""
                haste-active-digging:
                  tiers:
                    1: { amplifier: 1, duration-ticks: 100 }
                """);
        DiggingHasteActiveSkill skill = new DiggingHasteActiveSkill(gimmickConfig);

        ActivationResult result = skill.activate(player, new ActiveContext(1, new ItemStack(Material.DIAMOND_SHOVEL)));

        assertTrue(result.success());
        assertTrue(player.hasPotionEffect(PotionEffectType.HASTE));
        var effect = player.getPotionEffect(PotionEffectType.HASTE);
        assertEquals(1, effect.getAmplifier());
        assertEquals(100, effect.getDuration());
    }

    private static DiggingGimmickConfig loadedDiggingGimmickConfig(String yaml) throws Exception {
        java.io.File tempDir = java.nio.file.Files.createTempDirectory("digging-haste-active-skill-test").toFile();
        java.io.File file = new java.io.File(tempDir, DiggingGimmickConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> tempDir;
            case "getLogger" -> java.util.logging.Logger.getLogger("DiggingHasteActiveSkillTest");
            case "saveResource" -> throw new AssertionError("saveResource() must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        org.bukkit.plugin.Plugin fakePlugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(), new Class<?>[] {org.bukkit.plugin.Plugin.class}, handler);
        DiggingGimmickConfig config = new DiggingGimmickConfig();
        config.load(fakePlugin);
        return config;
    }
}
