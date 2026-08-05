package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.config.PotionEffectTypes;
import com.trinityforge.stats.PercentStatNormalize;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/role-buffs.yml}: combat/support role internal stat injections.
 */
public final class RoleBuffsConfig implements LoadableConfig {

    public static final String PATH = "progression/role-buffs.yml";

    // 数値上限 (OPEN_DECISIONS C1b): attack-buffs/defense-buffs は admin編集の任意キーmapで
    // 上限が無かったため、無制限のロール内部ステ注入(実質チート値)が可能だった。
    // 上限は既存default値のおよそ10倍を目安に保守的に設定する:
    //   - flat_defense / phys_flat_defense / magic_flat_defense: default(tankのflat-defense=40.0)の10倍=400.0
    //   - armor_strength(会心軽減率%[0,1]へ役割変更済): 割合系上限(1.0)を適用する
    //   - 割合系(percent-bonus-damage等): item-stats.yml の damage-reduction 等が採用する
    //     系全体の割合上限(1.0=100%)を踏襲する(定数個別の10倍計算より、既存の割合ステの
    //     システム上限と揃える方が一貫性がある)
    //   - hate-threat-multiplier: default(tankの1.5)の10倍=15.0
    //   - 未知の(将来追加される)キーは名前から割合系/絶対値系を推測し、上記のどちらかの上限を
    //     フォールバックとして適用する(必ず何らかの上限を持たせる安全弁)
    private static final double MAX_FLAT_DEFENSE_LIKE_BUFF = 400.0;
    private static final double MAX_PERCENT_BUFF = 1.0;
    private static final double MAX_HATE_THREAT_MULTIPLIER = 15.0;
    /** Multiplier-shaped stats (crit_damage as +fraction, damage_modifier as a x-endpoint vs 1.0):
     * the flat-scale +-400 cap would mean +40000% crit damage / a x400 per-hit roll, so these get
     * their own tight ceiling (x2 / +200%). */
    private static final double MAX_MULTIPLIER_LIKE_BUFF = 2.0;
    /** Support-role EXP multiplier ceiling: without one, an admin typo (x1000000) silently breaks
     * progression pacing the same way an unbounded combat buff breaks balance. */
    private static final double MAX_EXP_MULTIPLIER = 10.0;

    public record PotionBuffSpec(PotionEffectType type, int durationTicks, int amplifier) {
    }

    /**
     * @param icon        ロール選択GUIで使うアイコンMaterial名(空なら既定アイコン)
     * @param description GUIに1行で添える説明(空なら省略)
     */
    public record CombatRoleSpec(String id, String label, Map<String, Double> attackBuffs,
                                 Map<String, Double> defenseBuffs, double hateThreatMultiplier,
                                 String icon, List<String> description) {
        /** Back-compat: icon/description 未指定(既存テスト・呼び出し用)。 */
        public CombatRoleSpec(String id, String label, Map<String, Double> attackBuffs,
                              Map<String, Double> defenseBuffs, double hateThreatMultiplier) {
            this(id, label, attackBuffs, defenseBuffs, hateThreatMultiplier, "", List.of());
        }
    }

    /**
     * {@code description} は 1 行の文字列でも、行のリストでも書ける。
     * 2026-07-29 に複数行(lore)化したが、既存の 1 行表記も黙って読めるようにしてある
     * (アイテムカタログの lore と入力UIを揃えるための変更で、設定の書き直しを強いる話ではない)。
     */
    private static List<String> readDescription(ConfigurationSection sec) {
        Object raw = sec.get("description");
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> lines = new ArrayList<>(list.size());
            for (Object line : list) {
                if (line != null) {
                    lines.add(String.valueOf(line));
                }
            }
            return List.copyOf(lines);
        }
        String single = String.valueOf(raw);
        return single.isBlank() ? List.of() : List.of(single);
    }

    /** @param icon/description は {@link CombatRoleSpec} と同じ意味。 */
    public record SupportRoleSpec(String id, String label, String expSkill, double expMultiplier,
                                  PotionBuffSpec potionBuff, String icon, List<String> description) {
        /** Back-compat: icon/description 未指定(既存テスト・呼び出し用)。 */
        public SupportRoleSpec(String id, String label, String expSkill, double expMultiplier,
                               PotionBuffSpec potionBuff) {
            this(id, label, expSkill, expMultiplier, potionBuff, "", List.of());
        }
    }

    /** {@code role-change.cooldown-minutes} の上限(日)。桁を間違えて実質永久ロックになるのを防ぐ。 */
    private static final double MAX_COOLDOWN_MINUTES = 7 * 24 * 60;
    /**
     * {@code role-change.nearby-enemy-radius} の上限(ブロック)。この半径の立方体を毎回エンティティ走査
     * するので、桁を間違えたときに走査コストが跳ねないよう歯止めを掛ける。
     */
    private static final double MAX_NEARBY_ENEMY_RADIUS = 64.0;

    private volatile Map<String, CombatRoleSpec> combatRoles = Map.of();
    private volatile Map<String, SupportRoleSpec> supportRoles = Map.of();
    private volatile boolean allowRoleChange = true;
    private volatile long roleChangeCooldownMillis = 0L;
    private volatile boolean firstChoiceFree = true;
    private volatile double nearbyEnemyRadius = 0.0;

    public Map<String, CombatRoleSpec> combatRoles() {
        return combatRoles;
    }

    public Map<String, SupportRoleSpec> supportRoles() {
        return supportRoles;
    }

    /**
     * 職業(ロール)の付け替えそのものを許可するか。{@code role-change.allow-change}、既定 true。
     *
     * <p><b>false は「変更禁止」ではなく「アイテム消費でのみ変更可」を意味する</b>(2026-08-05, W-28)。
     * 空の枠を初めて埋める就職({@code first-choice-free})は false でも通り、それ以降の付け替えは
     * {@code role_reselect_ticket}(職業付け替えの証)を持っているときだけ通る。解除は塞ぐ
     * (解除で枠を空にできると「初回無料」を無限に再利用できてしまう)。
     *
     * <p>true のときは従来どおりクールダウン制({@link #roleChangeCooldownMillis()})。
     *
     * <p>旧キー {@code allow-command} は {@code /tf role set} 廃止(W-28)まで
     * 「コマンド/GUI からの変更を一切受け付けない」だった。配備済み config が無言で
     * 「許可」へ反転しないよう、{@code allow-change} が無いときだけ旧キーを読む。
     */
    public boolean allowRoleChange() {
        return allowRoleChange;
    }

    /**
     * ロールを変更してから次に変更できるまでの待ち時間(ミリ秒)。0 で待ち時間なし。
     *
     * <p>待ち時間が無いと、採掘するときだけ鉱夫・釣るときだけ漁師へ切り替えれば全系統に
     * 最大倍率が乗るので、補助職の選択そのものが意味を失う（横の選択肢を増やす設計が
     * 「全部同時に持てる」で崩れる）。
     */
    public long roleChangeCooldownMillis() {
        return roleChangeCooldownMillis;
    }

    /** 未設定の枠を初めて選ぶときは待ち時間を課さないか。始めたばかりの人を詰まらせないための逃げ道。 */
    public boolean firstChoiceFree() {
        return firstChoiceFree;
    }

    /**
     * 「交戦中はロールを変更できない」ガードの走査半径(ブロック)。<b>0 でガードそのものを無効化する
     * (既定)</b>。0 より大きいと、その半径内に<b>自分を狙っている敵</b>が居る間だけ変更できなくなる。
     *
     * <p>2026-07-31 まではこの値が 16.0 のハードコードで、しかも敵対判定が Bukkit の
     * {@code Monster} だったため、ネザーのゾンビピグリン／ピグリンや昼のクモ・壁越しの洞窟モブで
     * <b>常時変更不可</b>になり、逆に {@code Monster} でない Slime/Ghast/Shulker/EnderDragon/Hoglin
     * との戦闘中は<b>素通り</b>していた（意図と両方向に外れていた）。判定は Paper の
     * {@code Enemy} ＋「自分を狙っているか」へ寄せ、既定値は 0（無効）にしてある。
     *
     * <p>これは変更クールダウン({@link #roleChangeCooldownMillis()})とは<b>別の機構</b>で、
     * 乗せ替え悪用の抑止はクールダウン側が担う。
     */
    public double nearbyEnemyRadius() {
        return nearbyEnemyRadius;
    }

    public CombatRoleSpec combatRole(String id) {
        return id == null ? null : combatRoles.get(normalize(id));
    }

    public SupportRoleSpec supportRole(String id) {
        return id == null ? null : supportRoles.get(normalize(id));
    }

    private static String normalize(String id) {
        return id.trim().toLowerCase();
    }

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML error: " + ex.getMessage(), ex);
            return false;
        }

        Map<String, CombatRoleSpec> combat = new LinkedHashMap<>();
        ConfigurationSection combatRoot = yaml.getConfigurationSection("combat-roles");
        if (combatRoot != null) {
            for (String id : combatRoot.getKeys(false)) {
                ConfigurationSection sec = combatRoot.getConfigurationSection(id);
                if (sec == null) {
                    continue;
                }
                combat.put(normalize(id), new CombatRoleSpec(
                        normalize(id),
                        sec.getString("label", id),
                        readDoubleMap(sec.getConfigurationSection("attack-buffs")),
                        readDoubleMap(sec.getConfigurationSection("defense-buffs")),
                        // Bounded on BOTH sides: an upper cap alone still lets a huge negative through.
                        Math.max(0.0, Math.min(MAX_HATE_THREAT_MULTIPLIER,
                                sec.getDouble("hate-threat-multiplier", 1.0))),
                        sec.getString("icon", ""),
                        readDescription(sec)));
            }
        }
        this.combatRoles = Collections.unmodifiableMap(combat);

        Map<String, SupportRoleSpec> support = new LinkedHashMap<>();
        ConfigurationSection supportRoot = yaml.getConfigurationSection("support-roles");
        if (supportRoot != null) {
            for (String id : supportRoot.getKeys(false)) {
                ConfigurationSection sec = supportRoot.getConfigurationSection(id);
                if (sec == null) {
                    continue;
                }
                PotionBuffSpec potion = null;
                ConfigurationSection pot = sec.getConfigurationSection("potion-buff");
                if (pot != null) {
                    String typeName = pot.getString("type", "");
                    PotionEffectType type = PotionEffectTypes.resolve(typeName);
                    if (type != null) {
                        int duration = Math.max(20, pot.getInt("duration", 999999)) * 20;
                        int amp = Math.max(0, pot.getInt("amplifier", 0));
                        potion = new PotionBuffSpec(type, duration, amp);
                    } else {
                        log.warning("[" + PATH + "] support-role '" + id + "' potion-buff type '"
                                + typeName + "' is not a valid PotionEffectType; skipped");
                    }
                }
                support.put(normalize(id), new SupportRoleSpec(
                        normalize(id),
                        sec.getString("label", id),
                        sec.getString("exp-skill", ""),
                        Math.max(1.0, Math.min(MAX_EXP_MULTIPLIER, sec.getDouble("exp-multiplier", 1.2))),
                        potion,
                        sec.getString("icon", ""),
                        readDescription(sec)));
            }
        }
        this.supportRoles = Collections.unmodifiableMap(support);

        ConfigurationSection change = yaml.getConfigurationSection("role-change");
        // allow-change が書かれていないときだけ旧キー allow-command を読む(W-28 で改名)。
        // 既定を素の true にすると、旧キーで「不許可」にしてある配備済み config が
        // 無言で「許可」へ反転する。
        boolean legacyAllow = change == null || change.getBoolean("allow-command", true);
        this.allowRoleChange = change == null || change.getBoolean("allow-change", legacyAllow);
        double cooldownMinutes = change == null ? 0.0 : change.getDouble("cooldown-minutes", 0.0);
        if (!Double.isFinite(cooldownMinutes) || cooldownMinutes < 0.0) {
            cooldownMinutes = 0.0;
        }
        this.roleChangeCooldownMillis = (long) (Math.min(cooldownMinutes, MAX_COOLDOWN_MINUTES) * 60_000L);
        this.firstChoiceFree = change == null || change.getBoolean("first-choice-free", true);
        // 既定 0 = 交戦中ガードを無効化する。非有限/負は「無効」へ寄せる(無言で有効になる方が危険)。
        double nearbyRadius = change == null ? 0.0 : change.getDouble("nearby-enemy-radius", 0.0);
        if (!Double.isFinite(nearbyRadius) || nearbyRadius < 0.0) {
            nearbyRadius = 0.0;
        }
        this.nearbyEnemyRadius = Math.min(nearbyRadius, MAX_NEARBY_ENEMY_RADIUS);

        log.info("[" + PATH + "] loaded " + combat.size() + " combat + " + support.size() + " support role(s) OK");
        return true;
    }

    private static Map<String, Double> readDoubleMap(ConfigurationSection sec) {
        if (sec == null) {
            return Map.of();
        }
        Map<String, Double> map = new LinkedHashMap<>();
        for (String key : sec.getKeys(false)) {
            String canonicalKey = key.replace('-', '_');
            // CMB-15: 他の全ステ供給経路(item-stats / skilltree buffs / base-stats / permanent-buffs)と
            // 揃えて PercentStatNormalize を通す。これが無いと「penetration: 20」(20%のつもり)が
            // 生値20(=2000%相当、貫通が飽和し防御を無視)として combat に渡ってしまう。クランプより先に
            // 正規化する — coerce後の値でmax上限を判定させるため。
            double normalized = PercentStatNormalize.coerce(canonicalKey, sec.getDouble(key));
            map.put(canonicalKey, clampBuffValue(canonicalKey, normalized));
        }
        return Map.copyOf(map);
    }

    /** Symmetric ±max clamp so a mistaken/malicious huge negative value cannot be used either. */
    private static double clampBuffValue(String canonicalKey, double value) {
        double max = maxMagnitudeFor(canonicalKey);
        return Math.max(-max, Math.min(max, value));
    }

    private static double maxMagnitudeFor(String canonicalKey) {
        return switch (canonicalKey) {
            case "flat_defense", "phys_flat_defense", "magic_flat_defense" -> MAX_FLAT_DEFENSE_LIKE_BUFF;
            // 防具強度(armor_strength)は会心軽減率%[0,1]へ役割変更したので、flat系ではなく割合系の上限で縛る。
            case "armor_strength", "percent_bonus_damage", "damage_reduction" -> MAX_PERCENT_BUFF;
            // Neither percent-scale (a 1.0 cap would forbid any real buff) nor flat-scale (400 would
            // be x400): multiplier-shaped keys get their own ceiling.
            case "crit_damage", "damage_modifier" -> MAX_MULTIPLIER_LIKE_BUFF;
            default -> isLikelyPercentScaleKey(canonicalKey) ? MAX_PERCENT_BUFF : MAX_FLAT_DEFENSE_LIKE_BUFF;
        };
    }

    /** Name-based fallback for a future/unlisted attack-buffs/defense-buffs key, matching the
     * {@code percent}/{@code chance}/{@code reduction}/{@code rate} naming convention used
     * throughout {@code stats/item-stats.yml} for 0..1-scale stats. */
    private static boolean isLikelyPercentScaleKey(String canonicalKey) {
        return canonicalKey.contains("percent") || canonicalKey.endsWith("_chance")
                || canonicalKey.endsWith("_reduction") || canonicalKey.endsWith("_rate");
    }
}
