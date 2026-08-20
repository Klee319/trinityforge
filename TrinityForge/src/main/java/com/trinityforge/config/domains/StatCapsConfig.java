package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code combat/stat-caps.yml}: optional upper-bound ceilings applied to a player's fully
 * aggregated total for a {@code StatVocabulary}-registered stat (2026-07-26 stat-cap 導入).
 *
 * <p><b>クランプの適用点(2種類)</b>:
 * <ul>
 *   <li><b>(A)</b> {@code com.trinityforge.combat.PlayerCombatAggregate#totalOf} が
 *       「item(装備+base-stats+永続バフ+役職) + perkAttack + perkDefense + addon を加算 → 乗算レイヤ適用」
 *       まで終えた直後の値。GENERAL チャネルはすべてここを通る。</li>
 *   <li><b>(B)</b> 2026-07-26 カバレッジ拡大で追加。{@code CombatListener}(attackerStats 集計点 /
 *       baseDamage 算出点)と {@code PlayerDefenseResolver}({@code DefenseStats#combine} 直後、
 *       {@code DefenderProfile} を返す直前)が、{@code totalOf} を経由せず独自に item/perk/addon を
 *       合成している箇所。ATTACK/DEFENSE チャネルの本命ステはこちらを通るため、
 *       {@code PlayerCombatAggregate#clamp} を直接呼んでクランプする(意味論・CT短縮系除外・
 *       非有限値スルーは {@code totalOf} と共有)。</li>
 * </ul>
 * <b>2026-07-26 訂正</b>: このjavadocは当初「適用点は {@code totalOf} ただ1つで、リスナー側には
 * 一切クランプを持たせない」と書いていたが、(B) の追加でその前提は失効した。新しい合成経路を足すときは
 * 「{@code totalOf} を通るか」を確認し、通らないなら明示的に {@link #clamp} を呼ぶこと。
 * このクラス自体は上限値を保持するだけの純粋なデータローダーで、値へ適用するのは呼び出し側の責務。
 *
 * <p>設定できるキーの全一覧・「まだ効かないキー」「そもそも効かないキー」の理由は
 * {@code docs/config-reference/combat/stat-caps.md}(2026-07-26 に yml 本文コメントから移設 —
 * config-editor で保存すると本文コメントが復元されないため)。
 *
 * <p><b>対象外(意図的、詳細は各項目参照)</b>:
 * <ul>
 *   <li>アイテム個別ステ({@code durability}/{@code item-cooldown}/{@code tool-enchant-*}/
 *       {@code thread-slots}/{@code coating-charges}) — そもそも {@link StatVocabulary} に登録されて
 *       おらず、{@code totalOf} が読む item/perk/addon マップの「総合ステ」としては現れない
 *       (アイテム単位で別の経路により解決される)ため、このファイルに書いても効果は無い。</li>
 *   <li>{@code *-cooldown-reduction} 系(汎用の {@code cooldown-reduction} を含む) — 既に
 *       {@code com.trinityforge.active.CooldownManager#applyReduction} でクランプ済みの専用系統。
 *       ここへ書いても {@link #load} が警告した上で読み捨てる(二重管理防止)。</li>
 * </ul>
 *
 * <p><b>「上限なし」と「上限0」の区別</b>: キーが {@code stat-caps:} セクションに一切現れない場合
 * (コメントアウト/未記載を含む)は「上限なし」。数値として明示的に {@code 0} と書いた場合だけ
 * 「上限0」として扱う({@link BaseStatsConfig} の「0=未設定」規約とは意図的に逆 —
 * 上限系のファイルで0を無視すると「上限0を設定したつもりが無効化された」事故になるため)。
 * 非数値/空欄の値は「読めない」として無視され、結果的に「上限なし」と同じ扱いになる。
 *
 * <p><b>負値の扱い</b>: 上限は「上側だけ」。{@link #clamp} は {@code raw <= cap} ならそのまま、
 * {@code raw > cap} のときだけ {@code cap} へ切り詰める({@code Math.min})。生の値が負であっても、
 * それは「上限を下回っている」ので変更しない(下限は今回未実装)。キャップ自体に負値を書くことも
 * 許容する(「常にこの値以下にしたい」という意図をそのまま表現できる)。
 */
public final class StatCapsConfig implements LoadableConfig {

    public static final String PATH = "combat/stat-caps.yml";
    private static final String SECTION = "stat-caps";

    private volatile Map<String, Double> caps = Map.of();

    /** Canonical-keyed upper-bound cap values. Never null. Absent key = no cap for that stat. */
    public Map<String, Double> caps() {
        return caps;
    }

    /**
     * {@code canonicalKey} の最終合算値 {@code raw} に上限を適用した値を返す。上限未設定、CT短縮系
     * キー、非有限値({@code NaN}/{@code Infinity})のいずれかなら {@code raw} をそのまま返す。
     * 上側だけをクランプする(下限なし、負値の {@code raw} はそのまま)。
     */
    public double clamp(String canonicalKey, double raw) {
        if (!Double.isFinite(raw)) {
            return raw;
        }
        if (isCooldownReductionFamily(canonicalKey)) {
            return raw;
        }
        Double cap = caps.get(canonicalKey);
        if (cap == null) {
            return raw;
        }
        return Math.min(raw, cap);
    }

    private static boolean isCooldownReductionFamily(String canonicalKey) {
        return canonicalKey.equals("cooldown_reduction") || canonicalKey.endsWith("_cooldown_reduction");
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

        Map<String, Double> next = new LinkedHashMap<>();
        ConfigurationSection sec = yaml.getConfigurationSection(SECTION);
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                if (!sec.isDouble(key) && !sec.isInt(key) && !sec.isLong(key)) {
                    // 空欄/非数値 = 「上限なし」として扱う(BaseStatsConfig の0スキップとは違い、
                    // ここでは数値であれば0でも保持する — 判別ロジックは下のブロック参照)。
                    continue;
                }
                String canonicalKey = StatKeys.canonical(key);
                double cap = sec.getDouble(key);
                if (isCooldownReductionFamily(canonicalKey)) {
                    log.warning("[" + PATH + "] '" + key + "' はCT短縮系のキーです。"
                            + "com.trinityforge.active.CooldownManager#applyReduction が既にクランプ済みのため、"
                            + "ここに書いても二重管理を避けるため無視されます。");
                    continue;
                }
                if (!StatVocabulary.isKnown(canonicalKey)) {
                    log.warning("[" + PATH + "] '" + key + "' はプレイヤーへ合算される既知の総合ステータスでは"
                            + "ありません(綴り間違い、またはアイテム固有ステの可能性)。効果はありません。");
                }
                next.put(canonicalKey, cap);
            }
        }
        this.caps = Map.copyOf(next);

        // 2026-08-05 ユーザー決定で gathering-efficiency-max-enchant-level(ルート直下の後方互換
        // ブリッジ)を削除した。効率強化エンチャントの上限は stats/gathering-efficiency.yml の
        // max-enchant-level が唯一の設定箇所(設定が2箇所あって優先順位が要る状態そのものが不要だった)。
        // このファイルに残っている同名キーは無視される(セクション外なので警告も出ない)。

        log.info("[" + PATH + "] loaded " + this.caps.size() + " stat cap(s) OK");
        return true;
    }

    /**
     * テスト/プログラム的な組み立て用ファクトリ(ファイルI/Oなし)。本番の {@link #load} と
     * 同じ意味論(CT短縮系は登録時点で除外)を再現する。
     */
    public static StatCapsConfig withCaps(Map<String, Double> canonicalCaps) {
        StatCapsConfig config = new StatCapsConfig();
        Map<String, Double> filtered = new LinkedHashMap<>();
        canonicalCaps.forEach((key, value) -> {
            String canonical = StatKeys.canonical(key);
            if (!isCooldownReductionFamily(canonical)) {
                filtered.put(canonical, value);
            }
        });
        config.caps = Map.copyOf(filtered);
        return config;
    }
}
