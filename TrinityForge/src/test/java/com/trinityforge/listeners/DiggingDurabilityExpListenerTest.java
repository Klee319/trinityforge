package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.DiggingGimmickConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DiggingDurabilityExpListener}: シャベル(use-skill==DIGGING)の耐久消費のみ累積し、他アイテムは
 * 無視すること。バニラEXP(C-1)/職業EXP(C-2)のボーナスがそれぞれ独立の上限%でクランプされること。
 */
class DiggingDurabilityExpListenerTest {

    private static final String EFFECT_VANILLA_EXP = "digging-durability-vanilla-exp";
    private static final String EFFECT_JOB_EXP = "digging-durability-job-exp";

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private DiggingGimmickConfig gimmickConfig;
    private DiggingDurabilityExpListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(DiggingGimmickConfig.class);
        when(gimmickConfig.durabilityPerPercent()).thenReturn(100.0);
        // 2026-07-26 tier-expand: bonusFraction() は tier 版(内部でC-1/C-2のcap値をtierとして流用)を
        // 呼ぶようになったため、既存の no-arg スタブに加えて全tierで同値(100.0)を返すよう固定する
        // (durability-exp.tiers 未定義時の後方互換動作そのものを検証する意図)。
        when(gimmickConfig.durabilityPerPercent(org.mockito.ArgumentMatchers.anyInt())).thenReturn(100.0);
        listener = new DiggingDurabilityExpListener(dedicatedEffects, gimmickConfig);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack itemWithUseSkill(Material material, String useSkill) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setUseRequirement(useSkill, 0);
        stack.setItemMeta(meta);
        return stack;
    }

    private long accumulated() {
        return player.getPersistentDataContainer()
                .getOrDefault(PdcKeys.PLAYER_DIGGING_DURABILITY_ACCUM, PersistentDataType.LONG, 0L);
    }

    @Test
    void accumulatesDamageForShovelUseSkill() {
        ItemStack shovel = itemWithUseSkill(Material.DIAMOND_SHOVEL, "DIGGING");
        listener.onItemDamage(new PlayerItemDamageEvent(player, shovel, 5));
        listener.onItemDamage(new PlayerItemDamageEvent(player, shovel, 3));

        assertEquals(8L, accumulated());
    }

    @Test
    void ignoresNonDiggingUseSkillItems() {
        ItemStack pickaxe = itemWithUseSkill(Material.DIAMOND_PICKAXE, "MINING");
        listener.onItemDamage(new PlayerItemDamageEvent(player, pickaxe, 5));

        assertEquals(0L, accumulated());
    }

    @Test
    void ignoresItemsWithNoUseSkillStamped() {
        ItemStack plainShovel = new ItemStack(Material.DIAMOND_SHOVEL);
        listener.onItemDamage(new PlayerItemDamageEvent(player, plainShovel, 5));

        assertEquals(0L, accumulated());
    }

    @Test
    void vanillaExpBonusClampsAtNodeCapAndIsZeroWithoutNode() {
        player.getPersistentDataContainer().set(
                PdcKeys.PLAYER_DIGGING_DURABILITY_ACCUM, PersistentDataType.LONG, 10_000L);
        // Node not held: no bonus even with huge accumulation.
        when(dedicatedEffects.valueMax(eq(player), eq(EFFECT_VANILLA_EXP))).thenReturn(OptionalDouble.empty());
        assertEquals(0.0, listener.vanillaExpBonusFraction(player), 1e-9);

        // Node held with cap 50%: 10000/100=100% raw, clamped to 50%.
        when(dedicatedEffects.valueMax(eq(player), eq(EFFECT_VANILLA_EXP))).thenReturn(OptionalDouble.of(50.0));
        assertEquals(0.5, listener.vanillaExpBonusFraction(player), 1e-9);
    }

    @Test
    void jobExpBonusHasIndependentCapFromVanilla() {
        player.getPersistentDataContainer().set(
                PdcKeys.PLAYER_DIGGING_DURABILITY_ACCUM, PersistentDataType.LONG, 10_000L);
        when(dedicatedEffects.valueMax(eq(player), eq(EFFECT_JOB_EXP))).thenReturn(OptionalDouble.of(25.0));
        assertEquals(0.25, listener.jobExpBonusFraction(player), 1e-9);
    }

    @Test
    void onVanillaExpGainAppliesBonusMultiplicatively() {
        player.getPersistentDataContainer().set(
                PdcKeys.PLAYER_DIGGING_DURABILITY_ACCUM, PersistentDataType.LONG, 5_000L);
        when(dedicatedEffects.valueMax(eq(player), eq(EFFECT_VANILLA_EXP))).thenReturn(OptionalDouble.of(50.0));

        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 10);
        listener.onVanillaExpGain(event);

        assertEquals(15, event.getAmount(), "10 * (1 + 0.5) = 15");
    }

    @Test
    void onVanillaExpGainNoOpWhenNoAccumulation() {
        when(dedicatedEffects.valueMax(any(), eq(EFFECT_VANILLA_EXP))).thenReturn(OptionalDouble.of(50.0));
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, 10);
        listener.onVanillaExpGain(event);

        assertEquals(10, event.getAmount(), "no accumulated durability -> no bonus");
    }
}
