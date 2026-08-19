package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.SmithingGimmickConfig;
import com.trinityforge.smithing.FurnaceSmeltPolicy;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * かまど精錬の所有者追跡 + 「精錬速度」({@code feature:furnace-smelt-speed}, smithing.yml A-1/A-2/A-3)
 * / 「精錬ボーナス」({@code feature:furnace-smelt-bonus}, B-1/B-2/B-3) の両consumer(2026-07-25)。
 *
 * <p><b>横断制約(ユーザー明示)</b>: 実行した本人(=精錬物を入れた人)のステータスだけが乗る。近くに
 * 居るだけの他人には一切乗らない。結果物を後から拾った人にも乗らない。
 *
 * <p><b>所有者追跡の設計</b>({@code NativeSkillExperienceListener} の醸造スタンド実装(240-312行付近、
 * 読み取り専用)と同じ様式): かまどはブロックなのでプレイヤーを持たない
 * {@link FurnaceSmeltEvent}/{@link FurnaceStartSmeltEvent} からは誰の恩恵か判定できない。そこで
 * 精錬物スロット(スロット0)へ手で(または shift-click で)アイテムを入れた瞬間に
 * {@link InventoryClickEvent} で所有者UUID+モード(manual)を{@link Furnace}のTileState PDCへ刻む。
 * ホッパー投入は{@link InventoryMoveItemEvent}(destinationがFurnace)でモードをautoへ上書きする —
 * これは手動投入の有無に関わらず毎回上書きするため、「1回だけ手で触ってあとは放置」で無限に
 * manual倍率を稼ぐexploitを自動的に閉じる(醸造EXPのauto_multと同じ設計)。
 *
 * <p><b>ホッパー自動精錬の扱い</b>: {@link SmithingGimmickConfig#autoModeMultiplier}(既定0.25)で
 * 速度短縮/ボーナス確率の両方を減衰させる。0にはしていない(醸造EXPのauto_multが0.25である前例に
 * 揃え、「無人放置が完全に無価値ではないが手動より明確に劣る」バランスにするため)。
 *
 * <p><b>tier→%変換(2026-07-28)</b>: {@code dedicatedEffects.valueMax} が返すのは
 * {@code feature:furnace-smelt-speed/bonus}(SCALE)の tier番号(1/2/3)であり、そのまま%として使えない。
 * {@link SmithingGimmickConfig#smeltSpeedPercent(int)} / {@link SmithingGimmickConfig#smeltBonusPercent(int)}
 * で tier→% を解決してから {@link FurnaceSmeltPolicy#effectivePercent} へ渡す。
 *
 * <p><b>属性クリアのタイミング</b>: {@link #onSmelt}実行の1tick後、かまどの精錬物スロットが
 * 空になっていれば(=この所有者が入れた分を精錬し終えた)所有者/モードをクリアする。かまどは
 * 1回の燃料で連続して複数個を精錬するため、精錬物スロットにまだアイテムが残っている間は
 * クリアしない — これにより連続精錬中の2個目以降にもバフが乗り続ける(醸造(1回で完結)とは
 * 異なる挙動を意図的に採用)。PDCはBlockState(TileState)に保存するため、チャンクアンロードや
 * サーバー再起動を跨いでも維持される。
 */
public final class FurnaceSmeltListener implements Listener {

    private static final int SMELTING_SLOT = 0;
    private static final String MODE_MANUAL = "manual";
    private static final String MODE_AUTO = "auto";
    private static final String EFFECT_SPEED = "furnace-smelt-speed";
    private static final String EFFECT_BONUS = "furnace-smelt-bonus";

    private final Plugin plugin;
    private final DedicatedEffectsConfig dedicatedEffects;
    private final SmithingGimmickConfig gimmickConfig;
    private final NamespacedKey ownerKey;
    private final NamespacedKey modeKey;

    public FurnaceSmeltListener(Plugin plugin, DedicatedEffectsConfig dedicatedEffects,
                                 SmithingGimmickConfig gimmickConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
        this.ownerKey = new NamespacedKey(plugin, "furnace_owner");
        this.modeKey = new NamespacedKey(plugin, "furnace_mode");
    }

    /**
     * 精錬物スロット(0)へ手で(または shift-click で)アイテムを入れた瞬間だけ所有者+manualを刻む。
     * ブレアイングスタンドの{@code rememberBrewer}と同じ「実際にスロットへアイテムが移動した」判定:
     * かまどGUIが開いている間のクリック全てではなく、スロット0への挿入操作のみを対象にする。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Furnace furnace)) return;
        ItemStack inserted = manualInsertionCandidate(event, player);
        boolean shiftedFromPlayer = event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        if (!canEnterSmeltingSlot(furnace, inserted, shiftedFromPlayer)) return;

        stamp(furnace, player.getUniqueId(), MODE_MANUAL);
    }

    /**
     * InventoryClickEvent exposes the pre-click state. Direct placement therefore comes from the cursor
     * (not currentItem), while shift-click comes from the clicked player-inventory slot.
     */
    private ItemStack manualInsertionCandidate(InventoryClickEvent event, Player player) {
        InventoryAction action = event.getAction();
        int rawSlot = event.getRawSlot();
        if (rawSlot == SMELTING_SLOT) {
            return switch (action) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
                case HOTBAR_SWAP -> {
                    int hotbarButton = event.getHotbarButton();
                    yield hotbarButton >= 0
                            ? player.getInventory().getItem(hotbarButton)
                            : player.getInventory().getItemInOffHand();
                }
                default -> null;
            };
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && rawSlot >= topSize) {
            return event.getCurrentItem();
        }
        return null;
    }

    /** Rejects fuel/non-smeltable shift-clicks and clicks that cannot add to the current input stack. */
    private boolean canEnterSmeltingSlot(Furnace furnace, ItemStack inserted, boolean verifySmeltable) {
        if (inserted == null || inserted.getType().isAir()) {
            return false;
        }
        // A direct PLACE_* action already names the furnace input as its accepted destination. A
        // shift-click originates in the player inventory and could instead target the fuel slot, so
        // only that ambiguous path needs the recipe-aware canSmelt check.
        if (!verifySmeltable) {
            return true;
        }
        if (!furnace.getInventory().canSmelt(inserted)) {
            return false;
        }
        ItemStack current = furnace.getInventory().getSmelting();
        return current == null || current.getType().isAir()
                || (current.isSimilar(inserted) && current.getAmount() < current.getMaxStackSize());
    }

    /** ホッパー等の自動投入先がかまどなら、投入先スロットに関わらずモードをautoへ上書きする。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getDestination().getHolder();
        if (!(holder instanceof Furnace furnace)) return;
        UUID owner = readOwner(furnace);
        stamp(furnace, owner, MODE_AUTO);
    }

    /** 精錬速度: {@link FurnaceStartSmeltEvent#setTotalCookTime} で1個の精錬にかかる時間を短縮する。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStartSmelt(FurnaceStartSmeltEvent event) {
        if (!(event.getBlock().getState() instanceof Furnace furnace)) return;
        UUID ownerId = readOwner(furnace);
        if (ownerId == null) return;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) return; // オフライン中は加点しない(fail-soft、常人の許容仕様)。

        OptionalDouble tier = dedicatedEffects.valueMax(owner, EFFECT_SPEED);
        if (tier.isEmpty()) return;
        double rawPercent = gimmickConfig.smeltSpeedPercent((int) tier.getAsDouble());
        boolean automated = MODE_AUTO.equals(readMode(furnace));
        double effectivePercent = FurnaceSmeltPolicy.effectivePercent(
                rawPercent, automated, gimmickConfig.autoModeMultiplier());
        if (effectivePercent <= 0.0) return;

        event.setTotalCookTime(FurnaceSmeltPolicy.reducedCookTime(event.getTotalCookTime(), effectivePercent));
    }

    /** 精錬ボーナス: 確率で追加の結果アイテムをかまどの結果スロットへ積む(入り切らない分だけ地面へ)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof Furnace furnace)) return;
        UUID ownerId = readOwner(furnace);
        if (ownerId != null) {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null) {
                rollExtraDrop(owner, furnace, block, event.getResult());
            }
        }
        // 処理完了時のクリア判定(1tick後、精錬物スロットが空ならクリア)。連続精錬中(スロットに
        // まだアイテムが残っている間)はクリアしない — 醸造(1回で完結)とは異なる挙動。
        plugin.getServer().getScheduler().runTask(plugin, () -> clearIfIdle(block));
    }

    private void rollExtraDrop(Player owner, Furnace furnace, Block block, ItemStack result) {
        if (result == null || result.getType().isAir()) return;
        OptionalDouble tier = dedicatedEffects.valueMax(owner, EFFECT_BONUS);
        if (tier.isEmpty()) return;
        double rawPercent = gimmickConfig.smeltBonusPercent((int) tier.getAsDouble());
        boolean automated = MODE_AUTO.equals(readMode(furnace));
        double chance = FurnaceSmeltPolicy.effectivePercent(
                rawPercent, automated, gimmickConfig.autoModeMultiplier());
        if (chance <= 0.0) return;

        // 1.0%超 = 保証1回 + 端数分の追加抽選(GatheringExtraDropListenerと同じ丸め方)。
        double fraction = chance / 100.0;
        int guaranteed = (int) Math.floor(fraction);
        double remainder = fraction - guaranteed;
        int extraCopies = guaranteed;
        if (remainder > 0.0 && ThreadLocalRandom.current().nextDouble() < remainder) {
            extraCopies += 1;
        }
        if (extraCopies <= 0) return;

        // 2026-07-30 実サーバ報告「精錬ボーナスで増えた分がかまどから吐き出される」への対応。
        // 以前は無条件に dropItemNaturally していたため、ボーナス分だけが地面に散らばり、
        // ホッパー回収の構成では取りこぼしになっていた。結果スロットへ積むのが本来の挙動。
        //
        // 次tickに回すのは、この時点(FurnaceSmeltEvent)ではバニラがまだ本来の1個を結果スロットへ
        // 入れていないため。ここで先に積むと、バニラ側のマージでスタック上限を超えた分が
        // 黙って消える(かまどの結果スロットは上限を超えた投入を単純に切り捨てる)。
        ItemStack bonus = result.clone();
        final int copies = extraCopies;
        plugin.getServer().getScheduler().runTask(plugin, () -> depositExtra(block, bonus, copies));
    }

    /**
     * ボーナス分をかまどの結果スロットへ積む。
     *
     * <p>2026-08-01「かまどが満杯になってもアイテムを吐き出す」への対応:
     * <b>かまどが健在なら、収まらなかった分は地面へ落とさず破棄する。</b>
     * バニラは満杯のかまどでは精錬自体を止める({@code AbstractFurnaceBlockEntity#canBurn} が
     * 結果スロットの上限到達で false)ため、破棄されるのは「上限に達したちょうどその1回」の
     * 最大1個だけで、ホッパー回収の構成では散らばりの方が実害が大きい。
     * 2026-07-30 の「消滅させない」方針をここで反転させている。
     *
     * <p>ただし次tickまでにかまどが壊された/別ブロックになった場合は、既に付与が確定した分を
     * 消さないために従来どおり全数を地面へ落とす。
     *
     * <p>package-private なのはテストのため。{@link #onSmelt} からは次tickのスケジューラ越しに
     * 呼ばれるので、MockBukkit のスケジューラ進行に依存せずこの分岐だけを直接検証できるようにしてある
     * ({@link #clearIfIdle(org.bukkit.block.Furnace)} と同じ理由・同じ方針)。
     */
    void depositExtra(Block block, ItemStack bonus, int extraCopies) {
        int remaining = extraCopies;
        if (block.getState() instanceof Furnace furnace) {
            ItemStack current = furnace.getInventory().getResult();
            if (current == null || current.getType().isAir()) {
                int accepted = Math.min(remaining, bonus.getMaxStackSize());
                ItemStack placed = bonus.clone();
                placed.setAmount(accepted);
                furnace.getInventory().setResult(placed);
                remaining -= accepted;
            } else if (current.isSimilar(bonus)) {
                int room = Math.max(0, current.getMaxStackSize() - current.getAmount());
                int accepted = Math.min(remaining, room);
                if (accepted > 0) {
                    current.setAmount(current.getAmount() + accepted);
                    furnace.getInventory().setResult(current);
                    remaining -= accepted;
                }
            }
            return; // かまどが健在なら溢れた分は吐き出さない(地面に散らばらせない)。
        }
        for (int i = 0; i < remaining; i++) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), bonus.clone());
        }
    }

    /** 精錬物スロットが空(=この所有者の投入分を精錬し終えた)なら所有者/モードをクリアする。 */
    private void clearIfIdle(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof Furnace furnace)) return;
        clearIfIdle(furnace);
    }

    /** Package-private for direct unit testing (bypasses the {@link Block#getState()} round-trip). */
    void clearIfIdle(Furnace furnace) {
        ItemStack smelting = furnace.getInventory().getSmelting();
        if (!isInputExhausted(smelting)) {
            return; // 連続精錬中: まだ精錬物が残っているのでクリアしない。
        }
        furnace.getPersistentDataContainer().remove(ownerKey);
        furnace.getPersistentDataContainer().remove(modeKey);
        furnace.update();
    }

    /** True when the smelting-input slot has nothing left to process. */
    static boolean isInputExhausted(ItemStack smelting) {
        return smelting == null || smelting.getType().isAir() || smelting.getAmount() <= 0;
    }

    /**
     * 所有者/モードの刻印。<b>{@code furnace.update()} は「かまどの中身をスナップショットへ巻き戻す」
     * 操作でもある</b>ので、呼ぶ場所を選ぶ（{@code CraftBlockEntityState#update()} は PDC だけでなく
     * スナップショットの NBT を丸ごと実体へ load し、{@code loadAdditional} は {@code items} を
     * 新しいリストへ差し替える。詳細は {@link BrewOwnership} のクラス javadoc と
     * {@code docs/agent-context/common-traps.md}）。
     *
     * <p>ここが安全なのは、呼び口が {@code InventoryClickEvent} /
     * {@code InventoryMoveItemEvent} / {@code FurnaceStartSmeltEvent} の<b>いずれもバニラが
     * 中身を書き換える前</b>だから — スナップショット＝現在の中身なので書き戻しが実質 no-op になる。
     * <b>{@code FurnaceSmeltEvent}（精錬完了）の最中に呼んではいけない</b>。
     * そちらは {@link #onSmelt} が {@code runTask} で次 tick へ回し、
     * {@link #clearIfIdle(org.bukkit.block.Block)} で {@code BlockState} を取り直している。
     */
    private void stamp(Furnace furnace, UUID owner, String mode) {
        if (owner != null) {
            furnace.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        }
        furnace.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode);
        furnace.update();
    }

    private UUID readOwner(Furnace furnace) {
        String raw = furnace.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private String readMode(Furnace furnace) {
        return furnace.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
    }
}
