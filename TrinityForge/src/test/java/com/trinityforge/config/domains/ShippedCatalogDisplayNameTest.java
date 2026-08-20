package com.trinityforge.config.domains;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>出荷カタログの表示名が「内部ID」や「生のカラーコード」で画面に出ないことを固定する。</b>
 *
 * <p>2026-08-03 の実サーバ報告: レシピ一覧GUIで一部アイテムが内部IDで表示され、
 * 別のアイテムでは表示名にカラーコードが混ざっていた。原因は ArsPaper 側にあったが、
 * <b>TF カタログ側にも同じ穴が開けば同じ症状になる</b>:
 * <ul>
 *   <li>{@code display-name} が無いエントリは、名前解決の最終フォールバックが内部IDになる
 *       (ArsPaper の {@code RecipeBrowserGui#localize} / {@code CatalogRitualRegistrar} が
 *        TFカタログの表示名を引けなかったときの挙動)</li>
 *   <li>TF カタログの表示名は<b>MiniMessage</b>。ここにレガシーの {@code &a} / {@code §a} を
 *       書くと MiniMessage パーサはタグと見なさず、記号がそのまま画面に出る</li>
 * </ul>
 *
 * <p>したがって「TF 側は MiniMessage に統一する」という書式の約束を、データ側で機械的に固定する。
 * 準備中({@code draft: true})のアイテムも対象にする — 解禁した瞬間に表示が壊れるのを避けるため。
 */
class ShippedCatalogDisplayNameTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";

    /** レガシーカラーコード({@code &a} / {@code §a})。MiniMessage では解釈されない。 */
    private static final Pattern LEGACY = Pattern.compile("[&§][0-9a-fk-orA-FK-OR]");

    private static ConfigurationSection shippedItems() throws Exception {
        Path source = Path.of(CATALOG);
        assertTrue(Files.isRegularFile(source), "出荷カタログが見つからない: " + source.toAbsolutePath());
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(source));
        ConfigurationSection items = cfg.getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        return items;
    }

    @Test
    @DisplayName("全アイテムが display-name を持つ(無いと名前解決が内部IDへ落ちる)")
    void everyItemHasDisplayName() throws Exception {
        ConfigurationSection items = shippedItems();
        List<String> missing = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            ConfigurationSection entry = items.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            String name = entry.getString("display-name");
            if (name == null || name.isBlank()) {
                missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(),
                "display-name が無いアイテムはレシピGUI/図鑑に内部IDで出る。該当: " + missing);
    }

    @Test
    @DisplayName("display-name にレガシーカラーコードを書かない(TF側は MiniMessage 統一)")
    void displayNamesUseMiniMessageOnly() throws Exception {
        ConfigurationSection items = shippedItems();
        List<String> offenders = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            ConfigurationSection entry = items.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            String name = entry.getString("display-name");
            if (name != null && LEGACY.matcher(name).find()) {
                offenders.add(id + " -> " + name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "display-name にレガシー &/§ コードが混ざっている。MiniMessage パーサは解釈しないので"
                        + "記号がそのまま表示される。該当: " + offenders);
    }

    @Test
    @DisplayName("display-name は MiniMessage として解釈でき、解釈後にタグが残らない")
    void displayNamesParseAsMiniMessage() throws Exception {
        ConfigurationSection items = shippedItems();
        MiniMessage mm = MiniMessage.miniMessage();
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        List<String> offenders = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            ConfigurationSection entry = items.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            String name = entry.getString("display-name");
            if (name == null || name.isBlank()) {
                continue; // 別テストが報告する
            }
            String rendered;
            try {
                rendered = plain.serialize(mm.deserialize(name));
            } catch (RuntimeException ex) {
                offenders.add(id + " -> パース失敗: " + ex.getMessage());
                continue;
            }
            if (rendered.isBlank()) {
                offenders.add(id + " -> 解釈すると空文字になる: " + name);
            } else if (rendered.indexOf('<') >= 0 && rendered.indexOf('>') > rendered.indexOf('<')) {
                offenders.add(id + " -> タグが残る: " + rendered);
            }
        }
        assertTrue(offenders.isEmpty(),
                "MiniMessage として壊れている display-name がある。該当: " + offenders);
    }
}
