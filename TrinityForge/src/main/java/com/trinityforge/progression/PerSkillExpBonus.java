package com.trinityforge.progression;

import java.util.UUID;

/**
 * 付与先スキルごとに効くEXP倍率の供給元（2026-08-02 柱5-3）。
 *
 * <p>全スキル一律の {@code skill_exp_bonus} では「樵の大斧は伐採EXPだけ +15%」のような
 * 単発装備の個性を表現できないため追加した。実装は装備/パークの合算ステから
 * {@code <スキルID>_exp_bonus}（例: {@code woodcutting_exp_bonus}）を引く。
 *
 * <p><b>返す値は加算項</b>（0.15 = +15%）。倍率ではないので、何も無いときは 0.0 を返すこと。
 */
@FunctionalInterface
public interface PerSkillExpBonus {

    /** 何も上乗せしない実装。 */
    PerSkillExpBonus NONE = (playerId, skillId) -> 0.0;

    /**
     * @param playerId 付与対象
     * @param skillId  正規化済みのスキルID（{@code woodcutting} / {@code farming} / {@code digging} …）
     * @return 加算項。非有限値を返した場合、呼び出し側は 0 として扱う
     */
    double bonusFor(UUID playerId, String skillId);
}
