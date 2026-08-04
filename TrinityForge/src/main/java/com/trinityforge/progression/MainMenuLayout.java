package com.trinityforge.progression;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /tf menu} 統合メニュー(2026-08-04新設)の純粋なレイアウト(Bukkit非依存)。
 *
 * <p>項目の並び・スロット割当・クリック解決だけを扱う。実際のGUI描画(アイコン選定・lore整形)・
 * 各項目クリック時にどの既存GUIを開くかの配線は {@link MainMenuGui} が担う
 * ({@code AchievementCanvas}/{@code AchievementGui} と同じ「純粋な投影 + 薄いBukkit層」の分け方)。
 *
 * <p>MockBukkitでGUIの実オープンを検証するより、この「クリック→どの項目のどの状態
 * (開ける/使えない)へ解決されるか」を純粋関数として切り出す方がテストしやすい
 * ({@code AchievementCanvasTest} と同じ方針)。
 */
public final class MainMenuLayout {

    /** メニュー項目の安定ID(PDCへ書く値・テストが参照する値としても使う)。 */
    public static final String ROLE = "role";
    public static final String STATUS = "status";
    public static final String SKILLS = "skills";
    public static final String ACHIEVEMENT = "achievement";
    public static final String COLLECTION = "collection";
    public static final String SETTINGS = "settings";

    /**
     * 表示順・スロット割当。54枠中3段目(絶対スロット18-26)の中央6枠(19-24)に置く
     * (外周をガラス板等の装飾に残しつつ中央へ寄せる、既存GUIの中央寄せ配置に倣う)。
     */
    private static final List<Item> ITEMS = List.of(
            new Item(ROLE, 19, "ロールセット", List.of("戦闘職・補助職を選択します。")),
            new Item(STATUS, 20, "ステータス", List.of("現在のステータスを確認します。")),
            new Item(SKILLS, 21, "スキルツリー", List.of("スキルツリーを開きます。")),
            new Item(ACHIEVEMENT, 22, "実績", List.of("実績の達成状況を確認します。")),
            new Item(COLLECTION, 23, "図鑑", List.of("収集したアイテム・モブを確認します。")),
            new Item(SETTINGS, 24, "設定", List.of("称号・パーティクルなどを設定します。")));

    private static final Map<Integer, Item> BY_SLOT;
    private static final Map<String, Item> BY_ID;

    static {
        Map<Integer, Item> bySlot = new LinkedHashMap<>();
        Map<String, Item> byId = new LinkedHashMap<>();
        for (Item item : ITEMS) {
            if (bySlot.put(item.slot(), item) != null) {
                throw new IllegalStateException("duplicate main-menu slot: " + item.slot());
            }
            if (byId.put(item.id(), item) != null) {
                throw new IllegalStateException("duplicate main-menu id: " + item.id());
            }
        }
        BY_SLOT = Map.copyOf(bySlot);
        BY_ID = Map.copyOf(byId);
    }

    private MainMenuLayout() {
    }

    /** 表示順(登録順)の全項目。 */
    public static List<Item> items() {
        return ITEMS;
    }

    public static Optional<Item> itemForSlot(int slot) {
        return Optional.ofNullable(BY_SLOT.get(slot));
    }

    public static Optional<Item> itemForId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * クリックされたスロットが、実際にどの項目のどの状態(開ける/使えない)へ解決されるかを純粋に返す。
     *
     * @param availability 項目ID→利用可否。未記載のIDは既定で利用可能として扱う(fail-open。
     *                     権限判定はここで新設せず、呼び出し側が各機能の既存フラグをそのまま渡す)。
     */
    public static Resolution resolveClick(int slot, Map<String, Boolean> availability) {
        Item item = BY_SLOT.get(slot);
        if (item == null) {
            return Resolution.none();
        }
        boolean available = availability.getOrDefault(item.id(), true);
        return new Resolution(item.id(), true, available);
    }

    /** メニュー項目1件の定義。 */
    public record Item(String id, int slot, String displayName, List<String> description) {
    }

    /**
     * クリック解決の結果。{@code matched=false} は「そもそも項目の無いスロット」、
     * {@code available=false} は「項目はあるが今は使えない(グレーアウト)」を表す。
     */
    public record Resolution(String id, boolean matched, boolean available) {
        static Resolution none() {
            return new Resolution(null, false, false);
        }
    }
}
