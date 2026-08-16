# フォーク（EliteMobs / ArsPaper）とモブ系の恒久知識

この文書は、このリポジトリのフォーク統合（EliteMobs・ArsPaper）とモブ系の実装で
繰り返し踏まれてきた恒久的な落とし穴・設計上の不変条件・確定仕様をまとめたものです。
作業履歴ではなく「今後もそのまま効く事実」だけを載せています。

## 前提: 2つのフォークのソースはどちらもこのリポジトリの `.gitignore` で除外されている

- `fork-handoff/arspaper/fork/` は `.gitignore:40` の `fork-handoff/arspaper/fork/` で丸ごと除外されている。
- `fork-handoff/elitemobs/elitemobs-fork/` も **`.gitignore:41` の `fork-handoff/elitemobs/elitemobs-fork/`
  で丸ごと除外されている**（2026-08-02 実機確認。以前このファイルには「EliteMobs フォークだけは
  ignore されておらず通常どおり commit/push できる」と書かれていたが**誤り**——`git status` に
  フォーク内の変更が一切出ない・`git check-ignore -v` が `.gitignore:41` を指すことで確認済み。
  以下は両フォーク共通の帰結として訂正する）。

帰結（ArsPaper・EliteMobs 両フォーク共通）:

- どちらのフォークも **クローンしただけでは存在しない**。新しい git worktree を作っても現れない。
  `git add` すると "paths are ignored" で拒否される。
- どちらのフォークのソース変更も **TF 本体リポジトリに一切残らない**。ワークツリーを
  `git clean` / `git checkout` / `git reset` すると消える。TF 本体側の変更を commit/push しても
  「fork の作業も保全された」にはならない。
- どちらのフォークも実質的に **別リポジトリ**として扱う必要がある。各フォークディレクトリは
  それ自体が独立した `.git`（`origin`=上流、`trinityforge`=Klee319の下流フォーク、例:
  EliteMobsは`https://github.com/Klee319/EliteMobs-trinityforge.git`）を持つ。編集したら
  (1) TF 本体リポジトリの commit の対象にならないことを作業報告に明記する、(2) ビルド済み jar
  （ArsPaperは`fork-handoff/arspaper/fork/build/libs/ArsPaper-1.0.0.jar`、EliteMobsは
  `fork-handoff/elitemobs/elitemobs-fork/testbed/plugins/EliteMobs.jar`）を成果物として残す、
  (3) フォークソースを消す操作（clean・checkout・stash）を絶対に走らせない、の3点を守る。
  フォーク自身の `.git` へ commit すること自体は可能だが、**push 先は必ず `trinityforge` リモート**
  （`origin` は上流で絶対に push しない）。この文書群のタスクでは通常「commit もしない」運用
  （TF本体・フォーク双方とも）が指示されることが多いので、着手前の指示を優先すること。
  **⚠️ ArsPaper は EliteMobs と remote 構成が違う**（2026-08-03 実機確認、`git remote -v`）:
  `origin` = `https://github.com/Klee319/ArsPaper.git`（Klee319自身の下流フォーク）のみで、
  `trinityforge` という名前の remote は存在しない・上流（本家 ArsNouveau 等）への参照も無い。
  つまり ArsPaper では「push 先は `origin`」が正しい（EliteMobsの命名規約をそのまま適用すると
  「pushしてはいけない」と誤読する）。ArsPaper本体リポジトリ(`Klee319/ArsPaper`)も
  **public**（`gh repo view Klee319/ArsPaper --json visibility` で確認）なので、
  `libs/TrinityForge.jar`（tracked）は他フォークと同じ理由でcommit/pushしないこと
  （既にhistory上commit済みで`git status`上は`M`として出るが、追加のcommitに含めない）。

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

### ⚠️ ネイティブ表示の抑止（`native-display-suppression`）はスポーン直後の1経路しか塞がない — 「LibsDisguises 経由」「EnderDragonの頭上バー」は別経路

`trinityforge.yml` の `native-display-suppression` は `NativeDisplayPolicy` の4メソッドで
バニラ頭上名・カスタムモデル名札・ボス追跡バーを止めるが、**それ以外にも「TF側の表示と重複する
ネイティブ表示」を出す経路が独立して複数ある**。2026-08-02 に実際に見つかった2件（修正済み）:

- **`DisguiseEntity#applyDisguise`（LibsDisguises の `PlayerDisguise` 名札）**: `CustomBossMegaConsumer#setName`
  はスポーン直後に正しく `NativeDisplayPolicy` 経由で抑止するが、`DisguiseEntity#scheduleDisguise` が
  張る **+20tick 後の再適用タスク**（`applyDisguise` を再実行）は `DefaultConfig.isAlwaysShowNametags()`
  を直接読むだけで抑止を一切経由していなかった。`alwaysShowEliteMobNameTags: true` のサーバでは
  スポーン1秒後に名札が無言で復活する。`applyDisguise` 内の可視性判定を丸ごと
  `NativeDisplayPolicy.resolveNametagVisible(...)` に通すことで、即時適用と再適用の両方を一箇所で塞いだ。
- **`EliteEntity#setLivingEntity` の `EnderDragon` 分岐**: `Wither` はネイティブのトップ画面ボスバーを
  `getBossBar().setVisible(false)` で無条件に隠しているのに、3行下の `ENDER_DRAGON` 分岐は
  `getBossBar().setTitle(...)` するだけで **`setVisible` を呼んでいなかった**。ENDER_DRAGON をベースに
  した elite/custom boss は常にバニラのドラゴン体力バーが出続け、TF の FocusHp と重複していた。
  Wither の既存パターンに合わせて `setVisible(false)` を追加。

**教訓**: 「表示抑止を1箇所直した」で終わらせず、同じ情報（名札・体力バー）を描く**エンティティ種別
固有の別経路**（LibsDisguises・EnderDragon/Witherのネイティブボスバー・ModelEngineの別ボーン等）を
横串で洗い出すこと。`NativeDisplayPolicy` に新しいメソッドを足すたびに、そのメソッドを呼んでいない
既存の類似コード（同じ情報を描く別のクラス/別のタイミング）が無いか `grep` で確認する。

**2026-08-04 に見つかった追加2件（修正済み）**: 「ゲートを呼んでいるか」の grep だけでは足りない例。

- **`DisguiseEntity#setDisguiseNameVisibility(boolean, Entity, String)` が引数を無視していた**:
  呼び出し元（`CustomBossMegaConsumer#setName` の spawn 経路、`CustomBossEntity#setNameVisible` の
  戦闘出入り経路）はどちらも `NativeDisplayPolicy` で解決済みの値を正しく渡していたが、メソッド本体が
  `((PlayerDisguise) disguise).setNameVisible(true)` と**引数を使わずハードコードしていた**ため、
  LibsDisguises の名札はボス出現・戦闘遷移のたびに無条件で再表示されていた。`grep` で
  `NativeDisplayPolicy` 呼び出しの有無だけを確認すると「呼び出し元は正しい」ので見逃す — **呼ばれた
  先のメソッド本体が引数を実際に使っているか**まで読む必要がある。
- **`CustomBossEntity#setName(String, boolean)` が `customModel.setName(name, true)` を
  ハードコードしていた**: 兄弟の `setName(EliteMobProperties)` / `setNameVisible(boolean)` は
  `NativeDisplayPolicy.resolveCustomModelNametagVisible(...)` を正しく経由するのに、この
  オーバーロードだけ経由していなかった。`setPluginName()`（ボスの名前をレベル表示込みで
  再フォーマットするたびに呼ばれる）経由で到達するため、**名前が再フォーマットされるたびに**
  `native-display-suppression.custom-model-nametag` を無視してモデルのネームタグが復活していた。
  同名メソッドの**オーバーロード違い**は片方だけ直されがちなので、`grep -n "void setName("` で
  全オーバーロードを洗って一つずつ確認すること。

### ⚠️ モブの通常攻撃を魔法として解決するには `attack.magic-ratio` を使う(Lua power 経由ではない)

※かつて「EliteMobs の premade Lua power には魔法ダメージとして判定されるものが無いので、モブに
魔法攻撃をさせるには直接ダメージAPIを呼ぶ新しい Lua power を書くしかない」と診断していたが、
これは「EliteMobs 自身のスキル(Lua power)発火経路」に限った話で**誤りではないが不完全**だった。
2026-08-02 に、EliteMobs のスキル機構を一切経由しない別軸の機構(`attack.magic-ratio`)を新設し、
「モブの通常攻撃(近接/投射)そのものを魔法として解決する」を実現した。

- **設定場所**: `combat/mob-types.yml` / `combat/mob-profiles.yml` / `combat/mob-overrides.yml` の
  `attack:` ブロックに `magic-ratio` [0.0, 1.0] を追加。既定 0.0 = 完全物理(未設定の既存モブは
  1体も挙動が変わらない)。1.0 = 完全魔法。中間値は物理/魔法の2コンポーネントへ分割し、
  回避ロールは1回のまま両方へ通す(`SymmetricCombatService#hybridComponentResult`、
  `SymmetricDamagePipeline.computeResult(List<ComponentInput>, dodgeChance)` の既存の複数コンポーネント
  対応をそのまま使う設計)。
- **`hasAttack()` のゲート外**: `magic-ratio` は「型」であって「量」ではないので、
  `MobProfile#hasAttack()`(=他8フィールドが全部ゼロなら攻撃プロファイル無しとみなすゲート)には
  含まれない。fork の `TrinityForgeSpawnListener#stamp` はこのゲートの**外**で
  `PdcKeys.MOB_ATTACK_MAGIC_RATIO` を無条件に書く — `combat/mob-overrides.yml` で
  `attack.magic-ratio` だけを単独指定しても(`attack-power` 等を一切書かなくても)効く。
- **プレイヤー側は物理防具では受けられない**: `SymmetricCombatService` は物理/魔法それぞれ独立に
  `resolveDefender` するため、`magic-ratio=1.0` のモブの一撃は物理専用装備(`phys-flat-defense` 等)の
  軽減を一切受けない。「魔法モブなのに物理防具で受けられる」は明示的にテストで固定してある
  (`SymmetricCombatServiceMagicRatioTest`)。
- **表示**: `magic-ratio > 0` のモブは頭上HPプレートに `[攻:魔]`(完全魔法)/`[攻:混]`(ハイブリッド)
  タグが付く(`FocusHpText.AttackLean`)。既存の耐性寄りタグ `[耐:物]`/`[耐:魔]`(`ResistanceLean`、
  防御軸の情報)とは別軸で、同じ名前行に並んでも混ざらない(2行レイアウトのまま)。
- `combat/mob-profiles.yml` は**保護対象としてデプロイ時に上書き除外されている生成物**なのは
  従来どおり(以下の段落参照)。新規モブに `magic-ratio` を持たせたい場合、mob-types.yml で足りる
  フィールドモブ以外は「custombosses YAML を作って `combat/mob-overrides.yml`(生成物ではない)
  で上書きする」のが唯一の安全な経路。手順は `ops/deploy-elitemobs-magic-mobs.md` 参照。

`combat/mob-profiles.yml` は**保護対象としてデプロイ時に上書き除外されている生成物**
（`/trinityforge importmobs` の出力、本番は約268KBだがリポジトリ側は空のひな形 `profiles: {}`
のみ）。リポジトリ側でこのファイルへ手書きエントリを足しても、配備スクリプトがリポジトリの
ひな形で本番の268KBを上書きしないよう既に除外されているため実質無効（詳細は
`reports/ACTIVE_RECORD.md` の該当メモ）。このファイルを編集する新規モブ追加は、本番の
`mob-profiles.yml` を直接触るか `/trinityforge importmobs` を本番で走らせる形でしか実現できない。

**ただし `/trinityforge importmobs` は必須ではない**: `ConfigManager#resolveRuntimeProfileBase` は
mob-profiles.yml に無い EliteMobs モブを `combat/mob-import.yml` のランプで自動合成する
(`mob-import.yml` の `unknown-mobs.synthesize: true` が既定、既存の「未インポートEMモブ」節参照)。
`combat/mob-overrides.yml`(生成物ではない、リポジトリ由来がそのまま効く)側で `stats.attack` を
フル指定しておけば、importmobs を一度も走らせなくても強さ・技・ドロップは正しく上書きされる —
import はあくまで「override を消したときのフォールバック値を実値にする」ための保険。

### ⚠️ `combat/mob-overrides.yml` に `abilities:` を持つモブを増減すると `ShippedBossStrengthDriftTest` が落ちる(意図的なドリフトロック)

`ShippedBossStrengthDriftTest#abilityCarrierCountIsPinned`(`TrinityForge/src/test/java/.../config/domains/`)
は出荷 `mob-overrides.yml` 全体を走査して `abilities:` を持つモブの**総数**を固定定数
(`EXPECTED_ABILITY_CARRIER_COUNT`)と突き合わせる。技持ちモブを1体でも増減すると即座に失敗する
（テスト自体が「壊れた」のではなく設計どおり — 数が変わったら理由も一緒に書けというロック）。

- 対処は定数を実数へ更新し、同じテストファイルの `@DisplayName` と定数直上の javadoc コメントの
  内訳（何+何+何=合計）も一緒に更新すること。片方だけ直すと次にこのテストを読むエージェントが
  古い内訳のまま新しい数字を信じてしまう。
- 2026-08-02: `attack.magic-ratio` を実証する派生カスタムボス6体を `default.mobs` に追加した際に
  43→49へ実際に踏んだ。

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

### ⚠️ ダンジョン鍵の消費は「GUIで選んだ瞬間」ではなく「実際に入場が成立した瞬間」でなければならない — EliteMobs委譲先には既に本物の消費フックがある

`DungeonTeleporter#teleportTo`（TF側）が `onSuccess`（鍵消費）を渡す旧設計は、EliteMobs へ委譲する
ダンジョン（`gates.yml` に `aliases`/`content-package` があるもの）では**GUIでレベル/難易度を選んだ
時点**で鍵を消費していた。この直後にワールド生成失敗・満員・権限不足等でテレポートが失敗しても
鍵は戻らない（実質ロスト）。

- 委譲先には**別の・後段の**消費フックが既に存在する: `TrinityForgeDungeonGateListener.checkDungeonEntryAllowed`
  が `DungeonInstance#addNewPlayer`（`super.addNewPlayer()` 呼び出しの直前）と直接テレポート型の
  `onPreTeleport` の両方から呼ばれ、**そこが本当の入場成立点**（2026-08-01 HIGH-2/HIGH-3 で新設済み）。
  `DynamicDungeonInstance#addNewPlayer` も `super`(`DungeonInstance`) を呼ぶので同じフックを継承する。
- 修正（2026-08-02）: `DungeonTeleporter#delegateToElitemobs` から `onSuccess` 呼び出しを削除し、
  EliteMobs 委譲先では TF 側が鍵を一切消費しない設計にした（`EliteMobsDungeonBridge.canEnter`/
  `teleport` の成否のみでメッセージを出す）。実消費は fork 側の上記フックに一本化される。
  **非委譲**（`ExplicitLocation`/`RegionCenter`/`WorldSpawn`）はこの後段フックが無いため
  `teleportTo` の即時消費のまま変更していない（同等のfork側再検証経路が存在しないため）。

### ⚠️⚠️ 消費を「権限バイパスのあるメソッド」へ移すと、OP には消費が一切走らなくなる

上の 2026-08-02 の移設の直後に「ダンジョンの鍵が消費されなくなった」と実サーバから報告が来た
（2026-08-03 修正）。移設先の `DungeonGateService#checkRequiredEntry` は先頭に

```java
if (player.hasPermission("trinityforge.admin")
        || player.hasPermission("trinityforge.elitemobs.commands")) return true;
```

という早期 return を持っており、**`trinityforge.admin` は `paper-plugin.yml` で `default: op`**。
つまり OP は全員この行で返り、その下にある**唯一の鍵消費コードを丸ごと飛び越す**。
移設前は `DungeonEntryGui` 側の `consumeKey` に権限バイパスが無かったので OP でも消費されていた。
「移設元では消費しなくなったが、移設先では権限で素通りしていた」という形。

- 修正: 権限バイパスを**「拒否されるはずだったときの救済」だけに狭めた**。判定は全員に走らせ
  （条件を満たしていれば OP からも鍵を消費し）、判定に落ちた場合に限り権限保持者を通す。
  二相評価は「拒否時は何も消費しない」を保証しているので、救済経路で鍵だけ失うことはない。
  権限保持者には拒否メッセージを出さず「権限で通過した」旨だけ出す。
- **教訓**: 副作用（消費・付与・記録）を別メソッドへ移すときは、移設先の**先頭にある早期 return を
  全部読むこと**。特に権限チェックは「拒否の緩和」のつもりで書かれていても、副作用の実行位置より
  手前にあれば副作用の抑止にもなる。`default: op` の権限は開発者自身が必ず持っているため、
  自分でテストすると**必ず素通りする側**に落ちて再現しない。

### ⚠️⚠️⚠️ 上の TF 側修正だけでは直らない — 呼び出し元のフォークに「もう1つの」同型バイパスが残っていた

2026-08-03、TF側 `DungeonGateService#requiredEntry` を上記のとおり修正した（commit `cf15493`）
**後も実機で鍵が消費されない**ままだった。原因はフォーク側の唯一の呼び出し元
`TrinityForgeDungeonGateListener`（`fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/trinityforge/`）
自身が、TF を呼ぶ**手前**で同じ2権限（`trinityforge.admin`/`trinityforge.elitemobs.commands`）を見て
早期 `return true` していたこと（`checkDungeonEntry` 82行目付近、`checkConfiguredTeleportAllowed`
112-113行目付近）。TF側の「判定に落ちたときだけ救済する」形の安全な二相評価まで**制御が一度も
到達しない**ため、TF側をどれだけ正しく直しても効果が出ない。

- **教訓**: 「副作用を持つメソッドへの権限バイパス」はメソッド内部だけでなく、**呼び出し元チェーン
  全体**（fork→TF の複数プラグイン境界をまたぐ場合は特に）を辿って全箇所を洗うこと。同じ権限文字列
  (`trinityforge.admin` 等)で `grep -rn hasPermission` をフォーク側にも必ず当てる。片方だけ直して
  「直したはず」を報告すると、実機では直っていない。
- 修正（2026-08-03）: フォーク側の2箇所の早期 return を削除し、TF の `DungeonGateService` へ
  無条件に素通しさせる形にした。`checkDungeonEntryAllowed`/`previewDungeonEntryAllowed`
  （鍵消費あり/なしの二相）が呼ぶ `checkRequiredEntry`/`previewRequiredEntry` は上記のとおり
  内部で権限救済を持つので二重管理にならない。`checkConfiguredTeleportAllowed`
  （`onPreTeleport` 経由、`/em spawntp`・ギルド・NPC帰還等ダンジョン参加以外のテレポート専用）が呼ぶ
  `DungeonGateService#checkEntry`/`checkRegionEntry` は**そもそも権限救済を持たない設計**
  （TF自身の `DungeonGateListener`＝`PlayerTeleportEvent`/`PlayerMoveEvent` 側もゲート判定に
  管理者バイパスを一切持たない対称設計）なので、フォーク側だけが独自の管理者バイパスを持つのは
  元から一貫性を欠いていた。この経路はダンジョン入場そのものとは別ルート
  （`DungeonInstance#addNewPlayer`/`checkDungeonEntryAllowed` の実テレポートは素の
  `player.teleport(...)` を使い `PlayerPreTeleportEvent` を経由しない）なので、削除しても
  ダンジョン入場の二重ゲート化は起きない。
- フォーク全体を `grep -n hasPermission` で洗った結果、TF の判定を飛ばす早期 return は
  上記2箇所のみだった。他の `hasPermission` 呼び出しは全て EliteMobs 自身の権限系
  （`elitemobs.*`／NPC・アリーナ・ワームホール等の `getPermission()`）で、TF連携とは無関係。

### ダンジョン名のプレイヤー表示は `gates.yml` の `display-name`（TF側）が一次情報源。EM側の `getName()`(=`content-packages`の`name:`)と別々に存在するので、両方直さないとID表記が残る

TF側 `DungeonEntryGui`（潜入確認画面）は元々 `gate.world()`（=ゲートID、EliteMobs委譲先では
content-package名と同じ文字列）をそのまま表示していた。2026-08-02 に `DungeonGate` へ
`displayName` フィールド（8番目のcomponent、旧7引数コンストラクタは後方互換で `displayName=null`
委譲）を追加し `gates.yml` の全61ゲートへ `display-name:`（`tools/scripts/em_ja_names.py` の
`DUNGEON_JA` から注入）を設定、`displayNameOrWorld()`（未設定ならゲートIDへフォールバック）
経由で表示するよう修正した。

- **これはTF側の画面だけを直す**。EliteMobs委譲後にフォーク自身が描く画面（ダンジョン
  install/uninstallメッセージ等）は別の表示源 `ContentPackagesConfigFields#getName()` を持つ。
  `WorldInstancedDungeonPackage#doInstall`/`DynamicDungeonPackage#doInstall` はこれを無視して
  **`getFilename()`（生ID）**を `$name` プレースホルダに埋めていた非対称バグがあり（`doUninstall`
  側は元から `getName()` を正しく使っていた）、2026-08-02 に `getName()` へ統一して修正した。
  `InstancedDungeonBrowser`/`DynamicDungeonBrowser`（メニュー本体）は元から `getName()` を
  正しく使っており問題なし。
- 教訓: 「ダンジョン名がID表記」の症状は表示元が2つ（TFの`gates.yml display-name`とEM自身の
  `ContentPackagesConfigFields.getName()`）あるため、片方だけ直しても症状が残ることがある。
  新しいID露出を探すときは両方の画面フローを洗う。

### ⚠️ ダンジョンのボスダメージランキングは「TFが最終ダメージを確定する前」の値を拾っていた

`TrinityForgeCombatListener#onEliteDamagedByPlayer`（NORMAL、EliteMobs内部処理の一部）は
`event.setDamage(vanillaBase)` でバニラ基礎値に巻き戻してから return するが、EliteMobs内部は
この直後に `addDamager(player, vanillaBase)` を呼んでランキングへ積む。**本当の最終ダメージ**は
その後 HIGH 優先度の TF 側 `CombatListener`（`listeners/` パッケージ、編集禁止）が同じ生イベント
に対して計算する。つまりランキング集計点とダメージ確定点がイベント優先度で1段ずれていた。

- 修正（2026-08-02）: 同クラスに MONITOR 優先度の `onRawDamageFinalized` を追加。
  `onEliteDamagedByPlayer` 側は予約 Map（`pendingDamagerCorrections`、entityUUID→(player,記録した
  vanillaBase)）に控えるだけにし、MONITOR で `event.getFinalDamage()`（=HIGHまで確定済みの真値）
  との差分を `addDamager(player, delta)` で追加補正する。`addDamager` が加算式なのでこの形でしか
  補正できない（セッターが無い）。イベントが途中で cancel された場合は補正をスキップしつつ Map
  からは必ず除去する（残すとリーク兼誤補正の温床になる）。
- 同じ `damagers` map は `AdvancedAggroManager` のヘイト計算にも使われるため、この修正はダメージ
  ランキング表示だけでなくモブの狙う対象の精度も間接的に直す。

### EliteMobs には公式の高品質な日本語翻訳が同梱の `/em language japanese` コマンドで手に入る（コード修正不要）

`LanguageCommand`（`REMOTE_LANGUAGES` に `"japanese"` を含む）が
`https://magmaguy.com/api/elitemobs_translations/japanese.csv` から公式翻訳CSVを取得し、
`plugins/EliteMobs/translations/japanese.csv` へ保存、`DefaultConfig#setLanguage` 経由で
config書き込み＋自動 `/em reload` まで一括で行う。2026-08-02 に実ファイルを取得して検証:
26,303エントリ中 25,708件（97.7%）がバニラ英語と異なる実翻訳（`taunt`/`yggdrasil`/
`enchantment_challenge` 系を含む戦闘台詞・ダンジョン名も網羅）。

- モブ台詞（`custombosses` の `phrases`/`greetings`/`dialog`/`farewell`）はいずれも
  `translatable(...)` でラップ済み（`NPCChatBubble`/`CustomBossesConfigFields` で確認）なので、
  Java側のハードコード翻訳表は不要かつ有害（二重管理源になる）。管理者が
  `/em language japanese` を一度実行するだけで解決する運用問題であり、コード修正の対象ではない。

### 一部ダンジョン（エンチャント試練11-20・ユグドラシル等）が入れないのは EliteMobs 自身の Nightbreak 有料/無料コンテンツ配布ゲート — TF↔EM連携のバグではない

`PremiumEnchantmentChallengesMetaPackage`/`FreeEnchantmentChallengesMetaPackage`
（`config/contentpackages/premade/`）は試練1-10（無料）と11-20（premium）を明確に分けた別
content package として定義されている。`YggdrasilRealm` も同様に `DYNAMIC_DUNGEON` 型の premade
package で、Nightbreakの `NightbreakAccount`/`NightbreakContentManager` を経由したダウンロード権限
（`DownloadAllContentCommand`）が無いとワールド設計図自体が存在せず入場できない。

- `gates.yml` 側の設定（`content-package`/`aliases`）はこれら61ダンジョン全てに既に揃っており
  正しい。「入れない」の原因はTF側の入場判定ではなく、フォーク側のコンテンツ本体
  （`world_blueprints/`）が未ダウンロードであること。運用者が `/em downloadall` 等で
  Nightbreak権限相当のコンテンツを取得すれば解消する、TF連携とは独立した論点。

### ダンジョン難易度選択（levelSync/difficultyID）は実際にゲームプレイへ反映される — 3つの独立経路で消費される、死にコードではない

`DungeonInstance#setDifficulty` が `contentPackagesConfigFields.getDifficulties()`（yml の
`name`/`levelSync`/`id` を持つマップのリスト）から選ばれた難易度を解決し `levelSync`（int）と
`difficultyID`（String）をインスタンスへセットする。この2値は少なくとも3箇所で消費される
（2026-08-02 実コード確認）:

1. `PlayerItem#setItem`: `dungeonInstance.getLevelSync() > 0` のとき、プレイヤーの実効武器/防具
   ティア（ダメージ計算に使う `itemTier`）を `levelSync` で上限クランプする（オーバーレベル装備の
   弱体化＝レベル同期）。
2. `ElitePowerParser`（`mobconstructor/custombosses/`）: `InstancedBossEntity` へ付与する
   エリートパワーのうち、そのパワー定義に `difficultyID:` リストが指定されているものは
   `instancedBossEntity.getDungeonInstance().getDifficultyID()` が一致しないとスキップされる
   （難易度別にボスの技構成が変わる）。
3. `EliteCustomLootEntry#(difficultyID条件)`（`items/customloottable/`）: ドロップ表エントリに
   `difficultyID:` が指定されている場合、現在のダンジョンインスタンスの `difficultyID` と一致しない
   と抽選対象から外れる（難易度別ドロップテーブル）。

機構自体は生きている（3箇所いずれもデータ駆動で死にコードではない）が、**実際に差が出るかは
個々のダンジョンのYAMLが `difficulties:`/`difficultyID:` を実際に書き分けているか次第**。
ボスHP/攻撃力そのもの（TFが駆動する側）は `levelSync`/`difficultyID` を直接参照しておらず、
別軸（EliteMobsモブレベル→TF `combat/mob-import.yml` ランプ）で決まる点は要区別。

### ⚠️ `CustomLootTable` は `CustomBossDeath` 以外からも呼ばれる — boss-unique-loot ゲートは宝箱/アリーナ報酬を守らない

`items/customloottable/CustomLootTable`（`EliteDropPolicy` が守る「エリート戦利品」の実体）は
`CustomBossDeath#doLoot`（ボス死亡ドロップ）だけでなく、`TreasureChest`（ダンジョン宝箱、
`treasureChestDrop`/`treasureChestDropAtLevel`/`treasureChestDropScalableToPlayerLevel`）、
`CustomArenasConfigFields`（ギルドアリーナのウェーブ報酬、`arenaReward`）、`QuestReward`
（クエスト報酬、`questDrop`）の4箇所から `new CustomLootTable(...)` されて使われる。
`shouldDropBossUniqueLoot()` は `CustomBossDeath` 経路にしか刺さっておらず、残り3箇所は
無条件で素通りしていた。2026-08-04 に `treasure-chest-loot` / `arena-loot` の専用ゲートを
`EliteDropPolicy` へ追加して塞いだ（`TreasureChest` は `DungeonInstance`/`DynamicDungeonInstance`
と直結したダンジョン専用フィクスチャ、`ArenaInstance` も `MatchInstance` 継承のインスタンス戦闘
コンテンツなので両方「ダンジョンの EliteMobs 側アイテム」の対象。`QuestReward` はダンジョンに
紐付かない汎用報酬系のため意図的に対象外のまま）。**「ボスのドロップだけ塞げば十分」という
思い込みは禁物** — `CustomLootTable` を new する箇所を `grep -rn "new CustomLootTable("` で
毎回全部洗うこと。

### ⚠️ `TrinityForgeConfigMigration` はトップレベルキー単位でしか差分検出しない — 既存サブキーの既定値変更は稼働中サーバーに絶対に反映されない

`TrinityForgeConfigMigration#appendMissingKeys`（javadoc に明記）は「シップされた yml にあって
既存ファイルに無いトップレベルキー」だけを追記する。`elite-drop-sources:` のように**既に
トップレベルキー自体が存在するセクション**の中で、既存サブキー（例: `currency-shower`/
`boss-unique-loot`）の**既定値だけを変える**変更は、そのセクション丸ごとスキップされるため
**一切追記されない**。つまり出荷 yml の既定値を `true→false` に変えても、既にプレイしている
サーバーの `plugins/EliteMobs/trinityforge.yml` は古い値 (`true`) のまま永久に残る —
`jar 差し替え + サーバー再起動` だけでは効かず、**運用者が手でその行を書き換える必要がある**。
一方、同じセクション内に**新しいサブキー**を足す場合（例: `treasure-chest-loot`）は、キー自体が
存在しないので `yaml.getBoolean(key, codeDefault)` のコード側デフォルトがそのまま効き、
ファイルへの追記は不要（見た目上は何も変わらないが正しく既定値どおり動く）。
**この非対称を混同しないこと**: 「新設キー」は無言で正しく動くが、「既存キーの既定値変更」は
無言で効かない。既存キーの意味を変える改修をしたら、必ず「稼働中サーバーの config に当てるべき
diff」を作業報告に明記すること。

### ⚠️ emloot（共有戦利品テーブル）は 2026-08-09 以降 EliteMobs 側からは絶対に生成されない — 唯一の生成点は TF 追加ドロップの橋

`SharedLootTable`（`/em loot` の need/greed）を作る経路は上流では
`EliteCustomLootEntry#addGroupLoot` の 1 本しかなく、そこへ到達するには
`CustomBossDeath` の戦利品配布が走っている必要がある。2026-08-09 に
`elite-drop-sources.boss-unique-loot` を **false 確定**（コード既定も反転）にしたため、
**その配布自体が二度と走らない = EliteMobs 由来の emloot は構造的にゼロ**になった。
「emloot が出ない」のは設定ミスではなく仕様。

need/greed という機構だけは複数人ダンジョンで活かす方針なので、中身を TF の追加ドロップに
差し替えてある。経路は片方向で、**TF → フォーク**：

- TF 側 `com.trinityforge.mobs.EliteMobsSharedLootBridge#deliver(EntityDeathEvent, ItemStack)`
  （リフレクション、`EliteMobsInstanceBridge` と同じ作法）を
  `MobOverrideDropListener` / `MobLevelTableListener` が `event.getDrops().add(stack)` の
  **代わりに**呼ぶ。
- フォーク側 `com.magmaguy.elitemobs.trinityforge.TrinityForgeSharedLoot#offerDungeonLoot(Entity, ItemStack)`
  が引き取り条件を判定する: **エリートモブ / ダメージ寄与者が 2 人以上 / そのうち誰かが
  `DungeonInstance` の中**。1 つでも欠ければ `false` を返し、TF 側が従来どおり地面へ落とす。
- 引き取った場合は `SharedLootTable#addTrinityForgeLoot`（`addLoot` とは別メソッド）へ入る。

**踏み抜きやすい点が 3 つある。**

1. **クラス名・メソッド名・引数型を変えると TF 側は無言で fail-soft に戻る**（コンパイルは通る）。
   フォーク側 `SharedLootTableTrinityForgeTest#offerDungeonLootSignatureMatchesTheTrinityForgeBridge`
   が署名を固定しているので、名前を変えるならそのテストと TF 側の定数を同時に直す。
2. **`SharedLootTable#rollLoot` の EliteMobs 後処理（`SoulbindEnchantment` /
   `EliteItemLore` / `EliteItemManager#setEliteLevel`）を TF アイテムに掛けてはいけない。**
   ロアは rollSeed+品質から毎回導出されるので上書きすると二重表示になり `reload` でも直らず、
   エリートレベルは TF の必要レベルと別物、束縛は素材を取引不能にする。ガードは
   `trinityForgeLoot`（インスタンス同一性の `IdentityHashMap` セット）で、
   同テストが javap スライスで固定している。
3. **`remove-drops` は対象外。** あれはモブ本来のバニラドロップを削る処理で TF 追加分ではない。

## ビルド・配備（EliteMobs / ArsPaper / TF API 連携）

EliteMobsフォーク（`fork-handoff/elitemobs/elitemobs-fork`）と ArsPaper フォーク
（`fork-handoff/arspaper/fork`）はどちらも TrinityForge のクラスを
**`libs/TrinityForge.jar`**（`build.gradle(.kts)`: `compileOnly(files("libs/TrinityForge.jar"))`）に対して
コンパイルする（実行時は実TFプラグインがsoftdepend/hard depend提供、jarはコンパイル専用の thin jar・
全クラス/依存なし）。ArsPaper は TF に `join-classpath: true` で hard-depend しているため、
古い jar のままだと **`compileJava` がそのままコンパイルエラーで落ちる**（EliteMobs 側は
実行時 `NoClassDefFoundError` になるだけの場合もあるが、ArsPaper は静的解決なのでビルド自体が通らない）。

### ⚠️ TF の public API を変更したら `libs/TrinityForge.jar` の再生成が必須（両フォーク共通）

TF側のpublic API（configアクセサ/policyメソッド等）や `com.trinityforge.**` 配下の新規クラス
（例: 2026-08-02 の `com.trinityforge.stats.RandomRollPool`）を追加・変更したら、フォークが新APIを参照
できるよう jar を再生成して差し替えないとフォークのビルドが落ちる（`TrinityForge#applyDeathDurabilityPenalty`
や `RandomRollPool` 新設がこの経路で必要になった実例あり）。**"config のミスに見える" フォーク側の
コンパイルエラーの多くは、実は単にこの jar が古いだけ。** 手順:

1. `cd TrinityForge && ./gradlew releaseAssembly --offline "-Dorg.gradle.java.home=..."` を実行する。
   このタスクが `shadowJar`（配布用 `-all.jar`）と `apiJar`（フォーク向け curated API jar）を
   ビルドしたうえで、**thin jar（`archiveClassifier = "thin"`。全クラス同梱・依存ゼロ）を
   `fork-handoff/{elitemobs/elitemobs-fork, arspaper/fork, dpschecker/fork}/libs/TrinityForge.jar`
   へ自動コピーする**（3フォーク全部を一度に同期する。個別に `./gradlew jar` だけ叩いても
   `build/libs/TrinityForge-0.1.0-SNAPSHOT-thin.jar` が更新されるだけでフォークへは配られない
   ── 旧手順書は `TrinityForge-0.1.0-SNAPSHOT.jar`（classifier無し）という古いファイル名を
   前提にしていたが、現行 build.gradle.kts では jar タスクに `archiveClassifier.set("thin")` が
   付いており、その名前のファイルはもう出ない）。
   `apiJar`(`com.trinityforge.integration/api/pdc/combat.AttackStats` だけの狭い公開面)は
   フォークが TF 内部へ深く踏み込む(combat/config/stats/progression/mobs/hate)ため
   コンパイルには使えない ── これを使うのは配布用 `TrinityForge-api.jar` のみ。
2. フォークが git worktree 側にしか無い場合は releaseAssembly が
   「フォークが見つからないので配れなかった」と警告するだけで silently skip する
   （フォークは `.gitignore` 除外なので worktree に存在しない）。**メインのワークツリーで
   releaseAssembly を打ち直す**必要がある。
3. フォークのビルドは ArsPaper/EliteMobs とも Windows 環境では `gradlew.bat` を使うこともできるが、
   Git Bash からは `./gradlew`（unix ラッパー）がそのまま動く（ArsPaper で確認済み）。
   `cmd //c '...\gradlew.bat compileJava'` は `gradlew`(sh) が無い環境向けの代替。
   TF本体・フォークとも Java 21。TF/ArsPaper のテストは JUnit5。

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

### ⚠️ dedicated-effects チャネルと stat語彙チャネルの二重登録は無警告で合算を2倍にする（実例: ars-tier-bonus、2026-08-13修正済み）

`TrinityForgeBridge.tfArsTierUnlockBonus`（修正前、`TrinityForgeBridge.java:2274`付近）は
`tfEffectValue(player, "ars-tier")`（`DedicatedEffectsConfig.valueSum` 経由、skilltree ノードの
`dedicated-effects: [{id: ars-tier, value: N}]` 配置だけを合算する旧チャネル、perk 保有のみ加算）と
`tfNativeArsDouble(player, "unlockedTier")`（`ArsNativeBridge.unlockedTier`、stat語彙
`ars_tier_bonus` 経由でパーク general + 永続バフ + 役職バフ + base-stats を合算する現行チャネル）を
**両方**加算していた。`TrinityForge/src/main/resources/skilltree/ars_magic.yml` のノード A（10-44行）・
E（88-102行）は `buffs: {ars-tier-bonus: 1}` と `dedicated-effects: [{id: ars-tier, value: 1}]` を
**同じ値で両方**置いていたため、この2ノードを保有するプレイヤーの実効tier加算が意図の2倍
（正しくは+2のところ+4）になっていた。`GateEffectId.parse`（TF側）は `"ars-tier"` を有効な FLAG
として受理するため、姉妹キー `glyph-slot-plus`（受理されず常に0を返す「死んでいる」dedicated-effect、
`TrinityForgeBridge.EFFECT_GLYPH_SLOT_PLUS` の javadoc参照）と違って気づきにくい。

- **How**: 新しい dedicated-effects id を stat語彙（`PlayerStatAggregator`/`ArsNativeBridge` が
  合算する側）へ移行するときは、呼び出し側で旧チャネルを完全に切り離すこと
  （`tfArsTierUnlockBonus` は `tfEffectValue(EFFECT_ARS_TIER)` を撤去し `tfNativeArsDouble` のみに
  一本化した。`EFFECT_ARS_TIER` 定数自体は説明用に残置、他の呼び出し元は無い）。
  新しい perk buff キーを追加するたびに `skilltree/*.yml` を `grep -rn "dedicated-effects"` して、
  同じノードに同じ効果を `buffs:` と `dedicated-effects:` の両方で重ねて置いていないか確認する。

### マナ回復系8キーは `combat/base-stats.yml` の生値しか読まない — 装備/perk へ足しても無言で無視される（意図的）

`mana-max-base` / `mana-regen-base` / `mana-regen-interval-ticks` / `mana-onhit-percent` /
`mana-onattack-percent` / `mana-idle-seconds` / `mana-idle-bonus-percent` / `mana-idle-bonus-flat`
の8キーは `ManaBaseStats`（`fork-handoff/arspaper/fork/.../mana/ManaBaseStats.java`）が
`TrinityForgeBridge.manaBaseStatRaw`/`manaBaseStatSource` 経由で **base-stats.yml の生値だけ**を
読む。`PlayerStatAggregator`/`statTotal`/`nonItemStatTotal` は一切通らないので、item-stats.yml や
skilltree の `buffs:` にこれらのキーを足しても実行時は完全に無視される（エラーも警告も出ない）。

- 2026-08-13時点、`StatVocabulary` はこの8キーのうち5キー（onhit/onattack-percent・idle-seconds・
  idle-bonus-percent/flat）を今も `GENERAL_KEYS`（本来は装備/perk合算対象のチャネル）へ登録し、
  `stats/lore.yml` にも表示定義を残している。残り3キー（mana-max-base/mana-regen-base/
  mana-regen-interval-ticks）は同日 `StatVocabulary.BASE_STATS_ONLY_KEYS` へ明示的に切り出され、
  `stats/lore.yml` からも削除された（同ファイルの該当箇所コメント参照）。**残り5キーは
  同じ「base-stats専用」の実態を持つが、この整理がまだ及んでいない**（config-editorの
  `public/js/labels.js` は「ManaBaseStats.onHitPercent経由でフォークが読む」のように base-stats専用
  である旨を明記済みなので、意図的な設計であって合算漏れのバグではない）。
- **How**: この8キーに item/perk 由来の合算を実装しようとしないこと（フォーク側の消費経路を
  全部作り直す規模の変更になる）。表示定義の整理（残り5キーを `stats/lore.yml` から外す/
  `BASE_STATS_ONLY_KEYS` へ追加する）は `stats/lore.yml` の編集権を持つレーンの管轄。

### ⚠️ `SpellCaster.cast` の `catalyst` 引数はバインド詠唱では「杖」ではなく「バインド先の魔導書」になる — null チェックだけで経路を切り分けられない

`SpellCaster#cast(caster, recipe, sharedSpell, catalyst, castItem)` の5引数版で、`catalyst` と
`castItem` は別物（D6、`SpellCaster.java:151` javadoc参照）。呼び出し元3経路で意味が違う:

| 呼び出し元 | `catalyst` | `castItem` |
|---|---|---|
| `SpellWand#castSpell`（旧tier実装、死にコード） | 杖自身 | `null` |
| `item/impl/SpellBook#onRightClick` | 魔導書自身 | `null` |
| `SpellBindListener#onRightClick`（TFカタログ杖10本はこの経路） | `heldCatalyst`(触媒として`catalysts.yml`に登録済みならバインド品自身、**未登録なら常にバインド先の魔導書**) | バインド品自身(実際に右クリックしたアイテム) |

`SpellBindListener` 経由で `catalyst` が魔導書になったとき、`resolveSpellBookTierData(catalyst)` は
**null を返さない**（本物の登録済み魔導書なので `BOOK_TIER` PDC も `spell_book_` prefix も揃っている）。
つまり「`catalystData == null` かつ `bookTierData == null`」で新しい分岐を作ろうとすると、
TFカタログ杖10本（`BLAZE_ROD#400002〜400008`/`400012〜400014`、いずれも `spellbooks.yml` の
`catalysts:` 未登録）のバインド詠唱では `bookTierData != null` になり分岐に入れない ――
`spell-books:` の `cooldown:` が全ティア `0` なので実害は無いように見えるが、
**「null かどうか」ではなく「実際に自分のCT値(>0)を持っているかどうか」で判定しないと、
item-cooldown ステを杖CTの権威にする改修が無言で no-op になる**（2026-08-02、item-cooldown配線で実際に踏んだ）。
`SpellCaster.java` の `catalystOwnsCt` / `bookOwnsCt`（`catalystData/bookTierData` の
`cooldownMs()/getCooldownMs() > 0` を明示的に見る局所変数）がこの区別を固定している。

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

### ⚠️ 1アイテムに複数レシピを書くときは「登録キー」を結果アイテムIDから分離する

レシピの正規形は **TF の `catalog.yml` と ArsPaper 側の全 yml で共通**（2026-08-13 に統合）:

```
0件 → キーなし
1件 → recipe:  （マップ）
2件以上 → recipes:  （マップの配列）
```

`UnifiedRecipeLoader#recipeSections` が両形を読み、6ローダー（functional-items / items /
materials / threads / sourcejars・sourcelinks / spellbooks）すべてが**書かれた順に全件**回す。
統合前は `getConfigurationSection("recipe")` しか見ていなかったため、設定エディタの共通レシピUIで
2件目を足した瞬間に**レシピが丸ごと Java から見えなくなっていた**（エディタ画面上でも1件目が消えた）。

**踏むと無言で壊れる点:**

- **登録キーはアイテムIDそのものではいけない。** 作業台は `new NamespacedKey(plugin, data.id())`、
  儀式は `RitualRecipeRegistry` の `Map` キーが**どちらもレシピの id 文字列**なので、同じアイテムに
  2件登録すると**後勝ちで片方が黙って消える**（`RecipeManager` は重複キーを skip、
  `RitualRecipeRegistry` は `put` で上書き）。`UnifiedRecipeLoader#recipeKey(id, index)` が
  2件目以降を `<id>_r2` にする。**結果アイテムは常に元の id から解決する**（キーを結果に使わない）。
- **`unlock-gate.yml` の `recipe-perks` はアイテム単位で書かれている。** `_r2` のキーをそのまま引くと
  **2件目だけ perk ゲートを素通りする**。`RecipeUnlockGate#gateKey` が「完全一致 → 無ければ
  末尾の `_r<数字>` を落とした基底ID」でフォールバックする。
- **lore の「作り方」表示は `MaterialConfigManager` が別に読んでいる。** `recipe:` が無い
  （＝2件以上ある）エントリでは `recipes[0]` で代用する。
- 設定エディタ側は `lib/schema.js` の `validateRecipeForms()` が `recipe:`/`recipes:` を同じ検証器へ流す。
  **画面ごとにレシピUIの挙動を変えないこと**（「素材だけ1件まで」にする修正は差し戻された）。

### 圧縮アイテム（9倍→81倍→…）は TF の `catalog.yml` ではなく ArsPaper の `materials.yml` に住んでいる

「圧縮○○」を探して `items/catalog.yml` を grep すると1件も出ない。実体は
`fork-handoff/arspaper/fork/src/main/resources/materials.yml` にある。

- **命名**: `<素材id>_<段数>x`。`N` は倍率ではなく**段数**（`_1x`=9倍 / `_2x`=81倍 / `_3x`=729倍 /
  `_4x`=6561倍 / `_5x`=59049倍）。倍率は `display_name` に書く。
- **2系統ある**: ブロック系は `method: workbench` の 3x3（9倍刻み）、レア素材系は
  `method: inventory` の 2x2（4倍刻み。`echo_shard_*` 等）。既存シリーズに合わせること。
- **2段目以降の `ingredients.i` は必ず1段前の `custom:<id>`。** `recipe.result` は書かない
  （省略＝自分自身が既定値。上の `result:` 節の「破壊レシピ」注意はここでは逆に正しい挙動）。
- **解凍レシピを手書きしてはいけない。** `reversible: true` を付けると
  `RecipeManager#registerReverseIfNeeded` が `uniformIngredientValue`/`uniformIngredientCount` を見て
  「1個 → 元素材N個」の `ShapelessRecipe`（`RecipeChoice.ExactChoice`）を自動生成する。
  前提を満たさない形だと fail-soft で警告だけ出して逆レシピが登録されない。
- **CMD は手で決めない。** `tools/config-editor/lib/cmd-registry.js#allocateBulk` を Node から
  1回だけ呼んで払い出す（`allocate` を1件ずつ呼ぶと台帳の読み書きが競合する、とソースに明記）。
  `nextCmd` は「その material の使用済み最大値+1 から、全 material の使用値と予約値を避けて探す」ので
  **未使用 material では 17, 18, … のような小さい番号が付く。これは仕様どおりで衝突しない。**
- **リソースパックのモデル／テクスチャは要らない。** 既存の圧縮シリーズは CMD 台帳に
  `assetName` / `customModel` を持たない。CMD は「同一 Material 内でアイテムを識別する番号」
  としてしか使われず、見た目はバニラのまま。テクスチャを作ろうとしないこと。
- **`_editor.categories.material` と `_editor.orders.material` にも id を足す**こと（足さないと
  config-editor 上で分類なしになる）。
- ⚠️ **原木系の逆レシピはバニラの「原木1個→板材4個」と入力が競合しうる**
  （どちらも grid に圧縮原木1個を置いた状態にマッチする）。`oak_wood_*` は以前からこの状態。

### ⚠️ 圧縮「食料」は `materials.yml` に登録するだけでは食事効果が無い ── TF側 `stats/food-gimmick.yml` の `custom-foods` に別途登録が必要

圧縮パン/圧縮ステーキ系（調理済み食品を base_material にした圧縮アイテム）は、`materials.yml` に
定義してレシピが通っても、**食べたときの満腹度/隠し満腹度はバニラの base_material の栄養値のまま**
（例: `compressed_bread_2x`(81倍)を食べても素のBREADと同じ5満腹度/6.0隠し満腹度しか回復しない）。
`FoodGimmickListener#onFoodLevelChange`（TF側、`com.trinityforge.listeners`）が
`CrossPluginItemResolver.idOf(item)`（`BaseCustomItem#createItemStack`が刻む
`arspaper:custom_item_id` PDC、値は materials.yml の id キーそのもの、prefixなし）で
`FoodGimmickConfig#customFood(id)`（`stats/food-gimmick.yml` の `custom-foods:` セクション）を引き、
一致した場合だけ `FoodLevelChangeEvent#setFoodLevel`/`Player#setSaturation`（隠し満腹度はバニラの
上書きを避けるため次tickへ`runTask`で遅延）でREPLACEする。**`custom-foods:` に無いidはこの経路自体が
素通りし、バニラの`FoodLevelChangeEvent`既定処理（=base_materialの栄養値）がそのまま残る。**
2026-08-08時点で実際に `compressed_bread_2x`/`_3x`・`compressed_cooked_beef_2x`/`_3x` の4件が
未登録のまま長期間放置されていた（クラフト段数は正しく機能するのに食事効果だけ死んでいた）。

- **新しい圧縮食料（調理済み系）を追加したら、materials.yml だけでなく `food-gimmick.yml` の
  `custom-foods:` にも同じidで `food-level`(0-20, `FoodGimmickConfig.MAX_FOOD_LEVEL`でクランプ)/
  `saturation`(double, 負値は既定値0.0へfail-safe)を必ず追加すること。** レシピの段数（`_1x`/`_2x`/…）
  ごとに個別の値を持たせる必要はない ── バニラの満腹度上限は20固定なので、9倍しただけで大抵の食品は
  上限を超え、`_1x`より上の段は同値で十分（このリポジトリでは`_2x`/`_3x`に`_1x`と同じ値を登録して解消）。
  例外的に元の栄養値が小さい食品（`COOKIE`: hunger2/sat0.4）は9倍でも20に届かないため、
  段数ごとに実際の掛け算値を使うこと。
- `ExternalItemRegistry`（`custom:<id>`をTFレシピ素材として使うときの経路）はここでは不要。
  `food-gimmick.yml`の`custom-foods`キーはTFカタログ経由でもExternalItemRegistry経由でもなく、
  `CrossPluginItemResolver.idOf`がPDCを直接読むだけなので、TF側に一切登録しなくても解決できる。

### ⚠️⚠️ `materials.yml` と `resourcepack/cmd-registry.json` は、触る前に必ず「今すでに未コミットで汚れていないか」を確認する

どちらも複数セッションが同時に触る頻出ファイルで、**自分がまだ何も編集していない時点で既に
別セッションの未コミット変更が乗っていることがある**（2026-08-05 実例: 圧縮アイテム6種追加の
着手前チェックを怠り、fork の `materials.yml` に他セッションの `core_meat` レシピ変更・
`tf_scrap→scrap`等の改名リネームキャンペーンが、outer repo の `cmd-registry.json` にも同じ
改名＋新規チケット3件の追加が、それぞれ未コミットで混入していた）。

- **`allocateBulk`/`loadRegistry` は read-modify-write**（`tools/config-editor/lib/cmd-registry.js`）。
  ディスク上の現在の内容をそのまま読み、自分の追加分だけ足して**丸ごと保存し直す**ため、
  他セッションの未コミット編集も一緒に保存され、そのまま自分の commit に混入する。
- **How**: 対象ファイルを編集・スクリプト実行する前に必ず `git status`/`git diff <path>` で
  「自分が触る前から汚れていないか」を見る。汚れていたら、そのファイルの commit 対象は
  「自分の追加分だけ」に絞る。git reset/checkout は権限ゲートで使えないことがあるため、
  **`git show HEAD:<path>` で最後にコミットされた内容を取得し、そこに自分の追加分だけを
  足したものを Edit ツールで手動反映する**のが唯一の安全な復元手段
  （`cp`/`fs.writeFileSync` によるファイル全体の直接上書きは Bash 側の分類器に拒否される
  ことがあるため、大きな JSON を丸ごと書き戻すより、対象箇所だけ `Edit` で直す方が通りやすい）。
- **`cmd-registry.json` の `allocations` 配列は順序に意味を持たない**（線形リストを毎回全走査するだけ）。
  他セッションの編集が配列の途中に挿入されていた場合、そこだけ手で戻しても以降の全要素の
  配列内位置がずれたままになり、`git diff` が「中身は同じなのに数百行の差分」として大きく出る。
  これはデータ破損ではない ―― 差分の大きさで判断せず、`material#cmd` キー集合と `id` の
  完全一致を JS で比較して確認すること（見た目の diff サイズを信用しない）。

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

### ⚠️⚠️ ArsPaper の yml は「フォークの HEAD」からしか配備先へ届かない ── 作業ツリーで直しただけでは永久に反映されない

2026-08-16 に実サーバが起動のたびに落としていた
`[items/catalog.yml] failed to register recipe for 'key_binder'; skipped` /
`custom list member 'dungeon_seal_enchant_trial_10' is unknown` の**第2の真因**。
「レシピ登録の失敗」に見えるが、実際には**素材そのものが配備先の config に存在しない**という意味。

経路が2段あり、どちらも黙って古い版を維持する:

1. **プラグイン自身は config を更新しない。** `ArsPaper#updateResourceFiles` は
   **プラグインのバージョン文字列が変わったときしか**リソースを再展開しない。
   VERSION はずっと `0.1.0-SNAPSHOT` のままなので、**新しい jar を配備しても
   既存の `plugins/ArsPaper/*.yml` は 1 バイトも上書きされない**
   （jar の中には新しい yml が入っているのに、稼働側は古いまま ―― これが最も気づきにくい）。
   TF 本体側の同型の罠は `TrinityForgeConfigMigration`（トップレベルキー単位でしか差分を見ない）。
2. **config 配備はフォークの HEAD を配る。** `ops/launch/deploy-config-head.cmd` は
   `git show HEAD` 相当（**フォーク自身のリポジトリの HEAD**）から `*.yml` を robocopy する。
   フォークのソースは outer repo の `.gitignore` で除外されているので、
   **outer repo 側でいくら commit しても関係がない**。フォークの作業ツリーで yml を直しただけ、
   あるいはフォークのローカルリポジトリで commit していないだけで、配備は古い版を配り続ける。

実例の症状: TF 側の `items/material-lists.yml`（commit 済み）は印 28 種を要求していたのに、
フォーク HEAD の `materials.yml` は単数の `dungeon_seal_enchant_trial` を含む 19 種のまま。
28 種になっていたのは**フォークの作業ツリーと配備中の jar だけ**だった
（jar 同梱版と作業ツリー版は byte 一致していた ―― つまり「ビルドはされたが commit されていない」状態）。
結果、束縛者の鍵が作れず最下層へ入れなかった。

- **How（フォークの yml を触ったら必ず）**: `git -C fork-handoff/arspaper/fork status --porcelain`
  で自分の変更が ` M` のままになっていないか見る。**自分が触ったパスだけ**を
  `git -C <fork> add -- <path>` して commit する（フォークにも他セッションの WIP が常に乗っている）。
- **確認の型**: 「配備先の yml に入っているか」を jar の中身で判断しない。
  `plugins/ArsPaper/<file>.yml` を直接読むか、`git -C <fork> show HEAD:src/main/resources/<file>.yml`
  を読む。この 2 つが一致していない限り、config 配備を 1 回通すまで直っていない。

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

### ⚠️ ソースリンクの転送レートは「基準値 × 種別ごとの階梯倍率」。新しい上位ティアには倍率を明示しないと無印と同速のまま化ける

`sourcelinks.yml` の `transfer.sourcelink.max-per-transfer`（既定50/100tick、K-16が指摘した
「レートを上げる唯一の手段が台数を並べること」の元凶）は**全リンク共通の基準値**で、個体差は
`items.<id>.transfer-multiplier`（`SourcelinkConfig.ItemDef#transferMultiplier`、既定1.0）が
`Sourcelink#effectiveMaxPerTransfer` で基準値へ掛け算することで表現する（2026-08-02, K-16 の
「容量は伸びるがレートが伸びない」を解消）。`InfinityCoreEffect.scaleCap` を流用して階梯倍率→
infinity_source_core 補正の順に多段で掛ける。

- 新しい上位ソースリンク（`*_ii`/`*_iii` 等）を `sourcelinks.yml` の `items:` に足しても、
  `transfer-multiplier:` を明示しなければ**既定1.0＝無印と全く同じレート**になる。レシピもCMDも
  正しいのに「上位リンクを作ったのに速くならない」という無言の劣化再現になるので、階梯を追加した
  実装者は必ずこのキーを書くこと。
- 容量側（`buffer-cap`）には階梯倍率を掛けていない（既定が int 上限＝実質無制限で、ボトルネックは
  最初からレート側にしかないため）。容量で個体差を付けたい場合は別途キーが要る。
- `SourcelinkConfig.ItemDef` の canonical constructor は8要素（`transferMultiplier` が 2026-08-02、
  `yieldMultiplier` が 2026-08-03 に末尾へ追加済み）。旧来の5引数コンストラクタは `type` 推定 +
  両倍率=1.0 を委譲するので既存呼び出し元（`loadItems()` の1箇所のみ）は無改修で動く。

### ⚠️ ソースリンクの階梯倍率は「レート」と「生成量」の2本立て。生成量倍率を `addToBuffer` の中で掛けるとソースが無限増殖する

2026-08-03 追加の `items.<id>.yield-multiplier`（`SourceGenerationScaling#scaleYield`）は、上の
`transfer-multiplier` とは効き方が違う。取り違えると要件を満たしたつもりで何も変わらない:

- `transfer-multiplier`: バッファ→隣接ジャーへ**1周期に出せる量**（`effectiveMaxPerTransfer`）。
  速く運べるだけで、燃料1個から得られるソース＝素材効率は変わらない。
- `yield-multiplier`: **新しく生まれる量**。素材効率そのものが変わる。掛ける場所は4経路:
  ①燃料/食料/素材の手投入（`{Volcanic,Mycelial,Alchemical}Sourcelink#onBlockInteract` の `totalAdded`）
  ②ホッパー投入（`CustomBlockListener` のソースリンク宛て `InventoryMoveItemEvent` 経路）
  ③バイタリックの受動生成（`SOURCE_PER_TICK`）④成長/撃破ボーナス（`SourcelinkTickTask#accumulateNear`）。
  ①と②の片方だけに掛けると「自動化すると素材効率が落ちる」不一致になる。

**`Sourcelink#addToBuffer` の中で掛けてはいけない。** `addToBuffer` は生成経路だけでなく
`SourcelinkTickTask#tick` の**返却**（`SourceYield#refundToBuffer`＝隣接ジャーに注ぎ切れなかった分を
戻す）からも呼ばれる共通経路なので、ここで掛けると**隣接ジャーが満杯である限り毎周期ソースが増える
無限増殖**になる。倍率は必ず呼び出し側（生成点）で `scaleGeneratedYield(...)` を通してから渡す。
この配線は `SourcelinkYieldWiringTest`（ソース検査。このフォークは Bukkit ランタイムを持たないため
リスナーは文字列走査で守る）が生成4経路と「返却には掛かっていないこと」の両方を固定している。

- 階梯の値そのもの（転送 x2/x4/x8/x16、生成 x1.5/x2/x3/x4、5種 x 4段=20件）と、
  表示名に数字段表記を使わない規約は `ShippedSourceLadderTest` が出荷 yml を読んで固定する。
  段を足すときは `core-item` が前段を指し、`(core-item, pedestal-items)` の組が既存と重複しないこと
  （重複すると後発が `findFirst` に負けて永久にクラフト不可）。

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

### ⚠️ スレッド個体差は 2026-08-03 に「Ars専用 ThreadRoll」から「TFの item-stats.yml 個別定義」へ全面移行済み

※かつてこの節は「スレッド厳選（thread-rolls.yml）は生成時焼き込みなので TF品質を反映するには
player を生成経路まで手動で運ぶしかない」「個体差は Ars 側 `ThreadRoll` PDC だけで表現する設計を
維持する」と書いていたが、その設計自体が 2026-08-03 に置き換えられたため誤り（当時は正しかった）。
現在の実態は以下:

- `ThreadRoll`/`ThreadRollConfig`/`thread-rolls.yml` は削除済み。スレッド1個ぶんの個体差は、
  武器・触媒と全く同じ **TF の `rollSeed`(long) + `quality`(int)** の2値だけで表現する
  （`com.trinityforge.pdc.ItemData`）。ステの中身（何がどれだけ乗るか）は
  `stats/item-stats.yml` の `items:` 配下、スレッド40件それぞれの `MATERIAL#CMD` キー
  （CMD帯 300000-300099）に個別の `fixed`/`per-quality`/`random`/`advanced.randomize-grants` を
  書く（武器と完全に同じ仕様）。共有の「厳選プール」という概念自体が無い。
- `ThreadItem#createItemStack(Player crafter)` が
  `TrinityForgeBridge#stampThreadIdentity(item, crafter)` を呼び、
  `com.trinityforge.stats.ItemFactory#stamp(ItemStack, long rollSeed, int quality)`
  （武器のクラフト刻印と**同一の**エントリポイント）へ委譲する。品質は
  `TrinityForgeBridge#currentArsSmithingQuality` で得る（player 不明なら 0 = fail-open）。
  効果を持たない `thread_empty` は刻印しない。
- **`ItemFactory#stamp` をスレッドに使っても安全な理由（この安全性は条件付き）**:
  `stamp()` は `ItemAssembler#assemble` を通じて**フルの再組み立て**（PDC刻印・耐久・lore全体の
  再構築・バニラ `AttributeModifier` の投影 `AttributeProjection.project`）を行う重い処理だが、
  `AttributeProjection.defaults()` が vanilla 属性へ投影するのは
  `knockback_resistance`/`max_health`/`move_speed`/`attack_speed`/
  `attack_speed_bonus`/`attack_reach` の**6キーだけ**（`attack-power` は意図的に除外。
  `armor_defense_rate` も入っていたが 2026-08-15 に防具値ステごと廃止して `defense-rate` へ統合し、
  写像対象から外した）。
  スレッドの40エントリはこの6キーのどれも使っていない（2026-08-03時点で確認済み）ため
  `stamp()` はスレッドに対して余計な vanilla AttributeModifier を一切生成しない。
  **⚠️ 将来この6キーのいずれかをスレッドの `item-stats.yml` に足すと、無警告でスレッドに
  vanilla属性（例えば move_speed）が直接付与される** ── ソケットせず手に持つだけでも
  `EquipmentSlotResolver` が `Category.ANY` に落とすぶん `AttributeProjection` は
  `EquipmentSlotGroup.ANY` スコープで適用してしまうため、`thread-sets.yml` 側で意図している
  「ソケットしないと発動しない」という前提が崩れる。7キーに触れるスレッドを追加するときは
  `stamp()` を使わない別経路（PDC刻印のみの軽量パス）に切り替えること。
- 装着中スレッドの個体差は防具PDC `ItemKeys#THREAD_SLOT_ROLLS` に
  `"<rollSeed>:<quality>"` 形式（`com.arspaper.item.ThreadSlotIdentity#encode/decode`）で
  `THREAD_SLOTS` と同じ添字で保存する。旧形式（レア度+ステ値埋め込み）は例外を投げず
  `NONE`(rollSeed=0, quality=0) へ fail-open する。
- `TrinityForgeBridge#rerollThreadIdentity`/`ThreadGui#restoreRoll` のように
  **`ItemStack` を `editMeta` で in-place 書き換える** ブリッジ呼び出しは、呼び出し後には
  「書き換え前の状態」を再取得できない。旧lore行との差分など「前後比較」が必要な処理は、
  ブリッジ呼び出しの**前**に前状態をキャプチャしておくこと（`ThreadRerollRitualEffect#execute`
  が実例。呼び出し順を逆にすると新旧が同じ値になり lore の差分除去が無言で空振りする）。

### ⚠️ スレッド枠拡張儀式の `max-slots` は「1回で足す枠数」ではなく装備1個の累計上限 — 段位ごとに増やさないと上位段が無言で死ぬ

`items.yml` の `effect-type: thread_slot_expand` に書く `effect-params.max-slots` は、装備の
TF 側 PDC `ritual_thread_slot_bonus`（`PdcKeys.ITEM_RITUAL_THREAD_SLOT_BONUS`、**装備1個につき
1個だけのカウンタ。全段の儀式がこれを共有する**）に対する上限値である。
`ItemFactory#expandRitualThreadSlot`（`TrinityForge/src/main/java/com/trinityforge/stats/ItemFactory.java`）は
`ritualBonus >= maxSlots` なら `Optional.empty()` を返し、`ThreadSlotExpandRitualEffect#validate`
がそれを「拡張できません」で弾く（素材もソースも消費せず、ログにも何も出ない）。

- **したがって上位段の `max-slots` を下位段以下にすると、下位段を1回回した時点で上位段が永久に失敗する。**
  Ⅰ=1/Ⅱ=1 だった時期の Ⅱ がこの状態（Ⅰ の後は必ず失敗し、Ⅰ より先に回しても Ⅰ の上位互換にならない）。
  現在は Ⅰ=1 / Ⅱ=2 / Ⅲ=5 の単調増加。回帰は
  `fork-handoff/arspaper/fork/src/test/java/com/arspaper/ritual/ThreadRitualRecipeConfigTest`
  の `slotExpandMaxSlotsAreStrictlyIncreasing`。
- 実効上限はもう1段ある: `progression/crafting-features.yml` の `thread-slots.max-by-category`
  （現在 armor/weapon/tool/other すべて 5）。`ThreadSlotPolicy#applyCategoryCap` が derivation の
  たびにクランプするので、`max-slots` をこれ以上に上げても総枠は増えない（装備の基礎
  `thread-slots` は `stats/item-stats.yml` で 2〜3）。**枠が増えないという報告が来たら、
  まず `max-slots` の累計解釈とカテゴリ上限のどちらで止まっているかを切り分けること。**
- 振り直し儀式 `thread_reroll` は **効果クラス（`ThreadRerollRitualEffect`）と `ArsPaper#register`
  を残したまま、`items.yml` に儀式エントリを置かない＝ゲーム内非公開**という運用（2026-08-16 ユーザー決定）。
  「実装済みの effect-type にレシピが無い＝配線漏れ」と誤診して足し直さないこと。同テストの
  `rerollRitualIsNotPublished` が復活を検知する。

### ⚠️ `ThreadConfig`/`ThreadType` は静的初期化で `PotionEffectType` 定数を触るため、テスト基盤(Bukkitランタイム無し)ではクラスをロードするだけで落ちる

`org.bukkit.potion.PotionEffectType.SPEED` 等の静的定数は内部的にレジストリ経由で解決されており、
CraftBukkit実装(ライブサーバ)が無い状態で参照すると即座に `ExceptionInInitializerError`
(内部は `IllegalStateException`)で落ちる（2026-08-08、`PotionEffectType.SPEED` を1行だけ参照する
最小テストで実機確認。このフォークは MockBukkit も `libs/TrinityForge.jar` の実体も持たない）。
`ThreadType` enum はenum定数の初期化子でこの定数を使うため元からこの制約を持っていた
（既存テスト`ThreadHandheldWiringTest`のjavadocに「サーバ無しでロードできない」と明記済み）。
2026-08-08 に `ThreadConfig` へ `potion-effect:` の許可リスト
(`ThreadConfig.ALLOWED_POTION_EFFECTS`、静的final field)を追加した際、この制約が
**`ThreadConfig` クラス自体にも伝播した**——`new ThreadConfig(...)` はおろか、テストコードで
`ThreadConfig` クラスに触れる(クラス初期化が走る)だけで落ちる。

- 新しいテストを書くときは `ThreadConfig`/`ThreadType` を直接インスタンス化・参照せず、
  (1) 出荷 `threads.yml` を `org.bukkit.configuration.file.YamlConfiguration.loadConfiguration`
  で直接読む(このAPI自体はサーバ不要)、または (2) `ThreadConfig.java`/`ArmorManaListener.java` の
  ソースをテキストとして読み特定の実装が存在するかを文字列一致で確認する、の2手段に限定すること
  (既存の `ArmorManaListenerThreadPotionGuardTest`/`ThreadHandheldWiringTest`/
  `ThreadConfigPotionOverrideBackCompatTest` と同じ流儀)。
- `NamedTextColor`/`Component`(Adventure)は同じ制約を持たない(サーバ無しでも安全に参照できる)。
  制約があるのは Bukkit の `Registry` 経由で遅延解決される型(`PotionEffectType`、ブロック系
  `Material` の一部等)だけなので、新しいバニラ定数を static field に持たせる前に、その型が
  レジストリ解決かどうかを確認すること。

### ⚠️ 魔法基礎ダメージは「グリフ基礎＋杖の攻撃力」が加算合成される — グリフ側だけに固定値を積んでも高攻撃力帯で無意味化する

`TrinityForgeBridge#magicalFinalDamage` は `spellBase`(グリフ自身の基礎ダメージ) と
`杖の attack-power × combat/damage.yml の magical.attack-power-scale` を**加算**して
`effectiveBase` を作る（`MagicStatSourcePolicy#effectiveBase`）。杖の attack-power は
Lv100帯で10000超に育つため、グリフ側だけに「増幅1段+3.0HP」のような固定値を足しても、
高攻撃力帯では相対的に誤差（実測: 攻撃力10584の杖で harm を撃つと増幅5段の寄与は約0.14%）
になり、無言で無意味化する（2026-08-02、増幅グリフの仕様変更で実際に踏んだ）。

- **新しい「割合で効かせたい」魔法ダメージ補正**（増幅・グリフ別倍率スキルツリー等）は、
  必ず `effectiveBase` 合成**後**・対称パイプライン（守備力/耐性/会心）へ渡す**前**の層で掛ける
  こと。グリフ基礎だけに掛けると同じ罠を踏み、最終ダメージに掛けると守備力の減算より後ろに
  なって防御が無意味化する（`MagicStatSourcePolicy#applyGlyphMultiplier` /
  `#applyAmplifyMultiplier` の javadoc に根拠を記録済み）。
- 増幅(Amplify)グリフの乗算ボーナス（`glyphs.yml` の `amplify.params.damage-rate-per-stack`、
  既定1段+10%）はこの層で実装済み。個々のダメージ系エフェクト（`HarmEffect` 等9種）は
  もう `getAmplifyLevel()` を自分のダメージ式へ直接掛けてはいけない —
  `SpellContext#dealSpellDamage(target, spellBase, glyphId)` が呼び出し時点の
  `getAmplifyLevel()` を自動で乗せる。呼び出し側が既に段数を織り込み済みの経路
  （`HealEffect` の対アンデッド分岐）だけ4引数版で `applyAmplifyDamageMultiplier=false` を渡す。
- 増幅の実質的な上限は「スペルコスト」ではなく `glyphs.yml` の `max-augments.amplify`
  （グリフ互換性チェックでの積み増し上限、出荷時点で対象9グリフ全て6）。乗率計算側にも
  `amplify.params.max-damage-level` で独立の安全弁を持たせてある（将来その上限が外れても
  乗率だけは青天井にしない）。

### ⚠️ Ars効果が `LivingEntity#damage(...)` / `createExplosion(...)` を直接呼ぶと、TFスケールを一切通さずバニラダメージのまま出続ける（無警告）

`spell/effect/` 配下の各エフェクトのうち `context.dealSpellDamage(...)` を経由しないもの
（2026-08-04時点で `IgniteEffect`（継続火炎ダメージ）/`HexEffect`（追撃ダメージ）/
`ExplosionEffect`（爆発）の3件が該当していた、修正済み）は、TFの守備力・耐性・PvP抑制・
`glyph_damage_multiplier_bonus` を一切通らず、config で設定した基礎値がそのまま最終ダメージになる。
`ScorchEffect`/`HarmEffect`等の「手本」実装との違いは `dealSpellDamage` を呼んでいるかどうかの
1点だけなので、新規/既存エフェクトを見るときは必ず `grep -n "damage(\|createExplosion(" spell/effect/*.java`
でTFパイプライン未経由の箇所を横串チェックすること。

### ⚠️ `TrinityForgeBridge#applyMagicDamage` は常に `DamageType.MAGIC` 固定 — `DamageType.ON_FIRE` 等バニラの自動免疫判定に依存していたエフェクトをTF経由へ切り替えると、免疫が黙って外れる

`SpellContext#dealSpellDamage` → `TrinityForgeBridge#magicalFinalDamage` → `TrinityForgeBridge#applyMagicDamage`
（`fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java:574`）は
`DamageSource.builder(DamageType.MAGIC)` で固定適用する。バニラの `PotionEffectType.FIRE_RESISTANCE` は
`DamageTypeTags.IS_FIRE`（`ON_FIRE`/`IN_FIRE`/`LAVA`/`HOT_FLOOR`）だけを免疫にし `MAGIC` は対象外なので、
「`DamageType.ON_FIRE` の `DamageSource` を自分で組んで `target.damage(...)` していたから火炎耐性が効いていた」
エフェクトを単純に `dealSpellDamage` へ置き換えると、TFスケールは掛かるが火炎耐性が無言で無効化される
（`IgniteEffect` の継続火炎ダメージが実例）。**How**: `target.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)`
を呼び出し側で明示チェックしてから `dealSpellDamage` を呼ぶ（免疫時はダメージ自体を出さない）。
Bukkit API に `Entity#isFireImmune()` に相当するメソッドは存在しない（`paper-api-1.21.11` を `javap` で
確認済み）ため、Blaze等のEntityType単位の耐性まで再現したい場合は自前のホワイトリストが要る（未実装）。

### ⚠️ `createExplosion(...)` のエンティティダメージはTF `CombatListener#resolveAttacker` が attacker を解決できず（cause=ENTITY_EXPLOSION）完全にバイパスする — キャンセル＋自前ダメージが必須

`World#createExplosion(loc, power, false, false, source)` は `EntityDamageEvent`
（cause=`ENTITY_EXPLOSION`）を同期的に発火させるが、TF側 `CombatListener#resolveAttacker`
（`TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:1337-1348`）は
MELEE_CAUSES(ENTITY_ATTACK/ENTITY_SWEEP_ATTACK)とPROJECTILEしか認識せず、ENTITY_EXPLOSIONでは
`attacker == null` のまま即 return する。つまりバニラの爆発ダメージは守備力も耐性も一切引かれず
そのまま適用される。**How**（`ExplosionEffect` で採用したパターン）: `createExplosion` 呼び出しを
インスタンスの深度カウンタ（`MagicPipelineDamage` と同型、ネスト呼び出し対応）で囲み、
`EntityDamageEvent` リスナーが「深度>0 かつ cause==ENTITY_EXPLOSION」のときだけ `setCancelled(true)`
してバニラ分を丸ごと無効化し、代わりに爆心地からの距離減衰を自前で計算して対象ごとに
`dealSpellDamage` を呼ぶ。**setFire=false/breakBlocks=false の演出用createExplosion自体は残してよい**
（ブロック破壊やノックバックの挙動は変えず、ダメージ経路だけを差し替える設計）。

### ⚠️ `SpellRegistry` に登録する `SpellEffect` がコンストラクタ内で `registerEvents(this, ...)` すると、`ArsPaper#registerListeners()` の一括登録ループと二重に登録され `@EventHandler` が1イベントにつき2回発火する（無警告）

`ArsPaper#registerListeners()`（`ArsPaper.java:738-743`、`onEnable()`内で`initRegistries()`の**後**に呼ばれる）は
`spellRegistry.getAll()` を走査し `Listener` 実装を持つ component を一括で `pluginManager.registerEvents(...)`
する。これが `Listener` を実装する `SpellEffect`（`HexEffect`/`ExplosionEffect`/`BounceEffect`/`GlideEffect`）の
**唯一の正しい登録経路**。にもかかわらずコンストラクタ内で個別に `Bukkit.getPluginManager().registerEvents(this, plugin)`
を呼ぶと、Bukkitは同一インスタンスの重複登録を排除しない（`RegisteredListener`が2件積まれる）ため、
`@EventHandler`メソッドが1イベントにつき2回発火する。実例: `HexEffect#onEntityDamage` が追撃ダメージを
2倍にしていた（2026-08-04発覚。既存バグで、TFパイプライン統合により顕在化しただけ。合わせて
`ExplosionEffect`にも同型の誤りが新規混入していたため修正）。**How**: `spellRegistry.register(new XxxEffect(...))`
される`SpellEffect`は、コンストラクタで自己登録しない（`BounceEffect`/`GlideEffect`が正しい参照実装）。
新規/既存の`spell/effect/*.java`を見るときは `grep -rn "registerEvents(this" src/main/java` で
横串チェックすること（`SourcelinkTickTask`/`BlockParticleTask`/`InfinityCoreTracker`のような
`spellRegistry`に載らない独立クラスの自己登録は正しいので、登録経路の確認が先）。
回帰は `SpellEffectListenerRegistrationWiringTest`（ソーステキスト検査。フォークはBukkitランタイムを
持たないため実発火数を数える統合テストは組めない）。

### ⚠️ フォークは複数セッションが同じ非バージョン管理ワークツリーを共有する — 他レーンの未完了WIPで自分の変更と無関係にビルドが赤くなる

フォークは `.gitignore` 除外＝TF側の worktree では分離できない（本ファイル冒頭参照）ので、
並行作業は**同一のフォーク作業ディレクトリを直接共有**する。あるセッションが未完成のAPI参照
（例: 2026-08-02 時点で実在した「TF側にまだ存在しない `ItemStatsConfig#randomRollPoolFor` を
呼ぶコード」。この`RandomRollPool`構想自体その後2026-08-03に廃止・置換された）を残したまま
離脱すると、**自分が一切触っていないファイルの変更のせいで `./gradlew build` が丸ごと失敗する**。
この状態は `git diff --stat -- <自分が触ったファイル>` で自分の差分が孤立したハンクに
収まっていることを確認すれば、原因が自分のコードでないと切り分けられる。

- 自分の変更だけを検証したい場合、フォーク自身が独立した `.git` を持つことを利用し、
  作業ディレクトリを丸ごとスクラッチにコピー（`robocopy <fork> <scratch> /E /XD .git build .gradle tmp`。
  git-bash からは `export MSYS2_ARG_CONV_EXCL="*"` を先に打たないと `/E` 等のフラグがパス変換され
  `robocopy` が誤動作する）→ 原因不明の他レーンの壊れたメソッド呼び出しをスクラッチ側だけで
  一時的にスタブ化 → そこで `gradlew build`/`test` を回す、という手順で自分の差分だけを
  切り離して検証できる（本番のフォーク作業ディレクトリには一切触れない）。
- ライブのフォーク作業ディレクトリを直接 `git stash`/`checkout --`/`reset` で「一時的に元へ戻す」
  形の検証は**禁止**（他レーンの未コミットWIPを消す）。上記のコピー退避が唯一の安全な代替手段。
- **同じ1ファイルの中で自分のハンクと他レーンのハンクが隣接/混在する**こともある
  （ファイル単位のstage/addでは分離できない）。この場合、①まず自分の変更だけを含む状態へ
  他レーンのハンクをEditツールで一時的に手で戻す（増分が小さければ現実的。git内蔵の
  patch選択機能はこのBashツールから対話実行できない）②`./gradlew build`/`test`が通ることを
  確認 ③自分の担当ファイル一覧だけを明示 `git add` してcommit ④他レーンのハンクをEditで
  元どおり書き戻す（コミット後の作業ディレクトリが「commit直前に見つけた状態」に一致することを
  `git status --short` で確認）。他レーンの未コミットWIPを一切失わずに、自分の変更だけを
  正確なコミット境界で記録できる。

### ArsPaper フォークのテストは JavaPlugin/Bukkit ランタイムを一切構築しない（MockBukkit も Mockito も依存に無い）

`build.gradle.kts` に `mockbukkit`/`mockito` は無く、`JavaPlugin`/`Bukkit.getServer()` を要求する
クラスは生きたテストで駆動できない（`LegacyCastExperienceRemovalTest` に明記）。この制約下で
使われている2つのテスト手法:

1. **config-only テスト**: `YamlConfiguration.loadConfiguration(new File("src/main/resources/..."))`
   で出荷 yml を直接読み、`JavaPlugin` を経由せず値やキー構造だけを検証する
   （`ThreadSetThresholdReachabilityTest` / `SourcelinkConfigTest` が実例）。
   ロジックが plugin インスタンス無しで検証できるよう、config クラス側に
   `static`/`package-private` の純関数を切り出しておくと（例:
   `ThreadSlotIdentity.encode`/`decode`）ここで直接テストできる
   （※ 旧例だった `ThreadRollConfig.lowMultiplierFor`/`highMultiplierFor` は
   2026-08-03 のスレッド個体差移行でクラスごと削除済み）。
2. **wiring テスト（ソーステキスト検査）**: `Files.readString` で `.java` を文字列として読み、
   「呼ぶべきメソッド呼び出しの文字列が含まれているか」を `assertTrue(source.contains(...))` で
   固定する（`MagicStatSourceWiringTest` / `LegacyCastExperienceRemovalTest` が実例）。
   実行時分岐（player有無での経路切替等）を検証したいがオブジェクトを組み立てられないときの
   最終手段として使う。

### ⚠️ `ThreadSetThresholdReachabilityTest` は `../../../TrinityForge/...` という相対パスで TF 本体の
### `item-stats.yml` を読む ── robocopy スクラッチ検証は「フォークからの階層の深さ」を再現しないと
### この1テストだけが偽陽性で落ちる

`ThreadSetThresholdReachabilityTest#mainStatMinimums`（`src/test/java/com/arspaper/item/`）は
`Path.of("../../../TrinityForge/src/main/resources/stats/item-stats.yml")` を直接読む。この
`../../../`（3階層上）は「フォークの作業ディレクトリが `<repoルート>/fork-handoff/arspaper/fork`
（=リポジトリルートからちょうど3階層下）にある」ことを前提にしている。本ファイル前段で説明した
「複数セッション共有WIPから自分の差分だけを検証する」robocopyスクラッチ手法
（`robocopy <fork> <scratch> /E /XD .git build .gradle tmp`）で、スクラッチ先を
`tmp/ars-verify-scratch`（=リポジトリルートから2階層）のような**別の深さ**に置くと、
この1テストだけが「TrinityForge 本体の item-stats.yml が見つからない」で**本当は無関係な理由で**
失敗する（2026-08-04、圧縮素材64件追加の検証中に発見。before/after比較で「追加前のほうが
失敗が1件多い」という一見おかしな結果が出て、原因を辿ったらこれだった）。

- **How**: スクラッチ先は必ずリポジトリルートから**ちょうど3階層**（例:
  `tmp/<a>/<b>/scratch` のように3セグメント）に置く。2階層・4階層はどちらもこの相対パスを壊す。
- この罠は「自分の変更が原因で新規に失敗が増えた」という誤診断を招く（本セクション上部の
  robocopy手法自体は正しいので、深さだけを合わせれば before/after は正しく一致する。実際に
  深さを合わせたら before=after=324 tests/1 failed/0 skippedで一致した）。
- 同様の `../../../TrinityForge` 相対パス参照が将来増える可能性があるため、フォークのテストで
  新しい相対パス参照を追加しないこと（絶対パス解決 or `System.getProperty("user.dir")` 起点にする）が
  望ましいが、既存のこのテストはこの1本のみ（2026-08-04時点、`grep -rl '\.\./\.\./\.\./TrinityForge'
  src/test/java` で確認）。

### ⚠️⚠️ `gacha.yml`（TF側）の `tickets:` キーは ArsPaper `materials.yml` の実アイテムidと完全一致でなければならない ── 一致していないと券6種が全部無言で解決不能になる（2026-08-04 に発生・修正済み）

`GachaConfig#ticket(catalogId)`（TF側 `com.trinityforge.config.domains.GachaConfig`）は
`tickets:` セクションの**キー文字列をそのまま** `CrossPluginItemResolver.idOf(heldStack)`
（TF `ITEM_CATALOG_ID` → 無ければ Ars `arspaper:custom_item_id` の順で読む、`GachaListener.java:129`）
の返り値と完全一致で突き合わせる。プレフィックスの付け外し・別名解決は一切行わない。

2026-08-04 に実際に壊れていた。`gacha.yml` の `tickets:` が
`tf_gacha_ticket` / `tf_gacha_ticket_1`〜`_5` という**`tf_` 接頭辞つき**のキーなのに、
物理アイテムを定義する `fork-handoff/arspaper/fork/src/main/resources/materials.yml` 側の実際の
id は `gacha_ticket_1`〜`gacha_ticket_5`（**`tf_` 接頭辞なし**、`BaseCustomItem#createItemStack` が
`itemId` を PDC へそのまま刻む＝yml のキーそのものが実IDになる）。かつ `items/catalog.yml`
（TF側）には `gacha_ticket` を含む id が1件も無かった。
つまり `tf_gacha_ticket*` という catalogId を持つ物理アイテムはどこにも存在せず、
**券6種が1つも `ticket()` の検索にヒットしない**状態だった（`idOf()` は必ず `gacha_ticket_N` を返すため）。

- 症状は「解決失敗は券を消費しない」フェイルセーフ（本ファイル上部の「アイテムID解決」節、
  および `common-traps.md` 参照）に乗るため、**エラーは一切出ず「ガチャ券を右クリックしても
  何も起きず、券も減らない」という無言の機能不全**になる。
  つまり**この種の不一致はログにも例外にも出ない。yml を突き合わせない限り気づけない。**
- 修正内容（2026-08-04 完了）:
  - `materials.yml` に `gacha_ticket_0`（`standard` プール用。券は6種あるのに Ars 側が5種しか
    無く、6種目が構造的に欠番だった）を追加。
  - `gacha.yml` の `tickets:` キーを `gacha_ticket_0`〜`gacha_ticket_5` へ改名。
    同時に TF resources 全体の `tf_core_*` → `core_*` / `core_ground`、
    `tf_gacha_ticket*` → `gacha_ticket*` も直した（計152箇所・16ファイル）。
  - 再発防止に `ShippedLegacyCatalogIdDriftTest`（出荷 yml に旧 id が残っていないことを検査）を追加。
- **同種の不一致を今後作らないための要点**: `tickets:` のキーと `entries[].item` は
  「Ars materials.yml のキー」か「TF catalog.yml のキー」のどちらかと**文字単位で一致**していること。
  `custom:` 接頭辞は付けない（`gacha.yml` は無接頭辞規約。付けると3経路すべてを外す）。

### ⚠⚠ 構造物ルートの追加抽選は「実在しない名前空間」を対象にしていて 1 度も発火していなかった

`fork-handoff/arspaper/fork/src/main/resources/loot-tables.yml` は
`LootGenerateEvent` に割り込んでチェストの中身を増やす仕組みだが、2026-08-16 以前は
データパック向けプールの対象を **`dungeons_and_taverns:*`** と書いていた。
**Dungeons and Taverns の実際の名前空間は `nova_structures`**（データパックの
`data/<namespace>/` を実際に見ないと分からない。配布物の名前とは一致しない）。

- 名前空間を間違えても**例外もログも出ない**。当たり判定が false になるだけなので、
  「なぜかデータパックのチェストに何も入らない」としか観測できない。
- 同型の罠が他にもある：`terralith` / `structory` / `structory_towers` / `incendium` /
  `kaisyn`（Towers and Towers）。**配布名≠名前空間**なので、追加するときは必ず
  展開して `data/` 直下のディレクトリ名を見ること。
- 再発防止に `LootTableConfigTest` が案1のデータパック 6 種それぞれについて、
  **実在する表IDで `Pool#matches` が true になること**を固定している。

関連して、この yml の `pools:` は **`tmp/worldgen/gen_loot_yml.py` の生成物**である
（346 本のテーブルIDを手で書き写すと必ず取りこぼす）。手で表を足し引きしても
次の再生成で消える。仕様と再生成手順は
[docs/config-reference/arspaper/loot-tables.md](../config-reference/arspaper/loot-tables.md)。

### ⚠ 既存戦利品の「量を増やす」実装で四捨五入を使うと倍率が化ける

`quantity-multiplier` は整数部を確定で適用し、**端数はその確率で +1** する
（`LootTableListener#scaledAmount`）。四捨五入にすると 1 個のスタックが 1.5 倍で
**常に** 2 個になり、実効 2 倍になる。チェストの中身は 1 個スタックが多いので、
「1.5 倍のはずが体感 2 倍」という形でしか症状が出ず、yml の数字を疑って何度も配備し直すことになる。

複数プールが同じテーブルに当たったときは**掛け合わせず最大値**を採る。掛け合わせると
プールを 1 つ足しただけで既存の全チェストが黙って倍量になる。

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
