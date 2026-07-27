# combat/mob-overrides.yml

EliteMobs ダンジョンインスタンス内の「カスタムモブ」ごとに、個別の**強さ**(レベル/HP/防御/攻撃)、
**ドロップテーブル**、**レベル依存の経験値の式**を設定できるオーバーライド層。2026-07-26 新設。

> このドキュメントは yml 内ヘッダコメントの正本控えです。config-editor で保存すると yml のコメントは
> 消えるため、消えた場合はここを参照してください(`mob-level-table.md` と同じ扱い)。

## 位置づけ

`combat/mob-profiles.yml` は `/trinityforge importmobs` が生成する**自動生成ファイル**で、手編集は禁止
(再 import で上書きされる)。このファイルはその**上に重ねるオーバーレイ**なので、再 import が走っても
手で調整した値は失われません。

## 階層

```
overrides:
  <ワールド名 または default>:
    display-name: "<ダンジョンの表示名>"
    mobs:
      <モブid>:
        display-name: "<モブの表示名>"
        stats: { ... }
        drops: [ ... ]
        vanilla-exp: { ... }
```

- **ワールド名** — **「設計図ワールド名」**(EliteMobs のダンジョン設定 `worldName:` の値)を書く。
  インスタンスダンジョンの実ワールド名は EliteMobs が入場のたびに `<設計図ワールド名>_<連番>`
  (`em_xxx_1`, `_2`, `_3` …)と番号を増やして作るため、実ワールド名を書くと 1 インスタンスにしか
  当たりません。設計図名を書けばそのダンジョンの**全インスタンス**に当たります。
  照合順は「実ワールド名の完全一致 → 設計図名として照合」(複数該当時はより長いキーが勝つ)。
- **`default`** — 全ダンジョン共通のフォールバック。そのワールド専用の指定が無いモブに使われる。
- **`display-name`**(省略可、2026-07-26 追加) — ダンジョン/モブの**表示名**。config-editor が
  生のワールド名・EliteMobs モブidの代わりに表示するためだけの表示用メタデータで、
  **戦闘の計算にもスコープ解決にも一切影響しない**。EliteMobs 同梱ダンジョンのワールド名とモブidは
  変更するとオーバーライドが当たらなくなるため editor 側で編集不可にしてあり、
  分かりやすい名前を付けたい場合はこの `display-name` を使う。
  出荷 yml では 29 ダンジョン・396 モブすべてに日本語名が入っている
  (訳表は `tools/scripts/em_ja_names.py`、生成は `tools/scripts/gen-mob-overrides.py`)。
- **モブid** — EliteMobs のカスタムボス設定ファイル名(拡張子なし)。`/trinityforge importmobs` が
  `combat/mob-profiles.yml` のキーに使うものと同じ。スポーン時にフォークが PDC
  (`mob_profile_id`) へ刻印した値と照合される。

## 解決優先順位

### 強さ (`stats`) — 項目単位のマージ

書いた項目だけが上書きされ、書いていない項目は下位層の値がそのまま生きます。

1. `overrides.<ワールド>.mobs.<モブid>.stats` (最優先)
2. `overrides.default.mobs.<モブid>.stats`
3. `combat/mob-profiles.yml` の該当エントリ
   (未インポートのモブは `combat/mob-import.yml` の `unknown-mobs.synthesize: true`(既定)により
   スポーン時に実レベルで自動導出される。`false` にしている場合のみ `mob-defaults.yml` 扱い)

### ドロップ (`drops`) — 置換

マージではなく置換。実ワールド指定に `drops:` があればそれを丸ごと使い、無ければ `default` 指定の
`drops:` を丸ごと使います(どちらにも無ければ何も追加しない)。

### 経験値 (`vanilla-exp`) — 置換

ドロップと同じ置換。実ワールド指定に `vanilla-exp:` があればそれ、無ければ `default` 指定、
どちらにも無ければ**何もしない**(バニラ/他プラグインが決めた EXP 量がそのまま残る。0 EXP にはならない)。

## `vanilla-exp` — モブごとのレベル依存 EXP 式

このモブを倒したときに落とすバニラ経験値(オーブ)を、そのモブの**戦闘レベルの式**で決めます。式は
`combat/mob-import.yml` のランプと完全に同じ形:

```
exp(level) = (base + per-level * level) * growth^(level / growth-interval)
```

結果は四捨五入して 0 以上に丸められます。書き方は 2 通り:

```yaml
vanilla-exp: 25                                                        # レベル非依存の固定量
vanilla-exp: { base: 5, per-level: 1.5, growth: 1.03, growth-interval: 1.0 }
```

| キー | 既定 | 内容 |
|---|---|---|
| `base` | 0.0 | レベル 0 での基準値 |
| `per-level` | 0.0 | 1 レベルあたりの線形加算 |
| `growth` | 1.0 | 指数の底。`1.0`(既定)なら純粋な線形 `base + per-level * level` |
| `growth-interval` | 1.0 | 指数の適用間隔。`growth^(level / growth-interval)` |

> **絶対値でなく式である理由**: 導入済みダンジョンモブの多く(396体中265体)は `level: dynamic` で、
> プレイヤーが入場時に選んだレベルに追従します。固定値で書くと Lv1 でも Lv100 でも同じ EXP になります。
> EXP は評価時にキル時の PDC 刻印レベル(`MOB_LEVEL`)を使うため、`dynamic` モブでも正しく追従します。

### `combat/mob-level-table.yml` の `vanilla-exp` との関係

レベル帯単位の `mob-level-table.yml` の `vanilla-exp` は `MobLevelTableListener` (`HIGH`) で、
このファイルの式は `MobOverrideExpListener` (`MONITOR`) で適用されます。両方に該当するモブは
**後に走るこちらの「モブごとの式」が勝ちます**。帯指定は、式を書いていないモブのための広い既定として
そのまま生きます。

## `stats` の項目一覧

すべて省略可。省略した項目は下位層の値のまま。

| キー | 内容 |
|---|---|
| `level` | 戦闘レベル(整数) |
| `max-health` | 最大HP |
| `armor-strength` | 防具強度(会心軽減率%、physical/magical の両方に同じ値が適用される) |
| `physical` / `magical` | 物理/魔法防御の個別項目(下記4項目、省略可) |
| └ `defense-rate` | 防御率% [0,1] |
| └ `resistance` | 耐性% [0,1] |
| └ `damage-reduction` | 被ダメージ軽減% [0,1] |
| └ `flat-defense` | 守備力(固定値) |
| `attack` | 攻撃側の個別項目(省略可) |
| └ `attack-power` | 基礎ダメージ(`mob-profiles.yml`/`mob-types.yml` と同じキー名) |
| └ `flat-bonus-damage` | 固定追加ダメージ |
| └ `percent-bonus-damage` | 割合追加ダメージ% |
| └ `crit-chance` | 会心率 |
| └ `crit-damage` | 会心ダメージ% |
| └ `penetration` | 貫通率% |
| └ `damage-modifier` | ダメージ補正 |
| └ `fixed-damage` | 固定ダメージ(全防御貫通) |

## `drops` の項目一覧

| キー | 内容 |
|---|---|
| `item` | Material 名、または `custom:<items/catalog.yml のID / ArsPaper素材ID>` (必須) |
| `chance` | 1死亡あたりのドロップ確率 [0,1] (必須) |
| `min` / `max` | ドロップ個数の範囲 (両方0以上、`min <= max`、必須) |

## 他のドロップ源との共存

- **`combat/mob-types.yml` の `drops:`** — EliteMobs モブでは発火しません
  (`MobTypeDropListener` は `mob_type_stamped` を要求し、これはバニラフィールドモブのスポーン時にしか
  書かれない)。重複の心配なし。
- **`combat/mob-level-table.yml` の `add-drops`** — EliteMobs モブにも適用されます。
  このファイルのドロップは**独立した追加ドロップ源として加算的に共存**します
  (どちらか一方が他方を消すことはない)。あるモブでオーバーライドのドロップ「だけ」を出したい場合は、
  そのモブをレベル帯の `add-drops mobs:` (EntityType フィルタ)から外してください。

## リスナー優先度

`MobOverrideDropListener` は **`MONITOR`** で走ります。理由は2つ:

1. `MobLevelTableListener` (`HIGH`) の `remove-drops` に刈られないため。
2. EliteMobs 自身の `LootTables#onDeath` が非バニラ戦利品モブの `getDrops()` を丸ごとクリアするため。
   このクリアは EliteMobs の `NORMAL` 優先度 `EntityDeathEvent` リスナーから同期的に発火する
   `EliteMobDeathEvent` の内側で起きるので、`MONITOR` なら確実に生き残ります。

## 後方互換

`overrides:` が空(既定の `overrides: {}`)なら、このファイルは一切何もしません。強さ・ドロップ・EXP とも
`combat/mob-profiles.yml` 由来の挙動を完全に維持します。

## 記入例

```yaml
overrides:
  default:
    mobs:
      goblin_chief:
        stats:
          level: 50
          max-health: 1200
        vanilla-exp:
          base: 5
          per-level: 1.5
          growth: 1.03
          growth-interval: 1.0
        drops:
          - item: "custom:source_gem"
            chance: 0.25
            min: 1
            max: 3
  my_dungeon_world:
    mobs:
      goblin_chief:
        stats:
          max-health: 2000
```
