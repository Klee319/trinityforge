package com.trinityforge.skilltree.runtime;

import com.trinityforge.stats.UseSkillDefaults;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Resolves conditional armor-set buff stats: the {@code set-buffs} schema (SKILL_TREE armor-set-buffs
 * migration §1) is resolved by {@link PerkBuffResolver#setBuffsFor} and amplified here by the
 * {@code armor-set-bonus} total stat. This bridge only exists because that effect depends on the
 * number/type of worn armor pieces.
 *
 * <p>2026-07-31: 旧「装備部位数 × 係数」の平坦キー
 * ({@code light_armor_move_speed_per_piece} / {@code heavy_armor_move_speed_per_piece}) を撤去した。
 * 専用の読み出し経路(perk buffs の {@code general} のみ)を持つだけのキーで、同じ効果は
 * {@code set-buffs} の {@code move-speed}(段3/4条件)で表現できるため統合した
 * (light_armor.yml / heavy_armor.yml のノードAが移行先)。
 *
 * <p><b>移行で変わった点は2つ</b>(どちらも意図どおり):
 * (1) 1〜2部位では効かなくなった(閾値が段3のため)、
 * (2) <b>{@code armor-set-bonus} で増幅されるようになった</b> — 旧 per-piece 経路は
 * {@code general} から直読みしていたので増幅されなかったが、move-speed だけを増幅の例外に
 * するとノードB「セット効果量UP」の宣言(3・4部位のセット効果をまとめて強化)と食い違う。
 * 実効値は {@code NativeAttributeBridgeTest#moveSpeedFromSetBuffsIsAmplifiedByArmorSetBonus} が固定。
 */
public final class NativeAttributeBridge {

    private static final String ARMOR_SET_BONUS = "armor_set_bonus";
    private static final String LIGHT_ARMOR_SKILL = "LIGHT_ARMOR";
    private static final String HEAVY_ARMOR_SKILL = "HEAVY_ARMOR";

    private final PerkBuffResolver perkBuffs;

    /**
     * 2026-08-13 修正2({@code armor-set-bonus} 総合値化): パーク以外(装備アイテム / 役職バフ /
     * 永続バフ / {@code combat/base-stats.yml})由来の {@code armor-set-bonus} を供給する。null 可
     * (未注入なら 0.0 として扱う、既存挙動と完全互換)。コンストラクタでは注入できない
     * ({@code TrinityForge} での生成順の都合で {@code PlayerStatAggregator} より先にこのブリッジを
     * 作る必要があるため)ので、{@link #setNonPerkArmorSetBonusSupplier} で後から注入する。
     *
     * <p><b>スレッド可視性(2026-08-13):</b> {@code onEnable} 中に1回だけ設定され、以後は不変
     * (再設定されない)。{@link #armorAttributesFor} は非同期スレッドからも到達しうる
     * ({@code PlayerStatAggregator.java:260-262} が非同期呼び出し経路
     * {@code NativeExperienceDispatcher#drain} を明記している)ため、設定時の書き込みが他スレッドから
     * 確実に見えるよう {@code volatile} にしている。
     */
    private volatile ToDoubleFunction<Player> nonPerkArmorSetBonusSupplier;

    public NativeAttributeBridge(PerkBuffResolver perkBuffs) {
        this.perkBuffs = Objects.requireNonNull(perkBuffs, "perkBuffs");
    }

    /**
     * @param supplier パーク以外由来の {@code armor-set-bonus} 総合値を返す関数(通常は
     *                 {@code PlayerStatAggregator#nonPerkStatTotal(player, "armor_set_bonus")})。
     *                 {@code null} を渡すと未注入状態(0.0扱い)へ戻せる。
     */
    public void setNonPerkArmorSetBonusSupplier(ToDoubleFunction<Player> supplier) {
        this.nonPerkArmorSetBonusSupplier = supplier;
    }

    /**
     * The {@code set-buffs} contribution of both the {@code light_armor} and {@code heavy_armor} trees
     * (SKILL_TREE armor-set-buffs migration §1), each amplified by {@code 1 + max(0, armor-set-bonus)}
     * (§2). Because the set-buff threshold tiers are 3 and 4 out of 4 armor slots, a light set and a
     * heavy set can never both be active at once (3 + 3 &gt; 4).
     *
     * <p>Keys returned here span multiple channels ({@code move_speed}/{@code knockback_resistance} are
     * ATTRIBUTE; a {@code set-buffs} author may declare ATTACK/DEFENSE/GENERAL keys too) — callers must
     * route each key through {@link com.trinityforge.stats.StatVocabulary#channelOf} rather than assuming
     * a fixed shape.
     */
    public Map<String, Double> armorAttributesFor(Player player) {
        if (player == null) return Map.of();
        UUID id = player.getUniqueId();
        int light = 0;
        int heavy = 0;
        ItemStack[] armor = player.getInventory().getArmorContents();
        if (armor != null) {
            for (ItemStack piece : armor) {
                if (piece == null || piece.getType().isAir()) continue;
                // 2026-07-26: 軽装/重装の振り分けを com.trinityforge.stats.UseSkillDefaults へ一本化。
                // 従来ここは LEATHER_/CHAINMAIL_ だけを軽装とし **金装備を重装扱い**にしていたが、
                // UseSkillDefaults は GOLDEN_ を軽装に入れており、金防具を着ると「レベルゲートは軽装
                // なのにセット効果は重装」という矛盾状態になっていた。
                if (!isArmor(piece.getType())) continue;
                if (UseSkillDefaults.isLightArmor(piece.getType())) light++;
                else heavy++;
            }
        }
        // 2026-08-13(性能回帰修正): 増幅率(amplifier)は set-buffs に掛けるためだけの値。set-buffs が
        // light/heavy 両方とも空なら、増幅率をいくら精緻に求めても掛け算する相手が無い。
        // nonPerkArmorSetBonusSupplier(= PlayerStatAggregator#nonPerkStatTotal)は
        // usableArmorContents(UseRequirementService×4) + DerivedItemStats.resolve ×5〜6 +
        // RoleBuffResolver#contributionFor + PermanentBuffResolver#buffsFor(achievement/collection の
        // フルスキャン)を毎回フル実行するため、set-buffs が空と分かっている呼び出しでこれを走らせるのは
        // 純粋な無駄(PerkAttributeApplier が装備変更のたび armorAttributesFor を2回呼ぶので特に効く)。
        // そのため先に light/heavy の set-buffs を解決し、両方 empty なら supplier を一度も呼ばずに
        // Map.of() を早期returnする。
        Map<String, Double> lightSetBuffs = perkBuffs.setBuffsFor(id, LIGHT_ARMOR_SKILL, light);
        Map<String, Double> heavySetBuffs = perkBuffs.setBuffsFor(id, HEAVY_ARMOR_SKILL, heavy);
        if (lightSetBuffs.isEmpty() && heavySetBuffs.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> general = perkBuffs.buffsFor(id).general();
        Map<String, Double> out = new HashMap<>();
        // 2026-08-13 修正2: パーク分(general)に加え、供給されていれば装備/役職/永続/base-stats 由来の
        // armor-set-bonus も先に合算してから 0 未満をクランプする(base-stats.yml に行があっても
        // これまで無言で捨てられていたバグの修正)。未注入(null)なら以前と完全に同じ挙動。
        ToDoubleFunction<Player> supplier = nonPerkArmorSetBonusSupplier;
        double nonPerkBonus = supplier == null ? 0.0 : supplier.applyAsDouble(player);
        double amplifier = 1.0 + Math.max(0.0, general.getOrDefault(ARMOR_SET_BONUS, 0.0) + nonPerkBonus);
        mergeSetBuffs(out, lightSetBuffs, amplifier);
        mergeSetBuffs(out, heavySetBuffs, amplifier);

        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static void mergeSetBuffs(Map<String, Double> out, Map<String, Double> setBuffs, double amplifier) {
        setBuffs.forEach((key, value) -> add(out, key, value * amplifier));
    }

    private static boolean isArmor(Material material) {
        String n = material.name().toUpperCase(Locale.ROOT);
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    private static void add(Map<String, Double> out, String key, double value) {
        if (value == 0.0 || !Double.isFinite(value)) return;
        out.merge(key, value, Double::sum);
    }
}
