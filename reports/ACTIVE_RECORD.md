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

## 1. 現在の状態（2026-07-27 16:39 時点）

| 対象 | 状態 |
|---|---|
| TrinityForge テスト | **失敗 0 / スキップ 2**（実走・実測。スキップは既知の正当な 2 件のみ＝`OfflineMobImportRunner` / `NativeProgressionStabilizationContractsTest`。テスト結果 XML の `skipped=` を直接数えて確認済み） |
| config-editor テスト | **758 / 758 pass・fail 0**（実走・実測、`---EXIT 0---`。2026-07-28 の「数値のギミックyml集約」バッチ後。751 + 新規 7） |
| **配備（2026-07-28 のバッチ）** | **配備完了**（2026-07-28 03:5x、`tmp\deploy-v1.cmd` をユーザーが実行、全 copy 成功をログで実測）。jar 2 本（TF 03:11 / 15,860,881 bytes・ArsPaper 01:31 / 965,695 bytes）を **3 バックエンド全部**へ、yml 13 本を Main へ（Resource/Dev はジャンクション共有なので 1 回）。旧版は `backups/deploy-20260728v1/`。**`tmp\deploy-k8.cmd` / `deploy-k9.cmd` は消えたパス `PaperServer\TrinityForge` を指しており使用不可**（破棄してよい）。**フル再起動が必須**（jar 差し替え＋コマンドツリーは起動時登録なので `/tf dungeon` のサジェスト変更が reload では載らない）。**`smithing.yml` と `smithing-gimmick.yml` は必ず同時に配備すること**（片方だけ古いと tier が floor 解決で最大 tier へ無言で化ける。起動ログに tier 不一致 WARNING が出たら配備漏れ）。`digging.yml` と `digging-gimmick.yml` も同じ関係 |
| **配備（2026-07-27 16:39 のバッチ）** | **ビルド済み・未配備**。**`D:/` への書き込みが権限ゲートに拒否されるのでユーザー実行が必要**: <br>```cmd /c tmp\run-deploy-e.cmd```<br>これは `tmp\run-deploy-k5b.cmd`（15:35 分、**実行済み**）の**続き**。再ビルドした jar 2 本と、その後に変わった `stats/lore.yml` / `skilltree/woodcutting.yml` / `skilltree/mining.yml` / `combat/base-stats.yml` / `combat/stat-caps.yml` を `backups/deploy-20260727b/` へ退避してから上書きする。k5b で配備済みの `skilltree/farming.yml` / `ars_magic.yml` / gimmick 5 本は触らない。**jar を差し替えるのでフル再起動が要る**（reload では不可）。**この配備に config-editor の保存ミラー（下記「配備手段」）を使ってはいけない** — `lore.yml` の説明コメントが全部消えるため（§5 / K-3） |
| ArsPaper フォーク テスト | **全緑**（`BUILD SUCCESSFUL`、`test --offline` で実走） |
| `TrinityForge-0.1.0-SNAPSHOT-all.jar` | 2026-07-27 **20:52** に `42bf57e` で再ビルド（15,829,085 bytes、`TrinityForge/build/release/TrinityForge-all.jar`）→ **未配備**。16:39 の `532bcef` 版を含む上位互換 |
| `ArsPaper-1.0.0.jar` | 2026-07-27 **16:39** に `f034b94` で再ビルド（965,072 bytes、グロブ修正 `d3a3210` を含む）→ **未配備** |
| `EliteMobs.jar`（全同梱 uberjar）| 2026-07-26 16:20 ビルド → **配備済み** |
| 実サーバへの配備 | **完了**（yml 42 本＋jar 3 本）。バックアップ = `plugins/.deploy-backups/20260727_114327/`。W-6 / W-7 で変更した `skilltree/light_armor.yml` / `heavy_armor.yml` は **12:37 に config-editor 経由で再配備済み**（下記「配備手段」参照） |
| 配備手段 | **config-editor の保存が `deployPaths` へ自動ミラーする**（`server.js#mirrorToDeploy`）。`D:/` への直接書き込みが権限で止まる場合でも、editor の `PUT /api/config/:id` で保存すれば SoT と配備先の両方が同時に更新される。**ただし保存は yml を再シリアライズするので本文コメントが消える**（→ §5） |
| サーバ稼働 | **全台停止中（2026-07-28 20:0x 実測）**。java.exe は Gradle デーモン 2 本のみ・25565/25566/25567/25577/25580 いずれも LISTEN なし。**19:20 ビルドの TF / ArsPaper jar は 3 台とも配備済み**（→ §7 の 19:0x エントリ）。次に `launch\start-all.cmd` で起動すれば新版が載る。（前回起動は 04:28、そのときは 3 台とも `Done`・HuskSync も enable 成功） |
| ~~**最優先の配備（2026-07-28 実サーバ報告 ①）** `skilltree/smithing.yml` の `recipe:` ゲート 41 件が未配備~~ | **解決（2026-07-28 04:0x 確認）**。配備先 `Main_Server/plugins/TrinityForge/skilltree/smithing.yml` を grep したところ `recipe:` ゲートは 0 件（残っているのは撤去を説明するコメントのみ）。**なお「作業台が作れない」はこのゲートとは無関係だった**（真因は ArsPaper の `plank_scrap` レシピ、→ §7 の 2026-07-28 エントリ） |
| **HuskSync が起動できていない（2026-07-28 03:40 のログで真因確定）** | **真因は MariaDB の認証失敗**: `1045-28000: Access denied for user 'husksync'@'localhost' (using password: YES)` → `Failed to initialize MariaDB database connection`。**Redis は原因ではない**（同じ起動で LuckPerms が `storage provider [MARIADB]` と `messaging service [REDIS]` の両方に接続成功しており、MariaDB も Garnet も生きている）。`RedisManager.terminate()` の NPE は初期化途中で落ちたときの shutdown 経路の副作用にすぎない（RUNBOOK §トラブルシュートにも「無視してよい」とある）。**以前の記録の「Redis 未接続が原因」は誤り**。`husksync` アカウントが未作成か、`plugins/HuskSync/config.yml` の password が GRANT 時の値と違うかのどちらか。**サーバ起動は止まらないので気づきにくい**（→ §5）。**`preflight.ps1` はこれを検出できない** — TCP 到達性と「生成時の既定値のままでないか」しか見ておらず、実際に認証を試さないため素通りする |
| ~~**最優先の配備（2026-07-28 04:14 ビルド）** クラフト複製の真因修正と「作業台が作れない」の修正~~ | **配備完了（2026-07-28 04:26、`tmp\deploy-20260728-craftfix.cmd`）**。TF / ArsPaper の jar を 3 バックエンドへ、計 6 本すべて **MD5 一致で検証済み**。04:28 に `launch\start-all.cmd` で起動し全台 `Done`。旧版は `Main_Server\plugins\.deploy-backups\20260728_craftfix\`。**実ゲームでの動作確認だけ未実施**（リザルトから取っても素材が減ること／板材 2×2 で作業台が出ること） |

**配備先の構成が変わった（2026-07-28 実測）。旧 `D:/game/minecraft/PaperServer/TrinityForge/` は存在しない。**
現在は `D:/game/minecraft/PaperServer/Velocity_for_TF/{Main_Server, Resource_Server, Dev_Server}/` の
3 バックエンド構成。**`Resource_Server` と `Dev_Server` の `plugins\TrinityForge` は
`Main_Server\plugins\TrinityForge` への NTFS ジャンクション**なので、TF の yml は Main へ 1 回
コピーすれば 3 台に反映される（3 回コピーしても同じ実体を上書きするだけ）。**jar は 3 台とも実体
ファイルなので 3 回コピーが要る。`plugins\ArsPaper` も 3 台それぞれ実体ディレクトリ**なので
`unlock-gate.yml` は 3 回。配備スクリプトの現行版は `tmp\deploy-v1.cmd`。

`tools/config-editor/tool-config.json` の `deployPaths` は `Dev_Server` を指している。TF 側は
ジャンクション経由で 3 台に効くが、**`arspaper` は Dev_Server の実体ディレクトリなので editor 保存が
Main / Resource に届かない**（ArsPaper の yml を editor で編集したら 3 台へ手動コピーが要る）。

`combat/mob-profiles.yml` は保護対象として除外した（配備先 268KB の
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
| ~~J-9~~ | ~~**農業「ゴミ食II」を上位ノードとして成立させるか**~~ | **解決済み（2026-07-27、実コードで確認）**。懸念そのものが既に実装で解消されていた。満腹度側は `junkfood-inversion` とは**別の effect** `feature:junk-food-restore-boost`（LEVEL, `FeatureEffectRegistry.java:70`）が担い、`A-alpha-2` に `value: 30` で配置されている。`FoodBonusListener`（javadoc L26-31）は同ノード保持時、**ゴミ食には `food_restore_bonus` にノードの value を上乗せし、非ゴミ食には `food_restore_bonus` そのものを適用しない**＝草案どおり「ゴミ以外の満腹度回復量を戻す」挙動。`junkfood-inversion` の `value: 150` が 1.5 倍にするのは隠し満腹度（saturation）側だけで、そちらも `junk-saturation-bonus` / `non-junk-saturation-penalty` の 2 パラメータに分かれている（`FoodGimmickConfig.java:146-151`）。以下は解決前の記述: 段階化（W-5 ①）は倍率 1 個で実装したため、上位ノードの `value: 150` は**ゴミ食の恩恵と非ゴミ食のペナルティを両方 1.5 倍**にする。「ゴミ食専門家になるほど普通の飯が体に合わなくなる」という筋は通るが、**上位ノードを取ると普通の食事が今より不利になる**ので、スキルとしては取得を躊躇させる。元の草案テキストは「ゴミ以外の満腹度回復量を**戻す**」＝**欠点が消える**方向で、実装と正反対だった（2026-07-27 に発見、テキスト側を実装に合わせて修正済み）。草案の意図を採るなら**恩恵側とペナルティ側で別パラメータが要る**（`junkfood-inversion` を 2 値化）。現状は「両方 1.5 倍」で出荷される |
| ~~J-8~~ | ~~**ArsPaper フォークの push 先**~~ | **解決（2026-07-27）**。ユーザー判断により**3 リポジトリすべて PUBLIC**。`Klee319/ArsPaper` の `feat/trinityforge-fork` を再 push し、`trinityforge` と `EliteMobs-trinityforge` も public 化した |
| ~~J-7~~ | ~~**K-5（ステータスのトリガー/発動制限の明示）の修正プラン、着手前の 2 点**~~ | **解決（2026-07-27、ユーザー判断）**。①詳細の出し先 = **`/stats detail <key>` サブコマンド**（統合版で hover が効かない・lore の行数制限に当たらないため）。②着手範囲 = **120 キーを一括で埋める**（私の推奨は戦闘系 20 キーでの先行検証だったが、ユーザーが一括を選択。途中状態を残さない方を優先）。プラン本体は §4 の K-5 直下 |

---

## 3. 残タスク — 手を動かせば終わるもの

| # | 内容 | 前提 |
|---|---|---|
| ~~W-1~~ | ~~**実機スモークテスト**~~ **ユーザーにより完了と報告（2026-07-27）**。以下は元の記述: `dropsVanillaLoot: true` のダンジョンボスを 1 体倒し、バニラEXPオーブが意図した量で出るか確認 | **配備後にしかできない**。fork 側半分は TF のユニットテストで触れないため実機確認が必須。対象は実測 159 体（config 408 中 `false` 249 / `true` 32 / 未指定 127） |
| ~~W-2~~ | ~~**PvP の実プレイ調整**~~ **ユーザーにより完了と報告（2026-07-27）**。以下は元の記述 | `pvp.damage-multiplier: 0.5` / `max-damage-percent-of-max-health: 0.15` は机上値。「最低 7 発で倒れる」の手触りは実測前提 |
| ~~W-3~~ | ~~採取系見直し **W2（パラメータ化）/ W3（UX）**~~ | **取り下げ（2026-07-27、ユーザー指示）**。「一旦残タスクリストから削除」。設計書が実在せず設計の書き起こしから必要な規模だったため。W1 基盤（`com.trinityforge.active`）は実装済みで、そこまでで止める。再開したくなったらこの行を復活させる |
| ~~W-4~~ | ~~ars_magic のマナ系 4 キーを実効化~~ | **記録が stale だった（2026-07-27 に実コードで確認）。4 キーとも既に実効化済み。** `hit-mana-recovery` / `damage-mana-recovery` = `ArmorManaListener.java:279-282` が `TrinityForgeBridge.tfNonItemStatTotal(player, ...)` で非装備分（パーク/役職/永続バフ/base-stats）を加算している。`mana-cost-reduction-flat` / `-percent` = `SpellCaster.java:294-302` が同じく非アイテム分を触媒由来分と加算合成し、`clampReductionFraction` で [0,0.95] にクランプしている（いずれも 2026-07-26 実装）。「フォークがメインハンドしか読まない」という記述はその実装以前のもの |
| ~~W-5~~ | ~~スキルツリー草案が「今回は対象外」と明記した 2 件~~ | ①**農業のゴミ食の段階化** = **解決（2026-07-27、テスト実走待ち）**。`junkfood-inversion` を `NONE`→`LEVEL`(％) 化し、`FoodGimmickListener` を `isActive()` から `valueMax()` へ切替、`stats/food-gimmick.yml` のグローバル値に `value/100` を掛ける形にした。農業 `A-alpha-1` に `value: 100`（＝現行値と厳密一致）、`A-alpha-2` に `value: 150` を配置。**`LEVEL` は value 欠落時に現行挙動へフォールバックせず、パース時にエントリごと捨てられる**（`SkillTreeConfig.java:368-372` で warning）ため、`value` の書き忘れは `AllSkillTreesLoadTest`（warning ゼロを要求）がビルドで落とす。この「ビルドが守る」経路自体をテストで直接証明した。②②~~切削 E-β のオフハンド+スニーク破壊~~ = **取り下げ（2026-07-27、ユーザー判断）**。機構自体が未実装で、1 ノードのために新しい自動化機構を作る価値は無いという判断。スキルツリー側もノード未配置のまま |
| ~~W-13~~ | ~~**素材タブのカテゴリバーがスクロール時に本体から離れる**~~ | **解決済み（2026-07-27、実コードで確認）**。`test/split-view-sticky.test.js` に「カタログとアイテムステータスのカテゴリバーだけはスクロール追従しない」が**期待値として明文化**されており、実装もそれに一致している |
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

> **進捗（2026-07-27 更新）: 段階 1〜4 すべて完了。この節は履歴として残す。**
>
> - **段階 3 完了**: `/tf stats detail <key>` が実在する（`StatsCommand.java:95` の `detailNode()`。
>   Brigadier がリテラルを引数より優先解決する件のコメントも L59-60 にある）。
> - **段階 4 完了**: 未宣言キーは `LoreConfigDeclarationTest.UNDECLARED_ALLOW_LIST` に
>   **理由コメント付きで固定**され、`undeclaredKeysMatchAllowList` が許可リストとの**厳密一致**を要求する
>   （＝新しいキーを未宣言のまま足すとビルドが落ちるラチェット）。「31 キーが未宣言のまま放置」という
>   以前の記述は誤り。`stats/lore.yml` 側の `trigger:`/`limits:` は 121 箇所。
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
- **Gradle が `* What went wrong:` の下に Java のバージョン番号だけを吐いて落ちたら、JDK の取り違え。**
  2026-07-27 に Java **25.0.4** が自動更新で PATH の既定 `java` を奪い、Gradle 8.10.2（TF）と
  8.5（ArsPaper フォーク）が**両方とも `25.0.4` の一行だけを残して起動不能**になった。
  スタックトレースも「非対応の Java」という文言も出ないので原因が読み取れない。
  JDK 21 は `C:\Program Files\Java\jdk-21` に残っているので、
  `-Dorg.gradle.java.home="C:\Program Files\Java\jdk-21"` を渡して回避する
  （マシンの `JAVA_HOME` は空。環境変数側は触らない）。同じ症状は Java が更新されるたびに再発する。
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

### 2026-07-28 23:xx — モブ定義にないモブは EXP なし／切削アイコン（実サーバ報告 9 件バッチの一部）

検証は実走・実測: TF `2684 tests / fail 0 / error 0`（`gradlew test --offline`、結果 XML の
`tests/failures/errors` を直接集計）。**jar は未再ビルド・未配備**。

**① モブ定義にないモブは経験値なし（ユーザー要望）**
これまでは「表に行が無いモブ＝倍率 1.0（満額）」というフォールバックだった。3 経路すべてを 0.0 既定に反転。
- `SkillExpConfig#combatKillExp` / `#arsMagicKillExp` — 新キー
  `combat.kill-exp.unlisted-entity-multiplier` / `ars-magic.kill-exp.unlisted-entity-multiplier`（既定 `0.0`）。
  **EntityType が取れない（null/空）ケースも「定義に無い」と同じ扱い**にした。種別不明のまま満額を出すのは
  この設定の目的と真逆のため。`1.0` を書けば旧挙動に戻せる（`stats/skill-exp.yml` に日本語コメント付きで明記）。
- `NativeSkillExperienceListener#grantArmorHit` — 防具被弾 EXP も
  `entity_exp_multipliers.<TYPE>` に行が無ければ 0 で早期 return。
- **2026-07-26 の per-mob EXP ramp では「未設定は 0 EXP でなく無干渉(=1.0)」と逆の判断をしていた**
  （メモリ `mob-per-mob-exp-ramp-2026-07-26`）。運用してみると装飾用・イベント用・外部プラグイン由来の
  モブまで満額 EXP を出すため、ユーザー要望で既定を反転した。**この記録が新しい**。
- 実挙動の注意: 出荷 `skill-exp.yml` の `entity-type-multipliers` は敵性モブ中心で、
  **CHICKEN/COW/PIG/SHEEP など受動モブは行が無い＝討伐 EXP 0 になる**。意図どおりだが、
  受動モブから EXP を出したくなったら行を足すこと。

**② 切削（DIGGING）のスキル選択アイコンが伐採と同じだった**
`SkillTreeGuiVisuals.SKILL_MODELS` が DIGGING に WOODCUTTING と同じ `landscaping` モデルを
固定していたため、`skilltree/digging.yml` の `icon:` が無視されていた。DIGGING をこの表から外し、
config のアイコン（鉄のシャベル＝ユーザー指定）を出すようにした。**専用モデルが無いスキルはこの表に
載せない**のが正しい（載せると config 側のアイコン設定が無言で死ぬ）。

**テストの更新（旧契約を書いていたもの 8 件）**
`NativeSkillExperienceListenerArmorExpTest`（攻撃者 mock の `getType()` 未スタブ＝新既定 0.0 で全滅）、
`SkillExpConfigTest`（未知モブ 1.0 フォールバックを assert）、`CombatListenerNoSkillExpMobsTest`
（victim が CHICKEN で「no-skill-exp-mobs に無いから EXP が入る」を assert）、`SkillTreeGuiVisualsTest`
（DIGGING = landscaping を assert）。いずれも新契約へ更新し、**新契約側の回帰テストを 3 件追加**した。

### 2026-07-28 21:xx — editor UI 8 件＋採掘運の 6 倍ドロップ（実サーバ報告 10 件バッチ）

検証は実走・実測: TF `2681 tests / fail 0`（`gradlew test --offline`）、config-editor `780 / 780 pass`、
UI は port 8794 の検証用インスタンスで DOM 実測（本番 8000 は触っていない）。**jar は未再ビルド・未配備**。

**① 採掘運でドロップが約 6 倍になっていた（最重要・ゲーム挙動のバグ）**
真因は 2 つが重なっていた。
- `PercentStatNormalize.RATE_KEYS` に `mining-fortune` が **登録漏れ**（`fishing-luck` だけ入っていた）。
  skilltree は effect-text「ドロップ増加+15%」に合わせて `mining-fortune: 15` とパーセントポイントで
  書いているので、矯正されないまま **15（＝1500%）** が `MiningFortuneListener` に渡っていた。
- そのうえで `expectedExtraRate` が内部で **`× 0.30` という未文書の係数**を掛けていたため、
  ノードの表示値と実効値の対応が誰にも追えない状態だった。

修正は「表示どおりに効く」方向へ寄せた: `RATE_KEYS` に `mining-fortune` を追加し、`× 0.30` を撤去。
レベル由来分のバランスは変えないよう `fortune-per-level` を同じ比率で下げた（既定 `0.02 → 0.006`、
出荷 yml `0.01 → 0.003`）。**Lv100 の寄与は前後とも +30% で不変**。
`lore.yml` の `mining-fortune` は `FLAT → PERCENT`（`fishing-luck` は元から PERCENT で、こちらだけ
取り残されていた）。editor 側のミラー（`forms.js` の RATE_KEYS / `materials.js` の FORMAT）も同時更新。

**② カスタムアイテムが素材セレクトに出ない（醸造ほか）**
- Java: `ItemExpLookup`（新規）を追加し、EXP テーブルを **`custom:<id>` → バニラ Material 名**の順で
  引くようにした（`NativeSkillExperienceListener` のドロップ／採取／釣り／醸造の 4 経路）。
  カスタム行が未設定なら従来どおりバニラ行に落ちるので既存設定の挙動は不変。
  PDC 読み取りは `Bukkit.getItemFactory()` を触るため、サーバ非起動の単体テスト文脈では例外になる。
  `customIdOf()` で握り潰して「カスタムIDが分からない＝バニラ名で引く」へ縮退させている。
- editor: 候補を積んでいたのは `fetchCatalogCandidatesWithMaterials()` を明示的に呼ぶ一部画面だけで、
  醸造ギミック等を直接開くと `window.CUSTOM_ITEM_CANDIDATES` が空だった。**画面種別ごとに足すと必ず
  漏れる**ので、エディタ構築の共通入口（`buildEditorForLoadedConfig`）で一度だけ読むようにした。

**③ セレクトが絞り込み入力にならない** — `window.listSelect` にフィルタ行を追加（日本語名／ID の
どちらでも引ける・候補 8 件超で表示・最大 200 件描画＋「他 N 件」）。

**④〜⑧ ID 表記の日本語化**（実測で残 ID ゼロを確認）
- エンチャント EXP 設定・醸造結果 EXP 表・繁殖 EXP 表・考古学ブラシ EXP 表
- モブ定義カードの折り畳み時表示名（`ブレイズ` `洞窟グモ` …）
- バニラレシピ削除／アイテム自体を削除（`ネザライトの斧 (鍛冶台)` `全アイテム:修繕` まで解決）
- PotionType の日本語辞書は `labels.js` に一本化し、醸造ギミックのベースセレクトもそこを引く

旧 ID のまま出荷 yml に残っているキー（`sweeping` / `MUSHROOM_COW` / `JUMP`）は、TF 側リゾルバが
今もエイリアス解決するので **候補には出さず表示辞書だけ別名を持たせた**（候補に並べると新旧が二重に出る）。

**⑨ 使用制限スイッチのカードが隙間なく繋がっていた** — `.afk-section` を `flex` + `gap:12px` に。

**未実施**: この 8 件は **jar 未ビルド・未配備**。ゲーム挙動が変わるのは ①（採掘運）と ②（Java 側）で、
反映にはビルド＋配備＋再起動が必要。

### 2026-07-28 19:0x — 要望 6 件（グリフ素材GUI／儀式プレビュー／ロールUI／日光炎上／序盤火力）

ユーザー要望 6 件をまとめて実装。検証は実走・実測: TF `BUILD SUCCESSFUL`・`fail 0 / skip 2`（既知の
2 件のみ）、config-editor `780 / 780 pass`、ArsPaper フォーク `BUILD SUCCESSFUL`。
jar は TF / ArsPaper とも 19:20 に再ビルド済み → **配備完了（2026-07-28 20:0x）**。

**配備（`tmp\deploy-20260728-2000.cmd`、全 copy 成功・MD5 一致で実測）**
- TF jar `TrinityForge-0.1.0-SNAPSHOT-all.jar`（15,916,311 bytes / MD5 `2f9c5cae932e0f8f0be062a81211eb44`）と
  ArsPaper jar `ArsPaper-1.0.0.jar`（979,266 bytes / MD5 `cbfe0f36b9eb2181aa436f0ccff5c933`）を
  **Main / Resource / Dev の 3 バックエンド全部**へ。6 本すべて MD5 一致を確認。
- yml は `combat/damage.yml` と `progression/role-buffs.yml` の 2 本を **Main へ 1 回だけ**
  （Resource/Dev の `plugins\TrinityForge` はジャンクション共有。3 台から読んで同一 MD5 を実測）。
  配備前に repo↔配備先を diff し、**差分が今回の追記だけ**であることを確認してから上書きした。
- **EliteMobs は更新なしのため触っていない** — ソースは git clean、`clean shadowJar --offline` が
  `shadowJar UP-TO-DATE`、`jar tf` のエントリ一覧がローカル/配備先とも 4174 件で `diff` 空。
- 旧版のバックアップは `Main_Server\plugins\.deploy-backups\20260728_2000\`（jar 6 本 + yml 2 本）。
- **配備時点でサーバは全台停止していた**（java.exe は Gradle デーモン 2 本のみ・25565/25566/25567/25577/25580
  いずれも LISTEN なし）ため、稼働中 jar 差し替えの `NoClassDefFoundError` は発生していない。
  **起動は未実施** — `launch\start-all.cmd` で起動すること（コマンドツリーは起動時登録なので
  `/tf glyphs` は reload では生えない）。実ゲームでの動作確認も未実施。

- **①`/tf glyphs` 新設（グリフ解放素材の閲覧GUI）** — ArsPaper 側に `GlyphBrowserGui` を新設し、
  TF 側は `ArsGlyphBrowserBridge` + `GlyphsCommand` でリフレクション委譲（`/tf recipes` と同じ形）。
  一覧（種別→ティア順・未解放/解放済みの絞り込み）→ クリックで詳細（**解放素材を実アイテムで並べ、
  各素材に「必要 N 個 / 所持 M 個」**、必要経験値レベルと現在レベル）。
  **読み取り専用**にしてある — 解放は従来どおり筆記台。ここから解放できるようにすると、演出と
  TOCTOU 再検証（アニメーション後の XP 再検査・素材返還）を持つ筆記台の手順を二重に持つことになる。
  リフレクションの実体は `ArsGuiBridgeSupport` へ切り出し、レシピ側ブリッジもそこへ寄せた（重複解消）。
- **②儀式レシピの詳細画面（`RecipeBrowserGui`）** — 以前は一覧で儀式をクリックしても
  **何も起きなかった**（`if (clicked != null && !clicked.isRitual)` で弾いていた）ため、素材は lore の
  名前だけで実アイテムを確認できなかった。中央=コア・周囲 8 マス=ペデスタル素材（同一素材は集約して
  スタック数で表現）を実アイテムで並べる詳細画面を追加。素材⇔レシピの相互ジャンプも作業台と同じに揃え、
  「生産レシピが 1 件なら直接その詳細へ」の分岐からも儀式の除外を外した。
  **スロット→素材トークンの対応は描画時に `detailSlotTokens` へ記録し、クリック判定はそれだけを見る**
  ように変えた（描画とクリック判定でスロット計算を二重に持つと、配置ルールの違う画面を足した時点で
  必ずズレるため）。
- **③`/tf role` がロールIDを 1 行返すだけだったのを、ロール名 + **効果の内訳**表示に変更** —
  ステ名と数値書式は `stats/lore.yml`（`LoreConfig#displayTable`）を正にしたので `/tf stats` と語彙が
  ズレない。共通ロジックは `progression/RoleDescriptions`（チャットとGUIの両方が同じ説明文を使う）。
- **④`/tf role set`（引数なし）でロール選択GUI** — `progression/RoleSelectGui`。アイテム 1 個 = 1 ロールで
  表示名に職業名、lore に効果。引数版 `set <combat> [support]` と**同じゲート**を通すため
  `progression/RoleChangeService` に可否判定（`allow-command` / 近くに敵モブ）と適用を集約した。
  `progression/role-buffs.yml` に表示専用の `icon:` / `description:` を追加（未指定・不正なら既定アイコン）。
  config-editor にも両欄を追加。
- **⑤日光炎上ダメージ = 最大HPの10%（`combat/damage.yml` の `sunlight-burn`）** — バニラの炎上は
  1 発 1.0 固定で、TF のモブ最大HP（Lv0 のゾンビでも 400）に対して無意味だった＝「朝になっても
  敵が炎上で死なない」。`SunlightBurnListener` が `FIRE_TICK` を最大HP割合へ置き換える（既定 0.10 →
  約 10 秒で焼き切れる）。**バニラ値の方が大きい場合はバニラのまま**（上げる方向にだけ働く）。
  **対象 EntityType を `sunlight-burn.mobs` で絞ってあるのが肝** — `FIRE_TICK` は「日光炎上」と
  「火属性エンチャントでの着火」を区別できないので、全モブ対象にすると野外・昼間に着火しただけで
  ボスが毎秒 10% ずつ溶ける（火属性が最強の攻撃手段に化ける）。適用条件は通常世界/昼/晴れ/空が
  見えている（バニラの焼却条件と同じ）。
- **⑥序盤（〜Lv10）のモブ火力を緩和（`combat/damage.yml` の `early-level-attack`）** —
  `mob-types.yml` の attack-power 指数カーブ（base × 1.033^Lv）**自体は触らない後掛け倍率**として
  実装（`EarlyLevelAttackSoftening`、`SymmetricCombatService` のモブ→プレイヤー物理/魔法の 2 経路に適用）。
  base を直接下げると全レベル帯が下がり、「同帯装備で約 10 発耐える」中盤以降の校正がやり直しになる。
  既定は Lv0 で 0.7 倍・Lv5 で 0.85 倍・**Lv10 以上は等倍**（従来の校正値は一切動かない）。
  EliteMobs が最終ダメージを直接渡してくる経路（`physical/magicalFinalDamageFlat`）には掛からない。

### 2026-07-28 04:1x — クラフト複製の**真因**修正 ／「作業台が作れない」の真因判明

ユーザー報告 3 件（①リザルトの品を取ろうとするとちらつく ②ドラッグ＆ドロップで無限に回収できる
（カスタムアイテムで確認）③なぜか作業台がクラフトできない）。
検証は実走・実測: TF `tests 2613 / fail 0 / skip 2`（既知の 2 件のみ）、ArsPaper フォーク
`BUILD SUCCESSFUL`・`tests 54 / fail 0`。jar は TF / ArsPaper とも 04:14 に再ビルド。

- **①②は同一の真因。前回（`9293f6c`）の「ドラッグが原因」という診断は誤りだった。**
  真因は `CraftQualityListener#onCraft` の `player.setItemOnCursor(stamped)`。
  CraftBukkit の `handleContainerClick` は**イベント発火 → バニラの
  `AbstractContainerMenu.clicked(...)`** の順で走るので、`CraftItemEvent` ハンドラ内でカーソルを
  書き換えると、バニラは「カーソルが空 → 結果枠を取る」ではなく
  **「カーソルに同じ品がある → マージする」**経路に入る:
  ```java
  } else if (slot.mayPlace(carried)) {        // 結果枠(ResultSlot)は常に false
  } else if (isSameItemSameComponents(slotItem, carried)) {
      slot.tryRemove(carried.getCount(), carried.getMaxStackSize() - carried.getCount(), player)
          .ifPresent(taken -> { carried.grow(...); slot.onTake(player, taken); });
  }
  ```
  装備・道具は**最大スタック 1** なので取得上限が `1 - 1 = 0`、`tryRemove` は空 Optional を返し
  **`ResultSlot#onTake` が一度も呼ばれない = 素材が一切消費されない**。それでいてプレイヤーの手には
  リスナーが載せた完成品が残るので、盤面も結果枠もそのままで**いくらでも増える**。
  クライアントは「素材が減って結果を取った」と予測して描画しているため、直後のサーバ同期で盤面が
  巻き戻る — これが**「ちらつき」の正体**でもある。
  （Paper 1.21.11 の `AbstractContainerMenu` / `Slot` / `ServerGamePacketListenerImpl` を
  `javap -c` して実際の分岐順とバイトコードで確認済み。推測ではない。）
  → 結果枠だけを差し替える（`setCurrentItem` / `setResult`）ように修正。バニラが正規の
  「カーソルが空 → `tryRemove(count, MAX_VALUE)` → `onTake`（素材消費）→ カーソルへ」を実行する。
- **前回入れたドラッグ・ヒューリスティック（カーソル == 結果枠なら落とす）は撤去した。**
  バニラの quick-craft は**カーソルの中身をスロットへ配るだけ**で、スロットから取り出す処理を
  一切持たない。かつ配布先の条件は `slot.mayPlace(carried)` で、結果枠はこれが常に false。
  **ドラッグ経路では原理的に複製できない**。一方このヒューリスティックは「作ったばかりの品を
  手に持ったまま盤面でドラッグする」という普通の操作を必ず巻き込み（直後は カーソル == 結果枠 が
  成立する）、キャンセル＋`updateInventory()` で**ちらつきを自分で生んでいた**。
  置き先に結果枠を含むドラッグを弾く元のループだけ残した。
- **③「作業台がクラフトできない」= ArsPaper の `plank_scrap` がバニラの作業台レシピを潰していた。**
  `materials.yml` の `plank_scrap`（`base_material: OAK_PLANKS`）は「`custom:plank_scrap` を 2×2 →
  板材 1 枚」という戻しレシピを持つ。**`custom:` 素材は `RecipeChoice.MaterialChoice`（材質のみ照合）
  として登録される**ので、Bukkit から見るとこれは「板材 2×2」であり
  **`minecraft:crafting_table` と完全に重なる**。Bukkit は一致レシピを 1 つしか返さないため、
  そこでフォークレシピが選ばれると**バニラの作業台レシピは選択肢ごと消える**。そのあと
  `CustomIngredientCraftGuardListener` の per-slot 検証が「custom id が違う」と正しく弾き、
  兄弟にも合わないので `setResult(null)` → **結果枠が空のまま = 作業台が作れない**。
  ログは `Level.FINE` なので何も出ず、config にもゲートが無いため原因に辿り着けなかった
  （前回「単独では未説明のまま」と書いた項目の答え）。
  → **フォークレシピを弾いた盤面にカスタムアイテムが 1 つも無い場合に限り**、フォーク前進レシピを
  除外して `Bukkit.recipeIterator()` を再照合し、本来選ばれるはずだったレシピの結果を戻す
  （`CustomIngredientCraftGuardListener#shadowedVanillaResult`）。カスタムアイテムが混ざる盤面は
  従来どおりクラフト不可 — 圧縮ブロックの tier 誤爆（over-match）を再び開けないため、この非対称は意図的。
- **同じ穴が TF 側 `CatalogWorkbenchListener` にも開いていたので同時に塞いだ。**
  TF も `CatalogRecipeRegistrar` で `custom:`/`list:` を MaterialChoice 登録しているため原理は同一
  （例: `custom:blaze_rod_2x` 1 個の shapeless カタログレシピがあれば `minecraft:blaze_powder` が死ぬ）。
  `shadowedVanillaResult` を同じ方針で追加（回帰テスト 5 本）。
  **これは「今どのバニラレシピが死んでいるか」を数え上げずに塞げる形にしてある** — カタログ/フォークの
  yml を編集するたびに新しい衝突が生まれうるので、個別対処ではなく経路ごと塞ぐのが正しい。
- **配備（04:26〜04:28）**: `tmp\deploy-20260728-craftfix.cmd`（**jar 専用**。他セッションが yml を編集中なので
  yml には一切触らない）。TF / ArsPaper を 3 バックエンドへ、計 6 本を MD5 一致で検証。
  `launch\start-all.cmd` で起動し 3 台とも `Done`、TF/ArsPaper 起因のエラー 0。
  - **`launch\start-all.cmd` は非対話シェルから実行すると待機がスキップされる**。中の Windows
    `timeout` が `ERROR: Input redirection is not supported` で即抜けるため、
    「main を 60 秒待ってから resource/dev」という起動順の間隔が効かず**3 台がほぼ同時に上がる**。
    今回は既存 DB なので実害なし（スキーマ移行は初回のみ）だが、**初回起動でこれをやると
    共有 SQLite の移行が同時実行される**。初回は必ず対話コンソールから実行すること。
  - 停止したつもりでも `server-loop.cmd` の再起動ループで上がってくることがある
    （今回も一度 04:24 に起動しかけた）。**配備前に必ず java プロセスの実在を確認**すること。
- 副産物: フォークの shape 配置探索を `placeableAnywhere`（純関数 + `CellPredicate`）へ切り出し、
  フォークレシピ照合とバニラレシピ照合で共通化した（片方だけ直して挙動がズレる事故の予防）。
  フォークはテスト環境に MockBukkit が無く `ItemStack` を生成できないため、疑似グリッド文字列で
  配置・鏡像・空セル判定を検証している（既存の shape テストと同じ方針）。

### 2026-07-28 03:5x — 3 バックエンドへの配備（`tmp\deploy-v1.cmd`）

`Velocity_for_TF` 構成へ配備。TF jar / ArsPaper jar を Main・Resource・Dev の 3 台へ、
TF の yml 13 本を Main へ（ジャンクション共有）、`unlock-gate.yml` を 3 台へ。全 copy 成功をログで確認。
Resource / Dev 側から `use-level-scaling` が読めることを検証してジャンクションの生存も確認済み。
`EliteMobs.jar`（07-28 02:31、他セッションが配備）と `combat/mob-profiles.yml` には触れていない。

**この日の事故（再発防止）**: 起動中のサーバの jar を上書きしたため、`LoadResult` /
`TrainingDummies` / `PlayerData` が `NoClassDefFoundError` になった。JVM は未ロードのクラスを
実行時に jar から読むので、稼働中の jar 差し替えはコードのバグではなく**必ずこうなる**。
`/tf reload` では直らず JVM 再起動が唯一の復旧手段。発生箇所が
`ExecutorProgressionRepository.loadPerkIds` だったため、その間は進行データの保存が落ちていた。
→ `tmp\deploy-v1.cmd` は jar のコピーを最初に行い、1 本でも失敗したら
`ABORTED_JAR_LOCKED` で中断して yml に一切触れない（jar と config がちぐはぐにならない）。

### 2026-07-28 — 使用可能レベル連動EXP / 採掘EXP表の穴（テラコッタ）/ 採取ツールでの戦闘EXP誤付与

コミット: TF `cf5c969`（`dev` へ push 済み、13 files / +705 −6）。ArsPaper 側の変更なし。
検証は実走・実測: TF `BUILD SUCCESSFUL`・skipped は既知の 2 件のみ・failure/error 0 /
config-editor `tests 758 / pass 758 / fail 0`。release jar は 03:11 に再生成（15,860,881 bytes）。

- **使用可能レベル連動EXP（新機能・ユーザー要望）**。鍛冶・伐採・採掘・切削の EXP 取得量を、
  使ったツールの「使用可能レベル」で増やす。`stats/skill-exp.yml` の `use-level-scaling`。
  - 倍率 = `1 + 使用可能レベル × per-level`（既定 0.01 → Lv100 で 2.0 倍）、`max-multiplier` で頭打ち。
    ユーザー確認で「線形・控えめ」「鍛冶も採取3種と同じ強さ」を選択済み。
  - 鍛冶＝**作成した装備/ツール自身**の使用可能レベル（`CraftQualityListener`、`SMITHING` のみ。
    `ARS_SMITHING` には掛けない）。伐採/採掘/切削＝**破壊に使ったメインハンド**のツール。
  - **農業(FARMING)は対象外**（要件どおり）。**使用可能レベル0（素手・バニラツール・item-stats に
    プロファイル無し）は倍率1.0** なので、既存のバニラ相当プレイの取得量は1ビットも変わらない。
  - **爆破採掘(`EntityExplodeEvent`)には掛けない**。ツールで壊していないので要件の前提を満たさず、
    高レベルツルハシを持ったまま TNT を起爆するだけで倍率が乗る抜け道にもなるため。
- **バグ: 採取用ツールで敵を殴ると採取スキルEXPが入っていた**（ユーザー報告「斧で敵を殴ると
  伐採EXPが1もらえる」）。`CombatListener#maybeGrantCombatSkillExp` が
  **メインハンドの `use-skill` が何であれ、そのスキルへ戦闘EXPを付与する**実装だった。
  `item-stats.yml` は 2026-07-24 の対応で採取用の斧に `use-skill: WOODCUTTING` を持たせているため、
  斧で殴ると `combat.exp-per-hit`（=1.0）ぶんの伐採EXPが入っていた。
  **同じ穴がツルハシ(MINING)・シャベル(DIGGING)・クワ(FARMING)・釣竿(FISHING)にも開いていた**。
  `isCombatWeaponSkill` を新設し、この経路の付与対象を `HEAVY_WEAPONS`/`LIGHT_WEAPONS`/`ARCHERY`
  の 3 つだけに絞った。`ARS_MAGIC` は別経路（`grantMagicExp`）なので含めない。
  **「代わりに HEAVY_WEAPONS を与える」フォールバックは意図的にしない** — 採取用の斧は道具であって
  武器ではなく、TF には戦斧が別マテリアルの武器として存在する。
- **バグ: テラコッタを掘っても採掘EXPが0**（ユーザー報告）。`skills/base/mining_progression.yml` の
  `mining_break` 表が鉱石＋石系 47 行しかなく、バッドランドの地形ブロックが丸ごと抜けていた。
  表に無いブロックは `gatheringExp` が `blockExp <= 0` で即 return するため EXP 0 になる。
  自然生成のツルハシ採掘ブロック **42 行**を追加（テラコッタ 17 種 / 砂岩 2 / 海底神殿 4 + ドロップ 1 /
  ネザー 3 / 構造物石材 15 / 氷 3 / 尖った鍾乳石 1）。
  - **`exp-mode` 既定の `drop_sum` は「ドロップ品の材質名」で値を引く**ので、ブロック名の行だけでは
    足りない。`SEA_LANTERN` のように自分と違うもの（`PRISMARINE_CRYSTALS`）を落とすブロックは
    ドロップ側の行も必要。**この非対称は今後ブロックを足すたびに踏む**ので注意。
  - クラフト専用の装飾ブロック（`*_GLAZED_TERRACOTTA` 等）は「置いて掘るだけの EXP 稼ぎ面」を
    増やさないため意図的に除外し、回帰テスト（`glazedTerracottaIsIntentionallyAbsent`）で固定した。
  - シルクタッチ無しで何も落とさないブロック（氷 3 種）は既存の `drops.isEmpty()` ガードで 0 のまま。
- 私のレビューで見つけた抜け: 前バッチで SCALE 化した 4 feature が `tier-vocabulary.js` の
  `SCALE_FEATURE_SECTIONS` に未登録で editor の tier セレクトが効かなくなっていた（`cf5c969` の前に修正済み）。

### 2026-07-28 — 実サーバ報告 3 件（クラフト不可 / 複製 / 被弾パーティクル過多）

コミット: TF `9293f6c`（複製 + CME）、`1ce23b7`（パーティクル上限）。いずれも `dev` へ push 済み。
検証は実走・実測: TF `tests 2559 / fail 0 / skip 2`、config-editor `tests 758 / pass 758 / fail 0`。

- **①「op でない人が作業台などほとんどクラフトできない」= 配備漏れ（コード修正は不要）**。
  実サーバの `plugins/TrinityForge/skilltree/smithing.yml` に **`recipe:` ゲートが 41 件残っている**
  （石/鉄/金/ダイヤ/ネザライトのツール全種と鉄/金/ダイヤ/ネザライトの防具）。リポジトリ側は
  2026-07-28 にこのゲートを撤去済みだが、その yml が配備されていない。`removed-vanilla-recipes` が
  斧 6 種＋ネザライト斧の鍛冶を消しているぶんと合わさって「ほとんど作れない」状態になっていた。
  **op 権限とは無関係**（クラフト経路に `isOp()`/`hasPermission` 分岐は 1 つも無い。op が作れて見えたのは
  クリエイティブだったため）。ユーザー報告の「戦斧と槍は使える」がこの診断の裏付け＝どちらも
  ゲートの無い TF カタログ品。**`crafting_table` 自体はどの配備 config にもゲートが無く、単独では未説明のまま**。
  → 対処は `skilltree/smithing.yml` の配備と `/tf reload`（`D:/` への書き込みなのでユーザー実行）。
- **②「リザルトからドラッグで回収するとアイテムが消えず無限に増える」= 本物のサーバ側複製を修正**。
  `InventoryDragEvent#getRawSlots()` は**置き先スロットしか持たない**（引き出し元は入らない）ため、
  結果枠を起点にしたドラッグは置き先だけを見ていた旧ガードを素通りし、しかも `CraftItemEvent` を
  経由しないので素材が消費されない。さらに次 tick の `restampPreviewCrafts` がプレビュー品を
  本物の品質付きアイテムへ昇格させるので再ログイン後も残っていた。
  カーソルの中身が結果枠の中身と同一なら結果枠由来とみなして落とす方式に変更
  （`CraftQualityListener#draggedOutOfCraftingResult`、回帰テスト 8 本）。
- **③ 被弾時のハート型パーティクルが多すぎる件に上限を新設**（ユーザー選択「上限を掛けて減らす」）。
  バニラは与ダメージに比例した個数の `damage_indicator` を `sendParticles` で直接ブロードキャストしており、
  **対応する Bukkit イベントが存在しない**ためパケット層以外に手を入れる方法が無い。
  `combat/display.yml` に `damage-indicator-particles.max-count` を新設（既定 4 / `0`=完全に消す /
  `-1`=制限しない）、packetevents 経由で送信直前に個数だけを丸める。表示のみでダメージ計算には無影響。
  packetevents 未導入なら機能ごと無効化して起動ログに 1 行出すだけ（fail-open）。
  **`max-count: 0` はパケットごとキャンセルする**（個数 0 のパーティクルパケットは「消える」のではなく
  「offset を速度として 1 個だけ飛ばす」というバニラの特殊仕様になるため）。
- **④ 実サーバログにあった `ConcurrentModificationException` を修正**（コミット `9293f6c` → 真因判明後 `cf88bf4`）。
  `PlayerStatAggregator#tickCache`（同期化されていない `HashMap`）を**メインスレッドと非同期スレッドが
  同時に触っていた**のが真因。`NativeExperienceDispatcher#drain` は非同期タスクで、そこから
  `NativeProgressionService` → `TrinityForge` の `skill_exp_bonus` サプライヤ → `aggregate(...)` と
  降りてくる。`PerkAttributeApplier#reconcileAllOnline` の全員ループがこの CME でその場で中断していた
  （＝以降のプレイヤーに属性が当たらないまま放置）。**CME は最も軽い症状にすぎず、無限ループや
  エントリ消失まで起こりうる状態だった**。非同期呼び出しにはキャッシュを触らせない方式に変更。
  *（`9293f6c` の時点では「解決器経由の再入」と診断していたが、追加ログのスタックを読み直して訂正。
  スタックの `aggregate:199 → :224` は再入ではなくオーバーロードの委譲だった。）*
  **残る弱点**: 非同期スレッドから `player.getInventory()` / PDC を読むこと自体は解消していない
  （Bukkit 的には非推奨）。EXP 付与を同期側へ寄せるか、ボーナス値を事前に main で取っておくのが本筋。
- **⑤ `paper-plugin.yml` の依存宣言が実は 1 つも効いていなかったのを修正**（③の副産物）。
  paper-plugin.yml は Bukkit 形式の `softdepend:` を解釈せず**未知キーとして黙って捨てる**。
  Paper プラグインは宣言した依存以外のクラスを見られない（クラスローダ分離）ので、他プラグインの
  API を触るには `dependencies: server: {...}` 形式が必須。Vault の宣言もこれまで無効だった。

### 2026-07-28 — 数値のギミックyml集約（C〜D）/ `/tf dungeon` サジェスト / 特殊報酬の孤児掃除 / バニラ進捗の解除抑止

コミット: TF `b164678`（`dev` へ push 済み、50 files / +1973 −221）。ArsPaper 側の変更なし（jar は `98569af` のまま）。
検証は実走・実測: TF `BUILD SUCCESSFUL`・skipped は既知の 2 件のみ・failure 0 / config-editor `tests 758 / pass 758 / fail 0`。

- **`/tf dungeon` にインポート済み EliteMobs ダンジョンが出てこない件を修正**。サジェスト源が `gates.yml` の
  id/alias だけだった。`EliteMobsDungeonBridge#installedContentPackageIds` を新設して EM 側の
  インストール済みコンテンツパッケージを合流させ、`gates.yml` 未登録 ID でも EM へ直接入場する
  フォールバック（`quickEnterEliteMobs`）を足した。
  **`EMPackage.getEmPackages()` のキーは常に `.yml` 付き**なので `stripYamlExtension` で正規化している
  （この非対称は過去に「ドロップ指定が全不発」を起こした再発ポイント）。
- **生 % をスキルツリーから剥がして stats 側のギミック yml へ集約**（ユーザー承認 C〜D）。
  - `feature:furnace-smelt-speed` / `-bonus`、`feature:digging-durability-vanilla-exp` / `-job-exp` を
    `LEVEL`（生 %）→ `SCALE`（tier 番号）へ。実値は `stats/smithing-gimmick.yml` の
    `furnace-smelt.speed/bonus.tiers` と `stats/digging-gimmick.yml` の
    `durability-exp.vanilla-exp/job-exp.tiers[tier].cap-percent` に移した。
    切削側の旧実装は**同じ数値を「上限 %」と「tier 番号」の両方として使う二重定義**だった。
  - `feature:coating-stack-increase` を削除し、通常 stat `coating_charges_bonus` へ降格
    （単純加算しかしておらず feature である必然性がなかった）。`WeaponCoatingListener` は
    `PlayerStatAggregator#totalOf` で読む。
  - `dismantle-unlock` は `LEVEL` のまま据え置き。値は元から「レベル」で % は
    `crafting-features.yml` 側にあったため（**私が一度「生 % 直書き」と誤分類したのを訂正した**）。
    任意の `disassembly.tiers` を足したが、**レベル完全一致でのみ引き floor フォールバックしない**
    ——既に線形式という連続的な既定があるので floor を重ねると意味が二重に曖昧になるだけ。
  - **移行事故対策**: 配備先に旧 value（10/20/30）のままの skilltree yml が残って新 jar が載ると、
    「tier 以下で最大の行」規則で全部が最大 tier へ**無言で化ける**。tier 完全一致が取れなかった場合に
    一度だけ WARNING を出すようにし、`GimmickTierYamlDriftTest` でドリフトを検知する。
- **特殊報酬を config から消しても、プレイヤーが保持している分が残り続けていた件を修正**。
  `SpecialRewardPruner` / `SpecialRewardPruneListener` を新設し、参加時（オフライン PDC に触れる唯一の機会）と
  `/tf reload` 後のオンライン全員で掃除する。
  - 安全弁は**プルーナー自身**が持つ: `prune-orphaned-grants`（既定 true）と `lastLoadOk` の
    **両方**が true のときだけ走る。壊れた YAML を「全部未定義」と誤読して**全員の報酬を消し飛ばす**のを
    塞ぐため。`load()` は構文エラーだけでなく**エントリが 1 件でも skip された回**も false にする。
  - `PlayerData#revokeSpecialReward` は付与リストにある ID しか見ないので、
    **スキルツリーの `reward:<id>` perk 経由で装備しただけの ID**（付与リストに一度も入らない）は
    装備欄を別経路で個別に掃除する必要がある。
- **バニラ進捗の解除をサーバ側で抑止**（`achievements.yml` の `vanilla-advancements`、既定 `disabled: true`）。
  - 使えるイベントは **`com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent`**
    （`io.papermc.paper...` ではない。`javap` で実クラスを確認した）。既存の
    `PlayerAdvancementDoneEvent` は**キャンセル不可**なので使えない。
  - 対象は `minecraft:` 名前空間のみ（データパック・他プラグインの進捗は巻き込まない）。
    `minecraft:recipes/` を通す既定なのは、**バニラがレシピ本の解禁をこの隠し進捗で配っている**ため。
    ここを塞ぐと新しいレシピが一切解放されなくなる。
  - `trigger.type: advancement` の TF アチーブメントは**永久に達成不能**になる。定義が 1 件以上あって
    `disabled: true` なら起動/reload 毎に WARNING を出す（出荷 yml は 0 件なので既定では出ない）。
- config-editor: 新設キーの編集 UI・バリデータ・ラベルを追加。
  **私のレビューで見つけた抜け**として、SCALE 化した 4 feature が `tier-vocabulary.js` の
  `SCALE_FEATURE_SECTIONS` に未登録で tier セレクトが効かなくなっていたのを追加登録した
  （この 4 件だけ tiers が 1 段ネスト下にあるので key をドット区切りパス解決へ拡張）。
  以後は `gate-vocabulary.js` の `param:"scale"` 全件との**集合一致をテストで強制**する。
- **やっていないこと**: `junkfood-inversion`（C）と `junk-food-restore-boost`（D）は、
  対象ファイル（`FoodGimmickListener.java` / `FoodBonusListener.java` / `stats/food-gimmick.yml`）が
  **並行セッションの作業中**だったため手を付けていない。

### 2026-07-28 — 9 要件バッチ（鍛冶ティアゲート撤去 / recipe⇔ritual チャンネル / レシピGUI素材表示 / editor UI 4 件）

コミット: TF `592d1c8`（`dev` へ push 済み） / ArsPaper `98569af`（`feat/trinityforge-fork` へ push 済み）。
両 jar 再ビルド済み（`TrinityForge-all.jar` 15,833,272 bytes 01:27 / `ArsPaper-1.0.0.jar` 965,695 bytes 01:31）→ **未配備・要フル再起動**。

- **鍛冶主軸 A〜D の `recipe:` ティアゲート 41 件を撤去**（ユーザー決定「ツールにも使用可能レベルがあるのでゲートを付けるだけ無駄」）。
  代わりに 1（作業台品質の段階増）と 3（上振れ拡大 / ロール上振れ）をバランスさせた buff へ置換:
  A=品質+2 / B=品質+2・ロール上振れ+1% / C=品質+1・上振れ+0.10・ロール上振れ+2% / D=品質+1・上振れ+0.15・ロール上振れ+3%。
  **配分の根拠**: `workbench_quality_bonus` は `max-quality: 9` で飽和するので増分は低レベル側（A/B）へ、
  飽和しない σ 拡大系は高レベル側（C/D）へ寄せた。理由と再発防止の注記は `skilltree/smithing.yml` ヘッダに常駐。
  副次的に、`removed-vanilla-recipes` で消えた `minecraft:*_axe` を指していた **[PRG-02] 実害警告 4 件も解消**。
- **`recipe:` / `ritual:` チャンネル取り違えを修正（実バグ）**。`enchant_book_*` 8 件と `volcanic_sourcelink` は
  ArsPaper 側で `method: ritual` なのに `recipe:` に置かれていた。ArsPaper は儀式を `ritualPerks` /
  `tfRitualGatePerks` でしか照合しない（`UnlockGate#hasRitualPermission`）ため、**このゲートは無言で常時解放だった**。
- **[PRG-02] 起動時検証を最初の tick へ遅延**。ArsPaper は TF に depend していて後から enable するので、
  TF の `onEnable` 内では ArsPaper の作業台レシピ（`tf_core_*` / `compressed_*` / `source_gem_block`）が
  まだ Bukkit に登録されておらず、実在するのに「解決できない」と誤警告していた（残り 16 件の正体）。
- **ビルド時ドリフト検知を新設**: `RecipeRitualGateChannelDriftTest`（3 テスト）。
  `recipe:` ゲート id の集合完全一致 + 儀式 id が `recipe:` 側に混ざっていないこと + `ritual:` 側に揃っていること。
  ティアゲートの再混入もチャンネル取り違えも、次からはビルドで落ちる。
- **`/ars recipes` を撤去し `/tf recipes` へ一本化**。TF は ArsPaper にコンパイル依存できないので
  `ArsRecipeBrowserBridge` のリフレクション委譲（**`RecipeBrowserGui` のコンストラクタと `open()` の
  シグネチャを変えると TF 側が無言で fail-soft に落ちる**）。並べ替えボタンの Lore は `CollectionGui` 準拠へ。
- **レシピ詳細の素材表示バグを修正**（ジュエリーコアが「圧縮ブロックでない」件）。
  `RecipeManager#resolveIngredient` は `custom:` / `list:` を**意図的に** `MaterialChoice` へ倒している
  （`ExactChoice` だと PDC 付きの実物と `isSimilar` 不一致でクラフトが無言失敗するため）。その結果
  `describeChoice` が**先頭 Material しか復元できず**、`custom:coal_block_3x` が素の `COAL_BLOCK` に見えていた。
  `forwardRecipeData(key)` の元 config トークンで `ingredientMap` と `shape` を**セットで**上書きして解決。
- **config-editor 4 件**: skill-exp のラベルID混入（`mode` が items.yml の「天候」と衝突 → セクション単位の
  ラベル上書きで解決、`outside-dungeon-exp-rate` の入力欄欠落も追加）/ ギミック 5 画面のカード余白
  （**根本原因は `.card-list-body` に CSS ルールが 1 つも無かったこと**＋4 セクションの root が class 無しの div だったこと）/
  解体対象シリーズのネスト削減（長い見出し → 短いラベル + `?` ツールチップ）/ グリフ BAN リストと
  達成記録の対象モブを表示名表記へ。テスト 734 → **751**。

### 2026-07-27 — レベル差足きり / プレイヤー用インスタンスコマンド / 繁殖モブEXP対策 / 戦闘バランス

`42bf57e`。TF テスト失敗 0・スキップ 2（既知の 2 件のみ）、config-editor 734/734。**ビルド済み・未配備**。

**① モブ定義のレベル差足きり（`combat/mob-overrides.yml` の `level-cutoff:`）**
`MobLevelCutoff`（Bukkit 非依存の record）を新設。`diff = プレイヤー戦闘レベル − モブレベル` で 2 種類を独立に判定する。
自分が格上なら `over-level-threshold` 以上の差で**経験値と TF 追加ドロップにそれぞれ別の減衰率**を掛け、`-1` は「完全に入手不可」。
自分が格下なら `under-level-item-threshold` 以上の差で TF 追加ドロップを遮断（経験値には影響しない）。
解決順はワールド別モブ > ワールド別スコープ > 既定スコープのモブ > 既定スコープ（**フィールド単位ではなくブロック単位のマージ**）。
**バニラ本来のドロップには触れない**（モブトラップが完全に死んで「足きり」の域を超えるため）。config-editor から編集可。

**② `/tf start` `/tf stop` `/tf quit`（プレイヤー用）**
`/em start` `/em stop` がプレイヤーに使えないため相当コマンドを追加。EliteMobs の `MatchInstance` へ
リフレクション委譲（`EliteMobsInstanceBridge`）。**リフレクションは具象サブクラスではなく `MatchInstance` クラス自体から
`getMethod` する** — 具象が非 public だと `IllegalAccessException` になるため。EM 未導入でも全経路 fail-soft。

**③ EM コマンド遮断を許可リスト方式へ（ユーザー未報告の既存バグ）**
`EliteMobsCommandGateListener` がラベル単位で `/em` を全遮断しており、**既製ダンジョンのテレポーター NPC 約 40 体・
ボスバー追跡・ステータスダイアログのテレポート・スポーン帰還が無言で死んでいた**（NPC は `performCommand` で `/em` を撃つ）。
`start/quit/track/dungeontp/dungeontpdialog/spawntp/arena` を許可リスト化。
`/em dungeontp` を許可しても TF のゲートは素通りしない（`DungeonGateListener` が `PlayerTeleportEvent` を HIGH で捕まえる）。

**④ `/tf dungeon <id>`（管理者用・鍵なしクイック入場）**
登録済みゲート ID をサジェスト表示。ID 解決は `gate(id)`（ワールド名のみ）ではなく **`resolve(id)`**（コンテンツパッケージの
別名も引ける上位集合）を使う。`DungeonEntryGui` からテレポート処理を `DungeonTeleporter` へ抽出して共用した。

**⑤ 繁殖・建造で無限に増やせるモブの EXP 対策**
`mob-level-table.yml` のレベル帯から BEE / GOAT / LLAMA / TRADER_LLAMA / PANDA / WOLF / IRON_GOLEM を除外。
加えて新設 `no-skill-exp-mobs:` により、**TF の戦闘スキル EXP（武器＝命中／防具＝被弾）を一切加算しない**。
**バニラの EXP オーブは従来どおり落とす**（エンチャント等の用途を潰さないというユーザー判断。当初の
「討伐 EXP を 0 にする」案は取り下げ）。戦闘スキル EXP は**命中ごと**に入るため討伐 EXP とは別経路である点が要点。
**魔法（ARS_MAGIC）は構造的に対象外** — Ars 側の EXP は「詠唱したこと」に対して付き（`grantMagicExp` は対象 Entity を
引数に取らない）、何を撃ったかを参照しないため EntityType で絞る余地が無い。yml / javadoc / editor UI にその旨を明記した。

**⑥ 戦闘バランス（`combat/mob-types.yml`）**
防御 7 キーの `level-coefficients` を 0.007 → 0.0025、貫通を 0.008 → 0.002、`max-health-growth` を 1.055 → 1.048、
基準 HP を一律 ×1.0526。Lv100 の複合軽減率が 97% → 約 57% になり、表示 HP と実効 HP の乖離が約 33 倍 → 約 2.3 倍へ。
`damage.yml` の `defense.max-mitigation-rate: 0.9` は**キー個別の上限**で、複数キーの積は止められない点が原因だった。
`attack-power-growth` は 1.03 → **1.033**（ユーザー決定）。同帯フル装備・厳選なしでの被弾耐久が
革 8.1 / 銅 6.6 / 鎖 7.2 / 鉄 5.5 / 金 6.2 / ダイヤ 4.8 / ネザ 4.0 発。
**この値は 2 つの指数の引き算で決まるため極めて敏感**で、1.031 だと高帯が 6.7〜7.1 発、1.035 だと 2.7〜3.5 発まで振れる。
**`stats/item-stats.yml` の `phys-flat-defense` を変えたら必ず再校正すること**（yml ヘッダにも明記）。
再校正用スクリプト = `tmp/dmg-table.js`（config を直接読んで装備帯 × 敵レベルの被ダメージ表を再生成する）。

**このセッションでコミットしなかった変更（並行セッションの WIP と判断）**: `ops/**`、`FoodGimmick*` 一式、
`ResourceServerMobSimulationTest`、`tool-config.json`（配備先が `Velocity_for_TF/Dev_Server` へ変更されている）、
`.claude/launch.json`。**`dungeon/themes.yml` と `progression/special-rewards.yml` も除外した** — 差分が
`themes: {}` / `titles: {}` / `particles: {}` への**出荷サンプルの消去**になっており、editor 保存による事故の疑いがある（§5）。

### 2026-07-27 — アイテムステータスの幽霊枠 / 機能アイテム画面の新設 / editor 説明UIの整備

`532bcef`（本体）＋ `f034b94`（ArsPaper フォーク）。ユーザー報告 3 バッチをまとめて処理した。

**① 素材とスレッドが「補助」タブにステータス定義として並んでいた原因**（`forms.js`）。
`buildItemStatsForm` が**カタログ候補を総なめして枠を自動生成**していた。ここに 2 つの穴があった:

1. ArsPaper `materials.yml` 由来の候補（`tab: "material"`）まで枠を作っていた。
2. `tab` が決まらない候補を `|| "other"` で**暗黙に「補助」へ落としていた**。

→ 素材はスキップ、`tab` 未確定の候補は**枠自体を作らない**（暗黙のフォールバックを廃止）。
実測で 313 → 421 件に膨らみ、うち 87 件が素材のピン留めだった。
**ユーザーが見た状態はリポジトリにも配備先の yml にも再現しなかった**（＝保存済みデータではなく
画面生成側の挙動）ので、症状を作る機構のほうを塞いだ。

**② TF 特殊アイテム 2 件（スキル再構築の書 / スキルノードの楔）をカタログから「特殊アイテム」へ。**
`catalog.yml` で ID を書き換えられる状態だったが、この 2 件の ID は
`com.trinityforge.skilltree.runtime.SkillTreeItems` が直接参照する固定値。
→ 新設した「特殊アイテム」画面へ移し、**内部 ID 欄をロック**。カタログ画面からは非表示にしつつ
保存内容は無損失で維持する（`split-views.js` で退避 → `getData` で戻す）。

**当初この 2 件をアイテムステータスの「補助」に残す判断をしたが、ユーザーの指示で撤回**して
機能アイテム側へ寄せた。撤回して初めて分かった実害があり、こちらのほうが重い —
**この 2 件は `catalog.yml` に `custom-model-data` を持たない**ため、ステータス枠を作ると
キーが素の Material（`AMETHYST_SHARD` / `ECHO_SHARD`）になり、**バニラ素材全体にステータスが
乗る**。ID ロックの話ではなく、単体でバグだった。

**③ editor に「機能アイテム」カテゴリを新設**し、特殊アイテム（旧「機能アイテム」）/ ソースリンク /
ソースジャーを集約（`registry.js` の `section` ＋ `app.js` の `configSectionKey`）。
TF と Ars がユーザー目線で統合されている以上、プラグイン単位で分けない。

**④ 採集効率の表示名**が「最終効率」のまま残っていた箇所を統一（`lore.yml` / `labels.js` /
`skilltree/woodcutting.yml`・`mining.yml` の `effect-text` ＝**プレイヤーに見える文言**）。

**⑤ 基礎ステータス / 上限設定タブ**を Lore 表示のカテゴリ順にグループ化。死にキー
`tool-enchant-efficiency` を非表示に。上限の根拠（どこで clamp されるか）は見出しから消さず
**行ごとの `title` へ移した**。

**⑥ ArsPaper 全体設定**のカード名を日本語化し、**geyser 設定カードを撤去**。
`disable-custom-model-data` は **`config.yml` に定義自体が存在せず**既定値 `false` が常に
効いていた＝挙動は変わらない。無効化するとリソースパックのモデルが一切出なくなるので、
逃げ道ごと消した（`BaseCustomItem.java`）。

**⑦ `.form-hint` / `.field-desc` に CSS が 1 つも無かった**（19 ファイル 56 箇所が無指定＝本文と
同じ白字）。1 ルール追加で全部が薄いグレー 12px になる。あわせて `helpIcon` をネイティブ
`title` から独自ポップオーバーへ置換（hover / click / Escape / 外側クリック / 端で反転、
テキストは全部 `h()` のテキストノード＝`innerHTML` を使わない）。
「XXX へ移行しました」「廃止しました」系のレガシー説明を削除し、**現在の制約を説明する文**へ
書き換えた（現行仕様を述べている「廃止」表記は消さずに書き直した）。

**自分で踏んで自分で潰した罠**: `forms.js` に
`const IDS = (window.FUNCTIONAL_ITEMS_CORE && ...) || []` と書いたが、`index.html` の読み込み順は
`forms.js`(64 行) → `functional-items.js`(67 行) なので**モジュール読み込み時点では必ず空配列**。
呼び出し時に解決する関数へ直し、**require 順を逆にしたテスト**を足して固定した。

検証: config-editor **695 / 695 pass・fail 0**（実走）。実ブラウザで
サイドバー順・両「補助」タブ・特殊アイテム画面の ID ロック・`.field-desc` の算出値
（12px / `rgb(139,147,163)`）・ツールチップに `title` が無いこと・`aria-expanded` の開閉を確認。

**ビルド環境の変化**: Java **25.0.4**（2026-07-21 LTS）が自動更新で PATH の既定 `java` を奪い、
Gradle が `What went wrong: 25.0.4` だけを吐いて起動しなくなった。JDK 21 は
`C:\Program Files\Java\jdk-21` に残っているので、ビルドスクリプトで
`-Dorg.gradle.java.home` を明示指定して回避している（→ §5）。

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

### 2026-07-28 — EliteMobs / Backuper の起動時バージョン通知を止める

起動のたびに出ていた「更新版があります」の通知を両方とも止めた。

**EliteMobs**（フォーク側で対処。`Klee319/EliteMobs-trinityforge` の `55a38c1c`）

```
[EliteMobs] Latest public release is 10.7.3
[EliteMobs] Your version is 10.3.0
[EliteMobs] [EliteMobs] A newer version of this plugin is available for download!
```

**config に切る設定は無い。** `VersionChecker.checkPluginVersion()` が
spigotmc の API を叩いて本家の最新版と比べているだけで、
**このフォークは 10.3.0 固定＝本家に追従しない**（追従したら改変が消える）ので
**永久に「古い」と判定され続ける**。さらに `pluginIsUpToDate = false` が残るため、
`VersionCheckerEvents` が**管理者の参加時にも同じ案内をチャットへ送っていた**
（コンソールだけの問題ではない）。

対処: `checkPluginVersion()` を no-op にし、本家実装は
`checkPluginVersionUpstream()` として残置。
`serverVersionOlderThan`（1.21.x の互換分岐に多用されている）と
`checkContentVersion`（Nightbreak のコンテンツパック）には触っていない。

- 再ビルド: `gradlew.bat shadowJar --offline -Dorg.gradle.java.home="C:\Program Files\Java\jdk-21"`
- 出力は `testbed/plugins/EliteMobs.jar`（6,839,502 bytes）。
  **`build/libs/*-min.jar` を配ってはいけない**（MagmaCore 剥離で起動不能）
- main / dev の `plugins\EliteMobs.jar` へ配備済み（02:31）。resource には EliteMobs 自体が無い
- 逆アセンブルで `checkPluginVersion()` の本体が `return` のみになっていることを確認

**Backuper**（`Main_Server\plugins\Backuper\config.yml`）

`server.checkUpdates: true` → `false`。「Backuper is outdated」バナーが消える。

> 残るもの: 起動時の `Issue tracking` バナーは Backuper 4.0.4 に切る設定が無く、
> `onEnable` で無条件に出力される。消すには log4j のフィルタが要るので今回は手を付けていない。

**どちらも反映はフル再起動から**（jar 差し替えと config 読み込みのため）。

### 2026-07-28 — LuckPerms の per-server 設定と、dev を管理者専用にする方法

ユーザー依頼: **「luckperms でしておくべき設定を教えて、dev は管理者用とする」**
＋**「過去に dev にアクセスしていた人のデータは権限含めてすべて削除してほしい」**。

作業書は [ops/RUNBOOK.md](../ops/RUNBOOK.md) の**手順 14（LuckPerms）／手順 15（データ消去）**。

**実施済み（設定ファイルの書き換え）**

| 対象 | 変更 | 効く条件 |
|---|---|---|
| 3 台の `plugins\LuckPerms\config.yml` | `server: global` → `main` / `resource` / `dev` | 再起動 |
| `velocity.toml` | `try = ["main","resource","dev"]` → `["main"]` | 再起動 |

**非自明な点（次に触るときここを忘れる）**

- **`server: global` のままだと per-server 権限が「全サーバ権限」に化ける。**
  `server=dev` を付けても、そのコンテキスト値がどこにも発生しないので
  「一致しない」ではなく「コンテキスト無しの永続付与」として通る。
  dev 限定のつもりの権限が main で効く、という形で気付く。
- **Velocity は `/server` の行き先ごとの権限を持っていない。**
  `velocity-4.1.0-SNAPSHOT-9.jar` の `ServerCommand.class` を逆アセンブルして確認：
  参照している文字列は `velocity.command.server` の 1 個だけで、判定は
  `getPermissionValue(...) != Tristate.FALSE`。**UNDEFINED は「許可」**。
  さらに**プロキシに LuckPerms が入っていない**（proxy 側は Geyser / ViaVersion /
  ViaBackwards / floodgate のみ）ので、現状は**全員が `/server dev` を実行できる**。
  → dev の入口は `Dev_Server\server.properties` の `white-list=true` で閉じる。
  LuckPerms では実現できない。
- `try` に dev を残すと、main が落ちている間に来た人が dev に着地する。

**新規: `ops/scripts/purge-player-data.ps1`**

3 台のプレイヤーデータと権限を消す。既定は下見、消すのは `-Apply`。

- **サーバ起動中は中断する。** 起動中に消しても停止時に Paper が書き戻し、
  HuskSync が MariaDB から復元するので消えない
- **MariaDB を消さないと元に戻る。** インベントリの実体は `husksync_user_data`、
  権限の実体は `luckperms_*`。ファイル消去だけでは不十分なので、
  そのまま流せる SQL を書き出して手で実行してもらう（資格情報を持たないため）
- `player_progression.db` は 3 台でジャンクション共有している実体。消すと全台から消える
- 消す前の内容は `_purge-backup-<日時>\` へ退避

**実装中に踏んだ落とし穴 2 件**（同じ書き方をするとまた踏む）

- `@(... | ConvertFrom-Json)` は **JSON 配列を「配列 1 個」として受ける**。
  要素 6 の `ops.json` が `Count = 1`（中身は `Object[]`）になり、
  名前の突き合わせが全部外れて **Klee319 まで削除対象に入っていた**。
  一度変数で受けてから `@()` で均す必要がある。**要素 1 件のファイルでは再現しない**ので、
  main（op 1 人）だけで試すと素通りする
- `Set-Content -Encoding UTF8` は **BOM を付ける**。`ops.json` / `whitelist.json` は
  Minecraft 側が Gson で読むため、BOM 付きだと「op が全部消えた」ように見える。
  `[System.IO.File]::WriteAllText` + `UTF8Encoding($false)` で書く

**検証**: 実データを写したフィクスチャで下見→`-Apply` を実走。
起動中ガードが実サーバで発火すること、`ops.json` が Klee319 のみになること、
`whitelist.json` と `white-list=true` が書かれること、
出力 4 ファイルすべてに BOM が無く Python で JSON としてパースできることを確認。

**残（ユーザー側）**: 3 台を停止 → `-Apply` → SQL 実行 → 再起動 → 手順 14-3 以降の `/lp` 実行。

### 2026-07-28 — サーバ移動で「飛行状態だけ引き継ぐ」バグ（HuskSync `flight_status`）

**症状**: サーバ移動でゲームモードはサバイバルに戻るのに、飛行状態だけ維持される
（＝サバイバルなのに飛べる）。

**原因**: HuskSync の `flight_status` は `game_mode` とは**別の同期項目**で、
依存関係が **optional** でしかない。

```java
// husksync common/data/Identifier.java
FLIGHT_STATUS = huskSync("flight_status", true, Dependency.optional("game_mode"))
```

`game_mode: false`（資源サーバでクリエイティブにしないための必須設定）にしても
`flight_status` は既定の `true` のまま同期され続けるため、
「ゲームモードは復元しないが飛行状態は復元する」という食い違いが起きる。
**キー名からは読み取れない**ので、`game_mode` を落とした時点で必ず踏む。

**対処**: `flight_status` は `game_mode` と**必ず同値**にする。

- 実サーバ 3 台の `config.yml` を `flight_status: false` へ（退避あり）
- `apply-husksync-config.ps1` の配布内容に追加。書き込み後の検証も
  `game_mode` だけでなく `flight_status` も見るように変更
- `preflight.ps1` の必須 features に追加（起動前に検出できる）
- `ops/templates/husksync.config.yml` に理由と出典（Identifier.java の該当行）を明記
- 自己テストのフィクスチャを `game_mode: false` + `flight_status: true` に変え、
  **この組み合わせを preflight が名指しできること**を回帰テスト化

既に飛んでいるプレイヤーは `/gamemode survival` を撃ち直せば解除される。

**検証**: preflight 0 件 / `apply-husksync-config.ps1 -DryRun` が「正本に対する変更はありません」
（スクリプトと実ファイルが一致）/ 自己テスト 25/25。

### 2026-07-27 — 3 バックエンドの構成統一（main = dev）とジャンクション敷設

ユーザー指定: **データ移行は不要（1 から始める）／main と dev は同一構成／dev で問題なければ main を使う**。
確認事項の回答: dev と main は**個別にも同時にも起動する**／dev の world は残し **config だけ引き継ぐ**。

**実施**

- **`seed-backend-configs.ps1` を新設**して dev → main / resource へプラグイン config を配布（30 件）。
  **データは配らない**（`EliteMobs/data`・`CommandBinderGUI/playerdata`・`SetHome/homes.yml`・
  `WorldGuard/worlds`・`Multiverse-Core/worlds.yml`・LuckPerms の h2・`ArsPaper/ranking_cache.json` ほか）。
  `.paper-remapped` は Paper の再マップキャッシュなので除外（配ると数百 MB の無駄）。
  資源サーバの除外は PLUGIN_MATRIX.md 準拠（EliteMobs / DiscordSRV / Multiverse / WorldGuard / BlueMap / Backuper）。
- **`plugins/TrinityForge` の実体を Main_Server へ移し**、resource と dev からジャンクション。
  **進行 DB は `player_progression.db*.dev-<日時>` へ退避**して新規生成させた（config だけ引き継ぐ指定のため）。
- **jar を統一**。main と dev は**差分ゼロ**。resource へ Chunky と WorldEdit を追加。
  プロキシへ移設済みの `ViaVersion` / `ViaBackwards` / `geyserExtra` は
  バックエンドから `plugins/_moved-to-proxy/` へ退避（削除しない）。孤児 config も同様。
- **LuckPerms を `storage-method: h2` → `mariadb`、`messaging-service: auto` → `redis`** に変更（3 台）。
- **preflight の誤検出を修正**。既定値判定が大文字小文字を区別しておらず、
  実際の DB 名 `husksync` を既定値 `HuskSync` と同一視して 3 件の偽陽性を出していた（`-cmatch` へ）。
- `check-logs.ps1` に `Address already in use` / `BindException` を追加。
  **dev と main を同時に上げると BlueMap の Web ポート 8100 が衝突する**ため。

**発見して直した実害**

- **Floodgate の `key.pem` がプロキシとバックエンドで違っていた。**
  プロキシ側は floodgate-velocity が初回起動時に生成した別の鍵で、このままだと
  **Bedrock プレイヤーが全員別人扱い**になる。バックエンド側（旧サーバから引き継いだ鍵）へ揃え、
  プロキシの生成鍵は `key.pem.generated-<日時>` に退避。

**検証**: preflight **0 件** / `sync-configs.ps1 -DryRun` 問題なし（ジャンクション検出・ArsPaper 13 件一致・jar 重複なし）/
自己テスト 25/25 / main と dev の jar・config ディレクトリの差分は `bStats` のみ（サーバ固有 ID なので正しい）。

**残（初回起動後でないとできない）**: BlueMap の dev 側ポートを 8101 へ／3 ワールドの
`/worldborder set 5000`／LuckPerms の権限を h2 から引き継ぐなら `/lp export` → `/lp import`。
**残（要管理者）**: `schtasks` 登録。**残（要判断）**: `velocity.toml` の `try` が
`["dev","main","resource"]` のままなので、本番移行時は main を先頭へ。

### 2026-07-27 — `launch` フォルダ（起動バッチ＋testkit）と **`.cmd` の UTF-8 破壊バグ**

ユーザー依頼で「各種サーバと依存関係の start バッチと testkit を
`D:\game\minecraft\PaperServer\Velocity_for_TF\launch` にまとめる」を実施。
正本は `ops/launch/`、配置は `ops\launch\deploy.cmd`（robocopy /E・自分自身への上書きを拒否）。

**構成** — `launch-config.cmd`（パス/ヒープ/jar名を 1 箇所に集約＋存在確認）/
`start-{mariadb,garnet,main,resource,dev,velocity}.cmd` /
`start-all.cmd`（MariaDB → Garnet → preflight → main → resource → dev → Velocity。
**preflight が 1 件でも検出したらサーバを上げずに中断**）/ `stop-all.cmd` / `status.cmd` /
`testkit/{check-ops-scripts,check-datastores,check-config,check-logs,check-all}.cmd`。
新規 ps1 = `stop-network.ps1`（逆順停止・`stop.flag` を `stop` の**前**に置く・強制終了しない）/
`check-logs.ps1`（既知症状 10 種）/ `show-status.ps1`。

**最重要の発見: cmd.exe は UTF-8 のバッチファイルを正しく読めない。**
マルチバイト文字があるとファイル位置の計算がずれ、**行の途中から実行を始める**。
`chcp 65001` でも UTF-8 BOM でも直らない（ACP=65001 の環境で実測）。実害:

- **`ops/scripts/server-loop.cmd` が完全に壊れていた。** 引数検査も jar 存在確認も
  `stop.flag` 判定も素通りし、**遅延ゼロで空回りする無限ループ**（120 秒で 2.6MB の
  エラー出力）。これは全バックエンドを包む再起動ループ＝**サーバが 1 台も起動しない**状態。
  Main/Resource を一度も起動していなかったため発覚が遅れた。
- `setup-junction.cmd`（`move` と `mklink` を実行＝誤動作すると破壊的）も同様に壊れていた。
- `ops/templates/garnet.cmd`、`ops/templates/start-all.cmd` も同様。

対応: **`ops` 配下の全 `.cmd` を ASCII 化**（説明は `.ps1` と `.md` へ移設。PowerShell は
UTF-8 で問題ない）。`run-selftest.ps1` に**非 ASCII 検出テストを追加**して再発を防止（25/25 緑）。
`server-loop.cmd` は修正後に「引数なし」「jar 無し」「`stop.flag` あり」の 3 経路を実測。
`setup-junction.cmd` は引数化（`Resource_Server` / `Dev_Server`）し `Main_Server` 指定を拒否。
`ops/templates/start-all.cmd` は `launch/` に置換されたので**失敗して案内する stub** に変更
（`\"` エスケープをバッチが解釈しない別のバグも抱えていた）。

**副次の修正**: `find` / `timeout` を `%SystemRoot%\System32\...exe` で完全修飾。
GNU coreutils が PATH 前方にある環境では両方が乗っ取られ、Windows 構文を拒否するため
**チェックが黙って無効化される**（`server-loop.cmd` では再起動遅延が消えてクラッシュループが全速化する）。

検証: 自己テスト 25/25 / `status.cmd`・`check-datastores.cmd`・`check-logs.cmd`・
`check-config.cmd`・`stop-network.ps1 -DryRun`・`start-garnet.cmd`・`start-mariadb.cmd` を実測。
`check-logs.cmd` は dev の実ログから HuskSync enable 失敗を正しく検出。
preflight は 10 件 → **5 件**（残りは全て MariaDB の DB/ユーザー未作成に起因＝ユーザー側作業）。

### 2026-07-27 — Garnet 導入と forwarding secret 反映を実施（ユーザー依頼でセットアップ代行）

ユーザーの依頼で残りのセットアップを実行した。**dev は main と同一構成**という指定。

**実施済み**

- **Garnet 2.1.0 を導入し稼働開始**。`D:\game\minecraft\Garnet\`（zip は
  48,245,050 バイト / SHA-256 `b810ee55…`）。`preflight.ps1` が
  `Garnet 2.1.0` を RESP 経由で識別。実測 RSS 57MB
- **`apply-velocity-forwarding.ps1` を実行**。Resource と Dev の `proxies.velocity` を
  `enabled: true` + secret 一致に更新（main は既に正しかったので無変更）。**要再起動**
- 指摘は 11 件 → **5 件**（残りは全て dev の HuskSync config で、DB 資格情報待ち）

**私の誤りを 1 件訂正**: 「Garnet の zip は自己完結なので .NET 不要」は**間違い**。
`readytorun` は事前 JIT であって自己完結の意味ではなく、実際に `--version` が
「.NET 10 をインストールせよ」で落ちた。**zip には `net8.0/` と `net10.0/` の 2 つが入っており、
この環境には .NET 8.0.21 が既にあるので `net8.0` 版がそのまま動く**（追加インストール不要）。
サイズ（48MB）から自己完結だと推測したのが原因。

**Garnet の非自明な既定値**: **`--memory` の既定は 16g**。指定しないとメインログ用に
16GB を抱えに行き 8G + 6G の JVM と取り合う。`--bind` の既定は any（外部到達しうる）。
両方とも明示が必須。正本は `ops/templates/garnet.cmd`。

**`apply-husksync-config.ps1` を新設**（3 台へ同一の config.yml を配る）。正本は
`-BaseFrom` のサーバの**生成済み** config.yml で、テンプレートの丸写しではないので
HuskSync の版が変わってもキーがずれない。**パスワードは引数でも環境変数でも受け取らず、
実行時に `Read-Host -AsSecureString` で入力させる**（履歴とプロセス一覧に残さない）。

そのために `lib/Yaml.ps1` を新設した。HuskSync の config には
`database.credentials.host` と `redis.credentials.host`、同じく `password` が**同名で 2 組**あり、
行の単純置換では別ブロックを書き換える。親をたどって探索範囲を絞る `Find-YamlLineIndex` で回避。

**私が完了できない残り 2 件**（どちらも秘密情報が要るため）:
MariaDB の DB / ユーザー作成（root パスワード）と、Garnet の起動時タスク登録（管理者権限）。

`run-selftest.ps1` は **24/24**（yml のキー解決 4 本を追加）。

### 2026-07-27 — forwarding secret の一括反映スクリプトと Dev_Server の隔離方針

MariaDB のネイティブ導入が完了（`preflight.ps1` がハンドシェイクから **12.3.2-MariaDB** を確認）。
残った指摘のうち、`proxies.velocity` が Main_Server だけ設定済みで Resource / Dev が
未設定という点に対し **`ops/scripts/apply-velocity-forwarding.ps1`** を新設した。
secret が 1 文字違うと全員が `Unable to verify player details` で入れなくなるので、
3 台ぶんを手で貼らせない。`enabled` と `secret` の 2 行だけを書き換え、書く前に同じ
ディレクトリへ退避し、**secret は画面に出さず**、既に一致しているサーバは 1 バイトも触らない。

**Dev_Server に HuskSync を入れるときの落とし穴**: `cluster_id` を変えるだけでは隔離にならない。
`cluster_id` は **Redis のキーとメッセージチャンネルにしか効かず**（`RedisManager.java` を実読）、
**スナップショットの保存先である MySQL のテーブルは同じまま**。テーブル名は
`database.table_names` で別に決まる。dev で実験するなら **DB を別に作る**
（`husksync_dev` + `cluster_id: dev`）。それでも Garnet / MariaDB / HuskSync の経路は
本番と同じものを通るので、互換性検証としては十分。

**追加テストが実バグを 1 件検出**（`run-selftest.ps1` は 20/20）。yml の単一引用符スカラーを
`'?([^']*)'?` で剥がしていたため、**値に引用符が含まれるとマッチ自体が失敗し、
「値が読めない」を「値が違う」と誤判定**していた（既に正しい main を無駄に書き換えた）。
`ConvertFrom-YamlScalar` / `ConvertTo-YamlSingleQuoted` を `Common.ps1` に置き、
`preflight.ps1` と新スクリプトの両方をそれ経由に統一。

### 2026-07-27 — データストアを Windows ネイティブ構成へ（WSL2 は付録に降格）

ユーザーの要望で MariaDB / Redis を **WSL2 ではなく Windows ネイティブ**にできるか検討し、
できるので既定を差し替えた。ネイティブのほうが本構成では素直に有利:
WSL2 は VM なのでメモリを別枠で取り 8G + 6G の JVM と取り合う／Windows サービスなら
タスクスケジューラ運用と揃う（WSL は「ログオンしないと上がらない」事故がある）／
`wsl --install` の UNIX アカウント作成の対話が要らない／localhost 転送の層が消える。

**Redis の代替に Garnet を採用**（Microsoft・MIT・v2.1.0 = 2026-07-24・自己完結 zip で
.NET のインストール不要）。判断の根拠:

- **Redis 本体に公式の Windows 版は無い**
- **Memurai は不可**。Developer 版は**稼働 10 日上限かつ本番利用禁止**、本番は有料
- tporadowski/redis は Redis 5.0 相当で更新停止
- **HuskSync が使う Redis コマンドは 9 つだけ**（`PING` / `SET` / `SETEX` / `GET` / `DEL` /
  `KEYS` / `PUBLISH` / `SUBSCRIBE` / `INFO`。`common/.../redis/RedisManager.java` を実読）。
  すべて Garnet の API 互換表で対応済みで pub/sub も既定 ON

**ただし Garnet は Redis の再実装であって Redis ではない。** Dev_Server で先に検証してから
Main / Resource へ広げる。駄目なら WSL2 + Redis へ戻せばよく、**HuskSync 側の設定は変わらない**。

**preflight を「ポートの開閉」から「プロトコルで正体を判定」へ強化**（`lib/DataStore.ps1` 新設）。
外部ツールに依存せず、RESP で `PING` → `INFO server` を投げて Redis / Garnet / Valkey と
バージョンを識別し、MySQL は**認証前に平文で届く初期ハンドシェイク**からバージョン文字列を読む。
「6379 は開いているが RESP を喋らない」「パスワード認証が有効」も区別して報告する。

- `backup.ps1` をネイティブ (`mariadb-dump.exe` + `--defaults-file`) と WSL の両対応に。
  **`MysqldumpPath` が設定されているのに見つからないときは黙って WSL へ落ちない**
  （設定と違う経路でバックアップされているほうが事故として重い）
- `run-selftest.ps1` に 5 本追加して **19/19**。偽サーバを別スレッドで立てて RESP と
  ハンドシェイクの解釈を実測する。**この追加テストが実バグを 1 件検出した**:
  PowerShell の配列スライス `$buffer[0..$n]` は `Object[]` を返すため
  `List[byte].AddRange` に渡せず、握り潰されて「RESP を喋っていない」に化けていた
  （`MemoryStream` へ変更）。サービス名の部分一致で `GameInputRedistService` を
  Redis と誤検出していたのも同時に修正

### 2026-07-27 — 資源サーバ分離の追補（HuskSync 起動失敗の切り分け / `preflight.ps1` / 実レイアウト反映）

ユーザーが実環境の構築に着手し、`D:\game\minecraft\PaperServer\Velocity_for_TF\` に
**Velocity（`velocity-4.1.0-SNAPSHOT-9.jar`）+ Main_Server + Resource_Server + Dev_Server の
3 バックエンド**を作成済み（Geyser / floodgate / Via\* はプロキシへ移設済み、
Resource_Server の plugins は `PLUGIN_MATRIX.md` の推奨構成どおり）。作業書が想定していた
2 バックエンド・`TrinityForge-Res` 命名・RCON 25575/25576 とは食い違っていたので、実態へ合わせた。

**HuskSync の起動失敗（ユーザー報告）の原因**

`FailedToLoadException` → `ConnectException: Connection refused: getsockopt` は
**ビルドした jar の問題ではない**。この時点でプラグインは読み込まれ、config.yml も生成され、
shade された依存も解決できている。落ちているのは DB 接続だけで、実測すると
**3306 も 6379 も待ち受けておらず、WSL には Linux ディストロ自体が入っていない**
（`wsl --list --verbose` に `docker-desktop` のみ）＝ RUNBOOK 手順2 が未実施だった。
同時に出る `getRedisManager()` が null の `NullPointerException` は、初期化が途中で失敗した
ときの **HuskSync 側 shutdown 経路のバグ**で無害。

**重要な落とし穴**: HuskSync の enable 失敗は**サーバの起動を止めない**。
ログを読まないと「同期されていないまま運用する」事故になる。

**`ops/scripts/preflight.ps1` を新設**（起動前チェック。実環境で 11 件検出した）

3306 / 6379 の到達性、HuskSync の**生成時既定値の残り**（`root` / `pa55w0rd` / DB 名 `HuskSync`）、
`location: false` / `game_mode: false` / `persistent_data: true`、`ignored_modifiers` の
`trinityforge:*`、**全バックエンドでの `features` 一致**、`forwarding.secret` と各
`paper-global.yml` の一致を検査する。実際に検出したのは
「WSL ディストロなし」「Dev_Server の HuskSync が全て既定値・`game_mode: true`」
「Resource_Server と Dev_Server の `proxies.velocity` が `enabled: false` かつ secret 空」。

**`husksync.config.yml` テンプレートを実生成物から書き直した**。想定していたキー名が複数違っていた
（`redis.credentials.*` の入れ子、`connection_pool.*` の名前、存在しない `features.max_health` /
`features.locked_maps`、`save_on_death` はブロックでブール値でない）。あわせて 2 点:

- **`game_mode` の生成時既定は `true`。必ず `false` に変える**（メインの `world` は creative）
- **`ignored_modifiers` に `trinityforge:*` と `arspaper:*` を追記**する。TF はステータスを
  `trinityforge:perk_attr_<stat>`（`PerkAttributeApplier`）と `trinityforge:statmod.<name>`
  （`AttributeApplier`）という `AttributeModifier` で付与し、join と装備変更のたびに
  config から**再計算して付け直す**。スキル Lv はジャンクションで既に共有されているので
  同期は不要で、残すと再計算前の一瞬だけ別サーバの値が乗る

**スクリプト側の変更**

- `Get-OpsConfig` を `Servers` の**キー一覧を走査する**形に一般化（`Main`/`Resource` 決め打ちを撤去）。
  環境変数名はキー名から決まる（`Dev` → `TF_RCON_DEV_PASSWORD`）。`-RequireRconPasswords:$false` で
  RCON を使わない preflight からも読める
- `Resolve-OpsServer` を追加し、`restart-server.ps1 -Target dev` を可能にした（`ValidateSet` を撤去）
- **既定の設定ファイルパスが 1 階層ずれていたバグを修正**。`lib/Common.ps1` 内の `$PSScriptRoot` は
  `ops/scripts/lib` を指すため、`-ConfigPath` を省略すると `ops/scripts/ops-config.psd1` を探していた
  （全スクリプトが明示指定でしか動かなかった）
- `run-selftest.ps1` に 4 本追加して **14/14**（設定読み込み 3 本 + preflight の HuskSync 検査 1 本）

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
