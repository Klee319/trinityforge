package com.trinityforge.ops;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.listeners.MobOverrideExpListener;
import com.trinityforge.mobs.LevelTierRule;
import com.trinityforge.mobs.MobDropEntry;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobStatScaling;
import com.trinityforge.mobs.MobTypeDefinition;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 資源サーバ分離の事前検証: <b>EliteMobs を載せないサーバでも、モブのレベル推移と報酬テーブルが
 * 機能すること</b>をサーバを立てずに確定させる（作業計画 T1）。
 *
 * <p>検証の骨子は、TF のモブ系が二系統に分かれているという事実に立脚する。
 * <ul>
 *   <li>{@code combat/mob-types.yml} + {@code combat/mob-level-table.yml} —
 *       <b>バニラ／フィールドモブ用</b>。適用条件は「戦闘レベルが刻印されていること」だけで、
 *       刻印は TF 自身の {@code MobTypeSpawnListener} が行う。EliteMobs は要らない。</li>
 *   <li>{@code combat/mob-overrides.yml} —
 *       <b>EliteMobs 専用</b>。{@code MobData#profileId()}（= {@code MOB_PROFILE_ID} PDC）が
 *       刻印された個体にしか反応せず、その刻印は EliteMobs フォークしか書かない。</li>
 * </ul>
 * よって資源サーバでは前者が全面的に効き、後者は一切発火しない。本テストはその両方を
 * <b>出荷 yml そのもの</b>に対して確認し、レベル×モブの数値表を
 * {@code ops/reports/resource-server-mob-simulation.md} に書き出す。
 *
 * <p>個々の部品（パーサ・リスナーの分岐・スケーリング関数）には既に専用の単体テストがある。
 * 本テストが足しているのは「出荷設定を通しで流したときに、EliteMobs 不在でも全帯が埋まるか」という
 * 結合レベルの確認と、人間が目視でバランスを見るための成果物である。
 */
class ResourceServerMobSimulationTest {

    /** 表とアサーションで走査するレベル。0 は「ワールドスポーン地点の未スケール個体」。 */
    private static final int[] SIMULATED_LEVELS = {0, 1, 10, 25, 50, 75, 100};

    /** 表の見出しに使う代表モブ（詳細表は全モブを載せる）。 */
    private static final List<EntityType> SHOWCASE = List.of(
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER,
            EntityType.ENDERMAN, EntityType.WITHER_SKELETON, EntityType.BLAZE, EntityType.PIGLIN_BRUTE);

    private static final double EPSILON = 1.0e-9;

    /**
     * Spigot の {@code settings.attribute.maxHealth.max} の既定値。既存サーバはこれを
     * {@code Double.MAX_VALUE} まで引き上げてあるが、新設サーバは既定のままになるため、
     * ここを超える HP は無言でクランプされる。
     */
    private static final double SPIGOT_DEFAULT_MAX_HEALTH_CEILING = 1024.0;

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------------------------------
    // 1. フィールドモブのレベル推移とステータス（EliteMobs 不要の経路）
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("出荷 mob-types.yml: 全モブが全レベル帯で有限・非負・レベル単調のステータスを返す")
    void shippedMobTypesScaleMonotonicallyAcrossEveryLevel(@TempDir Path dir) throws Exception {
        MobTypesConfig mobTypes = loadMobTypes(dir);
        Map<EntityType, MobTypeDefinition> definitions = mobTypes.all();

        assertFalse(definitions.isEmpty(),
                "出荷 mob-types.yml に定義が 1 件も無い。資源サーバではフィールドモブが素のバニラになる");

        for (MobTypeDefinition def : definitions.values()) {
            String who = def.entityType().name();
            double previousHealth = Double.NEGATIVE_INFINITY;
            double previousAttack = Double.NEGATIVE_INFINITY;

            for (int level : SIMULATED_LEVELS) {
                double health = scaledHealth(def, level);
                AttackStats attack = MobStatScaling.scaleAttack(def.attack(), def.levelCoefficients().attack(), level);
                DefenseStats physical = scaledPhysical(def, level);

                assertTrue(Double.isFinite(health) && health >= 1.0,
                        who + " Lv" + level + ": maxHealth が有限かつ 1 以上でない -> " + health);
                assertTrue(Double.isFinite(attack.defaultDamage()) && attack.defaultDamage() >= 0.0,
                        who + " Lv" + level + ": 攻撃力が有限かつ非負でない -> " + attack.defaultDamage());
                assertTrue(Double.isFinite(physical.flatDefense()) && physical.flatDefense() >= 0.0,
                        who + " Lv" + level + ": 固定防御が有限かつ非負でない -> " + physical.flatDefense());
                assertTrue(Double.isFinite(physical.armorStrength()) && physical.armorStrength() >= 0.0,
                        who + " Lv" + level + ": 防具強度が有限かつ非負でない -> " + physical.armorStrength());

                assertTrue(health >= previousHealth - EPSILON,
                        who + " Lv" + level + ": maxHealth がレベルとともに減少した ("
                                + previousHealth + " -> " + health + ")");
                assertTrue(attack.defaultDamage() >= previousAttack - EPSILON,
                        who + " Lv" + level + ": 攻撃力がレベルとともに減少した ("
                                + previousAttack + " -> " + attack.defaultDamage() + ")");
                previousHealth = health;
                previousAttack = attack.defaultDamage();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. 報酬テーブル（討伐時バニラ EXP とドロップ）
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("出荷 mob-level-table.yml: 敵対モブは全レベルで帯が解決し、討伐 EXP が確定する")
    void shippedLevelTableCoversEveryHostileMobAtEveryLevel(@TempDir Path dir) throws Exception {
        MobTypesConfig mobTypes = loadMobTypes(dir);
        MobLevelTableConfig levelTable = loadLevelTable(dir);

        assertFalse(levelTable.dungeonOnly(),
                "mob-level-table.yml の dungeon-only が true。資源サーバにはダンジョンワールドが無いため "
                        + "ドロップ削除/追加/バニラEXP がまるごと効かなくなる");

        List<String> uncovered = new ArrayList<>();
        for (MobTypeDefinition def : sortedDefinitions(mobTypes)) {
            if (!isHostile(def.entityType())) {
                continue; // 受動モブに固定EXPを与える設計ではない（バニラ既定のまま）。§4 の表に載せて可視化する。
            }
            for (int level : SIMULATED_LEVELS) {
                if (!hasResolvedKillExp(levelTable, def.entityType(), level)) {
                    uncovered.add(def.entityType().name() + "@Lv" + level);
                }
            }
        }

        assertTrue(uncovered.isEmpty(),
                "以下の敵対モブ×レベルで討伐時バニラEXPが未確定（帯に載っていないか vanilla-exp 未設定）。"
                        + "資源サーバではこれらの討伐報酬がバニラ既定値のままになる: " + uncovered);
    }

    /**
     * Paper の {@link org.bukkit.entity.Enemy} で敵対判定する。Bukkit の {@code Monster}/{@code Animals}
     * は分類に使えない（例: HOGLIN は {@code Animals} だが敵対）という、このプロジェクトで既に踏んだ
     * 落とし穴を踏み直さないための基準。
     */
    private static boolean isHostile(EntityType type) {
        Class<?> clazz = type.getEntityClass();
        return clazz != null && org.bukkit.entity.Enemy.class.isAssignableFrom(clazz);
    }

    /** フィールドモブは profileId を持たないので、EliteMobs 不在の実挙動と同じ {@code null} を渡す。 */
    private static boolean hasResolvedKillExp(MobLevelTableConfig levelTable, EntityType type, int level) {
        return levelTable.resolve(level)
                .filter(rule -> rule.appliesTo(type, null))
                .map(LevelTierRule::vanillaExp)
                .isPresent();
    }

    // ------------------------------------------------------------------------------------------
    // 3. EliteMobs 専用経路が資源サーバで発火しないことの確認
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("出荷 mob-overrides.yml: profileId を持たないフィールドモブには一切適用されない")
    void shippedMobOverridesNeverApplyWithoutAnEliteMobsStamp(@TempDir Path dir) throws Exception {
        MobOverridesConfig overrides = loadOverrides(dir);
        // profileId 未刻印パスは combatService.combatLevelOf を呼ぶ前に return するため、モックの
        // 挙動は無関係(2026-07-27 足きり新設でコンストラクタにSymmetricCombatServiceが必須になった)。
        MobOverrideExpListener listener = new MobOverrideExpListener(overrides, mock(SymmetricCombatService.class));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        // MOB_PROFILE_ID は書かない = EliteMobs フォークが居ないサーバでのスポーン状態そのもの。
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
        List<ItemStack> drops = new ArrayList<>();
        EntityDeathEvent event = new EntityDeathEvent(zombie, source, drops);
        event.setDroppedExp(7);
        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) zombie).setKiller(server.addPlayer());

        listener.onDeath(event);

        assertEquals(7, event.getDroppedExp(),
                "profileId 未刻印のモブに mob-overrides.yml が適用された。資源サーバの前提が崩れている");
    }

    // ------------------------------------------------------------------------------------------
    // 4. 成果物: 人間が目視でバランスを確認するためのレポート
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("シミュレーション結果を ops/reports/ に書き出す")
    void writesSimulationReport(@TempDir Path dir) throws Exception {
        MobTypesConfig mobTypes = loadMobTypes(dir);
        MobLevelTableConfig levelTable = loadLevelTable(dir);
        MobOverridesConfig overrides = loadOverrides(dir);

        Path written = OpsReport.write("resource-server-mob-simulation.md",
                renderReport(mobTypes, levelTable, overrides));

        assertTrue(Files.exists(written), "レポートが書き出されていない: " + written);
        assertTrue(Files.size(written) > 2_000,
                "レポートが不自然に小さい。設定の読み込みに失敗している可能性がある: " + Files.size(written) + " bytes");
    }

    // ------------------------------------------------------------------------------------------
    // 設定ロード（出荷 yml をテストクラスパスから取り出して実ロード経路に通す）
    // ------------------------------------------------------------------------------------------

    private static MobTypesConfig loadMobTypes(Path dir) throws Exception {
        OpsReport.copyShippedResource(dir, MobTypesConfig.PATH);
        MobTypesConfig config = new MobTypesConfig();
        assertTrue(config.load(OpsReport.fakePlugin(dir, "ops-mob-types")),
                "出荷 " + MobTypesConfig.PATH + " のロードが警告付きで完了した（スキップされた定義がある）");
        return config;
    }

    private static MobLevelTableConfig loadLevelTable(Path dir) throws Exception {
        OpsReport.copyShippedResource(dir, MobLevelTableConfig.PATH);
        MobLevelTableConfig config = new MobLevelTableConfig();
        assertTrue(config.load(OpsReport.fakePlugin(dir, "ops-level-table")),
                "出荷 " + MobLevelTableConfig.PATH + " のロードが警告付きで完了した");
        return config;
    }

    private static MobOverridesConfig loadOverrides(Path dir) throws Exception {
        OpsReport.copyShippedResource(dir, MobOverridesConfig.PATH);
        MobOverridesConfig config = new MobOverridesConfig();
        assertTrue(config.load(OpsReport.fakePlugin(dir, "ops-overrides")),
                "出荷 " + MobOverridesConfig.PATH + " のロードが警告付きで完了した");
        return config;
    }

    // ------------------------------------------------------------------------------------------
    // スケーリング（本番と同じ関数を呼ぶ。式をテスト側で再実装しない）
    // ------------------------------------------------------------------------------------------

    private static double scaledHealth(MobTypeDefinition def, int level) {
        MobLevelCoefficients coeffs = def.levelCoefficients();
        double base = def.maxHealth() == null ? 20.0 : def.maxHealth();
        return MobStatScaling.scaleMaxHealth(base, coeffs.maxHealth(),
                coeffs.maxHealthGrowth(), coeffs.maxHealthGrowthInterval(), level);
    }

    private static DefenseStats scaledPhysical(MobTypeDefinition def, int level) {
        MobLevelCoefficients coeffs = def.levelCoefficients();
        return MobStatScaling.scaleDefense(def.physical(), coeffs.physical(),
                def.physical().armorStrength(), coeffs.armorStrength(), level);
    }

    private static List<MobTypeDefinition> sortedDefinitions(MobTypesConfig config) {
        List<MobTypeDefinition> all = new ArrayList<>(config.all().values());
        all.sort(Comparator.comparing(d -> d.entityType().name()));
        return all;
    }

    // ------------------------------------------------------------------------------------------
    // レポート生成
    // ------------------------------------------------------------------------------------------

    private static String renderReport(MobTypesConfig mobTypes, MobLevelTableConfig levelTable,
                                       MobOverridesConfig overrides) {
        List<MobTypeDefinition> definitions = sortedDefinitions(mobTypes);
        StringBuilder md = new StringBuilder(64 * 1024);

        md.append("# 資源サーバ モブシミュレーション（EliteMobs 非搭載時）\n\n");
        md.append("- 生成: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("（`ResourceServerMobSimulationTest` が自動生成。手編集しないこと）\n");
        md.append("- 入力: 出荷 `combat/mob-types.yml` / `combat/mob-level-table.yml` / `combat/mob-overrides.yml`\n");
        md.append("- 目的: **EliteMobs を載せない資源サーバでも、敵のレベル推移と報酬テーブルが機能すること**の確認\n\n");

        md.append("## 0. 結論\n\n");
        md.append("フィールドモブの経路（`mob-types.yml` + `mob-level-table.yml`）は **EliteMobs に一切依存しない**。\n");
        md.append("レベル刻印は TF 自身の `MobTypeSpawnListener` が `CreatureSpawnEvent` で行い、\n");
        md.append("討伐報酬は `MobLevelTableListener` がレベル値だけを見て適用する。\n");
        md.append("一方 `mob-overrides.yml` は `MOB_PROFILE_ID`（EliteMobs フォークだけが書く PDC）で\n");
        md.append("ゲートされているため、資源サーバでは **一切発火しない**。これは仕様どおりで、\n");
        md.append("ダンジョン専用の調整が資源ワールドへ漏れないことを意味する。\n\n");

        appendConfigSummary(md, mobTypes, levelTable, overrides, definitions);
        appendLevelBandTable(md, levelTable);
        appendShowcaseTable(md, mobTypes);
        appendFullMobTable(md, definitions, levelTable);
        appendPassiveMobNote(md, definitions);
        appendWarnings(md, definitions);

        return md.toString();
    }

    private static void appendConfigSummary(StringBuilder md, MobTypesConfig mobTypes,
                                            MobLevelTableConfig levelTable, MobOverridesConfig overrides,
                                            List<MobTypeDefinition> definitions) {
        md.append("## 1. 読み込んだ設定\n\n");
        md.append("| 項目 | 値 |\n|---|---|\n");
        md.append("| `mob-types.yml` の定義数 | ").append(definitions.size()).append(" EntityType |\n");
        md.append("| 距離スケールの上限レベル (`max-level`) | ").append(mobTypes.maxLevel()).append(" |\n");
        md.append("| 未定義モブの既定レベル (`defaults.level`) | ").append(mobTypes.defaultLevel()).append(" |\n");
        md.append("| 未定義モブの座標係数 | ").append(fmt(mobTypes.defaultCoordinateCoefficient())).append(" |\n");
        md.append("| `mob-level-table.yml` の `dungeon-only` | ").append(levelTable.dungeonOnly()).append(" |\n");
        md.append("| `mob-overrides.yml` | EliteMobs 専用。資源サーバでは未使用（`")
                .append(overrides.resourcePath()).append("`） |\n\n");
    }

    private static void appendLevelBandTable(StringBuilder md, MobLevelTableConfig levelTable) {
        md.append("## 2. レベル帯テーブル（討伐時バニラ EXP とドロップ）\n\n");
        md.append("シミュレーション対象レベルごとに、実際に解決される帯を示す。\n\n");
        md.append("| レベル | 討伐バニラEXP | 削除ドロップ | 追加ドロップ | 帯の対象EntityType数 |\n");
        md.append("|---:|---:|---:|---:|---:|\n");
        for (int level : SIMULATED_LEVELS) {
            Optional<LevelTierRule> rule = levelTable.resolve(level);
            if (rule.isEmpty()) {
                md.append("| ").append(level).append(" | (帯なし) | - | - | - |\n");
                continue;
            }
            LevelTierRule r = rule.get();
            md.append("| ").append(level)
                    .append(" | ").append(r.vanillaExp() == null ? "(バニラ既定)" : r.vanillaExp())
                    .append(" | ").append(r.removeDrops().size())
                    .append(" | ").append(r.addDrops().size())
                    .append(" | ").append(r.targets().entityTypes().isEmpty()
                            ? "全モブ" : String.valueOf(r.targets().entityTypes().size()))
                    .append(" |\n");
        }
        md.append('\n');
    }

    private static void appendShowcaseTable(StringBuilder md, MobTypesConfig mobTypes) {
        md.append("## 3. 代表モブのレベル推移\n\n");
        md.append("`最大HP / 攻撃力` の順に併記する。\n\n");
        md.append("| EntityType |");
        for (int level : SIMULATED_LEVELS) {
            md.append(" Lv").append(level).append(" |");
        }
        md.append("\n|---|");
        md.append("---:|".repeat(SIMULATED_LEVELS.length));
        md.append('\n');

        for (EntityType type : SHOWCASE) {
            Optional<MobTypeDefinition> maybe = mobTypes.definition(type);
            if (maybe.isEmpty()) {
                continue;
            }
            MobTypeDefinition def = maybe.get();
            md.append("| `").append(type.name()).append("` |");
            for (int level : SIMULATED_LEVELS) {
                AttackStats attack = MobStatScaling.scaleAttack(
                        def.attack(), def.levelCoefficients().attack(), level);
                md.append(' ').append(fmt(scaledHealth(def, level)))
                        .append(" / ").append(fmt(attack.defaultDamage())).append(" |");
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static void appendFullMobTable(StringBuilder md, List<MobTypeDefinition> definitions,
                                           MobLevelTableConfig levelTable) {
        md.append("## 4. 全モブ一覧（Lv1 と Lv100）\n\n");
        md.append("「敵対」は Paper の `Enemy` 判定。「討伐EXP」は `mob-level-table.yml` が確定させる値で、\n");
        md.append("`(バニラ既定)` はレベル帯が上書きせずバニラの経験値をそのまま出すことを意味する。\n\n");
        md.append("| EntityType | 敵対 | HP Lv1 | HP Lv100 | 攻撃 Lv1 | 攻撃 Lv100 | 物理防御率 Lv100 "
                + "| 防具強度 Lv100 | 討伐EXP Lv1 | 討伐EXP Lv100 | TF追加ドロップ |\n");
        md.append("|---|:-:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (MobTypeDefinition def : definitions) {
            AttackStats a1 = MobStatScaling.scaleAttack(def.attack(), def.levelCoefficients().attack(), 1);
            AttackStats a100 = MobStatScaling.scaleAttack(def.attack(), def.levelCoefficients().attack(), 100);
            DefenseStats p100 = scaledPhysical(def, 100);
            md.append("| `").append(def.entityType().name()).append('`')
                    .append(" | ").append(isHostile(def.entityType()) ? "✓" : "")
                    .append(" | ").append(fmt(scaledHealth(def, 1)))
                    .append(" | ").append(fmt(scaledHealth(def, 100)))
                    .append(" | ").append(fmt(a1.defaultDamage()))
                    .append(" | ").append(fmt(a100.defaultDamage()))
                    .append(" | ").append(fmt(p100.defenseRate()))
                    .append(" | ").append(fmt(p100.armorStrength()))
                    .append(" | ").append(killExp(levelTable, def.entityType(), 1))
                    .append(" | ").append(killExp(levelTable, def.entityType(), 100))
                    .append(" | ").append(describeDrops(def.drops()))
                    .append(" |\n");
        }
        md.append('\n');
    }

    private static String killExp(MobLevelTableConfig levelTable, EntityType type, int level) {
        return levelTable.resolve(level)
                .filter(rule -> rule.appliesTo(type, null))
                .map(LevelTierRule::vanillaExp)
                .map(String::valueOf)
                .orElse("(バニラ既定)");
    }

    private static void appendPassiveMobNote(StringBuilder md, List<MobTypeDefinition> definitions) {
        List<String> passive = new ArrayList<>();
        for (MobTypeDefinition def : definitions) {
            if (!isHostile(def.entityType())) {
                passive.add(def.entityType().name());
            }
        }
        md.append("## 5. 討伐EXPをバニラ既定のままにしているモブ（受動モブ）\n\n");
        if (passive.isEmpty()) {
            md.append("なし。\n\n");
            return;
        }
        md.append("以下は `mob-level-table.yml` のレベル帯 `mobs:` に含まれておらず、討伐経験値はバニラのまま。\n");
        md.append("`mob-types.yml` 側のステータス（HP・攻撃力・防御）は通常どおり適用される。\n");
        md.append("**意図的な設計**（牛や村人に固定の討伐EXPを与えない）であり、資源サーバでも同じ挙動になる。\n\n");
        md.append("計 ").append(passive.size()).append(" 種: ");
        md.append(String.join(", ", new TreeSet<>(passive)));
        md.append("\n\n");
    }

    /**
     * サーバ側の設定に依存していて、新しいサーバを立てたときに黙って壊れる箇所を洗い出す。
     * 個体名を 50 件並べても読めないので、件数と最大値と「何を設定すべきか」を出す。
     */
    private static void appendWarnings(StringBuilder md, List<MobTypeDefinition> definitions) {
        int overCeiling = 0;
        double peakHealth = 0.0;
        String peakHealthMob = "-";
        List<String> defenseRateOverflow = new ArrayList<>();

        for (MobTypeDefinition def : definitions) {
            double health = scaledHealth(def, 100);
            if (health > SPIGOT_DEFAULT_MAX_HEALTH_CEILING) {
                overCeiling++;
            }
            if (health > peakHealth) {
                peakHealth = health;
                peakHealthMob = def.entityType().name();
            }
            if (scaledPhysical(def, 100).defenseRate() > 1.0) {
                defenseRateOverflow.add(def.entityType().name());
            }
        }

        md.append("## 6. 新しいサーバを立てるときの注意\n\n");
        md.append("### 6-1. `spigot.yml` の `attribute.maxHealth.max`（必須）\n\n");
        md.append("Lv100 時点の最大HPが Spigot の既定上限 ").append(fmt(SPIGOT_DEFAULT_MAX_HEALTH_CEILING))
                .append(" を超えるモブが **").append(overCeiling).append(" 種**ある（最大は `")
                .append(peakHealthMob).append("` の ").append(fmt(peakHealth)).append("）。\n");
        md.append("既定のままの `spigot.yml` で新サーバを立てると、これらの HP は**無言で ")
                .append(fmt(SPIGOT_DEFAULT_MAX_HEALTH_CEILING)).append(" にクランプされる**。\n");
        md.append("症状は「なぜか高レベル帯だけ敵が柔らかい」という形でしか出ないため、"
                + "既存サーバと同じ値を必ずコピーすること:\n\n");
        md.append("```yaml\n# spigot.yml\nsettings:\n  attribute:\n    maxHealth:\n"
                + "      max: 1.7976931348623157E308\n```\n\n");

        md.append("### 6-2. 物理防御率が 1.0 を超えるモブ\n\n");
        if (defenseRateOverflow.isEmpty()) {
            md.append("なし。Lv100 でも全モブが 1.0 未満で、`combat/stat-caps.yml` のクランプに頼っていない。\n\n");
        } else {
            md.append("Lv100 で `combat/stat-caps.yml` のクランプ頼みになっているモブ: ")
                    .append(new TreeSet<>(defenseRateOverflow)).append("\n\n")
                    .append("両サーバで `stat-caps.yml` が同一でないと被ダメージが変わる。\n\n");
        }
    }

    private static String describeDrops(List<MobDropEntry> drops) {
        if (drops.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (MobDropEntry drop : drops) {
            if (!sb.isEmpty()) {
                sb.append("<br>");
            }
            sb.append(drop.material().name()).append(" ×").append(drop.min())
                    .append('-').append(drop.max())
                    .append(" (").append(fmt(drop.chance() * 100.0)).append("%)");
        }
        return sb.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
