package com.trinityforge.combat;

import java.util.List;
import java.util.Locale;

/**
 * 敵の「特殊攻撃」1件のテンプレート（{@code combat/mob-abilities.yml}、2026-07-31）。
 *
 * <p><b>なぜ作ったか</b>: 敵の違いが「HPと攻撃力の数値だけ」で、攻撃手法も演出も全モブ共通だった。
 * {@code combat/mob-overrides.yml} が持てるのは {@code stats} / {@code drops} / {@code vanilla-exp} /
 * {@code display-name} の4つだけで、<b>攻撃のバリエーションを設定で足す口が1つも無かった</b>。
 * EliteMobs 側の powers はサーバの {@code plugins/EliteMobs/custombosses} にしか無く、
 * このリポジトリからは触れない（フォークのソースも配布物に含まれない）。
 * そこで TF 側に汎用の攻撃プリミティブを置き、<b>どのモブにも yml で付け外しできる</b>形にした。
 *
 * <p>テンプレートは「型 + パラメータ」の組で、モブ側は {@code abilities: [id, ...]} で参照するだけ。
 * 396 体のモブに個別の攻撃を書き下すのではなく、少数のテンプレートを組み合わせて表情を出す。
 *
 * @param id              テンプレートID（yml のキー）
 * @param displayName     発動時に周囲へ出す技名（空なら無表示）。MiniMessage 可
 * @param type            攻撃の型
 * @param damageType      {@code PHYSICAL} なら物理防御、{@code MAGICAL} なら魔法防御で受ける
 * @param damagePercent   モブの攻撃力に対する倍率（1.5 = 通常攻撃の1.5倍）。0 以下なら無ダメージ演出
 * @param cooldownSeconds 同じモブが再発動できるまでの秒数
 * @param chance          クールダウン明けの判定1回あたりの発動率 [0,1]
 * @param range           発動を試みる対象までの距離（これより遠い相手には撃たない）
 * @param radius          AoE 半径（{@code GROUND_SLAM} / {@code AURA} / {@code TELEPORT_STRIKE}）
 * @param count           発射数（{@code PROJECTILE_VOLLEY}）/ 召喚数（{@code SUMMON}）/ 刻み数（{@code BEAM}）
 * @param spreadDegrees   扇の開き角（{@code PROJECTILE_VOLLEY}）
 * @param projectile      飛ばす {@code EntityType} 名（{@code PROJECTILE_VOLLEY}）
 * @param summonType      召喚する {@code EntityType} 名（{@code SUMMON}）
 * @param durationSeconds 効果の持続秒数（{@code AURA}）／予告から着弾までの秒数（{@code DELAYED_ZONE}）
 * @param knockback       ノックバック強度（0 なら吹き飛ばさない）。{@code REPULSE} では吹き飛ばし、
 *                        {@code VORTEX_PULL} では<b>引き寄せ</b>の強さとして使う（向きが逆になるだけ）
 * @param effects         命中した相手へ付けるポーション効果
 * @param particle        演出パーティクル名（{@code Particle} enum、空なら無し）
 * @param particleCount   パーティクル個数。<b>0 は「出さない」ではなく Bukkit では特殊な意味になる</b>ので
 *                        1 未満は演出そのものをスキップする
 * @param sound           効果音名（{@code Sound} enum、空なら無音）
 */
public record MobAbility(String id, String displayName, Type type, DamageType damageType,
                         double damagePercent, double cooldownSeconds, double chance,
                         double range, double radius, int count, double spreadDegrees,
                         String projectile, String summonType, double durationSeconds,
                         double knockback, List<EffectSpec> effects,
                         String particle, int particleCount, String sound) {

    /** 攻撃の型。<b>enum を増やすと {@code MobAbilityExecutor} の switch がコンパイルエラーで教えてくれる。</b> */
    public enum Type {
        /** 自分の足元を中心にした全方位 AoE。近接の「読んで離れる」動きを作る。 */
        GROUND_SLAM,
        /** 対象方向へ扇状に投射物を撒く。遮蔽物を使う動きを作る。 */
        PROJECTILE_VOLLEY,
        /** 対象へ突進する（ダメージは接触時ではなく突進直後の周囲判定）。 */
        CHARGE,
        /** 一定時間、周囲に継続ダメージ/効果を撒く。立ち位置を強制する。 */
        AURA,
        /** 対象の背後へ転移して斬る。逃げ切りを許さない。 */
        TELEPORT_STRIKE,
        /** 自分の向きへ直線状に判定を伸ばす。横に避ける動きを作る。 */
        BEAM,
        /** 増援を呼ぶ。単体火力より範囲処理を要求する。 */
        SUMMON,
        /**
         * 全方位へ<b>強く吹き飛ばす</b>。ダメージより「位置を崩す」ことが本体で、
         * 水平だけでなく上へも飛ばすので落下・溶岩・崖が脅威になる。
         * {@code GROUND_SLAM} の knockback は「少し押す」程度の味付けなのに対し、
         * こちらは吹き飛ばしそのものが技（2026-08-16）。
         */
        REPULSE,
        /**
         * 周囲のプレイヤーを<b>自分の方へ引き寄せる</b>（{@code REPULSE} の逆向き）。
         * 遠距離で張り付かない立ち回りを崩し、近接の間合いへ引きずり込む。
         */
        VORTEX_PULL,
        /**
         * 対象の足元へ<b>印を置き、遅れて着弾</b>する。予告を見て動けば完全に避けられる代わりに
         * 倍率を高くできる（「避ける技」を作るのが目的で、避けられない高倍率とは別物）。
         */
        DELAYED_ZONE
    }

    /**
     * 命中時に付与するポーション効果。
     *
     * @param type            {@code PotionEffectType} 名
     * @param durationSeconds 持続秒数
     * @param amplifier       強さ（0 = レベルI）
     */
    public record EffectSpec(String type, double durationSeconds, int amplifier) {
        public EffectSpec {
            type = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
            durationSeconds = Math.max(0.0, durationSeconds);
            amplifier = Math.max(0, amplifier);
        }

        /** Bukkit の tick 単位。1 tick 未満は「付けない」。 */
        public int durationTicks() {
            return (int) Math.round(durationSeconds * 20.0);
        }
    }

    public MobAbility {
        id = id == null ? "" : id.trim();
        displayName = displayName == null ? "" : displayName.trim();
        // 上限を置く理由: yml の書き間違い(半径 100000 など)がサーバを止めるのを防ぐ。
        // 「静かに丸める」ことになるが、起動不能やフリーズよりは軽い。
        damagePercent = clamp(damagePercent, 0.0, 100.0);
        cooldownSeconds = clamp(cooldownSeconds, 0.5, 600.0);
        chance = clamp(chance, 0.0, 1.0);
        range = clamp(range, 1.0, 64.0);
        radius = clamp(radius, 0.0, 32.0);
        count = (int) clamp(count, 0, 64);
        spreadDegrees = clamp(spreadDegrees, 0.0, 360.0);
        projectile = projectile == null ? "" : projectile.trim().toUpperCase(Locale.ROOT);
        summonType = summonType == null ? "" : summonType.trim().toUpperCase(Locale.ROOT);
        durationSeconds = clamp(durationSeconds, 0.0, 60.0);
        knockback = clamp(knockback, 0.0, 5.0);
        effects = effects == null ? List.of() : List.copyOf(effects);
        particle = particle == null ? "" : particle.trim().toUpperCase(Locale.ROOT);
        particleCount = (int) clamp(particleCount, 0, 500);
        sound = sound == null ? "" : sound.trim().toUpperCase(Locale.ROOT);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** クールダウンをミリ秒で。 */
    public long cooldownMillis() {
        return (long) (cooldownSeconds * 1000.0);
    }

    /** 継続時間を tick で（{@code AURA}）。 */
    public int durationTicks() {
        return (int) Math.round(durationSeconds * 20.0);
    }

    /** {@code DELAYED_ZONE} の予告時間の既定（{@code duration-seconds} 未設定時）。 */
    public static final int DEFAULT_DELAY_TICKS = 30;
    /** 予告が短すぎると「見てから動く」余地が無くなるので下限を置く。 */
    public static final int MIN_DELAY_TICKS = 10;
    /** 予告が長すぎると誰も踏んでおらず当たらない置物になるので上限を置く。 */
    public static final int MAX_DELAY_TICKS = 100;

    /**
     * 予告から着弾までの遅延 tick（{@code DELAYED_ZONE}）。
     *
     * <p>{@code duration-seconds} を流用しているが、{@code AURA} の {@link #durationTicks()} と違って
     * <b>0（未設定）は「即着弾」ではなく既定 1.5 秒</b>にする。0 を即着弾にすると
     * 「予告付きで避けられる技」という型そのものが、書き忘れひとつで
     * 「回避不能な高倍率AoE」へ静かに化けるため。
     */
    public int delayTicks() {
        if (durationSeconds <= 0.0) {
            return DEFAULT_DELAY_TICKS;
        }
        long ticks = Math.round(durationSeconds * 20.0);
        return (int) Math.max(MIN_DELAY_TICKS, Math.min(MAX_DELAY_TICKS, ticks));
    }
}
