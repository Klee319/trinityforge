package com.trinityforge.mob;

import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.pdc.PdcKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobDisplayNames}: TF側の表示・ログに出すモブ名の解決順
 * (mob-overrides の display-name → customName → EntityType の翻訳可能名)。
 *
 * <p>この解決順が壊れると、出荷ymlに入れた396体ぶんの日本語名が頭上表示に出なくなる(英語の
 * EliteMobs名に戻る)が、戦闘には一切影響しないため実プレイでもテストでも気付きにくい。
 */
class MobDisplayNamesTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("em_id_the_mines_1");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MobOverridesConfig loadedConfig(File dataFolder, String yaml) throws Exception {
        File file = new File(dataFolder, MobOverridesConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        MobOverridesConfig config = new MobOverridesConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    private static org.bukkit.plugin.Plugin fakePlugin(File dataFolder) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("MobDisplayNamesTest");
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class}, handler);
    }

    private Zombie stampedZombie(String profileId) {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        if (profileId != null) {
            zombie.getPersistentDataContainer()
                    .set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, profileId);
        }
        return zombie;
    }

    private static final String YAML = """
            overrides:
              em_id_the_mines:
                display-name: "鉱山"
                mobs:
                  the_mines_arachnidian_construct:
                    display-name: "蜘蛛型構造体"
            """;

    @Test
    void displayNameWinsOverCustomName(@TempDir File dir) throws Exception {
        MobDisplayNames names = new MobDisplayNames(loadedConfig(dir, YAML));
        Zombie zombie = stampedZombie("the_mines_arachnidian_construct");
        zombie.customName(Component.text("Arachnidian Construct"));

        Component resolved = names.displayName(zombie);

        assertInstanceOf(TextComponent.class, resolved);
        assertEquals("蜘蛛型構造体", ((TextComponent) resolved).content());
    }

    @Test
    void blueprintWorldNameMatchesInstancedWorld(@TempDir File dir) throws Exception {
        // 実ワールド名は em_id_the_mines_1 (入場ごとに連番が増える)。設計図名で書いたスコープが当たること。
        MobDisplayNames names = new MobDisplayNames(loadedConfig(dir, YAML));
        Zombie zombie = stampedZombie("the_mines_arachnidian_construct");

        assertEquals(Optional.of("蜘蛛型構造体"), names.configuredName(zombie));
    }

    @Test
    void fallsBackToCustomNameWhenNoDisplayNameConfigured(@TempDir File dir) throws Exception {
        MobDisplayNames names = new MobDisplayNames(loadedConfig(dir, YAML));
        Zombie zombie = stampedZombie("some_unconfigured_mob");
        zombie.customName(Component.text("Unconfigured Boss"));

        Component resolved = names.displayName(zombie);

        assertEquals("Unconfigured Boss", ((TextComponent) resolved).content());
    }

    @Test
    void fallsBackToTranslatableEntityTypeForPlainMob(@TempDir File dir) throws Exception {
        MobDisplayNames names = new MobDisplayNames(loadedConfig(dir, YAML));
        Zombie zombie = stampedZombie(null); // TF管理外(スタンプ無し)

        assertTrue(names.configuredName(zombie).isEmpty());
        assertInstanceOf(TranslatableComponent.class, names.displayName(zombie));
    }

    @Test
    void logLabelPairsJapaneseNameWithRawId(@TempDir File dir) throws Exception {
        MobDisplayNames names = new MobDisplayNames(loadedConfig(dir, YAML));

        assertEquals("蜘蛛型構造体 (the_mines_arachnidian_construct)",
                names.logLabel(stampedZombie("the_mines_arachnidian_construct")));
        assertEquals("some_unconfigured_mob", names.logLabel(stampedZombie("some_unconfigured_mob")));
        assertEquals("ZOMBIE", names.logLabel(stampedZombie(null)));
    }
}
