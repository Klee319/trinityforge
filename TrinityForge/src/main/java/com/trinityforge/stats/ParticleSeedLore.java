package com.trinityforge.stats;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.ItemData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 道具に焼き付いているパーティクルシードを lore の1行として見せる
 * (2026-08-25 / W-221、ユーザー要望「埋め込んでいるシードを lore に注入（日本語で表記）」)。
 *
 * <p>それまでは<b>刻印されているかどうかを見る手段が実物の挙動しか無かった</b>
 * ── 付けた本人ですら「この斧はどれを付けたか」を確かめられず、消したいときに
 * 何が付いているのか分からないまま金床へ持っていくことになる。
 *
 * <p>表示名は {@code special-rewards.yml} の {@code particle-seeds.<id>.display}。未設定なら ID を出す
 * (行が消えるより ID が出るほうがまだ辿れる)。<b>体裁だけは config に出さない</b> ──
 * 出す情報が「名前1つ」しかないので、テンプレートを1つ増やすと editor と Java の既定値が
 * ずれる面(2026-08 の {@code normalize*} ドリフト)だけが増えて得るものが無い。
 *
 * <p><b>行を2箇所から書く理由</b>: TF のカタログ品は {@link ItemAssembler#assemble} が lore を
 * <b>毎回ゼロから組み直す</b>(持ち替え・装備変更・参加時)ので、付与時に足しただけの行は次の
 * 組み直しで消える。逆にバニラの道具は組み直しの対象外なので、組み直し側だけでは一度も出ない。
 * 両方から同じ関数を呼ぶ。
 */
public final class ParticleSeedLore {

    /**
     * 行の頭。<b>消すときはこの前置きで探す</b>(構造の一致ではなく) ──
     * 表示名を config で変えた後でも古い行を回収できるようにするため。
     */
    public static final String PREFIX = "粒子: ";

    private ParticleSeedLore() {
    }

    /** {@code seed} の lore 1行。 */
    public static Component line(SpecialRewardsConfig.ParticleSeed seed) {
        String name = seed == null ? "" : seed.displayName();
        return Component.text(PREFIX, NamedTextColor.GRAY)
                .append(com.trinityforge.text.MiniText.render(name, NamedTextColor.LIGHT_PURPLE))
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 組み立て直した lore へ、いま刻印されているシードの行を足す({@link ItemAssembler} 用)。
     * 刻印が無い / 定義が消えたIDなら何もしない。
     */
    public static void appendTo(ItemMeta meta, List<Component> destination, SpecialRewardsConfig config) {
        SpecialRewardsConfig.ParticleSeed seed = seedOf(meta, config);
        if (seed != null) {
            destination.add(line(seed));
        }
    }

    /**
     * 既存の lore から古いシード行を取り除き、いまの刻印の行を入れ直す(付与・除去の直後用)。
     *
     * <p>{@code meta} の PDC は<b>呼ぶ前に</b>更新しておくこと(この関数は PDC を真として読む)。
     */
    public static void reapply(ItemMeta meta, SpecialRewardsConfig config) {
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.lore();
        List<Component> rebuilt = new ArrayList<>();
        if (lore != null) {
            for (Component line : lore) {
                if (line != null && !isSeedLine(line)) {
                    rebuilt.add(line);
                }
            }
        }
        SpecialRewardsConfig.ParticleSeed seed = seedOf(meta, config);
        if (seed != null) {
            rebuilt.add(line(seed));
        }
        // 元から lore が無く、刻印も無いなら触らない(空リストを書くと「空の lore を持つ」状態になる)。
        if (lore == null && rebuilt.isEmpty()) {
            return;
        }
        meta.lore(rebuilt);
    }

    private static boolean isSeedLine(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line).startsWith(PREFIX);
    }

    private static SpecialRewardsConfig.ParticleSeed seedOf(ItemMeta meta, SpecialRewardsConfig config) {
        if (meta == null || config == null) {
            return null;
        }
        return ItemData.of(meta).particleSeed().map(id -> config.particleSeeds().get(id)).orElse(null);
    }
}
