package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 皮剥ぎ（{@code woodcutting_strip}）の経験値は<b>実際に皮が剥がれたときだけ</b>入ること
 * （2026-08-24 / W-210 回帰）。
 *
 * <h2>元のバグ</h2>
 * 旧実装は {@code PlayerInteractEvent}（MONITOR）で「斧を持って原木を右クリックした」だけを見て
 * 配っていた。ところがバニラの {@code AxeItem#useOn} は
 * <b>「オフハンドに盾（{@code blocks_attacks}）を持っていて、かつスニークしていない」なら
 * 何もせず {@code PASS} を返す</b>。ブロックは原木のまま残り、
 * {@code PlayerInteractEvent} はキャンセルもされないので、
 * <b>同じ原木を連打するだけで無限に伐採EXPが入っていた</b>（実サーバ報告）。
 *
 * <p>直し方は「バニラの門を TF 側で真似る」ではなく
 * <b>「変換が実際に起きる瞬間（{@code EntityChangeBlockEvent}）だけを見る」</b>。
 * 真似ると次のバージョンで静かにズレるうえ、他プラグインが
 * {@code setUseItemInHand(DENY)} だけ立てた場合など同型の穴が残る。
 *
 * <p>MockBukkit は使わない（未実装 API でテストが SKIPPED に化けるのを避ける）。
 */
class WoodStripExpRequiresRealStripTest {

    private static final SkillCatalogEntry WOODCUTTING = new SkillCatalogEntry(
            SkillId.WOODCUTTING, 100, "1", level -> 1L,
            Map.of("woodcutting_strip.STRIPPED_OAK_LOG", 20.0),
            Map.of());

    @Test
    void strippingALogGrantsWoodcuttingExp() {
        Fixture fixture = fixture();
        withMainHand(fixture.player(), Material.IRON_AXE);

        fixture.listener().onWoodStrip(
                changeEvent(fixture.player(), block(Material.OAK_LOG), Material.STRIPPED_OAK_LOG));

        verify(fixture.dispatcher()).grant(fixture.player().getUniqueId(), SkillId.WOODCUTTING, 20.0);
    }

    /**
     * <b>この 1 件が報告そのもの。</b> 盾を持っていると変換が起きないので
     * {@code EntityChangeBlockEvent} は<b>発火しない</b>＝ハンドラが呼ばれない。
     * 「呼ばれても配らない」ではなく「呼ばれる経路が無い」ことを担保するために、
     * 皮剥ぎの入口が {@code PlayerInteractEvent} に戻っていないことを wiring で固定する。
     */
    @Test
    void woodStripIsNotWiredToPlayerInteractAnyMore() throws Exception {
        for (Method method : NativeSkillExperienceListener.class.getMethods()) {
            if (!method.isAnnotationPresent(org.bukkit.event.EventHandler.class)) {
                continue;
            }
            boolean interactBased = method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == PlayerInteractEvent.class;
            assertFalse(interactBased && method.getName().toLowerCase().contains("strip"),
                    "皮剥ぎEXPを PlayerInteractEvent で配ると、盾持ちのように"
                            + "「右クリックは通るが剥がれない」場合に無限に稼げる（W-210 の再発）: "
                            + method.getName());
        }
        // 正しい入口が残っていること。
        NativeSkillExperienceListener.class.getMethod("onWoodStrip", EntityChangeBlockEvent.class);
    }

    @Test
    void otherAxeConversionsDoNotPayWoodcutting() {
        Fixture fixture = fixture();
        withMainHand(fixture.player(), Material.IRON_AXE);

        // 蝋落とし: WAXED_COPPER_BLOCK -> COPPER_BLOCK。STRIPPED_ 変換ではないので対象外。
        fixture.listener().onWoodStrip(
                changeEvent(fixture.player(), block(Material.WAXED_COPPER_BLOCK), Material.COPPER_BLOCK));

        verifyNoGrant(fixture);
    }

    @Test
    void aChangeThatLeavesTheLogUnstrippedPaysNothing() {
        Fixture fixture = fixture();
        withMainHand(fixture.player(), Material.IRON_AXE);

        // 「原木が原木のまま」= 変換されていない。ここで配ると連打で無限に稼げる。
        fixture.listener().onWoodStrip(
                changeEvent(fixture.player(), block(Material.OAK_LOG), Material.OAK_LOG));

        verifyNoGrant(fixture);
    }

    @Test
    void nonPlayerBlockChangesPayNothing() {
        Fixture fixture = fixture();
        EntityChangeBlockEvent event = mock(EntityChangeBlockEvent.class);
        when(event.getEntity()).thenReturn(mock(Entity.class));

        fixture.listener().onWoodStrip(event);

        verifyNoGrant(fixture);
    }

    @Test
    void aStripWithoutAnAxeInEitherHandPaysNothing() {
        Fixture fixture = fixture();
        withMainHand(fixture.player(), Material.STONE);

        fixture.listener().onWoodStrip(
                changeEvent(fixture.player(), block(Material.OAK_LOG), Material.STRIPPED_OAK_LOG));

        verifyNoGrant(fixture);
    }

    /** バニラはオフハンドの斧でも皮を剥げる。イベントは使った手を教えないので両手を見る。 */
    @Test
    void anOffhandAxeStillCounts() {
        Fixture fixture = fixture();
        withMainHand(fixture.player(), Material.STONE);
        withOffHand(fixture.player(), Material.NETHERITE_AXE);

        fixture.listener().onWoodStrip(
                changeEvent(fixture.player(), block(Material.OAK_LOG), Material.STRIPPED_OAK_LOG));

        verify(fixture.dispatcher()).grant(fixture.player().getUniqueId(), SkillId.WOODCUTTING, 20.0);
    }

    @Test
    void playerPlacedLogsPayNothing() {
        Fixture fixture = fixture();
        withMainHand(fixture.player(), Material.IRON_AXE);
        Block placed = block(Material.OAK_LOG);
        when(fixture.placedBlockTracker().isPlaced(placed)).thenReturn(true);

        fixture.listener().onWoodStrip(changeEvent(fixture.player(), placed, Material.STRIPPED_OAK_LOG));

        verifyNoGrant(fixture);
    }

    // ============================================================

    private static void verifyNoGrant(Fixture fixture) {
        verify(fixture.dispatcher(), never())
                .grant(eq(fixture.player().getUniqueId()), anyString(), anyDouble());
    }

    private record Fixture(NativeSkillExperienceListener listener,
                           NativeExperienceDispatcher dispatcher,
                           Player player,
                           PlayerInventory inventory,
                           PlacedBlockTracker placedBlockTracker) {
    }

    private static Fixture fixture() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.WOODCUTTING)).thenReturn(WOODCUTTING);
        PlacedBlockTracker placedBlockTracker = mock(PlacedBlockTracker.class);

        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        // ⚠ モックの生成を thenReturn() の引数内で行わない(入れ子の when() で
        // UnfinishedStubbingException になる。このリポジトリの既知の罠)。
        ItemStack emptyMain = stack(Material.AIR);
        ItemStack emptyOff = stack(Material.AIR);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(emptyMain);
        when(inventory.getItemInOffHand()).thenReturn(emptyOff);

        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        NativeSkillExperienceListener listener = new NativeSkillExperienceListener(
                fakePlugin(), dispatcher, catalog, placedBlockTracker, null,
                mock(DedicatedEffectsConfig.class), aggregator);
        return new Fixture(listener, dispatcher, player, inventory, placedBlockTracker);
    }

    private static void withMainHand(Player player, Material material) {
        ItemStack held = stack(material);
        when(player.getInventory().getItemInMainHand()).thenReturn(held);
    }

    private static void withOffHand(Player player, Material material) {
        ItemStack held = stack(material);
        when(player.getInventory().getItemInOffHand()).thenReturn(held);
    }

    private static ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        return stack;
    }

    private static Block block(Material material) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        return block;
    }

    private static EntityChangeBlockEvent changeEvent(Player player, Block block, Material to) {
        EntityChangeBlockEvent event = mock(EntityChangeBlockEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(event.getTo()).thenReturn(to);
        return event;
    }

    private static Plugin fakePlugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                WoodStripExpRequiresRealStripTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TrinityForge";
                    // Plugin は Namespaced を実装しており、NamespacedKey はこちらを見る。
                    // 返さないと namespace が null で NPE になる。
                    case "namespace" -> "trinityforge";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakePlugin";
                    default -> null;
                });
    }
}
