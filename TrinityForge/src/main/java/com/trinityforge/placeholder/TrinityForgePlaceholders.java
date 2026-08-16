package com.trinityforge.placeholder;

import com.trinityforge.TrinityForge;
import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.ranking.RankingStats;
import com.trinityforge.ranking.RankingStatsService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * ランキングプラグイン向けの PlaceholderAPI 拡張。識別子は {@code trinityforge}。
 *
 * <h2>提供するプレースホルダ</h2>
 * <table>
 *   <caption>一覧</caption>
 *   <tr><th>書式</th><th>意味</th></tr>
 *   <tr><td>{@code %trinityforge_skill_<id>_level%}</td><td>スキル別レベル（id は mining / heavy_armor など 16 種）</td></tr>
 *   <tr><td>{@code %trinityforge_skill_<id>_prestige%}</td><td>スキル別プレステージ回数</td></tr>
 *   <tr><td>{@code %trinityforge_skill_<id>_totalexp%}</td><td>スキル別の累計 EXP（整数へ丸め）</td></tr>
 *   <tr><td>{@code %trinityforge_skill_total_level%}</td><td>16 スキルのレベル合計（総合ランキング用）</td></tr>
 *   <tr><td>{@code %trinityforge_glyphs_unlocked%}</td><td>グリフ解放数</td></tr>
 *   <tr><td>{@code %trinityforge_collection_entries%}</td><td>図鑑登録数（アイテム＋モブ）</td></tr>
 *   <tr><td>{@code %trinityforge_collection_items%}</td><td>図鑑登録数のうちアイテム</td></tr>
 *   <tr><td>{@code %trinityforge_collection_mobs%}</td><td>図鑑登録数のうちモブ<b>種類</b></td></tr>
 *   <tr><td>{@code %trinityforge_kills_total%}</td><td>討伐数（バニラ統計 MOB_KILLS の累計）</td></tr>
 * </table>
 *
 * <p><b>討伐「数」と図鑑の「モブ」は別物。</b> 図鑑はモブ<b>種類</b>を数えるので、
 * 同じゾンビを 1000 体倒しても 1 のまま増えない。累計討伐数が欲しいときは
 * {@code kills_total} を使うこと。
 *
 * <h2>サーバ間で同じ値になる仕組み</h2>
 * <ul>
 *   <li><b>スキル系</b>: {@code player_progression.db} から読む。この DB は
 *       {@code plugins/TrinityForge/} ごとディレクトリジャンクションで全バックエンドに
 *       共有されているため、メインでも資源でも同じ行を読む。オフラインでも UUID で引ける。</li>
 *   <li><b>図鑑／グリフ／討伐数</b>: 実体はプレイヤー PDC とバニラ統計にあり、
 *       本人がログインしているサーバからしか読めない。そこで {@link RankingStatsService} が
 *       共有 DB へ写しており、他サーバ／オフラインではそのミラーを読む。</li>
 * </ul>
 *
 * <p>書式の解釈そのものは {@link PlaceholderResolver} にある（PlaceholderAPI が
 * {@code compileOnly} でテストクラスパスに無いため、テスト可能な側へ出してある）。
 */
public final class TrinityForgePlaceholders extends PlaceholderExpansion {

    private final TrinityForge plugin;

    public TrinityForgePlaceholders(TrinityForge plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "trinityforge";
    }

    @Override
    public String getAuthor() {
        return "Klee319";
    }

    @Override
    public String getVersion() {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (RuntimeException | LinkageError e) {
            // バージョン取得に失敗しても拡張自体は使える。登録を落とす理由にはしない。
            return "unknown";
        }
    }

    /**
     * PlaceholderAPI 側のリロードで拡張を作り直させない。TF 本体のサービス参照を握っているため。
     */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return null;
        }
        return PlaceholderResolver.resolve(params,
                () -> {
                    NativeProgressionService service = plugin.progressionService();
                    return service == null ? null : service.snapshot(player.getUniqueId());
                },
                () -> {
                    RankingStatsService service = plugin.rankingStatsService();
                    return service == null ? RankingStats.EMPTY : service.stats(player);
                });
    }
}
