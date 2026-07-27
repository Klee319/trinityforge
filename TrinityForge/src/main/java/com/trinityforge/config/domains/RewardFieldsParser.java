package com.trinityforge.config.domains;

import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.PercentStatNormalize;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code items} / {@code vanilla-exp} / {@code job-exp} / {@code permanent-buffs} の共通パーサ
 * (achievements.yml の {@code rewards:} 配下と collection.yml の各 reward-tier 直下、両方の契約が
 * 完全に一致するため一箇所にまとめる)。fail-soft: 1エントリ不正で全体を落とさず、警告して当該
 * エントリのみスキップする。
 */
final class RewardFieldsParser {

    private RewardFieldsParser() {
    }

    /** {@code items:} — アイテム付与リスト。{@code id} が欠落/空のエントリは警告してスキップする。 */
    static List<ItemGrant> parseItems(ConfigurationSection section, String logPath, String contextLabel,
                                      Logger log) {
        if (section == null) {
            return List.of();
        }
        List<ItemGrant> items = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("items")) {
            Object rawId = raw.get("id");
            String id = rawId == null ? null : rawId.toString().trim();
            if (id == null || id.isBlank()) {
                log.warning("[" + logPath + "] " + contextLabel
                        + " rewards.items entry missing/blank id; skipped");
                continue;
            }
            Object rawAmount = raw.get("amount");
            int amount = 1;
            if (rawAmount instanceof Number number) {
                amount = number.intValue();
            }
            items.add(new ItemGrant(id, amount));
        }
        return List.copyOf(items);
    }

    /** {@code vanilla-exp:} — バニラ経験値。負値は0にクランプする(無し扱い)。 */
    static int parseVanillaExp(ConfigurationSection section) {
        if (section == null) {
            return 0;
        }
        return Math.max(0, section.getInt("vanilla-exp", 0));
    }

    /**
     * {@code job-exp:} — 職業EXP付与リスト。skillが {@link SkillId#ALL} に含まれない/amountが
     * 有限かつ非0でないエントリは警告してスキップする。
     */
    static List<ExpGrant> parseJobExp(ConfigurationSection section, String logPath, String contextLabel,
                                      Logger log) {
        if (section == null) {
            return List.of();
        }
        List<ExpGrant> grants = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("job-exp")) {
            Object rawSkill = raw.get("skill");
            String skill = rawSkill == null ? null : rawSkill.toString().trim().toUpperCase(Locale.ROOT);
            if (skill == null || skill.isBlank() || !SkillId.ALL.contains(skill)) {
                log.warning("[" + logPath + "] " + contextLabel
                        + " rewards.job-exp entry has invalid/unknown skill '" + rawSkill + "'; skipped");
                continue;
            }
            Object rawAmount = raw.get("amount");
            double amount = rawAmount instanceof Number number ? number.doubleValue() : Double.NaN;
            if (!Double.isFinite(amount) || amount <= 0.0) {
                log.warning("[" + logPath + "] " + contextLabel
                        + " rewards.job-exp entry for skill '" + skill + "' has invalid/zero/negative amount; skipped");
                continue;
            }
            grants.add(new ExpGrant(skill, amount));
        }
        return List.copyOf(grants);
    }

    /**
     * {@code permanent-buffs:} — 永続ステータスバフ({@code stat-key: value} のフラットマップ)。
     * キーは {@link StatKeys#canonical} で正規化して格納する(別表記が同一canonicalキーへ畳み込まれた
     * 場合は加算合成)。値が数値でない/有限でないエントリは警告してスキップする。
     *
     * <p>CMB-15: 他の全ステ供給経路(item-stats / skilltree buffs / base-stats)と同様に
     * {@link PercentStatNormalize#coerce} を適用する。ここを通さないと「20」(20%のつもり)が
     * 生値2000%として combat に渡り貫通等が飽和する。
     */
    static Map<String, Double> parsePermanentBuffs(ConfigurationSection section, String logPath,
                                                    String contextLabel, Logger log) {
        if (section == null) {
            return Map.of();
        }
        ConfigurationSection buffs = section.getConfigurationSection("permanent-buffs");
        if (buffs == null) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : buffs.getKeys(false)) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object rawValue = buffs.get(key);
            double value = rawValue instanceof Number number ? number.doubleValue() : Double.NaN;
            if (!Double.isFinite(value)) {
                log.warning("[" + logPath + "] " + contextLabel
                        + " rewards.permanent-buffs key '" + key + "' has non-numeric/invalid value; skipped");
                continue;
            }
            String canonicalKey = StatKeys.canonical(key);
            if (StatVocabulary.channelOf(canonicalKey) == StatVocabulary.Channel.NONE) {
                // 未知/typoキー(canonical後もどのperk-buffチャネルにも属さない)は skilltree の buffs:
                // パースと同様に警告してスキップする — 受理すると値は保存されるが誰も読まない silent no-op になる。
                log.warning("[" + logPath + "] " + contextLabel
                        + " rewards.permanent-buffs key '" + key + "' is not a recognised stat key; skipped");
                continue;
            }
            out.merge(canonicalKey, PercentStatNormalize.coerce(canonicalKey, value), Double::sum);
        }
        return Map.copyOf(out);
    }
}
