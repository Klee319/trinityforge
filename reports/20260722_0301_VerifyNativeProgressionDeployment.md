# Task

TrinityForgeネイティブ進行システム移行の最終検証、ValhallaMMO JARなし起動確認、ドキュメント同期、および段階配置。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java`
- `tools/config-editor/lib/registry.js`
- `tools/config-editor/public/js/tf-forms.js`
- `tools/config-editor/public/js/tf-skilltree.js`
- `reports/20260722_0301_VerifyNativeProgressionDeployment.md`
- 配置先: `D:\game\minecraft\PaperServer\TrinityForge\plugins\TrinityForge.jar`
- 配置先からValhallaMMO JARを退避（`plugins` 直下には残さない）

# Details

- `gradlew test shadowJar` が成功した。最終テストスイートは990件、失敗0件、エラー0件、スキップ0件。
- 配置用 `TrinityForge-0.1.0-SNAPSHOT-all.jar` は `sqlite-jdbc` を同梱し、ValhallaMMOクラスおよびValhallaMMOのPaper依存宣言を含まないことを確認した。
- ValhallaMMO JARなしのPaper 1.21.11ライブサーバーでTrinityForgeが有効化され、`Done` まで起動した。ロード済みプラグイン一覧にもValhallaMMOは存在しない。
- RCONから `/tf progression save` を実行し、ネイティブ進行DBのcheckpoint成功応答を確認した。
- `plugins/TrinityForge/player_progression.db` を読み取り専用で検査し、`PRAGMA integrity_check = ok`、schema version 1、および次の正規化テーブルを確認した。
  - `player_skill_state`
  - `player_point_balances`
  - `player_perk_states`
  - `schema_version`
- 起動時はTrinityForgeがArsPaperより先に有効化されるため、Ars所有素材を含む2件の作業台レシピが初回登録を延期する警告を出す。ArsPaperが50素材をロードした後にTFレシピを再登録し、サーバーは正常起動した。これはValhallaMMO依存ではない。
- README、Wiki、およびConfig Editorの進行システム説明をTFネイティブ仕様へ同期した。今回の最終監査では、Config Editorとメインクラスに残っていた「Valhallaが現在の所有者」と読める旧コメントだけを修正し、実行コード・設定値は変更していない。
- SQLite JDBC 3.47.1.0はApache-2.0であり、Shadow JARへ同梱する構成を`build.gradle.kts`とREADMEに記録した。

# Verification Scope

- ドメイン: EXP境界、少数EXP、負EXP、POWER/ポイント、Perk所有権、Prestige。
- 永続化: SQLite再オープン、原子的進行遷移、Prestige、リセット、checkpoint、同時アクセス保護。
- コンテンツ: 16スキル設定、EXPテーブル、全スキルツリー、Perk buff/native reward解決。
- UI/運用: 54スロットネイティブGUI、二段階解放、スキル切替、ページ切替、Prestige、`/tf skills`、`/tf progression`。
- 配置: ValhallaMMO JARなし起動、TrinityForge enable、ArsPaper連携後の再登録、ネイティブDB作成・整合性。

# Result

移行計画の6フェーズを完了。進行状態、EXP、レベル、ポイント、Perk、Prestige、GUI、および使用中の効果参照はTrinityForgeとそのSQLite DBが所有し、ValhallaMMO JARは実行依存から除去された。
