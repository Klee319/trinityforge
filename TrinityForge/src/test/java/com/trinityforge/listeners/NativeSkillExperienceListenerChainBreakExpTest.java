package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
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
 * W-143(2026-08-19) 実サーバ報告「一括伐採発動時に1個分しか経験値が入らない」。
 *
 * <p>連鎖破壊分の EXP 経路は<b>2種類</b>あり、報告が指していたのは<b>後者</b>だった(ユーザー確認済み):
 * <ul>
 *   <li><b>採取スキルEXP</b>({@code 伐採} スキルの経験値) — 元から連鎖1ブロックごとに
 *       {@link NativeSkillExperienceListener#grantChainBreak} が付与していた。ただし<b>それを縛る
 *       テストが1本も無かった</b>({@code TreeFellingListenerTest} は「{@code ChainBreakExpGrant} が
 *       N回呼ばれる」までしか見ていない)ので、金額が入ることをここで固定する。</li>
 *   <li><b>破壊時バニラEXP</b>({@code break-vanilla-exp}: 経験値オーブ) — 2026-07-28 以来
 *       <b>意図的に起点1回分だけ</b>だった(「連鎖分まで配ると一括破壊がそのままバニラEXP増殖装置に
 *       なる」)。W-143 で<b>連鎖分にも配る</b>へ変更し、増殖の懸念は
 *       {@code break-vanilla-exp.chain-max-blocks}(1バースト = 同tick・同プレイヤーあたりの
 *       上限ブロック数、既定 64。0 で旧挙動)で押さえる。</li>
 * </ul>
 *
 * <p>{@code MockBukkit} を使わないのは {@link NativeSkillExperienceListenerCropMaturityTest} と
 * 同じ理由({@code Block#getDrops} 等が未実装で SKIPPED に化ける)。プラグイン未起動なので
 * {@code getCurrentTick()} は読めず、リスナー側は「全部同じ1バースト」として扱う —
 * 上限が効くことをそのまま観測できる。
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

    /** W-143 の本題: 連鎖分にもバニラEXPオーブが出る(旧仕様は起点1回分だけだった)。 */
    @Test
    @DisplayName("連鎖伐採した原木にもバニラEXPオーブが出る(W-143 の仕様変更)")
    void chainBreakNowGrantsVanillaExpOrbs() {
        Fixture f = fixture(skillExp(1.0, 64));
        Block log = logBlock();
        List<ItemStack> drops = List.of(stack(Material.OAK_LOG));

        for (int i = 0; i < 3; i++) {
            f.listener().grantChainBreak(f.player(), log, drops, f.tool());
        }

        verify(f.player(), times(3)).giveExp(1);
    }

    /**
     * 増殖対策の上限。旧仕様が連鎖分を切っていた理由(「一括破壊がそのままバニラEXP増殖装置になる」)は
     * この上限で押さえる。上限を超えたぶんはオーブが出ない。
     */
    @Test
    @DisplayName("連鎖分のバニラEXPは chain-max-blocks で打ち切る(1バースト＝斧1振りぶん)")
    void chainVanillaExpStopsAtTheConfiguredCap() {
        Fixture f = fixture(skillExp(1.0, 2));
        Block log = logBlock();
        List<ItemStack> drops = List.of(stack(Material.OAK_LOG));

        for (int i = 0; i < 10; i++) {
            f.listener().grantChainBreak(f.player(), log, drops, f.tool());
        }

        verify(f.player(), times(2)).giveExp(1);
        // 採取スキルEXPには上限を掛けない(今回の変更対象ではない)。
        verify(f.dispatcher(), times(10))
                .grant(f.player().getUniqueId(), SkillId.WOODCUTTING, 40.0);
    }

    @Test
    @DisplayName("chain-max-blocks: 0 なら連鎖分にオーブを配らない(旧挙動へ戻せる)")
    void chainVanillaExpCanBeTurnedOff() {
        Fixture f = fixture(skillExp(1.0, 0));
        Block log = logBlock();
        List<ItemStack> drops = List.of(stack(Material.OAK_LOG));

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

    /**
     * ベース量と連鎖上限だけを持つ {@code stats/skill-exp.yml} スタブ。
     * ベースは端数持ち越しを考えなくて済む 1.0 を使う(0.25 のままだと「4回で1オーブ」になり、
     * 上限が効いたのかベースの端数なのかを切り分けられない)。
     */
    private static SkillExpConfig skillExp(double baseExp, int chainMaxBlocks) {
        SkillExpConfig config = mock(SkillExpConfig.class);
        when(config.breakVanillaBaseExp(org.mockito.ArgumentMatchers.anyString())).thenReturn(baseExp);
        when(config.breakVanillaChainMaxBlocks()).thenReturn(chainMaxBlocks);
        return config;
    }

    private static Fixture fixture() {
        // skillExp 未配線なら既定値(base-exp 0.25 / chain-max-blocks 64)にフォールバックする。
        return fixture(null);
    }

    private static Fixture fixture(SkillExpConfig skillExp) {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.WOODCUTTING)).thenReturn(WOODCUTTING);
        PlacedBlockTracker placedBlockTracker = mock(PlacedBlockTracker.class);
        when(placedBlockTracker.clearIfPlaced(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        ItemStack axe = stack(Material.IRON_AXE);
        Player player = player(axe);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        // 破壊時バニラEXPは「解放済み」にしておく — オーブが出ない/出る理由を
        // 「パーク未解放」ではなく連鎖上限の側だけに絞るため。
        when(dedicatedEffects.isActive(eq(player), eq("break-vanilla-exp-woodcutting"),
                eq(SkillId.WOODCUTTING))).thenReturn(true);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        NativeSkillExperienceListener listener = new NativeSkillExperienceListener(
                fakePlugin(), dispatcher, catalog, placedBlockTracker, null, dedicatedEffects, aggregator,
                null, skillExp);
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
