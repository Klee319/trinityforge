package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/special-rewards.yml}: 称号(titles) / パーティクル(particles) /
 * パーティクルシード(particle-seeds) のIDレジストリ (2026-07-23-stat-gate-overhaul §6.1/§6.7)。
 *
 * <p>参照方法の統一: ここで定義したIDはスキルツリー({@code reward:<id>} 動的ゲート)/ 図鑑報酬ティア
 * ({@code collection.yml reward-tiers.<id>.special})/ アチーブメント報酬
 * ({@code achievements.yml achievements.<id>.rewards.special}) の3箇所から共通参照される。本クラスは
 * 定義の読み込みのみ担い、保有/装備判定は {@code SpecialRewardService} が行う。
 *
 * <p>Malformed entries are skipped with a warning; the rest load. Snapshots swap atomically on reload.
 */
public final class SpecialRewardsConfig implements LoadableConfig {

    public static final String PATH = "progression/special-rewards.yml";

    /** @param id 称号ID @param display MiniMessage文字列(プレイヤー頭上に表示) */
    public record Title(String id, String display) {
    }

    /**
     * shape: 発生パターン (2026-08-25 / W-244 で 2 種 → 8 種)。
     *
     * <p><b>形状ごとに「効くパラメータ」が違う。</b>それぞれの javadoc に書いてあるものだけを読み、
     * 残りは無視する ── 全部の形状に全部のパラメータを効かせようとすると、
     * 「書いたのに何も変わらない」と「書いていないのに勝手に変わる」が同時に起きる。
     * どのパラメータが効くかは {@link Emission#of(Shape)} の既定値と対でしか意味を持たないので、
     * ここを増やしたら必ずそちらも足すこと。
     */
    public enum Shape {
        /** 中心のまわりに散らす雲(従来の aura)。{@code radius}=ばらつきの箱、{@code speed}=粒子の初速。 */
        AURA,
        /** 水平の輪(従来の circle)。{@code radius}=半径。<b>{@code speed}>0 で外向きに飛び散る</b>(円形拡散)。 */
        CIRCLE,
        /** 球の表面に均等配置。{@code radius}=半径。{@code speed}>0 で外向きへ射出。 */
        SPHERE,
        /** 中心から全方向へ射出(半径ゼロの爆発)。<b>{@code speed} が本体</b>で {@code radius} は使わない。 */
        BURST,
        /** 螺旋。{@code radius}=半径、{@code height}=高さ、{@code turns}=回転数。 */
        HELIX,
        /** 垂直の柱。{@code height}=高さ、{@code radius}=横方向のばらつき。 */
        PILLAR,
        /** 向いている方向へ薙ぐ弧。{@code radius}=半径、{@code arcDegrees}=開き角、{@code height}=傾き。 */
        ARC,
        /**
         * 足元から<b>後ろへ流れる軌跡</b>(2026-08-25)。{@code radius}=後ろへ伸ばす長さ、
         * {@code speed}=後ろへ流す速さ、{@code count}=点の数。
         *
         * <p>「後ろ」は<b>進んでいる向きの逆</b>で決まる(止まっているときだけ向いている方向の逆)。
         * 走った軌跡として見せるものなので、既定の高さ補正だけ他の形状と違い<b>足元寄り</b>になる
         * ({@link Emission#DEFAULT_TRAIL_Y_OFFSET})。
         */
        TRAIL,
        /** 1点だけ。{@code count} と {@code speed} しか効かない(最小構成)。 */
        POINT
    }

    /**
     * 発生パラメータ一式 (2026-08-25 / W-243・W-244)。{@code particles:} と {@code particle-seeds:} で
     * <b>同じ語彙・同じ既定値・同じ上限</b>を使うためにここへ集約している ── 画面ごとに別の意味を
     * 持たせると、片方で覚えた値がもう片方で通じなくなる。
     *
     * <p>上限は<b>クライアント保護</b>のためのもので、見た目の好みではない。粒子は per-viewer 送信
     * (人数ぶんパケットが出る)なので、1回の発生で数千個を撃つ設定は書けてはいけない。
     *
     * @param shape      発生パターン
     * @param count      1回の発生あたりの点の数
     * @param radius     半径/ばらつき(ブロック)。形状によって意味が変わる({@link Shape})
     * @param speed      粒子の初速(Bukkit の {@code extra})。0 = 動かない
     * @param height     垂直方向の伸び(ブロック)。HELIX / PILLAR / ARC で効く
     * @param turns      回転数。HELIX で効く
     * @param arcDegrees 開き角(度)。ARC で効く
     * @param yOffset    基準点からの高さ補正(ブロック)。負値可
     */
    public record Emission(Shape shape, int count, double radius, double speed,
                           double height, int turns, double arcDegrees, double yOffset) {

        /** プレイヤー基準の既定の高さ補正(腰から胸のあたり)。従来の {@code add(0, 1.0, 0)} と同値。 */
        public static final double DEFAULT_PLAYER_Y_OFFSET = 1.0;
        /** 当たった場所(ブロック中心/敵の胴)基準の既定の高さ補正。中心そのままなので 0。 */
        public static final double DEFAULT_IMPACT_Y_OFFSET = 0.0;
        /**
         * 1回の発生あたりの点の数の上限(クライアント保護)。
         *
         * <p><b>2026-08-25 に 400 から下げた。</b> 400 は「パケット数の上限」として見ると桁が違う ──
         * 輪・球・螺旋のような<b>座標が1点ずつ違う形状は、点の数だけ {@code spawnParticle} を呼ぶ</b>
         * (座標が違うものを1パケットに束ねる方法は Bukkit に無い)。しかも粒子は
         * {@code Player#spawnParticle} による per-viewer 送信なので、
         * <b>実際に飛ぶパケットは「点の数 × 近くに居る人数」</b>になる。
         * count 400 を 10 人が見ていると 1 回の発生で 4000 パケットで、これが interval-ticks ごとに出る。
         * 装備パーティクルは常時走る演出なので、ここは「見た目の好み」ではなく安全弁として持つ。
         */
        public static final int MAX_COUNT = 64;
        /**
         * {@link Shape#TRAIL} の既定の高さ補正。軌跡は<b>足元</b>から出るものなので、
         * 他の形状の既定(胸の高さ = {@link #DEFAULT_PLAYER_Y_OFFSET})では意味が変わってしまう。
         */
        public static final double DEFAULT_TRAIL_Y_OFFSET = 0.1;

        public Emission {
            shape = shape == null ? Shape.AURA : shape;
            count = clampInt(count, 0, MAX_COUNT);
            radius = clampDouble(radius, 0.0, 16.0);
            speed = clampDouble(speed, 0.0, 8.0);
            height = clampDouble(height, 0.0, 16.0);
            turns = clampInt(turns, 1, 16);
            arcDegrees = clampDouble(arcDegrees, 1.0, 360.0);
            yOffset = clampDouble(yOffset, -4.0, 8.0);
        }

        /**
         * その形状で「まず見栄えがする」既定値。<b>config に書かれていないキーはここから埋まる</b>ので、
         * 形状を切り替えただけで破綻しない(例: HELIX に height を書き忘れると高さ0の輪になる)。
         */
        public static Emission of(Shape shape) {
            Shape s = shape == null ? Shape.AURA : shape;
            // ⚠ 2026-08-25: 点の数を一段下げた。初版(sphere 24 / helix 24 / burst 20)は
            //   実機で「量が多すぎる」報告。1点ずつ座標が違う形状は点の数だけパケットが出るので、
            //   ここの既定値は見た目と負荷の両方を決めている(理由は MAX_COUNT の説明)。
            return switch (s) {
                case AURA -> new Emission(s, 8, 0.6, 0.0, 0.0, 1, 120.0, 0.0);
                case CIRCLE -> new Emission(s, 10, 1.0, 0.0, 0.0, 1, 120.0, 0.0);
                case SPHERE -> new Emission(s, 12, 0.8, 0.0, 0.0, 1, 120.0, 0.0);
                case BURST -> new Emission(s, 12, 0.0, 0.25, 0.0, 1, 120.0, 0.0);
                case HELIX -> new Emission(s, 12, 0.6, 0.0, 2.0, 3, 120.0, 0.0);
                case PILLAR -> new Emission(s, 10, 0.3, 0.0, 2.0, 1, 120.0, 0.0);
                case ARC -> new Emission(s, 10, 1.2, 0.0, 0.0, 1, 120.0, 0.0);
                case TRAIL -> new Emission(s, 6, 1.2, 0.05, 0.0, 1, 120.0, DEFAULT_TRAIL_Y_OFFSET);
                case POINT -> new Emission(s, 4, 0.0, 0.0, 0.0, 1, 120.0, 0.0);
            };
        }

        /** {@code shape}/{@code count}/{@code radius}/{@code yOffset} だけを指定する旧来ぶんの形。 */
        public static Emission legacy(Shape shape, int count, double radius, double yOffset) {
            Emission base = of(shape);
            return new Emission(base.shape(), count, radius, base.speed(),
                    base.height(), base.turns(), base.arcDegrees(), yOffset);
        }

        private static int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static double clampDouble(double value, double min, double max) {
            if (!Double.isFinite(value)) {
                return min;
            }
            return Math.max(min, Math.min(max, value));
        }
    }

    /** シードの粒子をどこに出すか (2026-08-25 / W-242)。 */
    public enum Anchor {
        /** 道具が実際に当たった場所(壊したブロックの中心 / 殴った敵の胴 / 矢の着弾点)。既定。 */
        IMPACT,
        /** プレイヤー自身(2026-08-25 より前の唯一の挙動)。演出をプレイヤーに固定したいとき用の逃げ道。 */
        PLAYER
    }

    /**
     * @param id            パーティクルID
     * @param particle      Bukkit Particle
     * @param emission      発生パラメータ一式
     * @param intervalTicks 発生間隔(tick)
     */
    public record ParticleEffect(String id, Particle particle, Emission emission, int intervalTicks) {

        /** 旧6値の呼び出し/テスト向け。{@code speed} 等はその形状の既定値で埋まる。 */
        public ParticleEffect(String id, Particle particle, int count, double radius,
                              int intervalTicks, Shape shape) {
            this(id, particle,
                    Emission.legacy(shape, count, radius, Emission.DEFAULT_PLAYER_Y_OFFSET),
                    intervalTicks);
        }

        public int count() {
            return emission.count();
        }

        public double radius() {
            return emission.radius();
        }

        public Shape shape() {
            return emission.shape();
        }
    }

    /**
     * @param id       パーティクルシードID
     * @param seedItem 合成素材(Material名 または {@code custom:<カタログID>})
     * @param particle Bukkit Particle
     * @param emission 発生パラメータ一式
     * @param anchor   発生位置の基準
     */
    public record ParticleSeed(String id, String seedItem, Particle particle, Emission emission,
                               String display, boolean clears, Anchor anchor) {

        /** 旧実装の見た目(半径0.3の雲)。既定を変えると既存シードの印象が変わる。 */
        static Emission legacySeedEmission(int count) {
            return Emission.legacy(Shape.AURA, count, 0.3, Emission.DEFAULT_IMPACT_Y_OFFSET);
        }

        /**
         * 通常のシード(表示名は ID、刻印を消すシードではない)。既存の呼び出しとテスト向けの短縮形。
         */
        public ParticleSeed(String id, String seedItem, Particle particle, int count) {
            this(id, seedItem, particle, count, null, false);
        }

        /** 旧6値の呼び出し/テスト向け。 */
        public ParticleSeed(String id, String seedItem, Particle particle, int count,
                            String display, boolean clears) {
            this(id, seedItem, particle, legacySeedEmission(count), display, clears, Anchor.IMPACT);
        }

        public int count() {
            return emission.count();
        }

        /**
         * lore / GUI に出す名前。{@code display} 未設定なら ID を返す
         * ── <b>行そのものを消さない</b>(ID のほうが「何も出ない」よりは辿れる)。
         */
        public String displayName() {
            return display == null || display.isBlank() ? id : display;
        }
    }

    /** {@link #titleNametagClearance()} の既定値。ネームタグ上端と称号行のあいだに空けるブロック数。 */
    private static final double DEFAULT_TITLE_NAMETAG_CLEARANCE = 0.4;
    /**
     * 余白の下限。<b>負値を許すのは 2026-08-24 から</b>(実サーバ報告「称号の y 座標をあと 0.3 ほど
     * 下げたい」)。それまでは負値を既定へ戻していたので、0 まで下げた時点で
     * <b>config からはそれ以上下げる手段が無かった</b>。
     *
     * <p>下限を -0.35 で止める理由: 称号の行もネームタグの行もおよそ 0.25 ブロックの高さがあるので、
     * -0.25 でちょうど同じ高さ、それより下げると<b>名前の下へ潜る</b>。0.1 ブロックぶんだけ
     * 潜ることは許す(実機で見て決めるための余地)が、そこから先は名前を完全に覆うので止める。
     * ⚠ 負にすると W-174「称号がネームタグを消す」に近づく。読みにくくなったら 0 へ戻すこと。
     */
    private static final double MIN_TITLE_NAMETAG_CLEARANCE = -0.35;
    /**
     * 称号の追従補間(tick)の既定。
     *
     * <p><b>3 の根拠</b>: クライアントは<b>プレイヤー本体の位置を約3tickかけて補間</b>する
     * (移動パケットが届くたびに補間をやり直す)。一方 {@link org.bukkit.entity.Display} の位置は
     * {@code teleport_duration} ぶんで補間される。ここを 1 にすると称号は<b>本体の描画より先に
     * 目的地へ着く</b>ので、走り出し・停止・方向転換のたびに頭からズレる。
     * 本体と同じ 3 にすると同じ補間の法則で動くので、他人を見たときのズレが最小になる。
     *
     * <p>⚠ {@code TEXT_DISPLAY} の {@code updateInterval} は <b>1</b>(稼働中の
     * paper-1.21.11 の {@code EntityType} を逆アセンブルして確認済み)。つまり位置更新は
     * 毎tick届いているので、「パケットが3tickに1回しか来ない」たぐいの遅れではない。
     *
     * <p>⚠ <b>自分の称号を F5(三人称)で見た場合の遅れは、ここを何にしても消えない。</b>
     * 自分の本体だけはクライアントが予測して即座に描くが、称号はサーバ由来なので
     * 必ず往復ぶん遅れる。他人から見た位置は合っている。
     */
    private static final int DEFAULT_TITLE_TELEPORT_DURATION_TICKS = 3;
    /** 補間の上限。これ以上長いと「まだ終わっていない補間」を上書きし続けて揺れて見える(W-135)。 */
    private static final int MAX_TITLE_TELEPORT_DURATION_TICKS = 10;

    private volatile Map<String, Title> titles = Map.of();
    private volatile Map<String, ParticleEffect> particles = Map.of();
    private volatile Map<String, ParticleSeed> particleSeeds = Map.of();
    private volatile double titleNametagClearance = DEFAULT_TITLE_NAMETAG_CLEARANCE;
    private volatile int titleTeleportDurationTicks = DEFAULT_TITLE_TELEPORT_DURATION_TICKS;
    // 孤児化した付与分の自動剥奪 (SpecialRewardPruner) の安全弁。既定true。壊れたYAMLを「全部未定義」と
    // 誤判定して全員の報酬を消し飛ばす事故を防ぐため、これがfalseの間はプルーナー自体を丸ごとスキップできる。
    private volatile boolean pruneOrphanedGrants = true;
    // 直近の load() 呼び出しが成功(true)だったか。SpecialRewardPruner はこれもゲート条件に含める —
    // YAML構文エラー/エントリ破損で load() が false を返した回に、たまたま titles/particles/
    // particleSeeds が空(または一部欠落)のまま prune を走らせて全員の報酬を消し飛ばす事故を防ぐ。
    // 起動直後(まだ一度も load() していない状態)は「未ロードでprune不可」ではなく安全側の true とする
    // (このクラスは load() を呼ばれて初めて意味を持つため、初期値がpruneの成否を左右することはない)。
    private volatile boolean lastLoadOk = true;

    public Map<String, Title> titles() {
        return titles;
    }

    public Map<String, ParticleEffect> particles() {
        return particles;
    }

    public Map<String, ParticleSeed> particleSeeds() {
        return particleSeeds;
    }

    /** {@code id} がいずれかのカテゴリ(titles/particles/particle-seeds)に定義済みか。 */
    public boolean isKnown(String id) {
        return id != null && (titles.containsKey(id) || particles.containsKey(id) || particleSeeds.containsKey(id));
    }

    /**
     * 称号の行(頭上の別行、{@link com.trinityforge.progression.TitleDisplayService})を、バニラの
     * ネームタグの<b>上端からさらに何ブロック上</b>に置くかの余白。既定 0.4。
     *
     * <p>この値が「余白」であって「足元からの高さ」ではないことが重要。旧実装はパッセンジャーの
     * マウント点からの相対オフセットを直接書いており、マウント点の実高さ(バニラ既定は
     * {@code 高さ×0.75} = 1.35)を計算に入れていなかったため、0.35 でも 0.75 でもネームタグ
     * (足元から {@code 高さ+0.5} = 2.3)に届かず<b>重なって名前を隠していた</b>のがバグ報告
     * 「称号を付けている人にネームタグが表示されなかった」の正体。現在は
     * {@code 高さ + 0.5 + この余白} という絶対座標へ毎tick追従させるので、マウント点という
     * 未知数が式から消えている。
     *
     * <p>{@code progression/special-rewards.yml} の {@code display.nametag-clearance} から設定し、
     * {@code /trinityforge reload} で次回の張り直しから反映される。
     */
    public double titleNametagClearance() {
        return titleNametagClearance;
    }

    /**
     * 称号の追従補間の長さ(tick)。{@code display.title-teleport-duration}、既定 3。
     *
     * <p>クライアントはプレイヤー本体の位置を約3tickかけて補間するので、称号も同じ長さにすると
     * 同じ法則で動いてズレが最小になる。1 にすると称号だけが先に目的地へ着くため、
     * 走り出し・停止・方向転換で頭からズレて見える。長くしすぎると「まだ終わっていない補間」を
     * 上書きし続けて揺れる(W-135)ので上限 10。0 は補間なし(20Hz で段階的に動く)。
     *
     * <p>{@code /trinityforge reload} で次tickから反映される(毎tick読み直している)。
     */
    public int titleTeleportDurationTicks() {
        return titleTeleportDurationTicks;
    }

    /**
     * true(既定) = このファイルから削除された報酬IDを、プレイヤーの保持分(付与リスト/装備欄)からも
     * 自動で取り除く({@code SpecialRewardPruner})。false ならプルーナーはオンライン参加/reload の
     * どちらでも一切走らない(安全弁)。
     */
    public boolean pruneOrphanedGrants() {
        return pruneOrphanedGrants;
    }

    /** 直近の {@link #load(Plugin)} 呼び出しが成功(true)だったか。{@code SpecialRewardPruner} の安全弁。 */
    public boolean lastLoadOk() {
        return lastLoadOk;
    }

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            this.lastLoadOk = false;
            return false;
        }

        ParseResult result = parse(yaml, log);
        this.titles = result.titles();
        this.particles = result.particles();
        this.particleSeeds = result.particleSeeds();
        // display.nametag-clearance:
        //   0 は「ネームタグ上端にぴったり載せる」。
        //   ⚠ 2026-08-24 から【負値も許す】(報告「称号のy座標をあと0.3くらい下げたい」)。
        //     それまでは負値を既定 0.4 へ戻していたので、0 まで下げた人が更に下げようとすると
        //     【逆に 0.4 上がる】という最悪の挙動だった。下限は MIN_TITLE_NAMETAG_CLEARANCE。
        //   非有限値(NaN/∞)だけは既定へ戻す。
        double clearance = yaml.getDouble("display.nametag-clearance", DEFAULT_TITLE_NAMETAG_CLEARANCE);
        if (!Double.isFinite(clearance)) {
            log.warning("[" + PATH + "] display.nametag-clearance が数値ではないため既定値 "
                    + DEFAULT_TITLE_NAMETAG_CLEARANCE + " を使います");
            this.titleNametagClearance = DEFAULT_TITLE_NAMETAG_CLEARANCE;
        } else if (clearance < MIN_TITLE_NAMETAG_CLEARANCE) {
            log.warning("[" + PATH + "] display.nametag-clearance: " + clearance
                    + " は下限 " + MIN_TITLE_NAMETAG_CLEARANCE
                    + " を下回るため下限として扱います(これ以上下げると称号が名前を完全に覆います)");
            this.titleNametagClearance = MIN_TITLE_NAMETAG_CLEARANCE;
        } else {
            this.titleNametagClearance = clearance;
        }
        // display.title-teleport-duration: 称号の追従補間(tick)。既定3(本体の補間と同じ長さ)。
        int duration = yaml.getInt("display.title-teleport-duration", DEFAULT_TITLE_TELEPORT_DURATION_TICKS);
        int clampedDuration = Math.max(0, Math.min(MAX_TITLE_TELEPORT_DURATION_TICKS, duration));
        if (clampedDuration != duration) {
            log.warning("[" + PATH + "] display.title-teleport-duration: " + duration
                    + " は範囲外(0.." + MAX_TITLE_TELEPORT_DURATION_TICKS + ")のため "
                    + clampedDuration + " として扱います");
        }
        this.titleTeleportDurationTicks = clampedDuration;
        this.pruneOrphanedGrants = yaml.getBoolean("prune-orphaned-grants", true);

        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + (titles.size() + particles.size() + particleSeeds.size())
                    + " special reward(s), " + result.skipped() + " skipped");
            this.lastLoadOk = false;
            return false;
        }
        log.info("[" + PATH + "] loaded " + titles.size() + " title(s), " + particles.size()
                + " particle(s), " + particleSeeds.size() + " particle-seed(s) OK");
        this.lastLoadOk = true;
        return true;
    }

    /** Pure parse of the whole document — unit-testable headlessly. */
    static ParseResult parse(YamlConfiguration yaml, Logger log) {
        int skipped = 0;
        Map<String, Title> titles = new LinkedHashMap<>();
        ConfigurationSection titlesSection = yaml.getConfigurationSection("titles");
        if (titlesSection != null) {
            for (String id : titlesSection.getKeys(false)) {
                ConfigurationSection entry = titlesSection.getConfigurationSection(id);
                String display = entry != null ? entry.getString("display") : null;
                if (entry == null || display == null || display.isBlank()) {
                    log.warning("[" + PATH + "] title '" + id + "' missing display; skipped");
                    skipped++;
                    continue;
                }
                titles.put(id, new Title(id, display));
            }
        }

        Map<String, ParticleEffect> particles = new LinkedHashMap<>();
        ConfigurationSection particlesSection = yaml.getConfigurationSection("particles");
        if (particlesSection != null) {
            for (String id : particlesSection.getKeys(false)) {
                ConfigurationSection entry = particlesSection.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("[" + PATH + "] particle '" + id + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                Particle particle = parseParticle(entry.getString("particle"));
                if (particle == null) {
                    log.warning("[" + PATH + "] particle '" + id + "' has invalid/missing particle name; skipped");
                    skipped++;
                    continue;
                }
                Shape shape = parseShape(entry.getString("shape", "circle"));
                if (shape == null) {
                    log.warning("[" + PATH + "] particle '" + id + "' has invalid shape ("
                            + shapeVocabulary() + "); skipped");
                    skipped++;
                    continue;
                }
                int interval = Math.max(1, entry.getInt("interval-ticks", 10));
                particles.put(id, new ParticleEffect(id, particle,
                        parseEmission(entry, shape, Emission.DEFAULT_PLAYER_Y_OFFSET), interval));
            }
        }

        Map<String, ParticleSeed> seeds = new LinkedHashMap<>();
        ConfigurationSection seedsSection = yaml.getConfigurationSection("particle-seeds");
        if (seedsSection != null) {
            for (String id : seedsSection.getKeys(false)) {
                ConfigurationSection entry = seedsSection.getConfigurationSection(id);
                String seedItem = entry != null ? entry.getString("seed-item") : null;
                if (entry == null || seedItem == null || seedItem.isBlank()) {
                    log.warning("[" + PATH + "] particle-seed '" + id + "' missing seed-item; skipped");
                    skipped++;
                    continue;
                }
                // clears: true は「刻印を消すシード」。粒子を出さないので particle は要らない
                // (2026-08-25 / W-221)。ここで particle 必須を掛けると、消し専用のシードが
                // 起動のたびに warning ごと落とされて【config に書いたのに存在しない】になる。
                boolean clears = entry.getBoolean("clears", false);
                Particle particle = parseParticle(entry.getString("particle"));
                if (particle == null && !clears) {
                    log.warning("[" + PATH + "] particle-seed '" + id
                            + "' has invalid/missing particle name; skipped");
                    skipped++;
                    continue;
                }
                Shape shape = parseShape(entry.getString("shape", "aura"));
                if (shape == null) {
                    log.warning("[" + PATH + "] particle-seed '" + id + "' has invalid shape ("
                            + shapeVocabulary() + "); skipped");
                    skipped++;
                    continue;
                }
                Anchor anchor = parseAnchor(entry.getString("origin", "impact"));
                if (anchor == null) {
                    log.warning("[" + PATH + "] particle-seed '" + id
                            + "' has invalid origin (impact|player); skipped");
                    skipped++;
                    continue;
                }
                // 既定の半径 0.3 は旧実装の見た目そのまま。shape を書いていないシードの印象を
                // 変えないために、ここだけ Emission.of(AURA) の 0.6 ではなく 0.3 を初期値にする。
                Emission seedDefaults = ParticleSeed.legacySeedEmission(
                        Emission.of(shape).count());
                Emission emission = parseEmission(entry,
                        shape == Shape.AURA ? seedDefaults : Emission.of(shape),
                        Emission.DEFAULT_IMPACT_Y_OFFSET);
                seeds.put(id, new ParticleSeed(id, seedItem.trim(), particle, emission,
                        entry.getString("display"), clears, anchor));
            }
        }

        return new ParseResult(Map.copyOf(titles), Map.copyOf(particles), Map.copyOf(seeds), skipped);
    }

    /**
     * Particle 名を解決する。実在する名前でも、<b>追加データを必須とする種類は受け付けない</b>。
     *
     * <p>2026-07-31 追加のガード。演出側({@code ParticleEffectService} / {@code ParticleSeedListener})は
     * {@code spawnParticle(particle, loc, count, ...)} をデータ引数なしで呼ぶため、{@code getDataType()}
     * が {@code Void} でない種類(FLASH/DUST/BLOCK/ITEM/ENTITY_EFFECT/SHRIEK/SCULK_CHARGE/VIBRATION 等)を
     * 設定すると発生の瞬間に {@code IllegalArgumentException} が飛ぶ。パーティクルは常時 tick で回る
     * ので、1件の設定ミスがログを埋め尽くし、同じリスナーに乗っている処理も道連れにする。
     * Paper 1.21.11 で {@code FLASH} が Color 必須になったとき、会心演出が落ちて戦闘処理が
     * 丸ごと止まった実績があるので、設定を読む時点で弾いて WARNING に落とす。
     */
    private static Particle parseParticle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Particle particle = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            return particle.getDataType() == Void.class ? particle : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Shape parseShape(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Shape.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Anchor parseAnchor(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Anchor.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** warning に出す「書ける形状の一覧」。列挙を増やしたときに文言が古びないよう組み立てる。 */
    private static String shapeVocabulary() {
        StringBuilder out = new StringBuilder();
        for (Shape shape : Shape.values()) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(shape.name().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /**
     * 発生パラメータを読む (2026-08-25 / W-243・W-244)。<b>書かれていないキーは
     * {@code defaults}(= その形状の既定値)で埋める</b> ── 形状を切り替えたときに、その形状が
     * 使うキーだけを書き足せば済むようにするため。範囲の矯正は {@link Emission} の
     * コンパクトコンストラクタが一手に引き受ける(読み手ごとに clamp を書くと必ずズレる)。
     */
    private static Emission parseEmission(ConfigurationSection entry, Shape shape, double defaultYOffset) {
        return parseEmission(entry, Emission.of(shape), defaultYOffset);
    }

    private static Emission parseEmission(ConfigurationSection entry, Emission defaults, double defaultYOffset) {
        return new Emission(
                defaults.shape(),
                entry.getInt("count", defaults.count()),
                entry.getDouble("radius", defaults.radius()),
                entry.getDouble("speed", defaults.speed()),
                entry.getDouble("height", defaults.height()),
                entry.getInt("turns", defaults.turns()),
                entry.getDouble("arc-degrees", defaults.arcDegrees()),
                entry.getDouble("y-offset", defaultYOffsetFor(defaults.shape(), defaultYOffset)));
    }

    /**
     * 高さ補正を書かなかったときの既定値。<b>{@link Shape#TRAIL} だけ足元寄りに落とす</b> ──
     * 軌跡は「足元から後ろへ流れる」ものなので、他の形状と同じ胸の高さ(1.0)を既定にすると
     * <b>形状を選び替えただけで空中に軌跡が浮く</b>。書いてあればもちろんそれに従う。
     */
    private static double defaultYOffsetFor(Shape shape, double categoryDefault) {
        if (shape != Shape.TRAIL) {
            return categoryDefault;
        }
        return Math.min(categoryDefault, Emission.DEFAULT_TRAIL_Y_OFFSET);
    }

    record ParseResult(Map<String, Title> titles, Map<String, ParticleEffect> particles,
                        Map<String, ParticleSeed> particleSeeds, int skipped) {
    }
}
