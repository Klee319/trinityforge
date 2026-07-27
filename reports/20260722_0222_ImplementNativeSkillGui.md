# Task

Valhalla非依存のスキルツリーGUI、Perk解放、Prestige、運用コマンドを実装。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativePerkService.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeMenu.java`
- `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java`

# Details

- 54スロットGUIに45枠ノード表示、ページ移動、スキル切替、レベル/コスト/解放状態を実装。
- GUI識別はTF NamespacedKey PDCのみを使用。
- ノード解放は二回クリック確認、親条件、レベル条件、排他group、ポイント支払いを検証。
- PrestigeはShift+クリックで実行し、スキルreset、通常ノード返金、永続tier Perkを単一DBトランザクションへ保存。
- `/tf skills`と管理者用`/tf progression exp|reset|save|diagnose`を追加。

# Verification

- コンパイル成功。
- Perk unlock/rollback/Prestige/resetのSQLite統合テスト成功。
