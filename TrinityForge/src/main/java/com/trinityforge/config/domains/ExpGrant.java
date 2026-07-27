package com.trinityforge.config.domains;

import java.util.Objects;

/**
 * アチーブメント/コレクション報酬で付与する職業EXP1件({@code rewards.job-exp[]})。
 *
 * @param skill  {@link com.trinityforge.progression.core.SkillId#ALL} のいずれか(大文字正規化済み)
 * @param amount 付与量(有限・非0であることをパーサ側で保証する)
 */
public record ExpGrant(String skill, double amount) {
    public ExpGrant {
        Objects.requireNonNull(skill, "skill");
    }
}
