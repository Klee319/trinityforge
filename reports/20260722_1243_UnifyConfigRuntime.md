# Unify Config Runtime

**Task:** Phase 2 — data-folder single authority for progression curves + atomic skilltree/catalog reload

## Files Changed
- `TrinityForge/src/main/java/com/trinityforge/progression/catalog/NativeSkillCatalog.java`
- `TrinityForge/src/main/java/com/trinityforge/config/domains/SkillTreeConfig.java`
- `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java` (`loadDataFolder`, `/tf reload` catalog reload)
- `TrinityForge/src/main/java/com/trinityforge/listeners/NativeSkillExperienceListener.java` (fishing/alchemy/enchant from catalog)
- `TrinityForge/src/test/java/com/trinityforge/progression/catalog/NativeSkillCatalogDataFolderTest.java`
- `TrinityForge/src/test/java/com/trinityforge/config/domains/SkillTreeConfigTest.java` (abort-on-lost-skill)
- `docs/NATIVE_PROGRESSION_WIRING_MATRIX.md`
- `TrinityForge/README.md`, `wiki/10-管理者ガイド-導入とコマンド.md`

## Details
- Runtime SoT is `plugins/TrinityForge/skills/base/*.yml`; classpath resources only seed missing files.
- `NativeSkillCatalog.reload` swaps the live immutable snapshot only when all 16 skills parse.
- `SkillTreeConfig.load` keeps the previous tree map when a reload candidate would drop a previously loaded skill (YAML syntax failure / unusable file).
- Fishing uses `fishing_catch.<MATERIAL>`; alchemy/enchant honor catalog flat keys when present.
- Config Editor already wrote `skills/base`; runtime now reads the same path after `/tf reload`.
