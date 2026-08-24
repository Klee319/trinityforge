package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.effects.FeatureEffectDefinition;
import com.trinityforge.skilltree.effects.FeatureEffectRegistry;
import com.trinityforge.skilltree.effects.GateEffectId;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for the {@code skilltree/} folder: each {@code <skill>.yml} is one canonical {@link SkillTree}
 * (SKILL_TREE design sections A–E). On load the bundled defaults are copied out of the plugin jar (best
 * effort), every {@code *.yml} in the data folder is parsed, and the immutable trees are exposed via
 * {@link #tree(String)} / {@link #all()}. Nothing runtime is wired here — that (PerkBuffResolver,
 * progression generator) is a later phase; this only owns the config → immutable-model → reload contract.
 *
 * <p>Follows the same robustness rules as the other domains: a YAML syntax error in a file skips only
 * that file (the rest still load); a malformed node (missing {@code name}/{@code level}/{@code role},
 * unknown role) is skipped with a warning; a disallowed or non-finite {@code buffs} entry is dropped
 * while the node is kept; a dangling {@code parent} or a single-member exclusive {@code group} is a
 * warning only. Any of these makes {@link #load(Plugin)} return {@code false} but never aborts the load
 * or crashes {@code onEnable}/reload. Buff keys are folded through {@link StatKeys#canonical} so they line
 * up with the combat pipeline's stat-key space.
 */
public final class SkillTreeConfig implements LoadableConfig {

    /** Data-folder subdirectory (and jar resource prefix) holding one YAML per skill tree. */
    public static final String DIR = "skilltree";

    // skill id (from the file's `skill:` field) -> immutable tree. Swapped atomically on reload.
    private volatile Map<String, SkillTree> trees = Map.of();

    /**
     * 直近の {@link #load(Plugin)} が<b>1件も問題なく</b>終わったか (2026-08-24 / W-213)。
     *
     * <p>「ツリーから消えたノードのperkを剥がしてSPを返す」掃除
     * ({@code SkillTreePerkPruner}) の安全弁。ノードが1つでも壊れて読み飛ばされた回は
     * 「消えた」と「読めなかった」を区別できないので、掃除ごと見送らせる。
     * 初期値 {@code false} = まだ一度も読んでいない状態では掃除しない。
     */
    private volatile boolean lastLoadOk;

    /** 直近のロードが完全成功したか。{@code SkillTreePerkPruner} の起動条件。 */
    public boolean lastLoadOk() {
        return lastLoadOk;
    }

    /** The tree for {@code skillId} (case-insensitive), or empty when none is loaded. */
    public Optional<SkillTree> tree(String skillId) {
        if (skillId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(trees.get(skillId.toUpperCase(Locale.ROOT)));
    }

    /** Every loaded tree, keyed by upper-cased skill id. Immutable. */
    public Map<String, SkillTree> all() {
        return trees;
    }

    public String resourcePath() {
        return DIR;
    }

    @Override
    public boolean load(Plugin plugin) {
        // 成否の記録は【出口を1つに絞って】行う。個々の return 側に書くと、
        // 後から分岐が増えたときに書き忘れて「壊れた回も掃除が走る」側へ倒れる。
        boolean ok = loadTrees(plugin);
        this.lastLoadOk = ok;
        return ok;
    }

    private boolean loadTrees(Plugin plugin) {
        Logger log = plugin.getLogger();
        saveBundledDefaults(plugin, log);

        File dir = new File(plugin.getDataFolder(), DIR);
        if (!dir.isDirectory()) {
            this.trees = Map.of();
            log.info("[" + DIR + "] no skill-tree directory; 0 tree(s) loaded");
            return true;
        }

        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            this.trees = Map.of();
            log.info("[" + DIR + "] no *.yml files; 0 tree(s) loaded");
            return true;
        }

        Map<String, SkillTree> parsed = new LinkedHashMap<>();
        int issues = 0;
        for (File file : files) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (InvalidConfigurationException | IOException ex) {
                // Syntax error in one file: skip only this file, keep loading the rest.
                log.log(Level.SEVERE, "[" + DIR + "/" + file.getName()
                        + "] YAML構文エラーのため、このファイルをスキップしました: " + ex.getMessage(), ex);
                issues++;
                continue;
            }
            TreeParse result = parseTree(yaml, file.getName(), log);
            if (result.tree() == null) {
                issues++;
                continue;
            }
            String key = result.tree().skill().toUpperCase(Locale.ROOT);
            if (parsed.containsKey(key)) {
                log.warning("[" + DIR + "/" + file.getName() + "] duplicate skill id '" + key
                        + "' already loaded from another file; skipped");
                issues++;
                continue;
            }
            parsed.put(key, result.tree());
            if (result.hadIssues()) {
                issues++;
            }
        }
        // Cross-tree unique-effect duplicate check (動的ID方式改修): every unlock-family gate id (glyph:/
        // recipe:/ritual:/drop:/brew:/trade:/feature:/overenchant:/reward:; ars-tier is additive and
        // exempt) must be placed on at most one node total, across every loaded tree.
        if (validateUniqueDedicatedEffects(parsed, log)) {
            issues++;
        }

        // Atomic publish: when a previously good snapshot exists, any YAML syntax/unusable-file
        // failure that would drop a skill aborts the swap so broken reloads cannot erase trees.
        if (!this.trees.isEmpty()) {
            boolean lostSkill = this.trees.keySet().stream().anyMatch(key -> !parsed.containsKey(key));
            if (lostSkill) {
                log.severe("[" + DIR + "] reload aborted: keeping previous "
                        + this.trees.size() + " tree(s); candidate had " + parsed.size()
                        + " tree(s) with " + issues + " issue(s)");
                return false;
            }
        }

        this.trees = Map.copyOf(parsed);

        if (issues > 0) {
            log.warning("[" + DIR + "] loaded " + parsed.size() + " tree(s), " + issues
                    + " file(s) with issue(s)");
            return false;
        }
        log.info("[" + DIR + "] loaded " + parsed.size() + " tree(s) OK");
        return true;
    }

    /**
     * Parses one file's top-level tree. Returns a {@code null} tree only when the file is unusable
     * (missing {@code skill} id); otherwise the tree is always built and {@code hadIssues} flags whether
     * any node/buff/parent/group warning fired.
     */
    static TreeParse parseTree(ConfigurationSection root, String fileName, Logger log) {
        String skill = nullableString(root, "skill");
        if (skill == null) {
            log.warning("[" + DIR + "/" + fileName + "] missing required 'skill' id; file skipped");
            return new TreeParse(null, true);
        }
        String tag = "[" + DIR + "/" + fileName + "] ";

        Issues issues = new Issues();
        Map<String, SkillNode> nodes = parseNodes(root.getConfigurationSection("nodes"), tag, skill, log, issues);
        Prestige prestige = parsePrestige(root.getConfigurationSection("prestige"), tag, skill, log, issues);

        String displayName = firstNonNull(nullableString(root, "display-name"), skill);
        SkillTree tree = new SkillTree(
                skill,
                displayName,
                nullableString(root, "icon"),
                firstNonNull(nullableString(root, "starting-coords"), ""),
                prestige,
                nodes);
        return new TreeParse(tree, issues.any);
    }

    private static Map<String, SkillNode> parseNodes(ConfigurationSection root, String tag, String skill,
                                                     Logger log, Issues issues) {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(id);
                if (entry == null) {
                    log.warning(tag + "node '" + id + "' is not a section; skipped");
                    issues.mark();
                    continue;
                }
                SkillNode node = parseNode(id, entry, tag, skill, log, issues);
                if (node == null) {
                    issues.mark();
                    continue;
                }
                // ConfigurationSection keys are already unique (YAML collapses duplicates); guard anyway.
                if (nodes.putIfAbsent(id, node) != null) {
                    log.warning(tag + "duplicate node id '" + id + "'; skipped");
                    issues.mark();
                }
            }
        }
        // Second pass: parent existence + exclusive-group health (warnings only; nodes retained).
        validateGraph(nodes, tag, log, issues);
        return Map.copyOf(nodes);
    }

    /**
     * Builds one node. Returns {@code null} (caller skips) when a required field is missing/invalid:
     * blank {@code name}, non-integer or absent {@code level}, or an unknown {@code role}. A disallowed
     * or non-finite {@code buffs} entry is dropped (marking {@code issues}) but the node is still built.
     */
    private static SkillNode parseNode(String id, ConfigurationSection entry, String tag, String skill,
                                       Logger log, Issues issues) {
        String name = nullableString(entry, "name");
        if (name == null) {
            log.warning(tag + "node '" + id + "' missing required 'name'; skipped");
            return null;
        }
        if (!entry.isInt("level")) {
            log.warning(tag + "node '" + id + "' missing or non-integer 'level'; skipped");
            return null;
        }
        Optional<SkillRole> role = SkillRole.fromConfig(entry.getString("role"));
        if (role.isEmpty()) {
            log.warning(tag + "node '" + id + "' has missing/unknown role '" + entry.getString("role")
                    + "'; skipped");
            return null;
        }
        return new SkillNode(
                id,
                name,
                entry.getInt("level"),
                role.get(),
                nullableString(entry, "parent"),
                entry.getStringList("parents-any"),
                nullableString(entry, "group"),
                nullableString(entry, "icon"),
                entry.getInt("cost", 1),
                description(entry),
                parseBuffs(entry.getConfigurationSection("buffs"), tag, id, log, issues),
                parseBuffs(entry.getConfigurationSection("mainhand-buffs"), tag, id + ".mainhand-buffs", log, issues),
                parseMultipliers(entry.getConfigurationSection("multipliers"), tag, id, log, issues),
                parseMultipliers(entry.getConfigurationSection("mainhand-multipliers"), tag,
                        id + ".mainhand-multipliers", log, issues),
                parseSetBuffs(entry.getConfigurationSection("set-buffs"), tag, id, skill, log, issues),
                parseNative(entry.getConfigurationSection("native")),
                entry.getStringList("commands"),
                entry.getStringList("effects"),
                parseDedicatedEffects(entry.getList("dedicated-effects"), tag, id, log, issues));
    }

    /** Skill ids allowed to carry {@code set-buffs} (SKILL_TREE armor-set-buffs migration §1). */
    private static final Set<String> SET_BUFF_SKILLS = Set.of("LIGHT_ARMOR", "HEAVY_ARMOR");

    /**
     * Parses {@code set-buffs}: {@code <3|4>: {stat: value, ...}}. The armor-piece-count condition is
     * decided at runtime by {@link com.trinityforge.skilltree.runtime.PerkBuffResolver}; this loader only
     * validates shape. A tier key other than 3/4, or a {@code set-buffs} block on a tree other than
     * {@code light_armor}/{@code heavy_armor}, is dropped with a warning (node/prestige kept).
     */
    private static Map<Integer, Map<String, Double>> parseSetBuffs(ConfigurationSection section, String tag,
                                                                    String owner, String skill, Logger log,
                                                                    Issues issues) {
        if (section == null) {
            return Map.of();
        }
        if (skill == null || !SET_BUFF_SKILLS.contains(skill.toUpperCase(Locale.ROOT))) {
            log.warning(tag + "'" + owner + "' has 'set-buffs' but this tree ('" + skill
                    + "') is not light_armor/heavy_armor; ignored");
            issues.mark();
            return Map.of();
        }
        Map<Integer, Map<String, Double>> result = new LinkedHashMap<>();
        for (String rawTier : section.getKeys(false)) {
            Integer tier = null;
            try {
                tier = Integer.parseInt(rawTier.trim());
            } catch (NumberFormatException ignored) {
                // handled by the null check below
            }
            if (tier == null || (tier != 3 && tier != 4)) {
                log.warning(tag + "'" + owner + "' set-buffs tier '" + rawTier
                        + "' is not 3 or 4; ignored");
                issues.mark();
                continue;
            }
            ConfigurationSection tierSection = section.getConfigurationSection(rawTier);
            if (tierSection == null) {
                log.warning(tag + "'" + owner + "' set-buffs tier '" + rawTier + "' is not a section; ignored");
                issues.mark();
                continue;
            }
            Map<String, Double> values = parseBuffs(tierSection, tag, owner + ".set-buffs." + rawTier, log, issues);
            if (!values.isEmpty()) {
                result.put(tier, values);
            }
        }
        return result;
    }

    /**
     * 要件⑤: unified free-text description. {@code description} (new key) wins; the legacy
     * {@code effect-text} key is the fallback so every pre-existing tree yml keeps working unchanged.
     */
    private static String description(ConfigurationSection entry) {
        String description = nullableString(entry, "description");
        return description != null ? description : nullableString(entry, "effect-text");
    }

    /**
     * Parses a node's {@code dedicated-effects} placements (2026-07-23 動的ID方式改修 §3): each entry's
     * {@code id} must parse via {@link GateEffectId#parse} — an unrecognized prefix, or a bare legacy id
     * that isn't exactly {@code ars-tier} (pre-conversion static-catalog id, see the design doc §5 W2c
     * note), is dropped with a warning (node kept). {@code ars-tier} and {@code feature:<id>} additionally
     * require a numeric {@code value} exactly when the shape demands one ({@code ars-tier} always;
     * {@code feature:<id>} only when {@link FeatureEffectRegistry} says so, e.g. {@code dismantle-unlock});
     * an unknown {@code feature:<id>} vocab word is likewise dropped with a warning.
     */
    private static List<DedicatedEffectEntry> parseDedicatedEffects(List<?> raw, String tag, String nodeId,
                                                                     Logger log, Issues issues) {
        List<DedicatedEffectEntry> entries = new ArrayList<>();
        if (raw == null) {
            return entries;
        }
        for (Object rawEntry : raw) {
            if (!(rawEntry instanceof Map<?, ?> map)) {
                log.warning(tag + "node '" + nodeId + "' has a non-map 'dedicated-effects' entry; skipped");
                issues.mark();
                continue;
            }
            Object rawId = map.get("id");
            String id = rawId == null ? null : String.valueOf(rawId).trim();
            if (id == null || id.isEmpty()) {
                log.warning(tag + "node '" + nodeId + "' has a 'dedicated-effects' entry missing 'id'; skipped");
                issues.mark();
                continue;
            }
            Double value = map.get("value") instanceof Number number ? number.doubleValue() : null;

            Optional<GateEffectId> parsed = GateEffectId.parse(id);
            if (parsed.isEmpty()) {
                log.warning(tag + "node '" + nodeId + "' dedicated-effect '" + id
                        + "' has an unrecognized prefix or is a pre-conversion legacy id; skipped");
                issues.mark();
                continue;
            }

            if (id.equals(GateEffectId.ARS_TIER)) {
                if (value == null) {
                    log.warning(tag + "node '" + nodeId + "' dedicated-effect 'ars-tier' requires a numeric"
                            + " 'value'; skipped");
                    issues.mark();
                    continue;
                }
            } else {
                Optional<String> featureId = GateEffectId.featureIdOf(id);
                if (featureId.isPresent()) {
                    Optional<FeatureEffectDefinition> feature = FeatureEffectRegistry.get(featureId.get());
                    if (feature.isEmpty()) {
                        log.warning(tag + "node '" + nodeId + "' dedicated-effect '" + id
                                + "' is not a known feature vocabulary word; skipped");
                        issues.mark();
                        continue;
                    }
                    if (feature.get().param().requiresValue() && value == null) {
                        log.warning(tag + "node '" + nodeId + "' dedicated-effect '" + id + "' requires a numeric"
                                + " 'value' (param=" + feature.get().param() + ") but none was given; skipped");
                        issues.mark();
                        continue;
                    }
                    if (feature.get().param().defaultsMissingValue() && value == null) {
                        // SCALE (2026-07-25 gather-rework-active-framework §1 item 2 / §5 risk 2): a
                        // placement authored before this feature's param changed from NONE to SCALE has no
                        // 'value' at all — default it to tier 1 instead of dropping the node, so every
                        // pre-existing boolean feature:<id> placement keeps granting the feature unchanged.
                        value = 1.0;
                    }
                }
            }
            entries.add(new DedicatedEffectEntry(id, value));
        }
        return entries;
    }

    /**
     * Cross-tree unique-effect check (2026-07-23 動的ID方式改修): every unlock-family gate id (every
     * {@link GateEffectId#parse}-able id except the additive {@code ars-tier} accumulator, and except
     * {@code feature:} placements — see below) may be placed on at most one node total, across every
     * tree in {@code parsed}. Returns {@code true} (and logs one warning per offending id, listing every
     * {@code tree/node} it was found on) when any duplicate fired.
     *
     * <p>2026-07-25 (docs/design/2026-07-25-gather-rework-active-framework.md §1/§6, ユーザー承認済み):
     * {@code feature:} placements are exempt from this uniqueness check, same as {@code ars-tier}. The
     * gathering-feature vocabulary (vein-mining / tree-fell / area-harvest / haste-active-mining / …) is
     * moving from a boolean flag to a tiered placement where the SAME feature id is intentionally placed
     * on multiple nodes (tier1/tier3/tier5 …) and the runtime takes the highest held tier via
     * {@code DedicatedEffectsConfig#valueMax}. {@code recipe:}/{@code glyph:}/{@code ritual:}/
     * {@code drop:}/{@code trade:}/{@code brew:}/{@code overenchant:}/{@code reward:} placements — where a
     * duplicate really is a copy-paste accident — keep the strict one-node check.
     */
    private static boolean validateUniqueDedicatedEffects(Map<String, SkillTree> parsed, Logger log) {
        Map<String, List<String>> placements = new LinkedHashMap<>();
        for (SkillTree tree : parsed.values()) {
            for (SkillNode node : tree.nodes().values()) {
                for (DedicatedEffectEntry effect : node.dedicatedEffects()) {
                    if (effect.id().equals(GateEffectId.ARS_TIER)) {
                        continue; // additive accumulator: intentionally placeable on many nodes.
                    }
                    if (GateEffectId.featureIdOf(effect.id()).isPresent()) {
                        continue; // tiered feature placement: intentionally placeable on many nodes.
                    }
                    if (GateEffectId.parse(effect.id()).isEmpty()) {
                        continue; // unrecognized id already warned about at parse time.
                    }
                    placements.computeIfAbsent(effect.id(), k -> new ArrayList<>())
                            .add(tree.skill() + "/" + node.id());
                }
            }
        }
        boolean any = false;
        for (Map.Entry<String, List<String>> entry : placements.entrySet()) {
            if (entry.getValue().size() > 1) {
                log.warning("[" + DIR + "] unique dedicated-effect '" + entry.getKey()
                        + "' is placed on more than one node: " + String.join(", ", entry.getValue()));
                any = true;
            }
        }
        return any;
    }

    /** Parses TF {@code buffs}: canonical key -&gt; finite double, allowed-set filtered. Bad entries dropped. */
    private static Map<String, Double> parseBuffs(ConfigurationSection section, String tag, String owner,
                                                  Logger log, Issues issues) {
        Map<String, Double> buffs = new LinkedHashMap<>();
        if (section == null) {
            return buffs;
        }
        for (String rawKey : section.getKeys(false)) {
            String key = StatKeys.canonical(rawKey);
            if (!StatVocabulary.isKnown(key)) {
                log.warning(tag + "'" + owner + "' buff '" + rawKey + "' is not an allowed TF stat key; dropped");
                issues.mark();
                continue;
            }
            if (!section.isDouble(rawKey) && !section.isInt(rawKey)) {
                log.warning(tag + "'" + owner + "' buff '" + rawKey + "' is not numeric; dropped");
                issues.mark();
                continue;
            }
            double value = com.trinityforge.stats.PercentStatNormalize.coerce(key, section.getDouble(rawKey));
            if (!Double.isFinite(value)) {
                log.warning(tag + "'" + owner + "' buff '" + rawKey + "' is not finite; dropped");
                issues.mark();
                continue;
            }
            buffs.put(key, value);
        }
        return buffs;
    }

    /**
     * Parses skill-tree multiplier mode:
     * {@code multipliers.<layer-id>.<stat> = multiplier} (1.2 = x1.2).
     * Attribute-side keys are intentionally rejected because they bypass the total-stat multiplier
     * pipeline and are applied separately by {@code PerkAttributeApplier}.
     */
    private static Map<String, Map<String, Double>> parseMultipliers(
            ConfigurationSection root, String tag, String owner, Logger log, Issues issues) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        if (root == null) {
            return result;
        }
        for (String layerId : root.getKeys(false)) {
            ConfigurationSection layer = root.getConfigurationSection(layerId);
            if (layer == null || layerId.isBlank() || layerId.startsWith("__")) {
                log.warning(tag + "'" + owner + "' multiplier layer '" + layerId
                        + "' is invalid; dropped");
                issues.mark();
                continue;
            }
            Map<String, Double> values = new LinkedHashMap<>();
            for (String rawKey : layer.getKeys(false)) {
                String key = StatKeys.canonical(rawKey);
                StatVocabulary.Channel channel = StatVocabulary.channelOf(key);
                if (channel != StatVocabulary.Channel.ATTACK && channel != StatVocabulary.Channel.DEFENSE
                        && channel != StatVocabulary.Channel.GENERAL) {
                    log.warning(tag + "'" + owner + "' multiplier '" + layerId + "." + rawKey
                            + "' is not an allowed total-stat key; dropped");
                    issues.mark();
                    continue;
                }
                if ((!layer.isDouble(rawKey) && !layer.isInt(rawKey))
                        || !Double.isFinite(layer.getDouble(rawKey))) {
                    log.warning(tag + "'" + owner + "' multiplier '" + layerId + "." + rawKey
                            + "' is not a finite number; dropped");
                    issues.mark();
                    continue;
                }
                values.put(key, layer.getDouble(rawKey));
            }
            if (!values.isEmpty()) {
                result.put(layerId, Map.copyOf(values));
            }
        }
        return result;
    }

    /**
     * Parses the Valhalla {@code native} perk-reward block as a raw key -&gt; value map (values are left
     * as authored, typically numbers). Not validated against the TF stat space — Valhalla owns these.
     */
    private static Map<String, Object> parseNative(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (section == null) {
            return map;
        }
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value != null) {
                map.put(key, value);
            }
        }
        return map;
    }

    private static Prestige parsePrestige(ConfigurationSection section, String tag, String skill, Logger log,
                                          Issues issues) {
        if (section == null) {
            return null;
        }
        // プレステージ上限回数(ng1..ngN): 未設定/1未満は1(従来の単一tier挙動)に丸められる(Prestigeのコンパクト
        // コンストラクタで再度clampされるため、ここでの丸めは可読性目的の二重ガード)。
        int maxTimes = Math.max(1, section.getInt("max-times", 1));
        return new Prestige(
                section.getBoolean("enabled", false),
                section.getInt("at-level", 100),
                nullableString(section, "name"),
                description(section),
                parseBuffs(section.getConfigurationSection("buffs"), tag, "prestige", log, issues),
                parseBuffs(section.getConfigurationSection("mainhand-buffs"), tag, "prestige.mainhand-buffs", log, issues),
                parseMultipliers(section.getConfigurationSection("multipliers"), tag, "prestige", log, issues),
                parseMultipliers(section.getConfigurationSection("mainhand-multipliers"), tag,
                        "prestige.mainhand-multipliers", log, issues),
                parseSetBuffs(section.getConfigurationSection("set-buffs"), tag, "prestige", skill, log, issues),
                parseNative(section.getConfigurationSection("native")),
                maxTimes);
    }

    /**
     * Second-pass structural checks (warnings only, nodes retained): every non-null {@code parent} must
     * name a node in the same tree, and every exclusive {@code group} should have at least two members
     * (a single-member group cannot actually be exclusive). Marks {@code issues} for any warning.
     */
    private static void validateGraph(Map<String, SkillNode> nodes, String tag, Logger log, Issues issues) {
        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        Set<String> groupNames = new java.util.LinkedHashSet<>();
        for (SkillNode node : nodes.values()) {
            for (String parent : node.prerequisiteParents()) {
                if (!nodes.containsKey(parent)) {
                    log.warning(tag + "node '" + node.id() + "' parent '" + parent
                            + "' does not exist in this tree");
                    issues.mark();
                }
            }
            if (node.group() != null && node.parent() != null) {
                groupNames.add(node.group());
                groupCounts.merge(node.parent() + "\u0000" + node.group(), 1, Integer::sum);
            }
        }
        for (String groupName : groupNames) {
            boolean hasExclusiveSiblings = groupCounts.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().endsWith("\u0000" + groupName)
                            && entry.getValue() >= 2);
            if (!hasExclusiveSiblings) {
                log.warning(tag + "exclusive group '" + groupName
                        + "' has no same-parent sibling pair; exclusivity is a no-op");
                issues.mark();
            }
        }
        for (SkillNode node : nodes.values()) {
            if (hasCycle(node.id(), node.id(), nodes, new java.util.HashSet<>())) {
                log.warning(tag + "node '" + node.id() + "' participates in a prerequisite cycle");
                issues.mark();
            }
        }
    }

    private static boolean hasCycle(String origin, String current, Map<String, SkillNode> nodes,
                                    Set<String> visited) {
        SkillNode node = nodes.get(current);
        if (node == null) return false;
        for (String parent : node.prerequisiteParents()) {
            if (origin.equals(parent)) return true;
            if (visited.add(parent) && hasCycle(origin, parent, nodes, visited)) return true;
        }
        return false;
    }

    /**
     * Copies bundled {@code skilltree/*.yml} defaults out of the plugin jar for any that are missing on
     * disk. Best effort: when the plugin is not running from a jar (unit tests, exploded classes) or the
     * code source cannot be resolved, this quietly does nothing and the loader simply reads whatever is
     * already in the data folder.
     */
    private static void saveBundledDefaults(Plugin plugin, Logger log) {
        try {
            var codeSource = plugin.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return;
            }
            URL location = codeSource.getLocation();
            if (location == null) {
                return;
            }
            File jar = new File(location.toURI());
            if (!jar.isFile()) {
                return; // exploded classpath (tests) — nothing to copy from.
            }
            try (JarFile jarFile = new JarFile(jar)) {
                var entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()
                            || !name.startsWith(DIR + "/")
                            || !name.toLowerCase(Locale.ROOT).endsWith(".yml")) {
                        continue;
                    }
                    File out = new File(plugin.getDataFolder(), name);
                    if (!out.exists()) {
                        plugin.saveResource(name, false);
                    }
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException ex) {
            log.log(Level.WARNING, "[" + DIR + "] bundled default copy skipped: " + ex.getMessage());
        }
    }

    private static String nullableString(ConfigurationSection section, String key) {
        String raw = section.getString(key);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null")) ? null : trimmed;
    }

    private static String firstNonNull(String primary, String fallback) {
        return primary != null ? primary : fallback;
    }

    /** Parse outcome for one file: the tree (null when unusable) and whether any warning fired. */
    record TreeParse(SkillTree tree, boolean hadIssues) {
    }

    /** Mutable warning accumulator threaded through a single file's parse (single-threaded). */
    private static final class Issues {
        private boolean any;

        void mark() {
            this.any = true;
        }
    }
}
