# Task

ValhallaMMO の総合（POWER）スキルツリーに8方向移動ボタンを確実に表示し、初期表示座標の残存不整合を修正する。

# Files Changed

- `external/ValhallaMMO/core/pom.xml`
- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/gui/implementations/SkillTreeMenu.java`
- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/gui/implementations/SkillTreeNavigation.java`
- `external/ValhallaMMO/core/src/test/java/me/athlaeos/valhallammo/gui/implementations/SkillTreeNavigationTest.java`

# Details

- POWER ツリーでは設定値にかかわらずナビゲーションを有効にする共通判定を追加した。
- 移動ボタンの描画とクリック処理を同じ判定へ統一した。
- 8方向ボタンをリソースパックのカスタムボタン定義に依存しない通常の `ARROW` に変更した。
- 残っていたコンストラクタの旧初期座標参照を `menu_starting_coordinates` 対応へ統一した。
- JUnit Jupiter 5.11.4（Eclipse Public License 2.0）をテストスコープで追加し、POWER 強制有効化と通常ツリーの既存挙動を回帰テストで検証した。Maven がテスト実行時に自動取得するため、サーバへの追加インストールや実行時依存はない。
- `SkillTreeNavigationTest` の修正前失敗と修正後成功、および ValhallaMMO core のビルド成功を確認した。
- 配備先 `plugins/ValhallaMMO_1.9.3.jar` へ更新クラスを適用し、適用前バックアップを作成した。
- README のセットアップ・仕様・利用方法との矛盾は生じないため更新不要と判断した。
