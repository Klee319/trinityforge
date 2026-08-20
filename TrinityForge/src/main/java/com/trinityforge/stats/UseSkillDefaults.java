package com.trinityforge.stats;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * Infers a default {@code use-skill} when item-stats authors a use-level (or skill-less gate) without
 * an explicit skill. Keeps catalog/item-stats from needing every equipment row filled by hand.
 */
public final class UseSkillDefaults {

    private UseSkillDefaults() {
    }

    /**
     * @return inferred skill id, or empty when the material is not a gated equipment type
     */
    public static Optional<String> infer(Material material, Integer customModelData) {
        if (material == null) {
            return Optional.empty();
        }
        return fromMaterialName(material.name());
    }

    private static Optional<String> fromMaterialName(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (n.equals("BOW") || n.equals("CROSSBOW")) {
            return Optional.of("ARCHERY");
        }
        if (n.equals("MACE") || n.endsWith("_AXE")) {
            return Optional.of("HEAVY_WEAPONS");
        }
        // N5(2026-07-31): TRIDENT は ARCHERY ではなく LIGHT_WEAPONS。
        // 出荷 stats/item-stats.yml のトライデント14行はすべて use-skill: LIGHT_WEAPONS を明記しており、
        // 推論だけが ARCHERY で食い違っていた(推論が効くのは use-skill を書かなかった行だけなので
        // 現物では不発だったが、use-skill 無しのトライデント行を1つ足した瞬間に挙動が変わる landmine)。
        // 旧 ArcheryExperiencePolicy は BOW/CROSSBOW 以外に 0.0 を返していたため、当時この推論に
        // 落ちたトライデントは警告なしで戦闘EXPが完全に0になる、という silent-zero も同居していた。
        if (n.equals("TRIDENT") || n.endsWith("_SWORD") || n.endsWith("_SPEAR")) {
            return Optional.of("LIGHT_WEAPONS");
        }
        if (isArmorPiece(n)) {
            return Optional.of(isLightArmorName(n) ? "LIGHT_ARMOR" : "HEAVY_ARMOR");
        }
        if (n.endsWith("_PICKAXE")) {
            return Optional.of("MINING");
        }
        if (n.endsWith("_SHOVEL")) {
            return Optional.of("DIGGING");
        }
        if (n.endsWith("_HOE")) {
            return Optional.of("FARMING");
        }
        if (n.equals("FISHING_ROD")) {
            return Optional.of("FISHING");
        }
        return Optional.empty();
    }

    /**
     * 防具マテリアルが「軽装」系統かどうか。防具でない場合も {@code false} を返す。
     *
     * <p>2026-07-26: 軽装/重装の振り分けが2箇所に重複して書かれており、**金装備の扱いが食い違っていた**
     * (ここは {@code GOLDEN_} を軽装に入れていたが、{@code skilltree.runtime.NativeAttributeBridge} は
     * {@code LEATHER_}/{@code CHAINMAIL_} だけを軽装としており金は重装扱い)。結果、金防具を着ると
     * 「レベルゲートは軽装なのにセット効果は重装」という矛盾した状態になっていた。
     * 判定をこのメソッドへ一本化し、両者が必ず同じ答えを返すようにする。
     */
    public static boolean isLightArmor(Material material) {
        if (material == null) return false;
        String n = material.name().toUpperCase(Locale.ROOT);
        return isArmorPiece(n) && isLightArmorName(n);
    }

    private static boolean isLightArmorName(String n) {
        return n.startsWith("LEATHER_") || n.startsWith("CHAINMAIL_") || n.startsWith("GOLDEN_");
    }

    private static boolean isArmorPiece(String name) {
        return name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || name.equals("TURTLE_HELMET")
                || name.equals("ELYTRA");
    }
}
