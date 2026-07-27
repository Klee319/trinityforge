# Task

Valhalla/Bukkit経由で登録されたTFレシピの情報表示を、アイテムカタログ設定に統一する。

# Files Changed

- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java`
- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/gui/RecipeBrowserGui.java`

# Details

- TFカタログレシピから表示専用ItemStackを生成するブリッジを追加した。
- 表示名、CustomModelData、革防具色、発光、固定flavor loreを `items/catalog.yml` から反映する。
- 品質で変動するステータスLoreは、品質未確定の静的レシピ画面には固定表示しない。
- レシピ詳細画面がカタログの固定Loreを消さず、レシピ情報を後置するようにした。
- READMEに当該GUI表示元の記述はなく、更新を必要とする矛盾はなかった。

# Verification

- ArsPaper `clean build` 成功。
- 生成jarを `D:/game/minecraft/PaperServer/TrinityForge/plugins/ArsPaper.jar` へ配置し、SHA-256一致を確認。
- サーバは起動していない。
