package com.trinityforge.stats;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3監査: 防具値(armor / armor_toughness)はTF著者指定で材質既定を置換する。攻撃速度など加算ステは
 * TF modifier と材質既定を両立させる。{@link AttributeApplier#missingDefaults} は MockBukkit 制約を
 * 避けて直接テストする。
 */
class AttributeApplierMissingDefaultsTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static AttributeModifier modifier(String key, double amount) {
        return new AttributeModifier(NamespacedKey.minecraft(key), amount,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
    }

    @Test
    void suppressesVanillaArmorWhenTfAuthorsArmorDefenseRate() {
        Multimap<Attribute, AttributeModifier> defaults = ArrayListMultimap.create();
        defaults.put(Attribute.ARMOR, modifier("base_armor", 8.0));
        Multimap<Attribute, AttributeModifier> existing = ArrayListMultimap.create();
        Set<Attribute> tfManaged = Set.of(Attribute.ARMOR);

        List<Map.Entry<Attribute, AttributeModifier>> result =
                AttributeApplier.missingDefaults(defaults, existing, tfManaged);

        assertTrue(result.isEmpty(), "authored armor-defense-rate replaces material armor defaults");
    }

    @Test
    void neverRestoresAttackSpeedMaterialDefaultRegardlessOfTfManagedState() {
        // 2026-07-25: attack-speed / attack-speed-bonus はプレイヤー単位(PerkAttributeApplier)で一元
        // 適用するため、item-level では tfManaged の有無に関わらず ATTACK_SPEED の材質既定を絶対に
        // 復元しない(無条件除外)。
        Multimap<Attribute, AttributeModifier> defaults = ArrayListMultimap.create();
        defaults.put(Attribute.ATTACK_SPEED, modifier("base_attack_speed", -2.4));
        Multimap<Attribute, AttributeModifier> existing = ArrayListMultimap.create();
        Set<Attribute> tfManagedEmpty = Set.of();

        List<Map.Entry<Attribute, AttributeModifier>> resultWhenNotManaged =
                AttributeApplier.missingDefaults(defaults, existing, tfManagedEmpty);
        List<Map.Entry<Attribute, AttributeModifier>> resultWhenManaged =
                AttributeApplier.missingDefaults(defaults, existing, Set.of(Attribute.ATTACK_SPEED));

        assertTrue(resultWhenNotManaged.isEmpty(),
                "ATTACK_SPEED material default must never be restored at item level");
        assertTrue(resultWhenManaged.isEmpty(),
                "ATTACK_SPEED material default must never be restored at item level");
    }

    @Test
    void keepsTheVanillaDefaultForAnAttributeTfDoesNotManage() {
        AttributeModifier attackDamageDefault = modifier("base_attack_damage", 7.0);
        Multimap<Attribute, AttributeModifier> defaults = ArrayListMultimap.create();
        defaults.put(Attribute.ATTACK_DAMAGE, attackDamageDefault);
        Multimap<Attribute, AttributeModifier> existing = ArrayListMultimap.create();
        Set<Attribute> tfManaged = Set.of(Attribute.ATTACK_SPEED);

        List<Map.Entry<Attribute, AttributeModifier>> result =
                AttributeApplier.missingDefaults(defaults, existing, tfManaged);

        assertEquals(1, result.size());
        assertEquals(Attribute.ATTACK_DAMAGE, result.get(0).getKey());
        assertEquals(attackDamageDefault, result.get(0).getValue());
    }

    @Test
    void staysIdempotentWhenTheExactDefaultModifierIsAlreadyPresent() {
        AttributeModifier attackDamageDefault = modifier("base_attack_damage", 7.0);
        Multimap<Attribute, AttributeModifier> defaults = ArrayListMultimap.create();
        defaults.put(Attribute.ATTACK_DAMAGE, attackDamageDefault);
        Multimap<Attribute, AttributeModifier> existing = ArrayListMultimap.create();
        existing.put(Attribute.ATTACK_DAMAGE, attackDamageDefault);
        Set<Attribute> tfManaged = Set.of();

        List<Map.Entry<Attribute, AttributeModifier>> result =
                AttributeApplier.missingDefaults(defaults, existing, tfManaged);

        assertTrue(result.isEmpty(), "re-apply on an already-restored item must not double the default");
    }
}
