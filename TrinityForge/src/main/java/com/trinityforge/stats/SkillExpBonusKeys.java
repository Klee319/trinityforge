package com.trinityforge.stats;

import com.trinityforge.progression.core.SkillId;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「職業EXP増加(スキル別)」ステの正規化済みキー集合（2026-08-05）。
 *
 * <p>実行時の消費側（{@code TrinityForge} が組み立てる {@code PerSkillExpBonus} ラムダ）は
 * <b>スキルIDから機械的に</b> {@code canonical(skillId + "_exp_bonus")} を引くので、キーを増やす
 * 作業は「語彙・%矯正・分類・lore への登録」だけで済む。ところが 2026-08-02 の新設時は
 * WOODCUTTING / FARMING / DIGGING の3件を4箇所へ手書きで並べていたため、実サーバから
 * 「職業EXP増加のステータスの種類が、総合と、掘削、農業、伐採しかない」と指摘された。
 * <b>手書きの列挙が3箇所以上に散ると必ず腐る</b>ので、ここで一度だけ導出して各所から参照する。
 *
 * <p><b>POWER を含めない理由</b>: POWER EXP はプレイヤー行動から直接付与されない。
 * 他スキルのレベルアップの副作用として {@code NativeProgressionService} が内部で加算するだけで、
 * その経路は倍率適用より後段にある。したがって {@code power_exp_bonus} を作っても
 * <b>一度も読まれない死んだキー</b>になる。全スキル一律の {@code skill_exp_bonus}
 * （lore 表記「職業EXP増加」）が POWER を含む全付与経路に効くので、そちらで代替する。
 */
public final class SkillExpBonusKeys {

    private SkillExpBonusKeys() {}

    /** キー接尾辞。{@code PerSkillExpBonus} の実装が組み立てる文字列と一致させること。 */
    public static final String SUFFIX = "_exp_bonus";

    private static final Set<String> KEYS = buildKeys();

    private static Set<String> buildKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String skillId : SkillId.ALL) {
            if (SkillId.POWER.equals(skillId)) {
                continue;
            }
            keys.add(StatKeys.canonical(skillId + SUFFIX));
        }
        return Set.copyOf(keys);
    }

    /** POWER を除く15スキル分の正規化済みキー（{@code woodcutting_exp_bonus} 等）。 */
    public static Set<String> all() {
        return KEYS;
    }

    /** {@code canonicalKey} がスキル別EXP増加ステなら {@code true}。 */
    public static boolean contains(String canonicalKey) {
        return canonicalKey != null && KEYS.contains(canonicalKey);
    }
}
