# TrinityForge — 現役記録（残タスク / 既知の問題 / 現在の状態）

**この文書が唯一の現役記録です。** 残タスク・バグ・現在の状態はここだけを見て、ここに追記する。

- **過去の作業記録は `reports/ACTIVE_RECORD_ARCHIVE.md`**（2026-08-04 に切り出した。解決根拠と失敗の型を辿る用）。
- `docs/` 直下の `*_SPEC.md` は仕様、`docs/config-reference/` は config キーの一次情報、
  `docs/agent-context/` は「触る前に知らないと黙って壊す知識」。`docs/archive/` は**失効した設計文書**。
- **新しい日付入りレポートを増やさない。** 2026-07-27 に 47 本まで膨れて全部 stale になり、
  2026-08-04 に削除した（git 履歴にはある）。

## 運用ルール

- 項目を閉じるときは行を消さず、`~~取り消し線~~ → 解決日と根拠` にする。
  §2〜§4 が長くなったら、**閉じた行をアーカイブの §A へ移す**（消さない）。
- 作業記録は §7 に**上へ**追記する。長くなったら古い順にアーカイブの §B 先頭へ移す。
- **着手前に必ず実コードで裏を取る。** 棚卸しをすると毎回「記録が腐っていた」が出る
  （2026-07-27 に 15 件、2026-08-01 に 5 件、2026-08-03 にさらに数件）。
- **「機構は実装済みで config に値が入っていないだけ」は残タスクにしない**（→ §5）。
- **直したと書く前に「修正前に戻すと落ちるテスト」を示す。** 証明のない修正は W-23 に積まれる（現在 12 件）。

---

## 1. 現在の状態（2026-08-04 17:5x 実測）

| 対象 | 状態 |
|---|---|
| `dev` HEAD | `4000f9d`（`origin/dev` と一致）。直近は 別セッションの `0b174cb`〜`4000f9d`（消費ソースEXP / 1SPあたりレベル間隔）と `67b2c02`（リポジトリ整理） |
| **他セッションが並行作業中** | **未コミット 35 ファイル前後**（`items/catalog.yml` ほか yml、`resourcepack/*`、`docs/agent-context/config-editor.md` など）。**この整理では 1 つも触っていない。** 触る前に `git status` で持ち主を確認する |
| worktree | **0 本**。2026-08-04 に 47 本（7/31〜8/1 の波の残骸・うち 31 本が未コミット変更つき）を整理した。差分は `backups/orphan-worktrees-20260804/<名前>/tracked.patch` と `untracked/` に退避済み。`work/*` `worktree-*` の 90 ブランチも削除（全て `dev` へマージ済みを確認してから） |
| 実サーバ | **稼働中**（25565 / 25566 / 25567 が LISTEN、17:45 実測）。**稼働中に jar を差し替えると必ず `NoClassDefFoundError`**。配備は停止後 |
| `TrinityForge-all.jar` | `TrinityForge/build/release/`、16,259,925 bytes、**2026-08-04 17:42**（別セッションのビルド） |
| `ArsPaper-1.0.0.jar` | `fork-handoff/arspaper/fork/build/libs/`、1,114,327 bytes、**2026-08-04 17:42** |
| `EliteMobs.jar` | **`build/libs` に jar が無い＝配備前に要ビルド。** 必ず全同梱 uberjar（`*-min.jar` は `NoClassDefFoundError` で起動不能） |
| テスト | **最終実測 2026-08-04（別セッション）**: TF **3713 / 1 failed / 2 skipped**、ArsPaper fork **325 / 1 failed**、config-editor **1089 / 12 failed**。**失敗はいずれも他セッションの未コミット yml 由来**（TF = `ShippedAchievementTreeTest`／fork = `ThreadRitualRecipeConfigTest`／editor 12 件は着手前と同一）。**skip 2 が正常値**（`OfflineMobImportRunner` / `NativeProgressionStabilizationContractsTest#prestigeRefundUsesLiveYamlCost`）。MockBukkit は未実装 API を SKIPPED に化けさせるので、**skip がこの 2 件から増えていないことが隠れ失敗ゼロの判定基準** |
| **整理後の再実測** | **していない。** 同一ワークツリーに他セッションの未コミット変更があるので、今回すのは他人の WIP の測定になる。整理はドキュメントとローカル生成物だけで、Java / 出荷 yml には触れていない |
| 配備 | 2026-08-04 の各バッチは**未配備**。`ops\launch\stop-all.cmd` → `ops\launch\deploy.cmd --config` → `start-all.cmd`。**`--config` を付け忘れると ArsPaper の `materials.yml` が無言で反映されない**（Ars 側にキー追記機構が無い） |

---

## 2. 残タスク — 判断待ち（こちらでは決められない）

| # | 内容 | メモ |
|---|---|---|
| ~~**J-11**~~ | ~~**フォーク 2 本を push するか**（3 択: jar を除外して push / jar も含めて push / push しない）~~ | **2026-08-04 決定: 「jar を除外して push」。** ArsPaper `feat/trinityforge-fork` は `be974a4` を push 済み（`libs/TrinityForge.jar` は未コミットのまま＝TF 本体 jar は再公開していない）。副作用は既知で受容: **クリーンクローンでのフォークビルドは新 API の 4 引数呼び出しで落ちる**（この環境のローカル jar は新 API でビルド済みなので配備には影響しない）。EliteMobs 側は同じ方針が適用できるか未確認なので**まだ push していない** |
| **J-12** | **`_Nx` 圧縮素材の EXP 規約**（線形か指数か） | 圧縮 1 段 = 9 倍。EXP をそのまま 9 倍にすると圧縮での EXP 稼ぎが成立する。2026-08-02 から持ち越し |
| **J-13** | **`apex-brew` の SPEED `amplifier: 2`（速度III）** | バニラ上限（速度II）超え。カスタム醸造なので意図的とも取れる。LOW |
| **J-14** | **「テクスチャ準備済み」18 種の置き場所**（→ K-15） | ガチャ券 6 種 / ミート・ダート・ベジタブルコア / モブ素材 13 種が `resourcepack/` にも配備先パックにも無い。**置き場所を聞かないと着手できない** |
| ~~**J-15**~~ | ~~**生産ステ「コート上限」(`coating_charges_bonus`) を消すか**（2026-08-05 バッチ6・報告「アイテム個別にコーティング回数あるしステータスは要らないのでは」）~~ | **2026-08-06 ユーザー決定: 「そのまま残す」（選択肢①）。コード・config の変更なし。** `skilltree/alchemy.yml` の3ノード（合計 +18）と `WeaponCoatingListener` の加算はそのまま。以下は判断材料として残す: **消すと skilltree からコート回数を伸ばす軸が無くなる**。現状 `skilltree/alchemy.yml` の3ノードが 3/10/5 = 合計 +18 を配っており、`WeaponCoatingListener` は `maxStacks = min(base, 素材上限) + パーク分 + アイテム個別分` で足している（アイテム個別とは別枠で加算＝重複ではない）。選択肢は ①そのまま残す ②ステだけ消して alchemy.yml の3ノードを別効果へ差し替える ③コート回数はアイテム個別だけにして skilltree からは伸ばせなくする |
| ~~**J-16**~~ | ~~**杖 cane→wand 改名で、流通済みの杖にエイリアスを用意するか**（2026-08-05 バッチ6・`d079bd0`）~~ | **2026-08-06 ユーザー決定: 「入れない」（流通量が少ないため）。コード変更なし。** 以下は受容した副作用: カタログにエイリアス機構が無いため、既存の杖の PDC `catalog_id=<旧id>` は解決できなくなる。装備性能は ItemStack に焼き込み済みで変わらないが、**カタログ由来フレーバー lore の再解決が止まり、コレクション図鑑の発見済みフラグが杖だけ未発見に戻る**（拾い直せば再登録）。旧id→新idのエイリアスを入れるかは要判断 |
| ~~**J-17**~~ | ~~**`attack-power` 上限 127,500 を上げるか（＝メイスを「もっと遅く・もっと重く」に戻せるか）**（2026-08-05 W-32/W-34 の副産物）~~ | **2026-08-06 ユーザー決定: 「そのまま」（選択肢①）。`stat-caps.yml` は触らない**＝メイスは `attack-speed` 0.88 のまま。以下は判断材料として残す: 上限は**最終合算値**に効くので、`attack-speed` が遅い武器は同じDPSを出すのに `attack-power` が反比例で必要になり、遅すぎる武器は上限と両立しない。**メイスを `attack-speed` 0.408 のまま剣の 95% に載せるには単品最大 267,108＝上限の ×2.09 が必要**で、超過分は表示だけで実効ゼロになる。今回は上限を触らず `attack-speed` を 0.88 へ上げて解決した（メイスは大斧 0.833 に次いで2番目に遅い武器になった）。選択肢は ①そのまま（現状） ②上限を 305,000（`stat-caps.yml` 本文の慣習「単品最大×1.1425」）へ上げてメイスを 0.408 へ戻す。**②を選ぶ場合の注意**: 上限は今すでに最上位の重武器で効いており（`binder_greataxe` 単品最大 111,396 × 職業レイヤ ×1.265 = 140,916 → 127,500 でクランプ）、上げると最上位帯だけ実効+10% 程度の底上げになる。**さらに `combat/stat-caps.yml` は別セッションが `stat-caps: {}`（全上限削除・コメント40行消失＝config-editor 保存事故の形）で未コミット変更を持っている**ので、触る前にそのセッションと衝突しないことを確認する（現に `ShippedStatCapsDriftTest` が3件落ちている） |

---

## 3. 残タスク — 手を動かせば終わるもの

| # | 内容 | 前提 |
|---|---|---|
| **W-14** | **カタログレシピ 1 件が起動時に無言で登録失敗**（`custom list member 'iron_axe_tool' is unknown`） | **リポジトリ側の不具合ではない。** その ID はリポジトリ全体に 1 件も無く、**サーバ上で editor から作られた互換リスト**が持っている。意図された ID はおそらく `iron_axe_tf`。修正は配備先 `Main_Server/plugins/TrinityForge/items/material-lists.yml` をユーザーが editor から直す作業 |
| **W-15** | **HIGH: WEAPONSMITH の追加取引 2 件が実行時に丸ごと消える**（`tf_scrap` / `tf_core_jewelry`） | `economy/villager-trades.yml`。職業まるごと死んでいる。柱4 の村人取引（2-b）未実装と同根 |
| **W-16** | **3 チケットの `PENDING_CMD_ASSIGNMENT` を外す** | CMD は**別セッションが割当済みだが未コミット**（`role_reselect_ticket`=NAME_TAG#10 / `stat_reroll_ticket`=RABBIT_FOOT#16 / `quality_upgrade_ticket`=HEART_OF_THE_SEA#5446）。割当をコミットする人が `CatalogVanillaOperationPolicyConfigTest.PENDING_CMD_ASSIGNMENT` を外す |
| **W-17** | **CMD・モデル未割当のアイテム** | 階梯ソースリンク 20 件（`_ii`〜`_v`）に `custom-model-data` が無く無印と同じ見た目。`BEACON#500001`（`infinity_source_core`）も素のビーコン。**リソースパック側の残タスク** |
| **W-18** | **`gold_test` の撤去と参照の掃除** | 出荷 `catalog.yml` にテスト用アイテムが残り（`GOLDEN_SWORD`・金インゴット 1 個の有効レシピ付き）、`catalog-combat-content.test.js:20` が**これ 1 件で落ち続けている**。`GOLDEN_SWORD#59` は定義だけ消えて `_editor.itemTabs` / `_editor.categories` に参照が残っている |
| **W-19** | **テストの名前と実挙動が食い違う 3 件** | ①`CatalogRecipeRegistrarNetheriteTest` = MockBukkit にバニラのスミスレシピが無いから通っているだけで、実サーバでは `NetheriteUpgradeGuard` が 12 件中 7 件を除外し **5 件しか登録されない**。②`ShippedBossStrengthDriftTest` = ランプ定数をハードコードしていて `mob-import.yml` を読まない（ランプを触っても緑）。③`ShippedBrewDeadEndMaterialTest` = `BrewPotionMixRegistrar.plan()` を通しておらず「yml に書いてある」までしか固定していない |
| **W-20** | **`docs/config-reference/` の stale 2 件** | ①`combat/stat-caps.md` は 137500 / 単品最大 120,349.8 と書いてあるが実際は 127500 / 111,395.9（**値をアサートするテストが無い**のでまた腐る）。②`stats/skill-exp.md` に `power.levels-per-skill-point` と `ars-smithing.exp-per-source` が未収録（yml 本文コメントは editor 保存で消えるので docs 側が正） |
| **W-21** | **`generate-item-stats.js` が出荷 `item-stats.yml` を丸ごと上書きする** | `tools/config-editor/scripts/`。U5/U6 の変更も日本語コメントも持たないので、**実行すると無言で巻き戻る** |
| **W-22** | **`em_id_enchantment_challenge_1〜9` の 9 ボスが特殊攻撃ゼロ** | `mob-overrides.yml`。付与できたのは 18 ダンジョン（設計書の「24」は設計書側の誤り） |
| **W-23** | **「直したが証明されていない」12 件に回帰テストを付ける** | 戻しても全テストが緑のまま通る＝次の誰かが黙って壊し直せる。一覧はアーカイブの「2026-08-03 全面監査」の節。**特に悪い例**: `MeleeChargeMultiplierTest.java:129` は javadoc に反して `0.1, 1.6` をリテラルで渡しており `damage.yml` を読まない。**ソース文字列走査型のテスト**（`Files.readString` + `contains`）も「呼び出しを別の門の内側へ移す」再発を素通りさせる |
| **W-24** | **EM の `elite-drop-sources` を稼働サーバで手書きする** | `TrinityForgeConfigMigration` は**トップレベルキー単位でしか差分を検出しない**ので、セクションが既にある稼働サーバへサブキーの既定値変更が届かない。`plugins/EliteMobs/trinityforge.yml` の `currency-shower` / `boss-unique-loot` / `treasure-chest-loot` / `arena-loot` を false にする（運用作業） |
| **W-25** | **原木の解凍レシピがバニラと競合しうる** | `reversible` の逆レシピが「原木 1 個 → 板材 4 個」と同じ盤面にマッチする。既存 `oak_wood_*` も同じ状態なので現状踏襲した。**実機で「圧縮原木を置くと板材になる」なら**逆レシピの登録順かレシピ形状を見直す |
| **W-26** | **config-editor の既知 fail** | 最新実測 12 件。**すべて他セッションの yml 数値編集由来**（`mining-gimmick` の段階 1/2/3 vs 1/3/5、防具 Lv.25 vs 30 など）。HEAD 版へ戻しても同じ 12 件が落ちることを実走で確認済み。持ち主の作業が確定してから再判定する |
| **W-27** | **運用作業（エージェントからは実行できない）** | ①`/em language japanese`（ダンジョン内の敵の発言が英語）②Nightbreak コンテンツの取得（エンチャント試練 11-20 / ユグドラシルに入れない。`gates.yml` 側は 61 件とも正しい）③実機確認: 虚空右クリックでガチャ券・鍵が使えるか |

### 実サーバ報告バッチ5（2026-08-04 受領）の未着手分

**2026-08-05 追記の経緯**: このバッチは 24 件あり、うち 10 件を 08-04 夜〜08-05 未明に修正・配備した。
しかし**残る 11 件をここに登録しないまま「配備完了」と報告した**ため、ユーザー側からは
バッチ全体が終わったように見えていた。**受領した項目は着手前に全件ここへ開いた行として登録する**
（→ §5 の「バッチ受領時は全件を先に登録する」）。

| # | 内容 | 前提 |
|---|---|---|
| ~~**W-28**~~ | ~~**`/tf menu` と `/tf role set` を削除し、ロールの確認・変更を `/tf status` GUI へ統合する**~~ | **2026-08-06 実装済み。** ①`/tf menu`（`MainMenuGui` / `MainMenuLayout` とテスト2本）と `/tf role set`（引数版・GUI版の両方）を削除。②`/tf status` の**スロット38に「職業(ロール)」アイコン**を追加（現在の戦闘職/補助職・変更可否・クリックで `RoleSelectGui`）。**可否の表示は `RoleChangeService#denyReasonForCombat/Support` の文言をそのまま出す** — 待ち時間や「券が必要」を画面ごとに組み立てると表示と実挙動が食い違うので、`RoleSelectGui` の lore も同じメソッドへ寄せた（`waitMillis`＋`gateNotice` の2引数を1本の `notice` に統合）。③**config を `role-change.allow-command` → `allow-change` へ改名し、`false` の意味を変えた**: 旧 `allow-command: false` は「一切変更不可」で**初回の就職すらできなかった**（出荷値が false だったので実際にそうなっていた）。新 `allow-change: false` は「**空の枠への初回就職だけ無料、それ以降は転職の証（`role_reselect_ticket`）を手に持って右クリックしたときだけ変更可**」。**解除も塞ぐ**（枠を空にできると初回無料を無限に再利用できて券が要らなくなる）。旧キーは `allow-change` 未指定時のフォールバックとしてだけ読む（配備済み config が無言で「許可」へ反転しないため）。④config-editor は「職業変更を許可するか」のトグルへ差し替え（保存時に旧キーを削除して二重化を防ぐ）。⑤`/tf role` は確認のみ・`/tf role clear` は解除のみ残す。プレイヤー wiki の生成側も `/tf status` 経由の導線へ更新。回帰は `RoleChangeTicketOnlyModeTest` 5件 + `StatusGuiRolePanelTest` 3件 + `RoleBuffsConfigTest` の旧キーフォールバック |
| ~~**W-29**~~ | ~~**`/tf skills` GUI の最下段左端を時計アイコンで固定し、現スキルツリーのパーク一覧 ON/OFF を切り替える**~~ | **2026-08-06 実装済み。** ①最下段左端（スロット45 = `SkillTreeOverviewLayout.PERK_LIST_SLOT`）を時計（`CLOCK`、`toggle-perk-list`）で固定。**その枠は近傍ツリー選択バーが使っていた**ので、選択バーを8枠（offset -4..3）→**7枠（-3..3）**へ縮めた（選択中は変わらずスロット49の中央）。時計と `toggle-view`(53) は `renderModeButtons` が**全モードで同じ位置に描く**ので、モードを跨いでも押す場所が変わらない。②パーク一覧は全ツリー一覧と**同じ格子・同じ体裁**（`SkillTreeOverviewLayout` の行1-4・列2-6）で並べ、解放状態はツリー本体と同じ配色・同じロックテクスチャ。クリック（`jump-perk`）で `canvas.nodes().get(perkId).point()` を中心に据えて通常モードへ戻る。③**ページ送りを付けた**（`PAGE_PREV_SLOT`=19 / `PAGE_NEXT_SLOT`=25、行き先が無い側は描画しない）: ツリー数16は格子容量20に収まるが**パーク数は POWER で36**（config 35ノード + generator が必ず足す合成ルート `<compact>_perk_root`）で、黙って切り落とすと「一覧に無いパークがある」ことに気づけない。④モードは `boolean overview` → **3値の `Mode`（DETAIL/OVERVIEW/PERK_LIST）**へ（boolean 2本だと「両方 true」という存在しない状態を型が許す）。再描画は `reopenNextTick(player, session, pendingPerkId)` に集約し、モード・中心・ページの書き写し漏れを機構で防いだ。回帰は `NativeSkillTreeMenuPerkListTest` 4件（時計の固定・一覧モードでも同じ位置・全パーク列挙＋中央へジャンプ・**容量超過分が2ページ目で必ず見られる**）+ `SkillTreeOverviewLayoutTest` のページ分割 + `SkillTreeGuiVisualsTest` の時計材質 |
| ~~**W-30**~~ | ~~**`/tf skills` と `/tf achievement` の GUI 状態（ページ/スクロール位置/選択タブ）を再オープン時に復元する**~~ | **2026-08-06 実装済み。** 両GUIが `Map<UUID, View>` を1本持ち、**画面を描くたびに上書き・引数なしの `open(player)` で復元**する。`/tf skills` は ツリー / スクロール位置 / モード（通常・全ツリー一覧・パーク一覧）/ ページ、`/tf achievement` は スクロール位置 / 選択中の系統。**⚠ `pending`（解放・解除の確認待ち）は意図的に持ち越さない** — 持ち越すと「閉じて開いて1クリック」で確定する経路ができ、誤爆でSPや報酬を消費する（`View` は `Session` から pending を落とした型にしてうっかり足せないようにした）。**ログアウトで捨てる**（`PlayerQuitEvent`）: ログイン跨ぎは PDC が必要だが、PDCキーはバックアップ網羅性テストの対象で保守コストが増える一方、この状態は「その場の作業位置」でしかないため。`/tf skills <skill>` のように**別ツリーを名指ししたときは復元せずそのツリーの起点から**開く。回帰は `NativeSkillTreeMenuPerkListTest` 3件（スクロール位置の復元・パーク一覧モードとページの復元・名指しの別ツリーは復元しない）+ `AchievementGuiLayoutTest` 2件（位置と選択系統の復元・**確認待ちを持ち越さない**） |
| ~~**W-31**~~ | ~~**`/tf achievement` の UI をスキルツリーと同じ配置に揃える**~~ | **2026-08-06 実装済み。** ①**ツリーをルートから上へ伸ばした**（`AchievementLayout` の `y = depth*STEP` → `-depth*STEP`）。y の意味（下方向が正）は変えていないので**明示 `coords` は書いた値がそのまま効く**。②**8方向の視点移動をキャンバスの四隅・辺の中央（スロット 0/4/8/18/26/36/40/44）へ移した** — `NativeSkillTreeMenu.NAVIGATION_SLOTS` と同じスロット番号。③**最下段 45-52 を「系統（ルート実績）切替バー」にした**（スキルツリーのスキル選択バーと同じ「選択中を中央 49 に置いて巡回」動作）。達成状況の本は 49 → **53**（バー右端の固定枠）へ移動。④**バーに並べるのはルート実績だけ**（`AchievementCanvas.branchHeadIds` = `parent` 未設定／自分自身／存在しないIDを指すもの）。**ユーザー確定（2026-08-06）**: 最初は「ルート＋そのルートの子（章の先頭）」を並べたが、**「真のルートだけ並べる」で差し戻された**。出荷configの真のルートは `main` 1件だけなので**バーはボタン1個になるが、それが仕様どおり** — 「1個しかないから壊れている」と判断して子を足し戻さないこと。系統を増やすなら `achievements.yml` 側でルートを増やす。あわせて**バーはルートが8枠に収まるうちは左詰め**にし、巡回（選択中を中央49）は9件以上のときだけにした（少数で巡回すると同じルートが8枠に並ぶだけで切替として読めない）。⑤バーのボタンは PDC キーを `achievement_head` に分けた（`achievement_focus` と同じにすると、**達成済みの起点をバーで選んだだけで解放が確定してしまう**）。回帰は `AchievementGuiLayoutTest` 7件（矢印のスロット番号・**最下段はルートだけの左詰め**・9件以上で巡回・押した系統が中央へ来る・**バーのクリックが claim へ流れない**・W-30 の復元2件）+ `AchievementCanvasTest` の伸びる向き2件・系統バーの並び3件。**⚠ 残り1点（コミットできない）**: `achievements.yml` のヘッダに `5本の道が下へ伸び` と `coords:` の説明が残っている（向きが変わったので文言が古い）。**このファイルは別セッションが未コミットで大改修中**（`custom:gacha_ticket_1`→`gacha_ticket_0` 等 244/139 行）なので、パス単位でしか add できない運用では巻き込みになる。**そのセッションが commit したあとで、ヘッダ2箇所を「上へ伸びる／子の y は親より小さい（深さ1で -2）／明示 coords は書いた値がそのまま効く」に直すこと。** 挙動・正典ドキュメント（`docs/agent-context/progression-skilltree.md`）は既に更新済みなので、残りはコメント文言だけ |
| ~~**W-32**~~ | ~~**メイスのダメージに落下高さボーナスが乗っているか確認し、乗るなら火力をそれ込みで調整する**~~ | **2026-08-05 修正済み。落下ボーナスは乗っており二重加算もしていない**（`MaceSmashDamage`。+4/blk×3 → +2/blk×5 → +1/blk、Density = level×0.5×落下距離）。ただし**バニラのHP単位なので最大でも数十**＝Lv100 の桁（1発 12万超）に対して無視できる量で、「乗っているのに体感できない」が実態。メイスの空中個性は既存の `power-attack-damage: 0.35`（空中で+35%）が担うので、**接地パリティを剣の 85%（空中 約115%）に置いて校正**した。あわせて `attack-speed` 0.408 → 0.88（理由は下の W-34 の上限の話） |
| ~~**W-33**~~ | ~~**杖の火力が他武器に比べて低すぎる**~~ | **2026-08-05 修正済み。剣の 4〜6% だった**（`attack-power` ×7.4〜11.1 へ引き上げ）。杖は近接で振らず詠唱で撃つので実効レートは `min(attack-speed,2.0)` ではなく **`1/item-cooldown`**（`damage.yml` の `magical.attack-power-scale: 1` が杖の `attack-power` を魔法基礎ダメージへ1:1加算する）。「控えめ」の意図を残して**剣の 47.5%** に置いた（50% にすると `magic_wand` の単品最大が `attack-power` 上限の余裕を食い潰す） |
| ~~**W-34**~~ | ~~**鎌の出血など、武器 tier・攻撃力・防具の防御力に見合っていないステータスの洗い直し**~~ | **2026-08-05 修正済み。tier 期待値テーブルを作った結果、個別の値ではなく武器種の校正そのものが噛み合っていなかった**。①`attack-speed` の段ごとのばらつき（戦斧 0.323/0.408/0.493+1.003、鎌 1.12/2.12/3.12/4.12、槍 1.38→0.71 の逆進行）を武器種ごとに統一 ②**2.0 超は無敵時間 10 tick で切り捨てられる死に設定**（4本あった）→ 2.0 へ ③出血は総DPSの 1% で個性が数字として存在せず、しかも `cryocore_greataxe`(4,164) が鎌(2,736) を上回る逆転 → 鎌を「15%、ただし上限 4500 で打ち切り」へ ④`revolution_bow` だけ 2026-08-02 の「遠隔の近接 AS は 0.1」から漏れて **弓が同帯最強の近接武器（剣の119%）**になっていた ⑤`koujien`（BOOK 1個で作れる）が剣の 125% ⑥接尾辞に武器種を持たない `Winter_Grim_Reaper` / `fnis_peccati_profundi`（どちらも鎌カテゴリ）が検査ごと素通りしていた。**⚠ 最初の適用は `stat-caps.yml` の上限を無視していて、メイスの `attack-power` が上限の ×2.09、鎌の `bleed-damage` が ×5.91（＝表示だけ上がって実効は伸びない死に設定）になっていた**ので全部やり直した。**防具側は外れ値なし**（34系列すべて 15:40:30:15、`turtle` はバニラ同様ヘルメット専用、レベル逆転18組はすべて魔法防具が `phys-flat-defense` を落として物理防具にはゼロの `magic-flat-defense` を持つトレード）。回帰は `WeaponTierParityTest` 9件 + `WeaponDpsParityTest`。恒久知識は `docs/agent-context/combat.md` |
| ~~**W-35**~~ | ~~**武器ごとの攻撃リーチを再設計する**~~ | **2026-08-05 実装済み。ユーザー決定「剣=バニラの剣の基準で」**（＝剣は `attack-reach` 0 でバニラの `entity_interaction_range` 3.0 そのまま）。表（バニラ 3.0 への加算 / 実効）: 短剣 -0.5/2.50、**剣・メイス・弓・弩・杖 0/3.00**、戦斧 +0.2/3.20、レイピア +0.3/3.30、ウォーハンマー +0.4/3.40、鎌 +0.5/3.50、大剣・大斧 +0.6/3.60、トライデント +0.8/3.80、槍・ハルバード +1.0/4.00。`hunter_javelin` の 1.4/4.40 は 2026-08-02 に「game 内最長」として決めた一点物なので据え置き。リーチ差の相殺は W-34 の実効DPS帯（同系列の剣に対する比）で行っている |
| **W-36** | **トライデント系・槍系が三人称視点で 2D アイテムモデルになる** | **2026-08-05 原因確定: パックソースは正しく、配信中の zip が古い。** `server.properties` の `resource-pack-sha1=5b251cb3676df2b681616447cfaf321bda5f85ab` は `resourcepack/dist/TrinityForge-Pack.zip`（作業ツリー版、08-04 10:43）と**ハッシュ一致**する。その zip を展開すると `trident.json` の CMD 121/122/174-181 と `netherite_spear.json` の 118/119、`diamond_spear.json` の 50 に `display_context` 分岐が無く、全コンテキストで平面モデルになる。**ソース側（`assets/minecraft/items/*.json`）は 08-05 16:12 に再生成済みで 13 件とも正しい**（生成側の修正は `c5754d7`、08-03）。**残っているのは zip の再生成と GitHub Release への再発行、`resource-pack-sha1` の更新だけ**。リソースパックは別セッションが作業中（`cmd-registry.json` と dist zip が未コミット）なので、**そのセッションが publish する時に必ず再生成すること** |
| ~~**W-37**~~ | ~~**鍵を使ったときに出る本のタイトルが内部 ID 表記**~~ | **2026-08-05 判明: コードの不具合ではなく配備漏れ。** 潜入確認 GUI の `KNOWLEDGE_BOOK` は `DungeonGate#displayNameOrWorld()` を出しており、リポジトリの `dungeon/gates.yml` は 61 ゲート全部に `display-name:` を持っている（`15236bc`、2026-08-02）。**配備先の `gates.yml` は 08-01 16:52 のままで `display-name` が 0 件**なので、ワールド名（＝ゲートID）へフォールバックしていた。`ops\launch\deploy-config-head.cmd` で config を配備すれば直る（サーバ停止が必要）。**同型で本当のコード不具合だった `/tf settings` の称号ボタン ID 表示は `c7b2d94` で修正済み** |
| ~~**W-38**~~ | ~~**ツルハシに射撃ダメージが付くなど、不適正なツールへエンチャントが付与される**~~ | **2026-08-05 修正済み（`0592838`）**。金床のオーバーエンチャント経路が候補エンチャントを `canEnchantItem` で絞っていなかった。本(`EnchantmentStorageMeta`)は素通し、それ以外は対象判定を通す（`OverEnchantAnvilTargetTest` 3件） |
| ~~**W-39**~~ | ~~**ネザライト化するとエンチャントが剥がれる**~~ | **2026-08-05 修正済み（`0592838`）**。`CatalogSmithingListener` が結果アイテムを組み直す際に元アイテムのエンチャントを引き継いでいなかった。上限超えレベルもそのまま維持する（`canEnchantItem` で絞らない理由は同メソッドの javadoc: 杖など素材が対象外のカタログ品があるため） |
| ~~**W-40**~~ | ~~**クリエイティブインベントリのタブにカスタムアイテムが出ない**~~ | **2026-08-05: Java版ではサーバ側から不可能**（クリエイティブタブはクライアントのアイテムレジストリから組まれ、TF品はCMD付きバニラアイテム）。同じ用途を `/tf catalog` GUI で満たした（`03b5265`。クリエイティブ中または `trinityforge.catalog` 権限。`_editor` の分類でタブ分け・未分類は受け皿タブへ回収） |
| ~~**W-41**~~ | ~~**反射のダメージが異常に大きい**（2026-08-05 追加報告 → 仕様変更）~~ | **2026-08-05 修正済み（`2c478af`）**。真因は旧仕様が `reflect-percent × 被ダメージ` を**毎回**返していたこと（被ダメージが大きいほど反射も青天井）。ユーザー確定仕様「反射率＝被弾時の発動確率／ダメージ＝武器の通常攻撃ダメージ（素殴り）」へ置換。`reflect-flat` は仕様文に無いが既存装備が対で配っているため「発動時の上乗せ実数」として残した |
| ~~**W-42**~~ | ~~**スレッドのステ lore の体裁とフォントを通常の装備と同じにする**（2026-08-05 追加報告）~~ | **2026-08-05 修正済み（TF `0ae78ef` / ArsPaper `6e64216`）**。スレッドは差し込み専用の `LoreComposer#statLines`（区切り線なし・品質行なし・全行fixed色）を使っていた。装備の lore 経路そのものを呼ぶ `ItemAssembler#statLoreBlock` を追加し、フォークの3経路（生成/返却/振り直し）を `ThreadItem#fullLore` のまるごと組み直しへ一本化（区切り線は幅可変なので部分書き換えと併用できない） |
| — | **スポナー回収時の「コマンドが実行される可能性があります」はバニラ仕様**（2026-08-05 質問） | **修正対象ではない**。TF は `BlockStateMeta` でスポナーの block entity を丸ごとアイテムへ載せている（中身・遅延・spawnPotentials を保つため必須）。バニラは `minecraft:block_entity_data` を持つアイテムに**OP にだけ**赤字の警告 tooltip を出す仕様（[Data component format](https://minecraft.wiki/w/Data_component_format)）。消すには block entity を載せない＝中身が失われるので、そのままにする |
| ~~**W-43**~~ | ~~**元からある(バニラの)防具以外、木材で修繕ができない**（2026-08-05 追加報告）~~ | **2026-08-05 修正済み（`7af192a`）**。優先度の取り合い。`WoodRepairListener#onPrepareAnvil`(HIGH) が入れた結果を `CatalogVanillaOperationGuardListener#onPrepareAnvil`(HIGHEST) が `setResult(null)` で潰していた。TF 装備はほぼ全部カタログ品なので金床の木材修繕は常に死んでおり、カタログ品でない=バニラ装備だけが通っていた。ガードの除外条件に「解放済みプレイヤーの木材修繕」を追加（未解放でも許可すると圧縮木材がバニラの修理素材として食われる穴になるので効果の保有まで見る） |
| — | **「EXPを保存する機能」の使い方**（2026-08-05 質問） | **不具合ではない**。エンチャントツリー B-3「EXPフリーザー」（Lv60、`feature:xp-bottle-store-unlock`）を取得後、**バニラの経験値瓶を手に持って** ①スニーク＋右クリック＝格納（段1で 100EXP／瓶1本）②格納済みの瓶を通常右クリック＝取り出し（段1は返却率 50%＝目減りあり。設計メモ「経験値増殖の観点からデバフ必須」）。値は `stats/fishing-gimmick.yml` の `xp-bottle-store`（段2〜4 は value 2/3/4 のノードを置いたときに到達し、200/300/400・60/70/80%）。格納済みの瓶は誤投げ防止で常にクリックを食う |

---

## 4. 既知の未修正の問題・弱点

いずれも**意図的に許容している**か、**直すには判断が要る**もの。新規に見つけたバグはここへ足す。

| # | 内容 | 判断 |
|---|---|---|
| K-1 | **同一地点EXP逓減が友好モブの養殖場を数えない** | カウンタはバニラEXP書き込み経路で回るため、レベル帯の対象外である C 群（友好モブ 39 種）には反応しない。TT の本命ではないので許容 |
| K-2 | **ATTRIBUTE チャネルの上限は「実効値」の上限ではない** | `move-speed` / `attack-speed-bonus` / `attack-reach` / `knockback-resistance` / `max-health` の上限は「**TF が要求する寄与分**」に掛かる。Haste や他プラグインの寄与は含まない。`PerkAttributeApplier` の「ライブ属性値を読まない」原則を守るための意図的な線引き（→ `docs/config-reference/combat/stat-caps.md`） |
| K-3 | **editor で yml 本文のコメントが保存時に消える** | `tools/config-editor/lib/yamlio.js` の仕様。実害は継続中（`item-stats.yml` で 65 行、`collection.yml`・`catalog.yml` でも確認）。**1 件ずつ復元しても次に同じ画面を保存すればまた消える**。恒久対策は editor 側でコメントを保持すること。当面は説明を `docs/config-reference/` へ退避し、yml 本文に長いコメントを書かない |
| K-5 | **ステータスのトリガー/発動制限の宣言** | 段階 1〜4 完了（宣言スキーマ＋拘束テスト＋`/tf stats detail <キー>`）。未宣言キーは理由コメント付きで `LoreConfigDeclarationTest.UNDECLARED_ALLOW_LIST` に固定＝**新しいキーを宣言なしで足すとビルドが落ちる**。プラン全文はアーカイブ |
| K-7 | **`armor-set-bonus` はスキルツリー由来分しか増幅に効かない** | `NativeAttributeBridge.armorAttributesFor` が `perkBuffs` だけを読む。`base-stats.yml` / 装備 / 永続バフ / 役職バフ に置いても効かないのに `/stats` には出る。正すには aggregator→bridge の循環依存を解く必要がある。editor では `NO_OP_BASE_STATS_KEYS` で非表示 |
| K-9 | **`glyph-damage-multiplier-bonus` がどこからも呼ばれていない** | 公開 API `TrinityForge#glyphDamageMultiplier` はあるが `fork-handoff/` 全体で呼び出し元ゼロ。lore に出るのに効かない。配線するかキーごと撤去するかの判断が要る |
| K-10 | **確率ステータスの二重縮小** | `PercentStatNormalize.RATE_KEYS` は集計時点で「20 → 0.2」へ矯正されるのに消費側がさらに 100 で割る＝**実効値が設定値の 1/100**。4 件見つけて修正済み（養蜂の幸運・怪しい塊の再湧き・食料節約・ガチャのレートアップ）。**全 RATE_KEYS の消費側の全数調査は完了していない** |
| **K-12** | **討伐素材 4 件にドロップ元が無い** | `dragon_scale` / `elder_guardian_spike` / `wither_skull_fragment` / `warden_tendril` が `mob-overrides.yml` のどこからも落ちない。**これらを材料にした 18 本のレシピがレシピ帳に出るのに永久に作れない**。`wither_skull_fragment` の lore は「ウィザーが討伐時に落とす」と明言していて実装と矛盾。修正は ENDER_DRAGON / ELDER_GUARDIAN / WITHER / WARDEN へ `add-drops` を足すだけで **editor から完結する** |
| **K-14** | **リソースパック配線が未了（副作用で 7 件が別アイテムに化ける）** | 27 枚の PNG は参照元（`models/item/*.json` と `assets/minecraft/items/*.json`）が無いので描画されない。既存 items json が張られている 7 件は**素のバニラではなく別アイテムの見た目で出る**（ピリジャーの鎧片が「重金属」、深淵/束縛者の弓・メイス・トライデント計 6 種が「ソースジェム武器」）。441 CMD 中 299 が未配線。**統合版のアイテム名は TF パックの `texts/*.lang` では出ない**（GeyserExtra が `custom_items.json` の `display_name` から自動生成する側が効く。詳細 = `docs/agent-context/bedrock-geyser.md`） |
| **K-15** | **「テクスチャ準備済み」18 種がリポジトリにも配備先パックにも無い** | → J-14（置き場所の確認待ち）。ウッドコアとジュエリーコアだけは専用見た目がある |
| K-16 | **ソース生産レートを上げる階梯**（部分前進） | 2026-08-03 に `yield-multiplier`（生成量）と `transfer-multiplier`（転送）で階梯 IV/V まで入り、**素材効率は階梯で改善するようになった**。ただし到達時間が「ソースリンクを何台並べたか」で決まる構造自体は残る。**バッファの int オーバーフロー**は `long` 受け＋`Integer.MAX_VALUE` 飽和で解消済み |
| **K-17** | **ロール限定コンテンツが乏しい** | ロールの効果はステ注入・EXP 倍率・常時ポーション・ヘイト係数の 4 種。2026-08-02 に `use-role` とロール専用装備 8 点が入ったので全否定ではないが、**`use-requirements` の required-role や drops のロールゲートが無い**ので「この職でないと作れない／出ない」は依然として無い |
| **K-18** | **厳選に「育てる」側が無い** | ランダム抽選と振り直しまでは成立。**強化レベル（+0→+20）／サブステ 4 本の段階解放／部位別の主ステ固定／ロック／スコア表示／一括分解**が全て無い。個体ごとに PDC が違うので**スタックせず周回でインベントリを圧迫する** |
| **K-19** | **`stats/stat-caps.yml` が空なので厳選の上限が効かない** | 設計注記は「TF 側 stat-caps が最終上限を担保する」と書いているが出荷 config は空。会心率・貫通・攻撃力に実効上限が無いまま 9 枠フル厳選が通る。上限を書くか `thread-rolls.yml` の幅を見直すかの判断が未着手 |
| **K-20** | **`reality_thread_core`（現実の芯）が説明どおりに使われていない** | 説明・ルート表・ドロップ設計はすべて「振り直しの触媒」なのに、**振り直し儀式の実レシピは別素材（汎用のソースジェム／アメシスト）を要求する**。深部ダンジョン周回と厳選の周回が結び付いていない |
| **K-22** | **束縛者が「最強」として設定されていない／第1目標が実質「最後の目標」** | (1) 束縛者 18 体に `level` / `max-health` / `attack` が 1 つも無く全モブ共通ランプ任せ。(2) `goal_worldbinder` の `parent` が `delve_all_seals`（19 種の印すべて）なので、**束縛者を倒しても他 18 ダンジョンを踏破するまで達成にならない**。(3) `dungeon/gates.yml` の入場ゲートが空で前段の踏破を強制しない |
| **K-23** | **NETHERITE メソッドの 13 件が Bukkit へ一切登録されない**（旧 U7） | `RecipeSpec.isBukkitCrafting()` が workbench/inventory だけ true で `CatalogRecipeRegistrar.java:95` が弾く。リポジトリ全体で `new SmithingTransformRecipe` は **0 件**。影響: `netherite_bow` / `netherite_trident` / `netherite_mace` / `netherite_crossbow` |
| **K-24** | **「攻撃速度を最低値へ」が一部で未適用**（旧 U6） | ×0.85 は HEAVY_WEAPONS 66 件のみ。BOW/CROSSBOW は `attack-speed: 4`（表中最大）、杖（`BLAZE_ROD#400001-400014`）は**キー自体が無く** `AttackSpeedResolver.java:46-48` で TF 不干渉＝バニラ 4.0、TRIDENT は 0.48 |
| **K-25** | **儀式の素材別EXPと `custom:` 素材表の 12/13 行が死んでいる**（旧 U1/N6） | `CraftQualityListener.java:227-236` の `materialToken` が TF カタログ PDC しか読まず Ars の `arspaper:custom_item_id` を読まない。素材表は作業台側にしかない。**2026-08-04 に「消費ソース量に応じた追加EXP」（`ars-smithing.exp-per-source`）が入って定額一辺倒ではなくなったが、素材表を参照しない点は未解決** |
| **K-26** | **召喚馬のインベントリを守る仕組みが無い**（旧 U14） | `SummonSteedEffect.java:85` が `setSaddle(new ItemStack(Material.SADDLE))`。保護は fork に 0 件 |
| **K-27** | **ArmorStand/Mannequin の除外が EXP 経路に無い**（旧 U10） | 除外は `DamagePopupDisplay.java:67` と `FocusHpDisplay.java:191,205` の 2 箇所だけ。現在の唯一の防波堤は `skill-exp.yml` の `unlisted-entity-multiplier: 0`。**未検証: ArmorStand で `EntityDeathEvent` が実際に発火するか**（発火しないなら 1.0 に戻しても再発しない可能性） |
| **K-28** | **レシピブラウザの並べ替えに品質・5 分類が無い**（旧 N4） | `SortMode` は NAME/KIND/LEVEL/DEFAULT のみ、`KindMode` は ALL/WORKBENCH/RITUAL のみ。`RecipeBrowserGui.java`(74KB) に `quality`/`品質` の出現 0 件 |
| **K-29** | **束縛者 HP 29,106 が Spigot 既定 `attribute.maxHealth.max`(1024) を超える** | 現行サーバは設定済みなので出ていないが、**新設サーバでは無言でクランプされる**。運用側の注意点 |
| **K-30** | **GOLD 帯（`use-level-requirement` 35）で重武器が弱い** | H/L が 0.89〜0.95。全帯 `damage-modifier` が揃っている構造的なもの。LOW |
| **K-31** | **U3（ドリリングでツルハシのモーションが消える）= inconclusive** | 描画事象なのでリポジトリ内では確定不能。実機でしか切れない |
| **K-32** | **editor の「素材タブへ移動」が `external-source` を引き継がない** | `moveEntryToMaterials` がエントリを作り直すため。`ShippedCatalogExternalSourceDriftTest` が赤くなって気づけるので未対応 |
| **K-33** | **フォーク側 `ThreadGui#isEffectThread` の PDC 2 種判定にテストが無い** | TF 側からは検証不能。フォークは Bukkit ランタイムを持たないのでソース検査になる |
| **K-34** | **editor のフォーム欄内に横あふれが 11 件残っている** | `ops/scripts/editor-overflow-audit.mjs` が 112 通り（8 幅 × 14 画面）で検出。**すべて `mainOver` 0 = ページ本体は横スクロールしない**ローカルなあふれ（フォーム欄内で 22〜48px）。箇所はカタログ武器/素材・モブ定義・スキルEXP獲得。レスポンシブ化時点（`fdb364e`）では 0 件だったので、その後 dev に入った 20 コミットぶんの新しい長文ラベル・追加フィールドで生えた。LOW |

---

## 5. 恒久的な注意事項（繰り返し踏んでいるもの）

**残タスクに載せてよいものの線引き（ユーザー指示・恒久ルール）:**

- **「機構は実装済みで、config に値が入っていないだけ」のものは残タスクにしない。** 握りつぶす。
  運用上どう設定するかはユーザーの裁量。例: 触媒のオフハンド運用（`offhand-stats-apply` は既に設定可能）。
- **死にステータスは 2 種類に分けて扱う:**
  - **配線は済んでいて config が未設定なだけ** → 握りつぶす。
  - **配線が無いのに config や計画にパラメータとして存在する** → 残タスクにする
    （「設定できるのに効かない」＝プレイヤーを騙す状態）。該当例 = K-9。
  - **この型で起票する前に必ず実コードで裏を取る。** 旧 W-12（エンチャント消費EXP軽減）は
    実装も語彙も既にあり、記録のほうが古かっただけだった。
- **ユーザー報告の「UI が崩れる」は判断待ちではなく実バグ**として扱う。

**バッチ受領時は全件を先に登録する（2026-08-05 の取りこぼしを受けた恒久ルール）:**

- 実サーバ報告が複数件まとめて来たら、**着手前に 1 件 1 行で §3 へ登録する**。
  計画を立てるより先にやる。計画は項目をまとめる（＝粒度を落とす）ので、
  **計画だけを進捗の台帳にすると、まとめた時点で落ちた項目が二度と現れない。**
- **完了報告は計画のステップ単位ではなく、ユーザーが書いた項目単位で出す。**
  未着手の行を省略しない。「N 件中 M 件完了、残り M' 件は未着手」と必ず明示する。
- 実害の実例: 2026-08-04 のバッチ 5（24 件）を 8 ステップの計画へ圧縮した結果、
  **3 件が計画にすら載らず、9 件が 2 行にまとめられた**。10 件を直して配備した時点で
  「配備完了」とだけ報告したため、ユーザーからはバッチ全体が終わったように見えていた。
  →W-28〜W-40。
- **2026-08-05 時点で配備待ち（ユーザー作業）**: サーバ停止 → ①jar（`ops\launch\deploy.cmd`。
  `--config` は付けない）②config（`ops\launch\deploy-config-head.cmd`）。
  jar は 2026-08-05 21:38 時点でビルド済み・検証済み（TF `build/release/TrinityForge-all.jar` に
  `SkillExpBonusKeys.class` あり／ArsPaper jar は `6e64216`（スレッド lore）より 2 分古かったので再ビルド済み）。
  **稼働中の jar 差し替えは必ず `NoClassDefFoundError`** なので必ず停止後に行う。
- **config 配備は `deploy-config-head.cmd`（2026-08-05 新設）を使う。** 他の 2 つはこの状況で外れる:
  `deploy.cmd --config` は**ワーキングツリーを配る**＝他セッションの未完成 yml を出荷する。
  `deploy-config-skip-wip.cmd` は**未コミットのファイルを丸ごと除外**するので、
  **自分の変更と他人の WIP が同じファイルに乗っていると自分の変更まで落ちる**
  （2026-08-05 の `stats/lore.yml`＝職業EXP増加 12 件の表示定義がまさにこれ）。さらに
  **ArsPaper フォークは外側リポジトリから `.gitignore` 除外なので `git status` が何も返さず、
  フォークの未コミット変更（`materials.yml` のレシピ変更）を検出できない**。
  新スクリプトは除外方式をやめ、`ops\scripts\export-head-config.ps1` で
  **HEAD（フォークは自前リポジトリの HEAD）を `tmp\deploy-head` へ展開してそこから配る**＝
  「配るのはコミット済みの状態」と定義する。誰の編集途中も混ざらず、
  コミット済みの変更が同居のせいで落ちることもない。**今回配らなかった未コミット yml の一覧も毎回表示する**。

git 系:

- **`Klee319/trinityforge` は PUBLIC。** 作業ブランチは `dev`、`main` はユーザーの明示指示があるときだけ。
  **push 前に `gh repo view --json visibility`**（TF 本体 jar を意図せず公開した事故がある）。
- **`git add -A` / `git commit -a` / パス指定なしの `git add --renormalize` を使わない。**
  並行セッションが同一ワークツリーで走るので、他人が**実装中**のファイルを巻き込む。
  2026-07-27 に実際に発生（docs 名義のコミットに set-buffs 移行のソースが丸ごと入った）。
  **同一ファイル内で混ざっている場合**は、HEAD の blob へ自分の変更だけを差し込んで index へ直接書く
  （手順は `docs/agent-context/ops-build-deploy.md`）。
- **`.gitattributes`（`*.java` / `*.js` / `*.yml` に `text eol=lf`）を消さない。** Windows の編集ツールが
  CRLF で書き戻すため、実質数行の変更が「全行変わった」差分になってレビュー不能になる。
- **`.project` / `.classpath` / `.settings/` は 2026-08-04 から ignore 対象。**
  post-check フックの compile が吐くので worktree ごとに生え、`wip-audit.ps1` の警告を埋めていた。
- **フォーク（`fork-handoff/arspaper/fork/`・`fork-handoff/elitemobs/elitemobs-fork/`）と `wiki/` は
  このリポジトリの対象外**（それぞれ独自の `.git`）。**`git clean` / `reset --hard` で消える。**
- **worktree を消す前に必ず中身を見る。** 未マージ commit が 0 でも**未コミット変更は消したら戻らない**
  （2026-08-04 の整理では `backups/orphan-worktrees-20260804/` へ patch 退避してから消した）。

配備・ビルド系:

- **`ops\launch\deploy.cmd --config` を使う。** ArsPaper の `materials.yml` は
  `MaterialConfigManager#load` が `saveResource(..., false)` しか呼ばないため、
  **jar 差し替えや `/ars reload` では無言で反映されない**。TF 側の `appendMissingKeys` に相当する機構が無い。
- **yml だけなら config-editor 経由でも配備できる**（保存が `deployPaths` へミラーする）。
  ただし**再シリアライズで本文コメントが消える**（K-3）。先に `docs/config-reference/` へ移設すること。
- **jar を差し替えたらフル再起動。** reload では不十分（`NoClassDefFoundError`）。
- **EliteMobs の配備は必ず全同梱 uberjar。** `*-min.jar` は `DungeonLocator NoClassDefFound` で起動不能。
- **`plugins/` に同名プラグインの jar を 2 つ置かない。** Paper は ERROR を吐くだけで起動を止めず、
  **どちらを読むかは不定**。配備のたびに `ls plugins/*.jar` で重複を確認する。
- **新しい config キーを足したら yml も一緒に配備する。** jar だけ入れると editor がその項目を空欄／OFF で
  表示し、**触っていないのに既定と違う値を書き込む**。
- **`combat/mob-profiles.yml` はリポジトリ側から上書きしない**（`importmobs` の生成物）。
- **Gradle が Java のバージョン番号だけを吐いて落ちたら JDK の取り違え。**
  `-Dorg.gradle.java.home="C:\Program Files\Java\jdk-21"` を渡す。Java が更新されるたび再発する。
- **`TrinityForge/.gradle/`（約 95MB）と `build/` は再生成物。** 消してもよいが、
  **並行セッションがビルド中だと巻き込む**（2026-08-04 の整理で 17:42 のビルドと重なった）。
- PowerShell 5.1 は `&&` を解釈しない。`ops/` 配下の `.cmd` は ASCII のみ
  （非 ASCII を書くと cmd.exe が行の途中から実行を始める）。

設計・実装系:

- **モブの敵対判定は Paper の `Enemy` マーカーが正。** Bukkit の `Monster`/`Animals` は使えない（HOGLIN が `Animals` 判定なのに敵対）。
- **ワールドゲートは `SkillExpConfig.worldExpRate(boolean)` の 1 箇所に閉じている。** 新しい EXP 経路は必ずここを通す。
- **採取ツールの判定に `stats.UseSkillDefaults` を流用しない**（`_AXE` を `HEAVY_WEAPONS` に落とすので素の斧で伐採できなくなる）。採取用は `gathering.GatheringToolMatcher`。
- **PvP のバランスは倍率だけで取らない。** 「1 発で最大体力の何 %」というスケールフリーな上限を併用する。
- **排他グループ（`group:`）は「同じ親を持つ兄弟」にしか効かない。** 路線固定で親を分けると no-op になる。`group` は A 帯だけに置く。
- **1.21.11 に弓の引き絞り時間を変えるレバーは無い。** クロスボウの装填だけ `QUICK_CHARGE` で縮められる。
- **採掘速度を `MINING_EFFICIENCY` / `BLOCK_BREAK_SPEED` 属性で実装しない**（Geyser 未対応＝統合版でゴーストブロック）。効率強化エンチャントのレベル操作が唯一の互換手段。
- **ソース系の倍率を共通経路（`Sourcelink#addToBuffer`）で掛けない。** 返却経路も通るので無限増殖する。
- **同じ式が複数箇所に散っていたら 1 メソッドへ集約する。** SP 付与式は付与・管理コマンド・reload 再計算の
  3 箇所にあり、片方だけ設定を見ると「レベルアップで増えた点が reload で消える」食い違いになる。

---

## 6. 参照先（一次情報）

| 知りたいこと | 見る場所 |
|---|---|
| **触る前に知らないと黙って壊す知識** | `docs/agent-context/README.md`（索引）。どのドメインでも `common-traps.md`、コマンドを打つ前に `ops-build-deploy.md` |
| config の各キーの意味・適用点 | `docs/config-reference/` 以下（yml 本文コメントは editor 保存で消えるのでこちらが正） |
| 仕様 | `docs/` 直下の `*_SPEC.md`。決定台帳は `docs/OPEN_DECISIONS.md`（LD-* はコードから参照されている） |
| 進行中のコンテンツ拡張 | `docs/design/2026-08-01-content-expansion-spec.md`（`-plan.md` が計画側） |
| **失効した設計文書** | `docs/archive/`（**正典ではない**。理由は同ディレクトリの `README.md`） |
| 過去の作業記録・解決根拠 | `reports/ACTIVE_RECORD_ARCHIVE.md`（**残タスクの一次情報ではない**） |
| 並列作業・worktree の作法 | `docs/agent-context/parallel-worktrees.md`。中断から復帰したら `ops\scripts\wip-audit.ps1` |
| サーバ運用・資源サーバ分離 | `ops/RUNBOOK.md`（周辺は `ops/README.md` から辿る） |

---

## 7. 作業履歴（新しいものを上に追記）

**全文は `reports/ACTIVE_RECORD_ARCHIVE.md` の §B。** ここは直近だけを 1 行で残す索引。

| 日付 | 内容 |
|---|---|
| 2026-08-05 | **config 配備を「HEAD を配る」方式へ差し替え**（`ops\launch\deploy-config-head.cmd` ＋ `ops\scripts\export-head-config.ps1`）。既存 2 本はどちらもこのワークツリー（複数セッション共有）で外れる: `deploy.cmd --config` はワーキングツリーを配る＝**他セッションの未完成 yml を出荷**、`deploy-config-skip-wip.cmd` はファイル単位で未コミットを除外するので**自分のコミット済み変更が他人の WIP と同居していると一緒に落ちる**（`stats/lore.yml` の職業EXP増加 12 件がまさにこれ）／さらに**ArsPaper フォークは `.gitignore` 除外で `git status` が常に空を返すため未コミット変更を検出できず、`materials.yml` の他セッションのレシピ変更をそのまま出荷していた**。新方式は除外をやめ `git archive` の tar 経由で HEAD（フォークは自前リポジトリの HEAD）を `tmp/deploy-head` へ展開してそこから配る（`git show` 経由は PowerShell の文字列化で UTF-8 コメントと改行が壊れる）。**配らなかった未コミット yml を毎回列挙**する |
| 2026-08-05 | **実サーバ報告バッチ6（12 件）＋前バッチ残 1 件を全件クローズ**。TF `c841704`（**EXP保存を「ガラス瓶を素の右クリック」で貯蔵／格納済み瓶の右クリックで払い出し」へ変更**。バニラのガラス瓶用途を全部守るため、水源は流体レイトレース・水入り大釜・waterlogged・`isInteractable()` ブロック・`isHarvestableFarmingInteraction` を先に除外する。払い出しは**ガラス瓶を返す**（経験値瓶を返すとガラス瓶→経験値瓶の無料変換になる）。`rayTraceBlocks` は MockBukkit 未実装なのでテスト用シームを切った）／`cadd0f8`（**釣りボーナスが `PercentStatNormalize.RATE_KEYS` 未登録で1回の釣りに追加25個**。`mining-fortune` と同じ壊れ方。出荷スキルツリー合算で期待値0.25個になることまで固定）／`8bc1f6e`（上振れ/ロール収束の単位を lore に明記）／`249e712`（**editor の GUI/簡易トグルが切り替え不可**の真因＝ロスレスパーサが `<icon>` 等の差し込みタグを未知タグ扱いして `ok:false`。フィールド単位の宣言で text ノードへ落とす。`<click:…>` は従来どおり非対応のまま／ロア表示の行が入力中に動く・動かせないのは `draggable` 行と内側 `<input>` の競合）／`2d9fc93`（**採集効率エンチャント上限の二重管理を撤去**＝`stat-caps.yml` 側の上書きを消して `stats/gathering-efficiency.yml` 一本へ）／`ad6c588`（**職業EXP増加を3→15キーへ**。実装は元から全スキル対応で、語彙/%矯正/分類/lore の4箇所へ3件だけ手書きしていたのが真因。`SkillExpBonusKeys`（`SkillId.ALL` 由来）へ集約。**POWER は作らない**＝POWER EXP は他スキルのレベルアップの副作用として倍率適用より後段で加算されるので死んだキーになる）／`d079bd0`（**杖の catalog id を cane→wand**。CMD 据え置き・`assetName` とアセット実ファイル名は据え置き）。ArsPaper fork `0fa2855`（editor 素材カテゴリの「EM限定素材」「ソース階梯」を廃止して ドロップ素材／Ars(魔法) へ統合）。**回答のみ2件**: 素材変換率と材料節約率は別物（前者=解体の戻り、後者=醸造/Ars投入の節約）／コート上限は skilltree の唯一の上限軸なので判断待ちへ。**別セッションが `lore.yml` / `base-stats.yml` / `catalog.yml` / `collection.yml` / `cmd-registry.json` / `stat-caps.yml` を未コミットで書き換え中だったため、staged 内容を「HEAD + 自分の変更だけ」で blob 直接構築した**（`-U0` の patch は行番号だけで位置を決めるので 174 行の挿入が別エントリの途中へ落ちて yml が壊れる。一度実際に壊した） |
| 2026-08-05 | **editor「編集しても未保存警告が出ず保存で捨てられる」3 経路を修正**（`ddb6f40`）。**全 76 画面 × 7 種のウィジェットをヘッドレス Chrome で掃引**して特定（`tmp/dirty-audit.mjs`。`isEditorDirty` は非公開だが cancelable な `beforeunload` の `defaultPrevented` で外から判定できる）。3 件はどれも「`working` に差分が出ない」ため `isEditorDirty()` が false になり、画面移動で警告が出ず、**保存ボタンが `save()` 冒頭の `if (!isEditorDirty())` に入ってサーバの内容を読み直す**＝編集を捨てていた。① **スキル各画面（弓術ほか 14 画面）で説明文の全行削除が消える**: 出荷 `skilltree/*.yml` の大半は新キー `description` を持たず旧 `effect-text` だけなので `delete obj.description` が **no-op**。TF 側は description が空/無いと effect-text へフォールバックするので**空文字を書いても消えない** → 空にするときは両方消す。`description` を持つ `light_weapons`/`heavy_weapons` では再現しない**スキル依存**の罠だった。② **レベルテーブルでモブ選択の変更・行削除が反映されない**、③ **ロールバフで説明 Lore 行の編集が反映されない**: どちらも `getData()` の刈り取りが `working[key] = working[key].map().filter()` で**配列を差し替え**、描画時にその配列を掴んだ行エディタが孤児化していた（`getData()` は画面を開いた直後の `syncBaseFromEditor` でも呼ばれるので**開いてから最初の 1 回の編集が必ず落ちる**）→ in-place 刈り取りへ。不変条件「**`getData()` は `working` 配下のコンテナを差し替えてはならない**」をテストで固定（配列の同一性そのものを検証）。**報告のあった「ガチャ券タブ」＝ガチャ画面（`gacha.yml`）と素材画面のガチャ券カテゴリは、券ID/プール/景品/weight/個数/品質ランダム/追加・削除ボタン/表示名リッチ入力/カテゴリタブ往復まで全部当たって再現しなかった**（該当ウィジェットの特定待ち）。掃引スクリプトの偽陽性源（表示フィルタ・`__…__` 番兵選択肢・readonly id 欄・`maxlength=1` セル・タブラベル）は `docs/agent-context/config-editor.md` に記録 |
| 2026-08-05 | **実サーバ報告バッチ（24 件）続き: EM 2 件＋スレッド 4 件**。EM fork `dfae97bc`（頭上表示の残り漏れ2つ = Lua の `set_custom_name_visible` が抑止を完全迂回／`EliteEntity` が `getName()==null` のときしか可視性を解決しないので**永続ボス・チャンク再読み込みで再トラッキングされる個体**は一度も解決し直されない）。**バニラEM装備のドロップはコード側は正しく、配備済み `plugins/EliteMobs/trinityforge.yml` に古い `true` が残っていたのが真因** ── フォークの設定ローダーは「無かったキーだけを追記し既存値は絶対に書き換えない」ので既定値の変更が効かない。修正スクリプト `ops/scripts/fix-elitemobs-drop-config.ps1`（`bc7d86f`、既定ドライラン・`-Apply` で書換・**実行はサーバ停止後にユーザー**）。TF `4a9e75b`（**スレッドのステ表記がステータスidのまま**の真因 = `stats/lore.yml` の表は yml の綴り（ハイフン）でキーなのに引く側だけ `StatKeys.canonical`（スネーク）へ畳んでいて**1件も一致していなかった**。`LoreConfig#displaySpecFor` に突き合わせを閉じ、`TrinityForge#loreComposer()` を公開してフォークが自前連結をやめた）。ArsPaper fork `9f55d35`（GUI ジェスチャーを**下向き+スニーク+右クリック+直近ジャンプ**へ＝真上はチャット出力へ譲った。下向き単独は採掘/耕作/設置と同姿勢なのでジャンプが唯一の安全装置／効果説明の色を6色から灰色固定へ／装備 lore は `・スレッド名【品質】` の1行だけにし明細は**真上+スニーク**でチャットへ＝`ThreadStatChatListener`／装着スレッドの読みを `SocketedThreads` へ共通化）。**既存の装着済み装備は次に GUI で保存した時点で1行要約へ切り替わる** |
| 2026-08-04 | **実サーバ報告バッチ（24 件）のうち 5 件を修正**。TF `cf12acf`（Ars鍛冶の品質を**回収時**ロールへ＝`ITEM_PENDING_CRAFT_QUALITY` 新設。旧実装は儀式時に PDC だけ書き `ItemFactory#stamp` を呼んでおらず「手に持つまでステが出ない」）／`447ed90`（**右クリックのたびに NPE**。`Map.copyOf` の不変Mapへ `catalogId().orElse(null)` を渡していた＝`get(null)` は HashMap と違い投げる）／`689843e`（**自分が置いたスポナーが壊すと消える**。08-03 の救済がパーク門の内側で一度も到達していなかった）／`5c2d567`（**素材がバニラ装備でない TF 品は品質が常に0**。`isStampableCraftResult` が `MaterialTier#isEquipment()` だけを見ていた＝広辞苑/杖/触媒が全滅）／`6215a0d`（図鑑ティア通知の MiniMessage 生タグ）。ArsPaper fork `ae82e8b`。**リーチ14番は非該当**（バニラ既定は 4 ではなく `entity_interaction_range=3.0`。`BaseStatsConfig` は差分保存なので定数 3.0 が正しい） |
| 2026-08-04 | **リポジトリ整理**（`67b2c02` ほか）。stale な日付レポート 46 本＋`audit-20260725/` を削除、失効 SPEC 12 本を `docs/archive/` へ、IDE ファイルを ignore、worktree 47 本と作業ブランチ 90 本を整理、ローカル生成物 約 800MB を破棄、この記録を現役分とアーカイブに分割。**`TrinityForge/docs/GREENFIELD-REMAINING-TODO.md`（未実装 11 件の台帳）は実コードで全件が実装済みか前提消滅と確認して archive へ**（根拠は `docs/archive/README.md`） |
| 2026-08-04 | 消費ソース量に応じた儀式EXP（`ars-smithing.exp-per-source`）／1SPあたりの総合レベル間隔（`power.levels-per-skill-point`）。TF `0b174cb`〜`4000f9d`、ArsPaper fork `be974a4`（jar を除外して push 済み） |
| 2026-08-04 | 圧縮シリーズ 64 件追加＋カタログID改名で壊れた参照 152 箇所の修復、editor 2 件。**圧縮アイテムは Ars の `materials.yml` に住む**／`reversible: true` で解凍レシピは自動生成（手書き禁止） |
| 2026-08-04 | 実機報告 15 件（機能アイテム 3 種 / アチーブメント手動解放 / 統合メニュー / 弓の距離悪用 / 総合プレステージ）。**うち 2 件はコードが正しく配備側の問題** |
| 2026-08-04 | editor「素材」画面の鍵カテゴリ統合とカラーコード表示の修正。**editor 保存 1 回で `catalog.yml` と `item-stats.yml` が壊れた事故あり**（復旧済み） |
| 2026-08-04 | editor「カタログに追加した新アイテムをステータス設定で選ぶと『同じキーが既に存在します』で弾かれる」を修正（`691ff03`）。**CMD 未割当だとステータスキーが素の Material に退化する**のが原因で、選択時にその場で採番して `catalog.yml` へ保存するようにした。同根の潜在バグ（素キーの空枠生成／素キーが新品に解決される表示バグ／`MATERIAL#` の壊れたキー）も `8703a1a` で解消。検証スクリプトを `ops/scripts/` へ昇格（`c90ed65`） |
| 2026-08-04 | config-editor のレスポンシブ化（375px まで、`fdb364e`）。editor は jar 配備を伴わない |
| 2026-08-03 | 全面監査への回答。**記録の腐り**と「直したが証明されていない 12 件」を洗い出し（→ W-23） |
| 2026-08-03 | 実機バグ報告 9 件のクローズ（証拠つき）／スレッド厳選の作り直し（前日の実装が指示違反だったので撤去）／ダンジョン別の物理・魔法コンセプト |
| 2026-08-02 | コンテンツ追加 7 本柱を全て閉じた／スレッド 45 種化／杖CT・ディメンション基準レベル |
| 2026-08-01 | 未マージ 7 ブランチを `dev` へ集約／孤児 stash の救出／棚卸しで記録の腐り 5 件を訂正 |
| 2026-07-31 | コンテンツ拡充バッチ Wave 0〜5。事後監査で K-11〜K-22 を起票 |
