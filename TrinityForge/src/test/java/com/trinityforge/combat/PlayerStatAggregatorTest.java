package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.BaseStatsConfig;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.progression.PermanentBuffResolver;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.UseRequirementResolver;
import com.trinityforge.progression.UseRequirementService;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;
import com.trinityforge.skilltree.runtime.NativeAttributeBridge;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import com.trinityforge.stats.StatKeys;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全ステ合算 + オフハンド per-item 化の {@link PlayerStatAggregator} 単体挙動: 防具4部位 + メインハンドは
 * 常に {@code item} へ合算され、オフハンドは<b>オフハンドにあるアイテム自身</b>の item-stats
 * {@code offhand-stats-apply}(既定 false)に従って合算のON/OFFが切り替わる(旧グローバルトグル
 * {@code stat-unification.offhand-enabled} は廃止)。{@code mainhand} はメインハンド単体のマップのみを保持し、
 * 防具/オフハンドは絶対に混ざらないことを検証する(武器CTの誤ゲート防止)。
 */
class PlayerStatAggregatorTest {

    private static final String ATTACK_POWER = StatKeys.canonical("attack-power");
    private static final String FLAT_DEFENSE = StatKeys.canonical("flat-defense");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * item-stats.yml: 剣=attack-power、胸当て=flat-defense、盾(オフハンド)=attack-power を別々に付与。
     * 盾には {@code offhand-stats-apply} を引数で設定し、オフハンド合算の対象/対象外を切り替える。
     */
    private void writeItemStats(File dir, boolean shieldOffhandApplies) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 10.0 }
                  DIAMOND_CHESTPLATE:
                    fixed: { flat-defense: 3.0 }
                  SHIELD:
                    fixed: { attack-power: 7.0 }
                    offhand-stats-apply: %s
                """.formatted(shieldOffhandApplies));
    }

    private PlayerStatAggregator aggregator(File dir, boolean shieldOffhandApplies) throws IOException {
        writeItemStats(dir, shieldOffhandApplies);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        // damage.yml は既定でよい(オフハンド合算はもう damage.yml では制御しない)。
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        return new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
    }

    private Player equippedPlayer() {
        Player player = server.addPlayer();
        PlayerInventory inv = player.getInventory();
        inv.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        inv.setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        inv.setItemInOffHand(new ItemStack(Material.SHIELD));
        return player;
    }

    @Test
    void offhandFlagFalse_excludesOffhandStats(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, false);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        // 盾の offhand-stats-apply=false: 防具のflat-defense + メインハンドのattack-powerのみ、盾(=7)は含まない。
        assertEquals(3.0, agg.item().getOrDefault(FLAT_DEFENSE, 0.0), 1e-9);
        assertEquals(10.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "offhand-stats-apply=false: item()のattack-powerはメインハンド(剣=10)のみで盾(=7)を含まない");
    }

    @Test
    void offhandFlagTrue_includesOffhandStats(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, true);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(3.0, agg.item().getOrDefault(FLAT_DEFENSE, 0.0), 1e-9);
        assertEquals(17.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "offhand-stats-apply=true: item()のattack-powerはメインハンド(剣=10)+盾(=7)の合算になる");
    }

    @Test
    void equippedArmorStatTotalSeparatesHeavyAndLightArmor(@TempDir File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_CHESTPLATE:
                    fixed: { defense-rate: 0.12 }
                  CHAINMAIL_BOOTS:
                    fixed: { defense-rate: 0.03 }
                """);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> List.of());
        PlayerStatAggregator aggregator =
                new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        Player player = equippedPlayer();
        player.getInventory().setBoots(new ItemStack(Material.CHAINMAIL_BOOTS));

        double heavy = aggregator.equippedArmorStatTotal(
                player, "defense-rate",
                item -> item.getType() == Material.DIAMOND_CHESTPLATE);
        double light = aggregator.equippedArmorStatTotal(
                player, "defense-rate",
                item -> item.getType() == Material.CHAINMAIL_BOOTS);

        assertEquals(0.12, heavy, 1e-9,
                "heavy total must not include the chainmail piece or held/offhand items");
        assertEquals(0.03, light, 1e-9,
                "light total must not include the diamond piece or held/offhand items");
    }

    @Test
    void mainhandMap_excludesArmorAndOffhand(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, true);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        // mainhand()は剣単体のattack-powerのみ: 防具のflat-defenseも盾のattack-powerも混ざらない。
        assertEquals(10.0, agg.mainhand().getOrDefault(ATTACK_POWER, 0.0), 1e-9);
        assertFalse(agg.mainhand().containsKey(FLAT_DEFENSE),
                "mainhand()に防具由来のflat-defenseが混ざってはならない(武器CT誤ゲート防止)");
    }

    @Test
    void armorAndMainhand_alwaysSummedIntoItem(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, false);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertTrue(agg.item().getOrDefault(FLAT_DEFENSE, 0.0) > 0.0,
                "item()は防具由来のflat-defenseを常に含む(offhandの有無に関わらず)");
        assertTrue(agg.item().getOrDefault(ATTACK_POWER, 0.0) > 0.0,
                "item()はメインハンド由来のattack-powerを常に含む");
    }

    @Test
    void armorThatFailsUseRequirementDoesNotContributeWhileAwaitingRemoval(@TempDir File dir)
            throws IOException {
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> List.of());
        UseRequirementService gate = mock(UseRequirementService.class);
        when(gate.denialFor(any(Player.class), any(ItemStack.class)))
                .thenReturn(Optional.of(new UseRequirementResolver.Resolved("HEAVY_ARMOR", 50)));
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()), null, null,
                null, null, gate);
        Player player = server.addPlayer();
        player.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));

        PlayerCombatAggregate aggregate = aggregator.aggregate(player);

        assertEquals(0.0, aggregate.item().getOrDefault(FLAT_DEFENSE, 0.0), 1e-9,
                "要件未達防具は次tickの剥離前でも装備ステータスへ寄与してはならない");
        assertEquals(10.0, aggregate.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "防具ゲートはメインハンドの集計まで誤って除外してはならない");
    }

    /**
     * Change 1(P10 魔法アグリゲーション): {@link PlayerStatAggregator#aggregateExcludingMainhand} は
     * メインハンド(剣=attack-power 10)の寄与を含まず、防具(flat-defense)のみ含む。
     */
    @Test
    void aggregateExcludingMainhand_excludesMainhandButIncludesArmor(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, false);
        Player player = equippedPlayer();

        Map<String, Double> stats = aggregator.aggregateExcludingMainhand(player);

        assertEquals(3.0, stats.getOrDefault(FLAT_DEFENSE, 0.0), 1e-9,
                "防具のflat-defenseは含む");
        assertEquals(0.0, stats.getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "メインハンド(剣=attack-power 10)の寄与を含んではならない");
    }

    /**
     * offhand-stats-apply=true の盾(オフハンド, attack-power=7)は
     * {@link PlayerStatAggregator#aggregateExcludingMainhand} にも合算される
     * (メインハンドのみが除外対象で、オフハンドは通常どおり合算されることの確認)。
     */
    @Test
    void heldArmor_doesNotContributeMainhandStats(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, false);
        Player player = server.addPlayer();
        // 装着なし・メインハンドに胸当てのみ → 防具ステは手持ちでは加算されない
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_CHESTPLATE));

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.0, agg.item().getOrDefault(FLAT_DEFENSE, 0.0), 1e-9,
                "手持ち防具の flat-defense は item() に入らない");
        assertTrue(agg.mainhand().isEmpty(), "手持ち防具は mainhand() マップも空");
    }

    /**
     * オフハンド二重計上防止(スロット除外): mainhandContributor がオフハンドのアイテム自身
     * (例: オフハンド釣竿)である場合、{@code contributorIsOffhand=true} でオフハンドスロットの
     * 合算を丸ごと除外し、寄与が一度だけになる。参照比較ではなくスロット単位である点が重要
     * (ライブサーバーの getItemInOffHand はスロット読み取りごとに新しいミラーを返すため)。
     */
    @Test
    void contributorIsOffhand_excludesOffhandSlotToPreventDoubleCount(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, true);
        Player player = server.addPlayer();
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        // ライブサーバー相当: contributor はスロットの同一インスタンスとは限らない(クローンを渡す)。
        ItemStack contributorMirror = player.getInventory().getItemInOffHand().clone();

        PlayerCombatAggregate agg = aggregator.aggregate(player, contributorMirror, true);

        assertEquals(7.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "オフハンド由来のcontributorはメインハンド寄与として一度だけ数え、オフハンド合算(offhand-stats-apply=true)と二重計上しない");
    }

    @Test
    void wornArmor_stillContributesWhenAlsoHoldingArmor(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, false);
        Player player = server.addPlayer();
        player.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_CHESTPLATE));

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(3.0, agg.item().getOrDefault(FLAT_DEFENSE, 0.0), 1e-9,
                "装着中の防具のみ寄与し、手持ち分は二重加算されない");
    }

    // --- NativeAttributeBridge wiring (SKILL_TREE MEDIUM audit finding: light armor set dodge-chance) ---

    private static final String DODGE_CHANCE = StatKeys.canonical("dodge-chance");

    /** Same fixture as {@link #aggregator}, plus a {@link NativeAttributeBridge} reporting a fixed dodge value. */
    private PlayerStatAggregator aggregatorWithNativeBridge(File dir, double nativeDodge) throws IOException {
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        NativeAttributeBridge bridge = mock(NativeAttributeBridge.class);
        when(bridge.armorAttributesFor(any())).thenReturn(Map.of("dodge_chance", nativeDodge));
        return new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()), bridge);
    }

    @Test
    void nativeAttributeBridgeDodgeChance_isAddedToPerkDefense(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregatorWithNativeBridge(dir, 0.15);
        Player player = server.addPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.15, agg.perkDefense().getOrDefault(DODGE_CHANCE, 0.0), 1e-9,
                "NativeAttributeBridge由来のdodge-chance(set-buffsのDEFENSEチャネル)はperkDefenseへ合算される");
    }

    @Test
    void nativeAttributeBridgeZeroDodgeChance_addsNoKey(@TempDir File dir) throws IOException {
        // NativeAttributeBridge#armorAttributesFor's own add() never emits a zero-valued key, which must
        // not fabricate a dodge_chance entry here.
        PlayerStatAggregator aggregator = aggregatorWithNativeBridge(dir, 0.0);
        Player player = server.addPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertFalse(agg.perkDefense().containsKey(DODGE_CHANCE),
                "bridgeがdodge_chanceを非報告のときはキー自体を追加しない");
    }

    /**
     * 2026-07-27(armor-set-buffs全面移行 §6汎化): set-buffsは任意のステキーを宣言できるため、
     * NativeAttributeBridgeの出力はDEFENSE以外(ATTACK/GENERAL)のチャネルも取りうる。
     * {@code nativeArmorSetContribution} がStatVocabulary.channelOfで判定し、それぞれの合流先
     * (attack / item(GENERAL) / perkDefense)へ正しく振り分けることを検証する。
     */
    @Test
    void nativeAttributeBridgeNonDefenseChannels_routeToAttackAndItemRespectively(@TempDir File dir)
            throws IOException {
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        NativeAttributeBridge bridge = mock(NativeAttributeBridge.class);
        when(bridge.armorAttributesFor(any())).thenReturn(Map.of(
                "crit_chance", 0.05,          // ATTACK channel
                "mining_fortune", 0.2,        // GENERAL channel
                "phys_flat_defense", 0.3));   // DEFENSE channel
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()), bridge);
        Player player = server.addPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.05, agg.perkAttack().getOrDefault(StatKeys.canonical("crit-chance"), 0.0), 1e-9,
                "ATTACKチャネルのset-buffsはattack()へ合流するべき");
        assertEquals(0.2, agg.item().getOrDefault(StatKeys.canonical("mining-fortune"), 0.0), 1e-9,
                "GENERALチャネルのset-buffsはitem()へ合流するべき");
        assertEquals(0.3, agg.perkDefense().getOrDefault(PHYS_FLAT_DEFENSE, 0.0), 1e-9,
                "DEFENSEチャネルのset-buffsはperkDefense()へ合流するべき");
    }

    // --- PermanentBuffResolver wiring (アチーブメント/図鑑報酬の永続ステータスバフ, Java-only拡張) ---

    @Test
    void permanentBuffResolver_isMergedIntoItemOnBothAggregatePaths(@TempDir File dir) throws IOException {
        // aggregator(...) 経由では PermanentBuffResolver=null のため、専用に6引数コンストラクタで組み直す。
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        when(permanentBuffResolver.buffsFor(any())).thenReturn(Map.of(ATTACK_POWER, 4.0));
        PlayerStatAggregator aggregator = new PlayerStatAggregator(cm.itemStats(), damage, perks,
                new RoleBuffResolver(cm.roleBuffs()), null, permanentBuffResolver);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);
        assertEquals(14.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "permanentBuffResolverの値(4)がメインハンド(剣=10)へ加算合成される");

        Map<String, Double> excludingMainhand = aggregator.aggregateExcludingMainhandWith(player, null);
        assertEquals(4.0, excludingMainhand.getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "aggregateExcludingMainhandWithでもpermanentBuffResolverの値が合算される");
    }

    /**
     * 2026-08-15 防具値の廃止で消えた迂回路の非回帰。かつて {@code armor-defense-rate} は
     * item map 側で {@link DefenseStatBridge} が常に0固定にしていた(バニラ防具属性ミラー専用の経路)ため、
     * 永続バフのこのキーだけを {@code perkDefense} へ逃がす特別な振り分けがあった。
     * 今は {@code defense-rate} を item map から直接読むので、他のステと同じく素直に
     * {@link PlayerCombatAggregate#item()} へ入って防御側へ届く。
     */
    @Test
    void permanentBuffResolver_defenseRateFlowsThroughTheItemMapLikeEveryOtherStat(@TempDir File dir)
            throws IOException {
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        String defenseRateKey = StatKeys.canonical("defense-rate");
        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        when(permanentBuffResolver.buffsFor(any())).thenReturn(Map.of(defenseRateKey, 0.1));
        PlayerStatAggregator aggregator = new PlayerStatAggregator(cm.itemStats(), damage, perks,
                new RoleBuffResolver(cm.roleBuffs()), null, permanentBuffResolver);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.1, agg.item().getOrDefault(defenseRateKey, 0.0), 1e-9,
                "permanent-buff の defense-rate は item() へ合流し、DefenseStatBridge がそこから読む");
        // 実際に防御側へ届くことまで見る(item map に入っているだけでは「届いた」証明にならない)。
        assertEquals(0.1,
                DefenseStatBridge.bridge(agg.item(), DefenseStatKeys.DEFAULT, DamageType.PHYSICAL).defenseRate(),
                1e-9, "ブリッジ通過後も防御率として残る");
    }

    @Test
    void nullPermanentBuffResolver_behavesExactlyAsBeforeThisWiring(@TempDir File dir) throws IOException {
        // The pre-existing 5-arg constructor (permanentBuffResolver=null) must be unaffected.
        PlayerStatAggregator aggregator = aggregatorWithNativeBridge(dir, 0.0);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(10.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9);
    }

    @Test
    void nullNativeAttributeBridge_behavesExactlyAsBeforeThisWiring(@TempDir File dir) throws IOException {
        // The pre-existing 4-arg constructor (bridge=null) must be unaffected: no dodge_chance fabricated.
        PlayerStatAggregator aggregator = aggregator(dir, false);
        Player player = server.addPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertFalse(agg.perkDefense().containsKey(DODGE_CHANCE));
    }

    @Test
    void nativeAttributeBridgeDodgeChance_sumsWithPerkBuffsDodgeChanceNoDoubleCount(@TempDir File dir)
            throws IOException {
        // A player with an unlocked LIGHT_ARMOR node granting an unconditional buffs:dodge-chance (0.1,
        // via PerkBuffResolver) PLUS the native armor-set dodge bonus (0.15, via NativeAttributeBridge) must
        // see both summed exactly once each — the two systems read disjoint YAML sections (buffs: vs
        // native:) for the same conceptual stat, so this is addition, not double-counting the same source.
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");

        Map<String, Double> buffs = new LinkedHashMap<>();
        buffs.put("dodge-chance", 0.1);
        SkillNode node = new SkillNode("C", "回避の型", 50, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                buffs, Map.of(), List.of(), List.of(), List.of());
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("C", node);
        SkillTree tree = new SkillTree("LIGHT_ARMOR", "軽装備", null, "2,10", null, nodes);
        String perkId = PerkNaming.perkId("LIGHT_ARMOR", "C");
        SkillPerkStatSource source = playerId -> java.util.Set.of(perkId);
        PerkBuffResolver perks = new PerkBuffResolver(source, () -> List.of(tree));

        NativeAttributeBridge bridge = mock(NativeAttributeBridge.class);
        when(bridge.armorAttributesFor(any())).thenReturn(Map.of("dodge_chance", 0.15));

        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()), bridge);
        Player player = server.addPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.25, agg.perkDefense().getOrDefault(DODGE_CHANCE, 0.0), 1e-9,
                "buffs:dodge-chance(0.1) + native setdodge(0.15) = 0.25、どちらも一度だけ計上");
    }

    // --- BaseStatsConfig wiring (全プレイヤー一律の基礎ステ, combat/base-stats.yml) ---

    private static final String CRIT_CHANCE = StatKeys.canonical("crit-chance");
    private static final String PHYS_FLAT_DEFENSE = StatKeys.canonical("phys-flat-defense");
    private static final String DEFENSE_RATE = StatKeys.canonical("defense-rate");

    private BaseStatsConfig baseStatsFrom(File dir, String yaml) throws IOException {
        File file = new File(dir, BaseStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        BaseStatsConfig cfg = new BaseStatsConfig();
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dir;
            case "getLogger" -> java.util.logging.Logger.getLogger("PlayerStatAggregatorTest-baseStats");
            case "saveResource" -> throw new AssertionError("saveResource() must not be called (file exists)");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[] {org.bukkit.plugin.Plugin.class}, handler);
        cfg.load(plugin);
        return cfg;
    }

    private PlayerStatAggregator aggregatorWithBaseStats(File dir, String baseStatsYaml) throws IOException {
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        return new PlayerStatAggregator(cm.itemStats(), damage, perks,
                new RoleBuffResolver(cm.roleBuffs()), null, null, baseStatsFrom(dir, baseStatsYaml));
    }

    @Test
    void baseStats_areMergedIntoItemMap(@TempDir File dir) throws IOException {
        // crit-chance:5 は RATE正規化で 0.05、phys-flat-defense:2 はそのまま。両方 item() へ一律加算される。
        PlayerStatAggregator aggregator = aggregatorWithBaseStats(dir, """
                base-stats:
                  crit-chance: 5
                  phys-flat-defense: 2
                """);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.05, agg.item().getOrDefault(CRIT_CHANCE, 0.0), 1e-9,
                "base-statsのcrit-chance(5→0.05)が全プレイヤーへ一律加算される");
        assertEquals(2.0, agg.item().getOrDefault(PHYS_FLAT_DEFENSE, 0.0), 1e-9,
                "base-statsのphys-flat-defense(2)が item() へ加算される");
    }

    @Test
    void baseStats_defenseRateFlowsThroughTheItemMapLikeEveryOtherStat(@TempDir File dir) throws IOException {
        // permanent-buff と同様、2026-08-15 の防具値廃止で perkDefense への特別振り分けは不要になった。
        // 0.3 と書く(3 と書くと PercentStatNormalize が 0.03 へ矯正する率系キーなので、
        // 「矯正が効いていること」自体は別テストの担当)。
        PlayerStatAggregator aggregator = aggregatorWithBaseStats(dir, """
                base-stats:
                  defense-rate: 0.3
                """);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.3, agg.item().getOrDefault(DEFENSE_RATE, 0.0), 1e-9,
                "base-stats の defense-rate は item() へ合流する");
        assertEquals(0.3,
                DefenseStatBridge.bridge(agg.item(), DefenseStatKeys.DEFAULT, DamageType.PHYSICAL).defenseRate(),
                1e-9, "ブリッジ通過後も防御率として残る");
    }

    @Test
    void baseStats_areAlsoAppliedToMagicAggregationPath(@TempDir File dir) throws IOException {
        // F1: 魔法(触媒)詠唱の攻撃側集計 aggregateExcludingMainhandWith にも一律加算される
        // (permanent-buffs と対称。近接だけ効いて魔法に効かない非対称を防ぐ)。
        PlayerStatAggregator aggregator = aggregatorWithBaseStats(dir, """
                base-stats:
                  flat-bonus-damage: 3
                """);
        Player player = equippedPlayer();

        Map<String, Double> magic = aggregator.aggregateExcludingMainhandWith(player, null);

        assertEquals(3.0, magic.getOrDefault(StatKeys.canonical("flat-bonus-damage"), 0.0), 1e-9,
                "base-statsのflat-bonus-damageは魔法集計パスにも加算される");
    }

    @Test
    void nullBaseStats_behavesExactlyAsBeforeThisWiring(@TempDir File dir) throws IOException {
        // 既存の6引数コンストラクタ(baseStats=null)経路は不変: base由来のキーが一切現れない。
        PlayerStatAggregator aggregator = aggregator(dir, false);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertFalse(agg.item().containsKey(CRIT_CHANCE), "baseStats未配線ではcrit-chanceは付かない");
    }

    // --- 2026-07-26 マナ系ステ穴埋め: nonItemContribution 切り出しの非回帰 + nonItemStatTotal ---
    //
    // PlayerStatAggregator#computeAggregate のソース3〜6(パーク general / 役職 attack・defense /
    // 永続バフ / base-stats)は #nonItemContribution へ切り出された。ここでは
    // (a) 4ソース(パーク・役職・永続バフ・base-stats・装備)を全部同時に持つプレイヤーの item() 合算結果が
    //     切り出し前と一致すること(2026-08-15 に防具値を廃止して以降は全キーが素直に全ソース合算)、
    // (b) 新しい公開API nonItemStatTotal が装備由来・addon由来を含まないこと、を検証する。

    private static final String MANA_BONUS = StatKeys.canonical("mana_bonus");

    /** mana_bonus をパーク general(2.0)に持たせた {@link PerkBuffResolver} + 装備(剣に1.0)を仕込んだ
     *  アグリゲータを組み立てる。{@code roleContribution}/{@code permanentBuffs}/{@code baseStatsYaml}
     *  は呼び出し側が残り3ソースを注入するために渡す。 */
    private PlayerStatAggregator fullyWiredAggregator(File dir, RoleBuffResolver.Contribution roleContribution,
                                                       Map<String, Double> permanentBuffs, String baseStatsYaml)
            throws IOException {
        writeItemStatsWithManaBonus(dir);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");

        Map<String, Double> buffs = new LinkedHashMap<>();
        buffs.put("mana_bonus", 2.0);
        SkillNode node = new SkillNode("N", "マナの型", 1, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                buffs, Map.of(), List.of(), List.of(), List.of());
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("N", node);
        SkillTree tree = new SkillTree("ARS_MAGIC", "アルス魔術", null, "1,1", null, nodes);
        String perkId = PerkNaming.perkId("ARS_MAGIC", "N");
        SkillPerkStatSource source = playerId -> java.util.Set.of(perkId);
        PerkBuffResolver perks = new PerkBuffResolver(source, () -> List.of(tree));

        RoleBuffResolver roleBuffResolver = mock(RoleBuffResolver.class);
        when(roleBuffResolver.contributionFor(any())).thenReturn(roleContribution);

        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        when(permanentBuffResolver.buffsFor(any())).thenReturn(permanentBuffs);

        BaseStatsConfig baseStats = baseStatsFrom(dir, baseStatsYaml);

        return new PlayerStatAggregator(cm.itemStats(), damage, perks, roleBuffResolver, null,
                permanentBuffResolver, baseStats);
    }

    /** タスク2要件: 通常キー(mana_bonus)が「パーク・役職・永続バフ・base-stats・装備」5ソースから
     *  それぞれ1回ずつ合算されること(切り出し前と同じ加算合成)。 */
    @Test
    void manaBonus_summedOnceFromEachOfFiveSources(@TempDir File dir) throws IOException {
        RoleBuffResolver.Contribution role = new RoleBuffResolver.Contribution(
                Map.of(MANA_BONUS, 3.0), Map.of(), 1.0, null, 1.0);
        Map<String, Double> permanent = Map.of(MANA_BONUS, 5.0);
        PlayerStatAggregator aggregator = fullyWiredAggregator(dir, role, permanent, """
                base-stats:
                  mana_bonus: 7
                """);

        Player player = equippedPlayer();
        PlayerCombatAggregate agg = aggregator.aggregate(player);

        // 装備(剣, mana_bonus=1) + パーク(2) + 役職(3) + 永続バフ(5) + base-stats(7) = 18
        assertEquals(18.0, agg.item().getOrDefault(MANA_BONUS, 0.0), 1e-9,
                "mana_bonusが装備・パーク・役職・永続バフ・base-statsの5ソースから1回ずつ合算される");
    }

    private void writeItemStatsWithManaBonus(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 10.0, mana_bonus: 1.0 }
                  DIAMOND_CHESTPLATE:
                    fixed: { flat-defense: 3.0 }
                  SHIELD:
                    fixed: { attack-power: 7.0 }
                    offhand-stats-apply: false
                """);
    }

    /**
     * {@code defense-rate} をパークの{@code buffs:}に置いた場合の非回帰。
     * このキーは {@link com.trinityforge.stats.StatVocabulary#channelOf} により
     * 常に DEFENSE チャンネルへ自動分類されるため、{@link PerkBuffResolver} の {@code accumulate} が
     * そもそも {@code perkBuffs.general()} ではなく {@code perkBuffs.defense()} へ振り分ける。
     * よって {@code perkBuffs.defense()} は {@code computeAggregate} で直接 {@code perkDefense} 経路
     * (via {@code PlayerStatAggregator#nativeArmorSetContribution})に渡り、item() には現れない。
     * item() 側にも perkDefense 側にも同じ量が二重に乗らないことがこのテストの本題
     * ({@code PlayerDefenseResolver} は両方を combine するため)。
     */
    @Test
    void defenseRateFromPerkBuffs_routesToPerkDefenseViaVocabularyChannelNotItem(@TempDir File dir)
            throws IOException {
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        Map<String, Double> buffs = new LinkedHashMap<>();
        buffs.put("defense-rate", 0.2);
        SkillNode node = new SkillNode("A", "防御の型", 1, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                buffs, Map.of(), List.of(), List.of(), List.of());
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("A", node);
        SkillTree tree = new SkillTree("DEF", "防御", null, "1,1", null, nodes);
        String perkId = PerkNaming.perkId("DEF", "A");
        SkillPerkStatSource source = playerId -> java.util.Set.of(perkId);
        PerkBuffResolver perks = new PerkBuffResolver(source, () -> List.of(tree));
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.2, agg.perkDefense().getOrDefault(DEFENSE_RATE, 0.0), 1e-9,
                "StatVocabularyのDEFENSEチャンネル自動分類により、perkの defense-rate は"
                        + " perkBuffs.defense()経由でそのままperkDefenseへ入る");
        assertFalse(agg.item().containsKey(DEFENSE_RATE),
                "item()には現れない(perkBuffs.general()を経由しないため。両方に入ると防御率が二重計上される)");
    }

    /** {@code defense-rate} を役職バフに置いた場合は item() にそのまま入り perkDefense へは回らないこと
     *  (役職 buff にはこのキーの振り分けが元々無い)。 */
    @Test
    void defenseRateFromRoleBuff_staysInItemNotRedirected(@TempDir File dir) throws IOException {
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> List.of());
        RoleBuffResolver roleBuffResolver = mock(RoleBuffResolver.class);
        when(roleBuffResolver.contributionFor(any())).thenReturn(new RoleBuffResolver.Contribution(
                Map.of(), Map.of(DEFENSE_RATE, 0.3), 1.0, null, 1.0));
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, roleBuffResolver);
        Player player = equippedPlayer();

        PlayerCombatAggregate agg = aggregator.aggregate(player);

        assertEquals(0.3, agg.item().getOrDefault(DEFENSE_RATE, 0.0), 1e-9,
                "役職defenseBuffのdefense-rateは item()にそのまま入る");
        assertFalse(agg.perkDefense().containsKey(DEFENSE_RATE),
                "役職バフ由来では perkDefense へ加算しない(振り分けが元々無いため)");
    }

    // --- nonItemStatTotal(タスク1公開API): 装備由来・addon由来を含まないことの検証 ---

    @Test
    void nonItemStatTotal_excludesEquipmentContribution(@TempDir File dir) throws IOException {
        writeItemStatsWithManaBonus(dir);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        Map<String, Double> buffs = new LinkedHashMap<>();
        buffs.put("mana_bonus", 2.0);
        SkillNode node = new SkillNode("N", "マナの型", 1, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                buffs, Map.of(), List.of(), List.of(), List.of());
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("N", node);
        SkillTree tree = new SkillTree("ARS_MAGIC", "アルス魔術", null, "1,1", null, nodes);
        String perkId = PerkNaming.perkId("ARS_MAGIC", "N");
        SkillPerkStatSource source = playerId -> java.util.Set.of(perkId);
        PerkBuffResolver perks = new PerkBuffResolver(source, () -> List.of(tree));
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        Player player = equippedPlayer(); // 剣(mana_bonus=1)を装備

        // 参考: 装備込みの総合値には剣の mana_bonus=1 が含まれる(item()で確認)。
        PlayerCombatAggregate agg = aggregator.aggregate(player);
        assertEquals(3.0, agg.item().getOrDefault(MANA_BONUS, 0.0), 1e-9,
                "参考: item()は装備(1) + パーク(2) = 3を含む");

        double nonItem = aggregator.nonItemStatTotal(player, "mana_bonus");
        assertEquals(2.0, nonItem, 1e-9,
                "nonItemStatTotalは装備由来(剣=1)を含まずパーク分(2)のみを返す");
    }

    @Test
    void nonItemStatTotal_excludesAddonContribution(@TempDir File dir) throws IOException {
        writeItemStats(dir, false);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        Map<String, Double> buffs = new LinkedHashMap<>();
        buffs.put("mana_regen", 0.1);
        SkillNode node = new SkillNode("N", "マナ回復の型", 1, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                buffs, Map.of(), List.of(), List.of(), List.of());
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("N", node);
        SkillTree tree = new SkillTree("ARS_MAGIC", "アルス魔術", null, "1,1", null, nodes);
        String perkId = PerkNaming.perkId("ARS_MAGIC", "N");
        SkillPerkStatSource source = playerId -> java.util.Set.of(perkId);
        PerkBuffResolver perks = new PerkBuffResolver(source, () -> List.of(tree));
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        Player player = server.addPlayer();
        // addonチャネルへ mana_regen=99 を書き込む(フォーク→TFの書き込みを模擬)。
        player.getPersistentDataContainer().set(
                com.trinityforge.pdc.PdcKeys.PLAYER_ADDON_COMBAT_STATS,
                org.bukkit.persistence.PersistentDataType.STRING,
                AddonCombatStats.encode(Map.of(StatKeys.canonical("mana_regen"), 99.0)));

        // 参考: totalOf(通常の総合値)にはaddon分(99)が含まれる。
        PlayerCombatAggregate agg = aggregator.aggregate(player);
        assertEquals(99.1, agg.totalOf(StatKeys.canonical("mana_regen")), 1e-9,
                "参考: totalOfはaddon(99) + パーク(0.1)を含む");

        double nonItem = aggregator.nonItemStatTotal(player, "mana_regen");
        assertEquals(0.1, nonItem, 1e-9,
                "nonItemStatTotalはaddon由来(99)を含まずパーク分(0.1)のみを返す");
    }

    // --- 2026-08-13 修正1(revert後の新仕様): 「実際に使ったアイテム(寄与アイテム)はどちらの手にあっても
    // 常に合算される」。offhand-stats-apply の門は「オフハンドに持っているだけのアイテム」(スロット側)
    // にだけ掛かる。 ---

    /**
     * オフハンド対応(offhand-stats-apply)が無くても、寄与アイテム自身(=今まさに使ったアイテム)は常に
     * 合算される。item()には寄与アイテム(盾=7)だけが入る — contributorIsOffhand=true のとき
     * 「実際に使ったアイテムではない実メインハンド(剣)」は合算対象に含まれない(offhand-stats-apply
     * はオフハンドに「持っているだけ」のアイテム向けの門であり、寄与アイテムには掛からない)。
     */
    @Test
    void contributorIsOffhand_contributorIsAlwaysSummedEvenWithoutOffhandStatsApply(@TempDir File dir)
            throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, false); // SHIELD offhand-stats-apply=false
        Player player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        ItemStack contributorMirror = player.getInventory().getItemInOffHand().clone();

        PlayerCombatAggregate agg = aggregator.aggregate(player, contributorMirror, true);

        assertEquals(7.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "offhand-stats-apply=falseでも寄与アイテム(盾=7)は常に合算される"
                        + "(実際に使っていない実メインハンドの剣=10は含まれない)");
    }

    /**
     * 寄与アイテムがオフハンドにあり、かつ「今のオフハンドの中身」と同一プロファイルのときは
     * オフハンドスロット側の合算が除外されるので、寄与アイテムはちょうど1回だけ数えられる。
     * この盾は offhand-stats-apply=true なので、excludeOffhandSlot が正しく効いていなければ
     * スロット側からもう1回(mainhand合算の7 + スロット合算の7 = 14)加算されてしまう —
     * それが起きず7のままであることが二重計上防止の固定点。
     */
    @Test
    void contributorIsOffhand_offhandSlotExcludedSoContributorCountedExactlyOnce(
            @TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, true); // SHIELD offhand-stats-apply=true
        Player player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        ItemStack contributorMirror = player.getInventory().getItemInOffHand().clone();

        PlayerCombatAggregate agg = aggregator.aggregate(player, contributorMirror, true);

        assertEquals(7.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "寄与アイテム(盾=7)がオフハンドの中身と同一プロファイルなのでスロット側は除外され、"
                        + "ちょうど7のまま(14への二重計上にならない)");
    }

    /**
     * mainhand マップ(アイテムCT専用)は contributorIsOffhand=true のとき<b>寄与アイテム</b>から
     * 作られる(実メインハンドではない) — 「今使ったアイテム」のCTが正しいため。
     */
    @Test
    void contributorIsOffhand_mainhandMapReflectsContributor(@TempDir File dir)
            throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, true);
        Player player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        ItemStack contributorMirror = player.getInventory().getItemInOffHand().clone();

        PlayerCombatAggregate agg = aggregator.aggregate(player, contributorMirror, true);

        assertEquals(7.0, agg.mainhand().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "mainhand()は寄与アイテム(盾=7)由来であるべきで、実メインハンド(剣=10)由来ではない");
    }

    /**
     * 2026-08-13 回帰固定(CRITICALバグの再発防止): メインハンドに attack-power の大きいアイテムを、
     * オフハンドに attack-power の小さい寄与アイテムを持って発射した場合、agg.item()のattack-power
     * は寄与アイテム(発射した武器)の値だけであり、メインハンド側の値が混ざってはならない。
     * 修正前のコード(実メインハンドを合算に使っていた)に戻すと、メインハンドの巨大な値がベース
     * ダメージへ混入してしまう(2026-08-13 実サーバ回帰: ネザライト剣+弓でおよそ54倍の矢ダメージ)。
     */
    @Test
    void offhandProjectile_baseAttackPowerComesFromFiredWeaponNotFromActualMainhand(@TempDir File dir)
            throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  NETHERITE_SWORD:
                    fixed: { attack-power: 3780.0 }
                  BOW:
                    fixed: { attack-power: 69.0 }
                """);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator =
                new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        Player player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
        player.getInventory().setItemInOffHand(new ItemStack(Material.BOW));
        ItemStack contributorMirror = player.getInventory().getItemInOffHand().clone();

        PlayerCombatAggregate agg = aggregator.aggregate(player, contributorMirror, true);

        assertEquals(69.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "発射した弓(69)だけが合算され、メインハンドの剣(3780)は絶対に混ざってはならない");
        assertEquals(69.0, agg.mainhand().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "mainhand()(武器CT)も寄与アイテム(弓=69)由来であるべき");
    }

    /**
     * 2026-08-13 回帰固定: 寄与アイテム(contributorIsOffhand=true)とは<b>別</b>のアイテムが
     * オフハンドにあり、そのオフハンドアイテムが offhand-stats-apply:true を持つ場合、
     * その寄与が落ちてはならない(飛び道具は発射から着弾まで秒単位の遅延があり、その間に
     * オフハンドの中身が入れ替わっていることがある — sameItemProfile が一致しないので
     * excludeOffhandSlot は false になり、スロット側の合算は生きたままでなければならない)。
     */
    @Test
    void contributorIsOffhand_offhandSwappedMidFlight_newOffhandItemStillCounts(@TempDir File dir)
            throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  BOW:
                    fixed: { attack-power: 69.0 }
                  SHIELD:
                    fixed: { attack-power: 7.0 }
                    offhand-stats-apply: true
                """);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator =
                new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        Player player = server.addPlayer();
        // 発射時点のオフハンド寄与アイテムは弓(発射後にretainされたクローン)。
        ItemStack contributorMirror = new ItemStack(Material.BOW);
        // 着弾までの間にオフハンドの中身が盾へ入れ替わっている(BOW != SHIELD なので sameItemProfile=false)。
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));

        PlayerCombatAggregate agg = aggregator.aggregate(player, contributorMirror, true);

        assertEquals(76.0, agg.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9,
                "寄与アイテム(弓=69) + 入れ替わった新しいオフハンドの盾(offhand-stats-apply=true, 7) の"
                        + "両方が合算されるべき(スロット除外は行われない)");
    }

    /**
     * contributorIsOffhand=false の既存挙動は1バイトも変わらない(回帰固定)。
     * 既存の offhandFlagTrue_includesOffhandStats / mainhandMap_excludesArmorAndOffhand と同じ
     * フィクスチャで、2引数/3引数(false)経路が同一の結果を返すことを確認する。
     */
    @Test
    void contributorIsOffhandFalse_behavesIdenticallyToTwoArgOverload(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, true);
        Player player = equippedPlayer();

        PlayerCombatAggregate viaTwoArg = aggregator.aggregate(player);
        PlayerCombatAggregate viaThreeArgFalse = aggregator.aggregate(
                player, player.getInventory().getItemInMainHand(), false);

        assertEquals(viaTwoArg.item().getOrDefault(ATTACK_POWER, 0.0),
                viaThreeArgFalse.item().getOrDefault(ATTACK_POWER, 0.0), 1e-9);
        assertEquals(viaTwoArg.mainhand().getOrDefault(ATTACK_POWER, 0.0),
                viaThreeArgFalse.mainhand().getOrDefault(ATTACK_POWER, 0.0), 1e-9);
    }

    // --- 2026-08-13 修正2: nonPerkStatTotal(armor-set-bonus 総合値化のための読み取り口) ---

    /**
     * nonPerkStatTotal は base-stats / 役職 / 永続 / 装備(防具+実メインハンド)を足し、
     * パーク general分は含まない(NativeAttributeBridge側が別途足すため二重計上防止)。
     */
    @Test
    void nonPerkStatTotal_sumsBaseRolePermanentAndEquipmentButExcludesPerk(@TempDir File dir)
            throws IOException {
        writeItemStatsWithManaBonus(dir); // DIAMOND_SWORD.mana_bonus=1.0
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");

        Map<String, Double> buffs = new LinkedHashMap<>();
        buffs.put("mana_bonus", 2.0); // パーク分: nonPerkStatTotalには含まれてはいけない
        SkillNode node = new SkillNode("N", "マナの型", 1, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                buffs, Map.of(), List.of(), List.of(), List.of());
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        nodes.put("N", node);
        SkillTree tree = new SkillTree("ARS_MAGIC", "アルス魔術", null, "1,1", null, nodes);
        String perkId = PerkNaming.perkId("ARS_MAGIC", "N");
        SkillPerkStatSource source = playerId -> java.util.Set.of(perkId);
        PerkBuffResolver perks = new PerkBuffResolver(source, () -> List.of(tree));

        RoleBuffResolver roleBuffResolver = mock(RoleBuffResolver.class);
        when(roleBuffResolver.contributionFor(any())).thenReturn(new RoleBuffResolver.Contribution(
                Map.of(MANA_BONUS, 3.0), Map.of(), 1.0, null, 1.0));
        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        when(permanentBuffResolver.buffsFor(any())).thenReturn(Map.of(MANA_BONUS, 5.0));
        BaseStatsConfig baseStats = baseStatsFrom(dir, """
                base-stats:
                  mana_bonus: 7
                """);

        PlayerStatAggregator aggregator = new PlayerStatAggregator(cm.itemStats(), damage, perks,
                roleBuffResolver, null, permanentBuffResolver, baseStats);
        Player player = equippedPlayer(); // 剣(mana_bonus=1)を装備

        double total = aggregator.nonPerkStatTotal(player, "mana_bonus");

        assertEquals(16.0, total, 1e-9,
                "装備(1) + 役職(3) + 永続(5) + base-stats(7) = 16。パーク分(2)は含まない");
    }

    @Test
    void nonPerkStatTotal_includesActualMainhandItemStats(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir, false);
        Player player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));

        double total = aggregator.nonPerkStatTotal(player, "attack-power");

        assertEquals(10.0, total, 1e-9, "実メインハンド(剣=10)のitem-statsが含まれる");
    }
}
