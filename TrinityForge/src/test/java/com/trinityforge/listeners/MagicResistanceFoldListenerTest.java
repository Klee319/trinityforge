package com.trinityforge.listeners;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.trinityforge.combat.MagicPipelineDamage;

import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 課題2の回帰テスト: {@link MagicResistanceFoldListener} が
 * 「cause=MAGIC かつ {@link MagicPipelineDamage#isActive()}」の両方が真のときだけ バニラ
 * {@code DamageModifier.RESISTANCE} を0化し、他causeやRESISTANCE以外のmodifierには一切触れない
 * ことを検証する。cause=MAGICだがマーカー無効(=バニラ負傷ポーション相当)のケースは、レビュー指摘の
 * 実害リグレッション(耐性ポーション所持者がバニラ負傷ポーションへの耐性を失う)を固定するテスト。
 * ArsPaperフォークの {@code TrinityForgeBridge#applyMagicDamage} が
 * {@code DamageSource.builder(DamageType.MAGIC)} で発火させるイベントを cause=MAGIC として
 * 直接シミュレートし、マーカーは {@link MagicPipelineDamage#mark()}/{@code clear()} を明示的に
 * 操作して両ケースを再現する(ArsPaperフォーク自体はTFのテスト対象外のため、TF側はこの契約を保証する)。
 */
@SuppressWarnings("removal") // deprecated-for-removal modifiers-map ctor is the only way to construct a
                              // multi-modifier EntityDamageEvent without a live server.
class MagicResistanceFoldListenerTest {

    private ServerMock server;
    private MagicResistanceFoldListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        listener = new MagicResistanceFoldListener();
        resetMarker();
    }

    @AfterEach
    void tearDown() {
        resetMarker();
        MockBukkit.unmock();
    }

    /** static な深度カウンタなので、テスト間の汚染を防ぐため0まで落としておく。 */
    private void resetMarker() {
        while (MagicPipelineDamage.isActive()) {
            MagicPipelineDamage.clear();
        }
    }

    private EntityDamageEvent damageEvent(Player victim, Player caster, EntityDamageEvent.DamageCause cause) {
        Map<EntityDamageEvent.DamageModifier, Double> modifiers =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 6.0);
        modifiers.put(EntityDamageEvent.DamageModifier.RESISTANCE, -1.2);

        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions =
                new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        functions.put(EntityDamageEvent.DamageModifier.BASE, Functions.identity());
        functions.put(EntityDamageEvent.DamageModifier.RESISTANCE, Functions.constant(-1.2));

        DamageSource source = DamageSource.builder(
                cause == EntityDamageEvent.DamageCause.MAGIC ? DamageType.MAGIC : DamageType.GENERIC)
                .withCausingEntity(caster).withDirectEntity(caster).build();
        return new EntityDamageEvent(victim, cause, source, modifiers, functions);
    }

    @Test
    void magicCauseWithActiveMarkerZeroesResistanceModifierButNotBase() {
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();
        EntityDamageEvent event = damageEvent(victim, caster, EntityDamageEvent.DamageCause.MAGIC);

        MagicPipelineDamage.mark();
        try {
            listener.onMagicDamage(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        assertEquals(0.0, event.getDamage(EntityDamageEvent.DamageModifier.RESISTANCE), 1e-9,
                "cause=MAGICかつマーカー有効(=TFパイプラインを通った魔法ヒット)ではRESISTANCEを0化するはず"
                        + "(TF耐性%と二重にならないよう)");
        assertEquals(6.0, event.getDamage(EntityDamageEvent.DamageModifier.BASE), 1e-9,
                "RESISTANCE以外のmodifier(BASE)には触れないはず");
    }

    @Test
    void magicCauseWithoutActiveMarkerLeavesResistanceModifierUntouched() {
        // レビュー指摘の実害リグレッション固定テスト: cause=MAGICはTF/ArsPaperの魔法経路の専有ではなく、
        // バニラの負傷(Instant Damage)ポーション/スプラッシュ負傷ポーション等も同じcauseで届く。
        // これらはTFの対称パイプラインを一切通っていない(potionResistanceReductionが加算されていない)
        // ため、マーカー無効(=ArsPaperのapplyMagicDamage経由ではない)なら絶対にRESISTANCEへ触れては
        // ならない — 触れると「耐性ポーション所持者がバニラ負傷ポーションへの耐性を完全に失う」バグになる。
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();
        EntityDamageEvent event = damageEvent(victim, caster, EntityDamageEvent.DamageCause.MAGIC);

        assertFalse(MagicPipelineDamage.isActive(), "前提: マーカーは無効な状態からテストを始める");
        listener.onMagicDamage(event);

        assertEquals(-1.2, event.getDamage(EntityDamageEvent.DamageModifier.RESISTANCE), 1e-9,
                "cause=MAGICでもマーカー無効(バニラ負傷ポーション相当)ならRESISTANCEに触れないはず");
    }

    @Test
    void nonMagicCauseLeavesResistanceModifierUntouchedEvenWithActiveMarker() {
        Player caster = server.addPlayer();
        Player victim = server.addPlayer();
        // 物理(ENTITY_ATTACK)は既にCombatListener.FOLDED_MODIFIERSが別途0化する経路であり、
        // このリスナーの対象外であること(cause集合を広げて物理側まで巻き込まないこと)を確認する。
        // マーカーが(万一)有効でもcauseガードが先に効くことも合わせて検証する。
        EntityDamageEvent event = damageEvent(victim, caster, EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        MagicPipelineDamage.mark();
        try {
            listener.onMagicDamage(event);
        } finally {
            MagicPipelineDamage.clear();
        }

        assertEquals(-1.2, event.getDamage(EntityDamageEvent.DamageModifier.RESISTANCE), 1e-9,
                "cause=MAGIC以外ではマーカーの状態に関わらずRESISTANCE modifierに触れないはず");
    }

    @Test
    void depthCounterStaysActiveAfterOneClearFollowingTwoMarks() {
        // MagicPipelineDamage(MobAbilityDamageと同じ設計)はbooleanではなく深度カウンタであるべき、
        // という設計要件そのものを固定する: 魔法ダメージが同期的に別の魔法ダメージを誘発するケース
        // (ネストしたmark)で、内側のfinallyが外側のマークを剥がしてしまわないことを検証する。
        assertFalse(MagicPipelineDamage.isActive());

        MagicPipelineDamage.mark(); // outer
        MagicPipelineDamage.mark(); // inner (synchronous nested magic hit)
        MagicPipelineDamage.clear(); // inner's finally

        assertTrue(MagicPipelineDamage.isActive(),
                "二重markの後に1回clearしても、外側の呼出し分がまだ残っているのでisActiveはtrueのまま");

        MagicPipelineDamage.clear(); // outer's finally
        assertFalse(MagicPipelineDamage.isActive(), "外側もclearし終えたらisActiveはfalseに戻るはず");
    }
}
