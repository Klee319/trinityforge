package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Shared test wiring for the combat integration tests: a reflective fake {@link Plugin} whose
 * {@code saveResource} copies the SHIPPED default YAML off the test classpath into a temp data folder,
 * so {@link ConfigManager#loadAll()} produces the real, fully-populated config tree without a live
 * server. Mirrors the reflective-fake-plugin pattern the config-loader unit tests use, extended to
 * actually materialise the bundled resources.
 */
final class CombatWiringSupport {

    private CombatWiringSupport() {
    }

    /** A ConfigManager loaded from the shipped defaults under {@code dataFolder}. */
    static ConfigManager loadedConfigManager(File dataFolder) {
        ConfigManager manager = new ConfigManager(resourcePlugin(dataFolder));
        manager.loadAll();
        return manager;
    }

    /**
     * A {@link CombatDamageConfig} loaded from a caller-authored {@code damage.yml} (so a test can flip
     * {@code magical.scale-with-combat-level}); schema defaults fill every unset knob.
     *
     * <p><b>PvP抑制は既定でOFFにする</b>(2026-07-27): これらの統合テストは「被害者」に
     * {@code server.addPlayer()} を使っており、実態としては全部 player→player である。出荷既定
     * ({@code pvp.enabled: true})のままだと、メイススマッシュ/チャージ減衰/防護軽減/ステ上限といった
     * <b>PvPとは無関係な検証まで全部PvP係数で目減りする</b>。ここで既定OFFにしておくことで、
     * 各テストは「自分が検証したい機構だけ」を見られる。PvP抑制そのものの検証は純関数テスト
     * {@code PvpDamagePolicyTest} が受け持つ。呼び出し側の yaml が {@code pvp:} を自分で書いている
     * 場合はそちらを尊重して何も足さない(将来PvPの統合テストを書けるようにするため)。
     */
    static CombatDamageConfig combatDamageFrom(File dataFolder, String damageYaml) throws IOException {
        File file = new File(dataFolder, CombatDamageConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        String yaml = damageYaml.contains("pvp:")
                ? damageYaml
                : damageYaml + "\npvp:\n  enabled: false\n";
        Files.writeString(file.toPath(), yaml);
        CombatDamageConfig config = new CombatDamageConfig();
        config.domain().load(resourcePlugin(dataFolder));
        return config;
    }

    /** Reflective {@link Plugin} backed by {@code dataFolder}, copying bundled resources on demand. */
    static Plugin resourcePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CombatWiringSupport");
            case "saveResource" -> {
                copyResource((String) args[0], dataFolder);
                yield null;
            }
            case "getResource" -> CombatWiringSupport.class.getResourceAsStream("/" + args[0]);
            case "toString" -> "ResourcePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static void copyResource(String resourcePath, File dataFolder) {
        try (InputStream in = CombatWiringSupport.class.getResourceAsStream("/" + resourcePath)) {
            if (in == null) {
                return; // No bundled default: the loader keeps its in-memory defaults (loadAll tolerates it).
            }
            Path target = new File(dataFolder, resourcePath).toPath();
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to copy bundled resource " + resourcePath, ex);
        }
    }
}
