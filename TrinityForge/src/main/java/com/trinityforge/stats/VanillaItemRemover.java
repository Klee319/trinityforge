package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Determines whether a vanilla item should be removed from the game per {@code progression/
 * crafting-features.yml removed-vanilla-items}: 指定した Material (または {@code Material:enchant_id}
 * で絞ったエンチャント本等) の入手経路を遮断し、既存所持も掃除する。
 *
 * <p>TF カタログ由来のアイテムは誤消去防止のため常に対象外。判定は以下いずれかを満たせば TF品として
 * 除外する ({@link #isTfCatalogItem}):
 * <ol>
 *   <li>{@link ItemData#catalogId()} または {@link ItemData#hasRollSeed()} が立っている(通常の刻印済み品)</li>
 *   <li>{@code trinityforge} namespace の PDC キーを1つでも持つ(未刻印でも owner/coating/xp瓶量など
 *       TF由来の状態を持つ改変バニラ品を含む)</li>
 *   <li>material + CustomModelData がカタログテンプレートに一致する(未刻印CMDカスタム品。素の
 *       {@code catalog}(nullable、テスト都合で注入省略可)が無い場合はこの条件はスキップされる)</li>
 * </ol>
 *
 * <p>{@link #updateTargets} で {@code volatile} な対象集合を丸ごと入れ替えるため reload に対応する。
 * 判定ロジック({@link #shouldRemove})は純粋関数として単体テスト可能。
 */
public final class VanillaItemRemover {

    /**
     * A single {@code removed-vanilla-items} entry: material, optionally scoped to one enchantment.
     *
     * <p>{@code material == null} is the wildcard form ({@code ANY:<enchant>}, 2026-07-25 修繕除去範囲拡大):
     * matches the enchantment on <em>any</em> material (gear/tools, not just {@code ENCHANTED_BOOK}). A
     * wildcard matcher MUST carry an {@code enchant} — a bare "remove everything" wildcard would be
     * catastrophic, so the compact constructor rejects {@code material == null && enchant == null}.
     */
    public record ItemMatcher(Material material, Enchantment enchant) {
        public ItemMatcher {
            if (material == null && enchant == null) {
                throw new IllegalArgumentException(
                        "wildcard ItemMatcher (material=null) must specify an enchant — a bare ANY would "
                                + "remove every item in the game");
            }
        }
    }

    private volatile Set<ItemMatcher> targets = Set.of();
    // カタログCMD照合用(修正1条件3)。null許容: 未注入時はこの条件のみスキップし判定1/2は機能する
    // (テストコード互換 — 既存の no-arg コンストラクタ呼び出しを壊さないため)。
    private final ItemCatalogConfig catalog;

    public VanillaItemRemover() {
        this(null);
    }

    public VanillaItemRemover(ItemCatalogConfig catalog) {
        this.catalog = catalog;
    }

    /**
     * Parses {@code raw} ({@code removed-vanilla-items}) into matchers and replaces the active target
     * set. Unknown materials/enchants are skipped with a warning (fail-soft, matches
     * {@link com.trinityforge.config.domains.MaterialListsConfig} conventions).
     */
    public void updateTargets(List<String> raw, Logger log) {
        this.targets = Set.copyOf(parse(raw, log));
    }

    /** Pure parse of raw config strings into matchers. Unit-testable without a running server. */
    static Set<ItemMatcher> parse(List<String> raw, Logger log) {
        Set<ItemMatcher> parsed = new LinkedHashSet<>();
        if (raw == null) {
            return parsed;
        }
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            // 2026-07-25 修繕除去範囲拡大: "ANY:<enchant>" はワイルドカード(全material対象)。
            // ENCHANTED_BOOK限定だった旧設計だと、チェスト戦利品/村人取引で生成される「修繕付きの防具/
            // 道具そのもの」(material が DIAMOND_PICKAXE 等)を検出できなかった。ANY はmaterial一致判定を
            // 完全にスキップし、enchant一致(hasEnchant)のみで判定する。
            if (trimmed.regionMatches(true, 0, "ANY:", 0, 4)) {
                String enchantPart = trimmed.substring(4).trim();
                Enchantment wildcardEnchant = enchantPart.isEmpty() ? null : resolveEnchant(enchantPart);
                if (wildcardEnchant == null) {
                    log.warning("[removed-vanilla-items] unknown enchant in wildcard entry '" + value
                            + "' (skipped)");
                    continue;
                }
                parsed.add(new ItemMatcher(null, wildcardEnchant));
                continue;
            }
            // Material 部分が "minecraft:iron_pickaxe" のような namespaced key を含みうる (Material.matchMaterial
            // は namespace 付き/無し両対応)ため、最後のコロン以降を enchant 指定として先に試し、解決できなければ
            // 全体を Material として解釈し直す(曖昧性の解消: 修正4)。
            Material material = null;
            Enchantment enchant = null;
            int lastSep = trimmed.lastIndexOf(':');
            if (lastSep >= 0) {
                String materialPart = trimmed.substring(0, lastSep).trim();
                String enchantPart = trimmed.substring(lastSep + 1).trim();
                Material candidateMaterial = Material.matchMaterial(materialPart);
                Enchantment candidateEnchant = enchantPart.isEmpty() ? null : resolveEnchant(enchantPart);
                if (candidateMaterial != null && candidateEnchant != null) {
                    material = candidateMaterial;
                    enchant = candidateEnchant;
                }
            }
            if (material == null) {
                // enchant 分割が成立しなかった(あるいはコロンが無い): 全体を Material として解釈する。
                // "minecraft:iron_pickaxe" のような namespaced Material も matchMaterial がそのまま扱える。
                material = Material.matchMaterial(trimmed);
            }
            if (material == null) {
                log.warning("[removed-vanilla-items] unknown material or enchant entry '" + value
                        + "' (skipped)");
                continue;
            }
            parsed.add(new ItemMatcher(material, enchant));
        }
        return parsed;
    }

    private static Enchantment resolveEnchant(String id) {
        // Registry.ENCHANTMENT.get(NamespacedKey) is the same lookup pattern already used elsewhere
        // in this codebase (CraftingFeaturesConfig#loadOverEnchant, MiningFortuneListener) — kept
        // consistent rather than introducing RegistryAccess/RegistryKey for a single call site.
        // NamespacedKey.minecraft は不正文字(空白等)を含む id で IllegalArgumentException を投げるため、
        // fail-soft(未知として扱いスキップ)を保証するために捕捉する(修正3)。
        try {
            return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(id.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException invalidKey) {
            return null;
        }
    }

    /** Currently active matchers (insertion order preserved as configured). */
    public Set<ItemMatcher> targets() {
        return targets;
    }

    public boolean hasTargets() {
        return !targets.isEmpty();
    }

    /** {@link #sanitize} の判定結果。 */
    public enum Verdict {
        /** 対象外。そのまま。 */
        KEEP,
        /** 指定エンチャントだけ剥がした(アイテム自体は残す)。 */
        STRIPPED,
        /** アイテムごと消す。 */
        REMOVE
    }

    /**
     * {@code stack} を {@code removed-vanilla-items} に従って<b>その場で無害化</b>し、
     * 呼び出し側が取るべき処置を返す(2026-07-30 ユーザー確定仕様)。
     *
     * <ul>
     *   <li>材質だけの指定({@code DIAMOND_SWORD} 等) → {@link Verdict#REMOVE}(従来どおり)。</li>
     *   <li>エンチャント指定({@code ANY:MENDING} / {@code ENCHANTED_BOOK:MENDING}) →
     *       <b>そのエンチャントだけを剥がして {@link Verdict#STRIPPED}</b>。装備を丸ごと消すのは
     *       ルートチェストの当たり装備が無言で消滅するのと同義で、体験として悪すぎる。</li>
     *   <li>ただしエンチャント本({@code ENCHANTED_BOOK})を剥がした結果、収録エンチャントが
     *       0 になった場合は {@link Verdict#REMOVE} — バニラに存在しない「エンチャントの付いていない
     *       エンチャント本」を世界に残さないため。</li>
     * </ul>
     *
     * <p><b>TF品保護({@link #isTfCatalogItem})はアイテムごと消す判定にだけ効く。</b>
     * エンチャントを剥がすだけなら TF品でも安全に適用できる — むしろ「TFの品質PDCが先に刻まれた
     * 釣果は削除対象から外れる」という順序依存で修繕付きアイテムが素通りしていたのが実バグの原因
     * ({@code FishingQualityListener}(NORMAL) が {@code VanillaItemRemovalListener}(HIGH) より先に
     * 走るため)。剥がす側に保護を掛けないことでこの順序依存そのものが消える。
     */
    public Verdict sanitize(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || targets.isEmpty()) {
            return Verdict.KEEP;
        }
        boolean tfItem = isTfCatalogItem(stack);
        Set<Enchantment> toStrip = new LinkedHashSet<>();
        for (ItemMatcher matcher : targets) {
            if (matcher.material() != null && matcher.material() != stack.getType()) {
                continue;
            }
            if (matcher.enchant() == null) {
                // 材質そのものの禁止。TF品は誤消去防止のため従来どおり除外する。
                if (!tfItem) {
                    return Verdict.REMOVE;
                }
                continue;
            }
            if (hasEnchant(stack, matcher.enchant())) {
                toStrip.add(matcher.enchant());
            }
        }
        if (toStrip.isEmpty()) {
            return Verdict.KEEP;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Verdict.KEEP;
        }
        for (Enchantment enchant : toStrip) {
            if (meta instanceof EnchantmentStorageMeta storage) {
                storage.removeStoredEnchant(enchant);
            }
            meta.removeEnchant(enchant);
        }
        stack.setItemMeta(meta);
        boolean emptyBook = stack.getType() == Material.ENCHANTED_BOOK
                && (!(meta instanceof EnchantmentStorageMeta storage) || !storage.hasStoredEnchants());
        return emptyBook && !tfItem ? Verdict.REMOVE : Verdict.STRIPPED;
    }

    /** True when {@code stack} matches a configured removal target and is not a TF catalog item. */
    public boolean shouldRemove(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || targets.isEmpty()) {
            return false;
        }
        if (isTfCatalogItem(stack)) {
            return false;
        }
        for (ItemMatcher matcher : targets) {
            // material() == null はワイルドカード(ANY:<enchant>、修繕除去範囲拡大)— 常に一致扱いで
            // material判定を素通りし、enchant一致のみで判定する。
            if (matcher.material() != null && matcher.material() != stack.getType()) {
                continue;
            }
            if (matcher.enchant() == null || hasEnchant(stack, matcher.enchant())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTfCatalogItem(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        if (data.catalogId().isPresent() || data.hasRollSeed()) {
            return true;
        }
        // 条件2: trinityforge namespace の PDC キーを1つでも持てば TF品(未刻印でも owner/coating/
        // XP瓶量などの単独状態を持つ改変バニラ品を守る)。
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (PdcKeys.NAMESPACE.equals(key.getNamespace())) {
                return true;
            }
        }
        // 条件3: material + CustomModelData がカタログテンプレートに一致すれば TF品(未刻印CMDカスタム品)。
        if (catalog != null) {
            Integer cmd = DerivedItemStats.customModelDataOf(meta);
            if (cmd != null && CatalogIdentity.find(catalog, stack.getType(), cmd).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnchant(ItemStack stack, Enchantment enchant) {
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storageMeta && storageMeta.hasStoredEnchant(enchant)) {
            return true;
        }
        return meta.hasEnchant(enchant);
    }
}
