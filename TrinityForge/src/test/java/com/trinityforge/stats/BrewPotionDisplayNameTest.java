package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BrewRecipeSupport#potionDisplayName} と、それを使う {@link BrewRecipeSupport#customPotion}
 * の表示名を固定する。
 *
 * <h2>何を守っているか(2026-08-18 実サーバ報告の再発防止)</h2>
 * 「醸造の進捗バーも動くし完了音も鳴るのに、出てくるのが<b>水入り瓶</b>」という報告の真因は、
 * TF が効果を確定させるために {@code setBasePotionType(WATER)} でベースを倒していることだった。
 * <b>Minecraft のポーション名はベースの種類からしか引かれない</b>({@code PotionContents#getName} が
 * {@code potion.effect.water} を返す)ので、カスタム効果を何個足しても名前は「水入り瓶」のまま
 * ── 表示だけが壊れ、効果は正しく付いているので<b>ログにも例外にも一切現れない</b>。
 *
 * <p>ベースを倒すこと自体は外せない(ベース効果とカスタム効果は両方適用されるので、
 * 倒さずに強化版を足すと二重に掛かる)。したがって<b>名前を明示的に焼き付ける</b>のが唯一の手で、
 * このテストはその焼き付けが消えたら落ちる。
 *
 * <h2>翻訳キーの選び方</h2>
 * {@code item.minecraft.potion.effect.<名前>}(ポーション名)ではなく
 * {@code effect.minecraft.<効果>}(効果名)を使う。前者は<b>バニラに存在するポーションにしか
 * 訳語が無い</b>ので、{@code HASTE}(採掘速度上昇) / {@code HEALTH_BOOST}(体力増強) /
 * {@code NAUSEA}(吐き気) / {@code BLINDNESS}(盲目) のように「効果はあるがバニラにポーションが無い」
 * 出荷レシピで<b>生の翻訳キーがそのまま画面に出る</b>。後者は効果一覧のツールチップが使っているので
 * 全 {@link PotionEffectType} に必ず存在する。
 */
class BrewPotionDisplayNameTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void 効果名の翻訳キーとポーションの接尾辞から名前を組み立てる() {
        Component name = BrewRecipeSupport.potionDisplayName(
                Material.POTION, List.of(new PotionEffect(PotionEffectType.SPEED, 3600, 2)));

        assertNotNull(name, "効果があるのに名前が組み立てられていない");
        // 先頭は必ず翻訳可能な効果名 — ここをプレーン文字列でハードコードすると英語クライアントで壊れる。
        Component first = name.children().isEmpty() ? name : name.children().get(0);
        TranslatableComponent translatable = assertInstanceOf(TranslatableComponent.class,
                name instanceof TranslatableComponent ? name : first,
                "効果名が translatable でない(クライアント言語に追従しなくなる)");
        assertEquals("effect.minecraft.speed", translatable.key());
        assertTrue(plain(name).endsWith("のポーション"), "実際の名前: " + plain(name));
    }

    @Test
    void スプラッシュと残留は接頭辞が付く() {
        List<PotionEffect> effects = List.of(new PotionEffect(PotionEffectType.SPEED, 3600, 2));

        assertTrue(plain(BrewRecipeSupport.potionDisplayName(Material.SPLASH_POTION, effects))
                .startsWith("スプラッシュ"));
        assertTrue(plain(BrewRecipeSupport.potionDisplayName(Material.LINGERING_POTION, effects))
                .startsWith("残留"));
        assertFalse(plain(BrewRecipeSupport.potionDisplayName(Material.POTION, effects))
                .startsWith("スプラッシュ"));
    }

    @Test
    void 効果が無ければ名前を作らない() {
        // 素の水入り瓶・奇妙なポーションはバニラの名前が正しいので、こちらから触ってはいけない。
        assertNull(BrewRecipeSupport.potionDisplayName(Material.POTION, List.of()));
        assertNull(BrewRecipeSupport.potionDisplayName(Material.POTION, null));
    }

    @Test
    void customPotionは水入り瓶のままにせず効果名を焼き付ける() {
        ItemStack potion = BrewRecipeSupport.customPotion(
                Material.POTION, new BrewPotionSpec("THICK", "SUGAR", PotionEffectType.SPEED, 3600, 2));

        assertInstanceOf(PotionMeta.class, potion.getItemMeta());
        Component name = potion.getItemMeta().displayName();
        assertNotNull(name, "ゲート付き醸造の完成品に表示名が付いていない(= 画面上は『水入り瓶』になる)");
        assertTrue(plain(name).endsWith("のポーション"), "実際の名前: " + plain(name));
        // 装備・カタログ品と同じ規約: 付け直した名前は斜体にしない。
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC),
                "斜体を切っていないとバニラ品と字面が揃わない");
    }
}
