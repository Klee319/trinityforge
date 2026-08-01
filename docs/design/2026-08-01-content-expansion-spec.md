# 追加コンテンツ 確定仕様（2026-08-01）— 柱5 / 柱6 / 柱7

`2026-08-01-content-expansion-plan.md` は方針と代表値までを決めた計画書で、
**実装に必要な粒度（アイテムID・CMD・13武器種ぶんの実数値・レシピ・入手経路）が未確定**だった。
本書がその粒度を埋める。**数値が計画書と食い違う場合は本書が正**（理由は §0 に書く）。

対象は計画書の柱5（新シリーズ2本＋単発装備8点）・柱6・柱7（ロール装備8点）。
柱1・柱3-A は実装済み、柱2・柱4 は別レーンで進行中。

---

## 0. 計画書からの変更点（実測して分かった2件）

### 0-1. 熾鉄防具の `phys-flat-defense` を「infinity の 90%」にしてはいけない

計画書 §5-2 は熾鉄防具の `phys-flat-defense` を 15.0（infinity 16.68 の 90%）としていたが、
**これは耐久ラダーを壊す。** `armor-ladder.test.js` と同じ式で Lv100 帯を実測した結果:

| `phys-flat-defense` 倍率 | 合計(最大ロール) | 耐久発数 | 判定 |
|---|---|---|---|
| ×1.10 | 138.70 | **Infinity（無敵）** | ★逸脱 |
| ×1.05 | 132.39 | 81.6 | ★逸脱 |
| **×1.00（infinity 実値）** | **126.09** | **20.7** | **OK（目標20発）** |
| ×0.98 | 123.57 | 15.9 | ★逸脱 |
| ×0.95 | 119.79 | 11.8 | ★逸脱 |
| **×0.90（計画書の値）** | **113.48** | **8.3** | **★逸脱** |

減算式（`(攻撃力 − 固定防御) × 倍率`）なので 0 に近づくと発散する。
**×0.98 ですら 20.7→15.9 発に落ちる。** 計画どおり 0.90 にすると
「耐久特化のはずの熾鉄が Lv100 で最も脆い防具」になり、コンセプトが逆転する。

**さらに厳選幅 ±5%（計画書 §5-2）も同じ理由で単独では使えない。**
現行 infinity は `fixed` 合計 111.20 に対し最大ロール 126.09（＝ロールが約13%上乗せする）。
±5% にすると最大ロールが 116.8 前後まで落ち、これは上表の ×0.92 相当＝**9.4発**になる。

**確定方針:**

- 熾鉄防具の **`phys-flat-defense` は infinity と完全に同値**（`fixed`・`random` とも）。
  部位別: HELMET 16.68 / CHESTPLATE 44.48 / LEGGINGS 33.36 / BOOTS 16.68（合計 111.20、比 0.15/0.40/0.30/0.15）。
- 熾鉄の識別は**減算されないキーだけ**で行う: `durability` ×3.0 / `damage-reduction` +0.02 /
  `thread-slots` 2 / 修理素材の安さ。
- **厳選幅 ±5% は `phys-flat-defense` 以外のキーにだけ適用する。**
  `phys-flat-defense` の `random` は infinity と同値のまま置く。

> **この4ステ（`phys-flat-defense` / `armor-defense-rate` / vanilla armor points / `max-health`）で
> 強弱を付けようとしないこと。** 乗算式の `phys-resistance` は安全（×1.30 でも全9帯 20.0〜22.4 に収まる）。

### 0-2. 「13種共通のレシピ」は成立しない

計画書 §5-1 / §5-2 はシリーズごとにレシピを1本だけ書いていたが、
**`RitualRecipeRegistry#findMatch` は `findFirst`** なので
`(core-item, pedestal-items)` が完全一致するレシピは**最初の1本しか成立しない**。
13種すべてに同じ材料を書くと、**12種が永久にクラフト不可**になる（無言・エラーなし）。

既に深淵シリーズがこの規約を確立しているので、**同じ差別化素材をそのまま使う**（§1 の表）。
新シリーズは core-item が異なるので深淵とは自動的に衝突しない。
`ShippedRitualRecipeUniquenessTest` が実装後に衝突ゼロを保証する。

### 0-3. 単発装備の攻撃力とリーチは、実装時に同レベル帯のピアへ合わせて引き下げた（2026-08-02）

§4 の表を書いた時点では同レベル帯の実測値を持っていなかった。実装時に測ったところ:

| 項目 | 表の値 | 同レベル帯のピア（実測） | 採用値 | 理由 |
|---|---|---|---|---|
| `hunter_javelin` attack-power | 2,800 | `DIAMOND_SPEAR`(Lv45) = 878.22 | **1,010** | 2,800 は Lv60 の `NETHERITE_SPEAR`(2,634.66) すら超える。ピアの約1.15倍に置いた |
| `hunter_javelin` attack-reach | +1.2 | 出荷全体の**最大が 1.0**（槍系） | **1.4**（絶対値） | +1.2 は全体最大の2倍超。1.4 なら「game 内で最長」を保ったまま尺度内 |
| `woodsman_greataxe` attack-power | 4,200 | `netherite_greataxe`(Lv60) = 5,947.9 | 4,200（表のまま） | ピアの71%。ユーティリティ寄りの斧として妥当 |

### 0-4. Lv30 の防具段は存在しない（防具2点だけ Lv35 へ寄せた）

§4-2 の「同レベル帯のバニラ相当部位と同値にする」を満たそうとすると、
出荷の防具ラダーの段が **Lv25 / 35 / 45 / 60** しかないことに突き当たる（Lv30 の段が無い）。
Lv30 用に `phys-flat-defense` を新規に作ると §0-1 の発散リスクに触れるため、
**防具2点（`herb_hat` / `bedrock_greaves`）だけ Lv35 とした**。
ツール2点（`harvest_hoe` / `leyline_shovel`）は防具ラダーに関わらないので Lv30 のまま。
結果の分布は **30 / 35 / 45 / 60** で、「Lv100帯を増やさない」という §4 の狙いは満たしている。

### 0-6. 柱7 の効果2件は既存の仕組みに無かったので置き換えた（2026-08-02）

| 表の効果 | 実在するか | 採用した効果 |
|---|---|---|
| `taunt_crest` の「hate係数 +0.3」 | **ヘイト（敵対度）の機構自体が無い** | `damage-reduction` +0.02 に `knockback-resistance` +0.05 を足した（前に出る役の性格は残る） |
| `pit_lamp` の「採掘ギミック発動率 +1%」 | 発動率を外から動かすステが無い | `mining-fortune` +0.01 |
| `leveling_gloves` の「掘削ギミック発動率 +1%」 | 同上 | `suspicious-respawn-chance` +0.01（怪しいブロックの復活 — 掘削の稼ぎ口そのもの） |
| `woodsman_whetstone` の「一括伐採CT −15%」 | **`tree-fell-cooldown-reduction` が実在した** | そのまま採用 |

### 0-7. 装飾品2点はオフハンド装備にした

`chanter_ring` / `woodsman_whetstone` は `AMETHYST_SHARD` で、防具スロットに入らない。
`PlayerStatAggregator` が読むのは **防具4部位・メインハンド・オフハンド** だけなので、
インベントリに入れているだけではステが一切効かない（＝完全な死にアイテムになる）。
`offhand-stats-apply: true` を立てて **オフハンドに持つと効く装飾品**とした。

### 0-5. 「伐採EXP +15%」等は専用ステキーを新設して表現した

§4 の「必ず守る2点」の1点目（`use-skill` を分類マーカーに使わない）を満たす手段が
既存語彙に無かった（`skill-exp-bonus` は全スキル一律）。
`woodcutting-exp-bonus` / `farming-exp-bonus` / `digging-exp-bonus` の3キーを新設し、
`NativeProgressionService` が付与先スキルごとに引くようにした（`PerSkillExpBonus`）。
全スキル一律ぶんとは**加算**で合成する。

---

## 1. 13武器種の型（既存シリーズから実測。新シリーズはこの型に量キーを載せるだけ）

剣を 1.0 とした `attack-power` 比と、シリーズ間で不変の形状キー。
**`penetration` は infinity（中庸）の値を採る** — 貫通は深淵のコンセプトなので新シリーズでは伸ばさない。

| 武器種 | 材料 | 比 | speed | reach | crit% | critD | pen | dmg-mod | 衝突回避素材 | source |
|---|---|---|---|---|---|---|---|---|---|---|
| 剣 sword | `NETHERITE_SWORD` | 1.0000 | 1.6 | 0.0 | 0.08 | 0.50 | 0.05 | 0.65 | —（基準） | 30,000 |
| 短剣 dagger | `NETHERITE_SWORD` | 0.6800 | 2.05 | -0.45 | 0.12 | 0.55 | 0.04 | 0.50 | `FLINT x1` | 15,000 |
| レイピア rapier | `NETHERITE_SWORD` | 0.7800 | 1.78 | 0.10 | 0.07 | 0.45 | 0.20 | 0.56 | `FEATHER x1` | 30,000 |
| 槍 spear | `NETHERITE_SPEAR` | 0.6970 | 0.71 | 1.00 | 0.05 | 0.48 | 0.24 | 0.59 | `STICK x2` | 15,000 |
| 三叉槍 trident | `TRIDENT` | 0.9000 | 0.48 | 0.50 | 0.06 | 0.50 | 0.20 | 0.62 | `PRISMARINE_SHARD x1` | 30,000 |
| 鎌 scythe | `NETHERITE_HOE` | 0.7600 | 4.12 | 0.10 | 0.06 | 0.45 | 0.06 | 0.53 | `HAY_BLOCK x1` | 45,000 |
| メイス mace | `MACE` | 1.2500 | 0.408 | 0.0 | 0.06 | 0.55 | 0.08 | 0.65 | `HEAVY_CORE x1` | 75,000 |
| 戦鎚 warhammer | `NETHERITE_SWORD` | 1.5375 | 1.003 | 0.22 | 0.05 | 0.70 | 0.10 | 0.84 | `IRON_BLOCK x1` | 60,000 |
| 大剣 grate_sword | `NETHERITE_SWORD` | 1.4000 | 0.952 | 0.25 | 0.05 | 0.58 | 0.06 | 0.72 | `NETHERITE_SCRAP x1` | 75,000 |
| 斧 axe | `NETHERITE_AXE` | 1.4875 | 0.493 | 0.05 | 0.06 | 0.60 | 0.08 | 0.81 | `COPPER_INGOT x2` | 30,000 |
| 大斧 greataxe | `NETHERITE_SWORD` | 1.7000 | 0.833 | 0.28 | 0.04 | 0.72 | 0.08 | 0.90 | `NETHERITE_SCRAP x2` | 60,000 |
| 弓 bow | `BOW` | 1.0957 | 4.0 | 0.0 | 0.18 | 0.72 | 0.10 | 0.65 | `STRING x2` | 45,000 |
| 弩 crossbow | `CROSSBOW` | 0.9200 | 4.0 | 0.0 | 0.11 | 0.85 | 0.13 | 0.72 | `TRIPWIRE_HOOK x1` | 15,000 |

**`use-skill` は武器種ごとの既存規約に従う**（剣・短剣・レイピア・三叉槍などは `LIGHT_WEAPONS`、
戦鎚・大剣・斧・大斧・メイスは `HEAVY_WEAPONS`）。**深淵シリーズの同武器種からそのまま写すこと。**
`use-level-requirement` は両シリーズとも **100**、`quality-mode-offset` は深淵と同じ **-8**。

**`per-quality` は `attack-power × 0.0476`**（深淵 1440/30240 の実比）、
**`random` は `min = -attack-power × 0.16` / `max = +attack-power × 0.20`**（深淵の実比）。
crit-chance / crit-damage / penetration の per-quality と random も深淵の同武器種から写す。

---

## 2. 柱5-1 ── 氷芯 `cryocore_*`（状態異常特化 / Lv100 / 13武器種）

`attack-power` 基準 **26,460**（infinity 37,800 × 0.70）/ `durability` **1218** / `thread-slots` **2**。

**シリーズ固有の追加ステ（全13種一律）**: `bleed-chance` **+0.12** / `bleed-damage` **+250**。
三叉槍・鎌は既存の `bleed-chance` 0.16 に加算されて 0.28 になる。

| id | material#CMD | attack-power | per-quality | random |
|---|---|---|---|---|
| `cryocore_sword` | `NETHERITE_SWORD#5700` | 26,460.0 | 1,259.5 | -4,233.6 / +5,292.0 |
| `cryocore_dagger` | `NETHERITE_SWORD#5701` | 17,992.8 | 856.5 | -2,878.8 / +3,598.6 |
| `cryocore_rapier` | `NETHERITE_SWORD#5702` | 20,638.8 | 982.4 | -3,302.2 / +4,127.8 |
| `cryocore_warhammer` | `NETHERITE_SWORD#5703` | 40,682.2 | 1,936.5 | -6,509.2 / +8,136.5 |
| `cryocore_grate_sword` | `NETHERITE_SWORD#5704` | 37,044.0 | 1,763.3 | -5,927.0 / +7,408.8 |
| `cryocore_greataxe` | `NETHERITE_SWORD#5705` | 44,982.0 | 2,141.1 | -7,197.1 / +8,996.4 |
| `cryocore_spear` | `NETHERITE_SPEAR#5700` | 18,442.6 | 877.9 | -2,950.8 / +3,688.5 |
| `cryocore_trident` | `TRIDENT#5700` | 23,814.0 | 1,133.5 | -3,810.2 / +4,762.8 |
| `cryocore_scythe` | `NETHERITE_HOE#5700` | 20,109.6 | 957.2 | -3,217.5 / +4,021.9 |
| `cryocore_mace` | `MACE#5700` | 33,075.0 | 1,574.4 | -5,292.0 / +6,615.0 |
| `cryocore_axe` | `NETHERITE_AXE#5700` | 39,359.2 | 1,873.5 | -6,297.5 / +7,871.9 |
| `cryocore_bow` | `BOW#5700` | 28,992.2 | 1,380.0 | -4,638.8 / +5,798.4 |
| `cryocore_crossbow` | `CROSSBOW#5700` | 24,343.2 | 1,158.7 | -3,894.9 / +4,868.6 |

**レシピ（`method: ritual`、13種すべて）:**

```yaml
core-item: custom:wither_rose_2x        # 袋小路の解消（1x は19用途で過密、2x は完全な袋小路）
pedestal-items:
- custom:breeze_rod_3x x2               # 袋小路の解消
- custom:hard_metal x4
- custom:elder_guardian_spike x2        # ドロップ素材の用途を分散
- <§1 の衝突回避素材>                    # 剣のみ無し
source: <§1 の source>
```

## 3. 柱5-2 ── 熾鉄 `emberforge_*`（耐久・維持コスト特化 / Lv100 / 13武器種＋防具4部位）

`attack-power` 基準 **32,130**（infinity 37,800 × 0.85）/ `durability` **3654**（既存最上位 1218 の3.0倍）/
`thread-slots` **2**。

| id | material#CMD | attack-power | per-quality | random |
|---|---|---|---|---|
| `emberforge_sword` | `NETHERITE_SWORD#5720` | 32,130.0 | 1,529.4 | -5,140.8 / +6,426.0 |
| `emberforge_dagger` | `NETHERITE_SWORD#5721` | 21,848.4 | 1,040.0 | -3,495.7 / +4,369.7 |
| `emberforge_rapier` | `NETHERITE_SWORD#5722` | 25,061.4 | 1,192.9 | -4,009.8 / +5,012.3 |
| `emberforge_warhammer` | `NETHERITE_SWORD#5723` | 49,399.9 | 2,351.4 | -7,904.0 / +9,880.0 |
| `emberforge_grate_sword` | `NETHERITE_SWORD#5724` | 44,982.0 | 2,141.1 | -7,197.1 / +8,996.4 |
| `emberforge_greataxe` | `NETHERITE_SWORD#5725` | 54,621.0 | 2,600.0 | -8,739.4 / +10,924.2 |
| `emberforge_spear` | `NETHERITE_SPEAR#5720` | 22,394.6 | 1,066.0 | -3,583.1 / +4,478.9 |
| `emberforge_trident` | `TRIDENT#5720` | 28,917.0 | 1,376.4 | -4,626.7 / +5,783.4 |
| `emberforge_scythe` | `NETHERITE_HOE#5720` | 24,418.8 | 1,162.3 | -3,907.0 / +4,883.8 |
| `emberforge_mace` | `MACE#5720` | 40,162.5 | 1,911.7 | -6,426.0 / +8,032.5 |
| `emberforge_axe` | `NETHERITE_AXE#5720` | 47,793.4 | 2,275.0 | -7,646.9 / +9,558.7 |
| `emberforge_bow` | `BOW#5720` | 35,204.8 | 1,675.8 | -5,632.8 / +7,041.0 |
| `emberforge_crossbow` | `CROSSBOW#5720` | 29,559.6 | 1,407.0 | -4,729.5 / +5,911.9 |

**武器の厳選幅は §1 の共通式（±16%/+20%）を使う。** 計画書の「±5%」は防具側でラダーを壊すため、
武器についても**シリーズ間の比較可能性を保つ観点から共通式に揃える**。
熾鉄の識別は `durability` 3倍と修理素材の安さで行う。

**防具4部位**（`emberforge_helmet` / `_chestplate` / `_leggings` / `_boots`、
`NETHERITE_HELMET#5720` / `NETHERITE_CHESTPLATE#5720` / `NETHERITE_LEGGINGS#5720` / `NETHERITE_BOOTS#5720`）:

| キー | 値 | 根拠 |
|---|---|---|
| `phys-flat-defense` | **infinity と完全同値**（16.68 / 44.48 / 33.36 / 16.68、`random` も同値） | §0-1。動かすとラダーが発散する |
| `armor-strength` | **0.19** | infinity 0.2625 / ウィザー 0.18 の間。乗算系なので安全 |
| `damage-reduction` | **0.02** | 乗算系 |
| `durability` | infinity の **3.0倍**（helmet 1620 / chest 2340 / leggings 2196 / boots 1890） | infinity 実値 540/780/732/630 の3倍 |
| `thread-slots` | 2 | |
| `move-speed` | infinity と同値（-0.002 / -0.004 / -0.004 / -0.002） | 2026-08-01 の重装 -12% 統一を崩さない |
| 判定スキル | `HEAVY_ARMOR` | |
| 厳選幅 ±5% | **`phys-flat-defense` 以外にのみ適用** | §0-1 |

**レシピ（武器・防具 共通の型）:**

```yaml
core-item: custom:netherite_block_1x    # 1段しかない袋小路の解消
pedestal-items:
- custom:stone_5x x1                    # 袋小路の解消
- custom:emerald_block_4x x1            # 袋小路の解消（エメラルド単一依存を割る）
- custom:hard_metal x6
- <§1 の衝突回避素材>                    # 剣のみ無し
source: <§1 の source>
```

防具4部位の衝突回避素材は武器と重ならないものを使う:
helmet `GOLDEN_HELMET x1` / chestplate `GOLDEN_CHESTPLATE x1` /
leggings `GOLDEN_LEGGINGS x1` / boots `GOLDEN_BOOTS x1`。source は各 40,000。

---

## 4. 柱5-3 ── 単発装備8点（コア4種の出口）

**コア1種につき「武器/ツール1点＋防具1点」の2用途。** Lv は 30/45/60 に散らし、**Lv100 帯を増やさない。**

| id | 和名 | material#CMD | 種別 | Lv | コア | 固有ステ |
|---|---|---|---|---|---|---|
| `woodsman_greataxe` | 樵の大斧 | `NETHERITE_SWORD#5740` | 大斧 | 60 | `tf_core_wood` ×1 | attack-power 4,200 / **伐採EXP +15%** |
| `forestfolk_cloak` | 森人の外套 | `NETHERITE_CHESTPLATE#5740` | 胴 | 60 | `tf_core_wood` ×1 | LIGHT / `dodge-chance` +0.05 / `move-speed` +0.015 |
| `feast_breastplate` | 饗宴の胸当て | `NETHERITE_CHESTPLATE#5741` | 胴 | 45 | `tf_core_meat` ×1 | HEAVY / `max-health` +4.0 / 食料節約率 |
| `hunter_javelin` | 猟師の投槍 | `NETHERITE_SPEAR#5740` | 槍 | 45 | `tf_core_meat` ×1 | attack-power 2,800 / `attack-reach` +1.2 |
| `harvest_hoe` | 豊穣の鍬 | `NETHERITE_HOE#5740` | ツール | 30 | `tf_core_vegetable` ×1 | **農業EXP +20%** / 範囲収穫CT短縮 |
| `herb_hat` | 香草の帽子 | `NETHERITE_HELMET#5740` | 兜 | 30 | `tf_core_vegetable` ×1 | LIGHT / 満腹度回復ボーナス |
| `leyline_shovel` | 地脈のシャベル | `NETHERITE_SHOVEL#5740` | ツール | 30 | `tf_core_dirt` ×1 | **掘削EXP +20%** / `thread_empty` ドロップ率 +2% |
| `bedrock_greaves` | 岩盤の具足 | `NETHERITE_BOOTS#5740` | ブーツ | 30 | `tf_core_dirt` ×1 | HEAVY / `knockback-resistance` +0.10 |

**必ず守る2点:**

1. **`use-skill` を分類マーカーに使わない。** 樵の大斧に `use-skill: WOODCUTTING` を付けると
   **斧で殴って伐採EXPが入る**罠を踏む。伐採EXP+15% は**EXP倍率ステで表現する**。
   `use-skill` は武器としての `HEAVY_WEAPONS` を入れる。
2. **防具4点（外套・胸当て・帽子・具足）の `phys-flat-defense` は、同レベル帯のバニラ相当部位と同値にする。**
   単発の1部位でもセット合計に効くので、ここを動かすと §0-1 の発散に触れる。
   識別は `dodge-chance` / `move-speed` / `max-health` / `knockback-resistance` など
   **減算されないキーだけ**で行う（上表の固有ステはすべてこの条件を満たしている）。
   同レベル帯の参照元は `item-stats.yml` の Lv30=鉄 / Lv45=金〜ダイヤ中間 / Lv60=ダイヤ の各部位。

**レシピ（`method: ritual`）**: core-item に該当コア、pedestal は各コアの系統素材＋`hard_metal x2`、
source は Lv に応じて 30/45/60 → 8,000 / 15,000 / 25,000。
**8点すべて core-item が異なるか pedestal が異なるので衝突しない**（コアが同じ2点は §1 の
衝突回避素材で割る: 武器側に該当武器種の素材、防具側に `GOLDEN_*` を1つ入れる）。

---

## 5. 柱6 ── `infinity_source_core` を「置いて機能させる」

**炉（儀式コア＋半径5のジャー群）の中心に据えると、周囲のジャー容量と転送レートに補正が乗る。**
判定用アイテムとして消費されない（＝使い回し可）が、**持ち歩くより置いた方が得**になるので死蔵しない。

- **マルチブロックのパターン判定は作らない。半径判定だけで成立させる。**
- 補正は既存の `sourcelinks.yml` の `transfer.*` と**同じキーで表現する**（新語彙を作らない）:

```yaml
# fork: sourcelinks.yml
transfer:
  infinity-core:
    # 設置された infinity_source_core からこの半径内のジャー/リンクに補正が乗る
    radius: 5
    # 転送量の倍率（max-per-transfer に乗算）
    transfer-multiplier: 2.0
    # ジャー容量の倍率（buffer-cap に乗算。int オーバーフローのクランプは既存実装が持つ）
    buffer-multiplier: 2.0
```

- **`buffer-cap` の int オーバーフロー保護は既存実装（`Sourcelink.java:105-135`）が持っている**ので、
  倍率を掛けた結果もそのクランプを通すこと。通さないと投入分が全損する（K-16 で塞いだ穴の再発）。

---

## 6. 柱7 ── ロール専用装備8点（`use-role` の最初の使い所）

**ステは §4 の単発装備と同等にし、「そのロールでないと装備できない」ことだけを差にする。**
縦強化にしない。ロール変更CTが120分あるので「今日はこの職で遊ぶ」を選ぶ理由になる。

| ロール | id | 和名 | material#CMD | 効果 |
|---|---|---|---|---|
| `swordfighter` | `gladiator_belt` | 闘士の帯 | `LEATHER_CHESTPLATE#5760` | `penetration` +0.04 |
| `mage` | `chanter_ring` | 詠唱者の指輪 | `AMETHYST_SHARD#5760` | `mana-cost-reduction-percent` +0.06（触媒枠） |
| `tank` | `taunt_crest` | 挑発の紋章 | `NETHERITE_CHESTPLATE#5760` | hate係数 +0.3 / `damage-reduction` +0.02 |
| `farmer` | `harvest_basket` | 実りの籠 | `LEATHER_CHESTPLATE#5761` | 農業ドロップ +10% |
| `fisher` | `tide_float` | 潮見の浮き | `LEATHER_HELMET#5760` | 釣り宝比率 +2% |
| `miner` | `pit_lamp` | 坑夫の灯 | `LEATHER_HELMET#5761` | 採掘ギミック発動率 +1% |
| `digger` | `leveling_gloves` | 均しの手袋 | `LEATHER_CHESTPLATE#5762` | 掘削ギミック発動率 +1% |
| `woodcutter` | `woodsman_whetstone` | 樵の砥石 | `AMETHYST_SHARD#5761` | 一括伐採CT −15% |

**実装の前提（計画書 §7 の確定事項）:**

- **`use-role:` は `UseRequirementService` に1箇所足すだけで全経路に効く**
  （近接/弓/ツール/`ArmorUseGateListener`/Ars触媒詠唱の呼び出し元が一本化済み）。
- ドロップ側は `mob-level-table.yml` の `add-drops` に **`roles:` を足す**
  （既に `mobs:` / `mob-ids:` というエントリ単位フィルタがあるので同じ形にする）。
- **ダンジョン入場（A）はロールで縛らない。** 鍵でゲートが成立しており、
  ロールで縛ると「そのロールでないと遊べないダンジョン」になる。
- **レシピ/儀式（D）とガチャ（E）にはロール条件を入れない。**
  D はフォーク改修必須、E は3箇所で別々にパースしていて共通ヘルパーが無い。

**防具扱いの5点（帯・紋章・籠・浮き・手袋）も §4-2 の規約に従う** ──
`phys-flat-defense` は同レベル帯のバニラ相当部位と同値にし、識別は減算されないキーだけで行う。

---

## 7. CMD 割当（帯で分ける）

**5700番台は全材料で完全に空いている**（現在の最大は 5611）。シリーズごとに帯を切る:

| 帯 | 用途 | 件数 |
|---|---|---|
| 5700〜5705 | 氷芯 `cryocore_*` | 13（材料ごとに 5700 から連番） |
| 5720〜5725 | 熾鉄 `emberforge_*` 武器＋防具 | 17 |
| 5740〜5741 | 単発装備8点 | 8 |
| 5760〜5762 | ロール専用装備8点 | 8 |

**CMD は material ごとの採番**（`NETHERITE_SWORD#5700` と `MACE#5700` は別物）。
**再利用は禁止**で、`resourcepack/cmd-registry.json` へ必ず登録する。
登録漏れは `resourcepack/build_item_pack.py` の検証で落ちる（台帳に無い threshold は zip に入らない）。

リソースパック側は `assets/minecraft/items/<material>.json` に threshold を**昇順で**足す。
モデルを用意しない場合はバニラモデルを指す形で構わない（既存の多数がそうなっている）。

---

## 8. 実装順と、いま着手できない理由

**`stats/item-stats.yml` は現在バグ修正ワークフロー（U5/U6 レーン）が専有している。**
本書の全項目が `item-stats.yml` を必要とするため、**着手はそのレーンのマージ後**。

1. §2 氷芯13種（catalog.yml + item-stats.yml + cmd-registry + リソパ）
2. §3 熾鉄17種（同上。防具は §0-1 の制約を厳守）
3. §4 単発装備8点 ← コア4種の用途ゼロがここで解消する
4. §6 ロール装備8点（`use-role` の Java 追加が先に要る）
5. §5 柱6（フォーク側。`sourcelinks.yml` + `Sourcelink.java`）

**検証は既存の実行可能ゲートで行う:**

- `ShippedRitualRecipeUniquenessTest` — 儀式レシピの衝突ゼロ
- `armor-ladder.test.js` — 耐久ラダー（新防具を10番目の帯として追加すること）
- `ShippedStatCapsDriftTest` — `stat-caps.yml` の上限が単品最大を下回らないこと
  （**熾鉄の大斧 54,621 が現在の最大 137,500 を超えないことは確認済み**）
- `build_item_pack.py` — CMD 台帳との整合
