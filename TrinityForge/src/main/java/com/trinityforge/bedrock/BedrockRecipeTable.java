package com.trinityforge.bedrock;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 統合版(Bedrock)クライアントへ渡す「素材の identity を保ったレシピ表」の中身。
 *
 * <h2>なぜこれが要るのか</h2>
 * Geyser の {@code JavaUpdateRecipesTranslator} は、Java のレシピを Bedrock へ変換するとき
 * <b>素材を「Java のアイテム型 → バニラの Bedrock 定義」に落とす</b>
 * ({@code mapping.getBedrockDefinition()})。CustomModelData は Java の
 * {@code Ingredient} に載らないので、そもそも変換元に情報が無い。
 * 一方で<b>結果アイテムだけは {@code ItemTranslator.translateToBedrock} を通る</b>ので
 * カスタム Bedrock アイテムになる。
 *
 * <p>その結果、統合版クライアントが持つレシピ表は
 * 「<b>バニラのプリズマリンの欠片</b> ×9 → <b>カスタムのソースジェムブロック</b>」という形になる。
 * ところが盤面に乗るのは<b>カスタムのソースジェム</b>なので、
 * <b>クライアント側の照合が永久に成立しない</b>（統合版はクラフト結果をクライアントが計算する）。
 * さらに Geyser は結果枠からカーソルへの取り出しを
 * 「クライアントが宣言した個数 &lt; サーバの結果の個数」で {@code rejectRequest} するため、
 * 表示が出た場合でも取り出せない。
 *
 * <p>この表は、その欠けた情報＝<b>素材が本当はどのカスタムアイテムなのか</b>を
 * material + CustomModelData の形で外へ出すためのもの。受け取った側
 * (GeyserExtra の Geyser extension) が、実際に登録済みの Bedrock アイテム定義を指す
 * 補正レシピをクライアントへ追送する。
 *
 * <h2>設計上の約束</h2>
 * <ul>
 *   <li><b>ここは純データで、Bukkit のサーバ実装に一切触らない。</b>
 *       {@link Material} は enum なので参照してよい。テストから直接組める。</li>
 *   <li>1マスに<b>複数の候補</b>を持てる ({@code list:} 素材)。組み合わせの展開は受け取り側の仕事。
 *       ここで展開すると 1 レシピが数百件に膨らむ。</li>
 *   <li>{@link Slot#items()} が空のマスは「空欄」を意味する (shaped のみ)。</li>
 * </ul>
 */
public final class BedrockRecipeTable {

    /**
     * ファイル形式のバージョン。受け取り側は<b>理解できないバージョンなら読まずに警告する</b>。
     *
     * <p><b>2 (2026-08-20)</b>: {@link Type#SMITHING} を追加した。<b>ここを上げないと事故る</b> ──
     * v1 の受け取り側は {@code type} が {@code "shaped"} でなければ問答無用で shapeless 扱いするので、
     * スミス台レシピ(テンプレ/base/追加素材の3スロット)を<b>「3素材の作業台 shapeless レシピ」として
     * 統合版クライアントへ配ってしまう</b>。配備は TF/ArsPaper と GeyserExtra で別スクリプトなので
     * 片側だけ新しい状態が実際に起こりうる。
     *
     * <p>逆方向(新しい受け取り側 × 古い書き手)は、受け取り側が v1 も受理することで吸収する
     * (v2 は v1 の厳密な上位互換 ── 増えたのは type の種類だけ)。
     */
    public static final int FORMAT_VERSION = 2;

    private BedrockRecipeTable() {
    }

    /** 1 スロットが受け付けるアイテム 1 種。{@code customModelData} が null ならバニラ素材そのもの。 */
    public record ItemRef(Material material, Integer customModelData, int count) {

        public ItemRef {
            Objects.requireNonNull(material, "material");
            if (count < 1) {
                throw new IllegalArgumentException("count must be >= 1: " + count);
            }
        }

        public static ItemRef of(Material material, Integer customModelData) {
            return new ItemRef(material, customModelData, 1);
        }

        /** カスタム識別を持つか (＝Geyser のバニラ変換では表現できない側)。 */
        public boolean isCustom() {
            return customModelData != null;
        }
    }

    /**
     * 盤面 1 マス。候補が複数あるのは {@code list:} 素材のときだけ。
     * 空リストは「このマスは空欄」。
     */
    public record Slot(List<ItemRef> items) {

        public Slot {
            items = List.copyOf(items);
        }

        public static Slot empty() {
            return new Slot(List.of());
        }

        public static Slot of(ItemRef... refs) {
            return new Slot(List.of(refs));
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public boolean hasCustom() {
            return items.stream().anyMatch(ItemRef::isCustom);
        }
    }

    /**
     * レシピの種別。
     *
     * <p>{@link #SMITHING} は<b>スミス台のネザライト強化</b>({@code items/catalog.yml} の
     * {@code method: netherite})。{@link Recipe#slots()} は必ず
     * {@link #SMITHING_TEMPLATE_SLOT}/{@link #SMITHING_BASE_SLOT}/{@link #SMITHING_ADDITION_SLOT}
     * の 3 マス固定で、盤面の幅・高さは持たない。
     */
    public enum Type { SHAPED, SHAPELESS, SMITHING }

    /** {@link Type#SMITHING} のスロット数(テンプレ / base / 追加素材)。 */
    public static final int SMITHING_SLOT_COUNT = 3;

    /** スミス台のテンプレ枠。TF では常にネザライト強化テンプレ(バニラ)。 */
    public static final int SMITHING_TEMPLATE_SLOT = 0;

    /** スミス台の base 枠。<b>ここだけがカスタム品</b>で、補正の本体。 */
    public static final int SMITHING_BASE_SLOT = 1;

    /** スミス台の追加素材枠。TF では常にネザライトインゴット(バニラ)。 */
    public static final int SMITHING_ADDITION_SLOT = 2;

    /**
     * 1 レシピ。
     *
     * @param id      Java 側の NamespacedKey 文字列。受け取り側が重複を排除するための識別子で、
     *                Bedrock のレシピ ID として使う保証はしない
     * @param type    shaped / shapeless
     * @param width   shaped のときの盤面幅 (shapeless では 0)
     * @param height  shaped のときの盤面高 (shapeless では 0)
     * @param slots   shaped なら行優先で {@code width * height} 個、shapeless なら素材の並び
     *                (空スロットを含まない)
     * @param result  完成品
     */
    public record Recipe(String id, Type type, int width, int height, List<Slot> slots, ItemRef result) {

        public Recipe {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(result, "result");
            slots = List.copyOf(slots);
            if (type == Type.SHAPED) {
                if (width < 1 || height < 1) {
                    throw new IllegalArgumentException("shaped recipe needs a positive grid: " + width + "x" + height);
                }
                if (slots.size() != width * height) {
                    throw new IllegalArgumentException(
                            "shaped recipe '" + id + "' has " + slots.size() + " slots for a "
                                    + width + "x" + height + " grid");
                }
            } else if (type == Type.SMITHING) {
                if (slots.size() != SMITHING_SLOT_COUNT) {
                    throw new IllegalArgumentException(
                            "smithing recipe '" + id + "' needs exactly " + SMITHING_SLOT_COUNT
                                    + " slots (template/base/addition) but has " + slots.size());
                }
                // 空マスのあるスミス台レシピは統合版では成立しない(3枠すべてを要求する UI)。
                // 空のまま配ると「クライアントは出せると思っているのにサーバが渡さない」になる。
                for (int i = 0; i < slots.size(); i++) {
                    if (slots.get(i).isEmpty()) {
                        throw new IllegalArgumentException(
                                "smithing recipe '" + id + "' has an empty slot at index " + i);
                    }
                }
            } else if (slots.isEmpty()) {
                throw new IllegalArgumentException("shapeless recipe '" + id + "' has no ingredients");
            }
        }

        /**
         * 素材のどこかにカスタム識別があるか。
         *
         * <p><b>ここが false のレシピは書き出さない。</b> 素材がすべてバニラなら Geyser の既定変換で
         * 正しく照合できる（結果だけカスタムでも結果は正しく変換される）ので、
         * 補正レシピを足すと同じレシピが二重に載るだけになる。
         *
         * <p>{@link Type#SMITHING} でも同じ判定でよい ── テンプレと追加素材は必ずバニラなので、
         * ここが true になるのは<b>base がカスタム品のとき</b>だけ。そしてそれが直したい唯一のケース。
         */
        public boolean needsBedrockFix() {
            return slots.stream().anyMatch(Slot::hasCustom);
        }
    }

    /** 書き出し 1 回分。{@code skipped} は素材の解決に失敗して落としたレシピ。 */
    public record Table(int version, String source, List<Recipe> recipes, List<String> skipped) {

        public Table {
            Objects.requireNonNull(source, "source");
            recipes = List.copyOf(recipes);
            skipped = List.copyOf(skipped);
        }

        public static Table of(String source, List<Recipe> recipes, List<String> skipped) {
            List<Recipe> sorted = new ArrayList<>(recipes);
            // 出力を安定させる。並びが揺れると差分が毎回出て、配備のたびに全プレイヤーへ
            // 補正パケットを撃ち直す判断が付かなくなる。
            sorted.sort((a, b) -> a.id().compareTo(b.id()));
            List<String> sortedSkipped = new ArrayList<>(skipped);
            Collections.sort(sortedSkipped);
            return new Table(FORMAT_VERSION, source, sorted, sortedSkipped);
        }
    }
}
