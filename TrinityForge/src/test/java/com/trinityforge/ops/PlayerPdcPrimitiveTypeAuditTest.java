package com.trinityforge.ops;

import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 資源サーバ分離の事前検証: <b>プレイヤー PDC が HuskSync で同期できる形をしていること</b>を
 * サーバを立てずに確定させる（作業計画 T2）。
 *
 * <p>2サーバ構成では、TF の進行データのうち スキルLv／ポイント／パーク は SQLite（ディレクトリ共有）で
 * 渡るが、<b>プレステージ・図鑑・実績・称号・ガチャ天井・採取トグル</b>はプレイヤー PDC
 * （= {@code world/playerdata/&lt;uuid&gt;.dat}）にあり、これは HuskSync の {@code persistent_data}
 * 同期に頼るしかない。そしてその同期は <b>primitive な {@link PersistentDataType} しか扱えない</b>。
 *
 * <p>したがって「TF のプレイヤー PDC が全て primitive である」ことは 2サーバ構成の前提そのものであり、
 * 誰かが {@code TAG_CONTAINER} などの複合型を1つ足した瞬間に、そのデータだけがサーバ移動で
 * <b>無言で消える</b>。本テストはその日を出荷前に検出するためのガードである。
 *
 * <p>ArsPaper 側のマナ／グリフ解放 PDC は別モジュールでこのテストのクラスパスに無いため、ここでは
 * 検証できない（{@code com.arspaper.mana.ManaManager} は INTEGER / LONG / BYTE のみを使っており、
 * 目視確認済み。詳細は {@code ops/RUNBOOK.md}）。
 */
class PlayerPdcPrimitiveTypeAuditTest {

    /**
     * HuskSync の {@code persistent_data} が復元できる型。複合型（{@code TAG_CONTAINER} /
     * {@code TAG_CONTAINER_ARRAY}）と、プラグイン独自の {@code PersistentDataType} 実装は入らない。
     */
    private static final Map<String, PersistentDataType<?, ?>> SYNCABLE_TYPES = new LinkedHashMap<>();

    static {
        SYNCABLE_TYPES.put("BYTE", PersistentDataType.BYTE);
        SYNCABLE_TYPES.put("SHORT", PersistentDataType.SHORT);
        SYNCABLE_TYPES.put("INTEGER", PersistentDataType.INTEGER);
        SYNCABLE_TYPES.put("LONG", PersistentDataType.LONG);
        SYNCABLE_TYPES.put("FLOAT", PersistentDataType.FLOAT);
        SYNCABLE_TYPES.put("DOUBLE", PersistentDataType.DOUBLE);
        SYNCABLE_TYPES.put("STRING", PersistentDataType.STRING);
        SYNCABLE_TYPES.put("BYTE_ARRAY", PersistentDataType.BYTE_ARRAY);
        SYNCABLE_TYPES.put("INTEGER_ARRAY", PersistentDataType.INTEGER_ARRAY);
        SYNCABLE_TYPES.put("LONG_ARRAY", PersistentDataType.LONG_ARRAY);
    }

    /** ガチャ天井は プールID 毎に動的導出されるため {@code PdcKeys} に定数が無い。代表プールで検証する。 */
    private static final String SAMPLE_GACHA_POOL = "ops-audit-pool";

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
    @DisplayName("プレイヤー PDC の全キーが HuskSync で同期可能な primitive 型である")
    void everyPlayerPdcValueUsesASyncablePrimitiveType() {
        Player player = server.addPlayer();
        writeEveryPlayerOwnedValue(player);

        PersistentDataContainer container = player.getPersistentDataContainer();
        Set<NamespacedKey> written = container.getKeys();
        assertFalse(written.isEmpty(), "テストが 1 件も PDC を書けていない（検証が空振りしている）");

        List<String> unsyncable = new ArrayList<>();
        for (NamespacedKey key : written) {
            if (resolveSyncableTypeName(container, key) == null) {
                unsyncable.add(key.getKey());
            }
        }

        assertTrue(unsyncable.isEmpty(),
                "HuskSync の persistent_data で復元できない型のプレイヤー PDC がある。"
                        + "2サーバ構成ではこれらの値がサーバ移動で無言で失われる: " + new TreeSet<>(unsyncable));
    }

    @Test
    @DisplayName("PdcKeys の PLAYER_* キーが漏れなく本監査の対象になっている")
    void everyPlayerScopedKeyIsCoveredByTheAudit() {
        Player player = server.addPlayer();
        writeEveryPlayerOwnedValue(player);

        Set<String> written = new TreeSet<>();
        for (NamespacedKey key : player.getPersistentDataContainer().getKeys()) {
            written.add(key.getKey());
        }

        List<String> missing = new ArrayList<>();
        for (NamespacedKey declared : declaredPlayerScopedKeys()) {
            if (!written.contains(declared.getKey())) {
                missing.add(declared.getKey());
            }
        }

        assertTrue(missing.isEmpty(),
                "PdcKeys に PLAYER_* キーが追加されたが、本監査が書き込んでいない。"
                        + "writeEveryPlayerOwnedValue に追記して、HuskSync で同期できる型か確認すること: "
                        + new TreeSet<>(missing));
    }

    @Test
    @DisplayName("採取トグルの既定 ON が PDC 未設定でも保たれる（サーバ移動で誤って OFF にならない）")
    void gatheringTogglesDefaultToEnabledWithoutAnyPdcValue() {
        // 資源サーバへ初めて渡ったプレイヤーは、その時点では PDC がまだ同期されていない瞬間がある。
        // その状態で「未設定=OFF」と読むと一括破壊/伐採が黙って止まる。既定 ON であることを固定する。
        PlayerData data = PlayerData.of(server.addPlayer());

        assertTrue(data.veinMiningEnabled(), "vein-mining は PDC 未設定時に ON でなければならない");
        assertTrue(data.treeFellEnabled(), "tree-fell は PDC 未設定時に ON でなければならない");
        assertTrue(data.autoReplantEnabled(), "auto-replant は PDC 未設定時に ON でなければならない");
        assertTrue(data.areaHarvestEnabled(), "area-harvest は PDC 未設定時に ON でなければならない");
    }

    @Test
    @DisplayName("書き込んだ値がサーバ移動相当の型往復を経ても壊れない")
    void writtenValuesSurviveATypeRoundTrip() {
        Player source = server.addPlayer();
        writeEveryPlayerOwnedValue(source);

        // HuskSync は「キー → primitive 型 → 値」を取り出して転送先で書き戻す。同じことを手で行い、
        // 転送先プレイヤーで元の値が読めることを確認する。
        Player destination = server.addPlayer();
        copyContainerAsHuskSyncWould(source.getPersistentDataContainer(),
                destination.getPersistentDataContainer());

        PlayerData restored = PlayerData.of(destination);
        assertEquals(3, restored.prestigeCount(), "プレステージ回数が往復で失われた");
        assertEquals(List.of("perk.a", "perk.b"), restored.heldPerks(), "所持パークが往復で失われた");
        assertEquals(List.of("item:sword"), restored.collectionEntries(), "図鑑エントリが往復で失われた");
        assertEquals(List.of("ach.first_kill"), restored.achievedIds(), "実績が往復で失われた");
        assertEquals(7, restored.gachaPityCount(SAMPLE_GACHA_POOL), "ガチャ天井カウンタが往復で失われた");
        assertFalse(restored.veinMiningEnabled(), "採取トグル(OFF)が往復で失われた");
    }

    // ------------------------------------------------------------------------------------------

    /**
     * {@link PdcKeys} が宣言する「プレイヤー所有」キーの一覧。命名規約 {@code PLAYER_*} が唯一の分類軸で、
     * これは {@code PdcKeys} 自身のセクションコメント（Item / Player / Mob）と一致している。
     */
    private static List<NamespacedKey> declaredPlayerScopedKeys() {
        List<NamespacedKey> keys = new ArrayList<>();
        for (Field field : PdcKeys.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            if (field.getType() != NamespacedKey.class || !field.getName().startsWith("PLAYER_")) {
                continue;
            }
            try {
                keys.add((NamespacedKey) field.get(null));
            } catch (IllegalAccessException ex) {
                throw new AssertionError("PdcKeys." + field.getName() + " が読み取れない", ex);
            }
        }
        return keys;
    }

    /** {@link PdcKeys} の全 {@code PLAYER_*} キーに値を入れる。新キー追加時はここも足すこと。 */
    private static void writeEveryPlayerOwnedValue(Player player) {
        PlayerData data = PlayerData.of(player);
        data.setPrestigeCount(3);
        data.setHeldPerks(List.of("perk.a", "perk.b"));
        data.setLockedPerks(List.of("perk.a"));
        data.setRoles("tank", "healer");
        // ロール変更クールダウンの刻印(2026-07-31)。サーバ間で同期されないと、
        // 資源サーバへ渡って戻るだけで待ち時間がリセットされる迂回路になる。
        data.setRolePrimaryChangedAt(1_700_000_000_000L);
        data.setRoleSupportChangedAt(1_700_000_000_000L);
        data.setCollectionEntries(List.of("item:sword"));
        data.setClaimedCollectionTiers(List.of("tier.1"));
        data.grantSpecialReward("reward.banner");
        data.setEquippedTitle("title.veteran");
        data.setEquippedParticle("particle.flame");
        data.setHideOthersCosmetics(true);
        data.setVeinMiningEnabled(false);
        data.setTreeFellEnabled(false);
        data.setAutoReplantEnabled(false);
        data.setAreaHarvestEnabled(false);
        data.markAchieved("ach.first_kill");
        data.setGachaPityCount(SAMPLE_GACHA_POOL, 7);

        // PlayerData を経由しないプレイヤー PDC。書き手のクラスと同じ型で書く。
        player.getPersistentDataContainer().set(
                PdcKeys.PLAYER_ADDON_COMBAT_STATS, PersistentDataType.STRING, "attack-power=5;defense-rate=0.1");
        player.getPersistentDataContainer().set(
                PdcKeys.PLAYER_DIGGING_DURABILITY_ACCUM, PersistentDataType.LONG, 1234L);
    }

    /** HuskSync 相当のコピー: キーごとに「反応する primitive 型」を探し、その型で読んで書き戻す。 */
    private static void copyContainerAsHuskSyncWould(PersistentDataContainer from, PersistentDataContainer to) {
        for (NamespacedKey key : from.getKeys()) {
            String typeName = resolveSyncableTypeName(from, key);
            if (typeName == null) {
                throw new AssertionError("primitive 型に解決できないキー: " + key);
            }
            copyAs(from, to, key, SYNCABLE_TYPES.get(typeName));
        }
    }

    @SuppressWarnings("unchecked")
    private static <P, C> void copyAs(PersistentDataContainer from, PersistentDataContainer to,
                                      NamespacedKey key, PersistentDataType<?, ?> rawType) {
        PersistentDataType<P, C> type = (PersistentDataType<P, C>) rawType;
        C value = from.get(key, type);
        if (value != null) {
            to.set(key, type, value);
        }
    }

    /** {@code key} が反応する {@link #SYNCABLE_TYPES} の名前。どれにも反応しなければ {@code null}。 */
    private static String resolveSyncableTypeName(PersistentDataContainer container, NamespacedKey key) {
        for (Map.Entry<String, PersistentDataType<?, ?>> candidate : SYNCABLE_TYPES.entrySet()) {
            if (container.has(key, candidate.getValue())) {
                return candidate.getKey();
            }
        }
        return null;
    }
}
