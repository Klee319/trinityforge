package com.trinityforge.mobs;

import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Shared dungeon entry evaluation for TF teleports, EliteMobs instanced-dungeon joins, and
 * (D3 topology) in-place 区画ダンジョン boundary crossings.
 * Single SoT: {@code dungeon/gates.yml} keyed by world name with optional content-package aliases
 * and an optional {@code region:} boundary.
 */
public final class DungeonGateService {

    private static final Logger LOG = Logger.getLogger(DungeonGateService.class.getName());
    private static final String ADMIN_PERMISSION = "trinityforge.admin";
    private static final String ELITEMOBS_TOOLING_PERMISSION = "trinityforge.elitemobs.commands";
    private static final Component UNCONFIGURED_GATE = Component.text(
            "このダンジョンは入場ゲートが設定されていないため入場できません。",
            NamedTextColor.RED);

    private final DungeonGateConfig gateConfig;
    private final SymmetricCombatService combatService;
    private final GateKeyMatcher keyMatcher;

    /**
     * 2026-07-27 カスタムアイテム鍵対応: 解決不能な {@code key-item} を持つゲートについて、
     * 1回だけ警告ログを出すためのマーカー集合(ゲート名+ワールド名で十分な粒度、ゲート単位で
     * 二度と警告しない。毎tick評価されうる区画ゲートでログが溢れるのを防ぐ)。
     */
    private final Set<String> unresolvedKeyWarned = ConcurrentHashMap.newKeySet();

    /**
     * 2026-07-27 鍵アイテムGUI入場対応: GUI確定処理で既にレベル/鍵を検証・消費した直後のテレポートが、
     * 到着時の {@code checkEntry}/{@code checkRegionEntry} で二重に判定・二重消費されるのを防ぐための
     * 「次の1回だけ無条件で通す」使い切りパス。キーは {@code (playerId, gate.world())}、値は失効時刻
     * (エポックミリ秒)。発行から必ず期限を持たせる(テレポートが起きなかった場合に無料入場化しないため)。
     */
    private static final long ONE_TIME_PASS_TTL_MILLIS = 10_000L;
    private final Map<UUID, Map<String, Long>> oneTimePasses = new ConcurrentHashMap<>();

    public DungeonGateService(DungeonGateConfig gateConfig, SymmetricCombatService combatService,
                              CrossPluginItemResolver itemResolver) {
        this.gateConfig = Objects.requireNonNull(gateConfig, "gateConfig");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.keyMatcher = new GateKeyMatcher(Objects.requireNonNull(itemResolver, "itemResolver"));
    }

    /** {@link GateKeyMatcher} の共有インスタンス({@code DungeonEntryGui} が表示用に再利用する)。 */
    public GateKeyMatcher keyMatcher() {
        return keyMatcher;
    }

    /**
     * Checks whether {@code player} may enter the dungeon identified by {@code lookupKey} (world name
     * or content-package alias). Sends denial messages and consumes the key on success.
     *
     * @return {@code true} if entry is allowed (or ungated), {@code false} if denied
     */
    public boolean checkEntry(Player player, String lookupKey) {
        if (player == null || lookupKey == null || lookupKey.isBlank()) {
            return true;
        }
        Optional<DungeonGate> gateOpt = gateConfig.resolve(lookupKey);
        if (gateOpt.isEmpty()) {
            return true;
        }
        return evaluate(player, List.of(gateOpt.get()), true, true);
    }

    /**
     * Returns whether a world/content-package has an entry gate, without evaluating requirements or
     * consuming a key. EliteMobs uses this before opening a dungeon browser so an unconfigured public
     * {@code /em dungeontp} route can be rejected while ordinary EliteMobs teleports remain unaffected.
     */
    public boolean hasEntryGate(String lookupKey) {
        return lookupKey != null
                && !lookupKey.isBlank()
                && gateConfig.resolve(lookupKey).isPresent();
    }

    /**
     * Preflights a required EliteMobs dungeon entry without consuming its key. The committed join
     * must still call {@link #checkRequiredEntry(Player, String)} so level/inventory changes between
     * browser selection and instance creation are checked again.
     */
    public boolean previewRequiredEntry(Player player, String lookupKey) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission(ADMIN_PERMISSION)
                || player.hasPermission(ELITEMOBS_TOOLING_PERMISSION)) {
            return true;
        }
        // checkRequiredEntry と同じ理由でゲート0本なら素通し。ここだけ拒否すると
        // 「一覧では入れないのに参加はできる」という食い違いが出る。
        if (!gateConfig.hasAnyGate()) {
            return true;
        }
        if (lookupKey == null || lookupKey.isBlank()) {
            player.sendMessage(UNCONFIGURED_GATE);
            return false;
        }
        Optional<DungeonGate> gateOpt = gateConfig.resolve(lookupKey);
        if (gateOpt.isEmpty()) {
            player.sendMessage(UNCONFIGURED_GATE);
            return false;
        }
        return evaluate(player, List.of(gateOpt.get()), true, false);
    }

    /**
     * EliteMobs のダンジョン作成・参加経路向けの必須ゲート判定。
     *
     * <p>通常のワールド移動用 {@link #checkEntry(Player, String)} は未設定ワールドを許可するが、
     * EliteMobs の公開コマンド/NPC導線では、対応するゲートが無い状態を一般ユーザーに許可すると
     * {@code /em dungeontp} から無制限に入場できる。そのため、この入口だけは設定漏れを拒否する。
     * 管理・復旧作業用の権限保持者はゲート未設定でも通過できる。</p>
     */
    public boolean checkRequiredEntry(Player player, String lookupKey) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission(ADMIN_PERMISSION)
                || player.hasPermission(ELITEMOBS_TOOLING_PERMISSION)) {
            return true;
        }
        // ゲートを1本も定義していないサーバーでは、この入口ごと無効にする。
        // 「1本も無い」と「このダンジョンだけ書き忘れた」は別物で、前者で拒否すると
        // 出荷時の gates.yml(エントリ0本)のまま一般プレイヤーが全ダンジョンに入れなくなる。
        // 1本でも定義した時点で下の fail-close が復活し、設定漏れは従来どおり拒否される。
        if (!gateConfig.hasAnyGate()) {
            return true;
        }
        if (lookupKey == null || lookupKey.isBlank()) {
            player.sendMessage(UNCONFIGURED_GATE);
            return false;
        }
        Optional<DungeonGate> gateOpt = gateConfig.resolve(lookupKey);
        if (gateOpt.isEmpty()) {
            player.sendMessage(UNCONFIGURED_GATE);
            return false;
        }
        return evaluate(player, List.of(gateOpt.get()), true, true);
    }

    /** 区画ゲートが1つでも設定されているか(移動イベントの早期リターン用)。 */
    public boolean hasRegionGates() {
        return gateConfig.hasRegionGates();
    }

    /**
     * D3 topology: {@code from} から {@code to} への移動/テレポートが区画ゲート境界を外→内へ
     * 跨ぐ場合に入場評価する。跨いだ全ゲートを二相で評価し(先に全ゲート通過を確認してから
     * キーを消費)、拒否時は {@code notify} が真のときだけメッセージを送る(移動イベントの
     * 連射スパム防止はリスナー側のスロットルに委ねる)。
     *
     * @return {@code true} if entry is allowed (or no gated region was entered)
     */
    public boolean checkRegionEntry(Player player, Location from, Location to, boolean notify) {
        if (player == null || to == null || to.getWorld() == null) {
            return true;
        }
        List<DungeonGate> candidates = gateConfig.regionGates(to.getWorld().getName());
        if (candidates.isEmpty()) {
            return true;
        }
        String toWorld = to.getWorld().getName();
        boolean sameWorldFrom = from != null && from.getWorld() != null
                && from.getWorld().getName().equals(toWorld);
        List<DungeonGate> entered = new ArrayList<>();
        for (DungeonGate gate : candidates) {
            GateRegion region = gate.region();
            boolean inTo = region.contains(toWorld, to.getBlockX(), to.getBlockY(), to.getBlockZ());
            // 別ワールドからのテレポートは「外から」扱い(fromの座標は比較しない)。
            boolean inFrom = sameWorldFrom
                    && region.contains(toWorld, from.getBlockX(), from.getBlockY(), from.getBlockZ());
            if (inTo && !inFrom) {
                entered.add(gate);
            }
        }
        if (entered.isEmpty()) {
            return true;
        }
        return evaluate(player, entered, notify, true);
    }

    /**
     * 二相評価: まず全ゲートの通過可否を確認し(1つでも拒否なら何も消費せずfalse)、
     * {@code consume} が真なら全通過確定後にキーを消費する — 重なった区画で片方のキーだけ
     * 先に消費される事故を防ぐ。事前確認では同じ評価を行い、消費フェーズだけを省略する。
     *
     * <p>2026-07-27: 有効な一回限りの通行許可({@link #grantOneTimePass}参照)を持つゲートは、
     * このフェーズではレベル/鍵チェックを完全にスキップして無条件通過扱いにする(GUI確定処理が
     * 直前に検証・消費済みのため)。パスの消費(remove)はフェーズ2、つまり全ゲート通過が確定した
     * 後にのみ行う — 同時に評価された他のゲートでレベル不足等の拒否が起きた場合、このバッチ全体は
     * falseで返り、パスは消費されず温存される(「入場が実際に許可された時」に限りパスを消費する)。
     */
    private boolean evaluate(Player player, List<DungeonGate> gates, boolean notify, boolean consume) {
        int combatLevel = combatService.combatLevelOf(player.getUniqueId());
        UUID playerId = player.getUniqueId();
        Set<DungeonGate> passGates = new HashSet<>();
        for (DungeonGate gate : gates) {
            if (hasValidOneTimePass(playerId, gate.world())) {
                passGates.add(gate);
                continue;
            }
            boolean keyRequired = gate.keyRequired() && keyGateActuallyEnforced(gate);
            boolean hasKey = !keyRequired
                    || keyMatcher.count(player.getInventory(), gate.keyItem()) >= gate.keyAmount();
            switch (DungeonGatePolicy.evaluate(
                    gate.requiredCombatLevel(), combatLevel, keyRequired, hasKey)) {
                case UNDER_LEVEL -> {
                    if (notify) {
                        player.sendMessage(Component.text(
                                "このダンジョンに入るには combat level " + gate.requiredCombatLevel()
                                        + " が必要です（現在 " + combatLevel + "）", NamedTextColor.RED));
                    }
                    return false;
                }
                case MISSING_KEY -> {
                    if (notify) {
                        player.sendMessage(Component.text(
                                "入場には " + keyMatcher.displayName(gate.keyItem()) + " x" + gate.keyAmount()
                                        + " が必要です", NamedTextColor.RED));
                    }
                    return false;
                }
                case NONE -> {
                    // fall through to consumption phase
                }
            }
        }
        if (consume) {
            for (DungeonGate gate : gates) {
                if (passGates.contains(gate)) {
                    consumeOneTimePass(playerId, gate.world());
                } else if (gate.keyRequired() && keyGateActuallyEnforced(gate)) {
                    keyMatcher.consume(player.getInventory(), gate.keyItem(), gate.keyAmount());
                }
            }
        }
        return true;
    }

    /**
     * GUI確定処理向け: 副作用(消費)なしでそのゲートへの入場可否だけを判定する。GUI確定ボタン押下時、
     * GUIを開いた時点の判定を信用せず、その場でもう一度検証するために使う。
     */
    public DungeonGatePolicy.Denial evaluateOnly(Player player, DungeonGate gate) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gate, "gate");
        int combatLevel = combatService.combatLevelOf(player.getUniqueId());
        boolean keyRequired = gate.keyRequired() && keyGateActuallyEnforced(gate);
        boolean hasKey = !keyRequired
                || keyMatcher.count(player.getInventory(), gate.keyItem()) >= gate.keyAmount();
        return DungeonGatePolicy.evaluate(gate.requiredCombatLevel(), combatLevel, keyRequired, hasKey);
    }

    /**
     * GUI確定処理向け: 二相評価を経ない直接消費。GUI側が {@link #evaluateOnly} で検証し、転送
     * (またはEliteMobsへの委譲呼び出し)が成功したことを確認した後にだけ呼ぶこと。
     */
    public void consumeKey(Player player, DungeonGate gate) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gate, "gate");
        if (gate.keyRequired() && keyGateActuallyEnforced(gate)) {
            keyMatcher.consume(player.getInventory(), gate.keyItem(), gate.keyAmount());
        }
    }

    /**
     * GUI確定処理向け: {@code gate}への次の1回の入場判定を無条件で通す一回限りの通行許可を発行する
     * (期限{@value #ONE_TIME_PASS_TTL_MILLIS}ms)。GUI側がテレポートを試みる直前に呼ぶこと。
     */
    public void grantOneTimePass(UUID playerId, String gateWorldName) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(gateWorldName, "gateWorldName");
        oneTimePasses.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(gateWorldName, System.currentTimeMillis() + ONE_TIME_PASS_TTL_MILLIS);
    }

    private boolean hasValidOneTimePass(UUID playerId, String gateWorldName) {
        Map<String, Long> passes = oneTimePasses.get(playerId);
        if (passes == null) {
            return false;
        }
        return isPassValid(passes.get(gateWorldName), System.currentTimeMillis());
    }

    /**
     * 純粋な期限判定(パッケージ内テスト用に抽出): {@code expiresAt} が未発行(null)でなく、かつ
     * {@code nowMillis} より後であれば有効。実時間で10秒待つユニットテストを避けるため、
     * このメソッド単体を境界値でテストする。
     */
    static boolean isPassValid(Long expiresAt, long nowMillis) {
        return expiresAt != null && expiresAt > nowMillis;
    }

    private void consumeOneTimePass(UUID playerId, String gateWorldName) {
        Map<String, Long> passes = oneTimePasses.get(playerId);
        if (passes == null) {
            return;
        }
        passes.remove(gateWorldName);
        if (passes.isEmpty()) {
            oneTimePasses.remove(playerId);
        }
    }

    /**
     * 発行したパスを使わずに取り消す。転送を試みる直前にパスを発行した後、その転送自体が失敗した
     * 場合に呼ぶこと — 取り消さないと、失敗して現地に残ったプレイヤーが期限
     * ({@value #ONE_TIME_PASS_TTL_MILLIS}ms)の間だけ歩いて無料入場できる窓が空く。
     */
    public void revokeOneTimePass(UUID playerId, String gateWorldName) {
        if (playerId == null || gateWorldName == null) {
            return;
        }
        consumeOneTimePass(playerId, gateWorldName);
    }

    /** プレイヤーログアウト時のパス掃除({@code DungeonGateListener#onQuit}に相乗り)。 */
    public void clearOneTimePasses(UUID playerId) {
        if (playerId != null) {
            oneTimePasses.remove(playerId);
        }
    }

    /**
     * 2026-07-27: {@code key-item} がタイポ等でカタログ/ArsPaper/Materialいずれにも解決できない場合、
     * そのゲートの鍵要求は「所持なし(入場不可)」ではなく「鍵ゲート自体を無効(通過)」として扱う。
     * タイポでプレイヤーが永久に入れなくなる方が、ゲートが一時的に緩む(通す)より有害なため。
     * 解決不能を検知したゲートについては1回だけ warning を出す(毎回出すとログが溢れる)。
     */
    private boolean keyGateActuallyEnforced(DungeonGate gate) {
        if (keyMatcher.resolves(gate.keyItem())) {
            return true;
        }
        String warnKey = gate.world() + "|" + gate.keyItem();
        if (unresolvedKeyWarned.add(warnKey)) {
            LOG.warning(
                    "[dungeon-gate] gate '" + gate.world() + "' has key-item '" + gate.keyItem()
                            + "' that resolves to neither a TF catalog id, an ArsPaper id, nor a vanilla "
                            + "Material; the key gate is disabled (players pass through) instead of "
                            + "permanently locking them out due to a typo");
        }
        return false;
    }
}
