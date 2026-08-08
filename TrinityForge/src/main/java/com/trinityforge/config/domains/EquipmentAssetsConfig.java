package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 防具を「着たときの見た目」に使うレイヤーテクスチャの割り当て表（{@code items/equipment-assets.yml}）。
 *
 * <p><b>CMD とは別系統。</b>アイテムとしての見た目（インベントリ／手持ち／地面）は
 * {@code custom-model-data} で差し替わるが、体の上に乗る防具レイヤーは CMD では一切変わらない。
 * あれを決めるのは {@code minecraft:equippable} データコンポーネントの {@code asset_id} で、
 * 次の3点が揃って初めて効く:
 *
 * <ol>
 *   <li>サーバがアイテムへ {@code equippable{asset_id: trinityforge:<セット名>}} を書く
 *       （{@code ItemFactory} が本設定を見て行う）</li>
 *   <li>パックに {@code assets/trinityforge/equipment/<セット名>.json} がある</li>
 *   <li>パックに {@code textures/entity/equipment/humanoid[_leggings]/<セット名>.png} がある</li>
 * </ol>
 *
 * <p><b>⚠ この yml は手書きしない。</b>{@code resourcepack/build_equipment_assets.py} が
 * 「パックに実物がある」セットだけを書き出す生成物で、それが唯一の安全弁になっている:
 * パックに定義の無い {@code asset_id} を書くと、その防具は着たときに
 * <b>バニラの見た目に戻るのではなく透明になる</b>。「まだテクスチャが無い」は
 * 「バニラのまま」であるべきで、透明はバグにしか見えない。
 *
 * <p>載っていない防具（＝既定では全部）には何も書かないので、機構を入れただけでは
 * 既存の見た目は 1 つも変わらない。PNG を所定の場所へ置いて生成スクリプトを回すと、
 * そのセットだけが自動で配線される。
 */
public final class EquipmentAssetsConfig implements LoadableConfig {
    public static final String PATH = "items/equipment-assets.yml";

    /** アセット名（＝パック側のファイル名）に許す文字。名前空間キーとしてそのまま使う。 */
    private static final String ASSET_PATTERN = "[a-z0-9_/.-]+";

    /** catalog id -> アセット名。生成物どおりの順序を保つ。 */
    private volatile Map<String, String> assetByCatalogId = Map.of();

    @Override
    public boolean load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) plugin.saveResource(PATH, false);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "[" + PATH + "] YAML構文エラー。直前の割り当て表を維持します", ex);
            return false;
        }
        return parse(yaml.getConfigurationSection("equipment-assets"), plugin.getLogger());
    }

    /**
     * {@code equipment-assets:} 直下を読む。{@code root} が {@code null}（キー自体が無い／空）でも
     * エラーにしない — 「配線済みのセットが 1 つも無い」は正常な初期状態だから。
     */
    public boolean parse(ConfigurationSection root, Logger log) {
        Map<String, String> parsed = new LinkedHashMap<>();
        boolean valid = true;
        if (root != null) for (String asset : root.getKeys(false)) {
            if (!asset.matches(ASSET_PATTERN)) {
                log.warning("[" + PATH + "] 不正なアセット名 '" + asset + "' を無視しました");
                valid = false;
                continue;
            }
            ConfigurationSection entry = root.getConfigurationSection(asset);
            List<String> items = entry == null ? List.of() : entry.getStringList("items");
            if (items.isEmpty()) {
                log.warning("[" + PATH + "] アセット '" + asset + "' に items: が無いので無視しました");
                valid = false;
                continue;
            }
            for (String id : items) {
                String previous = parsed.put(id, asset);
                if (previous != null && !previous.equals(asset)) {
                    // 1 つのアイテムを 2 つのセットへ入れると、後勝ちで見た目が入れ替わる。
                    log.warning("[" + PATH + "] アイテム '" + id + "' が '" + previous
                            + "' と '" + asset + "' の両方に載っています。'" + asset + "' を採用します");
                    valid = false;
                }
            }
        }
        this.assetByCatalogId = Map.copyOf(parsed);
        log.info("[" + PATH + "] loaded " + parsed.size() + " equipment asset binding(s)"
                + (valid ? " OK" : " with warnings"));
        return valid;
    }

    /**
     * {@code catalogId} に割り当てられたアセット名。未配線なら {@code null}
     * （＝{@code asset_id} を書かない＝バニラの見た目のまま）。
     */
    public String assetFor(String catalogId) {
        return catalogId == null ? null : assetByCatalogId.get(catalogId);
    }

    /** テストと診断用の読み取り専用ビュー。 */
    public Map<String, String> bindings() {
        return assetByCatalogId;
    }
}
