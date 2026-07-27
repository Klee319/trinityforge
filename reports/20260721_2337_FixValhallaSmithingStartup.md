# Task

ValhallaMMO が TrinityForge の鍛冶品質パークを読み込めず停止し、その後 TrinityForge が
`NoClassDefFoundError` で停止する起動順序問題を修正した。

# Files Changed

- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/playerstats/profiles/implementations/SmithingProfile.java`
- `TrinityForge/src/main/java/com/trinityforge/bridge/valhalla/ArsBridge.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/CraftQualityService.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/CraftQualitySmithingProfileTest.java`
- `TrinityForge/build.gradle.kts`
- `reports/20260721_2337_FixValhallaSmithingStartup.md`

# Details

- 鍛冶品質パーク報酬を、ValhallaMMO がスキル設定より先に登録する標準 `SmithingProfile` に移した。
- TrinityForge による後付け `SmithingCraftProfile` 登録を停止し、重複登録と起動順序依存を解消した。
- TrinityForge の鍛冶品質参照先を標準 `SmithingProfile` に変更した。
- 標準プロファイルを参照する回帰テストを追加し、テスト時にも既存 ValhallaMMO API JAR を読み込むようにした。
- ValhallaMMO core のビルド、および TrinityForge の対象テストとビルドが成功した。
- 配置後の一時起動で ValhallaMMO と TrinityForge の有効化、および Paper の `Done` 到達を確認し、
  RCON から正常停止した。対象パーク未登録エラーと `NoClassDefFoundError` は再発しなかった。
- `README.md` を確認したが、セットアップ手順や利用仕様に変更はなく更新不要と判断した。
- 配置先の ValhallaMMO JAR には直前の弓速度修正クラスも保持した状態で更新した。
