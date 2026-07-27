# DPSChecker TrinityForge Fork Spec

## Purpose

TrinityForge サーバー向けの DPSChecker fork。ダミーの防御スライダー値を
`MobData.stamp()` 経由で TrinityForge の対称ダメージパイプラインに渡し、
**実戦と同じ TF ステータス計算**で火力検証できるようにする。

## Integration

| TFステ | GUI (`TfDefenseGUI`) | PDC (`MobData.stamp` + dodge) |
|---|---|---|
| 防御率% (共通) | ✓ | `MOB_PHYS_DEFENSE_RATE` = `MOB_MAGIC_DEFENSE_RATE` (同値を両方へ) |
| 物理耐性% | ✓ | `MOB_PHYS_RESISTANCE` |
| 魔法耐性% | ✓ | `MOB_MAGIC_RESISTANCE` |
| 被ダメ軽減% (共通) | ✓ | `MOB_PHYS_DAMAGE_REDUCTION` = `MOB_MAGIC_DAMAGE_REDUCTION` |
| 守備力 (共通 flat) | ✓ | `MOB_PHYS_FLAT_DEFENSE` = `MOB_MAGIC_FLAT_DEFENSE` |
| 防具強度 (共通 flat) | ✓ | `MOB_ARMOR_STRENGTH` (step 6 flat = 守備力+防具強度) |
| 回避率 | ✓ | `MOB_DODGE_CHANCE` |

### TF pipeline alignment (2026-07-23, COMBAT_SYSTEM_SPEC LD-13 追随)

- **耐性% のみ typed**(物理/魔法別)。防御率%・被ダメ軽減%・守備力・防具強度・回避は
  **type非依存(共通)** — GUI も共通1本のスライダーに統合し、PDC へは同値を両 typed キーに書く
  (TF 側 `MobData` の読み手互換のため)。
- `armorStrength`(防具強度)は **flat 減算**(step 6: `flat = 守備力 + 防具強度`、
  攻撃側の固定ダメージで相殺可)。旧「会心ダメ軽減率」仕様は廃止(§2.1 復元)。
- バニラ armor → 防御率%、toughness → 防具強度(flat) のマッピングは TF
  `VanillaArmorMapping`/`combat/damage.yml` に委譲。
- 旧 per-type プロファイル PDC は物理側の値を共通値として読む(旧 crit 率時代の
  `armorStrength <= 1.0` は微小 flat としてそのまま許容、migration 不要)。

GUI: メインメニュー slot 17「TF防御設定」または設定画面から遷移。

## Build

```bash
cd fork-handoff/dpschecker/fork
# libs/TrinityForge.jar は TrinityForge ビルド成果物を配置
./gradlew build
```

成果物: `build/libs/DPSChecker-TF-<version>-tf.jar`

## Base fixes (upstream DPSchecker)

本 fork は以下の本体バグ修正を含む:

1. **BossBar 1ヒットずれ** — MONITOR 時点では HP 未減算のため、表示 HP を `health - finalDamage` で補正
2. **再起動後ゴースト化** — PDC からダミー再登録、`onDisable` でエンティティ削除しない
3. **再生 (undead)** — アンデッド時は仮想 REGENERATION タスクで回復 + GUI 表示

## UX notes (TF fork)

- **BossBar 視線ゲート** — 距離内かつ `Player#getTargetEntity` が当該ダミーのときのみ HP ゲージを表示
- **設定 GUI の HP** — TF防御と同様に桁ボタン（±1 / ±10 / ±100 / ±1000、範囲 1〜2048）で最大 HP を調整
