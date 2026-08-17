package com.trinityforge.listeners;

/**
 * 「ロールバフとして自分が付けたポーション効果か」の判定(2026-08-18、ユーザー報告
 * 「幸運のエフェクトが消える不具合直ってなくない？」の TF 側の原因)。
 *
 * <h2>直したバグ</h2>
 * {@link RoleBuffListener#refreshSupportBuff} は、ロールを同期する前に
 * <b>サポートロールが付け得るポーション型を全部無条件に {@code removePotionEffect} していた</b>。
 * {@code progression/role-buffs.yml} の {@code fisher} は {@code LUCK} を付けるため、
 * <b>幸運は常にこの一括除去の対象</b>で、プレイヤーや管理コマンドが付けた LUCK も
 * ログイン/リスポーン/ロール変更のたびに黙って消えていた
 * (ArsPaper 側の同型事故 {@code ThreadPotionOwnership} と同じ構造)。
 *
 * <h2>再起動を跨いでも効く識別子を選んだ理由</h2>
 * プロセス内の台帳では<b>再起動後に古いロールのバフを剥がせなくなる</b>
 * (ポーション効果はログアウトを跨いで残るため、ロールを変えても前のバフが残り続ける)。
 * そこで効果の<b>形</b>で判定する:
 * <ul>
 *   <li>TF/Ars が付ける効果は必ず {@code ambient=true, particles=false}。
 *       一方 {@code /effect give} は {@code ambient=false, particles=true} なので、
 *       この2つの組み合わせはプレイヤー起因の効果とは一致しない。</li>
 *   <li>amplifier が該当ロールの spec と一致すること。</li>
 *   <li>残り時間が spec の付与時間以下であること(自前の効果は減るだけで増えない)。
 *       無期限効果は spec が有限なので絶対に自前ではない(スレッド由来など)。</li>
 * </ul>
 * どれか1つでも合わなければ<b>触らない</b>(安全側=消さない方向)。誤って残った場合の害は
 * 「古いバフが自然消滅するまで残る」だけで、誤って消した場合の害
 * (プレイヤーが付けた効果が消える)より小さい。
 */
public final class RolePotionOwnership {

    private RolePotionOwnership() {
    }

    /**
     * @param specAmplifier    そのロールの {@code potion-buff.amplifier}
     * @param specDurationTicks そのロールの {@code potion-buff.duration}
     * @param currentAmplifier 今付いている効果の amplifier
     * @param ambient          今付いている効果の {@code isAmbient()}
     * @param particles        今付いている効果の {@code hasParticles()}
     * @param infinite         今付いている効果の {@code isInfinite()}
     * @param currentDuration  今付いている効果の残り tick
     */
    public static boolean mayRemoveRoleBuff(int specAmplifier, int specDurationTicks,
                                             int currentAmplifier, boolean ambient, boolean particles,
                                             boolean infinite, int currentDuration) {
        if (infinite) {
            return false;
        }
        if (currentAmplifier != specAmplifier) {
            return false;
        }
        if (!ambient || particles) {
            return false;
        }
        return currentDuration > 0 && currentDuration <= specDurationTicks;
    }
}
