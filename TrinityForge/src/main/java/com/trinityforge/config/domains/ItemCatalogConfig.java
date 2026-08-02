package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.RecipeIngredient;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Loader for {@code items/catalog.yml}: named item templates so new items are added by config alone
 * (concern: define detailed/extra items in an external file). The {@code items:} section is
 * open-ended (arbitrary item ids). Malformed entries are skipped with a warning; the rest load.
 * Snapshots swap atomically so {@code /trinityforge reload} picks up new/edited items live.
 *
 * <p>{@link #parse(ConfigurationSection, Logger)} is separated from {@link #load(Plugin)} so the
 * parsing is unit-testable headlessly without a Plugin.
 */
public final class ItemCatalogConfig implements LoadableConfig {

    public static final String PATH = "items/catalog.yml";
    private static final String ROOT = "items";
    private static final BindType DEFAULT_BIND = BindType.TRADEABLE;
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private volatile Map<String, ItemTemplate> templates = Map.of();
    private volatile Set<String> draftIds = Set.of();

    public Optional<ItemTemplate> template(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    public Map<String, ItemTemplate> all() {
        return templates;
    }

    /**
     * {@code draft: true}(エディタの「準備中」カテゴリ)と宣言され、<b>ゲーム側へ配線していない</b>
     * アイテムの ID 集合。{@link #template(String)} / {@link #all()} からは既に除いてあるので、
     * 通常のゲームプレイ経路がこれを見る必要は無い。
     *
     * <p>唯一の用途は<b>「解決に失敗した」と「意図して配らない」を区別したい所</b>。具体的には
     * ガチャで、景品が解決できないと {@code GachaListener} は<b>券を消費しない</b>設計になっており
     * (景品ロスト防止)、準備中のアイテムを景品欄に置いたままにすると
     * <b>券が減らないまま引き直せる</b>=実質無限ガチャになる。抽選前に除外するために要る。
     */
    public Set<String> draftIds() {
        return draftIds;
    }

    /**
     * このIDが「準備中」として配線対象から外されているか。{@code custom:} 接頭辞は剥がして判定する
     * ({@link #stripCustomPrefix} は他ドメイン(例: {@code CollectionListener#addWatched})と同じ規約)。
     *
     * <p>2026-08-02 敵対的レビュー指摘2: 剥がさずに生IDで判定していたため、editorが正規化する
     * {@code custom:<id>} 形式で書かれた draft ID(例: {@code gacha.yml} の
     * {@code item: custom:abyss_sword})が判定をすり抜けていた。{@code GachaListener} 側の
     * {@code create()} は下流で {@code custom:} を剥がすため、解決不能でも「景品未解決」として
     * <b>券を消費しない</b>フェイルセーフに乗ってしまい、実質無限ガチャになる。
     */
    public boolean isDraft(String id) {
        if (id == null) {
            return false;
        }
        String bare = stripCustomPrefix(id);
        return bare != null && draftIds.contains(bare);
    }

    public String resourcePath() {
        return PATH;
    }

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }

        // loadConfiguration(File) は構文エラーを握り潰して空configを返すため自前でload()する。
        // 構文エラー時は直前に成功ロード済みのtemplates(初回失敗時はMap.of())を維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }
        ParseResult result = parse(yaml.getConfigurationSection(ROOT), log);

        // draft(準備中)はここで【ゲーム側の参照面から落とす】。ゲームプレイ側の全経路
        // (レシピ登録・ドロップ・ガチャ・実績報酬・図鑑・分解・金床/鍛冶台)は例外なく
        // template(id) / all() を通るので、ここ1箇所で「配線されない」を保証できる。
        // 個々の配布サイト(12箇所以上)にガードを足す方式にすると、経路が1本増えるたびに
        // 漏れて「準備中のはずのアイテムが出た」になるため、参照面そのものを絞っている。
        // parse() の戻り値には draft も含めたまま残す — 出荷 yml を検査するテスト群
        // (ShippedCatalog*Test)は「yml に何が書いてあるか」を見るものなので、
        // ここで消すと準備中のアイテムだけ検査対象から外れてしまう。
        Map<String, ItemTemplate> live = new LinkedHashMap<>();
        Set<String> drafts = new LinkedHashSet<>();
        result.templates().forEach((id, template) -> {
            if (template.draft()) {
                drafts.add(id);
            } else {
                live.put(id, template);
            }
        });
        this.templates = Map.copyOf(live);
        this.draftIds = Set.copyOf(drafts);

        String draftNote = drafts.isEmpty() ? "" : " (準備中 " + drafts.size() + " 件は未配線)";
        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + live.size() + " item(s)" + draftNote + ", "
                    + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + live.size() + " item(s)" + draftNote + " OK");
        return true;
    }

    /** Pure parse of the {@code items:} section. Unknown/invalid entries are skipped, not fatal. */
    static ParseResult parse(ConfigurationSection root, Logger log) {
        Map<String, ItemTemplate> parsed = new LinkedHashMap<>();
        int skipped = 0;
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("[" + PATH + "] item '" + id + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                String materialName = entry.getString("material");
                Material material = materialName == null ? null : Material.matchMaterial(materialName);
                if (material == null) {
                    log.warning("[" + PATH + "] item '" + id + "' has missing/unknown material '"
                            + materialName + "'; skipped");
                    skipped++;
                    continue;
                }
                if (isAir(material)) {
                    log.warning("[" + PATH + "] item '" + id + "' material '" + materialName
                            + "' has no item form; skipped");
                    skipped++;
                    continue;
                }
                BindType bindType = parseBind(entry.getString("bind-type"), id, log);
                Integer customModelData = parseCustomModelData(entry, id, log);
                // 不正な recipe:/recipes: は「そのレシピだけ」を無効化し、アイテム本体
                // (material/lore等)のロードは継続する (fail-soft; 呼び出し元の全体成功判定には
                // skipped としてカウントし警告を表示する)。
                List<RecipeSpec> recipes = new ArrayList<>();
                if (entry.contains("recipe")) {
                    ConfigurationSection recipeSection = entry.getConfigurationSection("recipe");
                    if (recipeSection == null) {
                        log.warning("[" + PATH + "] item '" + id + "' recipe is not a section; recipe skipped");
                        skipped++;
                    } else {
                        try {
                            recipes.add(parseRecipe(recipeSection, id, log));
                        } catch (IllegalArgumentException ex) {
                            log.warning("[" + PATH + "] item '" + id + "' recipe invalid ("
                                    + ex.getMessage() + "); recipe skipped (item still loads)");
                            skipped++;
                        }
                    }
                }
                skipped += parseRecipesList(entry, id, recipes, log);
                try {
                    parsed.put(id, new ItemTemplate(
                            id,
                            material,
                            blankToNull(entry.getString("display-name")),
                            customModelData,
                            bindType,
                            parseUseLevelRequirement(entry, id, log),
                            parseUseSkill(entry, id, log),
                            parseLore(entry),
                            List.copyOf(recipes),
                            parseColor(entry, material, id, log),
                            entry.getBoolean("enchant-glow", false),
                            parseExternalSource(entry, id, log),
                            entry.getBoolean("draft", false)));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] item '" + id + "' invalid (" + ex.getMessage() + "); skipped");
                    skipped++;
                }
            }
        }
        return new ParseResult(Map.copyOf(parsed), skipped);
    }

    /**
     * Reads the optional {@code recipes:} list (multiple recipes per item, e.g. a ritual plus a
     * workbench decompression). Each element has the same shape as the single {@code recipe:} map.
     * Invalid elements are skipped individually (fail-soft).
     *
     * @return the number of skipped elements (added to the caller's skip counter)
     */
    private static int parseRecipesList(ConfigurationSection entry, String id, List<RecipeSpec> out, Logger log) {
        if (!entry.contains("recipes")) {
            return 0;
        }
        if (!entry.isList("recipes")) {
            log.warning("[" + PATH + "] item '" + id + "' recipes is not a list; recipes skipped");
            return 1;
        }
        int skipped = 0;
        List<?> raw = entry.getList("recipes", List.of());
        for (int i = 0; i < raw.size(); i++) {
            Object element = raw.get(i);
            if (!(element instanceof Map<?, ?> map)) {
                log.warning("[" + PATH + "] item '" + id + "' recipes[" + i + "] is not a map; skipped");
                skipped++;
                continue;
            }
            try {
                out.add(parseRecipe(sectionFromMap(map), id, log));
            } catch (IllegalArgumentException ex) {
                log.warning("[" + PATH + "] item '" + id + "' recipes[" + i + "] invalid ("
                        + ex.getMessage() + "); skipped (item still loads)");
                skipped++;
            }
        }
        return skipped;
    }

    /** Wraps a raw YAML map as a {@link ConfigurationSection} so {@code parseRecipe} can read it. */
    private static ConfigurationSection sectionFromMap(Map<?, ?> map) {
        Map<String, Object> stringKeyed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null) {
                stringKeyed.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        YamlConfiguration wrap = new YamlConfiguration();
        return wrap.createSection("recipe", stringKeyed);
    }

    /**
     * Reads {@code custom-model-data}, or {@code null} when absent. A present-but-non-integer value
     * (e.g. a string typo) is rejected with a warning rather than silently coerced to 0, which would
     * apply an unintended model.
     */
    private static Integer parseCustomModelData(ConfigurationSection entry, String id, Logger log) {
        if (!entry.contains("custom-model-data")) {
            return null;
        }
        if (!entry.isInt("custom-model-data")) {
            log.warning("[" + PATH + "] item '" + id + "' custom-model-data is not an integer; ignored");
            return null;
        }
        return entry.getInt("custom-model-data");
    }

    /**
     * Reads the optional {@code color:} hex string ({@code "#RRGGBB"}) used to dye a leather armor
     * piece ({@link org.bukkit.inventory.meta.LeatherArmorMeta}). Fail-soft (item 8 recipe convention):
     * an unknown/malformed hex string, or a {@code color:} on a non-{@code LEATHER_*} material, is
     * warned about and ignored (returns {@code null}) rather than skipping the whole catalog entry.
     */
    private static String parseColor(ConfigurationSection entry, Material material, String id, Logger log) {
        String raw = entry.getString("color");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!material.name().startsWith("LEATHER_")) {
            log.warning("[" + PATH + "] item '" + id + "' has 'color' but material '" + material
                    + "' is not a leather armor piece; color ignored");
            return null;
        }
        if (!HEX_COLOR.matcher(raw).matches()) {
            log.warning("[" + PATH + "] item '" + id + "' color '" + raw
                    + "' is not a '#RRGGBB' hex string; color ignored");
            return null;
        }
        return raw;
    }

    /**
     * 儀式コアの周囲に物理的に置ける台座は <b>48 台</b>（2026-08-02 訂正。かつて 16 と誤診していた
     * ── 下記参照）。フォーク実装 {@code RitualManager#findNearbyPedestals}
     * （{@code fork-handoff/arspaper/fork/.../ritual/RitualManager.java}）は
     * コアから X/Z ±2 の外周 16 マス（5x5 から 3x3 を引いた分）を、<b>Y ±1 の3段</b>にわたって
     * 走査し、同じ ingredient リストへ積む（{@code 16 × 3 = 48}）。{@code RitualRecipe#matches}
     * は multiset の完全一致を見るだけで段を区別しないため、19 台程度のレシピは Y ±1 の段を
     * 使えば普通に成立する。{@code pedestal-items} は {@code "NAME xN"} が
     * <b>台座 N 台ぶんに展開される</b>ので、行数ではなく合計台数で数える。
     *
     * <p>※かつて「物理上限は 16（Y軸を見ない1段だけ）」と誤診し、超過 4 レシピの素材を
     * 誤って軽量化する事故があった。原因はフォーク側 {@code RitualManager} 冒頭の javadoc
     * （{@code PEDESTAL_DISTANCE} のコメント）と {@code ThreadRitualRecipeConfigTest} のテスト定数が
     * どちらも「16」と書いており、それをコードで裏取りせずに定数化したこと。
     * <b>fork の doc/テスト定数側はまだ「16」のまま食い違っている</b>（このタスクでは fork 側は直さない
     * ── 別リポジトリ・別担当）。
     *
     * <p>超えていても<b>登録は止めない</b>（fail-soft）。止めるとアイテムのレシピが丸ごと消えて
     * 「なぜレシピ帳に出ないのか」が分からなくなるため。台座数が 1 段の上限（16）を超えるが
     * 3 段合計の上限（48）以内なら、Y ±1 にも台座を積む必要がある旨を案内するだけに留める
     * （実際に成立しうるので「クラフトできない」と断定しない）。3 段合計の上限（48）まで超えた
     * 場合のみ、{@code RitualRecipe#matches} が台座数の<b>完全一致</b>を要求する以上
     * <b>永久にクラフトできない</b>ため、ID を名指しで警告する。
     *
     * <p>エディタ側は {@code lib/schema.js} の {@code validatePedestalItems} が保存時に弾くが、
     * yml を直接書いた場合（スクリプト生成・手編集・エージェント）はそこを通らない。
     * 出荷 yml については {@code ShippedCatalogPedestalLimitTest} が別途固定している。
     */
    private static void warnIfPedestalsExceedRing(List<String> pedestals, String id, Logger log) {
        int total = 0;
        for (String entry : pedestals) {
            if (entry == null) {
                continue;
            }
            java.util.regex.Matcher matcher = PEDESTAL_COUNT.matcher(entry.trim());
            total += matcher.matches() ? Integer.parseInt(matcher.group(1)) : 1;
        }
        if (total > MAX_RITUAL_PEDESTALS) {
            log.warning("[" + PATH + "] item '" + id + "' の儀式レシピは台座 " + total
                    + " 台を要求していますが、コア周囲のリング(Y±1の3段合計)に置ける台座は "
                    + MAX_RITUAL_PEDESTALS + " 台までです。このレシピは登録されますが台座数が"
                    + "一致しないため【永久にクラフトできません】。pedestal-items を減らしてください");
        } else if (total > PEDESTALS_PER_RING_LAYER) {
            log.info("[" + PATH + "] item '" + id + "' の儀式レシピは台座 " + total
                    + " 台を要求しています。コアと同じ段(Y±0)には " + PEDESTALS_PER_RING_LAYER
                    + " 台までしか置けないため、コアの上下(Y±1)にも台座を配置する3段構成が必要です");
        }
    }

    /** 儀式コア周囲の台座リング1段(Y±0、チェビシェフ距離2の外周)あたりのマス数。 */
    private static final int PEDESTALS_PER_RING_LAYER = 16;

    /**
     * 儀式コア周囲の台座リングの物理上限（Y±1 の3段合計 = {@link #PEDESTALS_PER_RING_LAYER} × 3）。
     */
    private static final int MAX_RITUAL_PEDESTALS = PEDESTALS_PER_RING_LAYER * 3;

    /** {@code "NAME xN"} の N を取り出す。N が無い行は 1 台。 */
    private static final java.util.regex.Pattern PEDESTAL_COUNT =
            java.util.regex.Pattern.compile(".*\\s+x(\\d+)$");

    /**
     * {@code external-source:} を読む —— 「このIDの<b>実体</b>を持っているのは別プラグインだ」宣言。
     * 未指定/空は {@code null}(＝TF カタログが実体を持つ、従来どおり)。
     *
     * <p>これが要る理由は {@link ItemTemplate#externalSource()} の javadoc に書いてあるが、要点は
     * 「カタログにも同IDのエントリが必要(CMD台帳・レシピ・図鑑・エディタ表示の真源)だが、
     * <b>配るときに作るべき実体はフォーク側</b>」という二面性がここでしか表現できないこと。
     *
     * <p>fail-soft ({@code color:} と同じ方針): 未知のソース名は警告して宣言だけ無視する。
     * TF 側に問い合わせ経路が無い名前を黙って受け入れると、書いた側は宣言したつもりのまま
     * 従来の解決順で動き続け、<b>症状が「直っていない」としか出ない</b>ため。
     */
    private static String parseExternalSource(ConfigurationSection entry, String id, Logger log) {
        String raw = entry.getString("external-source");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!ItemTemplate.KNOWN_EXTERNAL_SOURCES.contains(normalized)) {
            log.warning("[" + PATH + "] item '" + id + "' external-source '" + raw
                    + "' は未知の外部ソース名。TF 側に問い合わせ経路が無いため宣言を無視します"
                    + "(有効値: " + ItemTemplate.KNOWN_EXTERNAL_SOURCES + ")");
            return null;
        }
        return normalized;
    }

    /**
     * Reads {@code lore}: optional flavor text lines (MiniMessage), or an empty list when absent.
     * {@code ItemTemplate}'s compact constructor defensively copies this, so the mutable list
     * {@code getStringList} returns is safe to hand off as-is.
     */
    private static List<String> parseLore(ConfigurationSection entry) {
        return entry.getStringList("lore");
    }

    /**
     * Reads {@code use-level-requirement}, or {@code 0} (unrestricted) when absent. A
     * present-but-non-integer value (e.g. a quoted {@code "20"}) is rejected with a warning rather
     * than silently coerced to {@code 0} by {@code ConfigurationSection#getInt}'s lenient default
     * fallback, which would mask a config typo as "no requirement" instead of surfacing it.
     */
    private static int parseUseLevelRequirement(ConfigurationSection entry, String id, Logger log) {
        if (!entry.contains("use-level-requirement")) {
            return 0;
        }
        if (!entry.isInt("use-level-requirement")) {
            log.warning("[" + PATH + "] item '" + id + "' use-level-requirement is not an integer; using 0");
            return 0;
        }
        return entry.getInt("use-level-requirement");
    }

    /**
     * Reads {@code use-skill}. Absent or empty is legitimately "unrestricted" and warns nothing; a
     * present-but-whitespace-only value or one containing control characters is almost certainly a
     * config mistake (stray quoting, invisible characters) rather than an intentional skill name, so
     * it is warned about and then treated as unrestricted, same as if it were absent.
     */
    private static String parseUseSkill(ConfigurationSection entry, String id, Logger log) {
        String raw = entry.getString("use-skill");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.isBlank() || containsControlChar(raw)) {
            log.warning("[" + PATH + "] item '" + id
                    + "' use-skill is blank or contains control characters; treated as unrestricted");
            return null;
        }
        return raw;
    }

    private static boolean containsControlChar(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static BindType parseBind(String raw, String id, Logger log) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BIND;
        }
        Optional<BindType> parsed = BindType.fromStorage(raw.trim().toUpperCase(Locale.ROOT));
        if (parsed.isEmpty()) {
            log.warning("[" + PATH + "] item '" + id + "' has unknown bind-type '" + raw
                    + "'; using " + DEFAULT_BIND.storageValue());
        }
        return parsed.orElse(DEFAULT_BIND);
    }

    /**
     * Reads the optional {@code recipe:} section into a {@link RecipeSpec}: {@code type} (shaped |
     * shapeless, default shaped), {@code shape} (shaped only, up to 3x3), {@code ingredients} (symbol
     * -&gt; material map for shaped, or a material list for shapeless), and {@code amount} (default 1).
     * Any structural problem (bad type, unknown material, oversized shape, undefined symbol, etc.)
     * throws {@link IllegalArgumentException} so the caller can skip just the recipe while keeping the
     * rest of the catalog entry intact.
     */
    private static RecipeSpec parseRecipe(ConfigurationSection recipe, String id, Logger log) {
        String rawMethod = recipe.getString("method", "workbench");
        RecipeSpec.Method method = switch (rawMethod.trim().toLowerCase(Locale.ROOT)) {
            case "workbench", "" -> RecipeSpec.Method.WORKBENCH;
            case "inventory" -> RecipeSpec.Method.INVENTORY;
            case "ritual" -> RecipeSpec.Method.RITUAL;
            case "combine" -> RecipeSpec.Method.COMBINE;
            case "netherite" -> RecipeSpec.Method.NETHERITE;
            default -> throw new IllegalArgumentException(
                    "recipe method must be workbench|inventory|ritual|combine|netherite, got '" + rawMethod + "'");
        };
        int amount = recipe.contains("amount") ? Math.max(1, recipe.getInt("amount")) : 1;
        boolean register = recipe.getBoolean("register", true);

        if (method == RecipeSpec.Method.RITUAL) {
            String core = blankToNull(recipe.getString("core-item"));
            List<String> pedestals = recipe.getStringList("pedestal-items");
            warnIfPedestalsExceedRing(pedestals, id, log);
            int source = Math.max(0, recipe.getInt("source", 0));
            RecipeSpec ritualSpec = RecipeSpec.ritual(core, pedestals, source, amount).withRegister(register);
            return withReversibleParsed(ritualSpec, recipe, id, log);
        }

        if (method == RecipeSpec.Method.COMBINE) {
            String sourceItem = stripCustomPrefix(blankToNull(recipe.getString("source-item")));
            if (sourceItem == null) {
                throw new IllegalArgumentException("combine recipe needs source-item");
            }
            String additionItem = stripCustomPrefix(blankToNull(recipe.getString("addition-item")));
            if (additionItem == null) {
                throw new IllegalArgumentException("combine recipe needs addition-item");
            }
            int combineExp = Math.max(0, recipe.getInt("combine-exp", 0));
            boolean inherit = recipe.getBoolean("inherit-source-quality", false);
            RecipeSpec combineSpec = RecipeSpec.combine(sourceItem, additionItem, combineExp, inherit, amount)
                    .withRegister(register);
            return withReversibleParsed(combineSpec, recipe, id, log);
        }

        if (method == RecipeSpec.Method.NETHERITE) {
            String sourceItem = stripCustomPrefix(blankToNull(recipe.getString("source-item")));
            if (sourceItem == null) {
                throw new IllegalArgumentException("netherite recipe needs source-item");
            }
            RecipeSpec netheriteSpec = RecipeSpec.netherite(sourceItem, amount).withRegister(register);
            return withReversibleParsed(netheriteSpec, recipe, id, log);
        }

        String rawType = recipe.getString("type", "shaped");
        RecipeSpec.Type type = switch (rawType.trim().toLowerCase(Locale.ROOT)) {
            case "shaped" -> RecipeSpec.Type.SHAPED;
            case "shapeless" -> RecipeSpec.Type.SHAPELESS;
            default -> throw new IllegalArgumentException("recipe type must be shaped|shapeless, got '"
                    + rawType + "'");
        };

        if (type == RecipeSpec.Type.SHAPED) {
            List<String> shape = recipe.getStringList("shape");
            ConfigurationSection ingredientsSection = recipe.getConfigurationSection("ingredients");
            if (ingredientsSection == null) {
                throw new IllegalArgumentException("shaped recipe needs an 'ingredients' symbol -> material map");
            }
            Map<Character, RecipeIngredient> ingredients = new LinkedHashMap<>();
            for (String symbol : ingredientsSection.getKeys(false)) {
                if (symbol.length() != 1) {
                    throw new IllegalArgumentException("recipe ingredient symbol '" + symbol
                            + "' must be exactly one character");
                }
                String token = ingredientsSection.getString(symbol);
                try {
                    ingredients.put(symbol.charAt(0), RecipeIngredient.parse(token));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("recipe ingredient '" + symbol + "': " + ex.getMessage());
                }
            }
            // strict-orientation: true で登録した向き以外 (左右反転配置) を拒否。
            // デフォルト false = バニラ同様に反転配置でもクラフト可。
            // (旧キー mirror は逆意味の旧仕様。互換のため mirror:false を strict 扱いにはしない)
            RecipeSpec shapedSpec = RecipeSpec.shaped(method, shape, ingredients, amount)
                    .withRegister(register)
                    .withStrictOrientation(recipe.getBoolean("strict-orientation", false));
            return withReversibleParsed(shapedSpec, recipe, id, log);
        }

        List<String> tokens = recipe.getStringList("ingredients");
        List<RecipeIngredient> ingredients = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            try {
                ingredients.add(RecipeIngredient.parse(token));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("shapeless recipe ingredient: " + ex.getMessage());
            }
        }
        RecipeSpec shapelessSpec = RecipeSpec.shapeless(method, ingredients, amount).withRegister(register);
        return withReversibleParsed(shapelessSpec, recipe, id, log);
    }

    /**
     * {@code reversible:} を読み、圧縮レシピに逆レシピ(結果→素材)自動生成フラグを付与する。
     * true を要求したのに前提(workbench/inventory かつ素材が全て同一アイテム)を満たさず
     * {@link RecipeSpec} 側で黙って false に落ちた場合は、ここで気づいて警告する (fail-soft:
     * 当該レシピ自体はそのまま登録される。逆レシピが付かないだけ)。
     */
    private static RecipeSpec withReversibleParsed(RecipeSpec spec, ConfigurationSection recipe,
                                                    String id, Logger log) {
        boolean requested = recipe.getBoolean("reversible", false);
        if (!requested) {
            return spec;
        }
        RecipeSpec withReversible = spec.withReversible(true);
        if (!withReversible.reversible()) {
            log.warning("[" + PATH + "] item '" + id + "' recipe reversible ignored: requires "
                    + "workbench/inventory method with all ingredients identical (no list: ingredients)");
        }
        return withReversible;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /**
     * combine/netherite の {@code source-item}/{@code addition-item} はカタログIDを直接書く仕様だが、
     * エディタや手書きで {@code custom:<id>} 形式が混在しがち (既存データにも存在) なため、
     * {@code custom:} プレフィックスを剥がして正規化する。剥がさないと
     * {@code ItemCatalogConfig.template()} の完全一致ルックアップに失敗し鍛冶/金床レシピが無言で不成立になる。
     */
    private static String stripCustomPrefix(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.regionMatches(true, 0, "custom:", 0, "custom:".length())) {
            String stripped = trimmed.substring("custom:".length()).trim();
            return stripped.isEmpty() ? null : stripped;
        }
        return trimmed;
    }

    /**
     * Air check via enum identity. {@code Material.isAir()} delegates to the block registry, which
     * is unavailable off a running server (and in unit tests), so compare constants directly.
     */
    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    /** Parse outcome: the immutable template map and how many entries were skipped. */
    record ParseResult(Map<String, ItemTemplate> templates, int skipped) {
    }
}
