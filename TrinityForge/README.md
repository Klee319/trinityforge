# TrinityForge

統合MMOサーバ（EliteMobs fork + ArsPaper fork + 自作ペットPL）の**統合アドオン中核**。
対称ダメージパイプライン・新規ステ・ヘイト値・厳選導出を担い、各forkはこのアドオンへ委譲する。

- Target: Paper API 1.21.11 / Java 21
- 設計: `../docs/`（COMBAT/ROLE/SELECTION/... 各SPEC）
- 実装計画: `../docs/IMPLEMENTATION_PLAN.md`

## 進捗

### M0 設定駆動の土台（完了）
- スキーマ検証付き Config ロード（不正値→既定値フォールバック＋明示ログ）
- ホットリロード（`/trinityforge reload`、別名 `/tf reload`）
- ドメイン別小ファイル構成（`combat/` ほか順次追加）
- ハードコード禁止。数値・バフ・レシピは全て設定外出し（`IMPLEMENTATION_PLAN.md §0`）

### M1 戦闘コア（物理成分PoC・進行中）
- 対称8stepパイプライン本体（`combat/`、Bukkit非依存の純ロジック）= COMBAT_SYSTEM_SPEC §2.1 準拠
  - 会心 / 防御率（貫通対象）/ 耐性（貫通不可）/ 補正・軽減 / flat装甲 / クランプ床 / 固定ダメージ相殺
- 既定ダメージ導出（gear非依存・レベルスケール・base係数、config駆動）
- `CombatListener` で player近接攻撃を config駆動パイプラインに流す（物理のみ）
- ユニットテスト16件（8step各段・固定ダメージcap・貫通仕様・対称合算・レベルスケール）全合格

## Build / Test

```
./gradlew build              # コンパイル + テスト + thin/shadow jar
./gradlew test               # テストのみ
./gradlew releaseAssembly    # 配布用 shadow + fork向け API jar を stage / libs 同期
```

成果物:
- **サーバー配布:** `build/libs/TrinityForge-<version>-all.jar`（shadow。sqlite-jdbc 同梱）
- **fork compile API:** `build/release/TrinityForge-api.jar`（`releaseAssembly` が各 fork の `libs/TrinityForge.jar` へ同期）
- thin classifier の通常 JAR は内部用で、配布対象外です。

`-all.jar` はTFネイティブ進行DB用の `sqlite-jdbc`（Apache-2.0）を同梱します。
進行状態は `plugins/TrinityForge/player_progression.db` に保存され、ValhallaMMOは不要です。
レベル曲線・行動EXPの権威は `plugins/TrinityForge/skills/base/*_progression.yml` です
（初回起動でJARからseed、`/tf reload` で原子的に再読込）。
プレイヤーは `/skills`（`/s`、`/tf skills`も可）、管理者は
`/tf progression <level|exp|reset|save|diagnose>` を使用できます。
レベルの直接編集は
`/tf progression level <player> <skill> <set|add|subtract> <amount> [prestige]`
です。`prestige` は省略時維持、指定時は絶対回数として扱います。
レベル低下時は要求Lvを超える通常ノードを剥奪し、現行YAML costでポイント返還します。

スキルツリーGUIはValhalla互換の54スロット構成で、上段45枠を9×5座標ツリー、
下段9枠を選択中スキルが中央になるスキルセレクターとして表示します。
8方向移動、階層型の枝配置、排他分岐/any-of合流、3状態の直線・曲角・分岐接続線、
GUI背景にはTF専用item model/fontを設定します。
対応リソースパックは `../resourcepack/dist/TrinityForge-SkillGUI.zip` です。既存サーバーパックへ
マージするかHTTPS配信してください。未適用時もバニラMaterialとLoreで操作できます。

Valhalla由来の非GUIアイテム57種、旧レシピ解放、旧CMDは削除済みです。既存ワールドの
Recipe Bookに残る`valhallammo:`キーは、サーバー停止中にリポジトリルートから次で除去できます。

```powershell
python -m pip install nbtlib
python tools\scripts\purge_valhalla_recipe_book.py "<server>\world\playerdata"
python tools\scripts\purge_valhalla_recipe_book.py "<server>\world\playerdata" --apply
```

`nbtlib`はMITライセンスで、オフラインNBT移行スクリプトだけが使用します。設定削除スクリプト
`tools/scripts/remove_valhalla_runtime_content.py`はPyYAML（MIT）を使用し、実行前の設定を
`<server>/migration-backups/`へ保存します。既存プレイヤーが所持するアイテム現物は削除しません。

## オフハンドのアイテムステータス

`stats/item-stats.yml` の各アイテムで `offhand-stats-apply: true` を指定した場合のみ、
武器・ツールのTFステータスとバニラAttributeがオフハンドでも有効になります。未指定または
`false` の場合、武器・ツールのAttributeはメインハンド専用です。防具はこの設定に関係なく、
本来の防具スロットでのみ有効になります。

### M1 追加実装（2026-07-14）
- 回避(Dodge)機構: `DodgeResolver`＋パイプラインが攻撃単位で1回判定→全成分0化（魔法含む・LD-9）
- プレイヤー被弾側 防御供給（C1 item側/LD-8 γ）: `DefenseStatBridge`（純粋・テスト済）＋`PlayerDefenseResolver`（装備防具の耐性/守備力/被ダメ軽減/回避を集約）＋`DefenseStats.combine`
- 防御 typing 確定（LD-13）: 耐性のみtyped・他は共通。vanilla装甲が魔法も軽減
- combat-level.yml 暫定修正（防具除外＋ARS_MAGIC追加。完全LD-7移行は要コード変更）
- 防御ロールキー（LD-8/I2）: `armor-defense-rate`ほか＋TF-only防御stat5種を`roll.yml`/`lore.yml`/`defense-stat-keys`へ外出し
- 仕様整合レビュー（3並列エージェント）実施＋spec差異修正（LD-13を各specへ反映・config外部化はクリーン確認）

## 次の作業
- **要外部入力**: ArsPaper fork ソース／EliteMobs fork ソース
- **要設計判断**: バインド/所有権タイミング(I4)・品質分布(I8)・ヘイトbalance(R2)・ダンジョンゲート(D2)
- **実装可(自律)**: 出血DoT(Q3=(c)・要実機検証)／item種別ごとstat roll(I7)／使用レベル執行(I3)
- combatレベル完全LD-7移行（`CombatLevelModel`のmax-of-pillars化）
- 実機サーバでの reload→ダメージ/防御反映の実地確認（Bedrock表示含む）
