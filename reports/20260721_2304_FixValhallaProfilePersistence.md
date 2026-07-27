# Task

ValhallaMMO のスキルレベルが Lv0 から旧値へ戻る問題と、異常終了時に以前の保存値まで巻き戻る可能性を修正。

# Files Changed

- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/persistence/ProfilePersistence.java`
- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/playerstats/profiles/ProfileRegistry.java`
- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/ValhallaMMO.java`

# Details

- 明示的に変更されたプロフィールを空プロフィール除外判定より先に保存し、負EXPで到達した Lv0 をDBへ永続化するよう修正。
- dirty/reset/save-in-progress の共有コレクションを並行アクセス対応にし、メインスレッドとプロフィール保存スレッド間の保存判定競合を防止。
- 固定間隔スケジューラに重複していた時刻判定を除去し、`db_persist_delay` どおりの間隔で保存するよう修正。
- 終了時はプロフィールスレッドを停止して処理完了を待ち、最終同期保存後にDB接続を閉じるよう変更。非同期保存との競合による最終保存スキップを防止。

# Verification

- `mvn -o -pl core -am test -DskipTests`
- Result: `BUILD SUCCESS`
- ValhallaMMO core にはテストソースがないため、808ソースの再コンパイルで検証。
- READMEを確認し、利用手順・設定仕様の変更はないため更新不要と判断。
