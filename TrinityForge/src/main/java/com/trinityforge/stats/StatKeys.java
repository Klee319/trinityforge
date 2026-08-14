package com.trinityforge.stats;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Canonical stat-key normalization: the single join point where the roll/lore key space and the
 * attribute-map key space meet (gap I1, OPEN_DECISIONS / COMBAT_SYSTEM_SPEC section 5).
 *
 * <p>The roll table ({@code stats/roll.yml}) and lore table ({@code stats/lore.yml}) author stat
 * keys in kebab-case (e.g. {@code attack-damage}); the attribute mapping table
 * ({@code stats/attribute-map.yml}) and the vanilla attribute registry use snake_case
 * (e.g. {@code attack_damage}). Without a shared canonical form the projection lookup misses and
 * no attribute modifier is ever applied. Folding both separators to {@code '_'} (and lowercasing)
 * yields one canonical key so the two sides agree regardless of which convention a config author
 * happened to use.
 */
public final class StatKeys {

    private static final Logger LOG = Logger.getLogger(StatKeys.class.getName());

    /**
     * 旧綴り→新綴りの後方互換エイリアス表。
     * <ul>
     *   <li>2026-07-26 ユーザー決定: {@code weapon-cooldown} を {@code item-cooldown} へキー統合。</li>
     *   <li>2026-07-26 ユーザー決定: 「効率」ステータス統合。{@code tool-enchant-efficiency}
     *       ({@code stats/item-stats.yml} にアイテム単位で書く、{@link ItemAssembler} が
     *       {@code tool-enchant-*} 接頭辞ルールで焼き込む経路)を {@code gathering-efficiency}
     *       (総合ステータス、{@code com.trinityforge.gathering.GatheringEfficiencyEnchantApplier} が
     *       メインハンドの農業/採掘/伐採/切削ツールへ実行時に反映する経路)へ統合する。
     *       同じ「効率強化エンチャントのレベル」を決める経路が2つ並存していたための統合であり、
     *       このエイリアスにより {@code tool-enchant-efficiency} キーは {@link ItemAssembler} の
     *       {@code TOOL_ENCHANT_PREFIX} 判定にもう引っかからなくなる(canonical化の時点で
     *       {@code gathering_efficiency} に化けるため) — 二重付与が自動的に消える。</li>
     * </ul>
     *
     * <p><b>なぜ必要か</b>: このメソッドがキー空間の唯一の合流点であり、他にエイリアス機構が存在しない。
     * 素のリネームだけだと、ユーザーが手編集した yml に残っている旧キー(例: {@code weapon-cooldown} /
     * {@code weapon_cooldown}、{@code tool-enchant-efficiency} / {@code tool_enchant_efficiency})は
     * canonical化しても新キーと一致せず、警告もエラーも出さずにそのステータスが黙って効かなくなる
     * (出荷ymlは配備で上書きされるが、手編集ymlはその限りではない)。ここで旧綴りを新綴りへ読み替える
     * ことで、そのリスクを潰す。
     *
     * <p><b>いつ消してよいか</b>: 旧キーを書いた yml が実運用に一切残っていないと判断できる時点
     * (十分な移行期間を置いたうえで、配備先のymlを実際に確認した後)で、該当エントリと
     * {@link #WARNED_LEGACY_KEYS} 一式を削除してよい。
     */
    private static final Map<String, String> LEGACY_KEY_ALIASES = Map.of(
            "weapon_cooldown", "item_cooldown",
            "tool_enchant_efficiency", "gathering_efficiency",
            // 2026-08-14 ユーザー決定: 「EXP増加(エンチャント)」の統合。enchant_exp_gain_bonus は
            // 「エンチャント時に得る ENCHANTING スキルEXP」の増減だったが、ENCHANTING への EXP 付与点は
            // NativeSkillExperienceListener#onEnchant の1箇所しかない(全数確認済み)ため、
            // 職業EXP増加の共通機構 enchanting_exp_bonus(<スキルID>_exp_bonus)と同じ量に
            // 別経路で掛かっているだけの重複だった。lore にも「EXP増加(エンチャント)」と
            // 「職業EXP増加(エンチャント)」が並んでいて区別できない状態だったので後者へ一本化する。
            // ★このエイリアスを入れる以上、onEnchant 側の乗算は必ず消すこと。残すと同じ倍率が
            //   listener と NativeProgressionService の二重で掛かる。
            "enchant_exp_gain_bonus", "enchanting_exp_bonus");

    /**
     * 旧綴り検出時に警告を1回だけ出すための既知集合。canonical() はホットパス(攻撃判定のたびに
     * 呼ばれる)なので、同じキーで毎回ログを吐くと実サーバでログが埋まる — スレッドセーフな
     * {@code newKeySet()} で「警告済みキー」を覚え、初回のみ出力する。
     */
    private static final Set<String> WARNED_LEGACY_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private StatKeys() {
    }

    /**
     * Returns the canonical (snake_case, lowercased) form of a stat key, treating {@code '-'} and
     * {@code '_'} as the same separator so kebab-case and snake_case spellings resolve identically.
     *
     * <p>2026-07-26: 旧綴り({@link #LEGACY_KEY_ALIASES})を検出した場合は新綴りへ読み替える
     * (詳細は同フィールドのJavadoc参照)。
     */
    public static String canonical(String statKey) {
        Objects.requireNonNull(statKey, "statKey");
        String folded = statKey.toLowerCase(Locale.ROOT).replace('-', '_');
        String alias = LEGACY_KEY_ALIASES.get(folded);
        if (alias == null) {
            return folded;
        }
        if (WARNED_LEGACY_KEYS.add(folded)) {
            LOG.warning(() -> "旧stat キー '" + folded + "' は非推奨です。'" + alias
                    + "' へ読み替えて処理します(yml設定を更新してください)。");
        }
        return alias;
    }
}
