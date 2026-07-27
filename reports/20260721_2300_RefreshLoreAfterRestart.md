# Task

Editorで変更した `score-line-template` が、再起動後も既存アイテムへ反映されない問題を修正する。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/stats/TableGeneration.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/TableGenerationTest.java`

# Details

- アイテム更新世代がプラグイン起動ごとに固定値 `1` へ戻っていたため、前回プロセスで世代 `1` を刻印された既存アイテムを誤って最新扱いしていた。
- 起動ごとに正のランダムな初期世代を採用し、前回プロセスのアイテムを確実に一度再構築するよう変更した。
- 同一プロセス内の `/trinityforge reload` では従来どおり世代をインクリメントする。
- READMEにはゲーム内反映に再読込が必要な旨が既に記載されており、更新を必要とする矛盾はなかった。

# Verification

- `TableGenerationTest` 成功。
- TrinityForge `clean build` 成功。
- 生成jarを `D:/game/minecraft/PaperServer/TrinityForge/plugins/TrinityForge.jar` へ配置し、SHA-256一致を確認。
- サーバは起動していない。
