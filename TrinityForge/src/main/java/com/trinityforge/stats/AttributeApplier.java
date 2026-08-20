package com.trinityforge.stats;

import com.google.common.collect.Multimap;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.AttributeProjection.AttributeModifierSpec;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Bukkit adapter that writes {@link AttributeModifierSpec}s onto an {@link ItemMeta} as vanilla
 * {@code AttributeModifier}s (COMBAT_SYSTEM_SPEC section 5 mapping). This is the only Bukkit-bound
 * step of the Attribute projection; the arithmetic lives in the pure {@link AttributeProjection}.
 *
 * <p>Attribute keys from config are resolved against {@code Registry.ATTRIBUTE} (1.21 registry keys
 * such as {@code attack_damage}), and the operation is mapped 1:1 by name onto
 * {@link AttributeModifier.Operation}. Each modifier's key is derived from the source stat
 * ({@code statmod.<statKey>}), giving it a stable identity independent of list order or vanilla
 * attribute key churn. {@link #apply} first clears <em>all</em> existing attribute modifiers
 * (including ValhallaMMO craft modifiers and previously restored vanilla defaults), so a re-apply
 * replaces rather than stacks and Valhalla's craft-time vanilla-stat injection cannot double-count
 * with TF's restored material defaults.
 *
 * <p>The slot scope defaults to a per-material inference ({@link EquipmentSlotResolver}): a
 * weapon/tool resolves to {@code MAINHAND} and an armor piece resolves to its matching armor slot,
 * so an armor stat cannot be double-counted by holding a second copy in hand while another is worn.
 * A material this plugin cannot categorize (blocks, food, materials with no clear equip slot, ...)
 * falls onto {@link EquipmentSlotResolver.Category#ANY}, which this class in turn maps onto
 * {@code MAINHAND}/{@code HAND} (per {@code offhand-stats-apply}) rather than the real Bukkit
 * {@code EquipmentSlotGroup.ANY} constant (2026-08-13, レーンC): {@code EquipmentSlotGroup} has no
 * "every slot except offhand" constant, and these materials are equippable only in a hand slot to
 * begin with, so this is how {@code offhand-stats-apply=false} reaches them. The constructor also
 * accepts an explicit {@link EquipmentSlotGroup} override that forces every item processed by that
 * instance onto one fixed slot scope regardless of material, for callers that want to bypass
 * inference entirely.
 *
 * <p>Since 1.20.5, an item's implicit material-default attribute modifiers (e.g. a diamond sword's
 * vanilla +7 attack damage) stop being applied automatically as soon as ANY explicit modifier is
 * written onto its {@code ItemMeta} — the explicit {@code attribute_modifiers} component replaces
 * rather than extends the material default. {@link #apply} re-adds the material's own default
 * modifiers for the resolved slot ({@link Material#getDefaultAttributeModifiers(EquipmentSlot)}) so
 * equipping an addon item never silently loses its vanilla weapon/armor baseline.
 *
 * <p><b>防具値(ARMOR)は常に空</b>: 2026-08-15 に {@code armor-defense-rate}(バニラ防具値の点数)を
 * 廃止し防御率({@code defense-rate})へ一本化したので、TFスタンプ品では {@link Attribute#ARMOR} へ
 * TFが値を書かないだけでなく<b>材質既定も復元しない</b>({@link #ALWAYS_SUPPRESSED_MATERIAL_DEFAULTS})。
 * 復元すると材質既定がバニラ防具ミラー経由で TF の防御率と二重に軽減するため。HUDの防具バーは常に空になる。
 *
 * <p><b>二重計上防止（置換ステ）</b>: {@code armor-strength} は
 * 「その防具のバニラ靭性そのもの」を著者指定する置換ステなので、TFがこの属性に
 * modifier を付けたときは材質既定の再付与を抑制する。一方 {@code max-health}/{@code move-speed}/
 * {@code attack-reach}/{@code knockback-resistance} はプレイヤー基礎値や材質既定の<em>上への加算</em>
 * なので、TF modifier を付けても材質既定は復元したまま重ねる（0指定は no-op で基礎のみ）。
 *
 * <p><b>attack-speed / attack-speed-bonus はこの層を完全にバイパスする(2026-07-25)</b>:
 * {@code Attribute.ATTACK_SPEED} は {@link #ITEM_LEVEL_EXCLUDED_ATTRIBUTES} により
 * <b>常に無条件で除外</b>される — TFはこの属性へ item-level の modifier を一切書かず、材質既定の復元も
 * 一切行わない（{@code attack-speed} の specも {@code attack-speed-bonus} の spec も、resolve される
 * 属性が同じ {@code ATTACK_SPEED} である以上、両方まとめて除外される）。理由: 両ステはプレイヤー単位で
 * {@code PerkAttributeApplier}(+{@code PlayerStatAggregator}) 側が一括計算・一元適用する設計に変わった
 * (メインハンド専用の絶対値と、全装備/パーク/base-stats横断の割合ボーナスをプレイヤー1人につき最大2個の
 * modifierへ集約する)。item-level で並行して modifier を書くと二重計上になるため、このクラスは
 * ATTACK_SPEED について「常に何もしない」のが正しい実装。他のARMOR/ARMOR_TOUGHNESS(防具値の置換)や
 * max-health/move-speed等(加算)の既存挙動は一切変更していない。
 */
public final class AttributeApplier {

    private static final String KEY_PREFIX = "statmod.";

    /**
     * TF値が「アイテムのその属性の全文」になる置換ステ（材質既定と二重計上しない）。
     * 加算ステ（max_health / attack_speed 等）はここに含めない。
     */
    private static final Set<Attribute> REPLACE_MATERIAL_DEFAULTS = Set.of(
            Attribute.ARMOR_TOUGHNESS);

    /**
     * 材質既定を<b>常に</b>復元しない属性(2026-08-15)。{@code armor-defense-rate}(防具値)を廃止して
     * 防御率へ一本化したので、TFスタンプ品の {@link Attribute#ARMOR} は「TFが値を書かない」だけでなく
     * 「材質既定も戻さない」= 実効 0 でなければならない。戻すと革7/ネザライト11といった材質既定が
     * 復活し、{@code SymmetricCombatService} のバニラ防具ミラー経由で TF の防御率と二重に軽減する。
     * その結果 HUD の防具バーは TFスタンプ装備では常に空になる(ユーザー確定の挙動)。
     */
    private static final Set<Attribute> ALWAYS_SUPPRESSED_MATERIAL_DEFAULTS = Set.of(Attribute.ARMOR);

    /**
     * item-level では一切扱わない属性(2026-07-25)。{@code attack-speed}(絶対値・メインハンド専用)と
     * {@code attack-speed-bonus}(割合・全ソース対象)はどちらもプレイヤー単位で {@code PerkAttributeApplier}
     * が集約・適用するため、このクラスは ATTACK_SPEED に対して spec の適用も材質既定の復元も常に行わない
     * (無条件除外。スロットや tfManagedAttributes の状態に関わらず常に skip)。
     */
    private static final Set<Attribute> ITEM_LEVEL_EXCLUDED_ATTRIBUTES = Set.of(Attribute.ATTACK_SPEED);

    private final Plugin plugin;
    private final EquipmentSlotGroup slotOverride;

    public AttributeApplier(Plugin plugin) {
        this(plugin, null);
    }

    /**
     * @param slotOverride when non-null, every item this instance processes is scoped to this fixed
     *                     slot group instead of the per-material inference.
     */
    public AttributeApplier(Plugin plugin, EquipmentSlotGroup slotOverride) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.slotOverride = slotOverride;
    }

    /**
     * Replaces this plugin's attribute modifiers on {@code meta} with the ones described by
     * {@code specs}, scoped to the slot inferred from {@code material} (or the constructor's
     * override). Specs whose attribute key cannot be resolved are logged and skipped rather than
     * aborting the whole item. Returns the number of modifiers actually applied (the restored
     * vanilla defaults are not counted; they are not this plugin's own stats).
     */
    public int apply(ItemMeta meta, Material material, List<AttributeModifierSpec> specs) {
        return apply(meta, material, specs, false);
    }

    /**
     * @param offhandApplies whether a weapon/tool's modifiers should be active from either hand;
     *                       ignored for armor and uncategorized materials
     */
    public int apply(ItemMeta meta, Material material, List<AttributeModifierSpec> specs,
                     boolean offhandApplies) {
        return apply(meta, material, specs, offhandApplies, false);
    }

    /**
     * @param forceMainhand true when config classifies a custom-model item as a weapon even though its
     *                      base Material (for example BOOK) is not intrinsically equippable
     */
    public int apply(ItemMeta meta, Material material, List<AttributeModifierSpec> specs,
                     boolean offhandApplies, boolean forceMainhand) {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(specs, "specs");
        Logger log = plugin.getLogger();
        // Clear Valhalla/foreign/prior TF modifiers so restoreVanillaDefaults + TF specs are the sole
        // sources (tool crafts otherwise keep Valhalla's vanilla-stat injection alongside TF's).
        clearAllAttributeModifiers(meta);

        EquipmentSlotGroup slotGroup = resolveSlotGroup(material, offhandApplies, forceMainhand);

        // AttributeModifier UUID identity is derived from the NamespacedKey we provide.
        // Using only statKey causes different equipped items to share the same UUID and therefore
        // overwrite each other instead of stacking. rollSeed is stable per TF-stamped item.
        //
        // CMB-19: an item with NO rollSeed used to always get the same ".nosd" suffix regardless of
        // slot, so two rollSeed-less pieces of gear (e.g. two armor pieces) granting the same stat
        // key collided on an identical modifier key -> Bukkit/vanilla treats same-key modifiers as
        // one instance, so only one piece's contribution actually applied. Mixing in the resolved
        // slot group's key (EquipmentSlotGroup#toString() just returns its stable "mainhand"/"head"/
        // "any" name; verified via javap) keeps modifier keys unique per worn slot, which is enough
        // because only one item can occupy a given non-HAND slot at a time.
        // 2026-08-13バグ修正: レーンCが ANY 分類を offhand-stats-apply=true のとき HAND へ落とした結果、
        // rollSeedを持たない MAINHAND 分類アイテムと ANY 分類アイテムが同じ ".nosd.hand" サフィックスを
        // 名乗るようになり、バニラが同一 modifier キーを1件として扱うため片方の寄与が無言で消えていた
        // (変更前は ".nosd.hand" と ".nosd.any" で別キーだったので両方効いていた)。EquipmentSlotGroup
        // だけでなく EquipmentSlotResolver.Category も混ぜて一意性を回復する。
        // 注意: このサフィックスは流通済みアイテムの modifier キーに現れる。キー体系が変わると
        // 再スタンプされるまで旧キーの modifier が残る — これは今日の ANY→HAND 変更で既に発生している
        // 事象であり、この修正で新たな害が増えるわけではない(むしろ衝突を解消する側)。
        String rollSeedSuffix = rollSeedSuffix(ItemData.of(meta).rollSeed(), slotGroup,
                EquipmentSlotResolver.resolve(material));

        int applied = 0;
        // TFがこのapply呼び出しで実際にmodifierを付けたAttribute。restoreVanillaDefaultsでの二重計上防止に使う。
        Set<Attribute> tfManagedAttributes = new HashSet<>();
        for (AttributeModifierSpec spec : specs) {
            Attribute attribute = resolve(spec.attributeKey());
            if (attribute == null) {
                log.warning("[attribute-map] unknown attribute '" + spec.attributeKey() + "'; skipped");
                continue;
            }
            if (ITEM_LEVEL_EXCLUDED_ATTRIBUTES.contains(attribute)) {
                // attack-speed / attack-speed-bonus はプレイヤー単位(PerkAttributeApplier)で一元適用する
                // ため、item-levelでは常にno-op(クラスjavadoc参照)。警告ではなく想定内の挙動。
                continue;
            }
            double amount = spec.amount();
            NamespacedKey modifierKey = modifierKey(spec.statKey(), rollSeedSuffix);
            try {
                AttributeModifier.Operation operation =
                        AttributeModifier.Operation.valueOf(spec.operation().name());
                meta.addAttributeModifier(attribute,
                        new AttributeModifier(
                                // per-item identity so multiple equipped pieces stack
                                modifierKey(spec.statKey(), rollSeedSuffix),
                                amount, operation, slotGroup));
                applied++;
                tfManagedAttributes.add(attribute);
            } catch (IllegalArgumentException rejected) {
                // Skip this one stat (operation with no Bukkit equivalent, or a duplicate key from two
                // stat keys sanitizing to the same value) rather than abort and leave the item stripped.
                log.warning("[attribute-map] could not apply modifier '" + modifierKey + "' for stat '"
                        + spec.statKey() + "' (" + rejected.getMessage()
                        + "); possible stat-key collision; skipped");
            }
        }

        restoreVanillaDefaults(meta, material, slotGroup, tfManagedAttributes);

        // バニラの属性表示(緑の「+X 攻撃力 / メインハンド使用時」ブロック)を完全非表示にする(ユーザー確定:
        // 完全非表示)。属性モディファイア自体は残るので実効値は不変(攻撃力/攻撃速度/防御は機能する)。TFの
        // ステはlore(LoreComposer)側で表示するため、二重表示のかさばりだけが消える。復元したバニラ既定値も
        // TFモディファイアもまとめて隠れる。
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        return applied;
    }

    /**
     * CMB-19: pure/Bukkit-light suffix computation, extracted so it is directly unit-testable without
     * a full {@code apply()} round-trip through {@code ItemMeta#getAttributeModifiers()} (which
     * {@code MockBukkit} does not implement — see {@code AttributeApplierMissingDefaultsTest}'s
     * javadoc for the same constraint on {@link #missingDefaults}). A rollSeed-bearing item keeps its
     * existing per-item hex suffix; a rollSeed-less item's suffix now also folds in the resolved slot
     * group's own stable key so two such items in different slots never collide (see the {@link #apply}
     * call site's CMB-19 comment for the full rationale).
     */
    static String rollSeedSuffix(java.util.Optional<Long> rollSeed, EquipmentSlotGroup slotGroup,
                                 EquipmentSlotResolver.Category category) {
        return rollSeed
                .map(seed -> "." + Long.toHexString(seed))
                .orElseGet(() -> ".nosd." + slotGroup + "." + category.name().toLowerCase(java.util.Locale.ROOT));
    }

    private EquipmentSlotGroup resolveSlotGroup(Material material, boolean offhandApplies,
                                                boolean forceMainhand) {
        if (slotOverride != null) {
            return slotOverride;
        }
        return slotGroupFor(material, offhandApplies, forceMainhand);
    }

    static EquipmentSlotGroup slotGroupFor(Material material, boolean offhandApplies) {
        return slotGroupFor(material, offhandApplies, false);
    }

    static EquipmentSlotGroup slotGroupFor(Material material, boolean offhandApplies,
                                           boolean forceMainhand) {
        Objects.requireNonNull(material, "material");
        if (forceMainhand) {
            return offhandApplies ? EquipmentSlotGroup.HAND : EquipmentSlotGroup.MAINHAND;
        }
        return switch (EquipmentSlotResolver.resolve(material)) {
            case MAINHAND -> offhandApplies ? EquipmentSlotGroup.HAND : EquipmentSlotGroup.MAINHAND;
            case HEAD -> EquipmentSlotGroup.HEAD;
            case CHEST -> EquipmentSlotGroup.CHEST;
            case LEGS -> EquipmentSlotGroup.LEGS;
            case FEET -> EquipmentSlotGroup.FEET;
            // レーンC(2026-08-13): EquipmentSlotResolverが頭装備(カボチャ/スカル類)をHEADへ分類した後に
            // ANYへ落ちる素材は、実際に装備できるスロットとしては手にしか入らない(SHIELD/FISHING_ROD/
            // SHEARS/FLINT_AND_STEEL/BOOK/食料/ブロック等)。「オフハンド以外の全スロット」を表す
            // EquipmentSlotGroupが存在しないため、offhand-stats-applyの門をここへ効かせるにはMAINHAND/HANDへ
            // 落とすしかない。失われるのはオフハンド適用だけ＝offhand-stats-apply=falseの仕様どおり。
            // BODY/SADDLEは非プレイヤー用スロットなので考慮不要。
            case ANY -> offhandApplies ? EquipmentSlotGroup.HAND : EquipmentSlotGroup.MAINHAND;
        };
    }

    /**
     * Re-adds {@code material}'s own default attribute modifiers for the resolved slot, skipping any
     * that are already explicitly present (so a re-apply on an already-restored item never doubles
     * them up; {@link #clearAllAttributeModifiers} strips everything first, so a re-apply rebuilds
     * defaults from scratch) AND skipping any attribute TF
     * itself supplies a modifier for this apply ({@code tfManagedAttributes}; see the class javadoc's
     * 二重計上防止 note).
     */
    private void restoreVanillaDefaults(ItemMeta meta, Material material, EquipmentSlotGroup slotGroup,
                                        Set<Attribute> tfManagedAttributes) {
        EquipmentSlot example = slotGroup.getExample();
        if (example == null) {
            return;
        }
        Multimap<Attribute, AttributeModifier> defaults = material.getDefaultAttributeModifiers(example);
        if (defaults.isEmpty()) {
            return;
        }
        for (Map.Entry<Attribute, AttributeModifier> entry :
                missingDefaults(defaults, meta.getAttributeModifiers(), tfManagedAttributes)) {
            meta.addAttributeModifier(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Pure filter behind {@link #restoreVanillaDefaults}: which of {@code defaults} still need to be
     * added. {@link #ALWAYS_SUPPRESSED_MATERIAL_DEFAULTS}(ARMOR)は TF が何を書いたかに関わらず常に
     * 復元しない(防具バーを空にする)。{@link #REPLACE_MATERIAL_DEFAULTS} は TF がこの apply で
     * 実際に書いた場合だけ材質既定を抑制する。加算ステは TF modifier と材質既定を両立させる。
     * {@link #ITEM_LEVEL_EXCLUDED_ATTRIBUTES}(ATTACK_SPEED)は無条件で常に除外する — TFが item-level で
     * その属性を管理しているかに関わらず、材質既定の復元自体を一切行わない(プレイヤー単位で一元管理する
     * ため; クラスjavadoc参照)。Also skips modifiers already present under the same key (idempotent
     * re-apply).
     */
    static List<Map.Entry<Attribute, AttributeModifier>> missingDefaults(
            Multimap<Attribute, AttributeModifier> defaults,
            Multimap<Attribute, AttributeModifier> existing,
            Set<Attribute> tfManagedAttributes) {
        List<Map.Entry<Attribute, AttributeModifier>> missing = new ArrayList<>();
        for (Map.Entry<Attribute, AttributeModifier> entry : defaults.entries()) {
            Attribute attribute = entry.getKey();
            if (ITEM_LEVEL_EXCLUDED_ATTRIBUTES.contains(attribute)) {
                continue;
            }
            if (ALWAYS_SUPPRESSED_MATERIAL_DEFAULTS.contains(attribute)) {
                // 防具値(ARMOR)は TF の防御率へ一本化したので材質既定も戻さない = 防具バーは常に空。
                continue;
            }
            if (REPLACE_MATERIAL_DEFAULTS.contains(attribute)
                    && tfManagedAttributes != null
                    && tfManagedAttributes.contains(attribute)) {
                continue;
            }
            if (existing != null && hasModifier(existing, attribute, entry.getValue().getKey())) {
                continue;
            }
            missing.add(entry);
        }
        return missing;
    }

    private static boolean hasModifier(Multimap<Attribute, AttributeModifier> existing,
                                       Attribute attribute, NamespacedKey key) {
        for (AttributeModifier modifier : existing.get(attribute)) {
            if (modifier.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes every attribute modifier on {@code meta} (TF {@code statmod.*}, Valhalla craft
     * injections, and previously restored vanilla defaults) so {@link #apply} starts from a clean
     * slate before writing TF specs and re-adding material defaults.
     */
    private static void clearAllAttributeModifiers(ItemMeta meta) {
        var existing = meta.getAttributeModifiers();
        if (existing == null || existing.isEmpty()) {
            return;
        }
        for (Attribute attribute : Set.copyOf(existing.keySet())) {
            meta.removeAttributeModifier(attribute);
        }
    }

    private NamespacedKey modifierKey(String statKey, String rollSeedSuffix) {
        String sanitized = statKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return new NamespacedKey(plugin, KEY_PREFIX + sanitized + rollSeedSuffix);
    }

    private Attribute resolve(String attributeKey) {
        NamespacedKey key = attributeKey.indexOf(':') >= 0
                ? NamespacedKey.fromString(attributeKey)
                : NamespacedKey.minecraft(attributeKey);
        if (key == null) {
            return null;
        }
        return Registry.ATTRIBUTE.get(key);
    }
}
