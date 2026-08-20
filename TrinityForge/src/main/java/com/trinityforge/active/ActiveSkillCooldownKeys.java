package com.trinityforge.active;

import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Derives the per-{@link ActiveSkill} CT短縮ステータスキー and verifies every registered
 * {@link ActiveSkill} actually has one wired into {@link StatVocabulary} (2026-07-25 CT設計一本化 §2).
 *
 * <p><b>背景</b>: 旧設計はアクティブスキル全体で1本の {@code skill-cooldown-reduction} を共有していた
 * ({@link ActivationDispatcher}があらゆるアクティブスキルに対して読む) — このため、ある採掘スキルツリーの
 * ノードで得たCT短縮が、無関係の別アクティブスキルにも波及していた(伐採ツリーの類似ノードを本設計の対象外に
 * した理由もこれ)。この波及を断つため、CT短縮ステータスを {@link ActiveSkill#id()} 単位のキーへ分割する
 * ({@code <id>-cooldown-reduction}、既存の {@code cooldown-reduction}(アイテムCT)と同じ
 * {@code <領域>-cooldown-reduction} 命名規約。2026-07-31 に廃止した {@code bow-cooldown-reduction} も
 * この規約の一員だった)。
 *
 * <p><b>キーは自動導出(手打ちマッピング表を持たない)</b>: {@link #forSkill(String)} は
 * {@link ActiveSkill#id()} から機械的にキーを組み立てるので、新しい {@link ActiveSkill} を実装しても
 * このクラス自体を編集し忘れる余地はない。ただし、そのキーが実際に perk-buff パイプラインを流れるためには
 * {@link StatVocabulary} の {@code ATTACK_KEYS} への登録が別途必要 — これを忘れると
 * {@link com.trinityforge.skilltree.runtime.PerkBuffResolver} がキーを無音で捨てる(Channel.NONE)ため、
 * CT短縮が黙って効かなくなる。{@link #verifyRegistered(ActiveSkillRegistry)} はこの登録漏れを
 * プラグイン起動時に{@link IllegalStateException}で検出する — {@code VanillaAttributeDefaults}が
 * {@code StatVocabulary}のATTRIBUTEチャンネルとのドリフトを静的初期化時に検出するのと同じ「静かに壊れるより
 * 騒がしく落ちる」方針。{@link ActiveSkillRegistry}はプラグイン有効化時に初めて構築される(静的に存在しない)
 * ため、{@code VanillaAttributeDefaults}と違って static initializer では書けず、
 * {@code TrinityForge#onEnable}が登録直後に明示的に呼び出す実行時アサーションとして実装している。
 */
public final class ActiveSkillCooldownKeys {

    private ActiveSkillCooldownKeys() {
    }

    /**
     * The canonical CT-reduction stat key for {@code skillId}: {@code <skillId>-cooldown-reduction}
     * (canonicalized via {@link StatKeys#canonical}, so kebab-/snake-case authoring both resolve here).
     */
    public static String forSkill(String skillId) {
        Objects.requireNonNull(skillId, "skillId");
        return StatKeys.canonical(skillId + "-cooldown-reduction");
    }

    /**
     * Verifies every {@link ActiveSkill} in {@code registry} has its {@link #forSkill(String)} key
     * registered in {@link StatVocabulary}'s ATTACK channel (the channel {@link ActivationDispatcher}/
     * {@code ActiveCommand} read the reduction from via {@code PlayerStatAggregator}). Call once at
     * plugin enable, right after every {@link ActiveSkill} is registered.
     *
     * @throws IllegalStateException listing every offending {@code id -> key} pair when at least one
     *         registered skill is missing its key — a new {@link ActiveSkill} whose author forgot to add
     *         {@code "<id>-cooldown-reduction"} to {@code StatVocabulary.ATTACK_KEYS} fails loudly at
     *         startup instead of silently never being CT-reducible.
     */
    public static void verifyRegistered(ActiveSkillRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        List<String> missing = new ArrayList<>();
        for (ActiveSkill skill : registry.all()) {
            String key = forSkill(skill.id());
            if (!StatVocabulary.isAttack(key)) {
                missing.add(skill.id() + " -> \"" + key + "\"");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "ActiveSkill(s) missing their StatVocabulary CT-reduction key (must be registered in "
                            + "StatVocabulary.ATTACK_KEYS): " + missing
                            + ". Add \"<id>-cooldown-reduction\" (snake_case) for each to StatVocabulary, "
                            + "PercentStatNormalize.RATE_KEYS, and stats/lore.yml.");
        }
    }
}
