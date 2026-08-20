package com.trinityforge.listeners;

import com.trinityforge.mobs.MobTransformCarryOver;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * モブが途中で別種へ変身したとき(ゾンビ→ドラウンド、村人→ゾンビ村人、豚→ゾンビピグリン等)に
 * TrinityForge のモブステータスを引き継ぐ(2026-07-29)。
 *
 * <p>Paper の {@code Mob#convertTo} は PersistentDataContainer も Attribute も新エンティティへ
 * コピーしないため、何もしないと TF のスタンプが丸ごと消える。詳細な理由と何が壊れるかは
 * {@link MobTransformCarryOver} の javadoc を参照。
 *
 * <p><b>発火順序</b>: {@code convertTo} は
 * 「新エンティティ生成 → {@code ConversionType.convert} → 変身後の初期化コールバック →
 * <b>EntityTransformEvent</b> → {@code addFreshEntity}(=CreatureSpawnEvent) → 旧個体を破棄」
 * の順に進む。つまり本リスナーは {@code MobTypeSpawnListener} より<b>先</b>に走るので、
 * ここで引き継いだ PDC は同リスナーから見える(ダンジョン個体の早期returnが正しく効く)。
 *
 * <p><b>優先度</b>: EliteMobs フォークが自分の管理下のモブについて本イベントを
 * キャンセルする(そちらは変身自体を起こさない)ため、MONITOR で走らせてキャンセル済みなら
 * 何もしない。{@code ignoreCancelled=true} を使わず明示的に判定しているのは、この
 * 「キャンセル＝変身が起きないので引き継ぎ不要」という理由をコード上に残すため。
 */
public final class MobTransformListener implements Listener {

    /**
     * 分裂(スライム/マグマキューブ)は「1体が複数体になる」ので、最大HPと現在HPの引き継ぎは
     * 意味を持たない(子は本来サイズ相応の別個体で、親のHPを配ると小スライムが親と同じ耐久になる)。
     * 由来を示すPDC(ダンジョン/スポナー/プロファイル)は引き継ぐが、HPだけは触らない。
     */
    private static final Set<EntityTransformEvent.TransformReason> SPLIT_REASONS =
            EnumSet.of(EntityTransformEvent.TransformReason.SPLIT);

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTransform(EntityTransformEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity from)) {
            return;
        }
        boolean carryHealth = !SPLIT_REASONS.contains(event.getTransformReason());
        double ratio = carryHealth ? MobTransformCarryOver.healthRatioOf(from) : 1.0;

        for (Entity transformed : event.getTransformedEntities()) {
            if (!(transformed instanceof LivingEntity to)) {
                continue;
            }
            MobTransformCarryOver.copyMobStamps(
                    from.getPersistentDataContainer(), to.getPersistentDataContainer());
            if (!carryHealth) {
                continue;
            }
            MobTransformCarryOver.copyMaxHealth(from, to, ratio);
            // 直後の CreatureSpawnEvent で MobTypeSpawnListener が新しい型の最大HPへ刻み直す
            // 個体のために、割合だけ預けておく(そちらが consume して max*ratio を設定する)。
            MobTransformCarryOver.rememberHealthRatio(to.getUniqueId(), ratio);
        }
    }
}
