# Task

TrinityForgeのValhallaMMOコード、ビルド、plugin依存を削除。

# Files Changed

- `TrinityForge/build.gradle.kts`
- `TrinityForge/src/main/resources/paper-plugin.yml`
- `TrinityForge/src/main/java/com/trinityforge/bridge/valhalla/**`（削除）
- `TrinityForge/src/main/java/com/trinityforge/progression/Valhalla*`（削除）
- `TrinityForge/src/main/java/com/trinityforge/skilltree/deploy/**`（Valhalla deployer削除）
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/Valhalla*`（削除）
- `TrinityForge/src/main/java/com/trinityforge/listeners/PrestigeConfirmListener.java`（削除）
- `TrinityForge/src/main/java/com/trinityforge/listeners/ItemDamageClampListener.java`
- `TrinityForge/src/test/java/**`（旧Valhalla characterizationテスト削除）
- `tools/config-editor/**`
- `wiki/**`

# Details

- `ValhallaMMO_1.9.3.jar`のcompile/test依存と`paper-plugin.yml` hard dependencyを削除。
- Profile/Skill subclass、reflection source、deployer、GUI横取り、crit抑止を削除。
- ArsPaper連携APIを`integration/ars/ArsProgressionBridge`へ移動。
- Config Editorの表示をTF native rewards / TF進行数式へ同期。
- Phase 1の凍結監査結果は`docs/NATIVE_PROGRESSION_16_SKILL_MATRIX.md`に保持。

# Verification

- Valhalla JARをclasspathへ置かず`clean compileJava`成功。
- `paper-plugin.yml`にValhalla dependencyなし。
