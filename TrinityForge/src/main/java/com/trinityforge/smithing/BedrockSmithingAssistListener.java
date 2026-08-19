package com.trinityforge.smithing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmithingTrimRecipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 統合版(Bedrock)プレイヤーが鍛冶台の base スロットへアイテムを置けない問題への対処
 * (2026-08-19 実サーバ報告「統合版でCMD付きアイテムをネザライト化できない／置こうとするとちらつく」)。
 *
 * <p><b>何が起きているのか</b>: 鍛冶台のスロットが何を受け付けるかは、Java 側では
 * {@code RecipePropertySet}(=読み込み済みスミスレシピの ingredient から毎回組み直される)で決まり、
 * {@code CatalogRecipeRegistrar#registerNetheriteOne} が base=BOW/CROSSBOW/TRIDENT/MACE の
 * {@link org.bukkit.inventory.SmithingTransformRecipe} を登録することで置けるようになっている。
 * ところが<b>統合版クライアントは鍛冶台のスロット判定を自前で持っており、サーバから届いたレシピでは
 * 広がらない</b>。GeyserMC/Geyser#4706 が "Can't Fix / Missing Client Feature" として閉じているとおり、
 * Geyser 側でも直せない。プレイヤーからは「置けない」「置こうとすると一瞬増殖して戻る」に見える
 * (クライアント予測が拒否 → サーバの正しい状態が送り直される往復)。
 *
 * <p><b>なぜ独自 GUI を作らないのか</b>: 鍛冶台の {@link org.bukkit.event.inventory.PrepareSmithingEvent}
 * には TF 以外にも判定が乗っている ── TF の
 * {@code CatalogSmithingListener}(カタログ宣言済みネザライト化 / W-51 の品質再刻印) と
 * {@code CatalogCraftGateListener}(スキルツリーの {@code recipe:<id>} ゲート) と
 * {@code CatalogVanillaOperationGuardListener}(宣言外のカタログ品を止める) に加え、
 * ArsPaper の {@code SmithingTableUnlockGate}(perk ゲート) と {@code CustomItemListener}
 * (Ars カスタム防具のトリムで PDC を保全)、EliteMobs の {@code PreventUpgradeDiamondToNetherite}
 * (エリート装備のネザライト化禁止) の計 7 つ。チェスト型の独自 GUI で結果を自前計算すると
 * <b>この 7 つを全部バイパスし、3 プラグインにまたがる二重管理になる</b>。
 *
 * <p><b>この実装がやること</b>: 統合版プレイヤーが<b>アイテムを持った状態で</b>鍛冶台を右クリックしたら、
 * その操作を横取りして {@link org.bukkit.inventory.MenuType#SMITHING} で<b>本物の鍛冶台</b>のビューを組み、
 * base スロットへ<b>サーバ側から</b>アイテムを差し込んでから開く。
 * サーバ側の {@code Inventory#setItem} はクライアントのスロット判定を一切通らないので置ける。
 * 以降は完全にバニラの経路で、
 * {@code ItemCombinerMenu} の入力コンテナは {@code setChanged()} で {@code slotsChanged()} →
 * {@code createResult()} を呼ぶため、<b>差し込みでも {@code PrepareSmithingEvent} は正規に発火する</b>。
 * つまり上の 7 つの判定はすべてそのまま効き、トリムもバニラのネザライト強化も Java と同一になる。
 *
 * <p><b>結果の取り出しは統合版でも通る</b>(2026-08-19 に Geyser のソースで確認):
 * {@code SmithingInventoryTranslator} は Java 0/1/2/3 を Bedrock 53/51/52/50 へ写すだけで、
 * {@code shouldRejectItemPlace} も {@code getSlotType} も上書きしていない。さらに
 * {@code InventoryTranslator#translateRequest} は汎用コンテナでの {@code CRAFT_RECIPE} を
 * <b>break で読み飛ばす</b>ので、結果スロットからの取り出しは<b>ただの TAKE</b> として
 * Java 側スロット 3 のクリックに変換される。<b>クライアントがそのレシピを知っている必要はない</b>
 * (＝レシピ表を統合版へ表現できないという {@code custom:} 素材側の制約はここには効かない)。
 *
 * <p><b>統合版の判定に Floodgate API を使わない理由</b>: Floodgate の UUID は
 * {@code new UUID(0, xuid)} で組まれるので上位 64bit が必ず 0 になり、UUID だけで判別できる
 * (Java の正規 UUID は version 4 = 上位 64bit に version/乱数が入るので 0 にならない)。
 * TF の {@code paper-plugin.yml} は {@code softdepend:} を黙って捨てるため、
 * 依存を増やさずに済むこの判定を採る。Java 版から動作確認するための逃げ道として
 * {@link #OVERRIDE_PERMISSION} を持つプレイヤーも対象に含める。
 */
public final class BedrockSmithingAssistListener implements Listener {

    /** Java 版クライアントからこの補助を動作確認するための権限(既定 false)。 */
    public static final String OVERRIDE_PERMISSION = "trinityforge.smithing.bedrock-assist";

    /** base スロット(Bukkit の {@link SmithingInventory} は 0=型 / 1=素材 / 2=追加素材 / 3=結果)。 */
    private static final int BASE_SLOT = 1;

    private static final Component PLACED_MESSAGE = Component.text(
            "手に持っていたアイテムを鍛冶台にセットしました", NamedTextColor.GREEN);

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        // 他プラグインが既にブロック操作を止めているなら横取りしない。
        // ここで ignoreCancelled を使わないのは、PlayerInteractEvent の isCancelled() が
        // useInteractedBlock()==DENY と等価で、RIGHT_CLICK_AIR が生成時点で常に「キャンセル済み」
        // になるという既知の罠(CatalogVanillaOperationGuardListener の javadoc 参照)を避けるため。
        if (event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SMITHING_TABLE) {
            return;
        }
        Player player = event.getPlayer();
        // スニーク右クリックは「手に持ったブロックを設置する」バニラ挙動。奪ってはいけない。
        if (player.isSneaking() || !isAssisted(player)) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!shouldAssist(held, smithingRecipes())) {
            return;
        }

        event.setCancelled(true);
        // checkReachable(false) は非推奨の openSmithingTable(loc, force=true) と同じ意図
        // (クリックしたブロックが本当に鍛冶台かの再確認を省く)。
        InventoryView view = MenuType.SMITHING.builder()
                .location(block.getLocation())
                .checkReachable(false)
                .build(player);
        if (!(view.getTopInventory() instanceof SmithingInventory)) {
            return; // 想定外。何も奪わない(手持ちはそのまま)
        }
        Inventory top = view.getTopInventory();
        // 開く前に差し込む。開いた後だと空→充填の 2 パケットになり、
        // 今回直そうとしている「ちらつき」を自分で作ることになる。
        top.setItem(BASE_SLOT, held.clone());
        // 手持ちを消すのは openInventory が成功してから。ここで先に消すと
        // 開けなかったときにアイテムが宙に浮いたビューごと消える。
        player.openInventory(view);
        player.getInventory().setItemInMainHand(null);
        player.updateInventory();
        player.sendActionBar(PLACED_MESSAGE);
    }

    /** この補助の対象か(統合版プレイヤー、または動作確認用の権限保持者)。 */
    private static boolean isAssisted(Player player) {
        return isBedrockId(player.getUniqueId()) || player.hasPermission(OVERRIDE_PERMISSION);
    }

    /**
     * Floodgate 由来の UUID か。Floodgate は {@code new UUID(0, xuid)} で組むので上位 64bit が 0 になる。
     * Java 版の正規 UUID(version 4)は上位 64bit に version ビットと乱数が入るので 0 にならない。
     */
    static boolean isBedrockId(UUID uuid) {
        return uuid != null && uuid.getMostSignificantBits() == 0L;
    }

    /**
     * 手持ちを base スロットへ差し込んでよいか。判定は<b>ハードコードした一覧ではなく
     * 登録済みスミスレシピの ingredient から引く</b> ── バニラが素材を増やしても、
     * TF/ArsPaper がレシピを足しても、そのまま追随する。
     *
     * <p>条件は 2 つ。
     * <ol>
     *   <li><b>どれかのレシピの base に一致する</b>こと。無関係な物(石ブロック等)を持ったまま
     *       鍛冶台を開いただけの人から手持ちを奪わないため。</li>
     *   <li><b>型スロット・追加素材スロットの物ではない</b>こと。鍛冶型やネザライトインゴット、
     *       トリム素材は統合版でも普通に置けている(＝バニラのネザライト強化とトリムが今動いている
     *       事実がその証拠)ので、base へ差し込むと<b>正しい操作を壊す</b>。</li>
     * </ol>
     *
     * @param held    メインハンドのスタック
     * @param recipes 登録済みのスミス台レシピ
     */
    static boolean shouldAssist(ItemStack held, List<SmithingRecipe> recipes) {
        if (held == null || held.getType().isAir()) {
            return false;
        }
        boolean matchesBase = false;
        for (SmithingRecipe recipe : recipes) {
            RecipeChoice addition = recipe.getAddition();
            if (addition != null && addition.test(held)) {
                return false; // 追加素材スロットに置くべき物
            }
            RecipeChoice template = templateChoiceOf(recipe);
            if (template != null && template.test(held)) {
                return false; // 型スロットに置くべき物
            }
            RecipeChoice base = recipe.getBase();
            if (base != null && base.test(held)) {
                matchesBase = true;
            }
        }
        return matchesBase;
    }

    private static RecipeChoice templateChoiceOf(SmithingRecipe recipe) {
        if (recipe instanceof SmithingTransformRecipe transform) {
            return transform.getTemplate();
        }
        if (recipe instanceof SmithingTrimRecipe trim) {
            return trim.getTemplate();
        }
        return null;
    }

    /**
     * 登録済みスミス台レシピの一覧。走査に失敗した場合は空を返す(=補助を行う側に倒す)。
     * 鍛冶台を右クリックした瞬間にしか呼ばれないのでキャッシュは持たない
     * ({@code /trinityforge reload} でレシピが入れ替わってもズレないことを優先する)。
     */
    private static List<SmithingRecipe> smithingRecipes() {
        List<SmithingRecipe> found = new ArrayList<>();
        try {
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                Recipe recipe = it.next();
                if (recipe instanceof SmithingRecipe smithing) {
                    found.add(smithing);
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        return found;
    }
}
