package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W-143(2026-08-19) 実サーバ報告「一括伐採発動時に1個分しか経験値が入らない」の切り分け。
 *
 * <p>連鎖破壊分の EXP 経路には<b>2種類</b>あり、片方だけが「起点1回分」に固定されている:
 * <ul>
 *   <li><b>採取スキルEXP</b>({@code 伐採} スキルの経験値) — 連鎖1ブロックごとに
 *       {@link NativeSkillExperienceListener#grantChainBreak} が付与する。ここを縛るテストが
 *       これまで1本も無かった({@code TreeFellingListenerTest} は「{@code ChainBreakExpGrant} が
 *       N回呼ばれる」までしか見ていない)ので、実際に金額が入っているかは未検証だった。</li>
 *   <li><b>破壊時バニラEXP</b>({@code break-vanilla-exp}: 経験値オーブ) — <b>意図的に起点1回分だけ</b>。
 *       連鎖分まで配ると一括破壊がそのままバニラEXP増殖装置になるため
 *       ({@link NativeSkillExperienceListener#grantChainBreak} の javadoc)。</li>
 * </ul>
 *
 * <p>このテストは上の2点を<b>別々に</b>固定する。将来「バニラEXPも連鎖分に配る」へ仕様変更する
 * ときは、下の {@code chainBreakDoesNotGrantVanillaExpOrbs} が落ちて設計判断の変更に気づける。
 *
 * <p>{@code MockBukkit} を使わないのは {@link NativeSkillExperienceListenerCropMaturityTest} と
 * 同じ理由({@code Block#getDrops} 等が未実装で SKIPPED に化ける)。
 */
class NativeSkillExperienceListenerChainBreakExpTest {

    private static final SkillCatalogEntry WOODCUTTING = new SkillCatalogEntry(
            SkillId.WOODCUTTING, 100, "1", level -> 1L,
            Map.of("woodcutting_break.OAK_LOG", 40.0),
            Map.of());

    @Test
    @DisplayName("連鎖伐採した原木は1本ごとに伐採スキルEXPが入る(起点1回分に丸まらない)")
    void chainBreakGrantsGatheringExpPerBlock() {
        Fixture f = fixture();
        Block log = logBlock();
        List<ItemStack> drops = List.of(stack(Material.OAK_LOG));

        for (int i = 0; i < 3; i++) {
            f.listener().grantChainBreak(f.player(), log, drops, f.tool());
        }

        verify(f.dispatcher(), times(3))
                .grant(f.player().getUniqueId(), SkillId.WOODCUTTING, 40.0);
    }

    /**
     * 連鎖分にバニラEXPオーブを配らないのは<b>意図した設計</b>(一括破壊がバニラEXP増殖装置になるため)。
     * 「一括伐採で経験値が1個分しか入らない」という報告がこちらを指している場合は仕様変更が必要で、
     * そのときはこのテストを意図的に書き換えることになる。
     */
    @Test
    @DisplayName("連鎖分にはバニラEXPオーブを配らない(起点1回分だけ = 意図した仕様)")
    void chainBreakDoesNotGrantVanillaExpOrbs() {
        Fixture f = fixture();
        Block log = logBlock();
        List<ItemStack> drops = List.of(stack(Material.OAK_LOG));

        // ベース 0.25 の端数持ち越しがあるので、起点1回分なら足りない回数ではなく
        // 「何回連鎖しても1オーブも出ない」ことを確かめる(20回 = 旧ベース1.0なら20EXP相当)。
        for (int i = 0; i < 20; i++) {
            f.listener().grantChainBreak(f.player(), log, drops, f.tool());
        }

        verify(f.player(), never()).giveExp(anyInt());
    }

    @Test
    @DisplayName("設置された丸太の連鎖分はEXP対象外(置く→壊すファーム対策は連鎖でも効く)")
    void chainBreakOfPlacedLogGrantsNothing() {
        Fixture f = fixture();
        Block log = logBlock();
        when(f.placedBlockTracker().clearIfPlaced(log)).thenReturn(true);

        f.listener().grantChainBreak(f.player(), log, List.of(stack(Material.OAK_LOG)), f.tool());

        verify(f.dispatcher(), never()).grant(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    // ============================================================
    // 組み立て
    // ============================================================

    private record Fixture(NativeSkillExperienceListener listener,
                           NativeExperienceDispatcher dispatcher,
                           Player player,
                           ItemStack tool,
                           PlacedBlockTracker placedBlockTracker) {
    }

    private static Fixture fixture() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.WOODCUTTING)).thenReturn(WOODCUTTING);
        PlacedBlockTracker placedBlockTracker = mock(PlacedBlockTracker.class);
        when(placedBlockTracker.clearIfPlaced(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        ItemStack axe = stack(Material.IRON_AXE);
        Player player = player(axe);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        // 破壊時バニラEXPは「解放済み」にしておく — それでも連鎖分では出ないことを見たいので、
        // 解放漏れで0になっているのではないことを明示する。
        when(dedicatedEffects.isActive(eq(player), eq("break-vanilla-exp-woodcutting"),
                eq(SkillId.WOODCUTTING))).thenReturn(true);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        NativeSkillExperienceListener listener = new NativeSkillExperienceListener(
                fakePlugin(), dispatcher, catalog, placedBlockTracker, null, dedicatedEffects, aggregator);
        return new Fixture(listener, dispatcher, player, axe, placedBlockTracker);
    }

    private static Player player(ItemStack mainHand) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        return player;
    }

    private static Block logBlock() {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.OAK_LOG);
        return block;
    }

    private static ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        return stack;
    }

    private static Plugin fakePlugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                NativeSkillExperienceListenerChainBreakExpTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TrinityForge";
                    case "namespace" -> "trinityforge";
                    case "toString" -> "FakePlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
