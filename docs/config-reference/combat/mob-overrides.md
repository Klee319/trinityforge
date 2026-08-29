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
    ability-damage-scale: 0.70          # 省略可。このダンジョンの技だけに掛かる倍率
    mobs:
      <モブid>:
        display-name: "<モブの表示名>"
        stats: { ... }
        drops: [ ... ]
        vanilla-exp: { ... }
        abilities: [ ... ]
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
- **`ability-damage-scale`**(省略可、2026-08-29 追加) — このダンジョンの**特殊攻撃だけ**に掛かる倍率。
  通常攻撃・HP には効かない。技テンプレ(`combat/mob-abilities.yml`)はダンジョン間で共有されているので、
  「このダンジョンの技だけ弱い」はテンプレの `damage-percent` ではなくここで書く。
  未設定は 1.0。範囲は `(0, 2]`。クォート文字列は弾く。
  **`default` へ書いた値はカスケードしない**(HP/攻撃力の倍率とは違う)。書いたワールドだけが対象。
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
| `max-health-multiplier` | **最大HPの倍率**(2026-08-14 追加)。0 より大きい有限数のみ有効 |
| `attack-power-multiplier` | **攻撃力(`attack.attack-power`)の倍率**(2026-08-14 追加)。0 より大きい有限数のみ有効 |
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

## 倍率キー — 難易度を「相対的な強さ差」で表現する

2026-08-14 追加。`max-health-multiplier` / `attack-power-multiplier` は**絶対値ではなく「元の値の何倍か」**
を書くキーで、どちらも **`stats:` 直下**に書きます(`armor-strength` と同じく、入れ子の中の 1 項目に効くが
親階層に置くキー。難易度は「HP 何倍・攻撃力何倍」の**対**で指定するものなので 2 つを別階層に散らさない)。

```yaml
overrides:
  em_hard_dungeon:
    stats:                        # ← このダンジョンの全モブに効く
      max-health-multiplier: 3.0
      attack-power-multiplier: 2.0
    mobs: {}
```

**絶対値ではなく倍率が要る理由**: ダイナミックダンジョン(プレイヤーが入場時にレベルを選ぶ。396 体中
265 体が `level: dynamic`)では、`max-health: 1200` と絶対値で書くと Lv1 でも Lv100 でも 1200 になり、
選んだレベルへの追従が死にます。倍率なら「**そのレベルで本来決まる強さの N 倍**」という相対差として
成立するので、レベル選択と難易度を両立できます。

### 適用順序(仕様)

1. **絶対値が先** — `max-health` / `attack.attack-power` が書かれていればまずその値で置き換える。
2. **倍率は後** — 1 の結果に倍率を掛ける。つまり併記すると
   `max-health: 1000` + `max-health-multiplier: 3` → **3000**。
3. 絶対値が無ければ、**下位層(ランプ/選んだレベル)が決めた値**に倍率が掛かる。これが本来の使い方。
4. 倍率を書かなければ**掛け算そのものを行わない**(既存 config の挙動は一切変わらない)。

### 注意点 2 つ

- **倍率だけは 4 層のカスケードで「後勝ち」ではなく掛け合わさる。**
  `default` に 1.5、ダンジョンに 2.0 と書けば合計 3.0 倍になります(`resolve` が層ごとに適用するため)。
  裏を返すと **`default` に書いた倍率は全ダンジョンに乗る**ので、ダンジョン間の難易度差だけを付けたい
  なら `default` には書かないこと。
- **上位層の絶対値は下位層の倍率の結果ごと捨てる。**
  ダンジョン全体に `max-health-multiplier: 4` を書いても、そのモブが `mobs.<id>.stats.max-health` で
  絶対値を持っていれば 4 倍した値は上書きされて消えます。
  ただし**出荷 yml でこれに当たるのは 411 体中ごく一部**です(2026-08-14 実測で 23 体 = `default`
  スコープの新規カスタムボス 6 体 + `em_id_binder_of_worlds` の 7 体 + `em_id_enchantment_challenge_*`
  の 10 体)。**残り 388 体は絶対値 HP を持たないので、ダンジョン単位の倍率はそのまま効きます。**
  倍率を入れるために per-mob の記述を触って回る必要はありません。
  なお**この件数は編集で動きます**(同日中に 13 → 23 体へ増えました)。判断の根拠にする前に
  `mobs.<id>.stats.max-health` の実在数を数え直してください(`grep -c "max-health:"` は
  コメントアウト行も拾うので、yml をパースして数えること)。
  かつてここには「出荷 yml の 396 体が per-mob の絶対値を持つ」と書いてありましたが**誤り**です
  (信じると、存在しない問題を回避するために 388 体を不要に触ることになります)。

### 【最重要】TF の倍率の後段に EliteMobs の `healthMultiplier` がもう一段掛かる

このファイルで決まるのは**実 HP そのものではありません**。フォークの
`EliteEntity#setMaxHealth()`
(`fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/mobconstructor/EliteEntity.java:408`)
は `this.maxHealth = calculatedHealth * healthMultiplier;` で、その `calculatedHealth` は同 407 行で
`TrinityForgeIntegration.resolveProfileMaxHealth()`(= ここまでで解決した TF プロファイルの
`max-health`。倍率適用済み)へ差し替えられています。正規化戦闘/フェーズリセット経路の
`setNormalizedMaxHealth()` も同 427 行で同じ形です。つまり:

```
実HP = ランプ(プレイヤーが選んだレベル) × TF の max-health-multiplier × EM の healthMultiplier
```

そして `healthMultiplier` は**モブごとにまったく揃っていません**。2026-08-14 実測で、このファイルの
411 体のうち EliteMobs の `custombosses/*.yml` に対応する 396 体を突き合わせると値は **0.0001 〜 120**
に散っています(最頻は 1.0 が 116 体)。同一ダンジョン内ですら揃っておらず、`em_id_binder_of_worlds` では
**ボス 4 体が 120 / ミニボス 3 体が 1.0** です。

→ **「TF 側で HP を 2 倍にした」は実機の HP が一律 2 倍になったという意味にはなりません。**
難易度を実測で語るときは必ず EM 側の `healthMultiplier` と併せて見てください。

### 不正値の扱い

`0` / 負値 / 非数値 / `NaN` は**警告ログを出したうえで無視**(その項目だけ未指定扱い。他の項目は生きる)。
`0` を弾くのは、`max-health` の 0 が「未設定 = EliteMobs 自身の HP を使う」、`attack-power` の 0 が
「TF の攻撃側を使わない」を意味するため —— `0` 倍は「弱くする」ではなく**設定ごと消える**という
真逆の結果になるからです。

**倍率キーだけはクォートされた数値文字列(`"2.5"`)も弾きます**(2026-08-14)。他の数値キーは文字列も
パースしますが、倍率は config-editor 側のバリデータ(`tools/config-editor/lib/schema.js` の
`isNumber` = `typeof value === "number"`)が文字列を通さないため、Java だけが受け付けると
**手書きで `"2.5"` と書いた yml を editor で開くとファイルごと保存できなくなる**という非対称が
生まれます。狭い側へ寄せても既存設定は壊れません —— 2026-08-14 実測で出荷 yml の倍率キーは 40 件
(scope 直下 18 / per-mob 22)ありますが、**全件が素の数値でクォート文字列は 0 件**です。

なお倍率キーを `attack:` の**中**に書いた場合は、`attack-power-multiplier` / `max-health-multiplier` の
**どちらも**「階層が違う」と警告します(Bukkit は未知キーを黙って捨てるため、警告が無いと無言で不発に
なる。難易度は対で書かれるので、片方しか警告しないと直したつもりでもう片方が不発のまま残る)。

## `ability-damage-scale` — このダンジョンの技だけ弱める/強める

2026-08-29 追加。`display-name` と同じ階層(scope 直下)に書く。`stats:` の中ではない。

```yaml
overrides:
  em_id_enchantment_challenge_2:
    display-name: "エンチャント試練 2"
    ability-damage-scale: 0.70    # shadow_step 1.55 × 0.70 ≒ 通常打相当
```

- エンチャント試練 10 本は出荷値 `0.70`。攻撃力(Lv100 尺度の絶対値)は触らず、技だけ弱めるため。
- 他ダンジョンに書く必要は無い(未設定 = 等倍)。
- editor で空欄にするとキーごと消える。1.0 を書き込んで「打ち消す」用途ではない。

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
