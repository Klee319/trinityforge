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

    /**
     * 2026-08-15 防具値の廃止: {@link Attribute#ARMOR} の材質既定は
     * <b>TFが何を書いたかに関わらず</b>復元しない(HUDの防具バーは常に空)。
     * 復元すると革7/ネザライト11といった材質既定がバニラ防具ミラー経由で復活し、
     * item-stats の防御率と二重に軽減する。
     */
    @Test
    void neverRestoresVanillaArmorMaterialDefaultRegardlessOfTfManagedState() {
        Multimap<Attribute, AttributeModifier> defaults = ArrayListMultimap.create();
        defaults.put(Attribute.ARMOR, modifier("base_armor", 8.0));
        Multimap<Attribute, AttributeModifier> existing = ArrayListMultimap.create();

        assertTrue(AttributeApplier.missingDefaults(defaults, existing, Set.of(Attribute.ARMOR)).isEmpty(),
                "TFがARMORへ書いた場合は当然復元しない");
        assertTrue(AttributeApplier.missingDefaults(defaults, existing, Set.<Attribute>of()).isEmpty(),
                "TFが何も書かなくてもARMORの材質既定は復元しない(防具バーを空にする)");
    }

    /** 靭性(ARMOR_TOUGHNESS)は従来どおり「TFが書いたときだけ」材質既定を抑制する。 */
    @Test
    void restoresArmorToughnessMaterialDefaultWhenTfDidNotAuthorIt() {
        Multimap<Attribute, AttributeModifier> defaults = ArrayListMultimap.create();
        defaults.put(Attribute.ARMOR_TOUGHNESS, modifier("base_toughness", 2.0));
        Multimap<Attribute, AttributeModifier> existing = ArrayListMultimap.create();

        assertTrue(AttributeApplier.missingDefaults(defaults, existing, Set.of(Attribute.ARMOR_TOUGHNESS))
                .isEmpty(), "TFが靭性を書いたら材質既定は戻さない(置換ステ)");
        assertEquals(1, AttributeApplier.missingDefaults(defaults, existing, Set.<Attribute>of()).size(),
                "TFが靭性を書いていなければ材質既定は戻す");
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
