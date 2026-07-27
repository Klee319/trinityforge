# ArsPaper x EliteMobs (fork改造版) 連携設計

- **作成日**: 2026-05-13
- **改訂**: v0.4 (level damage scale 維持 + custom drop 追加 を反映)
- **対象**: Minecraft Paper 1.21+ サーバー、個人利用
- **ステータス**: 実装前合意フェーズ

---

## 1. 目的

EliteMobs を個人 fork し、ソース改造により下記を実現:
- **mob level に応じた damage scaling は両方向維持** (Elite←→Player)
- **プレイヤーの装備tier依存だけ完全排除** (vanilla/Ars 装備でも level scale が乗る)
- Elite mob の HP scale は維持 (強敵感)
- Elite equipment / scroll drop を全停止
- **coin (currency) は維持 + カスタムドロップを config で自由設定**
- ArsPaper の魔法ダメージは vanilla base + level scale (Bridge plugin・ArsPaper 改修なし)

---

## 2. 確定スペック

### 2.1 戦闘
| 項目 | 設定 |
|---|---|
| 与ダメ baseline | vanilla 武器/魔法の base damage |
| 与ダメ scale | mob level に応じて scale (`LevelScaling` そのまま使用) |
| 与ダメ gear tier 依存 | **完全排除** (`WeaponOffenseCalculator.getWeaponAdjustment` を 1.0 で短絡) |
| 被ダメ baseline | mob level に応じた scaled mob attack damage |
| 被ダメ gear tier 依存 | **完全排除** (`ArmorDefenseCalculator.getGearAdjustment` を 1.0 で短絡) |
| Elite mob HP scale | 維持 (`LevelScaling.calculateMobHealth` そのまま) |
| ArsPaper 魔法 | base = vanilla の `livingEntity.damage()` 値、それに mob level scale が乗る |

### 2.2 Drop
| Drop種別 | 扱い |
|---|---|
| Elite procedural 装備 | **全停止** |
| Custom boss unique 装備 | **全停止** |
| Elite scroll | **全停止** |
| Elite coin / currency | **維持** |
| カスタムドロップ (config 設定) | **新設** (CustomDrops.yml) |
| Quest reward / dungeon key | **維持** |

### 2.3 その他
- plugin名: `EliteMobs` のまま
- 配布: 個人サーバーのみ、再配布しない (GPL-3.0 継承義務回避)
- ArsPaper: 無改修
- Bridge plugin: 不要

---

## 3. 上流リポジトリ調査結果

| 項目 | 値 |
|---|---|
| 上流 | https://github.com/MagmaGuy/EliteMobs |
| ライセンス | GPL-3.0 |
| ビルド | Gradle (Groovy DSL) + `shadow 9.0.0-beta12`、Java 21 |
| Paper API | 1.21.11 |
| 上流 version | 10.3.0 (master) |
| 最新release tag | v8.4.2 (2023-03) ※v10.x はタグ無し、master 開発中 |
| 主要 private dep | `repo.magmaguy.com` (MagmaCore) — 初回ビルド時に解決確認必要 |
| 出力 jar | `testbed/plugins/EliteMobs.jar` |
| Java file 数 | 約 1363 |

---

## 4. fork 戦略

### 4.1 配置
```
elitemobs/                     # カレント
├── EliteMobs (1).jar          # 既存配布版 (退避用、削除しない)
├── ArsPaper-1.0.0.jar
├── docs/
├── server/                    # Paper検証サーバー (Phase 2で作る)
└── elitemobs-fork/            # git clone 先
    ├── .git/
    ├── build.gradle
    ├── src/
    └── testbed/plugins/EliteMobs.jar
```

### 4.2 clone と branch
- `git clone https://github.com/MagmaGuy/EliteMobs.git elitemobs-fork`
- ローカル git のみ (GitHub fork なし)
- 取得 commit SHA を控え、`dev` ブランチを切って改造
- 既存 `EliteMobs (1).jar` の `/em version` と build.gradle version が一致するか実機確認、ずれてれば近い commit に巻き戻す

### 4.3 改造方針: **フラグガード方式**
- すべての改造を ON/OFF 可能にする internal フラグを 1か所に集約
- 各メソッドの冒頭に短い guard を追加するのみ → merge 容易

```java
// 新規ファイル: src/main/java/com/magmaguy/elitemobs/forkpatch/ForkPatch.java
public final class ForkPatch {
    public static final boolean DISABLE_GEAR_TIER_SCALING = true;  // gear依存だけ消す (level scale は残る)
    public static final boolean DISABLE_EQUIPMENT_DROP = true;
    public static final boolean DISABLE_SCROLL_DROP = true;
    public static final boolean ENABLE_CUSTOM_DROPS = true;
    private ForkPatch() {}
}
```

---

## 5. 改造仕様 (formula 解析済み)

### 5.1 damage scaling: gear tier 依存だけ排除

#### 5.1.1 `combatsystem/WeaponOffenseCalculator.java`

現状 formula (L151):
```
bonus = (weaponLevel <= mobLevel) ? 0.50 * (weaponLevel/mobLevel)
                                  : min(0.50 + (weaponLevel - mobLevel) * 0.025, 0.75)
return 0.5 + bonus    // gear に応じて 0.5 〜 1.25 倍
```

パッチ:
```java
public static double getWeaponAdjustment(double weaponLevel, int mobLevel) {
    if (ForkPatch.DISABLE_GEAR_TIER_SCALING) return 1.0;   // ← 追加
    // 既存ロジック保持
    ...
}
```

→ 効果: gear 依存の 0.5〜1.25 倍を一律 1.0 に固定。残る scale は `LevelScaling.calculateEffectiveDamage` 経由の mob level scale。

#### 5.1.2 `combatsystem/ArmorDefenseCalculator.java`

現状 formula (L240-275):
```
gearScore = armorLevelSum/4 + enchantBonusSum/4
red = (gearScore <= mobLevel) ? 0.50 * (gearScore/mobLevel)
                              : 0.50 + (gearScore - mobLevel) * 0.025
red = clamp(red, 0, 0.75)
return 2.0 * (1.0 - red)    // gear に応じて 0.5 〜 2.0 倍 (高 gear ほど被ダメ小)
```

パッチ:
```java
public static double getGearAdjustment(double gearScore, int mobLevel) {
    if (ForkPatch.DISABLE_GEAR_TIER_SCALING) return 1.0;   // ← 追加
    // 既存ロジック保持
    ...
}
```

→ 効果: 防具 tier 依存の 0.5〜2.0 倍を一律 1.0 に。残る scale は `LevelScaling.calculateIncomingDamage` の mob level scale (= 高 level mob ほど痛い)。

#### 5.1.3 触らないクラス
- `LevelScaling.java` — gear 非依存、HP/damage 両 scale 維持
- `NaturalEliteCombatTweak.java` — gear 非依存
- `mobconstructor/EliteEntity.java` — HP 保持に必要
- `api/EliteMobDamagedByPlayerEvent.java`, `api/PlayerDamagedByEliteMobEvent.java` — fire は残す (他 plugin との互換)

### 5.2 Elite equipment + scroll drop 全停止

改造対象 (`src/main/java/com/magmaguy/elitemobs/items/`):

| クラス | パッチ概要 |
|---|---|
| `LootTables.java` | `ForkPatch.DISABLE_EQUIPMENT_DROP` チェックで equipment 振り分け早期 return |
| `itemconstructor/ItemConstructor.java` | procedural 装備生成本体、フラグ ON なら null 返す |
| `customloottable/CustomLootTable.java` | unique drop 処理で equipment 判定して skip |
| `customloottable/EliteCustomLootEntry.java` | 同上 |
| `DefaultDropsHandler.java` | vanilla loot multiplier 1.0 固定 |

scroll は別 drop パスの可能性が高い → ItemSettings 系の `useEliteItemScrolls` を false にする config 設定で対応可能だが、確実性のためソース内 scroll drop 箇所にも `ForkPatch.DISABLE_SCROLL_DROP` ガード追加。

「装備か否か」判定: `Material` enum の SWORD / AXE / PICKAXE / SHOVEL / HOE / HELMET / CHESTPLATE / LEGGINGS / BOOTS / BOW / CROSSBOW / TRIDENT / SHIELD / MACE を含むかで判別。

### 5.3 カスタムドロップ機能 (新設)

#### 5.3.1 config: `plugins/EliteMobs/CustomDrops.yml`

```yaml
# elite mob 死亡時の custom drop。複数 entry を OR で評価し各 chance で roll。
enabled: true

# 自然湧き elite からの drop
natural-elite-drops:
  - id: diamond_drop
    material: minecraft:diamond
    amount: 1
    chance: 0.05                # 5%
    min-mob-level: 5            # level5 以上の elite からのみ
    max-mob-level: 999

  - id: emerald_chunk
    material: minecraft:emerald
    amount: [1, 3]              # 1〜3個ランダム
    chance: 0.1

  - id: arspaper_wand_basic    # 例: ArsPaper の item
    material: minecraft:stick
    custom-model-data: 100001    # ArsPaper item 識別用
    display-name: "&b初級魔法の杖"
    lore:
      - "&7基本の杖"
    chance: 0.01
    min-mob-level: 10

# regional/instanced boss からの drop (オプション)
boss-drops:
  - id: rare_treasure
    material: minecraft:netherite_ingot
    amount: 1
    chance: 0.25
    boss-filename: "FrozenKing.yml"   # 特定ボスのみ
```

#### 5.3.2 実装場所
- 新規パッケージ: `com/magmaguy/elitemobs/forkpatch/customdrop/`
  - `CustomDropConfig.java` — yml 読み込み (Bukkit `YamlConfiguration` ベース)
  - `CustomDropEntry.java` — 1 entry の表現
  - `CustomDropRoller.java` — chance roll とアイテム生成
  - `CustomDropListener.java` — `EliteMobDeathEvent` 系を listen して drop 適用
- フック箇所: `LootTables.java` の drop 処理直後 (装備 drop は skip 済み、ここで custom drop を追加)

#### 5.3.3 ArsPaper item 互換
- ArsPaper item は ItemStack の PDC (PersistentDataContainer) や CustomModelData で識別される前提
- config では Material + CustomModelData + DisplayName + Lore で簡易再現可能
- 厳密に NBT 一致が必要なら **将来拡張**: ArsPaper の ItemRegistry を hook して item ID で参照

---

## 6. ビルド・配布フロー

```bash
# 1. clone
git clone https://github.com/MagmaGuy/EliteMobs.git elitemobs-fork
cd elitemobs-fork
git checkout -b dev

# 2. 素のビルド (依存解決確認)
./gradlew build
# → testbed/plugins/EliteMobs.jar が出力されることを確認

# 3. 改造実装
#    - forkpatch/ForkPatch.java 新規作成
#    - combatsystem/{WeaponOffenseCalculator,ArmorDefenseCalculator}.java にガード追加
#    - items/* の装備drop に DISABLE_EQUIPMENT_DROP ガード
#    - forkpatch/customdrop/* を新規実装
#    - resources/CustomDrops.yml.default を追加

# 4. リビルド
./gradlew build

# 5. デプロイ
cp testbed/plugins/EliteMobs.jar ../server/plugins/EliteMobs.jar

# 6. サーバー起動
cd ../server && java -Xmx4G -jar paper-*.jar
```

---

## 7. 実装フェーズ

| Phase | 内容 | 完了条件 |
|---|---|---|
| 1 | `elitemobs-fork/` に clone、素のビルドが通ることを確認 | `./gradlew build` 成功、testbed/plugins/EliteMobs.jar 生成 |
| 2 | Paper 1.21.x + Java 21 検証サーバーを `server/` に setup、改造前の EliteMobs と ArsPaper を投入 | サーバー起動・両 plugin enabled、`/em version` 確認 |
| 3 | `ForkPatch.java` 新規 + damage 2クラスにガード追加 | リビルド成功、起動後 elite mob 戦闘で gear tier 無関係に level scale が乗ること実機確認 |
| 4 | 装備 + scroll drop 停止パッチ | elite mob 30 体倒して装備/scroll drop 0、coin は出る |
| 5 | CustomDrops 機能実装 | CustomDrops.yml の entry に従って drop すること実機確認 |
| 6 | サバイバルで実プレイ、不満があれば追加改造 | 物理職/魔法職とも EliteMobs ダンジョン攻略可能 |

---

## 8. 検証項目

| 確認 | 期待値 | 方法 |
|---|---|---|
| 装備 tier 排除 (与ダメ) | diamond sword と wooden sword で base damage 差分のみ、level scale 部分は同じ multiplier | level 10 elite に同じ level の素手・木剣・ダイヤ剣で1撃、HP差を比較 |
| 装備 tier 排除 (被ダメ) | iron armor と裸で被ダメ差分が **vanilla 差分のみ** (gear tier 倍率が消えている) | level 10 elite から固定回数殴られて HP 減少を計測 |
| level scale 健在 (与ダメ) | level 1 elite と level 50 elite で同武器の与ダメに level scale 差 | 同武器で各 level elite を殴ったときのダメージ表示 |
| level scale 健在 (被ダメ) | level 50 elite からの被ダメが level 1 より大きい | 各 level elite から固定 tick 内に受けるダメージ計測 |
| HP scale 健在 | level 50 elite が level 1 vanilla zombie より HP 多い | `/data get entity @e Health` |
| ArsPaper 魔法 | HarmEffect / LightningEffect が elite に level scale 乗ったダメージ | 魔法詠唱後 HP差で実測 |
| 装備 drop ゼロ | 50体倒して装備 drop 0 | 自然湧き elite + custom boss 両方 |
| scroll drop ゼロ | 50体倒して scroll drop 0 | 同上 |
| coin drop 維持 | 通常通り coin drop | 同上 |
| CustomDrops 適用 | CustomDrops.yml の entry が chance 通りに drop | 1000体程度倒して確率検証 |
| 既存機能 retain | dungeon teleport / Adventurer's Guild / quest が動く | 各種コマンド実行 |

検証結果は `docs/VERIFICATION.md` に表形式で記録。

---

## 9. リスクと対策

| リスク | 影響度 | 対策 |
|---|---|---|
| `repo.magmaguy.com` (MagmaCore) 解決失敗 | **高** | clone直後 `./gradlew build` で死活確認、代替 mirror 探索、最悪 MagmaCore も local clone |
| master HEAD が不安定 | 中 | clone後の commit SHA を pin、build成功 SHA を控える |
| 既存配布 jar と source version 不一致 | 中 | `/em version` と build.gradle version を照合 |
| LevelScaling の formula に隠れた gear 依存 | 低 | Codex 解析で gear 非依存を確認済み、念のため Phase 3 で実測検証 |
| scroll drop パスがソース内で分散 | 中 | grep で `EliteScroll` / `eliteItemScrollChance` 参照箇所を網羅、ForkPatch ガード追加 |
| CustomDrops の hook タイミング | 中 | `EliteMobDeathEvent` 系 API の存在を確認、無ければ `EntityDeathEvent` priority MONITOR で吸収 |
| EliteMobs 上流 update で merge conflict | 中 | dev branch、改造箇所最小化 (フラグ方式) |
| Lombok 設定漏れで IDE エラー | 低 | IDE setup を docs 化 |

---

## 10. 未決定事項

- [ ] Paper 検証サーバーの具体バージョン (1.21.1 / 1.21.4 / 1.21.6 等)
- [ ] clone 時の commit SHA pin 戦略 (HEAD or 既存 jar との version 一致)
- [ ] CustomDrops.yml の最終フォーマット (yml フィールドの命名)
- [ ] CustomDrops の boss-drop 部分を実装するか (Phase 5 で判断)
- [ ] 装備 drop の判定対象 Material の最終一覧 (Phase 4 で確定)

---

## 11. 参照

### ソース・ライセンス
- [EliteMobs LICENSE (GPL-3.0)](https://github.com/MagmaGuy/EliteMobs/blob/master/LICENSE)
- [build.gradle](https://github.com/MagmaGuy/EliteMobs/blob/master/build.gradle)

### 改造対象クラス
- [WeaponOffenseCalculator.java](https://github.com/MagmaGuy/EliteMobs/blob/master/src/main/java/com/magmaguy/elitemobs/combatsystem/WeaponOffenseCalculator.java)
- [ArmorDefenseCalculator.java](https://github.com/MagmaGuy/EliteMobs/blob/master/src/main/java/com/magmaguy/elitemobs/combatsystem/ArmorDefenseCalculator.java)
- [LevelScaling.java](https://github.com/MagmaGuy/EliteMobs/blob/master/src/main/java/com/magmaguy/elitemobs/combatsystem/LevelScaling.java)
- [LootTables.java](https://github.com/MagmaGuy/EliteMobs/blob/master/src/main/java/com/magmaguy/elitemobs/items/LootTables.java)
- [ItemConstructor.java](https://github.com/MagmaGuy/EliteMobs/blob/master/src/main/java/com/magmaguy/elitemobs/items/itemconstructor/ItemConstructor.java)
- [customloottable/](https://github.com/MagmaGuy/EliteMobs/tree/master/src/main/java/com/magmaguy/elitemobs/items/customloottable)
- [PreventEliteEquipmentDrop.java (参考)](https://github.com/MagmaGuy/EliteMobs/blob/master/src/main/java/com/magmaguy/elitemobs/collateralminecraftchanges/PreventEliteEquipmentDrop.java)

---

## 改訂履歴
- **v0.1 (2026-05-13)**: 初版。ArsPaper にカスタム event + Bridge plugin 案。
- **v0.2 (2026-05-13)**: Bridge 撤回、config tuning のみ案。
- **v0.3 (2026-05-13)**: config から fork 改造方針へ。damage scale 全停止案。
- **v0.4 (2026-05-13)**: damage scale を level 依存だけ残し gear 依存だけ排除に変更。scroll drop 停止 + CustomDrops 機能新設を追加。
