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

## 1. 現在の状態（2026-07-27 15:35 時点）

| 対象 | 状態 |
|---|---|
| TrinityForge テスト | **失敗 0 / スキップ 2**（実走・実測。スキップは既知の正当な 2 件のみ＝`OfflineMobImportRunner` / `NativeProgressionStabilizationContractsTest`。テスト結果 XML の `skipped=` を直接数えて確認済み） |
| config-editor テスト | **全緑**（実走・実測、`===EDITOR_EXIT=0`） |
| **配備（2026-07-27 15:35 のバッチ）** | **ビルド済み・未配備**。`TrinityForge/build/release/TrinityForge-all.jar`（15:34、15,780,723 bytes、`016ec30` までを含む）を作成済みだが、**`D:/` への書き込みが権限ゲートに拒否されたためユーザー実行が必要**。`cmd /c tmp\run-deploy-k5b.cmd` を実行すれば、既存 jar と `stats/lore.yml` / `skilltree/farming.yml` / `skilltree/ars_magic.yml` を `backups/deploy-20260727/` へ退避してから上書きし、反映を検証する。**jar を差し替えるのでフル再起動が要る**（reload では不可）。**yml だけでなく jar も要る**点に注意（`/tf stats detail` と確率バグ修正は Java 側）。**この配備に config-editor の保存ミラー（下記「配備手段」）を使ってはいけない** — `lore.yml` の説明コメントが全部消えるため（§5 / K-3） |
| ArsPaper フォーク テスト | **全緑**（`RecipeBrowserFilterTest` 11 件を新設。`test --offline` で実走） |
| `TrinityForge-0.1.0-SNAPSHOT-all.jar` | 2026-07-27 15:34 に `016ec30` で再ビルド済み → **未配備**（上の「配備」行が最新の手順） |
| `ArsPaper-1.0.0.jar` | 2026-07-27 13:04 ビルド → **未配備、かつ HEAD より古い**（グロブ修正 `d3a3210` を含まない）。**配備前に再ビルドが要る** |
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
| ~~J-1~~ | ~~次期方針 4 点~~ | **破棄（2026-07-27、ユーザー指示）** |
| ~~J-2~~ | ~~経済・通貨システムの新設~~ | **破棄（2026-07-27、ユーザー指示）** |
| ~~J-3~~ | ~~ダンジョン名を TF のどこに出すか~~ | **破棄（2026-07-27、ユーザー指示）** |
| ~~J-4~~ | ~~素材タブのカテゴリバーをスティッキーにしないか~~ | **判断待ちではなく実バグだった（2026-07-27、ユーザー指摘）**。「スクロールするとカテゴリバーがメインコンポーネントから離れる」UI の乱れ。**テストの期待値は変えない**（現行の期待値が正しく、実装側が崩れている）。§3 の W-13 へ移動 |
| ~~J-5~~ | ~~モブ HP 上限 1024 の是非~~ | **破棄（2026-07-27、ユーザー指示）**。上限なしのままで確定 |
| ~~J-6~~ | ~~触媒のオフハンド運用~~ | **破棄（2026-07-27、ユーザー指示）**。`offhand-stats-apply` で許可/不許可は**既に config で設定可能**であり、どのアイテムにも設定されていないのは**運用上の未設定であってバグではない** |
| ~~J-10~~ | ~~資源サーバ分離の配備とネザーのボーダー値~~ | **破棄（2026-07-27、ユーザー指示）**。作業書 `ops/RUNBOOK.md` は残す |
| J-9 | **農業「ゴミ食II」を上位ノードとして成立させるか** | 段階化（W-5 ①）は倍率 1 個で実装したため、上位ノードの `value: 150` は**ゴミ食の恩恵と非ゴミ食のペナルティを両方 1.5 倍**にする。「ゴミ食専門家になるほど普通の飯が体に合わなくなる」という筋は通るが、**上位ノードを取ると普通の食事が今より不利になる**ので、スキルとしては取得を躊躇させる。元の草案テキストは「ゴミ以外の満腹度回復量を**戻す**」＝**欠点が消える**方向で、実装と正反対だった（2026-07-27 に発見、テキスト側を実装に合わせて修正済み）。草案の意図を採るなら**恩恵側とペナルティ側で別パラメータが要る**（`junkfood-inversion` を 2 値化）。現状は「両方 1.5 倍」で出荷される |
| ~~J-8~~ | ~~**ArsPaper フォークの push 先**~~ | **解決（2026-07-27）**。ユーザー判断により**3 リポジトリすべて PUBLIC**。`Klee319/ArsPaper` の `feat/trinityforge-fork` を再 push し、`trinityforge` と `EliteMobs-trinityforge` も public 化した |
| ~~J-7~~ | ~~**K-5（ステータスのトリガー/発動制限の明示）の修正プラン、着手前の 2 点**~~ | **解決（2026-07-27、ユーザー判断）**。①詳細の出し先 = **`/stats detail <key>` サブコマンド**（統合版で hover が効かない・lore の行数制限に当たらないため）。②着手範囲 = **120 キーを一括で埋める**（私の推奨は戦闘系 20 キーでの先行検証だったが、ユーザーが一括を選択。途中状態を残さない方を優先）。プラン本体は §4 の K-5 直下 |

---

## 3. 残タスク — 手を動かせば終わるもの

| # | 内容 | 前提 |
|---|---|---|
| W-1 | **実機スモークテスト**: `dropsVanillaLoot: true` のダンジョンボスを 1 体倒し、バニラEXPオーブが意図した量で出るか確認 | **配備後にしかできない**。fork 側半分は TF のユニットテストで触れないため実機確認が必須。対象は実測 159 体（config 408 中 `false` 249 / `true` 32 / 未指定 127） |
| W-2 | **PvP の実プレイ調整** | `pvp.damage-multiplier: 0.5` / `max-damage-percent-of-max-health: 0.15` は机上値。「最低 7 発で倒れる」の手触りは実測前提 |
| ~~W-3~~ | ~~採取系見直し **W2（パラメータ化）/ W3（UX）**~~ | **取り下げ（2026-07-27、ユーザー指示）**。「一旦残タスクリストから削除」。設計書が実在せず設計の書き起こしから必要な規模だったため。W1 基盤（`com.trinityforge.active`）は実装済みで、そこまでで止める。再開したくなったらこの行を復活させる |
| ~~W-4~~ | ~~ars_magic のマナ系 4 キーを実効化~~ | **記録が stale だった（2026-07-27 に実コードで確認）。4 キーとも既に実効化済み。** `hit-mana-recovery` / `damage-mana-recovery` = `ArmorManaListener.java:279-282` が `TrinityForgeBridge.tfNonItemStatTotal(player, ...)` で非装備分（パーク/役職/永続バフ/base-stats）を加算している。`mana-cost-reduction-flat` / `-percent` = `SpellCaster.java:294-302` が同じく非アイテム分を触媒由来分と加算合成し、`clampReductionFraction` で [0,0.95] にクランプしている（いずれも 2026-07-26 実装）。「フォークがメインハンドしか読まない」という記述はその実装以前のもの |
| ~~W-5~~ | ~~スキルツリー草案が「今回は対象外」と明記した 2 件~~ | ①**農業のゴミ食の段階化** = **解決（2026-07-27、テスト実走待ち）**。`junkfood-inversion` を `NONE`→`LEVEL`(％) 化し、`FoodGimmickListener` を `isActive()` から `valueMax()` へ切替、`stats/food-gimmick.yml` のグローバル値に `value/100` を掛ける形にした。農業 `A-alpha-1` に `value: 100`（＝現行値と厳密一致）、`A-alpha-2` に `value: 150` を配置。**`LEVEL` は value 欠落時に現行挙動へフォールバックせず、パース時にエントリごと捨てられる**（`SkillTreeConfig.java:368-372` で warning）ため、`value` の書き忘れは `AllSkillTreesLoadTest`（warning ゼロを要求）がビルドで落とす。この「ビルドが守る」経路自体をテストで直接証明した。②②~~切削 E-β のオフハンド+スニーク破壊~~ = **取り下げ（2026-07-27、ユーザー判断）**。機構自体が未実装で、1 ノードのために新しい自動化機構を作る価値は無いという判断。スキルツリー側もノード未配置のまま |
| W-13 | **素材タブのカテゴリバーがスクロール時に本体から離れる**（2026-07-27 ユーザー報告） | UI の乱れ。スティッキー追従が崩れていて、スクロールするとカテゴリバーがメインコンポーネントから分離して見える。**`test/split-view-sticky.test.js` の期待値は変えないこと**——現行の期待値が正しく、壊れているのは実装側 |
| ~~W-12~~ | ~~エンチャントプレステージの「消費経験値レベルを減らす」stat が語彙に無い~~ | **記録が stale だった（2026-07-27 に実コードで確認）。新設は不要。** 語彙 `enchant_cost_reduction` は既に存在し、`EnchantCostReductionListener#reducedCost(int cost, double reduction)`（`MAX_REDUCTION = 0.9` / `MIN_LEVEL_COST = 1`）が**エンチャントテーブルの消費レベルそのもの**を減らしている。`skilltree/enchanting.yml:24-27` も既に `enchant_cost_reduction: 0.10` を宣言し、説明文も「エンチャントの消費経験値レベルを永続-10%軽減」で実装と一致。`enchant_exp_gain_bonus` への読み替えという記述自体が誤り |
| ~~W-6~~ | ~~**`set-buffs` の 4 部位帯の値を決める**~~ | **解決（2026-07-27、ユーザーからの裁量委任による）**。採用した規則 = **3 部位の値は移行前から一切動かさず、4 部位帯だけを約 1.5 倍**（既存バランスを動かさずフル装備の報酬だけを足す）。軽装 C = `dodge-chance 0.1→0.15` / D-1-1・D-1-2 = `0.05→0.08`、重装 C・D-1-1・D-1-2 = `knockback-resistance 0.1→0.15`。`effect-text` にも「4部位で強化」を明記した |
| ~~W-7~~ | ~~**軽装/重装 D ノードの説明文が実装と一致していない**~~ | **解決（2026-07-27）**。二択のうち「文言を消す」を採用（D は `set-buffs` も `armor-set-bonus` も持たない＝バフは全て無条件なので、説明を実装に合わせるのが正）。両ツリーの D から「3部位でセットが成立」の記述と `effects` リストを削除。併せて B の同記述も削除した（B が持つ `armor-set-bonus` は**増幅率であって成立条件ではない**ため、こちらも誤りだった） |
| ~~W-8~~ | ~~**`afk.yml` が config-editor に登録されていない**~~ | **解決（2026-07-27）**。ユーザー指示により独立タブは作らず、**「使用制限スイッチ (use-requirements)」画面内へコンパニオン表示**（`farming-gimmick`＋`food-gimmick` と同じ方式。`USE_REQUIREMENTS_COMPANION_IDS` → `HIDDEN_CONFIG_IDS` でサイドバーからは隠す）。描画は新規 `public/js/tf-afk-form.js` に隔離し、`tf-crafting-features.js` への変更は呼び出し 15 行のみ。**Java が黙って丸める 2 ケース（`check-interval-ticks < 20` / `kick-after-seconds` が非 0 で `idle-seconds` 未満）は editor 側では保存時エラーにした**（黙って丸めると「保存した値」と「実挙動」がずれるため）。実ブラウザで 12 キーの表示・値のロード・バリデーション 400・保存→配備ミラーまで確認済み |
| ~~W-9~~ | ~~**`combat/damage.yml` の 6 キーが「共通変数」画面に出ていない**~~ | **解決（2026-07-27）**。`FIELD_SPECS` へ 6 件追加。`min`/`max`/`def` は全て `CombatDamageConfig.java` の `SchemaField` 宣言（L90-98 / L125）と一致させた。`enchant-protection-scale` の上限は**バニラ相当の 1.0 ではなく Java 通りの 10**（editor だけ狭いと「yml では通る値が editor で弾かれる」ズレになる）。UI は「近接チャージ」「攻撃速度」セクションを新設し、`enchant-protection-scale` は既存の「防御(安全弁)」へ |
| ~~W-10~~ | ~~**registry のカバレッジドリフト検知テストが無い**~~ | **解決（2026-07-27、テスト実走待ち）**。`tools/config-editor/test/registry-coverage.test.js` を新設（3 本）。①`basePaths` 配下の全 yml が `registry.js` か `CONSTANT_SOURCES`（共通変数ビューは registry を通らないため正当なカバレッジ源として扱う）に載っているか、②許可リストの各行に実ファイルがあるか（死んだ許可リスト行の検知）、③registry の各エントリに実ファイルがあるか（typo/削除で editor が 404 になる事故の検知）の**双方向**。許可リストは 5 件で、「減る一方であるべき」旨をコメントに明記した |
| ~~W-11~~ | ~~ArsPaper の gate yml 2 本が挙げる SoT ファイル名が実在しない~~ | **解決（2026-07-27）。ただし調査で範囲が大幅に広がった。** `skilltree/dedicated-effects.yml` は 2026-07-23 の stat-gate 改修で**削除済み**なのに、これを「正本」として指す記述が**リポジトリ全体で 21 箇所**残っていた（gate yml 2 本のほか、`stats/*-gimmick.yml` 4 本、`TrinityForge.java` 4 箇所、各 `*GimmickConfig.java`、`SkillNode.java`、`VeinMiningAlgorithm.java` 等）。実体は各 `skilltree/*.yml` ノード内の `dedicated-effects:` フィールド。**全て参照先の記述を実体へ修正**。歴史的記録として残すもの（`docs/design/2026-07-23-stat-gate-overhaul.md`＝廃止を決めた設計書、`GREENFIELD-REMAINING-TODO.md`）と、コメントでなく実コードのファイル名フィルタ（`NativeRewardRegistryContractTest.java:28`）は意図的に除外 |

---

## 4. 既知の未修正の問題・弱点

いずれも**意図的に許容している**か、**直すには判断が要る**もの。新規に見つけたバグはここへ足す。

| # | 内容 | 判断 |
|---|---|---|
| K-1 | **同一地点EXP逓減が友好モブの養殖場を数えない** | カウンタはバニラEXP書き込み経路で回るため、レベル帯の対象外である C 群（友好モブ 39 種）の養殖場には反応しない。TT の本命ではないので現状は許容 |
| K-2 | **ATTRIBUTE チャネルの上限は「実効値」の上限ではない** | `move-speed` / `attack-speed-bonus` / `attack-reach` / `knockback-resistance` / `max-health` の上限は「**TF が要求する寄与分**」に掛かる。Haste や他プラグインの寄与は含まれない。`PerkAttributeApplier` の「ライブ属性値を一切読まない」設計原則を守るための意図的な線引き（詳細 = `docs/config-reference/combat/stat-caps.md`） |
| K-3 | **editor で yml 本文のコメントが保存時に消える** | `tools/config-editor/lib/yamlio.js` の仕様。対策は説明コメントを `docs/config-reference/` へ退避すること。新しく長いコメントを yml 本文に書かない |
| ~~K-4~~ | ~~**このリポジトリは git 管理下にない**~~ | **解決（2026-07-27）**。`Klee319/trinityforge`（private）を作成し初回インポート済み。作業ブランチは `dev`。ただし下記 K-6 の 2 フォークは対象外なので、そちらを触る前は従来どおり `backups/` を取ること |
| K-5 | **ステータスのトリガーと発動制限が、どこにも機械可読な形で存在しない**（2026-07-27 確認） | 説明文は自由文でエディタ専用、実装との紐付けがゼロ。結果として**説明が実装から静かにずれる**。**段階 1〜3 は 2026-07-27 に完了**（宣言スキーマ＋拘束テスト＋`/tf stats detail <キー>`＝仕組みは動いている）。**残りは段階 4 の記入分のみ（115 キー中 84 キー宣言済み・31 キー未宣言）**。未宣言分は「語彙が足りない」「単一の契機で表せない」「そもそも合算チャネルを通らない」のいずれかで、理由付きで `UNDECLARED_ALLOW_LIST` に固定してある（＝新しいキーを宣言なしで足すとビルドが落ちる）。詳細は直下 |
| ~~K-6~~ | ~~**2 つのフォークの作業がバックアップされていない**~~ | **解決（2026-07-27）**。両フォークとも未コミット分をコミットして push 済み（ArsPaper `17c9f65` → `Klee319/ArsPaper` の `feat/trinityforge-fork` / EliteMobs `ea043d3b` → `Klee319/EliteMobs-trinityforge` の `trinityforge-fork`）|
| ~~K-8~~ | ~~**マナ回復 2 キーの名前と実装が入れ替わっている**~~ | **解決（2026-07-27、ユーザー承認）**。二択のうち**①表示名を実装に合わせる**を採用（プレイヤーのビルドが変わらないため）。キーは configs/装備データが参照するので変更していない。`hit-mana-recovery` =「被弾マナ回復」（`ArmorManaListener#onPlayerDamaged`）／`damage-mana-recovery` =「攻撃マナ回復」（`onPlayerDealDamage`、近接直撃のみ）。**同じ向きの間違いが 3 箇所に波及していた**: ①`stats/lore.yml` の当該 2 キー ②同 `mana-onhit-percent` / `-flat`（フォーク `ManaRecoveryListener#onPlayerDamaged` が `onHitPercent/Flat` を使う＝被弾側なのに表示名が「命中マナ回復率/(実)」だった）③`skilltree/ars_magic.yml` の B「マナの操り手」の `effect-text`（プレイヤーに見える文言）。editor 側 `labels.js` の 4 系統（短縮ラベル／stat 説明／base-stats 説明／防具 stat ラベル）も同時に修正。**逆であることの根拠**は `items/catalog.yml` のスレッド 2 種（`thread_hit_mana_recovery` =「被弾マナ回復のスレッド」／`thread_damage_mana_recovery` =「攻撃マナ回復のスレッド」）が元から実装と一致していた点＝ズレていたのは `lore.yml` 側だけ |
| K-9 | **`glyph-damage-multiplier-bonus` がどこからも呼ばれていない**（2026-07-27 発見・未修正） | 公開 API `TrinityForge#glyphDamageMultiplier` は存在するが、`fork-handoff/` 全体を検索しても**呼び出し元がゼロ**。ステータスとしては lore に出るが**効果が無い**。K-7 と同じ「lore に出るのに効かない」型。配線するか、キーごと撤去するかの判断が要る |
| K-10 | **確率ステータスの二重縮小バグが 4 件連続で見つかった**（2026-07-27） | `PercentStatNormalize.RATE_KEYS` のキーは集計時点で「20 → 0.2」へ矯正されるのに、消費側がさらに 100 で割る。**実効値が設定値の 100 分の 1** になる。①養蜂の幸運（2026-07-26 修正済）②怪しい塊の再湧き ③食料節約 ④ガチャのレートアップ、の 4 件。②③は共有ユーティリティ `MiningGimmickPolicy.percentRoll` を呼んだのが原因で、**同関数は本番呼び出し元ゼロになったため削除**。④は引数名が `percentBonus` だったため小数を渡していることに気付けなかった別経路。**全 RATE_KEYS の消費側を全数調査中**（結果は §7 の履歴に記録する） |
| K-7 | **`armor-set-bonus` はスキルツリー由来分しか増幅に効かない**（2026-07-27） | `NativeAttributeBridge.armorAttributesFor` は `perkBuffs.buffsFor(id).general()`（＝パーク由来）だけを読む。そのため `base-stats.yml` / 装備 / 永続バフ / 役職バフ に `armor-set-bonus` を置いても**セット効果の増幅には効かない**。総合ステータスとしては登録済みなので `/stats` には出る＝**装備に付けると lore に出るのに効かない**。撤去した旧4キーと全く同じ制約なので回帰ではない。正すには aggregator→bridge の循環依存を解く必要がある。editor の base-stats 画面では `NO_OP_BASE_STATS_KEYS` で非表示にしてある |

### K-5 — 現状と修正プラン

> **進捗（2026-07-27）: 段階 1・2 完了。仕組みは動いている。残りは段階 3・4。**
>
> - **段階 1（宣言スキーマ）完了**。`stats/lore.yml` の各ステータスに任意の `trigger:` / `limits:` を書けるようにした。
>   語彙は Java enum 4 本（`StatTriggerWhen` / `StatSourceScope` / `StatAppliesTo` / `StatStacking`）＋
>   `tools/config-editor/lib/lore-declaration-vocabulary.js` の JS ミラーで閉じており、
>   両者の一致はドリフト検知テストが担保する（このリポジトリで Java/JS 語彙の食い違いは何度も起きているため）。
> - **段階 2（拘束テスト）完了**。`limits` の各上限は `<キー>-ref` で実装値への解決可能なポインタを持てる。
>   形式は `"combat/damage.yml#defense.max-dodge-chance"`（yml のキーパス）と
>   `"java:<完全修飾クラス名>#<定数名>"`（リフレクション）の 2 種。`LoreConfigDeclarationTest` が
>   全ステータス × 全宣言済み上限を回して**解決した実値と宣言値の一致を表明する**ので、
>   どちらかを動かすとビルドが落ちる。参照先が無い / 型違い / `public static final` でない場合も落ちる
>   （＝マジックナンバーを「単にリフレクションで読める」だけにして誤魔化せない）。
> - **記入済みは 10 キー**（`dodge-chance` / `armor-strength` / `distance-damage-bonus` / `phys-resistance` /
>   `magic-resistance` / `damage-reduction` / `armor-defense-rate` / `stun-duration-bonus` / `max-health` / `move-speed`）。
>   残り 110 キーは**明示的な許可リスト**に載っていて、**リストに無い未宣言キーはテストが落とす**
>   （＝新しい穴が増えない。リストは減る一方であるべきもの）。
> - 昇格させた Java 定数: `CombatListener.MAX_DISTANCE_DAMAGE_BLOCKS`（裸の `64.0` だった）、
>   `NativeCombatPerkListener.MAX_STUN_DURATION_TICKS`（パッケージプライベートだった）。
> - **設計のやり直しを 1 回した**: 当初は `-ref` が `cap` / `floor` にしか無かったため、
>   `distance-damage-bonus`（％表示）に `cap: 64`＝「64 ブロックで頭打ち」の意味で書かれ、
>   **「上限 6400%」と読める宣言**になっていた。スタン時間の `cap: 100`（実際は 100 tick）も同じ。
>   ずれを消すための宣言がずれを生んでいたので、**数値上限フィールドごとに `-ref` を持てる規則へ一般化**し、
>   `max-distance` / `max-duration-ticks` に専用フィールドを与えた。`limits` の未知フィールドはエラーにする
>   （typo が黙って無視されると「宣言したつもり」が発生し、仕組み全体が無意味になる）。
> - 下の段階 2 の表は**起票時のもので 2 箇所が誤り**だった: `NativeAttributeBridge.SET_BONUS_MIN_PIECES` は
>   set-buffs 移行で**既に存在しない**（部位閾値は各スキルツリー yml の `set-buffs:` の段）。
>   `armor-strength` の突き合わせ先は `PlayerDefenseResolver` ではなく
>   `DefenseStats.cappedCritReduction`（`combat/damage.yml#defense.max-crit-reduction`）。
> - **`armor-set-bonus` は意図的に未宣言**。部位閾値が「1 つの定数」ではなく各ツリーの `set-buffs:` に
>   複数段で分散しているため、単数形の `min-pieces` では表せない。語彙を増やす判断は保留。

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

**Phase 4 — 120 キーの移行**。**2026-07-27 のユーザー判断で「一括で埋める」に確定**（先行検証案は不採用）。
埋める順は誤解の実害が大きい順に **戦闘(attack/defense) → 採取 → 生産 → Ars → utility**。
許可リスト（未記入を許すキーの一覧）は**一括投入後に空になる前提**で置き、
「新規キー追加時は宣言必須」のラチェットだけを恒久ルールとして残す（新しい穴が増えないようにする）。

**着手前の決定事項（2026-07-27 ユーザー判断、J-7 として起票していたもの）**

| 決めたこと | 結論 | 理由 |
|---|---|---|
| プレイヤー向けの詳細の出し先 | **`/stats detail <key>` サブコマンド** | 統合版(Geyser)はチャットの hover が効かず、アイテム lore は行数制限に当たる。lore.yml から自動生成する |
| 移行の着手範囲 | **120 キー一括** | 途中状態を残さないことを優先（私の推奨は戦闘系 20 キー先行だった） |

---

## 5. 恒久的な注意事項（繰り返し踏んでいるもの）

**残タスクに載せてよいものの線引き（2026-07-27 ユーザー指示・恒久ルール）:**

- **「機構は実装済みで、config に値が入っていないだけ」のものは残タスクにしない。** 握りつぶすこと。
  運用上どう設定するかはユーザーの裁量であって、こちらが片付ける課題ではない。
  例: 触媒のオフハンド運用（`offhand-stats-apply` は既に config で許可/不許可を設定できる。
  どのアイテムにも設定されていないのは未設定であってバグではない）。
- **死にステータスは 2 種類に分けて扱う:**
  - **配線実装は済んでいて config が未設定なだけ** → 上と同じく**握りつぶす**。
  - **配線実装が無いのに config や計画にパラメータとして存在する** → **残タスクとして残す**。
    こちらは「設定できるのに効かない」＝プレイヤーを騙す状態なので放置しない。
    該当例: `glyph-damage-multiplier-bonus`（K-9）。
    ※ エンチャント消費EXP軽減（旧 W-12）はこの型ではなかった——実装も語彙も既にあり、
    記録のほうが古かっただけ。**この型で起票する前に必ず実コードで裏を取ること。**
- **ユーザー報告の「UI が崩れる」は判断待ちではなく実バグ**として扱う。設計の選択肢として起票しない。

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
- **リポジトリ直下の `.gitattributes`（`*.java` / `*.js` / `*.yml` に `text eol=lf`）を消さない。**
  2026-07-27 追加。Windows の編集ツールがファイル全体を CRLF で書き戻すため、実質数行の変更が
  「全行が変わった」差分になってレビュー不能になっていた（`TrinityForge.java` 1477/1426 ←
  実変更 61/10）。`text eol=lf` は**作業ツリーが CRLF でもコミットされるバイト列を常に LF に
  そろえる**ので、ツール側の挙動に関係なく差分が実変更だけに収まる。差分が異常に膨らんだら
  まずこのファイルが効いているか疑う。`*.json` はリソースパックの生成物が大半なので対象外。
- **`git add --renormalize` をパス指定なしで使わない。** 上記 `git add -A` と同じ理由（worktree の
  内容をそのままステージするので、並行セッションの作業途中の状態まで自分のコミットに入る）。
  正しい手順は §7「差分レビューと指摘修正」の項に書いてある。
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
| 資源サーバ分離（Velocity 2 バックエンド化）の作業書 | `ops/RUNBOOK.md`。周辺は `ops/README.md` から辿る |

---

## 7. 作業履歴（新しいものを上に追記）

### 2026-07-27 — マナ回復ステータスの表示名が実装と逆だった件（K-8）と W-12 の取り下げ

`016ec30`。**表示名だけを実装に合わせる**（キーは変えない＝プレイヤーの既存ビルドが変わらない）
方針をユーザーが承認したので適用した。

- `hit-mana-recovery` → 「被弾マナ回復」（`ArmorManaListener#onPlayerDamaged`）
- `damage-mana-recovery` → 「攻撃マナ回復」（`#onPlayerDealDamage`、近接直撃のみ）

**逆であることの根拠**は `items/catalog.yml` のスレッド 2 種が**元から実装と一致していた**こと
（`thread_hit_mana_recovery` =「被弾マナ回復のスレッド」/ `thread_damage_mana_recovery` =
「攻撃マナ回復のスレッド」）。つまりズレていたのは `stats/lore.yml` 側だけだった。

**同じ向きの間違いが 2 箇所に波及していた**（当初は 2 キーだけの問題だと思っていた）:

1. `stats/lore.yml` の `mana-onhit-percent` / `-flat` が「命中マナ回復率/(実)」。フォークの
   `ManaRecoveryListener#onPlayerDamaged` が `onHitPercent/Flat` を使う＝**被弾側**なので逆。
2. `skilltree/ars_magic.yml` B「マナの操り手」の `effect-text`（**プレイヤーに見える文言**）が
   「命中時マナ回復+1」。ここが一番実害があった。

editor 側 `labels.js` も 4 系統（短縮ラベル / stat 説明 / base-stats 説明 / 防具 stat ラベル）が
同じ向きにずれていたので同時に修正。スキルツリー草案の参照表 2 本も直した（次の草案作成で
同じ誤りが再生産されるため）。

**W-12（エンチャント消費EXP軽減の stat が語彙に無い）は記録が古かっただけで、実装は既にあった。**
`enchant_cost_reduction` は語彙にあり、`EnchantCostReductionListener#reducedCost`
（`MAX_REDUCTION = 0.9` / `MIN_LEVEL_COST = 1`）が**エンチャントテーブルの消費レベルそのもの**を
減らしている。`skilltree/enchanting.yml:24-27` も既に宣言済みで説明文も一致。
「`enchant_exp_gain_bonus` へ読み替えてある」という記述自体が誤りだった。
→ §5 の「死にステータスは 2 種類に分けて扱う」に**この型で起票する前に実コードで裏を取る**旨を追記。

検証: config-editor 全緑（`EDITOR_EXIT=0`）/ TrinityForge `BUILD SUCCESSFUL`・失敗 0・
スキップは既知の 2 件のみ（テスト結果 XML の `skipped=` を直接数えて確認）。
jar は 15:34 に再ビルド（15,780,723 bytes）。**配備は未実施**（§1 参照）。

### 2026-07-27 — 資源サーバ分離の作業書とオフライン検証（`ops/` 新設）

メインサーバを Velocity プロキシ + メイン + 資源サーバの 2 バックエンド構成へ移行するための
**作業書と、サーバを立てずに前提を潰す検証**を用意した。**配備はしていない**（ユーザーが
`ops/RUNBOOK.md` に沿って自分で実行する前提）。成果物はすべて `ops/` 配下。

**確定した構成**（データ同期は 3 層。新規に書いた Java の本番コードはゼロ）

| 層 | 中身 | 手段 |
|---|---|---|
| A | インベントリ / EC / 経験値 / 体力 / 効果 | HuskSync |
| B | プレイヤー PDC（図鑑・実績・称号・天井・採取トグル・Ars のマナとグリフ解放） | HuskSync の `persistent_data` |
| C | スキル Lv / ポイント / パーク（SQLite） | `plugins/TrinityForge` の**ディレクトリジャンクション** |

C がこの設計の肝。`plugins/TrinityForge` の中身は yml と SQLite とバックアップだけで
**サーバ固有の実行時状態が無い**こと、SQLite が既に WAL + `busy_timeout` で開かれていること、
`CachedProgressionRepository.evict()` が既に `PlayerQuitEvent` で呼ばれていることから、
NTFS ジャンクションで実体を共有すれば**進行データ共有と config パリティが同時に、
コード変更ゼロで成立する**。MariaDB 実装（1〜2 日規模）を回避できた。

**検出して修正した本体のバグ 1 件（重要）**

`SqliteProgressionRepository` のトランザクション 5 箇所（`unlockPerk` / `prestige` /
`saveProgressionTransition` / `saveAdminProgressionEdit` / `resetPlayer`）は全て
check-then-act だが、JDBC 既定の `setAutoCommit(false)` は `BEGIN DEFERRED` を発行する。
読み取りとして始まり最初の UPDATE で書き込みロックへ昇格するため、その間に別コネクションが
コミットしていると **`SQLITE_BUSY_SNAPSHOT` で失敗する。しかもこのエラーには
`busy_timeout` が効かない**（古いスナップショットは待っても直らないので SQLite が busy
ハンドラを呼ばない）。さらに `unlockPerk` は `SQLException` を握り潰して `false` を返すので、
**プレイヤーには「ポイント不足」と区別がつかない形でパーク解放が失われる**。

接続時に `transaction_mode=IMMEDIATE` を指定して解消した
（`SqliteProgressionRepository#immediateTransactionProperties`）。
**単一コネクション運用では挙動が変わらない**ので現行のシングルサーバへの影響はない。
2 つ目のコネクションが同じファイルを触った瞬間に初めて顕在化する種類のバグで、
資源サーバ分離はまさにその条件を作る。サーバを立てる前に潰せた。

**サーバなしで実証したこと**

| 検証 | 結果 |
|---|---|
| **EliteMobs 無しでモブのレベル推移と報酬テーブルが機能するか** | **機能する。** 89 種 × Lv0〜100 で全帯埋まる。`mob-overrides.yml` だけが EliteMobs 刻印付き個体にしか反応しないことも明示的にアサート。→ `ops/reports/resource-server-mob-simulation.md` |
| 2 プロセスから同じ進行 DB を触って壊れないか | **壊れない**（上記修正後）。子 JVM を 2 つ起動し、同一パーク 60 件を昇順/降順から奪い合わせて 30/30・合計ちょうど 60 件・SQL 例外 0 件。→ `ops/reports/shared-sqlite-concurrency.md` |
| PDC が HuskSync で同期できる型か | 全て primitive 型。`PlayerPdcPrimitiveTypeAuditTest` が将来の逸脱を検出する |
| ジャンクション先の誤削除が止まるか | 実際にジャンクションを作って実測。`ops/scripts/run-selftest.ps1` が 10/10 |

**シミュレーションで判明した配備上の要注意点**: Lv100 で **50 種のモブが最大体力 1024 を超える**
（最大は WARDEN の 482148）。新規サーバに `spigot.yml` の
`settings.attribute.maxHealth.max` をコピーし忘れると、**資源サーバだけモブが弱くなる**。
RUNBOOK の手順 7 に明記した。

**HuskSync のバージョン対応とビルド（完了）**

現行は Paper 1.21.11 だが、**対応する公式リリースが存在しない**。
HuskSync は essential の multi-version プリプロセッサで `bukkit/<版>/` 単位にソースを切り替える
構成で、**そのディレクトリ一覧がそのまま対応版の一覧**になる。

| 版 | `bukkit/` の中身 |
|---|---|
| 3.8.7 | `1.20.1` `1.21.1` `1.21.4` `1.21.5` `1.21.8` |
| 3.9.0 | **`26.1.2` のみ**（1.21.x を全削除・Java 25 前提） |
| master (4.0.0未リリース) | **`1.21.11`** `26.1.2` `26.2` |

**3.9.0 は「26.1.2 対応を追加した」のではなく「26.1.2 へ移行して 1.21.x を切った」版。**
新しい版に対応しても古い版へは戻れない構造なので、3.9.0 では 1.21.11 の jar は生成されない
（ユーザーから「3.9.0 が最新対応だから 1.21.11 も動きそう」との見立てがあったが、上記のとおり成り立たない）。

→ **master を `3dc619d5f641ee909004925dbbda2d507b7127c2`（2026-07-23）に固定してビルド済み。**

| 項目 | 値 |
|---|---|
| 成果物 | `tmp/husksync-dist/HuskSync-Bukkit-4.0.0-3dc619d+mc.1.21.11.jar`（3,247,291 バイト） |
| SHA-256 | `4E047339EFD25DD1DC776CF3E8D9F8AA007C54E35B77534367C8479274E82466` |
| コンパイル対象 | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`（解決を実測確認） |
| 記述子 | `version: 4.0.0-3dc619d` / `api-version: 1.21` / `folia-supported: true` / 1906 クラス |

**ルートの `javaVersion=25` に惑わされないこと。** それは 26.x と Fabric 向けで、
`bukkit/1.21.11/gradle.properties` は `java_version=21`。
**`:bukkit:1.21.11:shadowJar` だけを指定すれば JDK 21 でビルドできる**
（`./gradlew build` だと全バリアントを巻き込んで JDK 25 が要る）。

**jar は git に入れていない**（本リポジトリは public。`tmp/` は `.gitignore` 対象）。
未リリースブランチなので、上げ直すときは必ず新しいコミットハッシュを記録すること。

**その他の判明事項**

- `tools/config-editor/tool-config.json` が `external.enabled: true` / `bindHost: 0.0.0.0` /
  **パスワード空**。ただしこれは 2026-07-26 にユーザーが明示的に選んだ状態で、`server.js` の
  fail-safe を撤去した経緯がコメントに残っている。よって対策は「設定を戻す」ではなく
  **ファイアウォールで到達経路を塞ぐ**＋環境変数 `CONFIG_EDITOR_PASSWORD` での認証付与。
  → `ops/SECURITY.md` §0
- ネザーを一律 5000 にすると、**ポータル移動として意味があるのはネザー ±312 まで**
  （地上 ±2500 ÷ 8）。それより外のポータルは出口が全部ボーダー際へクランプされる。
  面積比で全体の約 1.5%。ネザーだけ 625 にする選択肢を計算表付きで RUNBOOK に載せた（ユーザー判断）
- 資源サーバの `pause-when-empty-seconds` は魅力的だが **Chunky の事前生成も止める**ので既定は無効
- 週次リセットの削除対象に `plugins/SetHome/homes.yml` を入れた（消えた地形の home 座標を残さない）。
  資源サーバの `max-homes` は 3 程度への引き下げを推奨

**成果物**

- ドキュメント: `ops/README.md` / `RUNBOOK.md` / `PLUGIN_MATRIX.md` / `SECURITY.md` /
  `PERFORMANCE.md` / `COST_AND_LICENSE.md`
- テンプレート: `ops/templates/` に velocity.toml / server.properties 差分 ×2 /
  paper-global.yml 差分 / husksync / sonar。HuskSync と Sonar は**生成された config へ差分適用**
  する方式にした（丸ごと差し替えるとバージョン差でキーが黙って既定値へ戻るため）
- スクリプト: `ops/scripts/` に RCON クライアント（外部バイナリ依存なし）/ 削除ガード /
  setup-junction / server-loop / sync-configs / reset-resource / restart-server / backup /
  run-selftest。**全て `-DryRun` で空撃ち済み**
- テスト: `com.trinityforge.ops.*` に 13 件追加

**自己テストが実バグを 2 件検出した**（いずれも修正済み）:
`Get-DirectorySizeMB` が空ディレクトリで `Measure-Object` の `Sum` 参照に失敗、
子プロセスの stderr が親の `ErrorActionPreference=Stop` で終了エラー化していた。

テストは TF **2374 件 / 失敗 0 / スキップ 2**、config-editor **651 / 651**（いずれも実走・実測）。
コミット = `188bdad`（本体修正＋検証テスト）/ `fd32b3d`（スクリプトとテンプレート）＋ドキュメント。

### 2026-07-27 — 14 要件バッチの差分レビューと指摘修正（改行正規化を含む）

前項の 14 要件バッチ（TF `ee374c2` / ArsPaper `e7028df`）を差分レビューし、確定した指摘を
軽微なものまで全件修正した。コミット = TF `3744ae8`（改行正規化）+ `9123116`（修正本体）/
ArsPaper `d3a3210`。いずれも push 済み。

**確定した実害 2 件**

- **AFK 解除がチャット経由だと非同期スレッドから `Player#playerListName` を呼んでいた。**
  `AsyncChatEvent`（非同期）→ `AfkService.touch` → `clearAfk` → `applyTabSuffix` の経路。
  Paper のこの API は async-safe でない。`applyTabSuffix` の先頭で `Bukkit.isPrimaryThread()`
  を見てホップする形にした。**呼び出し元にホップ責任を持たせない**のが要点で、そうしないと
  活動シグナルを1つ足すたびに同じ漏れが再発する。
- **AFK 判定が受動的な座標変化を「活動」に数えていた。** 水流に押される・ボート/トロッコ・
  落下は入力なしで毎tick座標が動くので、水流式・乗り物式の放置装置がそのまま素通りしていた。
  **視点回転(yaw/pitch)は入力でしか起きないので無条件に活動、座標変化は受動搬送中でないときだけ
  活動**、と非対称に扱う形へ変更（`AfkActivityListener#isPassivelyTransported`）。
  エリトラ滑空とクリエイティブ飛行は「空中だが入力由来」なので除外しない（除外すると飛行中に
  AFK 判定される）。**モブのノックバックで押され続ける形は残す** — 殴打の有無まで見ると
  この毎tick経路が重くなるため、殴られ続ける状況自体を別途潰すべきものと判断した。

**改行コードの churn（レビュー不能の原因、恒久対策）**

Windows の編集ツールがファイル全体を CRLF で書き戻すため、実質数行の変更が「全行が変わった」
差分としてコミットされていた（`TrinityForge.java` が 1477 追加 / 1426 削除 ← 実変更は 61 / 10、
`ConfigManager.java` が 575 / 566 ← 実変更は 9 / 0）。リポジトリ直下に `.gitattributes`
（`*.java` / `*.js` / `*.yml` に `text eol=lf`）を置き、index に残っていた 84 ファイルを正規化した。
効果は次のコミットで確認できる（`ConfigManager.java` の差分が **1 / 1** になった）。
`*.json` はリソースパックの生成物が大半なので対象外。

- **罠**: `git add --renormalize` をパス指定なしで使うと、worktree の内容をそのまま
  ステージするので**並行セッションが編集中のファイルの作業途中の状態まで自分のコミットに入る**
  （実際 18 ファイル巻き込みかけた）。手順は「renormalize → `git diff --cached
  --ignore-all-space --name-only` で改行以外の変更が出たファイルを全て `git restore --staged`
  → 残りをコミット」。コミット後に `git show HEAD --ignore-all-space --stat` が
  `.gitattributes` だけを出せば純粋な正規化コミットである。
- HEAD に CRLF で残っているのは `TrinityForge.java` と `FishingGimmickConfig.java` の 2 本だけ
  （正規化の時点で並行セッションが編集中だったため除外した）。次にそれらがコミットされる際に
  自動で LF になる。

**その他の修正**

- `tab-suffix` を AFK 中に false へ切り替えて reload すると `[AFK]` が張り付いたままになる問題。
  設定フラグではなく「**この機構が実際に付けた実績**」(`tabSuffixApplied`) で消すようにした。
- `GiveItemCommand` が解決順序（Ars レジストリ → TF カタログ）を `giveTo` と `buildOne` に
  二重に持っていた。`buildOne` へ一本化。**スタック不可の品は上限を 36 個**に分けた
  （指定数がそのまま `factory.stamp` の実行回数になり、2304 指定でメインスレッドが止まる）。
- **editor のアチーブメント図鑑トリガで、対象を増やしても閾値が 1 のままだと「どれか1つ登録で
  達成」に静かに劣化していた**（ヒント表示しかしていなかった）。閾値を対象数へ追随させる。
  判断は純関数 `autoCollectionThreshold` に切り出してテスト 8 件を追加。追随の条件を
  `scope=item/mob` かつ count 判定に限るのは、Java 側 `AchievementsConfig` の「threshold 省略時は
  targets の件数」既定と範囲をそろえるため（category/all は列挙数と候補数が一致しない）。
  一度手で閾値を触ったらそこで追随は止まる。
- ArsPaper の `RecipeBrowserFilter.compileGlob` をキャッシュ化（上限 256 件）し、壊れたパターンで
  例外が出る経路を塞いだ。**TF の `GlobMatcher` と実装が重複しているのは意図的**で、この GUI は
  TrinityForge が無くても動く必要があるため。片方だけ直すと同じ検索語で図鑑とレシピ一覧の結果が
  食い違うので、その旨を javadoc に明記した。
- 判定方式のセレクトを `listSelect` へ統一（raw 値表示だった）。`AfkConfig` の import 位置を移動。

**テスト（実走・実測）**: TF は変更領域 **81 件緑**（`--tests '*Afk*' '*GiveItem*' '*Disassembly*'
'*Collection*' '*Achievement*' '*PerkServiceLock*'`）、ArsPaper フォーク **BUILD SUCCESSFUL**、
config-editor `tf-rewards-forms` **30 件緑**（新規 8 件含む）。
全体を回すと当時 TF 2 件 / editor 18 件が失敗したが、**いずれも並行セッションの作業中コードが原因**
（TF = `com.trinityforge.ops` の SQLite 並行テストが gradle デーモン同時実行でロック競合、
editor = 18 件すべて `lib/schema.js` の `APPLIES_TO` 二重宣言によるロード失敗）。

**jar は再ビルドしていない。** 並行セッションが TF ソースを編集中で、いまビルドすると書きかけの
コードが混ざるため。配備前に改めてビルドすること。

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
