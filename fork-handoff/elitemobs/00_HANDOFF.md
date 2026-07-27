# EliteMobs fork — 引き継ぎ（fork作成セッション向け）

> 先に `../README.md`（共通前提・TrinityForge契約・PDCスキーマ）を読むこと。本書はEliteMobs固有の手順とコンテキスト。

## ゴール
EliteMobsを改造し、**自前のダメージ/レベル/装備倍率計算を撤去してTrinityForgeへ委譲**する。EliteMobsは「mob供給・ドロップ供給・targeting・ダンジョン・combatレベル提供元」の役割に専念する。詳細実装は同フォルダ `ELITEMOBS_FORK_SPEC.md`（decompile基準で作成済み）。

## このフォルダの資産
- `ELITEMOBS_FORK_SPEC.md` — 実装仕様（最重要）
- `EliteMobs-original.jar` — fork対象バイナリ（中身は `com.magmaguy.elitemobs.**`）
- `lib/TrinityForge.jar` — compileOnly用のTrinityForge API
- `specs/` — COMBAT/ADDON/DUNGEON/SELECTION/PROGRESSION/ROLE/TRINITY/DESIGN/IMPLEMENTATION_PLAN

## 着手前の必須確認（ブロッカー）
1. **ビルド可能なEliteMobsソース**を用意（OSS: MagmaGuy/EliteMobs。`EliteMobs-original.jar` のバージョンに合わせる）。リポジトリ内にソースは無い。
2. decompile済みバイトコードはリポジトリ `decompile/extract_elite/com/magmaguy/elitemobs/**` にあり、署名確認に使える:
   ```bash
   JAVAP="/c/Program Files/Java/jdk-21/bin/javap.exe"
   "$JAVAP" -p -cp <repo>/decompile/extract_elite com.magmaguy.elitemobs.combatsystem.<Class>
   ```
   （TrinityForgeのValhalla連携も同手法で署名接地した。`SymmetricCombatService`等の実シグネチャは `lib/TrinityForge.jar` を `javap` で確認可。）

## TrinityForgeへの委譲ポイント（EliteMobs側で繋ぐ箇所）
`ELITEMOBS_FORK_SPEC.md` の各項を、TrinityForge APIで実現する対応表:

| EliteMobs内の対象 | 委譲先 / やること |
|---|---|
| WeaponOffense/ArmorDefense Calculator（ダメージ計算） | 短絡し、最終ダメージは `combatService.physicalFinalDamage(attackerId, victim, vanillaBase, AttackStats)` 経由に。装備tier倍率は排除（gear非依存） |
| combatレベル算出 | 自前算出を撤去し `combatService.combatLevelOf(playerUuid)` に差し替え（スキルツリー平均写像。ADDON §1.5） |
| バニラResistance/PotionCombatModifier | TrinityForgeが防御率%へ統合済。EliteMobs側の二重軽減を除去 |
| ドロップ供給（SharedLootTable.PlayerTable等） | ドロップ生成時に `ItemAssembler.assemble(meta, rollSeed, quality)` を呼んでPDC(rollSeed+quality)を書く。所有者/バインドは `ItemData`（`BindType`）で |
| 装備使用レベル制限（GearRestrictionHandler） | `ItemData.useLevelRequirement()/useSkill()` を読んで判定。要求はスキルツリーレベル（combatレベルではない） |
| Soulbind（SoulbindEnchantment） | `ItemData.bindType()`＝`SOULBOUND/OWNER_BOUND`、`owner()` を使用 |
| Repair無効化 | 仕様通り修繕廃止（耐久=有限消耗リソース） |
| ダンジョン3層 / mob耐性テーマ | spawn時にmobのPDCへ防御プロファイル＋`MOB_DUNGEON_THEME` を書く（`MobData`/`PdcKeys`）。TrinityForgeは `MOB_LEVEL` 有無で profile有無を判定し、無ければ `combat/mob-defaults.yml` の既定を使う |
| **配布mobの一括変換＋spawn付与（重要）** | TrinityForgeに **mob一括変換ツール実装済**。`/trinityforge importmobs <EliteMobs custombossesフォルダ>` が配布mob定義(`<id>.yml`)を読み、`combat/mob-import.yml` の変換ポリシー(level連動 base+per-level、clamp)で `combat/mob-profiles.yml` を生成(id=ファイル名)。**fork側の仕事**: spawn時に `TrinityForge.getInstance().config().mobProfiles().profile(eliteMobsId)` を引き、得た `MobProfile`(level/dungeonTheme/physical・magical `DefenseStats`)を mobのPDCへ書く(`MobData` setterは未実装＝forkで追加、`PdcKeys.MOB_*` に直接 or MobDataにsetter追加)。`MobProfile.id()`=EliteMobsのファイル名規約と一致。変換ポリシーの数値は全て `combat/mob-import.yml` 外出し(ハードコード禁止)。**注意**: EliteMobs YAMLキー名(`entityType`/`level`/`name`)は逆コンパイル由来の仮定。実jarでキー名がズレる場合は `EliteMobsMobMapping`(`getString("entityType")`等)を調整。`entityType` を持たないファイルは非mobとしてskipする実装 |
| **新規ダンジョン作成サポート（テーマ）** | TrinityForgeに **ダンジョンテーマ実装済**。`dungeon/themes.yml` で属性テーマ(物理偏重/魔法偏重等の防御傾きプリセット、level連動ramp)を定義し、`/trinityforge importmobs theme <theme> <新ダンジョンのmobフォルダ>` でそのダンジョンのmobにテーマ適用(防御上書き＋`MOB_DUNGEON_THEME`タグ付与)＋プロファイル生成。`/trinityforge dungeon themes` でテーマ一覧。**fork側**: spawn付与時に `MobProfile.dungeonTheme()` も併せてPDC(`MOB_DUNGEON_THEME`)へ書く。新ダンジョン作成フロー=「テーマ選定/定義→該当mobフォルダをimportmobs→fork spawnでPDC付与」 |
| **新規ヘイト値システム** | **TrinityForge側に実装済み**（`com.trinityforge.hate` サブシステム）。公開API: `TrinityForge.getInstance().hateService()` → `com.trinityforge.hate.HateService`（`recordDamage(...)` / `topAttacker(UUID mobId)`）。レート等は `hate/rates.yml` で設定駆動。**ヘイトテーブルはPDC永続化せずメモリ揮発**（cap/LRU・TTL・sweep実装済み、decayは既定off）。**fork側の仕事**: EliteMobsのtargeting（`api.EliteMobTargetPlayerEvent`等）から `hateService().topAttacker(mobId)` を参照してターゲットを上書き/誘導する連携実装。ROLE §4 / TRINITY §3 参照 |

## 実装の進め方（推奨）
1. EliteMobsソースをビルド可能状態にし、TrinityForge.jar を compileOnly 追加、`paper-plugin.yml` に softdepend（README 1章）。
2. `ELITEMOBS_FORK_SPEC.md` の順でcombatsystem短絡→combatレベル差し替え→loot供給→既存流用(restriction/soulbind)→dungeon→hate。
3. 各改造後にビルド。実機（Paper1.21.11＋TrinityForge＋ValhallaMMO）で結合確認。
4. 数値は極力TrinityForgeのYAMLに寄せる（fork側ハードコード禁止）。

## 検証観点
- EliteMob殴打/被弾が **TrinityForgeのパイプライン経由**になり、装備tierで素ダメが変わらない（gear非依存）。
- combatレベルがスキルツリー平均に追従。
- ドロップ品に rollSeed+quality がPDCで乗り、`/trinityforge reload` で味付けが再計算される。
- 二重軽減（バニラResistance + 防御率%）が起きていない。

## 注意 / 落とし穴
- Paperのクラスローダ分離: TrinityForgeのクラスを直接呼ぶなら `join-classpath: true` 必須（README 1章）。
- TrinityForge未導入環境では起動させない/機能縮退の設計に（`required: true` 推奨）。
- `AttackStats` は触媒/装備ステを載せる器。EliteMobsの近接は当面 `AttackStats.plain(0)` で良いが、貫通/会心等をEliteMobs装備から渡す場合はtemplateに積む。
