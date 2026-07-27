# ArsPaper 改変仕様書

- **作成日**: 2026-05-15
- **対象**: ArsPaper-1.0.0 (自作プラグイン、fork可)
- **位置付け**: Phase 5 実装の事前資料
- **採用設計レベル**: **Level A (Full)** — ユーザー確認済み (2026-05-15)
- **想定規模**: 20-25 files / 900-1200 行 / 工数 3-5日
- **関連文書**:
  - `docs/DESIGN.md` v0.4 (EliteMobs改変全体方針)
  - `C:\Users\T-319\.claude\plans\3-typed-book.md` (3プラグイン連携の評価レポートとフェーズ計画)

---

## 1. 概要と目的

ValhallaMMO スキルツリーの perk 解放効果として、ArsPaper の魔法システムへ広範な影響を与えられるようにする。具体的には7つの相互作用 (§2) を ValhallaMMO 側から ArsPaper を操作できるよう、ArsPaper 側に **public API + Custom Bukkit Event + 設定拡張** を実装する。

### 1.1 設計原則

- **下流非依存**: EliteMobs と ValhallaMMO は ArsAPI と Bukkit Event のみを参照し、ArsPaper の内部実装には依存しない (リフレクションも禁止)
- **加算可能な modifier**: マナ修正・素材修正は key 別の加算 modifier として持つ (複数 source 対応、source 識別子で remove可能)
- **既存挙動の保全**: API を呼ばないプレイヤーの体験は ArsPaper 1.0.0 と完全互換
- **GPL/Apache 系ライセンス汚染回避**: ArsPaper は自作のためライセンス自由

### 1.2 スコープ外 (本仕様書では扱わない)

- ValhallaMMO 側の perk 設計 (別途 `docs/MAGIC_SKILLTREE.md` で設計予定)
- EliteMobs 側の物理/魔法耐性適用 (別途 `docs/RESISTANCE_SPEC.md` で設計予定)
- グリフ→パークの対応表 (Phase 7 で iterative に確定)

---

## 2. 要件: 7つの相互作用

| # | 相互作用 | ArsPaper側の対応 |
|---|---|---|
| 1 | グリフ解放権利の付与 (パーク取得→特定グリフunlock化) | `unlockGlyph()` API + `external-unlock-only` フラグ |
| 2 | マナ回復速度/最大値の上昇 | `addManaModifier()` API + Modifier system |
| 3 | 儀式/クラフト権利の解放 | `unlockRitual()`, `unlockRecipe()` API + Custom Event |
| 4 | 確率でマナ消費減少 | `ArsSpellCastEvent#setManaCost()` で外部から改竄可能 |
| 5 | 詠唱/儀式/クラフト素材の減少 | `ArsRitualPreEvent#setRequiredMaterials()`, `ArsRecipeCraftPreEvent#setIngredients()` |
| 6 | ValhallaMMO から Thread 等 Ars アイテム取得 | `ArsAPI.getItem(itemId)` で ItemStack 解決 |
| 7 | クラフト時の特殊加工 (Thread数増加・品質ランダム化) | `ArsItemCraftedEvent#setResultStack()` + `ItemQuality` system |

---

## 3. アーキテクチャ概要

```
┌────────────────────────────────────────────────────────────────────┐
│  ValhallaMMO fork (Skill: Magic, Perk: fire_glyph, mana_pool_1...) │
└───────────────────┬────────────────────────────────────────────────┘
                    │  PerkUnlockEvent → catch
                    │  ArsAPI.unlockGlyph("fire")
                    │  ArsAPI.addManaModifier(...)
                    ▼
┌────────────────────────────────────────────────────────────────────┐
│  ArsPaper fork    [ArsAPI ファサード]                              │
│  ├─ Registry (Glyph/Ritual/Recipe/Item)                            │
│  ├─ PlayerData (unlock state, modifier)                            │
│  ├─ Modifier (key-based 加算/乗算 modifier)                        │
│  └─ Event (Bukkit Custom Event 10種)                               │
│                                                                    │
│  既存コード (改造)                                                 │
│  ├─ SpellBindListener (詠唱) ──[ArsSpellCastEvent発火]──┐         │
│  ├─ SpellContext (ダメージ) ──[ArsSpellDamageEvent発火]──┤         │
│  ├─ ScribingTableGui (unlock) ──[ArsGlyphUnlockReq...]──┤         │
│  ├─ Ritual 実行 ──[ArsRitualPreEvent]──────────────────┤         │
│  ├─ Crafting ──[ArsRecipeCraftPreEvent/ItemCrafted]────┤         │
│  └─ ManaManager (modifier 適用)─────────────────────────┘         │
└─────────────────────────┬──────────────────────────────────────────┘
                          │  ArsSpellDamageEvent
                          ▼
┌────────────────────────────────────────────────────────────────────┐
│  EliteMobs fork                                                    │
│  - ArmorDefenseCalculator: 魔法ダメージは magic_resistance を適用 │
│  - Target に付与された PDC marker から caster 特定                │
└────────────────────────────────────────────────────────────────────┘
```

---

## 4. パッケージ構成

```
arspaper-fork/src/main/java/com/arspaper/api/
├── ArsAPI.java                       # 静的ファサード (全公開メソッド)
├── ArsPlayerData.java                # プレイヤー単位の unlock 状態 + modifier 管理
├── ArsPlayerDataStore.java           # 永続化 (load/save)
├── registry/
│   ├── ArsGlyphRegistry.java         # グリフID列挙、メタ取得
│   ├── ArsRitualRegistry.java
│   ├── ArsRecipeRegistry.java
│   └── ArsItemRegistry.java          # itemId → ItemStack 解決
├── modifier/
│   ├── ArsModifier.java              # key-based 数値 modifier
│   ├── ModifierType.java             # enum (下記 §7)
│   └── ModifierStore.java            # player × key × type → value
├── quality/
│   ├── ItemQuality.java              # int 0-5 + PDC キー定義
│   └── QualityHelper.java            # PDC R/W + lore 自動更新
├── event/
│   ├── ArsSpellCastEvent.java
│   ├── ArsSpellDamageEvent.java
│   ├── ArsGlyphUnlockRequestEvent.java
│   ├── ArsGlyphUnlockedEvent.java
│   ├── ArsGlyphLockedEvent.java
│   ├── ArsRitualPreEvent.java
│   ├── ArsRitualPostEvent.java
│   ├── ArsRecipeCraftPreEvent.java
│   ├── ArsItemCraftedEvent.java
│   └── ArsManaModifierChangeEvent.java
└── internal/
    ├── ApiBootstrap.java             # Plugin#onEnable で初期化
    └── MagicDamageMarker.java        # PDC marker (TTL 5 tick)
```

---

## 5. ArsAPI 完全シグネチャ

```java
package com.arspaper.api;

public final class ArsAPI {
    private ArsAPI() {}

    // ============ Glyph ============
    public static boolean isGlyphUnlocked(Player p, String glyphId);
    public static void unlockGlyph(Player p, String glyphId);
    public static void lockGlyph(Player p, String glyphId);
    public static Set<String> getUnlockedGlyphs(Player p);
    public static Set<String> getAllGlyphIds();
    public static @Nullable String getGlyphIdFromItem(ItemStack item);

    // ============ Ritual / Recipe unlock ============
    public static boolean isRitualUnlocked(Player p, String ritualId);
    public static void unlockRitual(Player p, String ritualId);
    public static void lockRitual(Player p, String ritualId);
    public static boolean isRecipeUnlocked(Player p, String recipeId);
    public static void unlockRecipe(Player p, String recipeId);
    public static void lockRecipe(Player p, String recipeId);
    public static Set<String> getAllRitualIds();
    public static Set<String> getAllRecipeIds();

    // ============ Mana / Cost / Material Modifier ============
    public static double getMaxMana(Player p);           // base + 全 MAX_MANA modifier
    public static double getManaRegenRate(Player p);     // base + 全 REGEN_RATE modifier
    public static void addManaModifier(Player p, NamespacedKey key, ModifierType type, double value);
    public static void removeManaModifier(Player p, NamespacedKey key, ModifierType type);
    public static @Nullable Double getManaModifier(Player p, NamespacedKey key, ModifierType type);
    public static Map<NamespacedKey, Double> listModifiers(Player p, ModifierType type);

    // ============ Item Registry ============
    public static @Nullable ItemStack getItem(String itemId);   // "arspaper:thread" → 新規 ItemStack
    public static Set<String> getItemRegistry();
    public static @Nullable String getItemId(ItemStack stack);  // 逆引き (PDC key 参照)

    // ============ Item Quality ============
    public static int getItemQuality(ItemStack stack);          // 0 (なし) ~ 5
    public static void setItemQuality(ItemStack stack, int quality);  // 0-5、PDC + lore更新

    // ============ Magic Damage Marker (EliteMobs連携用) ============
    public static boolean isMagicDamage(EntityDamageEvent e);
    public static @Nullable Player getCaster(EntityDamageEvent e);
    public static @Nullable Player getCaster(LivingEntity victim);  // PDC直接参照

    // ============ Lifecycle ============
    public static void reloadPlayerData(Player p);
    public static void savePlayerData(Player p);
    public static void saveAllPlayerData();
}
```

---

## 6. Custom Bukkit Event 詳細

### 6.1 ArsSpellCastEvent (cancellable)

```java
public class ArsSpellCastEvent extends PlayerEvent implements Cancellable {
    public ArsSpellCastEvent(Player caster, List<String> activeGlyphs,
                             double initialManaCost, double initialDamage);

    public List<String> getActiveGlyphs();   // immutable
    public double getManaCost();
    public void setManaCost(double cost);    // 0 で無料化可能
    public double getDamage();
    public void setDamage(double damage);    // 該当する場合のみ
    public String getPrimaryEffectId();      // "harm" / "lightning" / ...
    public String getCastForm();             // NEW: form ID ("projectile"/"touch"/"self"/"aoe"/"beam"/"wall"...)

    public boolean isCancelled();
    public void setCancelled(boolean b);
}
```
発火タイミング: `SpellBindListener` の詠唱判定処理の冒頭、マナ消費前。cancel で詠唱中止。

> **form別CT連携（MAGIC_BALANCE_SPEC §3）**: アドオンは `getCastForm()` で form を判定し、該当 form が CT 中なら本イベントを `setCancelled(true)`（マナ消費前）。`getCastForm()` は activeGlyphs の先頭 form グリフから解決する新規アクセサ。CT 状態はアドオン側のプレイヤー×form マップで保持（短命・永続化不要）。

### 6.2 ArsSpellDamageEvent (cancellable)

```java
public class ArsSpellDamageEvent extends Event implements Cancellable {
    public LivingEntity getTarget();
    public @Nullable Player getCaster();
    public List<String> getActiveGlyphs();
    public double getDamage();
    public void setDamage(double damage);
    public String getEffectId();             // "harm" / "lightning" / ...

    public boolean isCancelled();
    public void setCancelled(boolean b);
}
```
発火タイミング: ダメージ系 Effect (HarmEffect 等) が `LivingEntity.damage()` を呼ぶ **直前**。発火直前に target の PDC に magic damage marker を 5 tick TTL で attach。

### 6.3 ArsGlyphUnlockRequestEvent (cancellable)

```java
public class ArsGlyphUnlockRequestEvent extends PlayerEvent implements Cancellable {
    public String getGlyphId();
    public List<ItemStack> getMaterialsToConsume();
    public void setMaterialsToConsume(List<ItemStack> materials);
    public int getLevelCost();
    public void setLevelCost(int cost);

    public boolean isCancelled();
    public void setCancelled(boolean b);
}
```
発火タイミング: `ScribingTableGui` で player が素材を消費して glyph unlock を試みる際。`external-unlock-only: true` のグリフは ArsPaper 内部で必ず cancel。

### 6.4 ArsGlyphUnlockedEvent / ArsGlyphLockedEvent (post-event)

```java
public class ArsGlyphUnlockedEvent extends PlayerEvent {
    public String getGlyphId();
    public UnlockSource getSource();   // PLAYER_CRAFT, API, COMMAND
}
public class ArsGlyphLockedEvent extends PlayerEvent {
    public String getGlyphId();
    public LockSource getSource();
}
```

### 6.5 ArsRitualPreEvent (cancellable)

```java
public class ArsRitualPreEvent extends PlayerEvent implements Cancellable {
    public String getRitualId();
    public Location getLocation();
    public List<ItemStack> getRequiredMaterials();
    public void setRequiredMaterials(List<ItemStack> materials);   // 削減/置換可

    public boolean isCancelled();
    public void setCancelled(boolean b);
}
```

### 6.6 ArsRitualPostEvent (post-event)

```java
public class ArsRitualPostEvent extends PlayerEvent {
    public String getRitualId();
    public Location getLocation();
    public boolean isSuccessful();
}
```

### 6.7 ArsRecipeCraftPreEvent (cancellable)

```java
public class ArsRecipeCraftPreEvent extends PlayerEvent implements Cancellable {
    public String getRecipeId();
    public ItemStack getResultPreview();
    public List<ItemStack> getIngredients();
    public void setIngredients(List<ItemStack> ingredients);

    public boolean isCancelled();
    public void setCancelled(boolean b);
}
```

### 6.8 ArsItemCraftedEvent (post-event)

```java
public class ArsItemCraftedEvent extends PlayerEvent {
    public String getRecipeId();
    public ItemStack getResultStack();
    public void setResultStack(ItemStack stack);   // 個数増加/品質付与
    public int getRollSeed();
}
```

### 6.9 ArsManaModifierChangeEvent (post-event)

```java
public class ArsManaModifierChangeEvent extends PlayerEvent {
    public NamespacedKey getKey();
    public ModifierType getType();
    public @Nullable Double getOldValue();
    public @Nullable Double getNewValue();
}
```

---

## 7. ModifierType enum

```java
public enum ModifierType {
    MAX_MANA,                       // 最大マナ加算 (例: +50)
    REGEN_RATE,                     // マナ回復速度加算 (例: +0.5/sec)
    MANA_COST_MULT,                 // マナ消費倍率 (例: 0.95 = 5%削減)
    MANA_COST_REDUCTION_CHANCE,     // 0-1.0 で確率0コスト発生
    MATERIAL_REDUCTION,             // 素材消費削減 (例: 0.1 = 10%削減)
    MATERIAL_REDUCTION_CHANCE,      // 0-1.0 で材料1個消費スキップ
}
```

合算規則:
- `MAX_MANA`, `REGEN_RATE`: 全 modifier を **加算**
- `MANA_COST_MULT`, `MATERIAL_REDUCTION`: 全 modifier を **加算 → final = max(0, 1.0 - sum)**
- `MANA_COST_REDUCTION_CHANCE`, `MATERIAL_REDUCTION_CHANCE`: 全 modifier を **加算 → clamp(0, 1.0)** にして確率判定

---

## 8. 既存 ArsPaper コードへのフック

| 既存クラス | 改造内容 | 行数 |
|---|---|---|
| `SpellBindListener` (詠唱判定) | `ArsSpellCastEvent` 発火、cancellable + manaCost 改竄反映 | +20 |
| `SpellContext` or ダメージ実行クラス | `LivingEntity.damage()` 前に `ArsSpellDamageEvent` 発火 + PDC marker付与 (`arspaper:magic_damage_caster=<uuid>`, TTL 5 tick) | +25 |
| `ScribingTableGui` (グリフunlock GUI) | `ArsGlyphUnlockRequestEvent` 発火、`external-unlock-only: true` のグリフは強制cancel | +15 |
| Ritual 実行クラス (要特定) | `ArsRitualPreEvent` + `ArsRitualPostEvent` 発火、setRequiredMaterials 反映 | +20 |
| Recipe / Crafting (Spell Crafting, Scribe craft等) | `ArsRecipeCraftPreEvent` + `ArsItemCraftedEvent` 発火、setIngredients/setResultStack 反映 | +30 |
| `ManaManager` | `getMaxMana()` / `getRegenRate()` を modifier 合算結果に変更、`consume()` で `MANA_COST_MULT` 適用 | +40 |
| `glyphs.yml` パーサー | `external-unlock-only` フィールド読み込み | +5 |
| `rituals.yml` パーサー | 同上 | +5 |
| `recipes.yml` パーサー | 同上 | +5 |
| Plugin Main (`ArsPaper.java` or similar) | `ArsAPI` の static init、PlayerDataStore の load/save、Listener登録 | +30 |

合計: 既存改造 ~195行 + 新規API ~700-1000行 = **ArsPaper fork 全体: 900-1200行 / 20-25 ファイル**

---

## 9. 設定ファイル拡張

### 9.1 既存 yml に追加するフィールド

```yaml
# plugins/ArsPaper/glyphs.yml (各グリフエントリに追加)
fire:
  display-name: "&cFire Glyph"
  tier: 1
  unlock-cost:
    level: 5
    materials:
      - { material: minecraft:blaze_powder, amount: 4 }
  external-unlock-only: false   # NEW: true なら素材unlock不可、ArsAPI 経由のみ
```

```yaml
# plugins/ArsPaper/rituals.yml (各儀式エントリに追加)
ritual_of_warding:
  display-name: "Ritual of Warding"
  required-materials:
    - minecraft:diamond
    - arspaper:source_gem
  external-unlock-only: true    # NEW
```

```yaml
# plugins/ArsPaper/recipes.yml (各レシピに追加)
thread_recipe:
  ingredients:
    - minecraft:string × 4
  result: arspaper:thread × 1
  external-unlock-only: false   # NEW
```

### 9.2 新規ファイル

```yaml
# plugins/ArsPaper/items.yml (新規、品質エフェクト定義)
quality-effects:
  thread:
    "1": { lore-suffix: "&7[Poor]" }
    "2": { lore-suffix: "&aFine" }
    "3": { lore-suffix: "&aSuperior" }
    "4": { lore-suffix: "&bExceptional" }
    "5": { lore-suffix: "&dMasterwork" }
  arspaper_wand:
    "1": { lore-suffix: "&7[Poor]", mana-cost-mult: 1.1 }
    "5": { lore-suffix: "&dMasterwork", mana-cost-mult: 0.9, damage-bonus: 1.2 }
```

### 9.3 config.yml に追加

```yaml
api:
  enabled: true
  event-log: false                # debug 用、event 発火を全部ログ出力
  modifier-persistence: false     # false なら起動時 modifier クリア (ValhallaMMOから再適用)
```

---

## 10. 永続化

| データ | 保存先 | フォーマット | 永続化必須 |
|---|---|---|---|
| プレイヤーの unlock 状態 (glyph/ritual/recipe) | `plugins/ArsPaper/playerdata/<uuid>.yml` | yml | **YES** |
| マナ modifier (key別) | 同 yml の `modifiers:` セクション | yml | NO (config.yml `modifier-persistence` で切替) |
| アイテム品質 | ItemStack PDC `arspaper:quality` (int) | PDC | **YES** (ItemStackと一体) |
| マジックダメージマーカー | LivingEntity PDC `arspaper:magic_damage_caster` (UUID) + `magic_damage_expire_tick` (long) | PDC (TTL 5 tick) | NO (短命) |

### 10.1 playerdata yml 例 (synthetic uuid)
```yaml
# plugins/ArsPaper/playerdata/abcd-1234-...-uuid.yml
unlocked-glyphs:
  - fire
  - water
unlocked-rituals:
  - ritual_of_warding
unlocked-recipes:
  - thread_recipe
modifiers:
  - { key: "valhallammo:mana_pool_1", type: MAX_MANA, value: 50.0 }
  - { key: "valhallammo:thrift_1", type: MANA_COST_REDUCTION_CHANCE, value: 0.05 }
```

---

## 11. ValhallaMMO 側の連携コード例 (参考)

```java
// ValhallaMMO fork: ArsPaperIntegration.java
public class ArsPaperIntegration implements Listener {

    @EventHandler
    public void onPerkUnlock(PerkUnlockEvent e) {
        Player p = e.getPlayer();
        String perkId = e.getPerk().getId();

        switch (perkId) {
            case "magic.fire_glyph":
                ArsAPI.unlockGlyph(p, "fire");
                break;
            case "magic.mana_pool_1":
                ArsAPI.addManaModifier(p,
                    NamespacedKey.fromString("valhallammo:mana_pool_1"),
                    ModifierType.MAX_MANA, 50.0);
                break;
            case "magic.thrift_1":
                ArsAPI.addManaModifier(p,
                    NamespacedKey.fromString("valhallammo:thrift_1"),
                    ModifierType.MANA_COST_REDUCTION_CHANCE, 0.05);
                break;
            case "magic.ritual_warding":
                ArsAPI.unlockRitual(p, "ritual_of_warding");
                break;
        }
    }

    @EventHandler
    public void onArsItemCrafted(ArsItemCraftedEvent e) {
        Player p = e.getPlayer();
        ItemStack result = e.getResultStack();
        String itemId = ArsAPI.getItemId(result);

        if ("arspaper:thread".equals(itemId) && hasPerk(p, "magic.thread_master")) {
            result.setAmount(result.getAmount() * 2);
            e.setResultStack(result);
        }
        if (hasPerk(p, "magic.quality_craft")) {
            int q = rollQuality(p);   // perk levelに応じて 1-5
            ArsAPI.setItemQuality(result, q);
        }
    }
}
```

---

## 12. EliteMobs 側の連携コード例 (参考)

```java
// EliteMobs fork: ArsPaperResistanceHook.java
public class ArsPaperResistanceHook implements Listener {

    @EventHandler(priority = EventPriority.NORMAL)
    public void onArsSpellDamage(ArsSpellDamageEvent e) {
        LivingEntity target = e.getTarget();
        if (!EliteEntity.isEliteMob(target)) return;

        EliteEntity elite = EliteEntity.from(target);
        double magicResist = elite.getMagicResistance();   // 0.0-1.0 (config から)
        double finalDamage = e.getDamage() * (1.0 - magicResist);
        e.setDamage(finalDamage);
    }
}
```

ArsPaper 改修なしで判別する旧設計 (`EntityDamageByEntityEvent` の damager 識別) は不要に。

---

## 13. テスト計画

### 13.1 ユニットテスト (新規API単独)

- `ArsAPI.unlockGlyph()` → `isGlyphUnlocked()` で true
- `ArsAPI.addManaModifier()` × N → `getMaxMana()` が base + Σ
- `ArsAPI.setItemQuality(stack, 3)` → `getItemQuality(stack)` が 3、lore に "Superior" 表示
- `ArsAPI.getItem("arspaper:thread")` が non-null ItemStack を返す

### 13.2 統合テスト (Event発火)

- グリフ素材unlock試行 → `ArsGlyphUnlockRequestEvent` 発火確認
- 詠唱 → `ArsSpellCastEvent` 発火確認、`setManaCost(0)` で消費0確認
- HarmEffect命中 → `ArsSpellDamageEvent` 発火確認、`setDamage(0)` で無効化確認
- 儀式実行 → `ArsRitualPreEvent` 発火確認、`setRequiredMaterials([])` で素材消費0確認
- Recipe crafting → `ArsItemCraftedEvent` 発火確認、`setResultStack` で個数倍化確認

### 13.3 PDC ライフサイクル

- `ArsSpellDamageEvent` 発火 → target PDC に caster UUID attach
- 5 tick 経過後 → PDC 削除
- ItemQuality PDC → アイテム移動/格納/ドロップで保持

### 13.4 永続化

- `unlockGlyph` 直後にサーバー再起動 → playerdata yml で unlock 状態保持
- modifier (config.yml: persistence: false) → 再起動で消失、ValhallaMMO 側で再適用される動作確認

---

## 14. 開発スケジュール (3-5日)

| Day | 内容 | 出力 |
|---|---|---|
| 1 (午前) | `extract_ars/` 展開、既存クラス把握 (SpellBindListener, SpellContext, ScribingTableGui, ManaManager, ItemKeys, PdcHelper、Ritual実行系) | フック箇所メモ |
| 1 (午後) | `arspaper-fork/` git init、Gradle build 環境構築、素ビルド成功確認 | 素ビルド jar |
| 2 (午前) | API パッケージ scaffold (ArsAPI, ArsPlayerData, Registry, Modifier, Event 全クラスのスタブ) | コンパイル可能なスタブ |
| 2 (午後) | Glyph 機能実装 (unlockGlyph, lockGlyph, isGlyphUnlocked, ArsGlyphUnlockedEvent, ScribingTableGui hook) + テストプラグインで動作確認 | グリフ unlock API動作 |
| 3 (午前) | Mana modifier 実装 (ManaManager hook, addManaModifier, ModifierType 全種、ArsSpellCastEvent) | マナ調整 API動作 |
| 3 (午後) | Ritual/Recipe unlock + PreEvent (素材 modifier) | 儀式/レシピ unlock 動作 |
| 4 (午前) | ItemRegistry + ItemQuality (ArsItemCraftedEvent, PDC + lore更新) | Thread 数倍化、品質付与動作 |
| 4 (午後) | ArsSpellDamageEvent + MagicDamageMarker (PDC TTL 5tick) | EliteMobs連携可能な状態 |
| 5 (午前) | PlayerDataStore (yml load/save, autosave) + config.yml 拡張 | 永続化動作 |
| 5 (午後) | テストプラグイン or ValhallaMMO fork 仮実装で全機能 E2E テスト | Phase 5 完了 |

---

## 15. 検証項目 (Phase 5 完了条件)

| 確認 | 期待値 | 方法 |
|---|---|---|
| ArsAPI build OK | jar 生成成功、3rd party plugin から `import com.arspaper.api.ArsAPI` 可能 | テストプラグイン 1 個書いて compile |
| グリフ API動作 | `unlockGlyph("fire")` 後 player が fire glyph を呪文に組み込める | サバイバルでスクライブ |
| マナ modifier | `addManaModifier(MAX_MANA, +50)` 後 GUI 表示の最大マナが +50 | ArsPaper の mana bar/GUI 観察 |
| マナ消費 modifier | `setManaCost(0)` でその詠唱が無料化 | `/mana` で消費確認 |
| 素材 modifier | `ArsRitualPreEvent#setRequiredMaterials([])` で儀式が0素材で発動 | 儀式実機 |
| Item registry | `getItem("arspaper:thread")` が正規の thread ItemStack を返す (lore/PDC含む) | inventory 投入確認 |
| Item quality | `setItemQuality(stack, 5)` で lore に "Masterwork" 表示、PDC `arspaper:quality=5` | NBT viewer 確認 |
| Magic damage marker | HarmEffect 命中後 5 tick 以内に target の PDC に caster UUID 存在、5tick超で消失 | EliteMobs 側 listener で観測 |
| Event 発火 | 10種すべて発火、listener から catch 可能 | テストプラグインで全 event 受信 |
| 永続化 | unlockGlyph 後再起動でも unlock 維持 | server restart |
| 既存挙動 | API を使わない player は ArsPaper 1.0.0 と完全同一の体験 | regression test |

---

## 16. 既知の不確実性とリスク

| リスク | 影響度 | 対策 |
|---|---|---|
| ArsPaper の既存 Ritual 実行クラスが未特定 | 中 | Day 1 の `extract_ars/` 展開時に grep 検索、`com.arspaper.ritual.effect/*` 配下を読む |
| `SpellContext` でダメージ実行している Effect が複数あり、フック箇所が分散 | 中 | 12 個のダメージ Effect 共通の基底 (`SpellEffect`) で `damage()` を一元化、または各 Effect に individual hook |
| `ManaManager` の getMaxMana 直接参照箇所がコード中に分散 | 中 | grep でリファクタ、Day 3 で対応 |
| `glyphs.yml` の現フォーマット (Map vs List) が不明 | 低 | Day 1 で実機確認 |
| アイテム品質の lore 自動更新が translatable text と衝突 | 低 | lore 末尾追加方式、既存 lore は保全 |
| 既存 `playerdata` 機構が ArsPaper にあるか不明 (NBT/yml/SQLite) | 中 | Day 1 で確認、既存があれば統合、なければ yml で新設 |
| ArsPaper のソースコード入手 (自作プラグインだが Claude は jar しか持っていない) | **高** | Day 1 開始時に user からソースの場所を確認、必要なら decompile して fork スタート |

---

## 17. 関連リソース

- ArsPaper jar: `C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\elitemobs\ArsPaper-1.0.0.jar`
- 展開先 (Day 1): `C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\elitemobs\extract_ars\`
- fork 作業ディレクトリ (Day 1-): `C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\elitemobs\arspaper-fork\`
- 全体プラン: `C:\Users\T-319\.claude\plans\3-typed-book.md`

---

## 18. 次のアクション (user 確認待ち)

1. **ArsPaper のソースコード入手元の確認** (自作プラグインのため):
   - ローカル git リポジトリがあるか
   - GitHub 等にプッシュされているか
   - jar からのデコンパイル開始でいいか
2. 上記確認後、Phase 5 Day 1 (extract_ars 展開 + arspaper-fork セットアップ) に着手
