package com.trinityforge.listeners;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobProfile;
import com.trinityforge.mobs.MobTransformCarryOver;
import com.trinityforge.mobs.MobLevelScaling;
import com.trinityforge.mobs.MobStatScaling;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import com.trinityforge.progression.CombatLevelSource;
import com.trinityforge.progression.SkillLevelSource;
// W-61: 「ワールドに実体が現れた」側の入口。パッケージは io.papermc ではなく
// com.destroystokyo.paper（Paper 1.21.11 の paper-api でもこちらのまま）。
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * combat/mob-types.yml の定義をバニラモブのスポーン時に適用する。
 * effective = base + coefficient * effectiveLevel
 *
 * <p>mob-types に無い EntityType は {@code defaults:}（基準戦闘レベル・座標係数・防御・HP・係数）を
 * タグ付き定義と同じ式でスケールして刻印する。defaults が全てゼロで HP 未設定のときは刻印せず、
 * 戦闘時の {@code defaultDefense} + バニラ防具合算パスを維持する。
 *
 * <p>HP 適用は他プラグインより後に走らせ、1tick 後にも再適用する。以前の
 * {@code setHealth(min(target, attr.getValue()))} は setBaseValue 直後に古い getValue()
 * （バニラ20など）へ現在HPを戻し、「スポーン時点ですでに削れている」「個体ごとにHPが違う」
 * ように見える原因になっていた。
 */
public final class MobTypeSpawnListener implements Listener {

    private static final Logger LOG = Logger.getLogger("TrinityForge");

    /**
     * サーバーの {@code spigot.yml settings.attribute.maxHealth.max} が原因でMAX_HEALTHが無言に
     * 頭打ちされたときの直近の「観測された上限」(検知の重複警告を防ぐレート制限用)。上限値が変わった
     * ときだけ再度WARNINGを出す({@code null} = まだ一度もクランプを観測していない)。
     */
    private static volatile Double lastWarnedHealthCeiling = null;

    /**
     * ArsPaper が召喚モブへ刻む PDC キー。フォークの {@code SummonedMobListener} が
     * {@code new NamespacedKey(plugin, "summoned")} で作るものと同一
     * （プラグイン名前空間は {@code arspaper}）。
     *
     * <p>ここを {@code NamespacedKey.fromString} ではなくリテラルで書いているのは、
     * フォークが名前を変えたときに<b>コンパイルではなくテストで落ちる</b>ようにするため
     * （キー名は文字列なのでどのみち実行時一致しか検証できない）。
     */
    static final NamespacedKey ARS_SUMMONED_KEY = new NamespacedKey("arspaper", "summoned");
    static final NamespacedKey ARS_SUMMONER_UUID_KEY =
            new NamespacedKey("arspaper", "summoner_uuid");

    private final Plugin plugin;
    private final MobTypesConfig mobTypesConfig;
    private final ConfigManager configManager;
    private final SkillLevelSource skillLevelSource;
    private final CombatLevelSource combatLevelSource;

    public MobTypeSpawnListener(Plugin plugin, MobTypesConfig mobTypesConfig, ConfigManager configManager) {
        this(plugin, mobTypesConfig, configManager, SkillLevelSource.EMPTY, CombatLevelSource.EMPTY);
    }

    /**
     * @param skillLevelSource 召喚モブのレベルを召喚者のスキルレベルから決めるための読み出し口
     *                         ({@code summoned:})。{@link SkillLevelSource#EMPTY} を渡すと
     *                         全スキル0扱いになり、召喚モブは {@code summoned.base-level} だけになる。
     */
    public MobTypeSpawnListener(Plugin plugin, MobTypesConfig mobTypesConfig,
                                ConfigManager configManager, SkillLevelSource skillLevelSource) {
        this(plugin, mobTypesConfig, configManager, skillLevelSource, CombatLevelSource.EMPTY);
    }

    /**
     * @param skillLevelSource  召喚モブのレベルを召喚者のスキルレベルから決めるための読み出し口
     *                          ({@code summoned:})。{@link SkillLevelSource#EMPTY} を渡すと
     *                          全スキル0扱いになり、召喚モブは {@code summoned.base-level} だけになる。
     * @param combatLevelSource 手懐けモブのレベルを飼い主の総合戦闘レベルから決めるための読み出し口
     *                          ({@code tamed:}、M-2)。{@link CombatLevelSource#EMPTY} を渡すと
     *                          常に総合戦闘レベル0扱いになり、手懐けモブは {@code tamed.base-level}
     *                          だけになる。
     */
    public MobTypeSpawnListener(Plugin plugin, MobTypesConfig mobTypesConfig,
                                ConfigManager configManager, SkillLevelSource skillLevelSource,
                                CombatLevelSource combatLevelSource) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobTypesConfig = Objects.requireNonNull(mobTypesConfig, "mobTypesConfig");
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.combatLevelSource = Objects.requireNonNull(combatLevelSource, "combatLevelSource");
    }

    /**
     * MONITOR: 他プラグインのスポーン後処理のあとに適用する。
     * 個別 EntityType 定義がある場合は EliteMobs 由来を除き必ず適用する
     * （先に MOB_LEVEL だけ付いたケースで defaults や他プラグインHPに負けるのを防ぐ）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        MobData data = MobData.of(entity);
        Optional<MobTypeDefinition> maybeDef = mobTypesConfig.definition(entity.getType());

        // 2026-07-29: 変身(ゾンビ→ドラウンド等)由来のスポーンなら、変身前のHP割合を引き継ぐ。
        // 未記録(通常のスポーン)なら 1.0 = 従来どおり満タン。早期returnする経路でも
        // 必ず consume するため、ここで先に取り出しておく(保留マップに滞留させない)。
        double healthRatio = MobTransformCarryOver.consumeHealthRatio(entity.getUniqueId());

        // 召喚モブ(Ars の召喚魔法)は「どこで召喚したか」ではなく「誰が召喚したか」で強さが決まる。
        // EliteMobs 所有のモブより先には置かない — EM が自分でレベルを決める個体を横取りしないため。
        if (!isEliteMobsOwned(data)) {
            OptionalInt summoned = summonedLevel(entity);
            if (summoned.isPresent()) {
                applySummonedProfile(entity, summoned.getAsInt(), maybeDef.orElse(null), healthRatio);
                return;
            }
        }

        if (maybeDef.isPresent()) {
            if (isEliteMobsOwned(data)) {
                applyDimensionLevelBonus(entity, data);
                return;
            }
            MobTypeDefinition def = maybeDef.get();
            applyScaledProfile(entity, def.level(), def.coordinateCoefficient(),
                    def.physical(), def.magical(), def.maxHealth(),
                    def.attack(), def.levelCoefficients(), "mob-types." + entity.getType().name(),
                    healthRatio, attackPowerHighLevelFor(entity.getType()));
            return;
        }

        if (data.hasProfile()) {
            applyDimensionLevelBonus(entity, data);
            return;
        }
        applyUntaggedDefaults(entity, healthRatio);
    }

    /**
     * 2026-08-02(指摘4修正): {@link #isEliteMobsOwned} が真のモブは上の2箇所の早期returnで
     * {@link #applyScaledProfile} を一切通らないため、{@code dimensions.<ENV>.base-level} の下駄が
     * これまで一切乗らなかった（取り込んだ EliteMobs モブのほぼ全部が対象外という致命的な抜け）。
     *
     * <p>EM が既に決めたレベル決定ロジック自体（防御/攻撃/HPの値）は一切書き換えず、
     * {@link MobData#adjustLevel} で {@code MOB_LEVEL} だけを「EM が刻んだ値 + 下駄」へ上書きする
     * 最小加算にする。{@code dimensions.<ENV>.coordinate-coefficient} の上書きはここでは適用しない
     * — EliteMobs モブは {@code distanceFromWorldSpawn} ベースの座標線形スケーリング
     * （{@link #applyScaledProfile} 専用の仕組み）を一切通らず、難度は EM 自身のダンジョンレベル/
     * {@code SpawnRadiusDifficultyIncrementer} で決まる別系統のため、座標係数を混ぜる意味がない。
     */
    private void applyDimensionLevelBonus(LivingEntity entity, MobData data) {
        if (!isEliteMobsOwned(data)) {
            return;
        }
        World.Environment environment = entity.getWorld().getEnvironment();
        int bonus = mobTypesConfig.dimensionBaseLevel(environment);
        if (bonus == 0) {
            return;
        }
        int eliteLevel = data.level();
        int adjusted = Math.max(0, eliteLevel + bonus);
        MobData.adjustLevel(entity, adjusted);
        reapplyDimensionBonusMaxHealth(entity, data, adjusted);
        LOG.fine("[mob-types] dimension level bonus applied to elite-owned "
                + entity.getType().name() + " environment=" + environment.name()
                + " baseLevel=" + eliteLevel + " bonus=" + bonus + " -> " + adjusted);
    }

    /**
     * 2026-08-03(棚卸し指摘: 次元基準レベルが攻撃力だけ上げてHPを上げていない): {@link MobData#adjustLevel}
     * は {@code MOB_LEVEL} だけを書き換える。{@code MOB_LEVEL} は {@link com.trinityforge.combat.SymmetricCombatService}
     * が攻撃のたびにライブで読む(攻撃力側の {@code DefaultDamageResolver} レベル倍率に即反映される)が、
     * HP は Bukkit の {@code MAX_HEALTH} 属性としてフォークが所有権を主張した時点で一度だけ焼き込まれる値
     * なので、この下駄だけでは絶対に反映されない — 「HPだけ置き去り」になる非対称の原因。
     *
     * <p>フォークが刻んだ {@code MOB_PROFILE_ID} から {@link ConfigManager#resolveRuntimeProfile} を
     * 下駄込みの {@code adjustedLevel} で呼び直し(フォーク自身が HP を決めるのと同じ経路の再利用)、
     * {@link MobProfile#hasMaxHealth()} なら Bukkit 属性を上書きする。{@code profileId} が無い
     * ({@code dungeonTheme} だけの)個体は解決キーが無いため何もしない(安全側、既存個体を壊さない)。
     */
    private void reapplyDimensionBonusMaxHealth(LivingEntity entity, MobData data, int adjustedLevel) {
        Optional<String> profileId = data.profileId();
        if (profileId.isEmpty()) {
            return;
        }
        long rollSeed = data.rollSeed().isPresent() ? data.rollSeed().getAsLong() : 0L;
        String worldName = entity.getWorld().getName();
        configManager.resolveRuntimeProfile(profileId.get(), adjustedLevel, rollSeed, worldName)
                .filter(MobProfile::hasMaxHealth)
                .ifPresent(profile -> applyMaxHealth(entity, profile.maxHealth(), currentHealthRatio(entity)));
    }

    /** 現在HP/現在MAX_HEALTH属性値の比率(0-1)。属性が無い/0以下なら安全側で満タン(1.0)扱い。 */
    private static double currentHealthRatio(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null || attr.getValue() <= 0.0) {
            return 1.0;
        }
        return clampRatio(entity.getHealth() / attr.getValue());
    }

    /**
     * この個体が EliteMobs（フォークの {@code TrinityForgeSpawnListener}）に所有されているか。
     *
     * <p>判定は {@code MOB_PROFILE_ID} と {@code MOB_DUNGEON_THEME} の or。
     * {@link MobData#hasProfile()}（= {@code MOB_LEVEL}）は使えない — このリスナー自身も
     * {@code MOB_LEVEL} を刻むため、mob-types 由来と EliteMobs 由来を区別できない。
     *
     * <p>【2026-07-30 修正】以前は {@code dungeonTheme} だけを見ていた。フォーク側は
     * {@code theme != null && !theme.isBlank()} のときしか {@code MOB_DUNGEON_THEME} を刻まず、
     * {@code combat/mob-import.yml} の {@code theme.default} は空文字なので、
     * <b>テーマ未設定の EliteMobs モブ（＝取り込んだモブのほぼ全部）がこの除外を通り抜けて</b>
     * mob-types のプロファイルで上書きされていた（{@code setBaseValue} なので EliteMobs 側の
     * HP がそのまま消える）。{@code MOB_PROFILE_ID} はフォークが CustomBoss へ必ず刻む
     * （プロファイル未登録で早期 return する経路でも刻む）ため、これが正しい所有者マーカー。
     */
    private static boolean isEliteMobsOwned(MobData data) {
        return data.profileId().isPresent() || data.dungeonTheme().isPresent();
    }

    /**
     * このモブが ArsPaper の召喚魔法で出たものなら、召喚者のスキルレベルから決めた戦闘レベル。
     * 召喚モブでない／設定が無効／召喚者が見つからない場合は空。
     *
     * <p>PDC キーは ArsPaper 側が<b>スポーン consumer の中で</b>書いているので、
     * {@code CreatureSpawnEvent} の時点で既に読める（後から付くのではない）。
     * キー名はフォークの {@code SummonedMobListener} が持つ {@code arspaper:summoned} /
     * {@code arspaper:summoner_uuid} と一対一で対応する。<b>片方でも改名されるとこの機能は
     * 無言で効かなくなる</b>ので、フォーク側を触るときは必ずここも見ること。
     *
     * <p>召喚者がオフライン/退出済みなら空を返す = 従来どおりの距離ベースへ落ちる。
     * 「召喚者不明の召喚モブ」に勝手なレベルを与えるより、既存挙動へ戻すほうが安全。
     */
    private OptionalInt summonedLevel(LivingEntity entity) {
        // null を潰しているのは、MobTypesConfig を Mockito でモックしたテストが未スタブの
        // アクセサで null を返すため(実 config は必ず DISABLED を返す)。attackPowerHighLevel が
        // 同じ理由で nonNull() を噛ませているのと同じ事情 — ここで NPE にすると、この機能と
        // 無関係な既存テストが道連れで落ちる。
        MobTypesConfig.SummonedLevelPolicy policy = mobTypesConfig.summonedLevelPolicy();
        if (policy == null || !policy.enabled()) {
            return OptionalInt.empty();
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (!pdc.has(ARS_SUMMONED_KEY, PersistentDataType.BYTE)) {
            return OptionalInt.empty();
        }
        String summonerUuid = pdc.get(ARS_SUMMONER_UUID_KEY, PersistentDataType.STRING);
        if (summonerUuid == null) {
            return OptionalInt.empty();
        }
        UUID casterId;
        try {
            casterId = UUID.fromString(summonerUuid);
        } catch (IllegalArgumentException ex) {
            return OptionalInt.empty();
        }
        int skillLevel = skillLevelSource.levelsOf(casterId).getOrDefault(policy.skill(), 0);
        return OptionalInt.of(policy.levelFor(skillLevel, mobTypesConfig.maxLevel()));
    }

    /**
     * 召喚モブへ、召喚者由来のレベルでステータスを刻む。
     *
     * <p>元になる耐久/攻撃の値は<b>そのEntityTypeの通常の定義をそのまま使う</b>
     * （定義が無ければ {@code defaults:}）。召喚された骨は「骨としての素の強さ」を保ったまま、
     * レベルだけが召喚者に追随する、という形にしている。
     */
    private void applySummonedProfile(LivingEntity entity, int level, MobTypeDefinition def,
                                      double healthRatio) {
        World.Environment environment = entity.getWorld().getEnvironment();
        double distance = distanceFromWorldSpawn(entity);
        if (def != null) {
            applyStatsAtLevel(entity, level, def.physical(), def.magical(), def.maxHealth(),
                    def.attack(), def.levelCoefficients(),
                    "summoned:" + entity.getType().name(), healthRatio,
                    attackPowerHighLevelFor(entity.getType()), environment, distance);
            return;
        }
        Double maxHealthBase = mobTypesConfig.defaultMaxHealth().isPresent()
                ? mobTypesConfig.defaultMaxHealth().getAsDouble() : null;
        applyStatsAtLevel(entity, level, mobTypesConfig.defaultDefense(DamageType.PHYSICAL),
                mobTypesConfig.defaultDefense(DamageType.MAGICAL), maxHealthBase,
                mobTypesConfig.defaultAttack(), mobTypesConfig.defaultLevelCoefficients(),
                "summoned:defaults", healthRatio,
                nonNull(mobTypesConfig.defaultAttackPowerHighLevel()), environment, distance);
    }

    /**
     * M-2: 手懐けた友好モブがテイムされた瞬間。テイム直後は満タン想定(healthRatio=1.0)でよい
     * (vanillaのテイム自体もHPを削らない)。EliteMobs所有(交配/特殊個体で先にEMがPDCを刻んでいる
     * ようなケース)なら何もしない。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        if (isEliteMobsOwned(MobData.of(entity))) {
            return;
        }
        applyTamedLevelFromOwner(entity, event.getOwner(), 1.0);
    }

    /**
     * M-2: チャンク読み込みで手懐けモブが再構築されるたびに、飼い主の総合戦闘レベルが
     * 後から上がった分を追随させる。{@code PlayerJoinEvent} で全ワールドを走査する案は
     * 「そのプレイヤーが飼い主であるモブ一覧」を得る仕組みが無く全走査が重いため採らない
     * （設計指示）。現在HPの比率を必ず引き継ぐ({@link #currentHealthRatio}) —
     * 引き継がないと「読み込みのたびに全快／即死する」事故になる。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity loaded : event.getEntities()) {
            if (!(loaded instanceof LivingEntity entity)) {
                continue;
            }
            if (isEliteMobsOwned(MobData.of(entity))) {
                continue;
            }
            if (loaded instanceof Tameable tameable && tameable.isTamed()) {
                applyTamedLevelFromOwner(entity, tameable.getOwner(), currentHealthRatio(entity));
                continue;
            }
            backfillUnstampedProfile(entity);
        }
    }

    /**
     * W-61（2026-08-18）: <b>{@code CreatureSpawnEvent} が一度も発火しないモブ</b>を拾う受け皿。
     *
     * <p>構造物に「最初から置かれている」モブ（データパックの構造物、バニラの前哨基地・海底神殿など、
     * 構造物テンプレートの NBT に焼かれている個体）は、<b>チャンクが生成された時点で既に存在する</b>ため
     * スポーン処理を通らない。Bukkit の {@code SpawnReason.CHUNK_GEN} も
     * <b>「もう呼ばれない」として非推奨</b>になっている（Paper 1.21 javadoc）。
     * 結果 {@link MobTypeSpawnListener#onSpawn} がそのモブに一度も触れず、{@code MOB_LEVEL} PDC が
     * 付かないまま {@link MobData#level()} が既定の 0 を返す ＝ <b>「Lv0 のモブ」</b>になる。
     *
     * <p>そこで「ワールドに実体が現れた」側からも同じ処理を掛ける。{@link EntitiesLoadEvent} と
     * {@code EntityAddToWorldEvent} の<b>両方</b>から呼ぶのは、前者が新規生成チャンクを含むかを
     * javadoc が明言しておらず（"Called when entities are loaded."）、後者だけでは
     * ディメンション移動などの経路差を読み切れないため。何度呼ばれても下の
     * {@code hasProfile()} で弾かれるので二重適用にはならない。
     */
    private void backfillUnstampedProfile(LivingEntity entity) {
        // ⚠ 対象は AI を持つモブ(org.bukkit.entity.Mob)だけに絞る。
        // この受け皿の入口は「LivingEntity がワールドに入った」であって、
        // {@code CreatureSpawnEvent} が扱う母集団より【広い】。特にアーマースタンドは
        // LivingEntity だが CreatureSpawnEvent を発火しないので、これまで TF が一度も
        // 触っていない。絞らないと、ホログラム/装飾/他プラグインの表示用アーマースタンドが
        // defaults(max-health 800 + 防御ステ)を刻まれ、MOB_TYPE_STAMPED まで付いて
        // ドロップ処理の対象にまで入ってしまう。
        if (!(entity instanceof org.bukkit.entity.Mob)) {
            return;
        }
        MobData data = MobData.of(entity);
        // 刻印済み（レベル0で刻まれた個体を含む）は絶対に触らない。ここを緩めると
        // チャンク読み込みのたびにステが再計算され、HPが張り直されて事故になる。
        if (data.hasProfile()) {
            return;
        }
        // 現在HPの比率は必ず引き継ぐ。引き継がないと「チャンクを読むたびに全快する」。
        double healthRatio = currentHealthRatio(entity);
        Optional<MobTypeDefinition> maybeDef = mobTypesConfig.definition(entity.getType());
        if (maybeDef.isPresent()) {
            MobTypeDefinition def = maybeDef.get();
            applyScaledProfile(entity, def.level(), def.coordinateCoefficient(),
                    def.physical(), def.magical(), def.maxHealth(),
                    def.attack(), def.levelCoefficients(),
                    "backfill:mob-types." + entity.getType().name(),
                    healthRatio, attackPowerHighLevelFor(entity.getType()));
            return;
        }
        applyUntaggedDefaults(entity, healthRatio);
    }

    /**
     * W-61 のもう一方の入口。{@link #backfillUnstampedProfile} の javadoc を参照。
     *
     * <p>通常のスポーンでは {@code CreatureSpawnEvent}（キャンセル可能＝実体をワールドへ入れる前）が
     * 先に飛ぶので、ここへ来る頃には {@link #onSpawn} が刻印済みで素通りする。
     * EliteMobs が {@code EliteMobSpawnEvent} で所有権を主張する個体も同様に刻印済みなので、
     * この受け皿が横取りすることはない。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || entity instanceof Player) {
            return;
        }
        if (isEliteMobsOwned(MobData.of(entity))) {
            return;
        }
        backfillUnstampedProfile(entity);
    }

    private void applyTamedLevelFromOwner(LivingEntity entity, AnimalTamer owner, double healthRatio) {
        if (owner == null) {
            return;
        }
        OptionalInt level = tamedLevelFor(owner.getUniqueId());
        if (level.isEmpty()) {
            return;
        }
        applyTamedProfile(entity, level.getAsInt(), healthRatio);
    }

    /**
     * 飼い主のUUIDから、{@code tamed:} ポリシーに基づく手懐けモブのレベルを出す。
     * Bukkitイベント/エンティティを一切使わない純関数なので、MockBukkitが
     * {@code EntityTameEvent}/{@code Tameable} を実装していない場合でも直接単体テストできる
     * (テスト方針)。
     */
    OptionalInt tamedLevelFor(UUID ownerId) {
        // null を潰しているのは summonedLevel() と同じ事情(Mockitoモックの未スタブアクセサ対策)。
        MobTypesConfig.TamedLevelPolicy policy = mobTypesConfig.tamedLevelPolicy();
        if (policy == null || !policy.enabled() || ownerId == null) {
            return OptionalInt.empty();
        }
        int combatLevel = combatLevelSource.combatLevelOf(ownerId);
        return OptionalInt.of(policy.levelFor(combatLevel, mobTypesConfig.maxLevel()));
    }

    /**
     * 手懐けモブへ、飼い主由来のレベルでステータスを刻む。{@link #applySummonedProfile} と同じ考え方
     * (対象EntityTypeの通常の定義をそのまま使い、レベルだけを外部要因で決める)だが、ソース文字列を
     * "tamed:" にして召喚モブのログと混同しないようにしている。
     */
    private void applyTamedProfile(LivingEntity entity, int level, double healthRatio) {
        MobTypeDefinition def = mobTypesConfig.definition(entity.getType()).orElse(null);
        World.Environment environment = entity.getWorld().getEnvironment();
        double distance = distanceFromWorldSpawn(entity);
        if (def != null) {
            applyStatsAtLevel(entity, level, def.physical(), def.magical(), def.maxHealth(),
                    def.attack(), def.levelCoefficients(),
                    "tamed:" + entity.getType().name(), healthRatio,
                    attackPowerHighLevelFor(entity.getType()), environment, distance);
            return;
        }
        Double maxHealthBase = mobTypesConfig.defaultMaxHealth().isPresent()
                ? mobTypesConfig.defaultMaxHealth().getAsDouble() : null;
        applyStatsAtLevel(entity, level, mobTypesConfig.defaultDefense(DamageType.PHYSICAL),
                mobTypesConfig.defaultDefense(DamageType.MAGICAL), maxHealthBase,
                mobTypesConfig.defaultAttack(), mobTypesConfig.defaultLevelCoefficients(),
                "tamed:defaults", healthRatio,
                nonNull(mobTypesConfig.defaultAttackPowerHighLevel()), environment, distance);
    }

    private void applyUntaggedDefaults(LivingEntity entity, double healthRatio) {
        int baseLevel = mobTypesConfig.defaultLevel();
        double coordinateCoefficient = mobTypesConfig.defaultCoordinateCoefficient();
        DefenseStats physicalBase = mobTypesConfig.defaultDefense(DamageType.PHYSICAL);
        DefenseStats magicalBase = mobTypesConfig.defaultDefense(DamageType.MAGICAL);
        Double maxHealthBase = mobTypesConfig.defaultMaxHealth().isPresent()
                ? mobTypesConfig.defaultMaxHealth().getAsDouble() : null;
        MobLevelCoefficients coeffs = mobTypesConfig.defaultLevelCoefficients();

        // 2026-08-02(指摘4副次修正): dimensions.<ENV>.base-level/coordinate-coefficient だけを設定して
        // defaults.level=0 かつ defaults.coordinate-coefficient=0 のまま運用しようとすると、この下駄を
        // hasLeveling に勘定していなかったせいで下のガードに巻き込まれ applyScaledProfile 自体が
        // 呼ばれず(PDC刻印すら発生せず)無効化されていた。ディメンションの下駄もレベリングありと
        // 数える。
        World.Environment environment = entity.getWorld().getEnvironment();
        boolean hasDimensionLeveling = mobTypesConfig.dimensionBaseLevel(environment) != 0
                || mobTypesConfig.dimensionCoordinateCoefficient(environment).isPresent();
        boolean hasLeveling = baseLevel > 0 || coordinateCoefficient != 0.0 || hasDimensionLeveling;
        boolean hasDefense = !isZeroDefense(physicalBase) || !isZeroDefense(magicalBase);
        boolean hasScaling = !isZeroCoeffs(coeffs);
        if (maxHealthBase == null && !hasLeveling && !hasDefense && !hasScaling) {
            return;
        }
        applyScaledProfile(entity, baseLevel, coordinateCoefficient,
                physicalBase, magicalBase, maxHealthBase,
                mobTypesConfig.defaultAttack(), coeffs, "defaults", healthRatio,
                nonNull(mobTypesConfig.defaultAttackPowerHighLevel()));
    }

    /**
     * 2026-08-03(要件#63の残り): この EntityType の攻撃力 高レベル区間。
     *
     * <p>{@code null} を潰しているのは、{@code MobTypesConfig} を Mockito でモックしたテストが
     * 未スタブのアクセサで {@code null} を返すため（実 config は必ず
     * {@link MobTypesConfig.AttackPowerHighLevelPhase#NONE} を返す）。ここで NPE にすると、
     * 本来この修正と無関係な既存テストが道連れで落ちる。
     */
    private MobTypesConfig.AttackPowerHighLevelPhase attackPowerHighLevelFor(
            org.bukkit.entity.EntityType type) {
        return nonNull(mobTypesConfig.attackPowerHighLevel(type));
    }

    private static MobTypesConfig.AttackPowerHighLevelPhase nonNull(
            MobTypesConfig.AttackPowerHighLevelPhase phase) {
        return phase == null ? MobTypesConfig.AttackPowerHighLevelPhase.NONE : phase;
    }

    private void applyScaledProfile(LivingEntity entity, int baseLevel, double coordinateCoefficient,
                                    DefenseStats physicalBase, DefenseStats magicalBase,
                                    Double maxHealthBase,
                                    AttackStats attackBase,
                                    MobLevelCoefficients coeffs,
                                    String source,
                                    double healthRatio,
                                    MobTypesConfig.AttackPowerHighLevelPhase attackPowerHighLevel) {
        double distance = distanceFromWorldSpawn(entity);
        // 2026-08-02: ディメンション別の基準レベル下駄(dimensions.<ENV>.base-level)をここで加算する。
        // World.Environment で引く(ワールド名はネザー/エンド/EliteMobsインスタンスとも構成依存で
        // 一致しないため)。未設定のEnvironmentは0が返るので、dimensions: セクションを一切書かない
        // 既存configでは baseLevel/coordinateCoefficient とも完全に不変(後方互換)。
        World.Environment environment = entity.getWorld().getEnvironment();
        int adjustedBaseLevel = baseLevel + mobTypesConfig.dimensionBaseLevel(environment);
        double adjustedCoordinateCoefficient = mobTypesConfig.dimensionCoordinateCoefficient(environment)
                .orElse(coordinateCoefficient);
        // CMB-21: clamp to mob-types.yml's configured max-level (default 100) so distant mobs cannot
        // scale to an unbounded level (which saturates penetration and makes defense stats moot).
        int level = MobLevelScaling.effectiveLevel(
                adjustedBaseLevel, adjustedCoordinateCoefficient, distance, mobTypesConfig.maxLevel());
        applyStatsAtLevel(entity, level, physicalBase, magicalBase, maxHealthBase, attackBase,
                coeffs, source, healthRatio, attackPowerHighLevel, environment, distance);
    }

    /**
     * 解決済みの {@code level} でステータスを刻む。{@link #applyScaledProfile} から切り出してあるのは、
     * <b>召喚モブだけレベルの決め方が違う</b>ため — 通常のモブは「ワールドスポーンからの距離」で
     * レベルが決まるが、召喚モブは召喚者のスキルレベルで決まる（{@link #summonedLevel}）。
     * 距離計算を通した後で上書きするのでは {@code dimensions.<ENV>.coordinate-coefficient} が
     * 効いてしまい、拠点で召喚したか遠征先で召喚したかで強さが変わってしまう。
     */
    private void applyStatsAtLevel(LivingEntity entity, int level,
                                   DefenseStats physicalBase, DefenseStats magicalBase,
                                   Double maxHealthBase, AttackStats attackBase,
                                   MobLevelCoefficients coeffs, String source, double healthRatio,
                                   MobTypesConfig.AttackPowerHighLevelPhase attackPowerHighLevel,
                                   World.Environment environment, double distance) {
        double armorBase = physicalBase.armorStrength();
        DefenseStats physical = MobStatScaling.scaleDefense(
                physicalBase, coeffs.physical(), armorBase, coeffs.armorStrength(), level);
        DefenseStats magical = MobStatScaling.scaleDefense(
                magicalBase, coeffs.magical(), armorBase, coeffs.armorStrength(), level);
        AttackStats scaledAttack = MobStatScaling.scaleAttack(attackBase, coeffs.attack(), level);
        // 2026-08-03(要件#63の残り): Lv45以降だけ効く加算専用の第2区間。
        // MobStatScaling / MobLevelCoefficients は別レーン所有で触れないため、ランプ本体ではなく
        // ここで結果へ足す(MobTypesConfig.AttackPowerHighLevelPhase の javadoc に経緯とやり残し)。
        // 未設定(NONE)なら bonusAt() は必ず 0.0 を返すので、既存configの挙動は1ミリも変わらない。
        double attackPowerHighLevelBonus = attackPowerHighLevel.bonusAt(level);
        if (attackPowerHighLevelBonus != 0.0) {
            scaledAttack = scaledAttack.withDefaultDamage(
                    scaledAttack.defaultDamage() + attackPowerHighLevelBonus);
        }
        MobData.stampMobType(entity, level, physical, magical);
        // 高レベル区間だけを設定した config(base も係数も 0)でも刻印が発火するよう or を取る。
        // 取らないと「yml に書いたのに攻撃ステが刻まれず何も起きない」死んだ設定になる。
        if (hasConfiguredAttack(attackBase, coeffs.attack()) || attackPowerHighLevel.isActive()) {
            MobData.stampAttack(entity, scaledAttack);
        }
        syncVanillaArmorIcons(entity, physical.armorStrength());

        Double appliedMaxHealth = null;
        if (maxHealthBase != null) {
            appliedMaxHealth = MobStatScaling.scaleMaxHealth(
                    maxHealthBase, coeffs.maxHealth(),
                    coeffs.maxHealthGrowth(), coeffs.maxHealthGrowthInterval(),
                    coeffs.maxHealthHighLevelFrom(), coeffs.maxHealthHighLevelPerLevel(), level);
            applyMaxHealth(entity, appliedMaxHealth, healthRatio);
            scheduleHealthReassert(entity, appliedMaxHealth, healthRatio, physical.armorStrength());
        }

        String msg = "[mob-types] spawn "
                + entity.getType().name()
                + " via " + source
                + " environment=" + environment.name()
                + " effectiveLevel=" + level
                + " distance=" + String.format(Locale.ROOT, "%.1f", distance)
                + " armorStrength=" + String.format(Locale.ROOT, "%.3f", physical.armorStrength())
                + " attackPower=" + String.format(Locale.ROOT, "%.2f", scaledAttack.defaultDamage())
                + (attackPowerHighLevelBonus != 0.0
                    ? (" attackHighLevelBonus=+"
                        + String.format(Locale.ROOT, "%.2f", attackPowerHighLevelBonus))
                    : "")
                + (appliedMaxHealth != null
                    ? (" maxHealth=" + String.format(Locale.ROOT, "%.1f", appliedMaxHealth))
                    : " maxHealth=(vanilla)")
                + " currentHealth=" + String.format(Locale.ROOT, "%.1f", entity.getHealth());
        // Per-spawn diagnostics only — keep off the default INFO console (too noisy on busy worlds).
        LOG.fine(msg);
    }

    /**
     * Other plugins often adjust MAX_HEALTH on the same spawn tick. Re-assert next tick so the
     * configured value wins and current HP stays full.
     *
     * <p>ただし EliteMobs はこの1tickの間に所有権を主張しうる：{@code CreatureSpawnEvent}（このリスナー）
     * のあとに {@code EliteMobSpawnEvent} が飛び、フォークがそこで初めて {@code MOB_PROFILE_ID} と
     * EliteMobs 側の HP を刻む。再適用は {@code setBaseValue} で無条件に上書きするため、
     * 再チェックせずに走らせるとエリート化直後の個体の HP が毎回 mob-types 値へ潰れる（2026-07-30）。
     */
    private void scheduleHealthReassert(LivingEntity entity, double maxHealth, double healthRatio,
                                        double armorStrength) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!entity.isValid() || entity.isDead()) {
                return;
            }
            if (isEliteMobsOwned(MobData.of(entity))) {
                return;
            }
            applyMaxHealth(entity, maxHealth, healthRatio);
            syncVanillaArmorIcons(entity, armorStrength);
        });
    }

    private static boolean isZeroDefense(DefenseStats stats) {
        // null を「防御設定なし」として潰すのは attackPowerHighLevelFor の nonNull と同じ事情:
        // 実 config は必ず DefenseStats.NONE を返すが、Mockito でモックした MobTypesConfig は
        // 未スタブのアクセサで null を返す。W-61 の受け皿は EntitiesLoadEvent からも呼ばれるので、
        // ここで NPE にすると本来無関係な既存テストが道連れで落ちる。
        if (stats == null) {
            return true;
        }
        return stats.defenseRate() == 0.0
                && stats.resistance() == 0.0
                && stats.damageReduction() == 0.0
                && stats.flatDefense() == 0.0
                && stats.armorStrength() == 0.0;
    }

    private static boolean isZeroCoeffs(MobLevelCoefficients coeffs) {
        // null 潰しの事情は isZeroDefense と同じ（Mockito の未スタブアクセサ対策）。
        if (coeffs == null) {
            return true;
        }
        return coeffs.maxHealth() == 0.0
                && coeffs.armorStrength() == 0.0
                && isZeroDefenseCoeffs(coeffs.physical())
                && isZeroDefenseCoeffs(coeffs.magical());
    }

    private static boolean isZeroDefenseCoeffs(MobLevelCoefficients.DefenseCoeffs c) {
        return c.defenseRate() == 0.0
                && c.resistance() == 0.0
                && c.damageReduction() == 0.0
                && c.flatDefense() == 0.0;
    }

    /** パッケージプライベート(CMB-20テスト用: 他プラグイン相当のmodifierが残ることを直接検証する)。 */
    void applyMaxHealth(LivingEntity entity, double maxHealth) {
        applyMaxHealth(entity, maxHealth, 1.0);
    }

    /**
     * {@code healthRatio} は「最大HPのうちどれだけ現在HPとして入れるか」[0,1]。
     * 通常スポーンは 1.0(満タン)。変身由来のスポーンだけが変身前の割合を持ち込む —
     * 削ったゾンビを水に落とすだけで全回復させないため(2026-07-29)。
     */
    void applyMaxHealth(LivingEntity entity, double maxHealth, double healthRatio) {
        AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) {
            return;
        }
        double value = Math.max(1.0, maxHealth);
        // CMB-20: only drop TF's own transient MAX_HEALTH modifiers so getValue()/display match the
        // configured base. Removing every modifier unconditionally (previous behaviour) also stripped
        // EliteMobs'/other plugins' modifiers on the SAME spawn tick, silently discarding their HP
        // buffs. Reuses PerkAttributeApplier#clearOwnModifiers' identification rule (own NamespacedKey
        // namespace == this plugin) rather than inventing a new one.
        for (AttributeModifier modifier : List.copyOf(attr.getModifiers())) {
            if (isOwnModifier(modifier)) {
                attr.removeModifier(modifier);
            }
        }
        attr.setBaseValue(value);
        // Always fill to the intended max. Never clamp to a stale getValue() (was vanilla 20 etc.).
        // 変身由来のスポーンのときだけ healthRatio < 1 になり、削られた分を引き継ぐ。
        double target = Math.max(1.0, Math.min(value, value * clampRatio(healthRatio)));
        try {
            entity.setHealth(target);
        } catch (IllegalArgumentException ex) {
            // entity.setHealth(value) rejected value because attr.getValue() (the server-enforced
            // ceiling, e.g. spigot.yml settings.attribute.maxHealth.max) is lower than the requested
            // max health. This server currently raises that ceiling to Double.MAX_VALUE, so this path
            // should never trigger in normal operation — if it does, high-level mob HP is silently
            // collapsing to the ceiling with no other symptom, which is why we warn (rate-limited).
            double ceiling = attr.getValue();
            warnHealthCeilingClamp(value, ceiling);
            entity.setHealth(Math.max(1.0, Math.min(target, ceiling)));
        }
    }

    /** 不正値(NaN/負/1超)を [0,1] へ丸める。未指定相当の値は安全側(満タン)へ。 */
    private static double clampRatio(double ratio) {
        if (!Double.isFinite(ratio)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    /**
     * サーバーのMAX_HEALTH上限クランプを検知したときに1回だけ(=上限値が変わるまでは再度出さない)
     * WARNINGを出す。毎spawnで出すとログが埋まるためレート制限する。
     *
     * <p>パッケージプライベート(テスト用)。MockBukkitの{@code LivingEntityMock#setHealth}は実サーバーと
     * 異なり例外を投げず{@code Math.min(value, getMaxHealth())}へ静かにクランプするため、
     * {@link #applyMaxHealth}のcatch経路自体はMockBukkit経由のイベント発火では再現できない
     * (MockBukkit回避策)。そのためこのレート制限ロジック自体を単体で直接検証する。
     *
     * @return 実際にWARNINGを出した場合は{@code true}(テストのアサーション用)。
     */
    static boolean warnHealthCeilingClamp(double requested, double ceiling) {
        if (ceiling >= requested - 0.01) {
            return false; // not a meaningful clamp
        }
        Double previous = lastWarnedHealthCeiling;
        if (previous != null && Math.abs(previous - ceiling) < 0.01) {
            return false; // same ceiling already warned about
        }
        lastWarnedHealthCeiling = ceiling;
        LOG.warning(String.format(Locale.ROOT,
                "[mob-types] MAX_HEALTH clamped by server ceiling: requested=%.1f achieved=%.1f "
                        + "-- check spigot.yml settings.attribute.maxHealth.max (mob HP silently caps "
                        + "here if that ceiling is lower than configured mob-types values).",
                requested, ceiling));
        return true;
    }

    /** テスト専用リセット(レート制限用の静的状態をテスト間で隔離する)。 */
    static void resetHealthCeilingWarningStateForTests() {
        lastWarnedHealthCeiling = null;
    }

    /**
     * Aligns vanilla {@link Attribute#ARMOR} with stamped 防具強度 for HUD icons. Combat for
     * stamped mobs already reads PDC (not vanilla armor), so this is display-only.
     */
    /** パッケージプライベート(CMB-20テスト用)。 */
    void syncVanillaArmorIcons(LivingEntity entity, double armorStrength) {
        // CMB-20: same fix as applyMaxHealth — only remove TF-owned modifiers, never other plugins'.
        AttributeInstance armor = entity.getAttribute(Attribute.ARMOR);
        if (armor != null) {
            for (AttributeModifier modifier : List.copyOf(armor.getModifiers())) {
                if (isOwnModifier(modifier)) {
                    armor.removeModifier(modifier);
                }
            }
            armor.setBaseValue(Math.max(0.0, Math.floor(armorStrength)));
        }
        AttributeInstance toughness = entity.getAttribute(Attribute.ARMOR_TOUGHNESS);
        if (toughness != null) {
            for (AttributeModifier modifier : List.copyOf(toughness.getModifiers())) {
                if (isOwnModifier(modifier)) {
                    toughness.removeModifier(modifier);
                }
            }
            toughness.setBaseValue(0.0);
        }
    }

    /**
     * CMB-20: identifies a TF-owned attribute modifier by NamespacedKey namespace, reusing the same
     * rule as {@code PerkAttributeApplier#clearOwnModifiers} (namespace == this plugin's own). This
     * class does not currently stamp any of its own {@link AttributeModifier}s onto mob attributes
     * (it only calls {@code setBaseValue}), so today this always evaluates to {@code false} — which is
     * the correct, minimal fix: nothing here is TF's to remove, so nothing should be removed. This
     * keeps the removal loop future-proof if this class ever starts adding its own modifiers.
     */
    boolean isOwnModifier(AttributeModifier modifier) {
        return modifier.getKey().getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT));
    }

    private double distanceFromWorldSpawn(LivingEntity entity) {
        Location spawn = entity.getWorld().getSpawnLocation();
        try {
            return entity.getLocation().distance(spawn);
        } catch (IllegalArgumentException ex) {
            return 0.0;
        }
    }

    private static boolean hasConfiguredAttack(AttackStats base, MobLevelCoefficients.AttackCoeffs coeffs) {
        if (base.defaultDamage() != 0 || base.flatBonusDamage() != 0 || base.percentBonusDamage() != 0
                || base.penetration() != 0 || base.critChance() != 0 || base.critDamage() != 0
                || base.damageModifier() != 1 || base.fixedDamage() != 0) {
            return true;
        }
        return coeffs.attackPower() != 0 || coeffs.flatBonusDamage() != 0
                || coeffs.percentBonusDamage() != 0 || coeffs.penetration() != 0
                || coeffs.critChance() != 0 || coeffs.critDamage() != 0
                || coeffs.damageModifier() != 0 || coeffs.fixedDamage() != 0;
    }
}
