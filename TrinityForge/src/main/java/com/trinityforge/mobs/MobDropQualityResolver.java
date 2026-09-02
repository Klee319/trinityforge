package com.trinityforge.mobs;

import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.stats.CraftQualityPolicy;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.PlayerMobDropBonusSource;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 討伐ドロップの品質を1箇所で決めるための共有ロジック (2026-08-19 / W-130)。
 *
 * <h2>なぜ切り出したか(実バグ)</h2>
 * 討伐ドロップを積むリスナーは3本あり、そのうち<b>カスタムアイテム経路だけが品質を決めていなかった</b>。
 * 3本とも {@code CrossPluginItemResolver#create(String)} の1引数版を呼んでいて、この版は
 * <pre>return create(id, ThreadLocalRandom.current().nextLong(), 0);</pre>
 * と<b>品質を 0 に固定</b>する。0 は最低品質(劣悪)なので、スレッドのような品質付きカスタム品は
 * モブのレベルにも {@code mob_drop_quality} ステにも一切反応せず、<b>必ず劣悪で落ちていた</b>
 * (実サーバ報告「劣悪しか落ちない」)。バニラ材質の装備ドロップだけは
 * {@code MobTypeDropListener#resolveQuality} が正しく品質を振っていたので、
 * 「効いている場合もある」ぶん余計に気づきにくい。
 *
 * <p>同じ式を3本へ書き写すと必ずどれかがずれるので、<b>式はここだけ</b>に置いて
 * 3本のリスナーはこれを呼ぶ。乱数は {@link RandomGenerator} で受けるので、
 * 呼び出し側が持っている {@code SplittableRandom} をそのまま渡せる(乱数源を増やさない)。
 *
 * <h2>品質の決まり方</h2>
 * <ol>
 *   <li>{@code craft-quality.drop} が無効なら、0〜最大品質の一様乱数(従来どおり)。</li>
 *   <li>有効なら「モブレベルから決まる中心値 + {@code mob_drop_quality} ぶんの底上げ
 *       + そのアイテムの品質基準値({@code quality-mode-offset})」を中心に、
 *       クラフト/釣りと同じ split-normal で1つ引く。</li>
 * </ol>
 */
public final class MobDropQualityResolver {

    private final CraftQualityConfig craftQuality;
    private final QualityConfig quality;
    /** 未配線可。{@code null} なら品質基準値(quality-mode-offset)を 0 として扱う。 */
    private final ItemStatsConfig itemStats;
    /** 未配線可。{@code null} なら {@code mob_drop_quality} による底上げ無し。 */
    private final PlayerMobDropBonusSource mobDropBonus;
    /** Optional builder used to stamp plain-material equipment drops at death time. */
    private volatile ItemFactory itemFactory;

    public MobDropQualityResolver(CraftQualityConfig craftQuality, QualityConfig quality,
                                  ItemStatsConfig itemStats, PlayerMobDropBonusSource mobDropBonus) {
        this.craftQuality = Objects.requireNonNull(craftQuality, "craftQuality");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.itemStats = itemStats;
        this.mobDropBonus = mobDropBonus;
    }

    /** Supplies the shared item builder for non-custom equipment drops. */
    public void setItemFactory(ItemFactory itemFactory) {
        this.itemFactory = itemFactory;
    }

    /**
     * {@code mob_drop_quality} ステによる品質モードの底上げ。呼び出し側が同じキルで複数個ドロップする
     * ときに1回だけ引いて使い回せるよう、公開している(1キルの中で個体差が出ないようにするため)。
     */
    public int bonusMode(Player killer, RandomGenerator rng) {
        if (mobDropBonus == null || killer == null) {
            return 0;
        }
        return mobDropBonus.qualityModeBonus(killer, rng.nextDouble());
    }

    /**
     * 討伐ドロップ1個ぶんの品質。
     *
     * @param mobLevel   討伐したモブの戦闘レベル
     * @param bonusMode  {@link #bonusMode(Player, RandomGenerator)} の結果
     * @param material   ドロップするアイテムの素材(品質基準値の引き当てに使う)
     * @param customModelData カスタムアイテムなら その CustomModelData、バニラ素材なら {@code null}
     */
    public int resolve(int mobLevel, int bonusMode, Material material, Integer customModelData,
                       RandomGenerator rng) {
        int maxQuality = Math.max(0, quality.maxQuality());
        if (!craftQuality.dropEnabled()) {
            return rng.nextInt(maxQuality + 1);
        }
        int offset = itemStats == null || material == null
                ? 0
                : itemStats.qualityModeOffsetFor(material, customModelData);
        int mode = CraftQualityPolicy.modeFromLevel(mobLevel, craftQuality.dropStrengthPerQuality(),
                craftQuality.dropBaseQuality()) + Math.max(0, bonusMode) + offset;
        return CraftQualityPolicy.resolveDropQuality(mode, rng.nextGaussian(),
                quality.spreadUp(), quality.spreadDown(), maxQuality);
    }

    /**
     * Stamps a plain-material drop while the killer and mob level are still known. This closes the
     * path where an unmarked stack reached {@code PickupQualityListener} and was rolled using the
     * pickup player's 開運 instead of the defeated mob's level/drop-quality values.
     */
    public ItemStack stampPlainDrop(ItemStack stack, int mobLevel, int bonusMode,
                                    RandomGenerator rng) {
        if (stack == null || rng == null || itemFactory == null || !itemFactory.qualityVaries(stack)) {
            return stack;
        }
        int quality = resolve(mobLevel, bonusMode, stack.getType(), null, rng);
        itemFactory.stamp(stack, rng.nextLong(), quality);
        return stack;
    }

    /**
     * {@code custom:<id>} ドロップに品質を乗せ直す。品質付きでないアイテムや、resolver が未配線の
     * ときは受け取ったスタックをそのまま返す(fail-open —— 品質が付かないより「落ちない」ほうが悪い)。
     *
     * <p><b>2回組むのは品質基準値({@code quality-mode-offset})の引き当てに素材と
     * CustomModelData が要るため。</b>カタログIDの段階では分からないので、まず品質0で組んで
     * 実物から読み、決まった品質で同じ {@code rollSeed} を使って組み直す。
     * seed を共有しているので、品質以外のランダムロール(ステの上振れ)は1回目と同じ結果になる。
     *
     * @param built 品質0で組んだ現物(この呼び出しの前に必ず解決済みであること)
     */
    @SuppressWarnings("deprecation") // hasCustomModelData(): item-stats 側の引き当てが同じ旧APIで見ている
    public static ItemStack stamped(MobDropQualityResolver resolver,
                                    com.trinityforge.stats.CrossPluginItemResolver itemResolver,
                                    String catalogId, long rollSeed, ItemStack built,
                                    int mobLevel, int bonusMode, RandomGenerator rng) {
        if (resolver == null || itemResolver == null || built == null) {
            return built;
        }
        org.bukkit.inventory.meta.ItemMeta meta = built.getItemMeta();
        Integer cmd = meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : null;
        int rolled = resolver.resolve(mobLevel, bonusMode, built.getType(), cmd, rng);
        if (rolled <= 0) {
            return built;
        }
        return itemResolver.create(catalogId, rollSeed, rolled).orElse(built);
    }
}
