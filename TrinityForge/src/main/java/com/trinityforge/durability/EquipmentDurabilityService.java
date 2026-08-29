package com.trinityforge.durability;

import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * TF独自の装備耐久ペナルティの適用側(被弾時の上乗せ / 死亡時ペナルティ)。
 *
 * <p>{@code HumanEntity#damageItemStack} を使わないのは意図的 — MockBukkit が未実装で、呼ぶと
 * テストが<strong>失敗ではなく SKIPPED</strong> になり(既知の罠)、耐久が減ることを誰も検証できなく
 * なるため。{@code ChainBreakSupport#damageHeldTool} と同じく {@link Damageable} メタを直接操作する。
 *
 * <p>設定と背景は {@link DurabilityPenaltySettings} と {@code combat/damage.yml durability} を参照。
 */
public final class EquipmentDurabilityService {

    /** 防具4部位(被弾ペナルティの既定対象)。 */
    private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /** リロードで差し替わるので設定は毎回引き直す({@code CombatDamageConfig::durabilityPenalty})。 */
    private final Supplier<DurabilityPenaltySettings> settings;
    private final Predicate<Player> inDungeon;
    private final DoubleSupplier random;

    public EquipmentDurabilityService(Supplier<DurabilityPenaltySettings> settings, Predicate<Player> inDungeon) {
        this(settings, inDungeon, () -> ThreadLocalRandom.current().nextDouble());
    }

    EquipmentDurabilityService(Supplier<DurabilityPenaltySettings> settings, Predicate<Player> inDungeon,
                               DoubleSupplier random) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.inDungeon = Objects.requireNonNull(inDungeon, "inDungeon");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * 被弾1回分の上乗せを適用する(バニラの防具耐久消費は消さずに追加で減らす)。
     *
     * @return 実際に耐久が減った装備の数
     */
    public int applyOnHit(Player player) {
        DurabilityPenaltySettings current = settingsFor(player);
        if (current == null || !current.onHitEnabled()) {
            return 0;
        }
        Set<EquipmentSlot> slots = EnumSet.copyOf(ARMOR_SLOTS);
        if (current.onHitIncludeOffhand()) {
            slots.add(EquipmentSlot.OFF_HAND);
        }
        return damage(player, slots, current.onHitPercentOfMax(), current.onHitMinDamage(), current);
    }

    /**
     * 死亡(EliteMobsダンジョンのダウンを含む)1回分のペナルティを適用する。
     *
     * @return 実際に耐久が減った装備の数
     */
    public int applyOnDeath(Player player) {
        DurabilityPenaltySettings current = settingsFor(player);
        if (current == null || !current.onDeathEnabled()) {
            return 0;
        }
        Set<EquipmentSlot> slots = EnumSet.copyOf(ARMOR_SLOTS);
        if (current.onDeathIncludeHands()) {
            slots.add(EquipmentSlot.HAND);
            slots.add(EquipmentSlot.OFF_HAND);
        }
        return damage(player, slots, current.onDeathPercentOfMax(), current.onDeathMinDamage(), current);
    }

    /**
     * 適用対象なら設定を、対象外なら {@code null} を返す。クリエイティブ/スペクテイターは常に対象外、
     * {@code dungeon-only} が有効ならダンジョンワールドの外も対象外。
     */
    private DurabilityPenaltySettings settingsFor(Player player) {
        if (player == null) {
            return null;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return null;
        }
        DurabilityPenaltySettings current = settings.get();
        if (current == null) {
            return null;
        }
        if (current.dungeonOnly() && !inDungeon.test(player)) {
            return null;
        }
        return current;
    }

    private int damage(Player player, Set<EquipmentSlot> slots, double percentOfMax, int minDamage,
                       DurabilityPenaltySettings current) {
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            return 0;
        }
        int damaged = 0;
        for (EquipmentSlot slot : slots) {
            if (damageSlot(player, inventory, slot, percentOfMax, minDamage, current)) {
                damaged++;
            }
        }
        return damaged;
    }

    private boolean damageSlot(Player player, PlayerInventory inventory, EquipmentSlot slot,
                               double percentOfMax, int minDamage, DurabilityPenaltySettings current) {
        ItemStack item = itemAt(inventory, slot);
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) {
            return false;
        }
        int maxDurability = damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : item.getType().getMaxDurability();
        int amount = DurabilityPenalty.amountFor(maxDurability, percentOfMax, minDamage);
        if (current.respectUnbreaking()) {
            amount = DurabilityPenalty.afterUnbreaking(
                    amount, item.getEnchantmentLevel(Enchantment.UNBREAKING), random.getAsDouble());
        }
        if (amount <= 0) {
            return false;
        }
        DurabilityPenalty.Result result = DurabilityPenalty.apply(
                damageable.getDamage(), amount, maxDurability, current.preventBreak());
        if (result.broken()) {
            setItemAt(inventory, slot, null);
            ItemBreakSignal.fire(player, item);
            return true;
        }
        if (result.damage() == damageable.getDamage()) {
            return false; // prevent-break で残耐久1に張り付いている
        }
        damageable.setDamage(result.damage());
        item.setItemMeta(meta);
        // CraftBukkit の getter はミラーを返すので setItemMeta だけで書き戻るが、明示的に置き直して
        // 「ミラーではなくコピーを返す実装(テストダブル等)」でも確実に反映されるようにしておく。
        setItemAt(inventory, slot, item);
        return true;
    }

    /**
     * スロットの中身。{@code PlayerInventory#getItem(EquipmentSlot)} を使わず個別 getter を経由するのは、
     * MockBukkit のスロット統合APIの実装差でテストが黙って通り抜けるのを避けるため。
     */
    private static ItemStack itemAt(PlayerInventory inventory, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
            case HAND -> inventory.getItemInMainHand();
            case OFF_HAND -> inventory.getItemInOffHand();
            default -> null;
        };
    }

    private static void setItemAt(PlayerInventory inventory, EquipmentSlot slot, ItemStack item) {
        switch (slot) {
            case HEAD -> inventory.setHelmet(item);
            case CHEST -> inventory.setChestplate(item);
            case LEGS -> inventory.setLeggings(item);
            case FEET -> inventory.setBoots(item);
            case HAND -> inventory.setItemInMainHand(item);
            case OFF_HAND -> inventory.setItemInOffHand(item);
            default -> {
                // BODY/SADDLE など人間が持たないスロットは対象外。
            }
        }
    }
}
