package com.trinityforge.listeners;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.progression.CombatLevelSource;
import com.trinityforge.progression.SkillLevelSource;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M-2(手懐けた友好モブのレベルを飼い主の総合戦闘レベルで決める)の中核ロジック
 * ({@link MobTypeSpawnListener#tamedLevelFor}) を、Bukkit イベント/エンティティを一切使わずに
 * 直接検証する。
 *
 * <p>切り出した理由: MockBukkit が {@code EntityTameEvent}/{@code Tameable} をどこまで実装している
 * かは不確実（未実装なら {@code UnimplementedOperationException} で無言に SKIPPED 化する
 * — 本テストスイートの既知の罠）。レベル解決そのものは Bukkit 依存が無いので、
 * ここを切り出しておけば MockBukkit の対応状況に関係なく壊れを検出できる。
 *
 * <p>{@code Plugin}/{@code ConfigManager} は状態を持たないダミーとして Mockito でモックする
 * （{@link MobTypeSpawnListener} のコンストラクタは {@code Objects.requireNonNull} しか行わないため、
 * MockBukkit の {@code ServerMock} を立てる必要が無い）。
 */
class MobTypeSpawnListenerTamedLevelTest {

    private static final UUID OWNER = UUID.randomUUID();

    private MobTypeSpawnListener newListener(MobTypesConfig mobTypesConfig, CombatLevelSource combatLevelSource) {
        Plugin plugin = mock(Plugin.class);
        ConfigManager configManager = mock(ConfigManager.class);
        return new MobTypeSpawnListener(plugin, mobTypesConfig, configManager,
                SkillLevelSource.EMPTY, combatLevelSource);
    }

    @Test
    @DisplayName("飼い主の総合戦闘レベルからポリシー通りのレベルが出る")
    void resolvesLevelFromOwnersCombatLevel() {
        MobTypesConfig mobTypesConfig = mock(MobTypesConfig.class);
        when(mobTypesConfig.tamedLevelPolicy())
                .thenReturn(new MobTypesConfig.TamedLevelPolicy(true, 1.0, 1, 0));
        when(mobTypesConfig.maxLevel()).thenReturn(100);
        CombatLevelSource combatLevelSource = id -> id.equals(OWNER) ? 42 : 0;

        MobTypeSpawnListener listener = newListener(mobTypesConfig, combatLevelSource);

        OptionalInt level = listener.tamedLevelFor(OWNER);
        assertTrue(level.isPresent());
        assertEquals(43, level.getAsInt(), "base-level(1) + 総合戦闘レベル(42) * 1.0 = 43");
    }

    @Test
    @DisplayName("tamed.enabled=false なら常に空(=従来どおりEntityTypeの通常値のまま)")
    void disabledPolicyYieldsEmpty() {
        MobTypesConfig mobTypesConfig = mock(MobTypesConfig.class);
        when(mobTypesConfig.tamedLevelPolicy()).thenReturn(MobTypesConfig.TamedLevelPolicy.DISABLED);
        CombatLevelSource combatLevelSource = id -> 99;

        MobTypeSpawnListener listener = newListener(mobTypesConfig, combatLevelSource);

        assertTrue(listener.tamedLevelFor(OWNER).isEmpty());
    }

    @Test
    @DisplayName("飼い主UUIDがnullなら空(所有者不明の安全側フォールバック)")
    void nullOwnerYieldsEmpty() {
        MobTypesConfig mobTypesConfig = mock(MobTypesConfig.class);
        when(mobTypesConfig.tamedLevelPolicy())
                .thenReturn(new MobTypesConfig.TamedLevelPolicy(true, 1.0, 1, 0));
        CombatLevelSource combatLevelSource = id -> 99;

        MobTypeSpawnListener listener = newListener(mobTypesConfig, combatLevelSource);

        assertTrue(listener.tamedLevelFor(null).isEmpty());
    }

    @Test
    @DisplayName("mobTypesConfig.tamedLevelPolicy()がnullを返しても落ちない(Mockito未スタブ対策と同じ事情)")
    void nullPolicyIsTreatedAsDisabled() {
        MobTypesConfig mobTypesConfig = mock(MobTypesConfig.class); // 未スタブなのでnullを返す
        CombatLevelSource combatLevelSource = id -> 99;

        MobTypeSpawnListener listener = newListener(mobTypesConfig, combatLevelSource);

        assertTrue(listener.tamedLevelFor(OWNER).isEmpty());
    }

    @Test
    @DisplayName("上限は総合戦闘レベルが高くてもmax-levelでクランプされる")
    void clampsToMaxLevel() {
        MobTypesConfig mobTypesConfig = mock(MobTypesConfig.class);
        when(mobTypesConfig.tamedLevelPolicy())
                .thenReturn(new MobTypesConfig.TamedLevelPolicy(true, 1.0, 0, 20));
        CombatLevelSource combatLevelSource = id -> 999;

        MobTypeSpawnListener listener = newListener(mobTypesConfig, combatLevelSource);

        assertEquals(20, listener.tamedLevelFor(OWNER).getAsInt());
    }
}
