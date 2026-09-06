package com.trinityforge.combat;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 装備に挿さっているスレッドの item-stats を、フォークの {@code AddonCombatStats} 書き込みを待たずに
 * 装備 PDC から直接合算する。
 *
 * <p>ArsPaper の {@code ArmorManaListener#collectThreadsInto} は実効 {@code thread-slots} が 0 だと
 * 挿さっているスレッドを無視して空の addon マップを書く。GUI には挿せるのに {@code /tf status} と
 * 戦闘合算の両方からステが消える。こちらは「実際に PDC に並んでいる id」だけを見るので、枠数の門を
 * すり抜けない。
 *
 * <p>フォークが addon へ既に書いたキーは {@link PlayerStatAggregator} 側で優先する(セット効果込み)。
 * こちらは addon に無いキー(詠唱効率などマナ系がフォーク側へ迂回している場合)の穴埋め用。
 */
public final class SocketedThreadStats {

    private static final NamespacedKey THREAD_SLOTS = new NamespacedKey("arspaper", "thread_slots");
    private static final NamespacedKey THREAD_SLOT_ROLLS = new NamespacedKey("arspaper", "thread_slot_rolls");
    private static final String THREAD_PREFIX = "thread_";

    private static final AtomicReference<Map<String, ThreadRef>> CATALOG = new AtomicReference<>();

    private SocketedThreadStats() {
    }

    record ThreadRef(Material material, int customModelData) {
    }

    /**
     * 防具4部位 + メインハンド + オフハンドに挿さっているスレッドの加算ステ。空なら空マップ。
     * ATTRIBUTE チャネルはバニラ属性へ乗らない経路なので入れない。
     */
    public static Map<String, Double> collect(Player player, ItemStatsConfig itemStats) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (player == null || itemStats == null) {
            return out;
        }
        PlayerInventory inv = player.getInventory();
        accumulate(out, inv.getHelmet(), itemStats);
        accumulate(out, inv.getChestplate(), itemStats);
        accumulate(out, inv.getLeggings(), itemStats);
        accumulate(out, inv.getBoots(), itemStats);
        accumulate(out, inv.getItemInMainHand(), itemStats);
        accumulate(out, inv.getItemInOffHand(), itemStats);
        return out;
    }

    static void accumulate(Map<String, Double> into, ItemStack host, ItemStatsConfig itemStats) {
        if (host == null || host.getType().isAir() || !host.hasItemMeta()) {
            return;
        }
        ItemMeta meta = host.getItemMeta();
        if (meta == null) {
            return;
        }
        String rawSlots = meta.getPersistentDataContainer().get(THREAD_SLOTS, PersistentDataType.STRING);
        List<String> ids = parseStringList(rawSlots);
        if (ids.isEmpty()) {
            return;
        }
        List<String> rolls = parseStringList(
                meta.getPersistentDataContainer().get(THREAD_SLOT_ROLLS, PersistentDataType.STRING));
        Map<String, ThreadRef> catalog = catalogIndex();
        for (int i = 0; i < ids.size(); i++) {
            ThreadRef ref = resolve(catalog, ids.get(i));
            if (ref == null) {
                continue;
            }
            int quality = 0;
            long seed = 0L;
            if (i < rolls.size()) {
                long[] parsed = parseRoll(rolls.get(i));
                quality = (int) parsed[0];
                seed = parsed[1];
            }
            Map<String, Double> stats = DerivedItemStats.profileStats(
                    ref.material(), ref.customModelData(), quality, seed, itemStats);
            stats.forEach((key, value) -> {
                if (value == null || value == 0.0 || !Double.isFinite(value)) {
                    return;
                }
                String canonical = StatKeys.canonical(key);
                StatVocabulary.Channel channel = StatVocabulary.channelOf(canonical);
                if (channel == StatVocabulary.Channel.NONE || channel == StatVocabulary.Channel.ATTRIBUTE) {
                    return;
                }
                into.merge(canonical, value, Double::sum);
            });
        }
    }

    static ThreadRef resolve(Map<String, ThreadRef> catalog, String rawId) {
        if (rawId == null) {
            return null;
        }
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || "empty".equals(id) || "thread_empty".equals(id)) {
            return null;
        }
        ThreadRef direct = catalog.get(id);
        if (direct != null) {
            return direct;
        }
        if (!id.startsWith(THREAD_PREFIX)) {
            return catalog.get(THREAD_PREFIX + id);
        }
        return catalog.get(id.substring(THREAD_PREFIX.length()));
    }

    /**
     * {@code ["backpack","assassin"]} または {@code ["12:3"]}。壊れた入力は空リスト。
     */
    static List<String> parseStringList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return out;
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return out;
        }
        for (String token : body.split(",")) {
            String value = token.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * {@code quality:seed}。片側だけなら quality。壊れていれば 0,0。
     */
    static long[] parseRoll(String raw) {
        if (raw == null || raw.isBlank()) {
            return new long[] {0L, 0L};
        }
        String value = raw.trim();
        int colon = value.indexOf(':');
        try {
            if (colon < 0) {
                return new long[] {Long.parseLong(value), 0L};
            }
            long quality = Long.parseLong(value.substring(0, colon).trim());
            long seed = Long.parseLong(value.substring(colon + 1).trim());
            return new long[] {quality, seed};
        } catch (NumberFormatException ignored) {
            return new long[] {0L, 0L};
        }
    }

    static Map<String, ThreadRef> catalogIndex() {
        Map<String, ThreadRef> cached = CATALOG.get();
        if (cached != null) {
            return cached;
        }
        Map<String, ThreadRef> built = loadCatalogIndex();
        CATALOG.compareAndSet(null, built);
        return CATALOG.get();
    }

    /** テストからカタログ差し替え用。本番は呼ぶな。 */
    static void replaceCatalogIndexForTest(Map<String, ThreadRef> index) {
        CATALOG.set(index);
    }

    private static Map<String, ThreadRef> loadCatalogIndex() {
        Map<String, ThreadRef> out = new LinkedHashMap<>();
        try (InputStream in = SocketedThreadStats.class.getResourceAsStream("/items/catalog.yml")) {
            if (in == null) {
                return Map.of();
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection items = yaml;
            if (yaml.isConfigurationSection("items")) {
                items = yaml.getConfigurationSection("items");
            }
            if (items == null) {
                return Map.of();
            }
            for (String id : items.getKeys(false)) {
                if (id == null || !id.startsWith(THREAD_PREFIX)) {
                    continue;
                }
                ConfigurationSection entry = items.getConfigurationSection(id);
                if (entry == null) {
                    continue;
                }
                String materialName = entry.getString("material");
                if (materialName == null || materialName.isBlank() || !entry.isInt("custom-model-data")) {
                    continue;
                }
                Material material = Material.matchMaterial(materialName.trim());
                if (material == null) {
                    continue;
                }
                ThreadRef ref = new ThreadRef(material, entry.getInt("custom-model-data"));
                String typeId = id.substring(THREAD_PREFIX.length());
                out.put(id, ref);
                out.put(typeId, ref);
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.copyOf(out);
    }
}
