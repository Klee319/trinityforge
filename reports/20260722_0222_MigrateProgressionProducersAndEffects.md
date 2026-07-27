# Task

EXP発生源、レベル参照、Perk報酬参照をTFネイティブ進行へ切替。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java`
- `TrinityForge/src/main/java/com/trinityforge/listeners/NativeSkillExperienceListener.java`
- `TrinityForge/src/main/java/com/trinityforge/integration/ars/ArsProgressionBridge.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/CraftQualityService.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/PlayerLootLuckSource.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativePerkRewardResolver.java`
- `fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/SpellCaster.java`

# Details

- 戦闘・Ars詠唱・Ars鍛冶・採掘・伐採・農業・掘削・釣り・エンチャント・醸造・耐久消費・防具被弾EXPをTFサービスへ接続。
- `SkillLevelSource`と`SkillPerkStatSource`をSQLite実装へ差替え。
- 旧`native:`数値報酬をTF解放状態から合算し、鍛冶品質・スレッド・運へ反映。
- POWERは他スキルのレベルアップ数からEXPを得て、レベルごとにポイントを付与。

# Verification

- Valhalla APIなしで`compileJava`成功。
- EXP小数保持とPOWER連動テスト成功。
