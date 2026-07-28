package com.trinityforge.mobs;

import com.trinityforge.pdc.PdcKeys;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ゾンビ→ドラウンド等の「変身」でTrinityForgeのモブステータスが失われる問題への対処
 * (2026-07-29)。
 *
 * <p><b>なぜ失われるか</b>: 変身は {@code Mob#convertTo} で行われ、
 * {@code ConversionType.convert} が新エンティティへ引き継ぐのは座標・装備・ポーション効果・
 * 子供フラグ・年齢・ブレインのANGRY_AT・各種フラグ・スコアボードタグ等に限られる。
 * <b>PersistentDataContainer と Attribute(MAX_HEALTHを含む)・現在HPは一切コピーされない</b>。
 * つまり新エンティティは「PDC空・バニラHP」で生まれる。
 *
 * <p><b>何が壊れていたか</b>:
 * <ul>
 *   <li>{@link PdcKeys#MOB_DUNGEON_THEME} / {@link PdcKeys#MOB_PROFILE_ID} が消えるため、
 *       ダンジョン個体が「ただの野良モブ」に化ける({@code MobTypeSpawnListener} の
 *       ダンジョン早期returnが効かなくなり、フィールド用のステで上書きされる)。</li>
 *   <li>{@link PdcKeys#MOB_SPAWNER_SPAWNED} が消えるため、スポナー由来のEXP抑制を
 *       「水に落として変身させる」だけで回避できてしまう(再付与経路が無い —
 *       変身後の SpawnReason は SPAWNER ではなく DROWNED 等になる)。</li>
 *   <li>{@link PdcKeys#MOB_ROLL_SEED} が消えるため個体ばらつきが振り直される。</li>
 *   <li>MAX_HEALTH がバニラ値へ戻り、削ったダメージも消える(全回復)。</li>
 * </ul>
 *
 * <p><b>方針</b>: TrinityForge が刻んだ値は「同じ個体の属性」なので全て引き継ぐ。型に依存する
 * 戦闘ステ(レベル/防御/攻撃/HP)は、変身直後に発火する {@code CreatureSpawnEvent} で
 * {@code MobTypeSpawnListener} が<b>新しい型の設定で刻み直す</b>ため、引き継いだ値は
 * 上書きされる(＝野良モブは正しくドラウンドとしてのステになる)。一方ダンジョン/プロファイル
 * 個体では同リスナーが早期returnするので、引き継いだ値がそのまま残る。どちらも意図どおり。
 *
 * <p>HPは「割合」で引き継ぐ。半分まで削ったゾンビが水没して全回復するのを防ぎつつ、
 * 新しい型の最大HPに合わせるため。
 */
public final class MobTransformCarryOver {

    /** 変身後エンティティのUUID → 変身前のHP割合[0,1]。{@code MobTypeSpawnListener} が消費する。 */
    private static final int MAX_PENDING = 512;

    /**
     * 挿入順のLinkedHashMapを上限つきで使う。変身イベントと直後の CreatureSpawnEvent は
     * <b>同一の同期呼び出しの中</b>で連続して起きるため滞留はほぼ起きないが、
     * {@code MobTypeSpawnListener} が登録されていない/例外で消費されなかった場合に
     * 無制限に伸びないよう、古いものから捨てる(TTL管理は不要な複雑さなので入れない)。
     */
    private static final Map<UUID, Double> PENDING_HEALTH_RATIO =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Double> eldest) {
                    return size() > MAX_PENDING;
                }
            });

    private static final List<NamespacedKey> INTEGER_KEYS = List.of(PdcKeys.MOB_LEVEL);

    private static final List<NamespacedKey> STRING_KEYS =
            List.of(PdcKeys.MOB_DUNGEON_THEME, PdcKeys.MOB_PROFILE_ID);

    private static final List<NamespacedKey> LONG_KEYS = List.of(PdcKeys.MOB_ROLL_SEED);

    private static final List<NamespacedKey> BYTE_KEYS =
            List.of(PdcKeys.MOB_TYPE_STAMPED, PdcKeys.MOB_SPAWNER_SPAWNED);

    private static final List<NamespacedKey> DOUBLE_KEYS = List.of(
            PdcKeys.MOB_ARMOR_STRENGTH,
            PdcKeys.MOB_DODGE_CHANCE,
            PdcKeys.MOB_PHYS_DEFENSE_RATE,
            PdcKeys.MOB_PHYS_RESISTANCE,
            PdcKeys.MOB_PHYS_DAMAGE_REDUCTION,
            PdcKeys.MOB_PHYS_FLAT_DEFENSE,
            PdcKeys.MOB_MAGIC_DEFENSE_RATE,
            PdcKeys.MOB_MAGIC_RESISTANCE,
            PdcKeys.MOB_MAGIC_DAMAGE_REDUCTION,
            PdcKeys.MOB_MAGIC_FLAT_DEFENSE,
            PdcKeys.MOB_ATTACK_POWER,
            PdcKeys.MOB_ATTACK_FLAT_BONUS,
            PdcKeys.MOB_ATTACK_PERCENT_BONUS,
            PdcKeys.MOB_ATTACK_PENETRATION,
            PdcKeys.MOB_ATTACK_CRIT_CHANCE,
            PdcKeys.MOB_ATTACK_CRIT_DAMAGE,
            PdcKeys.MOB_ATTACK_DAMAGE_MODIFIER,
            PdcKeys.MOB_ATTACK_FIXED_DAMAGE);

    private MobTransformCarryOver() {
    }

    /** 引き継ぎ対象キーの総数(テスト用: キーを足し忘れていないことの回帰確認に使う)。 */
    public static int carriedKeyCount() {
        return INTEGER_KEYS.size() + STRING_KEYS.size() + LONG_KEYS.size()
                + BYTE_KEYS.size() + DOUBLE_KEYS.size();
    }

    /**
     * TrinityForge が刻んだモブPDCを {@code from} から {@code to} へコピーする。
     * 既に {@code to} 側にある値は上書きしない — 変身先が先に自前のスタンプを持っているケース
     * (他プラグイン/フォークが変身直後に刻む)を尊重するため。{@code from} に無いキーは触らない。
     */
    public static void copyMobStamps(PersistentDataContainer from, PersistentDataContainer to) {
        for (NamespacedKey key : INTEGER_KEYS) {
            copy(from, to, key, PersistentDataType.INTEGER);
        }
        for (NamespacedKey key : STRING_KEYS) {
            copy(from, to, key, PersistentDataType.STRING);
        }
        for (NamespacedKey key : LONG_KEYS) {
            copy(from, to, key, PersistentDataType.LONG);
        }
        for (NamespacedKey key : BYTE_KEYS) {
            copy(from, to, key, PersistentDataType.BYTE);
        }
        for (NamespacedKey key : DOUBLE_KEYS) {
            copy(from, to, key, PersistentDataType.DOUBLE);
        }
    }

    private static <T, Z> void copy(PersistentDataContainer from, PersistentDataContainer to,
                                    NamespacedKey key, PersistentDataType<T, Z> type) {
        if (to.has(key, type)) {
            return;
        }
        Z value = from.get(key, type);
        if (value != null) {
            to.set(key, type, value);
        }
    }

    /**
     * 現在HP / 実効最大HP を [0,1] で返す。最大HPが取得できない、または0以下なら 1.0(全快扱い)。
     * 純関数なのでテストから直接検証できる。
     */
    public static double healthRatio(double currentHealth, double maxHealth) {
        if (!(maxHealth > 0.0) || !Double.isFinite(currentHealth) || !Double.isFinite(maxHealth)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, currentHealth / maxHealth));
    }

    /** {@link #healthRatio(double, double)} のエンティティ版。属性が無ければ 1.0。 */
    public static double healthRatioOf(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return 1.0;
        }
        return healthRatio(entity.getHealth(), attr.getValue());
    }

    /**
     * 変身前のMAX_HEALTH基礎値と現在HP割合を新エンティティへ移す。
     *
     * <p>この時点(EntityTransformEvent)ではまだ {@code CreatureSpawnEvent} が発火していないので、
     * ここで入れた値は野良モブなら直後に {@code MobTypeSpawnListener} が新しい型の設定で
     * 上書きする。上書きされない個体(ダンジョン/プロファイル持ち)のために、ここでも
     * 正しい値を入れておく。
     */
    public static void copyMaxHealth(LivingEntity from, LivingEntity to, double ratio) {
        AttributeInstance fromAttr = from.getAttribute(Attribute.MAX_HEALTH);
        AttributeInstance toAttr = to.getAttribute(Attribute.MAX_HEALTH);
        if (fromAttr == null || toAttr == null) {
            return;
        }
        double base = fromAttr.getBaseValue();
        if (!(base > 0.0)) {
            return;
        }
        toAttr.setBaseValue(base);
        applyRatioHealth(to, toAttr.getValue(), ratio);
    }

    /** {@code max * ratio} を [1, 実効上限] へ収めて設定する(0HPで即死させない)。 */
    public static void applyRatioHealth(LivingEntity entity, double effectiveMax, double ratio) {
        if (!(effectiveMax > 0.0)) {
            return;
        }
        double target = Math.max(1.0, Math.min(effectiveMax, effectiveMax * ratio));
        try {
            entity.setHealth(target);
        } catch (IllegalArgumentException ignored) {
            // 実効上限が要求値より低いサーバー設定。ここでHPを諦めても他のステは既に移せている。
        }
    }

    /** 変身後エンティティのHP割合を記録する。 */
    public static void rememberHealthRatio(UUID transformedEntityId, double ratio) {
        PENDING_HEALTH_RATIO.put(transformedEntityId, ratio);
    }

    /**
     * 記録済みのHP割合を取り出して消す。未記録(＝変身由来ではない通常スポーン)なら 1.0。
     * 呼び出し側は「1.0 = 全快で刻む」という従来どおりの挙動になる。
     */
    public static double consumeHealthRatio(UUID entityId) {
        Double ratio = PENDING_HEALTH_RATIO.remove(entityId);
        return ratio == null ? 1.0 : ratio;
    }

    /** テスト用: 保留中のHP割合をすべて捨てる。 */
    public static void clearForTests() {
        PENDING_HEALTH_RATIO.clear();
    }

    /** テスト用: 保留件数。 */
    public static int pendingCountForTests() {
        return PENDING_HEALTH_RATIO.size();
    }
}
