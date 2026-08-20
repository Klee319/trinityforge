package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * 醸造が「進捗は完走するのに中身が変わらない」型の報告を、推測なしで確定させるための診断計装
 * (2026-08-19 / W-112 実サーバ報告「奇妙なポーションを作ろうとすると、完成してもネザーウォートが
 * 消費されず水入り瓶のまま。ただし経験値は入る。ブレイズパウダーは消費される」)。
 *
 * <h2>なぜ計装が要るのか</h2>
 * この報告の症状は<b>2つに1つ</b>しか説明が無い:
 * <ol>
 *   <li><b>{@link BrewEvent} がキャンセルされた</b> — {@code doBrew} は結果を書かず素材も
 *       {@code shrink} しないので「ビンも素材もそのまま」がまとめて説明できる。ただし
 *       {@link NativeSkillExperienceListener#onBrew} は {@code MONITOR} かつ
 *       {@code ignoreCancelled = true} なので<b>EXP は入らないはず</b>で、報告と食い違う。</li>
 *   <li><b>キャンセルされておらず、結果が本当に元のビンと同じだった</b> — この場合 EXP は説明できるが、
 *       素材が減らない説明は {@link BrewIngredientSaveListener} の確率(既定 10〜20%)しか無く、
 *       「毎回そうなる」という報告とは噛み合わない。</li>
 * </ol>
 * 静的解析ではどちらにも決められない(TF 側の5本のハンドラ・登録済み customMix 15件・
 * 配備 config・配備 jar・全ログの例外を突き合わせても、素の水入り瓶＋ネザーウォートに
 * 触る経路が1本も無い)。<b>そこで「どの優先度でキャンセルされたか」を実測する。</b>
 *
 * <h2>読み方</h2>
 * 出力の {@code cancel@} は各優先度に入った時点のキャンセル状態。
 * 例えば {@code LOWEST=no LOW=no NORMAL=no HIGH=yes} なら、キャンセルしたのは
 * <b>NORMAL と HIGH の間</b>に登録されたハンドラ ({@link BrewUnlockListener#onBrew} が NORMAL、
 * {@link CatalogVanillaOperationGuardListener#onBrew} と {@link PotionQualityListener#onBrew} が HIGH)。
 * 全部 {@code no} なら誰もキャンセルしておらず、原因は TF の外(Paper の mix 解決かクライアント表示)。
 *
 * <h2>ログを溢れさせない</h2>
 * <b>「怪しい醸造」だけ</b>を出す: キャンセルされたか、あるいは<b>結果のベースが入力のベースと
 * 同じまま</b>(＝何も変換されていない)のときだけ 1 行出す。正常な醸造では 1 行も出ない。
 */
public final class BrewDiagnosticListener implements Listener {

    private final Plugin plugin;

    /**
     * 直近の {@link BrewEvent} の観測。BrewEvent のディスパッチはメインスレッドで同期・不可分に
     * 走りきる(1つのイベントの LOWEST〜MONITOR の間に別の BrewEvent は挟まらない)ので、
     * イベントごとの Map を持たずフィールド1本で足りる。
     */
    private List<String> trace = new ArrayList<>();
    private List<String> inputs = new ArrayList<>();
    private boolean cancelledAnywhere;

    public BrewDiagnosticListener(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void atLowest(BrewEvent event) {
        trace = new ArrayList<>();
        inputs = new ArrayList<>();
        cancelledAnywhere = false;
        BrewerInventory inv = event.getContents();
        for (int slot = 0; slot < 3; slot++) {
            inputs.add(describe(inv.getItem(slot)));
        }
        // 素材は getIngredient() ではなく getItem(3) で読む(PotionQualityListener と同じ理由:
        // MockBukkit は素材未設定のとき getIngredient() が例外を投げる)。
        inputs.add("ingredient=" + describe(inv.getItem(3)));
        record(event, "LOWEST");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void atLow(BrewEvent event) {
        record(event, "LOW");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void atNormal(BrewEvent event) {
        record(event, "NORMAL");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void atHigh(BrewEvent event) {
        record(event, "HIGH");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void atHighest(BrewEvent event) {
        record(event, "HIGHEST");
    }

    /**
     * 最後の観測。ここまでの経過と結果を突き合わせ、<b>怪しいときだけ</b>1行出す。
     *
     * <p>この時点の {@code getResults()} は「バニラが解決した結果 + TF の各ハンドラが書き換えた後」で、
     * キャンセルされていなければ<b>直後にそのままスロットへ書かれる</b>値。つまりここに出る値が
     * プレイヤーの手に入るものと一致する。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void atMonitor(BrewEvent event) {
        record(event, "MONITOR");
        List<ItemStack> results = event.getResults();
        List<String> outputs = new ArrayList<>();
        for (int slot = 0; slot < 3; slot++) {
            outputs.add(describe(slot < results.size() ? results.get(slot) : null));
        }
        if (!isSuspicious(cancelledAnywhere, inputs, outputs)) {
            return;
        }
        String message = "[brew-diag] 変換されなかった醸造を観測: cancel@[" + String.join(" ", trace)
                + "] in=" + inputs + " out=" + outputs;
        plugin.getLogger().log(Level.WARNING, message);
    }

    private void record(BrewEvent event, String priority) {
        boolean cancelled = event.isCancelled();
        cancelledAnywhere |= cancelled;
        trace.add(priority + "=" + (cancelled ? "yes" : "no"));
    }

    /**
     * 出す価値があるか。<b>キャンセルされた</b>か、<b>どのビン枠も変換されていない</b>ときだけ true。
     *
     * <p>「変換されていない」は入力と出力の記述が一致することで見る。ベース種別・効果数・表示名まで
     * 含めた記述なので、バニラの水→奇妙のような正常な変換は必ず差が出る。
     *
     * @param inputs 0..2 がビン枠、末尾が素材(比較対象から外す)
     */
    static boolean isSuspicious(boolean cancelled, List<String> inputs, List<String> outputs) {
        if (cancelled) {
            return true;
        }
        boolean sawBottle = false;
        for (int slot = 0; slot < 3 && slot < inputs.size() && slot < outputs.size(); slot++) {
            String in = inputs.get(slot);
            if ("empty".equals(in)) {
                continue;
            }
            sawBottle = true;
            if (!in.equals(outputs.get(slot))) {
                return false; // 1本でも変換されていれば正常な醸造として扱う
            }
        }
        return sawBottle;
    }

    /** ログ1行に収まる粒度の記述。中身が変わったかを見分けられるだけの情報を入れる。 */
    @SuppressWarnings("deprecation") // hasCustomModelData(): カタログ判定側が同じ旧APIで見ているので合わせる
    static String describe(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return "empty";
        }
        StringBuilder out = new StringBuilder(stack.getType().name());
        out.append('x').append(stack.getAmount());
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof PotionMeta potion) {
            out.append("{base=")
                    .append(potion.hasBasePotionType() && potion.getBasePotionType() != null
                            ? potion.getBasePotionType().name() : "none")
                    .append(",effects=").append(potion.getCustomEffects().size())
                    .append('}');
        }
        if (meta != null && meta.hasDisplayName()) {
            out.append("+name");
        }
        if (meta != null && meta.hasCustomModelData()) {
            // カタログ判定(CatalogVanillaOperationGuardListener)は CustomModelData の有無で決まるので、
            // 「素のはずの水入り瓶に CMD が付いている」型の原因を見分けられるようにする。
            out.append("+cmd");
        }
        return out.toString();
    }
}
