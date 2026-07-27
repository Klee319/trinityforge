package com.trinityforge.listeners;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import com.trinityforge.config.domains.AchievementsConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Objects;

/**
 * サーバ側でバニラ進捗(advancement)の解除を止める(2026-07-28、{@code achievements.yml
 * vanilla-advancements})。TFは独自のアチーブメント({@code AchievementService})を持っているため、
 * バニラの進捗トースト/進捗画面の達成をここで抑止する。
 *
 * <p><b>採用イベントについて</b>: 指示書は {@code io.papermc.paper.event.player.
 * PlayerAdvancementCriterionGrantEvent} を想定していたが、実際にこの版(paper-api
 * 1.21.11-R0.1-SNAPSHOT)に存在するのは {@link com.destroystokyo.paper.event.player.
 * PlayerAdvancementCriterionGrantEvent}(パッケージが {@code com.destroystokyo.paper} 側)であり、
 * {@link org.bukkit.event.Cancellable} を実装している(2026-07-28 {@code javap} で実クラス確認済み)。
 * キャンセル不可の {@link org.bukkit.event.player.PlayerAdvancementDoneEvent} へのフォールバックは
 * 不要だった。
 *
 * <p>判定は {@link #shouldBlock} に切り出したpackage-private staticの純粋関数(このリポジトリの
 * 「pure helper をテスト可能にする」慣習、例: {@code CatalogCraftGateListener#isBlocked})。
 */
public final class VanillaAdvancementBlockListener implements Listener {

    private final AchievementsConfig achievements;

    public VanillaAdvancementBlockListener(AchievementsConfig achievements) {
        this.achievements = Objects.requireNonNull(achievements, "achievements");
    }

    /**
     * LOWEST優先度: このリスナーが最初に判定してキャンセルすることで、後続の(他プラグインの)
     * advancement関連処理が「まだ解除されていない」前提のまま動く。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCriterionGrant(PlayerAdvancementCriterionGrantEvent event) {
        AchievementsConfig.VanillaAdvancementGate gate = achievements.vanillaAdvancements();
        NamespacedKey key = event.getAdvancement().getKey();
        if (shouldBlock(gate.disabled(), key.getNamespace(), key.getKey(),
                gate.keepRecipeAdvancements(), gate.keep())) {
            event.setCancelled(true);
        }
    }

    /**
     * 判定順:
     * <ol>
     *   <li>{@code disabled} が false → 素通り</li>
     *   <li>進捗キーの namespace が {@code minecraft} でない → 素通り(データパック/他プラグインの
     *       進捗は巻き込まない)</li>
     *   <li>{@code keepRecipeAdvancements} が true かつ key path が {@code recipes/} で始まる →
     *       素通り(バニラのレシピ本解禁を壊さないため)</li>
     *   <li>{@code keep} のいずれか({@code namespace:path} 形式のフルキーに対する前方一致) → 素通り</li>
     *   <li>それ以外 → ブロック</li>
     * </ol>
     */
    static boolean shouldBlock(boolean disabled, String namespace, String path,
            boolean keepRecipeAdvancements, List<String> keep) {
        if (!disabled) {
            return false;
        }
        if (!"minecraft".equals(namespace)) {
            return false;
        }
        if (keepRecipeAdvancements && path != null && path.startsWith("recipes/")) {
            return false;
        }
        String fullKey = namespace + ":" + path;
        if (keep != null) {
            for (String prefix : keep) {
                if (prefix != null && !prefix.isBlank() && fullKey.startsWith(prefix)) {
                    return false;
                }
            }
        }
        return true;
    }
}
