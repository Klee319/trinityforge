package com.trinityforge.listeners;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.CollectionService;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DerivedItemStats;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * コレクション図鑑 (M7) の記録フィード。
 *
 * <p><b>アイテム</b>: カタログ(items/catalog.yml)アイテムの入手を記録する。拾得
 * ({@link EntityPickupItemEvent})に加え、直接インベントリへ入る経路(ダンジョンloot直入れ・
 * ガチャ・村人取引・クラフト結果の取り出し等)を取りこぼさないよう、インベントリを閉じた時と
 * 参加時に全スロットを走査して差分登録する(既知エントリはPDC書き込みなしで抜けるため軽量)。
 *
 * <p><b>討伐</b>: プレイヤーがキラーのモブ死亡を {@code mob:<ENTITY_TYPE>} として記録する
 * (EliteMobs個体もベースのEntityTypeで記録される)。
 *
 * <p>カタログID解決はPDC刻印を優先し、未刻印でも material+CustomModelData がテンプレートに
 * 一致すれば図鑑対象にする({@link CatalogIdentity#ensure} と同じくCMD無しのバニラスタックは
 * カタログ照合しない — 偶然materialが同じだけの vanilla /give を誤登録しないため)。
 *
 * <p><b>ArsPaper 側のアイテム (2026-07-31)</b>: PDC刻印の読み取りは
 * {@link com.trinityforge.stats.CrossPluginItemResolver#idOf} 経由にしてある。TF の catalog PDC しか
 * 見ていなかったため、ArsPaper の {@code materials.yml} / {@code sourcejars.yml} で定義したアイテム
 * (モブドロップ素材・ダンジョン踏破の証・ソースの階梯)が<b>永久に記録されず</b>、図鑑カテゴリに
 * 書いてある 116 件のうち 48 件が「絶対に埋まらない枠」として並び続けていた。
 *
 * <p><b>バニラアイテム (2026-07-29)</b>: 以前はカタログ品しか記録できなかったため、
 * アチーブメントの「アイテム条件」にバニラアイテムを書いても進捗が永久に0のままだった。
 * {@code item:<MATERIAL>} も記録できるようにしたが、拾った物を無条件に記録すると
 * プレイヤーPDCが全Material分まで膨らむ。記録するのは<b>設定から参照されている</b>
 * Material だけに限る:
 * <ul>
 *   <li>{@code progression/collection.yml} の図鑑カテゴリ entries に書かれた Material</li>
 *   <li>{@code progression/achievements.yml} の {@code collection.scope: item} 対象の Material</li>
 * </ul>
 * どちらの config も reload で差し替わるので、監視集合はキャッシュせず毎回引き直す。
 *
 * <p><b>K-11 (2026-07-31): 素のバニラ品が1件も記録されていなかった</b>。上の Material 分岐を
 * 足したとき、PDC を読むための前置条件だった {@code !stack.hasItemMeta()} 早期 return を
 * {@code catalogIdOf} の冒頭に残したままだった。実機の {@code CraftItemStack#hasItemMeta()} は
 * 「データコンポーネントの patch(既定値からの差分)が空でないか」を見るので、ルートチェストから
 * 出た無傷の {@code ELYTRA} / {@code TOTEM_OF_UNDYING} / {@code ECHO_SHARD} などは <b>false</b> になり、
 * Material 分岐に到達できない。結果 {@code collection.yml} の {@code items.structure} 16件が
 * 永久に錠前で、{@code goal_completionist}(percent: 100)が構造的に達成不能だった。
 * {@link com.trinityforge.combat.ProjectileWeapon#store} で一度直したのと<b>同じ轍</b>
 * (bare-material の弓を {@code hasItemMeta()} で落としていた High bug)の再発。
 *
 * <p>直し方として「Material 判定を PDC 判定より前へ出す」のは<b>誤り</b>。`materials.yml` の
 * {@code warden_tendril} / {@code reality_thread_core} は base が {@code ECHO_SHARD}、
 * {@code source_condenser} は {@code HEART_OF_THE_SEA} で、どちらの Material も図鑑に載っている。
 * Material を先に見るとこのカスタム3件が {@code item:ECHO_SHARD} 等に潰れて新たに到達不能になり、
 * かつ意図的に監視外にした圧縮品({@code echo_shard_1x} 等)を持つだけで枠が無料で埋まる。
 * 正しい形は「meta ゲートを早期 return から分岐セレクタへ変え、Material 判定を meta 無しスタックの
 * fallback にする」こと。分岐順序が本質なので、順序は {@link #resolveEntryId} に純関数として
 * 切り出してある(MockBukkit では本番条件を再現できないため。理由は同メソッドの javadoc)。
 *
 * <p><b>K-11 の副作用として入れた3つのガード (2026-07-31)</b>:
 * <ul>
 *   <li>クリエイティブ/スペクテイターを記録対象外にする({@link #excluded})。
 *       {@code items.structure} の16件が全部クリエイティブインベントリから出せる素のバニラ品なので、
 *       除外しないと図鑑と報酬ティアが無料で埋まる。</li>
 *   <li>遡り登録の通知抑止は「実際に1件以上記録した走査」だけがフラグを消費する
 *       ({@link #recordWithBackfillGate})。参加時の走査だけを静かにしても、遺物をチェストへ
 *       しまっているプレイヤーには効かない。</li>
 *   <li>監視集合は config snapshot の同一性でキャッシュする({@link #watchedConfigIds()})。
 *       {@link #onPickup} が拾得1件ごとに約150エントリを走査していた。</li>
 * </ul>
 *
 * <p><b>ゲームモードゲートだけでは構造的に閉じない (2026-07-31 追加修正)</b>: 上の1つ目は
 * <b>「クリエイティブでいる間の記録」しか</b>塞げない。図鑑は他の6箇所と違って
 * <b>インベントリの状態を遡って走査する</b>({@link #scanInventory})ので、クリエイティブで
 * {@code items.structure} の16件を並べてからサバイバルへ移ると、走査時のゲームモードは
 * SURVIVAL になりゲートを素通りして16件が一括登録される。しかも「最初の productive な走査は
 * 静か」という抑止と噛み合って<b>チャット0行で</b>通るため、修正前より検知しにくい。
 * ({@code ops/RUNBOOK.md} はメインの world を creative と書いており、HuskSync は
 * {@code game_mode: false} でインベントリだけ同期するので、{@code /server resource} で
 * 移動するだけで「クリエイティブで出した品を持ったサバイバルプレイヤー」が成立する。)
 *
 * <p>そこで<b>出自をアイテム側の PDC に刻む</b>({@link com.trinityforge.pdc.PdcKeys#ITEM_CREATIVE_ORIGIN})。
 * 刻む経路は3つ:
 * <ul>
 *   <li>{@link #onCreativeSet} — クリエイティブのアイテム欄から新しく湧いた品。</li>
 *   <li>{@link #onPickBlock} / {@link #onPickEntity} — クリエイティブの中クリック複製。
 *       <b>1.21.4 以降このパケットは SetCreativeModeSlot と別系統</b>なので onCreativeSet には来ない。</li>
 *   <li>{@link #onPickup} — クリエイティブ/スペクテイター滞在中の拾得。</li>
 * </ul>
 * <b>ゲームモードゲートは残す</b> — ゲートは「クリエイティブ中の記録」を、印は「クリエイティブで
 * 得た品をサバイバルで走査したとき」を塞ぐので、役割が違い両方必要。
 *
 * <p><b>印は best-effort(1) — 印が失われる側</b>:
 * <ul>
 *   <li>クラフト素材として消費した品・別アイテムへ変換した品・ブロックとして設置して壊し直した品は
 *       新しいスタックになるので印が失われる(そこから作った完成品は図鑑に載る)。</li>
 *   <li>{@code /give} や他プラグインが直接インベントリへ書き込む経路には印が付かない
 *       (どちらも op 相当の権限が前提なので受容する)。</li>
 *   <li>スタック合体で印は失われない代わりに、印付きスタックは素の同種スタックと
 *       <b>合体しなくなる</b>(PDC が違うので {@code isSimilar} が不一致になる)。</li>
 *   <li>クリエイティブで既に同種の品を持っている状態で同じ品を湧かせた分には印を付けない
 *       ({@link #isNewlySpawned})。既に持っている品はそちらで図鑑を埋められるので、
 *       塞ぐ意味が無いのに誤付与のリスクだけが増える。</li>
 * </ul>
 *
 * <p><b>印は best-effort(2) — 印が誤って付く側(2026-08-01 追記)。こちらの方が症状が重い</b>:
 * 誤って付いたスタックは {@link #resolveEntryId} が図鑑判定から丸ごと外すので
 * <b>永久に図鑑に載らない</b>のに、エラーも通知も出ない。判明している誤付与経路は
 * <ul>
 *   <li><b>クリエイティブ滞在中に地面から拾った品</b>({@link #onPickup})。モブ討伐ドロップ・
 *       他プレイヤーの落とし物・資源サーバから持ち帰った品を落として拾い直した場合が該当する。
 *       地面のスタックからは出自の手掛かりが取れないので絞れない。ここを塞がないと
 *       「クリエイティブでモブを即殺してドロップを集めサバイバルへ持ち込む」が通るため、
 *       誤付与を受け入れて刻む側を選んでいる。</li>
 *   <li><b>クリエイティブ画面での整理</b>のうち「持ち上げ→置き直し」の対応付けに失敗したもの
 *       ({@link #onCreativeSet} の {@code creativeLimbo} は<b>1件しか覚えない</b>ので、
 *       同種でない品を複数同時に juggle すると外れる)。</li>
 * </ul>
 * <b>回復手段: {@code /tf collection unmark}</b>(OP または {@code trinityforge.admin})。
 * 剥がす経路が無いと運用で回復できないので、この管理コマンドは印の一部である。
 *
 * <p>完全な出自追跡はアイテムを個体管理しない限り不可能なので、ここは
 * 「無料で16件埋まる」経路だけを閉じる割り切りである。
 *
 * <p><b>本番 world が実際にクリエイティブ運用なら、その world では図鑑機能が丸ごと不活性になる</b>
 * (ゲートで記録されず、生成した品にも印が付くのでサバイバルへ持ち込んでも載らない)。
 * これは意図した挙動であり、図鑑を機能させたい運用ではサバイバルの world / 資源サーバで遊ぶ必要がある。
 */
public final class CollectionListener implements Listener {

    /** editor が custom アイテムに付ける接頭辞。監視集合を作るときに落とす。 */
    private static final String CUSTOM_PREFIX = "custom:";

    /**
     * 自プラグイン名。参加時走査を遅延させるスケジューラを引くためだけに使う。
     *
     * <p>package-private なのは {@code CollectionListenerGuardsTest} が
     * {@code paper-plugin.yml} の {@code name:} と一致していることを機械的に縛るため。
     * ここが drift すると {@link #resolveOwnPlugin()} が黙って {@code null} を返し、
     * HuskSync 対策の遅延が<b>テスト全部緑のまま消える</b>。
     */
    static final String OWN_PLUGIN_NAME = "TrinityForge";

    /**
     * 参加時走査を遅らせる tick 数(2秒)。詳細は {@link #onJoin}。HuskSync の snapshot 適用が
     * 終わるのを待つのが目的なので、DB/Redis の往復に十分な余裕を持たせている。
     */
    private static final long JOIN_SCAN_DELAY_TICKS = 40L;

    private final CollectionConfig config;
    private final CollectionService service;
    private final ItemCatalogConfig catalog;
    private final AchievementsConfig achievements;

    /** 参加時走査の遅延に使う。null = 遅延せず即時走査(サーバ未起動の単体テスト)。 */
    private final Plugin plugin;

    /**
     * 監視集合のキャッシュ。<b>config snapshot のインスタンス同一性で無効化する</b>ので、
     * 明示的に捨てる呼び出しは要らない(捨て忘れると「editor で図鑑を編集しても反映されない」
     * という別のバグになるため、忘れようのない形にしてある)。詳細は {@link #watchedConfigIds()}。
     */
    private volatile WatchedSnapshot watchedCache;

    /**
     * クリエイティブ画面で「カーソルへ持ち上げられた」= サーバから見ると<b>消えた</b>スタック。
     * プレイヤーごとに直近1件だけ覚える。
     *
     * <p><b>これが無いと整理操作が新規生成に見える</b>(2026-08-01 の HIGH 指摘の本体):
     * クリエイティブ画面のアイテム欄タブでは、クライアントがスロットを自分で書き換えて
     * <b>結果のスロット内容だけ</b>を SetCreativeModeSlot で送る。持ち上げと置き直しは
     * 別クリック＝別パケットなので、
     * <ol>
     *   <li>ホットバー0のエリトラをクリック → 「スロット0 = 空」が届く(サーバ上からエリトラが消える)</li>
     *   <li>ホットバー3をクリック → 「スロット3 = エリトラ」が届く</li>
     * </ol>
     * となり、2 の時点でエリトラはインベントリのどこにも無い。カーソルもサーバ側では
     * 更新されない({@code handleSetCreativeModeSlot} は carried を触らない)ので、
     * <b>「持ち上げたものを置き直した」と「アイテム欄から湧かせた」を区別する手掛かりが
     * この記憶しか無い</b>。覚えていないと、資源サーバで正当に入手したエリトラを
     * メイン world(creative)で並べ替えるだけで印が付き、図鑑に永久に載らなくなる。
     *
     * <p>1件しか持たないので容量は増えない。{@link #onQuit} で明示的に捨てるのは、
     * 退出したプレイヤーの UUID を残さないため(1件でも「掃除していないマップ」は増え続ける)。
     */
    private final Map<UUID, ItemStack> creativeLimbo = new ConcurrentHashMap<>();

    /**
     * @param categories   キャッシュ作成時点の {@code config.itemCategories()} インスタンス
     * @param achievements キャッシュ作成時点の {@code achievements.achievements()} インスタンス
     * @param watched      上記2つから組んだ監視集合(不変)
     */
    private record WatchedSnapshot(List<CollectionConfig.Category> categories,
                                   List<AchievementsConfig.Achievement> achievements,
                                   Set<String> watched) {
    }

    public CollectionListener(CollectionConfig config, CollectionService service,
                              ItemCatalogConfig catalog, AchievementsConfig achievements) {
        this(config, service, catalog, achievements, resolveOwnPlugin());
    }

    /**
     * @param plugin 参加時走査を遅延させるためのプラグイン。{@code null} なら遅延せず即時走査する
     *               (サーバが立っていない単体テスト用の逃げ道)。
     */
    public CollectionListener(CollectionConfig config, CollectionService service,
                              ItemCatalogConfig catalog, AchievementsConfig achievements,
                              Plugin plugin) {
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.achievements = Objects.requireNonNull(achievements, "achievements");
        this.plugin = plugin;
    }

    /**
     * 自プラグインをプラグインマネージャから引く。配線(TrinityForge#onEnable)を変えずに
     * 参加時走査の遅延を効かせるための経路で、明示的に Plugin を渡す構築の方が望ましい。
     * サーバが未初期化(MockBukkit を使わない単体テスト)なら {@code null} を返す。
     */
    private static Plugin resolveOwnPlugin() {
        if (Bukkit.getServer() == null) {
            return null;
        }
        return Bukkit.getPluginManager().getPlugin(OWN_PLUGIN_NAME);
    }

    /**
     * 拾得。クリエイティブ/スペクテイター中の拾得は<b>記録せず、代わりに出自マーカーを刻む</b>
     * ({@link #onCreativeSet} と合わせてクラス javadoc の「ゲームモードゲートだけでは
     * 構造的に閉じない」を参照)。サバイバルへ戻ってから走査させる抜け穴を塞ぐのが目的。
     *
     * <p>通知の抑止は走査経路と同じ {@link #recordWithBackfillGate} に通す。
     * <b>「拾得は常に通知」という従来の流儀を変えている</b>理由は同メソッドの javadoc。
     *
     * <p><b>この経路は絞れない</b>(2026-08-01): 地面のスタックからは出自の手掛かりが一切取れないので、
     * クリエイティブ滞在中の拾得は<b>すべて</b>刻む。モブ討伐ドロップや他プレイヤーの落とし物、
     * 資源サーバから持ち帰った品を落として拾い直した場合まで巻き込む(クラス javadoc の
     * 「印が誤って付く側」)。それでも刻む側を選ぶのは、絞ると「クリエイティブでモブを即殺して
     * ドロップを集めサバイバルへ持ち込む」が通るため。誤付与は {@code /tf collection unmark} で剥がす。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (excluded(player)) {
            if (!config.enabled()) {
                // 図鑑機能が丸ごと無効なら刻まない(2026-08-01)。印は PDC なので、刻むと
                // 素の同種スタックと合体しなくなる副作用だけが残る(配布用チェストの石64個が
                // 手持ちの石と合体せずスロットを二重に食う)。得られる利益はゼロ。
                return;
            }
            ItemStack dropped = event.getItem().getItemStack();
            if (markCreativeOrigin(dropped)) {
                // CraftItem#getItemStack() は NMS スタックの mirror なので上の書き込みで既に
                // 通っているが、copy を返す実装に変わった場合に備えて書き戻す(冪等)。
                // この書き戻しは死にコードではない — creativePickupWriteBackSurvivesACopyingItemEntity
                // が「getItemStack() が copy を返す実装」で本当に刻まれることを固定している。
                event.getItem().setItemStack(dropped);
            }
            return;
        }
        if (!config.catalogItemsEnabled()) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        catalogIdOf(stack).ifPresent(id -> recordWithBackfillGate(player,
                Map.of(CollectionService.itemEntryId(id), qualityOf(stack))));
    }

    /**
     * クリエイティブのスロット書き込み({@code SetCreativeModeSlot} パケット)。
     * <b>アイテム欄から新しく湧いた品にだけ</b>出自マーカーを刻む。
     *
     * <p><b>このイベントは「アイテム生成」専用ではない</b>(2026-08-01 の HIGH 指摘)。
     * クリエイティブのクライアントはインベントリを自分で書き換えて<b>結果のスロット内容</b>を
     * この1本のパケットで送るので、アイテム欄からの取り出しだけでなく
     * <b>ホットバーの並べ替え・画面外へのドロップ・アイテム欄へ捨てる削除</b>も全部ここに来る。
     * 無条件に刻むと、資源サーバで正当に入手した {@code ELYTRA} を
     * メイン world(creative、{@code ops/RUNBOOK.md})で並べ替えるだけで印が付き、
     * <b>そのスタックは永久に図鑑に載らなくなる</b>(エラーも通知も出ない)。
     * 絞り込みの本体は {@link #isNewlySpawned} と {@link #creativeLimbo}。
     * (クリエイティブ画面の「インベントリ」タブでの操作は通常の {@code InventoryClickEvent} を
     * 通るのでここには来ない。ここに来るのはアイテム欄タブが選ばれているときの操作。)
     *
     * <p>{@code setCursor} で刻んだスタックを差し戻す。{@code InventoryCreativeEvent} の
     * {@code getCursor}/{@code setCursor} は<b>自前フィールド</b>を読み書きする実装で
     * (paper-api 1.21.11 のバイトコードで確認済み)、CraftBukkit は本イベント後の
     * {@code getCursor()} をスロットへ書き込む。カーソルを<b>差し替える</b>だけなので、
     * {@code CraftItemEvent} でカーソルを書くと素材が消費されず複製する既知の罠
     * ({@code docs/agent-context/common-traps.md})とは別経路であり無関係。
     *
     * <p>優先度は {@code HIGHEST}(MONITOR ではない)。カーソルを<b>書き換える</b>ハンドラなので
     * 「MONITOR では変更しない」の流儀を守りつつ、他プラグインより後に走って印が上書きされないようにする。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeSet(InventoryCreativeEvent event) {
        if (!config.enabled()) {
            // 図鑑機能が丸ごと無効なら刻まない(2026-08-01)。印は PDC なので、刻むと
            // 素の同種スタックと合体しなくなる副作用だけが残る。得られる利益はゼロ。
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || !excluded(player)) {
            // サーバは creative のプレイヤーにしか通さない(handleSetCreativeModeSlot が
            // 門前払いする)が、ここでインベントリを読むので Player は必須。
            return;
        }
        ItemStack written = event.getCursor();
        ItemStack previous = previousSlotContent(event);
        if (written == null || written.getType().isAir()) {
            // スロットが空になった側 = カーソルへ持ち上げた/削除した。次の書き込みが
            // 「置き直し」か「新規生成」かを見分ける唯一の手掛かりなので覚えておく。
            rememberCreativeRemoval(player, previous);
            return;
        }
        if (!isNewlySpawned(player, written, previous)) {
            return;
        }
        if (!canEverBeRecorded(written)) {
            return;
        }
        ItemStack marked = written.clone();
        if (markCreativeOrigin(marked)) {
            event.setCursor(marked);
        }
    }

    /**
     * このスロット書き込みが「アイテム欄から新しく湧いた」ものか。
     *
     * <p>3つの<b>「既に持っていた」証拠</b>のどれかが立てば刻まない。刻み過ぎ(= 図鑑に永久に
     * 載らない)の方が刻み漏れ(= 既に持っている品の複製が無印で増える)より症状が重いので、
     * 曖昧なら刻まない側へ倒している。刻み漏れが安全なのは、
     * <b>既に同じ品を持っているならその品で図鑑を埋められる</b>ため
     * ── 塞ぎたいのは「持っていない品を無料で手に入れる」経路だけ。
     */
    private boolean isNewlySpawned(Player player, ItemStack written, ItemStack previous) {
        if (previous != null && previous.isSimilar(written)) {
            // 同じ品が既にそのスロットに居た(個数の増減・積み増し・分割)。
            return false;
        }
        if (ownsSimilar(player, written)) {
            // 同じ品をインベントリのどこかに持っている。
            return false;
        }
        ItemStack limbo = creativeLimbo.get(player.getUniqueId());
        if (limbo != null && limbo.isSimilar(written)) {
            // 直前に持ち上げた品の置き直し。消費して次の判定に持ち越さない。
            creativeLimbo.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** インベントリのどこかに {@code candidate} と同一視できるスタックがあるか。 */
    private static boolean ownsSimilar(Player player, ItemStack candidate) {
        for (ItemStack held : player.getInventory().getContents()) {
            if (held != null && !held.getType().isAir() && held.isSimilar(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** クリエイティブ画面で消えたスタックを1件だけ覚える。詳細は {@link #creativeLimbo}。 */
    private void rememberCreativeRemoval(Player player, ItemStack removed) {
        if (removed == null || removed.getType().isAir()) {
            return;
        }
        creativeLimbo.put(player.getUniqueId(), removed.clone());
    }

    /**
     * 書き込み<b>前</b>のスロット内容。イベントはスロットへの書き込み前に飛ぶので、view から
     * 読めるのはまだ古い内容。{@code rawSlot} が負(-999 = 画面外へのドロップ)なら対応する
     * スロットが無いので {@code null}。
     */
    private static ItemStack previousSlotContent(InventoryCreativeEvent event) {
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) {
            return null;
        }
        return event.getView().getItem(rawSlot);
    }

    /** 退出したプレイヤーの {@link #creativeLimbo} を捨てる(掃除しないマップは増え続ける)。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        creativeLimbo.remove(event.getPlayer().getUniqueId());
    }

    /**
     * クリエイティブの中クリック複製(ブロック)。
     *
     * <p><b>この経路は {@link #onCreativeSet} には来ない</b>: 1.21.4 で pick block はサーバ側処理へ
     * 移り、{@code ServerboundPickItemFromBlockPacket} という SetCreativeModeSlot とは別系統の
     * パケットになった(paper-api 1.21.11 に {@code PlayerPickBlockEvent} /
     * {@code PlayerPickEntityEvent} が存在することが物証)。塞がないと、印付きの
     * {@code DRAGON_EGG} / {@code SNIFFER_EGG} を設置して中クリックするだけで<b>印無しのコピー</b>が
     * 手に入り、サバイバルで走査すれば記録できてしまう。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickBlock(PlayerPickBlockEvent event) {
        stampPickedItemNextTick(event.getPlayer());
    }

    /** クリエイティブの中クリック複製(エンティティ)。理由は {@link #onPickBlock}。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickEntity(PlayerPickEntityEvent event) {
        stampPickedItemNextTick(event.getPlayer());
    }

    /**
     * 中クリック複製で湧いた品に次tickで印を刻む。
     *
     * <p><b>次tickでなければならない</b>: {@code PlayerPickItemEvent} はピック<b>前</b>に飛ぶので、
     * イベント中に読めるのはまだ複製前のインベントリ。
     *
     * <p>読む先を {@code getTargetSlot()} ではなく<b>メインハンド</b>にしているのは、
     * pick block は必ず複製した品を選択スロットへ持ってくる一方、{@code getTargetSlot()} が
     * ホットバー index なのかコンテナ raw slot なのかは API の javadoc 依存で、
     * 取り違えると<b>無言で刻み漏れる</b>ため。メインハンドなら版に依存しない。
     *
     * <p>ピック前のインベントリを控えておき、複製された品が<b>既に持っていた品なら刻まない</b>
     * (サバイバル同様、既に持っている品の中クリックは「その品を選び直す」だけの操作なので、
     * 刻むと正当な品に印が付く)。
     */
    private void stampPickedItemNextTick(Player player) {
        if (!config.enabled() || !excluded(player) || plugin == null) {
            return;
        }
        List<ItemStack> before = new ArrayList<>();
        for (ItemStack held : player.getInventory().getContents()) {
            if (held != null && !held.getType().isAir()) {
                before.add(held.clone());
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack picked = player.getInventory().getItemInMainHand();
            if (picked == null || picked.getType().isAir()) {
                return;
            }
            for (ItemStack prior : before) {
                if (prior.isSimilar(picked)) {
                    return;
                }
            }
            if (!canEverBeRecorded(picked)) {
                // 記録され得ない品に刻むとスタックできなくなるだけ(W-141)。詳細は canEverBeRecorded。
                return;
            }
            if (markCreativeOrigin(picked)) {
                // getItemInMainHand() は mirror なので上の書き込みで既に通っているが、
                // copy を返す実装に変わった場合に備えて書き戻す(冪等)。
                player.getInventory().setItemInMainHand(picked);
            }
        });
    }

    /**
     * このスタックが<b>そもそも図鑑に記録され得るか</b>(2026-08-19 W-141)。
     *
     * <p><b>なぜこの門が必要か</b>: 実サーバ報告「クリエイティブユーザーが持ったアイテムが
     * サバイバルユーザーの入手品とスタックされない(バニラのアイテムであっても)」。
     * 出自マーカーは PDC なので、刻んだスタックは<b>同種の無印スタックと永久に合体しない</b>
     * ({@link #onCreativeSet} の javadoc にも副作用として書いてあった)。石でも鉄インゴットでも
     * クリエイティブのアイテム欄から出した瞬間に刻まれていたため、サバイバル側の同じ品と
     * 積めなくなっていた。
     *
     * <p><b>刻まなくても抜け道にならない理由</b>: 記録経路({@link #onPickup} /
     * {@link #scanInventory})は {@link #catalogIdOf} が解決できたIDしか登録しない。
     * つまり<b>解決できないスタックは印が無くても最初から記録されない</b>ので、印は純粋に
     * 「スタックしなくなる」という副作用だけを生んでいた。監視対象(監視バニラ Material /
     * カタログ品 / Ars 品)にだけ絞れば、塞ぎたい「持っていない品を無料で図鑑に載せる」経路は
     * そのまま塞がったままになる。
     */
    private boolean canEverBeRecorded(ItemStack stack) {
        return catalogIdOf(stack).isPresent();
    }

    /**
     * {@link #canEverBeRecorded} の判定本体を {@link ItemStack} から切り離した純関数
     * (テストの理由は {@link #resolveEntryId} の javadoc と同じ)。
     * <b>「刻む条件」と「記録する条件」を同じ1本の関数で決める</b>ので、片方だけ直して
     * 食い違う(印は付くのに記録されない/その逆)ということが起きない。
     */
    static boolean marksCreativeOrigin(String materialName, boolean hasItemMeta,
                                       MetaFacts facts, Set<String> watched) {
        return resolveEntryId(materialName, hasItemMeta, facts, watched).isPresent();
    }

    /**
     * クリエイティブ由来マーカーを刻む。既に刻まれていれば何もしない(PDC 書き込みを繰り返さない)。
     *
     * @return meta を書き換えたら true
     */
    private static boolean markCreativeOrigin(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        ItemData data = ItemData.of(meta);
        if (data.creativeOrigin()) {
            return false;
        }
        data.markCreativeOrigin();
        stack.setItemMeta(meta);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scanInventory(player);
        }
    }

    /**
     * 参加時の全スロット走査。<b>数十tick遅らせてから</b>行う(2026-07-31)。
     *
     * <p><b>なぜ遅延が必要か</b>: 資源サーバ分離構成ではプレイヤーのインベントリと PDC を
     * HuskSync が同期しており、<b>snapshot の適用は {@link PlayerJoinEvent} より後に起きうる</b>
     * (DB/Redis からの取得を待つため)。参加した瞬間に読むと
     * <ul>
     *   <li>まだ前サーバの分が入っていないインベントリを走査してしまい、</li>
     *   <li>遡り登録フラグ({@code PLAYER_COLLECTION_BACKFILL_DONE})も同期前の値で読む</li>
     * </ul>
     * ことになる。後者は「毎回の参加が遡り扱いになる」＝新規登録の通知が永久に出ない形で
     * 黙って壊れる。走査を遅らせれば、読むのも書くのも snapshot 適用後になる。
     *
     * <p>通知の抑止条件は走査側({@link #scanInventory})に寄せてあるので、この経路と
     * インベントリ閉時の経路で挙動は同じになる。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.catalogItemsEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin == null) {
            scanInventory(player);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                scanInventory(player);
            }
        }, JOIN_SCAN_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        if (!config.mobKillsEnabled()) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player || excluded(killer)) {
            return;
        }
        service.record(killer, Set.of(CollectionService.mobEntryId(event.getEntityType().name())));
    }

    /** インベントリ全スロットの差分登録。通知の抑止は {@link #recordWithBackfillGate} に寄せてある。 */
    private void scanInventory(Player player) {
        if (!config.catalogItemsEnabled() || excluded(player)) {
            return;
        }
        // 監視集合はスロットごとに組み直さない。K-11 の修正後は素のバニラ品も全部この経路を
        // 通るため、41スロット × 全エントリの Material.matchMaterial が毎回走ってしまう。
        Set<String> watched = watchedConfigIds();
        Map<String, Integer> ids = new LinkedHashMap<>();
        // getContents() はメイン36 + 防具4 + オフハンドの全スロットを含む。
        for (ItemStack stack : player.getInventory().getContents()) {
            catalogIdOf(stack, watched).ifPresent(id -> {
                String entryId = CollectionService.itemEntryId(id);
                int quality = qualityOf(stack);
                ids.merge(entryId, quality, Math::max);
            });
        }
        recordWithBackfillGate(player, ids);
    }

    /**
     * アイテム記録の唯一の出口。<b>遡り登録の通知抑止</b>をここに集約する。
     *
     * <p><b>抑止が必要な理由</b>: K-11 の修正で「既に持っていた素のバニラ品」が一斉に
     * 記録可能になったため、そのままだと1件ごとの「図鑑に登録」チャットが最大16行流れ、
     * {@code reward-tiers} の t3(60)/t4(120)/t5(200) を跨いだ分だけ {@code broadcast: true} の
     * サーバー全体告知が連続発火して<b>事故に見える</b>。そこで
     * <b>「そのプレイヤーで最初に1件以上記録した記録」だけを静かに行う</b>
     * (報酬そのものは通常どおり付与する)。
     *
     * <p>フラグを「初回参加の走査」で消費してはいけない。遺物系(エリトラ/トーテム/レコード/
     * バナー模様)はチェストやエンダーチェストにしまってあることが多く、初回参加の走査は
     * <b>0件で終わってフラグだけ焼かれる</b>。すると後でチェストから出した瞬間に、抑止したかった
     * 通知の束がそのまま出る。<b>記録が発生した走査だけがフラグを消費する</b>ことで、
     * 参加時でもインベントリ閉時でも「最初のまとまった追い付き分」を静かに通せる。
     *
     * <p><b>2026-07-31: 拾得({@link #onPickup})もこの抑止に揃えた</b> — 「拾得は常に通知」という
     * 従来の流儀を意図的に変えている。理由は、抑止が走査経路だけだと<b>地面を経由する取り出し</b>が
     * すり抜けるため。エンダーチェストから遺物をカーソルへ取って GUI を Esc/E で閉じるとカーソルの
     * スタックは地面へ落ちて即座に拾い直しになり({@code getContents()} はカーソルを含まないので
     * 走査側では見えない)、この経路が {@link #onPickup} を通る。結果、1件ずつ取り出す操作では
     * 抑止したかった通知の束と報酬ティアの全体告知がそのまま出るうえ、フラグは未消費のまま残るので
     * 後続のまとめ走査だけが無音になり、<b>通知の出方が経路依存で一貫しなくなる</b>。
     *
     * <p><b>代償の正確な記述(2026-08-01 に訂正)</b>: 「初回の正当な拾得1件が静かになるだけ」は
     * <b>過小申告だった</b>。{@link #resolveEntryId} の PDC 刻印分岐は監視集合を通さないので、
     * <b>TF カタログ品(約360件。{@code mob_parts} の素材など日常的に拾う品を含む)を1個拾うだけで
     * フラグが焼ける</b>。手持ちが空の状態で参加したプレイヤーがモブ素材を1個拾って静かに
     * フラグを消費し、そのあとチェストから遺物16件を出した走査が {@code announce = true} で通ると、
     * 16行のチャットと {@code reward-tiers} t1/t2/t3 の全体ブロードキャストが出る
     * ── {@code tmp/decisions.md} の D8 が避けたかった burst そのものが1回だけ通りうる。
     *
     * <p>それでもこの形にしているのは、抑止を走査経路だけに戻すと上記の「地面経由の取り出し」が
     * 一貫しなくなるため。実際には {@link #onJoin} の 40tick 後の走査が(装備しているカタログ武器を
     * 拾って)先にフラグを焼くことが多いので、この窓に入るのは「手持ちが完全に空で参加し、
     * 最初に拾うのがカタログ品で、そのあと初めて遺物をまとめて出す」場合に限られる。
     * <b>burst を完全に消したいなら、フラグではなく「1回の記録で N 件を超えたらまとめて1行」に
     * する方向</b>(通知そのものの整形)でなければ閉じない。
     */
    private void recordWithBackfillGate(Player player, Map<String, Integer> ids) {
        if (ids.isEmpty()) {
            return;
        }
        PlayerData data = PlayerData.of(player);
        boolean retroactive = !data.collectionBackfillDone();
        int newlyAdded = service.record(player, ids, !retroactive);
        if (retroactive && newlyAdded > 0) {
            // 実際に記録が発生した経路だけがフラグを消費する。品質ptの更新だけ(newlyAdded == 0)や
            // 既知エントリしか無かった場合は消費しない — 抑止したい「まとまった追い付き」は
            // まだ来ていないため。
            data.markCollectionBackfillDone();
        }
    }

    /**
     * 図鑑への記録対象外となるゲームモード。TF の進行系は
     * {@code EquipmentDurabilityService} / {@code ChainBreakSupport} /
     * {@code ArsMagicExperienceListener} / {@code BreedingBonusListener} /
     * {@code GatheringExtraDropListener} / {@code NativeSkillExperienceListener} と
     * 同じ流儀でクリエイティブ/スペクテイターを外す。
     *
     * <p>図鑑では特に重要で、{@code collection.yml} の {@code items.structure} 16件
     * (ECHO_SHARD / DRAGON_EGG / ELYTRA / TOTEM_OF_UNDYING / SNIFFER_EGG …)は
     * <b>全部クリエイティブインベントリから1クリックで取り出せる素のバニラ品</b>。
     * 除外しないと並べてインベントリを閉じるだけで16件が一括登録され、報酬ティアの
     * t1(10)/t2(30) を無条件に跨ぎ t3(60) の全体ブロードキャストにも寄る
     * ({@code ops/RUNBOOK.md} が「メインの world は creative」と書いているので想定外の環境ではない)。
     * 討伐側も同じで、クリエイティブなら任意のモブを即殺できる。
     *
     * <p><b>このゲートだけでは足りない</b>(2026-07-31)。見ているのは「そのフレームのゲームモード」
     * だけなので、クリエイティブで並べてからサバイバルへ移って走査させる経路は素通りする。
     * アイテム側の出自マーカー({@link #onCreativeSet} / {@link #onPickup})と<b>両方</b>で塞ぐ。
     * 詳細はクラス javadoc。
     */
    private static boolean excluded(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    /** 品質ptの取得(0-100)。品質PDC未刻印(Ars/vanilla)なら0。 */
    private static int qualityOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return 0;
        }
        return ItemData.of(stack.getItemMeta()).quality();
    }

    private Optional<String> catalogIdOf(ItemStack stack) {
        return catalogIdOf(stack, watchedConfigIds());
    }

    /**
     * {@link ItemStack} から「判定に必要な事実」を取り出して {@link #resolveEntryId} に渡すだけの
     * 薄いアダプタ。分岐順序はここに書かない。
     *
     * <p>{@code hasItemMeta()} が false のときは {@code getItemMeta()} を<b>呼ばない</b>。
     * meta を持たないスタックには PDC も CustomModelData も存在しえないし、CraftBukkit の
     * {@code getItemMeta()} は毎回 {@code CraftMetaItem} を新規生成するので、41スロット走査の
     * 前置フィルタとして性能上の意味もある(この性能上の意味が、K-11 で早期 return が
     * メソッド冒頭に残り続けた理由でもある)。
     */
    private Optional<String> catalogIdOf(ItemStack stack, Set<String> watched) {
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        return resolveEntryId(stack.getType().name(), meta != null,
                meta == null ? null : metaFactsOf(stack, meta), watched);
    }

    /**
     * 図鑑エントリIDの解決本体。<b>この分岐順序が K-11 の本質</b>なので、{@link ItemStack} から
     * 切り離して純関数にしてある。
     *
     * <p><b>なぜ純関数に切り出す必要があるのか</b>: MockBukkit 4.110.0 の
     * {@code ItemStackMock} はコンストラクタで {@code itemMeta} を無条件に代入し、
     * {@code hasItemMeta()} は {@code itemMeta != null && !ItemFactoryMock.equals(itemMeta, null)}
     * (中身は {@code Objects.equals}) を返すため、<b>素のスタックでも常に true</b> になる。
     * 実機の {@code CraftItemStack#hasItemMeta()} は {@code getComponentsPatch().isEmpty()} を
     * 見るので無傷のバニラ品は false。つまり MockBukkit を通したテストでは
     * <b>本番で壊れているコードが緑になる</b>(K-11 が実際にそうだった。SKIPPED 素通りと違い
     * アサーションが通ってしまうので更に危険)。さらに偽装したスタックを
     * {@code Inventory#setItem/addItem} や {@code Item} エンティティ経由で渡すと
     * すべて {@code ItemStack.clone()}(= {@code craftDelegate.clone()})やミラー包みで
     * ラッパのクラスが消えるため、インベントリ経路では本番条件を作れない。
     * <b>meta の有無を引数で受けるこの純関数だけが、MockBukkit に一切依存せずに
     * 「meta 無しスタック」の分岐を検証できる。</b>
     *
     * @param materialName スタックの Material 名(大文字)。AIR/null はアダプタ側で弾いている
     * @param hasItemMeta  実機 {@code CraftItemStack#hasItemMeta()} 相当。
     *                     <b>false は「記録対象外」ではなく「Material 判定だけで決まる」</b>という意味
     * @param facts        meta 由来の事実。{@code hasItemMeta} が false なら参照されない({@code null} 可)
     * @param watched      {@link #watchedConfigIds()} の監視集合
     */
    static Optional<String> resolveEntryId(String materialName, boolean hasItemMeta,
                                          MetaFacts facts, Set<String> watched) {
        if (!hasItemMeta) {
            // 素のバニラ品。PDC も CMD も存在しえないので Material 判定だけで決まる。
            // ここを早期 return(= Optional.empty())にしていたのが K-11。
            // 出自マーカーも PDC なので、meta が無いスタックには原理的に付いていない。
            return trackedVanillaId(materialName, watched);
        }
        if (facts.creativeOrigin()) {
            // クリエイティブ由来。ゲームモードゲートを素通りする「creative→survival 持ち込み」を
            // ここで落とす。カタログ刻印/Ars刻印/CMD より先に見るのは、クリエイティブインベントリから
            // 出せるのは素のバニラ品だけとは限らず(中クリック複製ならカスタム品も出せる。
            // その経路は onPickBlock / onPickEntity で刻む)、刻印の有無に関わらず出自が
            // 優先されるべきだから。詳細はクラス javadoc。
            return Optional.empty();
        }
        Optional<String> stamped = facts.stampedCatalogId();
        if (stamped.isPresent()) {
            return stamped;
        }
        // ArsPaper 側で定義したアイテム。2026-07-31 まで見ていなかったため、materials.yml /
        // sourcejars.yml 由来のアイテム(モブドロップ素材17件・ダンジョン踏破の証22件・
        // ソースの階梯9件 = 図鑑カテゴリ116件中48件)が永久に記録されず、図鑑に
        // 「絶対に埋まらない枠」として並び続けていた。
        //
        // この2つの PDC 判定は必ず Material 判定より先。warden_tendril / reality_thread_core は
        // base が ECHO_SHARD、source_condenser は HEART_OF_THE_SEA で、どちらの Material も
        // items.structure に載っているので、順序を逆にするとカスタム側が潰れる。
        Optional<String> arsId = facts.arsItemId();
        if (arsId.isPresent()) {
            // バニラ Material と同じく設定から参照されているIDだけに絞る。Ars の登録アイテムは
            // グリフ120件を含めて300件超あり、無条件に記録するとプレイヤーPDCがそれだけ膨らみ、
            // かつ図鑑の報酬ティア(10/30/60/120/200件)の重みが黙って変わってしまう。
            return watched.contains(arsId.get()) ? arsId : Optional.empty();
        }
        Integer cmd = facts.customModelData();
        if (cmd == null) {
            return trackedVanillaId(materialName, watched);
        }
        Optional<String> template = facts.catalogTemplateId(cmd);
        return template.isPresent() ? template : trackedVanillaId(materialName, watched);
    }

    /**
     * meta を持つスタックから読める事実の遅延アクセサ。{@link #resolveEntryId} を
     * {@link ItemStack} から切り離すための seam であり、meta を持たないスタックでは
     * どのメソッドも呼ばれない。遅延にしているのは、上位の判定で決着したときに
     * 下位のルックアップ(カタログ照合など)を走らせないため。
     */
    interface MetaFacts {

        /**
         * クリエイティブ由来マーカー({@code trinityforge:creative_origin})が刻まれているか。
         * true なら図鑑判定から丸ごと外す(クラス javadoc の「ゲームモードゲートだけでは
         * 構造的に閉じない」)。
         */
        boolean creativeOrigin();

        /** TF カタログの PDC 刻印 ({@code trinityforge:catalog_id})。 */
        Optional<String> stampedCatalogId();

        /** ArsPaper の PDC 刻印 ({@code arspaper:custom_item_id})。 */
        Optional<String> arsItemId();

        /** CustomModelData。未設定なら {@code null}。 */
        Integer customModelData();

        /** material + CustomModelData がカタログテンプレートに一致すればそのID。 */
        Optional<String> catalogTemplateId(int customModelData);
    }

    private MetaFacts metaFactsOf(ItemStack stack, ItemMeta meta) {
        return new MetaFacts() {
            @Override
            public boolean creativeOrigin() {
                return ItemData.of(meta).creativeOrigin();
            }

            @Override
            public Optional<String> stampedCatalogId() {
                return CatalogIdentity.catalogIdOf(meta);
            }

            @Override
            public Optional<String> arsItemId() {
                return CrossPluginItemResolver.arsIdOf(stack);
            }

            @Override
            public Integer customModelData() {
                return DerivedItemStats.customModelDataOf(meta);
            }

            @Override
            public Optional<String> catalogTemplateId(int customModelData) {
                return CatalogIdentity.find(catalog, stack.getType(), customModelData).map(t -> t.id());
            }
        };
    }

    /** 設定から参照されている Material だけ {@code item:<MATERIAL>} として図鑑に載せる。 */
    private static Optional<String> trackedVanillaId(String materialName, Set<String> watched) {
        if (materialName == null || materialName.isBlank()) {
            return Optional.empty();
        }
        return watched.contains(materialName) ? Optional.of(materialName) : Optional.empty();
    }

    /**
     * 図鑑カテゴリとアチーブメントのアイテム条件に書かれたIDの集合。
     *
     * <p>Material 名(大文字)と ArsPaper のカスタムID(小文字)の両方が入る。両者は表記が衝突しないので
     * 1つの集合で足りる。TF カタログIDも混ざるが、カタログ品はこの集合を経由せず PDC 刻印で
     * 記録されるため実害はない。
     *
     * <p><b>キャッシュの無効化は config snapshot のインスタンス同一性で行う</b>(2026-07-31)。
     * {@code CollectionConfig#itemCategories} と {@code AchievementsConfig#achievements} は
     * どちらも volatile な snapshot をそのまま返し、reload はその<b>リストを丸ごと差し替える</b>ので、
     * 参照が変わっていなければ内容も変わっていない。
     *
     * <p>キャッシュが必要な理由: {@link #onPickup} は MONITOR で<b>拾得1件ごとに</b>走るのに、
     * この集合は約120件の {@code itemCategories} エントリと36件のアチーブメントを走査し、
     * 各エントリで {@code Material.matchMaterial}(内部で正規化のため {@code Pattern.compile} が
     * 2回走る)を呼ぶ。連鎖採掘の落下物回収・モブファーム・複数人同時で 1〜3ms/tick が消える。
     *
     * <p><b>「reload で捨てる」を明示的な呼び出しにしないこと</b>: 捨て忘れると
     * 「editor で図鑑を編集して保存しても反映されない」という、エラーの出ない別のバグになる。
     * snapshot の同一性で判定すれば忘れようがない。
     */
    private Set<String> watchedConfigIds() {
        List<CollectionConfig.Category> categories = config.itemCategories();
        List<AchievementsConfig.Achievement> achievementList = achievements.achievements();
        WatchedSnapshot cached = watchedCache;
        if (cached != null && cached.categories() == categories
                && cached.achievements() == achievementList) {
            return cached.watched();
        }
        Set<String> watched = buildWatchedConfigIds(categories, achievementList);
        watchedCache = new WatchedSnapshot(categories, achievementList, watched);
        return watched;
    }

    /** 監視集合の組み立て本体。戻り値は不変(キャッシュとして共有されるため)。 */
    private static Set<String> buildWatchedConfigIds(
            List<CollectionConfig.Category> categories,
            List<AchievementsConfig.Achievement> achievementList) {
        Set<String> watched = new LinkedHashSet<>();
        for (CollectionConfig.Category category : categories) {
            category.entries().forEach(entry -> addWatched(watched, entry));
        }
        for (AchievementsConfig.Achievement achievement : achievementList) {
            AchievementsConfig.Trigger trigger = achievement.trigger();
            if (trigger == null || !"item".equals(trigger.collectionScope())) {
                continue;
            }
            trigger.collectionTargets().forEach(target -> addWatched(watched, target));
        }
        return Set.copyOf(watched);
    }

    /**
     * Material なら正規化した Material 名、そうでなければそのままカスタムIDとして登録する。
     * editor が付ける {@code custom:} 接頭辞は落とす(他ドメインと同じ扱い)。
     */
    private static void addWatched(Set<String> out, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String token = raw.trim();
        if (token.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
            token = token.substring(CUSTOM_PREFIX.length()).trim();
        }
        if (token.isEmpty()) {
            return;
        }
        Material material = Material.matchMaterial(token.toUpperCase(Locale.ROOT));
        out.add(material != null && material.isItem() ? material.name() : token);
    }
}
