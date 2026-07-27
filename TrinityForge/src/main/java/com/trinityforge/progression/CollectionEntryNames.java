package com.trinityforge.progression;

import com.trinityforge.stats.CrossPluginItemResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

/**
 * 図鑑エントリID({@code item:<catalogId>} / {@code mob:<ENTITY_TYPE>})を、プレイヤーが読める表示名へ
 * 解決する。従来は {@link CollectionService#displayOf(String)} が接頭辞を剥がした生IDをそのまま
 * 返しており、図鑑GUIにもチャット通知にも {@code infinity_sword} のような内部IDが出ていた。
 *
 * <p>解決規則:
 * <ul>
 *   <li><b>item:</b> 構築済み {@link ItemStack} の display-name(カタログ/ArsPaper が持つ日本語名)。
 *       display-name を持たない素の Material は Material の翻訳キーへ落とす。</li>
 *   <li><b>mob:</b> バニラの翻訳キー {@code entity.minecraft.<小文字EntityType>}。
 *       翻訳可能コンポーネントなのでクライアント側で各言語へ解決される(Geyser/統合版でも同じ)。
 *       Bukkit API を一切叩かないので、モブ名の解決だけはサーバ実装に依存しない。</li>
 * </ul>
 *
 * <p>いずれの経路も解決に失敗したら生IDへフォールバックする(表示のためにゲームループを壊さない)。
 */
public final class CollectionEntryNames {

    private static final String PREFIX_ITEM = "item:";
    private static final String PREFIX_MOB = "mob:";

    /** null 許容(itemResolver 未注入のテスト/呼び出し側では item: が生IDのままになるだけ)。 */
    private final CrossPluginItemResolver itemResolver;

    public CollectionEntryNames(CrossPluginItemResolver itemResolver) {
        this.itemResolver = itemResolver;
    }

    /** エントリIDの表示名。解決できないときは生ID。 */
    public Component display(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return Component.empty();
        }
        if (entryId.startsWith(PREFIX_MOB)) {
            return mobName(entryId.substring(PREFIX_MOB.length()));
        }
        if (entryId.startsWith(PREFIX_ITEM)) {
            String catalogId = entryId.substring(PREFIX_ITEM.length());
            ItemStack built = null;
            if (itemResolver != null) {
                try {
                    built = itemResolver.create(catalogId).orElse(null);
                } catch (RuntimeException | LinkageError ignored) {
                    built = null; // 表示名のためにアイテム構築の失敗を伝播させない。
                }
            }
            return itemName(catalogId, built);
        }
        return Component.text(entryId);
    }

    /**
     * GUI がアイコン用に既に構築した {@link ItemStack} から表示名を取る。同じアイテムを2度組み立てる
     * のを避けるためだけの入口で、意味は {@link #display(String)} と同じ。
     */
    public Component displayFrom(String entryId, ItemStack built) {
        if (entryId != null && entryId.startsWith(PREFIX_ITEM)) {
            return itemName(entryId.substring(PREFIX_ITEM.length()), built);
        }
        return display(entryId);
    }

    /**
     * ソート用の比較キー(プレーンテキスト)。翻訳可能コンポーネントは翻訳キーそのものが返るため、
     * モブは {@code entity.minecraft.<英名>} 順=英名順になる。表示は各クライアントの言語のままで、
     * 並び順だけがサーバ共通の決定的な順序になる(サーバ側にモブ名の日本語辞書を持たないので、
     * 「見た目の五十音順」は原理的に作れない — ここは意図した割り切り)。
     */
    public String sortKey(String entryId) {
        return plain(display(entryId));
    }

    /** 既に構築済みのアイコンがある場合のソートキー。 */
    public String sortKeyFrom(String entryId, ItemStack built) {
        return plain(displayFrom(entryId, built));
    }

    private static String plain(Component component) {
        try {
            return PlainTextComponentSerializer.plainText().serialize(component)
                    .toLowerCase(Locale.ROOT);
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    /** {@code item:} の表示名。{@code built} が null/解決不能なら生の catalogId。 */
    public static Component itemName(String catalogId, ItemStack built) {
        if (built != null && !built.getType().isAir()) {
            try {
                ItemMeta meta = built.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    Component name = meta.displayName();
                    if (name != null) {
                        return name.decoration(TextDecoration.ITALIC, false);
                    }
                }
                return Component.translatable(built.getType())
                        .decoration(TextDecoration.ITALIC, false);
            } catch (RuntimeException | LinkageError ignored) {
                // Material の翻訳キー解決は実装依存(MockBukkit 等では未実装)。生IDへ落とす。
            }
        }
        return Component.text(catalogId);
    }

    /**
     * {@code mob:} の表示名。バニラの翻訳キーを直接組み立てるので Bukkit API に依存しない。
     * フォールバック文字列に EntityType 名を入れてあるため、翻訳を持たないクライアントでも
     * 空文字にはならない。
     */
    public static Component mobName(String entityTypeName) {
        if (entityTypeName == null || entityTypeName.isBlank()) {
            return Component.empty();
        }
        String key = "entity.minecraft." + entityTypeName.trim().toLowerCase(Locale.ROOT);
        return Component.translatable(key, entityTypeName)
                .decoration(TextDecoration.ITALIC, false);
    }
}
