# Task

アイテムScoreを「実際に付与されたrandomステの正規化ロール位置の平均」へ変更し、TrinityForge / ValhallaMMO の修正版JARをサーバーへ配置。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/stats/QualityScoreCalculator.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/ItemAssembler.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/QualityScoreCalculatorTest.java`
- `wiki/11-管理者ガイド-設定リファレンス.md`
- `D:/game/minecraft/PaperServer/TrinityForge/plugins/TrinityForge.jar`
- `D:/game/minecraft/PaperServer/TrinityForge/plugins/ValhallaMMO_1.9.3.jar`

# Details

- 各randomステを `(actual - min) / (max - min)` で0〜1へ正規化し、実際に付与されたロールの単純平均×100をScoreとするよう変更。
- fixed / per-quality、未付与ステ、`max == min` の定数rangeは採点対象外。
- ステごとの正規化値を0〜1へクランプし、最終Scoreを0〜100に保証。
- 合算済みステ値から逆算せず、アイテムのroll seed・品質・有効なroll modelから同じ実値を再現して採点。
- 旧JARは `plugins/.deploy-backups/20260721_233332/` に退避してから置換。
- 稼働中サーバーは停止していないため、配置したJARは次回の正常再起動で有効化される。

# Verification

- RED: 旧APIに対する新仕様テストがコンパイル失敗することを確認。
- GREEN: `QualityScoreCalculatorTest` 5件成功。
- `TrinityForge`: `gradlew.bat clean build` → `BUILD SUCCESSFUL`（全テスト成功）。
- `ValhallaMMO core`: 808ソースのMavenコンパイル成功済み。
- ValhallaMMO全リアクタービルドは旧1.19用Spigot依存が取得不能なため停止。既存配布JARへ、コンパイル済みの変更4クラスを重複なしで差し替えて配布JARを再構成。
- 配置後の両JARでZIP整合性検査成功。ValhallaMMOの差し替え4クラスはcoreビルド出力とのSHA-256一致を確認。
- READMEにはScore仕様の記載がないため変更不要。管理者ガイドの導出式を更新。
