package com.trinityforge.stats;

import com.trinityforge.progression.core.SkillId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「破壊時バニラEXP増加(採取スキル別)」ステの正規化済みキー集合 (2026-08-15)。
 *
 * <p><b>背景 — 解放は職業別なのに倍率だけ共通で漏れていた</b>:
 * {@code feature:break-vanilla-exp}(解放ゲート)は 2026-08-01 に
 * 「破壊が属する採取スキルのツリーに置かれた配置だけを見る」形へ直したが、倍率側の
 * {@code break_vanilla_exp_bonus} は<b>スコープを持たない普通の総合ステ</b>のままだった。
 * 出荷スキルツリーは mining/woodcutting/digging×2/farming×2 の計6ノードがこのキーへ
 * {@code 0.5} を配っているので、<b>採掘ツリーで取った +50% が伐採・整地・農業の破壊EXPにも
 * そのまま乗っていた</b>(全部取ると解放済みの全職で +300%)。ノードの説明文は
 * 「破壊で1.5倍」「破壊で2倍」とツリー内で完結する前提の書き方で、表示と挙動が食い違っていた。
 *
 * <p>そこで {@code <採取スキルID>_break_vanilla_exp_bonus} を導入し、出荷6ノードを
 * 各ツリーのキーへ移した。消費側({@code NativeSkillExperienceListener#grantBreakVanillaExp})は
 * <b>破壊が属する採取スキルから機械的に</b> {@link #forSkill(String)} で引くので、キーの追加作業は
 * 「語彙・分類・{@code /tf stats} タブ・lore」への登録だけで済む
 * ({@link SkillExpBonusKeys} と同じ構造。手書きの列挙が3箇所以上に散ると必ず腐る)。
 *
 * <p><b>スコープ無しの {@code break_vanilla_exp_bonus} は残す</b>: 「採取全般の破壊EXPを増やす」
 * 意味のキーとして引き続き加算される(常時全源の {@code vanilla_exp_bonus} とは別レイヤー)。
 * 出荷スキルツリーからは使っていない — 使うと再び職業間で漏れるため。
 *
 * <p><b>4スキルしか作らない理由</b>: 破壊時バニラEXPは
 * {@code NativeSkillExperienceListener#grantGathering} が採取扱いと判定した破壊にしか出ず、
 * その戻り値は FARMING/WOODCUTTING/DIGGING/MINING のいずれかに限られる。他スキル分を作っても
 * <b>一度も読まれない死んだキー</b>になる(POWER を除く {@link SkillExpBonusKeys} と同じ判断)。
 *
 * <p><b>2026-08-18 (W-58) — {@code feature:break-vanilla-exp} 解放ゲート自体もスキルごとに分割</b>:
 * このクラスがこれまで扱っていたのは<b>倍率</b>({@code <skill>_break_vanilla_exp_bonus}、通常stat)
 * だけで、<b>解放</b>({@code feature:break-vanilla-exp}、{@code FeatureEffectRegistry}の
 * {@code feature:}語彙)は4ツリー共通の1本のidのままだった。倍率側と対称になるよう
 * {@link #featureId(String)} を追加し、gate id も
 * {@code break-vanilla-exp-mining}/{@code -digging}/{@code -farming}/{@code -woodcutting} の
 * スキルごとの id へ分割した(登録は {@code FeatureEffectRegistry}、消費側は
 * {@code NativeSkillExperienceListener#grantBreakVanillaExp} がこの新メソッドを経由する)。
 * <b>解放状態の永続化(PlayerData#heldPerks)はノードID(PerkNaming.perkId(skill, nodeId))基準</b>で、
 * この gate id 文字列そのものを保存するわけではないため、この改名にプレイヤーデータ移行は不要
 * (既存プレイヤーの解放状態は無傷)。3引数の木限定 {@code DedicatedEffectsConfig#isActive/valueMax}
 * 呼び出しは、gate id が既にスキル専用になった後も引き続き保険として維持する。
 */
public final class BreakVanillaExpBonusKeys {

    private BreakVanillaExpBonusKeys() {}

    /** キー接尾辞。{@link #forSkill(String)} が組み立てる文字列と一致させること。 */
    public static final String SUFFIX = "_break_vanilla_exp_bonus";

    /** {@code feature:} gate id の接頭辞。{@link #featureId(String)} が組み立てる文字列と一致させること。 */
    public static final String FEATURE_ID_PREFIX = "break-vanilla-exp-";

    /**
     * 破壊が採取扱いになりうる4スキル。
     * {@code NativeSkillExperienceListener#grantGathering} の戻り値と一致させること。
     */
    public static final List<String> GATHERING_SKILLS =
            List.of(SkillId.FARMING, SkillId.WOODCUTTING, SkillId.DIGGING, SkillId.MINING);

    private static final Set<String> KEYS = buildKeys();

    private static Set<String> buildKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String skillId : GATHERING_SKILLS) {
            keys.add(StatKeys.canonical(skillId + SUFFIX));
        }
        return Set.copyOf(keys);
    }

    /** 4採取スキル分の正規化済みキー（{@code mining_break_vanilla_exp_bonus} 等）。 */
    public static Set<String> all() {
        return KEYS;
    }

    /**
     * 採取スキルIDに対応するキー。採取扱いにならないスキル(や {@code null})なら {@code null} を返すので、
     * 呼び出し側は「存在しないキーを totalOf に渡す」ことなく素通りできる。
     */
    public static String forSkill(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        String key = StatKeys.canonical(skillId + SUFFIX);
        return KEYS.contains(key) ? key : null;
    }

    /** {@code canonicalKey} が採取スキル別の破壊時バニラEXP増加ステなら {@code true}。 */
    public static boolean contains(String canonicalKey) {
        return canonicalKey != null && KEYS.contains(canonicalKey);
    }

    /**
     * 採取スキルIDに対応する {@code feature:} gate id(bare、prefix無し。例:
     * {@code MINING} -&gt; {@code break-vanilla-exp-mining})。{@link #forSkill(String)} と対になる
     * gate版 — 採取扱いにならないスキル(や {@code null}/空文字)なら {@code null} を返すので、呼び出し側は
     * 「存在しない feature id を isActive/valueMax に渡す」ことなく素通りできる。
     *
     * <p>大文字小文字は問わない({@link SkillId} 定数は大文字だが、比較は正規化して行う)。
     */
    public static String featureId(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        String normalized = skillId.trim().toUpperCase(java.util.Locale.ROOT);
        for (String gatheringSkill : GATHERING_SKILLS) {
            if (gatheringSkill.equals(normalized)) {
                return FEATURE_ID_PREFIX + gatheringSkill.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }
}
