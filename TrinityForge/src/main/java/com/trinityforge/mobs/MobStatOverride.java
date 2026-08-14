package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;

/**
 * One "強さ" (strength) override layer for {@code combat/mob-overrides.yml} (2026-07-26 ダンジョン×
 * モブ単位オーバーライド新設): every field is nullable/boxed and means "leave the underlying
 * {@link MobProfile} value untouched" when absent, so an operator can override e.g. only
 * {@code max-health} without having to restate the whole profile. {@link #applyTo(MobProfile)} performs
 * this field-by-field merge; stacking two overrides (default layer then world-specific layer) is simply
 * two sequential {@link #applyTo} calls, each only touching the fields it carries.
 *
 * <p>{@link #physical()}/{@link #magical()}/{@link #attack()} are themselves partial (nested nullable
 * fields), mirroring the same key vocabulary {@code combat/mob-profiles.yml} uses
 * ({@link DefenseStats}/{@link AttackStats}), so an operator only writes the sub-fields they actually
 * want to change.
 *
 * <p><b>倍率キー(2026-08-14 「ダンジョンの難易度を敵の強さの差で表現する」要望)</b>:
 * {@link #maxHealthMultiplier()} / {@link #attackPowerMultiplier()} は絶対値ではなく
 * <b>「元の値の何倍にするか」</b>を書くキー。絶対値({@link #maxHealth()} 等)だけだと
 * {@code level: dynamic} のダンジョン(=プレイヤーが入場時にレベルを選ぶ 265 体)と両立しない ——
 * 絶対値で 1200 と書くと Lv1 でも Lv100 でも 1200 になり、選んだレベルへの追従が死ぬ。倍率なら
 * 「そのレベルで本来決まる強さの N 倍」という<b>相対的な強さ差</b>として難易度を表現できる。
 * 両キーとも {@code stats:} 直下に書く({@code armor-strength} と同じく、入れ子の中の1フィールドに
 * 効くが親階層に置くキー) —— 難易度は「HP 何倍・攻撃力何倍」の<b>対で</b>指定するものなので、
 * 2 つを別階層に散らさない。
 */
public record MobStatOverride(
        Integer level,
        Double maxHealth,
        Double maxHealthMultiplier,
        Double armorStrength,
        Double attackPowerMultiplier,
        DefenseFieldOverride physical,
        DefenseFieldOverride magical,
        AttackFieldOverride attack) {

    /** No fields set — {@link #applyTo(MobProfile)} is then a no-op copy. */
    public static final MobStatOverride EMPTY =
            new MobStatOverride(null, null, null, null, null, null, null, null);

    /**
     * 後方互換: 倍率キー(2026-08-14)を持たない旧 6 フィールド形。既存の呼び出し側(テスト含む)が
     * そのままコンパイルでき、倍率は未指定 = 完全な no-op になる。
     */
    public MobStatOverride(Integer level, Double maxHealth, Double armorStrength,
                           DefenseFieldOverride physical, DefenseFieldOverride magical,
                           AttackFieldOverride attack) {
        this(level, maxHealth, null, armorStrength, null, physical, magical, attack);
    }

    public boolean isEmpty() {
        return level == null && maxHealth == null && maxHealthMultiplier == null && armorStrength == null
                && attackPowerMultiplier == null && physical == null && magical == null && attack == null;
    }

    /**
     * Returns a copy of {@code base} with every non-null field of this override applied. Fields left
     * {@code null} here keep {@code base}'s value untouched (項目単位マージ、2026-07-26 mob-overrides
     * §2). {@link #armorStrength()} — if set — is applied identically to both {@link #physical()} and
     * {@link #magical()} components (mirrors {@link MobProfile}'s invariant that both components share
     * one armor-strength value).
     *
     * <p><b>絶対値と倍率の適用順序(2026-08-14、この順序は仕様)</b>:
     * <ol>
     *   <li><b>絶対値が先。</b> {@code max-health} / {@code attack.attack-power} が書かれていれば、
     *       まずその値で置き換える。</li>
     *   <li><b>倍率は後。</b> 1 の結果に {@code max-health-multiplier} /
     *       {@code attack-power-multiplier} を掛ける。つまり<b>絶対値と併記したら絶対値に倍率が掛かる</b>
     *       (例: {@code max-health: 1000} + {@code max-health-multiplier: 3} → 3000)。</li>
     *   <li><b>絶対値が無ければ元の値に掛かる。</b> EliteMobs のランプ(=プレイヤーが選んだレベル)や
     *       下位のオーバーライド層が決めた値がそのまま被乗数になる —— これが難易度差の本来の使い方。</li>
     *   <li><b>倍率が {@code null} なら掛け算そのものを行わない</b>(既存 config の挙動は 1 ミリも
     *       変わらない)。</li>
     * </ol>
     *
     * <p><b>複数レイヤーに書いたときは掛け合わさる(他フィールドの「後勝ち」とは違う)</b>:
     * {@code MobOverridesConfig#resolve} は default scope → default の mob 単位 → world scope →
     * world の mob 単位の順に {@code applyTo} を<b>4 回呼ぶ</b>ので、各層の倍率はそのつど掛かる
     * (default に 1.5、ダンジョンに 2.0 と書けば合計 3.0 倍)。倍率としてはこちらが自然で
     * 「全体を 1.5 倍にしたうえでこのダンジョンだけさらに 2 倍」が書けるが、裏を返すと
     * <b>{@code default} に倍率を書くと全ダンジョンに乗る</b> —— ダンジョン間の難易度差だけを
     * つけたいなら {@code default} 側には書かないこと。
     *
     * <p><b>ただし上位レイヤーの絶対値は下位レイヤーの倍率の結果ごと捨てる</b>(上記の順序 1 の定義
     * そのままだが、難易度設定では踏みやすいので明記する): scope 直下に
     * {@code max-health-multiplier: 4} と書いても、そのモブが {@code mobs.<id>.stats.max-health} で
     * 絶対値を持っていれば、4 倍して作った値は絶対値で上書きされて消える。
     * <b>ただしこれに当たるのは出荷 {@code combat/mob-overrides.yml} の 411 体のうちごく一部</b>
     * (2026-08-14 実測で 23 体 —— {@code default} スコープの新規カスタムボス 6 体、
     * {@code em_id_binder_of_worlds} の 7 体、{@code em_id_enchantment_challenge_*} の 10 体。
     * 残り 388 体は絶対値 HP を持たない)。つまり<b>ダンジョン単位の倍率はほぼ全モブにそのまま効く</b>
     * ので、倍率を入れるために per-mob の記述を触って回る必要は無い —— 手当てが要るのは
     * 絶対値を持つ数体だけ。
     *
     * <p><b>この件数は動くので、判断の根拠に使う前に必ず数え直すこと</b>(実際 2026-08-14 の作業中に
     * 13 → 23 体へ増えた)。数え方は {@code mobs.<id>.stats.max-health} の実在数 ——
     * {@code grep -c "max-health:" TrinityForge/src/main/resources/combat/mob-overrides.yml} は
     * コメントアウト行も拾うので、yml をパースして数えること。<b>「396 体が per-mob の絶対値を持つ」は
     * 誤り</b>(旧 javadoc の記述。信じると存在しない問題を回避するために 388 体を不要に触ることになる)。
     *
     * <p><b>【重要】TF の倍率の後段に EliteMobs 自身の {@code healthMultiplier} がもう一段掛かる。</b>
     * フォーク側の実体は
     * {@code fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/}
     * {@code mobconstructor/EliteEntity.java} の {@code setMaxHealth()}:
     * <b>408 行が {@code this.maxHealth = calculatedHealth * healthMultiplier;}</b> で、その
     * {@code calculatedHealth} は同 407 行で {@code TrinityForgeIntegration.resolveProfileMaxHealth()}
     * (= ここまでで解決した TF プロファイルの {@link MobProfile#maxHealth()}、倍率適用済み)に
     * 差し替えられている。正規化戦闘/フェーズリセット経路の {@code setNormalizedMaxHealth()} も
     * 同ファイル 427 行で同じ形。したがって
     * <b>実 HP = ランプ(選んだレベル) × TF の {@code max-health-multiplier} × EM の
     * {@code healthMultiplier}</b> になる。
     *
     * <p>この {@code healthMultiplier} は<b>モブごとにまったく揃っていない</b>: 2026-08-14 実測で、
     * {@code mob-overrides.yml} の 411 体のうち EliteMobs の custombosses ファイルに対応する 396 体を
     * 突き合わせると値は <b>0.0001 〜 120</b> に散っている(最頻は 1.0 が 116 体)。同一ダンジョン内でも
     * 揃っておらず、{@code em_id_binder_of_worlds} ではボス 4 体が 120、ミニボス 3 体が 1.0 ——
     * <b>同じスコープに倍率を 1 行書いても、実機での HP 差は元から 120 倍開いている</b>。
     * 「TF 側で HP を 2 倍にした」は実機の HP が一律 2 倍になったという意味には<b>ならない</b>ので、
     * 難易度を実測で語るときは必ず EM 側の {@code healthMultiplier} と併せて見ること。
     */
    public MobProfile applyTo(MobProfile base) {
        if (base == null || isEmpty()) {
            return base;
        }
        int newLevel = level != null ? level : base.level();
        // 1) 絶対値を先に適用 → 2) その結果に倍率を掛ける(倍率 null なら掛けない)。
        double newMaxHealth = multiplied(maxHealth != null ? maxHealth : base.maxHealth(), maxHealthMultiplier);
        DefenseStats newPhysical = mergeDefense(base.physical(), physical, armorStrength);
        DefenseStats newMagical = mergeDefense(base.magical(), magical, armorStrength);
        AttackStats newAttack = mergeAttack(base.attack(), attack);
        if (attackPowerMultiplier != null) {
            // attack-power(= AttackStats.defaultDamage)だけに掛ける。会心率や固定ダメージまで巻き込むと
            // 「難易度を上げたら会心率が 100% を超えた」のような別軸の破綻を作る。
            double scaled = multiplied(newAttack.defaultDamage(), attackPowerMultiplier);
            if (scaled != newAttack.defaultDamage()) {
                newAttack = newAttack.withDefaultDamage(scaled);
            }
        }
        return new MobProfile(base.id(), newLevel, base.dungeonTheme(), newPhysical, newMagical,
                newAttack, newMaxHealth, base.dynamic());
    }

    /**
     * 倍率の適用。{@code multiplier} が {@code null}・非有限・0 以下のときは<b>掛け算を行わず
     * {@code value} をそのまま返す</b>。
     *
     * <p>不正値をここでも弾いているのは二重の守り: 通常は {@code MobOverridesConfig} のパース時点で
     * 警告付きで捨てられる(そちらが一次の防波堤)が、万一 0 や負値が素通りすると
     * {@link MobProfile} のコンパクトコンストラクタが {@link IllegalArgumentException} を投げ、
     * {@code TrinityForgeSpawnListener} がそれを握り潰して<b>そのモブが TF 戦闘パイプラインから
     * 丸ごと無言で外れる</b>(2026-07-26 H3 で実際に踏んだ事故と同じ経路)。0 を「無視」にしているのは
     * それ以上に実害があるため —— {@code max-health} が 0 は「未設定 = EliteMobs 自身の HP を使う」、
     * {@code attack-power} が 0 は {@link MobProfile#hasAttack()} が false になり「TF の攻撃側を
     * 使わない」を意味するので、0 倍は「弱くする」ではなく<b>設定ごと消える</b>という真逆の結果になる。
     */
    private static double multiplied(double value, Double multiplier) {
        if (multiplier == null || !Double.isFinite(multiplier) || multiplier <= 0.0) {
            return value;
        }
        return value * multiplier;
    }

    private static DefenseStats mergeDefense(DefenseStats base, DefenseFieldOverride override,
                                              Double armorStrengthOverride) {
        if (override == null && armorStrengthOverride == null) {
            return base;
        }
        double defenseRate = override != null && override.defenseRate() != null
                ? override.defenseRate() : base.defenseRate();
        double resistance = override != null && override.resistance() != null
                ? override.resistance() : base.resistance();
        double damageReduction = override != null && override.damageReduction() != null
                ? override.damageReduction() : base.damageReduction();
        double flatDefense = override != null && override.flatDefense() != null
                ? override.flatDefense() : base.flatDefense();
        double armorStrength = armorStrengthOverride != null ? armorStrengthOverride : base.armorStrength();
        return new DefenseStats(defenseRate, resistance, damageReduction, flatDefense, armorStrength);
    }

    private static AttackStats mergeAttack(AttackStats base, AttackFieldOverride override) {
        if (override == null) {
            return base;
        }
        return new AttackStats(
                override.defaultDamage() != null ? override.defaultDamage() : base.defaultDamage(),
                override.flatBonusDamage() != null ? override.flatBonusDamage() : base.flatBonusDamage(),
                override.percentBonusDamage() != null ? override.percentBonusDamage() : base.percentBonusDamage(),
                override.critChance() != null ? override.critChance() : base.critChance(),
                override.critDamage() != null ? override.critDamage() : base.critDamage(),
                override.penetration() != null ? override.penetration() : base.penetration(),
                override.damageModifier() != null ? override.damageModifier() : base.damageModifier(),
                override.fixedDamage() != null ? override.fixedDamage() : base.fixedDamage(),
                override.magicRatio() != null ? override.magicRatio() : base.magicRatio());
    }

    /** Partial {@link DefenseStats} override; {@code armorStrength} lives on the parent record instead
     *  (shared across physical/magical, matching {@link MobProfile#armorStrength()}). */
    public record DefenseFieldOverride(Double defenseRate, Double resistance, Double damageReduction,
                                        Double flatDefense) {
    }

    /**
     * Partial {@link AttackStats} override. {@code magicRatio} (2026-08-02) can be set alone —
     * without also setting {@code defaultDamage} etc. — so an EliteMobs dungeon mob that has NO
     * TrinityForge {@code attack:} profile in {@code mob-profiles.yml} (the common case; it keeps
     * EliteMobs' own damage formula) can still be classified as a magic attacker. See
     * {@code MobProfile#hasAttack()}: {@code magicRatio} alone does not flip that flag, so setting
     * only this field does NOT divert the mob into TrinityForge's full attack-power-owned path.
     */
    public record AttackFieldOverride(Double defaultDamage, Double flatBonusDamage, Double percentBonusDamage,
                                       Double critChance, Double critDamage, Double penetration,
                                       Double damageModifier, Double fixedDamage, Double magicRatio) {
    }
}
