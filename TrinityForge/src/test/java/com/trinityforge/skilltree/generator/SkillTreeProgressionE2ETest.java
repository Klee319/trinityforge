package com.trinityforge.skilltree.generator;

import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage: load the shipped canonical {@code skilltree/light_weapons.yml} through the real
 * {@link SkillTreeConfig} loader, generate its ValhallaMMO progression, and verify the artifact offline —
 * perk count, pillar/greek/branch mapping, reciprocal exclusivity, prestige, lang integrity, buff
 * exclusion, and that the hand-emitted YAML re-parses cleanly through Bukkit's YAML engine.
 */
class SkillTreeProgressionE2ETest {

    private static final Pattern LANG_REF = Pattern.compile("<lang\\.([A-Za-z0-9_]+)>");

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("SkillTreeProgressionE2ETest");
            case "saveResource" -> throw new AssertionError("saveResource() must not run headlessly");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static SkillTree loadLightWeapons(File dataFolder) throws IOException {
        File file = new File(new File(dataFolder, SkillTreeConfig.DIR), "light_weapons.yml");
        Files.createDirectories(file.getParentFile().toPath());
        try (InputStream in = SkillTreeProgressionE2ETest.class.getClassLoader()
                .getResourceAsStream("skilltree/light_weapons.yml")) {
            assertNotNull(in, "bundled skilltree/light_weapons.yml must be on the test classpath");
            Files.copy(in, file.toPath());
        }
        SkillTreeConfig config = new SkillTreeConfig();
        assertTrue(config.load(fakePlugin(dataFolder)), "canonical tree loads clean");
        return config.tree("LIGHT_WEAPONS").orElseThrow();
    }

    @Test
    @DisplayName("light_weapons: load -> generate -> verify structure, wiring, lang, prestige")
    void endToEnd(@TempDir File dataFolder) throws IOException {
        SkillTree tree = loadLightWeapons(dataFolder);

        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(tree);

        // 24 nodes (5 main + 15 greek + 4 branch) + 1 root + prestige tier perk × max-times(3)。
        // 2026-07-30: 「プレステージは1個」という前提で26を期待していたが、生成側は prestige.max-times
        // 回数分のtierを鎖状に生成する(SkillTreeProgressionGenerator参照)ので実際は28。
        assertEquals(28, gen.perks().size());
        assertEquals("2,10", gen.startingCoordinates());
        assertNotNull(gen.perks().get("lightweapons_perk_root"));
        assertEquals(List.of("lightweapons_perk_root"),
                gen.perks().get("lightweapons_perk_a").requirePerkAll());

        // Pillars A..E map to required levels 10/30/50/70/90 and chain by parent.
        assertEquals(10, gen.perks().get("lightweapons_perk_a").requiredLv());
        assertEquals(90, gen.perks().get("lightweapons_perk_e").requiredLv());
        assertEquals(List.of("lightweapons_perk_d"),
                gen.perks().get("lightweapons_perk_e").requirePerkAll());

        // TF buffs never present in generated YAML (attack_speed moved to buffs channel).
        for (String buffKey : List.of("attack_power", "crit_chance", "crit_damage",
                "bleed_chance", "bleed_damage", "attack_speed")) {
            assertFalse(gen.yaml().contains(buffKey), "YAML must not mention TF buff key " + buffKey);
        }

        // A-greek: three members lock each other reciprocally.
        assertEquals(List.of("lightweapons_perk_a_beta_1", "lightweapons_perk_a_gamma_1"),
                gen.perks().get("lightweapons_perk_a_alpha_1").perkRewards().get("perks_locked_add"));
        assertEquals(List.of("lightweapons_perk_a_alpha_1", "lightweapons_perk_a_gamma_1"),
                gen.perks().get("lightweapons_perk_a_beta_1").perkRewards().get("perks_locked_add"));
        assertEquals(List.of("lightweapons_perk_a_alpha_1", "lightweapons_perk_a_beta_1"),
                gen.perks().get("lightweapons_perk_a_gamma_1").perkRewards().get("perks_locked_add"));

        // Prestige perk generated with reset + permanent rewards; visible at trunk apex.
        GeneratedPerk ng = gen.perks().get("lightweapons_perk_ng1");
        assertNotNull(ng);
        assertFalse(ng.hidden());
        assertEquals(0, ng.perkRewards().get("reset_skill_light_weapons"));

        // Lang integrity: every reference in the YAML resolves.
        for (GeneratedPerk perk : gen.perks().values()) {
            assertTrue(gen.lang().containsKey(perk.nameKey()));
            assertTrue(gen.lang().containsKey(perk.descriptionKey()));
        }
        Matcher matcher = LANG_REF.matcher(gen.yaml());
        while (matcher.find()) {
            assertTrue(gen.lang().containsKey(matcher.group(1)),
                    "unresolved lang ref: " + matcher.group(1));
        }

        // Deterministic across runs.
        assertEquals(gen.yaml(), SkillTreeProgressionGenerator.generate(tree).yaml());
    }

    @Test
    @DisplayName("emitted YAML re-parses through Bukkit's YAML engine with matching structure")
    void yamlReparses(@TempDir File dataFolder) throws IOException {
        SkillTree tree = loadLightWeapons(dataFolder);
        GeneratedProgression gen = SkillTreeProgressionGenerator.generate(tree);

        File out = new File(dataFolder, "light_weapons_progression.yml");
        Files.writeString(out.toPath(), gen.yaml());
        YamlConfiguration parsed = YamlConfiguration.loadConfiguration(out);

        assertEquals("2,10", parsed.getString("starting_coordinates"));
        ConfigurationSection perks = parsed.getConfigurationSection("perks");
        assertNotNull(perks);
        assertEquals(gen.perks().size(), perks.getKeys(false).size());

        // Root + A pillar coordinates survive the round trip.
        assertEquals("2,10", perks.getString("lightweapons_perk_root.coords"));
        assertEquals("2,8", perks.getString("lightweapons_perk_a.coords"));
        assertEquals(90, perks.getInt("lightweapons_perk_e.required_lv"));
        // Exclusive list survives as a real YAML sequence.
        assertEquals(List.of("lightweapons_perk_a_beta_1", "lightweapons_perk_a_gamma_1"),
                perks.getStringList("lightweapons_perk_a_alpha_1.perk_rewards.perks_locked_add"));
    }
}
