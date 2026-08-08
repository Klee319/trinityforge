package com.trinityforge.config.domains;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code items/equipment-assets.yml} と、リソースパックの実物との突き合わせ。
 *
 * <p><b>これが守っている事故:</b> パックに定義の無い {@code asset_id} をアイテムへ書くと、
 * その防具は着たときに<b>バニラの見た目に戻るのではなく透明になる</b>。
 * yml は {@code resourcepack/build_equipment_assets.py} の生成物で、
 * 「パックに実物があるセットだけを書く」のが唯一の安全弁なので、
 * 手編集や生成漏れでその不変条件が崩れていないかをここで実際に確かめる。
 *
 * <p>パス基準はモジュールルート（{@code TrinityForge/}）。パックはその 1 つ上にある。
 * パックごと存在しない環境では検査対象が無いだけなので、その場合だけ検査を飛ばす
 * （yml に載っているのにパックが無い、は下で必ず落ちる）。
 */
class ShippedEquipmentAssetsTest {

    private static final File YML = new File("src/main/resources/items/equipment-assets.yml");
    private static final File PACK = new File("../resourcepack/trinityforge-items/assets/trinityforge");

    private static Map<String, String> shippedBindings() {
        assertTrue(YML.isFile(), YML + " が無い。ItemFactory が saveResource() で配るので jar に必須");
        EquipmentAssetsConfig config = new EquipmentAssetsConfig();
        config.parse(YamlConfiguration.loadConfiguration(YML).getConfigurationSection("equipment-assets"),
                Logger.getLogger("test"));
        return config.bindings();
    }

    @Test
    void everyAssetTheYmlBindsHasARealDefinitionAndTextureInThePack() {
        for (String asset : shippedBindings().values().stream().distinct().toList()) {
            File definition = new File(PACK, "equipment/" + asset + ".json");
            assertTrue(definition.isFile(),
                    "yml が '" + asset + "' を配線しているのにパックに " + definition.getPath()
                            + " が無い。この状態で配るとその防具は着たときに透明になる");

            boolean anyLayer = new File(PACK, "textures/entity/equipment/humanoid/" + asset + ".png").isFile()
                    || new File(PACK, "textures/entity/equipment/humanoid_leggings/" + asset + ".png").isFile();
            assertTrue(anyLayer,
                    "yml が '" + asset + "' を配線しているのにレイヤー PNG が 1 枚も無い");
        }
    }

    @Test
    void theShippedYmlParsesWithoutWarnings() {
        assertTrue(YML.isFile());
        EquipmentAssetsConfig config = new EquipmentAssetsConfig();
        assertTrue(config.parse(YamlConfiguration.loadConfiguration(YML).getConfigurationSection("equipment-assets"),
                        Logger.getLogger("test")),
                "出荷 yml は警告なしで読めること（生成物なので、警告が出る時点で生成側が壊れている）");
    }
}
