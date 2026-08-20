package com.trinityforge.progression;

import java.util.UUID;

/**
 * プレイヤーの総合戦闘レベル({@code progression/combat-level.yml} の pillar 式)の読み出し口。
 *
 * <p>M-2(手懐けた友好モブのレベルを飼い主の総合戦闘レベルで決める)のために切り出した。
 * 実体は {@link com.trinityforge.combat.SymmetricCombatService#combatLevelOf(UUID)}
 * （= {@code combatLevelConfig.model().compute(skillLevelSource.levelsOf(id))}）そのものだが、
 * {@code MobTypeSpawnListener} が {@code SymmetricCombatService} 自体に依存すると
 * （召喚/攻撃力ではなく単なるレベル読み出しのために）依存が重くなるため、
 * 1メソッドのこの窓口だけを注入する。
 */
@FunctionalInterface
public interface CombatLevelSource {

    /** プレイヤーの現在の総合戦闘レベル。データが読めない場合は 0(未成長扱い)を返すこと。 */
    int combatLevelOf(UUID playerId);

    /** データ未配線時の既定(常に0)。summoned: の {@link SkillLevelSource#EMPTY} と同じ安全側フォールバック。 */
    CombatLevelSource EMPTY = playerId -> 0;
}
