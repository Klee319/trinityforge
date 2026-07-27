# Task

TFネイティブスキルツリーのノード状態と操作ボタンへ、ユーザー承認済みのValhallaMMO GUIテクスチャを割り当てて視認性を改善する。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativePerkService.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeMenu.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/SkillTreeGuiVisuals.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/NativePerkServiceValidationTest.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/SkillTreeGuiVisualsTest.java`
- `TrinityForge/README.md`
- `wiki/08-育成と解放.md`
- `resourcepack/trinityforge-skill-gui/pack.mcmeta`
- `resourcepack/trinityforge-skill-gui/README.md`
- `resourcepack/trinityforge-skill-gui/assets/minecraft/models/item/gui/*.json`（7モデル）
- `resourcepack/trinityforge-skill-gui/assets/minecraft/textures/item/gui/*.png`（7画像）
- `resourcepack/trinityforge-skill-gui/assets/trinityforge/items/gui/*.json`（7 item model）
- `resourcepack/dist/TrinityForge-SkillGUI.zip`
- `reports/20260722_0323_ImproveSkillTreeGuiTextures.md`

# Details

- Paper 1.21.11の`item_model`コンポーネントを使用し、既存CMDのグローバルdispatchを上書きせずTF名前空間からモデルを参照する構成にした。
- ノード表示を次の4状態に分けた。
  - 解放条件未達: Valhallaのロックアイコン
  - 解放可能: ノード設定本来のMaterialアイコン
  - 二段階確認中: Valhallaの確認アイコン
  - 解放済み: Valhallaの保存済みアイコン
- 前後スキルは西・東、前後ページは北・南のValhalla方向アイコンを使用する。
- 解放条件を表示前に純粋判定し、レベル不足、前提不足、排他競合、ポイント不足をLoreで区別する。条件未達ノードは確認状態へ移行せず、理由を即時表示する。
- action/value PDCは従来どおり保持するため、テクスチャ適用の有無でクリック処理は変わらない。
- 公式ValhallaMMO 1.21.4+パックをSHA-1
  `9ad5df64c86f0d5f1636e8a2bb8922dba6055b27`で検証し、ユーザーから再利用許可ありとの選択を受けた上で必要な14資産だけを抽出した。
- 生成パックはMinecraft 1.21.11用format 75、22ファイル、6,409 bytes、SHA-1
  `7d5cf722cdaa5abbeef85ed076c0f47264ef2088`。
- パック未導入・拒否時は、ROTTEN_FLESH、STRUCTURE_VOID、ARROWおよび既存Loreへフォールバックし、GUI操作を失わない。

# Verification

- TDDで新規テストを先に失敗させ、実装後に成功することを確認した。
- 同一排他ルートの後続ノードを誤って競合扱いしない回帰テストを追加した。
- `gradlew test shadowJar`: 成功。
- 全998テスト: failure 0 / error 0 / skipped 0。
- リソースパック内の全JSONを構文検証し、ZIPを再生成した。

# Deployment Note

プラグインJARとリソースパックはビルド済み。視覚変更をクライアントへ反映するには、
`resourcepack/dist/TrinityForge-SkillGUI.zip`を既存サーバーパックへマージするかHTTPSで配信し、
サーバーのリソースパックURL/SHA-1を設定する必要がある。
