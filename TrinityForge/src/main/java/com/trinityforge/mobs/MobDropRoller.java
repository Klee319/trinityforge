package com.trinityforge.mobs;

/**
 * Pure, Bukkit-free drop-roll logic extracted out of {@code MobTypeDropListener} so the
 * probability/count math is unit-testable without a live {@code Random}: the caller supplies an
 * already-generated random sample and this class only does the deterministic decision/mapping.
 */
public final class MobDropRoller {

    /** No drop table needs a stack size beyond this; also the overflow-safety bound for {@code max}. */
    private static final int MAX_STACK_COUNT = 1_000_000;

    /**
     * 追加個数のスポーンループ防止。採取の {@code GatheringPolicy.MAX_EXTRA} と同じ 256。
     * プレイヤーステの設計上限ではない。
     */
    static final int MAX_EXTRA_COUNT = 256;

    private MobDropRoller() {
    }

    /**
     * Whether a roll with the given {@code chance} [0,1] hits, given a uniform sample in [0,1).
     * {@code chance <= 0} never hits, {@code chance >= 1} always hits (strict {@code <} comparison).
     */
    public static boolean rolls(double chance, double randomDouble0To1) {
        return randomDouble0To1 < chance;
    }

    /**
     * Picks a stack count in {@code [min, max]} inclusive from an arbitrary random int (any sign/
     * magnitude, e.g. {@code Random#nextInt()}). {@code min == max} always returns that value
     * regardless of the random input.
     *
     * <p>{@code range = max - min + 1} is computed as a {@code long} to avoid the int overflow
     * that {@code max - min + 1} would hit at {@code min=0, max=Integer.MAX_VALUE} (range would
     * wrap to {@code Integer.MIN_VALUE}, a negative modulus). {@code max} is additionally clamped
     * to {@link #MAX_STACK_COUNT} first — no real drop table needs a range anywhere near
     * {@code Integer.MAX_VALUE}, and clamping keeps the result a sane inventory stack size instead
     * of merely avoiding the crash.
     */
    public static int rollCount(int min, int max, int randomInt) {
        // max(min, ...) guards the pathological case min itself exceeds MAX_STACK_COUNT, which
        // would otherwise leave boundedMax < min and make the range negative.
        int boundedMax = Math.max(min, Math.min(max, MAX_STACK_COUNT));
        long range = (long) boundedMax - (long) min + 1L;
        long offset = Math.floorMod((long) randomInt, range);
        return (int) (min + offset);
    }

    /**
     * ドロップ増加ステ({@code mob_drop_bonus})の設計上限は {@code combat/stat-caps.yml}。
     * ここは負と非有限だけ潰す。+200% のハードコードはしない。
     *
     * <p>呼び出し側は {@code PlayerCombatAggregate#totalOf} 済みの値を渡すこと。
     * {@code totalOf} が stat-caps を適用したあと、ここでさらに切ると caps が空のときでも
     * +200% で頭打ちになる。
     */
    public static double clampBonus(double mobDropBonus) {
        if (!Double.isFinite(mobDropBonus)) {
            return 0.0;
        }
        return Math.max(0.0, mobDropBonus);
    }

    /**
     * <b>個数が1個固定のドロップ</b>(＝レアドロップ)へのボーナスの効かせ方: 個数ではなく
     * <b>抽選確率そのもの</b>を元の確率の {@code (1 + bonus)} 倍へ引き上げる。
     *
     * <p>2026-08-13 のユーザー指示による仕様。旧実装は個数にしか掛からなかったので、
     * 「1%で1個落ちるアイテム」にドロップ増加+100%を盛っても<b>遭遇率は1%のまま</b>で、
     * 当たったときの個数だけが増えていた(＝レアドロップには実質無意味だった)。
     * 新仕様では 1% → 2% になる。
     *
     * <p>ドロップ「率」なので 1.0(=100%) で頭打ちにする。元が 0 のものは 0 のまま
     * (落ちない設定のものを落ちるようにはしない)。
     */
    public static double boostedChance(double chance, double mobDropBonus) {
        if (!(chance > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, chance * (1.0 + clampBonus(mobDropBonus)));
    }

    /**
     * <b>個数がランダムなドロップ</b>(および2個以上の固定個数)へのボーナスの効かせ方:
     * 抽選済みの個数へ<b>追加する個数</b>を返す。整数部は確定、端数はその確率で +1。
     *
     * <p>2026-08-13 のユーザー指示による仕様。+50% なら 50% の確率で1つ増える。
     * 100% を超えたら確定で1つ増えたうえで、超過分の確率でさらに1つ増える
     * (+150% → 確定+1、さらに50%で+1)。
     *
     * <p>旧実装は個数への<b>乗算</b>だったので、32個スタックに +100% を盛ると +32 個だった。
     * 新仕様は加算なので +1 個。この差は意図したもの(ユーザー指示)。
     *
     * <p>スポーンループ防止の天井は {@link #MAX_EXTRA_COUNT}（採取と同じ 256）。
     * プレイヤーステの設計上限は stat-caps 側。
     *
     * @param roll 0.0以上1.0未満の乱数。テストのために引数化している。
     */
    public static int extraCount(double mobDropBonus, double roll) {
        double bonus = clampBonus(mobDropBonus);
        int whole = (int) Math.floor(bonus);
        double fraction = bonus - whole;
        int extras = whole + (fraction > 0.0 && roll < fraction ? 1 : 0);
        return Math.min(MAX_EXTRA_COUNT, extras);
    }

    /**
     * ボーナス加算後の個数を安全な範囲へ収める(最低1個、上限は {@code maxStackSize × 8})。
     * 上限と下限は旧 {@code scaleCount} から変えていない。
     */
    public static int cappedCount(int amount, int maxStackSize) {
        int cap = Math.max(1, maxStackSize) * 8;
        return Math.min(cap, Math.max(1, amount));
    }

    /**
     * ドロップ定義の個数が「1個固定」か。{@link #boostedChance}(確率を上げる)と
     * {@link #extraCount}(個数を足す)のどちらを使うかの分岐に使う。
     */
    public static boolean isSingleFixed(int min, int max) {
        return min == 1 && max == 1;
    }

    /**
     * このドロップを<b>進行アイテム</b>(＝複数人ダンジョンで全員に1個ずつ配るべきもの)とみなすか。
     * 判定は<b>yml に書かれた素の {@code chance} が 1.0(確定ドロップ)かどうか</b>だけ。
     *
     * <p><b>なぜ確率で決めるのか(2026-08-18)。</b> 複数人でインスタンス化ダンジョンに潜ると、
     * TF の追加ドロップは EliteMobs の共有戦利品テーブル(need/greed)へ回り、
     * <b>1スタックにつき当選者が1人だけ</b>選ばれる。スレッド(chance 0.2)やガチャ券のような
     * ランダム報酬ならそれが正しい分配だが、{@code combat/mob-overrides.yml} の確定ドロップは
     * ダンジョン印・試練の鍵・かけらといった<b>全員が1個ずつ持っていないと詰むもの</b>しかない
     * (2人で試練1を倒すと試練2の鍵が1本しか出ず、片方が次へ入れなかった)。
     * 「確定で落ちる＝全員に配る前提で書かれている」という規約をそのまま判定にしている。
     *
     * <p>渡すのは<b>倍率を掛ける前の設定値</b>であること。ドロップ増加ステや低レベル狩りの
     * 倍率を掛けたあとの確率で判定すると、0.9 のランダム報酬がボーナスで 1.0 に到達した瞬間
     * 「全員に配る」へ化ける。
     */
    public static boolean isProgressionDrop(double configuredChance) {
        return configuredChance >= 1.0;
    }
}
