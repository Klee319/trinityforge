# arspaper/loot-tables.yml

出荷config `fork-handoff/arspaper/fork/src/main/resources/loot-tables.yml` のリファレンス。
構造物のルートチェストが生成される瞬間（`LootGenerateEvent`）に割り込んで、
**(1) 既存の戦利品の個数を増やし、(2) 追加報酬を確率で足し、(3) データパックのエンチャント本を取り除く**。

実装は `com.arspaper.loot.LootTableConfig`（パース）と `com.arspaper.loot.LootTableListener`（適用）。

> ⚠ **`pools:` は生成物である。** 中身は `tmp/worldgen/gen_loot_yml.py` が
> `tmp/worldgen/report/loot-tiers.csv` から機械的に書き出す。手で表を足し引きしても
> 次の再生成で消えるので、方針を変えるときはスクリプト側を直して再生成すること。
>
> ⚠⚠ **ただし `tmp/` も `fork-handoff/arspaper/fork/` も `.gitignore` 除外なので、
> 生成スクリプト・格付け CSV・生成された yml のいずれもバージョン管理されていない。**
> クリーンなクローンにも新しい worktree にも存在せず、`git clean` で消える。
> 触る前に実物があるか確かめること。無ければ案1のデータパックを再ダウンロードして
> `structures.json` / `loot_tables.json` から作り直すところからになる。

## トップレベルキー

| キー | 既定 | 意味 |
|---|---|---|
| `enabled` | `true` | このファイルの抽選・増量・除去をまとめて止めるスイッチ。ウォーデンの残響の欠片は `config.yml` の `mob-drops` 側なので影響しない |
| `block-datapack-enchant-books` | `true` | `minecraft` 以外の名前空間のエンチャントを格納した本を、ルート生成時に取り除く |
| `pools.<プールID>` | — | 追加抽選・増量の定義（下記） |

### `block-datapack-enchant-books` の判定基準

**エンチャント名の列挙ではなく、格納エンチャントの名前空間で判定する**
（`LootTableListener#hasNonVanillaEnchant`）。Dungeons and Taverns は `nova_structures:` で
32 種のエンチャントを追加するが、列挙方式にするとデータパック更新で増えた分だけ黙ってすり抜ける。

- **バニラの修繕はここでは落とさない。TF 側が別経路で消している**（2026-08-16 に実装で確認済み）。
  `progression/crafting-features.yml` の `removed-vanilla-items: [ANY:MENDING]` を
  `VanillaItemRemovalListener#onLootGenerate`（`EventPriority.HIGH`）が全ルートテーブルに適用し、
  修繕を剥がした結果エンチャントが 0 になった本は**アイテムごと消える**（`VanillaItemRemover.Verdict.REMOVE`）。
  ArsPaper 側は `EventPriority.NORMAL` なので**必ず TF より先に走る** ＝
  ArsPaper が増量・追加したあとで TF が修繕を掃除する、という順序になっている。
- 除去は **ArsPaper が自前のマナ系エンチャント本を足す前** に走るので、`type: enchant-book` で
  足した本は巻き込まれない。

## `pools.<プールID>` のキー

| キー | 既定 | 意味 |
|---|---|---|
| `tables` | （必須） | 対象ルートテーブル。空だと「書いたのに永久に出ない」ので警告が出る |
| `quantity-multiplier` | `1.0` | 既存の戦利品（バニラ/データパックが生成した分）の個数に掛ける倍率。`1.0`〜`3.0` に丸められる |
| `rolls` | `1` | 抽選回数。各候補について `rolls` 回だけ独立に確率判定する。`1`〜`16` に丸められる |
| `entries` | `[]` | 追加候補 |

`entries` も `quantity-multiplier` も無いプールは完全な no-op なので、パース時に警告が出る。

### `tables` の書き方は 3 通り

データパックが実際に使うキーを事前に知らなくても書けるようにしてある。

| 書き方 | 例 | 当たる条件 |
|---|---|---|
| 葉だけ | `simple_dungeon` | パスの最後の要素が一致（namespace 不問） |
| 完全一致 | `minecraft:chests/simple_dungeon` | `namespace:path` が丸ごと一致 |
| 名前空間ワイルドカード | `nova_structures:*` | その名前空間のルートテーブル全部 |

大文字小文字は無視する。

### `entries` の要素

| キー | 既定 | 意味 |
|---|---|---|
| `type` | `item` | `item` または `enchant-book`（ArsPaper 自前のマナ系エンチャント本を手続き生成する） |
| `item` | — | バニラ Material 名（`DIAMOND`）または `custom:<ID>`。`type: enchant-book` 以外は必須 |
| `chance` | `0.05` | 1 判定あたりの確率。`0.0`〜`1.0` に丸められる |
| `min` / `max` | `1` | 個数の範囲。`max < min` は `min` まで引き上げられる |

`custom:<ID>` は Ars の itemRegistry と TF の `items/catalog.yml` の両方から解決する。
**未登録IDは警告を1回出して黙って飛ばす**（例外もチェストの見た目の変化も無い）ので、
IDを足すときは `tmp/worldgen/verify_ids.py` で存在を確かめてから書く。
`catalog.yml` で `draft: true` のIDは配られない。

## 個数倍率（`quantity-multiplier`）の挙動

- **整数部は確定、端数はその確率で +1。** `1.5` なら 50% で切り上げ、期待値が倍率どおりになる。
  四捨五入にすると 1 個のスタックが `1.5` 倍で**常に**2 個になり、実効 2 倍に化ける。
- 上限は**スタック上限**。チェストの枠は有限なので、それ以上増やしても溢れて消えるだけ。
- 結果は必ず 1 以上。`setAmount(0)` はスタックを消してしまうため、増量処理で戦利品を減らすことはない。
- **複数プールが同じテーブルに当たったら、掛け合わせず最大値を採る。**
  掛け合わせると、あとからプールを 1 つ足しただけで既存の全チェストが黙って倍量になる。
- **追加抽選で足したアイテムには掛からない。** 適用順は「除去 → 増量 → 追加」で固定。

## 現在の構成（2026-08-16 全面改訂）

資源サーバへ入れるデータパック（案1）の構造物を実際にダウンロードして全ルートテーブルを読み、
**既存の戦利品の豪華さ順**に 5 ティアへ割り直した。方針は
「デフォルトの戦利品が豪華なところには豪華な報酬を、微妙なところには微妙な報酬を。
ベースのルートがバニラであっても同じ」。

| プールID | ティア | 表の本数 | 倍率 | 追加報酬の中心 |
|---|---|---|---|---|
| `t5_structures` | 最上位 | 28 | 1.2 | 現実の芯・品質系スレッド・深部の鍵・リセット系・ダイヤブロック |
| `t4_structures` | 上位 | 52 | 1.3 | 現実の芯・品質系スレッド・中位の鍵・モブ素材・金/エメラルドブロック |
| `t3_structures` | 中位 | 86 + 保険1 | 1.5 | 品質系スレッド（薄め）・浅い鍵・モブ素材・鉄ブロック |
| `t2_structures` | 下位 | 94 | 1.8 | モブ素材が主・スレッドは当たり枠・銅ブロック |
| `t1_structures` | 最下位 | 86 | 2.0 | モブ素材とソースの欠片だけ・石炭ブロック |

貧相なチェストほど倍率を大きくして底上げし、豪華なチェストは追加報酬の質で差を付けている。

対象は 346 本。**村・トライアルチャンバー・壺/発掘の 117 本は意図的に対象外**
（無限湧き or 大量にあるので経済が壊れる）。
どの表がどのスコアで何ティアになったかの根拠は `tmp/worldgen/report/loot-tiers.csv`。

### 配らないと決めたもの

- **深淵の合金 / 束縛者の欠片** — ダンジョン主の独占素材という設定を守るため構造物からは出さない。
- **エルダーガーディアン / ウォーデン / エンドラ / ウィザーの素材** — ボス討伐の意味を残すため。
- **データパック由来のエンチャント本** — TF のエンチャント体系と整合しないため（上記スイッチ）。

## 落とし穴

### ⚠ 旧版は実在しない名前空間を対象にしていて 1 度も発火していなかった

2026-08-16 以前は対象を `dungeons_and_taverns:*` と書いていたが、
**Dungeons and Taverns の実際の名前空間は `nova_structures`**。
名前空間を間違えても例外もログも出ず「なぜかチェストに何も入らない」としか観測できない。
`LootTableConfigTest` が案1のデータパック 6 種（`nova_structures` / `incendium` / `structory` /
`structory_towers` / `terralith` / `kaisyn`）それぞれの実在する表IDで当たり判定を固定している。

### ⚠ ティア制へ組み替えたときに旧ハードコードの入手経路が落ちかけた

移行前は「バニラ 15 表にエンチャント本とエンチャント金リンゴ」を Java で直書きしていた。
ティアへ機械的に割り直すと、`ENCHANTED_GOLDEN_APPLE` と
`minecraft:chests/stronghold_corridor`（案1では要塞が `stronghold/*` へ差し替わるため
structures.json に現れない）が黙って落ちる。両方とも復活させたうえで、
`LootTableConfigTest#shippedYamlKeepsLegacyAcquisitionPaths` が 15 表と 2 品を固定している。

## 再生成の手順

```bash
cd tmp/worldgen && python gen_loot_yml.py
```

`loot_tiers.py`（格付け）→ `gen_loot_yml.py`（yml 生成）の順に依存している。
格付けの物差し（S/A/B ラダー）は `make_scale_csv.py` と共有していて、2 本持つと必ずズレるので使い回している。
