package com.github.klee319.dpschecker.gui;

import com.github.klee319.dpschecker.dummy.DummyEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.klee319.dpschecker.gui.MainMenuGUI.noItalic;

public class EffectsGUI implements InventoryHolder {

    private static final int MAX_LEVEL = 5;
    private static final int DEFAULT_DURATION_SECONDS = 30;
    private static final int BACK_SLOT = 49;
    private static final int INFO_SLOT = 4;

    private final Map<Integer, PotionEffectType> slotToEffect = new LinkedHashMap<>();
    private final Inventory inventory;
    private final DummyEntity dummy;

    public EffectsGUI(JavaPlugin plugin, DummyEntity dummy) {
        this.dummy = dummy;
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text("DPS Dummy - エフェクト", NamedTextColor.DARK_PURPLE));
        initializeItems();
    }

    private void initializeItems() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Component.text("")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        ItemStack info = new ItemStack(Material.WRITABLE_BOOK);
        info.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("エフェクト付与", NamedTextColor.GOLD)));
            meta.lore(List.of(
                    noItalic(Component.text("左クリック: レベル+1で適用", NamedTextColor.GREEN)),
                    noItalic(Component.text("右クリック: 効果を解除", NamedTextColor.RED)),
                    noItalic(Component.text("デフォルト持続: " + DEFAULT_DURATION_SECONDS + "秒",
                            NamedTextColor.GRAY)),
                    noItalic(Component.text("即時系(回復/ダメージ)は1回適用",
                            NamedTextColor.DARK_GRAY))
            ));
        });
        inventory.setItem(INFO_SLOT, info);

        // Buff row (slots 9-16)
        registerEffect(9, PotionEffectType.REGENERATION, "再生", Material.GHAST_TEAR);
        registerEffect(10, PotionEffectType.INSTANT_HEALTH, "即時回復", Material.GLISTERING_MELON_SLICE);
        registerEffect(11, PotionEffectType.HEALTH_BOOST, "体力増強", Material.GOLDEN_APPLE);
        registerEffect(12, PotionEffectType.ABSORPTION, "衝撃吸収", Material.ENCHANTED_GOLDEN_APPLE);
        registerEffect(13, PotionEffectType.RESISTANCE, "耐性", Material.NETHERITE_CHESTPLATE);
        registerEffect(14, PotionEffectType.FIRE_RESISTANCE, "火炎耐性", Material.MAGMA_CREAM);
        registerEffect(15, PotionEffectType.STRENGTH, "攻撃力上昇", Material.BLAZE_POWDER);
        registerEffect(16, PotionEffectType.SPEED, "移動速度上昇", Material.SUGAR);

        // Debuff row (slots 18-25)
        registerEffect(18, PotionEffectType.POISON, "毒", Material.SPIDER_EYE);
        registerEffect(19, PotionEffectType.WITHER, "衰弱", Material.WITHER_SKELETON_SKULL);
        registerEffect(20, PotionEffectType.INSTANT_DAMAGE, "即時ダメージ", Material.FERMENTED_SPIDER_EYE);
        registerEffect(21, PotionEffectType.WEAKNESS, "弱体化", Material.BONE);
        registerEffect(22, PotionEffectType.HUNGER, "空腹", Material.ROTTEN_FLESH);
        registerEffect(23, PotionEffectType.SLOWNESS, "移動速度低下", Material.SOUL_SAND);
        registerEffect(24, PotionEffectType.LEVITATION, "浮遊", Material.SHULKER_SHELL);
        registerEffect(25, PotionEffectType.MINING_FATIGUE, "採掘速度低下", Material.TUFF);

        // Active effects display
        ItemStack label = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        label.editMeta(meta -> meta.displayName(
                noItalic(Component.text("現在の効果", NamedTextColor.AQUA))));
        inventory.setItem(36, label);

        Collection<PotionEffect> active = dummy.getActiveEffects();
        if (active.isEmpty()) {
            ItemStack noEffects = new ItemStack(Material.BARRIER);
            noEffects.editMeta(meta -> meta.displayName(
                    noItalic(Component.text("付与中の効果なし", NamedTextColor.DARK_GRAY))));
            inventory.setItem(40, noEffects);
        } else {
            int slot = 37;
            for (PotionEffect effect : active) {
                if (slot > 44) break;
                ItemStack item = new ItemStack(Material.POTION);
                item.editMeta(meta -> {
                    String name = translateEffect(effect.getType());
                    int level = effect.getAmplifier() + 1;
                    meta.displayName(noItalic(Component.text(
                            name + " " + toRoman(level), NamedTextColor.LIGHT_PURPLE)));
                    List<Component> lore = new ArrayList<>();
                    int durationTicks = effect.getDuration();
                    if (durationTicks == PotionEffect.INFINITE_DURATION) {
                        lore.add(noItalic(Component.text("時間: 無限", NamedTextColor.WHITE)));
                    } else {
                        int seconds = durationTicks / 20;
                        lore.add(noItalic(Component.text(
                                String.format("時間: %d:%02d", seconds / 60, seconds % 60),
                                NamedTextColor.WHITE)));
                    }
                    lore.add(noItalic(Component.text("レベル: " + level, NamedTextColor.YELLOW)));
                    meta.lore(lore);
                });
                inventory.setItem(slot, item);
                slot++;
            }
        }

        ItemStack back = new ItemStack(Material.ARROW);
        back.editMeta(meta -> meta.displayName(noItalic(Component.text("戻る", NamedTextColor.WHITE))));
        inventory.setItem(BACK_SLOT, back);
    }

    private void registerEffect(int slot, PotionEffectType type, String displayName, Material icon) {
        slotToEffect.put(slot, type);
        int currentLevel = currentAmplifier(type) + 1;
        ItemStack item = new ItemStack(icon);
        item.editMeta(meta -> {
            meta.displayName(noItalic(Component.text(displayName,
                    currentLevel > 0 ? NamedTextColor.GREEN : NamedTextColor.WHITE)));
            List<Component> lore = new ArrayList<>();
            if (currentLevel > 0) {
                lore.add(noItalic(Component.text("現在: Lv " + currentLevel, NamedTextColor.YELLOW)));
            } else {
                lore.add(noItalic(Component.text("未適用", NamedTextColor.DARK_GRAY)));
            }
            lore.add(noItalic(Component.text("左クリック: Lv+1で再付与", NamedTextColor.GREEN)));
            lore.add(noItalic(Component.text("右クリック: 解除", NamedTextColor.RED)));
            meta.lore(lore);
        });
        inventory.setItem(slot, item);
    }

    private int currentAmplifier(PotionEffectType type) {
        for (PotionEffect effect : dummy.getActiveEffects()) {
            if (effect.getType().equals(type)) {
                return effect.getAmplifier();
            }
        }
        return -1;
    }

    public void handleClick(Player player, int slot, ClickType clickType, JavaPlugin plugin) {
        if (slot == BACK_SLOT) {
            player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
            return;
        }

        PotionEffectType type = slotToEffect.get(slot);
        if (type == null) return;

        LivingEntity ent = dummy.getEntity();
        if (ent == null || ent.isDead()) return;

        if (clickType == ClickType.RIGHT || clickType == ClickType.SHIFT_RIGHT) {
            dummy.removePotionEffect(type);
            player.sendMessage(prefix().append(Component.text(
                    translateEffect(type) + " を解除しました。", NamedTextColor.YELLOW)));
        } else if (clickType == ClickType.LEFT || clickType == ClickType.SHIFT_LEFT) {
            int currentAmp = currentAmplifier(type);
            int nextAmp = Math.min(MAX_LEVEL - 1, currentAmp + 1);
            int durationTicks = isInstant(type) ? 1 : DEFAULT_DURATION_SECONDS * 20;
            PotionEffect effect = new PotionEffect(type, durationTicks, nextAmp, false, true, true);
            dummy.applyPotionEffect(effect);
            player.sendMessage(prefix().append(Component.text(
                    translateEffect(type) + " " + toRoman(nextAmp + 1) + " を付与しました。",
                    NamedTextColor.GREEN)));
        }

        player.openInventory(new EffectsGUI(plugin, dummy).getInventory());
    }

    private static boolean isInstant(PotionEffectType type) {
        return type.equals(PotionEffectType.INSTANT_HEALTH)
                || type.equals(PotionEffectType.INSTANT_DAMAGE)
                || type.equals(PotionEffectType.SATURATION);
    }

    private Component prefix() {
        return Component.text("[DPSChecker] ", NamedTextColor.GOLD);
    }

    private String translateEffect(PotionEffectType type) {
        String key = type.getKey().getKey();
        return switch (key) {
            case "speed" -> "移動速度上昇";
            case "slowness" -> "移動速度低下";
            case "haste" -> "採掘速度上昇";
            case "mining_fatigue" -> "採掘速度低下";
            case "strength" -> "攻撃力上昇";
            case "instant_health" -> "即時回復";
            case "instant_damage" -> "即時ダメージ";
            case "jump_boost" -> "跳躍力上昇";
            case "nausea" -> "吐き気";
            case "regeneration" -> "再生";
            case "resistance" -> "耐性";
            case "fire_resistance" -> "火炎耐性";
            case "water_breathing" -> "水中呼吸";
            case "invisibility" -> "透明化";
            case "blindness" -> "盲目";
            case "night_vision" -> "暗視";
            case "hunger" -> "空腹";
            case "weakness" -> "弱体化";
            case "poison" -> "毒";
            case "wither" -> "衰弱";
            case "health_boost" -> "体力増強";
            case "absorption" -> "衝撃吸収";
            case "saturation" -> "満腹度回復";
            case "glowing" -> "発光";
            case "levitation" -> "浮遊";
            case "luck" -> "幸運";
            case "unluck" -> "不運";
            case "slow_falling" -> "低速落下";
            case "conduit_power" -> "コンジットパワー";
            case "dolphins_grace" -> "イルカの好意";
            case "bad_omen" -> "不吉な予感";
            case "hero_of_the_village" -> "村の英雄";
            case "darkness" -> "暗闇";
            default -> key;
        };
    }

    private String toRoman(int num) {
        return switch (num) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(num);
        };
    }

    public DummyEntity getDummy() { return dummy; }

    @Override
    public Inventory getInventory() { return inventory; }
}
