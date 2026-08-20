package com.trinityforge.active;

import com.trinityforge.config.domains.DiggingGimmickConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.mining.DiggingHasteActiveSkill;
import com.trinityforge.mining.HasteActiveSkill;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「対象の道具から持ち替えたら効果を強制終了する」(2026-08-18 ユーザー確定要件)の挙動を固定する。
 *
 * <p>直っている不具合: 初版は {@code haste-active-mining} と {@code haste-active-digging} で
 * CT バケツを共有するだけだったので、<b>ツルハシで発動 → シャベルへ持ち替え</b>で効果を横流しでき、
 * シャベル側のノードを解放していないプレイヤーでも掘削がヘイストで速くなった。
 *
 * <p>{@code plugin} を注入しない({@code null})と {@link ToolBoundEffectListener} は次tickへ回さず
 * 即時判定する縮退経路になるので、スケジューラを回さずに判定だけを検証できる。
 */
class ToolBoundEffectListenerTest {

    private ServerMock server;
    private PlayerMock player;
    private ActiveSkillRegistry registry;
    private ActiveEffectSessions sessions;
    private ToolBoundEffectListener listener;
    private HasteActiveSkill mining;
    private DiggingHasteActiveSkill digging;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        registry = new ActiveSkillRegistry();
        mining = new HasteActiveSkill(new MiningGimmickConfig());
        digging = new DiggingHasteActiveSkill(new DiggingGimmickConfig());
        registry.register(mining);
        registry.register(digging);
        sessions = new ActiveEffectSessions();
        listener = new ToolBoundEffectListener(null, registry, sessions, new FeedbackLayer());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack taggedTool(Material material, String useSkill) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setUseRequirement(useSkill, 0);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 採掘側の発動を再現する(ディスパッチャがやることと同じ: 効果付与 + セッションを開く)。 */
    private int activateMining(int tier) {
        player.getInventory().setItemInMainHand(taggedTool(Material.DIAMOND_PICKAXE, "MINING"));
        mining.activate(player, new ActiveContext(tier, player.getInventory().getItemInMainHand()));
        sessions.open(player.getUniqueId(), mining.id(), tier,
                System.currentTimeMillis() + mining.effectDurationTicks(tier) * 50L);
        return new MiningGimmickConfig().hasteAmplifier(tier);
    }

    @Test
    @DisplayName("シャベルへ持ち替えると採掘の高速破壊は即終了する(効果の横流しができない)")
    void switchingToAnotherToolEndsTheEffect() {
        int amplifier = activateMining(1);
        assertTrue(player.hasPotionEffect(PotionEffectType.HASTE), "発動で HASTE が乗っていない(前提の破綻)");
        assertEquals(amplifier, player.getPotionEffect(PotionEffectType.HASTE).getAmplifier());

        // シャベル(use-skill=DIGGING)へ持ち替える。
        player.getInventory().setItemInMainHand(taggedTool(Material.DIAMOND_SHOVEL, "DIGGING"));
        listener.endIfToolLeft(player);

        assertFalse(player.hasPotionEffect(PotionEffectType.HASTE),
                "持ち替えても効果が残っている。これが残ると解放していない側のツリーの速度上昇を"
                        + "横流しできる(CT共有では止められなかった穴)");
        assertTrue(sessions.active(player.getUniqueId(), System.currentTimeMillis()).isEmpty(),
                "セッションを閉じていない(次の持ち替えでも余計な照合が走る)");
    }

    @Test
    @DisplayName("同じスキルの別ツール(素手・無タグ)へ持ち替えても終了する")
    void switchingToAnUntaggedItemEndsTheEffect() {
        activateMining(1);
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        listener.endIfToolLeft(player);
        assertFalse(player.hasPotionEffect(PotionEffectType.HASTE));
    }

    @Test
    @DisplayName("対象ツールを持ち続けている間は終了しない")
    void keepingTheToolKeepsTheEffect() {
        activateMining(1);
        // 別のツルハシ(同じ use-skill)へ持ち替えるのは「対象ツールのまま」なので終了しない。
        player.getInventory().setItemInMainHand(taggedTool(Material.NETHERITE_PICKAXE, "mining"));
        listener.endIfToolLeft(player);
        assertTrue(player.hasPotionEffect(PotionEffectType.HASTE),
                "同じスキルのツールへ持ち替えただけで効果が切れている(大文字小文字の比較漏れ?)");
    }

    @Test
    @DisplayName("自分が付けていない HASTE は剥がさない(ビーコン/管理コマンドの誤爆防止)")
    void foreignHasteIsNeverRemoved() {
        int tier = 1;
        activateMining(tier);
        // 管理コマンド相当の無期限 HASTE で上書きする。amplifier も別値。
        player.removePotionEffect(PotionEffectType.HASTE);
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, PotionEffect.INFINITE_DURATION, 200));

        player.getInventory().setItemInMainHand(taggedTool(Material.DIAMOND_SHOVEL, "DIGGING"));
        listener.endIfToolLeft(player);

        assertTrue(player.hasPotionEffect(PotionEffectType.HASTE),
                "他ソース由来の HASTE を剥がしている(W-54 と同型の事故)");
        assertEquals(200, player.getPotionEffect(PotionEffectType.HASTE).getAmplifier());
    }

    @Test
    @DisplayName("効果が自然に切れた後の持ち替えでは何も触らない")
    void expiredSessionIsIgnored() {
        sessions.open(player.getUniqueId(), mining.id(), 1, System.currentTimeMillis() - 1L);
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 100, 0));
        player.getInventory().setItemInMainHand(taggedTool(Material.DIAMOND_SHOVEL, "DIGGING"));

        listener.endIfToolLeft(player);

        assertTrue(player.hasPotionEffect(PotionEffectType.HASTE),
                "期限切れセッションで cancelEffect まで進んでいる");
    }

    @Test
    @DisplayName("掘削側も同じ規約(シャベル→ツルハシで終了)")
    void diggingSideBehavesTheSameWay() {
        int tier = 1;
        player.getInventory().setItemInMainHand(taggedTool(Material.DIAMOND_SHOVEL, "DIGGING"));
        digging.activate(player, new ActiveContext(tier, player.getInventory().getItemInMainHand()));
        sessions.open(player.getUniqueId(), digging.id(), tier,
                System.currentTimeMillis() + digging.effectDurationTicks(tier) * 50L);
        assertTrue(player.hasPotionEffect(PotionEffectType.HASTE));

        player.getInventory().setItemInMainHand(taggedTool(Material.DIAMOND_PICKAXE, "MINING"));
        listener.endIfToolLeft(player);

        assertFalse(player.hasPotionEffect(PotionEffectType.HASTE));
    }
}
