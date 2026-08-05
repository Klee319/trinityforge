package com.trinityforge.progression;

import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.PotionBuffSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.stats.StatDisplaySpec;
import com.trinityforge.stats.StatKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ロールのバフを人間が読める行へ変換する共通ロジック(2026-07-28)。
 *
 * <p>{@code /tf role}(チャット表示)とロール選択GUI(アイテムlore)の両方が同じ
 * 説明文を使うための単一の出所。ステ名と数値書式は {@code stats/lore.yml}
 * ({@link LoreConfig#displayTable()})を正とするので、{@code /tf stats} の表示と語彙がズレない。
 */
public final class RoleDescriptions {

    private final LoreConfig loreConfig;

    public RoleDescriptions(LoreConfig loreConfig) {
        this.loreConfig = Objects.requireNonNull(loreConfig, "loreConfig");
    }

    /** 戦闘職の効果行(攻撃バフ→防御バフ→ヘイト倍率)。効果が1つも無ければ空リスト。 */
    public List<Component> describeCombat(CombatRoleSpec spec) {
        List<Component> lines = new ArrayList<>();
        if (spec == null) {
            return lines;
        }
        appendStatLines(lines, spec.attackBuffs());
        appendStatLines(lines, spec.defenseBuffs());
        if (spec.hateThreatMultiplier() != 1.0) {
            lines.add(effectLine("ヘイト倍率", "x" + trimZeros(spec.hateThreatMultiplier())));
        }
        return lines;
    }

    /** 補助職の効果行(スキルEXP倍率→常時ポーション効果)。効果が1つも無ければ空リスト。 */
    public List<Component> describeSupport(SupportRoleSpec spec) {
        List<Component> lines = new ArrayList<>();
        if (spec == null) {
            return lines;
        }
        if (spec.expSkill() != null && !spec.expSkill().isBlank() && spec.expMultiplier() != 1.0) {
            lines.add(effectLine(spec.expSkill() + " EXP", "x" + trimZeros(spec.expMultiplier())));
        }
        PotionBuffSpec potion = spec.potionBuff();
        if (potion != null) {
            // 翻訳キーは自前で組む: Bukkit/Adventure どちらの Translatable 実装に依存するかが
            // API版で揺れるため、バニラのキー書式("effect.minecraft.<id>")を直接使う。
            // クライアント側で日本語化されるので Java/統合版とも表示は現地語になる。
            lines.add(Component.text("  ", NamedTextColor.GRAY)
                    .append(Component.translatable("effect.minecraft." + potion.type().getKey().getKey()))
                    .append(Component.text(" " + romanNumeral(potion.amplifier() + 1) + " (常時)"))
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return lines;
    }

    /**
     * ステキー → {@code stats/lore.yml} の表示名・書式で1行にする。lore.yml に未登録のキーは
     * キー名そのままと素の数値で出す(黙って落とすと「設定したのに表示されない」になるため)。
     */
    private void appendStatLines(List<Component> lines, Map<String, Double> buffs) {
        if (buffs == null) {
            return;
        }
        Map<String, StatDisplaySpec> table = loreConfig.displayTable();
        buffs.forEach((rawKey, value) -> {
            if (value == null || value == 0.0) {
                return;
            }
            String key = StatKeys.canonical(rawKey);
            StatDisplaySpec spec = lookup(table, key);
            String label = spec != null ? spec.displayName() : key;
            String rendered = spec != null ? spec.renderValue(value) : trimZeros(value);
            lines.add(effectLine(label, rendered));
        });
    }

    private static StatDisplaySpec lookup(Map<String, StatDisplaySpec> table, String canonicalKey) {
        StatDisplaySpec direct = table.get(canonicalKey);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, StatDisplaySpec> entry : table.entrySet()) {
            if (StatKeys.canonical(entry.getKey()).equals(canonicalKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Component effectLine(String label, String value) {
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false);
    }

    /** {@code 1.20} ではなく {@code 1.2}、{@code 1.0} は {@code 1} で出す。 */
    static String trimZeros(double value) {
        String text = String.format("%.2f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    /** ポーション効果のレベル表記(1..10のみローマ数字、それ以上は素の数値)。 */
    static String romanNumeral(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(level);
        };
    }
}
