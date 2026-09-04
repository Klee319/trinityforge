package com.trinityforge.listeners;

import com.trinityforge.combat.EnchantmentStatBridge;
import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.fishing.FishingGimmickPolicy;
import com.trinityforge.items.ItemStackDrops;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import com.trinityforge.stats.DropTablePolicy;
import com.trinityforge.stats.StatKeys;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * 釣りの獲得物置換 (2026-07-23 stat-gate-overhaul §2.3/§4): {@code stats/fishing-gimmick.yml} の
 * {@code fishing.groups}(treasure/junk) が非空なら、CAUGHT_FISH時の釣果を丸ごと置換する:
 *
 * <ol>
 *   <li>宝率 = clamp(group-ratio.treasure-percent × (1 + luckTotal), 0.05, 95.0)。luckTotal は
 *       ロッドの合算{@code fishing_luck}stat + {@link EnchantmentStatBridge}の宝釣りエンチャ分 +
 *       FISHINGスキルLv×{@code luck-per-level}。</li>
 *   <li>宝/ゴミ/魚(残り=通常の魚)のどのグループから引くかを宝率/ゴミ率でロールし、そのグループの
 *       開放済みカテゴリ全体から重み付き抽選({@link DropTablePolicy}、{@code drop:fishing:<catId>}/
 *       {@code drop:fishing:item:<id>}ゲート適用)で1件確定する。{@code fish}グループ(2026-07-25追加:
 *       「通常の魚」枠の設定可能化)が未設定(空)の場合は、旧来どおり通常の魚の抽選結果だとバニラキャッチを
 *       一切変更しない。</li>
 *   <li>ゴミグループから引いた結果に対しては、既存の {@code junk-to-scrap} 差し替え(→{@code tf_scrap})を
 *       そのまま適用する。{@code fish}グループから引いた結果には適用しない(通常の魚はゴミではないため)。</li>
 * </ol>
 *
 * <p>テーブルが空(未設定)の場合はバニラ釣果に一切手を加えず、旧 junk/treasure マテリアルリストに基づく
 * {@code junk-to-scrap} 動作だけをフォールバックとして維持する(design doc §4 fallback note)。
 *
 * <p>{@link EventPriority#LOW}で登録: {@link FishingQualityListener}({@link EventPriority#NORMAL})が
 * 釣果の品質刻印/追加ドロップを行うより先に走らせる必要がある — 置換後のMaterialを品質刻印側が正しく
 * 参照できるようにするため。置換したのが宝/ゴミどちらのグループだったかは、釣果エンティティのPDCに
 * {@code treasureFlagKey} で記録し、{@link FishingQualityListener}のトレジャー複製防止ロジックに渡す。
 */
public final class FishingGimmickListener implements Listener {

    private static final Logger LOG = Logger.getLogger(FishingGimmickListener.class.getName());

    /**
     * groups と unlock-groups でカテゴリidが衝突した組み合わせの記録(警告を1回だけ出すため)。
     * 判定は釣り上げるたびに通るので、毎回警告すると重複でコンソールが埋まり他の警告が埋もれる。
     */
    private final Set<String> warnedDuplicateCategories = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final String EFFECT_JUNK_TO_SCRAP = "junk-to-scrap";
    private static final String EFFECT_FISH_SELL_TOGGLE = "fish-sell-toggle";
    private static final String CATALOG_SCRAP = "tf_scrap";
    private static final String PROF_FISHING = "fishing";
    private static final String GROUP_TREASURE = "treasure";
    private static final String GROUP_JUNK = "junk";
    /** 2026-07-25追加: 「通常の魚」枠の設定可能化(T1)。未設定(空)なら旧来どおりバニラ釣果を維持する。 */
    private static final String GROUP_FISH = "fish";

    private static final String FISHING_LUCK_KEY = StatKeys.canonical("fishing-luck");

    private final DedicatedEffectsConfig dedicatedEffects;
    private final FishingGimmickConfig gimmickConfig;
    private final CrossPluginItemResolver itemResolver;
    private final PlayerStatAggregator aggregator;
    private final SkillLevelSource skillLevelSource;
    private final NamespacedKey treasureFlagKey;
    /**
     * {@code removed-vanilla-items} の参照口(任意注入・null可)。エンチャント本の中身を抽選するときに
     * 「サーバから消してあるエンチャント(既定は修繕)」を候補から外すために使う。
     */
    private volatile com.trinityforge.stats.VanillaItemRemover vanillaItemRemover;

    public FishingGimmickListener(DedicatedEffectsConfig dedicatedEffects, FishingGimmickConfig gimmickConfig,
                                   CrossPluginItemResolver itemResolver, PlayerStatAggregator aggregator,
                                   SkillLevelSource skillLevelSource, NamespacedKey treasureFlagKey) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.treasureFlagKey = Objects.requireNonNull(treasureFlagKey, "treasureFlagKey");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        ItemStack caughtStack = caught.getItemStack();
        if (caughtStack == null || caughtStack.getType().isAir()) {
            return;
        }

        if (gimmickConfig.dropTablesEmpty()) {
            applyLegacyJunkToScrapFallback(player, caught, caughtStack.getType());
            return;
        }
        replaceWithDropTable(player, caught);
    }

    /**
     * New table-driven replace flow (§2.3/§4, 2026-07-23 仕様確定・三択モデル化; T1 2026-07-25で「通常の魚」枠
     * 設定可能化): 宝% / ゴミ% / 残り=通常の魚。通常の魚が出た場合、{@code fish}グループが未設定(空)なら
     * 何もせずバニラキャッチをそのまま維持する(宝フラグPDCも付けない、旧来どおり)。{@code fish}グループが
     * 非空なら、宝/ゴミとまったく同じ抽選経路({@link DropTablePolicy#drawAcrossCategoriesWithExemption})で
     * 釣果を置換する — ただし宝フラグPDCには{@code false}を書く(宝ではないため)し、{@code junk-to-scrap}の
     * スクラップ差し替えは適用しない(魚はゴミではないため)。
     */
    private void replaceWithDropTable(Player player, Item caught) {
        double luckTotal = luckTotalOf(player);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        DropTablePolicy.FishOutcome outcome = DropTablePolicy.rollFishOutcome(
                gimmickConfig.treasurePercent(), gimmickConfig.junkPercent(), luckTotal, rng.nextDouble());

        if (outcome == DropTablePolicy.FishOutcome.NORMAL_FISH) {
            // T1: fishグループが空なら旧来どおり何もしない(バニラキャッチ維持、宝フラグPDCも付けない)。
            // replaceFromGroup自身もcategories空ならfail-safeで何もしないが、ここで先に判定することで
            // luckTotal/rng消費以外の余計な処理(perk走査等)を後方互換パスでは一切増やさない。
            if (gimmickConfig.groups().getOrDefault(GROUP_FISH, Map.of()).isEmpty()) {
                return;
            }
            replaceFromGroup(player, caught, GROUP_FISH, false, rng);
            return;
        }

        boolean treasure = outcome == DropTablePolicy.FishOutcome.TREASURE;
        if (treasure && dedicatedEffects.isActive(player, EFFECT_FISH_SELL_TOGGLE)
                && !gimmickConfig.groups().getOrDefault(GROUP_JUNK, Map.of()).isEmpty()) {
            // B-alpha-1(2026-07-25経済連携): 「宝が釣れなくなりゴミが釣れる(宝の確率でゴミが釣れる)」——
            // 宝が出るはずだった抽選結果をゴミ枠へ丸ごと差し替える。ゴミテーブルが未設定(空)の場合は
            // 差し替え不能のため元の宝抽選を維持する(fail-safe: 何も出せなくなるより安全側に倒す)。
            treasure = false;
        }
        String groupId = treasure ? GROUP_TREASURE : GROUP_JUNK;
        replaceFromGroup(player, caught, groupId, treasure, rng);
    }

    /**
     * {@code groupId}の開放済みカテゴリ全体から1件抽選し、{@code caught}を置換して宝フラグPDCに
     * {@code treasureFlag}を書く。抽選対象カテゴリが無い/抽選が空(全ロック等)の場合はバニラキャッチを
     * そのまま維持する(fail-safe)。{@code junk-to-scrap}のスクラップ差し替えは{@code groupId}が実際に
     * {@link #GROUP_JUNK}のときだけ適用される({@code fish}グループには適用しない)。
     *
     * <p>2026-08-15追加(機能解放追加テーブル): {@code fishing.groups.<groupId>}(デフォルト)と
     * {@code fishing.unlock-groups.<groupId>}(機能解放で開くカテゴリ)を{@link #mergeGateKeyedCategories}
     * で1つの重みプールへ合流させてから抽選する(希釈あり・別ロールにはしない、ユーザー確定仕様)。
     */
    private void replaceFromGroup(Player player, Item caught, String groupId, boolean treasureFlag,
                                   ThreadLocalRandom rng) {
        Map<String, DropTableConfig.Category> categories = mergeGateKeyedCategories(groupId);
        if (categories.isEmpty()) {
            return; // Fail-safe: nothing configured for this group -> leave the vanilla catch untouched.
        }
        Set<String> heldPerks = DropTableGateSupport.heldPerksOf(player);
        Map<String, Set<String>> dropGatePerks = dedicatedEffects.dropGatePerks();
        Optional<DropTablePolicy.DrawnEntry> drawn = DropTablePolicy.drawAcrossCategoriesWithExemption(
                PROF_FISHING, categories, heldPerks, dropGatePerks, rng.nextDouble());
        if (drawn.isEmpty()) {
            // Fail-safe: everything in the chosen group is locked/unresolvable — leave the vanilla
            // catch untouched rather than replacing it with nothing.
            return;
        }

        DropTableConfig.Entry entry = drawn.get().entry();
        boolean scrapExempt = drawn.get().scrapExempt();
        boolean applyJunkToScrap = GROUP_JUNK.equals(groupId);
        ItemStack replacement = resolveJunkToScrapOrEntry(player, applyJunkToScrap, scrapExempt, entry);
        if (replacement == null) {
            return;
        }
        // 2026-09-04 W-312: entry.amount() が99を超える設定だと、既存の Item エンティティ
        // (caught)へそのまま積むとアイテムエンティティのコーデック上限(99)を超えて消滅する。
        // 分割し、先頭の1本だけを既存エンティティへ載せ、残りは同じ位置へ新規に落とす。
        List<ItemStack> parts = ItemStackDrops.split(replacement);
        caught.setItemStack(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            caught.getWorld().dropItemNaturally(caught.getLocation(), parts.get(i));
        }
        caught.getPersistentDataContainer().set(treasureFlagKey, PersistentDataType.BOOLEAN, treasureFlag);
    }

    /**
     * {@code fishing.groups.<groupId>}(デフォルト)と{@code fishing.unlock-groups.<groupId>}(機能解放追加)の
     * カテゴリを1つの重みプールへ合流させる(2026-08-15追加)。同時に、既存バグ修正としてゲート照会キーを
     * {@code "<groupId>:<catId>"}へ前置きする — {@link DropTablePolicy#drawAcrossCategoriesWithExemption}は
     * このMapのキーをそのまま{@code categoryOpen(prof, key, ...)}へ渡すので、前置きすることで
     * {@code categoryOpen("fishing", "treasure:gatya", ...)}が{@code "fishing:treasure:gatya"}を引き、
     * {@link com.trinityforge.skilltree.effects.DedicatedEffectGateIndex}が{@code drop:fishing:treasure:gatya}
     * から登録する3セグメントのゲートキーと一致するようになる(旧実装は{@code catId}だけをキーにしていたため
     * {@code "fishing:gatya"}(2セグメント)を引いてしまい、未登録キー=常時オープン扱いになっていた)。
     *
     * <p>{@code groups}と{@code unlock-groups}に同じ{@code groupId}配下で同じカテゴリidが両方あるときは、
     * ゲートキーが完全に衝突して「どちらが効いているか判別不能」になるため、{@code unlock-groups}側を
     * 警告ログのうえ無視する(ユーザー確定仕様)。
     */
    private Map<String, DropTableConfig.Category> mergeGateKeyedCategories(String groupId) {
        Map<String, DropTableConfig.Category> defaults = gimmickConfig.groups().getOrDefault(groupId, Map.of());
        Map<String, DropTableConfig.Category> unlocks = gimmickConfig.unlockGroups().getOrDefault(groupId, Map.of());
        if (defaults.isEmpty() && unlocks.isEmpty()) {
            return Map.of();
        }
        Map<String, DropTableConfig.Category> merged = new LinkedHashMap<>();
        for (Map.Entry<String, DropTableConfig.Category> catEntry : defaults.entrySet()) {
            merged.put(groupId + ":" + catEntry.getKey(), catEntry.getValue());
        }
        for (Map.Entry<String, DropTableConfig.Category> catEntry : unlocks.entrySet()) {
            if (defaults.containsKey(catEntry.getKey())) {
                // 警告は組み合わせごとに1回だけ。ここは釣り上げるたびに通る経路なので、
                // 毎回出すとコンソールが重複警告で埋まり、他の警告が見えなくなる。
                if (warnedDuplicateCategories.add(groupId + ":" + catEntry.getKey())) {
                    LOG.warning("[fishing] unlock-groups." + groupId + "." + catEntry.getKey()
                            + " は groups." + groupId + "." + catEntry.getKey() + " と同じカテゴリidのため、"
                            + "unlock-groups側を無視しました(ゲートキー衝突を避けるため)。");
                }
                continue;
            }
            merged.put(groupId + ":" + catEntry.getKey(), catEntry.getValue());
        }
        return merged;
    }

    /**
     * Junk-group draws still honor {@code junk-to-scrap} (existing scrap swap, reused verbatim) — unless
     * the drawn entry came from a {@code scrap-exempt} category (2026-07-23 verifier指摘⑥: e.g.
     * {@code ocean_thread}), in which case the original catch is kept so it stays obtainable for
     * junk-to-scrap holders too. {@code applyJunkToScrap} is {@code true} only for an actual
     * {@link #GROUP_JUNK} draw (T1 2026-07-25: the {@code fish} group must never be scrap-swapped, even
     * though its PDC treasure flag is also {@code false} like junk).
     */
    private ItemStack resolveJunkToScrapOrEntry(Player player, boolean applyJunkToScrap, boolean scrapExempt,
                                                 DropTableConfig.Entry entry) {
        if (applyJunkToScrap && !scrapExempt && dedicatedEffects.isActive(player, EFFECT_JUNK_TO_SCRAP)) {
            Optional<ItemStack> scrap = itemResolver.create(CATALOG_SCRAP);
            if (scrap.isPresent()) {
                ItemStack stack = scrap.get();
                stack.setAmount(Math.max(1, entry.amount()));
                return stack;
            }
        }
        Optional<ItemStack> built = itemResolver.create(entry.item());
        if (built.isEmpty()) {
            return null;
        }
        ItemStack stack = built.get();
        stack.setAmount(Math.max(1, entry.amount()));
        return rollBookEnchantIfBare(stack);
    }

    /**
     * 任意注入(2026-07-30): {@code removed-vanilla-items} をエンチャント本の抽選候補から外すために使う。
     * 未注入でも動作する(その場合は候補を絞らない)。既存テストのコンストラクタ呼び出しを壊さないため
     * セッター注入にしてある(このリポジトリの横断ゲート追加の定石)。
     */
    public void setVanillaItemRemover(com.trinityforge.stats.VanillaItemRemover remover) {
        this.vanillaItemRemover = remover;
    }

    /**
     * ドロップテーブルの {@code item: ENCHANTED_BOOK} を、<b>中身のあるエンチャント本</b>にする
     * (2026-07-30 実サーバ報告「釣りでエンチャントのついていないエンチャント本がつれる」)。
     *
     * <p>原因: {@code CrossPluginItemResolver#create("ENCHANTED_BOOK")} は素の Material から
     * {@link ItemStack} を作るだけなので、収録エンチャントが空のエンチャント本になる。
     * バニラの釣り宝は loot table の {@code enchant_randomly} で必ず中身が付くため、
     * 「空のエンチャント本」はバニラには存在しない状態で、金床でも何にも使えない。
     *
     * <p>候補からは {@code progression/crafting-features.yml removed-vanilla-items} で消してある
     * エンチャント(既定 {@code ANY:MENDING})を除く — ここで修繕本を作ってしまうと、後段の
     * {@code VanillaItemRemovalListener} が剥がして結局また空の本に戻る。
     *
     * <p>呪い(束縛/消滅)も候補から外す(2026-08-13 ユーザー判断)。バニラの釣り宝
     * ({@code enchant_with_levels} treasure:true)は呪い本も出すが、TF では「釣果は当たり」に
     * 統一する。除外の判定は {@link #cursedEnchants()} 参照。
     *
     * <p><b>バニラと同じではない</b>: バニラは経験値レベル30相当の重み付き抽選で複数エンチャントが
     * 付きうるが、ここは候補から一様ランダムで1件・レベルも {@code 1..maxLevel} の一様乱数。
     * オーバーエンチャント表({@code crafting-features.yml over-enchant})は金床/エンチャント台側の
     * 上限なので、釣果の本には掛からない(付与レベルはバニラの maxLevel が上限)。
     *
     * <p>既に中身がある本(カタログ品/他プラグイン製)には一切触らない。
     */
    private ItemStack rollBookEnchantIfBare(ItemStack stack) {
        if (stack == null || stack.getType() != Material.ENCHANTED_BOOK) {
            return stack;
        }
        if (!(stack.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage)
                || storage.hasStoredEnchants()) {
            return stack;
        }
        Set<Enchantment> curses = cursedEnchants();
        java.util.List<Enchantment> pool = new java.util.ArrayList<>();
        for (Enchantment candidate : Registry.ENCHANTMENT) {
            if (curses.contains(candidate) || isRemovedEnchant(candidate)) {
                continue;
            }
            pool.add(candidate);
        }
        if (pool.isEmpty()) {
            return stack;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Enchantment chosen = pool.get(rng.nextInt(pool.size()));
        int max = Math.max(1, chosen.getMaxLevel());
        storage.addStoredEnchant(chosen, max == 1 ? 1 : rng.nextInt(1, max + 1), true);
        stack.setItemMeta(storage);
        return stack;
    }

    /**
     * 呪いエンチャントの集合。{@code EnchantLuckListener#enchantingTablePool()} と同じ二段構えで、
     * <b>タグ {@code #minecraft:curse} を第一経路</b>にする(ハードコードした除外リストと違い、
     * バニラ側で呪いが増減しても自動で追随する)。
     *
     * <p>第二経路は非推奨の {@link Enchantment#isCursed()}。MockBukkit 4.110.0 は
     * {@code Registry#hasTag}/{@code getTagValues} がどちらも {@code UnimplementedOperationException}
     * を投げるため、テスト環境ではこちらへ落ちる。
     *
     * <p>どちらも失敗したら空集合を返す = 呪いを弾かない(fail-open)。ここで例外を投げると
     * 釣果の生成そのものが落ちてアイテムが消えるので、「呪い本がたまに釣れる」より悪い。
     */
    @SuppressWarnings("deprecation") // isCursed(): タグ API が無い環境向けのフォールバックとしてのみ使う
    static Set<Enchantment> cursedEnchants() { // package-private: 単体テストの seam
        try {
            if (Registry.ENCHANTMENT.hasTag(EnchantmentTagKeys.CURSE)) {
                java.util.Collection<Enchantment> tagged =
                        Registry.ENCHANTMENT.getTagValues(EnchantmentTagKeys.CURSE);
                if (!tagged.isEmpty()) {
                    return Set.copyOf(tagged);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // タグ API 未実装/未ロードの環境。下の isCursed() 経路へ落ちる。
        }
        try {
            Set<Enchantment> out = new java.util.HashSet<>();
            for (Enchantment candidate : Registry.ENCHANTMENT) {
                if (candidate.isCursed()) {
                    out.add(candidate);
                }
            }
            return out;
        } catch (RuntimeException | LinkageError ignored) {
            return Set.of();
        }
    }

    private boolean isRemovedEnchant(org.bukkit.enchantments.Enchantment candidate) {
        com.trinityforge.stats.VanillaItemRemover remover = this.vanillaItemRemover;
        if (remover == null || !remover.hasTargets()) {
            return false;
        }
        ItemStack probe = new ItemStack(Material.ENCHANTED_BOOK);
        if (!(probe.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta probeMeta)) {
            return false;
        }
        probeMeta.addStoredEnchant(candidate, 1, true);
        probe.setItemMeta(probeMeta);
        return remover.shouldRemove(probe);
    }

    /** {@code luckTotal} for the treasure-ratio shift: rod's aggregated stat + enchant bonus + skill level. */
    private double luckTotalOf(Player player) {
        ItemStack rod = resolveRod(player.getInventory());
        // 2026-08-13バグ修正: ロッドがオフハンド側にある場合は offhand-stats-apply の門を正しく通す
        // (FishingQualityListener#onFish の rodFromOffhand と同じ導出。参照比較はライブサーバーでは
        // スロット読み取りごとの新ミラーで一致しないため、Material判定で求める)。
        boolean rodFromOffhand = player.getInventory().getItemInMainHand().getType() != Material.FISHING_ROD
                && rod.getType() == Material.FISHING_ROD;
        PlayerCombatAggregate agg = aggregator.aggregate(player, rod, rodFromOffhand);
        double statLuck = agg.totalOf(FISHING_LUCK_KEY);
        double enchantLuck = EnchantmentStatBridge.bonuses(rod, null).fishingLuckBonus();
        int fishingLevel = skillLevelSource.levelsOf(player.getUniqueId())
                .getOrDefault(gimmickConfig.fishingSkillId(), 0);
        return statLuck + enchantLuck + fishingLevel * gimmickConfig.luckPerLevel();
    }

    private static ItemStack resolveRod(PlayerInventory inventory) {
        ItemStack mainHand = inventory.getItemInMainHand();
        if (mainHand.getType() == Material.FISHING_ROD) {
            return mainHand;
        }
        ItemStack offHand = inventory.getItemInOffHand();
        if (offHand.getType() == Material.FISHING_ROD) {
            return offHand;
        }
        return mainHand;
    }

    /**
     * Fallback (drop tables not yet configured): the pre-2026-07-23 behavior — a junk-list catch is
     * replaced with {@code tf_scrap} when {@code junk-to-scrap} is active. {@code fish-sell-toggle}
     * (revived 2026-07-25 for the Vault economy bridge — see {@link FishSellListener}) has no
     * treasure/junk ratio to redirect in this legacy fallback path (no drop tables configured yet),
     * so only its "sell what you catch" half applies here; the "no more treasure" half only exists
     * once {@code fishing.groups} is populated (see {@link #replaceWithDropTable}).
     */
    private void applyLegacyJunkToScrapFallback(Player player, Item caught, Material caughtMaterial) {
        if (!dedicatedEffects.isActive(player, EFFECT_JUNK_TO_SCRAP)) {
            return;
        }
        if (!FishingGimmickPolicy.isJunk(caughtMaterial, gimmickConfig.junkMaterials())) {
            return;
        }
        Optional<ItemStack> scrap = itemResolver.create(CATALOG_SCRAP);
        if (scrap.isEmpty()) {
            // Fail-safe: a missing/renamed catalog entry must never throw out of a fish-catch handler.
            return;
        }
        ItemStack current = caught.getItemStack();
        int amount = current == null ? 1 : Math.max(1, current.getAmount());
        ItemStack stack = scrap.get();
        stack.setAmount(amount);
        caught.setItemStack(stack);
    }
}
