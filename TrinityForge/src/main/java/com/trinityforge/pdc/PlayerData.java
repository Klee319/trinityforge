package com.trinityforge.pdc;

import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Type-safe view over a player's PersistentDataContainer (ADDON_INTEGRATION_SPEC 6).
 * Holds prestige count, retained perks (the unlock truth, UNLOCK 2.1), and the player's
 * combat role pair (primary + support, ROLE spec).
 *
 * <p>Held perks are persisted as a single delimiter-joined string and returned as an immutable
 * list. ValhallaMMO remains the source of truth for skill levels; this only mirrors addon-owned
 * retained perks and prestige bookkeeping.
 */
public final class PlayerData {

    /** Default prestige count for a player who has never prestiged. */
    public static final int DEFAULT_PRESTIGE = 0;

    /** ASCII unit separator (0x1F): never appears in a perk id, so it is a safe join delimiter. */
    private static final String PERK_DELIMITER = String.valueOf((char) 0x1F);

    private final org.bukkit.persistence.PersistentDataContainer container;

    private PlayerData(org.bukkit.persistence.PersistentDataContainer container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    /** Wraps the holder's container. */
    public static PlayerData of(PersistentDataHolder holder) {
        return new PlayerData(Objects.requireNonNull(holder, "holder").getPersistentDataContainer());
    }

    public int prestigeCount() {
        return container.getOrDefault(
                PdcKeys.PLAYER_PRESTIGE_COUNT, PersistentDataType.INTEGER, DEFAULT_PRESTIGE);
    }

    public void setPrestigeCount(int count) {
        container.set(PdcKeys.PLAYER_PRESTIGE_COUNT, PersistentDataType.INTEGER, Math.max(0, count));
    }

    /** Retained perks as an immutable list; empty when none are stored. */
    public List<String> heldPerks() {
        return readJoined(PdcKeys.PLAYER_HELD_PERKS);
    }

    public void setHeldPerks(List<String> perks) {
        writeJoined(PdcKeys.PLAYER_HELD_PERKS, perks, "perk id");
    }

    /**
     * スキルノードロック (2026-07-27): プレステージしても維持するノードの perk ID 一覧。
     * 未指定なら空。ロック自体は「所持しているか」とは独立した保護指定なので、
     * 所持していない perk がロックされていても無害(プレステージ時に無視される)。
     */
    public List<String> lockedPerks() {
        return readJoined(PdcKeys.PLAYER_LOCKED_PERKS);
    }

    public void setLockedPerks(List<String> perks) {
        writeJoined(PdcKeys.PLAYER_LOCKED_PERKS, perks, "perk id");
    }

    /**
     * ロック状態を反転する。
     *
     * @return 反転後にロックされていれば {@code true}(=ロックを付けた)、外したなら {@code false}
     */
    public boolean toggleLockedPerk(String perkId) {
        if (perkId == null || perkId.isBlank()) {
            throw new IllegalArgumentException("perk id must not be blank");
        }
        List<String> current = new java.util.ArrayList<>(lockedPerks());
        boolean added;
        if (current.remove(perkId)) {
            added = false;
        } else {
            current.add(perkId);
            added = true;
        }
        writeJoined(PdcKeys.PLAYER_LOCKED_PERKS, current, "perk id");
        return added;
    }

    /** コレクション図鑑 (M7): 発見済みエントリID一覧。未記録なら空。 */
    public List<String> collectionEntries() {
        return readJoined(PdcKeys.PLAYER_COLLECTION_ENTRIES);
    }

    public void setCollectionEntries(List<String> entries) {
        writeJoined(PdcKeys.PLAYER_COLLECTION_ENTRIES, entries, "collection entry id");
    }

    /** コレクション図鑑: 解放済み報酬ティアID一覧。未解放なら空。 */
    public List<String> claimedCollectionTiers() {
        return readJoined(PdcKeys.PLAYER_COLLECTION_CLAIMED_TIERS);
    }

    public void setClaimedCollectionTiers(List<String> tiers) {
        writeJoined(PdcKeys.PLAYER_COLLECTION_CLAIMED_TIERS, tiers, "collection tier id");
    }

    private List<String> readJoined(org.bukkit.NamespacedKey key) {
        String raw = container.get(key, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(PERK_DELIMITER))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private void writeJoined(org.bukkit.NamespacedKey key, List<String> values, String what) {
        Objects.requireNonNull(values, "values");
        List<String> cleaned = values.stream()
                .filter(p -> p != null && !p.isBlank())
                .toList();
        for (String value : cleaned) {
            if (value.indexOf(0x1F) >= 0) {
                throw new IllegalArgumentException(what + " must not contain the 0x1F delimiter: " + value);
            }
        }
        container.set(key, PersistentDataType.STRING, String.join(PERK_DELIMITER, cleaned));
    }

    // --- 特殊報酬レジストリ (2026-07-23-stat-gate-overhaul §6.1) ---

    /** 達成/図鑑ティア経由で直接付与された特殊報酬ID一覧(スキルツリーperk保有とは別枠)。 */
    public List<String> unlockedSpecialRewards() {
        return readJoined(PdcKeys.PLAYER_UNLOCKED_SPECIAL_REWARDS);
    }

    /** {@code id} を直接付与済み特殊報酬として追加する(重複は無視)。 */
    public void grantSpecialReward(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        List<String> current = new java.util.ArrayList<>(unlockedSpecialRewards());
        if (!current.contains(id)) {
            current.add(id);
            writeJoined(PdcKeys.PLAYER_UNLOCKED_SPECIAL_REWARDS, current, "special reward id");
        }
    }

    /**
     * {@code id} の直接付与を取り消す (2026-07-27、{@code /tf reward revoke} 用)。
     * 未保有なら何もしない。
     *
     * <p>取り消せるのは<b>直接付与された分だけ</b>。スキルツリーの {@code reward:<id>} perk 由来の保有は
     * perk 側が真実なので、ここを消しても {@code SpecialRewardService#isUnlocked} は true のままになる
     * (perk を剥がすのは prestige/リセット側の責務)。
     *
     * @return 実際に取り消したら {@code true}
     */
    public boolean revokeSpecialReward(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        List<String> current = new java.util.ArrayList<>(unlockedSpecialRewards());
        if (!current.remove(id)) {
            return false;
        }
        writeJoined(PdcKeys.PLAYER_UNLOCKED_SPECIAL_REWARDS, current, "special reward id");
        // 取り消した報酬を装備したままだと、以後ずっと未保有の称号/パーティクルが出続ける。
        if (equippedTitle().filter(id::equals).isPresent()) {
            setEquippedTitle(null);
        }
        if (equippedParticle().filter(id::equals).isPresent()) {
            setEquippedParticle(null);
        }
        return true;
    }

    public Optional<String> equippedTitle() {
        return Optional.ofNullable(container.get(PdcKeys.PLAYER_EQUIPPED_TITLE, PersistentDataType.STRING))
                .filter(s -> !s.isBlank());
    }

    public void setEquippedTitle(String id) {
        if (id == null || id.isBlank()) {
            container.remove(PdcKeys.PLAYER_EQUIPPED_TITLE);
        } else {
            container.set(PdcKeys.PLAYER_EQUIPPED_TITLE, PersistentDataType.STRING, id);
        }
    }

    public Optional<String> equippedParticle() {
        return Optional.ofNullable(container.get(PdcKeys.PLAYER_EQUIPPED_PARTICLE, PersistentDataType.STRING))
                .filter(s -> !s.isBlank());
    }

    public void setEquippedParticle(String id) {
        if (id == null || id.isBlank()) {
            container.remove(PdcKeys.PLAYER_EQUIPPED_PARTICLE);
        } else {
            container.set(PdcKeys.PLAYER_EQUIPPED_PARTICLE, PersistentDataType.STRING, id);
        }
    }

    /** true = 他プレイヤーの称号/パーティクル演出を非表示にする(軽量化トグル)。既定 false。 */
    public boolean hideOthersCosmetics() {
        return container.getOrDefault(PdcKeys.PLAYER_HIDE_OTHERS_COSMETICS, PersistentDataType.BYTE, (byte) 0) != 0;
    }

    public void setHideOthersCosmetics(boolean hide) {
        container.set(PdcKeys.PLAYER_HIDE_OTHERS_COSMETICS, PersistentDataType.BYTE, (byte) (hide ? 1 : 0));
    }

    // --- 採取プレイヤートグル (2026-07-25 gather-rework-active-framework §2 B-2)。既定 全ON。 ---

    /** true(既定) = vein-mining の一括破壊が有効。 */
    public boolean veinMiningEnabled() {
        return readToggleDefaultOn(PdcKeys.PLAYER_VEIN_MINING_ENABLED);
    }

    public void setVeinMiningEnabled(boolean enabled) {
        writeToggle(PdcKeys.PLAYER_VEIN_MINING_ENABLED, enabled);
    }

    /** true(既定) = tree-fell の一括伐採が有効。 */
    public boolean treeFellEnabled() {
        return readToggleDefaultOn(PdcKeys.PLAYER_TREE_FELL_ENABLED);
    }

    public void setTreeFellEnabled(boolean enabled) {
        writeToggle(PdcKeys.PLAYER_TREE_FELL_ENABLED, enabled);
    }

    /** true(既定) = auto-replant(植え直しと収穫同時)が有効。 */
    public boolean autoReplantEnabled() {
        return readToggleDefaultOn(PdcKeys.PLAYER_AUTO_REPLANT_ENABLED);
    }

    public void setAutoReplantEnabled(boolean enabled) {
        writeToggle(PdcKeys.PLAYER_AUTO_REPLANT_ENABLED, enabled);
    }

    /** true(既定) = area-harvest(範囲収穫)が有効。 */
    public boolean areaHarvestEnabled() {
        return readToggleDefaultOn(PdcKeys.PLAYER_AREA_HARVEST_ENABLED);
    }

    public void setAreaHarvestEnabled(boolean enabled) {
        writeToggle(PdcKeys.PLAYER_AREA_HARVEST_ENABLED, enabled);
    }

    /** 未記録(デフォルト値0x1)=ON、明示的に0が書かれていればOFF。既定ONトグル共通ヘルパ。 */
    private boolean readToggleDefaultOn(org.bukkit.NamespacedKey key) {
        return container.getOrDefault(key, PersistentDataType.BYTE, (byte) 1) != 0;
    }

    private void writeToggle(org.bukkit.NamespacedKey key, boolean enabled) {
        container.set(key, PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
    }

    // --- アチーブメント (2026-07-23-stat-gate-overhaul §6.2) ---

    /** 達成済みアチーブメントID一覧。 */
    public List<String> achievedIds() {
        return readJoined(PdcKeys.PLAYER_ACHIEVEMENTS_DONE);
    }

    /** {@code id} を達成済みとして記録する。既に記録済みなら {@code false} を返し何もしない。 */
    public boolean markAchieved(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        List<String> current = new java.util.ArrayList<>(achievedIds());
        if (current.contains(id)) {
            return false;
        }
        current.add(id);
        writeJoined(PdcKeys.PLAYER_ACHIEVEMENTS_DONE, current, "achievement id");
        return true;
    }

    public Optional<String> rolePrimary() {
        return Optional.ofNullable(
                container.get(PdcKeys.PLAYER_ROLE_PRIMARY, PersistentDataType.STRING));
    }

    public Optional<String> roleSupport() {
        return Optional.ofNullable(
                container.get(PdcKeys.PLAYER_ROLE_SUPPORT, PersistentDataType.STRING));
    }

    public void setRoles(String primary, String support) {
        container.set(PdcKeys.PLAYER_ROLE_PRIMARY, PersistentDataType.STRING,
                Objects.requireNonNull(primary, "primary"));
        container.set(PdcKeys.PLAYER_ROLE_SUPPORT, PersistentDataType.STRING,
                Objects.requireNonNull(support, "support"));
    }

    public void setRolePrimary(String primary) {
        container.set(PdcKeys.PLAYER_ROLE_PRIMARY, PersistentDataType.STRING,
                Objects.requireNonNull(primary, "primary"));
    }

    public void setRoleSupport(String support) {
        container.set(PdcKeys.PLAYER_ROLE_SUPPORT, PersistentDataType.STRING,
                Objects.requireNonNull(support, "support"));
    }

    public void clearRoles() {
        container.remove(PdcKeys.PLAYER_ROLE_PRIMARY);
        container.remove(PdcKeys.PLAYER_ROLE_SUPPORT);
    }

    /**
     * ガチャ天井(pity)カウンタ: {@code poolId}のプールで連続して最高レア枠を引けなかった回数。
     * 未記録(未プレイ)なら0。{@code com.trinityforge.gacha.GachaDraw#drawWithPity}の
     * {@code currentPityCount}引数へそのまま渡す想定。
     */
    public int gachaPityCount(String poolId) {
        return container.getOrDefault(PdcKeys.gachaPityKey(poolId), PersistentDataType.INTEGER, 0);
    }

    /**
     * ガチャ天井(pity)カウンタを保存する。{@code com.trinityforge.gacha.GachaDraw#drawWithPity}が
     * 返す{@code updatedPityCount}をそのまま渡す想定。負値は0にクランプする。
     */
    public void setGachaPityCount(String poolId, int count) {
        container.set(PdcKeys.gachaPityKey(poolId), PersistentDataType.INTEGER, Math.max(0, count));
    }
}
