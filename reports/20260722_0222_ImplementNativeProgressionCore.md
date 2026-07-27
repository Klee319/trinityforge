# Task

TFネイティブ進行ドメインとSQLite永続化を実装。

# Files Changed

- `TrinityForge/build.gradle.kts`
- `TrinityForge/src/main/java/com/trinityforge/progression/core/**`
- `TrinityForge/src/main/java/com/trinityforge/progression/catalog/**`
- `TrinityForge/src/main/java/com/trinityforge/progression/repository/**`
- `TrinityForge/src/main/java/com/trinityforge/progression/infrastructure/sqlite/**`
- `TrinityForge/src/main/java/com/trinityforge/progression/NativeProgressionService.java`
- `TrinityForge/src/test/java/com/trinityforge/progression/**`
- `TrinityForge/README.md`

# Details

- 16スキルのEXP曲線、signed EXP、複数レベル遷移、上限、POWER連動、ポイントを純粋ドメイン化。
- 小数EXPを失わない`double`モデルとSQLite `REAL`列を採用。
- `player_progression.db`へスキル、ポイント、Perk、schema versionを正規化保存。
- WAL、foreign keys、busy timeout、prepared upsert、解放・Prestige・resetトランザクションを実装。
- `sqlite-jdbc 3.47.1.0`（Apache-2.0）をShadow JARへ同梱。

# Verification

- ドメイン、全16曲線、SQLite再open、fractional EXP、rollback、Prestige、player分離テストを追加。
- targeted progression tests成功。
