# Task: フィールド湧きモブのステータスを装備帯に合わせて登録

## Task

`stats/item-stats.yml` の武器 `attack-power` と `use-level-requirement` をアンカーに、フィールド敵対モブ（`combat/mob-types.yml`）の HP / 攻撃 / 貫通 / 距離レベル係数を標準体感で登録した。

## Files Changed

- `TrinityForge/src/main/resources/combat/mob-types.yml`
- `wiki/09-モブとダンジョン.md`
- `reports/20260724_1835_FieldMobGearBandStats.md`（本レポート）

## Details

### 方針

- 体感目標（標準）: 同帯ライト武器で約6ヒット、同帯軽〜中防具で約12〜20ヒット耐え
- 主戦場: use-lv 0〜40。Lv55+ フィールドは意図的に柔らかめ（高難易度はダンジョン側）
- `defaults` は動物等にも効くため、戦闘カーブは載せない（従来の軽い既定を維持）
- 個別 `mob-types` エントリは defaults を継承しないため、敵対モブごとにフル定義

### 標準カーブ（ゾンビ系）

| 項目 | 値 |
|---|---|
| `coordinate-coefficient` | 0.02（≈500blk で +10Lv） |
| `max-health` | 380 + 55×Lv |
| `attack-power` | 22 + 7.5×Lv |
| `penetration` | 0.05 + 0.008×Lv |
| `damage-modifier` | 1.0（係数 0） |

### 役割倍率

| 系統 | HP | AP | 対象 |
|---|---|---|---|
| 標準 | ×1.0 | ×1.0 | ZOMBIE / HUSK / DROWNED / ZOMBIE_VILLAGER / ZOMBIFIED_PIGLIN / PIGLIN |
| 遠距離寄り | ×0.85 | ×1.1 | SKELETON / STRAY |
| ガラス | ×0.7 | ×1.15 | SPIDER / CAVE_SPIDER |
| クリーパー | ×0.75 | ×1.0 | CREEPER |
| 硬め | ×1.4 | ×1.2 | ENDERMAN |
| 標準寄り | ×0.9 | ×1.0 | WITCH |

### 副作用・割り切り

- スポーン付近の敵対モブが従来（HP1000テスト値や攻撃未設定）より痛い／硬い可能性
- Golden フルの flat 守備はフィールド線形火力ではほぼ無効化しきれない
- `mob-import.yml`（Elite）は未変更
- 反映: プラグイン再配備後 `/trinityforge reload`（または再起動）

### README

ルート README は無し。`TrinityForge/README.md` に mob-types 数値の矛盾記述は無し。プレイヤー向けは `wiki/09` を同期済み。
