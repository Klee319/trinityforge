# Task

SkillTree のノード／プレステージの TF バフに、ステータス別乗算レイヤを使う乗算モードを追加。
あわせて `mining-fortune` / `fishing-luck` / `fishing-bonus` を SkillTree 由来の総合ステータスとして加算・乗算可能にした。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/skilltree/SkillNode.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/Prestige.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/PerkBuffs.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/PerkBuffResolver.java`
- `TrinityForge/src/main/java/com/trinityforge/config/domains/SkillTreeConfig.java`
- `TrinityForge/src/main/java/com/trinityforge/config/domains/LoreConfig.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/LoreLayout.java`
- `TrinityForge/src/main/java/com/trinityforge/combat/PlayerStatAggregator.java`
- `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java`
- `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/PerkBuffResolverTest.java`
- `TrinityForge/src/test/java/com/trinityforge/config/domains/SkillTreeConfigTest.java`
- `TrinityForge/src/test/java/com/trinityforge/config/domains/LoreConfigTest.java`
- `tools/config-editor/public/js/tf-skilltree.js`
- `tools/config-editor/lib/schema.js`
- `tools/config-editor/server.js`
- `tools/config-editor/README.md`

# Details

- SkillTree YAML に `multipliers.<layer-id>.<stat>: <multiplier>` を追加した。
- 同一レイヤは `Σ(multiplier - 1)`、異なるレイヤは積として、装備由来倍率と同じ最終総合ステータス経路へ統合した。
- ノードとプレステージの両方に対応し、プレステージ複数回分も従来の加算バフ同様に累積する。
- `mining-fortune` / `fishing-luck` / `fishing-bonus` を SkillTree allow-list と general buff 集計へ追加した。
- `stats/lore.yml` の乗算レイヤが持つ `stat` を Java 側でも読み、未定義レイヤと基準ステータス不一致をランタイムで防御的に除外する。
- config editor の SkillTree バフ行へ乗算チェック、倍率入力、該当ステータス用レイヤ選択を追加した。
- 同一ステータスは加算1行＋定義済みレイヤ数ぶんの乗算行を設定可能。同一レイヤ重複は拒否する。
- SkillTree 保存時に、未定義レイヤ、基準ステータス不一致、未選択レイヤ、非数値倍率を検証して HTTP 400 で保存を拒否する。
- 属性系 SkillTree バフは既存の Vanilla Attribute 適用経路を維持し、今回の総合ステータス乗算対象外とした。
- TDDで resolver/config parser のテストを追加し、`gradlew.bat test` と `gradlew.bat build` が成功した。
