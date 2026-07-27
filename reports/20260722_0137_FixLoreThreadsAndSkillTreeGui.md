# Task

耐久値Loreのカテゴリ無視、スレッド装着時のLore消失・誤ったマナ表示、総合スキルツリーGUIの
見切れと移動不能を修正した。直前に承認されたValhalla運ステータスAPI更新も同じ配置へ含めた。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/stats/LoreComposer.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/ItemAssembler.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/ArsThreadLore.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/PlayerLootLuckSource.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/CraftQualityService.java`
- `TrinityForge/src/main/resources/skills/base/power_progression.yml`
- `TrinityForge/src/test/java/com/trinityforge/stats/LoreComposerTest.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/ItemAssemblerTest.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/PlayerLootLuckSourceTest.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/CraftQualitySmithingProfileTest.java`
- `TrinityForge/src/test/java/com/trinityforge/test/ValhallaAbsentClassLoader.java`
- `TrinityForge/src/test/java/com/trinityforge/progression/ValhallaSkillLevelSourceTest.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/ValhallaSkillPerkStatSourceContractTest.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/ValhallaSkillPerkStatSourceTest.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/deploy/ValhallaProgressionDeployerTest.java`
- `TrinityForge/build.gradle.kts`
- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/gui/ThreadGui.java`
- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/gui/ThreadLoreMerge.java`
- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/item/ItemKeys.java`
- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/ritual/RitualManager.java`
- `fork-handoff/arspaper/fork/src/test/java/com/arspaper/gui/ThreadLoreMergeTest.java`
- `fork-handoff/arspaper/fork/build.gradle.kts`
- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/skills/skills/Skill.java`
- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/gui/implementations/SkillTreeMenu.java`
- `reports/20260722_0137_FixLoreThreadsAndSkillTreeGui.md`

# Details

- `durability`の固定専用セクションを廃止し、`stats/lore.yml`のカテゴリ・順序・表示形式を使用するようにした。
- ArsPaperが所有するスレッドLoreを`arspaper:thread_lore`へAdventure JSON配列として記録した。
- スレッド変更時は既存TF Loreを保持し、以前のArs所有サフィックスだけを置換するようにした。
- 旧Loreの「セット」「マナボーナス」「スレッドスロット」サフィックスは初回更新時に安全に移行する。
- TF再構築と防具アップグレード儀式でもスレッドLoreを末尾へ一度だけ復元する。
- POWERツリーで既存の8方向パンを有効化し、任意の初期カメラ座標
  `menu_starting_coordinates`をValhallaMMOへ追加した。POWERの初期中心は`2,8`。
- Valhalla運連携を廃止済み`EntityStatRegistry`から現行
  `AccumulativeStatManager#getCachedStats("LUCK_BONUS", ...)`へ更新した。
- ArsPaperへテスト専用JUnit Jupiter 5.11.4を追加した。ライセンスはEPL-2.0、
  実行時JARには同梱されず、Gradleがテスト時のみ取得する。
- TrinityForge 928テスト、ArsPaperテスト、ValhallaMMO coreビルドが成功した。
- 配置後の再起動で3プラグインの有効化とPaperの`Done (67.677s)`到達を確認した。
  POWERの配備済み設定も`menu_starting_coordinates: 2,8`、`navigable: true`を確認した。
- `README.md`を確認したが、公開セットアップ手順・コマンド仕様の変更はなく更新不要と判断した。
