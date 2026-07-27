package com.trinityforge.integration;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/**
 * 外部プラグイン「DPSChecker-TF」が設置する訓練用ダミー(カカシ)を判定する。
 *
 * <p>ダミーは AI 無効の {@code Zombie} で、PDC キー {@code dpschecker-tf:dummy}(BOOLEAN)を
 * 永続保持する(スポーン時に書き込み、再起動を跨いで保持)。TF はこのマーカーを見て、
 * <b>殴打時の戦闘スキルEXP付与</b>と<b>頭上のLv/HP表示</b>からダミーを除外する。
 *
 * <p>ヘイト記録は意図的に除外しない — ダミーをヘイト/敵対挙動の検証に使えるようにするため
 * (要件: 抑制対象は EXP と頭上表示の2つのみ)。
 *
 * <p>名前空間 {@code dpschecker-tf} は DPSChecker-TF プラグイン名(小文字化)に由来する固定値。
 * TF は当該プラグインへコンパイル依存せず、PDC の存在確認だけで疎結合に連携する。
 */
public final class TrainingDummies {

    private static final NamespacedKey DUMMY_KEY = new NamespacedKey("dpschecker-tf", "dummy");

    private TrainingDummies() {
    }

    /** {@code entity} が DPSChecker-TF の訓練用ダミーなら true。null は false。 */
    public static boolean isTrainingDummy(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(DUMMY_KEY, PersistentDataType.BOOLEAN);
    }
}
