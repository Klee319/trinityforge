package com.trinityforge.progression.catalog;

import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * アイテムをキーに EXP テーブル({@code stats/skill-exp.yml} の {@code brew_ingredient} /
 * {@code block_drops} / {@code entity_drops} / {@code fishing_catch} など)を引くための共通解決。
 *
 * <p>2026-07-28 以前は {@code stack.getType().name()} だけで引いていたため、TF/Ars のカスタム
 * アイテム(見た目と PDC は独自だが土台はバニラ Material)は必ず土台 Material の行に落ちていた。
 * その結果「醸造の素材にカスタムアイテムを設定できない(設定しても効かない)」状態だった。
 *
 * <p>解決順は <b>{@code custom:<id>} が先、無ければバニラ Material 名</b>。カスタム行が未設定
 * (=0)ならバニラ行へフォールバックするので、既存の設定は一切挙動が変わらない。
 */
public final class ItemExpLookup {

    public static final String CUSTOM_PREFIX = "custom:";

    private ItemExpLookup() {
    }

    /**
     * カスタムアイテムIDの解決。PDC の読み取りは {@code ItemStack#hasItemMeta()} 経由で
     * {@code Bukkit.getItemFactory()} を触るため、Bukkit サーバーが立っていない文脈
     * (EXP算出の純粋関数を直接叩く単体テスト等)では例外になる。ここは「カスタムIDが分からない」
     * = バニラ Material 名で引く、へフォールバックするだけで正しく縮退するので握り潰す。
     */
    private static Optional<String> customIdOf(ItemStack stack) {
        try {
            return CrossPluginItemResolver.idOf(stack);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    /** {@code custom:<id>} を優先し、未設定ならバニラ Material 名で引いた EXP。 */
    public static double expFor(SkillCatalogEntry entry, String action, ItemStack stack) {
        if (entry == null || action == null || stack == null || stack.getType().isAir()) {
            return 0.0;
        }
        Optional<String> customId = customIdOf(stack);
        if (customId.isPresent()) {
            double custom = entry.expFor(action, CUSTOM_PREFIX + customId.get());
            if (custom > 0.0) {
                return custom;
            }
        }
        return entry.expFor(action, stack.getType().name());
    }

    /**
     * 同一アイテムの重複計上を防ぐための識別キー。カスタムアイテムは土台 Material が同じでも
     * 別物として数えたいので、id があればそちらを返す。
     */
    public static String dedupeKey(ItemStack stack) {
        if (stack == null) {
            return "";
        }
        return customIdOf(stack)
                .map(id -> CUSTOM_PREFIX + id)
                .orElseGet(() -> stack.getType().name());
    }
}
