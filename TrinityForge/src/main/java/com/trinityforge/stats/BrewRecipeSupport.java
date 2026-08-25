package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.List;
import java.util.Locale;

/**
 * {@code progression/crafting-features.yml} の {@code brew-unlocks} を解釈する共有ヘルパー。
 *
 * <p><b>なぜ共有するのか</b>: 同じ spec を 2 箇所が読む。
 * {@link BrewPotionMixRegistrar} は Paper の {@code customMixes} へ登録する述語を作り、
 * {@code BrewUnlockListener} は {@code BrewEvent} で結果を差し替える。ここが食い違うと
 * 「素材は上段に置けるのに結果が差し替わらない(またはその逆)」という、
 * <b>片方だけ直したときに静かに壊れる</b>種類のバグになるため、判定は必ずこの1本を通す。
 */
public final class BrewRecipeSupport {

    private BrewRecipeSupport() {
    }

    /** 醸造ビンとして扱う材質(バニラの醸造台が下段に受け付ける3種)。 */
    public static boolean isPotionContainer(Material type) {
        return type == Material.POTION || type == Material.SPLASH_POTION
                || type == Material.LINGERING_POTION;
    }

    /** {@code custom:<id>} 形式か。 */
    public static boolean isCustomKey(String raw) {
        return raw != null && raw.trim().toLowerCase(Locale.ROOT).startsWith("custom:");
    }

    /** {@code custom:<id>} の {@code <id>} 部分。{@code custom:} でない/空なら {@code null}。 */
    public static String customId(String raw) {
        if (!isCustomKey(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        String id = trimmed.substring(trimmed.indexOf(':') + 1).trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * 醸造素材の照合。{@code custom:<id>} は TF カタログID と ArsPaper の
     * {@code arspaper:custom_item_id} の<b>両方</b>を見る。
     *
     * <p>2026-07-31: 以前は {@code CatalogIdentity.catalogIdOf}(TF の PDC だけ)を見ていたため、
     * ArsPaper の materials.yml 側で定義した素材(モブドロップ素材・圧縮素材・ソース階梯など)を
     * 醸造素材に書いても<b>永久に一致せず、レシピが無言で成立しない</b>状態だった。
     * 共有シームである {@link CrossPluginItemResolver#idOf} へ寄せて、
     * config 側は「どちらのプラグインで定義したか」を意識しなくてよいようにする。
     */
    public static boolean matchesIngredient(ItemStack ingredient, String expected) {
        if (ingredient == null || expected == null || expected.isBlank()) {
            return false;
        }
        String raw = expected.trim();
        if (isCustomKey(raw)) {
            String customId = customId(raw);
            if (customId == null) {
                return false;
            }
            return CrossPluginItemResolver.idOf(ingredient).filter(customId::equals).isPresent();
        }
        Material mat = Material.matchMaterial(raw);
        return mat != null && ingredient.getType() == mat;
    }

    /**
     * {@code (base, ingredient)} の正規化キー。<b>重複検出と dedup の唯一の基準</b>
     * (2026-07-31 D10 レビュー指摘#2: 同じ組を2グループが宣言すると先勝ちで上位版が到達不能になる)。
     *
     * <p><b>正規化は {@link #matchesIngredient} / {@link #matchesBase} の照合規則と一致させる</b>
     * (2026-07-31 レビュー指摘#8)。ここだけ緩いと「重複として片方を黙って落としたのに、
     * 実行時には別アイテムとして扱う」という取り違えが起きる:
     * <ul>
     *   <li>base: {@link #matchesBase} が {@code PotionType.valueOf(大文字化)} で解決するので大文字化。
     *       空欄は「任意のビン」なので {@code *} へ倒す。</li>
     *   <li>バニラ材質: {@link #matchesIngredient} が {@link Material#matchMaterial} で解決するので
     *       {@link Material#name()} へ正規化する({@code sugar} と {@code SUGAR} を別物にしない)。</li>
     *   <li>{@code custom:<id>}: {@link #matchesIngredient} が {@code customId::equals} で
     *       <b>大小を区別して</b>比較するので、ここでも小文字化しない
     *       ({@code custom:Hoglin_Fang} と {@code custom:hoglin_fang} は実行時に別物なので
     *       重複にしてはいけない)。</li>
     * </ul>
     */
    public static String pairKey(String base, String ingredient) {
        String normalizedBase = base == null || base.isBlank()
                ? "*" : base.trim().toUpperCase(Locale.ROOT);
        String normalizedIngredient;
        if (isCustomKey(ingredient)) {
            String id = customId(ingredient);
            normalizedIngredient = "custom:" + (id == null ? "" : id);
        } else {
            Material mat = ingredient == null ? null : Material.matchMaterial(ingredient.trim());
            normalizedIngredient = mat != null ? mat.name()
                    : String.valueOf(ingredient).trim().toUpperCase(Locale.ROOT);
        }
        return normalizedBase + " + " + normalizedIngredient;
    }

    /**
     * ビンのベース照合。{@code baseName} が空欄なら「任意のベース」を意味する(true)。
     * 未知の {@link PotionType} 名は false(綴り間違いで全ビンに一致させない)。
     */
    public static boolean matchesBase(PotionMeta meta, String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return true;
        }
        if (meta == null) {
            return false;
        }
        try {
            PotionType expected = PotionType.valueOf(baseName.trim().toUpperCase(Locale.ROOT));
            return meta.getBasePotionType() == expected;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * base を {@code WATER} へ倒したポーションに焼き付ける表示名。効果が無ければ {@code null}
     * (＝バニラの名前をそのまま使う)。
     *
     * <h2>なぜ必要か(2026-08-18 実サーバ報告の真因)</h2>
     * 「醸造の進捗バーも動くし完了音も鳴るのに、出てくるのが<b>水入り瓶</b>」という報告の原因は
     * ここにある。TF は段階の異なる効果を一意に確定させるためにベースを {@code WATER} へ倒すが、
     * <b>Minecraft のポーション名はベースの種類からしか引かれない</b>
     * ({@code PotionContents#getName})。カスタム効果を何個足しても名前は「水入り瓶」のままで、
     * 効果自体は正しく付いているので<b>ログにも例外にも一切現れない</b>。
     * ベースを倒すのは外せない(倒さないとベース効果と強化版が二重に掛かる)ので、
     * 名前を明示的に付け直すのが唯一の手当てになる。
     *
     * <p><b>翻訳キーは効果名({@code effect.minecraft.<効果>})を使い、ポーション名
     * ({@code item.minecraft.potion.effect.<名前>})は使わない。</b>後者は<b>バニラに実在する
     * ポーションにしか訳語が無い</b>ため、{@code HASTE}(採掘速度上昇) / {@code HEALTH_BOOST}(体力増強)
     * のように「効果はあるがバニラにポーションが無い」出荷レシピで生の翻訳キーが画面に出る。
     *
     * <p>効果が複数ある場合は先頭(＝レシピが主役として宣言した効果)で命名する。
     */
    public static Component potionDisplayName(Material bottleType, List<PotionEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return null;
        }
        PotionEffect primary = effects.get(0);
        if (primary == null || primary.getType() == null) {
            return null;
        }
        Component name = Component.translatable(
                "effect.minecraft." + primary.getType().getKey().getKey());
        if (bottleType == Material.SPLASH_POTION) {
            name = Component.text("スプラッシュ").append(name);
        } else if (bottleType == Material.LINGERING_POTION) {
            name = Component.text("残留").append(name);
        }
        // 付け直した名前は斜体にしない(装備・カタログ品と同じ規約。バニラ品と字面を揃える)。
        return name.append(Component.text("のポーション")).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * spec の効果を持つポーションを組み立てる。段階の異なる効果を一意に確定させるため base は
     * {@code WATER} へ倒して全て custom effects で表現する({@code PotionQualityListener} と同じ既存パターン)。
     * ベースを倒すと名前が「水入り瓶」に化けるので、{@link #potionDisplayName} で名前を焼き付ける。
     *
     * <p><b>Bukkit サーバが必要</b>({@code Bukkit.getItemFactory()} 経由で {@code ItemMeta} を作る)。
     * サーバ無しで検証したい呼び出し側は組み立て自体を注入できるようにしてある
     * ({@link BrewPotionMixRegistrar} の result factory シーム)。
     */
    public static ItemStack customPotion(Material bottleType, BrewPotionSpec spec) {
        Material type = bottleType == Material.SPLASH_POTION || bottleType == Material.LINGERING_POTION
                ? bottleType : Material.POTION;
        ItemStack out = new ItemStack(type);
        if (!(out.getItemMeta() instanceof PotionMeta meta)) {
            return out;
        }
        PotionEffect effect = new PotionEffect(spec.type(), spec.durationTicks(), spec.amplifier());
        meta.clearCustomEffects();
        meta.setBasePotionType(PotionType.WATER);
        meta.addCustomEffect(effect, true);
        meta.displayName(potionDisplayName(type, List.of(effect)));
        applyMixedColor(meta, List.of(effect));
        out.setItemMeta(meta);
        return out;
    }

    /**
     * base を {@code WATER} へ倒したポーションに、効果から導出した色を焼き付ける
     * (2026-08-25 / 統合版で「水入り瓶」に見える件の修正)。
     *
     * <h2>なぜ必要か</h2>
     * Geyser はポーションの見た目(タイル色)を {@code PotionContents} の base(醸造の素の種類)から
     * 引く。TF は段階の異なる効果を一意に確定させるため base を常に {@code WATER} へ倒すが、
     * {@link PotionMeta#setColor} を一度も焼いていなかったため、Geyser 側は「色の無い WATER」
     * ＝水入り瓶として描画していた。Java 版のクライアントは custom effects から色を都度計算して
     * 表示するため気づかれなかった(Java 版では症状が出ない)。
     *
     * <p>色の合成規則はバニラの複数効果ポーション色計算({@code PotionContents#getColor} /
     * 旧 {@code PotionUtils#mixColor})に倣い、<b>持続時間で重み付けした effect 色の平均</b>を使う。
     * パーティクル非表示({@link PotionEffect#hasParticles()} が false)の効果はバニラ同様に
     * 色計算から除外するが、全効果が非表示だった場合は色が無くなって再び水入り瓶化してしまうため、
     * その場合だけ全効果を対象にフォールバックする。
     *
     * @return 焼き付けた色。焼く効果が1つも無ければ {@code null}(呼び出し側は何もしない)
     */
    public static Color applyMixedColor(PotionMeta meta, List<PotionEffect> effects) {
        Color color = mixColor(effects);
        if (color != null) {
            meta.setColor(color);
        }
        return color;
    }

    /**
     * バニラの複数効果ポーション色計算(持続時間で重み付けした平均)を再現する。
     * {@link PotionEffect#hasParticles()} が true の効果だけを対象にし、1件も無ければ
     * 全効果へフォールバックする。色を持つ効果が1つも無ければ {@code null}。
     */
    public static Color mixColor(List<PotionEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return null;
        }
        Color visibleOnly = mixColor(effects, true);
        return visibleOnly != null ? visibleOnly : mixColor(effects, false);
    }

    private static Color mixColor(List<PotionEffect> effects, boolean particlesOnly) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long totalWeight = 0;
        for (PotionEffect effect : effects) {
            if (effect == null || (particlesOnly && !effect.hasParticles())) {
                continue;
            }
            Color effectColor = effect.getType().getColor();
            if (effectColor == null) {
                continue;
            }
            long weight = Math.max(1, effect.getDuration());
            totalWeight += weight;
            red += (long) effectColor.getRed() * weight;
            green += (long) effectColor.getGreen() * weight;
            blue += (long) effectColor.getBlue() * weight;
        }
        if (totalWeight == 0) {
            return null;
        }
        return Color.fromRGB((int) (red / totalWeight), (int) (green / totalWeight), (int) (blue / totalWeight));
    }
}
