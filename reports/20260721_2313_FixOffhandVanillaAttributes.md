# Task

`offhand-stats-apply: false` の武器・ツールをオフハンドへ持った際、バニラAttributeが有効になる問題を修正・配置する。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/stats/AttributeApplier.java`
- `TrinityForge/src/main/java/com/trinityforge/stats/ItemAssembler.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/AttributeApplierSlotScopeTest.java`
- `TrinityForge/README.md`

# Details

- 武器・ツールのAttributeスロットを常時 `HAND` にしていた処理を変更した。
- `offhand-stats-apply: false` では `MAINHAND`、`true` では `HAND` を使用する。
- `ItemAssembler` からmaterial+CMDに対応するoffhand設定を `AttributeApplier` へ渡す。
- 防具は設定値に関係なくHEAD/CHEST/LEGS/FEETの本来スロットを維持する。
- READMEへoffhand設定とAttributeスロットの仕様を追記した。

# Verification

- RED: 新規スロット回帰テストが未実装APIにより失敗することを確認。
- GREEN: OFF時MAINHAND、ON時HAND、防具スロット不変のテストが成功。
- 既存のバニラAttribute復元テストも成功。
- TrinityForge `clean build` 成功。
- 生成jarを `D:/game/minecraft/PaperServer/TrinityForge/plugins/TrinityForge.jar` へ配置し、SHA-256一致を確認。
- サーバ再起動は行っていない。
