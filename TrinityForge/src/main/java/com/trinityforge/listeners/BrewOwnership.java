package com.trinityforge.listeners;

import org.bukkit.NamespacedKey;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 醸造スタンドの「所有者(= その醸造の主体とみなすプレイヤー)」PDC追跡の<b>単一定義</b>。
 * キー文字列だけでなく<b>書き込み規則と寿命もこのクラスにしか無い</b>。
 *
 * <p>この追跡ロジック(手で入れたときだけ刻む/ホッパー投入は自動フラグ)は
 * {@link NativeSkillExperienceListener} が最初に実装した(rememberBrewer/markAutomatedBrew)。
 * かまど、エンチャント、ポーションは「実行した本人＝スキル取得者」だけにステータスが乗るという
 * 横断制約があるため、品質/速度/EXP/解放ゲートなど醸造に関わる全リスナーがこの同じPDCキーを読む必要がある —
 * 二重実装すると片方だけキー文字列が変わって不整合を起こす事故クラスになる。
 *
 * <p><b>⚠️ 2026-07-31 の是正</b>: 醸造解放ゲート({@link BrewUnlockListener})が
 * {@code PdcKeys.BREW_STAND_OWNER} という<b>2本目の所有者記録</b>を新設していた。書き込み規則も寿命も
 * 違う2本が同じブロックに同居したため、「誰に醸造を許可するか」と「誰にEXP/品質を付けるか」が
 * <b>別のキーで決まる</b>状態になっていた(未解放プレイヤーが燃料スロットを触るだけで報酬側の所有者を
 * 奪える)。2本目は撤去し、ゲートもこのクラスの {@link #lastBrewerKey()} を読む。
 * <b>所有者記録を増やしてはいけない。</b>用途に足りない書き込み規則が要るならこのクラスを拡張する。
 *
 * <h2>書き込み規則(4つ。これ以外の経路で所有者を書かない)</h2>
 * <ol>
 *   <li>{@link #rememberOwner}: 手による投入で<b>記録が無いときだけ</b>刻む(先着優先)。
 *       後から触った人が上書きできると、他人の醸造の報酬を最後にクリックするだけで奪える
 *       (逆に、未解放プレイヤーが最後に触るだけで解放済みの台を止められる)。</li>
 *   <li>{@link #replaceOwner}: <b>記録済み所有者がその醸造を成立させられないときだけ</b>差し替える
 *       唯一の上書き経路({@link BrewUnlockListener#isLockedInsertion} が呼ぶ)。これが無いと
 *       未解放プレイヤーが先に空の台へ1回投入するだけで、以降その台のゲート付き醸造を
 *       <b>永久に止められる</b>(燃料も受け付けなくなる)。</li>
 *   <li>{@link #markAutomated}: ホッパー投入で mode だけを {@code auto} にする(所有者は触らない)。</li>
 *   <li>{@link #clear}: 醸造が1回完了したら所有者と mode を消す。<b>これが唯一の寿命の終わり</b>で、
 *       「1回手で触ると以降ずっと手動レート(8倍)が効き続ける」exploit を閉じている。</li>
 * </ol>
 *
 * <p>{@link NamespacedKey} は namespace(プラグイン名) + key文字列が同じであれば別インスタンスでも
 * 等価に扱われる(PDCの get/set はキーの同一性ではなく文字列一致で解決される)ため、このクラスの
 * 複数インスタンスをそれぞれのリスナーで独立に生成しても、同じ {@code plugin} を渡す限り
 * 安全に同じPDCエントリを共有できる。
 *
 * <p><b>重要(読み取り順序)</b>: {@link NativeSkillExperienceListener#onBrew} は醸造完了時
 * ({@link org.bukkit.event.inventory.BrewEvent}) に、このPDCを読んでEXPを付与した「あと」に
 * {@link org.bukkit.event.EventPriority#MONITOR} で消去する(win/lose問わず、手動フラグが後続の
 * 無関係な醸造へ persist するのを防ぐため)。この値を読む他のリスナーは、消去より前の優先度
 * (例: {@link org.bukkit.event.EventPriority#HIGH})で {@code BrewEvent} を購読すること。
 * Bukkitのイベント優先度は LOWEST&lt;LOW&lt;NORMAL&lt;HIGH&lt;HIGHEST&lt;MONITOR の固定順で必ず
 * この順に呼ばれる(同一プラグイン内の登録順には依存しない)ため、優先度さえ守れば消去前に
 * 確実に読み取れる。
 *
 * <p><b>PDC の書き戻し</b>: {@link BrewingStand} は {@link org.bukkit.block.TileState} なので、
 * {@code getPersistentDataContainer()} で書いた値は<b>スナップショット側</b>に乗る。
 * {@code stand.update()} を呼ぶまで実体へ反映されない(このクラスの write は必ず update() する)。
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
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
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

    /**
     * 手による投入で所有者を刻む。<b>既に記録があれば何もしない</b>(先着優先。書き込み規則1)。
     *
     * @return 実際に書いたら {@code true}(既存記録を尊重して何もしなかったら {@code false})
     */
    public boolean rememberOwner(BrewingStand stand, Player player) {
        if (stand == null || player == null || ownerOf(stand).isPresent()) {
            return false;
        }
        writeOwner(stand, player);
        return true;
    }

    /**
     * 記録済み所有者を差し替える(書き込み規則2)。<b>呼び出し側が「現所有者ではこの醸造が成立しない」
     * ことを確認した場合にだけ呼ぶ</b> — 無条件に呼ぶと最後に触った人が所有者になり、
     * {@link #rememberOwner} の先着優先が意味を失う。
     */
    public void replaceOwner(BrewingStand stand, Player player) {
        if (stand == null || player == null) {
            return;
        }
        writeOwner(stand, player);
    }

    /** ホッパー投入のマーク(書き込み規則3)。所有者は触らない。 */
    public void markAutomated(BrewingStand stand) {
        if (stand == null) {
            return;
        }
        stand.getPersistentDataContainer().set(brewModeKey, PersistentDataType.STRING, MODE_AUTO);
        stand.update();
    }

    /** 所有者と mode を消す(書き込み規則4 = 唯一の寿命の終わり)。 */
    public void clear(BrewingStand stand) {
        if (stand == null) {
            return;
        }
        stand.getPersistentDataContainer().remove(lastBrewerKey);
        stand.getPersistentDataContainer().remove(brewModeKey);
        stand.update();
    }

    private void writeOwner(BrewingStand stand, Player player) {
        stand.getPersistentDataContainer().set(
                lastBrewerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        stand.getPersistentDataContainer().set(
                brewModeKey, PersistentDataType.STRING, MODE_MANUAL);
        stand.update();
    }
}
