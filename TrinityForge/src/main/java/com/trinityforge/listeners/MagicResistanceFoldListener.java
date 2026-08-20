package com.trinityforge.listeners;

import com.trinityforge.combat.MagicPipelineDamage;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * TF対称パイプラインを通った魔法ダメージ(cause=MAGIC かつ {@link MagicPipelineDamage#isActive()})の
 * バニラ RESISTANCE modifier を0化する(課題2)。
 *
 * <p>{@code SymmetricCombatService#resolveDefender} は potionResistanceReduction(Lv×10%)を
 * 全ダメージタイプ共通でTF耐性%へ加算済み — つまりArsPaperフォークの
 * {@code TrinityForgeBridge#applyMagicDamage} が返す最終魔法ダメージには、既にTF側の耐性軽減が
 * 織り込まれている。フォークはその最終ダメージを {@code DamageType.MAGIC} の {@code DamageSource}
 * (causingEntity/directEntityともに詠唱者)で適用するため、Bukkitはこれを cause=MAGIC の
 * {@code EntityDamageEvent} として発火し、バニラの {@code DamageModifier.RESISTANCE}(Lv×20%)が
 * そのまま重ねて掛かってしまう(Resistance Iで実効28%軽減という物理/魔法非対称バグ)。
 * これを防ぐため、cause=MAGICのイベントに限り RESISTANCE modifier だけを0にする
 * (盾BLOCKING・ABSORPTION等の他modifierには一切触れない)。
 *
 * <p>cause=MAGIC 確定の根拠: {@code TrinityForgeBridge#applyMagicDamage} は
 * {@code DamageSource.builder(DamageType.MAGIC)...build()} → {@code victim.damage(finalDamage, source)}
 * を主経路として使う(この対応関係は同フォークの {@code ManaRecoveryListener} のコメントと
 * {@code NativeCombatPerkListenerTest#meleeOnlyPerksAreGatedToRealMeleeCausesSoMagicDoesNotTriggerThem}
 * が cause=MAGIC を前提に検証済み)。同メソッドには {@code DamageSource} 構築が失敗した場合の
 * フォールバック({@code victim.damage(finalDamage, caster)}, cause=ENTITY_ATTACK)もあるが、
 * そちらは既に {@code CombatListener.FOLDED_MODIFIERS} が(物理経路として)RESISTANCEを0化済みなので、
 * ここでは意図的に対象外にする — cause集合を広げて物理側まで巻き込まないため。
 *
 * <p><strong>なぜ cause=MAGIC だけでは不十分か(レビュー修正・実害のあるリグレッションだった)</strong>:
 * {@code DamageCause.MAGIC} は TF/ArsPaper の魔法経路の専有ではない。Bukkitの定義は「ダメージポーション
 * またはスプラッシュ負傷ポーション・状況によりエヴォーカーの牙/ガーディアンのビーム・{@code /damage}
 * コマンドのmagic指定」も同じcauseで届く。これらはTFの対称パイプラインを一切通っておらず
 * ({@code resolveDefender} の {@code potionResistanceReduction} が加算されていない)、cause だけを
 * ゲートにするとバニラの負傷ポーションに対してまで RESISTANCE を0化してしまい、「耐性ポーションを
 * 飲んだプレイヤーがバニラの負傷ポーションに対して耐性を完全に失う」という別の非対称バグを生む
 * (初版実装で実際に混入していた)。これを避けるため、{@link MagicPipelineDamage}
 * ({@link com.trinityforge.combat.MobAbilityDamage}/{@link com.trinityforge.combat.EliteCombatDelegation}
 * と同型の「main-thread深度カウンタ」マーカー、既存の確立した手本に倣う)で「TFパイプラインを通った
 * 魔法ヒットそのもの」を明示的に区別する。ArsPaperフォークの {@code applyMagicDamage} が
 * {@code victim.damage(...)} 呼出しの直前後で {@code mark()}/{@code clear()}(finally)する契約になっており、
 * cause=MAGIC のイベントであっても {@link MagicPipelineDamage#isActive()} が偽(=マーク無し=バニラ発生源)
 * のときは何もしない。
 *
 * <p><b>⚠️ なぜ RESISTANCE だけで、バニラ {@code MAGIC} modifier(防護/飛び道具耐性エンチャント)は
 * 0化しないのか(2026-07-31 F5 指摘8 への回答。「物理と同じように MAGIC も畳むべき」は誤り)</b>:
 * 物理経路は {@code SymmetricCombatService} の物理エントリポイントが {@code extraDefense} 引数へ
 * {@code vanillaProtectionDefense}({@link com.trinityforge.combat.DefenseEnchantmentBridge})を
 * <b>渡しているから</b>畳んでよい。一方 {@code magicalFinalDamage} / {@code magicalFinalDamageFlat} /
 * {@code magicalFinalDamageFromMob} は {@code extraDefense} を渡さない
 * ({@code componentResult} の3引数オーバーロード = {@code DefenseStats.NONE})。
 * {@code SymmetricCombatService} の {@code extraDefense} 引数の javadoc にも
 * 「magical/DoT/hybrid 経路は TF が MAGIC modifier を0化しないのでこの再導出を二重適用してはならない」
 * と明記されている。つまり<b>魔法に対する防護エンチャントの軽減はバニラ modifier だけが担っている</b>ので、
 * ここで0化すると「魔法に対して防護が丸ごと効かなくなる」無言削除(B1型)になる。
 * 二重軽減は起きていない。なお {@code ARMOR} modifier はバニラの {@code minecraft:magic} が
 * {@code bypasses_armor} なので cause=MAGIC では最初から適用されない(触る必要がない)。
 *
 * <p>物理経路(cause=ENTITY_ATTACK等)は既に {@code CombatListener.FOLDED_MODIFIERS} が同じ理由で
 * 0化済み(このリスナーの対象外)。DoT(POISON/WITHER)は {@link DotDamageListener} が別途処理する。
 * {@link DotDamageListener} に統合しなかったのは、DoTが「BASEダメージそのものを
 * {@code bleedFinalDamageFlat} で再計算する」責務を持つのに対し、魔法はTFパイプラインが既にBASEを
 * 確定済みで RESISTANCE modifier の後始末だけが必要という、性質の異なる処理だから
 * (1メソッドに詰め込むとcause分岐が読みにくくなるため分離した)。
 *
 * <p>優先度はHIGH: ArsPaperフォークの {@code ManaRecoveryListener}(MONITOR)が
 * {@code getFinalDamage()>0} でマナ回復判定するより前に、必ずRESISTANCE 0化を確定させておく必要が
 * あるため({@code CombatListener}/{@link DotDamageListener} と同じ優先度に揃える)。Bukkitのダメージ
 * イベント発火は {@code victim.damage(...)} 呼出しに対して同期的なので、フォークの {@code mark()}/
 * {@code clear()} の間でこのHIGHハンドラも実行される(EliteCombatDelegationと同じ前提)。
 */
public final class MagicResistanceFoldListener implements Listener {

    @SuppressWarnings("deprecation") // DamageModifier is deprecated-for-removal, same as CombatListener/DotDamageListener.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMagicDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.MAGIC) {
            return;
        }
        if (!MagicPipelineDamage.isActive()) {
            return; // cause=MAGICだがTFパイプライン外(バニラ負傷ポーション等) — 触れない。
        }
        EntityDamageEvent.DamageModifier modifier = EntityDamageEvent.DamageModifier.RESISTANCE;
        if (event.isApplicable(modifier)) {
            event.setDamage(modifier, 0.0);
        }
    }
}
