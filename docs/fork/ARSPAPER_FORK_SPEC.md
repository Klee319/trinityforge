# ArsPaper fork 実装仕様書（個別リポジトリ用）

- **作成日**: 2026-06-27
- **対象**: ArsPaper（自作Ars Nouveauポート）を fork した個別リポジトリで行う改変。「どのクラスをどう変えるか」を実装可能レベルで示す
- **クラス参照元**: `extract_ars/com/arspaper/**`（逆コンパイル済み。**正確なメソッド署名は実装時に実ソースで確認**）
- **上位文書**: `ADDON_INTEGRATION_SPEC.md`（§3 ArsPaper fork 改変点）、`ARS_SPEC.md`、`MAGIC_BALANCE_SPEC.md`、`UNLOCK_SYSTEM_SPEC.md`、`COMBAT_SYSTEM_SPEC.md`、`SELECTION_SPEC.md`
- **大原則**: CT秒数・マナレート・増減量・レシピ・スレッド効果・品質分布は **fork に焼き込まず設定から供給**（ArsPaperは既に config 駆動。perkゲート連携とパイプライン供給を足す）

---

## 0. 改変の全体方針

ArsPaper は自作のため改変自由度が高い。本サーバ向けに足すのは主に4系統:

1. **使用ゲート**（Valhalla perk で glyph 使用を制御）
2. **form別CT**（バースト抑制。マナ消費前に判定）
3. **魔法ダメージを統合アドオンの対称パイプラインへ供給**（増減グリフは内側に隔離）
4. **解放/品質/バインドの連携**（recipe/ritual の perk ゲート、ItemQuality+rollSeed、soulbind）

---

## 1. 使用ゲート α/β（UNLOCK §2.2・Model Y）

「glyph入手(scribe)は自由、使用にperk必要」。

| 段階 | クラス（`spell.` / `block.impl.`） | 改変 |
|---|---|---|
| **(α) 作成時ブロック（主）** | `block.impl.ScribingTable`（scribe）＋ `spell.SpellRecipe` / `spell.SpellRegistry` | スペル組成時、含まれる各 glyph の **perk所持を Valhalla（統合アドオン経由）で確認**。未許可があれば**組み込み不可**（バインド拒否） |
| **(β) 発動時不発（保険）** | `spell.SpellCaster`（cast本体）＋ `spell.SpellComponent`(`ComponentType`) / `spell.GlyphConfig`(`GlyphData`) | cast時に未許可 glyph を含むスペルは**不発＋メッセージ**（プレステージ等で後から未許可化したケース） |

- glyph 判定キーは `GlyphConfig`/`SpellComponent` の glyph ID。perk→glyph 対応は UNLOCK §5 マスター表（設定）

---

## 2. form別CT（MAGIC_BALANCE §3 / ARS_SPEC §6.1）

| クラス（`spell.`） | 役割 | 改変 |
|---|---|---|
| `SpellForm` | スペルform（Projectile/Touch/Self/AOE/Beam/Wall 等） | form識別子の取得元 |
| `SpellCaster` / `SpellContext` | 詠唱処理・文脈 | **form独立CTトラッカー**を実装。**詠唱成立時を起点**、**マナ消費前にCT判定→未回復なら cancel**（マナを消費させない） |
| （`ARS_SPEC §6.1` ArsSpellCastEvent） | キャストイベント | `getCastForm()` で form を公開（ARS_SPEC追記済）。CT連携の判定面 |

- CT秒数は form ごとに設定（数値フェーズ）。トラッカーはプレイヤー×form のキー

---

## 3. 魔法ダメージのパイプライン供給（COMBAT §2.2 / MAGIC_BALANCE §2）

| クラス（`spell.`） | 役割 | 改変 |
|---|---|---|
| `SpellEffect`(`AoeMode`) / `SpellContext` | スペル効果・ダメージ算出 | **デフォルト魔法ダメージ＝「Arsスペル攻撃力(Ars config値) ＋ 触媒の攻撃力ステ」**を、統合アドオンの**魔法成分 base** として供給（最終ダメージはパイプラインが計算） |
| `SpellAugment` | 増強/減衰（Amplify/Dampen） | **デフォルト魔法ダメージの内側に閉じ込める**（層分離）。統合アドオンの `割合追加ダメージ%`/`ダメージ補正%` とは**二重計上させない**（MAGIC_BALANCE §2） |
| `SpellComponent`(`ComponentType`) | 成分種別 | ダメージ成分か否かの判定に使用 |

- 結果として魔法も物理と同じ8stepパイプライン（gear非依存・三すくみ耐性・対称式）に従う

---

## 4. マナ（COMBAT §3.4-3.5）

| クラス（`mana.` / `item.`） | 役割 | 改変 |
|---|---|---|
| `mana.ManaManager` / `mana.ManaConfig` | マナ管理・設定 | **マナ消費量低下ステ**（`setManaCost`相当・ARS_SPEC §2-4）、**マナ最大値/回復速度上昇ステ**を反映 |
| `item.ArmorManaListener`（+ `ArmorConfigManager` / `ArmorSetConfig`） | 防具由来マナ挙動 | **被弾時/攻撃時/非発動時マナ回復**（COMBAT §3.4）を本サーバ新規ステとして拡張 |
| `mana.ManaBarDisplay` / `mana.RankingCache` | 表示・ランキング | Bedrock表示安定の確認、dpschecker/ranking連携の足場 |

---

## 5. 解放：recipe / ritual の perk ゲート（UNLOCK §3, §5.6-5.10）

| クラス（`recipe.` / `ritual.`） | 役割 | 改変 |
|---|---|---|
| `recipe.RecipeManager` / `recipe.UnifiedRecipeLoader`(`WorkbenchRecipeData`) | クラフトレシピ | **perk所持でレシピ解放**（unlockRecipe相当）。金属/コア/ソースジェム/Waystone/エンチャ品など |
| `ritual.RitualManager`（`PedestalInfo`）/ `ritual.RitualRecipeRegistry` / `ritual.RitualEffectRegistry` | 儀式 | **perk所持で儀式解放**（unlockRitual相当）。召喚/天候/飛行/修繕 |
| `block.impl.Waystone` | Waystone | UNLOCK §5.10 のWaystoneクラフト解放と接続 |

---

## 6. 修繕儀式の許可（UNLOCK §6・確定b）

- バニラ修繕は廃止（EliteMobs fork 側 `RepairEnchantment` 無効化）だが、**Ars鍛冶 B-2-3「修繕の儀式」は許可**
- 改変: `ritual.RitualManager` / `RitualEffectRegistry` に修繕儀式を残し、**コスト（ソース/素材/詠唱手間）を重め**にできる設定を用意（無限修繕の抜け道防止）

---

## 7. 厳選：ItemQuality(0-5) ＋ rollSeed（SELECTION §3, §5）

| クラス（`item.`） | 役割 | 改変 |
|---|---|---|
| `item.BaseCustomItem` | カスタムアイテム基盤 | **quality(0-5) ＋ rollSeed を PDC 保持**。ステは統合アドオンの「rollSeed＋品質＋調整可能テーブル」から**導出**（不変ベイクしない） |
| `item.MaterialConfig`(`Manager`) / `item.RecipeDefinition` | 素材・レシピ定義 | ベース固定値はここ、味付けステの振れは導出テーブル（設定） |
| `item.ThreadConfig` / `item.ThreadType` | スレッド | **特殊効果系＝クラフト**（RecipeManager）／**厳選系＝トレジャー**（`loot.LootTableListener`）の振り分け（UNLOCK §6 / SELECTION §4） |

- 品質効果は「抽選質の底上げ」へ再マッピング（ARS_SPEC の `damage-bonus` 等を SELECTION §3 解釈へ）

---

## 8. バインド（PROGRESSION §3）

- `enchant.SoulboundListener`（既存）を**厳選完成品＝ソウルバインド**へ流用
- EliteMobs 側 `SoulbindEnchantment` と**バインド表現を統一**（どちらを真実にするか実装時に一本化。PDC所有者UUID で揃える）

---

## 9. 設定外出し（ハードコード禁止）

ArsPaper は既に config 駆動（`ManaConfig` / `GlyphConfig` / `ThreadConfig` / `MaterialConfig` / `ArmorSetConfig` / `world.WorldSettingsManager` / `source.sourcelink.SourcelinkConfig`）。本サーバ追加分も同様に外出し:
- form別CT秒数、増強/減衰の量
- マナ消費低下/最大/回復の各ステ係数
- 品質別の抽選分布・ロール範囲・ベース固定値
- レシピ/儀式の perk ゲート対応（UNLOCK §5 マスター表）、修繕儀式コスト
- スレッド効果の定義
- ホットリロード対応（`command.ArsCommand` に reload 経路があれば流用）

---

## 10. 実装順（M2 連動・IMPLEMENTATION_PLAN）

1. 魔法ダメージのパイプライン供給（§3）＋増減グリフ隔離 — 魔法が統合式に乗ることを確認
2. マナ系ステ（§4・`setManaCost`/回復ステ）
3. form別CT（§2・マナ消費前cancel）
4. 使用ゲート α/β（§1・Valhalla perk連携）
5. recipe/ritual perk ゲート（§5）＋修繕儀式（§6）
6. ItemQuality+rollSeed 導出（§7）＋バインド統一（§8）

---

## 11. 未決（実装時に実ソースで確認）

- [ ] `SpellCaster` の cast シーケンスで**マナ消費の正確な順序**（form別CT cancel を消費前に差し込む位置）
- [ ] `ArsSpellCastEvent`（ARS_SPEC §6.1）の**実体有無**と `getCastForm()` 公開状況（無ければ新設）
- [ ] `SpellEffect`/`SpellContext` で**ダメージ算出を横取り**できる箇所（base供給の差し込み点）
- [ ] `SpellAugment` の適用順（増減を base 内側に閉じる実装位置）
- [ ] `RecipeManager`/`RitualManager` の**解放フラグの持ち方**（perk連携の差し込み）
- [ ] `BaseCustomItem` の PDC スキーマ（quality/rollSeed 追加の互換）
