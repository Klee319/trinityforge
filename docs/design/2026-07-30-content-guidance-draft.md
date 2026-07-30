# コンテンツ設計 草案 — アチーブメントを「指南」にする / 7 本の柱

作成: 2026-07-30 ／ 改訂: 2026-07-30（ユーザーフィードバック反映）／ 状態: **草案（未実装・未合意）**
制約: **今の config-editor で編集できる範囲だけで組む**こと。Java 実装が要るものは §9 に隔離した。

---

## 0. 設計の憲法 — 「選択肢は多く、しかし億劫にさせない」

これが全体の受け入れ条件。以下の 3 つを同時に満たさない案は、どれだけ面白くても採らない。

1. **一度に見えるゴールは 3 つまで。**
   アチーブメント GUI は「今の自分に開いているノード」だけを明るく出し、先は暗いままにする
   （`parent` による前提縛りが既にこの挙動をする ―― 前提未達成の間は条件を満たしても達成にならない）。
   コンテンツ総量が 10 倍になっても、**プレイヤーの画面上の選択肢は常に 3 前後**に保たれる。
2. **どのノードも「次の一手」が 1 行のコマンドで書ける。**
   `/tf skill mining` のように、読んだ直後に打てるものを lore の最終行に必ず置く。
   置けない＝そのコンテンツは今のプレイヤーには早すぎる、というシグナルとして扱う。
3. **横（選択肢）と縦（到達度）を分ける。**
   横 = 章の中の並列ノード（採取・戦闘・生産・探索のどれから触ってもよい）。
   縦 = 章そのもの（1 章クリアで次章が開く）。**横は多く、縦は細く。**
   「やることが多すぎる」は横が多いせいではなく、**縦と横が混ざって全部が同時に見える**ときに起きる。

---

## 0.5 先に潰しておく「設計を壊す前提」

実コードを読んで確認した事実。ここを知らずに書くと草案がまるごと動かない。

| # | 事実 | 出典 | 設計への影響 |
|---|---|---|---|
| 1 | `trigger.type: advancement` は **今の設定では永久に達成不能** | `achievements.yml` の `vanilla-advancements.disabled: true`。`AchievementsConfig` 自身が起動時に WARNING を出す | バニラ進捗を指南の骨格に使えない。**使わない** |
| 2 | ~~`trigger.type: statistic` は UNTYPED 統計しか受け付けない~~ **→ 2026-07-30 解消** | `statistic-qualifier:` を追加実装（`AchievementsConfig.StatisticQualifier`） | **`MINE_BLOCK: DIAMOND_ORE` / `CRAFT_ITEM: <Material>` / `KILL_ENTITY: ENDER_DRAGON` が書けるようになった。**指南の条件表現が一気に広がる |
| 3 | 図鑑のモブ記録は **バニラ EntityType のみ** | `CollectionListener:102` = `event.getEntityType().name()` | 「世界を繋ぐ者を倒した」は図鑑には書けない。ただし §0.5-2 の解消で **`KILL_ENTITY` 統計なら EntityType 単位で書ける**（EM ボスの個体指定は依然できないので、印アイテム経由は §5 のまま有効） |
| 4 | ルートチェストへの独自アイテム注入は **存在しない** | `LootGenerateEvent` は `VanillaItemRemovalListener`（削除）でしか使っていない | Dungeons and Taverns の「旨み」は**バニラ戦利品を図鑑と儀式素材に昇格させる**形で作る（§6） |
| 5 | **ソースジャーは `source_jar` 1 種類がハードコードされている** | `SourceJarConfig` L82-84 が yml から `source_jar`/`creative_source_jar` の 2 件だけを明示 put。`RitualManager` 3 箇所と `Sourcelink` L133 が `"source_jar".equals(blockId)` 決め打ち。容量も `SourceJar.applyConfiguredCapacity(capacityOf("source_jar"))` で**全ジャー共通の 1 値** | **yml に上位ジャーを足しても、ブロックとして登録されず儀式もソースリンクも見ない＝完全に無意味。**「上位ジャー階梯」は fork 改修が前提（§3.2） |
| 6 | 図鑑は **「入手したことがあるか」しか記録しない**（中身・個数は見ない） | `CollectionService.record` はエントリ ID の集合 | 「1 億入りジャーを持っている」は判定できない。空のジャーを 1 個拾っただけで達成になる（§3.4） |

**結論**: 指南ツリーの骨格は **`trigger.type: static`（図鑑）** と **`statistic`（修飾子付きを含む）** の 2 本。
図鑑に載せられるのは
`item:<catalogId>` / `item:<MATERIAL>`（categories か achievements から参照されている物だけ）/ `mob:<ENTITY_TYPE>` の 3 種。

---

## 1. 指南の核になる考え方 —「lore が wiki」

`achievements.<id>` は `display-name` / `icon` / `lore`（複数行・MiniMessage 可）/ `coords` /
`parent` / `parents-any` / `broadcast` / `rewards` を持つ（`AchievementsConfig` L215-228 で確認）。

つまり **アチーブメント GUI はそのままツリー型の攻略チャートとして使える**。
「wiki を見るのがだるい」への答えは、**wiki の内容を lore に移す**こと。ノード 1 つが wiki 1 項目。

書式ルール（草案として提案する統一規約）:

```yaml
    lore:
      - "<gray>― いま何をする ―</gray>"        # 1 行目: 行動の指示（命令形・1 文）
      - "<white>石を 64 個掘る。</white>"
      - ""
      - "<gray>― なぜ ―</gray>"                # 2 段目: 理由（1 文）
      - "<white>採掘スキルが開くと鉱石の追加ドロップが乗る。</white>"
      - ""
      - "<yellow>次: /tf skill mining</yellow>"  # 3 段目: 次の一手（コマンドをそのまま書く）
```

`rewards.commands` に `tellraw` を仕込めば、達成した瞬間に**次のノードの案内をチャットへ流せる**。
これが「wiki を開かなくても次が分かる」の実体になる。

```yaml
    rewards:
      commands:
        - 'tellraw %player% {"text":"[指南] 次は 第2章「ソースの火を灯す」。/tf achievement で確認","color":"gold"}'
```

---

## 2. 指南ツリーの章立て（全 7 章・38 ノード）

`coords` は `x,y` 形式。章ごとに y 帯を固定して縦に伸ばす。`parent` で 1 本鎖、
分岐合流は `parents-any`。

| 章 | ID 接頭辞 | ノード数 | 到達目安 | 主に使う trigger |
|---|---|---|---|---|
| 序章 はじめの一歩 | `g0_` | 5 | 初日 | 図鑑(item:バニラ) / `PLAY_ONE_MINUTE` |
| 第1章 採取と生産 | `g1_` | 6 | ~Lv20 | 図鑑(item:バニラ・カタログ) |
| 第2章 ソースの火 | `g2_` | 6 | ~Lv40 | 図鑑(item:カタログ) |
| 第3章 ダンジョン入門 | `g3_` | 6 | ~Lv60 | `MOB_KILLS` / 図鑑(mob:) |
| 第4章 厳選と最適化 | `g4_` | 5 | ~Lv80 | 図鑑(item:スレッド) |
| 第5章 終章・世界を繋ぐ者 | `g5_` | 6 | 最高難易度 | 図鑑(item:束縛者ドロップ) |
| 常設 図鑑コンプリート | `gc_` | 4 | 恒常 | 図鑑(scope:all / percent) |

### 序章（そのまま貼れる形）

```yaml
achievements:
  g0_start:
    display-name: "はじめの一歩"
    icon: WOODEN_PICKAXE
    coords: "0,0"
    broadcast: false
    lore:
      - "<gray>― いま何をする ―</gray>"
      - "<white>石を掘り、木を切る。ふつうに始めてよい。</white>"
      - ""
      - "<gray>― なぜ ―</gray>"
      - "<white>採掘・伐採はスキルEXPの主柱。ここが全ての土台になる。</white>"
      - ""
      - "<yellow>次: /tf skill</yellow>"
    trigger:
      type: static
      collection:
        scope: item
        targets: [COBBLESTONE, OAK_LOG]      # ← ここに書くとバニラ品でも図鑑に記録される
    rewards:
      vanilla-exp: 10
      commands:
        - 'tellraw %player% {"text":"[指南] /tf achievement で次の目標が見られる","color":"gold"}'

  g0_role:
    display-name: "生き方を決める"
    icon: IRON_SWORD
    coords: "0,1"
    parent: g0_start
    lore:
      - "<gray>― いま何をする ―</gray>"
      - "<white>/tf role set で戦闘職と補助職をひとつずつ選ぶ。</white>"
      - ""
      - "<gray>― なぜ ―</gray>"
      - "<white>戦闘職は貫通/魔法/ヘイトの性格を決め、補助職はEXP倍率とバフを配る。</white>"
      - "<white>後から変更できる。まずは触ってみること。</white>"
    trigger:
      type: statistic
      statistic: PLAY_ONE_MINUTE
      threshold: 20                          # 20 分プレイ = 実質「開始直後の案内」
    rewards:
      commands:
        - 'tellraw %player% {"text":"[指南] 迷ったら 鉱夫 + 剣闘士。/tf role set","color":"gold"}'
```

以降のノードは同じ型の反復なので、この草案では**表**で示す（実装時に上の形へ展開する）。

### 第1章 採取と生産（`g1_`）

| ID | 表示名 | trigger | 指南内容（lore 要旨） |
|---|---|---|---|
| `g1_ore` | 鉱脈を知る | 図鑑 item: `IRON_INGOT, COPPER_INGOT, GOLD_INGOT` | 鉱石ごとに採掘EXPが違う。`/tf skill mining` |
| `g1_smith` | 最初の鍛冶 | 図鑑 item: 使用可能レベル付きのカタログ武器 1 種 | **鍛冶EXPは「盤面に置いた素材の合計」で決まり、使用可能レベルの無い完成品では入らない**（2026-07-30 実装） |
| `g1_quality` | 品質を見る | 図鑑 item: `custom:<品質付き素材>` | 品質ティアと厳選幅 ±18% の説明 |
| `g1_fish` | 釣り人 | `FISH_CAUGHT` >= 50 | 釣りギミックと `LUCK_OF_THE_SEA` 上限 4 |
| `g1_farm` | 畑を持つ | 図鑑 item: `WHEAT, CARROT, POTATO` | 農業EXPと 農家ロールの HERO_OF_THE_VILLAGE |
| `g1_tree` | 一括伐採 | 図鑑 item: `custom:<斧カタログ品>` | 一括伐採の解放条件と葉ブロック連鎖 |

### 第3章 ダンジョン入門（`g3_`）

`MOB_KILLS`（UNTYPED、使える）と図鑑 `mob:<ENTITY_TYPE>` の組み合わせで組む。
**EM ボス個体は書けない**ので、ボス到達の証明は §5 の専用ドロップ品で行う。

| ID | trigger |
|---|---|
| `g3_kill_100` | `statistic: MOB_KILLS, threshold: 100` |
| `g3_undead` | 図鑑 `scope: mob, targets: [ZOMBIE, SKELETON, HUSK, DROWNED]`（threshold 省略＝4種全部） |
| `g3_nether` | 図鑑 `scope: mob, targets: [BLAZE, WITHER_SKELETON, PIGLIN_BRUTE]` |
| `g3_first_dungeon` | 図鑑 `scope: item, targets: [dungeon_seal_mines]`（§5 のダンジョン印） |
| `g3_dungeon_5` | 図鑑 `scope: item, targets: [dungeon_seal_*×5]`（5 ダンジョン分の印） |
| `g3_deep` | 図鑑 `scope: item, targets: [dungeon_seal_dark_cathedral]` |

---

## 3. 柱①「1 億ソース」強化ループ（クッキークリッカー型）

### 3.1 実機構（確認済み）

- `sourcelinks.yml`（editor: ArsPaper タブ）: `materials:` に `Material名 / custom:<ArsID> / TFカタログID → ソース点` を書く。石炭 ≈ 5 点。
- `sourcejars.yml`: `source_jar` 容量 **10000**、`creative_source_jar` 容量 -1。
- 儀式のソース消費: `RitualManager.consumeSourceFromNearby` は **半径5・高さ±2（11×5×11）内の `source_jar` を全部合算して引く**。
  → **ジャーを並べた「炉」を物理的に建てるほど 1 回の儀式に注げるソースが増える**。理論上限は 605 マス × 10000 = 605 万。
- 儀式レシピは `items/catalog.yml` 側で定義（結果 ID は `tfcatalog:<catalogId>`）。

つまり **生産（sourcelink に燃やす）→ 貯蔵（ジャー増設）→ 消費（儀式）→ 生産効率が上がる** が
config だけで閉じる。これはクッキークリッカーの構造そのもの。

### 3.2 「上位のソース瓶とソースジャーを作る」— 本命の構想と、その障害

ユーザーの意図した形は **貯めたソースを使ったクラフトで上位のソース瓶／ソースジャーを作る**、
つまり **貯蔵容量そのものが upgrade** という構造（クッキークリッカーの building に相当）。これは正しい設計だが、
**今のフォークではそのまま作れない**。

**障害（§0.5-5）**: `source_jar` はブロック実装・儀式・ソースリンクの 4 箇所で **ID が決め打ち**され、
`SourceJarConfig` は yml から `source_jar` / `creative_source_jar` の 2 件しか読まない。容量も
`applyConfiguredCapacity(capacityOf("source_jar"))` で**全ジャー共通の 1 値**。
→ **yml に `source_reservoir` を足しても、置いても何も起きず、儀式もソースリンクも見ない。**

**選べる道は 3 本**:

| 案 | 内容 | できること | コスト |
|---|---|---|---|
| **A. 今すぐ（editor だけ）** | `sourcejars.yml` の `source_jar.capacity` を 10,000 → 1,000,000 に上げる | 規模は**ジャーを何個並べるか**だけで表現。種類は増やせない | 0（1 行） |
| **B. 本命（fork 小改修）** | `SourceJarConfig` を yml の全 jar 対応にし、`RitualManager`/`Sourcelink` の `"source_jar"` 決め打ちを「登録済み jar ID のいずれか」へ、容量を **jar ごと**に | **上位ジャー階梯が成立する**。ユーザー構想そのもの | 小〜中（4 ファイル・約 100 行） |
| **C. ソース瓶（携行容器）** | アイテムに PDC で残量を持たせる新機構。ブロックではないので儀式の吸い出し対象にも新規実装が要る | ソースの持ち運び・取引が可能に | 中（完全新規） |

**推奨: B を先に入れる。** A は「並べる数」だけの単調な拡張で、
§0 の憲法「選択肢は多く」に対して選択肢を 1 つも増やさない。B なら 1 行の改修で
「ジャーを増やす／良いジャーに替える」の 2 軸になり、置き場所の制約（半径 5 = 605 マス）と噛み合って
**限られた床面積に何を置くかというパズル**になる。これが building の面白さの本体。

### 3.3 階梯（B 採用前提・9 段・累積 ≈ 1 億ソース）

各段は「前段のアイテム ×4」＋「ソース」で作る。**貯蔵段（ジャー）と加工段（触媒）を交互に置く**のが要点で、
「容量が足りないから次の段が作れない → ジャーを作る → 次の触媒が作れる」の往復になる。

| 段 | 種別 | ID | 儀式コスト(source) | 追加素材 | 効果 | 累積ソース目安 |
|---|---|---|---|---|---|---|
| 1 | 触媒 | `source_shard` | 100 | 石炭×64 | 燃やすと 500 点（種銭） | 100 |
| 2 | **ジャー** | `source_jar_ii` | 500 | `source_shard`×4 | 容量 50,000 | 600 |
| 3 | 触媒 | `source_crystal` | 2,500 | `source_shard`×8 | 燃やすと 18,000 点 | 3,500 |
| 4 | **ジャー** | `source_jar_iii` | 12,000 | `source_crystal`×4 | 容量 250,000 | 20,000 |
| 5 | 触媒 | `source_condenser` | 60,000 | `source_crystal`×8 | 燃やすと 700,000 点 | 110,000 |
| 6 | **ジャー** | `source_reservoir` | 300,000 | `source_condenser`×4 | 容量 2,000,000 | 600,000 |
| 7 | 触媒 | `source_engine` | 1,000,000 | `source_condenser`×8 | 燃やすと 30,000,000 点 | 3,300,000 |
| 8 | **ジャー** | `source_singularity_jar` | 3,000,000 | `source_engine`×4 | 容量 50,000,000 | 18,000,000 |
| 9 | 目標 | **`infinity_source_core`** | 5,000,000 | `source_singularity_jar`×4 + `source_engine`×8 | 到達証明 | **≈ 100,000,000** |

- **1 回の儀式コストの上限＝半径 5 以内に置いたジャーの合計。** A のままだと 605 マス × 10,000 = 605 万が天井で、
  9 段目（500 万）がぎりぎり。B なら容量段階が効くので、床面積の制約が「良いジャーに替える動機」に変わる。
- **9 段目でジャーを 4 個消費する**のが肝。貯蔵設備そのものを溶かすので、
  「1 億の証明」は文字通り**それまでに積み上げた設備を焚べた結果**になる。

### 3.4 「1 億溜まったジャーをインベントリに入れれば判定」は成立しない

**図鑑は「そのアイテムを入手したことがあるか」しか記録しない**（`CollectionService.record` はエントリ ID の集合。
中身の量も個数も見ない）。したがって **空のジャーを 1 個拾った瞬間に達成**になる。

正しい判定は **§3.3 の 9 段目 `infinity_source_core`**（＝ 1 億を消費しないと存在しないアイテム）。

**使い回し問題について**: これは config だけでは防げない。図鑑は「一度でも所持したか」なので、
作った `infinity_source_core` を他人に渡せば、その人も達成する。取れる手は 3 つ:

| 案 | 内容 | 判定 |
|---|---|---|
| **許容する** | 1 個を回して全員が称号を取る = 協力プレイの一形態 | 報酬が称号/コスメに限定されている（§7 の憲法）ので**実害はほぼ無い**。**推奨** |
| 消費判定 | アチーブメント trigger に `consume-items:` を足し、達成時に手元から消す | 要 Java（小）。回すと最初の 1 人しか取れない |
| 所有者バインド | 儀式の結果に作成者 UUID を PDC で刻み、他人が持っても図鑑に載らない | 要 Java（中）。譲渡不可アイテムという概念が新規に増える |

### 3.5 マルチブロックについて

**実は既に「疑似マルチブロック」が動いている。** 儀式は `ritual_core` を中心に
**半径 5・高さ ±2（11×5×11）の範囲のジャーを全部合算して吸う**ので、
プレイヤーから見れば「コアの周りにジャー群を組んだ 1 つの装置」であり、置き方に意味がある。
**追加実装なしで「炉を建てる」体験は既に成立している** ので、まずはこれを設計上そう呼んで前面に出すのが安い。

本格的なパターン判定（決まった形に組まないと動かない）を入れるなら要 Java（中）。
入れる価値が出るのは **「形ごとに効果が違う」を作るとき**（例: 十字に組むと消費 -10%、塔状に組むと容量 +20%）。
形を 1 種類しか用意しないなら、それは「ジャーを N 個置け」と同じことなので**入れない方がよい**。

### 3.6 バランスの当て方

- 生産側の点数は `sourcelinks.yml` の 5 系統（volcanic / mycelial / alchemical / vitalic / botanical）に
  **段ごとに別々の系統を要求する**と、5 種類の農場を全部作らせられる（＝コンテンツ量が「やること」に化ける）。
  §0 の憲法に照らすと、これは**横の選択肢**（どの系統から手を付けてもよい）なので増やしてよい。
- 各段の儀式に `use-requirements.yml` でレベル制限を掛けるとスキル進行と噛み合う。

---

## 3.7 柱⓪ ネザー／エンドの基準レベル（2026-07-30 実装済み）

**症状**: エンドラを 1 発で倒せた。

**原因**: `combat/mob-types.yml` の全 89 エントリが `level: 0` で、レベルは
`coordinate-coefficient: 0.02`（**そのワールドのスポーン地点からの距離**、`MobTypeSpawnListener#distanceFromWorldSpawn`）
だけで決まる。ところが
- **エンド**: `the_end` のスポーンは主島のすぐそば。エンドラの湧き位置まで約 100 ブロック → **+2 レベル**。
  HP は `2200 × 1.048^2 = 2,420` にしかならず、ネザライト剣（攻撃力 3,780）で**1 発**。
- **ネザー**: 座標が 1/8 スケールなので、オーバーワールドで 8,000 ブロック旅した相当でもネザー内では 1,000 ブロック。
  **距離由来のレベルがほとんど乗らない。**

つまり `coordinate-coefficient` は**オーバーワールド専用の形をした機構**で、他 2 次元では機能していない。

**対応**: 次元専有の EntityType に基準 `level` を入れた（距離加算はそのまま上に乗る。`max-level: 100` の天井も従来どおり）。

| EntityType | level | Lv での HP | Lv での攻撃力 |
|---|---|---|---|
| `PIGLIN` / `ZOMBIFIED_PIGLIN` / `MAGMA_CUBE` | 25 | 約 1,300 | 約 12 |
| `BLAZE` / `WITHER_SKELETON` / `GHAST` / `HOGLIN` / `ZOGLIN` | 35 | 約 1,750 | 約 21 |
| `PIGLIN_BRUTE` | 40 | 約 3,900 | 約 30 |
| `SHULKER` | 45 | 約 3,600 | 約 26 |
| `WITHER` | 55 | 約 29,000 | 約 72 |
| **`ENDER_DRAGON`** | **60** | **約 36,500** | **約 85** |

エンドラ検算: ネザライト剣 3,780 × (1 − 防御率 0.15) ≒ 3,190 → **約 12 発**。
被弾側は 85 − ネザライト一式の守備力 36.8 = 48.2 → 防御率 0.6 → 19.3 → 耐性 0.2 → **約 15 ダメージ／発**
（プレイヤー最大 HP はネザライト一式込みで約 43）。**3 発耐えて 12 発で倒す**、ボスとして妥当な帯。

**除外した EntityType（意図的）**: `ENDERMAN` と `ENDERMITE` は**オーバーワールドにも湧く**ので上げていない。
上げるとオーバーワールドの序盤が破綻する。次元ごとにレベルを変えたい場合は
**`mob-types.yml` にワールドスコープが無い**（EntityType 単位のみ）ため fork/TF 側の小改修が要る（§9-8）。

**厳密には「次元専有」でないもの（レビューで指摘・受容）**: `ZOMBIFIED_PIGLIN` は
オーバーワールドのネザーポータルブロックからも湧く。ただし**中立モブ**（挑発されるまで攻撃しない）で、
かつ湧く条件がプレイヤー自身のポータル設置＝ネザー到達と同じゲートなので、
初期地帯の事故にはならないと判断して level 25 のままにしている。
`HOGLIN`→`ZOGLIN` の変換も、ネザーからホグリンを持ち出す能動的な行為が前提なので同様。
ワールドスコープ（§9-8）を入れたら整理する。

---

## 4. 柱②「世界を繋ぐ者」= 最高難易度（主目標）

ダンジョン `em_id_binder_of_worlds`（世界を繋ぐ者の聖所）。EM モブ 18 体、4 フェーズ構成:

```
em_id_binder_of_worlds_phase_1 / _phase_2 / _phase_3 / _phase_4  (本体)
+ melee/ranged/status ミニボス 3 種、増援 8 種
```

`combat/mob-overrides.yml`（editor: モブオーバーライドタブ）で **ワールドスコープ
`em_id_binder_of_worlds`** に対し、フェーズごとに性格を分けて書く。**これがロールシステムの存在意義になる**
（§5 と一体で設計する）。

| フェーズ | 性格 | 主に効く数値 | 要求されるロール |
|---|---|---|---|
| phase_1 | 物理の壁 | `physical.defense-rate: 0.75`、`magical.defense-rate: 0.10` | **剣闘士**（貫通 0.2）が居ないと削れない |
| phase_2 | 魔法の壁 | `physical.defense-rate: 0.10`、`magical.resistance: 0.70` ＋ 高 `flat-defense` | **魔術師**（割合追加 0.3）が要る |
| phase_3 | 総攻撃 | 増援 6 種が同時湧き、`attack.fixed-damage` を高く | **守衛**（`damage-reduction` + ヘイト 1.5）が居ないと後衛が溶ける |
| phase_4 | 消耗戦 | HP を最大にし、`attack.percent-bonus-damage` を漸増 | 補助職の恒久バフ（HASTE/SPEED/LUCK）と回復が効く |

```yaml
overrides:
  em_id_binder_of_worlds:
    display-name: 世界を繋ぐ者の聖所
    mobs:
      em_id_binder_of_worlds_phase_1:
        display-name: 世界を繋ぐ者 (第2段階)
        stats:
          level: 120
          max-health: 40000
          physical: { defense-rate: 0.75, flat-defense: 400 }
          magical:  { defense-rate: 0.10, resistance: 0.05 }
          attack:   { attack-power: 900, penetration: 0.30 }
        drops:
          - { item: "custom:binder_fragment", chance: 1.0, min: 1, max: 1 }
```

**ドロップの解決は「置換」であって加算ではない**（`MobOverridesConfig` クラス javadoc）。
実ワールド名は `em_id_binder_of_worlds_1` のように連番が付くので、**設計図ワールド名を書くこと**。

---

## 5. 柱③④⑤ — EM 限定ドロップ / ロールの意義 / それを使う制作

### 5.1 「ダンジョンの印」= 図鑑にボスを載せるための代替手段

§0-3 の通り EM ボスは図鑑に載らない。そこで**ダンジョンごとに 1 個だけ確定ドロップする印アイテム**を
`items/catalog.yml` に作り、`mob-overrides.yml` でボスに `chance: 1.0` で付ける。

| catalogId | 出所（ワールドスコープ） | 用途 |
|---|---|---|
| `dungeon_seal_mines` | `em_id_the_mines` | 図鑑・指南の到達証明 |
| `dungeon_seal_quarry` | `em_id_the_quarry` | 同上 |
| `dungeon_seal_nether_bell` | `em_id_the_nether_bell` | 同上 |
| `dungeon_seal_dark_cathedral` | `em_the_dark_cathedral` | 同上 |
| `dungeon_seal_binder` | `em_id_binder_of_worlds` phase_4 | 最終到達証明 |

印は **図鑑カテゴリ `categories.items.dungeon`** にまとめる。これで
「どのダンジョンを踏破したか」が一目で分かる = wiki の踏破表の代わりになる。

### 5.2 高レベル限定の素材（制作の入口）

`mob-overrides.yml` の `level-cutoff.over-level` に `drop-rate` があるので、
**低レベル帯では出ないレア素材**を設計できる。素材は 3 系統に絞ると覚えやすい。

| 素材 | 出所 | 儀式/鍛冶での役割 |
|---|---|---|
| `binder_fragment` | 束縛者 全フェーズ 1.0 | 最終装備の主素材 |
| `reality_thread_core` | 束縛者ミニボス 3 種 0.25 | **スレッドの厳選素材**（§5.3） |
| `abyssal_ingot` | Lv80+ の EM ボス全般 0.10 | 中間装備・`source_engine` の触媒 |

### 5.3 スレッド厳選 ＝ 「旨み」の本体

スレッドは `threads.yml` 定義 + **単体ステは `stats/item-stats.yml` の `MATERIAL#CMD` キー**
（`TIDE_ARMOR_TRIM_SMITHING_TEMPLATE#300002` 等、CMD 300002〜300016）、
セット効果は `thread-sets.yml`。装着枠は `crafting-features.yml` の `thread-slots.max-by-category`（現在 armor/weapon/tool/other 各 5）。

**編集だけでできる厳選要素**:

1. `item-stats.yml` のスレッドキーに `fixed:` ではなく**厳選幅（品質ランダム）を持たせる**
   → 同じスレッドでも当たり外れが出る。
2. `thread-sets.yml` の累積しきい値を **3 個 / 5 個** の 2 段にして、
   「同種 5 個そろえる」＝ 厳選を回す動機にする。
   - ⚠ `armor-defense-rate` はバニラ防具（最大 0.8）に**加算**されるので、
     セット効果でここを盛ると物理無効化が起きる。盛るなら `damage-reduction` / `flat-defense`。
3. `reality_thread_core` を消費する**スレッド再抽選の儀式**を catalog に置く
   （入力: スレッド 1 + core 1 → 出力: 同じスレッド。品質が振り直される）。
   - ⚠ **`recipe.result` を省略すると「自分自身に戻る破壊レシピ」が無警告で登録される**ので、必ず明示すること。

### 5.4 ロールシステムを意味あるものにする（editor だけで）

現状のロールは常時バフでしかない。**意味を与えるのは敵側の数値**（§4 の表）。加えて:

- `role-buffs.yml` の `description` / `label` / `icon` を全ロールに埋め、`/tf role` の GUI を**職業紹介ページ**にする（表示専用なので安全）。
- 補助職の `exp-multiplier` は現在すべて 1.2 で横並び。**1.15 / 1.25 / 1.35 の 3 段**に散らして選択に意味を持たせる。
- 指南の第3章に「ロールを変えて同じダンジョンに入る」ノードを置く（達成条件は 2 個目のダンジョン印）。

---

## 6. 柱⑥ Dungeons and Taverns / 構造物ルートチェスト

**注意: 独自アイテムをチェストへ注入する機構は今は無い**（§0-4）。
それを前提に、**editor だけで「漁る旨み」を作る手段**は 3 つ。

1. **バニラ戦利品を図鑑エントリに昇格させる**
   `collection.yml` の `categories.items.structure.entries` に構造物固有のバニラ品を並べる。
   collection.yml のコメント通り、**ここに書いた Material は図鑑に記録されるようになる**。

   ```yaml
   categories:
     items:
       structure:
         display-name: 遺物
         order: 2
         entries:
           - ECHO_SHARD
           - HEART_OF_THE_SEA
           - NETHERITE_UPGRADE_SMITHING_TEMPLATE
           - TRIAL_KEY
           - OMINOUS_TRIAL_KEY
           - MUSIC_DISC_OTHERSIDE
   ```

2. **その戦利品を儀式素材にする**
   `ECHO_SHARD` → `reality_thread_core` の下位互換、`HEART_OF_THE_SEA` → `source_condenser` の触媒、等。
   「チェストで拾ったゴミが上位素材になる」だけで漁る理由になる。

3. **`economy/villager-trades.yml` で買取先を作る**
   構造物固有品 → ソース系素材への交換レートを置く。ソース経済（§3）と直結する。

Dungeons and Taverns を入れた後は、その固有ブロック/アイテムを 1 と 2 に追記するだけで拡張できる。

---

## 7. 柱⑦ 図鑑コンプリート

`collection.yml` の **`reward-tiers: {}` が空**（＝報酬が一切出ない状態）。ここが最大の未使用資産。

**憲法**（collection.yml 冒頭コメント）: 報酬は **称号 / コスメ / 恒久QoL に限定し、縦強化（戦闘ステ）にしない**。
`reward-tiers` は `items` / `job-exp` / `permanent-buffs` / `vanilla-exp` も受け付けてしまうが、**使わない**。

```yaml
reward-tiers:
  t1:
    threshold: 10
    title: "<gray>収集家</gray>"
    special: [title_collector]
  t2:
    threshold: 50
    title: "<white>探究者</white>"
    special: [title_seeker, particle_sparkle]
  t3:
    threshold: 120
    special: [title_archivist, particle_ember_aura]
    broadcast: true
  t4:
    threshold: 250
    special: [title_worldwalker, particle_void_aura]
    broadcast: true
```

**前提**: `special-rewards.yml` の `titles: {}` / `particles: {}` も**現在すべて空**。
上の ID を先に定義しないと reward-tiers は無言で何も配らない
（しかも `prune-orphaned-grants: true` なので、後から ID を消すと全員から剥奪される）。

図鑑側の指南ノード（`gc_`）:

| ID | trigger |
|---|---|
| `gc_10` | `scope: all, threshold: 10` |
| `gc_25pct` | `scope: all, threshold: 25, percent: true` |
| `gc_dungeon_all` | `scope: category, targets: [dungeon]`（印を全部） |
| `gc_100pct` | `scope: all, threshold: 100, percent: true` / `broadcast: true` |

---

## 8. 導入順（依存関係が壊れない順序）

0. **fork 改修 §9-1（ジャーの yml 駆動化）**。これを入れないと 3 番の上位ジャーが全部無意味になる。
1. **`special-rewards.yml`**: titles / particles を 8 個ほど定義（これが無いと §7 が空振り）
2. **`items/catalog.yml`**: ダンジョン印 5 種 + ソース階梯 9 種 + 限定素材 3 種（＝ CMD 自動割当とリソパ生成が走る）
3. **`sourcelinks.yml` / `sourcejars.yml`**: 階梯アイテムの点数と上位ジャー 4 種
4. **`mob-overrides.yml`**: 束縛者 4 フェーズの数値 + 全ダンジョンの印ドロップ
5. **`collection.yml`**: categories（dungeon / structure / source）+ reward-tiers
6. **`achievements.yml`**: 指南 38 ノード（**最後**。図鑑エントリが確定してから書かないと targets が空振りする）
7. **`role-buffs.yml`**: description/icon の充実と exp-multiplier の段差

⚠ **ArsPaper 側（sourcelinks / sourcejars / threads / thread-sets）の編集は Dev サーバにしか届かない。**
`tools/config-editor` の `deployPaths.arspaper` が Dev_Server だけを指しているため。
`tmp\sync-arspaper-config.cmd`（全サーバ停止が前提）で Main / Resource へ配る必要がある。

---

## 9. Java 実装が要るもの（優先度順）

| # | 提案 | 効果 | 規模 | 状態 |
|---|---|---|---|---|
| ✅ | **`trigger.type: statistic` の typed 対応** — `statistic-qualifier:` | `MINE_BLOCK: DIAMOND_ORE` / `CRAFT_ITEM` / `KILL_ENTITY: ENDER_DRAGON` が書けるようになった。指南の条件表現が一気に広がる | 小 | **2026-07-30 実装済み** |
| ✅ | **ネザー／エンドの基準レベル** | エンドラのワンパンを解消（§3.7） | 小 | **2026-07-30 実装済み**（yml） |
| 1 | **ジャーを yml 駆動にする** — `SourceJarConfig` の 2 件決め打ちを解除し、`RitualManager`/`Sourcelink` の `"source_jar"` 決め打ちを登録済み ID 集合へ。容量を jar ごとに | **上位ジャー階梯が成立する**（§3.2 案 B）。1 億ソース構想の前提 | 小〜中（4 ファイル） | 未着手・**最優先** |
| 2 | **ルートチェスト注入** — `LootGenerateEvent` + `loot/structure-loot.yml` | Dungeons and Taverns の旨みを直接作れる。§6 の迂回策が不要になる | 中 | 未着手 |
| 3 | **EM ボスの図鑑エントリ化** — `mob:em:<mobId>` を `CollectionListener` で記録 | 「世界を繋ぐ者 討伐」が図鑑に直接書ける。印アイテムの迂回が不要 | 小 | 未着手 |
| 4 | **参加時ナビ** — ログイン時に「未達成の最前線ノード 1 件」だけをチャットへ | §0 の憲法「見えるゴールは 3 つまで」を導線側からも担保する。GUI を開かせるより強い | 小 | 未着手 |
| 5 | **累積ソース統計** — PDC に生涯ソース生産量を積む | 「1 億」を数字で追える。今は最終アイテムでしか表現できない | 中 | 未着手 |
| 6 | **`/tf guide`** — アチーブメント GUI を「指南」名義で別コマンド化し章タブを付ける | 「実績」ではなく「攻略チャート」と認識させる | 小 | 未着手 |
| 7 | **ソース瓶（携行容器）** — アイテム PDC に残量を持たせる | ソースの持ち運び・取引 | 中 | 保留 |
| 8 | **`mob-types.yml` にワールドスコープ** | `ENDERMAN` のように 3 次元にまたがるモブを次元ごとに変えられる（§3.7 の残課題） | 小〜中 | 未着手 |
| — | **マルチブロックのパターン判定** | 形ごとに効果を変えるなら価値がある。1 種類しか作らないなら入れない（§3.5） | 中 | 見送り推奨 |
| — | **図鑑の「未発見ヒント」** | ユーザー判断で優先度低（tips はあまり読まれない） | 小 | 見送り |

---

## 10. 未決事項（要判断）

1. **ソース階梯の 9 段は多すぎないか。** 6 段（累積 60 万）で「1 億」を諦め、
   代わりに「1 億」は**サーバ全体の累計**として運営が手動集計する、という選択肢もある。
2. **束縛者のフェーズ別ロール要求は「ソロ不可」を意味する。** それを是とするか。
   是としないなら defense-rate を 0.75 → 0.5 程度に落として「有利」止まりにする。
3. **スレッド厳選の再抽選儀式は、既存のスレッド在庫の価値を壊す。** 導入時の告知が要る。
4. **図鑑報酬の憲法（縦強化にしない）を維持するか。** 維持しない場合、
   `reward-tiers` に `permanent-buffs` を入れると図鑑が実質必須コンテンツになる。
5. **`infinity_source_core` の使い回しを許容するか**（§3.4）。許容が推奨。
6. **ネザー／エンドの基準レベルは実測で詰める必要がある。** §3.7 の数値は装備帯からの計算値であって
   実戦データではない。特にエンドラの攻撃力 85 は、**装備が 1 帯足りないプレイヤーには即死**になる。
