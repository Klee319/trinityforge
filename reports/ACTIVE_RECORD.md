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

## 1. 現在の状態（2026-07-27 10:41 時点）

| 対象 | 状態 |
|---|---|
| TrinityForge テスト | **2309 件 / 失敗 0 / スキップ 2**（実測） |
| config-editor テスト | **582 / 582**（実測） |
| ArsPaper フォーク | BUILD SUCCESSFUL（2026-07-26 時点。07-27 バッチでは未変更） |
| `TrinityForge-0.1.0-SNAPSHOT-all.jar` | **2026-07-27 10:41** ビルド済み |
| `ArsPaper-1.0.0.jar` | 2026-07-26 23:01 ビルド済み |
| 実サーバへの配備 | **未確認**（配備先が CWD 外のため状態を見ていない。少なくとも 07-26 夜以降のぶんは未配備の想定） |

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
| J-8 | **2 フォークの版管理をどうするか**（K-6） | 選択肢: ①`Klee319/EliteMobs-trinityforge` を private で新設して push（GPL-3.0 なので派生の保持は可能。ArsPaper 側は既存の `Klee319/ArsPaper` へ push するだけ） ②本リポジトリの submodule として組み込む ③現状維持（バックアップ無しを許容）。**私の推奨は ①**。いずれも remote への push を伴うのでユーザー判断が要る |
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
| K-6 | **2 つのフォークの作業がバックアップされていない**（2026-07-27 確認） | `fork-handoff/elitemobs/elitemobs-fork/`（branch `trinityforge-fork`）と `fork-handoff/arspaper/fork/`（branch `feat/trinityforge-fork`）にそれぞれ**未コミットの変更**が残っている。ArsPaper は `Klee319/ArsPaper` へ push すれば済むが、**EliteMobs フォークは remote が upstream の MagmaGuy/EliteMobs しか無く push 先が無い**。upstream は GPL-3.0 なので派生の公開自体は可能。対処 = J-8 |

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
- `backups/` と `backups.zip`（455MB）は git 導入以前の手動バックアップ。追跡しない。
- jar は全て追跡しない（`source/` のベンダー jar、フォークの `libs/TrinityForge.jar` を含む）。
  例外は gradle wrapper のみ。

配備・ビルド系:

- **EliteMobs フォークの配備は必ず `testbed/plugins/EliteMobs.jar`（全同梱 uberjar）。**
  `build/libs/*-min.jar` は MagmaCore 剥離で `NoClassDefFoundError` になり起動しない。
- **`combat/mob-profiles.yml` はリポジトリ側から上書きしない。** `importmobs` の生成物（268KB）。
- **jar を差し替えたらフル再起動。** reload では不十分（ホットスワップは `NoClassDefFoundError` を招く）。
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
