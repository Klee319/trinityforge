package com.trinityforge.pdc;

import org.bukkit.NamespacedKey;

/**
 * Central registry of every PersistentDataContainer key the addon owns
 * (ADDON_INTEGRATION_SPEC 6: "数値の真実=統合アドオンのPDC").
 *
 * <p>Key names are stable structural identifiers, not balance numbers, so they live in
 * code rather than config. All keys share the {@link #NAMESPACE} so a single audit/clear
 * pass can find every value the addon wrote.
 */
public final class PdcKeys {

    /** Shared namespace for every addon-owned PDC value. */
    public static final String NAMESPACE = "trinityforge";

    // --- Item (SELECTION 5 / 6): stats are derived from rollSeed + quality, never baked. ---
    public static final NamespacedKey ITEM_ROLL_SEED = key("roll_seed");
    public static final NamespacedKey ITEM_QUALITY = key("quality");
    public static final NamespacedKey ITEM_OWNER = key("owner");
    public static final NamespacedKey ITEM_BIND_TYPE = key("bind_type");
    public static final NamespacedKey ITEM_USE_LEVEL_REQ = key("use_level_req");
    public static final NamespacedKey ITEM_USE_SKILL = key("use_skill");
    /** PDC schema generation stamped by {@code ItemAssembler}; foundation for future read-side
     * migration (STAT_DICTIONARY_RECONCILIATION 0). No migration logic reads this yet. */
    public static final NamespacedKey ITEM_DATA_VERSION = key("data_version");
    /** {@code TableGeneration} value in effect when this item was last assembled; lets
     * {@code ItemRefreshListener} skip re-assembling an item that is already current. */
    public static final NamespacedKey ITEM_TABLE_GENERATION = key("table_generation");
    /**
     * The {@code items/catalog.yml} id this item was created from, stamped by {@code ItemFactory.create}
     * so {@code ItemAssembler.assemble} can re-resolve the catalog entry's flavor {@code lore} on every
     * (re-)assembly even though {@code assemble} itself only ever sees the bare Material (no template).
     * Absent for items not created from a catalog template (the crafted/fished {@code stamp} path), so
     * those items simply get no flavor lore — back-compat with pre-flavor-lore items.
     */
    public static final NamespacedKey ITEM_CATALOG_ID = key("catalog_id");
    /**
     * TrinityForge's own contribution to the item's vanilla enchant levels (tool stats via enchant,
     * ITEM_ECONOMY_SPEC 5.2b), as a compact {@code "efficiency=2;unbreaking=1"} string. Lets the
     * quality-driven enchant bonus be re-applied idempotently ON TOP of player/anvil-added levels
     * without double-counting or wiping them: playerBase = currentLevel - thisStoredBonus. */
    public static final NamespacedKey ITEM_TOOL_ENCHANT_BONUS = key("tool_enchant_bonus");
    /**
     * Per-crafter stage-2 (ステータスロール) perk deltas baked into the item at craft time ({@code
     * CraftRollMods}), since the crafter is not known at later stat derivation ({@code DerivedItemStats}).
     * Absent (no key written) when the crafter had no such perks — reads back as {@code CraftRollMods.NONE}.
     */
    public static final NamespacedKey ITEM_CRAFT_ROLL_UP = key("craft_roll_up");
    public static final NamespacedKey ITEM_CRAFT_ROLL_DOWN_REDUCTION = key("craft_roll_down_reduction");
    public static final NamespacedKey ITEM_CRAFT_ROLL_INSET_DELTA = key("craft_roll_inset_delta");
    /**
     * 儀式(スレッド枠拡張)由来の {@code thread-slots} 累計付与数。儀式の効果パラメータ {@code max-slots}
     * (この儀式で1つの装備に付与できる累計スレッド枠数の上限)をこのカウンタ値そのもので判定できる。
     * 付与時に derivation で {@code thread-slots} へ加算される。absent = 0。
     */
    public static final NamespacedKey ITEM_RITUAL_THREAD_SLOT_BONUS = key("ritual_thread_slot_bonus");

    /**
     * The firing weapon (bow/crossbow/trident) serialized onto a player-shot projectile at launch so
     * the attack stats used at impact are re-derived from THAT weapon, not from whatever the shooter is
     * holding when the projectile lands (High bug: crit/penetration/attack-power were re-read from the
     * mainhand at impact time). Stored as {@code ItemStack.serializeAsBytes()} under
     * {@link org.bukkit.persistence.PersistentDataType#BYTE_ARRAY} and re-derived at impact via
     * {@code DerivedItemStats.resolve}. */
    public static final NamespacedKey PROJECTILE_WEAPON = key("projectile_weapon");
    /**
     * 2026-07-25バグ修正: 弓の引き絞り量({@code EntityShootBowEvent#getForce()}, 0.0〜1.0)を発射時に
     * {@link #PROJECTILE_WEAPON} と同じ projectile PDC へ retain する。BOW は {@code item-stats.yml} で
     * {@code attack-power} を持つため着弾時に {@code vanillaBaseDamage} を丸ごと TF値へ置換してしまい
     * (CombatListener tfBaseReplaces)、バニラの引き絞りスケール(軽く引く=低ダメージ/最大まで引く=満額)が
     * 消えていた。クロスボウは常時 {@code 1.0}(Paper PR#12308, 2025-05-02マージ以降は正しい値)なので
     * 同じ乗率適用で無害。トライデントはこのキーを書かないので既定 {@code 1.0}(バニラに引き絞りスケールが
     * 無いことと整合)。 */
    public static final NamespacedKey PROJECTILE_DRAW_FORCE = key("projectile_draw_force");
    /**
     * XP amount (int, total points — see {@code XpBottlePolicy#totalExperience}) stored onto a
     * filled experience bottle by {@code XpBottleListener} ({@code xp-bottle-store-unlock},
     * enchanting.yml B-3). Presence of this key is what distinguishes a "filled" bottle from a
     * plain vanilla {@code EXPERIENCE_BOTTLE} — the Material is identical for both.
     */
    public static final NamespacedKey ITEM_XP_BOTTLE_AMOUNT = key("xp_bottle_amount");
    /**
     * TFがこのアイテムへ実行時に足した「効率強化」エンチャントレベル(2026-07-25 採集効率エンチャント
     * 連動方式、属性ベースを取り下げ再設計)。除去は必ずこの記録値ぶんだけ厳密に差し引く(現在のレベルから
     * 機械的に引くと、金床や tool-enchant-efficiency 由来の正規のレベルまで消してしまう)。absent = 0
     * ({@code com.trinityforge.gathering.GatheringEfficiencyEnchantApplier} が唯一の読み書き元)。
     */
    public static final NamespacedKey ITEM_GATHERING_EFFICIENCY_APPLIED = key("gathering_efficiency_applied");
    /** Weapon coating stack count (alchemy weapon-coating-unlock). */
    public static final NamespacedKey ITEM_COATING_STACKS = key("coating_stacks");
    /** Accumulated flat bonus damage from weapon coating materials. */
    public static final NamespacedKey ITEM_COATING_FLAT_DAMAGE = key("coating_flat_damage");
    /**
     * クリエイティブ由来マーカー (BYTE=1, 2026-07-31)。「このスタックはクリエイティブで生成された／
     * クリエイティブ・スペクテイター中に拾われた」ことを<b>アイテム側</b>に刻む。
     * {@code CollectionListener} はこの印が付いたスタックを図鑑の判定から丸ごと外す。
     *
     * <p><b>なぜゲームモード判定だけでは足りないか</b>: 図鑑は「拾った瞬間」だけでなく
     * <b>インベントリの状態を遡って走査する</b>経路を持つので、クリエイティブで並べた品を
     * サバイバルへ持ち込んで走査させれば、走査時のゲームモードは SURVIVAL になり
     * 既存のゲームモードゲートを素通りする(HuskSync がインベントリを同期する構成では
     * {@code /server} で移るだけで成立する)。行為の瞬間しか見ない他の6箇所
     * ({@code EquipmentDurabilityService} 等)と違い、図鑑だけは出自をアイテムに残す必要がある。
     *
     * <p><b>best-effort である限界(印が失われる側)</b>: クラフト素材として消費した品・別アイテムへ
     * 変換した品・ブロックとして設置して壊し直した品では印が失われる(新しいスタックになるため)。
     * 逆に印の付いたスタックは PDC が違うので素の同種スタックと<b>合体しない</b>
     * (クリエイティブで出した石と survival で拾った石が別スタックになる)。
     * これらは「クリエイティブ品が図鑑を無料で埋める」ことを塞ぐ代償として受け入れている。
     *
     * <p><b>best-effort である限界(印が誤って付く側 — 2026-08-01 追記)</b>: こちらの方が症状が重い。
     * 誤って付くと<b>そのスタックは以後どのサバイバル走査でも永久に図鑑に載らない</b>のに、
     * エラーも通知も出ない。判明している誤付与経路は
     * <ul>
     *   <li>クリエイティブ滞在中に<b>地面から拾った</b>品(モブ討伐ドロップ・他プレイヤーの落とし物・
     *       資源サーバから持ち帰った品を落として拾い直した場合)。地面のスタックからは出自を
     *       判定する手掛かりが一切取れないため絞れない。</li>
     *   <li>クリエイティブ画面での整理のうち、「持ち上げ→置き直し」の対応付けに失敗したもの
     *       ({@code CollectionListener#onCreativeSet} の限界。同種の品を複数同時に juggle した場合など)。</li>
     * </ul>
     * <b>回復手段は {@code /tf collection unmark}</b>(OP または {@code trinityforge.admin})。
     * {@link com.trinityforge.pdc.ItemData#clearCreativeOrigin()} がその実体。
     */
    public static final NamespacedKey ITEM_CREATIVE_ORIGIN = key("creative_origin");

    // --- Player (PROGRESSION / UNLOCK / ROLE): perks, prestige, role are the unlock truth. ---
    public static final NamespacedKey PLAYER_PRESTIGE_COUNT = key("prestige_count");
    public static final NamespacedKey PLAYER_HELD_PERKS = key("held_perks");
    /**
     * スキルノードロック (2026-07-27): プレステージしても解放を維持するノードの perk ID 集合。
     * {@link #PLAYER_HELD_PERKS} と同じ 0x1F 結合 STRING コーデック。
     *
     * <p>進行DB(SQLite)ではなくプレイヤーPDCに置くのは、ロックが「所持している解放状態」ではなく
     * 「プレイヤーが選んだ保護指定」であり、DBスキーマ変更なしで足せるため。プレステージ時に
     * {@code NativePerkService} が「ロック ∩ 所持中」だけを無償で再付与する。
     */
    public static final NamespacedKey PLAYER_LOCKED_PERKS = key("locked_perks");
    public static final NamespacedKey PLAYER_ROLE_PRIMARY = key("role_primary");
    public static final NamespacedKey PLAYER_ROLE_SUPPORT = key("role_support");
    /**
     * ロールを最後に変更した時刻 (epoch millis, 2026-07-31)。戦闘職・補助職で別々に持つ。
     *
     * <p>別々にするのは、GUI で戦闘職を選んだ直後に補助職も選べる必要があるため
     * (共通のクールダウンにすると片方を選んだ瞬間にもう片方が押せなくなる)。
     *
     * <p>クールダウンが無いと、採掘するときだけ鉱夫・釣るときだけ漁師へ切り替えれば
     * 全系統に最大倍率が乗るので、補助職の選択そのものが意味を失う。
     */
    public static final NamespacedKey PLAYER_ROLE_PRIMARY_CHANGED_AT = key("role_primary_changed_at");
    public static final NamespacedKey PLAYER_ROLE_SUPPORT_CHANGED_AT = key("role_support_changed_at");
    /**
     * Generic addon combat-stat contribution channel ({@code AddonCombatStats}): a per-player canonical
     * stat map that any hard-dependent addon may write, folded into BOTH the attacker (CombatListener)
     * and defender (PlayerDefenseResolver) paths exactly like a perk buff. Used by the ArsPaper fork for
     * socketed armor "thread" stats (per-thread item-stats + same-type-count set bonus). Stored as a
     * compact {@code "key=value;key=value"} STRING (same codec style as {@link #ITEM_TOOL_ENCHANT_BONUS},
     * so no JSON dependency); TF owns the codec so the fork writes the exact format TF reads. */
    public static final NamespacedKey PLAYER_ADDON_COMBAT_STATS = key("addon_combat_stats");
    /**
     * コレクション図鑑 (M7): 発見済みエントリID ({@code item:<catalogId>} / {@code mob:<ENTITY_TYPE>})
     * の集合。{@link #PLAYER_HELD_PERKS} と同じ 0x1F 結合 STRING コーデック。
     */
    public static final NamespacedKey PLAYER_COLLECTION_ENTRIES = key("collection_entries");
    /** コレクション図鑑: 解放済み報酬ティアID集合(再付与防止)。0x1F 結合 STRING。 */
    public static final NamespacedKey PLAYER_COLLECTION_CLAIMED_TIERS = key("collection_claimed_tiers");
    /**
     * コレクション図鑑 (2026-07-31, K-11): 参加時の全スロット走査を「遡り登録」として
     * 静かに1回済ませたかどうか。1 = 済み。
     *
     * <p>K-11 の修正で、既にインベントリへ入っている素のバニラ品が一斉に記録可能になる。
     * 通知したままだと最大16行のチャットと {@code broadcast: true} の報酬ティア告知が
     * 連続発火して事故に見えるため、プレイヤーごとに初回の走査だけ通知を抑止する。
     * 未設定(=既存プレイヤー全員)が「まだ遡り登録していない」を意味するので、
     * 追加のマイグレーションは要らない。
     */
    public static final NamespacedKey PLAYER_COLLECTION_BACKFILL_DONE = key("collection_backfill_done");

    // --- 特殊報酬レジストリ (2026-07-23-stat-gate-overhaul §6.1) ---
    /** 達成/図鑑ティア経由で直接付与された特殊報酬ID集合(スキルツリー経由の reward:&lt;id&gt; とは別枠)。
     *  0x1F 結合 STRING、{@link #PLAYER_HELD_PERKS} と同じコーデック。 */
    public static final NamespacedKey PLAYER_UNLOCKED_SPECIAL_REWARDS = key("unlocked_special_rewards");
    /** 現在装備中の称号ID(null=非装備)。 */
    public static final NamespacedKey PLAYER_EQUIPPED_TITLE = key("equipped_title");
    /** 現在装備中のパーティクルID(null=非装備)。 */
    public static final NamespacedKey PLAYER_EQUIPPED_PARTICLE = key("equipped_particle");
    /** true = 他プレイヤーの称号/パーティクル演出を非表示にする(軽量化トグル)。 */
    public static final NamespacedKey PLAYER_HIDE_OTHERS_COSMETICS = key("hide_others_cosmetics");

    // --- 採取プレイヤートグル (2026-07-25 gather-rework-active-framework §2 B-2)。既定 全ON。 ---
    /** false = vein-mining の一括破壊を個人設定で無効化(選択採掘したい場面向け)。 */
    public static final NamespacedKey PLAYER_VEIN_MINING_ENABLED = key("vein_mining_enabled");
    /** false = tree-fell(旧small/large-tree-fell)の一括伐採を個人設定で無効化。 */
    public static final NamespacedKey PLAYER_TREE_FELL_ENABLED = key("tree_fell_enabled");
    /** false = auto-replant(植え直しと収穫同時)を個人設定で無効化(苗を残したい場面向け)。 */
    public static final NamespacedKey PLAYER_AUTO_REPLANT_ENABLED = key("auto_replant_enabled");
    /** false = area-harvest(範囲収穫)を個人設定で無効化。 */
    public static final NamespacedKey PLAYER_AREA_HARVEST_ENABLED = key("area_harvest_enabled");

    // --- 切削(DIGGING)シャベル耐久累計EXP (2026-07-25、digging.yml C-1/C-2) ---
    /** 累計シャベル耐久消費量(long)。ログアウト/サーバー再起動を跨いで永続化する
     * ({@code DiggingDurabilityExpListener})。use-skill==DIGGINGのアイテムのみ加算対象。 */
    public static final NamespacedKey PLAYER_DIGGING_DURABILITY_ACCUM = key("digging_durability_accum");

    // --- アチーブメント (2026-07-23-stat-gate-overhaul §6.2) ---
    /** 達成済みアチーブメントID集合。0x1F 結合 STRING。 */
    public static final NamespacedKey PLAYER_ACHIEVEMENTS_DONE = key("achievements_done");

    // --- パーティクルシード (2026-07-23-stat-gate-overhaul §6.1): 道具側に焼き込むシードID。 ---
    public static final NamespacedKey ITEM_PARTICLE_SEED = key("particle_seed");

    // --- 称号頭上表示 (2026-07-23-stat-gate-overhaul §6.1、FocusHpDisplayと同じ孤児掃除パターン)。 ---
    public static final NamespacedKey TITLE_DISPLAY = key("title_display");

    /** ガチャ天井(pity)カウンタキーの接頭辞。プールID毎に独立したカウンタを持つため、他の
     * キーと違い固定の{@code NamespacedKey}ではなく{@link #gachaPityKey(String)}で動的に導出する。 */
    private static final String GACHA_PITY_PREFIX = "gacha_pity_";

    /**
     * プールID毎のガチャ天井(pity)カウンタキー(ITEM_ECONOMY_SPEC CR-9安全弁②)。
     * {@code com.trinityforge.gacha.GachaDraw#drawWithPity} が返す連続非最高レア回数を
     * このキーでプレイヤーPDCに永続化する想定。
     */
    public static NamespacedKey gachaPityKey(String poolId) {
        return key(GACHA_PITY_PREFIX + java.util.Objects.requireNonNull(poolId, "poolId"));
    }

    /** 累計カウンタキーの接頭辞。{@link #lifetimeCounterKey(String)} 経由でのみ使う。 */
    private static final String LIFETIME_COUNTER_PREFIX = "counter_";

    /**
     * 「一生分の累計値」を数えるカウンタキー(2026-07-31)。バニラ {@code Statistic} では表現できない
     * TF/Ars 独自の総量(累計消費ソースなど)を、アチーブメントの {@code trigger.type: counter} から
     * 参照できるようにするために置いた汎用の器。
     *
     * <p>{@link #gachaPityKey(String)} と同じ動的導出パターン。カウンタは減らさない前提なので
     * 型は {@code LONG}(1億を超えても溢れない)。
     *
     * <p><b>ArsPaper フォークもこのキーへ直接書く</b>({@code TrinityForgeBridge#recordSourceSpent})。
     * あちらは TF API の jar を差し替えずにビルドできるよう {@code "trinityforge:counter_<id>"} を
     * 文字列で組むので、ここの命名を変えると静かに別カウンタになる。
     * {@code PdcKeysCounterTest} がその文字列を固定している。
     */
    public static NamespacedKey lifetimeCounterKey(String counterId) {
        return key(LIFETIME_COUNTER_PREFIX + java.util.Objects.requireNonNull(counterId, "counterId")
                .trim().toLowerCase(java.util.Locale.ROOT));
    }

    // --- Mob (COMBAT 6 / DUNGEON): defender stat profile + level + dungeon theme. ---
    public static final NamespacedKey MOB_LEVEL = key("mob_level");
    public static final NamespacedKey MOB_DUNGEON_THEME = key("mob_dungeon_theme");
    /**
     * EliteMobsのカスタムボス設定ファイル名(拡張子なし、{@code /trinityforge importmobs} が
     * {@code combat/mob-profiles.yml} のキーに使うものと同じid)。{@code TrinityForgeSpawnListener}が
     * {@link #MOB_LEVEL} 等と同じタイミングでスポーン時に刻む(2026-07-26 mob-overrides新設)。
     * これにより討伐時(EntityDeathEvent)にもモブidが判別でき、ダンジョン×モブ単位のドロップ
     * オーバーライド({@code combat/mob-overrides.yml})を解決できる。absent = オーバーライド対象外
     * (未対応の古いスポーン由来、またはEliteMobs以外のモブ)。
     */
    public static final NamespacedKey MOB_PROFILE_ID = key("mob_profile_id");
    /**
     * 個体ばらつき(厳選ロール)用の per-mob シード(long)。spawn 時に一度だけ焼き、以後は
     * {@code RollHash} で HP/攻撃の ±variance 倍率を「決定的」に導出する。これにより全回復・
     * フェーズreset・動的レベル再評価をまたいでも同一個体は同一倍率を保つ(毎回 new Random しない)。
     * absent の場合は HP解決/attack stamp のどちらか先に走った側が lazy-init で焼く。
     * mob-import.yml の {@code variance:} が 0 のときは焼いても倍率1.0(実質無効)。
     */
    public static final NamespacedKey MOB_ROLL_SEED = key("mob_roll_seed");
    /** 防具強度 is type-independent (DefenseStats javadoc): one shared key across components. */
    public static final NamespacedKey MOB_ARMOR_STRENGTH = key("mob_armor_strength");
    /**
     * 回避率 (SKILL_TREE_SPEC 6.2, Q3 = (c)): type-independent defender dodge chance [0,1], rolled
     * once per attack (whole-attack avoid, magical included). Read via {@code MobData.dodgeChance()}
     * from any victim container, so the same key serves a player-defender once the C1/LD-8 supply
     * (armor-skill baseline + item aggregation) stamps it. Absent -> 0 (no dodge).
     */
    public static final NamespacedKey MOB_DODGE_CHANCE = key("mob_dodge_chance");

    // Per-component (physical/magical) defender keys. Prefix supplied by DamageType.
    public static final NamespacedKey MOB_PHYS_DEFENSE_RATE = key("mob_phys_defense_rate");
    public static final NamespacedKey MOB_PHYS_RESISTANCE = key("mob_phys_resistance");
    public static final NamespacedKey MOB_PHYS_DAMAGE_REDUCTION = key("mob_phys_damage_reduction");
    public static final NamespacedKey MOB_PHYS_FLAT_DEFENSE = key("mob_phys_flat_defense");

    public static final NamespacedKey MOB_MAGIC_DEFENSE_RATE = key("mob_magic_defense_rate");
    public static final NamespacedKey MOB_MAGIC_RESISTANCE = key("mob_magic_resistance");
    public static final NamespacedKey MOB_MAGIC_DAMAGE_REDUCTION = key("mob_magic_damage_reduction");
    public static final NamespacedKey MOB_MAGIC_FLAT_DEFENSE = key("mob_magic_flat_defense");
    /**
     * Marker (BYTE=1) written ONLY by {@code MobTypeSpawnListener} when it stamps a
     * {@code combat/mob-types.yml} vanilla-mob profile. {@link #MOB_LEVEL} alone cannot
     * distinguish a mob-types mob from an EliteMobs/dungeon mob (both share that key), so
     * {@code MobTypeDropListener} requires this marker before applying mob-types drop tables /
     * equipment-quality stamps — otherwise Elite/dungeon mobs (and mobs spawned before plugin/
     * config load) would incorrectly receive vanilla mob-type drops.
     */
    public static final NamespacedKey MOB_TYPE_STAMPED = key("mob_type_stamped");

    // Attacker-side mob stats (combat/mob-types.yml attack: block). Absent keys → vanilla mob damage.
    public static final NamespacedKey MOB_ATTACK_POWER = key("mob_attack_power");
    public static final NamespacedKey MOB_ATTACK_FLAT_BONUS = key("mob_attack_flat_bonus");
    public static final NamespacedKey MOB_ATTACK_PERCENT_BONUS = key("mob_attack_percent_bonus");
    public static final NamespacedKey MOB_ATTACK_PENETRATION = key("mob_attack_penetration");
    public static final NamespacedKey MOB_ATTACK_CRIT_CHANCE = key("mob_attack_crit_chance");
    public static final NamespacedKey MOB_ATTACK_CRIT_DAMAGE = key("mob_attack_crit_damage");
    public static final NamespacedKey MOB_ATTACK_DAMAGE_MODIFIER = key("mob_attack_damage_modifier");
    public static final NamespacedKey MOB_ATTACK_FIXED_DAMAGE = key("mob_attack_fixed_damage");
    /**
     * Marker (BYTE=1) written by {@code CombatListener#onCreatureSpawn} for a mob that came out of a
     * monster spawner, so {@code ArcheryExperiencePolicy} can apply the spawner EXP multiplier.
     *
     * <p>2026-07-29: moved here from a listener-local {@code new NamespacedKey(plugin, ...)} (same
     * resulting key — the plugin namespace IS {@link #NAMESPACE}) so {@code MobTransformCarryOver}
     * can carry it across a transformation. Before that, drowning a spawner zombie produced a
     * Drowned with no spawner origin, silently bypassing the spawner EXP nerf.
     */
    public static final NamespacedKey MOB_SPAWNER_SPAWNED = key("combat_exp_spawner_spawned");

    // --- Villager (economy/villager-trades.yml): perk-gated custom trades. ---
    /**
     * 村人へカスタム取引を「初回のみ」注入したことを示すマーカー。値は注入時点の
     * {@code Villager.Profession#name()}。{@code VillagerTradeListener} は GUI を開くたびに
     * 呼ばれるが、このマーカーが現在の職業と一致していれば<b>再注入しない</b>。
     *
     * <p>毎回 {@code setRecipes} で再構築すると、(1) 新規 {@code MerchantRecipe} の {@code uses=0}
     * によって取引ロック(使用回数)が開くたびに巻き戻り無限購入 exploit になり、(2)
     * {@code block-vanilla-trades=false} 時は既存レシピへカスタムを再度 addAll して開くたびに
     * 重複蓄積する。初回のみ注入し以降はバニラの使用回数/補充機構へ委ねることで両者を防ぐ。
     * 職業名を保持するのは、村人が転職した場合(バニラが取引を再生成する)に新職業向けの
     * 再注入を許すため。※configリロードは注入済み村人へ遡及しない(バニラ取引の不変性と同様)。
     */
    public static final NamespacedKey VILLAGER_TRADES_INJECTED = key("villager_trades_injected");

    // --- Brewing stand ---
    // 醸造台の所有者キーは<b>ここに置かない</b>。所有者記録は
    // com.trinityforge.listeners.BrewOwnership が唯一の定義(キー文字列・書き込み規則・寿命の全部)で、
    // 醸造解放ゲートもそれを読む。2026-07-31 に一度ここへ BREW_STAND_OWNER を新設して
    // 「誰に醸造を許可するか」と「誰にEXP/品質を付けるか」が別キーで決まる状態を作ったため撤去した
    // (レビュー指摘#1)。所有者の記録を2本目にしないこと。

    // --- Cosmetic display entities (COMBAT focus-HP overlay). ---
    /** Tags a {@code TextDisplay} spawned by {@code FocusHpDisplay} so an orphan sweep on enable
     * can find and remove any left behind by a crash (the entity is also non-persistent). */
    public static final NamespacedKey FOCUS_HP_DISPLAY = key("focus_hp_display");
    /** Tags a short-lived {@code TextDisplay} spawned by {@code DamagePopupDisplay} (プレイヤーが
     * モブへ与えた実TFダメージのポップアップ表記)。FocusHpDisplay と同じく起動時の孤児掃除で回収する
     * (エンティティ自体も non-persistent かつ duration 経過で自動除去)。 */
    public static final NamespacedKey DAMAGE_POPUP_DISPLAY = key("damage_popup_display");

    private PdcKeys() {
    }

    private static NamespacedKey key(String path) {
        return new NamespacedKey(NAMESPACE, path);
    }
}
