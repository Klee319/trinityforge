package com.trinityforge.mining;

import com.trinityforge.active.ActivationResult;
import com.trinityforge.active.ActiveContext;
import com.trinityforge.config.domains.MiningGimmickConfig;
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
 * {@link HasteActiveSkill}: the framework's first {@link com.trinityforge.active.ActiveSkill} — id/gate/
 * target-skill metadata, tier-resolved cooldown, and the HASTE potion effect it grants on
 * {@link HasteActiveSkill#activate}.
 */
class HasteActiveSkillTest {

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
        HasteActiveSkill skill = new HasteActiveSkill(new MiningGimmickConfig());
        assertEquals("haste-active-mining", skill.id());
        assertEquals("haste-active-mining", skill.gateEffectId());
        // 2026-08-18 (W-59): digging.yml には元々 feature:haste-active-mining の配置が一度も無く、
        // "DIGGING" 対応は実質シャベルから発動不能な死んだ宣言だった。専用の DiggingHasteActiveSkill
        // (targetSkills=DIGGING、独立gate/config)へ切り出したため、こちらは MINING 単独へ戻す。
        assertEquals(Set.of("MINING"), skill.targetSkills());
    }

    @Test
    void keepsIndependentCooldownBucketsAndEndsOnToolSwitch() {
        // 2026-08-18 第2波(ユーザー確定要件「共有ではなく持ち替えで効果が強制終了する」):
        // 初版の cooldownGroup 共有は撤回した。共有CTでは「ツルハシで発動 → シャベルへ持ち替え」で
        // 効果だけを横流しできてしまい、シャベル側のノードを解放していないのに掘削が速くなる。
        HasteActiveSkill mining = new HasteActiveSkill(new MiningGimmickConfig());
        DiggingHasteActiveSkill digging = new DiggingHasteActiveSkill(new com.trinityforge.config.domains.DiggingGimmickConfig());
        assertTrue(!mining.cooldownGroup().equals(digging.cooldownGroup()),
                "CTバケツを共有に戻してはいけない(持ち替え強制終了が本線)");
        assertEquals(mining.id(), mining.cooldownGroup(), "CTバケツは id 単位");
        assertEquals(digging.id(), digging.cooldownGroup(), "CTバケツは id 単位");
        // 効果はツール束縛。false に戻ると持ち替えても効果が残る(=ずるが復活する)。
        assertTrue(mining.toolBound());
        assertTrue(digging.toolBound());
        assertTrue(!mining.id().equals(digging.id()));
    }

    @Test
    void cooldownMillisIsTierInvariantAndUsesTheGlobalScalarOnly() throws Exception {
        // 2026-07-25 CT設計一本化 §1: 段階(tier)はCTに一切影響させない。tier行にamplifier/duration-ticks
        // しか無くても(cooldown-ticksは撤去済み)、cooldownMillis(tier)はどのtierでも同じ、常に
        // haste-active-mining.cooldown-ticks(グローバルscalar、ここでは700)を返す。
        MiningGimmickConfig gimmickConfig = loadedMiningGimmickConfig("""
                haste-active-mining:
                  cooldown-ticks: 700
                  tiers:
                    1: { amplifier: 3, duration-ticks: 160 }
                    3: { amplifier: 4, duration-ticks: 200 }
                """);
        HasteActiveSkill skill = new HasteActiveSkill(gimmickConfig);
        assertEquals(700L * 50L, skill.cooldownMillis(1));
        assertEquals(700L * 50L, skill.cooldownMillis(3));
        assertEquals(700L * 50L, skill.cooldownMillis(99), "an unresolved tier also falls back to the same global scalar");
    }

    @Test
    void activateGrantsHasteAtTierResolvedAmplifierAndDuration() throws Exception {
        MiningGimmickConfig gimmickConfig = loadedMiningGimmickConfig("""
                haste-active-mining:
                  tiers:
                    1: { amplifier: 3, duration-ticks: 160 }
                """);
        HasteActiveSkill skill = new HasteActiveSkill(gimmickConfig);

        ActivationResult result = skill.activate(player, new ActiveContext(1, new ItemStack(Material.DIAMOND_PICKAXE)));

        assertTrue(result.success());
        assertTrue(player.hasPotionEffect(PotionEffectType.HASTE));
        var effect = player.getPotionEffect(PotionEffectType.HASTE);
        assertEquals(3, effect.getAmplifier());
        assertEquals(160, effect.getDuration());
    }

    private static MiningGimmickConfig loadedMiningGimmickConfig(String yaml) throws Exception {
        java.io.File tempDir = java.nio.file.Files.createTempDirectory("haste-active-skill-test").toFile();
        java.io.File file = new java.io.File(tempDir, MiningGimmickConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> tempDir;
            case "getLogger" -> java.util.logging.Logger.getLogger("HasteActiveSkillTest");
            case "saveResource" -> throw new AssertionError("saveResource() must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        org.bukkit.plugin.Plugin fakePlugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(), new Class<?>[] {org.bukkit.plugin.Plugin.class}, handler);
        MiningGimmickConfig config = new MiningGimmickConfig();
        config.load(fakePlugin);
        return config;
    }
}
