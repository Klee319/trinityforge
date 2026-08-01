package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.BrewPotionMixRegistrar.MixPlan;
import com.trinityforge.stats.BrewRecipeSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BrewingStartEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Gated custom brew results ({@code brew-unlocks} in crafting-features.yml), gated by
 * {@code brew:<groupId>} (2026-07-23 動的ID方式改修 §3): unlike a recipe gate, an unreferenced group stays
 * <b>locked</b> — no node placing {@code brew:<groupId>} means {@code isActive} naturally returns
 * {@code false} for every player, so no explicit "unreferenced -&gt; open" branch is needed here.
 *
 * <h2>役割分担 (2026-07-31 D10 = K-13 の修正後)</h2>
 * <ul>
 *   <li><b>「素材を上段に置ける / 醸造が始まる」を成立させるのは
 *       {@link com.trinityforge.stats.BrewPotionMixRegistrar}</b>(Paper の customMixes へ登録)。
 *       それまでこのリスナーが {@code stand.setBrewingTime(400)} で押し込んでいたが、
 *       (a) 素材がスロットに入らないので到達しない、(b) 到達しても {@code isBrewable()} が false なので
 *       {@code serverTick} が次tickで 0 に戻す、(c) NMS の {@code ingredient} フィールドを更新しない、
 *       の三重で<b>原理的に効いていなかった</b>(しかも燃料を減らさない)。強制開始の実装は削除済み。</li>
 *   <li><b>このリスナーは「解放判定」と「結果の差し替え」だけを担う。</b></li>
 * </ul>
 *
 * <h2>ゲート対象は「実際に登録された mix」だけ</h2>
 * 判定に使うのは生の {@code brewUnlocks()} ではなく
 * {@link com.trinityforge.stats.BrewPotionMixRegistrar#livePlans()}。登録されなかった組
 * (バニラ衝突・素材名の誤り・重複の敗者)は<b>そもそも醸造が始まらない</b>か<b>バニラのレシピとして
 * 成立する</b>ので、ゲートを掛けても守るものが無く「バニラの俊敏のポーションが作れない」型の
 * 誤爆になるだけ。両者が同じ一覧を見ることで「素材は置けるのに結果が差し替わらない(逆も)」という
 * <b>片方だけ直したときに静かに壊れる</b>食い違いも構造的に起きなくなる。
 *
 * <h2>未解放プレイヤーの扱い: 燃料と時間が動く前に止める</h2>
 * mix を登録すると素材は<b>誰でも</b>上段に置けて醸造も始まる。完成時に {@code BrewEvent} を
 * キャンセルするだけだと、{@code doBrew} は素材を {@code shrink} せず即 return し、
 * {@code brewTime} は既に 0 なので次tickで {@code brewable && fuel>0} から再開する
 * → <b>20秒ごとに燃料を1つ燃やし続ける</b>。{@code BrewingStartEvent} は {@code Cancellable} ではない
 * (1.21.11 で確認済み)ので、開始イベントで止めることもできない。そこで3段で塞ぐ:
 * <ol>
 *   <li>{@link #onBrewerClick} / {@link #onBrewerDrag} / {@link #onHopperMove} —
 *       <b>組み合わせが成立する投入を弾く</b>。素材側・ビン側の<b>両方</b>を見るので
 *       「素材→ビン」「ビン→素材」どちらの順序でも抜けない
 *       (素材だけを見ていた旧実装は、砂糖を先に入れてから THICK ビンを入れると素通りしていた)。</li>
 *   <li>{@link #onBrewingStandFuel} — 未解放の組み合わせが載っているスタンドは
 *       <b>燃料を受け付けない</b>({@code BrewingStandFuelEvent} はキャンセル可能で、
 *       キャンセルするとブレイズパウダーも消費されない)。これが「開始そのものを弾く」本体
 *       かつ<b>ループの終端を保証する唯一の機構</b>。</li>
 *   <li>{@link #onBrewingStart} — 既に燃料を持っていたスタンドが走り出した場合の記録
 *       (診断ログのみ)。<b>残りの燃料チャージには触らない</b> — 詳細は同メソッドの javadoc。</li>
 * </ol>
 *
 * <h2>解放判定は「スタンドに記録された所有者」</h2>
 * 所有者の記録は {@link BrewOwnership}(醸造の所有者PDCの単一定義)で、EXP・品質・速度の帰属先と
 * <b>同じ1本</b>。閲覧者や半径8ブロックのプレイヤーで判定していた旧実装は、
 * <b>解放者が醸造中(20秒)に8ブロック歩くだけで</b>未解放扱いへ落ちて上記の燃料ループに入っていた。
 *
 * <p><b>所有権の移り方</b>({@link #isLockedInsertion} で決まる):
 * <ul>
 *   <li>記録が無ければ、その組み合わせを解放している操作者が所有者になる。</li>
 *   <li>記録済み所有者が<b>オンラインかつその組み合わせを解放している</b>間は、
 *       他の解放済みプレイヤーが同じ組を投入しても<b>所有権は移らない</b>
 *       (乗っ取り→ログアウトで他人の台を止められる穴を閉じるため)。</li>
 *   <li>逆に、記録済み所有者がオフラインまたは未解放なら、解放している操作者へ<b>差し替える</b>。
 *       ここを閉じると未解放プレイヤーが先に1回投入するだけで他人の台を永久に止められる。</li>
 * </ul>
 *
 * <p><b>⚠️ ホッパー自動化への影響</b>: ホッパーには操作者がいないので、判定は
 * <b>スタンドに記録された所有者</b>だけで行う。したがって
 * 「所有者が未記録」「所有者がオフライン」「所有者がその組み合わせを解放していない」のいずれでも
 * <b>投入が拒否され、素材はホッパーに残って詰まる</b>。燃料が無言で溶けるより事故が少ないという判断
 * (オーケストレータ決定 2026-07-31)。
 *
 * <p>さらに所有者の記録は<b>醸造が1回完了するたびに消える</b>
 * ({@link NativeSkillExperienceListener#onBrew} の {@link BrewOwnership#clear})。したがって
 * <b>ゲート付き醸造をホッパーで連続量産することは構造的にできない</b> — サイクルごとに
 * 解放済みプレイヤーの手投入が必要になる(レビュー指摘#5「所有者記録後は未解放プレイヤーが
 * ホッパーで量産できる」の是正)。
 */
public final class BrewUnlockListener implements Listener {

    private static final String GATE_PREFIX = "brew:";
    private static final Component LOCKED_MESSAGE = Component.text(
            "この醸造素材を使うにはスキルツリーで解放する必要があります", NamedTextColor.RED);

    private final DedicatedEffectsConfig dedicatedEffects;
    private final Supplier<List<MixPlan>> gatedMixes;
    private final BrewStandOwners owners;
    private final Plugin plugin;

    public BrewUnlockListener(DedicatedEffectsConfig dedicatedEffects,
                              Supplier<List<MixPlan>> gatedMixes,
                              BrewStandOwners owners,
                              Plugin plugin) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gatedMixes = Objects.requireNonNull(gatedMixes, "gatedMixes");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 優先度は {@code NORMAL} 固定。
     * <ul>
     *   <li><b>上限側</b>: {@code PotionQualityListener}(HIGH)が結果の {@code ItemMeta} を書き換えた
     *       <b>後</b>に {@code results.set} で丸ごと差し替えると、{@code potion_quality_bonus}
     *       (Lv70/Lv90 ノード)がゲート付き醸造に一切効かなくなる(2026-07-31 に発覚したバグ)。
     *       品質より<b>前</b>に差し替える必要がある。</li>
     *   <li><b>下限側</b>: {@code BrewIngredientSaveListener}(HIGHEST)は
     *       「全キャンセラより後」であることが複製防止の前提なので、キャンセルするこちらは
     *       それより前でなければならない。</li>
     * </ul>
     * この順序は {@code BrewUnlockIngredientGateTest} が注釈値で固定している。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        BrewerInventory inv = event.getContents();
        ItemStack ingredient = inv.getIngredient();
        if (ingredient == null || ingredient.getType().isAir()) {
            return;
        }

        List<ItemStack> results = event.getResults();
        List<MixPlan> matched = new ArrayList<>();
        for (MixPlan plan : gatedMixes.get()) {
            if (BrewRecipeSupport.matchesIngredient(ingredient, plan.spec().ingredient())
                    && hasMatchingBottleBase(inv, results, plan.spec().base())) {
                matched.add(plan);
            }
        }
        if (matched.isEmpty()) {
            return; // not a gated TF brew recipe
        }
        Player owner = resolveOwner(inv);

        // スタンド全体で「どれか1つの解放」を見ると、同じ材料を使う別baseの未解放瓶を
        // 混ぜるだけでゲートを迂回できる。各瓶について、そのbaseに一致するspecの少なくとも
        // 1つを解放していることを要求する。BrewEventは瓶単位でcancelできないため、1本でも
        // 未解放ならスタンド全体を止める。
        for (int slot = 0; slot < 3; slot++) {
            PotionMeta probe = potionMetaForSlot(inv, results, slot);
            if (probe == null) {
                continue;
            }
            boolean hasGatedMatch = false;
            boolean hasUnlockedMatch = false;
            for (MixPlan plan : matched) {
                if (!BrewRecipeSupport.matchesBase(probe, plan.spec().base())) {
                    continue;
                }
                hasGatedMatch = true;
                if (holdsUnlock(owner, plan)) {
                    hasUnlockedMatch = true;
                    break;
                }
            }
            if (hasGatedMatch && !hasUnlockedMatch) {
                event.setCancelled(true);
                return;
            }
        }

        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = inv.getItem(slot);
            if (bottle == null || bottle.getType().isAir()) {
                continue;
            }
            PotionMeta probe = potionMetaForSlot(inv, results, slot);
            if (probe == null) {
                continue;
            }
            // 万一同じ (base, ingredient) が複数残っていても、要求レベルが最も高い段を出す
            // (登録側の dedup と同じ勝敗規則。上位段が下位段に食われないための二重の保険)。
            MixPlan best = null;
            for (MixPlan plan : matched) {
                if (!holdsUnlock(owner, plan)
                        || !BrewRecipeSupport.matchesBase(probe, plan.spec().base())) {
                    continue;
                }
                if (best == null || plan.requirementLevel() > best.requirementLevel()) {
                    best = plan;
                }
            }
            if (best == null) {
                continue;
            }
            ItemStack custom = BrewRecipeSupport.customPotion(bottle.getType(), best.spec());
            while (results.size() <= slot) {
                results.add(null);
            }
            results.set(slot, custom);
        }
    }

    /**
     * 燃料の取得段でゲートを掛ける(= 醸造の開始そのものを止める本体)。
     *
     * <p>{@code BrewingStandFuelEvent} はキャンセル可能で、キャンセルすると
     * <b>燃料スロットのブレイズパウダーも消費されない</b>(CraftBukkit はイベント判定の後に
     * {@code fuel = getFuelPower()} と {@code shrink(1)} を行う)。燃料が 0 のままなら
     * {@code brewable && fuel > 0} が成立せず、醸造は1tickも進まない。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewingStandFuel(BrewingStandFuelEvent event) {
        BrewerInventory brew = BrewStandOwners.inventoryOf(event.getBlock());
        if (brew == null || !isLockedBrew(brew)) {
            return;
        }
        event.setCancelled(true);
        plugin.getLogger().log(java.util.logging.Level.FINE,
                () -> "[brew-unlocks] refused to fuel a brewing stand holding a locked gated brew");
    }

    /**
     * 既に燃料を持っていたスタンドが未解放の組み合わせで走り出した場合の記録
     * (所有者がログアウトした・ノードを振り直した・他プラグインが素材を差し込んだ等)。
     *
     * <p>{@code BrewingStartEvent} は {@code Cancellable} ではないので開始自体は止められない。
     * <b>この1周分の燃料チャージ1つ</b>は、イベント発火前にバニラが既に減らしているため取り返せない。
     *
     * <p><b>⚠️ 残りのチャージには触らない</b> (2026-07-31 レビュー指摘#6 の是正)。以前はここで
     * {@code setFuelLevel(0)} していたが、バニラはブレイズパウダー1個を<b>20チャージ</b>にまとめて
     * 充填するので、<b>正当な所有者がログアウトを挟むだけで最大19醸造分が無言で消えていた</b>
     * (javadoc は「1周分だけ失われる」と書いてあり実挙動と食い違っていた)。
     *
     * <p>チャージを残すとこの周回は繰り返すが、<b>止まることは保証されている</b>:
     * {@link #onBrewingStandFuel} が補給を拒否するので、残りチャージ(1個のブレイズパウダーで最大20)を
     * 使い切った時点で必ず停止する(最長 20 周 × 400tick = 400秒)。しかもその間に所有者が戻る/
     * 解放条件が満たされれば、<b>次の周回はそのまま完成する</b>(0 にしていた頃は再点火が必要だった)。
     * 未解放のまま空回りするのは所有者自身の燃料だけで、ポーションは1本も出ないので悪用価値は無い。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBrewingStart(BrewingStartEvent event) {
        BrewerInventory brew = BrewStandOwners.inventoryOf(event.getBlock());
        if (brew == null || !isLockedBrew(brew)) {
            return;
        }
        plugin.getLogger().log(java.util.logging.Level.FINE,
                () -> "[brew-unlocks] a brewing stand started a locked gated brew: it will spin down on its "
                        + "remaining fuel charges (refuelling is refused) and produce nothing");
    }

    /** 未解放プレイヤーによる投入をクリック経路で弾く(クラスjavadoc「投入自体を弾く」参照)。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewerClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory brew)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack moving = insertedStack(event, brew);
        if (moving == null || !isLockedInsertion(brew, player, moving)) {
            return;
        }
        event.setCancelled(true);
        player.sendActionBar(LOCKED_MESSAGE);
    }

    /** ドラッグ配布経路。醸造台側のスロットに1つでも配られるなら、カーソルの中身を判定する。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewerDrag(InventoryDragEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory brew)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean touchesStand = event.getRawSlots().stream().anyMatch(raw -> raw < brew.getSize());
        if (!touchesStand) {
            return;
        }
        ItemStack moving = event.getOldCursor();
        if (moving == null || !isLockedInsertion(brew, player, moving)) {
            return;
        }
        event.setCancelled(true);
        player.sendActionBar(LOCKED_MESSAGE);
    }

    /**
     * ホッパー経由の投入。操作者がいないので、判定は<b>スタンドに記録された所有者</b>だけで行う
     * (クラスjavadocの「ホッパー自動化への影響」参照)。所有者が未記録/オフラインなら弾く。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        Inventory dest = event.getDestination();
        if (!(dest instanceof BrewerInventory brew)) {
            return;
        }
        ItemStack moving = event.getItem();
        if (moving == null || !isLockedInsertion(brew, null, moving)) {
            return;
        }
        event.setCancelled(true);
        // 「ホッパーが動かない」の原因を運営が追えるようにする(クラスjavadoc の詰まり挙動)。
        plugin.getLogger().log(java.util.logging.Level.FINE,
                () -> "[brew-unlocks] refused hopper insertion of " + moving.getType()
                        + " into a brewing stand: its recorded owner does not hold the unlock");
    }

    /**
     * クリック操作が「醸造台へアイテムを入れる」ものなら、その入るスタックを返す(それ以外は null)。
     *
     * <p><b>注意</b>: {@code getAction()} は Mockito の既定値が {@code null} になる。
     * ここを通るテストは必ず action を明示的にスタブすること(このリポジトリの既知の罠)。
     */
    private static ItemStack insertedStack(InventoryClickEvent event, BrewerInventory brew) {
        InventoryAction action = event.getAction();
        if (action == null) {
            return null;
        }
        boolean clickedStand = event.getClickedInventory() == brew;
        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR ->
                    clickedStand ? event.getCursor() : null;
            // 醸造台以外(=プレイヤーインベントリ)からのシフトクリックが「入れる」側。
            case MOVE_TO_OTHER_INVENTORY -> clickedStand ? null : event.getCurrentItem();
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> clickedStand
                    ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                    : null;
            default -> null;
        };
    }

    /**
     * この投入を弾くべきか。判定は<b>「この投入でゲート対象の組み合わせが成立してしまうか」</b>
     * (2026-07-31 D10 レビュー指摘#1(a)):
     * <ul>
     *   <li>入れるものがポーション瓶なら、上段に載っている素材と組んで成立するかを見る
     *       (<b>「素材 → ビン」の順序で抜けていた穴</b>)。</li>
     *   <li>入れるものが素材なら、下段に載っているビンと組んで成立するかを見る。</li>
     * </ul>
     * こうすると「{@code THICK + SUGAR} をゲートしているためにバニラの俊敏のポーション
     * ({@code AWKWARD + SUGAR})まで作れない」という誤爆も起きない — ベースが違えば成立しないため。
     *
     * <p>成立してしまう組み合わせのうち<b>1つでも解放している</b>なら投入を許し、その操作者を
     * スタンドの所有者として記録する(以降の完成時判定とホッパー投入はこの所有者で行う)。
     *
     * <p><b>判定するのは「操作者」であって「記録済み所有者」ではない</b>(クリック/ドラッグ経路)。
     * ここを記録済み所有者にすると<b>未解放プレイヤーが解放済み所有者の台へ手で対象素材を入れられる</b>
     * (= ゲートが実質無効になる)。ホッパー経路だけは操作者がいないので記録済み所有者で判定する。
     * この優先順位は {@code BrewUnlockIngredientGateTest} が、actor と owner の解放状態を
     * <b>別々に</b>スタブしたテストで固定している(レビュー指摘#9)。
     *
     * @param actor クリック/ドラッグの操作者。ホッパー経路は {@code null}(記録済み所有者で判定する)。
     */
    private boolean isLockedInsertion(BrewerInventory brew, Player actor, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        List<MixPlan> completed = completedGates(brew, stack);
        if (completed.isEmpty()) {
            return false;
        }
        Player judged = actor != null ? actor : resolveOwner(brew);
        for (MixPlan plan : completed) {
            if (holdsUnlock(judged, plan)) {
                if (actor != null) {
                    claimOwnership(brew, actor, completed);
                }
                return false; // 解放済み: 通常どおり投入できる
            }
        }
        return true;
    }

    /**
     * 投入を許した操作者を所有者として記録する。<b>所有権は先着優先で、現所有者がその組み合わせを
     * 実際に成立させられる間は移らない</b>(クラスjavadoc「所有権の移り方」/ レビュー指摘#5)。
     *
     * <p>差し替えを完全に禁じると、<b>未解放プレイヤーが空の台へ1回投入して所有者になるだけで
     * その台のゲート付き醸造を永久に止められる</b>(燃料も受け付けなくなる)。逆に無条件で差し替えると
     * 「解放済みの第三者が1回投入 → ログアウト」で他人の醸造を止められる。両方を閉じる条件が
     * 「現所有者がオンラインかつ解放しているなら動かさない」。
     */
    private void claimOwnership(BrewerInventory brew, Player actor, List<MixPlan> completed) {
        Player recorded = resolveOwner(brew);
        if (recorded != null && Objects.equals(recorded.getUniqueId(), actor.getUniqueId())) {
            return; // 既に自分が所有者(毎クリック PDC を書き直さない)
        }
        boolean recordedCanBrewIt = false;
        for (MixPlan plan : completed) {
            if (holdsUnlock(recorded, plan)) {
                recordedCanBrewIt = true;
                break;
            }
        }
        if (recordedCanBrewIt) {
            return; // 正当な所有者が現に使えている台は奪わせない
        }
        if (owners.ownerOf(brew).isPresent()) {
            owners.replace(brew, actor);
        } else {
            owners.remember(brew, actor);
        }
    }

    /**
     * {@code stack} を入れたあとのスタンドで成立してしまうゲート対象 spec。
     * 素材側とビン側の<b>両方</b>を見るので投入順序に依存しない。
     */
    private List<MixPlan> completedGates(BrewerInventory brew, ItemStack stack) {
        List<MixPlan> out = new ArrayList<>();
        boolean incomingIsBottle = BrewRecipeSupport.isPotionContainer(stack.getType());
        PotionMeta incomingMeta = incomingIsBottle && stack.getItemMeta() instanceof PotionMeta meta
                ? meta : null;
        ItemStack loadedIngredient = brew == null ? null : brew.getIngredient();
        for (MixPlan plan : gatedMixes.get()) {
            BrewPotionSpec spec = plan.spec();
            // ビンとして入る解釈(上段の素材と組む)
            boolean asBottle = incomingMeta != null
                    && BrewRecipeSupport.matchesBase(incomingMeta, spec.base())
                    && loadedIngredient != null
                    && BrewRecipeSupport.matchesIngredient(loadedIngredient, spec.ingredient());
            // 素材として入る解釈(下段のビンと組む)。両方を見るのは、素材にポーションを指定した
            // config でも取りこぼさないため(通常の config では一方しか成立しない)。
            boolean asIngredient = BrewRecipeSupport.matchesIngredient(stack, spec.ingredient())
                    && bottleBaseLoaded(brew, spec.base());
            if (asBottle || asIngredient) {
                out.add(plan);
            }
        }
        return out;
    }

    /**
     * このスタンドに<b>今載っている</b>組み合わせが「ゲート対象なのに所有者が解放していない」状態か
     * ({@link #onBrewingStandFuel} / {@link #onBrewingStart} の判定)。
     */
    private boolean isLockedBrew(BrewerInventory brew) {
        ItemStack ingredient = brew.getIngredient();
        if (ingredient == null || ingredient.getType().isAir()) {
            return false;
        }
        Player owner = resolveOwner(brew);
        boolean gated = false;
        for (MixPlan plan : gatedMixes.get()) {
            if (!BrewRecipeSupport.matchesIngredient(ingredient, plan.spec().ingredient())
                    || !bottleBaseLoaded(brew, plan.spec().base())) {
                continue;
            }
            gated = true;
            if (holdsUnlock(owner, plan)) {
                return false;
            }
        }
        return gated;
    }

    /** 指定 base のビンがスタンドの下段(0..2)に入っているか。 */
    private static boolean bottleBaseLoaded(BrewerInventory brew, String baseName) {
        if (brew == null) {
            return false;
        }
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = brew.getItem(slot);
            if (bottle != null && bottle.getItemMeta() instanceof PotionMeta meta
                    && BrewRecipeSupport.matchesBase(meta, baseName)) {
                return true;
            }
        }
        return false;
    }

    /** スタンドに記録された所有者のうち<b>オンラインのもの</b>。未記録/オフラインなら {@code null}。 */
    private Player resolveOwner(BrewerInventory brew) {
        UUID uuid = owners.ownerOf(brew).orElse(null);
        return uuid == null ? null : owners.online(uuid);
    }

    private boolean holdsUnlock(Player player, MixPlan plan) {
        return player != null && dedicatedEffects.isActive(player, GATE_PREFIX + plan.groupId());
    }

    private static boolean hasMatchingBottleBase(BrewerInventory inv, List<ItemStack> results,
                                                 String baseName) {
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = inv.getItem(slot);
            PotionMeta probe = bottle != null && bottle.getItemMeta() instanceof PotionMeta pm ? pm : null;
            if (probe == null && slot < results.size() && results.get(slot) != null
                    && results.get(slot).getItemMeta() instanceof PotionMeta rm) {
                probe = rm;
            }
            if (probe != null && BrewRecipeSupport.matchesBase(probe, baseName)) {
                return true;
            }
        }
        return false;
    }

    private static PotionMeta potionMetaForSlot(BrewerInventory inv, List<ItemStack> results, int slot) {
        ItemStack bottle = inv.getItem(slot);
        if (bottle != null && bottle.getItemMeta() instanceof PotionMeta meta) {
            return meta;
        }
        if (slot < results.size()) {
            ItemStack result = results.get(slot);
            if (result != null && result.getItemMeta() instanceof PotionMeta meta) {
                return meta;
            }
        }
        return null;
    }
}
