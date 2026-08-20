package com.trinityforge.mobs;

import com.trinityforge.stats.CrossPluginItemResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/**
 * Dungeon gate key matching/counting/consumption (2026-07-27 カスタムアイテム鍵対応), shared by
 * {@link DungeonGateService}. {@code keyItem} は TF カタログID / ArsPaper ID / バニラ {@link Material}
 * 名のいずれか。判定の一致条件は2段階:
 * <ol>
 *   <li>スタックに TF/ArsPaper のカスタムID PDC が焼かれている場合、そのIDと {@code keyItem} の
 *       完全一致(大文字小文字を区別)のみを鍵として扱う。</li>
 *   <li>PDCが無い(＝バニラ品)場合に限り、{@code keyItem} を {@link Material#matchMaterial(String)}
 *       で解いてスタックの {@link ItemStack#getType()} と比較する。</li>
 * </ol>
 * この2段階により、鍵が {@code AMETHYST_SHARD} のようなバニラMaterialのとき、同じMaterialを持つ
 * カスタム品(PDC付き)が誤って鍵として消費されることはない。逆にカタログ/ArsPaper IDの鍵に
 * バニラ品が誤って一致することもない({@code Material.matchMaterial(catalogId)} は通常null)。
 *
 * <p>走査対象は {@link Inventory#getStorageContents()}({@code getContents()} ではない)。
 * {@link org.bukkit.inventory.PlayerInventory} の {@code getContents()} は防具4枠とオフハンドを
 * 含む41枠を返すのに対し、置き換え前の {@code inventory.contains(Material, int)} /
 * {@code inventory.removeItem(ItemStack)} は CraftBukkit 側で {@code getStorageContents()} だけを
 * 見ていた。{@code getContents()} にすると「かぶっているヘルメット」や「オフハンドの所持品」が
 * 鍵として黙って消費されるようになり、置き換え前と挙動が変わってしまう。
 * 保管枠のスロット番号は {@code setItem(slot, …)} のスロット番号と 0〜35 で一致するため、
 * 走査で得た添字をそのまま書き戻しに使える。
 */
public final class GateKeyMatcher {

    private final CrossPluginItemResolver itemResolver;

    public GateKeyMatcher(CrossPluginItemResolver itemResolver) {
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
    }

    /** True when {@code stack} counts as (at least) one instance of the {@code keyId} gate key. */
    public boolean matches(ItemStack stack, String keyId) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0
                || keyId == null || keyId.isBlank()) {
            return false;
        }
        Optional<String> stampedId = CrossPluginItemResolver.idOf(stack);
        if (stampedId.isPresent()) {
            return stampedId.get().equals(keyId);
        }
        Material material = Material.matchMaterial(keyId);
        return material != null && stack.getType() == material;
    }

    /** Total count of {@code keyId} across {@code inventory}'s contents. */
    public int count(Inventory inventory, String keyId) {
        Objects.requireNonNull(inventory, "inventory");
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (matches(stack, keyId)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Consumes up to {@code amount} matching stacks from {@code inventory}, in slot order. The caller
     * must have already verified {@code count(inventory, keyId) >= amount} (this is always paired with
     * the two-phase evaluate-then-consume flow in {@link DungeonGateService}) — this method does not
     * re-check and simply removes as many matching items as it finds, up to {@code amount}.
     */
    public void consume(Inventory inventory, String keyId, int amount) {
        Objects.requireNonNull(inventory, "inventory");
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!matches(stack, keyId)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            int newAmount = stack.getAmount() - take;
            if (newAmount <= 0) {
                inventory.setItem(slot, null);
            } else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(newAmount);
                inventory.setItem(slot, reduced);
            }
            remaining -= take;
        }
    }

    /**
     * Best-effort display name for {@code keyId}, used in the denial message shown to players.
     * Resolves via {@link CrossPluginItemResolver#create(String)} (catalog -> ArsPaper -> vanilla
     * Material, same precedence as everywhere else) and reads the built stack's meta display name when
     * present; falls back to the Material's enum name, then to the raw id when nothing resolves.
     */
    public String displayName(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return "";
        }
        Optional<ItemStack> built = itemResolver.create(keyId);
        if (built.isPresent()) {
            ItemStack stack = built.get();
            if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
                return PlainTextComponentSerializer.plainText().serialize(stack.getItemMeta().displayName());
            }
            return stack.getType().name();
        }
        return keyId;
    }

    /** Whether {@code keyId} resolves to anything (catalog / ArsPaper / vanilla Material). */
    public boolean resolves(String keyId) {
        return itemResolver.exists(keyId);
    }
}
