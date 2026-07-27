# Fork Handoff — EliteMobs / ArsPaper

このフォルダは **fork作成を担当する別セッションのための引き継ぎ一式** です。plごとに自己完結したサブフォルダ（`elitemobs/` / `arspaper/`）にまとめてあります。

> あなた（fork作成セッション）へ: まず自分の担当plフォルダの `00_HANDOFF.md` を読み、次に同フォルダの `*_FORK_SPEC.md`、必要に応じて `specs/` を参照してください。本READMEは両fork共通の前提（ビルド環境・TrinityForge契約・PDCスキーマ）です。

---

## 0. 大前提（最重要）

- **統合方針 = TrinityForgeアドオン先行 → 各forkが委譲**。数値の真実・対称ダメージパイプライン・combatレベル写像・厳選導出・PDCスキーマは **TrinityForge側に既に実装済み**。forkは「自前計算をやめてTrinityForgeのAPIを呼ぶ」方向に改造する。
- **設定駆動が憲法**: 数値・倍率・係数・レシピは全てYAML外出し。fork側でもハードコード禁止（構造識別子=PDCキー名/イベント名/クラス名はコード側で可）。
- TrinityForge側のM0/M1/M3(一部)/M2受け口は完了済み・テスト/レビュー済み。残るのは **fork側の委譲実装**。

## 1. ビルド環境

| 項目 | 値 |
|---|---|
| Server API | Paper 1.21.11 (`paper-api:1.21.11-R0.1-SNAPSHOT`) |
| Java | 21 |
| Build | Gradle（TrinityForgeは`gradlew`同梱。forkも同等で揃える） |
| TrinityForge package | `com.trinityforge` / plugin名 `TrinityForge` |

### forkがTrinityForgeを参照する方法
1. `lib/TrinityForge.jar`（このフォルダ同梱＝ビルド済みAPI）を **compileOnly** 依存に追加。
2. `paper-plugin.yml` に softdepend を宣言（TrinityForgeを先にロード＋クラスパス結合）:
   ```yaml
   dependencies:
     server:
       TrinityForge:
         load: BEFORE
         required: true        # forkはTrinityForge前提なら true
         join-classpath: true  # Paperのクラスローダ分離下でTrinityForgeのクラスを直接呼ぶため必須
   ```
3. 実行時は `TrinityForge.getInstance()` から各APIを取得（下記2章）。

### fork元の「ビルド可能ソース」について（ブロッカー）
このフォルダの `*-original.jar` は **バイナリ（フォーク対象の現物）** であり、ソースではありません。decompile済みバイトコードはリポジトリの `decompile/extract_elite/` `decompile/extract_ars/` にあり、`javap` で署名確認できます（例は各 `00_HANDOFF.md`）。**実際にforkするにはビルド可能なソースツリーが必要**:
- **EliteMobs**: OSS。公式リポジトリ（MagmaGuy/EliteMobs）から該当バージョンのソースを取得。
- **ArsPaper**: 自作ポート。**ビルド可能ソースの所在をユーザーに確認**してから着手（リポジトリ内には無い）。

## 2. TrinityForge 公開API（forkが呼ぶ契約）

エントリ: `com.trinityforge.TrinityForge.getInstance()`
- `.combatService()` → `SymmetricCombatService`
- `.config()` → `ConfigManager`（各ドメインのtyped accessor）
- `.hateService()` → `com.trinityforge.hate.HateService`（ヘイト記録/参照。`recordDamage(...)` / `topAttacker(UUID mobId)`。`hate/rates.yml`駆動、テーブルはPDC永続化せずメモリ揮発）

### 2.1 SymmetricCombatService（対称ダメージ）
`com.trinityforge.combat.SymmetricCombatService`
- `int combatLevelOf(UUID attackerId)` — gear非依存combatレベル（スキルツリー平均→写像）
- `double physicalFinalDamage(UUID attackerId, PersistentDataHolder victim, double vanillaBaseDamage, AttackStats attack)`
- `double magicalFinalDamage(UUID attackerId, PersistentDataHolder victim, double spellBaseDamage, AttackStats attack)` ← **ArsPaperのM2受け口**

`AttackStats`（`com.trinityforge.combat.AttackStats`, record）:
`(defaultDamage, flatBonusDamage, percentBonusDamage, critChance, critDamage, penetration, damageModifier, fixedDamage)`
- `AttackStats.plain(d)` 全extras0、`attack.withDefaultDamage(d)` defaultDamageだけ差し替え。
- 呼び方: 触媒/装備ステ（会心・貫通等）でtemplateを作り、`magicalFinalDamage` に渡す。serviceがdefaultDamageを算出して注入し、8stepパイプライン後の最終ダメージを返す。fork側はその値を実ダメージとして適用する。

### 2.2 アイテム生成（書込側）

**推奨: `ItemFactory` を使う**（forkはこれ一本で良い。コンストラクタ変更がforkに波及しない）
`com.trinityforge.stats.ItemFactory`
- 取得: `TrinityForge.getInstance().itemFactory()`
- `ItemStack create(ItemTemplate template, long rollSeed, int quality)` — `items/catalog.yml` のテンプレ（material/表示名/CustomModelData/bindType/使用レベル要求）から完成 `ItemStack` を生成。内部で `ItemAssembler` を通すので rollSeed+quality のPDC書込・stat live導出・**Lore生成**・attribute写像まで一括。
- テンプレ取得: `cm.itemCatalog().template(id)`（`cm = TrinityForge.getInstance().config()`）。新規アイテムは `items/catalog.yml` に足すだけ（コード変更不要）。
- 動作確認/権限者の手動付与: `/trinityforge give <id> [quality]`。

**低レベルAPI: `ItemAssembler`**（独自のItemStackに直接書きたい時のみ）
`com.trinityforge.stats.ItemAssembler`
- 構築（5引数。**3引数版は廃止**）:
  `new ItemAssembler(new ItemStatRoller(cm.statRoll(), cm.quality()), cm.attributeMapping(), new AttributeApplier(plugin), cm.lore(), new LoreComposer())`
- `int assemble(ItemMeta meta, long rollSeed, int quality)` — PDCに **rollSeed+quality のみ** を書き、stat値は設定テーブルからlive導出、**Loreを `stats/lore.yml` 体裁で生成**、attribute写像分をItemMetaへ適用。ベイクしないので再ロール/再味付け/Lore再描画はすべてreloadで効く。
- ドロップ/クラフト時に `ItemStack` の `ItemMeta` を渡して呼ぶ。

### 2.2.1 アイテムLore（ValhallaMMO体裁）
- ランダムstatのLoreは `stats/lore.yml` で設定駆動（`layout.line-template` はMiniMessage `<icon>/<value>/<name>`、`positive-color`/`negative-color`、stat毎に `name/icon/format(FLAT|PERCENT|INTEGER|SCALAR)/decimals/order/show-sign/hide-when-zero`）。
- ValhallaMMOの `%icon%%value% Name` グレー体裁に既定で寄せてある。`icon` にValhallaMMOのリソースパックPUAグリフを貼れば完全に統一可能。`ItemAssembler`/`ItemFactory` 経由で生成すれば自動付与される。

### 2.3 PDCスキーマ（`com.trinityforge.pdc`、namespace `trinityforge`）
`PdcKeys` のキー（型）:
- アイテム: `ITEM_ROLL_SEED`(LONG), `ITEM_QUALITY`(INT 0-5), `ITEM_OWNER`(STRING/UUID), `ITEM_BIND_TYPE`(STRING), `ITEM_USE_LEVEL_REQ`(INT), `ITEM_USE_SKILL`(STRING)
- プレイヤー: `PLAYER_PRESTIGE_COUNT`(INT), `PLAYER_HELD_PERKS`(STRING, 0x1F区切り), `PLAYER_ROLE_PRIMARY`/`PLAYER_ROLE_SUPPORT`(STRING)
- mob: `MOB_LEVEL`(INT=プロファイル有無の目印), `MOB_DUNGEON_THEME`(STRING), `MOB_ARMOR_STRENGTH`(DOUBLE 共有), `MOB_PHYS_*`/`MOB_MAGIC_*`{DEFENSE_RATE,RESISTANCE,DAMAGE_REDUCTION,FLAT_DEFENSE}(DOUBLE)
- **整合注記（現実装準拠）**: ヘイトテーブルは上記PDCキーに含まれない。PDC永続化せず `com.trinityforge.hate.HateService` 内でメモリ揮発として保持される

型安全ラッパー（直接PdcKeysを触らずこちらを使う）:
- `ItemData.of(ItemMeta)` — rollSeed/quality/owner/bindType/useLevelRequirement/useSkill + setters
- `PlayerData.of(PersistentDataHolder)` — prestigeCount/heldPerks/rolePrimary/roleSupport + setters
- `MobData.of(PersistentDataHolder)` — level/dungeonTheme/`hasProfile()`/`defenseFor(DamageType)`
- `BindType` — SOULBOUND / MATERIAL_TRADEABLE / OWNER_BOUND

### 2.4 combatレベル
TrinityForgeが ValhallaMMO のスキルツリーレベルをreflectionで読み、`combatLevelOf` を提供。**EliteMobs forkは自前のcombatレベル算出を撤去し、これに差し替える**（ELITEMOBS_FORK_SPEC参照）。

### 2.5 reload
全config（**全12ドメイン**: `combat/` `stats/` `progression/` `items/` `dungeon/` `hate/` 等）は `/trinityforge reload`（別名 `/tf reload`）でホットリロード。fork側の数値もできる限りTrinityForgeのconfigに寄せる。

## 3. このフォルダの中身

```
fork-handoff/
  README.md                       ← 本ファイル（共通前提）
  elitemobs/
    00_HANDOFF.md                 ← EliteMobs fork作成セッション向け手順・コンテキスト
    ELITEMOBS_FORK_SPEC.md        ← 実装仕様（decompile基準で作成済み）
    EliteMobs-original.jar        ← fork対象バイナリ
    lib/TrinityForge.jar          ← compileOnly用API
    specs/                        ← 関連設計spec一式
  arspaper/
    00_HANDOFF.md                 ← ArsPaper fork作成セッション向け
    ARSPAPER_FORK_SPEC.md
    ArsPaper-original.jar
    lib/TrinityForge.jar
    specs/
```

## 4. 進行順の推奨
1. **EliteMobs fork**（供給/combatレベル/書込呼出元/ヘイトが集中、Arsの前提）
2. **ArsPaper fork**（魔法トリガ→`magicalFinalDamage`）
3. 実機結合テスト → 数値バランス（IMPLEMENTATION_PLAN M7）

## 5. 状態の真実 = メモリ
プロジェクト進捗の最新は Claude の自動メモリ `project-server-design-progress` に記録済み。fork着手時はそれも参照。
