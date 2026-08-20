package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.stats.PercentStatNormalize;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import com.trinityforge.stats.StunDurationStatNormalize;
import com.trinityforge.stats.VanillaAttributeDefaults;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code combat/base-stats.yml}: player base stats folded uniformly into every player's
 * aggregated stat map (and, for attribute-channel keys, into the vanilla-attribute apply path).
 *
 * <p>Keys are lore.yml stat names (e.g. {@code crit-chance}, {@code phys-flat-defense},
 * {@code max-health}). Values follow the same raw convention as {@code stats/item-stats.yml}:
 * PERCENT-family stats may be written as whole percent points ({@code 5} = 5%) and are normalised to
 * fractions here via {@link PercentStatNormalize#coerce} exactly like item stats. A key absent from
 * the file means "vanilla" — nothing is added for that stat, except {@code stun-duration-bonus}:
 * the former implicit 25tick baseline is inserted when absent and can be overridden as a tick value.
 *
 * <p>2026-07-25 (config editor T1): the {@link StatVocabulary.Channel#ATTRIBUTE} keys
 * ({@code attack-reach} / {@code max-health} / {@code move-speed} / {@code knockback-resistance};
 * {@code attack-speed} was removed from this channel and from this file entirely on 2026-07-26 — see
 * below) are the one exception to "value = addend": for these keys the written
 * value is the player's intended <b>final absolute value</b>, not an addend on top of vanilla. This
 * loader converts {@code written - vanillaDefault} (see {@link VanillaAttributeDefaults}) into the
 * internal representation so every downstream consumer ({@link StatVocabulary}-routed aggregation,
 * {@code PerkAttributeApplier}, {@link com.trinityforge.stats.AttributeProjection}) keeps treating the
 * stored value as an addend exactly as before — no downstream change needed. {@code 0} / absent still
 * means "vanilla, unchanged" (as does writing exactly the vanilla default, since that converts to an
 * addend of {@code 0}); consequently the exact vanilla default itself cannot be distinguished from "not
 * set" and a value below the vanilla default converts to a negative (but well-defined) addend.
 *
 * <p>2026-07-26 (stat-scope 境界引き直し §2, C→A 降格): {@code attack-speed}(絶対値・メインハンド専用)
 * no longer has a player-wide base-stat entry here at all — it is exclusively an item-level stat now
 * ({@code stats/item-stats.yml}), resolved directly by {@code PerkAttributeApplier} via
 * {@code DerivedItemStats.resolve} instead of this file / {@link StatVocabulary}. {@code attack-speed-bonus}
 * (the percentage-bonus counterpart) is unaffected and remains a normal addend-layer key here.
 *
 * <p>2026-07-25 (config editor T2) / 2026-08-16 (一部を ArsPaper へ差し戻し): このファイルは ArsPaper の
 * マナ関連の既定値のうち <b>5キー</b>({@code mana-onhit-percent} / {@code mana-onattack-percent} /
 * {@code mana-idle-seconds} / {@code mana-idle-bonus-percent} / {@code mana-idle-bonus-flat})を持つ。
 * これらは {@link StatVocabulary}(GENERAL チャネル)に登録され、percent 系は
 * {@link PercentStatNormalize} で正規化される。ArsPaper は
 * {@code TrinityForgeBridge.manaBaseStatRaw(key)} → {@link #stats()} 経由で読む。
 *
 * <p>2026-08-16: 残る3キー({@code mana-max-base} / {@code mana-regen-base} /
 * {@code mana-regen-interval-ticks})は ArsPaper の {@code config.yml} の {@code mana.default-max} /
 * {@code mana.default-regen-rate} / {@code mana.regen-interval-ticks} へ<b>移設した</b>。
 * この3キーは {@code stats/lore.yml} に載せられない「全プレイヤー共通の定数」で、結果として
 * 設定エディタのどの画面にも出ず手編集でしか変えられなかったため、真源を ArsPaper 側へ戻して
 * 「ArsPaper 全体設定 (config)」画面から編集できるようにしたもの。
 * 稼働中サーバの配備済みファイルには旧キーの行が残る({@link #load} の {@code saveResource} は
 * 既存ファイルを上書きしない)ので、残存を検出したら移設先つきで WARNING を出す
 * （{@link #MIGRATED_TO_ARSPAPER_KEYS}。黙って無視すると「設定したのに効かない」事故になる）。
 */
public final class BaseStatsConfig implements LoadableConfig {

    public static final String PATH = "combat/base-stats.yml";
    private static final String SECTION = "base-stats";
    private static final String ATTACK_POWER_KEY = StatKeys.canonical("attack-power");

    /**
     * 2026-08-16 に ArsPaper の {@code config.yml} へ移設した旧キー(canonical) → 移設先キーの対応表。
     *
     * <p>{@link #load} は既存ファイルを上書きしない({@code saveResource(PATH, false)})ので、
     * 稼働中サーバの {@code plugins/TrinityForge/combat/base-stats.yml} には旧キーの行が残り続ける。
     * 黙って無視すると「設定したのに効かない」事故になるため、残存を検出したら移設先を示して警告する。
     * 判定には {@code this.stats}(0値が落ちる)ではなく生のキー集合を使う — 「0 に書き換えて無効化した
     * つもり」の人にも移設を伝える必要があるため。
     */
    private static final Map<String, String> MIGRATED_TO_ARSPAPER_KEYS = migratedToArsPaperKeys();

    private static Map<String, String> migratedToArsPaperKeys() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(StatKeys.canonical("mana-max-base"), "mana.default-max");
        map.put(StatKeys.canonical("mana-regen-base"), "mana.default-regen-rate");
        map.put(StatKeys.canonical("mana-regen-interval-ticks"), "mana.regen-interval-ticks");
        return java.util.Collections.unmodifiableMap(map);
    }

    // Canonical stat-key -> normalised value. Before load it is empty; load injects the migrated
    // 25tick stun baseline even when an older file does not contain that key.
    private volatile Map<String, Double> stats = Map.of();

    /** Canonical-keyed base stat values (already unit-normalised). Never null. */
    public Map<String, Double> stats() {
        return stats;
    }

    /**
     * Single base-stat value by (kebab- or snake-case) key, or {@code fallback} if absent/vanilla(0).
     * Used by fork bridges that read one specific base-stat default directly (ArsPaper のマナ回復5キーを
     * {@code TrinityForgeBridge.manaBaseStat*} が読む経路)rather than folding into the combat
     * aggregate — see {@code combat/base-stats.yml} header comment for the "absent = vanilla" contract.
     *
     * <p>2026-08-16: マナ基礎3キー({@code mana-max-base} 等)はこの経路から外れ、ArsPaper の
     * {@code config.yml} の {@code mana.*} が真源になった({@link #MIGRATED_TO_ARSPAPER_KEYS})。
     * シグネチャ自体はフォークが使い続けるので残す。
     */
    public double statOrDefault(String key, double fallback) {
        Double value = stats.get(StatKeys.canonical(key));
        return value != null ? value : fallback;
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
            log.log(Level.SEVERE, "[" + PATH + "] YAML error: " + ex.getMessage(), ex);
            return false;
        }

        Map<String, Double> next = new LinkedHashMap<>();
        ConfigurationSection sec = yaml.getConfigurationSection(SECTION);
        // 2026-07-26 stat-scope 境界引き直し §4 (ドリフト防止): 値が0でも「キーとして書かれている」ことを
        // 拾うため、下の next への格納(0値は継続的にスキップされる)とは別に、生のキー集合をここで控える。
        Set<String> rawFileKeysCanonical = new java.util.LinkedHashSet<>();
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                rawFileKeysCanonical.add(StatKeys.canonical(key));
                if (!sec.isDouble(key) && !sec.isInt(key) && !sec.isLong(key)) {
                    log.warning("[" + PATH + "] base stat '" + key + "' is not a number; skipped");
                    continue;
                }
                String canonicalKey = StatKeys.canonical(key);
                double raw = sec.getDouble(key);
                if (StunDurationStatNormalize.KEY.equals(canonicalKey)) {
                    next.put(canonicalKey, StunDurationStatNormalize.normalizeBase(raw));
                    continue;
                }
                // 0 は「加算なし」= バニラと実質同じなので保持しても無害だが、明示的に落として
                // aggregate/attribute のループを無駄に回さない。
                if (raw == 0.0) {
                    continue;
                }
                double effective = raw;
                if (StatVocabulary.isAttribute(canonicalKey)) {
                    // T1(2026-07-25): ATTRIBUTE チャネルの5キーは「プレイヤーの最終絶対値」として書かれる
                    // (クラスjavadoc参照)。内部表現は従来通り「バニラ既定値からの加算量」なので、ここで
                    // written - vanillaDefault へ変換する。書いた値がバニラ既定値と一致する場合(=加算量0)は
                    // 「バニラのまま」として扱い、raw==0 と同じ意味論でここで捨てる。
                    effective = raw - VanillaAttributeDefaults.get(canonicalKey);
                    if (effective == 0.0) {
                        continue;
                    }
                }
                next.put(canonicalKey, PercentStatNormalize.coerce(canonicalKey, effective));
            }
        }
        // 旧配備ファイルにはこのキーが無いこともある。暗黙の旧基準25tickを明示的な
        // base statへ移行し、設定ファイルの部分更新でも1tickへ退行しないようにする。
        next.putIfAbsent(StunDurationStatNormalize.KEY, StunDurationStatNormalize.DEFAULT_TICKS);
        this.stats = Map.copyOf(next);

        // attack-power をベース値に入れると CombatListener のベース置換規則が全ヒットで発火し、
        // 素手/バニラ武器の基本ダメージ置換 + エンチャント無効化が全プレイヤーへ及ぶ(高影響)。
        // 意図的でない事故を防ぐため、値がある場合は明示的に警告する。
        if (this.stats.containsKey(ATTACK_POWER_KEY)) {
            log.warning("[" + PATH + "] 'attack-power' に基礎値が設定されています。"
                    + "これは武器の基本ダメージを置き換える特殊ステで、全プレイヤーの素手/バニラ武器の"
                    + "ダメージ・エンチャント効果に影響します。意図的でなければ空欄にしてください。");
        }
        // 2026-08-16: ArsPaper の config.yml へ移設したマナ基礎3キーの残存を検出して、移設先つきで警告する。
        // 稼働中サーバの配備済みファイルは saveResource(false) では更新されないので旧キーが残り続ける。
        // 黙って無視すると「base-stats.yml に書いた値が効かない」事故になる(下の汎用警告だけだと
        // 「綴り間違い」に見えて移設先へ辿り着けない)。値0で書かれていても残存として扱う。
        for (Map.Entry<String, String> migrated : MIGRATED_TO_ARSPAPER_KEYS.entrySet()) {
            if (true || !rawFileKeysCanonical.contains(migrated.getKey())) {
                continue;
            }
            log.warning("[" + PATH + "] '" + migrated.getKey().replace('_', '-')
                    + "' は ArsPaper の config.yml の '" + migrated.getValue()
                    + "' へ移設されました(2026-08-16)。この行はもう読まれません(無視されます) — "
                    + "値の変更は設定エディタの「ArsPaper 全体設定 (config)」画面、または "
                    + "plugins/ArsPaper/config.yml で行ってください。この行は削除して構いません。");
        }
        // プレイヤーへ効果が届かないキー(綴り間違い / アイテム固有ステ等)を診断する。
        // 空欄はバニラなので、記述されたのに no-op になるキーだけを注意喚起する。
        for (String key : this.stats.keySet()) {
            if (MIGRATED_TO_ARSPAPER_KEYS.containsKey(key)) {
                // 移設済みキーは直上で移設先つきの専用警告を出している。ここで「綴り間違いの可能性」と
                // 二重に出すと、旧ファイルを持つ全稼働サーバで誤解を招く(移設先へ辿り着けなくなる)。
                continue;
            }
            if (!StatVocabulary.isKnown(key)) {
                log.warning("[" + PATH + "] base stat '" + key + "' はプレイヤーへ適用される既知のステータスでは"
                        + "ありません(綴り間違い、またはアイテム固有ステの可能性)。効果はありません。");
            }
        }
        // 2026-07-26 stat-scope 境界引き直し §4 (ドリフト防止、逆方向): StatVocabulary に登録されている
        // のに base-stats.yml に一切キーが無い(editorが初期値を出せない)ものを警告する。
        // 「静かに壊れるより騒がしく落ちる」方針(ActiveSkillCooldownKeys.verifyRegistered と同種)だが、
        // このファイルはユーザーが手編集/reloadする既存ファイルであるため load() 自体は失敗させず、
        // ログ警告に留める(missingVocabularyKeys はテストからも直接呼べる)。
        Set<String> missing = missingVocabularyKeys(rawFileKeysCanonical);
        if (!missing.isEmpty()) {
            log.warning("[" + PATH + "] StatVocabulary に登録済みだがこのファイルに存在しないキー: " + missing
                    + " — editor で初期値(0)を設定できません。base-stats.yml へ追加してください。");
        }

        log.info("[" + PATH + "] loaded " + this.stats.size() + " player base stat(s) OK");
        return true;
    }

    /**
     * {@link StatVocabulary#allKeys()} のうち {@code rawFileKeysCanonical}(base-stats.yml に実際に
     * 書かれているキーの canonical 集合、値の有無は問わない)に存在しないキーの集合(空なら drift なし)。
     * {@link #load} からも、ユニットテストからも同じロジックを共有するために切り出した純粋関数。
     */
    static Set<String> missingVocabularyKeys(Set<String> rawFileKeysCanonical) {
        Set<String> missing = new TreeSet<>(StatVocabulary.allKeys());
        missing.removeAll(rawFileKeysCanonical);
        return missing;
    }
}
