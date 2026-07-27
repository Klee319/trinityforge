# EliteMobs ダンジョン連携 — 敵ステータスのTrinityForge化

最終更新: 2026-07-24

EliteMobs の**インスタンス性ダンジョン**と**ボスのユニーク攻撃**をそのまま維持したまま、
敵の**HP / 攻撃 / 防御**を TrinityForge(TF)のステータス体系で駆動させるための連携仕様と運用手順。
討伐時のエリートコイン・戦利品は本連携の対象外(OFFにして後日手動設定する前提)。

---

## 1. 何がどう駆動されるか

| ステータス | 駆動元 | 仕組み |
|---|---|---|
| **防御**(HP以外) | TF | `mob-profiles.yml` → spawn時にPDC刻印 → `SymmetricCombatService`(既存) |
| **攻撃**(与ダメ) | TF(完全) | `mob-import.yml` の `attack` ramp を非ゼロにし、importmobs で全モブへ attack を刻印 → TF が与ダメ本体を算出 |
| **HP**(体力) | TF | `mob-profiles.yml` の `max-health`(>0)を、フォークが EliteMobs の指数HP計算の代わりに使用(本連携で新設) |

- **ダンジョン生成・ボスのユニーク攻撃(powers/scripts)はステータス非依存**なので、ステをTF化しても壊れない。
  アビリティダメージは従来どおり「魔法」としてTFの魔法防御のみ適用される。
- 適用範囲は **CustomBoss(custombosses/*.yml のモブ)限定**。素の natural elite は対象外
  (プロファイルidがCustomBossのファイル名で解決されるため)。

---

## 2. 設定サーフェス(TF側)

### `TrinityForge/plugins/TrinityForge/combat/mob-import.yml`
importmobs 実行時の変換ポリシー。EliteMobsファイルは防御/攻撃/HPフィールドを持たないため、
`base + per-level * level` で合成する。**数値は全て暫定 — 実バランスは別途チューニングすること。**

```yaml
# HP: 0=未設定(フォークがEliteMobs HPを維持)。非ゼロでHPをTF駆動化。
max-health:           { base: 20.0, per-level: 5.0 }

# 攻撃を完全TF駆動にするため attack-power を非ゼロにしてある。
attack:
  attack-power:         { base: 4.0, per-level: 0.5 }
  ...
  damage-modifier:      { base: 1.0, per-level: 0.0 }   # 1.0=中立。0にするとUniform[0,1]で威力半減
```

> ⚠️ `damage-modifier` は乗算補正の端点で、中立は **1.0**。0.0 にすると1発ごとに
> `Uniform[0,1]` ≈ ×0.5 で威力が半減する。attack を刻印するなら 1.0 のままにする。

### `.../combat/mob-profiles.yml`
importmobs が生成する各モブの確定プロファイル。`max-health`(double, 0=未設定)を持つ。
手編集して `/trinityforge reload` で即再チューニング可。

### フォーク `EliteMobs/plugins/EliteMobs/trinityforge.yml`
```yaml
hp-delegation: true   # TFプロファイルのmax-health(>0)で敵HPを駆動。既定ON。
```

---

## 3. 有効化ワークフロー(importmobs)

`max-health` / `attack` は **importmobs 実行時に mob-profiles.yml へ焼き込まれる**。ramp を変えたら再importが必要。

```
1. combat/mob-import.yml を編集(HP/attack ramp を調整)
2. /trinityforge reload            ← 重要: importmobs はメモリ上のポリシーを使う。reload しないと旧ポリシーで焼かれる
3. /trinityforge importmobs <folder>
   または  /trinityforge importmobs theme <theme> <folder>   ← テーマ付きでもHP/attackは維持される
4. /trinityforge reload            ← 生成された mob-profiles.yml をロード
```

- 個別モブだけ微調整したいときは importmobs せず `mob-profiles.yml` を直接編集 → reload。
- importmobs は**マージ**(既存の手編集エントリは残り、再import対象idのみ置換)。

---

## 4. エリートコイン・戦利品を止める(EliteMobs本体config, デプロイ側)

連携コードは触らない。サーバの `plugins/EliteMobs/` 設定でOFFにする(ソース実読で確認したキー)。

**推奨(最短で全部止める):**

| ファイル | キー | 設定値 | 効果 |
|---|---|---|---|
| `plugins/EliteMobs/ItemSettings.yml` | `doEliteMobsLoot` | `false` | **コイン・カスタム品・プロシージャル生成品を含む全EliteMobsドロップを停止** |
| `plugins/EliteMobs/EconomySettings.yml` | `enableCurrencyShower` | `false` | 討伐時のコイン投下を停止(補強) |

**補足:**
- 特定ボスだけ止めるなら `custombosses/<boss>.yml` の
  `dropsEliteMobsLoot: false` / `dropsRandomLoot: false` / `dropsVanillaLoot: false`。
- ボスファイルの loot table に `currencyAmount=...` を直書きしたエントリだけは
  グローバルの `enableCurrencyShower` を通らないため、そのエントリ削除が別途必要。
- コイン量を残しつつ0にしたい場合の代替: `EconomySettings.yml` `currencyShowerTierMultiplier: 0`。

> 後日、TF側で独自の戦利品を手動設定する想定。上記は「EliteMobs由来のコイン/ドロップを一旦全部消す」設定。

---

## 5. ビルドとデプロイ

```
# TrinityForge 本体(API変更あり → 再ビルド必須)
cd TrinityForge && ./gradlew build
#   デプロイjar: build/libs/TrinityForge-0.1.0-SNAPSHOT-all.jar (uberjar)
#   fork用API:  build/libs/TrinityForge-0.1.0-SNAPSHOT-thin.jar を
#               fork-handoff/elitemobs/elitemobs-fork/libs/TrinityForge.jar へ上書き

# EliteMobs フォーク(uberjar)
cd fork-handoff/elitemobs/elitemobs-fork
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain shadowJar --offline
#   デプロイjar: testbed/plugins/EliteMobs.jar (shadowJarのuberjar。-min.jarは配布禁止)
```

配備先: `D:\game\minecraft\PaperServer\TrinityForge\plugins\`(TF本体 + EliteMobs uberjar)。
サーバ再起動はユーザー側。

---

## 6. 実サーバ検証チェックリスト

コード/ユニットテストでは検証できない実行時挙動(Bukkit/EliteMobs依存)。デプロイ後に確認:

- [ ] CustomBoss の **HP が TF値**(mob-profiles.yml の max-health × EliteMobs healthMultiplier)になっている。
- [ ] CustomBoss の **通常近接与ダメが TF算出**(attack-power ベース)になっている。
- [ ] **フェーズボス / インスタンスダンジョンボス**が、フェーズ再ヒール後も HP が TF値のまま
      (setMaxHealth/setNormalizedMaxHealth 両方でTF上書き済み)。
- [ ] **ボスのユニーク攻撃(powers)が発火する**(発火ロジックはステ非依存)。
- [ ] ⚠️ **HP係数を参照するボスpower(`%HP`系)の威力**: HP基準が指数計算→TF値に変わるため威力が変動し得る。
      該当スキルがあればバランス確認。
- [ ] コイン・戦利品が落ちない(§4の設定確認)。
- [ ] `hp-delegation: false` にすると EliteMobs の従来HPに戻る(トグル動作確認)。

---

## 7. 既知の制約・設計判断

- **CustomBoss限定**: HP/attack/防御いずれも CustomBoss のみ。natural elite は対象外。
- **healthMultiplier は残す**: TF max-health を baseline とし、その上に EliteMobs の per-instance HP変異
  (ミニボス/増援スケール等)を乗せる。ほとんどのモブは 1.0 なので実質no-op。
- **手編集の負値 max-health**: `mob-profiles.yml` で負の max-health を書くとそのエントリ全体が
  skip され `mob-defaults.yml` にフォールバックする(既存の負level/防具不一致と同じ「エントリごとskip」方針)。
  importmobs 経由の負ramp端点は 0 にclampされ import は継続する(非対称だが両者ともfail-safe)。
- **フォークは fail-open**: TF未検出/例外時は EliteMobs の従来HP・与ダメに戻る(サーバは劣化モードで起動)。

---

## 8. 変更ファイル(この連携で触った箇所)

**TrinityForge本体:**
`mobs/MobProfile.java`(maxHealth), `config/domains/MobProfileConfig.java`,
`mobs/ConversionPolicy.java`(maxHealth ramp), `config/domains/MobImportConfig.java`,
`mobs/EliteMobsMobMapping.java`, `mobs/EliteMobsImporter.java`, `mobs/DungeonTheme.java`(toPolicyでmaxHealth伝播),
`resources/combat/mob-import.yml`, `resources/combat/mob-profiles.yml`

**EliteMobsフォーク:**
`trinityforge/TrinityForgeIntegration.java`(hp-delegation + resolveProfileMaxHealth),
`mobconstructor/EliteEntity.java`(setMaxHealth/setNormalizedMaxHealthでTF上書き),
`resources/trinityforge.yml`

**テスト:** MobProfileTest, EliteMobsMobMappingTest, EliteMobsImporterTest, MobProfileConfigTest,
MobImportConfigTest, DungeonThemeConfigTest(toPolicy回帰)
