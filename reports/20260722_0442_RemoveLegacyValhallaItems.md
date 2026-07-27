# Remove Legacy Valhalla Items

## Task

`/skills` GUIで使用するTF namespacedモデルを除き、ValhallaMMO由来のアイテム定義、
レシピ解放、CustomModelData、再導入ツール、既存プレイヤーのRecipe Book残骸を削除する。
ユーザー指定により、既存インベントリ・チェスト内のアイテム現物と履歴用参照ソースは保持する。

## Files Changed

- `TrinityForge/build.gradle.kts`
- `fork-handoff/arspaper/fork/build.gradle.kts`
- `resourcepack/build_skill_gui_pack.py`
- `TrinityForge/src/main/java/com/trinityforge/stats/UseSkillDefaults.java`
- `TrinityForge/src/main/resources/items/catalog.yml`
- `TrinityForge/src/main/resources/stats/item-stats.yml`
- `TrinityForge/src/main/resources/skills/base/*.yml`
- `fork-handoff/arspaper/fork/src/main/resources/materials.yml`
- `TrinityForge/src/test/java/com/trinityforge/config/LegacyValhallaRuntimeContentTest.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/UseSkillDefaultsTest.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/CatalogIdentityTest.java`
- `TrinityForge/src/test/java/com/trinityforge/combat/UseRequirementResolverTest.java`
- `tools/scripts/remove_valhalla_runtime_content.py`
- `tools/scripts/purge_valhalla_recipe_book.py`
- `.native-smoke/plugins/TrinityForge/items/catalog.yml`
- `.native-smoke/plugins/TrinityForge/stats/item-stats.yml`
- `TrinityForge/README.md`
- `wiki/10-管理者ガイド-導入とコマンド.md`

Deleted:

- `TrinityForge/src/main/resources/valhalla/languages/ja-jp.json`
- `TrinityForge/src/main/resources/skilltree/valhalla-valid-perks.txt`
- 旧Valhalla catalog import/name patch scripts 4本

## Details

- catalogから旧武器42種、矢10種、vial 5種の合計57種とeditor参照を削除した。
- item-statsから対応する武器・矢・vial CMD、Ars側から`choral_leather` CMDを削除した。
- 旧progression YAMLから`recipes_unlock`と非GUI CMDを削除した。
- `UseSkillDefaults`のValhalla代替武器CMD特例を削除し、Material分類だけにした。
- 履歴用`.bak-*`は保持するが、Gradle成果物から除外した。空の`valhalla/`もJARへ収録しない。
- ArsPaperも`.bak-*`と空ディレクトリを成果物から除外した。
- GUIパックのビルドを141ファイルの厳密allowlist方式にし、想定外資産の混入を失敗扱いにした。
- オフライン移行スクリプトは`server.properties`の`level-name`を解決し、
  `session.lock`をバックアップ・書換え・検証の完了まで保持する。取得不能時は処理を拒否する。
- GUI専用リソースパック141ファイルは変更せず保持した。
- `nbtlib` 2.0.4（MIT）を使用するオフライン移行スクリプトを追加した。
- PyYAML 6.0.3（MIT）を使用する設定削除・バックアップスクリプトを追加した。

## Live Migration

- サーバー停止を確認してから4 playerdataファイル（`.dat` / `.dat_old`）を移行した。
- `recipeBook.recipes` / `toBeDisplayed`から`valhallammo:`を1,918件削除した。
- 再検査結果: `affected_files=0 stale_entries=0`
- NBTバックアップ:
  `D:/game/minecraft/PaperServer/TrinityForge/world/migration-backups/20260722_043221/playerdata`
- plugin configバックアップ:
  `D:/game/minecraft/PaperServer/TrinityForge/migration-backups/20260722_043356/plugin-configs`
- orphaned `plugins/ValhallaMMO` データフォルダを削除した。
- `.native-smoke`の稼働configも同じ削除処理を適用した。
- 既存プレイヤー・チェスト内のアイテム現物は削除していない。

## Verification

- TF focused removal tests: 18 tests / 0 failures
- TF clean full suite: 1006 tests / 0 failures（最終再実行）
- ArsPaper tests: success
- ArsPaper JAR: 360 entries / backup paths 0 / legacy material files 0
- deployable JAR: 688 entries / forbidden paths 0 / forbidden content files 0
- deployable JAR: 15,196,036 bytes /
  SHA-256 `45ff055e2288bfda07871c23353f622b4f95ee7cd113719925593c6d4d95f9dc`
- live configs: forbidden IDs / CMD / `recipes_unlock` / `valhallammo:` 0
- GUI pack allowlist: 141 files / SHA-1 `00f5b28b1e4d473a2c202ffe12097aad631da030`
