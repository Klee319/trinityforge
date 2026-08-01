# 追加コンテンツ 詳細プラン（2026-08-01）

> 前提となる制約は `docs/design/2026-07-30-content-guidance-draft.md`（設計の憲法・§0.5 の技術制約・
> §10 の確定事項）と `tmp/decisions.md`（装備シリーズ方針・ダンジョンの鍵・破棄項目）。
> **本書は「何を・いくつ・どの数値で作るか」だけを書く。**制約の再説明はしない。
>
> 数値の出典はすべて 2026-08-01 の棚卸し（R1 装備 / R2 素材経済 / R3 ダンジョン・ロール / R4 スレッド厳選）。
> **`items/catalog.yml` と `stats/item-stats.yml` は他セッションが未コミットで改修中**なので、
> 本書の実装は**その WIP が commit されてから**着手する。

---

## 0. 着手順を変える発見（プランより先に直すもの）

| # | 事実 | 影響 |
|---|---|---|
| **0-1** | `DungeonGateService.checkRequiredEntry` は**ゲート未設定のダンジョンを一般プレイヤーに拒否する**（`trinityforge.admin` / `trinityforge.elitemobs.commands` のみ素通り）。`dungeon/gates.yml` は `gates: {}` で**0件** | **一般プレイヤーは EM 経由でどのダンジョンにも入れない。**鍵コンテンツは「追加」ではなく**復旧**。柱1を最優先にする理由 |
| **0-2** | `effect-type: thread_slot_expand` の儀式レシピが**リポジトリ全体で0件**。効果クラス・登録・TF側 API・PDC 加算はすべて完成済み | **「装備を育てる」機構がフルスタックで実装済みなのにレシピ1本を書いていないだけで死んでいる。**K-18 の最短解 |
| **0-3** | `combat/stat-caps.yml` は `stat-caps: {}` で空。厳選 15 キーは**全部「書けば効く」**（K-2 の ATTRIBUTE 制約は厳選にかからない） | 攻撃側 8 キーが**完全に青天井**。19枠フル厳選で crit-chance 171% / penetration 171% / attack-power 20,520 に届く |
| **0-4** | `mob-overrides.yml` 全体（30ワールド・**405モブ**）で `level:` / `max-health:` / `attack:` の出現が**0件** | 束縛者だけでなく**全ダンジョンが共通ランプ任せ**。K-22(1) は束縛者固有の問題ではない |
| **0-5** | `abilities:` の出現は `default` 9件と束縛者 7件のみ。**他24ダンジョンのボスは特殊攻撃を1つも持たない** | ダンジョンごとの手触りが「耐性の数値」だけで決まっている |
| **0-6** | `wood-repair.materials` のキー `compressed_wood_1x` が**どの yml にも存在しないID**（実在は `oak_wood_1x`） | 木材修理が永久に発動しない。1語の修正 |
| **0-7** | `farming-gimmick.yml` に `drop-tables` セクション自体が存在しない | 農業だけドロップ経路の機構ごと未設定＝**最大の空き経路** |

**0-6 / 0-7 は本プランの実装中に一緒に直す。0-1 は柱1そのもの。**

---

## 1. 柱1 — ダンジョンの鍵（`gates.yml` を埋める）

`DungeonGateConfig` のスキーマは**すでに鍵を書ける**（`key-item` / `key-amount` / `required-combat-level` /
`content-package` / `entry-location`）。二相評価・二重消費防止・鍵右クリック→潜入GUI・fail-open も実装済み。
**足りないのは設定だけ。**

### 1-1. 鍵アイテム 19 種（新規）

すべて ArsPaper `materials.yml` に定義（印と同じ層に置く。base `TRIAL_KEY` / CMD 5501–5519）。
**印（`dungeon_seal_*`）は鍵ではない。**印は踏破の証で、`recipe:` を持たない。

配分の根拠 = **入手経路の空きを埋める向きに割る**。棚卸しで判明した密度は
クラフト87件・EMボス112エントリに対し、**村人2・釣り3・採掘3・伐採1・掘削1・農業0・儀式6**。
鍵をルート限定にすることが、そのまま空き経路の解消になる。

| # | ダンジョン | 鍵ID | 入手 | 経路の内訳 |
|---|---|---|---|---|
| 1 | 鉱山 | `key_mines` | **クラフト** | `iron_ingot_scrap`×4 + `stone_2x`×1 + `IRON_INGOT`×2 |
| 2 | 深層鉱山 | `key_deep_mines` | **クラフト** | `key_mines`×1 + `copper_ingot_scrap`×4 + `AMETHYST_SHARD`×3 |
| 3 | 採石場 | `key_quarry` | **クラフト** | `stone_3x`×2 + `iron_ingot_scrap`×6 |
| 4 | 灼熱の洞窟 | `key_cave` | **クラフト** | `netherrack_2x`×1 + `blaze_rod_1x`×2 + `MAGMA_BLOCK`×4 |
| 5 | エンチャント試練10 | `key_enchant_trial` | **クラフト** | `LAPIS_BLOCK`×4 + `BOOK`×4 + `source_gem`×1 |
| 6 | 古橋の聖所 | `key_bridge` | **採掘ギミック 2.0%** | `mining-gimmick.yml` の新カテゴリ `dungeon_keys` |
| 7 | ドワーフの地下都市 | `key_city` | **採掘ギミック 1.5%** | 同上 |
| 8 | 蒸気機関工房 | `key_steamworks` | **採掘ギミック 1.0%** | 同上 |
| 9 | 登攀路 | `key_climb` | **掘削ギミック 2.0%** | `digging-gimmick.yml`（現在 `thread_empty` 1件のみ） |
| 10 | 宮殿 | `key_palace` | **掘削ギミック 1.5%** | 同上 |
| 11 | 下水道迷宮 | `key_sewer_maze` | **掘削ギミック 1.0%** | 同上 |
| 12 | 騎士団の城 | `key_knight_castle` | **釣り（宝）weight 3** | `fishing-gimmick.yml`（宝比率5%） |
| 13 | 闇の大聖堂 | `key_dark_cathedral` | **釣り（宝）weight 2** | 同上 |
| 14 | ネザーの鐘 | `key_nether_bell` | **ガチャ tier2 weight 6** | `gacha.yml` |
| 15 | ネザーの荒野 | `key_nether_wastes` | **ガチャ tier3 weight 6** | 同上 |
| 16 | 花火工房 | `key_fireworks` | **ガチャ tier1 weight 4** | 同上 |
| 17 | ハロウィン闘技場 | `key_hallosseum` | **戦利品**（ルートチェスト mid 4%） | `loot-tables.yml` |
| 18 | 北極ミニダンジョン | `key_north_pole` | **戦利品**（ルートチェスト shallow 5%） | 同上 |
| 19 | 世界を繋ぐ者の聖所 | `key_binder` | **合成（印から）** | §1-3 |

**クラフト5 / 採掘3 / 掘削3 / 釣り2 / ガチャ3 / 戦利品2 / 合成1。**
ユーザー方針「一部はクラフト、多くはルート限定」に対し **5 : 13 : 1**。
クラフト側を序盤5種に寄せたのは、**鍵が無いと1つも入れないため入口が必ずクラフトで開く必要がある**から。

### 1-2. `gates.yml` の書き方（19件すべて同型）

```yaml
gates:
  em_id_the_mines:
    content-package: "the_mines"
    required-combat-level: 0
    key-item: "custom:key_mines"
    key-amount: 1
```

`required-combat-level` は**全ダンジョンで 0（無効）にする**。EM 側に「最低レベル未満は入場拒否」が
存在せず、`contentLevel: -1` の level-sync が入場後の強さを合わせるため、**レベルで弾く必要が無い**。
鍵の入手難度が実質のゲートになる。例外は束縛者（`contentLevel: 50`、level-sync なし）で
`required-combat-level: 40` を置く。

### 1-3. 束縛者の鍵 — 用途ゼロだった印18種の出口

**`items/material-lists.yml` に互換リスト `dungeon_seals` を定義**し（19種の印を全部メンバーにする）、
鍵のレシピで `list:dungeon_seals` を **5個** 要求する。

```
key_binder = list:dungeon_seals ×5 + custom:abyssal_ingot ×2 + custom:reality_thread_core ×1
```

**「19種すべて」ではなく「任意5種」にするのが要点。**憲法③「横は多く、縦は細く」に従い、
どのダンジョンから攻めても束縛者に到達できる。これで**用途ゼロだった印18種に一斉に出口ができる**
（棚卸しの用途ゼロ30件のうち18件がここで解消）。

### 1-4. K-22(2) — 第1目標が実質最後になる件

`achievements.yml` の `goal_worldbinder` の `parent` を **`delve_all_seals`（19種すべて）から
`delve_relics` へ戻す**。`delve_all_seals` は**収集系の別ノードとして残す**（図鑑コンプ側の枝）。
これで「名目上の第1目標が実際は最後に解ける」が消える。

`goal_worldbinder` の `permanent-buffs`（`percent-bonus-damage: 5.0` / `damage-reduction: 3.0`）は
**変更しない**。縦強化を持つ唯一のノードであることは `ShippedAchievementTreeTest` が固定している。

---

## 2. 柱2 — 束縛者を実際に最強にする（K-22(1)）

`mob-overrides.yml` は 405 モブすべてで `level` / `max-health` / `attack` が未設定＝共通ランプ任せ。
共通ランプは `mob-import.yml` の `max-health {base:150, growth:1.072}` / `attack-power {base:7.0, growth:1.03}`。

**束縛者だけに明示値を置く。**他24ダンジョンは共通ランプのままにする
（全ダンジョンに手を入れると level-sync との相互作用が読めなくなるため、まず1点だけ動かして実機で見る）。

| 段階 | max-health | attack-power | 根拠 |
|---|---|---|---|
| 第2段階 (`phase_1`) | 共通ランプ ×2.5 | ×1.3 | 「ここから別格」を最初の一撃で伝える |
| 第3段階 (`phase_2`) | ×3.0 | ×1.4 | 魔法寄り（既存の耐性配分を維持） |
| 第4段階 (`phase_3`) | ×3.5 | ×1.5 | |
| 最終 (`phase_4`) | **×6.0** | **×1.8** | 「ソロで頑張ればぎりぎり行ける」の上限 |
| ミニボス ×3 | ×1.8 | ×1.2 | |
| 増援 ×10 | **変更しない** | 変更しない | 増援は数で圧をかける役。個体を強くすると事故る |

**倍率で書くのは、`contentLevel: 50` に対する共通ランプの実値（HP ≈ 4,851 / attack ≈ 30.7）が
level-sync と個体ばらつき ±15% を挟むため。**確定値ではなく初期値として置き、
§10-4 の確定方針どおり**実機校正で詰める**。

**`fixed-damage` は使わない**（§10-3 のユーザー確定）。難度は HP と手数で作る。

### 2-1. 他24ダンジョンのボスに `abilities` を配る（0-5）

出荷済みの11テンプレート（7型）を、ダンジョンの属性配分に合わせて**最終ボスにだけ**割り当てる。

| 属性配分 | 対象ダンジョン | 割り当てる ability |
|---|---|---|
| 物理寄り（物理df .418） | 鉱山 / 深層鉱山 / 採石場 / 地下都市 / 騎士団の城 / 蒸気機関工房 / 花火工房 | `shockwave` + `bull_rush` |
| 魔法寄り（魔法df .418） | 灼熱の洞窟 / ネザーの鐘 / 闇の大聖堂 / ハロウィン闘技場 | `piercing_beam` + `withering_aura` |
| 均等（.266/.266） | 古橋の聖所 / 登攀路 / 宮殿 / 下水道迷宮 | `call_the_horde` + `crippling_stomp` |
| 低難度 | エンチャント試練10 / 北極 / ネザーの荒野 | `frost_field` のみ（1つ） |

**ミニボス・雑魚には付けない。**「ボスだけが技を撃つ」という手触りを守る。

---

## 3. 柱3 — スレッド厳選の「育てる」側（K-18 / K-19 / K-20）

### 3-A. editor と yml だけで届く範囲（**先にこれを全部やる**）

#### A-1. 枠拡張儀式のレシピを1本書く（0-2）

`ThreadSlotExpandRitualEffect` は完成済み・登録済み・TF側 API も PDC 加算も配線済み。**レシピが0件なだけ。**

```yaml
# fork: items.yml
thread_slot_expand_ritual:
  effect-type: thread_slot_expand
  effect-params:
    max-slots: 2            # 1装備あたり累計 +2 まで
  # core-item は書かない（任意の装備を置ける）
  pedestal-items:
    - "custom:source_engine ×1"
    - "custom:reality_thread_core ×2"
    - "custom:abyssal_ingot ×1"
  source: 25000
```

**「装備を育てる」軸がこれ1本で復活する。**コア（装備そのもの）は消費されない。
上限を `max-slots: 2` にしたのは、天井19枠に対して+2×装備5個で最大29枠になると
セット効果の予算が壊れるため（§3-A-4 と合わせて調整する）。

#### A-2. `reality_thread_core` を振り直し儀式へ入れる（K-20 解消）

現行の `thread_reroll` は `source_gem ×4 + AMETHYST_SHARD ×4 + source 2000` で、
**説明・ドロップ配線・テクスチャまで「振り直しの触媒」として用意された `reality_thread_core` を
1個も使っていない。**

```yaml
thread_reroll:
  pedestal-items:
    - "custom:reality_thread_core ×1"   # 追加
    - "custom:source_gem ×4"
    - "AMETHYST_SHARD ×4"
  source: 2000
```

これで**深部ダンジョン周回と厳選周回が接続する**（芯の入手は要塞/洋館2%・バスティオン/古代都市/
エンドシティ6%・デタパ3%・束縛者ミニボス25%）。テスト鯖なので既存在庫への配慮は不要（2026-08-01 確定）。

#### A-3. 一括分解を Java 変更なしで通す

`DisassemblyListener` は**カタログIDを優先照合する**ので、`crafting-features.yml` の
`disassembly` に `thread_*` ルールを足すだけで成立する。

```yaml
disassembly:
  thread_common:                     # 並/希 の不要スレッド
    materials: ["custom:thread_empty ×1"]
  thread_epic:                       # 極/神
    materials: ["custom:thread_empty ×1", "custom:source_gem ×1"]
```

分解先を `thread_empty` にすると**引く→捨てるのループが閉じる**（`thread_empty` は
スタックするのでインベントリを食わない）。金床を落とすだけで複数スタック同時処理できる既存挙動に乗る。

#### A-4. `combat/stat-caps.yml` を埋める（K-19 / 0-3）

19枠フル厳選の理論最大に対して上限が無いのは攻撃側8キー。**防御側は `damage.yml` の
既存上限が最後の砦になっているので、攻撃側だけ書けば足りる。**

| キー | 19枠フル厳選の理論最大 | 提案する cap | 根拠 |
|---|---|---|---|
| `attack-power` | 20,520 | **16,000** | `binder_sword` 43,470 に対し厳選寄与 37%。`thread-rolls.yml:26` の想定17%と現状47%の中間 |
| `crit-chance` | 1.71 | **0.75** | 率として意味が壊れない上限。会心特化ビルドは届く |
| `crit-damage` | 3.42 | **2.50** | |
| `penetration` | 1.71 | **0.60** | 貫通1.0超は守備力機構そのものを無効化する |
| `percent-bonus-damage` | 1.71 | **1.00** | |
| `flat-bonus-damage` | 102.6 | **60** | |
| `bleed-chance` | 2.05 | **0.60** | |
| `bleed-damage` | 2,736 | **1,500** | |

**すべて初期値。**実機で「特化ビルドが cap に当たって伸びない」と感じたら上げる方向で調整する。

#### A-5. `thread-sets` の死に値を直す

`hero_of_the_village` の `attack-power +1.0 / +2.0` は、**厳選1本の主ステ最小値 200 の 1/100 以下**で
完全な死に値。`conduit_power` / `night_vision` の flat 2.0/3.0 も同様に「4個捧げる対価」になっていない。

| id | 現行 | 変更後 |
|---|---|---|
| `hero_of_the_village` | attack-power +1.0 / +2.0 | **+400 / +900** |
| `night_vision` | flat-bonus-damage +2.0 / +3.0 | **+8 / +18** |
| `conduit_power` | magic-flat-defense +2.0 / +3.0 & damage-reduction +0.02 | **+12 / +26** & +0.02 |

加えて**しきい値を上げる**。天井19枠に対し 6+6+4=16枠で3系統フル発動が成立してしまい、
「1種に寄せるほどその系統のビルドになる」という設計意図（枠9前提の時代の値）が機能していない。

- 3/6 段の系統 → **4/8 段**
- 2/4 段の系統 → **3/6 段**（ただし `max: 1` の `spell_cost_down` は 2/4 のまま。到達不能になるため）

### 3-B. Java 追加が要る範囲（**A を実機で確かめてから判断する**）

| # | 要素 | 追加箇所 | 規模 |
|---|---|---|---|
| B-1 | **ロック付き振り直し** | `ThreadRerollRitualEffect#execute` に `effect-params.lock-main` / `lock-subs` を追加し、別レシピとして登録 | 小。`effect-params` の器は既にある |
| B-2 | **スコア表示** | `ThreadRoll#lore` に合計評価値の行を足す。重み表は `thread-rolls.yml` へ | 小。抽選ロジックに触らない |
| B-3 | **部位別の主ステ固定** | `ThreadRollConfig#roll` に部位引数を通し、`thread-rolls.yml` に部位別の主ステ表を足す | 中 |
| B-4 | **強化レベル +0→+20** | `ThreadRoll` レコードにレベル欄／PDC 形式 `"<rarity>\|<main>\|<subs>"` に枠を追加（**フォーマット変更＝既存個体の移行が要る**） | 大 |
| B-5 | **サブステ4本の段階解放** | B-4 と同じ器の上に乗る（強化レベルで解放） | 大（B-4 前提） |

**推奨: B-1 と B-2 だけ入れて止める。**B-4/B-5 は PDC フォーマットの変更を伴い、
「引く→捨てる」ループの解消には A-1（枠拡張）と A-3（一括分解）で十分届く。
テスト鯖なので移行は不要だが、**B-4 は 16種を40種へ増やす作業（§5-3）と PDC を取り合う**ので、
やるなら同時にやる。

---

## 4. 柱4 — 用途ゼロ30件の解消

棚卸しで確定した内訳は **印18 / コア4 / 圧縮の袋小路5 / その他3**。

| 素材 | 現状 | 出口 |
|---|---|---|
| `dungeon_seal_*` 18種 | 用途ゼロ | **§1-3 の `key_binder`**（`list:dungeon_seals` ×5） |
| `tf_core_wood` | 用途ゼロ・**ガチャ standard の天井報酬** | §5-2 単発「樵の大斧」＋「森人の外套」 |
| `tf_core_meat` | 用途ゼロ・**ガチャ tier2 の天井報酬** | §5-2 単発「饗宴の胸当て」＋「猟師の投槍」 |
| `tf_core_vegetable` | 用途ゼロ・**ガチャ tier1 の天井報酬** | §5-2 単発「豊穣の鍬」＋「香草の帽子」 |
| `tf_core_dirt` | 用途ゼロ | §5-2 単発「地脈のシャベル」＋「岩盤の具足」 |
| `stone_5x` | 袋小路 | §5-1 熾鉄シリーズの素材 |
| `emerald_block_4x` | 袋小路 | §5-1 熾鉄シリーズの素材 |
| `netherite_block_1x` | **1段のみ・袋小路** | §5-1 熾鉄シリーズの主素材 |
| `breeze_rod_3x` | 袋小路 | §5-1 氷芯シリーズの素材 |
| `wither_rose_2x` | 袋小路 | §5-1 氷芯シリーズの主素材 |
| `tf_scrap` | **入手だけあって出口ゼロ**（村人が鉄12→3で吸う純粋シンク） | 鍵のクラフト素材の代替枠 ＋ 村人 `TOOLSMITH` に「`tf_scrap`×8 → `iron_ingot_scrap`×2」を追加 |
| `tf_crystal_apple` | 食べるだけ | **変更しない**（消費アイテムとして正しい） |
| `infinity_source_core` | 到達証明 | §6（意図的に用途ゼロのまま。設置で機能させる） |

**「1素材1用途」を避ける方針に従い、コア4種はそれぞれ武器/ツール1本＋防具1点の2用途を持たせる。**

さらに**真の単一用途12件**のうち、醸造も持たない完全な行き止まり3件を解消する。

| 素材 | 現状 | 追加する用途 |
|---|---|---|
| `pillager_plate` | `tf_core_vegetable` のみ | 醸造 `survivor-brew` → `FIRE_RESISTANCE` |
| `piglin_ear` | `tf_core_meat` のみ | 醸造 `hunter-hex` → `NAUSEA` |
| `skeleton_horse_bone` | `tf_core_vegetable` のみ | 醸造 `apex-brew` → `SPEED` |

---

## 5. 柱5 — 新シリーズ2本と単発装備8点

**tier の上限は上げない。**新シリーズは既存最上位帯（Lv100）に置き、**コンセプトで差別化する。**

### 5-0. 既存シリーズの位置（剣を代表に、attack-power）

| シリーズ | Lv | attack-power | thread枠 | コンセプト |
|---|---|---|---|---|
| `nuclear` 星枢 | 80 | 12,600 | 2 | デメリット付き（move-speed −0.018 / dodge −0.02） |
| `hero` 守護者 | 100 | 23,520 | 3 | 中庸 |
| `abyss` 深淵 | 100 | 30,240 | 3 | **貫通 +0.06** |
| `infinity` | 100 | 37,800 | **0** | 素の火力最上位・厳選できない |
| `binder` 束縛者 | 100 | **43,470** | 3 | 唯一の縦強化帯 |

**空いているコンセプト**: 会心特化 / 状態異常特化 / 手数特化 / 耐久・維持コスト特化。
このうち**状態異常**と**耐久・維持コスト**を新シリーズ2本で埋め、
**会心**と**手数**は単発装備と既存の武器種差（弓 crit .18 / 鎌 speed 4.12）で足りるので作らない。

### 5-1. 新シリーズ①「氷芯」`cryocore_*` — 状態異常特化 / Lv100 / 全13武器種

単発火力を捨てて、出血と継戦で殺す型。**攻撃力は infinity の 70%。**

| 項目 | 値 | 備考 |
|---|---|---|
| `attack-power`（剣） | **26,460** | infinity 37,800 × 0.70 |
| 全13武器種の型 | **既存の共通型をそのまま使う**（attack-speed / reach / crit / penetration / damage-modifier） | R1 の武器種表のとおり。シリーズで変えるのは量キーだけ |
| 追加 `bleed-chance` | **+0.12** | 全武器種に一律。トライデント/鎌は既存 0.16 に加算されて 0.28 |
| 追加 `bleed-damage` | **+250** | |
| `thread-slots` | **2** | binder/abyss の 3 より1つ少ない（厳選で伸ばす型ではない） |
| `durability` | 既存最上位と同等 | |

レシピ（`method: ritual` / 13種共通の型）:

```
core-item     : custom:wither_rose_2x ×1        ← 袋小路の解消
pedestal-items: custom:breeze_rod_3x ×2         ← 袋小路の解消
                custom:hard_metal ×4
                custom:elder_guardian_spike ×2  ← ドロップ素材の用途を分散
source        : 45000
```

**`wither_rose_2x` を core にしたのは、`wither_rose_1x` が既に19用途で過密な一方、
2x が完全な袋小路だったため。**圧縮の段を1つ進めるだけで上位素材になる導線を作る。

### 5-2. 新シリーズ②「熾鉄」`emberforge_*` — 耐久・維持コスト特化 / Lv100 / 全13武器種＋防具4部位

**火力は infinity の 85%（剣 32,130）だが、耐久が3倍で修理素材が安い。**
金ティア（`damage-modifier` 0.95–0.98／耐久19／極端な博打レンジ）が既に
「コンセプト差別化」の実例になっているので、その逆側＝**安定と維持**を担う。

| 項目 | 武器 | 防具（helmet 基準） |
|---|---|---|
| `attack-power`（剣） | **32,130** | — |
| `durability` | **既存最上位の 3.0 倍** | 同 |
| `random`（厳選幅） | **±5%**（他シリーズ ±18% に対し極小） | 同 |
| `thread-slots` | **2** | **2** |
| `armor-strength` | — | **0.19**（infinity 0.21 / wither 0.18 の間） |
| `phys-flat-defense` | — | **15.0**（infinity 16.68 の 90%） |
| `damage-reduction` | — | 0.02 |
| 判定スキル | — | **HEAVY_ARMOR** |

レシピ（武器）:

```
core-item     : custom:netherite_block_1x ×1    ← 1段しかない袋小路の解消
pedestal-items: custom:stone_5x ×1              ← 袋小路の解消
                custom:emerald_block_4x ×1      ← 袋小路の解消
                custom:hard_metal ×6
source        : 40000
```

**袋小路3件をまとめてここで消化する。**`emerald_block_3x` が5種のコア全部の共通素材＝
エメラルドが全コアの律速という単一依存があるので、**4x を別ラインへ逃がすことで依存を割る。**

### 5-3. 単発装備8点（シリーズ化しない）— コア4種の出口

| 装備 | 種別 | Lv | コア | 特徴（既存キーのみで表現） |
|---|---|---|---|---|
| 樵の大斧 | 大斧 | 60 | `tf_core_wood` ×1 | attack-power 4,200 / **伐採EXP+15%**（`use-skill: WOODCUTTING` は付けない — 斧で殴って伐採EXPが入る罠を踏むため、EXP倍率ステで表現する） |
| 森人の外套 | チェスト | 60 | `tf_core_wood` ×1 | LIGHT / dodge +0.05 / move-speed +0.015 |
| 饗宴の胸当て | チェスト | 45 | `tf_core_meat` ×1 | HEAVY / max-health +4.0 / **食料節約率** |
| 猟師の投槍 | 槍 | 45 | `tf_core_meat` ×1 | attack-power 2,800 / attack-reach +1.2（既存槍の 1.00 より長い） |
| 豊穣の鍬 | ツール | 30 | `tf_core_vegetable` ×1 | **農業EXP+20%** / 範囲収穫の CT 短縮 |
| 香草の帽子 | ヘルメット | 30 | `tf_core_vegetable` ×1 | LIGHT / 満腹度回復ボーナス |
| 地脈のシャベル | ツール | 30 | `tf_core_dirt` ×1 | **掘削EXP+20%** / `thread_empty` ドロップ率 +2% |
| 岩盤の具足 | ブーツ | 30 | `tf_core_dirt` ×1 | HEAVY / knockback-resistance +0.10 |

**コア1種につき「武器/ツール1点＋防具1点」の2用途。**単一用途を作らない方針に従う。
Lv は 30/45/60 に散らし、**Lv100 帯を増やさない**（tier の上限を上げない方針）。

### 5-4. スレッド 16種 → 40種は**今回やらない**

`ThreadType` が **Java の enum（`ThreadType.java:17-59`）**なので、
**yml だけでは増やせずフォークの再ビルドが必須**。しかも同じ16種が3層
（enum / `threads.yml` / `catalog.yml`）に独立に書かれており、増やすと3層すべてに手が要る。

**先に §3-A（枠拡張・振り直し・一括分解・stat-caps・セット効果）で「今ある16種を回す価値」を作る。**
種類を増やすのはその後。増やすなら **B-4（強化レベル）と同時**にして PDC 変更を1回で済ませる。

---

## 6. 柱6 — `infinity_source_core` を「置いて機能させる」

2026-08-01 確定「使い回し許容・工夫があるとなお良し」への回答。

**炉（儀式コア＋半径5のジャー群）の中心に据えると、周囲のジャー容量と転送レートに補正が乗る。**
判定用アイテムとしては消費されないまま（＝使い回し可）だが、**持ち歩くより置いた方が得**になるので死蔵しない。

- マルチブロックのパターン判定は作らない（§10-2 の確定）。**半径判定だけ**で成立する。
- 補正値は §柱7 のソースリンク config 化（現在 Ars レーンで実装中）と同じキーで表現する。
- 代案（採らないが記録）: 累計ソース量で見た目と称号が育つ / 設置者以外も恩恵を受ける。

---

## 7. 柱7 — ロール限定コンテンツ（K-17）

ロールを読むのは**ロール自身の管理系6ファイルだけ**で、コンテンツ側からの参照はゼロ。
棚卸しが出した最小改修 A〜E のうち、**B（装備使用）と C（ドロップ）だけ入れる。**

- **B が最小コストで最大**: 呼び出し元（近接/弓/ツール/`ArmorUseGateListener`/Ars 触媒詠唱）は
  `UseRequirementService` に**一本化済み**なので、`use-role:` を1箇所足せば全経路に効く。
- **C も前例がある**: `mob-level-table.yml` の `add-drops` は既に `mobs:` / `mob-ids:` という
  エントリ単位フィルタを持つので、同じ形で `roles:` を足すのが最小。
- **A（ダンジョン入場）は入れない。**鍵で既にゲートが成立しており、
  ロールで入場を縛ると「そのロールでないと遊べないダンジョン」になって憲法③に反する。
- **D（レシピ/儀式）と E（ガチャ等）は入れない。**D はフォーク改修必須、
  E は3箇所で別々にパースしていて共通ヘルパーが無く重複実装になる。

### 7-1. ロール専用装備 8点（`use-role` の最初の使い所）

各ロールに1点ずつ。**ステは既存の単発装備と同等**にし、
**「そのロールでないと装備できない」ことだけを差にする**（縦強化にしない）。

| ロール | 装備 | 効果 |
|---|---|---|
| `swordfighter` 剣闘士 | 闘士の帯 | penetration +0.04 |
| `mage` 魔術師 | 詠唱者の指輪（触媒枠） | mana-cost-reduction-percent +0.06 |
| `tank` 守衛 | 挑発の紋章 | hate 係数 +0.3 / damage-reduction +0.02 |
| `farmer` 農家 | 実りの籠 | 農業ドロップ +10% |
| `fisher` 漁師 | 潮見の浮き | 釣り宝比率 +2% |
| `miner` 鉱夫 | 坑夫の灯 | 採掘ギミック発動率 +1% |
| `digger` 土工 | 均しの手袋 | 掘削ギミック発動率 +1% |
| `woodcutter` きこり | 樵の砥石 | 一括伐採 CT −15% |

**ロール変更CTが120分**あるので、これらは「今日はこの職で遊ぶ」を選ぶ理由になる。

---

## 8. 空き経路の埋め方（まとめ）

棚卸しの経路密度に対し、本プランが何をどこへ置くか。

| 経路 | 現状 | 本プランの追加 |
|---|---|---|
| **農業ギミック** | **`drop-tables` セクション自体が存在しない** | セクションを新設し、`tf_core_vegetable` の素材と「豊穣の鍬」を配る（0-7 の解消） |
| 村人取引 | 2件（`tf_scrap` / `tf_core_jewelry`） | `TOOLSMITH` に `tf_scrap` の出口を追加。**`LIBRARIAN` の `trades: []` + `block-vanilla-trades: true` は司書が何も売買できない状態なので、他セッションの WIP が確定してから直す** |
| 釣り | 3件 | 鍵2種 |
| 採掘ギミック | 3件 | 鍵3種 |
| 掘削ギミック | 1件 | 鍵3種 |
| 伐採ギミック | 1件 | `tf_core_wood` の素材 |
| 儀式 | 6件 | 新シリーズ2本（26種）＋枠拡張＋鍵1種 |
| ルートチェスト | 9件 | 鍵2種 |

---

## 9. 実装順（依存関係が壊れない順）

| 段 | 内容 | 依存 |
|---|---|---|
| **1** | **`gates.yml` を19件埋める＋鍵19種を定義**（柱1） | **他セッションの `catalog.yml` WIP 確定待ち。これが済むまで誰もダンジョンに入れない** |
| 2 | `wood-repair` の `compressed_wood_1x` → `oak_wood_1x`（0-6）／`farming-gimmick.yml` に `drop-tables` 新設（0-7） | なし。単独で入る |
| 3 | `stat-caps.yml` を埋める（A-4）／`thread-sets` の死に値としきい値（A-5） | なし |
| 4 | 枠拡張レシピ（A-1）／振り直しに芯を追加（A-2）／一括分解（A-3） | フォーク（Ars レーンの完了待ち） |
| 5 | 束縛者の HP/attack（柱2）／他24ボスへ `abilities`（2-1） | `mob-overrides.yml` の WIP 確定待ち |
| 6 | `goal_worldbinder` の parent を戻す（1-4） | 段1 |
| 7 | 新シリーズ2本 26種（柱5-1 / 5-2） | `catalog.yml` / `item-stats.yml` の WIP 確定待ち |
| 8 | 単発装備8点（5-3）＋醸造3件（柱4） | 段7 |
| 9 | `use-role` / ドロップの `roles:`（柱7）＋ロール専用装備8点 | Java 追加。段8 の後 |
| 10 | 芯を炉の中心で機能させる（柱6） | フォーク。ソースリンク config 化の完了待ち |
| — | **やらない**: スレッド40種化（5-4）／強化レベル B-4・B-5（3-B） | 判断待ち |

---

## 10. 本プランが意図的に採らなかった案

| 案 | 不採用の理由 |
|---|---|
| 鍵を「前のダンジョンの印」から作る連鎖 | 19段の縦になる。憲法③「縦は細く」に反する。任意5種の互換リストで横にした |
| 束縛者の鍵に印19種すべてを要求 | K-22(2) と同じ「第1目標が最後になる」構造を再生産する |
| 新シリーズの tier を Lv100 超へ | 「tier の上限を上げて差別化しない」ユーザー方針に反する |
| 会心特化シリーズ | 弓（crit .18）と鎌（speed 4.12）が既に武器種として担っている。シリーズを増やすより単発で足す方が幅が出る |
| ダンジョン入場のロール条件（改修A） | 「そのロールでないと遊べないダンジョン」になる。憲法③に反する |
| レシピ/儀式のロール条件（改修D） | フォーク改修必須で、`use-role`（改修B）で同じ体験を作れる |
| ガチャ/採掘/釣りのロール条件（改修E） | 3箇所で別々にパースしており共通ヘルパーが無い＝重複実装 |
| 図鑑 `reward-tiers` に `permanent-buffs` | 2026-08-01 ユーザー確定「図鑑は縦強化にしない」を維持 |
| 強化レベル +0→+20 を今入れる | PDC フォーマット変更を伴う。A-1（枠拡張）と A-3（一括分解）で「引く→捨てる」の実害は解消する |
