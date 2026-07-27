# Match Valhalla Skill GUI

## Task

TFネイティブの16スキル内容を維持したまま、`/skills` のレイアウト・操作・テクスチャを
ValhallaMMOのスキルGUI構成へ合わせる。

## Files Changed

- `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeCanvas.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeMenu.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/SkillTreeGuiVisuals.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/NativeSkillTreeCanvasTest.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/SkillTreeGuiVisualsTest.java`
- `resourcepack/trinityforge-skill-gui/**`
- `resourcepack/build_skill_gui_pack.py`
- `resourcepack/dist/TrinityForge-SkillGUI.zip`
- `TrinityForge/README.md`
- `wiki/08-育成と解放.md`
- `wiki/10-管理者ガイド-導入とコマンド.md`
- `wiki/12-実装状況と注意点.md`
- `wiki/14-スキルツリー詳細.md`
- `wiki/README.md`
- `docs/NATIVE_PROGRESSION_16_SKILL_MATRIX.md`

## Details

- 上段45枠を一覧ページから9×5座標ビューポートへ変更した。
- Valhallaと同じ8枠へ上下左右・斜め移動を配置し、各軸を独立して境界判定する。
- 下段45〜53番を9スキルの循環セレクターにし、選択中スキルを49番へ固定する。
- TF正典ツリーを既存の決定的レイアウト生成器から投影し、ノード・接続線・Prestigeを表示する。
- ノード解放とPrestigeを2回クリック確認へ統一した。
- `/skills [skill]` とalias `/s`を追加し、`/tf skills`も維持した。
- 8方向矢印、3状態×7形状の接続線、ノード状態、9種の旧スキルアイコン、
  Valhalla互換GUI背景fontをTF namespaced item model/fontとして収録した。
- 公式ValhallaMMO 1.21.4+パック
  （SHA-1 `9ad5df64c86f0d5f1636e8a2bb8922dba6055b27`）から、
  ユーザー承認済みのGUI関連資産のみを抽出した。実行時JAR依存はない。
- リソースパックの決定的ビルド・JSON参照検証スクリプトを追加した。

## Verification

- `.\gradlew.bat test shadowJar`: 1004 tests / 0 failures / 0 errors / 0 skipped
- `python resourcepack\build_skill_gui_pack.py`: 141 files
- `TrinityForge-SkillGUI.zip`: SHA-1 `00f5b28b1e4d473a2c202ffe12097aad631da030`

クライアントでのピクセル表示確認には、上記ZIPを既存サーバーリソースパックへマージして
Minecraft 1.21.11クライアントへ適用する必要がある。
