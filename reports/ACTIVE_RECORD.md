# TrinityForge — 現役記録（残タスク / 既知の問題 / 作業履歴）

**この文書が唯一の現役記録です。** 2026-07-27 に、それまで 47 本に膨れ上がっていた
`reports/*.md` の残タスク・バグ履歴を棚卸しし、**現時点で本当に開いているものだけ**をここへ集約
しました。過去の日付入りレポートは「その日の作業記録」として残してありますが、
**残タスクの一次情報としては参照しないでください**（大半が stale です）。

## 運用ルール

- 特別な指示がない限り、**残タスク・バグ・作業履歴はこのファイルを参照し、このファイルに追記**する。
- 新しい日付入りレポートを増やさない。大きなバッチの詳細を別紙に書く場合でも、
  **開いている項目の一次情報は必ずここへ反映**する。
- 項目を閉じるときは行を消さず、`~~取り消し線~~ → 解決日と根拠` の形で残す
  （「一度やった調査を二度やらない」ため）。ただし §7 の履歴が長くなったら古い順に間引いてよい。
- **着手前に必ず実コードで裏を取ること。** 2026-07-27 の棚卸しでは、判断待ちとして繰り越されていた
  項目の **15 件が既に実装済み**だった。並行セッションが走る運用では、記録はすぐ腐る。

---

## 1. 現在の状態（2026-07-27 13:05 時点）

| 対象 | 状態 |
|---|---|
| TrinityForge テスト | **2330 件 / 失敗 0 / スキップ 2**（`cleanTest test` で実走・実測。14要件バッチで 12 件追加） |
| config-editor テスト | **614 / 614**（実走・実測） |
| ArsPaper フォーク テスト | **全緑**（`RecipeBrowserFilterTest` 11 件を新設。`test --offline` で実走） |
| `TrinityForge-0.1.0-SNAPSHOT-all.jar` | 2026-07-27 13:01 ビルド → **未配備**（14要件バッチ分） |
| `ArsPaper-1.0.0.jar` | 2026-07-27 13:04 ビルド → **未配備**（レシピGUI改修分） |
| `EliteMobs.jar`（全同梱 uberjar）| 2026-07-26 16:20 ビルド → **配備済み** |
| 実サーバへの配備 | **完了**（yml 42 本＋jar 3 本）。バックアップ = `plugins/.deploy-backups/20260727_114327/`。W-6 / W-7 で変更した `skilltree/light_armor.yml` / `heavy_armor.yml` は **12:37 に config-editor 経由で再配備済み**（下記「配備手段」参照） |
| 配備手段 | **config-editor の保存が `deployPaths` へ自動ミラーする**（`server.js#mirrorToDeploy`）。`D:/` への直接書き込みが権限で止まる場合でも、editor の `PUT /api/config/:id` で保存すれば SoT と配備先の両方が同時に更新される。**ただし保存は yml を再シリアライズするので本文コメントが消える**（→ §5） |
| サーバ稼働 | **停止中**（配備作業の前から停止していた）。**起動すれば新しい jar と config が載る** |

配備先は `D:/game/minecraft/PaperServer/TrinityForge/`（`tools/config-editor/tool-config.json` の
`deployPaths` が正）。`combat/mob-profiles.yml` は保護対象として除外した（配備先 268KB の
`importmobs` 生成物に対し、リポジトリ側は 2.3KB のひな形しかない）。

スキップ 2 件は既知の正当なもの（`OfflineMobImportRunner` と
`NativeProgressionStabilizationContractsTest`）。**MockBukkit の `UnimplementedOperationException` は
SKIPPED として報告される**ため、「この 2 件から増えていないこと」が隠れ失敗ゼロの判定基準。

---

## 2. 残タスク — 判断待ち（こちらでは決められないもの）

| # | 内容 | メモ |
|---|---|---|
| J-1 | **次期方針 4 点** — ダンジョン作成→TF化の手順 / overworld の豊穣化 / editor UX（ランププレビュー） / レベル報酬を討伐EXP通貨へ一本化 | 2026-07-24 に立案したまま未決 |
| J-2 | **経済・通貨システムの新設**（魚売却・スクラップ経済） | 通貨機構が TF に存在しないため実装不能。`fish-sell-toggle` は恒久 no-op と明記済み。Vault 連携か独自通貨の新設を伴う規模 |
| J-3 | ダンジョン名（`MobOverridesConfig#dungeonDisplayName`）を TF のどこに出すか | 現状は editor のカード見出し専用で Java 側消費者ゼロ。決まるまで消さない |
| J-4 | 素材タブのカテゴリバーを「そもそもスティッキーにしない」か | 変える場合 `test/split-view-sticky.test.js` の期待値を**意図的に**書き換える必要がある |
| J-5 | モブ HP 上限 1024 の是非 | 2026-07-27 に「今回は適用しない」選択。現行は上限なし |
| J-6 | 触媒のオフハンド運用 | 同上。`offhand-stats-apply` は既定 false でどのアイテムにも設定されていない |
| ~~J-8~~ | ~~**ArsPaper フォークの push 先**~~ | **解決（2026-07-27）**。ユーザー判断により**3 リポジトリすべて PUBLIC**。`Klee319/ArsPaper` の `feat/trinityforge-fork` を再 push し、`trinityforge` と `EliteMobs-trinityforge` も public 化した |
| J-7 | **K-5（ステータスのトリガー/発動制限の明示）の修正プラン、着手前の 2 点** — ①詳細の出し先を `/stats detail <key>` にするか、アイテム lore の shift 切替にするか ②戦闘系 20 キーで先行検証するか、120 キー一括で埋めるか | 私の推奨は ①`/stats detail`（統合版で hover が効かない・lore の行数制限に当たらない）②先行検証。プラン本体は §4 の K-5 直下 |

---

## 3. 残タスク — 手を動かせば終わるもの

| # | 内容 | 前提 |
|---|---|---|
| W-1 | **実機スモークテスト**: `dropsVanillaLoot: true` のダンジョンボスを 1 体倒し、バニラEXPオーブが意図した量で出るか確認 | **配備後にしかできない**。fork 側半分は TF のユニットテストで触れないため実機確認が必須。対象は実測 159 体（config 408 中 `false` 249 / `true` 32 / 未指定 127） |
| W-2 | **PvP の実プレイ調整** | `pvp.damage-multiplier: 0.5` / `max-damage-percent-of-max-health: 0.15` は机上値。「最低 7 発で倒れる」の手触りは実測前提 |
| W-3 | 採取系見直し **W2（パラメータ化）/ W3（UX）** | **設計書が実在しない**ので設計の書き起こしから。W1 基盤（`com.trinityforge.active`）は実装済み |
| W-4 | ars_magic のマナ系 4 キーを実効化 | `hit-mana-recovery` / `damage-mana-recovery` / `mana-cost-reduction-percent` / `mana-cost-reduction-flat`。TF 側は配線済みだが **ArsPaper フォークの `TrinityForgeBridge` がメインハンドしか読まない**ためパーク由来分が乗らない |
| W-5 | スキルツリー草案が「今回は対象外」と明記した 2 件 | 農業のゴミ食の段階化（グローバル設定の stat 化）／切削 E-β のオフハンド+スニーク破壊 |
| ~~W-6~~ | ~~**`set-buffs` の 4 部位帯の値を決める**~~ | **解決（2026-07-27、ユーザーからの裁量委任による）**。採用した規則 = **3 部位の値は移行前から一切動かさず、4 部位帯だけを約 1.5 倍**（既存バランスを動かさずフル装備の報酬だけを足す）。軽装 C = `dodge-chance 0.1→0.15` / D-1-1・D-1-2 = `0.05→0.08`、重装 C・D-1-1・D-1-2 = `knockback-resistance 0.1→0.15`。`effect-text` にも「4部位で強化」を明記した |
| ~~W-7~~ | ~~**軽装/重装 D ノードの説明文が実装と一致していない**~~ | **解決（2026-07-27）**。二択のうち「文言を消す」を採用（D は `set-buffs` も `armor-set-bonus` も持たない＝バフは全て無条件なので、説明を実装に合わせるのが正）。両ツリーの D から「3部位でセットが成立」の記述と `effects` リストを削除。併せて B の同記述も削除した（B が持つ `armor-set-bonus` は**増幅率であって成立条件ではない**ため、こちらも誤りだった） |
| ~~W-8~~ | ~~**`afk.yml` が config-editor に登録されていない**~~ | **解決（2026-07-27）**。ユーザー指示により独立タブは作らず、**「使用制限スイッチ (use-requirements)」画面内へコンパニオン表示**（`farming-gimmick`＋`food-gimmick` と同じ方式。`USE_REQUIREMENTS_COMPANION_IDS` → `HIDDEN_CONFIG_IDS` でサイドバーからは隠す）。描画は新規 `public/js/tf-afk-form.js` に隔離し、`tf-crafting-features.js` への変更は呼び出し 15 行のみ。**Java が黙って丸める 2 ケース（`check-interval-ticks < 20` / `kick-after-seconds` が非 0 で `idle-seconds` 未満）は editor 側では保存時エラーにした**（黙って丸めると「保存した値」と「実挙動」がずれるため）。実ブラウザで 12 キーの表示・値のロード・バリデーション 400・保存→配備ミラーまで確認済み |
| ~~W-9~~ | ~~**`combat/damage.yml` の 6 キーが「共通変数」画面に出ていない**~~ | **解決（2026-07-27）**。`FIELD_SPECS` へ 6 件追加。`min`/`max`/`def` は全て `CombatDamageConfig.java` の `SchemaField` 宣言（L90-98 / L125）と一致させた。`enchant-protection-scale` の上限は**バニラ相当の 1.0 ではなく Java 通りの 10**（editor だけ狭いと「yml では通る値が editor で弾かれる」ズレになる）。UI は「近接チャージ」「攻撃速度」セクションを新設し、`enchant-protection-scale` は既存の「防御(安全弁)」へ |
| W-10 | **registry のカバレッジドリフト検知テストが無い** | リポジトリ内の yml と `registry.js` の登録項目を突き合わせるテストが存在しないため、W-8 のような登録漏れが無言で発生する。除外してよいもの（`paper-plugin.yml` / DEPRECATED な `combat/mob-defaults.yml` / ArsPaper の「空を維持」前提な `usage-gate.yml`・`unlock-gate.yml`）は明示的な許可リストにする |
| W-11 | ArsPaper の gate yml 2 本が挙げる SoT ファイル名が実在しない | `usage-gate.yml` / `unlock-gate.yml` のコメントは正本を `skilltree/dedicated-effects.yml` と書いているが、**そのファイルは存在しない**。実体は各スキルツリー yml のノード内 `dedicated-effects:` フィールド（`SkillTreeConfig#parseDedicatedEffects`）。コメントの修正だけで済む |

---

## 4. 既知の未修正の問題・弱点

いずれも**意図的に許容している**か、**直すには判断が要る**もの。新規に見つけたバグはここへ足す。

| # | 内容 | 判断 |
|---|---|---|
| K-1 | **同一地点EXP逓減が友好モブの養殖場を数えない** | カウンタはバニラEXP書き込み経路で回るため、レベル帯の対象外である C 群（友好モブ 39 種）の養殖場には反応しない。TT の本命ではないので現状は許容 |
| K-2 | **ATTRIBUTE チャネルの上限は「実効値」の上限ではない** | `move-speed` / `attack-speed-bonus` / `attack-reach` / `knockback-resistance` / `max-health` の上限は「**TF が要求する寄与分**」に掛かる。Haste や他プラグインの寄与は含まれない。`PerkAttributeApplier` の「ライブ属性値を一切読まない」設計原則を守るための意図的な線引き（詳細 = `docs/config-reference/combat/stat-caps.md`） |
| K-3 | **editor で yml 本文のコメントが保存時に消える** | `tools/config-editor/lib/yamlio.js` の仕様。対策は説明コメントを `docs/config-reference/` へ退避すること。新しく長いコメントを yml 本文に書かない |
| ~~K-4~~ | ~~**このリポジトリは git 管理下にない**~~ | **解決（2026-07-27）**。`Klee319/trinityforge`（private）を作成し初回インポート済み。作業ブランチは `dev`。ただし下記 K-6 の 2 フォークは対象外なので、そちらを触る前は従来どおり `backups/` を取ること |
| K-5 | **ステータスのトリガーと発動制限が、どこにも機械可読な形で存在しない**（2026-07-27 確認） | 説明文は自由文でエディタ専用、実装との紐付けがゼロ。結果として**説明が実装から静かにずれる**。修正プランは直下 |
| ~~K-6~~ | ~~**2 つのフォークの作業がバックアップされていない**~~ | **解決（2026-07-27）**。両フォークとも未コミット分をコミットして push 済み（ArsPaper `17c9f65` → `Klee319/ArsPaper` の `feat/trinityforge-fork` / EliteMobs `ea043d3b` → `Klee319/EliteMobs-trinityforge` の `trinityforge-fork`）|
| K-7 | **`armor-set-bonus` はスキルツリー由来分しか増幅に効かない**（2026-07-27） | `NativeAttributeBridge.armorAttributesFor` は `perkBuffs.buffsFor(id).general()`（＝パーク由来）だけを読む。そのため `base-stats.yml` / 装備 / 永続バフ / 役職バフ に `armor-set-bonus` を置いても**セット効果の増幅には効かない**。総合ステータスとしては登録済みなので `/stats` には出る＝**装備に付けると lore に出るのに効かない**。撤去した旧4キーと全く同じ制約なので回帰ではない。正すには aggregator→bridge の循環依存を解く必要がある。editor の base-stats 画面では `NO_OP_BASE_STATS_KEYS` で非表示にしてある |

### K-5 — 現状と修正プラン

ステータスの挙動は 3 つの事実で決まるが、いずれも宣言されていない。

| 決める事実 | 現状どこにあるか |
|---|---|
| **トリガー**（近接命中 / 射撃命中 / 被弾 / ブロック破壊 / 醸造 …） | どこにも無い。リスナー実装を読むしかない |
| **集計元**（メインハンド限定 / 着用防具限定 / オフハンド opt-in / 全ソース） | Java のガード条件に散在（`isWornOnlyArmor` 等） |
| **発動制限**（上限・クランプ・部位閾値・距離上限・スタック規則） | `combat/damage.yml` と **Java のハードコード定数**に分散 |

プレイヤーが見る `stats/lore.yml` は `name / format / decimals / order / category` だけで、
**120 キー全部がトリガーも制限も持っていない**。唯一の説明文は
`tools/config-editor/public/js/labels.js` の `STAT_DESCRIPTIONS`（**自由文・ゲーム内非表示・実装と未接続**）。

**実際にずれている箇所（2026-07-27 実測、5 件）**

| 箇所 | 記述 | 実装 |
|---|---|---|
| `labels.js:269-272` | 軽装/重装セットは「2 部位以上」 | `NativeAttributeBridge.SET_BONUS_MIN_PIECES = 3` |
| `labels.js:618` | `軽装: セット回避率+ (2部位以上)` | 同上 |
| `labels.js:190` | `distance-damage-bonus` = 「距離に応じて増える」だけ | 16 ブロック単位で加算・**64 ブロックで頭打ち** |
| `labels.js:170` | `dodge-chance` に上限の記載なし | `damage.yml#defense.max-dodge-chance: 0.9` |

既存テストは「説明文が存在し `未登録` でないこと」しか見ないので、**内容の誤りは原理的に検知できない**。

**Phase 1 — `lore.yml` にトリガー/制限を構造化して持たせる**（表示の真源かつ editor 編集対象なのでここへ寄せる）

```yaml
  dodge-chance:
    name: 回避率
    trigger:
      when: ON_DAMAGE_TAKEN       # 閉じた列挙(Java enum + JS ミラー)
      sources: ALL                # MAINHAND / WORN_ARMOR / OFFHAND_OPT_IN / ALL
      applies-to: [MOB, PLAYER]
    limits:
      cap: 0.9
      cap-ref: "combat/damage.yml#defense.max-dodge-chance"
```

`when` の候補は実装から逆算（`ON_MELEE_HIT` / `ON_PROJECTILE_HIT` / `ON_ANY_HIT` / `ON_DAMAGE_TAKEN` /
`ON_KILL` / `PASSIVE_ATTRIBUTE` / `ON_BLOCK_BREAK` / `ON_CRAFT` / `ON_BREW` / `ON_ENCHANT` / `ON_FISH` /
`ON_DISASSEMBLE` / `ON_SMELT` 程度）。閉じた語彙にすれば editor がドロップダウンで出せる。

**Phase 2 — 拘束テスト（このプランの本体）**。宣言値と実装値を突き合わせて落とす。
説明文を増やすだけでは同じことが再発する。

| 宣言（lore.yml） | 突き合わせ先 |
|---|---|
| `light-armor-set-*.limits.min-pieces` | `NativeAttributeBridge.SET_BONUS_MIN_PIECES` |
| `distance-damage-bonus.limits.max-distance` | `CombatListener.distanceDamage` の 64 |
| `dodge-chance.limits.cap` | `damage.yml#defense.max-dodge-chance` の実値 |
| `armor-strength.limits.clamp` | `PlayerDefenseResolver` のクランプ範囲 |

そのため Java 側の裸のマジックナンバー（`Math.min(64.0, blocks)` 等）を `static final` へ昇格させる。
**上の 5 件のずれは、この仕組みがあれば即座に赤で落ちていた。**

**Phase 3 — プレイヤーが読める場所へ出す**。アイテム lore は行数が厳しいので全部は載せない。
既存の `/stats` に `detail <key>` を足し、lore.yml から自動生成する（J-7 ①で確定させる）。
Java 版はチャット行の hover も上乗せできるが、**Geyser/統合版は hover 非対応**なのでサブコマンドが正。
editor 側はトリガー/制限を lore.yml からの自動表示へ切り替え、二重管理をやめる。

**Phase 4 — 120 キーの移行はラチェット方式**。未記入を許す許可リストを置き、そのリストは減る一方にする。
新規キー追加時は宣言必須（新しい穴が増えない）。埋める順は誤解の実害が大きい順に
**戦闘(attack/defense) → 採取 → 生産 → Ars → utility**。

---

## 5. 恒久的な注意事項（繰り返し踏んでいるもの）

git 系（2026-07-27 に導入）:

- **このリポジトリは `Klee319/trinityforge`（private）で版管理されている。** 作業ブランチは `dev`。
  `main` への push はユーザーの明示指示があるときだけ。
- **`fork-handoff/arspaper/fork/`・`fork-handoff/elitemobs/elitemobs-fork/`・`wiki/` は対象外。**
  それぞれ独自の `.git` を持つため `.gitignore` で除外している。
  **ArsPaper と EliteMobs のフォークには未コミットの変更が残っている**（EliteMobs 側は remote が
  upstream の MagmaGuy/EliteMobs しか無く、フォーク作業のバックアップが存在しない）→ K-6。
- **`git add -A` / `git commit -a` を使わない。** 並行セッションが走る運用なので、他セッションが**実装中**の
  ファイルを巻き込む。2026-07-27 に実際に発生した: docs 名義の `63ae36a`
  「フォーク2件の版管理を反映」に、別セッションが実装中だった `NativeAttributeBridge.java` ほか
  set-buffs 移行のソースが丸ごと入ってしまい、**コミットメッセージと中身が一致しない上に、
  中間状態でスナップショットされた**。commit は必ず自分が触ったパスを明示して stage すること。
- `backups/` と `backups.zip`（455MB）は git 導入以前の手動バックアップ。追跡しない。
- jar は全て追跡しない（`source/` のベンダー jar、フォークの `libs/TrinityForge.jar` を含む）。
  例外は gradle wrapper のみ。

配備・ビルド系:

- **yml だけの配備は config-editor 経由でできる。** editor の保存（`PUT /api/config/:id`）は
  `server.js#mirrorToDeploy` が `tool-config.json#deployPaths` へ同じ内容を書くので、
  **SoT と配備先が同時に更新される**。`D:/` への直接書き込みが権限で止まる状況でもこの経路は通る。
  ただし保存は yml を**再シリアライズ**するため、
  **先頭ヘッダ以外の本文コメントが消える**（`lib/yamlio.js#serializeConfig`。K-3 と同じ仕様）。
  **本文コメントが残っているファイルを editor 経由で保存する前に、
  必ず `docs/config-reference/` へ移設すること。** 2026-07-27 に軽装/重装スキルツリーで実施した際、
  移設していなければ「路線固定(lane-lock)」の警告コメント（group を B〜E に置くと no-op になる件）が
  無言で消えるところだった。
- **EliteMobs フォークの配備は必ず `testbed/plugins/EliteMobs.jar`（全同梱 uberjar）。**
  `build/libs/*-min.jar` は MagmaCore 剥離で `NoClassDefFoundError` になり起動しない。
- **`combat/mob-profiles.yml` はリポジトリ側から上書きしない。** `importmobs` の生成物（268KB）。
- **jar を差し替えたらフル再起動。** reload では不十分（ホットスワップは `NoClassDefFoundError` を招く）。
- **`plugins/` に同名プラグインの jar を 2 つ置かない。** Paper は
  `Ambiguous plugin name '<名前>'` を ERROR に吐くだけで起動を止めず、**どちらを読むかは不定**。
  バージョン文字列が同じだとログからも判別できない。配備のたびに `ls plugins/*.jar` で重複を確認する
  （2026-07-27 に ArsPaper で実際に踏んでいた）。
- **新しい config キーを足したら、その yml も一緒に配備する。** jar だけ入れると editor がその項目を
  空欄／OFF で表示し、**触っていないのに既定と違う値を書き込む**（2026-07-27 の `pvp.enabled` で実際に踏んだ）。
- **editor の `lib/` は Node プロセスにキャッシュされる。** フィールド定義を足したらサーバを再起動しないと
  既定値が出ない（一度これで「値が空だ」と誤診しかけた）。
- ビルドは `java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain <task> --offline`
  （`./gradlew` は使えない）。ロック時は `cleanTest test`。
- PowerShell 5.1 は `&&` を解釈しない。コマンドは 1 行 1 コマンドで渡すこと。

設計・実装系:

- **モブの敵対判定は Paper の `Enemy` マーカーが正。** Bukkit の `Monster`/`Animals` は使えない
  （HOGLIN は `Animals` 判定なのに敵対）。列挙は必ず実 jar から。
- **ワールドゲートは `SkillExpConfig.worldExpRate(boolean)` の 1 箇所に閉じている。** 新しい EXP 経路を
  足すときは必ずここを通す。呼び出し側で個別に判定を書くと、忘れた経路が静かに全額付与になる。
- **採取ツールの判定に `stats.UseSkillDefaults` を流用しない。** あちらは装備ゲート用で `_AXE` を
  `HEAVY_WEAPONS` に落とすため、素のバニラの斧で一括伐採ができなくなる。採取用は
  `gathering.GatheringToolMatcher`。
- **PvP のバランスは倍率だけで取らない。** 攻撃力は指数で伸びるのにプレイヤーの体力はほぼ一定
  （約 33 が上限）なので、倍率だけだと攻撃カーブを触るたびに調整し直しになり、漏らせば即死ゲーに戻る。
  「1 発で最大体力の何 %」というスケールフリーな上限を併用する。
- **排他グループ（`group:`）は「同じ親を持つ兄弟」にしか効かない。** 路線固定で親を分けると
  `group` が兄弟ゼロの no-op になる。`group` は親が共通の A 帯にだけ置く。
- **1.21.11 に弓の引き絞り時間を変えるレバーは無い**（Attribute に draw 系が無く、引き絞りは
  クライアント側予測）。クロスボウの装填時間だけは `QUICK_CHARGE` のレベル操作で縮められる。
- **採掘速度を `MINING_EFFICIENCY` / `BLOCK_BREAK_SPEED` 属性で実装しない**（Geyser 未対応 =
  統合版でゴーストブロック）。効率強化エンチャントのレベル操作が唯一の互換手段・上限 5。

---

## 6. 参照先（一次情報）

| 知りたいこと | 見る場所 |
|---|---|
| config の各キーの意味・適用点 | `docs/config-reference/` 以下（yml 本文コメントは editor 保存で消えるためこちらが正） |
| 設計判断の経緯 | `docs/design/` |
| 過去の作業記録 | `reports/` の日付入りファイル（**残タスクの一次情報ではない**） |

---

## 7. 作業履歴（新しいものを上に追記）

### 2026-07-27 — 14 要件バッチ（AFK / 図鑑 / スクラップ / スキルツリー機能アイテム / Ars レシピGUI / editor UI）

実測: TrinityForge **2330 / 失敗 0 / スキップ 2**、config-editor **614 / 614**、ArsPaper フォーク全緑。
コミット = TF `ee374c2`（`dev`）/ ArsPaper `e7028df`（`feat/trinityforge-fork`）。両方 push 済み。
**jar 2 本は未配備**（TF 13:01 / ArsPaper 13:04 ビルド）。

着手前ゲートでのユーザー確定事項:
- AFK 対策 = **報酬停止＋自動キック**（停止のみではなく、キックまでやる）
- スキル系アイテム = **ツリーGUI内でクリック**して使う（右クリック発動ではない）
- ソート仕様 = 名前検索はワイルドカード / 種類順は **ステータスに設定されたスキル種別（なければ素材）** /
  **素材数順は使わず「使用可能レベル順」** / 解放済み・未開放・すべては**ソートとは別のボタン**で切替

実装で非自明だった判断:
- **AFK 判定に素振り・素クリックを入れない。** 入れるとオートクリッカーで全部無効化される。
  移動（座標だけでなく**視点回転も**）・チャット・コマンド・インベントリ操作・ドロップ・スニークを活動とみなす。
- **バニラ EXP の停止は `PlayerExpChangeEvent` の LOWEST 1 箇所に集約した。** モブ討伐のオーブも
  拾った時点でこのイベントを通るので、`MobLevelTableListener` 側の `vanilla-exp` は**二重に止めない**
  （`add-drops` だけを止め、`remove-drops` は報酬ではないので通す）。
- **既存リスナーのコンストラクタは変えず、任意セッター注入（`setDropGate` / `setSellGate` /
  `setGrantSuppressor` / `setLockedPerkSupplier`）で横断ゲートを差した。** 述語の例外は全て fail-open。
- **モブ名はサーバ側で日本語化できない**ので `Component.translatable("entity.minecraft.*")` を使う
  （クライアント側翻訳＝Geyser でも効く）。日本語での検索・ソートが要る場合のために
  `collection.yml` の `display-names.mobs` で上書きできるようにした。
- **図鑑の検索は未発見エントリを対象外にする。** 名前を伏せている以上、名前で引けてはいけない。
- **解体でクラフトレシピを持たないアイテム（釣りのゴミ）を扱う唯一の手段が `base-amount`。**
  `input` はレシピを引くので必ず 0 個になり、戻りが発生しない。
- **ノードロックはプレステージ時のみの保護。** ロック済みノードは**返却せずに**無償再付与する
  （返却してから再付与すると、払っていない SP が増えて実質無限増殖になる）。
  ツリーリセット（`resetTree`）は逆にロックも含めて全解除する（保護すると SP が二度と戻らない）。
- **ツリーリセットは DB スキーマを増やさず `saveAdminProgressionEdit(prestigePerkPrefix=null)` に載せた。**
  レベル・プレステージ段はそのまま渡すので書き換わらない。
- **Ars レシピGUI の素材⇔完成品の突き合わせは `describeChoice` と対称な語彙**
  （`custom:<id>` / Material 名）で行う。TF カタログレシピだけは結果 ItemStack に
  カタログ id の PDC が載らない経路があるので、`catalog_<id>` というキー名から起こしている。
- **レシピGUIの検索入力はチャット経由。** 砧(anvil)GUI 方式は Bedrock/Geyser での見え方を
  こちらで実機確認できないため採らなかった。

**並行セッションとの衝突が 1 件あった**: `tools/config-editor/public/js/tf-crafting-features.js` への
解体フォーム改修が、別セッションのコミット `dba4b17`（AFK editor 統合）に巻き込まれて先に入った。
内容は失われていないが、§5 の「`git add -A` を使わない」規則が守られないと**他セッションの作業中の
変更が別名義のコミットに混ざる**という実例。

### 2026-07-27 — editor カバレッジの穴を 2 件ふさぐ（W-8 / W-9）

実測: config-editor **614 / 614**（`afk` 26 件・共通変数の新規 3 件を含む）。

- **W-8 `afk.yml`**: 「使用制限スイッチ」画面内のコンパニオンとして実装。
  **実ブラウザ（新ポートで起動し直した editor）で確認済み**:
  サイドバーに `afk` 単独項目が出ないこと / 12 キーが描画されること /
  出荷値（300・1800・40・免除権限・suppress 4 種）がロードされること /
  不正値の保存が 400 で弾かれ**ファイルが書かれない**こと /
  保存が `D:/.../plugins/TrinityForge/afk.yml` へミラーされること。
  **サーバ側 `registry.js` は起動時に読み込まれるので、登録を足したら editor の再起動が必要**
  （既存プロセスのままだと `/api/config/afk` が 404 になり、画面には何も出ない）。
- **`afk.yml` の本文コメントを `docs/config-reference/afk.md` へ移設した。**
  この画面から保存されるようになった＝**次の保存で本文コメントが全部消える**状態だったため、
  landmine を置いたまま終わらせないよう先に移設した（§5 の規則の適用例）。
- **W-9 `damage.yml` の 6 キー**: `FIELD_SPECS` と UI へ追加。値の正本は Java の `SchemaField` 宣言。
- 副次: 数値下限のエラーメッセージが `0.01以上値が必要です` という壊れた日本語だったので
  `0.01以上の値が必要です` に直した（既存テストで固定されていないことを確認済み）。
- **未着手**: W-10（registry ドリフト検知テスト）/ W-11（gate yml の SoT 名）。
- **版管理の注意**: `public/js/tf-crafting-features.js` には並行セッションが実装中の
  「解体ルールエディタ」が同居しており、この環境では**ファイルの一部だけを stage できない**ため
  一緒にコミットしている（隠さず commit message に明記した）。また **AFK 機構本体
  （`com/trinityforge/afk/` ・ `AfkConfig.java` ・ `afk.yml`）はまだ未追跡**で、
  そちらは並行セッションの担当。editor 側だけが先にコミットされている状態。

### 2026-07-27 — 軽装/重装ツリーを config-editor 経由で再配備 + editor カバレッジ監査

- **配備**: `PUT /api/config/skilltree-light-armor` / `skilltree-heavy-armor` を実行し、
  `D:/game/minecraft/PaperServer/TrinityForge/plugins/TrinityForge/skilltree/*.yml` へミラー成功
  （`deploy: {"ok":true}` を確認、SoT 側は `backups/` に自動退避）。
  editor の保存経路が配備も兼ねることを §5 に恒久メモとして記載した。
- **事前に本文コメントを移設**: editor 保存で本文コメントが消えるため、両 yml のコメントを
  `docs/config-reference/skilltree/{light,heavy}_armor.md` へ退避（light 8 件 / heavy 9 件、
  うち docs に無かった新規が 7 / 8 件）。移設漏れがあれば **lane-lock の警告コメントが消えていた**。
  差分は全てコメント行のみで、値の変更は `max-times: 1` の行末コメント除去だけであることを
  `git diff -U0` で確認済み。
- **editor カバレッジ監査**（「editor にあるべきなのに反映されていない設定」の洗い出し）:
  → 新規に W-8（`afk.yml` 未登録）/ W-9（`damage.yml` の 6 キーが共通変数画面に無い）/
  W-10（registry ドリフト検知テストが無い）/ W-11（gate yml の SoT 名が実在しない）を起票。
  なお `progression/combat-level.yml` の `skills` / `pillars` は `FIELD_SPECS` には無いが
  `extractConstants` が別途拾っており露出済み（＝漏れではない）。
- **検証**: TF **2318 件 / 失敗 0 / スキップ 2**。config-editor は **34 失敗**だが、
  全て `public/js/tf-crafting-features.js:808` の `SyntaxError: Unexpected token ')'` に起因する
  **並行セッションの実装中コード**が原因で、本作業とは無関係（set-buffs 系 7 件は全て緑）。

### 2026-07-27 — 防具セット効果を `set-buffs` + `armor-set-bonus` へ全面移行

実測: TF **2318 件 / 失敗 0 / スキップ 2**、config-editor **590 / 590**（いずれも独立に再実行して確認）。

- **新スキーマ `set-buffs`**（`light_armor` / `heavy_armor` のノードと prestige のみ）。
  `mainhand-buffs` と同型の条件バフだが、条件は**装備部位数**。
  段は **3 と 4 のみ**（1/2/5+ はロード時に警告して無視）。防具枠は 4 なので 3+3>4 となり、
  **軽装セットと重装セットは機構として併用不能**。閾値 2 だと軽装2＋重装2で二重取りできてしまう。
- **数える部位は所属ツリーで決まる**（`light_armor`→軽装 / `heavy_armor`→重装）。冗長なフィールドは足していない。
- **段の解釈は「最大の成立段のみ」**。ただし段の選択は**ノード単位**で行ってから加算する。
  先に全ノードを段ごとに合算してから最大段を選ぶと、片方のノードしか定義していない段が消える
  （`PerkBuffResolverTest` にこの順序の回帰テストを置いた）。
- **旧4キーを完全撤去**し `armor-set-bonus`（+%）1本へ統一
  （`light/heavy-armor-set-bonus-multiplier` / `light-armor-set-dodge-chance` /
  `heavy-armor-set-knockback-resistance`）。`*-move-speed-per-piece` は部位数比例の連続効果であって
  セット効果ではないので**対象外・現行維持**。
- ノードの数値は**現行挙動を厳密に保存する翻訳**（`3:` と `4:` に同値）で移行した。
  **その後（同日、ユーザーからの裁量委任を受けて）4 部位帯を約 1.5 倍に引き上げ、説明文の誤りを削除した**
  → W-6 / W-7 として下に分離して記載。
- **移行のついでに直した既存の穴 2 件**（set-buffs が任意ステータスを取れるようになると露呈するもの）:
  - `PlayerStatAggregator` が `dodge_chance` 決め打ちだった → `StatVocabulary.channelOf` で
    ATTACK/DEFENSE/GENERAL へ振り分け。値 0 のキーを生成しないガードは維持。
  - `PerkAttributeApplier` が bridge の戻り値を**全キー無条件**でマージしていた（すぐ下の
    `permanentBuffResolver` は ATTRIBUTE で絞っているのに）→ 同じ絞り込みを追加。
    絞らないと上の変更と合わせて非属性キーが二重に流れる。
- **副次効果**: 閾値が Java のハードコード定数（`SET_BONUS_MIN_PIECES=3`）から yml の宣言値へ移り、
  K-5 で挙げた「`labels.js` が 2 部位のまま」という stale の**発生源ごと消えた**。
- 新規に判明した制約 = K-7。

### 2026-07-27 — 移行に合わせたスキルツリー設定の調整（W-6 / W-7 を解決）

実測: TF **2318 件 / 失敗 0 / スキップ 2**（`:test` は実走、UP-TO-DATE ではない）、config-editor **590 / 590**。
既存テストがノード値をハードコードしていないことは事前に確認済み
（`AllSkillTreesLoadTest` / `AllSkillTreesPerkBuffTest` は実 yml を読むが値を検証しない。
`SkillTreeConfigTest` / `NativeAttributeBridgeTest` / `PerkBuffResolverTest` は合成ツリーを使う）。

- **4 部位帯の値（W-6）** — 規則は「**3 部位の値は移行前から一切動かさず、4 部位帯だけ約 1.5 倍**」。
  既存バランスに手を入れずフル装備の報酬だけを足すため。
  軽装 C `dodge-chance 0.1→0.15` / D-1-1・D-1-2 `0.05→0.08`、
  重装 C・D-1-1・D-1-2 `knockback-resistance 0.1→0.15`。
- **説明文の誤り（W-7）** — 両ツリーの **D** から「3部位でセットが成立」と `effects` リストを削除
  （D は `set-buffs` も `armor-set-bonus` も持たず、buffs は全て無条件）。
  併せて **B** の同じ記述も削除した。B が持つ `armor-set-bonus` は**増幅率であって成立条件ではない**ので、
  こちらも実装と一致していなかった。代わりに「セット効果量UP」という増幅である旨の文言に置き換えた。
- 変更の意図が次に読む人に伝わるよう、yml 側にも 1.5 倍規則と「B は成立条件ではない」旨をコメントで残した。

### 2026-07-27 — 実サーバへの配備（yml 42 本 + jar 3 本）

`cleanTest test` を実走させ **2318 件 / 失敗 0 / スキップ 2** を確認してから配備。
バックアップは `plugins/.deploy-backups/20260727_114327/`（置換前の全 yml と全 jar）。

- TrinityForge: jar（11:33 版）＋ yml 42 本。07-26 夜以降の未配備分（スキルツリー 16 本・
  skills/base 16 本・`mob-level-table` / `mob-types` / `crafting-features` / 採取ギミック /
  `skill-exp`）が丸ごと溜まっていた。**`combat/mob-profiles.yml` は保護して除外**。
- ArsPaper: jar（07-26 23:01 版）＋ `materials.yml`。
- EliteMobs: 全同梱 uberjar（`testbed/plugins/EliteMobs.jar`、07-26 16:20 版）。

**発見した実害バグ**: `plugins/` に `ArsPaper.jar`（07-26 09:10）と `ArsPaper-1.0.0.jar`
（07-26 17:33）が**両方存在**し、`latest.log` に
`[ModernPluginLoadingStrategy] Ambiguous plugin name 'ArsPaper'` の ERROR が出ていた。
両者は `paper-plugin.yml` の `name` も `version`（`0.1.0-SNAPSHOT`）も同一なので、
**どちらが読まれるかは不定＝ArsPaper の配備が黙って無視されうる状態**だった。
古い `ArsPaper.jar` を `.deploy-backups/` へ退避して解消（削除ではなく移動）。
**今後 plugins/ に同名プラグインの jar が 2 つ無いか、配備のたびに確認すること。**

### 2026-07-27 — フォーク 2 件の版管理（K-6 の大半をクローズ）

長期間ローカルにしか存在しなかったフォーク作業をコミットした。

- **ArsPaper** `feat/trinityforge-fork` = `17c9f65`（117 ファイル / +10004 -4054）。触媒アイテム・
  魔導書ティア・ソースジャー・スレッドセット・グリフ解放・レシピゲート・`SpellBreakMarker` ほか。
- **EliteMobs** `trinityforge-fork` = `ea043d3b`（52 ファイル / +2862 -223）。
  `com.magmaguy.elitemobs.trinityforge` パッケージ 12 クラス＋テスト 3 本。
  → **`Klee319/EliteMobs-trinityforge`（private）を新設して push 済み**。
  upstream は GPL-3.0 なので派生を保持すること自体に問題はない。

**最終的に 3 リポジトリすべて PUBLIC**（ユーザー判断）。公開前に追跡済み全ファイルを走査し、
資格情報・外部 IP・サーバーアドレス・ローカル絶対パスの混入がゼロであることを確認済み。

| リポジトリ | 可視性 | 既定ブランチ | 内容 |
|---|---|---|---|
| `Klee319/trinityforge` | PUBLIC | `main`（作業は `dev`）| TF 本体・editor・resourcepack・docs |
| `Klee319/ArsPaper` | PUBLIC | `master` | ArsPaper 本体。TF 連携は `feat/trinityforge-fork` |
| `Klee319/EliteMobs-trinityforge` | PUBLIC | `trinityforge-fork` | EliteMobs フォーク（upstream GPL-3.0）|

**途中で踏んだ事故**: `Klee319/ArsPaper` の可視性を確認せずに push し、`libs/TrinityForge.jar`
（TF 本体のコンパイル済み jar、1.4MB。**master には存在しない**）を意図せず公開した。
気付いた時点で remote ブランチを削除して差し戻し、判断を仰いだうえで再 push している。
**push 先の可視性は `gh repo view <repo> --json visibility` で毎回確認すること**
（同じ持ち主でも public/private が混在している）。

### 2026-07-27 — git 導入（K-4 クローズ）

`Klee319/trinityforge`（**private**）を新規作成し、初回インポートを `main` と `dev` に push。
以後の作業ブランチは `dev`。

- 追跡対象 1695 ファイル / 3.3MB。Java 381・出荷 yml 75・テスト 294・editor 140・resourcepack 522。
- 除外の主な判断:
  - `backups.zip` は **455MB** で GitHub の 100MB 制限に引っかかるため除外。`backups/`（38MB）も
    git で役目が置き換わるので除外した。
  - `fork-handoff/arspaper/fork/`・`fork-handoff/elitemobs/elitemobs-fork/`・`wiki/` は
    **独自の `.git` を持つ**ため除外。含めると gitlink（実体の無い submodule 参照）になってしまう。
    → 未バックアップのフォーク作業が残る問題を K-6 / J-8 として起票。
  - jar は全て再生成物として除外（例外は gradle wrapper）。`source/` はベンダー jar だけなので空になる。
  - `.cursor-rcon.py` は `D:\game\...` の絶対パスを含むローカル運用スクリプトなので除外。
    RCON パスワードは `server.properties` から実行時に読む作りで、**ハードコードはされていなかった**。
- `README.md` を新規作成（構成表 / ビルド手順 / 含まれないものの明示）。

### 2026-07-27 — 保留リストの棚卸し / 承認 4 件 / チャージ射撃解放の撤去

詳細は `reports/20260726_1740_PendingAndResidualTasks.md` §11。要点のみ:

- **棚卸し**: 判断待ちとして繰り越されていた項目の **15 件が既に実装済み**だった
  （スキルツリー草案 16 本の適用 / 生産草案 4 件 / スタン時間キー /
  `ingredient_save_chance` のバニラ醸造拡張 / 弓術の距離ダメージ / セット効果 2 部位と金装備の矛盾 ほか）。
- **採取ギミックのツール判定**を use-skill ベースへ移行（`gathering.GatheringToolMatcher` 新設）。
  一括伐採は戦闘用の斧で発動しなくなり、**判定ゼロだった**一括破壊（採掘）と範囲収穫（農業）に
  判定が入った。素のバニラ道具はマテリアル推論で従来どおり許可。
- **PvP 抑制**を新設（`combat.PvpDamagePolicy` / `combat/damage.yml` の `pvp:`）。
  主命中・AoE スプラッシュの両方に適用。出血 DoT は主命中の最終ダメージ由来なので自動追随。
- **ATTRIBUTE チャネルの上限**を実装（`PerkAttributeApplier` の合算完了直後にクランプ点）。
  editor の「上限が効かないので出さない 5 キー」枠は廃止し、表示する側へ反転（キー総数 87→92）。
- **`charged-shot-unlocked` を全面撤去**。「解放フラグ」を名乗りながら、解放対象の効果が
  いずれも自分自身のステの非 0 判定を個別に持ち、フラグの OR 条件にも同じステが並ぶ
  **完全な同語反復**で、あってもなくても挙動が変わらなかった。
  併せて `base-stats.yml` が `1` を全プレイヤーに配っていたことも判明（死にキーゆえ実害なし）。
- **ドキュメント 3 ファイル**（`VALHALLA_DEFAULT_SKILLS.md` ほか）は削除ではなく
  **警告の明示**で対応。3 件とも自らを「無改変の転写」「履歴資料」と宣言しており、
  記述を削ると文書自身の契約が壊れるため。

### 2026-07-26 — オーバーワールドEXP開放 / TT対策 / 全モブ定義

- オーバーワールドの戦闘スキル EXP を `outside-dungeon-exp-rate: 0.25` で開放（ダンジョンが 4 倍速い）。
- TT 対策 = 同一地点の逓減（`LocationExpDiminishing`）。半径 24 / 5 分窓 / しきい値 30 体 /
  1 体あたり -5% / 下限 10% / ダンジョン除外。**カウンタを増やすのは撃破のみ**
  （読むたびに記録する設計だと被弾でも増え、正常なプレイで EXP が減り始める）。
- 1.21.11 の全モブを `mob-types.yml` に定義（13 体 → **89 体**）。`MANNEQUIN` は唯一 `Mob` を
  実装しない `LivingEntity` なので意図的に除外。
- 魔法（Ars）の破壊グリフが合成する `BlockBreakEvent` を `SpellBreakGuard` で遮断
  （農業 × 破壊グリフの永久機関、採掘の鉱石消失を封じた）。
- `skills/base/*_progression.yml` から ValhallaMMO 時代の死にキー **66 件 / 15 ファイル**を除去。
