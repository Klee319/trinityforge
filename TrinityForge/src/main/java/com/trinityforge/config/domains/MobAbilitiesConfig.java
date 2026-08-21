package com.trinityforge.config.domains;

import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.MobAbility;
import com.trinityforge.config.LoadableConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code combat/mob-abilities.yml} のローダー（2026-07-31）。
 *
 * <p>敵の特殊攻撃を「テンプレート」として定義し、{@code combat/mob-overrides.yml} の
 * {@code abilities: [id, ...]} から参照する。テンプレート方式にした理由は
 * <b>モブが 396 体あるから</b> — 個別に攻撃を書き下すと yml が破裂するし、バランス調整のときに
 * 396 箇所を直すことになる。テンプレート側の数値を1つ変えれば全体に効く形にしてある。
 *
 * <p>壊れたエントリは警告つきで<b>そのエントリだけ</b>捨てる（他のアチーブメント/モブ設定と同じ方針）。
 * ロード全体を失敗させると「1つの typo で全モブの特殊攻撃が消える」ため。
 */
public final class MobAbilitiesConfig implements LoadableConfig {

    public static final String PATH = "combat/mob-abilities.yml";

    private volatile Map<String, MobAbility> abilities = Map.of();
    private volatile boolean enabled = true;
    private volatile int checkIntervalTicks = 20;
    private volatile double globalCooldownSeconds = DEFAULT_GLOBAL_COOLDOWN_SECONDS;

    private volatile boolean requireTarget = DEFAULT_REQUIRE_TARGET;
    private volatile boolean requireLineOfSight = DEFAULT_REQUIRE_LINE_OF_SIGHT;
    private volatile double elementBias = DEFAULT_ELEMENT_BIAS;

    /** 技と技の間に必ず空ける秒数の既定。 */
    public static final double DEFAULT_GLOBAL_COOLDOWN_SECONDS = 12.0;
    /** 「狙っている相手にだけ撃つ」の既定。false は 2026-08-18 以前の挙動（非追跡でも発動）。 */
    public static final boolean DEFAULT_REQUIRE_TARGET = true;
    /** 「遮蔽越しには撃たない」の既定。false は 2026-08-18 以前の挙動（壁の裏でも発動）。 */
    public static final boolean DEFAULT_REQUIRE_LINE_OF_SIGHT = true;
    /** 同、下限。0 を許すと「常時発動」に戻せてしまうので置かない選択はしない。 */
    public static final double MIN_GLOBAL_COOLDOWN_SECONDS = 0.0;
    /** 同、上限（10分）。書き間違いで技が一生出てこないのを防ぐ。 */
    public static final double MAX_GLOBAL_COOLDOWN_SECONDS = 600.0;
    /** 技が自分の属性へ引き寄せる強さの既定（2026-08-21 W-181）。{@link #elementBias()} 参照。 */
    public static final double DEFAULT_ELEMENT_BIAS = 0.35;

    /** テンプレートID→定義。未定義IDの参照は {@code null} を返す（呼び出し側が読み飛ばす）。 */
    public MobAbility ability(String id) {
        if (id == null) {
            return null;
        }
        return abilities.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** 全テンプレート（editor の候補生成とテスト用）。 */
    public Map<String, MobAbility> abilities() {
        return abilities;
    }

    /** 機能全体のスイッチ。false なら周期タスクそのものを回さない。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 発動判定を回す間隔（tick）。<b>短くするとサーバ負荷が直線的に増える</b>ので下限 5 tick で丸める。
     * 判定間隔とクールダウンは別物で、ここは「抽選をどれだけ細かく行うか」だけを決める。
     */
    public int checkIntervalTicks() {
        return checkIntervalTicks;
    }

    /**
     * <b>同じモブが技と技の間に必ず空ける秒数</b>（2026-08-17、ユーザー報告「スキルが常時発動している」）。
     *
     * <p>技ごとの {@code cooldown-seconds} だけでは頻度を抑えられない。技を2〜3個持つモブでは
     * 「どれか1つはクールダウン明け」の状態がほぼ途切れず、判定のたびに {@code chance} を引くので
     * <b>技を足すほど発動間隔が短くなる</b>（3個持たせると平均3秒に1回）。
     * ここは技の種類によらずモブ単位で効く共通の間合いで、
     * 「バニラの通常行動の合間に技が挟まる」テンポを作るための唯一の摘み。
     */
    public double globalCooldownSeconds() {
        return globalCooldownSeconds;
    }

    /** 同、ミリ秒。 */
    public long globalCooldownMillis() {
        return Math.round(globalCooldownSeconds * 1000.0);
    }

    /**
     * <b>そのモブが実際にその相手を狙っているときだけ技を撃つか</b>
     * （2026-08-18、ユーザー報告「非追跡状態でも発動する」）。
     *
     * <p>発動判定の走査はプレイヤー起点で「半径32m以内の全 LivingEntity」を舐めるだけなので、
     * これが false だと<b>こちらに気づいてすらいないモブが技を撃ってくる</b>。
     * {@code true} のとき、AI を持つモブ（{@code org.bukkit.entity.Mob}）は
     * {@code getTarget()} がその相手本人である場合のみ発動する。
     * AI を持たない {@code LivingEntity} には狙う対象の概念自体が無いので、この条件は課されない。
     */
    public boolean requireTarget() {
        return requireTarget;
    }

    /**
     * <b>遮蔽物越しに技を撃たないか</b>（2026-08-18、ユーザー報告「壁の裏でも発動する」）。
     *
     * <p>{@code true} のとき {@code LivingEntity#hasLineOfSight} が通らない相手には発動しない。
     * 「壁に隠れて凌ぐ」という回避手段を成立させるための条件で、W-63（予兆を入れて回避余地を作る）
     * と目的は同じだが、こちらは<b>そもそも撃たせない</b>側の門。
     */
    public boolean requireLineOfSight() {
        return requireLineOfSight;
    }

    /**
     * <b>技が「自分の属性」へどれだけ引き寄せるか</b> [0,1]（2026-08-21 W-181
     * 「格下レベルのボスにワンパンされる」）。
     *
     * <p>2026-08-21 以前、技のダメージは {@code damage-type} の側へ<b>100%</b>寄せて解決していた。
     * これはモブの {@code magic-ratio}（通常攻撃の物理/魔法の配分）を<b>完全に素通り</b>する経路で、
     * 実害が2つあった:
     * <ul>
     *   <li><b>magic-ratio の上限 0.45 が効かない。</b> あの上限は
     *       「魔法防御を持たないプレイヤーが何発耐えるか」で校正した安全弁なのに、
     *       {@code damage-percent: 2.0} の魔法技は実質 magic-ratio 2.0 相当で飛んでいた。</li>
     *   <li><b>コンセプトと逆属性の技が理不尽になる。</b>「敵は物理型(magic-ratio 0.10)」の
     *       ダンジョンで、正しく物理防御を積んだプレイヤーが<b>魔法技1発だけで即死</b>する。
     *       対策のしようが無い＝属性を選ぶ設計そのものを壊す。</li>
     * </ul>
     *
     * <p>そこで技のダメージも<b>そのモブの magic-ratio で物理/魔法へ分割する</b>ようにし、
     * {@code damage-type} は「そこからどれだけ自分の属性側へ引き寄せるか」という<b>偏り</b>に
     * 格下げした。実効の魔法割合は
     * <pre>
     *   魔法技: r + (1 - r) * bias
     *   物理技: r * (1 - bias)
     * </pre>
     * （{@code r} = そのモブの magic-ratio）。{@code bias = 0} なら技もモブと完全に同じ配分になり、
     * {@code bias = 1} なら 2026-08-21 以前の「技は100%その属性」に戻る。
     * 既定 0.35 では magic-ratio 0.10 のダンジョンの魔法技が 41.5% 魔法／58.5% 物理になり、
     * 物理防御を積んだプレイヤーにも<b>受け止める余地が残る</b>。
     *
     * <p>刻印を持たないバニラモブ（magic-ratio 0）でも魔法技は bias ぶんだけ魔法で入るので、
     * ウィザーのビームが完全物理に化けることはない。
     */
    public double elementBias() {
        return elementBias;
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
            return false;
        }
        this.enabled = yaml.getBoolean("enabled", true);
        this.checkIntervalTicks = Math.max(5, Math.min(200, yaml.getInt("check-interval-ticks", 20)));
        this.globalCooldownSeconds = Math.max(MIN_GLOBAL_COOLDOWN_SECONDS,
                Math.min(MAX_GLOBAL_COOLDOWN_SECONDS,
                        yaml.getDouble("global-cooldown-seconds", DEFAULT_GLOBAL_COOLDOWN_SECONDS)));
        this.requireTarget = yaml.getBoolean("require-target", DEFAULT_REQUIRE_TARGET);
        this.requireLineOfSight =
                yaml.getBoolean("require-line-of-sight", DEFAULT_REQUIRE_LINE_OF_SIGHT);
        this.elementBias = Math.max(0.0, Math.min(1.0,
                yaml.getDouble("ability-element-bias", DEFAULT_ELEMENT_BIAS)));

        ParseResult result = parse(yaml.getConfigurationSection("abilities"), log);
        this.abilities = result.abilities();
        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + result.abilities().size()
                    + " ability template(s), " + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + result.abilities().size() + " ability template(s) OK");
        return true;
    }

    /** ヘッドレスにテストできる純パース。 */
    public static ParseResult parse(ConfigurationSection root, Logger log) {
        Map<String, MobAbility> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(rawId);
                String id = rawId.trim().toLowerCase(Locale.ROOT);
                if (entry == null) {
                    log.warning("[" + PATH + "] ability '" + rawId + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                MobAbility.Type type = parseType(entry.getString("type"));
                if (type == null) {
                    log.warning("[" + PATH + "] ability '" + rawId + "' has invalid type ("
                            + typeNames() + "); skipped");
                    skipped++;
                    continue;
                }
                parsed.put(id, new MobAbility(id,
                        entry.getString("display-name", ""),
                        type,
                        parseDamageType(entry.getString("damage-type")),
                        entry.getDouble("damage-percent", 1.0),
                        entry.getDouble("cooldown-seconds", 10.0),
                        entry.getDouble("chance", 0.3),
                        entry.getDouble("range", 16.0),
                        entry.getDouble("radius", 4.0),
                        entry.getInt("count", 1),
                        entry.getDouble("spread-degrees", 45.0),
                        entry.getString("projectile", ""),
                        entry.getString("summon-type", ""),
                        entry.getDouble("duration-seconds", 0.0),
                        entry.getDouble("knockback", 0.0),
                        parseEffects(entry.getMapList("effects")),
                        entry.getString("particle", ""),
                        entry.getInt("particle-count", 0),
                        entry.getString("sound", "")));
            }
        }
        return new ParseResult(Map.copyOf(parsed), skipped);
    }

    private static List<MobAbility.EffectSpec> parseEffects(List<Map<?, ?>> raw) {
        List<MobAbility.EffectSpec> out = new ArrayList<>();
        for (Map<?, ?> map : raw) {
            Object type = map.get("type");
            if (type == null || String.valueOf(type).isBlank()) {
                continue;
            }
            out.add(new MobAbility.EffectSpec(String.valueOf(type),
                    toDouble(map.get("duration-seconds"), 3.0),
                    (int) toDouble(map.get("amplifier"), 0.0)));
        }
        return out;
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static MobAbility.Type parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MobAbility.Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * {@code damage-type} は物理/魔法のみ。{@code TYPELESS} を許すと防御を一切通さない
     * 「即死級の抜け道」になるので、未知の値は物理へ寄せる。
     */
    private static DamageType parseDamageType(String raw) {
        if (raw != null && raw.trim().equalsIgnoreCase("magical")) {
            return DamageType.MAGICAL;
        }
        return DamageType.PHYSICAL;
    }

    private static String typeNames() {
        StringBuilder sb = new StringBuilder();
        for (MobAbility.Type type : MobAbility.Type.values()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(type.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** @param abilities id→定義 / @param skipped 壊れていて捨てたエントリ数 */
    public record ParseResult(Map<String, MobAbility> abilities, int skipped) {
    }
}
