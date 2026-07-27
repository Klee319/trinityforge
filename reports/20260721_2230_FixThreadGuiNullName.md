# Task

ArsPaper のスレッドGUIを、カスタム表示名がない防具でも開けるよう修正し、サーバへ配置する。

# Files Changed

- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/gui/BaseGui.java`
- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/gui/ThreadGui.java`

# Details

- `ItemMeta#displayName()` は表示名未設定時に `null` を返すため、`ThreadGui#createArmorInfoButton` が
  `BaseGui#createButton` へ `null` を渡し、斜体解除処理で `NullPointerException` が発生していた。
- `ThreadGui` では、コンストラクタで解決済みの表示名（未設定時はMaterial名）を `Component` 化して渡すようにした。
- `BaseGui` 側にもMaterial名へのnullフォールバックを追加し、他のGUI呼び出しからnullが渡ってもクラッシュしないようにした。
- READMEに該当GUIの仕様記載はなく、更新を必要とする矛盾はなかった。

# Verification

- ArsPaper `clean build`
- 生成jarを `D:/game/minecraft/PaperServer/TrinityForge/plugins/ArsPaper.jar` へ配置
