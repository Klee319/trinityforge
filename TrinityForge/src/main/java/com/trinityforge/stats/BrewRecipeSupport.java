package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

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
     * spec の効果を持つポーションを組み立てる。段階の異なる効果を一意に確定させるため base は
     * {@code WATER} へ倒して全て custom effects で表現する({@code PotionQualityListener} と同じ既存パターン)。
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
        meta.clearCustomEffects();
        meta.setBasePotionType(PotionType.WATER);
        meta.addCustomEffect(new PotionEffect(spec.type(), spec.durationTicks(), spec.amplifier()), true);
        out.setItemMeta(meta);
        return out;
    }
}
