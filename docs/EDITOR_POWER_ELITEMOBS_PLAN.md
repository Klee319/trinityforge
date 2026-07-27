# Config Editor / Skills / EliteMobs — 調査メモ (2026-07-21)

調査時点の現状と次アクション。実装が進んだら本ファイルと `tools/config-editor/FACADES.md` を更新する。

---

## 1. Config Editor — 専用 GUI 化

### 方針
インデント型汎用ツリー (`buildGenericEditor`) のままの config を、アイテムカタログ級の専用フォームへ置き換える。

### フェーズ

| Phase | 対象 | 状態 |
|---|---|---|
| **1** | `dungeon-gates`, `dungeon-themes`, `mob-profiles`, `mob-import`, `hate-rates` | **完了** (`tf-dungeon-forms.js`) |
| **2** | `gathering`, `mining/woodcutting/farming/food/fishing-gimmick`, `villager-trades`, `role-buffs` | **完了** (`tf-lifestyle-forms.js`) |
| **3** | `skilltree-dedicated-effects`, `ars-config`, `ban`, `skills-ars-magic`, `skills-ars-smithing` + `glyph-unlock-items` 登録(2026-07-23廃止) | **完了** (`tf-phase3-forms.js`) |

### 弄れない／弱いもの（監査）

| 状態 | 対象 | 備考 |
|---|---|---|
| 定数ビューのみ・一部キー不可 | `combat/damage.yml` の `attack-stat-keys` / `defense-stat-keys` など | `/api/constants` の FIELD_SPECS 外 |
| 非推奨孤児 | `combat/mob-defaults.yml` | `mob-types` defaults へ移行済み想定 |

### 既に専用／スプリット済み（参考）
catalog / item-stats / lore / skilltree×15 / crafting-features / quality* / skill-exp / mob-types / gacha / Ars materials·threads·spellbooks·glyphs·gates·sources など。

---

## 2. POWER（総合）— Valhalla バニラ残存

### 現状（2026-07-21 更新）
- **本命完了:** `skilltree/power.yml` — TF 縦トランク（活力主軸 + 足元/天恵/持久/回収/俊敏）。攻撃・防具倍率なし。プレステージなし
- Config Editor: `skilltree-power` 登録済み
- `skills/base/power_progression.yml` は experience / starting_perks / leveling SP の SoT。perk 木は deploy 時に tree merge で上書き
- standalone deploy は tree が無いスキル向けフォールバック（POWER は tree があるので通常 merge）
- ついで: light/heavy weapons・archery の **starting/leveling** `*damagemultiplier*` も base から除去済み

### 衝突ポイント（要約・対応状況）
| ソース | 内容 | 状態 |
|---|---|---|
| starting | critdamage / bleed / oneshot | **除去済み** |
| Physique | `attackdamagemultiplier` | **持久 (hungersave) に置換** |
| Iron Skin | `armorbonus` | **足元強化 (knockback resist) に置換** |
| perk UI | Valhalla 横並び木 | **`skilltree/power.yml` で TF 縦トランク化** |

### 推奨実装順
1. ~~**即時:** `skills/base/power_progression.yml` からダメージ層を除去し、deploy 経路に載せる~~ **完了**
2. ~~**本命:** `skilltree/power.yml` 新設~~ **完了**
3. ~~ついでに武器スキル base の `*_damagemultiplier` leveling も掃除~~ **完了**（perk 内 native のダメ倍率は各 skilltree 側の別タスク）

関連: `docs/VALHALLA_DEFAULT_SKILLS.md`, `docs/OPEN_DECISIONS.md` (G3/LD-9), `ValhallaCritSuppressListener`

---

## 3. EliteMobs — 実装アプローチ（GUI 完了後）

### 既にできていること
- EM → TF 物理 flat（`physicalFinalDamageFlat` + `AttackStats.plain(0)`）
- spawn 時 defense PDC stamp（`TrinityForgeSpawnListener`）
- `importmobs` + themes、共有 `gates.yml`（中身は空 = fail-open）
- バニラ `mob-types` の attack stamp → `physicalFinalDamageFromMob`
- **EM attack プロファイル（2026-07-21 実装）**: `mob-import.yml attack:`（8ステ ramp）→ `mob-profiles.yml attack:` → spawn stamp（`MOB_ATTACK_*`）→ TF `CombatListener` が近接を所有。fork 側は stamp 済み近接を二重適用しないようスキップ（projectile/explosion は従来 flat 委譲のまま）
- **mob → player 魔法（2026-07-21 実装）**: TF `magicalFinalDamageFromMob`（mob level スケール + attack-power 置換）と `magicalFinalDamageFlat`（EM 済算ベース向け）。EM script `DAMAGE` アクション = アビリティを `TrinityForgeAbilityDamage` マーカーで識別し、fork listener が MAGICAL flat へ委譲（魔法耐性が効く）。TF 側は `MobAbilityDamage` マーカーで物理 mob-melee 経路が stand down

### 足りないこと
- Ars を非プレイヤー詠唱者に載せる経路（B3・大工事、後回し）
- `gates.yml` の実データ（運用データ投入）
- テーマ別バランス（数値調整）
- fork の実機結合確認（jar は `libs/TrinityForge.jar` 更新済み・compile 確認済み）

### 推奨方針

| 目的 | 方針 |
|---|---|
| **オリジナルダンジョン** | EM content-package + ボス／スクリプトが本体。TF は gates + themes + importmobs。TF 独自インスタンスは作らない |
| **敵の魔法** | ~~EM アビリティ見た目 + ダメを TF 魔法経路へ（B1）~~ **実装済み**。Ars 実詠唱（B3）は後回し |
| **既存ダンジョン性能** | 守備 stamp 済み・攻撃 `attack:` stamp 済み。残りは数値投入（mob-import.yml → importmobs 再実行） |

### 実装順の目安
1. `gates.yml` 中身投入（エディタ Phase1 済み）
2. ~~EM attack プロファイル（importer + spawn + combat listener）~~ **完了**
3. ~~`magicalFinalDamageFromMob` + EM 能力配線~~ **完了**（B1: script DAMAGE = 魔法扱い）
4. テーマ別バランス（数値調整・実機確認）

関連: `fork-handoff/elitemobs/00_HANDOFF.md`, `docs/DUNGEON_SPEC.md`, `wiki/09-モブとダンジョン.md`

---

## 4. 次アクション

1. ~~Phase 2 専用 GUI 実装~~ **完了**
2. ~~Phase 3 + 未登録穴埋め~~ **完了**
3. ~~POWER（LD-9 scrub + `skilltree/power.yml`）~~ **完了**
4. ~~EliteMobs 攻撃／魔法~~ **完了**（残: gates 実データ・テーマ別バランス・実機結合）
