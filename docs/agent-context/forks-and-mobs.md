# フォーク（EliteMobs / ArsPaper）とモブ系の恒久知識

この文書は、このリポジトリのフォーク統合（EliteMobs・ArsPaper）とモブ系の実装で
繰り返し踏まれてきた恒久的な落とし穴・設計上の不変条件・確定仕様をまとめたものです。
作業履歴ではなく「今後もそのまま効く事実」だけを載せています。

## 前提: 2つのフォークのソースはこのリポジトリの `.gitignore` で除外されている

- `fork-handoff/arspaper/fork/` は `.gitignore:31` の `fork-handoff/arspaper/fork/` で丸ごと除外されている。
- `fork-handoff/elitemobs/elitemobs-fork/` は ignore されていない（`.gitignore` の対象は arspaper 側の fork ディレクトリだけ）。

帰結:

- ArsPaper フォークは **クローンしただけでは存在しない**。新しい git worktree を作っても現れない。
  `git add` すると "paths are ignored" で拒否される。
- ArsPaper フォークのソース変更は **このリポジトリに一切残らない**。ワークツリーを
  `git clean` / `git checkout` / `git reset` すると消える。TF 本体側の変更を commit/push しても
  「fork の作業も保全された」にはならない。
- ArsPaper フォークは実質的に **別リポジトリ**として扱う必要がある。編集したら
  (1) commit の対象にならないことを作業報告に明記する、(2) ビルド済み jar
  （`fork-handoff/arspaper/fork/build/libs/ArsPaper-1.0.0.jar`）を成果物として残す、
  (3) fork ソースを消す操作（clean・checkout・stash）を絶対に走らせない、の3点を守る。
- EliteMobs フォーク（`fork-handoff/elitemobs/elitemobs-fork/`）はリポジトリに含まれているため、
  通常どおり commit/push できる。

## EliteMobs フォーク

### ⚠️ ダンジョン内では PlayerDeathEvent が発火しない

`MatchInstance.MatchInstanceEvents.onPlayerDamage` が致死ダメージを**キャンセル**して
`InstancePlayerManager.playerDeath(...)`（ダウン→スペクテイター→復活）へ流すため、
インスタンスダンジョン内では `PlayerDeathEvent` が一度も発火しない。

- 死亡ペナルティは `PlayerDeathEvent` では実装できない。TF は API を公開し
  （`TrinityForge#applyDeathDurabilityPenalty`）、fork の `InstancePlayerManager#playerDeath` から
  呼ぶ。**`addSpectator` より前に呼ぶこと** — TF はクリエイティブ/スペクテイターを除外するので、
  順番を間違えると無言で無効化される。
- キャンセルされた一撃分のバニラ処理（防具耐久消費など）も丸ごと消える。被弾側の上乗せに
  `ignoreCancelled = true` を張ると致死の一撃だけ抜け落ちるので、「被弾ぶんは被弾ハンドラ／
  致死ぶんは死亡ペナルティ」と役割分担すること（TF側設定は `combat/damage.yml` の
  `durability:` 節、既定 `dungeon-only: true`）。
- 致死ダメージを起点に何かを付与する実装は無限ループ経路になる。TF のリスナーは EliteMobs より
  先に走るため「致死→付与→EMがキャンセル→ダウン→復活」で無限に稼げる（防具スキルEXPが
  「デスルーラーでレベル上げ可能」になっていた実例あり。`finalDamage >= health` を弾く純関数
  ガードで対処）。**ダンジョンでは「死んだ」は「死んでいない」として扱われる。**

### ⚠️ モブidの表記ゆれ（`.yml` 付き/裸）を必ず正規化する

MagmaCore `CustomConfigFields(String,boolean)` のコンストラクタが「`.yml` を含まなければ付ける」ため、
`getCustomBossesConfigFields().getFilename()` は常に拡張子付きで返る。一方 `importmobs` が
`mob-profiles.yml` に書くキーは `stripExtension` + `sanitizeId`（残った `.` を `_` に）した裸idである。
この不一致により `MOB_PROFILE_ID` PDC に `boss.yml` が焼かれ、`mob-overrides.yml` の
ドロップ指定（`boss` と書く）が永久に一致しない、という実バグがあった。

- 正規化は fork 側 `TrinityForgeSpawnListener#resolveProfileId` と TF側
  `ConfigManager#normalizeMobId` の両方に実装済み。id を扱う新コードでは必ずこの正規化を通すこと。
- レベルテーブル向けの新軸 `mob-ids:`（EliteMobsモブid、`MobIdNormalizer` 経由）も同じ正規化を
  通す前提で作られている。

### ⚠️ インスタンスダンジョンのワールド名は毎回変わる

`WorldInstantiator.getNewWorldName` は `<設計図ワールド名>_<連番>` を返し、連番はサーバ稼働中
ずっと増え続ける（`em_xxx_1`, `_2`, `_3`...）。実ワールド名でconfigを引くと1インスタンスにしか
当たらない。

- `mob-overrides.yml` のワールドキーは「完全一致 → 設計図名（`_<数字>` サフィックス）照合」の
  2段で解決する（`MobOverridesConfig#worldScopeKey`）。ワールド名でダンジョンを識別する新機能は
  同じ罠を踏む。

### ⚠️ 未インポートのEMモブはPDCスタンプすら書かれない

`mob-profiles.yml` に無いモブは `resolveRuntimeProfile` が empty を返し、fork は PDC スタンプを
一切書かずに return する。結果、ステータスもドロップも設定不能になるが、エラーは一切出ない。
無料DL枠のダンジョンを後から追加すると必ずこの状態になる。

- `mob-import.yml` の `unknown-mobs.synthesize: true`（既定）で、未インポートのモブもスポーン時に
  ランプを実レベルで評価して自動導出する。converter は元ボスファイルの数値を読まず
  レベルだけを見るため、importmobs が焼く値と結果が同一になる。

### ⚠️ 「EM のモブか」を判定するマーカーは `MOB_PROFILE_ID` 一択（テーマや `MOB_LEVEL` では判定できない）

fork の `TrinityForgeSpawnListener#stamp` が刻むキーの性質が全部違う。

| キー | いつ刻まれるか | 所有者マーカーとして使えるか |
|---|---|---|
| `MOB_PROFILE_ID` | CustomBoss なら**必ず**（プロファイル未登録で早期 return する経路でも刻む） | ✅ **これを使う** |
| `MOB_DUNGEON_THEME` | `theme != null && !theme.isBlank()` のときだけ | ❌ `mob-import.yml` の `theme.default` は空文字なので**取り込んだモブのほぼ全部に付かない** |
| `MOB_LEVEL` | EM も刻むが `MobTypeSpawnListener` も刻む | ❌ mob-types 由来と区別できない |

`MobTypeSpawnListener` が除外条件を `dungeonTheme` の有無だけにしていたため、**テーマ未設定の
EM モブが除外を通り抜けて mob-types のプロファイルで上書きされ、`setBaseValue` なので
EliteMobs 側の HP がそのまま消えていた**（2026-07-31 修正）。

### ⚠️ `CreatureSpawnEvent` は `EliteMobSpawnEvent` より先に飛ぶ — 遅延再適用は必ず再チェックする

EliteMobs はエンティティを普通にスポーンさせた**後**にエリート化するので、
`CreatureSpawnEvent`（TF の mob-types）→ `EliteMobSpawnEvent`（fork のスタンプ）の順になる。
つまり**スポーン時点では EM のマーカーがまだ無い**。

`MobTypeSpawnListener#scheduleHealthReassert` のような「他プラグインに負けないよう1tick後に
再適用する」処理は、この1tickの間に所有権が EM へ移るため、**再適用の直前にもう一度
`MOB_PROFILE_ID` を見て降りる**必要がある。見ないとエリート化直後の個体の HP が毎回潰れる。

### ⚠️ mob-overrides の絶対値指定はレベル追従を破壊する

導入済み396体のうち265体が `level: dynamic`（入場時にプレイヤーが選んだレベルへ追従）である。
`mob-overrides` の値は絶対値なので、ここに `max-health` / `attack-power` / `flat-defense` を
書くと Lv1でもLv100でも同じ値に固定されてしまう。

- ダンジョン単位の調整はレベル非依存の率（physical/magical の `defense-rate` / `resistance` /
  `damage-reduction`）だけで行い、レベル依存の量は `combat/mob-import.yml` のランプに任せる。
  `defense-rate` は貫通で抜ける層、`resistance` は抜けない層（themes.yml のコメント準拠）。
  `armor-strength` は [0,1] の率ではない疑いがあり未検証。
- `em_adventurers_guild` の `training_dummy_*` は DPSchecker の計測器。耐性を付けると測定値そのものが
  狂うため、バランス調整の一括適用からは常に除外する。

### ⚠️ mob-overrides UI 台帳・訳表・display-name の運用ルール

- 既定EliteMobsダンジョンの台帳 `tools/config-editor/public/data/elitemobs-dungeons.json`
  （29ダンジョン396モブ + モブ無し32 content_package = 61件）は**生成物**。
  `tools/scripts/gen-mob-overrides.py` が実サーバの `plugins/EliteMobs` から生成する。手編集禁止
  （再生成で上書きされる）。ローダは `public/js/em-dungeons.js` の `window.EM_DUNGEONS`。
- 日本語名の訳表は `tools/scripts/em_ja_names.py`（DUNGEON_JA / MOB_JA / SUFFIX_JA /
  DISAMBIGUATION_JA）。同一ダンジョン内で名前が衝突したグループにだけ「第N波」等の識別子が付く。
- `display-name` は**表示専用**。戦闘計算にもスコープ解決にも一切効かない。ワールド名とモブidは
  EliteMobs 側の実体と一致していないと当たらないので editor で編集不可（`.entry-key-fixed` の
  code 表示）にしてあり、名前を変えたい要求は display-name で吸収する設計。
- `.mob-drop-row` は flex 行。その直下に `.field-grid` と `.mob-drops-section` を並べると左右に
  潰し合ってUIが崩れる。縦積みしたいものは `.mob-drop-body` ラッパを1枚挟むこと。

### ⚠️ `/em` `/elitemobs` `/ag` は TF 本体のリスナーが全ブロックしている

TF有効サーバで `/em` `/elitemobs` `/ag`（adventurersguild）が「EliteMobsのプレイヤーコマンドは
無効です。進行・ステータスは /skills を使ってください。」で**サブコマンドに関係なく全部ブロック**
されるのは、**TrinityForge本体の `com.trinityforge.listeners.EliteMobsCommandGateListener`**
（`PlayerCommandPreprocessEvent`, LOWEST）がラベル単位で `event.setCancelled()` しているため。
EliteMobsフォーク側のコマンドrouting（MagmaCore）は正常なので、フォークをいくら調べても
原因は出てこない。

- バイパス: `trinityforge.elitemobs.commands` または `trinityforge.admin` 権限を持つプレイヤーは
  免除。OP単体では不足（LuckPerms `enable-ops:true` でも明示付与が必要）。
- 解禁は権限付与のみで即時反映（再ビルド/再配備/再起動は不要）:
  `/lp user <name> permission set trinityforge.elitemobs.commands true`
- バイパスを持っていても player 経済系コマンド（`/em shop` 等）はフォークの CommandHandler が
  TF有効時に未登録なので `Unknown command` になる。実質 admin 系（`/em setup` `/em downloadall`
  等）のみ通る。

### ⚠️ フォークのテストで使う定数プール一致チェックは「クラス単位」— 同一クラス内の別メソッドが身代わりになる

`TrinityForgeGateWiringTest`（`src/test/java/.../trinityforge/`）はコンパイル済み `.class` を
生バイト読みして policy メソッド名の文字列一致を見るだけなので、**クラス全体で1回でもその名前を
参照していれば通る**。2026-08-01のミューテーションテストで実証: `CurrencyCustomLootEntry#directDrop`
からゲートを削除しても`#locationDrop`側の呼び出しが残っているだけで検出されず、
`LootTables#generatePlayerLoot`の`bonus_coins.yml`判定をリテラル`false`に差し替えても
（呼び出し自体は残るため）検出されなかった。**「戻り値を無視した呼び出しが盲点」という旧説は誤り**
（同ファイルjavadocで訂正済み）。

- メソッド単位で確認したいときは `Javap.java`（同パッケージ）を使う: 実行中JVMの
  `java.home/bin/javap -p -c -constants` を子プロセスで呼び、出力をメンバ宣言行（2スペース
  インデント固定）の境界でメソッド単位にスライスする。文字列定数（`ldc`のコメント）もそのまま
  読めるので、「どの引数が渡っているか」（例: `"bonus_coins.yml"` の有無）まで検証できる。
  `CurrencyShowerCallSiteTest` が実例。
- 挙動テスト（`EliteEntityNametagTest` 系のRecordingLivingEntity Proxy駆動）で潰せるならそちらを
  優先する。javapベースの検証は「ライブサーバ無しでは駆動できない経路」（DB/経済/実際のダンジョン
  loot生成が必要な箇所）専用の最終手段。

### ⚠️ フォークのテストで重いコンストラクタを回避する `Unsafe.allocateInstance` 技法

`MagmaCore`（`getInstance()`が private static singleton）、`EliteMobProperties`（abstract、
フィールド初期化子が `ElitePower.getDefensivePowers().clone()` 等の静的config参照を伴う）、
`CustomBossesConfigFields`（コンストラクタが `CustomBossesConfig` 経由の実config読込を要求）、
`CustomBossEntity`（コンストラクタが `EMPackage.getContent` 等サーバ依存処理を実行）は、
どれも実コンストラクタを通すとサーバ起動なしのユニットテストでは組み立てられない。

- `sun.misc.Unsafe.allocateInstance(Class)`（`theUnsafe`静的フィールドをreflectionで取得）は
  コンストラクタ・フィールド初期化子を一切実行せずインスタンスを確保する。abstract/interfaceは
  不可（最小限の空サブクラスを用意する。`FakeMagmaCore.TestJavaPlugin`、
  `EliteEntitySpawnNametagTest.TestEliteMobProperties` が実例）。
- 確保後に必要なフィールドだけreflectionまたは既存の`@Setter`（Lombok）で埋める。
  `CustomBossesConfigFields#setAlwaysShowName`のような公開setterがあればreflection不要。
- **このフォークは `spigot-api` でコンパイルされており `paper-api` ではない**
  （`build.gradle`の`compileOnly`が`org.spigotmc:spigot-api`）。`io.papermc.paper.plugin.configuration.PluginMeta`
  は存在しない。`JavaPlugin#getName()`は`final`で`getDescription()`（`final`、`PluginDescriptionFile`を返す）
  経由、その`description`フィールドを直接reflectionで書き換えるのが唯一の細工経路（`FakeMagmaCore`実装）。

### HIGH-2/HIGH-3設計メモ（2026-08-01, `TrinityForgeDungeonGateListener` / `TrinityForgeIntegration`）

- `trinityforge.yml`の`dungeon-entry-gate`（トップレベル真偽値、既定`true`）はTF連携そのものの
  緊急停止スイッチ。`false`にすると全ダンジョンの入場制限がTF側判定を一切経由せず解除される
  （権限バイパスと同格）。`TrinityForgeConfigMigration.appendMissingKeys`が既存config差分適用する。
- fail-open/fail-close は「TFに聞けた上でNOと言われた」（fail-CLOSE、`DungeonGateService`側が
  そもそも0ゲート設定でfail-openする設計なのでここで更に緩めない）と「TFに聞くこと自体ができな
  かった」（null lookupKey／サービス未解決／例外、fail-OPEN＋`Logger.warn`）を必ず分ける。
  `evaluateOrFailOpen`ヘルパ（`DungeonGateService`が`final`でモック不可なため`GateOperation`
  関数型インターフェースで例外注入用の穴を作った）に両呼び出し元を通す。

## ビルド・配備（EliteMobs / TF API 連携）

EliteMobsフォーク（`fork-handoff/elitemobs/elitemobs-fork`）は TrinityForge のクラスを
**`libs/TrinityForge.jar`**（`build.gradle`: `compileOnly files('libs/TrinityForge.jar')`）に対して
コンパイルする（実行時は実TFプラグインがsoftdepend提供、jarはコンパイル専用の thin jar・全クラス/
依存なし）。自動コピーは無く手動運用。

### ⚠️ TF の public API を変更したら `libs/TrinityForge.jar` の再生成が必須

TF側のpublic API（configアクセサ/policyメソッド等）を追加・変更したら、フォークが新APIを参照
できるよう jar を再生成して差し替えないとフォークのビルドが落ちる（`TrinityForge#applyDeathDurabilityPenalty`
などの新設APIがこの経路で必要になった実例あり）。手順:

1. `cd TrinityForge && ./gradlew jar` → `build/libs/TrinityForge-0.1.0-SNAPSHOT.jar` が生成される
   （`./gradlew releaseAssembly` がこの同期を行うタスクとしても存在する）。
2. 生成物をコピー＆リネームして `fork-handoff/elitemobs/elitemobs-fork/libs/TrinityForge.jar` に
   配置する。
3. フォークのビルドは `gradlew.bat`（unix `gradlew` は無し）。git-bashからは絶対Windowsパスで
   `cmd //c '...\gradlew.bat compileJava'` のように呼ぶ。TF本体は `./gradlew`（unix）が使える。
   両方 Java 21。TF のテストは JUnit5。

### ⚠️ EliteMobs フォークの配布jarは `build/libs` の thin jar ではなく `testbed/plugins` の uberjar

`gradlew`（sh）が無いフォークで `jar` タスクを実行すると `build/libs/EliteMobs-*-min.jar`
（classifier `-min`）が出るが、これは MagmaCore を含む依存を **minimize で剥ぎ取った thin jar**で、
単体配備すると起動時に `NoClassDefFoundError: com/magmaguy/magmacore/location/DungeonLocator` で
プラグインロードが失敗する（実サーバがこれを掴んで起動不能クラッシュした実例あり）。

- 配布すべき本物は **`shadowJar`** タスクが `fork-handoff/elitemobs/elitemobs-fork/testbed/plugins/EliteMobs.jar`
  に出力する uberjar（MagmaCore全254クラス+DungeonLocator同梱、約6.8MB。minはMagmaCoreクラス0個・
  約4.2MB）。配備前に `unzip -l <jar> | grep -c magmacore` で254付近であることを確認する。
- `MagmaCore:2.2.0-SNAPSHOT` は `repo.magmaguy.com` から取得するが、到達不可の環境では `--offline`
  （gradleキャッシュ利用）でビルドを通す。
- 差し替え時は旧jarを `.bak-...` に退避し、`plugins/.paper-remapped/EliteMobs.jar` を削除して
  Paperに再remapさせる。

## ArsPaper フォーク

`fork-handoff/arspaper/fork/`（`com.arspaper.**`、正典ツリー。`external/ArsPaper/` は古い祖先コピーで
触らない）は TF と `integration/TrinityForgeBridge.java` を窓口に統合されている。

### 統合の実態（設計前提として確定している事実）

- フォークは既に TF の `item-stats.yml` を読んでいる: `resolveCatalystStats()` / `attackPowerOf()`
  （触媒）、`resolveItemStats(material, cmd)`（スレッド、`ArmorManaListener` から）が
  `MATERIAL#CMD` キーで TF の item-stats を参照する。書き戻しは `writeAddonCombatStats()` →
  プレイヤーPDC `PLAYER_ADDON_COMBAT_STATS` → TF `combat/AddonCombatStats` が攻撃/防御パイプラインへ
  合流する。
- Ars固有ステはfork側のyml/enumで管理されている: 触媒=魔導書 `spellbooks.yml`
  （tier/slots/glyph-tier、mana-cap無し）、ワンド=`WandTier.java` ハードコード（実稼働ワンドは
  非tierの `Wand`=dominion_wand・アイテムバインド方式。`SpellWand`/`SpellBookTier` 系tier実装は
  死にコード）、グリフ `glyphs.yml`、防具4部位 `armors.yml`（mana_bonus/defense/toughness/lore
  静的文字列/ARMOR_SET_ID）、スレッド実効果=`ThreadType.java` enum + `config.yml`。
- **mana は完全にfork-local**で TF の語彙に無い。`ManaManager.getMaxMana()` = default +
  GLYPH/ARMOR/THREAD/ENCHANT の各mana PDC加算。`AddonCombatStats` は攻撃/防御キーのみを運ぶ設計で
  manaは運べない（混ぜてもドロップされる）。表示は BossBar（`ManaBarDisplay`）+防具loreの静的文字列。

### ⚠️ tier「全部config化」は誤解 — config化対象は魔導書だけ

- **魔導書 SpellBook**: config化済み（`spellbooks.yml` の `spell-books:` 順序付きリスト＝tier番号、
  PDC `BOOK_TIER` 整数=リスト位置、`SpellBookConfig`/`SpellBookTierData`）。旧enumは削除済み。
- **ワンド `WandTier`/`SpellWand`**: 死にコード。`SpellWand` はどこからも `new` されない。
  config化しても効果ゼロなので対象外（ユーザー確定事項）。
- **防具 `ArmorTier`/`MageArmor`**: 実稼働防具は既に `armors.yml`/`ArmorSetConfig`/
  `ConfigurableArmor` でconfig駆動（任意set-idキー、`/ars reload` 対応）。`ArmorTier` は旧アイテム用
  レガシーフォールバック値のみ。新規tier configを作ると二重管理になるため対象外
  （ユーザー確定事項）。

新規に「tier化」を頼まれても、この実態を再確認してから着手すること。

### ⚠️ `materials.yml` の `recipe.result` を省略すると自己破壊レシピが無言で登録される

`UnifiedRecipeLoader.loadWorkbenchFromSection` は `recipe:` ブロックの `result:` を省略すると
**`"custom:" + そのアイテム自身のid` を既定値にする**。つまり「4個→1個」のレシピを書いて
`result:` を書き忘れると「スクラップ4個→スクラップ1個」という純粋な破壊レシピが、エラーも
ログ警告も出さずに登録される（解体スクラップ8種すべてがこの状態になっていた実例あり。
lore には「4個で鉄インゴットに戻せる」と書いてあるのに実際は戻せなかった）。

- `result:` は素のバニラ Material 名も受け付ける（`RecipeManager.resolveResult` の
  `Material.matchMaterial` フォールバック）。
- materials.yml に「素材Aから別の何かを作る」レシピを書くときは必ず `result:` を明示する。
  テストで固定するときも `base_material` だけを見るのは不十分 — `recipe.result` を直接検証すること。

### ⚠️ Ars の破壊グリフはTFの採取系リスナーを丸ごと誤発動させる（未修正）

`fork-handoff/arspaper/fork/.../spell/effect/AdvancedBreakEffect.java:55`（`BreakEffect.java` も同系統）
は保護プラグイン互換のためだけに合成 `BlockBreakEvent` を発火し、`isCancelled()` の判定にしか
使わない。実処理は自前で `block.getDrops(偽のDIAMOND_PICKAXE+幸運3)` → `dropItemNaturally` →
`setType(AIR)` を行う。`setDropItems(false)` もこの自前ドロップパスには効かない。

結果、TFの採取系リスナーは「プレイヤーが手で殴った」と区別できず全部発動する。

- **農業＝永久機関(CRITICAL)**: `FarmingHarvestListener.java:74-99` はツール判定が無い（作物は
  素手で採れる設計のため）。TFが自前ドロップ＋age0再設置を予約 → 制御が魔法へ戻り同じ作物を
  再ドロップ → `setType(AIR)` 後にTFの再設置で作物が復活。鍬なし・耐久消費なしで二重ドロップ＋
  自動再植＋範囲収穫が成立する。
- **採掘＝鉱石消失(HIGH)**: `VeinMiningListener.java:76-100` にもツール判定が無く、
  `handleVeinMining` が `breakNaturally(メインハンド=杖)` するため鉱脈がドロップ0で消える。
- `TreeFellingListener.java:138` は斧を要求しているため安全。この非対称が問題の温床。
- **却下済みの修正案**: 「メインハンドが杖か」で判定する案は不可 — `SpellBindListener.java:36` は
  任意のアイテムにスペルをバインドできるため、ピッケルにバインドすれば「正しいツールを持ったまま
  魔法破壊」になり、フルドロップ＋一括破壊＋耐久ゼロという上位互換の exploit になる。
- **正しい修正方針**: フォーク側マーカ方式（player→elite 二重ダメージ対策のThreadLocalマーカと
  同じ流儀）。①フォークが合成イベント発火をマーカで挟む ②TFの採取系リスナー
  （VeinMining/TreeFelling/FarmingHarvest/Digging/break-vanilla-exp/ドロップテーブル）が冒頭で抜ける
  ③フォークが `breakEvent.isDropItems()` を尊重して二重ドロップを塞ぐ。

### ⚠️ `custom:<ArsPaperのid>` 素材は ExternalItemRegistry 登録が無いと永久にクラフト不可

TFのカタログレシピで `custom:source_gem` のようにArsPaper側のアイテムを素材指定するとき、
TFがそれを認識できるのは `com.trinityforge.stats.ExternalItemRegistry` に
`(id, Material, CustomModelData, displayName)` が登録されている場合だけ。

- レシピ登録側 `CatalogRecipeRegistrar.materialOfCustom` は3段フォールバック
  （catalog → ExternalItemRegistry → `ArsItemGiveBridge.create`）で、Ars橋渡しだけでもベース素材が
  引けるため**レシピは登録され、レシピ帳にも表示される**。しかしクラフト時照合
  `CatalogWorkbenchListener.matchesIngredient` は2段（TF自身の `catalogId` PDC →
  ExternalItemRegistry）しか無い。`ArsItemGiveBridge` は「新しいアイテムを作る」ためのものなので
  グリッド上の既存アイテムの身元確認には原理的に使えない。ArsPaperが刻むPDCは自前の `arspaper`
  名前空間で、TFの `catalogId` ではない。
  → レジストリが空だと**どのアイテムを置いても素材条件を満たせず結果スロットが永久に空**になる
  （エラーも出ない。source_gem系20品が全滅していた実例あり）。
- `external-items.yml` に手書きでコピーしないこと。`materials.yml` を編集した瞬間ずれる。
  ArsPaper が起動時/`/ars reload` 時に `TrinityForgeBridge.registerExternalItems(itemRegistry.getAll())`
  でレジストリへ push する（`registerCatalystStats` と同じ作法・同じ起動フック）。
- レジストリは層構造: `localDefinitions`（TFの external-items.yml）+ `pluginLayers`（source名キー）。
  `update()` はlocal層だけ、`updateExternalPlugin(source, map)` はその層だけを差し替える。だから
  TF reload で Ars の登録は消えず、`/ars reload` で TF 側も消えない。id衝突はlocal層が勝つ。
- Ars橋渡しにしか解決できないidは `materialOfCustom` が WARNING でid名を出す。この警告が出たら
  レジストリ未登録のサインなので、そのidをレジストリへ登録すること。
- バニラ素材が `custom:` を満たしてしまわないことがexploit防止線。Bukkit側は `MaterialChoice`
  なので素の `PRISMARINE_SHARD` でも通ってしまい、リスナーの拒否だけが防波堤になっている。

### ⚠️ `itemRegistry`/`blockRegistry` は `Map.put` の無警告上書き — 登録順序を変えると無言で挙動が変わる

`CustomItemRegistry#register`/`CustomBlockRegistry#register`（どちらも `com.arspaper.item`/`com.arspaper.block`）
は同idなら黙って上書きする。`ArsPaper#initRegistries` は `registerDefaultBlocks()` →
`registerDefaultItems()` の順で呼ぶため、後者内の `materialConfigManager.getAll()` ループ
（`materials.yml` の全アイテムを `ConfigurableMaterial` として `itemRegistry` へ登録）が
**先に登録された CustomBlock を無言で踏み潰す**。

- `materials.yml` に書いた素材id（例: `infinity_source_core`）を後から「置ける CustomBlock」に
  昇格させたいときは、この上書きを防ぐガードが必須（`ArsPaper#registerDefaultItems` /
  `#reloadMaterialConfig` に `if (blockRegistry.has(mat.id())) continue;` を追加した実装参照、
  2026-08-02）。ガードを忘れると「ブロックのJavaクラスは存在するのに、実際に生成される
  ItemStack は非設置の ConfigurableMaterial のまま」という気づきにくい退行になる
  （見た目/CMDだけが変わってエラーは一切出ない）。
- 一方で `UnifiedRecipeLoader` の `materials.yml` パース（`loadMaterialSection` 系）は
  **Java クラスの登録と完全に独立**している（`sourcejars.yml`/`sourcelinks.yml` の recipe も同様）。
  なので `materials.yml` の `recipe:` ブロックはそのまま残してよく、削除する必要はない
  ―― 素材からブロックへ「格上げ」しても既存のレシピ登録経路はそのまま生きる。

### ⚠️ NETHER_STAR 等バニラで非設置の Material をベースにした `materials.yml` アイテムは、CustomBlock 化しないと絶対に「置けない」

`materials.yml`（`MaterialConfigManager`→`ConfigurableMaterial`）で定義されるアイテムは
`BaseCustomItem` 止まりで `CustomBlock` を継承しない。`base_material` に元々ブロックとして
存在しない Material（`NETHER_STAR` 等）を指定していると、`BlockPlaceEvent` はバニラ側で
そもそも発火しないため「設置して機能させる」系の要求は、既存の素材定義を流用するだけでは
実現できない。`com.arspaper.block.impl.*`（`Waystone`/`Pedestal`/`RitualCore` 等）と同じ形で
**新しい `CustomBlock` サブクラスを起こし、`getBlockMaterial()` に `BEACON` 等 TileState を
持つ設置可能 Material を返す**必要がある（`InfinitySourceCore` が実例、2026-08-02）。
見た目(CMD)は base_material が変わるため既存のリソースパックモデルを引き継げない
（新規CMDでの追従が必要。今回は資源パック変更を対象外としたため機能面のみ先行実装）。

## モブ系（TF ↔ EliteMobs 全般）

- TF側のモブconfigはモブ**id**キーで、EntityTypeは実行時無視される: `combat/mob-defaults.yml`
  （`MobDefaultsConfig`→`DefenseStats`、プロフィール無しモブの防御既定・全0=バニラ）、
  `combat/mob-import.yml`（`MobImportConfig`→`ConversionPolicy.Ramp` = base+perLevel×level。
  レベル線形であって座標線形ではない）、`combat/mob-profiles.yml`（`MobProfileConfig`→
  `MobProfile` record、キー=EliteMobs custombossファイル名、`/trinityforge importmobs`で生成）。
  消費は `SymmetricCombatService`（entity-typeはinformationalのみで、「EntityType→ステ/レベル/
  ドロップ」は未実装）。
- PDCスタンプ（`MOB_LEVEL` 等）の付与は **CustomBoss限定**。fork `TrinityForgeSpawnListener`
  （`EliteMobSpawnEvent`）がMobProfileをPDCへ焼く。**素のバニラ/naturalモブには一切付かない**。
  判定は `MobData.hasProfile()`（=`MOB_LEVEL`有無）。
- 座標線形スケーリングはfork固有の機能: `SpawnRadiusDifficultyIncrementer.distanceFromSpawnLevelIncrease()`
  = 距離/unit×level。EliteMobsのlevelにのみ加算され、TFステとは未連動。
- ドロップ品質はfork `TrinityForgeLootListener` が `beginDropContext(enemyStrength=EMlevel)` →
  `CraftQualityPolicy.resolveDropQuality`（TF `craft-quality.yml` の `drop:` 節 + `quality.yml`）
  という既存パイプに乗る。
- fork連携はfail-open規約（`TrinityForgeIntegration` 参照）。

## 関連

- [./combat.md](./combat.md)
- [./progression-skilltree.md](./progression-skilltree.md)
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)
