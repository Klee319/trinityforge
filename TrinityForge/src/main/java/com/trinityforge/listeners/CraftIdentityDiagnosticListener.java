package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.ExternalItemRegistry;
import com.trinityforge.stats.ItemTemplate;
import org.bukkit.Keyed;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;

/**
 * 「統合版だけカスタムアイテムを材料にしたクラフトが通らない」を推測なしで確定させるための診断計装
 * (2026-08-20 / W-158 続報。実サーバ報告「統合版でカスタムアイテムを使ったクラフトをしようとすると
 * 『見た目は同じでも違うアイテムです』が出て一切クラフトできない。Java では出ない。
 * レシピ帳を使わず 3×3 へ手で置いても同じ」)。
 *
 * <h2>なぜ計装が要るのか</h2>
 * 静的解析では<b>どこで死んでいるかを決められない</b>ところまで来ている:
 * <ul>
 *   <li>メッセージを出しているのは {@link CatalogWorkbenchListener#onPrepareCraft} の
 *       <b>{@code ours.isEmpty()} 分岐</b>＝<b>選択されたレシピが TF のカタログレシピではない</b>とき。
 *       ところが圧縮素材や TF 装備は<b>TF のカタログレシピそのもの</b>なので、本来この分岐に入らない。
 *       つまり {@code event.getRecipe()} が期待と違う(別レシピ、あるいは null)。</li>
 *   <li>Geyser の自動クラフトは材料スロットを Bedrock クライアントの {@code ConsumeAction} で決めるが、
 *       <b>手置きでも落ちる</b>との回答が出たのでこの経路だけでは説明が付かない。</li>
 *   <li>GeyserExtra は Bedrock プレイヤー限定で作業台の結果枠を書き換える経路を持っている
 *       ({@code CraftingRecipeHandler})。TF から見ると結果が勝手に変わる。</li>
 * </ul>
 *
 * <h2>読み方</h2>
 * 1 回のクラフト操作につき最大 2 行出る。
 *
 * <pre>
 * [craft-diag] prepare player=X bedrock=yes recipe=&lt;キー or none&gt; owner=&lt;名前空間&gt;
 *              slots=[3:GLOW_BERRIES cmd=200001 id=source_berry owner=arspaper, ...]
 *              result@LOWEST=.. LOW=.. NORMAL=.. HIGH=.. HIGHEST=.. MONITOR=..
 * [craft-diag] craft   player=X bedrock=yes cancelled=no recipe=&lt;キー&gt; result=.. cursor=..
 * </pre>
 *
 * <ul>
 *   <li><b>{@code recipe=none}</b> なら Paper がそもそもレシピを 1 つも解決していない
 *       ＝盤面の型か配置が期待と違う(材料の identity 以前の問題)。</li>
 *   <li><b>{@code recipe=} が TF 以外</b>なら、TF レシピより後勝ちで別レシピが選ばれている。</li>
 *   <li><b>{@code result@} がどこで {@code none} に変わったか</b>で、結果を消したハンドラの優先度帯が分かる
 *       ({@link CatalogWorkbenchListener} と {@link CatalogCraftGateListener} は HIGH、
 *       {@code CatalogVanillaOperationGuardListener} は HIGHEST、
 *       GeyserExtra の {@code CraftingRecipeHandler} は MONITOR)。</li>
 *   <li><b>{@code craft} 行が出ない</b>なら、そもそも結果枠を取り出す経路まで到達していない。</li>
 * </ul>
 *
 * <h2>ログを溢れさせない</h2>
 * <b>盤面に識別付きアイテム(TF カタログ品／{@code ExternalItemRegistry} 登録の他プラグイン品)が
 * 1 つ以上乗っているときだけ</b>出す。素のバニラ素材だけのクラフトでは 1 行も出ない。
 * さらに<b>直前と同じ内容の行は捨てる</b> ── {@link PrepareItemCraftEvent} はマス目を触るたびに
 * 飛ぶので、これが無いと 1 回の配置で同じ行が何十行も出る。
 *
 * <p><b>Java 版も対象にしている</b>のは意図的で、同じログの中で {@code bedrock=yes/no} を
 * 突き合わせられるようにするため。「Java では出ない」という報告の差がどこに現れるかを
 * 1 回のテストで比較できる。
 *
 * <p><b>原因が確定したら外すこと。</b>
 */
public final class CraftIdentityDiagnosticListener implements Listener {

    private final Plugin plugin;
    private final ItemCatalogConfig catalog;

    /** LOWEST / LOW / NORMAL / HIGH / HIGHEST / MONITOR の順に、その時点の結果枠。 */
    private final String[] resultByPriority = new String[6];
    /** 直前に出した prepare 行。同じなら捨てる(マス目を触るたびに飛ぶイベントなので必須)。 */
    private String lastPrepareLine;

    public CraftIdentityDiagnosticListener(Plugin plugin, ItemCatalogConfig catalog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void atLowest(PrepareItemCraftEvent event) {
        java.util.Arrays.fill(resultByPriority, "?");
        resultByPriority[0] = describe(event.getInventory().getResult());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void atLow(PrepareItemCraftEvent event) {
        resultByPriority[1] = describe(event.getInventory().getResult());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void atNormal(PrepareItemCraftEvent event) {
        resultByPriority[2] = describe(event.getInventory().getResult());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void atHigh(PrepareItemCraftEvent event) {
        resultByPriority[3] = describe(event.getInventory().getResult());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void atHighest(PrepareItemCraftEvent event) {
        resultByPriority[4] = describe(event.getInventory().getResult());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void atMonitor(PrepareItemCraftEvent event) {
        resultByPriority[5] = describe(event.getInventory().getResult());
        report(event);
    }

    private void report(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        if (!hasIdentifiedItem(matrix)) {
            return; // 素のバニラ素材だけ — 今回の調査対象ではない
        }
        Player player = viewerOf(event);
        Recipe recipe = event.getRecipe();
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < matrix.length; i++) {
            ItemStack item = matrix[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (slots.length() > 0) {
                slots.append(", ");
            }
            slots.append(i).append(':').append(item.getType()).append(" x").append(item.getAmount());
            Integer cmd = customModelDataOf(item);
            slots.append(" cmd=").append(cmd == null ? "-" : cmd);
            slots.append(" id=").append(identityOf(item).orElse("-"));
            slots.append(" owner=").append(ExternalItemRegistry.pluginSourceOf(item.getType(), cmd).orElse("-"));
        }
        String line = "[craft-diag] prepare"
                + " player=" + (player == null ? "?" : player.getName())
                + " bedrock=" + (player != null && isBedrock(player) ? "yes" : "no")
                + " recipe=" + recipeKeyOf(recipe)
                + " grid=" + matrix.length
                + " slots=[" + slots + "]"
                + " result@LOWEST=" + resultByPriority[0]
                + " LOW=" + resultByPriority[1]
                + " NORMAL=" + resultByPriority[2]
                + " HIGH=" + resultByPriority[3]
                + " HIGHEST=" + resultByPriority[4]
                + " MONITOR=" + resultByPriority[5];
        if (line.equals(lastPrepareLine)) {
            return;
        }
        lastPrepareLine = line;
        plugin.getLogger().info(line);
    }

    /** 結果枠を実際に取り出せたか。prepare 行だけでは「取り出しが死んでいる」型と区別できない。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraft(CraftItemEvent event) {
        if (!hasIdentifiedItem(event.getInventory().getMatrix())) {
            return;
        }
        Player player = event.getWhoClicked() instanceof Player p ? p : null;
        plugin.getLogger().info("[craft-diag] craft"
                + " player=" + (player == null ? "?" : player.getName())
                + " bedrock=" + (player != null && isBedrock(player) ? "yes" : "no")
                + " cancelled=" + (event.isCancelled() ? "yes" : "no")
                + " action=" + event.getAction()
                + " click=" + event.getClick()
                + " recipe=" + recipeKeyOf(event.getRecipe())
                + " result=" + describe(event.getInventory().getResult())
                + " cursor=" + describe(event.getCursor()));
    }

    private boolean hasIdentifiedItem(ItemStack[] matrix) {
        if (matrix == null) {
            return false;
        }
        for (ItemStack item : matrix) {
            if (item != null && !item.getType().isAir() && identityOf(item).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code CatalogWorkbenchListener#catalogIdentityOf} と同じ手順で識別する
     * (PDC の catalog id → カタログの material+CMD → {@code ExternalItemRegistry})。
     * 向こうを public にしないのは、診断が本番判定の形を変えないようにするため。
     * <b>ここを変えると診断の意味が変わる</b>ので、向こうを直したらここも合わせる。
     */
    private Optional<String> identityOf(ItemStack item) {
        Integer cmd = customModelDataOf(item);
        if (cmd == null) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        Optional<String> stamped = ItemData.of(meta).catalogId();
        if (stamped.isPresent()) {
            return stamped;
        }
        Optional<String> catalogId = CatalogIdentity.find(catalog, item.getType(), cmd).map(ItemTemplate::id);
        if (catalogId.isPresent()) {
            return catalogId;
        }
        return ExternalItemRegistry.find(item.getType(), cmd).map(ExternalItemRegistry.Definition::id);
    }

    private static Integer customModelDataOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return DerivedItemStats.customModelDataOf(item.getItemMeta());
    }

    private static String recipeKeyOf(Recipe recipe) {
        if (recipe == null) {
            return "none";
        }
        return recipe instanceof Keyed keyed ? keyed.getKey().toString() : recipe.getClass().getSimpleName();
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "none";
        }
        Integer cmd = customModelDataOf(stack);
        return stack.getType() + "x" + stack.getAmount() + (cmd == null ? "" : "(cmd=" + cmd + ")");
    }

    private static Player viewerOf(PrepareItemCraftEvent event) {
        if (event.getView() != null && event.getView().getPlayer() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** Floodgate の UUID は {@code new UUID(0, xuid)} なので上位 64bit が 0。 */
    private static boolean isBedrock(Player player) {
        return player.getUniqueId() != null && player.getUniqueId().getMostSignificantBits() == 0L;
    }
}
