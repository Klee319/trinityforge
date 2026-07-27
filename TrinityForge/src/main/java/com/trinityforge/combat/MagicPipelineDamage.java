package com.trinityforge.combat;

/**
 * Main-thread transient marker for 課題2(魔法RESISTANCE二重軽減修正)のレビュー修正: {@code
 * DamageCause.MAGIC} だけでは「TrinityForgeの対称パイプラインを通った魔法ダメージ」を判別できない。
 * Bukkitの定義上 {@code MAGIC} は「ダメージポーション(Instant Damage)またはスプラッシュ負傷ポーション、
 * 状況によりエヴォーカーの牙/ガーディアンのビーム、{@code /damage} コマンドのmagic指定」も同じcauseで届く
 * — これらは {@code SymmetricCombatService#resolveDefender} の {@code potionResistanceReduction} を
 * 一切通っていないため、cause だけでRESISTANCE modifierを0化すると「耐性ポーション所持者がバニラの
 * 負傷ポーションに対して耐性を完全に失う」という別の非対称バグを生む(初版の実装で発生していた実害)。
 *
 * <p>Contract (mirrors {@link MobAbilityDamage}/{@link EliteCombatDelegation}, the established
 * precedent for this exact class of "an addon already owns this hit" signal): ArsPaperフォークの
 * {@code TrinityForgeBridge#applyMagicDamage} が {@code victim.damage(...)} を呼ぶ直前に {@link #mark()}
 * し、{@code finally} で {@link #clear()} する。{@link com.trinityforge.listeners.MagicResistanceFoldListener}
 * は「cause=MAGIC かつ {@link #isActive()}」の両方が真のときだけRESISTANCE modifierを0化することで、
 * バニラの魔法ダメージ(マーク無し)には一切触れない。
 *
 * <p>マークは真偽値ではなく DEPTH COUNTER にすること({@link MobAbilityDamage} と同じ理由): 魔法ダメージの
 * 適用(グリフのオンヒット効果等)が同期的に別の魔法ダメージを誘発しうる。その場合、内側の呼び出しの
 * {@code finally} が先に走ってマークを完全に剥がしてしまうと、まだ処理中の外側の呼び出しに対する
 * イベント発火(あるいは同一tick内の後続処理)がマーク無しとして扱われてしまう。深度カウンタなら、内側が
 * 1減らしても外側の分がまだ残るため、外側の呼び出し全体を通してマークが有効なまま保たれる。
 */
public final class MagicPipelineDamage {

    private static int depth = 0;

    private MagicPipelineDamage() {
    }

    /** Marks the immediately following synchronous damage call as TF-pipeline-priced magic damage. */
    public static void mark() {
        depth++;
    }

    /** Clears one mark; the marking caller must call this in a {@code finally}. */
    public static void clear() {
        if (depth > 0) depth--;
    }

    /** True while inside a marked TF-pipeline magic-damage call. Read-only (the marker's finally clears). */
    public static boolean isActive() {
        return depth > 0;
    }
}
