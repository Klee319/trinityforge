package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/special-rewards.yml}: 称号(titles) / パーティクル(particles) /
 * パーティクルシード(particle-seeds) のIDレジストリ (2026-07-23-stat-gate-overhaul §6.1/§6.7)。
 *
 * <p>参照方法の統一: ここで定義したIDはスキルツリー({@code reward:<id>} 動的ゲート)/ 図鑑報酬ティア
 * ({@code collection.yml reward-tiers.<id>.special})/ アチーブメント報酬
 * ({@code achievements.yml achievements.<id>.rewards.special}) の3箇所から共通参照される。本クラスは
 * 定義の読み込みのみ担い、保有/装備判定は {@code SpecialRewardService} が行う。
 *
 * <p>Malformed entries are skipped with a warning; the rest load. Snapshots swap atomically on reload.
 */
public final class SpecialRewardsConfig implements LoadableConfig {

    public static final String PATH = "progression/special-rewards.yml";

    /** @param id 称号ID @param display MiniMessage文字列(プレイヤー頭上に表示) */
    public record Title(String id, String display) {
    }

    /** shape: 発生パターン。circle=周囲へ円形散布 / aura=自分の周りにまとわりつく。 */
    public enum Shape { CIRCLE, AURA }

    /**
     * @param id            パーティクルID
     * @param particle      Bukkit Particle
     * @param count         1回の発生あたりのパーティクル数
     * @param radius        発生半径(ブロック)
     * @param intervalTicks 発生間隔(tick)
     * @param shape         発生パターン
     */
    public record ParticleEffect(String id, Particle particle, int count, double radius,
                                  int intervalTicks, Shape shape) {
    }

    /**
     * @param id       パーティクルシードID
     * @param seedItem 合成素材(Material名 または {@code custom:<カタログID>})
     * @param particle Bukkit Particle
     * @param count    ブロック破壊/攻撃時に発生させる数
     */
    public record ParticleSeed(String id, String seedItem, Particle particle, int count) {
    }

    /** {@link #titleHeadOffsetY()} の既定値(ブロック単位)。詳細は同メソッドのjavadoc参照。 */
    private static final double DEFAULT_TITLE_HEAD_OFFSET_Y = 0.75;

    private volatile Map<String, Title> titles = Map.of();
    private volatile Map<String, ParticleEffect> particles = Map.of();
    private volatile Map<String, ParticleSeed> particleSeeds = Map.of();
    private volatile double titleHeadOffsetY = DEFAULT_TITLE_HEAD_OFFSET_Y;

    public Map<String, Title> titles() {
        return titles;
    }

    public Map<String, ParticleEffect> particles() {
        return particles;
    }

    public Map<String, ParticleSeed> particleSeeds() {
        return particleSeeds;
    }

    /** {@code id} がいずれかのカテゴリ(titles/particles/particle-seeds)に定義済みか。 */
    public boolean isKnown(String id) {
        return id != null && (titles.containsKey(id) || particles.containsKey(id) || particleSeeds.containsKey(id));
    }

    /**
     * 称号(頭上表示)を {@code TextDisplay} のパッセンジャー既定マウント点から追加で持ち上げる高さ
     * (ブロック単位、{@code TitleDisplayService})。バグ報告B1: 旧ハードコード値 0.35 だとちょうど
     * ネームタグの位置に重なり、プレイヤー名が見えなくなっていた。既定値 0.75 は
     * 実サーバで目視確認できない制約下での暫定値 — ネームタグに重なる/離れすぎる場合は
     * {@code progression/special-rewards.yml} の {@code display.head-offset-y} を調整すること。
     * {@code /trinityforge reload} で次回の表示張り直し(参加/リスポーン/ワールド移動/テレポート)から反映される。
     */
    public double titleHeadOffsetY() {
        return titleHeadOffsetY;
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
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        ParseResult result = parse(yaml, log);
        this.titles = result.titles();
        this.particles = result.particles();
        this.particleSeeds = result.particleSeeds();
        // display.head-offset-y (B1): 非有限値/未設定は既定へフォールバック。負値は「頭上表示を下げる」
        // 正式な運用として許容する(称号を胸元に置く等の演出も構成できる)。
        double headOffsetY = yaml.getDouble("display.head-offset-y", DEFAULT_TITLE_HEAD_OFFSET_Y);
        this.titleHeadOffsetY = Double.isFinite(headOffsetY) ? headOffsetY : DEFAULT_TITLE_HEAD_OFFSET_Y;

        if (result.skipped() > 0) {
            log.warning("[" + PATH + "] loaded " + (titles.size() + particles.size() + particleSeeds.size())
                    + " special reward(s), " + result.skipped() + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + titles.size() + " title(s), " + particles.size()
                + " particle(s), " + particleSeeds.size() + " particle-seed(s) OK");
        return true;
    }

    /** Pure parse of the whole document — unit-testable headlessly. */
    static ParseResult parse(YamlConfiguration yaml, Logger log) {
        int skipped = 0;
        Map<String, Title> titles = new LinkedHashMap<>();
        ConfigurationSection titlesSection = yaml.getConfigurationSection("titles");
        if (titlesSection != null) {
            for (String id : titlesSection.getKeys(false)) {
                ConfigurationSection entry = titlesSection.getConfigurationSection(id);
                String display = entry != null ? entry.getString("display") : null;
                if (entry == null || display == null || display.isBlank()) {
                    log.warning("[" + PATH + "] title '" + id + "' missing display; skipped");
                    skipped++;
                    continue;
                }
                titles.put(id, new Title(id, display));
            }
        }

        Map<String, ParticleEffect> particles = new LinkedHashMap<>();
        ConfigurationSection particlesSection = yaml.getConfigurationSection("particles");
        if (particlesSection != null) {
            for (String id : particlesSection.getKeys(false)) {
                ConfigurationSection entry = particlesSection.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("[" + PATH + "] particle '" + id + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                Particle particle = parseParticle(entry.getString("particle"));
                if (particle == null) {
                    log.warning("[" + PATH + "] particle '" + id + "' has invalid/missing particle name; skipped");
                    skipped++;
                    continue;
                }
                Shape shape = parseShape(entry.getString("shape", "circle"));
                if (shape == null) {
                    log.warning("[" + PATH + "] particle '" + id + "' has invalid shape (circle|aura); skipped");
                    skipped++;
                    continue;
                }
                int count = Math.max(1, entry.getInt("count", 1));
                double radius = Math.max(0.0, entry.getDouble("radius", 0.5));
                int interval = Math.max(1, entry.getInt("interval-ticks", 10));
                particles.put(id, new ParticleEffect(id, particle, count, radius, interval, shape));
            }
        }

        Map<String, ParticleSeed> seeds = new LinkedHashMap<>();
        ConfigurationSection seedsSection = yaml.getConfigurationSection("particle-seeds");
        if (seedsSection != null) {
            for (String id : seedsSection.getKeys(false)) {
                ConfigurationSection entry = seedsSection.getConfigurationSection(id);
                String seedItem = entry != null ? entry.getString("seed-item") : null;
                if (entry == null || seedItem == null || seedItem.isBlank()) {
                    log.warning("[" + PATH + "] particle-seed '" + id + "' missing seed-item; skipped");
                    skipped++;
                    continue;
                }
                Particle particle = parseParticle(entry.getString("particle"));
                if (particle == null) {
                    log.warning("[" + PATH + "] particle-seed '" + id
                            + "' has invalid/missing particle name; skipped");
                    skipped++;
                    continue;
                }
                int count = Math.max(1, entry.getInt("count", 1));
                seeds.put(id, new ParticleSeed(id, seedItem.trim(), particle, count));
            }
        }

        return new ParseResult(Map.copyOf(titles), Map.copyOf(particles), Map.copyOf(seeds), skipped);
    }

    private static Particle parseParticle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Shape parseShape(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Shape.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    record ParseResult(Map<String, Title> titles, Map<String, ParticleEffect> particles,
                        Map<String, ParticleSeed> particleSeeds, int skipped) {
    }
}
