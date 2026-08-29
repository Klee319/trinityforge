package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code /tf settings} — 称号/パーティクル選択 + 「他人の演出を非表示」トグル
 * (2026-07-23-stat-gate-overhaul §6.1)。未保有の報酬はロック表示で選択不可。
 *
 * <p>称号・パーティクルはそれぞれ2行ぶんの枠(解除ボタン + 15件 + ページ送り2個)を使い、
 * 15件を超えたらページ送りで全件に届く。<b>件数で黙って切ってはいけない</b> —
 * 2026-08-17 まで1行8件で打ち切っており、出荷 special-rewards.yml の称号20件のうち
 * 12件が GUI から永久に見えなかった(エラーも警告も出ないので気づけない)。
 *
 * <p>最下段はパーティクルシードの<b>閲覧専用</b>一覧(2026-08-25 / W-215)。
 * こちらは装備するものではなく道具へ刻印するものなので、クリックしても何も起きない。
 */
public final class SettingsGui implements Listener {

    private static final int SIZE = 54;
    private static final int TOGGLE_SLOT = 4;
    /**
     * メール受信箱への入口(2026-08-19 / W-155)。ユーザー要望「tfのメニューからも受け取れるように」。
     * 統合メニューは W-28 で廃止済みなので、いま全員が開ける画面の中ではここが一番近い。
     */
    private static final int MAIL_SLOT = 0;
    // 2026-07-25 gather-rework-active-framework §2 B-2: 採取プレイヤートグル4種、同じ0行目の空きスロットへ。
    private static final int GATHER_TOGGLE_VEIN_MINING_SLOT = 5;
    private static final int GATHER_TOGGLE_TREE_FELL_SLOT = 6;
    private static final int GATHER_TOGGLE_AUTO_REPLANT_SLOT = 7;
    private static final int GATHER_TOGGLE_AREA_HARVEST_SLOT = 8;
    // ページ送り (2026-08-17 ユーザー報告「称号とパーティクルの9個目以降が表示されない」)。
    // 以前は1行(解除ボタン+8件)しか描いておらず、出荷 special-rewards.yml の称号20件のうち
    // 12件が GUI から永久に見えなかった。上限で黙って切らず、ページで送る。
    private static final int TITLE_ROW_START = 9;      // 1〜2行目 (9〜26)
    private static final int PARTICLE_ROW_START = 27;  // 3〜4行目 (27〜44)
    /** 1ページあたりの選択肢数(解除ボタンとページ送り2個を除いた残り)。 */
    private static final int OPTIONS_PER_PAGE = 15;
    private static final int PREV_PAGE_OFFSET = 16;
    private static final int NEXT_PAGE_OFFSET = 17;

    /**
     * パーティクルシードの一覧(2026-08-25 / W-215、実サーバ報告
     * 「現状何のパーティクルシードがあるかをGUIで確認するすべがない」)。
     *
     * <p>称号・パーティクルと違って<b>装備するものではない</b>(道具に刻印する)ので、ここは閲覧専用。
     * それでも同じ画面に置くのは、解放通知を見逃した人が「自分が何を持っていて、
     * どの素材で、どこで付けるのか」を確かめられる場所が他に1つも無かったため。
     *
     * <p>5行目(45〜53)の9枠に収める: 45=説明、46〜51=6件、52/53=ページ送り。
     * 出荷は6件なのでいまは1ページだが、<b>件数で黙って切らない</b>
     * (称号が1行8件で打ち切られ12件が永久に見えなかった 2026-08-17 の再発防止)。
     */
    private static final int SEED_INFO_SLOT = 45;
    private static final int SEED_ROW_START = 46;
    private static final int SEED_OPTIONS_PER_PAGE = 6;
    private static final int SEED_PREV_SLOT = 52;
    private static final int SEED_NEXT_SLOT = 53;

    private final Plugin plugin;
    private final SpecialRewardsConfig config;
    private final SpecialRewardService rewardService;
    private final NamespacedKey titleKey;
    private final NamespacedKey particleKey;
    private final NamespacedKey toggleKey;
    private final NamespacedKey mailKey;
    private final NamespacedKey gatherToggleKey;
    private final NamespacedKey titlePageKey;
    private final NamespacedKey particlePageKey;
    private final NamespacedKey seedPageKey;
    /**
     * {@code custom:<カタログID>} を実体へ解決する種(本番は
     * {@code CrossPluginItemResolver::create})。<b>シードのアイコンを「素材そのもの」で出すため</b>に要る ──
     * カタログ品は material と custom-model-data(=テクスチャ)を持つので、実体を作らないと
     * その見た目を再現できない。解決できない指定のときだけ Material 名から引く。
     */
    private final Function<String, Optional<ItemStack>> itemResolver;
    private Consumer<Player> onTitleChanged = p -> { };
    /** メール受信箱を開くフック。メール機能の初期化に失敗した回は null のままで、ボタンも出さない。 */
    private Consumer<Player> onOpenMail;
    private Consumer<Player> onParticleChanged = p -> { };

    /**
     * @param itemResolver {@code custom:<カタログID>} → {@link ItemStack}(本番は
     *                     {@code CrossPluginItemResolver::create})。<b>省略可能な setter にしない</b> ──
     *                     配線を忘れると「カタログ品のシードだけ代用アイコンで出る」という、
     *                     エラーも警告も出ない見た目の劣化になり、気づく手段が実機の目視しか無くなる。
     */
    public SettingsGui(Plugin plugin, SpecialRewardsConfig config, SpecialRewardService rewardService,
                       Function<String, Optional<ItemStack>> itemResolver) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.rewardService = Objects.requireNonNull(rewardService, "rewardService");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.titleKey = new NamespacedKey(plugin, "settings_gui_title");
        this.particleKey = new NamespacedKey(plugin, "settings_gui_particle");
        this.toggleKey = new NamespacedKey(plugin, "settings_gui_toggle");
        this.mailKey = new NamespacedKey(plugin, "settings_gui_mail");
        this.gatherToggleKey = new NamespacedKey(plugin, "settings_gui_gather_toggle");
        this.titlePageKey = new NamespacedKey(plugin, "settings_gui_title_page");
        this.particlePageKey = new NamespacedKey(plugin, "settings_gui_particle_page");
        this.seedPageKey = new NamespacedKey(plugin, "settings_gui_seed_page");
    }

    /** メール受信箱を開くフック({@code MailInboxGui::open})。未設定ならボタンを描かない。 */
    public void setOnOpenMail(Consumer<Player> onOpenMail) {
        this.onOpenMail = onOpenMail;
    }

    /** 称号の装備/解除が確定した直後に呼ばれるフック(頭上表示の張り直し用)。 */
    public void setOnTitleChanged(Consumer<Player> onTitleChanged) {
        this.onTitleChanged = onTitleChanged == null ? p -> { } : onTitleChanged;
    }

    /**
     * パーティクルの装備/解除が確定した直後に呼ばれるフック(2026-07-23 verifier指摘⑨:
     * {@code ParticleEffectService} のメモリキャッシュ無効化用)。
     */
    public void setOnParticleChanged(Consumer<Player> onParticleChanged) {
        this.onParticleChanged = onParticleChanged == null ? p -> { } : onParticleChanged;
    }

    public void open(Player player) {
        open(player, 0, 0, 0);
    }

    /** 既存の呼び出し互換(シードのページは先頭)。 */
    void open(Player player, int titlePage, int particlePage) {
        open(player, titlePage, particlePage, 0);
    }

    /** ページ数(0件でも1ページある扱いにして、ページ番号を常に 0 に丸められるようにする)。 */
    static int pageCount(int optionCount) {
        return Math.max(1, (optionCount + OPTIONS_PER_PAGE - 1) / OPTIONS_PER_PAGE);
    }

    /** 範囲外のページ番号を丸める(報酬が減ったあとに古いページを開いても空にならないように)。 */
    private static int clampPage(int page, int optionCount) {
        return Math.max(0, Math.min(page, pageCount(optionCount) - 1));
    }

    void open(Player player, int titlePage, int particlePage, int seedPage) {
        Session session = new Session();
        Inventory inventory = Bukkit.createInventory(session, SIZE, Component.text("設定"));
        session.inventory = inventory;

        PlayerData playerData = PlayerData.of(player);
        boolean hideOthers = playerData.hideOthersCosmetics();
        inventory.setItem(TOGGLE_SLOT, toggleButton(hideOthers));
        if (onOpenMail != null) {
            inventory.setItem(MAIL_SLOT, mailButton());
        }

        // 2026-07-25 gather-rework-active-framework §2 B-2: 採取プレイヤートグル4種。
        inventory.setItem(GATHER_TOGGLE_VEIN_MINING_SLOT, gatherToggleButton(
                "vein-mining", "鉱脈一括破壊", Material.IRON_PICKAXE, playerData.veinMiningEnabled()));
        inventory.setItem(GATHER_TOGGLE_TREE_FELL_SLOT, gatherToggleButton(
                "tree-fell", "一括伐採", Material.IRON_AXE, playerData.treeFellEnabled()));
        inventory.setItem(GATHER_TOGGLE_AUTO_REPLANT_SLOT, gatherToggleButton(
                "auto-replant", "植え直しと収穫同時", Material.WHEAT_SEEDS, playerData.autoReplantEnabled()));
        inventory.setItem(GATHER_TOGGLE_AREA_HARVEST_SLOT, gatherToggleButton(
                "area-harvest", "範囲収穫", Material.IRON_HOE, playerData.areaHarvestEnabled()));

        String equippedTitle = PlayerData.of(player).equippedTitle().orElse(null);
        List<String> titleIds = new ArrayList<>(config.titles().keySet());
        int titles = clampPage(titlePage, titleIds.size());
        session.titlePage = titles;
        inventory.setItem(TITLE_ROW_START, clearTitleButton("称号を外す", equippedTitle == null));
        for (int i = 0; i < OPTIONS_PER_PAGE; i++) {
            int index = titles * OPTIONS_PER_PAGE + i;
            if (index >= titleIds.size()) {
                break;
            }
            String id = titleIds.get(index);
            boolean unlocked = rewardService.isUnlocked(player, id);
            inventory.setItem(TITLE_ROW_START + 1 + i,
                    titleButton(id, config.titles().get(id), unlocked, id.equals(equippedTitle)));
        }
        applyPageButtons(inventory, TITLE_ROW_START, titles, titleIds.size(), titlePageKey, "称号");

        String equippedParticle = PlayerData.of(player).equippedParticle().orElse(null);
        List<String> particleIds = new ArrayList<>(config.particles().keySet());
        int particles = clampPage(particlePage, particleIds.size());
        session.particlePage = particles;
        inventory.setItem(PARTICLE_ROW_START, clearParticleButton("パーティクルを外す", equippedParticle == null));
        for (int i = 0; i < OPTIONS_PER_PAGE; i++) {
            int index = particles * OPTIONS_PER_PAGE + i;
            if (index >= particleIds.size()) {
                break;
            }
            String id = particleIds.get(index);
            boolean unlocked = rewardService.isUnlocked(player, id);
            inventory.setItem(PARTICLE_ROW_START + 1 + i,
                    particleButton(id, config.particles().get(id), unlocked, id.equals(equippedParticle)));
        }
        applyPageButtons(inventory, PARTICLE_ROW_START, particles, particleIds.size(), particlePageKey, "パーティクル");

        // 読み込みに失敗した回は空(実装によっては null)になりうる。設定画面そのものは開けること。
        Map<String, SpecialRewardsConfig.ParticleSeed> seedDefs = config.particleSeeds();
        if (seedDefs == null) {
            seedDefs = Map.of();
        }
        List<String> seedIds = new ArrayList<>(seedDefs.keySet());
        int seeds = Math.max(0, Math.min(seedPage, seedPageCount(seedIds.size()) - 1));
        session.seedPage = seeds;
        inventory.setItem(SEED_INFO_SLOT, seedInfoButton());
        for (int i = 0; i < SEED_OPTIONS_PER_PAGE; i++) {
            int index = seeds * SEED_OPTIONS_PER_PAGE + i;
            if (index >= seedIds.size()) {
                break;
            }
            String id = seedIds.get(index);
            SpecialRewardsConfig.ParticleSeed seed = seedDefs.get(id);
            // 消すシード(clears)は解放を要求しないので常に「持っている」扱いで出す。
            boolean unlocked = (seed != null && seed.clears()) || rewardService.isUnlocked(player, id);
            inventory.setItem(SEED_ROW_START + i, seedButton(id, seed, unlocked));
        }
        int seedPages = seedPageCount(seedIds.size());
        if (seedPages > 1) {
            if (seeds > 0) {
                inventory.setItem(SEED_PREV_SLOT, pageButton(seedPageKey, seeds - 1,
                        "シード 前のページ (" + seeds + "/" + seedPages + ")"));
            }
            if (seeds < seedPages - 1) {
                inventory.setItem(SEED_NEXT_SLOT, pageButton(seedPageKey, seeds + 1,
                        "シード 次のページ (" + (seeds + 2) + "/" + seedPages + ")"));
            }
        }

        player.openInventory(inventory);
    }

    /** シード一覧のページ数(0件でも1ページある扱い)。 */
    static int seedPageCount(int seedCount) {
        return Math.max(1, (seedCount + SEED_OPTIONS_PER_PAGE - 1) / SEED_OPTIONS_PER_PAGE);
    }

    /** 一覧の見出し。<b>付け方をここに書く</b>のが目的(解放通知を見逃すと他に知る手段が無い)。 */
    private ItemStack seedInfoButton() {
        ItemStack stack = new ItemStack(Material.ANVIL);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("パーティクルシード", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("金床の左に 道具/武器、右に素材を置くと", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("その道具に粒子が焼き付きます", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("ブロック破壊・攻撃のたびに発生", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("付け替えは上書き(元の粒子は消えます)", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * シード1件分の表示(閲覧専用・クリックしても何も起きない)。
     *
     * <p>アイコンは<b>その シードの素材そのもの</b>にする ── プレイヤーが探すのは ID ではなく
     * 「どのアイテムを金床へ持っていけばいいか」なので、そこを見た目で答える。
     * {@code custom:<カタログID>} のように {@link Material} へ解決できない指定のときだけ
     * 代用アイコンにして、素材名は lore の文字で示す。
     */
    private ItemStack seedButton(String id, SpecialRewardsConfig.ParticleSeed seed, boolean unlocked) {
        ItemStack resolved = unlocked ? resolveSeedItem(seed) : null;
        ItemStack stack = resolved != null ? resolved : new ItemStack(unlocked ? seedIconOf(seed) : Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        // カタログ品をそのまま持ってきたときは、その品の説明文・属性・エンチャントが付いてくる。
        // ここは閲覧専用のボタンなので、見た目(material と custom-model-data)だけ借りて中身は隠す。
        if (resolved != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        }
        // 表示は日本語名(display)。未設定なら ID へ落とす。運用でIDが要るので lore に必ず出す。
        String label = seed == null ? id : seed.displayName();
        meta.displayName(unlocked
                ? com.trinityforge.text.MiniText.render(label, NamedTextColor.LIGHT_PURPLE)
                : Component.text("？？？ (未解放)", NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (unlocked && seed != null) {
            lore.add(Component.text("ID: " + id, NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("素材: ", NamedTextColor.GRAY)
                    .append(seedItemLabel(seed, resolved))
                    .decoration(TextDecoration.ITALIC, false));
            if (seed.clears()) {
                lore.add(Component.text("道具に付いている粒子を取り除きます", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("解放は不要(誰でも使えます)", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                if (seed.particle() != null) {
                    lore.add(Component.text("粒子: " + seed.particle().name() + " ×" + seed.count(),
                            NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                }
                lore.add(Component.text("金床で 道具/武器 に付与できます", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false));
            }
        } else {
            lore.add(Component.text("アチーブメントで解放", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * {@code seed-item} が {@code custom:<カタログID>} のとき、その<b>実体</b>を1個だけ作って返す。
     * バニラ Material 指定・解決できないID・準備中(draft)のときは null(呼び出し側が Material から引く)。
     *
     * <p>これがあることで、シードは<b>特殊アイテム設定(items/catalog.yml)側だけで</b>
     * material・テクスチャ(custom-model-data)・クラフトレシピを定義できる ──
     * 専用の設定層を増やさずに済む唯一の経路なので、ここで実体を引けないと
     * 「カタログでテクスチャを定義したのに一覧では別物のアイコンが出る」で破綻する。
     *
     * <p>実体の生成はカタログ側の任意コード(ステータス抽選など)を通るので、
     * <b>設定画面を開く操作で例外を投げさせない</b>。壊れたエントリ1件で画面全体が開かなくなる。
     */
    private ItemStack resolveSeedItem(SpecialRewardsConfig.ParticleSeed seed) {
        if (seed == null || seed.seedItem() == null) {
            return null;
        }
        String spec = seed.seedItem().trim();
        if (!spec.toLowerCase(Locale.ROOT).startsWith("custom:")) {
            return null;
        }
        try {
            ItemStack built = itemResolver.apply(spec).orElse(null);
            if (built == null || built.getType().isAir() || built.getItemMeta() == null) {
                return null;
            }
            ItemStack copy = built.clone();
            copy.setAmount(1);
            return copy;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("パーティクルシード " + seed.id() + " の素材 " + spec
                    + " を設定画面用に作れませんでした: " + ex.getMessage());
            return null;
        }
    }

    /** lore の「素材:」に出す名前。カタログ品は実体の表示名(日本語)、それ以外は指定そのまま。 */
    private static Component seedItemLabel(SpecialRewardsConfig.ParticleSeed seed, ItemStack resolved) {
        if (resolved != null && resolved.getItemMeta() != null && resolved.getItemMeta().hasDisplayName()) {
            return resolved.getItemMeta().displayName().decoration(TextDecoration.ITALIC, false);
        }
        return Component.text(seed.seedItem(), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** {@code seed-item} を表すアイコン。解決できない指定(custom:等)は代用アイコン。 */
    private static Material seedIconOf(SpecialRewardsConfig.ParticleSeed seed) {
        if (seed == null || seed.seedItem() == null || seed.seedItem().isBlank()) {
            return Material.FIREWORK_STAR;
        }
        Material material = Material.matchMaterial(seed.seedItem().trim());
        return material == null || material.isAir() || !material.isItem()
                ? Material.FIREWORK_STAR : material;
    }

    /**
     * ページ送りボタンを置く。1ページしか無いときは何も置かない
     * (選択肢が少ないサーバでボタンだけ並ぶのを避ける)。
     */
    private void applyPageButtons(Inventory inventory, int rowStart, int page, int optionCount,
                                  NamespacedKey key, String label) {
        int pages = pageCount(optionCount);
        if (pages <= 1) {
            return;
        }
        if (page > 0) {
            inventory.setItem(rowStart + PREV_PAGE_OFFSET,
                    pageButton(key, page - 1, label + " 前のページ (" + page + "/" + pages + ")"));
        }
        if (page < pages - 1) {
            inventory.setItem(rowStart + NEXT_PAGE_OFFSET,
                    pageButton(key, page + 1, label + " 次のページ (" + (page + 2) + "/" + pages + ")"));
        }
    }

    private ItemStack pageButton(NamespacedKey key, int targetPage, String label) {
        ItemStack stack = new ItemStack(Material.ARROW);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, targetPage);
        stack.setItemMeta(meta);
        return stack;
    }

    /** メール受信箱を開くボタン(2026-08-19 / W-155)。 */
    private ItemStack mailButton() {
        ItemStack stack = new ItemStack(Material.CHEST_MINECART);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("メール受信箱", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("運営から届いたメールを受け取る", NamedTextColor.GRAY),
                Component.text("/tf mail でも開けます", NamedTextColor.DARK_GRAY)));
        meta.getPersistentDataContainer().set(mailKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack toggleButton(boolean hideOthers) {
        ItemStack stack = new ItemStack(hideOthers ? Material.RED_DYE : Material.LIME_DYE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("他人の演出を非表示: " + (hideOthers ? "ON" : "OFF"),
                hideOthers ? NamedTextColor.RED : NamedTextColor.GREEN));
        meta.lore(List.of(Component.text("クリックで切替(軽量化用)", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(toggleKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 採取プレイヤートグル1個分のボタン(2026-07-25 §2 B-2)。{@code id}は{@code gatherToggleKey}のPDC値。 */
    private ItemStack gatherToggleButton(String id, String label, Material icon, boolean enabled) {
        ItemStack stack = new ItemStack(enabled ? icon : Material.GRAY_DYE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label + ": " + (enabled ? "ON" : "OFF"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        meta.lore(List.of(Component.text("クリックで切替", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(gatherToggleKey, PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack clearTitleButton(String label, boolean active) {
        ItemStack stack = new ItemStack(active ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, active ? NamedTextColor.GREEN : NamedTextColor.WHITE));
        meta.getPersistentDataContainer().set(titleKey, PersistentDataType.STRING, "");
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack clearParticleButton(String label, boolean active) {
        ItemStack stack = new ItemStack(active ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, active ? NamedTextColor.GREEN : NamedTextColor.WHITE));
        meta.getPersistentDataContainer().set(particleKey, PersistentDataType.STRING, "");
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * 称号1件分のボタン。
     *
     * <p><b>2026-08-05 の修正</b>: 以前はボタン名に内部ID({@code title_completionist} 等)をそのまま
     * 出していた。{@code title} 引数は受け取っているのに一度も使っておらず、プレイヤーは頭上に出る
     * 実際の称号({@code <gradient:...>万象を知る者</gradient>})と選択画面の名前が繋がらなかった。
     * 表示は {@link com.trinityforge.text.MiniText} で描画する — {@code display} は special-rewards.yml
     * 由来の MiniMessage 文字列なので、{@code Component.text()} に渡すとタグが生で見える
     * (図鑑ティア解放通知で実際に起きた不具合と同じ形)。IDは運用で必要なので lore へ落とす。
     */
    private ItemStack titleButton(String id, SpecialRewardsConfig.Title title, boolean unlocked, boolean equipped) {
        ItemStack stack = new ItemStack(unlocked
                ? (equipped ? Material.NAME_TAG : Material.PAPER) : Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        String display = title == null ? null : title.display();
        if (!unlocked) {
            meta.displayName(Component.text("？？？ (未解放)", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
        } else if (display == null || display.isBlank()) {
            // display 欠落は config 側の不備。IDへフォールバックして選択自体は壊さない。
            meta.displayName(Component.text(id, equipped ? NamedTextColor.GOLD : NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            meta.displayName(com.trinityforge.text.MiniText.render(display,
                    equipped ? NamedTextColor.GOLD : NamedTextColor.WHITE));
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("ID: " + id, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(equipped ? "装備中" : (unlocked ? "クリックで装備" : "未解放"),
                        equipped ? NamedTextColor.GREEN : (unlocked ? NamedTextColor.GRAY : NamedTextColor.RED))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (unlocked) {
            meta.getPersistentDataContainer().set(titleKey, PersistentDataType.STRING, id);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack particleButton(String id, SpecialRewardsConfig.ParticleEffect effect, boolean unlocked,
                                      boolean equipped) {
        ItemStack stack = new ItemStack(unlocked
                ? (equipped ? Material.GLOWSTONE_DUST : Material.BLAZE_POWDER) : Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(unlocked ? id : "？？？ (未解放)",
                        equipped ? NamedTextColor.GOLD : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        if (unlocked) {
            meta.getPersistentDataContainer().set(particleKey, PersistentDataType.STRING, id);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        // 開き直すときは【今見ているページを持ち越す】。ここで 0 に戻すと、2ページ目で
        // 称号を選んだ瞬間に1ページ目へ飛ばされて「選べない」ように見える。
        int titlePage = session.titlePage;
        int particlePage = session.particlePage;
        int seedPage = session.seedPage;
        ItemMeta meta = clicked.getItemMeta();
        Integer nextTitlePage = meta.getPersistentDataContainer().get(titlePageKey, PersistentDataType.INTEGER);
        if (nextTitlePage != null) {
            open(player, nextTitlePage, particlePage, seedPage);
            return;
        }
        Integer nextParticlePage = meta.getPersistentDataContainer().get(particlePageKey, PersistentDataType.INTEGER);
        if (nextParticlePage != null) {
            open(player, titlePage, nextParticlePage, seedPage);
            return;
        }
        Integer nextSeedPage = meta.getPersistentDataContainer().get(seedPageKey, PersistentDataType.INTEGER);
        if (nextSeedPage != null) {
            open(player, titlePage, particlePage, nextSeedPage);
            return;
        }
        if (meta.getPersistentDataContainer().has(mailKey, PersistentDataType.BYTE)) {
            Consumer<Player> openMail = onOpenMail;
            if (openMail != null) {
                player.closeInventory();
                openMail.accept(player);
            }
            return;
        }
        if (meta.getPersistentDataContainer().has(toggleKey, PersistentDataType.BYTE)) {
            PlayerData data = PlayerData.of(player);
            data.setHideOthersCosmetics(!data.hideOthersCosmetics());
            open(player, titlePage, particlePage, seedPage);
            return;
        }
        String gatherToggleId = meta.getPersistentDataContainer().get(gatherToggleKey, PersistentDataType.STRING);
        if (gatherToggleId != null) {
            toggleGatherPref(player, gatherToggleId);
            open(player, titlePage, particlePage, seedPage);
            return;
        }
        String titleId = meta.getPersistentDataContainer().get(titleKey, PersistentDataType.STRING);
        if (titleId != null) {
            if (rewardService.equipTitle(player, titleId.isBlank() ? null : titleId)) {
                onTitleChanged.accept(player);
            }
            open(player, titlePage, particlePage, seedPage);
            return;
        }
        String particleId = meta.getPersistentDataContainer().get(particleKey, PersistentDataType.STRING);
        if (particleId != null) {
            if (rewardService.equipParticle(player, particleId.isBlank() ? null : particleId)) {
                onParticleChanged.accept(player);
            }
            open(player, titlePage, particlePage, seedPage);
        }
    }

    /** {@code gatherToggleKey} のPDC id を対応するトグルへ反転する(2026-07-25 §2 B-2)。 */
    private void toggleGatherPref(Player player, String id) {
        PlayerData data = PlayerData.of(player);
        switch (id) {
            case "vein-mining" -> data.setVeinMiningEnabled(!data.veinMiningEnabled());
            case "tree-fell" -> data.setTreeFellEnabled(!data.treeFellEnabled());
            case "auto-replant" -> data.setAutoReplantEnabled(!data.autoReplantEnabled());
            case "area-harvest" -> data.setAreaHarvestEnabled(!data.areaHarvestEnabled());
            default -> { /* unknown id: no-op (defence in depth) */ }
        }
    }

    /**
     * 2026-07-23 verifier指摘⑨: ドラッグでアイテムがGUIへ置けて消失するのを防ぐ(クリックと同様、常に
     * キャンセル)。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    private static final class Session implements InventoryHolder {
        private Inventory inventory;
        /** 開いている画面のページ。片方を送ってももう片方のページを保つために持つ。 */
        private int titlePage;
        private int particlePage;
        private int seedPage;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
