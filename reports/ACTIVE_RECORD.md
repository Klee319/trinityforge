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
