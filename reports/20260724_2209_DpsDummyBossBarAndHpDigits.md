# Task: DPSDummy BossBar視線ゲート + HP桁調整GUI

## Files Changed

- `fork-handoff/dpschecker/fork/src/main/java/com/github/klee319/dpschecker/dummy/DummyEntity.java`
- `fork-handoff/dpschecker/fork/src/main/java/com/github/klee319/dpschecker/gui/SettingsGUI.java`
- `fork-handoff/dpschecker/DPSCHECKER_FORK_SPEC.md`

## Details

### BossBar 視線ゲート

`updateBossBarVisibility` に `Player#getTargetEntity(range)` による視線判定を追加。
距離内かつ照準が当該ダミー本体のときのみ `showBossBar`、それ以外は `hideBossBar`。
ダミーを見ていないプレイヤーに HP ゲージが常時出る問題を解消する。

### SettingsGUI HP桁ボタン

従来の ±10 固定をやめ、`TfDefenseGUI` と同系統の桁ボタン（±1 / ±10 / ±100 / ±1000）に変更。
範囲は従来どおり 1.0〜2048.0。変更後は全回復（従来どおり）。
レイアウトは上段プラス・下段マイナスを同一カラムで対応させ、アンデッド等の既存操作枠は衝突しない位置へ再配置。
