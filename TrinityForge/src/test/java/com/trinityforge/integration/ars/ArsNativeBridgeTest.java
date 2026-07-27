package com.trinityforge.integration.ars;

import com.trinityforge.config.domains.BaseStatsConfig;
import com.trinityforge.progression.PermanentBuffResolver;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffs;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ArsNativeBridge} 移行B11 (2026-07-23 stat-gate-overhaul §2): maxManaBonus/manaRegenBonus は
 * perk-buffs general の {@code mana_bonus}/{@code mana_regen} からのみ読む(装備分は含めない —
 * ArsPaperフォークのitem-stats経路と二重計上を避けるため)。unlockedTier/glyphSlots も buffs から読む。
 */
class ArsNativeBridgeTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void manaBonusAddsPermanentBuffOnTopOfPerkGeneralChannel() {
        // 修正B: mana_bonus/mana_regenのpermanent-buffはPlayerStatAggregatorのitem mapには合流するが
        // このブリッジはperkBuffResolver.general()しか読まないため素通りしていた。permanentBuffResolver
        // を追加注入し、perk分と加算合成されること(装備分の二重計上を避ける既存方針は維持)を確認する。
        PerkBuffResolver perkBuffResolver = mock(PerkBuffResolver.class);
        Player player = server.addPlayer();
        UUID id = player.getUniqueId();
        when(perkBuffResolver.buffsFor(id)).thenReturn(
                new PerkBuffs(Map.of(), Map.of(), Map.of(), Map.of("mana_bonus", 15.0, "mana_regen", 2.0),
                        Map.of()));
        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        when(permanentBuffResolver.buffsFor(any()))
                .thenReturn(Map.of("mana_bonus", 5.0, "mana_regen", 1.0));
        ArsNativeBridge bridge = new ArsNativeBridge(perkBuffResolver, permanentBuffResolver);

        assertEquals(20.0, bridge.maxManaBonus(id), "perk(15) + permanent-buff(5)");
        assertEquals(3.0, bridge.manaRegenBonus(id), "perk(2) + permanent-buff(1)");
    }

    @Test
    void manaBonusIgnoresPermanentBuffWhenPlayerOffline() {
        PerkBuffResolver perkBuffResolver = mock(PerkBuffResolver.class);
        UUID id = UUID.randomUUID(); // オンラインでない = Bukkit.getPlayer(id) は null
        when(perkBuffResolver.buffsFor(id)).thenReturn(
                new PerkBuffs(Map.of(), Map.of(), Map.of(), Map.of("mana_bonus", 15.0), Map.of()));
        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        ArsNativeBridge bridge = new ArsNativeBridge(perkBuffResolver, permanentBuffResolver);

        assertEquals(15.0, bridge.maxManaBonus(id), "オフラインならpermanent-buffは加算されない(perk分のみ)");
    }

    @Test
    void manaBonusReadsPerkGeneralChannelOnly() {
        PerkBuffResolver perkBuffResolver = mock(PerkBuffResolver.class);
        UUID id = UUID.randomUUID();
        when(perkBuffResolver.buffsFor(id)).thenReturn(
                new PerkBuffs(Map.of(), Map.of(), Map.of(), Map.of("mana_bonus", 15.0, "mana_regen", 2.0),
                        Map.of()));
        ArsNativeBridge bridge = new ArsNativeBridge(perkBuffResolver);

        assertEquals(15.0, bridge.maxManaBonus(id));
        assertEquals(2.0, bridge.manaRegenBonus(id));
    }

    @Test
    void manaBonusIsZeroWhenNoPerksUnlocked() {
        PerkBuffResolver perkBuffResolver = mock(PerkBuffResolver.class);
        UUID id = UUID.randomUUID();
        when(perkBuffResolver.buffsFor(id)).thenReturn(PerkBuffs.EMPTY);
        ArsNativeBridge bridge = new ArsNativeBridge(perkBuffResolver);

        assertEquals(0.0, bridge.maxManaBonus(id));
        assertEquals(0.0, bridge.manaRegenBonus(id));
    }

    @Test
    void arsTierAndGlyphSlotsReadPerkBuffs() {
        UUID id = UUID.randomUUID();
        PerkBuffResolver perkBuffResolver = mock(PerkBuffResolver.class);
        when(perkBuffResolver.buffsFor(id)).thenReturn(new PerkBuffs(Map.of(), Map.of(), Map.of(),
                Map.of("ars_tier_bonus", 2.0, "glyph_slot_bonus", 3.0), Map.of()));
        ArsNativeBridge bridge = new ArsNativeBridge(perkBuffResolver);

        assertEquals(2, bridge.unlockedTier(id));
        assertEquals(3, bridge.glyphSlots(id));
    }

    // --- 2026-07-26 マナ系ステ穴埋め: 4キー(mana_bonus/mana_regen/ars_tier_bonus/glyph_slot_bonus)を
    // ArsNativeBridge の唯一の非装備供給源にする(オーケストレータ決定)。role-buffs / base-stats を追加し、
    // unlockedTier/glyphSlots にも permanent-buff(以前はmanaの2キーだけの非対称だった)を対称に足す。

    private BaseStatsConfig baseStatsFrom(File dir, String yaml) throws IOException {
        File file = new File(dir, BaseStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        BaseStatsConfig cfg = new BaseStatsConfig();
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dir;
            case "getLogger" -> java.util.logging.Logger.getLogger("ArsNativeBridgeTest-baseStats");
            case "saveResource" -> throw new AssertionError("saveResource() must not be called (file exists)");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        org.bukkit.plugin.Plugin plugin = (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[] {org.bukkit.plugin.Plugin.class}, handler);
        cfg.load(plugin);
        return cfg;
    }

    /**
     * 4キー(mana_bonus/mana_regen/ars_tier_bonus/glyph_slot_bonus)それぞれについて、
     * パーク general / 永続バフ / 役職バフ / base-stats の4ソースがちょうど1回ずつ合算されることを検証する。
     */
    @Test
    void allFourKeys_sumPerkPlusPermanentPlusRolePlusBaseStatsOnce(@TempDir File dir) throws IOException {
        Player player = server.addPlayer();
        UUID id = player.getUniqueId();

        PerkBuffResolver perkBuffResolver = mock(PerkBuffResolver.class);
        when(perkBuffResolver.buffsFor(id)).thenReturn(new PerkBuffs(Map.of(), Map.of(), Map.of(), Map.of(
                "mana_bonus", 1.0, "mana_regen", 1.0, "ars_tier_bonus", 1.0, "glyph_slot_bonus", 1.0),
                Map.of()));

        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        when(permanentBuffResolver.buffsFor(any())).thenReturn(Map.of(
                "mana_bonus", 10.0, "mana_regen", 10.0, "ars_tier_bonus", 10.0, "glyph_slot_bonus", 10.0));

        RoleBuffResolver roleBuffResolver = mock(RoleBuffResolver.class);
        when(roleBuffResolver.contributionFor(any())).thenReturn(new RoleBuffResolver.Contribution(
                Map.of("mana_bonus", 100.0, "mana_regen", 100.0), // attackBuffs
                Map.of("ars_tier_bonus", 100.0, "glyph_slot_bonus", 100.0), // defenseBuffs
                1.0, null, 1.0));

        BaseStatsConfig baseStats = baseStatsFrom(dir, """
                base-stats:
                  mana_bonus: 1000
                  mana_regen: 1000
                  ars_tier_bonus: 1000
                  glyph_slot_bonus: 1000
                """);

        ArsNativeBridge bridge = new ArsNativeBridge(
                perkBuffResolver, permanentBuffResolver, roleBuffResolver, baseStats);

        // 各キー: パーク(1) + 永続バフ(10) + 役職(100) + base-stats(1000) = 1111
        assertEquals(1111.0, bridge.maxManaBonus(id), 1e-9, "mana_bonusが4ソースから1回ずつ合算される");
        assertEquals(1111.0, bridge.manaRegenBonus(id), 1e-9, "mana_regenが4ソースから1回ずつ合算される");
        assertEquals(1111, bridge.unlockedTier(id), "ars_tier_bonusが4ソースから1回ずつ合算される");
        assertEquals(1111, bridge.glyphSlots(id), "glyph_slot_bonusが4ソースから1回ずつ合算される");
    }

    /**
     * 非回帰の固定: unlockedTier/glyphSlots は以前 permanentBuffResolver を拾わない非対称バグだった
     * (mana_bonus/mana_regenだけが修正Bで永続バフ対応していた)。この非対称が解消されたことを固定する。
     */
    @Test
    void unlockedTierAndGlyphSlots_nowPickUpPermanentBuffSymmetricWithMana() {
        // permanentBuffFor は Player 必須(PDC読み取り)なのでオンラインプレイヤーで検証する
        // (manaBonusIgnoresPermanentBuffWhenPlayerOfflineと対称: オフラインなら0になる挙動は別途検証済み)。
        Player player = server.addPlayer();
        UUID id = player.getUniqueId();
        PerkBuffResolver perkBuffResolver = mock(PerkBuffResolver.class);
        when(perkBuffResolver.buffsFor(id)).thenReturn(PerkBuffs.EMPTY);
        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        when(permanentBuffResolver.buffsFor(any())).thenReturn(Map.of(
                "ars_tier_bonus", 4.0, "glyph_slot_bonus", 6.0));
        ArsNativeBridge bridge = new ArsNativeBridge(perkBuffResolver, permanentBuffResolver, null, null);

        assertEquals(4, bridge.unlockedTier(id), "unlockedTierが永続バフを拾うようになった(以前は非対称で0だった)");
        assertEquals(6, bridge.glyphSlots(id), "glyphSlotsが永続バフを拾うようになった(以前は非対称で0だった)");
    }

    @Test
    void nullRoleBuffResolverAndBaseStats_behaveExactlyAsBeforeThisWiring() {
        // 3引数コンストラクタ(role/base-stats=null)は不変: role/base-stats由来の値が一切現れない。
        Player player = server.addPlayer();
        UUID id = player.getUniqueId();
        PerkBuffResolver perkBuffResolver = mock(PerkBuffResolver.class);
        when(perkBuffResolver.buffsFor(id)).thenReturn(
                new PerkBuffs(Map.of(), Map.of(), Map.of(), Map.of("mana_bonus", 15.0), Map.of()));
        PermanentBuffResolver permanentBuffResolver = mock(PermanentBuffResolver.class);
        when(permanentBuffResolver.buffsFor(any())).thenReturn(Map.of("mana_bonus", 5.0));
        ArsNativeBridge bridge = new ArsNativeBridge(perkBuffResolver, permanentBuffResolver);

        assertEquals(20.0, bridge.maxManaBonus(id), "perk(15) + permanent-buff(5)のみ、role/base-statsは0");
    }
}
