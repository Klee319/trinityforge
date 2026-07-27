package com.github.klee319.dpschecker.dummy;

import com.github.klee319.dpschecker.integration.TrinityForgeBridge;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DummyEntity {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int MAX_DAMAGE_RECORDS = 10000;

    private final JavaPlugin plugin;
    private final UUID ownerUuid;
    private final UUID dummyUuid;
    private final String name;
    private final List<DamageRecord> damageRecords = new ArrayList<>();
    private final BossBar bossBar;
    private final Map<PotionEffectType, PotionEffect> virtualEffects = new ConcurrentHashMap<>();

    private final NamespacedKey dummyKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey idKey;
    private final NamespacedKey nameKey;
    private final NamespacedKey maxHpKey;
    private final NamespacedKey defenseKey;
    private final NamespacedKey toughnessKey;
    private final NamespacedKey undeadKey;

    private Zombie entity;
    private Location spawnLocation;
    private double maxHp;
    private DummyDefenseProfile defenseProfile;
    private boolean undead;
    private BukkitTask virtualEffectTask;
    private long lastRegenTick = -1L;

    public DummyEntity(JavaPlugin plugin, UUID ownerUuid, String name, Location location,
                       double maxHp, double defense, double armorToughness) {
        this.plugin = plugin;
        this.ownerUuid = ownerUuid;
        this.name = name;
        this.spawnLocation = location.clone();
        this.maxHp = maxHp;
        this.defenseProfile = DummyDefenseProfile.fromVanillaArmor(plugin, defense, armorToughness);
        this.undead = plugin.getConfig().getBoolean("dummy.default-undead", true);
        this.dummyUuid = UUID.randomUUID();

        this.dummyKey = new NamespacedKey(plugin, "dummy");
        this.ownerKey = new NamespacedKey(plugin, "owner");
        this.idKey = new NamespacedKey(plugin, "dummy_id");
        this.nameKey = new NamespacedKey(plugin, "dummy_name");
        this.maxHpKey = new NamespacedKey(plugin, "dummy_max_hp");
        this.defenseKey = new NamespacedKey(plugin, "dummy_defense");
        this.toughnessKey = new NamespacedKey(plugin, "dummy_toughness");
        this.undeadKey = new NamespacedKey(plugin, "dummy_undead");

        this.bossBar = BossBar.bossBar(
                buildDisplayName(),
                1.0f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS
        );

        spawn();
    }

    private DummyEntity(JavaPlugin plugin, UUID ownerUuid, UUID dummyUuid, String name,
                        Location spawnLocation, double maxHp, boolean undead, Zombie entity,
                        DummyDefenseProfile defenseProfile) {
        this.plugin = plugin;
        this.ownerUuid = ownerUuid;
        this.dummyUuid = dummyUuid;
        this.name = name;
        this.spawnLocation = spawnLocation.clone();
        this.maxHp = maxHp;
        this.defenseProfile = defenseProfile;
        this.undead = undead;
        this.entity = entity;

        this.dummyKey = new NamespacedKey(plugin, "dummy");
        this.ownerKey = new NamespacedKey(plugin, "owner");
        this.idKey = new NamespacedKey(plugin, "dummy_id");
        this.nameKey = new NamespacedKey(plugin, "dummy_name");
        this.maxHpKey = new NamespacedKey(plugin, "dummy_max_hp");
        this.defenseKey = new NamespacedKey(plugin, "dummy_defense");
        this.toughnessKey = new NamespacedKey(plugin, "dummy_toughness");
        this.undeadKey = new NamespacedKey(plugin, "dummy_undead");

        this.bossBar = BossBar.bossBar(
                buildDisplayName(),
                1.0f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS
        );

        applyRuntimeMetadata();
        applyAttributes();
        persistMetadata();
        updateBossBar();
    }

    public static Optional<DummyEntity> fromExisting(JavaPlugin plugin, Zombie zombie) {
        NamespacedKey dummyKey = new NamespacedKey(plugin, "dummy");
        if (!zombie.getPersistentDataContainer().has(dummyKey, PersistentDataType.BOOLEAN)) {
            return Optional.empty();
        }

        PersistentDataContainer pdc = zombie.getPersistentDataContainer();
        NamespacedKey ownerKey = new NamespacedKey(plugin, "owner");
        NamespacedKey idKey = new NamespacedKey(plugin, "dummy_id");
        NamespacedKey nameKey = new NamespacedKey(plugin, "dummy_name");
        NamespacedKey maxHpKey = new NamespacedKey(plugin, "dummy_max_hp");
        NamespacedKey defenseKey = new NamespacedKey(plugin, "dummy_defense");
        NamespacedKey toughnessKey = new NamespacedKey(plugin, "dummy_toughness");
        NamespacedKey undeadKey = new NamespacedKey(plugin, "dummy_undead");

        String ownerRaw = pdc.get(ownerKey, PersistentDataType.STRING);
        String idRaw = pdc.get(idKey, PersistentDataType.STRING);
        if (ownerRaw == null || idRaw == null) {
            return Optional.empty();
        }

        UUID ownerUuid;
        UUID dummyUuid;
        try {
            ownerUuid = UUID.fromString(ownerRaw);
            dummyUuid = UUID.fromString(idRaw);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }

        double defaultHp = plugin.getConfig().getDouble("dummy.default-hp", 100.0);
        double defaultDefense = plugin.getConfig().getDouble("dummy.default-defense", 0.0);
        double defaultToughness = plugin.getConfig().getDouble("dummy.default-armor-toughness", 0.0);
        boolean defaultUndead = plugin.getConfig().getBoolean("dummy.default-undead", true);

        String name = pdc.getOrDefault(nameKey, PersistentDataType.STRING, "カカシ");
        double maxHp = pdc.getOrDefault(maxHpKey, PersistentDataType.DOUBLE, defaultHp);
        double legacyDefense = pdc.getOrDefault(defenseKey, PersistentDataType.DOUBLE, defaultDefense);
        double legacyToughness = pdc.getOrDefault(toughnessKey, PersistentDataType.DOUBLE, defaultToughness);
        boolean undead = pdc.getOrDefault(undeadKey, PersistentDataType.BOOLEAN, defaultUndead);
        DummyDefenseProfile profile = DummyDefenseProfile.fromPdc(
                plugin, pdc, legacyDefense, legacyToughness);

        return Optional.of(new DummyEntity(
                plugin, ownerUuid, dummyUuid, name, zombie.getLocation(),
                maxHp, undead, zombie, profile));
    }

    public void spawn() {
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }

        entity = spawnLocation.getWorld().spawn(spawnLocation, Zombie.class, zombie -> {
            zombie.setAI(false);
            zombie.setSilent(true);
            zombie.setCanPickupItems(false);
            zombie.setRemoveWhenFarAway(false);
            // setRemoveWhenFarAway(false) だけでは環境によってはワールドセーブで消えうるため、明示的に
            // 永続化フラグも立てる。これで再起動/チャンクアンロードを跨いでエンティティ本体が保存される。
            zombie.setPersistent(true);
            zombie.customName(buildDisplayName());
            zombie.setCustomNameVisible(true);
            zombie.setShouldBurnInDay(false);
            zombie.setAdult();
            zombie.setBaby(false);

            EntityEquipment eq = zombie.getEquipment();
            eq.clear();
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
            eq.setItemInOffHandDropChance(0f);
        });

        applyRuntimeMetadata();
        persistMetadata();
        applyAttributes();
    }

    /**
     * Bukkit メタデータ("NPC"/"dpschecker_dummy")は再起動で揮発するため、スポーン時と
     * 既存エンティティからの復元時({@link #fromExisting})の両方で付け直す。恒久判定は PDC
     * ({@code dummy} キー)が担うが、他プラグイン連携用にランタイムメタデータも維持する。
     */
    private void applyRuntimeMetadata() {
        if (entity == null || entity.isDead()) return;
        entity.setMetadata("NPC", new FixedMetadataValue(plugin, true));
        entity.setMetadata("dpschecker_dummy", new FixedMetadataValue(plugin, true));
    }

    private void persistMetadata() {
        if (entity == null || entity.isDead()) return;

        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(dummyKey, PersistentDataType.BOOLEAN, true);
        pdc.set(ownerKey, PersistentDataType.STRING, ownerUuid.toString());
        pdc.set(idKey, PersistentDataType.STRING, dummyUuid.toString());
        pdc.set(nameKey, PersistentDataType.STRING, name);
        pdc.set(maxHpKey, PersistentDataType.DOUBLE, maxHp);
        pdc.set(undeadKey, PersistentDataType.BOOLEAN, undead);
        defenseProfile.writePdc(plugin, pdc);
    }

    private void applyAttributes() {
        if (entity == null || entity.isDead()) return;

        AttributeInstance maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(maxHp);
            entity.setHealth(Math.min(entity.getHealth(), maxHp));
        }

        syncTfDefenseProfile();
    }

    private void syncTfDefenseProfile() {
        if (entity == null || entity.isDead()) return;
        // 装備中のTF防具があればその防御を優先し、無ければ手動プロファイルへ復帰する(#6)。
        TrinityForgeBridge.syncEquipmentOrProfile(entity, defenseProfile);
    }

    /**
     * 装備変更後に呼ぶ: 実効防御(装備由来 or 手動)を TF PDC へ再 stamp する(#6)。
     * TF防具を着せた/外したタイミングで {@link com.github.klee319.dpschecker.gui.EquipmentGUI} から起動する。
     *
     * <p>防具のバニラ armor/armor_toughness 属性は {@code setHelmet} 等の直後には未同期で、
     * エンティティの次tickで反映される。防御率%はこの属性ミラーから来るため、<b>1tick後</b>に再stampして
     * スタール値の読み取りを避ける(装備item-stats由来は即時読めるが、属性ミラー分の遅延に合わせる)。
     */
    public void refreshEquipmentDefense() {
        if (entity == null || entity.isDead()) return;
        Bukkit.getScheduler().runTaskLater(plugin, this::syncTfDefenseProfile, 1L);
    }

    private Component buildDisplayName() {
        String format = plugin.getConfig().getString("dummy.name-format",
                "<gold>[DPS Dummy]</gold> <white><name></white>");
        return MM.deserialize(format, Placeholder.unparsed("name", name));
    }

    public void onDamage(DamageRecord record) {
        if (damageRecords.size() >= MAX_DAMAGE_RECORDS) {
            damageRecords.subList(0, damageRecords.size() - MAX_DAMAGE_RECORDS + 1).clear();
        }
        damageRecords.add(record);
        if (entity == null || entity.isDead()) {
            updateBossBar(0.0);
            return;
        }
        double displayHealth = Math.max(0.0, entity.getHealth() - record.finalDamage());
        updateBossBar(displayHealth);
    }

    public void updateBossBar() {
        if (entity == null || entity.isDead()) {
            updateBossBar(0.0);
            return;
        }
        updateBossBar(entity.getHealth());
    }

    private void updateBossBar(double displayHealth) {
        if (entity == null || entity.isDead()) {
            bossBar.progress(0f);
            bossBar.color(BossBar.Color.RED);
            return;
        }

        float ratio = (float) (displayHealth / maxHp);
        ratio = Math.max(0f, Math.min(1f, ratio));
        bossBar.progress(ratio);

        if (ratio > 0.5f) {
            bossBar.color(BossBar.Color.GREEN);
        } else if (ratio > 0.25f) {
            bossBar.color(BossBar.Color.YELLOW);
        } else {
            bossBar.color(BossBar.Color.RED);
        }

        Component barName = Component.text()
                .append(buildDisplayName())
                .append(Component.text(String.format(" %.1f / %.1f HP",
                        displayHealth, maxHp), NamedTextColor.WHITE))
                .build();
        bossBar.name(barName);
    }

    public void showBossBar(Player player) {
        player.showBossBar(bossBar);
    }

    public void hideBossBar(Player player) {
        player.hideBossBar(bossBar);
    }

    public void updateBossBarVisibility(int range) {
        if (entity == null || entity.isDead()) return;

        Location loc = entity.getLocation();
        long rangeSquared = (long) range * range;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(loc.getWorld())
                    && player.getLocation().distanceSquared(loc) <= rangeSquared
                    && isLookingAtDummy(player, range)) {
                showBossBar(player);
            } else {
                hideBossBar(player);
            }
        }
    }

    /** プレイヤーの視線先がこのダミー本体かどうかを判定する。 */
    private boolean isLookingAtDummy(Player player, int range) {
        Entity target = player.getTargetEntity(range);
        return target != null && target.getUniqueId().equals(entity.getUniqueId());
    }

    public void resetRecords() {
        damageRecords.clear();
    }

    public void respawn() {
        cancelVirtualEffectTask();
        virtualEffects.clear();
        if (entity != null && !entity.isDead()) {
            spawnLocation = entity.getLocation();
            entity.remove();
        }
        spawn();
        damageRecords.clear();
        updateBossBar();
    }

    public void remove() {
        cancelVirtualEffectTask();
        virtualEffects.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            hideBossBar(player);
        }
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }
    }

    public void cleanupWithoutRemovingEntity() {
        cancelVirtualEffectTask();
        virtualEffects.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            hideBossBar(player);
        }
    }

    public void setMaxHp(double maxHp) {
        this.maxHp = maxHp;
        if (entity != null && !entity.isDead()) {
            AttributeInstance maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealthAttr != null) {
                maxHealthAttr.setBaseValue(maxHp);
                entity.setHealth(Math.min(entity.getHealth(), maxHp));
            }
            persistMetadata();
            updateBossBar();
        }
    }

    public void setDefenseProfile(DummyDefenseProfile defenseProfile) {
        this.defenseProfile = defenseProfile;
        syncTfDefenseProfile();
        persistMetadata();
    }

    public void adjustTfDefenseStat(TfDefenseStat stat, double delta) {
        setDefenseProfile(defenseProfile.adjust(stat, delta));
    }

    public DummyDefenseProfile getDefenseProfile() {
        return defenseProfile;
    }

    public void healToFull() {
        if (entity != null && !entity.isDead()) {
            entity.setHealth(maxHp);
            updateBossBar();
        }
    }

    public boolean applyPotionEffect(PotionEffect effect) {
        if (entity == null || entity.isDead()) return false;

        PotionEffectType type = effect.getType();
        if (requiresVirtualEffect(type)) {
            entity.removePotionEffect(type);
            virtualEffects.put(type, effect);
            ensureVirtualEffectTask();
            return true;
        }

        virtualEffects.remove(type);
        return entity.addPotionEffect(effect, true);
    }

    public void removePotionEffect(PotionEffectType type) {
        virtualEffects.remove(type);
        if (entity != null && !entity.isDead()) {
            entity.removePotionEffect(type);
        }
        if (virtualEffects.isEmpty()) {
            cancelVirtualEffectTask();
        }
    }

    private boolean requiresVirtualEffect(PotionEffectType type) {
        return undead && type.equals(PotionEffectType.REGENERATION);
    }

    private void ensureVirtualEffectTask() {
        if (virtualEffectTask != null) return;
        virtualEffectTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickVirtualEffects, 1L, 1L);
    }

    private void cancelVirtualEffectTask() {
        if (virtualEffectTask != null) {
            virtualEffectTask.cancel();
            virtualEffectTask = null;
        }
        lastRegenTick = -1L;
    }

    private void tickVirtualEffects() {
        if (entity == null || entity.isDead() || virtualEffects.isEmpty()) {
            cancelVirtualEffectTask();
            return;
        }

        long tick = entity.getWorld().getFullTime();
        Iterator<Map.Entry<PotionEffectType, PotionEffect>> it = virtualEffects.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PotionEffectType, PotionEffect> entry = it.next();
            PotionEffect effect = entry.getValue();
            if (effect.getDuration() != PotionEffect.INFINITE_DURATION) {
                int remaining = effect.getDuration() - 1;
                if (remaining <= 0) {
                    it.remove();
                    continue;
                }
                entry.setValue(new PotionEffect(
                        effect.getType(), remaining, effect.getAmplifier(),
                        effect.isAmbient(), effect.hasParticles(), effect.hasIcon()));
            }

            if (effect.getType().equals(PotionEffectType.REGENERATION)) {
                applyVirtualRegeneration(effect, tick);
            }
        }

        if (virtualEffects.isEmpty()) {
            cancelVirtualEffectTask();
        }
    }

    private void applyVirtualRegeneration(PotionEffect effect, long tick) {
        int amplifier = effect.getAmplifier();
        int period = Math.max(1, 50 >> Math.min(amplifier, 4));
        if (tick - lastRegenTick < period) {
            return;
        }
        lastRegenTick = tick;
        double heal = Math.min(4.0, 1.0 + amplifier);
        double next = Math.min(maxHp, entity.getHealth() + heal);
        entity.setHealth(next);
        updateBossBar();
    }

    public void setUndead(boolean undead) {
        if (this.undead == undead) return;
        this.undead = undead;
        persistMetadata();

        if (!undead) {
            Map<PotionEffectType, PotionEffect> toApply = new HashMap<>(virtualEffects);
            virtualEffects.clear();
            cancelVirtualEffectTask();
            for (PotionEffect effect : toApply.values()) {
                entity.addPotionEffect(effect, true);
            }
        }
    }

    public boolean isEntity(Entity e) {
        return entity != null && entity.getUniqueId().equals(e.getUniqueId());
    }

    public Zombie getEntity() { return entity; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public UUID getDummyUuid() { return dummyUuid; }
    public String getName() { return name; }
    public double getMaxHp() { return maxHp; }
    public boolean isUndead() { return undead; }
    public Location getSpawnLocation() { return spawnLocation; }
    public BossBar getBossBar() { return bossBar; }
    public List<DamageRecord> getDamageRecords() { return Collections.unmodifiableList(damageRecords); }

    public Collection<PotionEffect> getActiveEffects() {
        if (entity == null || entity.isDead()) {
            return Collections.unmodifiableCollection(virtualEffects.values());
        }

        Map<PotionEffectType, PotionEffect> merged = new LinkedHashMap<>();
        for (PotionEffect effect : entity.getActivePotionEffects()) {
            merged.put(effect.getType(), effect);
        }
        for (Map.Entry<PotionEffectType, PotionEffect> entry : virtualEffects.entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableCollection(merged.values());
    }
}
