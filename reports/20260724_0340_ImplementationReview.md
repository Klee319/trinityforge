# Task

> **⚠️ 2026-07-27: この文書は残タスクの一次情報ではありません。**
> 残タスク・既知の問題・作業履歴は `reports/ACTIVE_RECORD.md` に集約しました。
> 本書はその日の作業記録として残していますが、記載されている「残タスク」「保留リスト」は
> 既に解決済みのものを多く含みます（棚卸しで15件が実装済みと判明）。参照しないでください。


現在実装の調査レビュー（修正なし）。config配線漏れ、仕様未実装、バグ、進行阻害仕様、Config EditorのUIずれ／同一入力のUI不一致をコードと出荷YAMLから確認した。

# Files Changed

- （コード変更なし）本レポートのみ新規作成

# Details

調査日: 2026-07-24  
スコープ: TrinityForge本体、ArsPaper／EliteMobsフォーク設定、tools/config-editor  
方針: 修正は行わず、事実の洗い出しのみ

---

## 総評

コア戦闘・スキルツリー・使用制限・PerkMirrorは動く一方、**出荷設定の空ゲート／魔法のcombatレベル乗算／純魔ビルのcombat上限**が進行設計を崩しうる。Editorは専用フォーム化が進むが、**同一概念の入力UI差とラベル幅のずれ**が残る。wiki／OPEN_DECISIONSの一部は実装より古い。

---

## 1. 進行を妨げる・崩しうる仕様（Critical / High）

| # | 深刻度 | 内容 | 根拠 |
|---|--------|------|------|
| 1 | **Critical** | `dungeon/gates.yml` が `gates: {}` のまま。入場制限なし（fail-open） | 出荷YAML。ダンジョン難易度ゲートが機能しない |
| 2 | **High** | `magical.scale-with-combat-level: true`（スキーマ既定も true）。C2／JavaDocは「魔法はcombatレベルbypassが既定」と矛盾 | `combat/damage.yml`、`CombatDamageConfig`、`SymmetricCombatService` |
| 3 | **High** | combatレベルが「4スキル共通の max-of-top-N」。純 `ARS_MAGIC` 100 → 約40。物理2特化100+100 → 約80。魔法特化がゲート／レベル差で不利 | `progression/combat-level.yml`、`CombatLevelModel` |
| 4 | **High** | ペット未実装＋タンク挑発なし。ソロ高難易度のヘイト肩代わり手段なし（LD-6既知） | wiki・OPEN_DECISIONS・Pt1 |
| 5 | **High** | `repair-ritual.extra-source-cost: 0`。修繕儀式の無限修繕抜け道（U9） | `unlock-gate.yml` |
| 6 | **Medium** | 区画ゲート `region:` は外→内のたびにキー消費しうる（運用で進行阻害） | `gates.yml` コメント、`DungeonGateService` |
| 7 | **Medium** | BleedがHP直書き。トーテム／吸収／無敵フレームをバイパスしうる | `BleedService` |

**補足:** `use-requirements.enforce: true` 自体は壊れていない。防具は `ArmorUseGateListener`、触媒は Ars `SpellCaster` → TF bridge でゲート済み（`progression/use-requirements.yml` 先頭コメント「防具・触媒は未実装」は**誤り**）。

---

## 2. Config 配線漏れ・死に設定

| # | 深刻度 | 内容 |
|---|--------|------|
| 1 | **High** | Editor「共通変数」の `FIELD_SPECS` に `defense.max-dodge-chance` / `attack-stat-keys` / `defense-stat-keys` が無い。YAMLにあっても定数UIから触れない（FACADES既知） |
| 2 | **Medium** | `stats/attribute-map.yml` はランタイム非読込（`AttributeProjection.defaults()` ハードコード）。Editor README／schemaに残骸表現あり → **編集しても効かない**可能性 |
| 3 | **Medium** | `combat/mob-defaults.yml` は非推奨孤児。権威は `mob-types.yml` の `defaults:` |
| 4 | **Medium** | `item-stats.yml` の `GLOWSTONE.fixed.再生: 3`。正規キー外。戦闘／属性に乗らず死にステ（意図は `health-regen-bonus` 等と思われる） |
| 5 | **Low** | `ConfigManager` に `magic/`・`pets/` 登録 TODO（M2+） |
| 6 | **Low（意図的）** | `attack-power` はバニラAttributeに写さない。TF戦闘パイプラインでは使用中（wikiの「死に設定」はattribute写像の話に限定すべき） |

**ゲート系の誤解注意:** `unlock-gate.yml` / `usage-gate.yml` の空マップは「yml側オーバーレイなし」。正本は skilltree の `dedicated-effects`（`glyph:` / `recipe:` 等）。ars_magic・smithing等に多数定義あり。wiki「空＝perkゲート無効」は**過言**。

ヘイト二重計上は、フォーク側が TF存命時に記録をスキップするよう修正済み（wikiの「二重計上」記述は古い）。根拠: `TrinityForgeTargetingListener` の `tfHateService() != null` 時 early-return。

---

## 3. 仕様にあるが未実装／未結線

| # | 深刻度 | 内容 |
|---|--------|------|
| 1 | **High** | LD-12 `TYPELESS` チャネル未実装（`DamageType` は PHYSICAL/MAGICAL のみ） |
| 2 | **High** | ペット連携（属性・ヘイト・ダメ補正）完全未実装 |
| 3 | **Medium** | 真のハイブリッド攻撃（物理＋魔法同時1撃）未対応 |
| 4 | **Medium** | タンクの挑発・被ダメ由来ヘイト、獣使いロール未実装（ヘイト倍率1.5のみ） |
| 5 | **Medium** | Ars一部経路（ignite/hex/explosion/solar/lunar/fangs）が対称パイプライン外 |
| 6 | **Medium** | データバージョン・マイグレーション未実装 |
| 7 | **Low** | バニラジャンプクリ×1.5の扱い未定義（C12） |
| 8 | **ドキュメントずれ** | C1b「防具スキルbaseline未結線」は**現状は perk `buffs` → `PerkBuffResolver` → `PlayerDefenseResolver` で結線済み**。`SymmetricCombatService` の「future addend」コメントが古い |

---

## 4. バグ・データ不整合

| # | 深刻度 | 内容 |
|---|--------|------|
| 1 | **Medium** | Editor定数の既定値が出荷YAMLと不一致（例: `weapon-base-formula.b` 既定1000 vs YAML `100`、`level-scaling.per-level` 既定0.05 vs YAML `0.01`）。未設定時の表示・フォールバックが誤解を招く |
| 2 | **Medium** | `StatVocabulary` に `phys-flat-defense` / `magic-flat-defense` が無い。item側は `DefenseStatBridge` で読めるが、スキルツリー `buffs` に書くと **NONEでドロップ** |
| 3 | **Low** | reload後、インベントリ奥の装備は手に取るまで古いステのまま（設計上の制約だが運用バグに見える） |
| 4 | **Low** | Paper `DamageModifier` deprecated（TODOあり） |

---

## 5. Config Editor — ゆがみ・ずれ・同一入力のUI差

### レイアウトずれ

- `tf-dungeon-forms.js` のランプ行ラベルが **`width:140px` インライン**。全体CSSの `.form-label` は **190px** → ダンジョン／モブランプ行だけ縦ラインがずれる。

### 同一概念なのにUIが違う

| 概念 | 差 |
|------|-----|
| カタログID | `catalogItemSuggest` と、候補なし時の素の `textInput` が混在（`tf-crafting-features.js`） |
| Material | `materialInput`（サジェスト）vs `datalist`付き素text vs 素`textInput` |
| 数値 | 共有 `numberInput` と、生の `h("input", {type:"number"})`（`p5-forms` / `forms.js` CMD等） |
| チェック | `checkboxInput` と生 `<input type="checkbox">`（`ars-forms` / `recipes.js`） |
| 色 | `colorPickerInput` と素`textInput`フォールバック（`tf-forms` quality-tiers） |
| ID編集 | `entry-id` 専用クラスと普通の `field-input` が画面ごとに混在 |
| 進行曲線 | skill-expの専用UI（SVG付き）が正。`progression-*` は registry上 `generic`（サイドバー非表示のcompanion）— 直接開くと別UI |

### Editor配線ギャップ（UI→runtime）

- 戦闘定数の一部キー未掲載（上記 §2）
- attribute-map系のドキュメント／schema残骸
- skilltree専用の quality/spread ウィジェット未整備（FACADES）
- `mob-defaults` 非推奨だがファイル残存

---

## 6. ドキュメントの陳腐化（調査上のノイズ）

次は**コードと食い違う**ため、wikiだけ見ると誤診断しやすい。

- 防具スキル防御「未結線」→ perk経由で結線済み
- ヘイト二重計上 → フォークで防止済み
- use-requirements「防具・触媒未実装」→ 実装済み
- unlock/usage 空＝ゲート無効 → skilltree dedicated-effects が正本
- OPEN_DECISIONS の Valhalla hard-depend（LD-10）→ Valhalla除去後の記述が残存

参照ドキュメント:

- `wiki/12-実装状況と注意点.md`
- `docs/OPEN_DECISIONS.md`
- `tools/config-editor/FACADES.md`
- `docs/EDITOR_POWER_ELITEMOBS_PLAN.md`

---

## 優先度の目安（ウォッチリスト）

1. ダンジョンゲート実データ投入（空のまま本番は危険）
2. `magical.scale-with-combat-level` と C2／純魔combat曲線の方針統一
3. 修繕コスト／ペット代替ヘイト
4. Editorラベル幅・定数FIELD_SPECS・死にキー `再生`
5. TYPELESS・ハイブリッド・ドキュメント同期

---

## 主要参照パス

- `TrinityForge/src/main/resources/dungeon/gates.yml`
- `TrinityForge/src/main/resources/combat/damage.yml`
- `TrinityForge/src/main/resources/progression/combat-level.yml`
- `TrinityForge/src/main/resources/progression/use-requirements.yml`
- `TrinityForge/src/main/resources/stats/item-stats.yml`（`再生` キー）
- `TrinityForge/src/main/java/com/trinityforge/combat/SymmetricCombatService.java`
- `TrinityForge/src/main/java/com/trinityforge/combat/PlayerDefenseResolver.java`
- `TrinityForge/src/main/java/com/trinityforge/combat/BleedService.java`
- `TrinityForge/src/main/java/com/trinityforge/listeners/ArmorUseGateListener.java`
- `tools/config-editor/lib/constants.js`（FIELD_SPECS）
- `tools/config-editor/public/js/tf-dungeon-forms.js`（label width:140px）
- `fork-handoff/arspaper/fork/src/main/resources/unlock-gate.yml`
- `fork-handoff/elitemobs/elitemobs-fork/.../TrinityForgeTargetingListener.java`
