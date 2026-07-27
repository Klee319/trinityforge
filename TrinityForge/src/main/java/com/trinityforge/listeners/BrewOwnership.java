package com.trinityforge.listeners;

import org.bukkit.NamespacedKey;
import org.bukkit.block.BrewingStand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 醸造スタンドの「最後に手で触った(=所有者とみなす)プレイヤー」PDC追跡キーの単一定義。
 *
 * <p>この追跡ロジック(手で入れたときだけ刻む/ホッパー投入は自動フラグ)は
 * {@link NativeSkillExperienceListener} が最初に実装した(rememberBrewer/markAutomatedBrew)。
 * かまど、エンチャント、ポーションは「実行した本人＝スキル取得者」だけにステータスが乗るという
 * 横断制約があるため、品質/速度など醸造に関わる全リスナーがこの同じPDCキーを読む必要がある —
 * 二重実装すると片方だけキー文字列が変わって不整合を起こす事故クラスになる。
 *
 * <p>{@link NamespacedKey} は namespace(プラグイン名) + key文字列が同じであれば別インスタンスでも
 * 等価に扱われる(PDCの get/set はキーの同一性ではなく文字列一致で解決される)ため、このクラスの
 * 複数インスタンスをそれぞれのリスナーで独立に生成しても、書き込み側({@link NativeSkillExperienceListener})
 * と同じ {@code plugin} を渡す限り安全に同じPDCエントリを共有できる。
 *
 * <p><b>重要(読み取り順序)</b>: {@link NativeSkillExperienceListener#onBrew} は醸造完了時
 * ({@link org.bukkit.event.inventory.BrewEvent}) に、このPDCを読んでEXPを付与した「あと」に
 * {@link org.bukkit.event.EventPriority#MONITOR} で消去する(win/lose問わず、手動フラグが後続の
 * 無関係な醸造へ persist するのを防ぐため)。この値を読む他のリスナーは、消去より前の優先度
 * (例: {@link org.bukkit.event.EventPriority#HIGH})で {@link BrewEvent} を購読すること。
 * Bukkitのイベント優先度は LOWEST&lt;LOW&lt;NORMAL&lt;HIGH&lt;HIGHEST&lt;MONITOR の固定順で必ず
 * この順に呼ばれる(同一プラグイン内の登録順には依存しない)ため、優先度さえ守れば消去前に
 * 確実に読み取れる。
 */
public final class BrewOwnership {

    public static final String MODE_MANUAL = "manual";
    public static final String MODE_AUTO = "auto";

    private final NamespacedKey lastBrewerKey;
    private final NamespacedKey brewModeKey;

    public BrewOwnership(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.lastBrewerKey = new NamespacedKey(plugin, "last_brewer");
        this.brewModeKey = new NamespacedKey(plugin, "brew_mode");
    }

    public NamespacedKey lastBrewerKey() {
        return lastBrewerKey;
    }

    public NamespacedKey brewModeKey() {
        return brewModeKey;
    }

    /** Reads the recorded owner UUID, if any and well-formed. Never mutates the stand. */
    public Optional<UUID> ownerOf(BrewingStand stand) {
        if (stand == null) {
            return Optional.empty();
        }
        String raw = stand.getPersistentDataContainer().get(lastBrewerKey, PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** {@code true} when this brew was flagged as hopper-fed (never manually touched by a player). */
    public boolean isAutomated(BrewingStand stand) {
        if (stand == null) {
            return false;
        }
        return MODE_AUTO.equals(
                stand.getPersistentDataContainer().get(brewModeKey, PersistentDataType.STRING));
    }
}
