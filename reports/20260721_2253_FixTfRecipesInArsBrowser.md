# Task

TrinityForge のcatalog作業台レシピを `/ars recipes` に確実に表示する。

# Files Changed

- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java`
- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/gui/RecipeBrowserGui.java`

# Details

- Bukkit全体の `recipeIterator()` に依存していたTFレシピ列挙を廃止した。
- TrinityForge の `CatalogRecipeRegistrar#allRegistered()` から現在の登録済みキーを直接取得するブリッジを追加した。
- 各キーを `Bukkit#getRecipe` で解決し、既存の `custom:` 素材トークン復元を維持したままレシピ一覧へ追加する。
- READMEにレシピブラウザの仕様記載はなく、更新を必要とする矛盾はなかった。

# Verification

- ArsPaper `clean build` 成功。
- 生成jarを `D:/game/minecraft/PaperServer/TrinityForge/plugins/ArsPaper.jar` へ配置し、SHA-256一致を確認。
- サーバは起動していない。
