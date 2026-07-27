package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.stats.PercentStatNormalize;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
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
 * the file means "vanilla" — nothing is added for that stat.
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
 * <p>2026-07-25 (config editor T2): also holds the {@code mana-max-base} / {@code mana-regen-base} /
 * {@code mana-regen-interval-ticks} / {@code mana-onhit-percent} / {@code mana-onhit-flat} /
 * {@code mana-onattack-percent} / {@code mana-onattack-flat} / {@code mana-idle-seconds} /
 * {@code mana-idle-bonus-percent} / {@code mana-idle-bonus-flat} keys — ArsPaper mana defaults
 * relocated here from ArsPaper's own {@code config.yml}. They are registered in
 * {@link StatVocabulary} (GENERAL channel) and {@link PercentStatNormalize} for the 3 percent keys,
 * but deliberately <b>not</b> in {@code stats/lore.yml}: lore.yml entries require a hand-maintained
 * description in the config editor's {@code labels.js}, enforced by
 * {@code tools/config-editor/test/lore-stat-descriptions.test.js}, and ArsPaper mana defaults are not
 * player-facing item-lore stats. ArsPaper reads them via
 * {@code TrinityForgeBridge.manaBaseStat(key, fallback)} → {@link #statOrDefault}.
 */
public final class BaseStatsConfig implements LoadableConfig {

    public static final String PATH = "combat/base-stats.yml";
    private static final String SECTION = "base-stats";
    private static final String ATTACK_POWER_KEY = StatKeys.canonical("attack-power");

    // Canonical stat-key -> normalised value. Empty by default (all vanilla).
    private volatile Map<String, Double> stats = Map.of();

    /** Canonical-keyed base stat values (already percent-normalised). Never null. */
    public Map<String, Double> stats() {
        return stats;
    }

    /**
     * Single base-stat value by (kebab- or snake-case) key, or {@code fallback} if absent/vanilla(0).
     * Used by fork bridges that read one specific base-stat default directly (e.g. ArsPaper mana
     * defaults via {@code TrinityForgeBridge.manaBaseStat}) rather than folding into the combat
     * aggregate — see {@code combat/base-stats.yml} header comment for the "absent = vanilla" contract.
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
        this.stats = Map.copyOf(next);

        // attack-power をベース値に入れると CombatListener のベース置換規則が全ヒットで発火し、
        // 素手/バニラ武器の基本ダメージ置換 + エンチャント無効化が全プレイヤーへ及ぶ(高影響)。
        // 意図的でない事故を防ぐため、値がある場合は明示的に警告する。
        if (this.stats.containsKey(ATTACK_POWER_KEY)) {
            log.warning("[" + PATH + "] 'attack-power' に基礎値が設定されています。"
                    + "これは武器の基本ダメージを置き換える特殊ステで、全プレイヤーの素手/バニラ武器の"
                    + "ダメージ・エンチャント効果に影響します。意図的でなければ空欄にしてください。");
        }
        // プレイヤーへ効果が届かないキー(綴り間違い / アイテム固有ステ等)を診断する。
        // 空欄はバニラなので、記述されたのに no-op になるキーだけを注意喚起する。
        for (String key : this.stats.keySet()) {
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
