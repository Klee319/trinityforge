# コンテンツ設計 草案 — アチーブメントを「指南」にする / 7 本の柱

作成: 2026-07-30 ／ 状態: **草案（未実装・未合意）**
制約: **今の config-editor で編集できる範囲だけで組む**こと。Java 実装が要るものは §9 に隔離した。

---

## 0. 先に潰しておく「設計を壊す前提」4 件

実コードを読んで確認した事実。ここを知らずに書くと草案がまるごと動かない。

| # | 事実 | 出典 | 設計への影響 |
|---|---|---|---|
| 1 | `trigger.type: advancement` は **今の設定では永久に達成不能** | `achievements.yml` の `vanilla-advancements.disabled: true`。`AchievementsConfig` 自身が起動時に WARNING を出す | バニラ進捗を指南の骨格に使えない。**使わない** |
| 2 | `trigger.type: statistic` は **UNTYPED 統計しか受け付けない** | `AchievementsConfig` L349-373（Material/EntityType 修飾が要る統計は読み込み時に skip） | `MINE_BLOCK` `CRAFT_ITEM` `KILL_ENTITY` は**書けない**。使えるのは `MOB_KILLS` `FISH_CAUGHT` `DAMAGE_DEALT` `PLAY_ONE_MINUTE` `ITEM_ENCHANTED` `TRADED_WITH_VILLAGER` `RAID_WIN` 等 |
| 3 | 図鑑のモブ記録は **バニラ EntityType のみ** | `CollectionListener:102` = `event.getEntityType().name()` | 「世界を繋ぐ者を倒した」は図鑑にもアチーブメントにも**直接書けない**。→ **専用ドロップ品を経由する**（§5） |
| 4 | ルートチェストへの独自アイテム注入は **存在しない** | `LootGenerateEvent` は `VanillaItemRemovalListener`（削除）でしか使っていない | Dungeons and Taverns の「旨み」は**バニラ戦利品を図鑑と儀式素材に昇格させる**形で作る（§6） |

**結論**: 指南ツリーの骨格は **`trigger.type: static`（図鑑）** が主、**UNTYPED 統計**が従。
そして図鑑に載せられるのは
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

### 3.2 階梯（9 段・累積 ≈ 1 億ソース）

各段は「前段のアイテム ×n」＋「ソース」で作る。**上の段ほど、燃やしたときの点数効率が上がる**
＝ これが upgrade（cps 上昇）にあたる。

| 段 | カタログID | 儀式コスト(source) | 追加素材 | 燃やした時の点数 | 累積ソース目安 |
|---|---|---|---|---|---|
| 1 | `source_shard` | 100 | 石炭×64 | 500 | 100 |
| 2 | `source_crystal` | 500 | `source_shard`×4 | 3,000 | 600 |
| 3 | `source_core` | 2,500 | `source_crystal`×4 | 18,000 | 3,500 |
| 4 | `source_condenser` | 12,000 | `source_core`×4 | 110,000 | 20,000 |
| 5 | `source_reactor` | 60,000 | `source_condenser`×4 | 700,000 | 110,000 |
| 6 | `source_forge` | 300,000 | `source_reactor`×4 | 4,500,000 | 600,000 |
| 7 | `source_engine` | 1,000,000 | `source_forge`×4 | 30,000,000 | 3,300,000 |
| 8 | `source_singularity` | 3,000,000 | `source_engine`×4 | ― | 18,000,000 |
| 9 | **`infinity_source_core`** | 5,000,000 | `source_singularity`×4 | ― | **≈ 100,000,000** |

- **1 回の儀式コストは 605 万を超えられない**（ジャーの物理上限）ので、7 段以降のコストはここで頭打ちにし、
  残りは「前段アイテム ×4」に寄せてある。7 段以降は**ジャーを 100 個以上並べた炉が必須**になる ＝ 建造が目標になる。
- 「1 億ソース到達」は**統計として取れない**（§0-2）。なので**達成条件は `infinity_source_core` の入手**にする。
  ```yaml
  g2_infinity:
    display-name: "1億のソース"
    icon: NETHER_STAR
    broadcast: true
    trigger:
      type: static
      collection: { scope: item, targets: [infinity_source_core] }
    rewards:
      special: [title_source_lord, particle_source_aura]
  ```
- **貯蔵の upgrade も階梯に混ぜる**: 4 段 `source_condenser` を素材にした
  `source_reservoir`（`sourcejars.yml` に容量 200,000 で追加）を作れるようにすると、
  「ジャーを並べる」から「良いジャーに置き換える」へ進化して building 感が出る。

### 3.3 バランスの当て方

- 生産側の点数は `sourcelinks.yml` の 5 系統（volcanic / mycelial / alchemical / vitalic / botanical）に
  **段ごとに別々の系統を要求する**と、5 種類の農場を全部作らせられる（＝コンテンツ量が「やること」に化ける）。
- 各段の儀式に `use-requirements.yml` でレベル制限を掛けるとスキル進行と噛み合う。

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

1. **`special-rewards.yml`**: titles / particles を 8 個ほど定義（これが無いと §7 が空振り）
2. **`items/catalog.yml`**: ダンジョン印 5 種 + ソース階梯 9 種 + 限定素材 3 種（＝ CMD 自動割当とリソパ生成が走る）
3. **`sourcelinks.yml` / `sourcejars.yml`**: 階梯アイテムの点数と `source_reservoir`
4. **`mob-overrides.yml`**: 束縛者 4 フェーズの数値 + 全ダンジョンの印ドロップ
5. **`collection.yml`**: categories（dungeon / structure / source）+ reward-tiers
6. **`achievements.yml`**: 指南 38 ノード（**最後**。図鑑エントリが確定してから書かないと targets が空振りする）
7. **`role-buffs.yml`**: description/icon の充実と exp-multiplier の段差

⚠ **ArsPaper 側（sourcelinks / sourcejars / threads / thread-sets）の編集は Dev サーバにしか届かない。**
`tools/config-editor` の `deployPaths.arspaper` が Dev_Server だけを指しているため。
`tmp\sync-arspaper-config.cmd`（全サーバ停止が前提）で Main / Resource へ配る必要がある。

---

## 9. 「今はできない」が、入れると効く改善案（要 Java・優先度順）

| # | 提案 | 効果 | 規模 |
|---|---|---|---|
| 1 | **ルートチェスト注入** — `LootGenerateEvent` + `loot/structure-loot.yml`（構造物ごとの `custom:` 追加テーブル） | Dungeons and Taverns の旨みを直接作れる。§6 の迂回策が不要になる | 中（リスナー1本 + config 1枚） |
| 2 | **EM ボスの図鑑エントリ化** — `mob:em:<mobId>` を `CollectionListener` で記録 | 「世界を繋ぐ者 討伐」が図鑑と指南に直接書ける。印アイテムの迂回が不要 | 小 |
| 3 | **累積ソース統計** — プレイヤー PDC に生涯ソース生産量を積み、`trigger.type: stat_tf` で参照 | 「1億ソース」を**数字で**追える。今は最終アイテムでしか表現できない | 中 |
| 4 | **`trigger.type: statistic` の typed 対応** — `statistic-qualifier:` を足して `MINE_BLOCK: DIAMOND_ORE` 等を許可 | 指南の書ける条件が一気に広がる（§0-2 の最大の制約） | 小〜中 |
| 5 | **参加時ナビ** — ログイン時に「未達成の最前線ノード 1 件」だけをチャットへ | wiki を開かせない導線の完成形。GUI を開くより強い | 小 |
| 6 | **`/tf guide`** — アチーブメント GUI を「指南」名義で別コマンド化し、章タブを付ける | 「アチーブメント＝実績」ではなく「攻略チャート」と認識させる | 小 |
| 7 | **図鑑の「未発見ヒント」** — 未取得エントリの lore に入手先ヒントを出す（`collection.yml` に `hint:` を追加） | 図鑑そのものが攻略情報になる | 小 |

---

## 10. 未決事項（要判断）

1. **ソース階梯の 9 段は多すぎないか。** 6 段（累積 60 万）で「1 億」を諦め、
   代わりに「1 億」は**サーバ全体の累計**として運営が手動集計する、という選択肢もある。
2. **束縛者のフェーズ別ロール要求は「ソロ不可」を意味する。** それを是とするか。
   是としないなら defense-rate を 0.75 → 0.5 程度に落として「有利」止まりにする。
3. **スレッド厳選の再抽選儀式は、既存のスレッド在庫の価値を壊す。** 導入時の告知が要る。
4. **図鑑報酬の憲法（縦強化にしない）を維持するか。** 維持しない場合、
   `reward-tiers` に `permanent-buffs` を入れると図鑑が実質必須コンテンツになる。
