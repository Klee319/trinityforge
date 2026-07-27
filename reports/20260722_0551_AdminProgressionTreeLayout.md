# Task

管理者向けスキルレベル編集コマンドを追加し、スキルツリーを階層型配置へ変更しました。
排他分岐とany-of合流の接続を修正し、曲角・分岐・交差を含むGUIリソースを追加しました。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/NativeProgressionAdminService.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/NativeProgressionService.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/repository/ProgressionRepository.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/infrastructure/sqlite/SqliteProgressionRepository.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/SkillNode.java`
- `TrinityForge/src/main/java/com/trinityforge/config/domains/SkillTreeConfig.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativePerkService.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/generator/SkillTreeLayout.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/generator/GeneratedPerk.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/generator/SkillTreeProgressionGenerator.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeCanvas.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeMenu.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/SkillTreeGuiVisuals.java`
- `TrinityForge/src/main/resources/paper-plugin.yml`
- `TrinityForge/src/main/resources/skilltree/{farming,smithing,ars_smithing,ars_magic}.yml`
- `TrinityForge/src/test/java/com/trinityforge/progression/NativeProgressionAdminServiceTest.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/generator/{SkillTreeLayoutTest,SkillTreeProgressionGeneratorTest,AllSkillTreesProgressionTest}.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/{NativePerkServiceValidationTest,NativeSkillTreeCanvasTest,SkillTreeGuiVisualsTest}.java`
- `tools/config-editor/public/js/tf-skilltree.js`
- `resourcepack/generate_connector_assets.py`
- `resourcepack/build_skill_gui_pack.py`
- `resourcepack/trinityforge-skill-gui/assets/{minecraft,trinityforge}/**/connection*`
- `resourcepack/trinityforge-skill-gui/README.md`
- `resourcepack/dist/TrinityForge-SkillGUI.zip`
- `TrinityForge/README.md`
- `wiki/08-育成と解放.md`
- `wiki/10-管理者ガイド-導入とコマンド.md`
- `wiki/14-スキルツリー詳細.md`

# Details

- `/tf progression level <player> <skill> <set|add|subtract> <amount> [prestige]` を追加しました。
  レベルはEXP境界へ正規化され、POWER EXP・ポイント残高・任意のPrestige tierをSQLiteの
  単一transactionで更新します。Prestigeは省略時維持、指定時は絶対回数です。
  変更後POWERで消費済みポイントを維持できない減算は拒否し、EXP付与とのread-modify-writeは
  repository共通lockで直列化します。
- `parents-any` を追加し、FARMINGとSMITHINGの合流ノードをどちらのルートからでも
  解放可能にしました。生成表現にも`requireperk_one`を出力します。
  排他判定は同じ親を持つ同group兄弟だけに適用します。
- MAINの縦幹を維持しながら、枝を親より上の層へ進める決定的配置へ変更しました。
  sibling rootを子孫より先に予約し、接続線はBFSで他ノードを避けてroutingします。
- 排他候補を前の候補へ偽接続する処理を削除し、各候補を論理親へ直接接続しました。
  複数経路が重なるセルは方向を合成して曲角、三方向分岐、四方向交差として描画します。
- ARS_SMITHINGのアイコンを`AMETHYST_SHARD`、ARS_MAGICを`LECTERN`へ変更しました。
- Config Editorへ`parents-any`編集と、ノード改名/削除時の参照追従を追加しました。
- リソースパックは240ファイル、81,854 bytes、
  SHA-1 `3b9bc3e7b35c5a87bca792424ef66f53a639e133`です。

# Verification

- `gradlew test shadowJar --rerun-tasks`: 1,016 tests passed / build successful
- Config Editor `tf-skilltree.js` Node syntax check: passed
- Resource-pack strict allowlist/reference validation: passed
- Resource-pack deterministic rebuild: SHA-1一致
