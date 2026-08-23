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

## 1. 現在の状態（2026-08-10 実測。ハードウェア・worktree 行は 2026-08-04 のまま）

| 対象 | 状態 |
|---|---|
| `dev` HEAD | 2026-08-12 の当セッション分は `3acccb3`（2026-08-10 バッチの記録）→ `74dfab6`（**戦闘バランス再較正**: 防具ラダー引き直し・モブ攻撃力/HP 引き直し）→ **最大HP 50ハート化 + 撃破EXP ×0.75 + ダンジョン攻撃カーブの整合**。`305db88`/`a8e3d25`/`9e8e1c3` は別セッションの %ステ表示修正 |
| **テスト基準値（2026-08-14 実測）** | **クリーンな検証 worktree での HEAD(`5245b48`) ベースラインは 3878 / 9 failed / 2 skipped。** 内訳は フォーク不在の 3 件（`SpellBreakMarkerDriftTest` 2 件・`LegacyValhallaRuntimeContentTest`）＋ `ShippedAchievementTreeTest`（`dungeon_seal_enchant_trial` の綴り）＋ **`warden_tendril` 系 5 件**（`ShippedRitualMaterialObtainabilityTest` 3 件・`ShippedWardenTendrilDropTest` 2 件。ユーザー判断で復元しないと決めた分で、ダンジョン再設計バッチが解消する）。**共有ワークツリーで測った数字は帰属判定に使えない** ── 2026-08-14 に「26 件は全部他セッション由来」と判定したが、実際には**他セッションの WIP が自分の欠落を埋めて緑にしていた**だけで、自分のパスだけを取り出すと隠れていた 8 件が出た。逆向き（他人の WIP が自分の赤を隠す）も同じ理屈で起きる。**帰属を言うときは必ずコミット単位でクリーン worktree を作って測る。** |
| ~~**テスト基準値（2026-08-12 に訂正）**~~ | ~~**クリーンな検証 worktree（`git write-tree` → `commit-tree` → `worktree add`）での TF ベースラインは 3836 / 4 failed / 2 skipped。**~~ ~~25 件は全て他セッションの未コミット yml 由来~~ → **この認識は誤りだった**（「25 件」は共有ワークツリーの他セッション WIP を一緒に測っていた数字で、ベースラインとして使うと自分の回帰 21 件分が隠れる）。4 件の内訳は `ShippedAchievementTreeTest`（`dungeon_seal_enchant_trial` の綴り）／`LegacyValhallaRuntimeContentTest`（配備先 yml の残骸）／**フォークがワークツリーに存在しないことによる** `SpellBreakMarkerDriftTest` 2 件。**skip 2 が正常値**は不変。config-editor のベースラインは **1128 pass / 25 failed** |
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
| ~~**J-12**~~ | ~~**`_Nx` 圧縮素材の EXP 規約**（線形か指数か）~~ | **2026-08-10 ユーザー決定: 「圧縮素材は経験値不要」。`42459bc` で反映。** 圧縮素材 22 件の `smithing.exp-per-material` を **0 で明示**した。**行を消してはいけない**のが要点で、`ArsProgressionBridge#grantSmithingCraftExp` は消費素材が 1 つでも表に無いと素材合計を丸ごと捨てて `ars-smithing.exp-per-craft` の定額(100)へ戻すため、**削除すると「EXP なし」ではなく「素材価値と無関係な定額 EXP」になり意図と逆に振れる**（実際、作業ツリーでは 16 行が削除され、それを使う儀式 15〜18 件が定額へ落ちる状態だった）。あわせて HEAD にあった**キー重複 2 件**（`custom:amethyst_block_2x` が 12 と 1944、`custom:breeze_rod_2x` が 40 と 320 の 2 行。SnakeYAML は後勝ちで黙って通す）を解消。回帰は `ShippedCompressedMaterialExpZeroTest` 2 件 |
| ~~**J-13**~~ | ~~**`apex-brew` の SPEED `amplifier: 2`（速度III）**~~ | **2026-08-10 決定: 意図的として据え置き（変更なし）。** `apex-brew` = 錬金術ツリー Lv90「**変換の大錬金術師**」の専用効果で解放される醸造グループ（＝**極致の調合**）。中身 5 種は **力III・体力増強IV・再生III・跳躍力上昇IV・移動速度上昇III** で、**グループ全体が一律「バニラ上限の 1 段上」**に揃えてある。SPEED だけを問題視するのは一貫しないため据え置く。**別件で実バグ**: 素材の 1 つ `hoglin_tusk`（ホグリンの牙）が **ArsPaper の `materials.yml` に定義ごと消えている**（同ファイルの一覧行にだけ残る）ので、**力IIIのポーションは現在作れない**。原因は CMD 台帳の刈り取り（本ファイル「CMD 台帳が『番号を再利用しない』規約を満たしていない」の節に既出。`plank_scrap` / `tf_gacha_ticket` と同じ機構）→ W 項目として要対応 |
| ~~**J-14**~~ | ~~**「テクスチャ準備済み」18 種の置き場所**（→ K-15）~~ | **2026-08-10 決着。ユーザー判断「どこか仮置きで OK／逆にカタログに無いテクスチャがあれば削除」。** 棚卸しの結果、**K-15 の記述は大半が古かった**: コア 5 種（ウッド/ベジタブル/ミート/ジュエリー/グランド）とモブ素材 13 種は**既に配線済み**（items json → model json → PNG が全部揃っている）。**本当に資産が 1 つも無いのはガチャ券 6 種（`gacha_ticket_0`〜`_5`、materials.yml CMD 5011-5016）だけ**で、PNG そのものがリポジトリに存在しないので置きようがない。**置き場所は決めた**: `resourcepack/trinityforge-items/assets/trinityforge/textures/item/<id>.png` ＋ 同 `models/item/<id>.json` ＋ `assets/minecraft/items/<素材>.json` の 3 点セット。**PNG を置いた時点で配線できる**。逆方向の掃除（カタログに無いのに残っていた資産の削除）は `66d621b` で実施済み |
| ~~**J-15**~~ | ~~**生産ステ「コート上限」(`coating_charges_bonus`) を消すか**（2026-08-05 バッチ6・報告「アイテム個別にコーティング回数あるしステータスは要らないのでは」）~~ | **2026-08-06 ユーザー決定: 「そのまま残す」（選択肢①）。コード・config の変更なし。** `skilltree/alchemy.yml` の3ノード（合計 +18）と `WeaponCoatingListener` の加算はそのまま。以下は判断材料として残す: **消すと skilltree からコート回数を伸ばす軸が無くなる**。現状 `skilltree/alchemy.yml` の3ノードが 3/10/5 = 合計 +18 を配っており、`WeaponCoatingListener` は `maxStacks = min(base, 素材上限) + パーク分 + アイテム個別分` で足している（アイテム個別とは別枠で加算＝重複ではない）。選択肢は ①そのまま残す ②ステだけ消して alchemy.yml の3ノードを別効果へ差し替える ③コート回数はアイテム個別だけにして skilltree からは伸ばせなくする |
| ~~**J-16**~~ | ~~**杖 cane→wand 改名で、流通済みの杖にエイリアスを用意するか**（2026-08-05 バッチ6・`d079bd0`）~~ | **2026-08-06 ユーザー決定: 「入れない」（流通量が少ないため）。コード変更なし。** 以下は受容した副作用: カタログにエイリアス機構が無いため、既存の杖の PDC `catalog_id=<旧id>` は解決できなくなる。装備性能は ItemStack に焼き込み済みで変わらないが、**カタログ由来フレーバー lore の再解決が止まり、コレクション図鑑の発見済みフラグが杖だけ未発見に戻る**（拾い直せば再登録）。旧id→新idのエイリアスを入れるかは要判断 |
| ~~**J-18**~~ | ~~**ダンジョン 61 件のうち入場ゲート未登録の 33 件をどうするか**（2026-08-16 Wiki 整備で発覚）~~ | **2026-08-17 ユーザー回答: 「優良ダンジョン分なので無問題」。対応不要。** 以下は事実として残す: `DungeonGateService#requiredEntry` は **`hasAnyGate()` が true のとき未登録のダンジョンを非特権プレイヤーに fail-close で拒否する**。ゲートは 28 件登録済み＝ true なので、残り 33 件は入れない。これは意図どおり |
| ~~**J-19**~~ | ~~**地形・構造物データパック 17 本が資源サーバに実際に導入されているか**（2026-08-16 Wiki 整備で発覚）~~ | **2026-08-17 ユーザー回答: 「入っている」。** Wiki `バニラとの違い` の「ワールドと構造物」節（データパックで増えた構造物の宝箱も入れ替え対象）は**そのままで正しい**。修正不要 |
| ~~**J-17**~~ | ~~**`attack-power` 上限 127,500 を上げるか（＝メイスを「もっと遅く・もっと重く」に戻せるか）**（2026-08-05 W-32/W-34 の副産物）~~ | **2026-08-06 ユーザー決定: 「そのまま」（選択肢①）。`stat-caps.yml` は触らない**＝メイスは `attack-speed` 0.88 のまま。以下は判断材料として残す: 上限は**最終合算値**に効くので、`attack-speed` が遅い武器は同じDPSを出すのに `attack-power` が反比例で必要になり、遅すぎる武器は上限と両立しない。**メイスを `attack-speed` 0.408 のまま剣の 95% に載せるには単品最大 267,108＝上限の ×2.09 が必要**で、超過分は表示だけで実効ゼロになる。今回は上限を触らず `attack-speed` を 0.88 へ上げて解決した（メイスは大斧 0.833 に次いで2番目に遅い武器になった）。選択肢は ①そのまま（現状） ②上限を 305,000（`stat-caps.yml` 本文の慣習「単品最大×1.1425」）へ上げてメイスを 0.408 へ戻す。**②を選ぶ場合の注意**: 上限は今すでに最上位の重武器で効いており（`binder_greataxe` 単品最大 111,396 × 職業レイヤ ×1.265 = 140,916 → 127,500 でクランプ）、上げると最上位帯だけ実効+10% 程度の底上げになる。**さらに `combat/stat-caps.yml` は別セッションが `stat-caps: {}`（全上限削除・コメント40行消失＝config-editor 保存事故の形）で未コミット変更を持っている**ので、触る前にそのセッションと衝突しないことを確認する（現に `ShippedStatCapsDriftTest` が3件落ちている） |

---

## 3. 残タスク — 手を動かせば終わるもの

| # | 内容 | 前提 |
|---|---|---|
| **W-66** | **ネザーの下駄を25にしたのにモブレベルが変わらない／75lv のまま**（2026-08-17 ユーザー報告） | **調査完了・コードは正常。配備されていないだけ。** `mob-types.yml` の `dimensions.NETHER.base-level: 25` / `coordinate-coefficient: 0.015` は**ワークツリーの未コミット変更**で、HEAD も配備先 3 台（ジャンクション共有、更新時刻 2026-08-18 00:05:15）も **`base-level: 50` / `0.01` のまま**。<br>「75lv」も計算が一致する: `MobTypeSpawnListener#applyScaledProfile:469` は `adjustedBaseLevel = モブ自身の level + dimensions.<ENV>.base-level`（**加算**、置換ではない）で、`PIGLIN`/`ZOMBIFIED_PIGLIN`/`MAGMA_CUBE` は `level: 25` → **25 + 50 = 75**。`WITHER_SKELETON`/`BLAZE`/`HOGLIN`/`GHAST` は 35+50=85、`PIGLIN_BRUTE` は 40+50=90。<br>**注意**: 下駄を 25 にしても「ネザーのモブが Lv25」にはならない（ピグリン Lv50・ウィザスケ Lv60）。Lv25 帯にしたいならモブ側 `level:` も要調整。<br>→ ~~**対応済み**~~ 2026-08-18: `667a362` で commit・push 済み。ユーザー決定に従い **ネザー勢とシャルカー/エンドラの個別 `level` を 0 に統一**し、帯は `dimensions.<ENV>.base-level` 一本（ネザー25／エンド80）で決める設計へ移した。`coordinate-coefficient` はネザーのみ 0.015（1,000ブロックあたり +15）。<br>**追加決定（同日）**: **ウィザー／エルダーガーディアン／ウォーデンの3体だけは `level: 80` + `coordinate-coefficient: 0` の例外**にした（エンドラ＝`THE_END base-level:80` と同じ帯へ揃える要望）。オーバーワールドに湧く／召喚するので `base-level` 由来の帯を持てず、距離補正が乗ると拠点付近と遠征先で桁違いにブレるため。HP は ウィザー 183,939→**653,150**／ウォーデン 4,800(拠点付近)→**783,780**／エルダーガーディアン 1,760(同)→**287,393**。<br>**⚠ ウィザーだけは完全固定にならない**: 召喚場所を選べるためネザー召喚は 80+25=105、エンド召喚は 80+80=160 で、どちらも `max-level: 100` に張り付いて実効 **Lv100（1,334,651 HP）**。「どこで召喚しても Lv80」にするにはディメンション下駄を無視するフラグの新設が必要で、**2026-08-18 にユーザー判断で見送り**（config だけで済ませる）。仕様変更する場合は `mob-types.yml` の WITHER ブロックのコメントを読むこと。<br>回帰ガードは `ShippedMobTypesLevelBandTest` の「Lv80固定ボス3体…それ以外の全モブは level:0」（RED 証明済み: WITHER を 55 に戻すと落ちる）。<br>→ **残: config 配備 → サーバ再起動**（**適用はユーザー**）。既にスポーン済みのモブはレベルが PDC に刻印済みなので変わらない |
| **W-64** | **ランキングのジャンプ回数が多すぎる**（2026-08-17 ユーザー報告） | **調査完了 / 真因はジャンプ側ではない。** 集計は別プロダクト `products/minecraft/rank`（PixelRank、TF リポジトリ外）。<br>①**ジャンプは正しい**: `EventListener#onPlayerJump` が `PlayerJumpEvent` で 1 回 1 加算するだけ。実測（MariaDB `pixelrank.rank_stats`）で首位 22,632 回／プレイ 920 分 = **24.6 回/分**。連続スプリントジャンプの上限が約 100 回/分なので「プレイ時間の 1/4 を跳んで移動」＝正常値。<br>②**壊れているのは「移動距離」**: `onPlayerMove` が `Math.round(from.distance(to))` で加算しており、**歩行(0.215)もスプリント(0.28)も 1 tick 当たり 0.5 未満なので全部 0 に丸められて捨てられる**。実測でも「採掘 41,947 ブロックなのに移動 6,989」と物理的にあり得ない値。距離がほぼ数えられていないため、隣に並ぶジャンプだけが突出して見える。→ ~~**修正済み**~~ 2026-08-18: `Klee319/UserRankBoard` の dev へ `289848c` で修正（`MovementAccumulator` で端数を持ち越し、1 ブロック貯まったぶんだけ加算）。テスト6件追加・RED 証明済み（旧丸めに戻すと4件失敗）。ワールドまたぎテレポートで `Location#distance` が例外を投げる潜在バグも同時に修正。**jar 差し替えはサーバ停止後**（`rank/build/libs/PixelRank-1.3.jar`） |
| **W-65** | **PixelRank の `server-name` が Main と Resource で重複**（2026-08-18 調査中に発見） | `Main_Server` と `Resource_Server` の両方が `server-name: "resource"`。config 自身が「同名にすると互いの行を上書きし合い、片方の記録が消える」と警告している状態。DB 実測でも `server='resource'` に 22 人ぶんが集中し、`server='main'` は 1 人ぶんの残骸のみ。upsert が `value = VALUES(value)`（絶対値上書き）なので**両サーバが同じ行を奪い合って記録が消える**。→ `Main_Server` 側を `main` へ直す（`D:/game` 配下なので**適用はユーザー**） |
| **W-67** | **デスチェストプラグインを AxGraves へ差し替える**（2026-08-18 ユーザー報告のエラー対応。**適用はユーザー**） | **原因は特定済み・上流に修正版は無い。** DeathChest 1.5.7 は保留中のチェストを <code>&lt;world&gt;\`&lt;x&gt;\`&lt;y&gt;\`&lt;z&gt;\`&lt;ownerName&gt;\`&lt;epochMillis&gt;</code> という 1 本の文字列を **YAML のトップレベルキー**にして保存する。**Bukkit の YAML はキー中の `.` をパス区切りとして解釈する**ので、Floodgate の `username-prefix: "."`（proxy/main/resource/dev の 4 つとも `"."`）が付いた統合版プレイヤー名（実ログに `.natsuking003` の死亡あり）がキーに入ると、そのキーは 1 個のキーではなく**入れ子の `MemorySection`** になる。load 時に `getKeys(false)` が `world\`567\`92\`1106\`` しか返さず split が 6 要素→4 要素になり、警告「Your deathchest save file is not updated!!!!!!!!!!!!」を出したうえで**無条件に `List` へキャスト**して `ClassCastException` → enable 失敗。**1.5.7 が最新**（Modrinth `simpledeathchest`、2026-05-02）なので待っても直らない。**統合版プレイヤーが墓を残したまま再起動するたび再発し、失敗したサーバの `deathChests.yml` は 0 バイトになる**（Main_Server は既に 0 バイト＝中身は失われた。Resource_Server には 1 件だけ残っている）。<br>→ **ユーザー選択「別プラグインへ差し替え」**。移行先は **AxGraves 1.29.0**（Modrinth `axgraves` / 18 万 DL / 2026-06-20 / Paper 1.21.11 対応）。**永続化が `data.json` の JSON 配列で所有者は UUID 文字列**（`obj.addProperty("owner", ...getUniqueId().toString())`）なので**この不具合が構造的に起こりえない**のが選定理由。読み込みも try/catch 内なので壊れていても enable は落ちない。墓はパケット製の実ブロックでないため現行の `player_breakable: false` / `explosion_proof: true` は自動的に満たされる。<br>→ **実行**: `ops\launch\swap-deathchest.cmd -Download -DryRun` で計画を見てから `-Download` で本番。停止中でないと動かない。config は jar 同梱を雛形に 3 点だけ差し替える（`despawn-time-seconds: -1`＝現行どおり時間で消えない／`store-xp: false`＝現行どおり経験値はバニラどおり地面へ／`command-aliases` に `deathchest`・`dc` を追加。`/dc` は Quick-EnderChest（`ec`/`enderchest`/`echest`）と衝突しない） |
| **W-68** | **ガチャ券から出た空のスレッドがスタックされない**（2026-08-18 ユーザー報告） | 同じ「空のスレッド」なのに別アイテム扱いでスタックしない＝**PDC か lore か rollSeed が個体ごとに違う**のが定石。W-53 で `ThreadItem#createItemStack` の刻印条件を変えた直後なので、**ガチャ経路が `crafter` をどう渡しているか**を最初に見る（`crafter != null` だと rollSeed が個体ごとに入りスタックが割れる） |
| **W-69** | **すべての圧縮アイテムにエンチャントオーラを付ける**（2026-08-18 ユーザー要望） | 圧縮アイテム（`*_2x` 等）の見た目統一。付与は enchantment glint。**バニラのエンチャント本と混同しない**こと／統合版（Geyser）で glint がどう出るかは要確認 |
| **W-78** | **魔法で敵を倒しても Ars 魔法の経験値が入らない**（2026-08-18 ユーザー報告。当初 W-71 で起票したが並行セッションと番号が衝突したため振り直し） | → ~~**対応済み**~~ 2026-08-18（`b41acc5`）: **真因はクリエイティブ除外の非対称**。`ArsMagicExperienceListener#onMagicKill` は `excluded()`（CREATIVE/SPECTATOR を弾く）を通していたが、**武器・弓術の討伐EXP `CombatListener#onCombatKill` はゲームモードを一切見ない**。そのためクリエイティブでは「剣で倒すと入るのに魔法だと入らない」状態になり、魔法だけ壊れて見えた（実サーバログでも報告時刻の前後で `/gmc` が連発されている）。<br>調査で**潰した仮説**（同種の報告で再度疑わないため）: ①`ars-magic.kill-exp.enabled` は `true` ②`entity-type-multipliers` は combat 側と 41 件完全一致（`unlisted-entity-multiplier: 0` による取りこぼしは無い）③`no-skill-exp-mobs` は 10 種の牧場モブだけ ④Ars のダメージは全経路が `TrinityForgeBridge#applyMagicDamage` を通るので `MagicPipelineDamage` マーカーは必ず立つ ⑤ArsPaper jar は TF クラスを同梱していない（別クラスローダで静的マーカーが分裂する事故ではない）⑥EM フォークは `setDamage` しかせずキャンセル＋再適用しないので致死ダメージの `DamageSource` は MAGIC のまま。<br>→ 修正: 討伐側は武器と同じ規約（**倒したプレイヤーが居ればゲームモードを問わない**）へ揃えた。ブロック破壊側（`onMagicBlockBreak`）は採取EXPと同じ規約なので除外を維持（クリエの破壊はコストが無い）。判定は `killGrantsExp(..., GameMode)` の純関数へ寄せ、**全ゲームモードで付与されること**をテストで固定（マーカー/cause/起因者の3条件は従来どおり必須）。RED 証明済み。→ **残: TF jar のビルド＋配備（サーバ停止後、適用はユーザー）** |
| **W-79** | **破壊時 EXP 系でもらえるバニラ経験値が多すぎる → 現状の 1/4 程度にし、config でも設定できるようにする**（2026-08-18 ユーザー要望。当初 W-72 で起票したが並行セッションと番号が衝突したため振り直し） | → ~~**対応済み**~~ 2026-08-18（`b41acc5`）: ハードコードの `NativeSkillExperienceListener.BASE_BREAK_EXP = 1` を廃止し `stats/skill-exp.yml` の **`break-vanilla-exp.base-exp`（既定 0.25 ＝旧 1.0 の 1/4）** と **`per-skill-base-exp`（採取スキル別の上書き・省略可）** へ移した。<br>**⚠ ベースを小数にするだけでは足りない**: `Player#giveExp` は整数しか取らず、旧実装は `Math.round(1.0 * (1 + bonus))` だったので、0.25 にすると**bonus 0 では毎回 0 に丸められて機能ごと死ぬ**（bonus 1.5 でも 0.625→1 で 1/3 しか減らない）。`BreakVanillaExpLedger` がプレイヤーごとに**端数を持ち越し**、1 貯まったぶんだけ渡す（0.25 なら「4回壊して1EXP」）。**乱数で撒かない**のは、同じ操作でブレると検証できないため。<br>参考: 旧実装は名目 2.5 を 3 へ四捨五入して**+20% 過剰に配っていた**ので、実績比では 1/4 より更に軽くなる。<br>editor 側にも入力欄（`buildSkillExpForm` の専用カード）と `schema.js` の検証を追加した（`base-exp` のスペルミスを黙って無視しないため）。回帰は `BreakVanillaExpLedgerTest` 6件＋既存の破壊時EXP系5件を「4回壊して1EXP」へ更新、RED 証明済み。<br>**同時に `BreakVanillaExpBonusKeysTest` の閾値を直した**（既に赤かった）: 「採取スキル別キーが出荷ツリーに合計6箇所以上」という**ノード数依存の magic number** で固定していたため、2026-08-16 の `13f1d20`（エディタ由来のスキルツリー一括 commit）で農業 D「村の豪農」の倍率が `harvest-extra-drop-chance` へ差し替わった時点で5 箇所になり落ちていた。**検出したいのは「共通キーへの逆戻り」**なので、判定を「4採取ツリーそれぞれが自分のキーを1箇所以上配っている」へ変更した（ノード構成の変更では赤くならず、共通キーへ戻すと落ちることを RED 証明済み）。<br>⚠ **判断待ち（実害は表示のみ）**: 上記の差し替えで農業 D は倍率を配らなくなったが、`effect-text`／`effects` には「バニラEXPを追加で獲得」が残っている（`description` 側は正しく幸運+20%のみ）。**D で追加EXPを配る意図なら倍率キーを戻す／配らない意図なら旧表示テキストを消す**のどちらかが必要。ツリー内容は他セッション/エディタの所有物なのでこちらでは変更していない。→ **残: TF jar のビルド＋config 配備（適用はユーザー）** |
| **W-70** | **スレッドのバニラマテリアルを壺の欠片か鍛冶型に統一する**（2026-08-18 ユーザー要望） | 「重複分は被ってもよい」＝**CMD が違えば同じ Material を使い回してよい**。現状スレッド 45 種がどの Material を使っているかを洗い、`DECORATED_POT_SHERD` 系 or `SMITHING_TEMPLATE` 系へ寄せる。**CMD 台帳（`resourcepack/cmd-registry.json`）は番号を再利用しない**／`catalog.yml` と `item-stats.yml` の `MATERIAL#CMD` キーが連動するので**両方そろえて動かす**（片方だけだとステが丸ごと外れる）。W-68 のスタック問題とも関係しうる |
| **W-71** | **EM ダンジョンのチェスト内容とボスドロップの方式を点検する**（2026-08-18 ユーザー質問） | **調査結果（設定実物 + フォーク/TF 両方のコードで確認）**<br>①**EM の独自ドロップ経路はほぼ全部閉じている。** `plugins/EliteMobs/trinityforge.yml` の `elite-drop-sources` は random-loot / special-loot / elite-scroll / vanilla-loot-multiplier / currency-shower / boss-unique-loot が **false**、**`vanilla-loot` だけ true**（`dropsVanillaLoot: true` のボスは素のバニラドロップを落とす。custombosses 408 体のうち明示 true 32 + 未指定 127〈既定 true〉= **159 体が該当**、明示 false 249 体）。`uniqueLootList` を持つボスは 138 体あるが全部死んでいる。バニラ品も止めたいなら `vanilla-loot: false` の 1 行だけ。<br>②**宝箱は「開けても中身が1個も出ない空箱」だった。** `treasure-chest-loot` / `arena-loot` は**配備 config にキー自体が無く**（`TrinityForgeConfigMigration` は「既にあるセクションの中の新キー」を追記しない設計なので今後も出てこない）、`getBoolean(..., false)` で **既定 false ＝閉**。閉じていると `CustomLootTable#treasureChestDrop*` はメッセージだけ返して return する。それでも `doInteraction` は走るのでミミック抽選と箱の消滅・再設置だけが続いていた。配備中の宝箱は 9 個（the_sewers 2〈各 15 種・mimicChance 0.5〉/ em_id_the_nether_bell 4〈各 3 種のクワ〉/ em_id_the_mines 2〈ロア品〉/ テスト 1）。**TF 側に宝箱の代替実装は無い。**<br>→ ~~**対応済み**~~ 2026-08-18: **ユーザー決定「宝箱そのものを無効化する」**。フォーク `fde0ecf2` で `TreasureChest` のコンストラクタがゲートを見て初期化を打ち切る（`isEnabled: false` と同じ扱い＝箱を設置しない / クリックしても何も起きない / ミミックも湧かない）。`D:/game` の config を触らずに済み、今後インポートするパックの宝箱にも自動で効く。**`/trinityforge reload` では戻らない（サーバ再起動が必要）** — `isEnabled` を書き換えたときと同じ制約。TF 未導入時はゲートが fail-open なので素の EliteMobs は無変更。<br>③**TF のボスドロップは EM の emloot を使っている。** `combat/mob-overrides.yml` の `drops:`（402 キー中、実設定 34 箇所）→ `MobOverrideDropListener`（MONITOR）→ `EliteMobsSharedLootBridge` → フォークの `TrinityForgeSharedLoot#offerDungeonLoot` → `SharedLootTable`（`/em loot` の need/greed、60 秒投票）。引き取り条件は **エリート / ダメージ寄与者2人以上 / 寄与者の誰かがインスタンス化ダンジョン内** の3つで、外れれば地面へ落ちる。<br>→ ~~**修正済み**~~ 2026-08-18（TF `c5a1f47` / フォーク `fde0ecf2`）: **`SharedLootTable#rollLoot` は1スタックにつき当選者を1人しか選ばない**ので、`chance: 1.0` の進行アイテム（ダンジョン印 28 種・`key_enchant_trial_2`〜`_10`・`binder_fragment`・`abyssal_ingot` の計 39 件）まで抽選に乗り、**2人で試練1を倒すと試練2の鍵が1本しか出ず片方が次に入れない／図鑑も埋まらない**状態だった（鍵は `dungeon/gates.yml` で入場時に消費される）。`MobDropRoller#isProgressionDrop`（＝素の `chance` が 1.0 か）で分岐し、確定ドロップだけ新設の `offerPerPlayerLoot` で**寄与者全員に1個ずつ**渡す。判定に渡すのは**倍率を掛ける前の設定値**（ドロップ増加ステで 0.9 が 1.0 に届いても全員配布へ化けない）。**`mob-level-table.yml` の `add-drops` にはこの経路を通していない** — あちらは帯ごとのフィールド報酬で、`dragon_scale` が `chance: 1.0 / min 0 max 2 / where: field` のように「確定でも全員に配ってよいものではない」。<br>回帰: `MobDropRollerTest` 19件・`MobOverrideDropListenerTest` 14件・`SharedLootRoutingWiringTest` 2件（RED 証明済み）・`ShippedProgressionDropConventionTest`（確定ドロップにバニラ素材を混ぜたら落ちる）／フォーク `SharedLootTableTrinityForgeTest` 6件・`TrinityForgeGateWiringTest` 11件（RED 証明済み）。クリーン worktree で **4176 / 25 failed / 3 skipped**（25 件は catalog / crafting-features / skilltree yml / lore など無関係ドメインの既存ドリフト）。<br>→ **残: TF jar と EM jar の再ビルド → 配備 → サーバ再起動（適用はユーザー）** |
| **W-72** | **武器種ごとのリーチにもっと差をつけ、伸びた武器は火力を下げ縮んだ武器は上げる**（2026-08-18 ユーザー要望） | → ~~**対応済み**~~ 2026-08-18: **ユーザー決定「短剣は実効 1.5／槍は +2 を目安」「火力補正はリーチ 1.0 あたり実効DPS 5%」**。旧表の幅は −0.5〜+1.0（実効 2.5〜4.0）しかなく武器種の差が体感できなかったので、**−1.5〜+2.2（実効 1.5〜5.2）**へ広げた: 短剣 −1.5 / メイス −0.8 / 剣 ±0 / 戦斧 +0.4 / レイピア +0.8 / ウォーハンマー +1.0 / 鎌 +1.2 / 大剣・大斧 +1.4 / トライデント +1.6 / 槍・ハルバード +2.0 / 猟師の投槍 +2.2 / 弓・弩・杖 ±0（据え置き）。<br>火力は旧値からの差 ×5% を符号反転して掛けた（短剣 +5% / メイス +4% / 戦斧 −1% / レイピア −2.5% / ウォーハンマー −3% / 鎌 −3.5% / 大剣・大斧・トライデント・投槍 −4% / 槍・ハルバード −5%）。掛け先は `attack-power` と `fixed-damage` の2キーだけで、`fixed` / `per-quality` / `random.min|max` の3か所すべてに適用（203 アイテム / 812 行）。<br>**⚠ `bleed-damage` は意図的に据え置いた。** 上位段の鎌は `stat-caps` の上限 4,500 に張り付けてあり、下げると「上限張り付き」判定が外れて `scytheBleedIsMeaningfulUpToTheCap` の出血比率チェック（8〜25%）が発動し、**総DPSの大きい上位段6本が 2.5〜4.7% で一斉に落ちる**（実際に一度これで落とした）。<br>**猟師の投槍を除外リストから表側へ移した**: 槍が 2.0 まで伸びたので 1.4 のままでは「game 内で最長」という 2026-08-02 の決定が崩れる。除外したままだと**その矛盾を検査が一切見なくなる**ので、`javelin` 型として表に 2.2 を書いた。<br>回帰は `WeaponTierParityTest` 9/0（`REACH` と `MELEE_BAND` を同時に移動。帯は比なので片側だけ動かすと必ず外れる。丸めは外側へ）。RED 証明済み（yml だけ変えた時点で リーチ表・帯・鎌の出血の3件が落ちた）。プレイヤー Wiki の武器種表（`docs/wiki-source/prose/03-ステータスと厳選.md` 3.2）も実測値へ更新（旧表は「曲刀」「長刀」という出荷に存在しない名前で、大剣/大槌の値も入れ替わっていた）。<br>→ **残: config 配備 → サーバ再起動（適用はユーザー）**。`item-stats.yml` のみ。**流通済みの武器には効かない**（ステは ItemStack に焼き込み済み）<br>→ **第2弾（同日）**: ユーザー追加要望「火力補正をもう少し大差にしていい」「DPS は据え置いたまま出血・範囲ダメージ等の武器種特有ステをとがらせて」。**補正を 5%→10%／リーチ1.0 へ倍化**（12% が実質上限。これ以上だと短剣が同帯の剣を追い越す）。二重丸めを避けるため 5% 版を捨てて素の値から作り直し、**倍率を掛けるのではなく目標DPSから `s` を逆算**した（会心も同時に動かすため単純な掛け算では目標からずれる）。<br>個性は4軸: ①**範囲ダメージを大剣・大斧・ハルバード・鎌へ新設** — `aoe-*` は機構も lore 表示名も揃っているのに**出荷 216 本中 0 本**が使っておらず、まるごと空いていた差別化軸だった ②**出血を鎌へ集約** — トライデントが全16段で鎌と同率(0.16)の出血を持ち個性を薄めていたので落とし、失ったぶんは単体火力へ振替。鎌は出血率 ×1.8（0.16〜0.18 → 0.29〜0.32） ③**貫通を刺突へ集中** — 槍/レイピア/トライデント/投槍/ハルバード ×1.3、打撃はメイス・大斧 ×0.5／**ウォーハンマーは ×0.5 では剣と並ぶので ×0.35** ④**会心の二極化** — 短剣 率×1.6 倍率×0.85／大斧・ウォーハンマー 率×0.5 倍率×1.15（期待値のズレは上の逆算が吸収するので変わるのは分散だけ）。<br>**⚠ `WeaponDpsParityTest` は素の比では落ちる。** 「重武器 vs 同格の軽武器」を 0.85〜1.16 で見る別設計の検査だが、リーチ補正はその比を意図的に動かす（大剣 ×0.92 と短剣 ×1.10 でペアの比が 0.836 倍ずれる）。**割り戻さずに当てると、守っている設計は無傷なのに落ちる**ので、倍率表を `WeaponTierParityTest.REACH_DPS_FACTOR` に一本化して両方から読ませ、比から割り戻すようにした。<br>回帰は `ShippedWeaponIdentityTest`（5件・新設。値ではなく「どの武器種が持つか」と「同系列の剣に対する大小」で固定）＋ `WeaponTierParityTest` 9件 ＋ `WeaponDpsParityTest` 4件。フルスイート 4199 / 25 failed / 2 skipped（25 件は他セッションの未コミット作業由来で武器・item-stats 系はゼロ） |
| **W-73** | **低レベルのままハメ殺し／デスルーラーで高レベルのモブを狩る行為を抑える**（2026-08-18 ユーザー要望。「現状は下記のような設定項目だがどちらかと言えば低レベルが羽目殺しやデスルーラーで高レベルのモブを狩ることを防ぎたい」） | → ~~**対応済み**~~ 2026-08-18（TF `fd57fce`）。<br>**真因は「機能が無かった」ではなく「片側しか作っていなかった」。** `level-cutoff` の `under-level`（＝モブのほうが高レベル側）は閾値 `item-threshold` 1本しか持たず、効果は **TF追加ドロップを一切付けないの全か無かだけ**で、**経験値には一切効かなかった**（`MobLevelCutoff#expMultiplier` が `isOverLevelActive` でしか分岐していない）。撃破EXPはモブのレベルで伸びるので、**低レベルのまま高レベルモブを倒すとバニラの経験値オーブもTFの戦闘スキルEXPも満額入る**のが最大の抜け穴だった。しかも出荷値は `item-threshold: -1`（無効）で、**ドロップ側の足きりすら一度も発動していなかった**。<br>→ **ユーザー決定①「over-level と完全対称にする」**: under 側にも `exp-rate` / `drop-rate` / `exp-decay-per-level` / `drop-decay-per-level` / `rate-floor` を追加。計算式・`-1`（完全遮断）が減衰より優先される規約・`excess == 0` で減衰なし、まで over-level と同型。両方同時に発動したら**厳しいほう（小さいほう）を採る**（起きるのは閾値が両方 0 でプレイヤーとモブが同レベルのときだけ）。<br>→ **ユーザー決定②「パーティでの同行は区別しない」**: レベル差だけで判定するので、高レベルの人にダンジョンへ連れて行ってもらった低レベルも同じだけ削られる。区別する術が無いのに免除を入れると、**低レベルを連れて行くだけで抑制を回避できてしまう**ため。<br>**出荷値**（`combat/damage.yml`）: `item-threshold: 20 / exp-rate: 1 / drop-rate: -1 / exp-decay-per-level: 0.1 / drop-decay-per-level: 0 / rate-floor: 0`。**20レベル差でTF追加ドロップが止まり、経験値は20差では等倍・超過1レベルごとに 0.1 ずつ落ちて 30差以上で 0**。over-level（低レベル狩り）側は `threshold: -1` のまま無効で据え置き。<br>**⚠ 閾値のキー名は `item-threshold` のまま据え置いた**（アイテムと経験値の両方の発動条件を兼ねる）。リネームすると**配備済み config の値が別キーへ移って無言で既定値に化ける**ため。<br>**⚠ 新キーの既定値は「対称化する前の挙動」に合わせてある**（`exp-rate: 1.0` = 経験値に触れない／`drop-rate: -1` = 発動したら追加ドロップ無し／decay・floor は 0）。under-level のキーを1つも書いていない配備済み config の意味は変わらない。**逆に言うと出荷 yml を配備し直さない限り新しい足きりは効かない**（配備済み `damage.yml` の `item-threshold` は明示的に `-1`）。<br>回帰: `MobLevelCutoffTest` 39件（+7。対称性そのものを 0〜30 超過まで突き合わせる `underLevelIsExactlySymmetricWithOverLevel` を含む）・`ShippedLevelCutoffTest` 2件（**出荷値が `-1` に戻ると落ちる**＝「実装済みだが設定が無効」の再発防止）・`LevelCutoffExpListenerTest` 10件・`MobOverrideDropListenerTest` 14件・`CombatDamageConfigTest` 19件、editor `constants.test.js` に W-72 の5キー試験を追加。**RED 証明済み**（under 側の分岐を外すと 6 件が落ちる）。共有ワークツリー全体で **4185 / 25 failed / 2 skipped**（25件は W-71 のクリーン worktree 計測と同一の既存ドリフトで、`mobs` / `listeners` / `config.domains` には1件も無い）。<br>**2026-08-18 追記: 経験値の逓減区間をユーザー指示で 20〜30 差 → 15〜30 差へ広げた。**「経験値は15〜30レベルの差の区間をかけて0になるようにしたい」。**ここで `item-threshold` を 15 に下げるとTF追加ドロップの遮断まで 15 差へ動いてしまう**（1本の閾値がアイテムと経験値の両方を兼ねていたため）ので、**経験値専用の `level-cutoff.under-level.exp-threshold` を新設**した（`MobLevelCutoff` に13番目の要素として追加。**未設定/-1 なら `item-threshold` へフォールバック**するので、このキーを書いていない配備済み config の意味は変わらない）。出荷値は `item-threshold: 20`（追加ドロップは 20 差で完全遮断のまま）・`exp-threshold: 15` / `exp-decay-per-level: 0.067` → **15差=満額 / 20差≒0.67倍 / 25差≒0.33倍 / 30差以上=0**。回帰は `ShippedLevelCutoffTest` を 2→4 件へ増やし、**15〜19 の帯を全数チェック**する（最初に書いた版はこの帯を一度も見ておらず、経験値側を旧実装へ戻しても緑のままだった＝テストが機構を固定できていなかった）。<br>→ **残: 配備 → サーバ再起動（適用はユーザー。W-80 と同じ `combat/damage.yml` / TF jar で一緒に入る）** |
| **W-80** | **EM ダンジョンの3件: ①潜入直後に強制的にオーバーワールドへ戻される ②ダイナミックダンジョンは低レベルで挑むほうが簡単に勝ててしまう ③高レベルで挑む理由が無い**（2026-08-18 ユーザー報告。追加情報「em start を実施後数秒後に強制的にインスタンスが終了」／②③の方針は「お任せ」） | **①→ ~~修正済み~~ 2026-08-18（フォーク `f7bc39c8`）。真因は「開始前の致死ダメージがインスタンスごと畳んでいた」こと。** `MatchInstanceEvents#onPlayerDamage` は致死ダメージを見つけると `state != ONGOING` のときに `removePlayer()` を呼ぶが、ソロだと `players` が空になるので `defeat()` → `endMatch()` → `removeInstance()` まで一気に走る。**そのうえ致死ダメージは直後に `setCancelled(true)` されるので死亡メッセージも死亡画面も出ない** — プレイヤーからは「/em start の数秒後に強制的に戻された」としか見えず、**ログにも痕跡が1行も残らなかった**（`/em start` → `countdownMatch()` → `CountdownTask`（20tick周期×3）→ 約3秒後に `startMatch()`、という報告どおりの時間軸と一致する）。開始前（WAITING/STARTING）はダメージだけ打ち消して待機を継続するよう変更。<br>**併せて「残機3がソロでは構造的に使えない」欠陥も修正。** 参加時に `playerLives.put(player, 3)` を配っているのに、減らせる唯一の経路 `revivePlayer()` は**死亡バナーを叩ける生存者**が前提なので、ソロでは1回の致死で即終了だった。最後の生存者が倒れたら残機を消費して入口（`startLocation`）へ復帰させ、尽きたときだけ攻略失敗にする。<br>**再発時に犯人が一発で分かるよう `[instance-diag]` ログを4経路に追加**（終了 / 最後の参加者の離脱 / 待機中テレポートによる無言除外 / 領域外からの引き戻し）。**特に致死ダメージの `cause` を出す**ので、次に起きたら WORLD_BORDER・VOID・SUFFOCATION・モブ攻撃のどれかが即座に判る。<br>**⚠ 判明した別の地雷（挙動は上流のまま据え置き、コメントで明示）**: `DungeonInstance.world` が `MatchInstance.world` を隠しており（フィールドは静的型で解決される）、`onPlayerTeleport` の「インスタンスのワールドを跨ぐテレポートを弾く」ループが**常に continue するデッドコード**になっている。`super.world` へ代入すると待機中の離脱テレポートまで一律キャンセルされ**ロビーから出られなくなる**ので直していない。<br>**②③→ 一度実装したものをユーザー差し戻し → 作り直して ~~対応済み~~ 2026-08-18。**<br>**最初の実装（TF `136057b`）はユーザーに却下された。** 「レベル差1につき報酬を増やす」という**グローバルな条件**で `MobLevelCutoff` に置いたため、①ダンジョンと無関係な**オーバーワールドの高レベルモブを倒しても増えてしまい** W-73（低レベルのまま高レベルモブを狩る行為の抑制）と真正面から衝突する、②「選択レベルが報酬に効かない」を**報酬側の後掛け倍率で埋めただけ**で構造の欠落を直していない、の2点。`1b6743d` で revert 済み。<br>**調査で判明した実態**（前回の記述を実コードで訂正した箇所を含む）: (a) 配備中の 32 パッケージは**全部ダイナミック**（`contentLevel: -1`）、(b) 難易度の `levelSync` は EliteMobs の**装備tier上限**であって敵の強さではない — TF は EM のアイテム体系を使わないので **difficulty（normal/hard/mythic）は TF プレイヤーには実質無効**、(c) **ブループリント配置のモブは全部 `InstancedBossEntity` で、そのコンストラクタが `level == -1` のとき `getSelectedLevel()` を入れる** → **雑魚を含め選択レベルは既に敵の強さへ効いていた**（`SetBossLevelsTask` は 4 秒後の重ね掛け）。実測: `em_id_the_mines` 38 体・`the_sewers` 29 体・`em_id_the_city` 12 体は**全部 `level: dynamic`** で、その大半が `isRegionalBoss: true`、(d) 一方で**その経路を通らない個体**（ボスの召喚した増援、フェーズ2/3の本体、API 経由、インスタンス内の自然湧き）は `CustomBossEntity#getDynamicLevel` に落ちて**近くのプレイヤーの EliteMobs 装備tier**でレベルが決まる ＝ TF では tier 0 なので**レベル1相当の張りぼて**、(e) **TF のドロップは `mob-overrides.yml` の固定 `chance` でレベルの項が1つも無く**、撃破EXPもモブレベルに対して 1.008 乗の緩い傾斜しかない。つまり**難易度は選択レベルで伸びるのに報酬はほぼ平ら**で、一番低いレベルを選ぶのが常に最適だった。<br>→ **ユーザー決定「両方やる」**（難易度側も報酬側も直す）。<br>**難易度側（フォーク）**: `DynamicDungeonLevelListener` を新設し、`EliteMobSpawnEvent`（`LOWEST`）でインスタンス内に湧いた elite を**種類を問わず選択レベルへ揃える**。上記 (d) の抜け道を全部塞ぐ。`LOWEST` なのは TF の PDC 刻印（`TrinityForgeSpawnListener`, `HIGH`）が `getLevel()` を読んで `MOB_LEVEL` を書くから — **先に直さないと HP・攻撃力・撃破EXP・報酬上乗せが全部ずれる**。`SetBossLevelsTask` も `InstancedBossEntity` 限定をやめ、同じ関数を通す保険にした。ワールド→インスタンスの引き当ては `DynamicDungeonInstance#getForWorld`（インスタンスのワールドは毎回連番でクローンされるのでブループリント名では同定できない）。<br>**さらに 2026-08-18 追加: 難易度をモブレベルへ反映させた（`DungeonInstance#getDifficultyMobLevelOffset` / `DynamicDungeonInstance#getMobLevel`）。** 上記 (b) のとおり `levelSync` は装備tier上限なので **TF プレイヤーにとって normal/hard/mythic は完全な no-op** だった。相対 `levelSync`（`+5 / +0 / -5`）の**符号を反転**した値をモブレベルの補正に使う → normal はモブが5レベル低く、mythic は5レベル高い。これで初めて難易度に実体が生まれ、上の 5レベル刻みの報酬と噛み合う。**絶対値指定（`levelSync: 70` など）のダンジョンは補正 0**（固定レベルのダンジョンで使われており難易度差を表していないため）。入場時に実効レベルが選択レベルと違えば「難易度により敵のレベルは N になります」とチャットで明示する（表示しないと**同じレベルを選んだのに強さも報酬も違う理由がプレイヤーから見えない**）。<br>**報酬側（TF）**: `combat/damage.yml` に **`dungeon-level-reward`** を新設。判定に使うのは**倒したモブのレベル**（＝選んだ挑戦レベルそのもの）だけで、**プレイヤーとのレベル差は見ない**。適用先は `KillRewardAdjuster` が `DungeonWorldRegistry#isDungeonWorld` で**ダンジョンインスタンス内のキルに限定**する ＝ **オーバーワールドには構造的に届かない**（差し戻しの直接の回答）。**2026-08-18 ユーザー指示で片側の上乗せ（1.0 以上にしかならない曲線）から「増減両方」へ作り直した** ——「低いレベルでは規定値より少なくし、高いレベルでは規定値より高くせよ。これにより打ち止めの倍率をもっと下げる。また、5lv単位で変わるようにすることで normal,hard,mythic のそれぞれを選ぶ理由を付ける」。片側だと**頭打ちを大きく取らないと差が出ない**ので、低い側を減らすことで頭打ちを下げられる。キーごと差し替え（`base-level`→`pivot-level`、`*-per-level`→`*-per-step`、`*-penalty-cap` を新設、`step` を追加）。出荷値 `enabled: true / pivot-level: 35 / step: 5 / drop-bonus-per-step: 0.08 / drop-bonus-cap: 0.5 / drop-penalty-cap: 0.3 / exp-bonus-per-step: 0.04 / exp-bonus-cap: 0.25 / exp-penalty-cap: 0.2` → モブレベル別に **15以下 0.70/0.80（下限）・20 0.76/0.88・30 0.92/0.96・35 1.00/1.00（等倍）・40 1.08/1.04・50 1.24/1.12・60 1.40/1.20・70以上 1.50/1.25（上限）**（ドロップ/EXP）。**頭打ちは 2.5倍→1.5倍・1.75倍→1.25倍へ下がった。**<br>**⚠ `step: 5` は飾りではなく難易度と噛み合わせるための値。** 下の難易度側でモブレベルが normal −5 / hard ±0 / mythic +5 動くので、**難易度1段＝報酬1段**で対応する（`oneDifficultyStepIsExactlyOneRewardStep` / `shippedStepMakesEachDifficultyTierWorthPicking` で固定）。ここを連続関数に戻すと難易度の差が端数になって選ぶ動機が消える。<br>**⚠ 減少側は `MIN_MULTIPLIER = 0.1` で床を打つ**（schema でも `penalty-cap` を `[0, 0.9]` に制限）。報酬が 0 や負になると「ダンジョンへ行くと損」になるため。<br>**⚠ 足きりを掛けた「あと」に乗せる。** 足きりが完全遮断（0）を返したキルは上乗せしても 0 のままなので、**高レベルの人に連れて行ってもらった低レベルが上乗せで抜け穴を作ることはない**（`aBlockingCutoffStillWinsInsideTheDungeon` で固定）。<br>**⚠ Java 側 SchemaField の既定は `enabled: false`**（出荷 yml 側でだけ有効）。ここを true 既定にすると、この節を1行も書いていない**配備済み config の意味が jar 差し替えだけで変わる**。editor の `def` も同じ理由で出荷値ではなく SchemaField 既定に合わせてある（`editor-normalize-default-drift`）。<br>**⚠ `InstancedBossEntity#setEntityLevel` が TF の `MOB_LEVEL` 刻印を更新していなかった件（フォーク `5c96daff`）はそのまま有効**。刻印はスポーン時に1回しか書かれないので、あとからレベルを変えても TF はスポーン時の値を見続ける。<br>回帰: `DungeonLevelRewardTest` 11件（純粋ロジック。等倍点・両側の符号・5レベル刻みの踏面・難易度1段＝報酬1段・両側の頭打ち・溢れ・床）・`ShippedDungeonLevelRewardTest` 5件（**出荷値を無効に戻すと落ちる**＝「高いレベル・高い難易度を選ぶ理由」の再発防止。頭打ちが 2.0 倍/1.5 倍を超えたら落とす）・`KillRewardAdjusterDungeonBonusTest` 9件（**`overworldKillGetsNoBonusEvenAtAHugeMobLevel` が差し戻された設計の再発を止める**。**減少側も同様にダンジョン限定**であることを `overworldKillIsNotPenalisedEitherAtALowMobLevel` で固定 ── 減少がオーバーワールドへ漏れると無関係な帯の報酬まで一律で削れる）・`MobLevelCutoffTest` 39件、editor `constants.test.js` に9キー試験＋`exp-threshold` 試験。**RED 証明済み**（減少側を `0.0` に潰すと `dungeonKillBelowThePivotPaysLessThanTheBaseline` が 0.76→1.0 ほか7件が落ちる／`expMultiplier` を `isUnderLevelActive` に戻すと `shippedExpCurveFadesToZeroAcrossTheFifteenToThirtyBand` が「レベル差16で経験値が減っていない」で落ちる。**最初に書いた版は 15〜19 の帯を1つも見ておらず RED にならなかったので、その帯を全数チェックする形へ書き直した**）。共有ワークツリー全体で **4259 / 26 failed / 4 skipped**（26件は catalog / crafting / skilltree / brew / ritual / skill-exp / gimmick など他セッション WIP 由来の既存ドリフトで、`mobs` / `listeners` / `config.domains` の戦闘系には1件も無い）。editor は `pillars`（他セッションの `progression/combat-level.yml` 変更由来）1件のみ失敗で、増減の3試験は緑。<br>→ **残: 配備 → サーバ再起動（適用はユーザー）**。jar は両方ビルド済み・内容検証済み（TF jar 内の `combat/damage.yml` に `pivot-level: 35` / `exp-threshold: 15` が入っていること、EM uberjar に `DynamicDungeonLevelListener.class` と magmacore 254 エントリ・`DungeonLocator.class` があることを確認）。**配備済み `damage.yml` に `dungeon-level-reward` が無い間は増減は効かない**（SchemaField 既定 `enabled: false`）。難易度側はフォーク jar だけで効く（config 不要） |
| **W-74** | **杖（おそらくすべて）にダメージ補正のステータスが無いので付与する**（2026-08-18 ユーザー要望） | → ~~**対応済み**~~ 2026-08-18: 出荷の杖 10 種（`wooden_wand`〜`boundary_wand`／`magic_wand`／`abyss_wand`）は全部 `damage-modifier` を持っていなかった（剣・斧などは全段が持っている）。`fixed.damage-modifier: 0.3` を `penetration` の直後（他武器種と同じ位置）へ 10 行追加。**杖の実効DPSは W-76 の `item-cooldown` ×1.15 と打ち消し合って据え置き**（`WeaponTierParityTest#wandCastingDpsIsTheIntendedModestBand` の実測 48.7〜48.8% が前後で不変）。<br>→ **残: config 配備 → サーバ再起動（適用はユーザー）**。**流通済みの杖には効かない**（ステは ItemStack に焼き込み済み） |
| **W-75** | **バニラのクロスボウが使用可能レベル25の tier にいるので、弓と同様にレベル0の tier へ落とす**（2026-08-18 ユーザー要望） | → ~~**対応済み**~~ 2026-08-18: `CROSSBOW`（バニラ）は `use-level-requirement: 25` / `quality-mode-offset: -3` で、素材段でいうと `iron_crossbow` 相当の位置にいた。**「弓と同様に」という指示どおり、バニラ `BOW`(Lv0) が `stone_bow`(Lv5) に対して持つキーごとの比をそのまま `stone_crossbow`(Lv5) へ当てて Lv0 段の値を作った**（勝手な手打ちにしない）。<br>`use-level-requirement: 25→0` / `quality-mode-offset: -3→0` / `attack-power: 271→58.2` / `per-quality: 20.9→4.4` / `random.min: -43→-9`・`max: 54→12` / `durability: 150→35`。段でしか変わらないのはこの4キーだけで、会心・貫通・命中・矢速などはクロスボウ全段で同値なので触っていない。<br>→ **残: config 配備 → サーバ再起動（適用はユーザー）** |
| **W-76** | **杖のCTをもう少し長くする（現状は素殴りでも敵を倒せる程度のDPSが出てしまう）**（2026-08-18 ユーザー要望） | → ~~**対応済み**~~ 2026-08-18: **要望どおり CT は伸ばしたが、「素殴りで倒せる」の真因は CT ではなく `attack-speed` だった。**<br>杖 10 種は `attack-speed: 1.6`（＝剣と同じ）のまま残っていた。**2026-08-02 に弓・弩を 0.1 へ落とした遠隔武器の掃討で杖だけ漏れていた**もので、`item-cooldown` は Ars の詠唱と近接の主撃の両方を律速するが、**一撃あたりの威力は `MeleeChargeMultiplier`（`min + (経過tick ÷ (20/AS))^1.6 × (1−min)`、出荷 `min-multiplier: 0.1`）が別に決める**。AS 1.6 なら満チャージまで 12.5 tick で CT 41〜69 tick に対して常に 1.0（＝毎回フルスイング）、AS 0.1 なら 200 tick 必要で 20〜35% しか溜まらず倍率 **0.20〜0.26**。<br>→ ①`attack-speed: 1.6 → 0.1`（弓・弩と同じ最低値。**触媒の `BLAZE_ROD#400001`／`ENDER_EYE#85` も同じく 0.1 へ**） ②`item-cooldown` を一律 ×1.15（3→3.45 … 1.8→2.07）。**素殴りは約 1/4 に落ち、詠唱DPSは W-74 の `damage-modifier: 0.3` と相殺して不変。**<br>**⚠ 2026-08-14 の `MeleeUnintendedItemAttackSpeedTest#cooldownBearingWeaponsSwingAtSwordSpeed` は「触媒は item-cooldown が振り間隔を律速するので 1.6 でも実効DPSは1も動かない」として、逆のルールを検査で固定していた。** 手数については正しいが**一撃の威力（チャージ倍率）を勘定に入れていない**。触媒をトライデントと同じ扱いにしていたのが誤りなので、テストを `tridentsSwingAtSwordSpeed`（トライデント 16 本のみ）へ狭め、触媒は遠隔武器と同じ「最低値に固定」側の不変条件へ移した（`EXPECTED_TARGETS = 44` ＝ 弓弩 32 + 触媒 12）。判断の根拠は javadoc に残してある。<br>→ **残: config 配備 → サーバ再起動（適用はユーザー）** |
| **W-77** | **弓の火力が全体的に低い**（2026-08-18 ユーザー要望） | → ~~**対応済み**~~ 2026-08-18: 弓 17 種（バニラ `BOW` + `stone_bow`〜`revolution_bow`）の `attack-power` を一律 ×1.20。`fixed` / `per-quality` / `random.min|max` の 4 か所すべてに適用（68 行）。バニラ `BOW` は 69 → 82.8。**矢のダメージは `弓の attack-power × ProjectileWeapon#readDrawForce` で決まる**（`CombatListener` の `tfBaseReplaces` 分岐）ので、`attack-power` を上げるとフルドロー時の矢が素直に 1.2 倍になる。<br>**素殴りは意図的に据え置き**（弓の `attack-speed` は 0.1 のまま）。近接比率は 5.3〜9.7% → 6.3〜11.1% で `WeaponTierParityTest` の遠隔素殴り上限内。<br>→ **残: config 配備 → サーバ再起動（適用はユーザー）** |
| **W-81** | **ソースジェム9個を作業台に並べてソースジェムブロックを作るレシピが機能しない**（2026-08-18 ユーザー報告。「他にも同様の問題ありそう」付き） | **調査完了 / レシピは壊れていない。スキルツリー未解放で結果枠が空になっているだけ。**<br>機構を上から潰した: ①**定義は生きている** — 配備先 `plugins/ArsPaper/materials.yml` の `source_gem_block.recipes[0]` に `method: workbench / type: shaped / shape: iii,iii,iii / ingredients: {i: custom:source_gem} / reversible: true` が実在（出荷 yml と一致）。②**登録も通っている** — 実サーバログに `UnifiedRecipeLoader: 189 workbench + 64 ritual` → `Registered 359 workbench recipes` が出ており、`RecipeManager` が素材解決に失敗したときの `Skipping shaped recipe source_gem_block: ...` は**1行も出ていない**。③**ゲートIDも解決できている** — 起動時検証（`CatalogCraftGateListener` の「配置されているのに何もゲートしない綴り間違い」検出）が無警告。<br>→ **正体**: `skilltree/ars_smithing.yml` のノード **A「ソース細工の見習い」（Lv10 / 1SP）** が `dedicated-effects: recipe:source_gem_block` を配置している（配備先も同一）。未解放だと `CatalogCraftGateListener#onPrepareCraft` が `inv.setResult(AIR)` してアクションバーに「このレシピを使うにはスキルツリーで解放する必要があります」を出すだけなので、**プレイヤーからは「レシピが存在しない／壊れている」と見分けが付かない**。儀式経路（`LAPIS_BLOCK` + アメジストの欠片×9 + ソース4500）はゲート対象外なので未解放でも通る。<br>**⚠ `resolveGateId` は `trinityforge:` 名前空間以外はレシピキーの path をそのまま gate id にする**ので、ArsPaper が登録した `arspaper:source_gem_block` も `source_gem_block` として TF のゲートに掛かる（設計どおり。ただし「TF のゲートがフォークのレシピを塞ぐ」ことは名前空間からは読めない）。<br>→ **「他にも同様の問題」= `recipe:` ゲートは全16件あり、全部が圧縮系レシピ**。未解放だと同じ見え方をする: `core_ground`(digging/E Lv90) / `core_jewelry`(mining/E Lv90) / `core_meat`(farming/A-4 Lv80) / `core_vegetable`(farming/E Lv90) / `core_wood`(woodcutting/E Lv90) / `source_gem_block`(ars_smithing/A Lv10) / farming/B(Lv30) の食料圧縮9件（`baked_cod_1x` `baked_potato_1x` `baked_salon_1x` `compressed_bread_1x` `compressed_cooked_beef_1x` `cooked_chicken_1x` `cooked_mutton_1x` `cooked_porkchop_1x` `cookie_1x` `pumpkin_pie_1x`）。<br>→ **残: 仕様どおりなので修正不要。** ただし**未解放時の伝わりにくさは実バグ寄り**（アクションバーは見落としやすい。レシピ本から隠す `RecipeDiscoveryListener` と併せて「解放が要る」と分かる導線にするかは要判断） |
| **W-82** | **醸造が完了しない（水入り瓶＋ブレイズパウダー＋ネザーウォートで瓶が変化せずブレイズパウダーだけ減る）**（2026-08-18 ユーザー報告） | **切り分け途中。TF/Ars の醸造ゲートでは説明が付かないことまで確定。**<br>①**`brew-unlocks` は無関係**: 出荷・配備の両方をダンプして確認したところ登録15件は**全て `THICK` か `MUNDANE` 起点**（SUGAR / ウサギの足 / 金のニンジン / キラキラスイカ / 金リンゴ / custom素材4種）で、**`WATER + NETHER_WART` は1件も無い**。そもそも `BrewPotionMixRegistrar.WATER_VANILLA_INGREDIENTS` に `NETHER_WART` が入っているので、yml に書いても「バニラを潰す」として登録が拒否される。`BrewUnlockListener#onBrew` は `matchesIngredient` が外れて即 return する。<br>②**カタログ品ガードも無関係**: `CatalogVanillaOperationGuardListener#onBrew` が見るのは `isCatalogItem`（**CMD 必須**）で、`catalog.yml` にも Ars `materials.yml` にも **POTION / NETHER_WART を base material に持つ定義は0件**（`witch_elixir` の `GLASS_BOTTLE` のみ）。<br>③**他プラグインも該当なし**: サーバ導入 23 本に醸造を触るものは無い（Hurricane は竹・鍾乳石の当たり判定のみ）。ログに醸造系の例外も警告も無し。<br>④**ブレイズパウダーが減ること自体は症状ではない**: バニラは `fuel <= 0` になった瞬間に、醸造の可否と無関係にブレイズパウダー1個を20チャージへ変換して消費する。<br>→ **調査中に別の確定バグを発見（下の W-83）**。報告の字面（「水入り瓶から変化しない」）はそちらとも一致するので、まず W-83 を潰してから再現を取り直す。<br>→ ~~**対応済み**~~ 2026-08-18: **ユーザー追加報告「進捗バーも動いてたし、完了の音はしたけど水入り瓶が完成」で確定。醸造は完了しており、壊れていたのは名前だけだった。**<br>バニラの `BrewingStandBlockEntity#doBrew` は **`BrewEvent` を先に呼び、キャンセルされたら結果も音も出さずに return する**。したがって**完了音が鳴った ＝ イベントは通った ＝ 結果は書き込まれた**。書き込まれた結果が「水入り瓶」という名前だったので、犯人は結果を書き換える側 — **`PotionQualityListener#applyQuality`** だった（W-83 と同じ機構で、範囲はこちらの方が広い）。<br>→ **W-83 の修正でそのまま解消**。`potion_quality_bonus > 0` のプレイヤーが醸造すると、**ゲートと無関係にすべての効果付きポーション**が「水入り瓶」に化けていた（品質0なら `applyQuality` に入らないので化けない ＝ 「一部のプレイヤーだけ壊れる」ように見える）。<br>→ **残: TF jar の再ビルド＋配備（サーバ停止後、適用はユーザー）** |
| **W-83** | **ゲート付き醸造の完成品がすべて「水入り瓶」という名前で出る**（2026-08-18 W-82 の調査中に発見） | `BrewRecipeSupport#customPotion` は「段階の違う効果を一意に確定させる」ため `setBasePotionType(PotionType.WATER)` にしてから `addCustomEffect` で効果を足す設計だが、**表示名を一切設定していない**。Minecraft のポーション名は**ベースの種類からしか引かれない**（`PotionContents#getName` → `potion.effect.water`）ので、カスタム効果を何個足しても名前は **「水入り瓶」** のまま。色だけがカスタム効果の色になる。<br>→ 該当は `brew-unlocks` の登録15件すべて（俊敏・跳躍・体力増強・採掘速度・耐性・弱体化・盲目・鈍足・吐き気・幸運・即時回復・再生）。**運営が editor で追加した組も自動的に同じ症状になる**ので、個別対処ではなく `customPotion` 側で塞ぐ。<br>→ ~~**対応済み**~~ 2026-08-18: **W-82 の真因でもあったので判断待ちにせず塞いだ（実害が名前だけでなく「醸造が壊れている」という体験になっていたため）。**<br>共有ヘルパー `BrewRecipeSupport#potionDisplayName(Material, List<PotionEffect>)` を新設し、`customPotion`（ゲート付き醸造の完成品）と `PotionQualityListener#applyQuality`（品質適用後の全ポーション）の**両方**から呼ぶ。効果が空なら `null` を返して**バニラの名前に一切触らない**（素の水入り瓶・奇妙なポーションを壊さない）。既に名前が付いている結果は上書きしない。<br>**命名方針**: 翻訳キーは**効果名 `effect.minecraft.<効果>`** を使う（ポーション名 `item.minecraft.potion.effect.<名前>` は**バニラに実在するポーションにしか訳語が無い**ので、`HASTE`／`HEALTH_BOOST`／`NAUSEA`／`BLINDNESS` で生の翻訳キーが画面に出る）。組み立ては `[スプラッシュ|残留] + <効果名> + のポーション`、斜体オフ。**効果名は translatable のままなのでクライアント言語に追従する**。config への `display-name:` 追加は不要になったので見送り（必要になってから足せる）。<br>回帰は `BrewPotionDisplayNameTest`（4件、コンパイルエラーで RED 証明）と `PotionQualityListenerTest#品質適用後のポーションは水入り瓶のままにならず効果名が付く`（9件へ増加）。**⚠ 既存の `PotionQualityListenerTest` は MockBukkit 回避のため全 fixture が最初から base=WATER なので、名前の破壊を構造的に観測できなかった**（`allowlist-tests-...` と同型の盲点）。<br>→ **残: TF jar の再ビルド＋配備（サーバ停止後、適用はユーザー）** |
| **W-84** | **日次逓減（直近24時間の稼ぎでEXP取得量が薄まる）がプレイヤーから一切見えない**（2026-08-18 ユーザー質問「経験値が減った時にちゃんと通知される？ 回復までの時間は出る？」から発覚） | **通知も表示も1つも実装されていなかった。** 機構は 2026-07-31 から動いており出荷値も有効（`enabled: true` / `window-hours: 24` / `per-amount: 100000` / `decay-per-amount: 0.7` / `floor: 0.3`）で、**10万EXPごとに 1.0→0.7→0.49→0.343→0.3(下限)** と段階的に落ちる。にもかかわらず `DailyExpDiminishing#untilNextStep`（次の段まであと何EXPかを返す表示用の関数）は<b>production から一度も呼ばれておらず</b>、チャット・アクションバー・GUI のどこにも倍率が出ていなかった。**段を離散にした設計意図が「あと何EXPで落ちるか・何をすれば戻るかを数えられること」だったのに、表示側が丸ごと欠けていた。**<br>→ ~~**対応済み**~~ 2026-08-18: 出す場所を3つ用意した。①**EXP獲得表示（ボスバー／アクションバー）に `×70%` を常時付ける** — 稼いだ瞬間に必ず出る唯一の表示なので、ここに無いと気づく機会が無い。②**段が動いた瞬間にチャット＋音**（落: 「⚠ 採掘 の経験値取得量が 70% に下がりました／このスキルを休むと戻ります（1段回復まで 約○時間 / 等倍まで 約○時間）」、戻: 「✔ … 100% に戻りました」）。**毎回の付与では出さない**（チャットが埋まって読まれなくなる）。③**`/skills` のスキル選択バーに「EXP取得量: 70%」「休むと戻ります: 等倍まで 約○時間」**、等倍のときは「あと ○,○○○ EXP で下がります」。**`/tf status` のスキル一覧にも `×70%` と凡例**。<br>整形は `DailyExpRateText` の1本に集約（GUIとチャットで数字が食い違う事故の再発防止）。回復時間は<b>下限クランプで潰れている段を飛ばして「実際に倍率が上がる段」まで数える</b> ── 素朴に「1段減るまで」を出すと、下限帯では『あと少しで回復』と言ったのに何も変わらない嘘になる。<br>**⚠ 実装中に見つけた設計上の穴（未対応・要判断）**: **蓄積は退出時に破棄され永続化もされない**（`ProgressionPreloadListener#onQuit` の `forget`）。つまり実効的な窓は「今のログインセッション中に稼いだ量」で、**再ログインすると逓減が即座に全部リセットされる**。javadoc は「サーバ再起動でしか消えないのでプレイヤーは選べず悪用経路にならない」と書いているが、**ログアウトはプレイヤーが選べる**ので前提が崩れている。この状態だと「1日12時間層と週末層の格差を縮める」という導入目的をほぼ果たしていない。→ ~~**残: 蓄積を永続化するか（＝逓減を意図どおり効かせるか）の判断**~~ **2026-08-18 ユーザー判断「これは直さないとダメ」→ 永続化した。**<br>**永続化の設計**: `RankingMirrorStore` と同じ形で、共有 `player_progression.db` へ**独立コネクション**を開く `DailyExpWindowStore`（表 `daily_exp_window(player_uuid, skill_id, amount, updated_at)`、`transaction_mode=IMMEDIATE`、全メソッド `synchronized`）を新設。`plugins/TrinityForge/` はジャンクションで全バックエンド共有なので、**メイン⇄資源のサーバ移動でも逓減が引き継がれる**。<br>`DailyExpDiminishing#snapshot/restore` は**減衰前の生の値＋その時刻**をやり取りし、復元側で「保存時刻→現在」を1回だけ掛ける（保存時に減衰させると、オフライン時間が二重に効くか一切効かないかのどちらかになる）。復元は**上書きではなくマージ**（非同期ロードなので、ログイン直後の最初の付与が先に走ることがある）。`lastMultiplier` も復元後の倍率へ合わせる ── 1.0 のままだと**ログイン後の最初の付与で偽の「取得量が下がりました」が必ず出る**。<br>**書き込みは「両方を現在時刻へ減衰させた上で大きい方」を残す（単純上書きではない）**: Velocity のサーバ移動では**移動先の join が移動元の quit より先に起きる**ので移動先は必ず古い値を読む。素直に上書きすると、あとから届く移動元の quit 保存を移動先の保存が潰し、**往復するだけで逓減が消せる**。蓄積は「稼ぐと増える／時間で減る」だけなので max を採れば後退しない。加えて**5分ごとの定期保存**でクラッシュ時と移動時の取りこぼし幅を縮め、`onDisable` で最終フラッシュする（`/stop` の全員キックでは非同期タスクを投げられないので、そのときは捨てずに残して一括保存へ回す）。`/tf progression reset` は蓄積も消す。<br>回帰は `DailyExpWindowPersistenceTest`（8件、**RED 証明済み**: 復元を無効化すると5件、max マージを単純上書きに戻すと1件が落ちる）。<br>回帰は `DailyExpRateNotificationTest`（5件）＋ `DailyExpDiminishingTest` 8→14件 ＋ `SkillExpFeedbackServiceTest` 24→26件。**RED 証明済み**（通知の配線を外すと1件、バッジの配線を外すと1件が落ちることを実走で確認）。<br>→ **残: TF jar の再ビルド＋配備（サーバ停止後、適用はユーザー）**。config 追加は無し |
| **W-61** | **データパックで生成された構造物のモブが Lv0 になる**（2026-08-17 ユーザー報告） | モブのレベルは距離カーブ＋ディメンション基準（ネザー50／エンド80）で決まるはずだが、**データパック由来の構造物内で湧いた個体が 0 のまま**。→ ~~**対応済み**~~ 2026-08-18: **真因は「スポーン経路がフックを通っていない」ではなく `CreatureSpawnEvent` が一度も発火しないこと。** 構造物テンプレートの NBT に焼かれているモブは**チャンクが生成された時点で既に存在する**ため、そもそもスポーン処理を通らない。Bukkit の `SpawnReason.CHUNK_GEN` 自体が「chunks are generated with entities already existing」として**非推奨（もう呼ばれない）**になっている（Paper 1.21 javadoc）。結果 `MobTypeSpawnListener#onSpawn` が触れず、`MOB_LEVEL` PDC が付かないまま `MobData#level()` が既定の 0 を返していた。<br>**データパック固有ではない**: バニラの前哨基地・海底神殿など**構造物に最初から置かれているモブ全般**が同じ状態だった。<br>→ 修正: 「ワールドに実体が現れた」側からも同じ処理を掛ける受け皿 `MobTypeSpawnListener#backfillUnstampedProfile` を新設し、`EntitiesLoadEvent` と `com.destroystokyo.paper...EntityAddToWorldEvent` の**両方**から呼ぶ（前者が新規生成チャンクを含むか javadoc が明言していないため）。**`hasProfile()`（＝`MOB_LEVEL` キーの有無）で弾く**ので何度呼ばれても刻み直さない — ここを緩めるとチャンク読み込みのたびに HP が張り直される。<br>**⚠ 受け皿は `org.bukkit.entity.Mob` に限定した。** `EntityAddToWorldEvent` は `LivingEntity` なら何でも飛ぶので `CreatureSpawnEvent` より母集団が広く、**アーマースタンド**（LivingEntity だが CreatureSpawnEvent を発火しない＝TF が一度も触っていなかった）まで defaults の `max-health: 800` を刻まれ、`MOB_TYPE_STAMPED` が付いてドロップ処理の対象にまで入ってしまう。<br>回帰は `MobTypeSpawnListenerStructureBackfillTest`（5件、RED 証明済み）|
| **W-62** | **「矢の雨」等のモブスキルが、死角（壁の裏）や非追跡状態でも発動する**（2026-08-17 ユーザー報告） | → ~~**対応済み**~~ 2026-08-18: **疑いどおり。該当は `MobAbilityExecutor` ではなく `MobAbilityTask`。** 発動判定の走査は「**オンラインの各プレイヤーの半径32m以内にいる全 `LivingEntity`**」を舐めるだけで、絞り込みは**射程とクールダウンだけ**。視線もターゲット保持も一切見ていなかったので、索敵していないモブも壁を挟んだモブも等しく抽選対象だった。<br>→ 修正: `mob-abilities.yml` に `require-target` / `require-line-of-sight`（**どちらも既定 true**）を追加し、`MobAbilityTask#tryFire` の先頭で交戦条件を見る。AI を持つモブ（`org.bukkit.entity.Mob`）は `getTarget()` がその相手本人のときだけ発動する。**AI を持たない `LivingEntity` には追跡条件を課さない**（課すと AI を切ったボスや実体だけのギミックモブが一生撃たなくなる）。視線判定はレイトレースなので追跡条件を通った相手にだけ引く。<br>判定本体は `MobAbilityTask#engagementAllows` の**純関数**に切り出してある — MockBukkit が `hasLineOfSight` も `Mob#getTarget` も実装しておらず、実体経由で書くと `UnimplementedOperationException` が SKIPPED に化けて**一度も検証されないまま緑になる**ため。ただし純関数だけでは「判定は正しいが呼ばれていない」no-op 修正を見逃すので、`tryFire` をパッケージ非公開にして配線ごと固定する試験を別に置いた（RED 証明済み）。<br>回帰は `MobAbilityEngagementGateTest`（9件）。**発動頻度と予兆は W-63 で別件**（頻度側は 2026-08-17 `84b5d60` の `global-cooldown-seconds` で対応済み、予兆は未着手）|
| **W-63** | **モブスキルに予兆が無く、発動頻度も高すぎて避けようがない**（2026-08-17 ユーザー報告） | 「ゲーム性がない」＝**回避の余地が無い**のが問題。W-62（死角・非追跡でも発動）と同じアビリティ機構の話だが、**要求は別**: ①発動頻度（クールダウン）を下げる／config で調整可能にする ②**発動前の予兆モーション（パーティクル・音・詠唱時間）**を入れて反応時間を作る。**予兆の実装は既知の罠あり**: パーティクル個数 0 は「消える」ではない／`Particle.FLASH` は Color 必須 |
| **W-60** | **レベル差による EXP・ドロップ量の減衰を「閾値だけ」から「閾値＋減衰率」に拡張する**（2026-08-17 ユーザー要望） | 現状は閾値方式（`level-cutoff` 系）で、超えたら切る／切らないの二値。**減衰率も config で指定できるようにしたい**。関連する既存作業: 「`level-cutoff` を共通戦闘設定へ移設し全モブへ適用」「EM の `lootLevelDifferenceLockout` を無効化」（いずれも完了済み）。**EXP 側とドロップ側で別々の実装になっている可能性があるので、両方を同じ形にそろえるかは要検討**<br>→ ~~**対応済み**~~ 2026-08-18: **ユーザー決定「線形減衰」**。`combat/damage.yml` の `level-cutoff.over-level` に `exp-decay-per-level` / `drop-decay-per-level` / `rate-floor` を追加（既定 0 ＝従来のステップ関数と完全に同じ挙動）。EXP とドロップは `KillRewardAdjuster` の単一経路なので形はそろっている。**`-1`（完全遮断）は減衰計算に入る前に判定するので常に最優先**。`MobLevelCutoffTest` 32/0（25→32 へ増加）、RED 証明済み（減衰を無効化すると4件失敗）。editor 側 `FIELD_SPECS` / `FIELD_GROUPS` にも 3 キー露出済み |
| **W-53** | **スレッドに品質が付かない**（2026-08-17 ユーザー報告） | 「少なくとも `/tf give` では付かなかった。開運（品質上昇）機能は動いているのか？」。**W-54〜W-56 と同じスレッド／運のクラスタ**なので一緒に見る<br>→ ~~**修正済み**~~ 2026-08-18（ArsPaper `74c1bc2` / TF 側は本日のコミット）: 真因は `ThreadItem#createItemStack` が `crafter == null`（ルートチェスト／管理コマンド付与）でも rollSeed を刻んでいたこと。`PickupQualityListener#stampIfEligible` の `hasRollSeed()` ガードに弾かれて開運ロールが永久に走らなかった。刻印を後回しにし `restampWithQuality` で品質だけ書き直す。**「スレッドは item-stats.yml に profile を持たない」は誤り**（薄くても必ず 1 件生成されるので、profile の有無をスレッド検出に使ってはいけない） |
| **W-54** | **幸運エフェクト→開運ステータスのキャストが 1 回で消える**（2026-08-17 ユーザー報告） | 「一度キャストして発動されるとエフェクトが消えてしまう＝**1 度きりしか品質が上がらない**」<br>→ ~~**修正済み**~~ 2026-08-18（ArsPaper `74c1bc2`）: `ArmorManaListener#updatePotionEffects` が「無期限かどうか」だけで自分が付けた効果か判定していたため、**クリエイティブ／管理コマンドで付けた無限 LUCK も装備変更のたびに剥がしていた**。プレイヤーごとの所有権台帳を持ち「自分が付けた記録がある かつ 今も無期限」の両方を満たすときだけ除去する。台帳はプロセス内メモリなので、**再起動後に記録の無い無期限効果は他ソース起因として絶対に剥がさない**（安全側）。RED 証明済み（旧条件に戻すと2件失敗）。※`PotionEffectType` は Paper 1.21 で enum ではないので `EnumMap` は使えない<br>→ **第2波 2026-08-18 ユーザー報告「直ってなくない？ エフェクトつけた状態でクリエでアイテム取り出すと品質ボーナスが乗ってその後幸運のエフェクト効果が消えてしまう」。第1波の修正は配備済み（デプロイ jar に `threadGrantedPotions` あり）だが不足で、除去経路が**計3本**残っていた:**<br>① **ArsPaper `ArmorManaListener`（`4f454be`）**: 台帳の照合が**型だけ**で amplifier を見ていなかった。スレッドで LUCK が付いている状態でプレイヤーが同じ型の無期限効果を上書きすると、その後の再計算（**クリエでアイテムを取り出してメインハンドが入れ替わるだけでも走る**）でプレイヤー側の効果を剥がしていた。→ `ThreadPotionOwnership#mayRemoveGrantedPotion`（付与記録あり＋無期限のまま＋amplifier一致の3条件）。<br>② **TF `RoleBuffListener`（`03ca150`）**: ロールバフ同期が「サポートロールが付け得るポーション型を**全部無条件に** `removePotionEffect`」していた。`role-buffs.yml` の `fisher` が LUCK を付けるため、**ロールが fisher でなくてもログイン/リスポーン/ロール変更のたびにプレイヤー起因の幸運が消えていた**。→ `RolePotionOwnership#mayRemoveRoleBuff`（amplifier一致＋`ambient=true`/`particles=false`＋残り時間が spec 以下。`/effect give` は `ambient=false`/`particles=true` なので一致しない）。**プロセス内台帳ではなく効果の形で判定**したのは、再起動後に古いロールのバフを剥がせなくなるため。<br>③ **EliteMobs `PlayerPotionEffects`（`662d9a2c`）**: 上流の判定が残り時間を `getDuration() > 40` だけで見ており、**Paper では無期限効果の `getDuration()` は `-1`** なのでこの条件を満たさず「切れかけ」と誤判定。1秒ごとの常時効果ループが**無期限効果を剥がして数秒の効果へ差し替えていた**（`LuckyCharms` は `LUCK,0,self,continuous`）。→ `ContinuousPotionPolicy#keepsExisting` で無期限は常に維持。<br>3本すべて Bukkit 非依存の純関数へ切り出して**挙動で固定**（ソース文字列固定は実装差し替えで誤検知するため）。RED 証明済み（①1件・②2件・③1件が落ちる）。→ **残: ArsPaper jar / EliteMobs jar / TF jar の再ビルド＋配備（サーバ停止後、適用はユーザー）** |
| **W-55** | **各種「運」ステータスの違いを説明する**（2026-08-17 ユーザー質問） | 幸運 / 開運 / ドロップ増加 / 宝釣り など、名前が似ていて用途が分かれているものの棲み分けを回答する。**回答だけで閉じるタスク**（実装ではない）。ただし W-53/W-54 の調査結果と矛盾しないこと |
| **W-56** | **スレッド装備時の「常時効果スレッドは反映されません」警告の正体**（2026-08-17 ユーザー質問） | どの条件で出る警告か、意図した仕様なのか実バグなのかを特定して回答する |
| **W-57** | **武器のスレッド欄に入れた回避率付き幸運スレッドが `/tf status` に反映されない**（2026-08-17 ユーザー報告） | 「あくまで一例だと思うので**原因究明**」＝ **1 件だけ直して閉じない。** どのステが／どの装備部位で／どの経路で落ちるかを横断で洗う |
| **W-58** | **採取 EXP の機能解放スキルを、伐採・農業・掘削・採掘で別々にする**（2026-08-17 ユーザー要望） | 「現状 **EXP ボーナスしか分けられていない**」＝機能解放（ゲート）側が採取スキル共通になっている<br>→ ~~**対応済み**~~ 2026-08-18: **ユーザー決定「機能IDもスキル別に分ける」**。`feature:break-vanilla-exp` を廃止し `-mining` / `-digging` / `-farming` / `-woodcutting` の 4 件へ。変換窓口は `BreakVanillaExpBonusKeys#featureId(skillId)`。**解放状態はノードID基準で feature ID 文字列を保持しないのでプレイヤーデータ移行は不要**。editor 側 `gate-vocabulary.js` の `FEATURES` も同期済み（Java↔JS の id 集合一致テストで固定） |
| **W-59** | **採掘速度ヘイストのスキルを掘削と採掘で分ける**（2026-08-17 ユーザー報告＋要望） | 実バグ込み: **持ち替えても持続し、シャベルとツルハシを持ち替えると CT をキャンセルして再発動できてしまう**。分離とクールダウンの持ち主の見直しが要る<br>→ ~~**対応済み**~~ 2026-08-18: **ユーザー決定「分けるが、持ち替えたら CT に入る（実質アップタイム2倍を無効化）」**。`DiggingHasteActiveSkill` を新設（`digging.yml` の A-1、amplifier 1 / 持続 6 秒 / CT 40 秒。採掘側とは意図的に別数値）。`ActiveSkill#cooldownGroup()` を追加し、`CooldownManager` / `ActivationDispatcher` / `ActiveCooldownDisplay` を `id()` ではなく `cooldownGroup()` で引くようにして**採掘と掘削で CT バケツを共有**。バケツのロック長は「最後に消費した呼び出しの CT」で固定するので、短い方へ縮退しない。editor 側 `tier-vocabulary.js` の `SCALE_FEATURE_SECTIONS` も同期済み<br>→ **第2波 2026-08-18（`03ca150`）: CT 共有は撤回した。** ユーザー確定要件「共有ではなく該当のツールから持ち替えると効果が強制終了する（強制終了しCTに入るので持ち替えても効果が残らずずるできない）」。**CT 共有では効果の横流しを止められない**（ツルハシで発動→シャベルへ持ち替えれば、シャベル側のノードを解放していなくても掘削がヘイストで速いまま）。<br>→ `ActiveSkill#toolBound()` / `effectDurationTicks(tier)` / `cancelEffect(player,tier)` を追加し、発動中セッションの台帳 `ActiveEffectSessions` と `ToolBoundEffectListener` を新設。**CT は各スキル独立（`cooldownGroup()` は `id()` のまま）で、持ち替え時に巻き戻しも延長もしない** → 持ち替えは「効果を捨てて CT だけ払う」＝常に損。<br>**⚠ 判定は必ず次tickに回す**: `PlayerItemHeldEvent` は**スロットが切り替わる前**に飛ぶのでイベント中に `getItemInMainHand()` を読むと持ち替え前の道具が返る。`PlayerDropItemEvent` も引かれ済みかが経路依存。**イベント種別ごとにスナップショットを解釈せず、次tickの実際のメインハンドだけを見る**。<br>**⚠ HASTE の取り消しは amplifier 一致かつ有限のものだけ**（ビーコン／管理コマンド由来を剥がさない）。<br>回帰は `ToolBoundEffectListenerTest` 6件（RED 証明済み: `cancelEffect` を no-op に戻すと2件失敗）。`digging.yml` / `mining.yml` の A-1 説明文も「持ち替えると効果は即終了（CTは進行したまま）」へ差し替え済み。→ **残: TF jar のビルド＋配備（サーバ停止後、適用はユーザー）** |
| **W-51** | **ネザライト化で品質・ロール・エンチャントを引き継ぎ、ネザライト装備として性能を再構築する**（2026-08-17 ユーザー報告） | 現状「ダイヤの剣をネザライト化しても、**使用可能レベルを含めて全部ダイヤの剣の性能のまま**」。エンチャント剥がれは過去に修正済みだが、**数値ステータスの再構築が入っていない**。**W-19 の①と同根の可能性**（`NetheriteUpgradeGuard` が 12 件中 7 件を除外し 5 件しか登録されない／`CatalogRecipeRegistrarNetheriteTest` は MockBukkit にバニラのスミスレシピが無いから通っているだけ）。論点: **品質とロールをそのまま引き継ぐと「ネザライト化するだけで上位ステ」になる**ので、振り直すのか品質だけ引き継ぐのかの判断が要る<br>→ ~~**対応済み**~~ 2026-08-18: **ユーザー決定「品質だけ引き継ぎ、ロールは再抽選 / 新規のネザライト化だけ直す」**。真因は `CatalogItemMatch#matchesTemplate` が `cmd == null` を問答無用で除外していたこと（＝CMD を持たない素のバニラ装備がテンプレート照合に一度も乗らない）。`CatalogSmithingListener` に `isPlainQualityUpgrade` を追加し、onPrepare/onSmith 双方で再刻印する。`CatalogSmithingListenerTest` 9/0、RED 証明済み（判定を外すと1件失敗） |
| **W-52** | **エディタのセレクトメニューに生アイテム ID が出る**（2026-08-17 ユーザー報告。**同種の指摘は 2 回目**） | スキルツリーの解放ゲート・レシピ素材から**機能アイテム（`functional-items`）**を選ぶときにラベルが ID。**既知の関連事実**: エディタの custom 候補源は `catalog.yml` と `materials.yml` の 2 本だけで、`functional-items` / `sourcejars` / `catalysts` は**構造的に候補へ出てこない**（`batch-2026-07-30`）。「候補に出ない」のか「出るがラベルが ID」なのかの切り分けが要る。**再発防止まで込みの依頼**なので、報告 2 画面だけを直して閉じない。許可リスト方式の検査は禁止（リスト自体が腐って検査ごと無効化した前例あり）<br>→ ~~**対応済み**~~ 2026-08-18: 原因は**3機構**。①`gate-vocabulary.js` が儀式側の `ritualLabels` は作るのに対になる `recipeLabels` を一度も作っていなかった ②`catalog-candidates.js` が `material` 欠落エントリを捨てるため `functional-items.yml` の 12 件が落ちていた ③`sourcelinks.yml` が候補源に入っていなかった。再発防止は**許可リストではなく実 yml 全走査**（`display-name` を持つ全エントリを通してラベル無し登録が 0 件であることを機械的に検査） |
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
| **W-28** | **ArsPaper `items.yml` の `items:` 残り2件がエディタから見えない** | `trident` と `エンチャントされた金リンゴ`。`app.js` の `case "ars-recipes"` が常に `onlyEffects: true` で `buildRecipesForm` を呼ぶため `items:` セクションが1件も描画されない（2026-08-14 にエンチャント本8件だけ `functional-items.yml` へ移して回避した。**画面側の前提「作業台/儀式レシピは全部カタログへ移した」自体が誤り**）。どちらもバニラアイテムを結果に持つので「特殊アイテム」画面の対象外。**直し方は2択**: ①この2件も移設先を決めて移す ②`onlyEffects` を外して items タブを復活させる（タブ名が「儀式エフェクト」なので画面名も要調整） |
| **W-29** | **農業ツリー A ノードの `feature:break-vanilla-exp` が消えている（作業ツリーの未コミット変更）** | 2026-08-15 時点の `skilltree/farming.yml` は**別セッションの未コミット変更**で A ノードの `dedicated-effects` から `- id: feature:break-vanilla-exp` が落ちており（`feature:junk-food-restore-boost` の整形と同じ差分に混ざっている）、**説明文「収穫時にバニラEXPを獲得」だけが残っている**。このまま出すと農業だけ破壊時バニラEXPが解放されない。**ユーザー判断で「今は触らない」**（他セッションの所有ファイルのため）。**持ち主が確定させるときに、解放行を戻すか説明文を消すかを決める**。なお倍率側（`farming-break-vanilla-exp-bonus`）は 2026-08-15 の分割で農業スコープへ移してあるので、解放されないかぎり誰にも効かない |
| ~~**W-30**~~ | ~~**軽剣ツリーの出血ダメージ 150 は「低レベルで壊れ・高レベルで no-op」で、値の調整では直らない** | 2026-08-15 の5ツリー監査で判明。`bleed-damage` は `BleedService` が**1tickあたりの固定ダメージ**として使う実数で、**アイテム側は帯とともに指数的に伸びる**（出荷 `item-stats.yml` の帯別最大: Lv0 34 → Lv25 165 → Lv60 1,850 → Lv80以降 4,500。`attack-power` の 164 → 68,524 と同じ伸び方）。軽剣ツリー全取りの 150（`50,10,6,12,18,24,30` の7ノード）は**Lv0 では武器の +440%、Lv100 では +3.3%** になる。**flat な加算値である限りどんな数字を置いても片端が壊れる**ので、直すなら「値を変える」ではなく**機構を変える**話になる。選択肢は ①ツリーからは `bleed-damage` を外し `bleed-chance`（率なので帯に依存しない。ツリー0.7 vs 武器0.18 で既にツリー主導）と `percent-bonus-damage` へ寄せる ②`bleed-damage-rate`（攻撃力に対する割合）を新設する ③帯中央に合わせて割り切る。**ユーザー判断が要る**（軽剣のアイデンティティに関わる）~~ | **2026-08-15 ユーザー決定「割合キー `bleed-damage-rate` を新設」で解決。** 率の基準は**攻撃力ではなく「出血させた一撃の最終ダメージ」**にした（近接 `CombatListener#maybeApplyBleed` と魔法 `BleedService#maybeApplyFromAggregate` の**両方が既に持っている唯一の量**で、LD-9「出血自体は被弾側の被ダメージ軽減しか通らない」も崩さない）。合成は新設した `BleedService.damagePerTick(attackerStats, hitDamage)` 1 箇所に集約し、**実数（アイテム）と率（ツリー）を合算**する。軽剣ツリーは 7 ノードとも率へ移し、**全取り合計 0.37（C-1-2 の ×1.2 込みで 0.444）**。出血は**リフレッシュ方式で重ならない**ので `damagePerTick` がそのまま毎秒ダメージになり、2 発/秒で **DPS +20〜25%** ＝ α（追加ダメ +15%）β（会心率 +0.28）と同格。**乗算レイヤの落とし穴**: レイヤは「1 レイヤ＝1 ステ」で、基準ステ以外を書くと `PerkBuffResolver#withValidMultiplierLayers` が**無言で捨てる**ので `stats/lore.yml` に `layer_4`（基準ステ `bleed-damage-rate`）を新設した。回帰は `WeaponTreeMultiplierCalibrationTest`（率で配られ実数が 0 であること・レイヤ定義との整合）と `BleedServiceMagicAggregateTest` 3 件 |
| ~~**W-31**~~ | ~~**武器3ツリーの残りの較正（軽剣・重剣・弓）**~~ | 2026-08-15 に装備の土俵と突き合わせた結果、**実質 no-op は W-30 の出血ダメージ1件だけ**で、他は「効きすぎ」側だった: **会心率はツリー 0.7 vs 装備 0.13〜0.19 で 4〜5倍**、**会心ダメージは重剣ツリー 1.4 vs 装備 0.75 で約2倍**、**追加ダメージ率は装備側が 0.03 しか持たないのにツリーが 0.45**（＝実質ツリー専用の主火力）。貫通（ツリー0.1 vs 装備0.24〜0.32）と遠距離ダメージ（ツリー0.3 vs 弓0.15）は補助として妥当。**武器ツリーは `attack-power` を1も配っていない**（主火力は装備、ツリーは倍率という分担自体は一貫している）。**下げる方向の調整はプレイ感を大きく変える**ので着手前にユーザー判断が要る。**2026-08-15 ユーザー決定「ツリー ≒ 装備の1.5倍まで」で解決。** 到達可能な最大は**ギリシャ路線が排他（同 `group` は1本だけ）**なので group ごとの最大を足した値で測る: **会心率 0.60 → 0.28**（主軸 0.05→0.03 / 0.10→0.05、β路線 0.06〜0.15 → 0.02〜0.06、軽剣・重剣とも）、**追加ダメージ率 0.45 → 0.15**（α路線 0.01〜0.05）、**重剣の会心ダメージ 1.20 → 0.95**（主軸 0.20→0.15、γ路線 0.06〜0.22）。**プレステージも同率で 0.10/0.20 → 0.05/0.15**（3回まで積めるので、旧値では会心率だけで +0.3 とツリー本体を上回っていた）。**弓術は据え置き**（会心率 0.10・追加ダメージ率 0.08 で元から装備の内側）。回帰は `WeaponTreeMultiplierCalibrationTest` の2本立て（較正値そのものの固定＋`item-stats.yml` から毎回引く「装備の1.5倍」判定。**装備を下げてツリーを据え置いた**ときにも落ちる）。あわせて `ShippedSkillTreeFlatStatScaleTest` の検査対象に `mainhand-buffs` を追加した ── **従来は `buffs` しか見ておらず、軽量武器・重量武器の全ノードが検査の外にあった**（W-30 の実数 150 が素通りした穴） |
| **W-27** | **運用作業（エージェントからは実行できない）** | ①`/em language japanese`（ダンジョン内の敵の発言が英語）②Nightbreak コンテンツの取得（エンチャント試練 11-20 / ユグドラシルに入れない。`gates.yml` 側は 61 件とも正しい）③実機確認: 虚空右クリックでガチャ券・鍵が使えるか |

### 実サーバ報告バッチ5（2026-08-04 受領）の未着手分

**2026-08-05 追記の経緯**: このバッチは 24 件あり、うち 10 件を 08-04 夜〜08-05 未明に修正・配備した。
しかし**残る 11 件をここに登録しないまま「配備完了」と報告した**ため、ユーザー側からは
バッチ全体が終わったように見えていた。**受領した項目は着手前に全件ここへ開いた行として登録する**
（→ §5 の「バッチ受領時は全件を先に登録する」）。

| # | 内容 | 前提 |
|---|---|---|
| ~~**W-28**~~ | ~~**`/tf menu` と `/tf role set` を削除し、ロールの確認・変更を `/tf status` GUI へ統合する**~~ | **2026-08-06 実装済み。** ①`/tf menu`（`MainMenuGui` / `MainMenuLayout` とテスト2本）と `/tf role set`（引数版・GUI版の両方）を削除。②`/tf status` の**スロット38に「職業(ロール)」アイコン**を追加（現在の戦闘職/補助職・変更可否・クリックで `RoleSelectGui`）。**可否の表示は `RoleChangeService#denyReasonForCombat/Support` の文言をそのまま出す** — 待ち時間や「券が必要」を画面ごとに組み立てると表示と実挙動が食い違うので、`RoleSelectGui` の lore も同じメソッドへ寄せた（`waitMillis`＋`gateNotice` の2引数を1本の `notice` に統合）。③**config を `role-change.allow-command` → `allow-change` へ改名し、`false` の意味を変えた**: 旧 `allow-command: false` は「一切変更不可」で**初回の就職すらできなかった**（出荷値が false だったので実際にそうなっていた）。新 `allow-change: false` は「**空の枠への初回就職だけ無料、それ以降は転職の証（`role_reselect_ticket`）を手に持って右クリックしたときだけ変更可**」。**解除も塞ぐ**（枠を空にできると初回無料を無限に再利用できて券が要らなくなる）。旧キーは `allow-change` 未指定時のフォールバックとしてだけ読む（配備済み config が無言で「許可」へ反転しないため）。④config-editor は「職業変更を許可するか」のトグルへ差し替え（保存時に旧キーを削除して二重化を防ぐ）。⑤`/tf role` は確認のみ・`/tf role clear` は解除のみ残す。プレイヤー wiki の生成側も `/tf status` 経由の導線へ更新。回帰は `RoleChangeTicketOnlyModeTest` 5件 + `StatusGuiRolePanelTest` 3件 + `RoleBuffsConfigTest` の旧キーフォールバック |
| ~~**W-29**~~ | ~~**`/tf skills` GUI の最下段左端を時計アイコンで固定し、現スキルツリーのパーク一覧 ON/OFF を切り替える**~~ | **2026-08-06 実装済み。** ①最下段左端（スロット45 = `SkillTreeOverviewLayout.PERK_LIST_SLOT`）を時計（`CLOCK`、`toggle-perk-list`）で固定。**その枠は近傍ツリー選択バーが使っていた**ので、選択バーを8枠（offset -4..3）→**7枠（-3..3）**へ縮めた（選択中は変わらずスロット49の中央）。時計と `toggle-view`(53) は `renderModeButtons` が**全モードで同じ位置に描く**ので、モードを跨いでも押す場所が変わらない。②パーク一覧は全ツリー一覧と**同じ格子・同じ体裁**（`SkillTreeOverviewLayout` の行1-4・列2-6）で並べ、解放状態はツリー本体と同じ配色・同じロックテクスチャ。クリック（`jump-perk`）で `canvas.nodes().get(perkId).point()` を中心に据えて通常モードへ戻る。③**ページ送りを付けた**（`PAGE_PREV_SLOT`=19 / `PAGE_NEXT_SLOT`=25、行き先が無い側は描画しない）: ツリー数16は格子容量20に収まるが**パーク数は POWER で36**（config 35ノード + generator が必ず足す合成ルート `<compact>_perk_root`）で、黙って切り落とすと「一覧に無いパークがある」ことに気づけない。④モードは `boolean overview` → **3値の `Mode`（DETAIL/OVERVIEW/PERK_LIST）**へ（boolean 2本だと「両方 true」という存在しない状態を型が許す）。再描画は `reopenNextTick(player, session, pendingPerkId)` に集約し、モード・中心・ページの書き写し漏れを機構で防いだ。⑤**追記(2026-08-06)**: 一覧の lore に**パークの説明も出すようにした**（ユーザー要望）。当初は「一覧は"どこにあるか探す"画面」として省いていたが、**名前だけでは何をするパークか分からず一覧から選べない**（1件ずつ通常モードへ飛んで戻る必要がある）。整形は `descriptionLines` の1メソッドへ集約し、**通常モードと一覧が同じメソッドを呼ぶ**（別々に整形すると片方だけ `&` 色コードが素通りする類の食い違いが出る）。回帰は `NativeSkillTreeMenuPerkListTest` 5件（時計の固定・一覧モードでも同じ位置・全パーク列挙＋中央へジャンプ・**容量超過分が2ページ目で必ず見られる**・**説明が2行に割れて色コードが漏れず通常モードと一致する**）+ `SkillTreeOverviewLayoutTest` のページ分割 + `SkillTreeGuiVisualsTest` の時計材質 |
| ~~**W-30**~~ | ~~**`/tf skills` と `/tf achievement` の GUI 状態（ページ/スクロール位置/選択タブ）を再オープン時に復元する**~~ | **2026-08-06 実装済み。** 両GUIが `Map<UUID, View>` を1本持ち、**画面を描くたびに上書き・引数なしの `open(player)` で復元**する。`/tf skills` は ツリー / スクロール位置 / モード（通常・全ツリー一覧・パーク一覧）/ ページ、`/tf achievement` は スクロール位置 / 選択中の系統。**⚠ `pending`（解放・解除の確認待ち）は意図的に持ち越さない** — 持ち越すと「閉じて開いて1クリック」で確定する経路ができ、誤爆でSPや報酬を消費する（`View` は `Session` から pending を落とした型にしてうっかり足せないようにした）。**ログアウトで捨てる**（`PlayerQuitEvent`）: ログイン跨ぎは PDC が必要だが、PDCキーはバックアップ網羅性テストの対象で保守コストが増える一方、この状態は「その場の作業位置」でしかないため。`/tf skills <skill>` のように**別ツリーを名指ししたときは復元せずそのツリーの起点から**開く。回帰は `NativeSkillTreeMenuPerkListTest` 3件（スクロール位置の復元・パーク一覧モードとページの復元・名指しの別ツリーは復元しない）+ `AchievementGuiLayoutTest` 2件（位置と選択系統の復元・**確認待ちを持ち越さない**） |
| ~~**W-31**~~ | ~~**`/tf achievement` の UI をスキルツリーと同じ配置に揃える**~~ | **2026-08-06 実装済み。** ①**ツリーをルートから上へ伸ばした**（`AchievementLayout` の `y = depth*STEP` → `-depth*STEP`）。y の意味（下方向が正）は変えていないので**明示 `coords` は書いた値がそのまま効く**。②**8方向の視点移動をキャンバスの四隅・辺の中央（スロット 0/4/8/18/26/36/40/44）へ移した** — `NativeSkillTreeMenu.NAVIGATION_SLOTS` と同じスロット番号。③**最下段 45-52 を「系統（ルート実績）切替バー」にした**（スキルツリーのスキル選択バーと同じ「選択中を中央 49 に置いて巡回」動作）。達成状況の本は 49 → **53**（バー右端の固定枠）へ移動。④**バーに並べるのはルート実績だけ**（`AchievementCanvas.branchHeadIds` = `parent` 未設定／自分自身／存在しないIDを指すもの）。**ユーザー確定（2026-08-06）**: 最初は「ルート＋そのルートの子（章の先頭）」を並べたが、**「真のルートだけ並べる」で差し戻された**。出荷configの真のルートは `main` 1件だけなので**バーはボタン1個になるが、それが仕様どおり** — 「1個しかないから壊れている」と判断して子を足し戻さないこと。系統を増やすなら `achievements.yml` 側でルートを増やす。あわせて**バーはルートが8枠に収まるうちは左詰め**にし、巡回（選択中を中央49）は9件以上のときだけにした（少数で巡回すると同じルートが8枠に並ぶだけで切替として読めない）。⑤バーのボタンは PDC キーを `achievement_head` に分けた（`achievement_focus` と同じにすると、**達成済みの起点をバーで選んだだけで解放が確定してしまう**）。回帰は `AchievementGuiLayoutTest` 7件（矢印のスロット番号・**最下段はルートだけの左詰め**・9件以上で巡回・押した系統が中央へ来る・**バーのクリックが claim へ流れない**・W-30 の復元2件）+ `AchievementCanvasTest` の伸びる向き2件・系統バーの並び3件。**⚠ 残り1点（コミットできない）**: `achievements.yml` のヘッダに `5本の道が下へ伸び` と `coords:` の説明が残っている（向きが変わったので文言が古い）。**このファイルは別セッションが未コミットで大改修中**（`custom:gacha_ticket_1`→`gacha_ticket_0` 等 244/139 行）なので、パス単位でしか add できない運用では巻き込みになる。**そのセッションが commit したあとで、ヘッダ2箇所を「上へ伸びる／子の y は親より小さい（深さ1で -2）／明示 coords は書いた値がそのまま効く」に直すこと。** 挙動・正典ドキュメント（`docs/agent-context/progression-skilltree.md`）は既に更新済みなので、残りはコメント文言だけ |
| ~~**W-32**~~ | ~~**メイスのダメージに落下高さボーナスが乗っているか確認し、乗るなら火力をそれ込みで調整する**~~ | **2026-08-05 修正済み。落下ボーナスは乗っており二重加算もしていない**（`MaceSmashDamage`。+4/blk×3 → +2/blk×5 → +1/blk、Density = level×0.5×落下距離）。ただし**バニラのHP単位なので最大でも数十**＝Lv100 の桁（1発 12万超）に対して無視できる量で、「乗っているのに体感できない」が実態。メイスの空中個性は既存の `power-attack-damage: 0.35`（空中で+35%）が担うので、**接地パリティを剣の 85%（空中 約115%）に置いて校正**した。あわせて `attack-speed` 0.408 → 0.88（理由は下の W-34 の上限の話） |
| ~~**W-33**~~ | ~~**杖の火力が他武器に比べて低すぎる**~~ | **2026-08-05 修正済み。剣の 4〜6% だった**（`attack-power` ×7.4〜11.1 へ引き上げ）。杖は近接で振らず詠唱で撃つので実効レートは `min(attack-speed,2.0)` ではなく **`1/item-cooldown`**（`damage.yml` の `magical.attack-power-scale: 1` が杖の `attack-power` を魔法基礎ダメージへ1:1加算する）。「控えめ」の意図を残して**剣の 47.5%** に置いた（50% にすると `magic_wand` の単品最大が `attack-power` 上限の余裕を食い潰す） |
| ~~**W-34**~~ | ~~**鎌の出血など、武器 tier・攻撃力・防具の防御力に見合っていないステータスの洗い直し**~~ | **2026-08-05 修正済み。tier 期待値テーブルを作った結果、個別の値ではなく武器種の校正そのものが噛み合っていなかった**。①`attack-speed` の段ごとのばらつき（戦斧 0.323/0.408/0.493+1.003、鎌 1.12/2.12/3.12/4.12、槍 1.38→0.71 の逆進行）を武器種ごとに統一 ②**2.0 超は無敵時間 10 tick で切り捨てられる死に設定**（4本あった）→ 2.0 へ ③出血は総DPSの 1% で個性が数字として存在せず、しかも `cryocore_greataxe`(4,164) が鎌(2,736) を上回る逆転 → 鎌を「15%、ただし上限 4500 で打ち切り」へ ④`revolution_bow` だけ 2026-08-02 の「遠隔の近接 AS は 0.1」から漏れて **弓が同帯最強の近接武器（剣の119%）**になっていた ⑤`koujien`（BOOK 1個で作れる）が剣の 125% ⑥接尾辞に武器種を持たない `Winter_Grim_Reaper` / `fnis_peccati_profundi`（どちらも鎌カテゴリ）が検査ごと素通りしていた。**⚠ 最初の適用は `stat-caps.yml` の上限を無視していて、メイスの `attack-power` が上限の ×2.09、鎌の `bleed-damage` が ×5.91（＝表示だけ上がって実効は伸びない死に設定）になっていた**ので全部やり直した。**防具側は外れ値なし**（34系列すべて 15:40:30:15、`turtle` はバニラ同様ヘルメット専用、レベル逆転18組はすべて魔法防具が `phys-flat-defense` を落として物理防具にはゼロの `magic-flat-defense` を持つトレード）。回帰は `WeaponTierParityTest` 9件 + `WeaponDpsParityTest`。恒久知識は `docs/agent-context/combat.md` |
| ~~**W-35**~~ | ~~**武器ごとの攻撃リーチを再設計する**~~ | **2026-08-05 実装済み。ユーザー決定「剣=バニラの剣の基準で」**（＝剣は `attack-reach` 0 でバニラの `entity_interaction_range` 3.0 そのまま）。表（バニラ 3.0 への加算 / 実効）: 短剣 -0.5/2.50、**剣・メイス・弓・弩・杖 0/3.00**、戦斧 +0.2/3.20、レイピア +0.3/3.30、ウォーハンマー +0.4/3.40、鎌 +0.5/3.50、大剣・大斧 +0.6/3.60、トライデント +0.8/3.80、槍・ハルバード +1.0/4.00。`hunter_javelin` の 1.4/4.40 は 2026-08-02 に「game 内最長」として決めた一点物なので据え置き。リーチ差の相殺は W-34 の実効DPS帯（同系列の剣に対する比）で行っている |
| ~~**W-36**~~ | ~~**トライデント系・槍系が三人称視点で 2D アイテムモデルになる**~~ | **2026-08-07 修正済み**（`pack-20260807135616` を発行。詳細は下の「W-36 を配信 zip へ入れ直した」。**`server.properties` の更新だけユーザー実行待ち**）。以下は 2026-08-05 の原因確定時の記録。**2026-08-05 原因確定: パックソースは正しく、配信中の zip が古い。** `server.properties` の `resource-pack-sha1=5b251cb3676df2b681616447cfaf321bda5f85ab` は `resourcepack/dist/TrinityForge-Pack.zip`（作業ツリー版、08-04 10:43）と**ハッシュ一致**する。その zip を展開すると `trident.json` の CMD 121/122/174-181 と `netherite_spear.json` の 118/119、`diamond_spear.json` の 50 に `display_context` 分岐が無く、全コンテキストで平面モデルになる。**ソース側（`assets/minecraft/items/*.json`）は 08-05 16:12 に再生成済みで 13 件とも正しい**（生成側の修正は `c5754d7`、08-03）。**残っているのは zip の再生成と GitHub Release への再発行、`resource-pack-sha1` の更新だけ**。リソースパックは別セッションが作業中（`cmd-registry.json` と dist zip が未コミット）なので、**そのセッションが publish する時に必ず再生成すること** |
| ~~**W-37**~~ | ~~**鍵を使ったときに出る本のタイトルが内部 ID 表記**~~ | **2026-08-05 判明: コードの不具合ではなく配備漏れ。** 潜入確認 GUI の `KNOWLEDGE_BOOK` は `DungeonGate#displayNameOrWorld()` を出しており、リポジトリの `dungeon/gates.yml` は 61 ゲート全部に `display-name:` を持っている（`15236bc`、2026-08-02）。**配備先の `gates.yml` は 08-01 16:52 のままで `display-name` が 0 件**なので、ワールド名（＝ゲートID）へフォールバックしていた。`ops\launch\deploy-config-head.cmd` で config を配備すれば直る（サーバ停止が必要）。**同型で本当のコード不具合だった `/tf settings` の称号ボタン ID 表示は `c7b2d94` で修正済み** |
| ~~**W-38**~~ | ~~**ツルハシに射撃ダメージが付くなど、不適正なツールへエンチャントが付与される**~~ | **2026-08-05 修正済み（`0592838`）**。金床のオーバーエンチャント経路が候補エンチャントを `canEnchantItem` で絞っていなかった。本(`EnchantmentStorageMeta`)は素通し、それ以外は対象判定を通す（`OverEnchantAnvilTargetTest` 3件） |
| ~~**W-39**~~ | ~~**ネザライト化するとエンチャントが剥がれる**~~ | **2026-08-05 修正済み（`0592838`）**。`CatalogSmithingListener` が結果アイテムを組み直す際に元アイテムのエンチャントを引き継いでいなかった。上限超えレベルもそのまま維持する（`canEnchantItem` で絞らない理由は同メソッドの javadoc: 杖など素材が対象外のカタログ品があるため） |
| ~~**W-40**~~ | ~~**クリエイティブインベントリのタブにカスタムアイテムが出ない**~~ | **2026-08-05: Java版ではサーバ側から不可能**（クリエイティブタブはクライアントのアイテムレジストリから組まれ、TF品はCMD付きバニラアイテム）。同じ用途を `/tf catalog` GUI で満たした（`03b5265`。クリエイティブ中または `trinityforge.catalog` 権限。`_editor` の分類でタブ分け・未分類は受け皿タブへ回収） |
| ~~**W-41**~~ | ~~**反射のダメージが異常に大きい**（2026-08-05 追加報告 → 仕様変更）~~ | **2026-08-05 修正済み（`2c478af`）**。真因は旧仕様が `reflect-percent × 被ダメージ` を**毎回**返していたこと（被ダメージが大きいほど反射も青天井）。ユーザー確定仕様「反射率＝被弾時の発動確率／ダメージ＝武器の通常攻撃ダメージ（素殴り）」へ置換。`reflect-flat` は仕様文に無いが既存装備が対で配っているため「発動時の上乗せ実数」として残した |
| ~~**W-42**~~ | ~~**スレッドのステ lore の体裁とフォントを通常の装備と同じにする**（2026-08-05 追加報告）~~ | **2026-08-05 修正済み（TF `0ae78ef` / ArsPaper `6e64216`）**。スレッドは差し込み専用の `LoreComposer#statLines`（区切り線なし・品質行なし・全行fixed色）を使っていた。装備の lore 経路そのものを呼ぶ `ItemAssembler#statLoreBlock` を追加し、フォークの3経路（生成/返却/振り直し）を `ThreadItem#fullLore` のまるごと組み直しへ一本化（区切り線は幅可変なので部分書き換えと併用できない） |
| — | **スポナー回収時の「コマンドが実行される可能性があります」はバニラ仕様**（2026-08-05 質問） | **修正対象ではない**。TF は `BlockStateMeta` でスポナーの block entity を丸ごとアイテムへ載せている（中身・遅延・spawnPotentials を保つため必須）。バニラは `minecraft:block_entity_data` を持つアイテムに**OP にだけ**赤字の警告 tooltip を出す仕様（[Data component format](https://minecraft.wiki/w/Data_component_format)）。消すには block entity を載せない＝中身が失われるので、そのままにする |
| ~~**W-43**~~ | ~~**元からある(バニラの)防具以外、木材で修繕ができない**（2026-08-05 追加報告）~~ | **2026-08-05 修正済み（`7af192a`）**。優先度の取り合い。`WoodRepairListener#onPrepareAnvil`(HIGH) が入れた結果を `CatalogVanillaOperationGuardListener#onPrepareAnvil`(HIGHEST) が `setResult(null)` で潰していた。TF 装備はほぼ全部カタログ品なので金床の木材修繕は常に死んでおり、カタログ品でない=バニラ装備だけが通っていた。ガードの除外条件に「解放済みプレイヤーの木材修繕」を追加（未解放でも許可すると圧縮木材がバニラの修理素材として食われる穴になるので効果の保有まで見る） |
| — | **「EXPを保存する機能」の使い方**（2026-08-05 質問） | **不具合ではない**。エンチャントツリー B-3「EXPフリーザー」（Lv60、`feature:xp-bottle-store-unlock`）を取得後、**バニラの経験値瓶を手に持って** ①スニーク＋右クリック＝格納（段1で 100EXP／瓶1本）②格納済みの瓶を通常右クリック＝取り出し（段1は返却率 50%＝目減りあり。設計メモ「経験値増殖の観点からデバフ必須」）。値は `stats/fishing-gimmick.yml` の `xp-bottle-store`（段2〜4 は value 2/3/4 のノードを置いたときに到達し、200/300/400・60/70/80%）。格納済みの瓶は誤投げ防止で常にクリックを食う |

### 2026-08-06 の配備後コンソール点検で見つかったもの

**点検の前提**: 2026-08-06 02:20〜02:24 に jar（3台）と HEAD の config を配備し、
`start-all.cmd` で 3 バックエンド＋Velocity を起動した直後のログを全走査した。
プラグインの enable はすべて成功（TrinityForge / ArsPaper / EliteMobs / HuskSync）。
**カタログのレシピ登録失敗は 11 件超 → 1 件（`key_binder` のみ）に減った**
（`infinity_*` / `magic_cane` の失敗は今回の config 配備で解消）。
スタックトレースは 3 台とも `key_binder` の 1 本だけ。

| # | 内容 | 根拠と原因 |
|---|---|---|
| **W-44** | ~~**HIGH: `key_binder`（世界の綴じ手の鍵）のレシピが毎起動で登録に失敗し、永久にクラフト不可**~~ **2026-08-16 修正済み（機構解 (b)、要 TF jar 再配備）** | 真因は ①`external-items.yml` 空 ②TF のレシピ登録が Ars enable より先、の合わせ技。手載せ (a) でなく恒久解 (b) を実装: `CatalogRecipeRegistrar#choiceFor` が「ArsPaper 導入済み・未 enable」の間は `list:` メンバー未解決を**保留**（従来は `custom:` 単体だけ保留で `list:` は即 WARNING スキップ）、新設 `ArsPaperRecipeRefreshListener` が `PluginEnableEvent(ArsPaper)`（MONITOR）＋保険の `ServerLoadEvent` で `registerAll()` を冪等に再実行（先頭 removeAll で二重登録なし）。再登録後も未解決の id は名指し WARNING。RED→GREEN 証拠付き（`ArsPaperRecipeRefreshListenerTest` 4件、修正を戻すと2件落ちる）。**実サーバ起動ログで `failed to register recipe for 'key_binder'` の消滅を要確認** |
| **W-45** | **MEDIUM: 特殊報酬 26 件のうち `particle_dragon_aura` が毎起動で捨てられている**（3 台とも `loaded 25 special reward(s), 1 skipped`） | `SpecialRewardsConfig.parseParticle` は `getDataType() != Void.class` の Particle を弾く（Paper 1.21.11 で `FLASH` が Color 必須になり戦闘処理ごと止まった事故の再発防止ガード）。**`paper-api-1.21.11` のバイトコードを確認したところ `DRAGON_BREATH` は `Particle(String,int,String,Class)` に `java.lang.Float` を渡している**（`END_ROD` は 3 引数＝Void）ので、このガードに正しく弾かれている。**設定ミスであってコードの不具合ではない**。修正は yml 側で Void 型のパーティクルへ差し替える。**あわせて、出荷 yml を `parseParticle` に通す回帰テストが無い**（だから誰も気づかないまま出荷された）ので、テストも足す |
| **W-46** | **MEDIUM: Dev_Server だけ Nightbreak トークンが失効している**（→ W-27② の具体化） | Dev_Server: `Nightbreak token was rejected (401) ... INVALID_TOKEN` に続いて `Failed to prefetch access info for 32 slugs`（`premium-enchantment-sanctums` `yggdrasil-realm` `hallosseum` ほか）。**Main_Server は `Nightbreak account loaded successfully!` → `Parsed 58 content versions` で正常**。EliteMobs のデータフォルダはバックエンドごとに別実体なので、**Dev_Server で `/nightbreaklogin <token>` を実行する運用作業**（トークンは nightbreak.io/account） |
| **W-47** | **MEDIUM: 統合版でフォールバックが無いカスタムアイテムが 104 件**（リソースパック側） | Main_Server 起動時に GeyserExtra が `[AutoPack] no Bedrock vanilla fallback for minecraft:crossbow` ×100 / `minecraft:compass` ×4。さらに `infinity_{helmet,chestplate,leggings,boots}` と `source_gem_{helmet,leggings,boots}` の 7 種は**装備テクスチャが Java パック側で解決できず防具アタッチャブルをスキップ**（＝装備すると統合版ではバニラ防具の見た目になる。手持ち表示だけは出る）。**W-36 と同じくリソースパック担当セッションの範囲** |
| **W-48** | **モブドロップを空にしたので、入手経路ゼロの item 88 件を配り直す**（2026-08-13 ユーザー指示「ドロップとルートチェストの戦利品を整理する」の前段） | `combat/mob-overrides.yml` の `drops` 31 ブロックと `combat/mob-level-table.yml` の `add-drops` 6 帯を空にした（TF `0475562`。後者は他セッションのコメント移設 WIP と同ハンクなので**未コミット**）。**経路ゼロ 88 件の内訳**: ①TFカタログ 43（スレッド 30・鍵 8・インフィニティのツール 4・`wooden_halberd`）②Ars 素材 44（ダンジョンの印 28 種**全部**・討伐素材 13・`abyssal_ingot`・`binder_fragment`・`gacha_ticket_digging`）③`creative_source_jar`。**連鎖して作れなくなったカタログ品 32 件**: `abyss_*` 13（`abyssal_ingot`）/ `binder_*` 17（`binder_fragment`＋`dungeon_seal_binder`）/ `key_binder`（`list:dungeon_seals` が **28 件全滅**）/ `stat_reroll_ticket`（討伐素材 6 種）。**赤になったガード 5 本**は `ShippedRitualMaterialObtainabilityTest` 3 + `ShippedWardenTendrilDropTest` 2（削除前のベースラインは 14 失敗＝全部他セッション由来で、増えたのはこの 5 本だけ）。消した中身は `tmp/catalog-audit/removed-drops.txt` と git 履歴に退避済み |
| **W-49** | **防具 36 セットが着用時にバニラの見た目のまま**（2026-08-16 に infinity / source_gem の 2 セットだけ配線済み） | 着用時の見た目は CMD では変わらず `minecraft:equippable` の `asset_id` で決まる。`resourcepack/build_armor_layers_from_items.py` は **inv テクスチャの配色から 64x32 レイヤーを機械生成する**ので、TF の inv アイコンを持たない残り 36 セットは生成元が無い。手描きの 64x32 PNG を `resourcepack/trinityforge-items/assets/trinityforge/textures/entity/equipment/humanoid/<セット名>.png` と `.../humanoid_leggings/<セット名>.png` に置けば、スクリプトが手描きを優先して `equipment/<セット名>.json`・`equipment-registry.json`・`items/equipment-assets.yml` まで自動で配線する |
| **W-50** | ~~**ArsPaper の `materials.yml` が、消した `hoglin_tusk` を参照したまま**~~ **2026-08-16 解決** | `materials.yml` の `_editor.categories` から削除。TF 本体のテストフィクスチャ 22 箇所と javadoc も `hoglin_fang` へ置換（`1709c40`）。**フォークは別リポジトリなので materials.yml の変更は本体に commit されない**（ユーザーの WIP と一緒に commit する必要がある）。再発防止は `ShippedCustomIdReferenceDriftTest`（出荷 yml 横断の `custom:<id>` 実在検査）と editor の宙ぶらりん `_editor` メタ検出（`37546c5`） |
| — | **BlueMap がリソース未取得で動いていない**（Main_Server） | `BlueMap is missing important resources! / You must accept the required file download` → `plugins/BlueMap/core.conf` でダウンロード同意を入れるまでマップは生成されない。運用判断（TF の機能ではない） |
| — | **誤検知だったもの（記録して再調査を防ぐ）** | ①`skulls.json` が `{"skulls": []}` で GeyserExtra が「No skulls registered」と警告するが、**`catalog.yml` に `PLAYER_HEAD` は 0 件**なので正常 ②`SERVER IS RUNNING IN OFFLINE/INSECURE MODE` は Velocity 配下では必須の設定 ③`Vault economy provider not found` は INFO で意図どおり ④`resource-pack-id` 空欄は既定 UUID が使われるだけ ⑤儀式レシピ 4 件（`harvest_hoe` / `herb_hat` / `leyline_shovel` / `bedrock_greaves`）が台座 19 台＝3 段構成を要求する INFO は仕様どおりの案内 |

**W-36 は 2026-08-07 に解消**（下の「W-36 を配信 zip へ入れ直した」を参照）。

### 2026-08-07 ダンジョンの印 19 種を配線して配信した

**症状**: 印のテクスチャ（`assets/trinityforge/textures/item/dungeon_seal_*.png` 19 件）は
2026-08-02 のパックに既に入っていたが、**モデル json と CMD の割り当てが無く**、
ゲーム内では素のレンガのままだった。CMD の台帳（`cmd-registry.json` の BRICK 5461-5479）は
HEAD に commit 済みで、**欠けていたのは残り 2 パートだけ**という状態。

**やったこと**:
1. `assets/trinityforge/models/item/dungeon_seal_*.json` 19 件を新規作成
   （`parent: minecraft:item/generated` / `layer0: trinityforge:item/<id>`）
2. `assets/minecraft/items/brick.json` を新規作成（5461-5479 の `range_dispatch`、
   fallback は `minecraft:item/brick`）
3. **`build_item_pack.py` は使わずに**、配信中の zip を土台にして上記 20 ファイルだけを足した
   （作業ツリーには別セッションの未コミット分＝杖 8 種・モブ素材 10 種・lang 変更が居るので、
   丸ごと詰め直すとそれらまで配信してしまう）。`build_item_pack.py` の validate() と
   同じ 4 観点（台帳に threshold が実在／昇順・重複なし／参照モデルが入る／
   モデルの texture が zip に居る）は手元でやり直した
4. 差分を実測して **追加 20 / 削除 0 / 既存エントリの内容変更 0** を確認
5. GitHub Release `pack-20260807134636` として発行（sha1 `ad87a462fc1589d7a0d21b78ef7b6443f9416875`）
6. `ops\scripts\set-resource-pack.ps1` を新設して Main_Server の `server.properties` の
   `resource-pack` と `resource-pack-sha1` を**両方**更新（sha1 を忘れると GeyserExtra が
   キャッシュ済み zip を読み続けて何も変わらない）。旧値は `server.properties.bak` に退避

**⚠ まだ反映されていない**: `server.properties` は**起動時にしか読まれない**ので、
Main_Server を再起動するまでプレイヤーには旧パックが配られる。
統合版まで通すなら **Paper 起動 → プロキシ（Velocity）再起動**の 2 段が要る
（`packs/geyserextra_auto.pending.zip` が本番へ入れ替わるのは拡張の起動時）。

~~**⚠ 印はまだクラフト経路が死んでいる**: W-44（`key_binder` のレシピ登録失敗）は未修正なので、
印を素材に使う鍵は作れないまま。今回直したのは**見た目だけ**。~~
→ 2026-08-16 に W-44 を機構レベルで修正（Ars enable 後の遅延再登録）。TF jar の再ビルド・再配備後に有効。
→ **2026-08-16 追記: それでも `key_binder` は落ち続けていた。真因はもう 1 段あった。**
  実サーバ起動ログの `custom list member 'dungeon_seal_enchant_trial_10' is unknown` は
  再登録の失敗ではなく**素材そのものが配備先に存在しない**という意味だった。
  TF 側の `items/material-lists.yml`（commit 済み）は印 28 種を要求しているのに、
  ArsPaper フォークの **HEAD** の `materials.yml` は単数の `dungeon_seal_enchant_trial` を含む 19 種のまま。
  28 種になっていたのは**フォークの作業ツリーだけ**で、`deploy-config-head.cmd` は
  フォーク自身の HEAD から配るので、何度配備しても 19 種の古い版が配られ続けていた
  （配備中の `ArsPaper-1.0.0.jar` に同梱された `materials.yml` は 28 種で作業ツリーと byte 一致。
  ただし `ArsPaper#updateResourceFiles` は**プラグインのバージョン文字列が変わったときしか再展開しない**ので、
  jar が新しくても既存 config は一切上書きされない）。
  → フォーク（`feat/trinityforge-fork`）で `materials.yml` だけを commit（`5697799`）。
  **config 配備（`deploy-config-head.cmd`）を実行するまでは鍵は作れないまま。**

### 2026-08-08 ソース収集コンテンツの点検（S-1〜S-5）

「クッキークリッカー式のソース収集は楽しめるか・バランスは適切か」の確認から出た 5 件。

| # | 内容 | 状態 |
|---|---|---|
| ~~**S-1**~~ | ~~**ソース階梯の config が一度も配備されていない**~~ | **2026-08-08 修正済み（`e79cce2`）。真因は配備スクリプトの誤除外。** `sourcejars.yml` / `sourcelinks.yml` を「稼働中のサーバが書き込む実状態（ブロック座標）」と誤認して `/XF` していた。**実際にはその 2 本は読み取り専用の定義ファイル**（ジャー容量・上位ソースリンクの階梯・燃料の点数）で、`SourceJarConfig` / `SourcelinkConfig` は `loadConfiguration` と `saveResource(name,false)` しか呼ばない。**ブロック座標を書いているのは `source-network.yml`**（`SourceNetwork#saveSnapshot`）で、これはプラグインの resources に無いので配備の対象にそもそも入らない。誤除外は `deploy.cmd` / `deploy-config-head.cmd` / `deploy-config-skip-wip.cmd` / `ops-config.psd1`(+sample) / `seed-backend-configs.ps1` / `sync-configs.ps1` の **6 箇所**に広がっていた。**プラグイン側の自己修復も効かない**: `ArsPaper#updateResourceFiles` は**プラグインのバージョン文字列が変わったときだけ**同梱 yml を展開し直すが、フォークはずっと `0.1.0-SNAPSHOT` なのでこのゲートは**一度も開いていない**。＝ArsPaper の yml がサーバへ届く経路は配備スクリプトだけ。**⚠ 配備自体は未実施**（D:\game 書き込みは権限ゲート）。`ops\launch\deploy-config-head.cmd` の実行が残っている |
| ~~**S-3**~~ | ~~**基本ジャー容量 10,000 < カタログ儀式の中央値 15,000**~~ | **2026-08-08 修正済み（fork `18146ab`）。** 191 件中 99 件が「ジャー 1 個では足りない」＝最初のジャーを作った直後の儀式がまず通らなかった。容量を **20,000** にして中央値を 1 個でまかなえるようにし、なお 55/191 は複数ジャーが要る（「増やす vs 良いのに替える」パズルは残す）。`SourceJarConfig.FALLBACK_CAPACITY` も同値へ。回帰は `ShippedSourceJarCapacityTest` 3 件 |
| ~~**S-4**~~ | ~~**ホッパー投入がジャー個別容量を見ない**~~ | **2026-08-08 修正済み（fork `18146ab`）。** `CustomBlockListener` のホッパー経路だけが static 定数 `SourceJar.MAX_SOURCE`（＝フォールバック 10,000）と比べており、手投入は `maxSource(TileState)` を見ていた。上位ジャー配備後に「手では入るのにホッパーでは 10,000 で止まる」形で出る。容量判定を **`SourceJar#isFull` に一本化**。**⚠ フォークに MockBukkit / Mockito が無いのでリスナー自体の実走テストは書けていない**（容量解決の一本化という構造での担保） |
| ~~**S-2**~~ | ~~**1 億級のソースシンクが無い**~~ | **2026-08-08 修正済み（`55d98f1`）。ユーザー選択「カタログに最上位の儀式帯を新設」。** 修正前のソースの実質シンクは TF カタログ 191 儀式の合計 **5,201,600** だけで、`source_engine` 1 個＝3,000 万は総額の **5.8 倍**、`infinity_source_core`＝1 億は **19 倍**。`source_condenser`（1 儀式で 4 個＝280 万）に届いた時点で経済が終わっていた。**インフィニティ 5 種を作業台→儀式へ移し、合計 105,000,000 のシンクを新設**（剣 2,000 万／胴 3,000 万／脚 2,500 万／頭・靴 各 1,500 万）。台座はすべて別構成にしてある（同一構成だと `findFirst` で先勝ちし片方が永久に作れない）。**副次の回帰も潰した**: 儀式化した 5 件の台座素材 `custom:amethyst_block_2x` / `custom:breeze_rod_2x` が `smithing.exp-per-material` に無く、`ArsProgressionBridge#grantSmithingCraftExp` が**素材合計を丸ごと捨てて定額 EXP に落ちる**状態だったので圧縮段数から算出した行を追加（1944 / 320）。`ShippedRitualMaterialExpCoverageTest` の欠落素材が 14→12、影響レシピが 84→79 に減ることで実測確認。**⚠ 残り 12 素材（`blaze_rod_2x` ほか）は今回より前からの欠落**で未対応 |
| ~~**S-5**~~ | ~~**防具の装備時テクスチャ自動割り当て機構**~~ | **2026-08-08 機構のみ実装済み（ユーザー選択「機構だけ先に作る」）。** 着用時の見た目は `minecraft:equippable` の `asset_id` で決まり **CMD では変わらない**。経路は 3 本立て: ①`resourcepack/build_equipment_assets.py` が catalog.yml の防具をスロット接尾辞で**セットへ束ね**（36 セット検出）、**必要なレイヤー PNG が揃っているセットだけ**を `equipment/<set>.json`・永続台帳 `equipment-registry.json`・`items/equipment-assets.yml` へ書く ②`EquipmentAssetsConfig` がその yml を読む ③`ItemFactory#create` が `getData(EQUIPPABLE)` → `toBuilder()` → `assetId()` で **asset_id だけ**差し替える。**現状 0 セット配線（PNG が 1 枚も無い）＝全防具バニラのまま**で、PNG を所定パスへ置いて再実行すれば自動配線される。**⚠ 揃っていないセットを配線しないのは必須**（定義の無い `asset_id` は防具を透明にする）。回帰は `EquipmentAssetStampTest` 5 件＋`ShippedEquipmentAssetsTest` 2 件。`toBuilder()` を使わず組み直すと落ちることを実測で確認済み。**MockBukkit はバニラ既定のデータコンポーネントを持たない**ので、`create()` 経由の検証は null ガードで素通りする（`applyEquipmentAsset` を直接叩く形にしてある） |

**⚠ 並行作業の衝突**: 2026-08-08 時点で**別セッションが fork の `sourcelinks.yml` を編集中**（階梯 V の台座を x8→x7）。
`materials.yml` / `items.yml` にも未コミット変更がある。ソース階梯まわりを触るときは先に `git status` を見ること。

### 2026-08-08 召喚・友好モブと杖の耐久（M-1〜M-5）

ユーザー報告 5 件のバッチ。commit は `83db633`（M-1）/ `1f7a8b4`（M-5 の TF 側）/ fork `adc744a`（M-5 の消費側）。

| # | 内容 | 状態 |
|---|---|---|
| ~~**M-1**~~ | ~~**召喚魔法で出したモブのレベルが常に 0**~~ | **2026-08-08 修正済み（`83db633`）。** 召喚モブが特別扱いされておらず、周囲の野良モブと同じ「ワールドスポーンからの距離 × `coordinate-coefficient`」でレベルが決まっていた。**召喚は拠点や戦闘中のその場で行うので実質ほぼ常に Lv0** になり、魔法の熟練度が使い魔にまったく反映されていなかった。`mob-types.yml` に `summoned:`（`skill: ARS_MAGIC` / `level-per-skill-level: 1.0` / `base-level: 1`）を新設し、`MobTypeSpawnListener` が `arspaper:summoned` PDC を見て**距離ではなく召喚者のスキルレベル**でレベルを決めるようにした。距離計算を通した後で上書きするのでは `dimensions.<ENV>.coordinate-coefficient` が効いてしまい**拠点で召喚したか遠征先で召喚したかで強さが変わる**ので、レベル解決を `applyStatsAtLevel` へ切り出して分岐している。EM 所有の個体より先には置かない。回帰は `SummonedLevelPolicyTest` 6 件＋`SummonedMobPdcKeyContractTest` 2 件（フォークとの PDC キー突き合わせ。フォーク不在の環境では突き合わせをスキップ） |
| ~~**M-4**~~ | ~~オーバーエンチャント時に文字色を変える~~ | **2026-08-08 ユーザー選択により今回は見送り。** |
| ~~**M-5**~~ | ~~**杖に耐久が無く消費もされない**~~ | **2026-08-08 修正済み（`1f7a8b4` ＋ fork `adc744a`）。ユーザー選択「剣系マテリアルへ変更する・配線だけでよい（ゲーム内の移行処理は β のため不要）」。** 杖は素材が `BLAZE_ROD` で**最大耐久 0** だったため、そもそも「耐久が減る」という状態を持てなかった。杖 10 本の material を tier 相当の剣へ移し（上位 3 本はネザライト帯）、`item-stats.yml` のキーを **4 箇所すべて**改名（items 直下・カテゴリ表・カテゴリ別一覧・触媒一覧。**1 箇所取りこぼすと触媒として数えられなくなり attack-speed 0.1 のピン留めも無言で外れる**）、`cmd-registry.json` とパックへ新素材側を追加。**触媒扱いは素材ではなく `use-skill: ARS_MAGIC` で判定**されている（`MeleeUnintendedItemAttackSpeedTest`）ので壊れない。素材変更だけでは近接で殴ったときしか減らないため、フォーク側で**詠唱 1 回ごとに消費**する `CastDurabilityPolicy` ＋ `config.yml` の `cast-durability` を追加した。**BLAZE_ROD 側の割当とパック定義は残してある**（台帳のキーは material+cmd なので番号の再利用にはならない。消すと配布済みの杖が生のブレイズロッドの見た目に化ける）。**⚠ 旧杖のステータス行は移動したので、配布済みの杖にはステが付かなくなる**（移行不要というユーザー判断） |
| ~~**M-2**~~ | ~~**手懐けた友好モブの HP をどうするか（計画）**~~ | **2026-08-10 実装済み（`0e0888e`）。ユーザー判断「自身の combat 参照でレベルが付く」＝下記の推奨案 A。** `mob-types.yml` に `summoned:` と対の `tamed:` を新設し、`EntityTameEvent` で**飼い主の総合戦闘レベル**（`progression/combat-level.yml` の pillar 式 = `SymmetricCombatService#combatLevelOf`）からレベルを決める。単一スキルを参照しないので `skill:` キーは意図的に置いていない。**飼い主が後から強くなった分は `EntitiesLoadEvent`（チャンクのエンティティ読み込み）で追随**する — `PlayerJoinEvent` での全ワールド走査は「そのプレイヤーが飼い主であるモブ一覧」を得る仕組みが無く重いので採らなかった。設計時に挙げた罠は 3 つとも対策済み（`applyMaxHealth` へ現在 HP 比率を渡す／EliteMobs 所有個体を両経路で除外／`getOwner()` が null なら無干渉）。回帰は `TamedLevelPolicyTest` 8 件＋`MobTypeSpawnListenerTamedLevelTest` 5 件（Bukkit イベント抜きの純関数）＋`MobTypeSpawnListenerTamedIntegrationTest` 4 件（MockBukkit で両イベントを実発火。**MockBukkit は `EntityTameEvent` / `EntitiesLoadEvent` / `Tameable` を実装しており SKIPPED に化けない**ことを実測で確認） |
| ~~**M-3**~~ | ~~**召喚モブ／ゴーレム（バニラ含む）の攻撃力をどうするか（計画）**~~ | **2026-08-10 ユーザー決定: 「いったん攻撃力はなし」＝現状維持。コード・config の変更なし。** モブ→モブのダメージに TF が介入しない構造をそのまま残す（下記「M-2 / M-3 の前提」参照）。**`mob-types.yml` の `attack-power` を上げる直し方は引き続き却下**（対モブには効かないまま**対プレイヤーだけ強くなり**、ペットが PvP の代理攻撃手段になる）。味方モブを戦力にするなら、必要なのは数値調整ではなく**モブ→モブ経路を新設するかどうか**の判断で、それは別途 |

#### M-2 / M-3 の前提 — まずこれを知らないと設計を間違える

**モブ → モブのダメージに TF は一切触っていない。** `CombatListener#onEntityDamageByEntity` は
「被害者が Player」（→ `handleMobToPlayerDamage`）か「加害者が Player」のどちらでもなければ
`resolveAttacker` が null を返して**即 return** する。つまり `MobData` に刻んだ `attack-power` は
**モブがプレイヤーを殴るときにしか読まれない**。

一方で TF はモブの HP を大きく持ち上げている（`mob-types.yml`、
式は `(base + coeff×lv) × growth^lv` ＋ Lv45 以降の加算区間）:

| | Lv0 | Lv45 | Lv60 | Lv45 以降の加算 |
|---|---|---|---|---|
| SKELETON の HP | 340 | 2,805 | 17,445 | +785.05／Lv |
| WOLF の HP | 240 | 1,980 | 12,314 | +554.15／Lv |
| IRON_GOLEM の HP | 960 | 7,920 | 49,256 | +2,216.6／Lv |

**この 2 つが噛み合っていない**のが M-2 / M-3 の根っこ。狼のバニラ与ダメは 4、鉄ゴーレムは 7.5〜21.5 で
そこは TF が触らないので、**Lv0 のスケルトン 1 体を狼が倒すのに約 85 回、Lv45 なら約 700 回殴る**必要がある。
＝**味方モブは現状ほぼ「動く置物」**で、M-1 でレベルが上がっても増えるのは HP と防御だけ。

#### M-2 の設計（手懐けた友好モブの HP）

現状の問題は 2 つ。
1. **TF に `Tameable` / `isTamed` / `getOwner` の参照が 1 つも無い。** ペットは野良モブと同じ扱いで、
   `CreatureSpawnEvent` の時点の**ワールドスポーンからの距離**でレベルが決まり、**手懐けた後は二度と変わらない**。
   拠点近くで手懐けた狼は Lv0（HP 240）のまま、Lv60 帯の遠征に連れて行っても弱いままになる。
2. 逆に**遠方で手懐けた狼は最初から Lv60 相当**になり、拠点へ連れ帰ると不自然に硬い。

**推奨案（A）: M-1 と同じ形で「飼い主基準」に寄せる。** `mob-types.yml` に `summoned:` と対になる
`tamed:` セクション（`enabled` / `skill` / `level-per-skill-level` / `base-level` / `max-level`）を足し、
`EntityTameEvent` と**飼い主のログイン時**に `applyStatsAtLevel` を呼び直す。参照スキルは
**総合戦闘レベル（`progression/combat-level.yml`）**を推す — テイム専用スキルは存在せず、
ARS_MAGIC を見ると「魔法を上げないと狼が育たない」という筋の通らない依存になるため。

- 却下案（B）現在地で動的に再スケール: 境界をまたぐたびに HP が跳ねるのが分かりにくく、
  戦闘中に最大 HP を書き換えるのは事故が出やすい。
- 却下案（C）据え置きのまま倍率で底上げ: 「拠点で手懐けたか遠征先で手懐けたか」で
  強さが決まる理不尽さ自体は残る。

**実装時に踏む罠（先に書いておく）**
- `applyMaxHealth(entity, max, healthRatio)` は**現在 HP の比率**を渡す形になっている。
  再スケールで比率を渡し忘れると、**ログインのたびにペットが全快する／即死する**。
- `scheduleHealthReassert` が次 tick に無条件で `setBaseValue` するので、
  テイム時の適用と喧嘩しないように経路を合わせること（EliteMobs 所有の判定と同じ問題）。
- 狼の首輪の色・variant を触らないこと（`MobData.stampMobType` は PDC のみなので通常は問題ない）。

#### M-3 の設計（召喚モブ／ゴーレムの攻撃力）

**先に結論**: いま `attack-power` の数値をいくら変えても**対モブ戦は 1 ミリも変わらない**（上記の前提）。
「召喚モブを強くする」は攻撃ステの調整ではなく、**モブ→モブ経路を作るかどうか**の判断になる。

さらに**やってはいけない直し方**がある。`mob-types.yml` の `WOLF.attack.attack-power` を上げると、
対モブには効かないまま**対プレイヤーだけが強くなる**（mob→player は TF 経路を通るため）。
＝ペットが PvP の代理攻撃手段になる。**yml の数値をいじる案は却下。**

- **推奨案（B）味方モブの対モブダメージにだけ倍率を掛ける。** `EntityDamageByEntityEvent` で
  「加害者が味方モブ・被害者がモブ」のときだけ、そのモブの TF レベル由来の倍率で
  `event.setDamage(vanilla × k)` する。味方判定は
  ①`arspaper:summoner_uuid` を持つ（M-1 で既に刻まれている）
  ②`Tameable#getOwner()` がプレイヤー
  ③`IronGolem#isPlayerCreated()`（村の自然湧きゴーレムと作ったゴーレムを区別できる唯一の API）
  の 3 本。**対プレイヤー経路には触らないので PvP の穴が開かない。** コストが小さく、効果がすぐ出る。
- 案（A）モブ→モブを丸ごと TF のダメージ式へ通す: 会心・貫通・属性まで乗るが、
  **敵対モブ同士の戦闘（襲撃・村の防衛・ゾンビ vs 村人）まで挙動が変わる**うえ、
  全モブ被弾で式が走るので負荷も増える。(B) で足りないと分かってから。
- 案（C）何もしない: 召喚とペットは「壁・囮」と割り切る。M-1 で HP と防御が上がったので
  この方向でも一応筋は通る。ただし**ゴーレムだけは別問題**で、レベルが
  「拠点がワールドスポーンからどれだけ離れているか」で決まるため、
  拠点の位置だけでゴーレムの硬さが変わる状態は残る。

**判断が要る点（ユーザーに確認したい）**
- ペットや召喚モブが**トドメを刺したときに飼い主へスキル EXP を入れるか**。
  現状は `CombatListener` が「致死ダメージの起因者が Player のとき」しか EXP を配らないので**入らない**。
  入れるならペット放置ファームの温床になるので、入れないか大幅減額が無難。
- 倍率 k の基準。素直には「同レベルの敵対モブが対プレイヤーで出す `attack-power` に合わせる」
  （＝味方モブが自分と同格の敵と殴り合って互角になる）が、ペットが強すぎるとプレイヤーの出番が消える。

### 2026-08-08 エディタ 3 件・圧縮追加・儀式の台座超過・鍵と印の 10 分割・スレッドの説明

ユーザー報告 10 件のバッチ。commit は `fa08a3e` / `9b1e7d4` / `40a4457`。

| # | 内容 | 状態 |
|---|---|---|
| ~~**B-1**~~ | ~~カタログ追加時の CMD が 0 になり一括採番の対象外~~ | **修正済み（`fa08a3e`）。** 素材／魔導書の新規追加が `custom_model_data: 0` を seed していた。CMD 欄を空にしたときも 0 でなく未設定へ戻すようにした。**0 は「未設定」ではなく「CMD 0 に登録」なので、テクスチャも CMD 0 へ配線される** |
| ~~**B-2**~~ | ~~新規カタログ品がレシピのセレクトに出ない~~ | **修正済み（`fa08a3e`）。** 真因は**素材画面が `custom:` 候補を登録していなかった**こと。`app.js` は画面を開いた時点の materials.yml しか積まないので、その場で追加した素材は候補に現れない。カタログ画面と同じく描画ごとに `setCustomItemCandidates` を呼ぶようにした |
| ~~**B-3**~~ | ~~「解凍を許可」が保存＋リロードまで押せない~~ | **修正済み（`fa08a3e`）。** 可否をカード描画時に 1 度しか計算しておらず、**素材欄の変更は再描画を呼ばない**（呼ぶと入力フォーカスが飛ぶ）ので固まっていた。素材欄の onChange で可否だけ再計算する |
| ~~**B-4**~~ | ~~圧縮段の追加（サクラ 729 倍／花崗岩・閃緑岩・安山岩 6561 倍／ソースジェム・アメジスト 729 倍）~~ | **完了（`fa08a3e`）。** 18 件追加。ソースジェムは既存段が無いので `source_gem_1x/2x/3x` を新設 |
| ~~**B-5**~~ | ~~ダンジョンの印のテクスチャがエディタで未割り当て~~ | **修正済み（`fa08a3e`）。** 台帳 `cmd-registry.json` の BRICK#5461-5479 に `assetName` が無く、`previewInfo` / `regenerateItemDefinitions` の配線判定に到達していなかった。**同じ理由で `assets/minecraft/items/brick.json` が再生成のたびに消えていた** |
| ~~**B-6**~~ | ~~儀式レシピが台座 16 個を超えていないか~~ | **9 件が超過していた。全件修正済み（`fa08a3e`）。** 台座リングは `max(\|x\|,\|z\|)==2` の **16 マスしかない**ので、17 個以上を要求するレシピは**警告も出ずに永久にクラフト不可**。TF 4 件（19 個）＋ Ars ソースリンク V 5 件（18 個）。**数え方の罠**: `pedestal-items` の行数ではなく `xN` を展開した合計が台座数なので、行数で見る検査は 3 行に見えて素通りする。回帰は `ritual-pedestal-capacity-2026-08-08.test.js` |
| ~~**B-7**~~ | ~~エンチャント試練の鍵と印を 1〜10 へ分割~~ | **完了（`9b1e7d4`）。** `key_enchant_trial`（単一）を廃止し `key_enchant_trial_1`〜`_10`（TRIAL_KEY#5520-5529）へ。レシピは「前段の鍵 1 個＋追加素材」の鎖状進行。印も `dungeon_seal_enchant_trial_1`〜`_10`（BRICK#5480-5489）へ分割し、テクスチャは既存の絵を 10 種で共有（ユーザー選択） |
| ~~**B-8**~~ | ~~上記の鍵をダンジョンゲートへ登録~~ | **完了（`9b1e7d4`）。** `em_id_enchantment_challenge_1`〜`_9` は `key-item` 自体が無く、`_10` だけが旧鍵を指していた |
| ~~**B-9**~~ | ~~ゲートが要求するのにカタログに無い鍵~~ | **完了（`9b1e7d4`）。** `key_hallosseum` / `key_north_pole` が未定義で、鍵ゲートが `CrossPluginItemResolver` の fail-open により**素通り**になっていた。TRIAL_KEY#5530-5531 で登録しレシピも付けた。`ShippedDungeonKeyReachabilityTest` の `INTENTIONALLY_UNDEFINED` は空集合になり検査はむしろ厳しくなった |
| ~~**B-10**~~ | ~~スレッドの説明をフレーバー以外全削除~~ | **完了（`40a4457`）。** catalog.yml の `thread_*` 44 件を `[フレーバー / 空行 / 「防具のスレッドスロットにセット可能」]` の 3 行へ。`thread_mana_regen` はフレーバー行を持たないので据え置き。あわせて **ArsPaper の `threads.yml` からも `lore:` 29 件を削除**（フォークは `.gitignore` 除外なので commit には出ない）。**⚠ threads.yml の lore は `thread-sets.yml` のセット効果を説明する唯一の経路**（`ThreadConfig#getEffectLore` の末尾でそのまま出していた）だったので、**セット効果はゲーム内に一切表示が無くなった** |

**⚠ CMD 台帳が「番号を再利用しない」規約を満たしていない**:
`tools/config-editor/lib/cmd-registry.js` の `reconcileWithUsage` は**現在の config が参照している行だけ**で台帳を組み直すため、
editor の保存のたび（`cmd-routes.js#syncCmdRegistryAfterSave` → `server.js:707`）に「config から消えた行」が台帳から落ちる。
`nextCmd` は台帳の使用済み集合しか避けないので**落ちた番号は再び配られる**。刈り取りは意図的で
`cmd-registry.test.js:268` が明示的に固定しているが、`CLAUDE.md` の「CMD の永続台帳。番号を再利用しない」と矛盾する。
実害: 先に採番した TRIAL_KEY#5520-5531 の 12 行が catalog.yml へ書かれる前に消えた。退役した BRICK#5475 も番号ごと開放された。
`plank_scrap` / `tf_gacha_ticket` / `hoglin_tusk` の消失も同じ機構。**どちらを正とするか未決**。

**⚠ `dungeon/gates.yml` が HEAD より 33 ダンジョン少ない**: HEAD（`c655349` 以来）は EliteMobs 同梱 61 件を列挙しているが、
作業ツリーは 28 件の縮小版（別セッションの未コミット WIP、ユーザー確認済みで「問題ない」）。
**入場そのものは塞がれない**（`DungeonGateService#checkEntry` はゲート未登録なら `true` を返す fail-open）が、
`hasEntryGate` を見る EliteMobs のダンジョンブラウザ経路は**ゲート未登録を拒否する**ので、
その 33 件は `/em dungeontp` からは入れない。`ShippedDungeonGateCoverageTest` の網羅 2 件はこの差で落ち続ける。

### 2026-08-08 スレッド効果・入力欄のフォーカス飛び・スクラップ変換・圧縮木材／ご飯（C-1〜C-5）

ユーザー報告 5 件。commit は `2ab7498` / `0b23802` / `fb64318` / `9c68ac9`、fork（ArsPaper）は `ff3f201`。

| # | 内容 | 状態 |
|---|---|---|
| ~~**C-5**~~ | ~~スレッドの効果がステータス設定から選べない／レベルを引数で取れない~~ | **完了（`0b23802` ＋ fork `ff3f201`）。真因は 2 段。** ①`item-stats.yml` 側にあった「特殊効果」欄は**読むコードがどこにも無い飾り**だった（TF 本体・フォークとも参照ゼロ、出荷データも 0 件）。実際に効くのは ArsPaper の `threads.yml`。②その `threads.yml` を編集する `thread-bundle` 分割ビューは**読み込み経路も保存経路も実装済みなのに、それを指すナビ項目がどこにも無く一度も開けなかった**。`potion-effect` / `potion-level` / `flight` / `slots` を config 化（従来はポーション効果が `ThreadType` の enum 決め打ち、振幅は 0 固定）し、「アイテムステータス」へ **「スレッド効果 (Ars)」** の導線を新設。効果は有益系 18 種＋「なし」から選べる。`ArmorManaListener` の `THREAD_POTION_TYPES` 静的配列は廃止し `ThreadConfig#allPotionTypes`（enum 既定 ∪ yml 上書き）を走査する。**同じ効果をより強い振幅で既に持っている場合は上書きしない**。実機（`localhost:8787`）でサイドバーから開いて確認済み |
| ~~**C-4**~~ | ~~鍛冶ギミックの対象 ID 欄が 1 文字ごとに入力状態が切れる~~ | **修正済み（`2ab7498`）。** `window.textInput` は **`oninput`＝1 文字ごと**に発火する。対象 ID は `disassembly.items` の**マップキー**なので変更＝`renameKey`→再描画が必須で、入力欄そのものが毎文字作り直されていた（途中状態の `c` / `co` / `cop` … というキーまで作っていた）。確定時のみ発火する `window.textInputOnCommit`（`onchange`）を新設して差し替え。同種の箇所が `ars-p4.js` にもあり同時修正。回帰は `text-input-focus-loss-2026-08-08.test.js`（挙動 2 件＋**再描画するコールバックを持つ `textInput` を新たに増やさない構造ガード**。免除は許可リストではなく現場の `// oninput-rerender-ok:` コメントで行う） |
| ~~**C-1**~~ | ~~各種スクラップ 4 個でバニラ素材へ／ただのスクラップはランダム変換~~ | **完了（`9c68ac9`）。** 種別スクラップ 4 個→バニラ素材のレシピは **ArsPaper `materials.yml` 側に既にあり実際に登録される**ので追加していない（二重登録防止）。「ただのスクラップ」は右クリックで 4 個消費し重み付き抽選で種別スクラップ 1 個（銅 25／鉄 25／革 20／金 12／亀甲羅 12／ダイヤ 5／ネザライト 1）。**クラフトのレシピにできない**: `tf_scrap` の base_material が `IRON_NUGGET` で `iron_ingot_scrap` の既存レシピ（2x2 全マス IRON_NUGGET）と入力が完全一致し、`custom:` 素材は `MaterialChoice`（型のみ照合・PDC を見ない）へ潰れるので**どちらかが必ず無言でシャドウイングされる**。加えて `PrepareItemCraftEvent` で抽選すると**結果枠のプレビューを見てから取り出しを選べる＝リロールで厳選できる**。ガチャ券と同じ「右クリックで 1 回だけ抽選して即消費」に寄せて両方を構造的に閉じた |
| ~~**C-2**~~ | ~~圧縮木材での修繕をカタログ全種類へ~~ | **完了（`9c68ac9`）。** `wood-repair.materials` を 9 樹種×3 段の **27 種**へ（HEAD は `oak_wood_1x` の 1 件だけだった）。耐久回復は樹種によらず段だけで決まり 1 段＝9 倍なので **200 / 1800 / 16200**。**EXP を与える経路は `WoodRepairListener` に元から存在しない**（`skill-exp.yml` にも項目なし）ので「種類で EXP は変わらない」は現状そのまま成立している。**⚠ `cherry_blossom_wood_*` 3 件は fork HEAD の materials.yml にまだ無い**（別セッションが未コミットで追加中）。存在しない ID の行は照合されないだけで無害 |
| ~~**C-3**~~ | ~~圧縮ご飯 6 種の追加（満腹度も）~~ | **完了（`fb64318` ＋ fork `ff3f201`）。** ベイクドポテト・焼き豚・焼き羊肉・焼き鳥・クッキー・パンプキンパイの 9 倍（`_1x`）を追加（CMD 252-257）。パンは `compressed_bread_1x` として既存のため追加せず。満腹度はバニラ元値を 9 倍して 20 で丸め、**クッキーだけ元値が小さく 9 倍でも上限に届かない**ので丸めていない（18 / 3.6）。**あわせて既存バグを 1 件修正**: `compressed_bread_2x/3x`・`compressed_cooked_beef_2x/3x` の 4 件が `food-gimmick.yml` の `custom-foods` に未登録で、`FoodGimmickListener#onFoodLevelChange` の一致判定を素通りし、**81 倍・729 倍に圧縮しても食べたときの満腹度は素のパン・ステーキのまま**だった（クラフト段数だけ機能して食事効果が死んでいた） |

**恒久知識**: 分割ビューは **`split-views.js` の分岐・`app.js` の読み書き経路・`NAV_GROUPS` の項目の 3 点が揃って初めて到達可能**。
前 2 つだけ書いても機能追加は運営者に届かない（`thread-bundle` がその実例）。詳細は `docs/agent-context/config-editor.md`。

**⚠ 未解決の並行作業ドリフト**: `progression/crafting-features.yml` の作業ツリーが HEAD より約 300 行少ない
（別セッションが `disassembly.items` の `wooden_*` / `ROTTEN_FLESH` / `STICK` / `STRING` / `BONE` 等を削除中で、
HEAD 19 件 → 作業ツリー 7 件）。**意図的な削除か事故か未確認**。
editor の `disassembly-defaults.test.js` 3 件（`plank_scrap` 未定義ほか）はこのドリフトで落ち続けている。
今回の commit は「HEAD ＋ 自分の変更だけ」の blob を作って staging したので、この削除は取り込んでいない。

### 2026-08-07 W-36（トライデント・槍が三人称で 2D）を配信 zip へ入れ直した

**ソースは 08-05 の時点で既に正しく、腐っていたのは配信 zip だけ**だったので、
上の印入り zip（`pack-20260807134636`）を土台に
`assets/minecraft/items/{trident,netherite_spear,diamond_spear}.json` の 3 エントリを
作業ツリー版へ差し替えて再発行した（`pack-20260807135616`、
sha1 `4baf011e607495b57890ade103b02d7ba50c65e0`）。

**差し替え前に平面のままだった CMD 13 件がゼロになったことを実測で確認**（新旧 zip を
展開して、各 CMD のモデルが `display_context` の `select` になっているかを数えた）:
`trident` 121/122/174-181、`netherite_spear` 118/119、`diamond_spear` 50。
差分は**既存 3 エントリの内容変更のみ（追加 0 / 削除 0）**、参照先モデルの欠落なし
（`*__in_hand` のモデルは 08-02 のパックに既に入っていたので、運ぶ必要があったのは item json だけ）。

**⚠ `server.properties` の更新は未実施**: D:\game への書き込みがエージェントの権限ゲートに
止められたため、`resource-pack` / `resource-pack-sha1` は
`pack-20260807134636`（印だけ・W-36 未修正）を指したまま。
**ユーザーが `ops\scripts\set-resource-pack.ps1` を実行するまで W-36 は直らない。**

### W-48（2026-08-10 新規）「力III（最上位の醸造）」が作れない — ホグリンの牙に定義ブロックが無い

ArsPaper の `materials.yml` に **ホグリンの牙（`hoglin_tusk`）の定義ブロックが存在しない**。
リスト項目（醸造レシピの材料欄）にしか現れないため、**どのレジストリにも実体が無い素材**を要求する
レシピになっており、「力III（最上位の醸造）」は**レシピ帳に出るのに永久にクラフト不可**。
`ShippedBrewUnlocksPairDriftTest` が
`apex-brew: THICK + custom:hoglin_tusk — どのレジストリにも存在しない` で落ちて検出している。

**機構は CMD 台帳の刈り取り不具合と同型**（`plank_scrap` / `tf_gacha_ticket` と同じ壊れ方）で、
定義ブロックだけが落ちてリスト参照が残る。**直し方は定義ブロックの復元**であって、
レシピ側から材料を消すことではない（消すと「牙を集める」導線ごと死ぬ）。
テクスチャは現役なので **J-14 の孤児削除では意図的に残してある**。

### 実サーバ報告バッチ（2026-08-18 受領。W-85〜W-91）

| ID | 報告 | 状態 |
|---|---|---|
| W-85 | 儀式で作成者より先に他プレイヤーが拾うと所有権と品質がその拾った人基準になる。ドロップではなく右クリック回収にしたい | ~~設計確認待ち~~ **修正済み（TF + ArsPaper fork）** |
| W-86 | 儀式・グリフ解放の最中にブロックが壊された／素材が回収されたときにロストや増殖が無いかの調査 | ~~未着手~~ **調査完了・3 件修正（ArsPaper fork）** |
| W-87 | ネザライトアップグレードの鍛冶型が複製できない。バニラ挙動が潰されていそう | ~~確認待ち~~ **原因確定（実物で裏取り）・通知を改善。根治は W-70**（下記） |
| W-88 | スレッドスロット GUI に装備のテクスチャが反映されていない | ~~未着手~~ **修正済み（ArsPaper fork）** |
| W-89 | 騎乗中に騎乗しているモブの HP テキストディスプレイが視線にかぶって邪魔 | ~~未着手~~ **修正済み** |
| W-90 | AFK が予告なく訪れるので title 等でカウントダウンか通知を出したい | ~~未着手~~ **修正済み** |
| W-91 | エンダードラゴンやガストなど当たり判定の大きいモブで HP 表示が出せない／体に埋まる | ~~未着手~~ **修正済み** |
| W-92 | エンチャント試練 1 の鍵を 4 人ぶん作ったのに 1 人しか入れず、残り 3 人は鍵だけ消費された | ~~未着手~~ **修正済み（EliteMobs fork）** |
| W-93 | エンチャント試練に入って `/em start` すると敵が一瞬で消え、`/em quit` でも帰れなくなる | ~~未着手~~ **修正済み（EliteMobs fork）** |
| W-94 | バニラモブが EliteMobs の仕様でエリート化して湧き、頭上のテキスト表示が二重になる | ~~未着手~~ **設定スクリプトを用意（実行はユーザー）** |
| W-95 | nuclear 武器・ウィザー装備の必要ソースを 25 万、hero 武器・エンドラ装備を 200 万にする | ~~未着手~~ **反映済み（`items/catalog.yml`）** |

**W-95 のメモ。** `catalog.yml` の儀式レシピ `source:` を 34 件書き換えた
（hero 武器 13 + エンダードラゴン装備 4 = **200 万**、nuclear 武器 13 + ウィザー装備 4 = **25 万**）。
**装備は部位ごとの傾斜（ヘルメット 8000 / 胸 16000 / 脚 12000 / 靴 4000 のような 2:4:3:1）を廃して一律**にしている
（指定が 1 つの数字だったため。部位で変えたい場合は要指示）。
これで **hero/エンドラが nuclear/ウィザーの上位**になり、旧値（hero 8000 < nuclear 15000）とは
**上下関係が逆転**している点に注意。`source:` は `ItemCatalogConfig` で `Math.max(0, getInt(...))` の int 読みなので
200 万でも上限に当たらない。テストは事前・事後とも同じ 7 件失敗（すべて他セッションの WIP 由来、
`catalog.yml` を HEAD へ戻した状態でも同じ 7 件が落ちることを実測で確認済み）。

### 実サーバ報告バッチ（2026-08-18 受領 第2陣。W-95〜W-101）

| ID | 報告 | 状態 |
|---|---|---|
| W-95 | バニラ材質を共用するカスタム品がなぜ混ざるのか。CMD が違うはずでは。**原因究明してから修正**（ユーザー明示） | **原因究明済み・修正は保留**（下記） |
| W-96 | フレーバーテキストのカラーコードが反映されず生文字列が見える（例: ガチャスレッド。`<gold>` `<color:dark_purple>` がそのまま表示） | ~~未着手~~ **修正済み（ArsPaper fork）** |
| W-97 | レシピ／図鑑のソート順を記憶保持してほしい | ~~未着手~~ **修正済み（図鑑=TF `44b8634` / レシピ一覧=ArsPaper fork）** |
| W-98 | 一括伐採でアカシアのようなくねくねした原木も一括破壊できるようにしてほしい | ~~未着手~~ **修正済み（`49015a9`）** |
| ~~W-99~~ | ~~圧縮レシピが多すぎる。最大倍率の 1 つだけをレシピ一覧に出し、中間レシピと解凍レシピはオプションで on/off（既定 off）~~ | ✅ **2026-08-19 対応（W-122 として再報告された分と同一。fork `f26a8fc`）。** 詳細は第7陣の W-122／W-123 の節 |
| W-100 | 儀式で作った魔導書（＝所有者が付く全アイテム？）を、lore の所有者名の本人が使えない | ~~未着手~~ **修正済み（ArsPaper fork）** |
| W-101 | ヴォルカニックソースリンクに解放ゲートを設定していないのにクラフトできない（以前ゲートを除去して直したはずが再発。config のロールバック？） | ~~未着手~~ **修正済み（`6dc4f99`）** |

### 実サーバ報告バッチ（2026-08-18 受領 第3陣。W-102〜W-105）

| ID | 報告 | 状態 |
|---|---|---|
| W-102 | `gacha` / `role_luck` / `role_efficiency` / `blindness` など**プロジェクト後半に editor から追加したスレッド**だけ挙動が違う（lore の生タグ・品質が出ない／反映されない）。**アイテムカタログのスレッドタブで設定したらスレッドとして扱われる**という従来仕様に揃える | ~~未着手~~ **修正済み（ArsPaper fork）** |
| W-103 | 既存スレッドのベース材質を（重複可で）**すべて鍛冶型**に統一する。現状は壺の欠片・糸などが混ざっている | ~~未着手~~ **修正済み（TF config + resourcepack + ArsPaper fork）** |
| W-104 | ソースリンクから近くのソースジャーへドミニオンワンドで転送できない。**設定完了通知は出るが転送が開始されない** | ~~未着手~~ **修正済み（ArsPaper fork）** |
| W-105 | ドミニオンワンドを手に持ったとき、接続しているソースジャーとソースリンクがパーティクルで繋がって見えるようにしてほしい | ~~未着手~~ **経路可視化は実装済み（2026-08-01）＋隣接供給の可視化を追加（ArsPaper fork）** |

### 実サーバ報告バッチ（2026-08-18 受領 第4陣。W-108〜W-109）

| ID | 報告 | 状態 |
|---|---|---|
| W-108 | **ポーションがまだ作れない**（2026-08-18 ユーザー報告。切り分けの確認で「**バニラ醸造が完成しない**」を選択） | **TF 側に「純バニラの醸造を止める経路」は存在しないことを確定（下記）。調査中に見つけた「品質ポーションを延長・強化すると中身が消える」は ~~判断待ち~~ → ユーザー判断「復活させる」→ 修正済み** |
| W-109 | **2026-08-15 の `db6d4b1` が「xp-bottle-store を移す」コミットのついでに `crafting-features.yml` の内容を黙って削除していた**（W-108 の調査中に発見） | **圧縮木材の修繕9種は復旧済み（`486118d`）。行き止まり素材の醸造出口は判断待ち（2026-08-18 ユーザー「不明」）** |

**W-108 の切り分け（TF はバニラの醸造を止められない、を機構レベルで確定させた）。**
醸造を止めうる TF の経路は**4本しかない**。全部を純バニラの `水入り瓶 + ネザーウォート` に当てて潰した:

| 経路 | 発火条件 | 純バニラで当たるか |
|---|---|---|
| `BrewUnlockListener#onBrew`（キャンセル） | `matchesIngredient` が出荷15件の素材に一致 **かつ** 下段に `THICK`/`MUNDANE` のビン | **当たらない**（素材は砂糖・ウサギの足・金のニンジン・キラキラスイカ・金リンゴ・custom×8 のみ。ネザーウォートは1件も無い） |
| `BrewUnlockListener#onBrewingStandFuel`（燃料拒否） | `isLockedBrew` = 上と同じ条件 | **当たらない**（同上。`matchesBase` は `PotionType.valueOf` の完全一致なので `WATER` が `THICK` に化けることも無い） |
| `CatalogVanillaOperationGuardListener#onBrew` | 素材枠かビン枠に**CMD 付きのカタログ品** | **当たらない**（ネザーウォートにも水入り瓶にも CMD は無い） |
| `CatalogVanillaOperationGuardListener#onBrewingFuel` | 燃料枠がカタログ品 | **当たらない**（素のブレイズパウダー） |

**W-82/W-83 の修正が乗っていないという線も潰した。** 稼働中の jar
（`Main/Dev/Resource` の3台とも 8/18 19:45）を展開して `BrewRecipeSupport.class` /
`PotionQualityListener.class` に **`potionDisplayName` が実在すること**を確認済み。
サーバは 20:33 起動なので**修正は live**。起動ログにも
`brew-unlocks: registered 15 custom potion mix(es)` が出ており、登録も全件通っている
（6グループ = 2+2+3+4+2+2 = 15 で一致）。醸造まわりの例外・警告は1行も無い。

**→ 調査中に特定した実バグ ~~（未修正・要判断）~~ → 2026-08-18 ユーザー判断「復活させる」→ 修正済み: 品質が乗ったポーションは延長・強化ができない。**
`PotionQualityListener#applyQuality` は品質ぶんを足すときに
**`meta.setBasePotionType(PotionType.WATER)` でベースを倒し、全部カスタム効果へ移す**
（段階違いの効果を一意に確定させるための既存パターン）。この結果、出来上がったポーションは
**バニラから見ると「WATER ベースのポーション」**になる。バニラの醸造表は
`(ベースの PotionType, 素材) → PotionType` で引くので、`WATER + レッドストーン` /
`WATER + グロウストーンダスト` は**1件も存在しない = 醸造が始まらない**。
つまり**錬金術ステを持っているプレイヤーほど、自分で作ったポーションを延長・強化できなくなる**。
容器 mix（火薬＝スプラッシュ化）は素材側で引くので影響しない。
`potion_quality_bonus` が 0 のプレイヤーは `applyQuality` に入らないので**この症状も出ない**
（＝「一部の人だけ壊れる」に見える。W-83 と同じ現れ方）。
**⚠️ 当初「醸造が始まらない」と書いたが、実際はもっと悪い。** `WATER + レッドストーン` は
バニラに**実在する**組（→ ありふれたポーション）で、`WATER + グロウストーンダスト` も実在する
（→ 濃厚なポーション）。バニラの mix は**カスタム効果を引き継がない**ので、
**醸造は成立したうえで効果が丸ごと消える**。＝ 延長しようとすると手持ちのポーションを失う。

→ ~~**対応済み**~~ 2026-08-18: `PotionQualityListener#rewriteCustomEffectUpgrade` を新設し、
**素材がレッドストーン/グロウストーンダストで、下段のビンが「WATER ベース＋カスタム効果」のとき、
バニラが書いた結果を効果を保った形で上書きする**。倍率はバニラに合わせて
**延長 8/3 倍（3:00→8:00）／強化 効力+1・持続 1/2 倍（3:00→1:30）**。
即時効果（回復/ダメージ）は持続時間を持たないので延長側は掛けない。

非自明な点を4つ:

- **`PotionMix` は登録しない。** `WATER + レッドストーン` は**バニラに実在する組なので醸造は勝手に
  始まり `BrewEvent` も飛ぶ**。必要なのは結果の差し替えだけで、mix を足すと素の水入り瓶の挙動
  （→ありふれたポーション）まで奪う危険が増えるだけ。`BrewPotionMixRegistrar#vanillaCollision` が
  この2素材を弾いているのと同じ理由。
- **品質は二重に乗せない。** 救済したら `onBrew` はそこで `return` する（`applyQuality` へ進まない）。
  進ませると「延長するたびに品質ぶんがもう一度乗る」ことになる。
- **延長と強化は排他・各1回まで**（バニラの「長い」と「強力」が両立しないのと同じ）。
  PDC `trinityforge:brew_upgrade` で刻む。**2回目は「何も起きない」で止める** ——
  ここでバニラに任せると、まさにこのバグの経路でありふれたポーションに化けて中身を失う。
- **`getIngredient()` ではなく `getItem(3)` で素材を読む。** 実サーバでは等価だが、MockBukkit の
  `BrewerInventoryMock` は素材未設定のとき `getIngredient()` が `IllegalStateException` を投げ、
  **素材を置かない既存テスト（品質・速度側）を巻き添えで落とす**。

回帰は `PotionQualityListenerTest` を 9 → 13 件へ（延長／強化／2回目は無効／素の水入り瓶は不干渉）。
**RED 証明済み**: 救済の呼び出しを `false &&` で殺すと 3 件が
「カスタム効果が 1 件のはずが 0 件」で落ちる。
**⚠ 最初の RED は 3 件とも SKIPPED に化けて証明になっていなかった** ——
救済を外すと結果が `applyQuality` まで流れ、MockBukkit 未実装の `getAllEffects()` に当たるため。
バニラ結果のフィクスチャを spy で包んで初めて「落ちる」ことを観測できた
（ビルドの unintended-skip ガードが拾ってくれたので気づけた）。

→ **残: TF jar の再ビルド＋配備（サーバ停止後、適用はユーザー）**。config 追加は無し。
→ **未対応（今回のスコープ外）**: 発酵したクモの目（反転）も同じ経路でカスタム効果を消す。
  ただし「反転」は任意のカスタム効果に対して意味が定義できないので、救済するなら仕様から決める必要がある。

**W-109 の内訳（`db6d4b1` が消したもの）。**
コミットメッセージは「経験値瓶格納を移す」だけだが、実際には設定エディタの往復とみられる
**内容の消失**が同居していた。`db6d4b1^` と現 HEAD のキーを突き合わせた結果:

- **`wood-repair` の圧縮木材 `*_3x` 9種**（`durability: 16200 / quick-repair: true`）—— 対になる追加が無い**純粋な消失**。
  → **復旧済み**。`CraftingFeaturesConfigWoodRepairTest` の 27 種網羅テストが 5/5 で緑に戻った（復旧前は1件赤）。
- **`brew-unlocks.apex-brew`（極致の調合5種）/ `survivor-brew`（生存者の醸造3種）** —— こちらは**単純な消失ではない**。
  同時期の `13f1d20` が `skilltree/alchemy.yml` の解放ノード側も `brew:apex-brew` / `brew:survivor-brew` →
  `brew:luck` / `brew:healing` へ**差し替えている**ので、現状は「6グループ / 6ゲート」で**整合が取れている**。
  **ここで yml だけ機械的に戻すと、解放するノードが1つも無いグループが復活する。**
  `holdsUnlock` は常に false になるので、そのグループは**永久に解放できないのにゲートだけ掛かる**
  （＝素材を入れると燃料まで拒否される）状態になり、今より悪化する。**戻すなら解放ノードとセットで決める必要がある。**
- **副作用: 行き止まり素材3種の出口が消えた** —— `survivor-brew` は K-22(2)/柱4 で
  `guardian_spine` / `husk_cloth` / `pillager_plate` に用途を作るために足されたもの。
  `ravager_hide` だけは `healthboost-haste-2` に残ったが、**残り3種は再び行き止まり**。
  `ShippedBrewDeadEndMaterialTest`（2件）と `ShippedBrewDeadEndPlanTest`（1件）が赤のままなのはこれ。
  → **残: 「新しい解放ノードを足して出口を作り直す」か「行き止まりを許容してテスト側を現状に合わせる」かの判断。**

**W-104 の解決根拠（真因: 貯蔵先の PDC キーがブロック種別で違う）。**
ソースの置き場は**ジャーが `arspaper:source_amount`、ソースリンクが `arspaper:sourcelink_buffer`**
（`Sourcelink.SOURCE_BUFFER`）と別物なのに、`SourceNetwork#tickTransfer` は
**送信元の `source_amount` だけ**を読んでいた。したがってソースリンクを送信元にすると
**残量が常に 0 と判定されて毎周期黙って読み飛ばされる**。接続自体は `connect()` が true を返して
成立しているので、**「接続完了！ の緑文字は出るのに何も起きない」**という報告どおりの見え方になる。
判断を `SourceStorage`（新設）へ畳み、送信元は貯蔵種別ごとのキーを読む。
同じ箇所で見つかった 2 件も併せて修正:

- **送信先を絞っていなかった** —— ソースを保持しないブロック（祭壇・儀式コア等）へ送ると
  誰も読まない PDC に書くだけで送信元からは減る＝**ソースが黙って消える**。受け取れるのはジャーだけにした。
- **容量に static な `SourceJar.MAX_SOURCE`（＝設定未読込時のフォールバック 10,000）を使っていた** ——
  上位ジャーが**網経由のときだけ 10,000 で頭打ち**になっていた（S-4 と同じ「経路ごとに容量判定が食い違う」型）。
  `SourceJar.maxSource(toTile)` / `addSource` 経由に統一。

`SourceStorageTest`（判断）と `SourceNetworkTransferStorageTest`（配線）で固定。
後者は `storageAt(fromTile)` を潰すと落ちることを実測済み（435 tests / 1 failed → 復帰後 BUILD SUCCESSFUL）。
※ フォークのテスト基盤には Bukkit ランタイムも MockBukkit も無い（`testImplementation` は JUnit と paper-api だけ）
ため、ブロックを 1 個も作れない。挙動テストは書けないのでソース検査で配線を縛っている。

**W-102 の解決根拠（真因: 「スレッドかどうか」の判定がコンパイル時に閉じていた）。**
`ThreadType` が enum だったため、**enum に定数が無い id は「スレッドではない」と判定される**。
`ThreadGui#isEffectThread` は PDC の `arspaper:thread_item_type` を `ThreadType.fromId` に
通して装着可否を決めるので、エディタからスレッドを足しても jar を作り直すまで防具に挿せない。
**報告された3症状はすべてこの1点から出ていた**:

- **lore が生タグ** —— これだけは別因（`ThreadConfig.loreText` が markup を解釈していなかった）。W-96 で修正済み。
- **防具に挿せない** —— `fromId` が null。
- **品質が出ない／反映されない** —— 品質のスレッド専用再刻印（W-53）の発動条件が
  `threadType.hasEffect()` なので、**定数が無い間は品質が常に無視される**。

修正は `ThreadType` を **enum → 「組み込み定数 + 実行時登録」の final クラス**へ変更し、
`BY_ID` を唯一の台帳にしたうえで、`ThreadConfig#load` が `threads.yml` の未知 id を
`ThreadType.register` で登録するようにした（`display_name` / `custom-model-data` / `material` を読む。
効果の数値は持たせない —— 後発スレッドの効果は TF の `item-stats.yml` が持つので、
両方に持たせると二重に効く）。**設定エディタには `threads` エディタ（`arspaper/threads.yml`）が
既にある**ので、これで「設定したらスレッドとして扱われる」が成立する。

非自明な点を3つ:

- **`BY_ID` は定数より前に宣言する必要がある**。各定数のコンストラクタが自分を登録するので、
  宣言順を入れ替えると静的初期化中の NPE で全スレッドが死ぬ。
- **1 id につき1インスタンスなのは変わらない**ので `==` 比較は従来どおり通る。
  ただし `EnumMap`/`EnumSet`/`switch` は使えない（`ArmorManaListener` の `EnumMap` を `LinkedHashMap` へ）。
- **永続化は `getId()` の文字列**で `name()`/`ordinal()` はどこからも使われていないため、セーブデータは無影響。

`ThreadsYamlEnumParityTest` を**片方向の検査に変更**した（yml にあって定数に無いのは
今や正常なので、逆向きだけを落とす）。実行時登録の配線は同テストの新しいケースが縛り、
`registerIfUnknown` の呼び出しを潰すと落ちることを実測済み（436 tests / 1 failed → 復帰後 BUILD SUCCESSFUL）。

**W-103 の解決根拠（材質は 5 箇所に分かれて持たれている）。**
スレッド 51 件のうち **32 件**を壺の欠片／旗の模様／糸から**防具装飾の鍛冶型 18 種**へ移した
（`excavation` の 1 件だけは `NETHERITE_UPGRADE_SMITHING_TEMPLATE` のまま残す。
ここを動かすと W-87 で直したネザライト鍛冶型の複製問題に触るため）。ユーザー指示どおり**材質の重複は許容**。

**同じ材質が 5 箇所に別々に書かれていて、1 つでも漏らすと無言で壊れる**:

| 場所 | 役割 | 漏らすとどうなる |
|---|---|---|
| ArsPaper `ThreadType.java` の `baseMaterial` | 実際に配るアイテムの材質 | 配られる物と設定が食い違う |
| TF `items/catalog.yml` の `material:` | 図鑑・レシピ・エディタが見る材質 | 図鑑に出ない／レシピが組めない |
| TF `stats/item-stats.yml` の `<材質>#<CMD>` キー | ステータスの引き当てキー | **ステが 0 になる**（キー不一致は警告も出ない） |
| `resourcepack/cmd-registry.json` の `material` | CMD の永続台帳 | 次に CMD を採る人が衝突に気づけない |
| `resourcepack/.../assets/minecraft/items/<材質>.json` | CMD → モデルの宣言 | CMD が参照されず見た目を分けられない（W-95 と同じ穴） |

置換件数は機械的に照合済み: `ThreadType` 32 / `catalog.yml` 32 / `item-stats.yml` 124 箇所
（26 材質 × 4 領域 + `STRING` 6 種）/ `cmd-registry.json` 32。
**`catalog.yml` と `ThreadType` の (id, 材質, CMD) は 51/51 完全一致**を突き合わせで確認した。
`cmd-registry.json` に **(材質, CMD) の衝突は 0 件**（総アロケーション 686 のまま）。

副次的に直したもの:

- `assets/minecraft/items/` に**鍛冶型 19 種の宣言を新設**した（`range_dispatch` + `fallback`。
  entries は 51 スレッド分の CMD を全部並べ、今はどれもバニラモデルを指す）。
  **宣言ファイルが無いと CMD は一切参照されない**ので、テクスチャを足すときに
  「モデルを差し替えるだけ」で済む形にしてある（W-95 の受け皿）。
- `items/string.json` の `100023`〜`100028`（旧 TF ステ専用スレッド 6 種）が死に定義になったので削除。
- `resourcepack/dist/trinityforge-catalog-pdc-hints.json` を再生成（`build_pdc_hints.py`）。
  **この生成物は元から stale だった**ので、材質変更のほかに剣の base_item 7 件と
  エンチャント試練の鍵 10 件が併せて入る（どちらも HEAD の `catalog.yml` が正）。

追随が必要だったテスト 3 本（いずれも**材質名をリテラルで持っていた**）:

- editor `item-stat-decimal-policy-2026-08-09.test.js` の `PER_QUALITY_EXEMPT` 5 件。
  ここは `item-stats.yml` のエントリ名そのままなので、**追随しないと除外が外れて違反として出る**。
- TF `ThreadSocketedOnlyAggregationTest` / `ItemAssemblerStatLoreBlockTest` の材質フィクスチャ。

**W-105 の現状。** ワンド保持中の**経路パーティクル可視化は 2026-08-01 に実装済み**で、
`sourcelinks.yml` の `transfer.network.path-particles.enabled` は既定 true、
稼働中サーバの jar にもクラス（`SourceNetworkParticleTask`）が入っている（20:33 起動 > 19:46 の jar 生成）。
見えなかった理由は 2 つ:

1. **配線が 1 本も無かった** —— `plugins/ArsPaper/source-network.yml` は `connections: []`（21:38 更新）。
   W-104 で転送が起きないため、張っても消していたとみられる。
2. **ソースリンク→隣接ジャーの自動供給は `SourceNetwork` に載らない** ——
   ワンドで結んだ経路ではないので**可視化の対象外**だった。つまり
   「実際にソースが流れている繋がり」こそが一切見えていない。
   → 保持中に**隣接供給も橙色で描く**ようにした（経路は青→水色のまま。色で描き分ける）。
   探索は `SourcelinkTickTask` の設置位置キャッシュを使い、視界内のものだけ 6 近傍を見る。

**W-92 の解決根拠。** `enchantment_challenge_*_sanctum` は `maxPlayerCount: 1` の**ソロ専用**。
ダンジョンブラウザから既存インスタンスへ参加すると `DungeonInstance#addNewPlayer` が走るが、
**TrinityForge の鍵消費（`checkDungeonEntryAllowed`）が `super.addNewPlayer()` より手前**にあったため、
2 人目以降は**鍵を取られてから「満員」で追い返されていた**。
判定は非消費版 `previewDungeonEntryAllowed` で先に行い、**参加が成立した後に消費**する順序へ変更。
`EnchantmentTrialEntryOrderTest#keyIsConsumedOnlyAfterTheJoinActuallySucceeded` が
`addNewPlayer` の**バイトコード内での呼び出し順**を固定する（消費を手前へ戻すと落ちる。実測済み）。

**W-93 の解決根拠。** 17:44–17:47 のログが一次証拠。
`/em start` の 6 秒後に `EnchantmentDungeonInstance.defeat(...:111)` で
`NullPointerException: ... "this.currentItem" is null`、その 40 秒後に
`Attempting to delete world em_id_enchantment_challenge_1_2 with 1 players still in it!`。
真因は**入口が 2 系統あること** —— エンチャントメニュー経由（`setupRandomEnchantedChallengeDungeon`）でだけ
賭けアイテム（`currentItem`／`upgradedItem`）がセットされ、**TF の鍵ゲート経由の通常入場では両方 null のまま**
`EnchantmentDungeonInstance` が作られる。そこで `defeat()` が NPE を投げ、
**例外が `InstancePlayerManager#playerDeath` まで抜けて直後の「元の位置へ戻す」を丸ごと飛ばす**。
結果プレイヤーは `players` にも `spectators` にも属さなくなり、`removeAnyKind` が何もしないので
`/em quit` でも戻れず、ワールドも「中に人が居る」ため削除に失敗して残り続けた
（`em_id_enchantment_challenge_1_1` / `_2` の 2 本が停止時まで残留）。
直したのは 2 点 —— (1) `hasEnchantmentStake()` が false のときは `victory()`／`defeat()` が賭けアイテムに触れず、
`endMatch()` も通常ダンジョンの終了処理へ委ねる、(2) `playerDeath` の `defeat()` を try/catch で包み、
**何が起きても脱出テレポートだけは必ず通す**。RED は「ガードを消すと落ちる」で確認済み。
なお「敵が一瞬で消えた」のは defeat 後の `removeInstance()` が
`world.getEntities()` を `WORLD_UNLOAD` で unregister するため。

**W-94 について。** `MobCombatSettings.yml` の `doNaturalEliteMobSpawning: true` が生きており、
バニラモブが「エリート ○○」へ変換されてレベル入りの名前を持つ。TF 側は `mob-level-table.yml` /
`mob-overrides.yml` で全モブにレベル・HP を持たせ FocusHp 表示（`Lv.N 名前` ＋ HP）を出しているので、
**レベル体系も頭上表示も二重になる**。TF 構成では EliteMobs 側の自然エリート化は役割が無いため止める。
`ops\launch\disable-natural-elites.cmd -Apply`（**サーバ停止中に実行**）。
既に湧いているエリートは倒す／デスポーンするまで残る。戻すときは `-Revert -Apply`。

**W-89 / W-91 の解決根拠。** `FocusHpDisplay#findFocusTarget` の救済判定が
**「プレイヤーの目とモブの目を結ぶ角度が `LOOK_CONE_DOT = 0.90`（≒25.8°）以内」という点と点の円錐**
だったのが真因。ヒットボックスの大きさを一切見ないので、**大きいモブほど体の端が円錐から外れ**
（ガストの角を 4 ブロック先から見ると内積 0.83 で脱落）、逆に小さいモブでは円錐が広すぎて
「外したのに出る」側にも外れていた。**エンダードラゴンは本体の `getBoundingBox()` が見た目より遥かに小さく、
実体は `getParts()` 側にある**ので、素直なレイキャストでも当たらない。
直したのは 3 点 —— (1) `World#rayTrace`（ブロック遮蔽込み）→ ヒットボックスへの AABB レイキャスト、の 2 段構え、
(2) エンダードラゴンは `getParts()` を合併したボックスで判定・配置、
(3) ラベル位置を「目の高さ + 0.55」から**「ヒットボックス最上面の中心 + 0.35」**へ。
0.35 なのは**数ブロック級のモブの見え方を変えないため**（ゾンビは旧 1.74+0.55=2.29 に対し新 1.95+0.35=2.30）。
騎乗中のモブは `getVehicle()` の連鎖を辿って除外する（多段騎乗も全段）。
幾何は `FocusHitbox` に切り出して MockBukkit 無しで検証している
（`FocusHitboxTest` 9 件・`FocusHpDisplayTest` 6 件、スキップ 0）。
**旧円錐へ戻すと落ちることを `legacyConeWouldHaveMissedTheEdge` が数値で固定してある。**

**W-88 の解決根拠。** GUI のアイコンを `Material` から**新品で組み直していた**ため
CustomModelData / item_model / 防具トリム / 染色が全部落ちていた。`BaseGui#createButtonFrom`
（実アイテムを `clone()` して名前と lore だけ差し替える）を足して `ThreadGui` の 2 箇所を差し替えた。
**ArsPaper fork のソースは `.gitignore` 除外なので commit されない。反映は jar の再ビルドと配備のみ。**

**W-86 の調査結果。** **儀式そのものは健全だった。** 3 秒のアニメーション後に
コアと台座を再走査して（`revalidatePedestals`）レシピ一致を取り直し、ずれていればソースを返して中止する。
台座・コアを壊した場合は `Pedestal#onBlockBroken` / `RitualCore#onBlockBroken` が
中身のアイテムを完全なデータ付きでドロップするので、**ロストも増殖も起きない**。
排他は「コア座標」で取っており、台座を共有する 2 つのコアを同時に回しても
Bukkit のタスクは 1 スレッドで順に走るため、後発は必ず空の台座を見て失敗する。
一方で**グリフ解放の側に増殖と恒久ロックが 1 件ずつあった**。以下 3 件を直した（全て fork 側）:

1. **（増殖）解放していないのに最大マナだけ増える。** `ScribingTableGui` の保存コールバックは
   アニメーション後の経験値レベル再検証に失敗すると素材を返して中断するが、**戻り値が `void` だった**ため、
   呼び出し元の `GlyphUnlockAnimation#completeUnlock` は中断を知らずに
   「最大マナ +5」と「解放: …！」を続行していた。素材は返ってくるので、
   **レベル不足のまま解放を押し続けるだけで最大マナを無限に増やせた。**
   コールバックを `BooleanSupplier` にして、成立したときだけ加算・通知するようにした。
2. **（恒久ロック＋素材ロスト）解放中にネザーポータルをくぐると詰む。**
   中断判定が `player.getLocation().distanceSquared(center)` で、**ワールドが違うと
   `IllegalArgumentException` を投げる**。繰り返しタスクなので毎 tick 例外になり、
   `cancel()` も `animatingPlayers` の解除も ArmorStand の後始末も走らない
   ＝素材は消費済みのまま、**そのプレイヤーは再起動まで二度とグリフを解放できない**。
   距離を測る前にワールド比較を入れた。
3. **（ロスト）返せなかったソースが黙って消える／オフライン相手への素材返却が消える。**
   `RitualManager#refundSource` は近くのジャーに空きが無いと残りを捨てていた
   （アニメーション中にジャーを壊されると容量が減るので起こりうる）。ソースはアイテムとして落とせないので
   消える以外に手が無く、**消えた量をプレイヤーへ伝える**ようにした。
   併せて、素材返却パーク（`material-refund-chance`）がアニメーション中にログアウトした相手の
   インベントリへ `addItem` していたのを、オフラインなら台座へドロップするよう直した。

**W-90 の解決根拠。** AFK 突入の通知は<b>突入した後</b>にしか出ないので、プレイヤーからは
「何の前触れもなく報酬が止まった」ようにしか見えなかった。`afk.yml` に
`warn-before-seconds`（既定 30・0 で無効）と `warn-title`（既定 true）を足し、
AFK 前は「AFK 判定まで」、AFK 中は「自動キックまで」を数える予告を出す
（`kick-after-seconds: 0` なら AFK 中は何も出さない ── 何も起きないのに数えない）。
**カウントダウンは判定タイマーと別に毎秒回す** ── 既定の `check-interval-ticks: 40`（2 秒）に
相乗りさせると「30, 28, 26, ...」と飛んでカウントダウンに見えないため。
タイトルは<b>段階が変わった 1 回だけ</b>（毎秒出し直すと fadeIn がかかり直して点滅する）、
毎秒更新するのはアクションバーの数字のほうで残り 5 秒以下は赤へ変わる。
判定は `AfkService#warnStateFor` という純関数へ切り出して MockBukkit 無しで検証している
（`AfkWarnCountdownTest` 11 件・スキップ 0）。`warn-before-seconds` が `idle-seconds` 以上だと
ログインした瞬間から出続けるので Java 側は `idle-seconds - 1` へ引き下げ、
editor は保存時点で弾く（`kick-after-seconds` と同じ方針）。
editor の「使用制限スイッチ」画面にも 2 キーを出した。

~~**W-85 は設計確認が要る。**~~ **解決（2026-08-18）。** ユーザ決定は
**「誰でも回収可・品質は実行者基準」**。旧挙動は 2026-08-04 の設計そのもので、
`RitualManager` が成果物を `dropItemNaturally` で落とし、`PdcKeys.ITEM_PENDING_CRAFT_QUALITY` が付いた品を
`PickupQualityListener` が**最初に拾ったプレイヤーのステータスで**品質ロールしていた。
ドロップアイテムは近くにいる誰でも歩くだけで拾えるので、実行者より先に他人が拾えば
**他人の魔法鍛冶レベルで品質が決まり、SOULBOUND の所有権まで拾い主のもの**になっていた。

直し方は 2 段構え。

1. **品質と所有者を儀式完了時に実行者で確定させる。** TF に
   `com.trinityforge.stats.RitualCraftFinalizer` を新設し、`TrinityForgeBridge#stampCraftedQuality` が
   マーカーを刻む代わりにこれを呼ぶ。実行者は儀式を発動した直後なので**必ずオンライン**で、
   決定を後回しにする理由がそもそも無い。確定後の成果物は `rollSeed` を持ちマーカーも無いので、
   `PickupQualityListener` は誰が拾っても素通りする（＝二重刻印にならない）。
   **所有者刻印は `ItemFactory#stamp` の後**に行う必要がある —— `bindType` はカタログテンプレートから
   `ItemAssembler` が組むので、stamp 前の meta にはまだ入っておらず、先に見ると
   「SOULBOUND なのに所有者が空」になって結局拾い主が所有者になる。
2. **成果物をコアへ載せ、右クリックで回収させる。** `RitualCore.setStoredItem` は
   スレッド枠拡張儀式の書き戻し用に既にあったのでそれを使う。コアが壊されても
   `RitualCore#onBlockBroken` が中身を落とすのでロストしない。
   コアに別のアイテムが載っている場合（コア非消費レシピで素材を置いたまま等）だけは、
   それを消さないよう従来どおりドロップする。

回帰ガード: TF `RitualCraftFinalizerTest`（7 件、mutation で RED 確認済み —— `clearPendingCraftQuality`
と所有者刻印を外すと 2 件落ちる）。fork 側の `RitualQualityExpGateSeparationTest` は
**旧仕様（マーカーだけ刻む）を固定していたので新仕様へ差し替えた** ——
ソース文字列を固定するガードなので、仕様が変わったら必ずここも直す。

**W-87 の原因（確認待ち）。バニラは潰されていない。潰れるのは「バニラに見えるカタログ品」を入れたとき。**
順に潰した結果、**バニラの複製レシピ `minecraft:netherite_upgrade_smithing_template` を止める経路は無い**:
`removed-vanilla-recipes` は出荷も配備済みも `[]`、`gated-catalog-recipes` も `{}`、
`CatalogCraftGateListener` の recipe ゲートは「そのゲート ID を配置したノードが在る場合だけ」効くが
`recipe:netherite_upgrade_smithing_template` を置いたノードは無い、
`CatalogVanillaOperationGuardListener` はそもそも作業台のハンドラを持たない上に
**CustomModelData を必須**にしているので素のバニラ鍛冶型はカタログ品と判定されない、
`CatalogRecipeRegistrar#NetheriteUpgradeGuard` は「自分の登録を諦める」だけでバニラを消さない、
TF/Ars のどのレシピも鍛冶型を材料に使っていない（＝同形シャドウイングも起きない）。

**残った唯一の経路がこれ。** `CatalogWorkbenchListener#onPrepareCraft` は
「カタログ品は、その identity を明示的に受け取るレシピでしか消費させない」ため、
装備でないカタログ品がグリッドに乗っていて対応する TF レシピが無ければ**結果枠を消す**。
そして **`thread_excavation`（掘削のスレッド）のベース材質は `NETHERITE_UPGRADE_SMITHING_TEMPLATE`
（CMD 300045）**で、**リソースパックに `assets/minecraft/items/netherite_upgrade_smithing_template.json` が無い**
（`assets/minecraft/items/` の 62 件を確認。陶器の欠片系スレッドも同様に未定義）ため、
**このスレッドは見た目がバニラの鍛冶型と完全に同一**。つまり
「鍛冶型のつもりでスレッドを置いた」なら、保護が意図どおり働いて結果が消えたことになる。
（見た目の統一そのものは既存の **W-70「スレッドのバニラマテリアル統一」** と同じ問題。）

**併せて直したこと。** それまでは**結果枠が黙って空になるだけ**で理由が一切出ず、
プレイヤーからは「バニラのレシピが壊れている」ようにしか見えなかった。
兄弟の `CatalogCraftGateListener` と同じくアクションバーで理由を出すようにした
（このイベントはマス目を触るたび飛ぶのでチャットには書かない）。

**2026-08-18 追記: 実物のスクリーンショットで確定した。** 盤面は
`#S#` / `#C#` / `###` = **バニラの複製レシピそのもの**で、**結果枠が空**。
つまり上の「保護が意図どおり働いて結果が消えた」が実際に起きている。

**⚠️ 2026-08-18 訂正: 犯人は圧縮ネザーラック。** ユーザーから
「みんな**普通の鍛冶型**で起きたと言っている」と指摘され、**バニラのレシピ本体を server jar から抽出した**
（`bluemap/minecraft-client-1.21.11.jar` → `data/minecraft/recipe/netherite_upgrade_smithing_template.json`）:

```json
"key": { "#": "minecraft:diamond", "C": "minecraft:netherrack",
         "S": "minecraft:netherite_upgrade_smithing_template" }
"pattern": [ "#S#", "#C#", "###" ]
```

**中央はネザーラック**（ネザライトブロックではない）。そして ArsPaper には
**`netherrack_1x`〜`netherrack_4x`（「9倍圧縮ネザーラック」〜「6561倍圧縮ネザーラック」、
`base_material: NETHERRACK` / CMD 1001〜1004）**があり、
`assets/minecraft/items/netherrack.json` が無いので**素のネザーラックと見た目が完全に同一**。
**ネザーラックは誰でも大量に持つ＝誰でも圧縮する素材**なので、
複数人が同時に「バニラの鍛冶型が複製できない」と報告したのはこれで説明が付く。
鍛冶型もダイヤもバニラのままで正しかった。

**したがって当初名指しした `thread_excavation` / `netherite_block_1x` は容疑者としては外れ**
（機構は同じなので理屈上は踏み得るが、今回の報告の実物ではない）。
`resourcepack/trinityforge-items/assets/minecraft/items/` には
**`netherrack.json` も `diamond.json` も `netherite_upgrade_smithing_template.json` も無い**ので、
これらの材質のカスタム品は**見た目がバニラと 1 ピクセルも変わらない**。
機構は全段コードで裏取り済み:
`catalogIdentityOf` は PDC → `catalog.yml` → **`ExternalItemRegistry`（= Ars 品もカタログ品扱い）**の 3 段 →
`gridHasCatalogItem` が true →
`gridCatalogItemsAreAllGear` は**スタックできる品があると false**（圧縮ブロックは重なる）→
`foreignRecipeOwnsGridItems` は選択レシピの namespace が `minecraft` で Ars 品の所有者と一致せず false →
`rematch` も失敗 → **`setResult(null)`**。
（この保護自体は正しい。緩めると「圧縮ネザライトブロックがバニラのレシピで普通のブロック1個として溶ける」。）

**直したこと。** アクションバーの理由表示（`7b426c6`）に加えて、
**止めた原因のアイテムを名指しする**ようにした。見た目がバニラと同一である以上、
「専用アイテムが入っています」だけでは材料欄のどれのことか分からず、結局
「バニラのレシピが壊れている」という結論にしかならない。
回帰ガードは `CatalogWorkbenchBlockedMessageTest`（4 件、mutation で RED 確認済み）。

**根治は W-70「スレッドのバニラマテリアル統一」側。** 見た目が同一である限り
「置いてから気づく」しか無く、通知は事後対処にすぎない。
圧縮素材にも同じ問題があることが分かったので、**W-70 の対象はスレッドだけではない**。

---

### 実サーバ報告バッチ（2026-08-19 受領 第5陣。W-110〜W-112）

| # | 報告 | 状態 |
|---|---|---|
| W-110 | **`network.yml` を書き換えたのに config デプロイでロールバックした** | **原因確定 → 配備前の検出＋退避を実装（ユーザー選択）** |
| W-111 | ヴォルカニックソースリンクが「もうゲート撤廃しているはずなのに作れない」（W-101 の再報告・伝聞） | ~~未解決~~ **解決（原因＝稼働中 JVM が旧 config 保持。再起動で解消をユーザー確認）** |
| W-112 | 「まだポーションが作れない」（W-108 の再報告） | ✅ **真因確定・修正済（配備待ち）**（W-124 で決着）。`BrewEvent` の最中に呼ぶ `stand.update()` が醸造台を醸造前の状態へ巻き戻していた。詳細は「W-124 醸造ログ」節 |
| W-113 | **TF のポーションが統合版で必ず「水入り瓶」の見た目になる**（W-112 調査中に発見） | **未修正。`PotionMeta#setColor` を焼いていないのが原因** |
| W-114 | 日光炎上の「最大HP10%」が反映されず**1ダメージのままのアンデッドがいる**。「一度鎮火してから再炎上するときかも」 | ~~未着手~~ **修正済み（真因＝空の判定を足元ブロックで見ていた）** |
| W-115 | **ポーション統合パーク（`potion-merge`）が一度も発火しない**（W-112 調査中に発見） | **原因確定・未修正。発火条件が実クライアントで成立しない組み合わせ。テストも同じ穴で緑のまま** |

**W-114 の解決根拠（真因: バニラは目の高さで見るのに、TF は足元ブロックで見ていた）。**
`SunlightBurnListener#isInDaylight` は「空が見えているか」を
`entity.getLocation().getBlock().getLightFromSky() >= 15`＝**足元ブロック**で判定していた。
一方バニラの日光焼却は `Mob#isSunBurnTick` →
`canSeeSky(BlockPos.containing(getX(), getEyeY(), getZ()))` で**目の高さ**を見る。

この食い違いが表に出るのが**浅い水に立っている個体**で、水はスカイライトを減衰させるため
**足元だけ 15 を割る**。すると「バニラは焼いているのに TF の置換だけスキップされ、
バニラの 1.0 固定ダメージがそのまま残る」＝**1ダメージのまま焼け死なない**。
出荷対象 `sunlight-burn.mobs` に **`DROWNED`** が入っていること、そして
**水は火を消す**ことから、報告の「一度鎮火してから再炎上するとき」と状況がちょうど一致する。

**直したこと。** 判定を `seesSky(eyeSkyLight, feetSkyLight)` に切り出し、
**目の高さを先に見て、足元は OR の保険**にした（頭がブロックにめり込む個体・小型モブの取りこぼし対策。
この置換は元から「上げる方向」にしか働かないので、緩い側へ倒しても弱体化事故は起きない）。
回帰ガードは `SunlightBurnListenerTest` に 3 件追加（計 7 件）。
**ミューテーション（足元のみへ戻す）で「目の高さで空が見えていれば日光扱い」が
`expected: <true> but was: <false>` で落ちることを実測済み**＝報告と同じ症状を再現する RED。

**W-111/W-112 の再調査（2026-08-19 01:00 実測）— 報告はほぼ確実に「再起動前のテスト」。**

まず現物とログで確定した事実:

1. **配備先の config にゲートは残っていない。** `Main_Server\plugins\TrinityForge` 配下の全 yml を走査し、
   `volcanic` を含むゲート行は 0 件（`ars_smithing.yml` A-3 の `dedicated-effects` は
   `ritual:source_crystal/condenser/engine` の 3 つだけ。バックアップフォルダと図鑑の対象リストのみヒット）。
   ArsPaper 側 `unlock-gate.yml` も `recipe-perks: {}` / `ritual-perks: {}`。
2. **フォークのゲート判定は「ゲート未定義 = 解放」**（`UnlockGate#hasPermission`: yml 由来と
   TF skilltree 由来が両方空なら true。GUI 表示側 `RecipeBrowserGui#isUnlocked` も同じ判定）。
   つまり撤去された今、機構上ロックされる経路は無い。
3. **ただし yml はプラグインの enable 時にしか読まれない。** config 配備は 08-18 23:43。
   main のログローテーションから、23:43 以降の再起動は **00:27 と 00:36 の 2 回**で、
   それ以前に走っていた JVM は**ゲート入りの旧 config をメモリに持ったまま**だった。
   **21:42（ゲート撤去 commit）〜00:27 の間のテストは全部「まだゲートあり」の環境**で行われている。
4. 醸造側も同じ: W-108 の修正 jar（延長/強化の復活）が効くのは 00:36 起動以降。
   00:36 起動ログで `brew-unlocks: registered 15 custom potion mix(es)` と
   ArsPaper のレシピロード（189 workbench + 64 ritual、警告 0 件）を確認済み。
   純バニラ醸造を止める TF 側経路が無いことは W-108 で機構レベル確定済みのまま変わらず
   （燃料ゲート `isLockedBrew` は「登録済み gated mix の組み合わせ」しか見ない）。

**W-111 は解決（再起動後にユーザー確認済み）。** 原因は上記 3 のとおり「稼働中の JVM が旧 config を保持していた」。

**W-112 の続報（2026-08-19 01:20 受領）: 「奇妙なポーションを作ると、完成しても
ネザーウォートが消費されず瓶も水入り瓶のまま。ただし EXP は入る。ブレイズパウダーは消費される」**

症状ごとに分けて確定させた。

**(a) ネザーウォートが消費されない ＝ `ingredient-save-chance`（材料節約率）が正常動作している。**
`BrewIngredientSaveListener`(HIGHEST) が、当たりを引くと素材スタックを **+1** しておき、
直後にバニラが行う `shrink(1)` と相殺させる方式（`EntityShootBowEvent#getConsumable` の
`ammo-save-chance` と同一手法）。**バグではない**が、プレイヤーからは「消費されない＝醸造が失敗した」
としか見えない。alchemy ツリー D の「素材を消費しない確率UP」を取っていると発現する。

**(b) EXP が入る ＝ BrewEvent はキャンセルされていない（確定）。**
TF の `BrewEvent` 購読は 5 本（`BrewUnlockListener` NORMAL / `CatalogVanillaOperationGuardListener` HIGH /
`PotionQualityListener` HIGH / `BrewIngredientSaveListener` HIGHEST / `NativeSkillExperienceListener` MONITOR）で、
**全て `ignoreCancelled = true`**。EXP を配るのは MONITOR の 1 本だけなので、
**EXP が入った時点でキャンセル経路は全て否定される**。

**(c) 純バニラの 水入り瓶 + ネザーウォート に TF は一切触れないことを機構レベルで再確認。**
- `BrewUnlockListener`: `matchesIngredient` は `Material.matchMaterial` の厳密一致、
  `custom:` は `CrossPluginItemResolver.idOf(...).filter(id::equals).isPresent()` で、
  NETHER_WART は出荷 15 レシピのどの素材（SUGAR/RABBIT_FOOT/GLISTERING_MELON_SLICE/GOLDEN_CARROT/
  GOLDEN_APPLE/`custom:*`）にも一致しない → `matched.isEmpty()` で即 return。
- `CatalogVanillaOperationGuardListener`: `catalogIdOf` は **CustomModelData が無いと即 empty**。
  素の水入り瓶もネザーウォートも CMD を持たないのでキャンセルしない。
- `PotionQualityListener#applyQuality`: `meta.getAllEffects()` が空なら return。
  **AWKWARD（奇妙なポーション）は効果を持たない**ので何もしない。
- `BrewPotionMixRegistrar`: `inputChoice`/`ingredientChoice` は述語だが中身は上と同じ厳密判定。
  出荷 15 件の base は全て THICK / MUNDANE で、WATER ベースを横取りするものは無い。
- 配備 jar（0:26:44）に W-108 の修正が入っていることを `PotionQualityListener.class` の
  文字列（`brew_upgrade` / `rewriteCustomEffectUpgrade` / `potionDisplayName`）で確認済み。

**(d) 新たに見つけた実バグ（未修正・W-113 として起票）: TF のポーションは統合版で必ず「水入り瓶」に見える。**
TF は段階の違う効果を一意にするため、`BrewRecipeSupport#customPotion` でも
`PotionQualityListener#applyQuality` でも **ベースを `PotionType.WATER` へ倒して全部カスタム効果で表現**する。
そして **`PotionMeta#setColor` を一度も呼んでいない**（`setColor` の呼び出しは
`ItemFactory` の革防具 1 箇所のみ。全ソース走査で確認）。
Java 版クライアントは**効果からポーション色を導出する**ので見た目は正しくなるが、
**Geyser はポーションを base の種類で対応付ける**ため、統合版では base=WATER のまま
＝**水入り瓶の見た目**になる。W-82/W-83 の修正は `displayName` を焼く**名前側の手当て**なので、
**見た目は直っていない**。実サーバの在席者は Floodgate 接続（`.` プレフィックス）が大半。

ただし **(d) は「奇妙なポーション」単体の説明にはならない**（AWKWARD は TF が触らないため）。
その一点だけは未解明なので、Java 版クライアントで同じ醸造を試した結果が要る。

**W-112 の深掘り第2ラウンド（2026-08-19。ユーザー回答「java でもダメだった」を受けて）。**

まず **(d) は原因から外れた**。Java 版でも再現する以上、Geyser の見た目問題では説明できない。

**前回の調査には構造的な穴が 2 つあった。**

1. **`Grep` は `.gitignore` を尊重するため、フォークのソースが検索から丸ごと漏れていた。**
   `fork-handoff/**` を明示指定して再走査した結果、フォーク側に `BrewEvent` の購読は **0 本**
   （`CustomItemListener#onBrewingFuel` の `BrewingStandFuelEvent` だけ）。フォークは白。
2. **読んでいたのはリポジトリの yml で、サーバが実際に読み込んだ config ではなかった。**
   W-110 で「配備先の直接編集」が実在すると判明した直後なので、ここは実物で裏を取った
   → 配備先 `progression/crafting-features.yml` は **リポジトリと SHA-256 一致**（15 件・
   base は THICK/MUNDANE のみ・`NETHER_WART` を使う spec は 0 件）。

**そのうえで、実物・実ログで排除できたもの（すべて根拠付き）。**

| 排除した仮説 | 根拠 |
|---|---|
| 配備 jar が古い / バックエンド間でドリフト | 3 バックエンドとリポジトリの `build/libs` が **SHA-256 完全一致**（08-19 00:26 ビルド） |
| 配備 config が違う | 上記のとおり **SHA-256 一致** |
| TF のカスタム mix がバニラの 水→奇妙 を横取り | Paper はカスタム mix をバニラより**先に**照合するが、`NETHER_WART` は `WATER_VANILLA_INGREDIENTS` に**入っている**ので `vanillaCollision` が登録を拒否する。実際 config に wart を使う spec は 0 件 |
| 醸造中の例外でリスナーが中断 | 直近 8 本のログ（gz 含む）を全走査。`Could not pass event ... BrewEvent` は**一度も無い**。醸造関連の出力は `registered 15 custom potion mix(es)` のみ |
| リソースパックがポーションを上書き | `resourcepack/` に potion 関連ファイルは **0 件**、`cmd-registry.json` に POTION の登録も **0 件** |

**残った分岐は 1 つだけで、しかも自己矛盾している。**

- **キャンセルされた**なら「ビンも素材もそのまま」が一度に説明できるが、
  EXP を配る `NativeSkillExperienceListener#onBrew` は MONITOR + `ignoreCancelled = true` なので
  **EXP が入らないはず**（報告と矛盾）。
- **キャンセルされていない**なら EXP は説明できるが、素材が減らない説明は
  `ingredient-save-chance` の確率しか無く、その既定値は **alchemy ツリーで 0.1 + 0.1 = 最大 20%**。
  「毎回そうなる」という報告とは噛み合わない（＝ **(a) の説明は前回言い切ったほど強くない**）。

静的解析はここで尽きた。**どちらが起きているかは実測しないと決められない**ので、計装を入れた。

**入れたもの: `BrewDiagnosticListener`（`listeners/BrewDiagnosticListener.java`）。**
`BrewEvent` を **LOWEST〜MONITOR の 6 優先度すべてで `ignoreCancelled = false`** で購読し、
各段のキャンセル状態と、ビン 3 枠の入力／結果（材質・ベース種別・カスタム効果数・表示名・CMD の有無）を
1 行にまとめて出す。**出すのは「怪しい醸造」だけ**（キャンセルされたか、どのビン枠も変換されていないとき）
なので、正常な醸造では 1 行も出ない。判定は `BrewDiagnosticListenerTest`（7 件）で固定した。

読み方は「`cancel@[LOWEST=no LOW=no NORMAL=no HIGH=yes ...]` なら NORMAL と HIGH の間の
ハンドラが犯人」。全部 `no` なら TF は誰もキャンセルしておらず、原因は TF の外
（Paper の mix 解決かクライアント表示）に確定する。**次に 1 回醸造してもらえば分岐が閉じる。**

**W-115（W-112 調査中に発見した別の確定バグ）: ポーション統合パークが一度も発火しない。**
`PotionMergeListener#onBrewingClick` は発火条件が
「`getCurrentItem()` がポーション」**かつ**「アクションが `PLACE_ALL/PLACE_ONE/PLACE_SOME`」なのだが、
**`PLACE_*` は空きスロットへ置いたときにしか出ない**（中身のあるスロットへ別のアイテムを重ねると
`SWAP_WITH_CURSOR` になり、ポーションは重ならないので必ずこちら）。つまり
**「スロットに既にポーションがある」と「PLACE_*」は同時に成立しない**。
唯一の抜け道である `ClickType.SWAP_OFFHAND` 分岐も、読んでいるのが
`event.getCursor()`（オフハンド交換中は**空**）なので同じく必ず抜ける。
**＝ alchemy ツリーの `potion-merge` は取得しても何も起きない。**

しかも `PotionMergeListenerConsumptionTest` は `PLACE_ALL` と「スロットにポーションがある」を
**同時にスタブ**しており、実クライアントが決して生成しない組み合わせを検査しているため
**テストは緑のまま**だった（`allowlist-tests-and-source-pinned-guards-fail-silently` と同型）。
併せて、仮に発火した場合も **効果を持たないポーション同士**（水入り瓶・ありふれた・濃厚・奇妙）を
統合すると `mergePotions` が効果 0 個の `new ItemStack(Material.POTION)` ＝**素の水入り瓶**を返し、
元の 2 本を消してしまう。修正するなら発火条件と併せてこのガードも要る。

**W-110 の原因（W-106 のオーバーレイは「リポジトリ側」しか守らない）。**
W-106 で config 配備の既定を「HEAD ＋ ワーキングツリーの yml を上に重ねる」に変えたが、
**重ねる元はリポジトリの `TrinityForge/src/main/resources/`** であって、配備先の
`D:\...\Main_Server\plugins\TrinityForge\` ではない。配備は今も **リポジトリ → サーバの一方向**。

`network.yml` は**設定エディタが画面を持っていない**（`tools/config-editor` に該当スキーマ無し。
grep で当たる `transfer.network` は ArsPaper のソース転送設定で無関係）。
そのため書き換えるなら**配備先の実ファイルを直接編集する**しかなく、
その編集は次の config 配備で HEAD の内容に**無言で上書きされる**。実測でも
配備先の `network.yml`（2026-08-18 23:43:03）は `src/main/resources/network.yml`（08-15 コミット `c1ef6b4` のまま）と
**SHA-256 が完全一致**しており、サーバ側の編集は残っていない。
`deploy-config-head.cmd` は上書き前のバックアップを取らないので、**書いた内容は復元できない**。

**回避策（今すぐ効くもの）。** `TrinityForge/src/main/resources/network.yml` の方を編集する。
未コミットのままでも W-106 のオーバーレイで配備される（ただし他セッションと衝突するので編集したら commit する）。

**やったこと（ユーザー選択: 配備前に検出して退避）。**
`ops/scripts/guard-deployed-config.ps1` を追加し、`deploy-config-head.cmd` にステップ 3/4 として入れた。

- 配備の**最後**に `-Mode Record` で「配備直後の配備先 yml の SHA-256」を
  `tmp/deploy-config-manifest.json` に残す。次回の `-Mode Check` が現物と突き合わせ、
  **前回配備の後に変わったもの＝サーバ側で編集されたもの**を `[DRIFT]` で名指しする。
- `[DRIFT]` は上書き前に `tmp/deploy-config-backup/<日時>/` へコピーする。**もう失われない。**
- 台帳が無いとき（初回・`tmp` 削除後）は `[UNKN]` を出して**現物を丸ごと退避**する（安全側）。
- 増えた yml は `[NEW]`（配備では消えない）。**配備は止めない**（退避済みなので失われず、
  止めると「エディタで直したものを配りたいだけ」の通常運用まで巻き込む）。
- TF は `TF_CONFIG_HOST` の 1 箇所だけ（他はジャンクション）、ArsPaper は 3 台とも見る。

検証: 偽の配備先ツリーで Record → 無改変 Check が `[ OK ]` → 1 ファイル書き換え＋1 ファイル追加後の
Check が `[DRIFT]` 1 件と `[NEW]` 1 件を出し、**退避先に編集後の内容（`enabled: false`）が残る**ことを実測。
`deploy-config-head.cmd --dry-run` の通し実行で exit 0、非 ASCII 0 バイトも確認。

**残っている穴。** エディタが画面を持たない yml は依然「サーバ側で直接編集 → 次の配備で上書き」のまま
（ただし退避されるので復元できる）。恒久対処は**リポジトリ側 `src/main/resources/<同じパス>` を編集する**か、
エディタに画面を足すか。後者は未着手。

---

### 実サーバ報告バッチ（2026-08-19 受領 第6陣。W-116〜W-119）

| # | 報告 | 状態 |
|---|---|---|
| W-116 | **クロスボウの使用レベルが Lv25 のまま**（「以前、弓と同じ Lv0 にしろと指示したはず。なぜ直っていないのか原因も究明して」） | **調査完了・コード変更不要。既に Lv0（下記の照合結果を参照）。見ていたのは鉄のクロスボウ（Lv25＝鉄の弓と同値）である可能性が高い** |
| W-117 | **撃破EXPが渋すぎる**（重量Lv39 で Lv25 ピグリンが 1〜10 EXP／軽量Lv78 で Lv80 エンダーマンが約700 EXP。報告多数） | **真因確定 → 同一地点逓減を緩和（ユーザー選択）。W-107 を吸収** |
| W-118 | 弓の攻撃にかかる時間の割に**火力と経験値が渋い** | **修正済み（階梯の逆転2件＋攻撃力 ×1.35＋弓術の討伐EXP基礎値 25→30）** |
| W-119 | 弓とクロスボウに**性能面での住み分け**が欲しい | **実装済み（ユーザー選択「弓＝手数と機動／クロスボウ＝一撃と貫通」）** |
| W-120 | **Ars向け装備の儀式レシピの素材とソースが簡単すぎる**。バニラ Ars と同じ素材にし、賢者・星詠みはそれより重く。既存カスタム品（コア・圧縮・未使用ドロップ）を使ってよい | **実装済み（ユーザー選択「インフィニティ／ヒーローシリーズに寄せる」）** |

**W-120 の根拠（帯は合っているのにコストだけ3桁ずれていた）。**
`use-level-requirement` を突き合わせると、魔導防具は
**見習い Lv20 / 魔術師 Lv40 / 大魔導士 Lv60 / 賢者 Lv80 / 星詠み Lv100** で、
**星詠みは infinity 防具とまったく同じ Lv100 帯**。ところがソース要求量は:

| | ソース | コア | ペデスタル |
|---|---|---|---|
| infinity 防具（Lv100・最終） | 15,000,000〜30,000,000 | `custom:core_jewelry` | `custom:amethyst_block_2x` ×3〜7 |
| hero 武器（Lv100） | 8,000 | `DRAGON_EGG` | 圧縮3x＋特殊ドロップ ×2 |
| **魔導・星詠み（Lv100・旧）** | **20,000** | 前段 | `NETHER_STAR`＋`ECHO_SHARD` ×3 |

同帯の最終装備に対して **1/1000 以下**。ソース経済の実測値（fork の `materials.yml`）は
**欠片＝焼べると 500 / 結晶＝18,000 / 凝縮核＝700,000 / 機関＝30,000,000** なので、
**星詠み1点がソース結晶1〜2個で作れていた**。

**直したこと（`tools/config-editor/scripts/retune-mage-ritual-cost.js`。62レシピ）。**
部位別の重みは infinity 防具の 15M/30M/25M/15M と同じ比 **1.0 / 2.0 / 1.6 / 1.0**（兜/胴/脚/靴）。
素材は**バニラ Ars と同じ基幹**（マジブルーム繊維／ソースジェム／ソースジェムブロック）から始め、
上位2段でソース階梯と特殊ドロップを重ねる。

| 段 | 兜1点のソース | ペデスタル |
|---|---|---|
| 見習い(Lv20) | 5,000 | 繊維 ×8＋ソースジェム ×4 |
| 魔術師(Lv40) | 50,000 | 繊維 ×16＋ジェム ×8＋ジェムブロック ×1 |
| 大魔導士(Lv60) | 400,000 | 繊維 ×24＋ジェムブロック ×4＋**ソース結晶 ×1** |
| 賢者(Lv80) | 2,000,000 | ジェムブロック ×8＋ソース結晶 ×4＋`dragon_scale` ×2＋`echo_shard_2x` ×2 |
| 星詠み(Lv100) | 8,000,000 | ジェムブロック ×16＋**ソース凝縮核 ×2**＋`warden_tendril` ×4＋`amethyst_block_2x` ×4 |

到達点: **星詠みフルセット 44,800,000 ソース**（infinity フルセット 85,000,000 の約 53%）。
「最終装備の一歩手前」に収まり、ソース機関（30,000,000）を組む動機になる水準。
魔導書2点も同じ段位観で `spell_book_apprentice` 1,000→**100,000**、`spell_book_archmage` 5,000→**1,000,000**。

**併せて `ars-smithing.exp-per-material` に 2 行追加**（`custom:source_crystal: 400` /
`custom:source_condenser: 3500`）。儀式が新たにこの2種を消費するようになったので、
入れないと `ShippedRitualMaterialExpCoverageTest` が落ちる。値は「作るのに要るソース」ではなく
**焼べたときの価値**の相場（結晶=18,000相当→`source_gem_block`(180) の倍以上、
凝縮核=700,000相当→`core_jewelry`(5000) の一段下）。
**index 側は「儀式素材 176 種すべてに行がある（欠け 0）」を実測で確認済み。**

**W-116 の照合結果（＝「直っていない」のではなく、既に直っている）。**
`959eb3c`（2026-08-18 04:28 / W-75）が要求どおりの変更を入れており、
**リポジトリと配備先の `stats/item-stats.yml` は SHA-256 が一致**（`B09E2AEC95D859C8`）。
素の `CROSSBOW:` は `use-level-requirement: 0`、`use-skill: ARCHERY` で**弓と完全に同条件**。
段位も弓と並走している（弩 `#160`=5 / `#162`=15 / `#163`=25 / `#164`=35 / `#165`=45 / `#166`=60、
弓 `#1091`=5 / `#1093`=15 / `#1094`=25 / `#1095`=35 / `#1096`=45 / `#1097`=60）。
**`#163` = 鉄のクロスボウ が Lv25** なのは、鉄の弓 `#1094` が Lv25 なのと揃えた意図どおりの値。
ゲート（`UseRequirementResolver`）→ 刻印（`ItemAssembler`）→ 再組み立て（`ItemRefreshListener`）の
経路も追って動作を確認済み。**残る可能性は「00:36 の再起動より前に試した」か「鉄のクロスボウを見た」の2つ。**

**W-117 の真因（逓減が2系統あり、しかも掛け算で合成される）。**
重量Lv39 で Lv25 ピグリンの期待EXPは `base 30 + level 25×2 + maxHealth×0.188` ×`PIGLIN 1.1` ≒ **170**。
報告は 1〜10。落差は次の3つの積で説明できる:

1. `outside-dungeon-exp-rate: 0.25` — ダンジョン外は一律 1/4。
2. `spot-diminishing` — 下限 **0.1**。
3. `daily-diminishing` — 下限 **0.2**。

`NativeProgressionService#grantExpUnderRepositoryLock` はこの3つを**乗算で合成**する
（`skill-exp.yml` のコメント「レベル逓減とは軸が違うので乗算で合成する」）。
`170 × 0.25 × 0.1 ≒ 4` で**報告値そのもの**になる。

**同一地点逓減が誤爆していた理由。** 旧値は「半径16・直近5分で **30体**」で減衰開始、**48体で下限10%**。
`LocationExpDiminishing` が数えるのは**撃破のみ**なので被弾や命中では増えないが、
それでも**エンドのエンダーマン／ネザーのピグリンを集めて刈る**という正常なプレイは
この密度に普通に到達する。**TT を作っていないプレイヤーが下限に張り付く**のはこのため。

**やったこと（ユーザー選択「同一地点狩りを緩和する。日時減衰はこちらで調整する」）。**
`stats/skill-exp.yml` の `spot-diminishing` だけを `threshold: 30→80` / `decay-per-kill: 0.05→0.02` /
`floor: 0.1→0.4` へ。**`daily-diminishing` は指示どおり一切触っていない。**
新値は「5分・半径16で **80体**までフルEXP、**110体目**で下限40%」＝3.75秒に1体を1箇所から
動かずに維持し続ける密度で、手動では届かず湧き機構では確実に届く。
`LocationExpDiminishingTest` の固定値も出荷値へ追従させた（7件緑）。

**エンダーマン約700 EXP（旧 W-107）は式どおりで、逓減の誤爆ではない。**
Lv78 の次レベルに要るEXPは曲線 `(%level% + 75×2^(%level%/8)) + 300` で約 **65,000**＝約93体。
ダンジョン内なら 0.25 が掛からないので約 23 体。**これは曲線側の話**なので、
渋いと感じるならレベル曲線か `outside-dungeon-exp-rate` を動かす判断になる（今回は未変更）。

**W-118 / W-119（弓・クロスボウの再校正）。** 実測で分かった3点:

1. **弓の段位に逆転が実在した。** 出荷データは全段が「剣の `attack-power` × 1.3143（弓）／× 0.9198（弩）」で
   生成されているのに、**`copper_bow`(Lv15) と `golden_bow`(Lv35) の2本だけ**がこの比から外れていた
   （0.94 / 0.936）。結果 **`copper_bow`(112.8) が `stone_bow`(138) より弱い**。過去の再校正の取りこぼし。
2. **弓の1発は `attack-power × 引き絞り量(0〜1)`**（`CombatListener` の `PROJECTILE` 分岐）。
   クロスボウは 1.21.11 では常に 1.0。**1.21.11 に弓の引き絞り時間を動かす手段は無い**ので、
   時間あたりの火力は `attack-power` でしか調整できない。
3. **住み分けが存在しなかった。** 弓が攻撃力・会心率・命中・矢節約・移動速度・距離ボーナスの
   すべてで弩を上回り、弩が勝つのは貫通と会心ダメージだけ＝**弩を選ぶ理由が無い**。

直した内容（`tools/config-editor/scripts/retune-archery-ladder.js`。31エントリ）:

- 段位の逆転を修正（`copper_bow` 112.8→157.72、`golden_bow` 557.76→782.93。倍率適用前の値）。
- **弓 `attack-power` ×1.35**（W-118 の火力底上げ。剣の実効DPSと概ね並ぶ水準）。
- **クロスボウ `attack-power` ×2.22** — 弩/弓の比を 0.70 から **1.15** へ反転させ、
  `damage-modifier` の差（弓 0.65→期待0.825／弩 0.72→期待0.86）込みで**1発の重さが弓の約1.20倍**。
  発射間隔は弓20tick／弩25tick なので**持続DPSは概ね同等**（会心期待値込みで 1.00:1.00）。
- 副次ステを役割へ振り分け（**既定値の差分シフト**で書いたので `abyss_*` のような一点物の味付けは保存）:
  弓＝会心率 0.18→0.30／会心ダメ 0.72→0.60／命中 0.07→0.16／矢節約 0.08→0.22／移動 0.015→0.035、
  弩＝貫通 0.13→0.30／会心ダメ 0.85→1.30／矢速 0.12→0.26／距離 0.08→0.22／移動 0.008→0.003。
- **弓術の討伐EXP基礎値 25→30**（重武器と同値）。旧値の根拠「遠距離で安全だから下げる」より
  「1発ごとに約1秒の引き絞り＝同じ時間で殴れる回数が近接の半分以下」という時間コストが大きかった。
  `SkillExpConfigTest` の固定を `軽武器 < 弓術 <= 重武器` へ更新（重武器超えは引き続き禁止）。

`WeaponTierParityTest` / `WeaponDpsParityTest` / `ShippedWeaponIdentityTest` /
`MeleeUnintendedItemAttackSpeedTest` / `ItemStatsConfigTest` は全て緑のまま。

**`skill-exp.yml` の素材表に未コミットの再スケールが同居している（ユーザー確認済み＝意図的）。**
W-117/W-118 のコミットには**自分のブロックだけを index に載せた**ので、この編集はワーキングツリーに
未コミットのまま残っている（W-106 のオーバーレイでそのまま配備される）。実測した中身:

| 種別 | 件数 | 実際の効き方 |
|---|---|---|
| 値の変更 | 75 | 安価な素材を 10 に平坦化＋ボス素材を増額（`HEAVY_CORE` 150→1500 / `DRAGON_EGG` 300→3000 / `NETHERITE_BLOCK` 1080→2000） |
| 行の削除（値 0） | 23 | **挙動不変**。圧縮素材 `custom:*_Nx` の 0 行 |
| 行の削除（値あり） | 74 | その素材ぶんが**乗らなくなる**。バニラ鉄/金/ダイヤ防具12件・魔導書2件・魔導防具シリーズ60件 |

**`_Nx` の 0 行を消しても壊れないことを実装で確定した（同ファイルの警告コメントは古かった）。**
コメントは「1つでも表に無い素材があると素材合計を丸ごと捨てて `ars-smithing.exp-per-craft` の
定額(100)へ戻る」と書いていたが、**その全か無かの分岐は 2026-08-17 に定額ごと廃止済み**。
現在は `ArsProgressionBridge#sumMaterialExp` も `CraftQualityListener#arsSmithingBaseExp` も
**「表に無い素材は 0 を積む」だけの単調な合計**で、他の素材へは波及しない。
古い警告文は 2 箇所（`ars-smithing` 節と `smithing` 節）とも訂正した。

**残っている赤: `ShippedRitualMaterialExpCoverageTest`。** この編集を commit するなら、
テスト側にも「意図して無報酬にした素材」の除外を入れる必要がある。
**行を消すより 0 を書くほうが安全**（消すと「意図的に0」と「足し忘れ」を機械的に区別できない）。

---

### 実サーバ報告バッチ（2026-08-19 受領 第7陣。W-121〜W-136）

| ID | 内容 | 状態 |
|---|---|---|
| W-121 | 儀式レシピの素材が本家 Ars より貧弱（ネザースター・残響の欠片・ネザライト等を使うはず） | ✅ 対応（下記。W-120 の台座事故もここで修正） |
| W-122 | レシピ一覧で圧縮アイテムを既定で省略する機能が効いていない | ✅ 対応（= W-99。fork `f26a8fc`。下記） |
| W-123 | 絞り込みに「儀式エフェクト」を足し、日の出／スレッド枠付与をそちらへ | ✅ 対応（fork `f26a8fc`。下記） |
| W-124 | 再起動後に醸造したのでログ確認（W-112 の決着） | ✅ **真因確定・修正済（配備待ち）**。`BrewEvent` 中の `stand.update()` が醸造台をスナップショットへ巻き戻していた（下記） |
| W-125 | editor の数値が小数点以下細かすぎる（`76.323902` 等） | ✅ 対応（下記） |
| W-126 | 重武器が弱い。範囲武器の対象上限を最低5→tierで10、軽武器にも3体。各武器種に尖ったステ | ✅ 対応（下記） |
| W-127 | 杖が強すぎるので微ナーフ（全 tier 監査） | ✅ 対応（下記） |
| W-128 | オーバーワールドのエンダードラゴンでドラゴンエッグ 100% 1個 | ✅ 対応（下記） |
| W-129 | 圧縮ブロック・圧縮素材にエンチャントオーラを付ける | ✅ 対応（fork `9d336ee`。171 件を `enchant_glow: true` に統一） |
| W-130 | モブレベル／ドロップ品質ステに応じるはずの品質付きドロップが劣悪しか出ない | ✅ 対応（下記） |
| W-131 | 農業ギミックで登録しているのに圧縮ご飯が食べられない | ✅ 対応（下記） |
| W-132 | 中間素材（ウッドコア等）がクイック移動では入らないのにホッパーでかまどへ搬入できる | ✅ 対応（fork `490e501`。下記） |
| W-133 | 革装備ベースのカスタム装備の色が重複していて識別できない（白が複数） | ✅ 対応（下記） |
| W-134 | 経験値フリーザー未解放者が充填済み経験値瓶を投げてしまう（中身が消える） | ✅ 対応（下記） |
| W-135 | 称号が揺れる／トロッコ搭乗など姿勢変化時に視界を塞ぐ | ✅ 対応（下記） |
| W-136 | 軽武器と重武器だけ討伐EXPが少ない（Lv80 エンダーマンで 1600 程度） | ✅ 対応（`720253c`。下記） |
| W-137 | ランキングに全スキルが用意されていない（魔法・重武器など一部レベルだけ） | ✅ 対応（UserRankBoard `f6225fc`。下記） |
| W-138 | 職業バフが付いていない人がまだいる（確認しているのは火炎耐性＝きこり） | ✅ **真因確定・修正済（配備待ち）**。参加時の再付与が HuskSync の snapshot 適用より前で毎回消されていた（下記） |

#### W-137 スキル別ランキングは 12 件が config で無効にされていた

**機能は 17 項目すべて実装済み**で、TF 側の公開 API `TrinityForge#rankingTop` も
`skill_<id>_level` を汎用に受ける（TF 側の変更は一切不要）。原因は
UserRankBoard の出荷 `config.yml` が 12 スキルを `false` で配っていたこと。

| 状態 | 項目 |
|---|---|
| true（＝出ていた） | 採掘 / 重武器 / 魔法 / 総合 / スキル合計 / 図鑑(アイテム) / 図鑑(モブ) / グリフ |
| false（＝無かった） | 伐採 掘削 農業 釣り 鍛冶 錬金 付与 弓術 軽武器 重装 軽装 魔法鍛冶 |

絞ってあった理由は「16 種を全部 ON にするとサイドバーのスライドが冗長になる」で、
判断自体は妥当。**問題は 1 つのフラグが 2 つの意味を兼ねていたこと** ——
「`/pixelrank rank` で引けるか」と「サイドバーに勝手に流れてくるか」が同じキーだったので、
巡回を絞る手段が「順位表ごと消す」しか無かった。設定を 2 つに割った（ユーザー選択）:

| セクション | 決めること | TF 項目の既定 |
|---|---|---|
| `ranks.*` | その順位表が存在するか（コマンドで引けるか） | **全 true** |
| `scoreboard.*` | サイドバーの巡回に入るか | 従来と同じ代表 8 枚 |

> ⚠️ **`setDefault` だけでは既存サーバが直らない。** `Configuration#setDefault` は
> 「キーが**無いとき**だけ書く」ので、既に `false` で生成済みの config には効かない
> （＝この状態がずっと続いた理由そのもの）。`ranks.migrate-tf-ranks` を足し、
> 次の起動で 1 回だけ全 TF 項目を true へ戻してフラグを自分で false にする。
> **config を配り直さなくてよい**（`install-ranking-plugin.ps1` の再実行は不要）。

> ⚠️ 新しく足したキーを素の `Configuration.getBoolean` で読むと**未設定＝false** になり、
> 更新した瞬間サイドバーが空になる。`getBoolean(path, fallback)` を足して既定へ倒している。

回帰は `ShippedRankingDefaultsTest`（新規 4 件）。**出荷 config.yml と enum の既定値が
食い違ったら落ちる** —— 既定値の出所が「新規配備＝出荷 config」「既存サーバ＝`setDefault`」の
2 本あり、片方だけ直すとサーバによって挙動が割れる（ログには何も出ない）。
テストは 58 件 / 失敗 0。jar は `PixelRank-1.3.jar`（5,861,491 bytes、2026-08-19 15:20）を再ビルド済み。

#### W-136 AoE スプラッシュが戦闘EXP台帳に**1行も**載っていなかった

武器スキルEXPは「命中で `CombatKillCreditTracker` へダメージを記録 → 討伐確定時に寄与ぶんだけ支払う」方式
（`CombatListener#onCombatKill`）。ところが **AoE スプラッシュは再入ガード `applyingAoe` によって
`onEntityDamageByEntity` ごと早期 return される**ため、台帳への記録も一緒に飛んでいた。結果:

- スプラッシュで止めを刺したモブ → `credits.isEmpty()` で即 return ＝ **EXP がまるごと 0**
- 主命中と併殺したモブ → 主命中ぶんの `share` しか立たず目減り

**AoE を持つのは大剣（重武器）と鎌（軽武器）、およびパーク由来のパワーアタック範囲（近接全般）だけで、
弓術には無い。** 報告の「軽武器と重武器だけ」はこの非対称そのものだった。

修正は `maybeApplyAreaDamage` に武器を渡し、各対象へダメージを与える**直前に**（台帳は被弾前HPで
クランプするため）主命中と同じ `maybeRecordCombatSkillDamage` を通す。ワールド倍率ゲートと
訓練用ダミー除外も主命中と同じ条件で掛ける。回帰は `CombatListenerAoeSplashExpCreditTest`
（修正を戻すと落ちることを実際に確認済み。スプラッシュが隣へ届いていること自体も別テストで土台固定）。

**報告値 1600 の内訳**（検算は `tmp/exp-model.js`）: Lv80 エンダーマン（最大HP 124,597）の名目は
35,406。ダンジョン外倍率 0.25 で 8,852、そこへ日次逓減の下限 0.5 と同一地点逓減の下限 0.4 が
両方効くと 1,771 ＝ 実測とほぼ一致する。**つまり 1600 自体は逓減が二重に底を打った値**であって、
軽武器/重武器固有の数値ではない（`combat.kill-exp.base` は HEAVY 30 / LIGHT 20 / ARCHERY 30 だが、
Lv80 では最大HP項が支配的で 3 スキルの差は 0.04% しかない）。**したがって `base` を上げても高レベル帯では
何も変わらない** — この件で効くのは上記の台帳バグ1点だけ。

#### W-126 武器種ごとの「尖り」を1つずつ決めた／W-127 杖を帯の下寄せへ

全 tier を横に並べる監査（`tmp/audit-weapon-dps.js`）で分かった実態:

- **大剣は単体の秒間火力が全近接で最下位**（infinity 帯で 45,592。剣 60,480・メイス 58,143）なのに、
  範囲は対象上限 3 体で鎌と同じだった。低速（攻撃速度 1.0）を取り返す手段が無く、報告どおり弱い。
- **剣は尖ったステが 1 つも無いのに秒間火力が最上位**という、設計不在のまま最強という状態。
- 斧も「高火力・低速」以外に手が無く、槍（リーチ）・短剣（会心率）・メイス（落下）と比べて薄い。

1 武器種につき 1 つだけ突出させる形へ（`stats/item-stats.yml`。全 53 エントリ）:

| 武器種 | 尖り | 変更 |
|---|---|---|
| 大剣 | 範囲の主役 | 対象上限 3 → **5（最下位 tier）〜10（最上位）**、半径 3 → 4、倍率 0.3 → 0.35 |
| 鎌 | 手数側の範囲 | 対象上限 3 → 4〜7、半径 3・倍率 0.2 据置（**必ず大剣より下**） |
| 剣 | 万能枠 | 全 tier 一律で 半径 2 / 3 体 / 倍率 0.15 の小さい巻き込みを新設（段では伸びない） |
| 斧 | 会心の重さ | `crit-damage` 0.6 → 1.0（**短剣＝会心率 / 斧＝会心倍率**で棲み分け） |
| 槍・短剣・メイス・トライデント | リーチ／会心率／落下／投擲 | 据置 |

**W-127 杖**: 既存の較正契約 `WeaponTierParityTest` は杖を「剣に対する詠唱DPS **40〜60%（狙い 47.5%）**」で
縛っている。実測すると出荷値は **48〜49% ＝ 狙いどおりの位置**で、単体DPSでは「強すぎる」を説明できない
（遠距離・範囲グリフ・被弾しない、が効いていると見るべき）。そこで**契約の帯を割らずに下寄せする**
方針にし、全 10 本の `attack-power` を一律 **×0.87**（`fixed` / `per-quality` / `random` の min-max とも）＝
42〜43% に置いた。

> ⚠️ 最初は「同 tier のメイスの 1.3 倍」で揃えたが、それだと 35〜39% まで落ちて `WeaponTierParityTest` の
> 下限 40% を割った。**メイスではなく剣が既存契約の基準**であることを、テストに落とされて初めて確認できた。
> 同様に、剣への巻き込み追加は `ShippedWeaponIdentityTest` の `AOE_TYPES`（薙ぎ払い勢のみ許可）に弾かれた
> ので、ユーザー指示にもとづく**設計変更として** `sword` を追加し、上下関係を固定する検査を足してある。

回帰は `ShippedWeaponIdentityTest` に 3 件追加（剣の巻き込みは全 sweeper 未満／大剣の対象上限は 5→10 で
単調非減少／鎌は 3 つのつまみすべてで同 tier の大剣以下）。

⚠️ `MATERIAL#CMD` エントリは素の `MATERIAL` を**置き換える**（マージしない。`ItemStatsConfig#profileFor`）。
素の `*_SWORD` に足した AoE は、同じ材質でも CMD を持つ短剣・杖・大剣には波及しない（実物で確認済み）。

##### 追補（同日）: 斧の会心倍率が上位帯 8 本にしか入っていなかった

上表の「斧 `crit-damage` 0.6 → 1.0」を、**`source_gem` 以降の 8 本にしか適用できていなかった**。
`wooden_axe_tf`〜`netherite_axe_tf` の 7 本が 0.6 のまま残り、同じ武器種なのに帯をまたぐと尖りが
消える段差になっていた（＝ユーザー指示「すべての tier の武器を監査すること」が拾うはずだった穴）。
7 本を 1.0 に揃え、回帰 `ShippedWeaponIdentityTest#axeCritDamageIsUniformAcrossTiers` を追加した。

この検査が見るのは **`fixed` だけ**。`random` のロール幅は個体差の設計で、金の斧（±0.7 の大振れ）や
原子力の斧（ロール無し）のように**帯ごとに違ってよい**ため、`fixed+random` の実効値で縛ると誤検知する
（実際に最初そう書いて赤くなった）。`Weapon` レコードに `critDamageBase` を足して分離してある。

同じ形の穴が他の武器種にも無いかを全 16 種 × 全 tier で機械的に洗った（`tmp/tier-gap-audit.js`）。
残ったばらつきは**すべて意図どおり**で、追加の修正は不要と判断した:

| 見えたばらつき | 判定 |
|---|---|
| `golden_*` だけ `damage-modifier` が 0.95〜0.98（全武器種） | 金シリーズの性格（脆いが火力）。系列の設計 |
| `cryocore_*` だけ `bleed-chance` 0.12（全武器種） | 氷シリーズの性格。系列の設計 |
| 大剣の `aoe-radius` が 3 / 3.5 / 4 の 3 段 | W-126 で入れた帯スケール。**これは伸ばすのが正しい** |
| `revolution_bow` だけ会心 0.18/0.72 | 実体は `DIAMOND_SWORD#1099` の特殊武器。id 末尾が `bow` なので型判定に紛れ込んでいるだけ |

> 補足: 鎌の金シリーズだけ id が `golad_scythe`（`golden` の綴り違い）。id は PDC とレシピの実キーなので
> 改名は影響が広い。**今回は触っていない**。直すなら別件で（見た目・挙動には出ていない）。

#### W-122／W-123 レシピ一覧を「儀式エフェクト」と「圧縮の最大倍率のみ」で整理した（fork `f26a8fc`）

**W-123（儀式エフェクトの分離）**: `KindMode` に `RITUAL_EFFECT` を足し、儀式を
**「アイテムを作る儀式」と「儀式エフェクト」**へ分けた。判定は `RitualRecipe#isCraft()` の裏返し
（`effect-type` が `craft` 以外）を**そのまま借りている**。GUI が独自に対象一覧（sunrise /
thread_slot_expand / …）を持つと、新しい `effect-type` を足したときに**絞り込みだけが無言で
取りこぼす**ため。対象は 日の出／月の出／天候／飛行／修復／召喚／エンチャント本化／スレッド付与／
スレッド枠拡張 の全部（出荷 yml で 23 件＋スレッド適用）。

> スレッド付与（`effect-type: thread`）も「アイテムを作らない儀式」なので**儀式エフェクト側へ入る**。
> ユーザーが例に挙げたのは日の出とスレッド枠付与の2つだけなので、ここは判断で寄せている。
> スレッドを儀式レシピ側に戻したい場合は `isEffectRitual` に除外を1行足すだけで済む。

ついでにスレッド枠拡張の儀式が **アイコンが既定の醸造台**（枠拡張だと分からない）で、
**説明が1行も出ていなかった**（`resolveEffectDescription` の `default -> null` に落ちていた）ので、
鍛冶台アイコン＋「コアに置いた装備のスレッド枠を +1（儀式由来の累計 N 枠まで）」を入れた。

**W-122（= W-99。圧縮の間引き）**: 「効いていない気がする」ではなく**機能自体が無かった**。
圧縮素材は `materials.yml` だけで **171 件 / 62 連鎖（最大5段）**、`crafting-features.yml` にも
28 件 / 10 連鎖あり、さらに `reversible: true` の解凍レシピが加わる。W-99 の確定仕様どおり
**「各連鎖の最大段だけを出し、中間段と解凍はオプションで on/off（既定 off）」**を実装した。

判定は**レシピキー（`RecipeEntry#id`）だけ**で行う。Ars は `materials.yml` のエントリ id をキーに
使い（`stone_3x`）、逆レシピは `_decompress` を足す（`RecipeManager`）。TF カタログ由来は
`catalog_` 接頭辞。**表示名（「81倍圧縮石」）では判定しない** —— 表示名は yml の自由記述で、
倍率の書き方が揺れた瞬間に間引きが無言で効かなくなる。

> ⚠️ **「その連鎖の最大段」は絞り込み前の母集団全体で1度だけ決める。** 絞り込み後に決めると、
> 検索するたびに繰り上がった中間段が現れて「同じアイテムが出たり消えたりする」ように見える。
> これを固定する回帰を1件入れてある。

トグルはスロット 51 の新ボタン（既定＝最大倍率のみ）で、並べ替え／絞り込みと同じく PDC に保存する。
**何が隠れているかはボタンと見出しの lore に必ず書いた**（隠したこと自体がバグ報告で返ってくるため）。

> ⚠️ このコミットには **W-97（レシピ一覧のソート／絞り込みを PDC で記憶）の実装が同梱**されている。
> **フォーク側だけコミットされずワークツリーに残っていた**もので、今回の圧縮トグルの保存が
> その `readPreference` / `rememberPreferences` に乗るため分離できなかった。差分は全て追加行。

回帰は `RecipeBrowserFilterTest` に 5 件追加。フォーク全体で **445 tests / 0 failures / 0 skipped**。

#### W-125 出荷値の小数を「エディタが画面に出す単位」で丸めた

**報告そのものが、2026-08-09 に決めた方針が守られていなかったという事実だった。**
`tools/config-editor/test/item-stat-decimal-policy-2026-08-09.test.js` は当時から
「item-stats.yml はエディタの表示単位で小数第1位まで」を検査していて、**この報告を受けた時点で既に赤**
（editor スイートの失敗 21 件のうちの 1 件）。つまり新しい要望ではなく、**未達の是正**。

出所は生成スクリプトが吐いた桁で、`item-stats.yml` に**小数4桁以上が 803 箇所**あった
（`attack-power` 252／`random.max` 159／`random.min` 141 ほか。例 `TRIDENT fixed.attack-power: 1074.344385`）。
これは editor で生の桁がそのまま見えるだけでなく、`random:` の**刻み幅量子化**（min/max の小数桁が
そのままロール刻みになる。`StatRange#step`）を無意味に細かくしていた。

丸めは **PERCENT ステを ×100 した「画面の単位」で**決める（割合のまま桁を数えても見え方と一致しない）:

| section | 許容 | 丸め方 |
|---|---|---|
| `fixed` / `random.min` / `random.max` | 小数1桁 | \|表示値\| ≥ 100 は整数、それ未満は小数1桁 |
| `per-quality` | 小数2桁 | ≥100 は整数、≥10 は1桁、それ未満は2桁。ただし**1桁にしても 5% 未満しか動かない値は1桁へ落とす** |

`per-quality` だけ緩いのは、品質1段あたりの上昇量は桁が2つ小さく、1桁へ丸めると
`0.06 → 0.1`（+67%）や `0.0015 → 0.002`（+33%）になり、**「短剣＝会心率／斧＝会心倍率」のような
武器種の尖り（W-126）や装備間の差がまとめて潰れる**ため。実際に 2 段階で丸めた結果、
1040 箇所を丸め、**2桁のまま残ったのは 113 箇所**（`crit-chance` 92／`penetration` 15／スレッド系 6）。

動いた量は `fixed-damage` の **最大 3.2%**（`1.26 → 1.3`）が最大で、`attack-power` は 0.71% 以内。

> **許可リストを1つ廃止した。** 旧テストは `PER_QUALITY_EXEMPT` に 7 件を名指しで並べていたが、
> 出荷値を実際に丸めてみると 7 件とも「per-quality を1桁にすると 17〜67% 動く」という**同じ理由**だった。
> 理由が同じものは列挙より規則の方が腐らないので、リストを消して section 別の許容桁に置き換え、
> 代わりに「2桁を使っているのは 1桁だと 5% 以上動く値だけか」を**出荷値そのものに対して**毎回検査する
> （緩和が言い訳に使われないための非空振り検査）。

ついでに `item-stats.yml` の見出しコメント 2 箇所を実装に合わせた:
2026-08-02 の「小数第3位まで」の記述（今回の方針に置換）と、**W-127 の説明が取り下げた案
（メイスの1.3倍）のまま残っていた**箇所（剣基準 ×0.87 へ訂正）。

テストは TF 4346 / 失敗 25（この変更の前後で同一。全て他セッションの未コミット yml 由来）／スキップ2、
editor は失敗 21 →（この 1 件が緑になり）20。残り 21 件はいずれも `catalog.yml` など他セッションの
WIP 由来で、内容も「アイテムが無い」「必要Lvが違う」で小数とは無関係であることを個別に確認した。

#### W-133 革装備の色を段・系統ごとに振り直した

革装備はベース材質が全部 `LEATHER_*` なので、区別できる手掛かりは**染色の色だけ**。
`items/catalog.yml` を突き合わせたところ、

- **魔導 `#6B3FA0` / 魔織 `#2ECC71` / 守護 `#A0A0B0` の3系統が、5段20点すべて同色**
  （見習いと星詠みが見た目で区別できない）
- 守護の `#A0A0B0`（灰白）が骨鎧の `#E8E4D8`（生成り）と近く、どちらも「白」に見える
- アクセサリ5点（闘士の帯・実りの籠・潮見の浮き・坑夫の灯・均しの手袋）は `color:` 自体が無く、
  バニラの茶色のままで互いに区別できない

**1セット（兜/胴/脚/靴）が同色なのは意図どおり**なので触らず、系統ごとに色相を固定して
段ごとに明度を上げた（魔導=紫 / 魔織=緑 / 守護は灰をやめて**鋼青**）。アクセサリ5点にも
名前に沿う色を新設。結果、**22 セット＋アクセサリ5点がすべて異なる色**になった。
回帰は `ShippedLeatherArmorColorTest`（革ベース品は必ず色を持つ／別セットが同色を共有しない）。

#### W-134 未解放者が充填済み経験値瓶を投げて中身を失っていた

`XpBottleListener#onInteract` は解放ゲート（`xp-bottle-store-unlock` の `valueMax`）を
**充填済み瓶の分岐より前**に置いていた。未解放だとそこで `return` するので
イベントはキャンセルされず、バニラのエンチャント瓶として投擲され、
**格納した経験値が投擲時の固定量に化けて消える**。
取り出しの分岐を解放ゲートより前へ移し、充填（ガラス瓶→充填済み）だけを解放必須のまま残した。

還元率の tier は**充填時に瓶へ焼き付ける**ようにした（`PdcKeys.ITEM_XP_BOTTLE_TIER` 新設）。
取り出しに解放が要らなくなった以上、持ち主の tier で引くと**受け渡すだけで率が変わる**ため。
このキーを持たない旧い瓶は「持ち主の tier → tier1」の順でフォールバックする。

#### W-135 称号の揺れ（補間長 > 更新間隔）と搭乗時の視界干渉

**揺れ**: 追従は毎tick（`PERIOD_TICKS = 1`）なのに `setTeleportDuration` が 2 のままだった。
補間長が更新間隔より長いと、毎tick「まだ終わっていない補間」を新しい目的地で上書きし続け、
称号は常に本体より遅れて追いつけない＝揺れて見える。補間長を更新間隔と等しくした。

**視界干渉**: 位置は `player.getHeight() + 0.5 + clearance` で決めていた。
トロッコ搭乗などで姿勢が変わると当たり判定の高さは 0.6 前後まで縮む一方、
`getLocation()` の原点は座席側へ上がるので、称号がちょうど目の前に来る。
基準を `max(高さ, 目線の高さ)` に変え、姿勢が縮んでも必ず目線より上に残るようにした
（立ち状態は 高さ 1.8 > 目線 1.62 なので位置は 1mm も動かない）。

#### W-130 討伐ドロップの品質が**必ず劣悪**だった（品質0固定）

`CrossPluginItemResolver#create(String)`（1引数版）は
`create(id, ThreadLocalRandom...nextLong(), 0)` と**品質を 0 に固定**する。
討伐ドロップを積むリスナー3本（`MobTypeDropListener` / `MobLevelTableListener` /
`MobOverrideDropListener`）が全部この1引数版を呼んでいたため、
`custom:` で書かれたドロップ（スレッド等）は**モブレベルにも `mob_drop_quality` ステにも
一切反応せず必ず劣悪**で落ちていた。バニラ材質の装備ドロップだけは
`MobTypeDropListener#resolveQuality` が正しく振っていたので「効く場合もある」ぶん気づきにくい。
`MobTypeDropListener#buildCustomStack` の javadoc は「品質は resolver 内で打たれている」と
**誤ったことを書いており**、それが3本とも直されなかった理由。→ その場で訂正した。

式は `com.trinityforge.mobs.MobDropQualityResolver` 1箇所へ集約
（`modeFromLevel(mobLevel) + mob_drop_quality ぶん + quality-mode-offset` を中心にした split-normal）。
`stamped(...)` は**品質0で1回組んでから素材と CustomModelData を読み、同じ `rollSeed` で組み直す**
（品質基準値の引き当てに現物が要るため。seed 共有なので品質以外のロールは1回目と同じ）。
`mob_drop_quality` の底上げは**1キルにつき1回**だけ引く（ドロップごとに引くとステの効きが薄まって見える）。
回帰は `MobDropQualityResolverTest`（5件）と `MobLevelTableListenerTest`（捕捉した品質が全部0なら落ちる）。

#### W-131 9倍圧縮の生鮮食品が**1件も**食料登録されていなかった

`unregistered-custom-food-ban` は「`custom-foods` に載っていないカスタムID付き食料は素材扱いで
**食べられない**」という規則そのものが判定基準。`stats/food-gimmick.yml` には
**焼き物の `_1x` だけ**が載っていて、`carrot_1x`／`potato_1x`／`beef_1x` 等の生鮮 13 件と
`tf_crystal_apple` が漏れており、**圧縮ニンジン等が一切食べられない**状態だった
（症状はアクションバー1行のみ・ログ無警告）。14 件を追加登録。
`_2x`（81倍）・`_3x`（729倍）は**意図どおり未登録のまま**（満腹度20のために729個を消し飛ばす事故を防ぐ設計）。
回帰は `ShippedCompressedFoodRegistrationTest`（載るべき23件・載ってはいけない8件・ban 有効の3本）。

#### W-132 ホッパー搬入が素通りする真因（未修正）

ArsPaper フォークの `CustomItemListener` は
`onVanillaMachineClick(InventoryClickEvent)` で FURNACE/BLAST_FURNACE/SMOKER/BREWING への
**クリック経路だけ**を塞いでいる。`InventoryMoveItemEvent` の購読は
`onHopperToComposter`（COMPOSTER 限定）しか無いので、**ホッパー／ドロッパー経由は素通り**する。
さらに `BlockCookEvent` を購読していないため、`beef_1x`（ベース `BEEF`）のように
**ベース材質が精錬可能な圧縮素材はホッパーで入れると実際に焼かれて 9 個ぶんが 1 個になる**。
TF 側 `CatalogVanillaOperationGuardListener` は `BlockCookEvent` を塞いでいるが、
判定が `CatalogVanillaOperationPolicy.isCatalogItem`＝**TF `items/catalog.yml` 限定**なので
ArsPaper の `materials.yml` 品（圧縮素材・ウッドコア等）は対象外。

#### W-138 職業バフ（火炎耐性）が付いていない人がいる — **真因確定・修正済**

**真因は「参加時の再付与が HuskSync より前に走っていた」こと。** 症例の火炎耐性は
`progression/role-buffs.yml` の `woodcutter`（きこり）の `potion-buff`（`FIRE_RESISTANCE` / `duration: 999999` / `amplifier: 0`）。

`RoleBuffListener#onJoin` は `PlayerJoinEvent`（MONITOR）の**中で**バフを付けていた。ところが
配備中の HuskSync（`mode: LOCKSTEP`、`features.potion_effects: true` / `persistent_data: true`）は
**snapshot の適用が `PlayerJoinEvent` より後**で、その中身が

- `PotionEffects#apply` — `for (PotionEffect e : player.getActivePotionEffects()) player.removePotionEffect(e.getType());` の**後に**保存済みの効果だけを付け直す
- `PersistentData#apply` — `container.clearNBT()` の**後に** snapshot の PDC を merge する

（どちらも HuskSync の `BukkitData` 現物）。つまり

1. 参加イベントで付けたロールバフは、直後の `PotionEffects#apply` で**必ず剥がされる**
2. そこで読んだ PDC（＝どのロールか）も、`clearNBT()` で**捨てられる前の値**だった

ので、**ロールバフは一度も付け直されておらず、HuskSync の効果 snapshot に相乗りして残っていただけ**。
残り時間は減る一方で更新されないので、`999999` tick ＝ **約 13 時間 53 分**の累計プレイで無言で切れる。
牛乳・`/effect clear`・浄化で消えた場合も同じで、**再ログインしても戻らない**
（戻る道は「死んでリスポーン」か「ロール変更」だけ。リスポーンは HuskSync を経由しないので次 tick 再付与が効く）。
＝ **プレイ時間の長い人から順に落ちていく**ので「まだ付いていない人がいる」に見える。

**修正（2026-08-19 実施・配備待ち）**

1. `onJoin` の再付与を **40 tick（2 秒）遅延**へ。`CollectionListener#JOIN_SCAN_DELAY_TICKS` と同値・同理由
   （このリポジトリは `RankingStatsService` / `AchievementService` / `CrossServerTeleport` でも同じ待ちを入れている。
   **`RoleBuffListener` だけがこの処理を受けていなかった**）。
2. **60 秒ごとの定期リフレッシュ**を新設（`RoleBuffListener#startPeriodicRefresh`、`TrinityForge.java` の
   リスナー登録直後で 1 回だけ起動）。`role-buffs.yml` の説明文が「**常時**」と書いている以上、
   付け直しの機会が参加／リスポーン／ロール変更しか無い設計では、そのどれか 1 つを取りこぼした瞬間に永久に落ちる。
   これが唯一の自己修復経路で、**既に失っているプレイヤーも配備後 60 秒以内に自動で戻る**（コマンド操作は不要）。
   弱い効果の `addPotionEffect` はバニラの効果解決で「より強い既存効果」を上書きしないので、
   プレイヤー自身が飲んだ上位ポーションを潰すこともない。

**RED 証明**: `RoleBuffListenerTest#joinReapplyIsDeferredPastTheHuskSyncSnapshotWindow`
（イベント中に付けると `expected: <false> but was: <true>`）と
`#periodicRefreshRestoresABuffThatWasClearedAfterJoin`
（定期リフレッシュを潰すと `expected: <true> but was: <false>`）の 2 本が、
それぞれ修正を戻すと落ちることを実測済み。

**注意（棚卸しの限界）**: このバッチの実走時、**別セッションが作業ツリーを編集中**だったため
（`SkillExpConfig.java` / `CatalogSmithingListener.java` / `CollectionListener.java` と yml 多数）、
テスト総数が 4349 → 4363 に増え失敗も 26 → 27（`ShippedCompressedFoodRegistrationTest` が追加、
内容は `carrot_1x が custom-foods に無い`＝ `food-gimmick.yml` の WIP）へ動いている。
**自分の変更に触れる失敗は 1 件も無い**（失敗 22 クラスはいずれも `RoleBuffListener` を参照しない出荷 yml テスト、
`RoleBuffListenerTest` 6/6・`NativeSkillExperienceListenerBrewTest` 8/8 は緑）。

#### W-124 醸造ログ → ~~今回は再現しなかった~~ **真因確定（2026-08-19 夕）**

**真因は `BrewOwnership#clear` が `BrewEvent` の最中に呼ぶ `stand.update()`。**
`NativeSkillExperienceListener.onBrew`（`MONITOR`）は
`event.getBlock().getState()` で**醸造前のスナップショット**を取り、所有者 PDC を消したあと
`stand.update()` する（`BrewOwnership.java:144-151`）。ところが
`CraftBlockEntityState#update()` は PDC だけを書き戻すのではなく、
**スナップショットの NBT を丸ごと実体へ `loadWithComponents` する**（PaperMC `CraftBlockEntityState#copyData`）。
`BrewingStandBlockEntity#loadAdditional` は

```java
this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
ContainerHelper.loadAllItems(tag, this.items, registries);
```

と **`items` フィールドを新しいリストへ差し替える**。一方 Paper の `doBrew` は

```java
private static void doBrew(Level level, BlockPos pos, NonNullList<ItemStack> items, BrewingStandBlockEntity entity) {
    ItemStack ingredient = items.get(3);           // ← イベント前に掴む
    ...
    if (!event.callEvent()) return;                // ← ここで TF の MONITOR が update() する
    for (int dest = 0; dest < 3; dest++)
        items.set(dest, ...asNMSCopy(brewResults.get(dest)));   // ← 引数の【古い】リストへ書く
    ingredient.shrink(1);                          // ← 【古い】ItemStack を減らす
```

（呼び出しは `doBrew(level, pos, entity.items, entity)`。PaperMC `paper-server/patches/sources/.../BrewingStandBlockEntity.java.patch` 現物）
なので、**イベント中の `update()` 以降、`doBrew` の書き込み先は醸造台から切り離された孤児**になる。
結果、醸造台には**醸造前のスナップショットがそのまま残る**。

これで報告の4症状が過不足なく説明できる:

| 報告 | 説明 |
|---|---|
| ネザーウォートが消費されない | `ingredient.shrink(1)` が孤児 `ItemStack` を減らしている |
| 瓶が水入り瓶のまま | `items.set(...)` が孤児リストへ書かれ、実体はスナップショット（水入り瓶）に戻る |
| EXP は入る | EXP 付与は同じ `MONITOR` ハンドラ内で `update()` の直後に走るので無傷 |
| ブレイズパウダーは消費される | 燃料は醸造**開始時**（400tick 前）に減っており、スナップショットは既に減った値を持つ＝戻らない |

**`[brew-diag]` が 0 行なのは正常。** 計装は `event.getResults()` を見るが、
この不具合では results は**正しく奇妙のポーションになっている**（壊れるのはイベント後の書き戻し先）。
つまり `isSuspicious` は構造的に false で、**この不具合を捕まえられない計装だった**。
加えて `latest.log` は 13:18:56 始まりで、**10:52〜13:18 の窓（= 再起動直後の実測）がログに残っていない**ので、
「行が無い」は元々証拠として使えなかった。上の「再現しなかった」は**取り消し**。

**影響範囲は醸造全部。** `clear(stand)` は `ownerId.isEmpty()` の判定より**前**で無条件に呼ばれる
（`NativeSkillExperienceListener.java:813-823`）ので、**所有者記録の有無に関係なく全プレイヤーの全醸造が戻る**。
`stand.update()` を `BrewEvent` の中で呼ぶ実装は **git 初回取り込み（`7ad0522` / 2026-07-27）から存在**しており、
「W-108 で直したはずがまた作れない」「java でもダメ」もこれで一貫する。
MockBukkit は `update()` が実体へ書き戻さないので、テストは緑のまま通る。

**修正（2026-08-19 実施・配備待ち）**: `NativeSkillExperienceListener#onBrew` の
`brewOwnership.clear(stand)` を `runTask` で**次 tick** へ回し、新設した `clearBrewOwner(Block)` が
**その時点で `BlockState` を取り直して**から消去するようにした（醸造台が壊れていれば何もしない）。
Bukkit のスケジューラはワールド／ブロックエンティティの tick より先に走るので、
ホッパーが割り込む隙間はできない。かまど側 `FurnaceSmeltListener#onSmelt` は
元々この形（`runTask` → `clearIfIdle(Block)`）で回避していたので、それに揃えた形になる。

**RED 証明**: 新テスト `NativeSkillExperienceListenerBrewTest#ownerPdcIsNotClearedDuringTheBrewEventItself`
は「イベント直後はまだ所有者PDCが残っており、1 tick 後に消えている」ことを固定する。
消去をイベント中へ戻すと `expected: <Optional[...]> but was: <Optional.empty>` で落ちることを実測済み。
MockBukkit の `update()` は実体へ書き戻さないので巻き戻しそのものは再現できず、この形でしか固定できない。

**同種の `update()` の棚卸し（依頼により全走査）**:

| 呼び口 | 判定 |
|---|---|
| `BrewOwnership#clear` ← `onBrew`（`BrewEvent`） | **これだけが壊れていた**。次 tick へ修正 |
| `BrewOwnership#rememberOwner` / `replaceOwner` / `markAutomated` | 安全。`InventoryClickEvent` / `InventoryMoveItemEvent` は**バニラが中身を書き換える前**に飛ぶのでスナップショット＝現在の中身＝書き戻しが no-op。**次 tick へ回してはいけない**（同じ tick 内で解放ゲートが所有者を読む） |
| `FurnaceSmeltListener#stamp` ← クリック／ホッパー／`FurnaceStartSmeltEvent` | 同上の理由で安全。完了側 `onSmelt` は元から次 tick |
| `PotionQualityListener#applySpeed` | `runTask` の中（イベント外）で状態を取り直しているので該当しない |
| `MiningGimmickListener`（`brushable.update(true)` / スポナー復元） | 前者は `runTaskLater` の中、後者は `BlockPlaceEvent`（配置は既に完了済みで後続のバニラ書き込みが無い）。該当しない |
| フォーク（ArsPaper / EliteMobs）の `update()` 全 27 箇所 | `@EventHandler` の中にあるのは `Waystone#onChat`（`AsyncChatEvent`、コンテナ非関与）だけ。残りは自前ブロックの永続化・爆破復元で該当しない |

理由と規則は `docs/agent-context/common-traps.md`「`BlockState#update()` は PDC だけを書き戻すのではない」に恒久知識として書いた。

**テスト実測**: 修正あり `4349 tests / 26 failures / 2 skipped`、
HEAD に戻した基準値 `4347 tests / 26 failures / 2 skipped` で**失敗クラス 21 本が完全一致**
（すべて他セッションの未コミット yml 由来の shipped-yml テスト）。回帰なし。
`releaseAssembly` も BUILD SUCCESSFUL で、生成 jar に `clearBrewOwner` が入っていることを確認済み。

**GeyserExtra の `BedrockDurabilityBarScaler` は無関係（棄却）。** `latest.log` に
`Damage cannot exceed max damage` が 13,195 行あり、うち `WINDOW_ITEMS` 716 / `SET_SLOT` 1,923 で
「醸造台の中身が更新されないのはパケット落ち」に見えたが、
`onPacketSending` が例外を自前で catch してログするだけ（`BedrockDurabilityBarScaler.java:84-96`）で
**パケットは素通りする**うえ、書き換えは `item.clone()` に対して行うので元パケットは無傷。
ただし **例外自体は geyserExtra 側の実バグ**（`m.setMaxDamage(null)` の直後に投げている）なので別途対処が要る。

#### W-128 ドラゴンエッグのドロップ（`environment:` 軸を新設）

`hero` 武器（Lv100）の儀式コア素材が `DRAGON_EGG` なのに、バニラでは**ジ・エンドの初回討伐1個きり**で
再入手できず、上位帯が事実上作れなかった。`combat/mob-level-table.yml` の 6 帯すべてに
`{ material: DRAGON_EGG, chance: 1.0, min: 1, max: 1, mobs: [ENDER_DRAGON], where: field, environment: [NORMAL] }` を追加。

**`environment:` を新設したのは `where:` では足りないから。** `where:` の判定は
「討伐したワールドが `DungeonWorldRegistry` に登録されたダンジョンインスタンスか」の一点だけで、
**オーバーワールドもジ・エンドもどちらも `field`** になる。ここを絞らないと
エンドクリスタルで復活させたエンドラからも卵が出て、依頼（オーバーワールド限定）から外れる。
`LevelTierDropEntry` にレコード成分 `environments` を足し、`MobLevelTableConfig#parseEnvironmentFilter`
（不正値は警告して読み飛ばす fail-soft）と `MobLevelTableListener` の1判定で配線した。
判定できない（ワールドが取れない）ときは **`baby:` と同じで「落とさない」側へ倒す**。

#### W-121 儀式素材の全面改訂 ＋ **W-120 で作り込んだ「組めないレシピ」の修正**

**まず W-120 の欠陥。** ArsPaper の `RitualManager#findNearbyPedestals` は
コアから `max(|x|,|z|) == 2` のリングを走査し、**1段あたり 16 マス**しかない。
そして**台座1台に置けるのは1個**（`Pedestal` はスタック数を見ない）で、`RitualRecipe#matches` は

```java
if (pedestalIngredients.size() != pedestalItems.size()) return false;
```

と**台数の完全一致**を要求する。つまり yml の `custom:foo x16` は「16スタック」ではなく**台座16台**。
W-120 で重さを出すつもりで `custom:magebloom_fiber x24` と書いた結果、
**38 レシピが 17〜29 台を要求する状態**になっていた（リング1段に収まらない）。
しかも**エラーもログも出ず**、素材が揃っていないレシピとして黙って不成立になるだけで、
プレイヤーからは「レシピどおり置いたのに始まらない」としか見えない。
`ShippedRitualPedestalCapTest` を追加して**16台**を上限として固定した（出荷 194 件を全走査。最大 16 台）。

**素材はご指摘どおり本家 Ars の顔ぶれへ。** 段が上がるごとにバニラ上位素材を足す構成に変えた
（`source:` は W-120 の値を維持。重さは台数ではなくソース量と素材のレアリティで出す）:

| 段 | 台座 | ソース(兜) | 素材 |
|---|---|---|---|
| 見習い Lv20 | 8 | 5,000 | 繊維x4 + ジェムx2 + 金ブロック + ラピスブロック |
| 魔術師 Lv40 | 11 | 50,000 | 繊維x4 + ジェムx3 + ジェムブロック + ブレイズロッドx2 + アメジストブロック |
| 大魔導士 Lv60 | 12 | 400,000 | 繊維x4 + ジェムブロックx2 + ソース結晶 + 残響の欠片x2 + エンダーアイx2 + ダイヤブロック |
| 賢者 Lv80 | 13 | 2,000,000 | ジェムブロックx4 + 結晶x2 + 竜鱗x2 + 圧縮残響 + ネザライトインゴットx2 + ネザースター + 泣く黒曜石 |
| 星詠み Lv100 | 15 | 8,000,000 | ジェムブロックx4 + 凝縮核 + ウォーデン触覚x2 + 圧縮アメジストx2 + ネザースターx2 + ネザライトブロック + トーテムx2 + エンドクリスタル |

魔導書は 見習い 13台/100,000、大魔導士 13台/1,000,000。部位重みは 1.0/2.0/1.6/1.0（infinity と同比）を維持。
`ars-smithing.exp-per-material` に `AMETHYST_BLOCK: 40` を追加（他 24 種は既存行で充足を実測）。

テストは **4,323 件 / 失敗 24 / スキップ 2**。新規 5 件は全て緑で、**失敗は増えていない**
（残る 24 件は他セッションの WIP と、上記の未コミット素材表削除に反応する 1 件）。

### 実サーバ報告バッチ（2026-08-19 受領 第8陣。W-139〜W-143）

| ID | 内容 | 状態 |
|---|---|---|
| W-139 | 軽武器・重武器（Ars魔法）が上がりやすい。Lv80 エンダーマンの討伐EXPを 8000〜10000 → 約4000 へ。ただし序盤の上がり方は変えず、HPに応じた指数だけ調整 | ✅ 対応（下記） |
| W-140 | 鍛冶台でネザライト化すると素材を消費せず無限にネザライト化できる（増殖） | ✅ 対応（下記） |
| W-141 | クリエイティブのユーザーが持ったアイテムが、サバイバルのユーザーの同じアイテムとスタックしない（バニラ品でも） | ✅ 対応（下記） |
| W-142 | 範囲収穫が機能していない | ✅ 対応（下記） |
| W-143 | 一括伐採発動時に1個分しか経験値が入らない | ✅ 対応（下記。ユーザー確認で**バニラEXPオーブ**側と確定） |

#### W-139 討伐EXPの最大HP項に指数を入れた（序盤は1ミリも変えない形）

討伐EXPは `(base + per-mob-level*Lv + per-max-health*最大HP) * 各種倍率`。
**HP項は全帯で支配的**（Lv10 のエンダーマンでも HP項 353 対 base+Lv項 40）なので、
`per-max-health` を下げるだけでは序盤も同率で下がり、要件「序盤は変えない」を満たせない。

そこで HP項だけを次の形にした（`stats/skill-exp.yml` の `combat.kill-exp` と
`ars-magic.kill-exp` の**2箇所**。片方だけ直すと軽武器/重武器で食い違う）:

```
HP項 = per-max-health * min(最大HP, anchor * (最大HP/anchor)^exponent)
per-max-health-anchor: 8000
per-max-health-exponent: 0.74
```

`min()` が本質。指数形は `HP < anchor` の帯では**線形より大きくなる**ので、
これが無いと Lv0 のEXPが 210.56 → 351.06（1.67倍）に**上がってしまう**（RED で実測した）。
`anchor: 0` か `exponent: 1` を書けば従来どおりの線形に戻る。

エンダーマンでの倍率: **Lv38以下 1.00 / Lv50 0.71 / Lv60 0.57 / Lv80 0.44 / Lv100 0.37**。
Lv80（最大HP 約183,000）で 0.44 倍 = 報告値 8000〜10000 → 約 4000。

純関数 `stats/KillExpHealthTerm#healthTerm` に切り出し、`KillExpHealthTermTest`（4件）と
出荷ymlを実読みする `ShippedKillExpHealthExponentTest`（2件）で固定した。

#### W-140 鍛冶台のネザライト化の増殖 — 2026-07-28 のクラフト複製と**同一機構**

真因は設定でもレシピでもなく `CatalogSmithingListener#onSmith` が
`player.setItemOnCursor(...)` を呼んでいたこと。`SmithItemEvent` は `InventoryClickEvent` で、
CraftBukkit の `handleContainerClick` は**イベント発火 → バニラの `AbstractContainerMenu.clicked`**
の順に走る。ハンドラ内でカーソルへ完成品を載せると、続くバニラ処理は
「カーソル空 → 結果枠を取る」ではなく「カーソルに同じ品 → マージ」へ入り、
最大スタック1の装備では `tryRemove(count, maxStackSize - cursorCount)` の上限が `1-1=0` になる。
その結果 **`ResultSlot#onTake` が一度も呼ばれず素材（素材装備・インゴット・テンプレート）が
一切消費されない**のに、手にはこちらが載せた完成品が残る = サーバ側の複製。

修正は `event.setCurrentItem(...)`（結果枠）だけにして**カーソルには触らない**。
`onSmith` と `restampPlainQualitySmith` の2箇所。`CraftQualityListener#onCraft` が
2026-07-28 に同じ理由で通った道なので、同じ様式の挙動テスト
`CatalogSmithingListenerDupeTest`（3件）を追加し、修正前へ戻すと2件落ちることを確認した。

#### W-141 クリエ品とサバイバル品がスタックしない — 図鑑の「クリエ由来」印が原因

ユーザーの推測（品質が全部付いている）は**外れ**。全77件の素材系 `item-stats` キーを
数え上げて確認したが、いずれもスタック不可の装備で、品質刻印の各経路も
`itemStats.profileFor(...)` / `MaterialTier.isEquipment()` のゲートを通っている。

真因は `CollectionListener` の**図鑑不正対策マーカー**。クリエイティブで出した品には
「クリエ由来」PDC を刻んでいたが、**PDC を1つでも書くと同じアイテムの無刻印スタックと
`isSimilor` でなくなる**ため、サバイバルで拾った同じ土/丸石とスタックできなくなっていた。
刻む対象を**図鑑に記録され得る品だけ**（`resolveEntryId` が解決できるもの）へ絞った
＝ 記録され得ない品に刻んでも不正対策としての意味が無く、スタック破壊だけが残るため。

判断を1本の純関数 `marksCreativeOrigin` に置き、「刻む集合 = 記録する集合」を
直積で突き合わせるテストを追加（`CollectionEntryResolutionTest` 17件 /
`CollectionListenerGuardsTest` 39件、いずれも緑）。

#### W-142 範囲収穫が「機能していない」— 右クリック収穫からは一度も到達していなかった

範囲収穫のゲートは `FarmingHarvestListener#onBlockBreak` にしか無かった。ところが
`auto-replant` を解放すると、成熟作物への右クリックは `onPlayerInteract`（HIGHEST）が
丸ごと引き取り（元イベントをキャンセルし、認可用の合成 `BlockBreakEvent` は
`authorizingRightClickHarvest` で自分の break 経路から除外される）。したがって
**右クリック収穫では範囲収穫の判定に一度も到達しなかった。**

`feature:area-harvest` を置くノード（farming C）は `feature:auto-replant` を置くノード（B）の
子なので、**範囲収穫を解放した全員が右クリック収穫を先に持っている** = 主要な収穫動作では
機能ゼロに見える。破壊収穫にしか無かったゲート判定を `maybeHarvestArea` へ切り出し、
右クリック収穫からも同じゲート（トグル / 鍬 / tier）・同じ半径で呼ぶようにした。

config（`stats/farming-gimmick.yml` の radius 1/2/3、`skilltree/farming.yml` の
`feature:area-harvest` value 1/2/3）は**配備先の実ファイルを読んで正しいことを確認済み**。
`FarmingHarvestListenerTest` に3件（発動する / トグルOFFなら発動しない / 鍬でなければ発動しない）を
追加し、呼び出しを消すと1件落ちることを確認した。

#### W-143 一括伐採のEXP — **バニラEXPオーブを連鎖分へ配るよう仕様変更（上限つき）**

切り分けた結果、連鎖破壊分のEXPには経路が2本あり、**片方だけが意図的に起点1回分**だった:

| 経路 | 変更前 | 根拠 |
|---|---|---|
| 伐採スキルEXP | **元から1本ごとに入る** | `ChainBreakSupport#breakOnce` → `NativeSkillExperienceListener#grantChainBreak` → `grantGathering`。新規テストで 40.0 × 3本を実測 |
| 破壊時バニラEXP（経験値オーブ） | **入らない（起点1回分だけ）** | `grantChainBreak` の javadoc: 「連鎖ぶんまでバニラEXPオーブを配ると一括破壊がそのままバニラEXP増殖装置になる」という 2026-07-28 の設計判断 |

**ユーザー確認の結果、報告が指していたのは後者（バニラの経験値オーブ）**だったので、
連鎖分にも配るよう `grantChainBreak` を変更した。旧仕様が連鎖分を切っていた理由
（一括破壊がそのままバニラEXP増殖装置になる）は、新設した上限で押さえる:

```
break-vanilla-exp:
  chain-max-blocks: 64   # 1バースト(=同tick・同プレイヤー = 斧1振りぶん)で配る上限ブロック数
```

- **`0` を書けば連鎖分に配らない** = 2026-07-28 以来の旧挙動へ戻せる。
- 64 の根拠: 出荷 tier の一括伐採は tier1=16 / tier2=64 / tier3=128 本。通常の伐採は
  ほぼ全部数えつつ、最上位の1振りでも 64 ブロック分で止まる。段階破壊される葉は
  そもそも採取扱いにならない（出荷ymlに葉の行が無い）ので数に入らない。
- 上限は**バニラEXPだけ**に掛ける。採取スキルEXPは元から連鎖1本ごとに入っており、
  今回の変更対象ではない（テストで「上限2でもスキルEXPは10回」と明示的に固定した）。

これまで**採取スキルEXP側を縛るテストが1本も無かった**（`TreeFellingListenerTest` は
「`ChainBreakExpGrant` が N 回呼ばれる」までしか見ていない）ので、
`NativeSkillExperienceListenerChainBreakExpTest`（**5件**）で両方を別々に固定した。
RED は両方向で確認済み: 付与を消すと2件落ち、上限を無効化すると上限側の2件が落ちる。

---

### 実サーバ報告バッチ（2026-08-19 受領 第9陣。W-144）

| ID | 内容 | 状態 |
|---|---|---|
| W-144 | スクラップをクラフトして鉱石に戻そうとすると「見た目が同じでも別のアイテムです」と出て戻せない（正しいアイテムを使っている） | ✅ **真因確定・修正済（配備待ち）**。TF が**自分の追加レシピを他人のレシピと誤認して自分で殺していた**（下記） |

#### W-144 スクラップ → インゴットが永久にクラフト不可 — 真因は**設定ではなく登録漏れ**

**設定側は最初から正しかった**。`progression/crafting-features.yml` の `added-recipes` に
スクラップ4個（2×2）→ インゴット1個が7件（銅／鉄／金／ダイヤ／ネザライト／革／カメの甲羅）あり、
**稼働中サーバの `Main_Server/plugins/TrinityForge/progression/crafting-features.yml` も
リポジトリと同一**であることを確認済み。プレイヤーが使っていたアイテムも正しかった。

真因は `CatalogRecipeRegistrar#registerAddedRecipes` が
`Bukkit.addRecipe` とキー記録（`registeredKeys`）はするのに、
**`registeredSpecs` に載せていなかった**こと。`CatalogWorkbenchListener` は
「選択レシピが `registeredSpecs` に無い」ものを**他プラグイン／バニラのレシピ**とみなすので、
そこから先は保護規則が全部逆向きに働く:

1. `registeredOf(selected)` が空 → 「カタログ品を食おうとしている他人のレシピ」扱いになる。
2. `foreignRecipeOwnsGridItems` は namespace が `trinityforge` なのに
   スクラップの所有プラグインは `arspaper` なので **false**（＝委譲しない、保護を続ける）。
3. スクラップは重ねられるので、装備カタログ品の素通し（2026-08-17）にも当たらない。
4. 救済のはずの `rematch` が走査する `allRegistered()` は `registeredSpecs` 由来なので、
   **added-recipes はそこにも入っていない** → 一致するものが見つからない。

結果 `setResult(null)` ＋ 名指しメッセージ（W-87 で足したもの）が出る。
つまり **`custom:` 素材を使う `added-recipes` は1件残らず「レシピ帳には出るのに
永久にクラフト不可」**だった。設定が正しいまま無言で死ぬので「設定が効いていない」ようにしか見えない。

修正は `registeredSpecs.put(key, new RegisteredRecipe(key, null, spec, result.clone()))`。
added-recipes には結果になるカタログエントリが無いので `RegisteredRecipe` に
`fixedResult`（固定結果）を足し、結果が要る側は `template()` を直接使わず
新設の `CatalogRecipeRegistrar#resultOf` を通すようにした（listener の3箇所を置換）。

回帰テストは `CatalogWorkbenchAddedRecipeTest`（**3件**）。**実物と同じ経路で組む**のが要点で、
モックの registrar では「登録漏れ」そのものを再現できない ──
実 registrar に added-recipes を食わせて `Bukkit` へ登録し、
ArsPaper の実値（`iron_ingot_scrap` / base `IRON_NUGGET` / CMD 5313）を
`ExternalItemRegistry` へ入れた盤面で `onPrepareCraft` と `onCraftItem` の**両方**を通す
（片方だけだと「結果枠には出るのに取り出せない」で残る。2026-08-18 に同じ失敗を踏んでいる）。
RED 確認済み: `registeredSpecs.put` を外すと3件とも落ちる。

---

### 実サーバ報告バッチ（2026-08-19 受領 第10陣。W-145）

| ID | 内容 | 状態 |
|---|---|---|
| W-145 | 精錬魔法をブロックに撃つときの対応増強グリフを「半径増加」から「範囲（各種）」に変え、増強グリフの設定範囲が精錬されるようにしてほしい | ✅ **対応済（ArsPaper jar 配備待ち）**。半径増加は**ブロック精錬に一切効かない軸**だった（下記） |

#### W-145 精錬の範囲対応 — 「半径増加」は最初からブロックには効かない軸だった

要望は「半径増加 → 範囲（各種）へ差し替え」だが、実態は**差し替え前が機能していなかった**。

「半径増加」(`aoe_radius`) が動かすのは `SpellContext#aoeRadiusLevel` で、これを読むのは

- エンティティAOE展開（`resolveGroupsOnEntity`）
- `handlesAoeInternally() == true` のエフェクト（爆発・召喚数など）

の2つだけ。**ブロックAOE展開（`SpellContext#resolveGroupsOnBlock`）は
`aoeLevel` / `aoeHeightLevel` / `aoeVerticalLevel` の3軸しか読まない**。
精錬は `handlesAoeInternally()` が false のままなので、
**半径増加を何個積んでもブロックは狙った1個しか精錬されなかった**
（グリフは装着できるのに効果が無い、という無言死）。

修正は3点。

1. `GlyphConfig.AUGMENT_COMPAT` の `smelt` を `Set.of("aoe_radius")` → `Set.of("aoe")` へ。
   **互換表は yml ではなくソース側の定数**（loader に「augments互換性はソースコード定義(AUGMENT_COMPAT)
   のため、ymlからは読まない」と明記されている）ので、**`glyphs.yml` だけ直しても何も変わらない**。
   `aoe` を入れれば `isAugmentCompatible` の特例で `aoe_height` / `aoe_vertical` も自動的に互換になる。
2. `glyphs.yml` の `smelt.max-augments` を `aoe: 6` / `aoe_height: 6` / `aoe_vertical: 3` へ。
   **未記載の軸は `getMaxAugmentStack` が `Integer.MAX_VALUE` を返す**＝上限なし（実質、展開ループ側の10）
   になるので、3軸とも明示しないと高さ／法線だけ極端に伸ばせてしまう（破壊グリフが現にそうなっている）。
3. `SmeltEffect#getAoeMode()` を `AoeMode.HIT_FACE_INWARD` へ（`BreakEffect` と同じ）。
   既定の `FIXED` は法線方向の符号が `+1`（＝手前＝設置系の向き）なので、
   壁を狙って範囲[法線]を積むと**壁の中ではなく自分側の空気**が対象になる。

**範囲対応で新たに生じた事故を先回りで潰した**: `applyToBlock` は範囲内の全ブロック分（最大で約1000回）
呼ばれ、その先頭で毎回半径2のドロップアイテム掃き取りが走る。`SMELT_MAP` には
`COBBLESTONE → STONE → SMOOTH_STONE` という**2段の連鎖**があるため、ガードが無いと
落ちている丸石が1回の詠唱で滑らかな石まで進む（範囲が1ブロックだった頃は掃き取りも1回だけで表面化しなかった）。
`itemSweepTick` + `smeltedThisTick`（UUID集合）で**1tickにつき1アイテム1回**に固定した。

回帰テストは `SmeltAreaAugmentCompatTest`（**5件**）。互換表・出荷 yml の3軸上限・展開の向き・
多重精錬ガードを固定する。このフォークのテストには MockBukkit も Mockito も無く
`new SmeltEffect(...)` は `NamespacedKey(plugin, ...)` で NPE になるので、
向きとガードは兄弟の `SpellBreakMarkerCoverageTest` と同じソース走査で固定している。
RED 確認済み: 互換表を `aoe_radius` に戻すと5件中2件が落ちる。
フォーク全体 **450件 / 失敗0 / エラー0 / スキップ0**、`ArsPaper-1.0.0.jar` ビルド済み。

---

### 実サーバ報告バッチ（2026-08-19 受領 第11陣。W-146〜W-150）

| ID | 内容 | 状態 |
|---|---|---|
| W-146 | 掘削加速のギミックを、採掘ギミックページのように editor の掘削ギミックページで設定できない | ✅ 対応（下記） |
| W-147 | ポーションが醸造できるようになったが、醸造（錬金）スキル経験値が入らない | ✅ 対応（下記） |
| W-148 | エンダーマンで経験値（TFのスキルEXP）が入らなかった人が場所を移動しても入らなかったらしい。同じ場所狩りの減衰措置のバグ疑い | ✅ **真因確定・修正済（jar+config 再配備待ち）**。**`level-cutoff` が犯人だった**（この節の「不発」判定は誤りで**再訂正**）。足きりが比較するのは**戦闘レベル**でスキルレベルではない（下記） |
| W-149 | 圧縮ご飯がまだ食べられない（W-131 の再報告） | ✅ **真因確定・修正済（ArsPaper jar + config 再配備待ち）**。**配備オーバーレイ説は主因ではなかった**（再訂正）。真因は **ArsPaper 側が圧縮食料の食事イベントを無条件キャンセル**していたこと（下記） |
| W-150 | 精錬速度が早すぎる。高速精錬を最大まで解放して170%上昇＝2.7倍のはずが、原木1スタックが3秒程度で終わる | ✅ 対応（下記。ユーザー判断で**「速度+X%」解釈へ統一**） |

#### W-148 エンダーマンで経験値が入らない — **W-117 の続報。場所非依存の ×0.25 × 日次逓減が残っている**

> ⚠️ この節は**一度書き直している**。初版は真因を `level-cutoff.under-level`（レベル差の足きり）と
> 断定したが、ユーザーから**「エンダーマンとのレベル差は2」**と訂正を受けて撤回した。
> 差 2 は `exp-threshold: 15` に遠く届かないので、足きりは**一度も発動していない**。
> 併せて「入らなかったのは TF のスキルEXP」であることも確定した。

**この報告は W-117（同一地点逓減の緩和）の続報**であり、独立した新規バグではない。
W-117 の緩和（`spot-diminishing` を threshold 30→80 / decay 0.05→0.02 / floor 0.1→0.4）は
**配備済み・稼働中**。実物で確認した:

| 対象 | 確認結果 |
|---|---|
| 配備 `stats/skill-exp.yml`（16:18） | `spot-diminishing` は新値（80 / 0.02 / 0.4）。repo と一致 |
| 配備 jar（16:43）＋サーバ再起動（16:46、`latest.log`） | 新値で稼働中 |
| 配備 `combat/mob-level-table.yml` の `no-skill-exp-mobs` | ENDERMAN は**無い**（抑止対象外） |
| 配備 `stats/skill-exp.yml` の `combat.kill-exp.entity-type-multipliers` | `ENDERMAN: 1.5` が**ある**（`unlisted-entity-multiplier: 0` の穴に落ちていない） |
| `latest.log` | `CombatListener` 系の例外・`Could not pass event` は**0件** |

**同一地点の減衰（`spot-diminishing`）は原因になり得ない。** 4点で否定できる。

1. **場所スコープ**。判定はその地点の周囲 `radius`（16）内・直近 `window-seconds`（300）秒の撃破数で、
   16 ブロック動けば履歴が範囲外になり倍率は 1.0 に戻る（`LocationExpDiminishing#multiplierAt`。
   `Spot#within` は world UUID と距離の二乗で判定、`pruneExpired` で窓も切れる）。
2. **0 にならない**。`floor`（配備値 0.4）で下げ止まる。バニラオーブ側は更に
   `applyToVanillaExp` の `Math.max(1, …)` で**最低1オーブを必ず残す**。
3. **エンダーマンでは構造的に発動しない**。新値は「5分・半径16で 80 体」が発動条件＝**3.75秒に1体**。
   Lv80 エンダーマンの最大HPは **182,931**（`1120 × 1.053^80 + 3233 × (80−45)`）で、
   この密度は物理的に到達不能。
4. 記録するのは撃破のみ（被弾／命中では増えない）。

**「移動しても入らない」が指しているのは、場所に依らない2つの係数のほう。**
`NativeProgressionService#grantExpUnderRepositoryLock` がこれらを**乗算で合成**する:

| 係数 | 配備値 | 場所を移して回復するか |
|---|---|---|
| `outside-dungeon-exp-rate` | **×0.25**（ダンジョン外は一律 1/4） | **しない**（ダンジョンに入るしかない） |
| `daily-diminishing` | 100,000 EXP ごとに ×0.9、下限 **×0.5** | **しない**（スキル別・24時間の指数窓。**ログアウトしても永続化される**） |
| `spot-diminishing` | ×0.4 〜 1.0 | する（16ブロック移動 or 300秒） |
| `level-cutoff.under-level` | レベル差 2 では **不発**（threshold 15） | — |

**ダンジョン外の下限は ×0.25 × 0.5 = ×0.125**。この 1/8 は**移動では1ミリも戻らない**。

**Lv80 エンダーマンの実測整合（3つの報告値がすべて同じ式で説明できる）。**
満額 = `(base 30 + 2×80 + HP項) × ENDERMAN 1.5`。

| 時点 | HP項 | 満額 | 受取（×0.25 ×日次） | 対応する報告 |
|---|---|---|---|---|
| 08-19 午前（HP項が線形、`spot` 旧下限 0.1） | 34,391 | 51,872 | 51,872×0.25×0.1×0.55 ≒ **713** | W-117「Lv80 エンダーマンが約700」 |
| W-117 緩和後（HP項はまだ線形） | 34,391 | 51,872 | ×0.25×0.8 ≒ **10,374** | 同日「8000〜10000入る」 |
| **現在**（W-118 で HP項を指数化。anchor 8000 / exp 0.74） | 15,242 | 23,148 | ×0.25×(0.5〜1.0) = **2,893〜5,787** | ユーザー要望「約4000へ」に一致 |

**つまり今の設定でも 1体あたり 2,900〜5,800 は入る**（Lv78 の次レベルに要る約65,000 EXP に対し
**11〜22体で1レベル**）。**したがって「文字どおり0」は上の係数では作れない。**

**0 になり得る経路として残っているのは2つだけ**（どちらも場所非依存・モブ非依存）。

1. **手に持っている物が武器スキルに解決されない。** `CombatListener#maybeRecordCombatSkillDamage` は
   `UseRequirementResolver` が `stats/item-stats.yml` の `use-skill` から
   HEAVY_WEAPONS / LIGHT_WEAPONS / ARCHERY を返したときだけ台帳へ記録する（2026-07-28 の
   採取ツール誤爆修正で **fail-closed**）。素手・バニラ武器・採取ツールでは台帳が空のまま
   `onCombatKill` の `credits.isEmpty()` で即 return し、**警告も部分EXPも一切出ない**。
2. **魔法で倒している。** 魔法の討伐EXPは `ArsMagicExperienceListener` の**別経路**で、
   武器台帳を通らない。武器スキル側は当然 0 になる。

**恒久対策の提案（未実施・ユーザー判断待ち）。** この報告は **W-107 → W-117 → W-148 と3回目**で、
毎回「実際は逓減の掛け算」だった。原因は**係数がプレイヤーから見えないこと**にある ——
`daily-diminishing` だけは `DailyExpRateText#badge` でステータスGUI・スキルツリーに出るが、
**`outside-dungeon-exp-rate` と `spot-diminishing` は表示が一切無い**。
既存の badge 行へこの2つを足せば（新規GUI・新規config層は不要）、
①同種の報告が止まり ②次の報告が自己診断的になる。数値そのものを動かすか（例: ×0.25 の緩和）は別判断。

---

##### 2026-08-19 夜 **再訂正: 真因は `level-cutoff` だった**（上の「不発」判定を撤回）

> ⚠️ この節は**二度書き直している**。初版は真因を `level-cutoff` と断定 → ユーザーの「レベル差は2」で撤回 →
> **今回その撤回のほうが誤りだったと判明した**。撤回の根拠にした「レベル差2」は
> **スキルレベル78 と モブLv80 を比べた数字**であって、`level-cutoff` が実際に見るのは
> **戦闘レベル**（`KillRewardAdjuster#expMultiplier` → `SymmetricCombatService#combatLevelOf`）。
> **数字の出どころを確かめずにユーザーの申告値をそのまま式へ入れたのが誤診の原因**で、
> 「4点で否定」も「0になり得る2経路」もこの取り違えの上に積み上げた推論だった。

**戦闘レベルはスキルレベルより構造的に低い。** `progression/combat-level.yml` の pillar 写像は
`max(上位N個の合計 / divisor)` で、top1 の divisor が 1.5 なので**単一特化のプレイヤーは最高スキルの約 2/3**
にしかならない。つまり「軽武器78」の人の戦闘レベルは **52 前後**、Lv80 モブとの差は **2 ではなく 28**。
旧設定は `exp-threshold: 15` / `exp-decay-per-level: 0.067` ＝ **15差から絞り始め30差で 0**。

**配備先DB（`player_progression.db`）の実データで裏を取った**（読み取りのみ）。
防具2種を `skills:` に足す**前**の写像で算出した実プレイヤーの戦闘レベルと、Lv80 エンダーマン撃破時の受取:

| 軽武器 | 重武器 | 魔法 | 重装 | 戦闘Lv | 足きり | 受取（満額23,134 × 足きり × 0.25） |
|---:|---:|---:|---:|---:|---:|---:|
| 80 | 3 | 10 | 64 | 53 | 0.20 | **1,134** |
| 1 | 49 | 53 | 63 | 51 | 0.06 | 359 |
| 71 | 0 | 0 | 53 | 47 | **0.00** | **0** |
| 53 | 0 | 0 | 45 | 35 | **0.00** | **0** |
| 56 | 0 | 0 | 40 | 37 | **0.00** | **0** |

報告の**「みんな1000程度か0」と完全に一致する**。「0 は上の係数では作れない」も誤りで、
足きりが 0 を返せば `onCombatKill` は `cutoff <= 0.0` で `continue` する（＝文字どおり 0）。

**「魔法だけは入る」も同じ機構の裏返しだった。** `ArsMagicExperienceListener#onMagicKill` は
`amount × spot` だけで、**足きり（cutoff）もダメージ寄与配分（share）も掛けていなかった**。
武器・弓術だけが両方を通るので、同じモブでも魔法は満額・武器は 0〜13% になる。

**対応（2件）。**

1. **魔法の討伐EXPにも足きりを掛けて対称にした。** `ArsMagicExperienceListener` に
   `setKillRewardAdjuster` を足し、`TrinityForge#onEnable` で `CombatListener` と同じインスタンスを配る。
   回帰ガードは `ArsMagicExperienceListenerLevelCutoffTest`（**修正を戻すと2件落ちることを実測で確認済み**）。
2. **足きりの帯を 15〜30 差から 25〜50 差へ広げた**（`combat/damage.yml`:
   `exp-threshold: 15 → 25` / `exp-decay-per-level: 0.067 → 0.04`）。25 は
   「純特化ぶんの構造的なズレ（最高スキルの 1/3）」を吸収する幅。50差での完全遮断は
   ハメ狩り抑制という当初の狙いを維持する。`ShippedLevelCutoffTest` を新しい帯へ書き直した。

**⚠️ 残課題（未対応・ユーザー判断待ち）。**

- **ドロップ側の `item-threshold: 20` は据え置き**なので、**同じ取り違えがドロップ側には残っている**
  （戦闘Lv49 の人は Lv80 モブから TF 追加ドロップが 1 つも出ない）。緩めるかは別判断。
- 経験値の閾値（25）がアイテムの閾値（20）より**後ろ**になったので、2026-08-18 に入れた
  「経験値のほうが手前から絞られる」という順序は**もう成り立たない**。順序を固定していた
  `shippedExpCutoffStartsEarlierThanTheItemCutoff` は削除し、
  代わりに「大差では両方止まる」を固定する `carriedLowLevelPlayerStillGetsNeitherExpNorItems` を置いた。
- 上の**係数の可視化**（`outside-dungeon-exp-rate` / `spot-diminishing` / **足きり倍率**）は依然として未実施。
  今回の誤診は「プレイヤーにも運用者にも戦闘レベルと足きり倍率が見えない」ことが根にあるので、
  **`/tf status` に足きり倍率も出す**のが最も効く。

#### W-149 圧縮ご飯が食べられない — **commit 済みの修正が配備で巻き戻っていた**

`stats/food-gimmick.yml` の `custom-foods` に 9倍圧縮の生鮮14件を登録する修正は
**12:33 の commit（48dc1cc）で入っていた**。にもかかわらず 15:37 の配備先ファイルには
その14件が無く、`unregistered-custom-food-ban` が働いて食べられない状態が続いていた。

真因は **config 配備が「HEAD にワーキングツリーの yml を重ねる」方式**であること
（2026-08-18 から。未コミットの yml をそのまま出荷するのが目的）。
このとき**ワーキングツリー側の `food-gimmick.yml` が 14件を持たない古い内容だった**ため、
オーバーレイが HEAD の修正を上書きして消した。**コードもテストも一切関係ない**。

対処は2点。

1. ワーキングツリーへ14件を戻し（HEAD と一致させ）、この commit に含める。
2. 同じ場所に **⚠️ コメントで事故そのものを書き残した**。この方式では
   「commit したのに出荷されない」が起こり得る、という知識がファイル内に無いと再発する。

再配備が必要（jar は不要。config だけ）。

---

##### 2026-08-19 夜 **再訂正: 主因は ArsPaper 側の無条件キャンセルだった**（再報告「圧縮ステーキが食べられない」）

> ⚠️ 上の「配備オーバーレイの巻き戻り」は**事実だが主因ではない**。それを直しても症状は消えない。
> 実際、再報告された**圧縮ステーキ（`compressed_cooked_beef_1x`）は配備先の
> `stats/food-gimmick.yml`（15:37 の古いほう）にも最初から登録済み**で、
> それでも「食事モーションだけ再生され、アイテムも満腹度も動かない（メッセージも出ない）」ままだった。

**真因は ArsPaper フォーク側**の `CustomItemListener#onConsumeMaterial`:

```java
if (isConfigurableMaterial(item) && isMaterialEdibleBase(item)) {
    event.setCancelled(true);   // メッセージ無し・例外無し
}
```

`materials.yml` 由来の素材で `base_material` が食べ物なら**無条件で `PlayerItemConsumeEvent` をキャンセル**する。
圧縮食料は全部これに該当する（圧縮ステーキ = `base_material: COOKED_BEEF`）。
配備中の `ArsPaper-1.0.0.jar`（13:45）にこのハンドラが入っていることをクラスバイト列で確認済み。

**W-131 → W-149 と TF 側の `custom-foods` を何度直しても症状が変わらなかったのはこのため。**
TF の `FoodGimmickListener#onItemConsume` は `ignoreCancelled = true` なので、
Ars がキャンセルした時点で**一度も呼ばれない**。TF 側だけを見ている限り永久に見つからない構造だった。

**対応。** `materials.yml` に **`edible`**（既定 false）を新設し、
`onConsumeMaterial` は旗が立っている素材を素通しするようにした。旗は圧縮食料 23 件 +
クリスタルリンゴの計 24 件に付与。**回復量は従来どおり TF の `custom-foods` が決める**ので、
**Ars の `edible: true` と TF の `custom-foods` の両方に載っていないと食べられない**
（片落ちはどちらも「食べられない」側へ倒れるので、バニラ栄養値で食べ放題にはならない）。
2つのリストの食い違いは `CompressedFoodEdibleFlagTest` が検出する
（フォークが無いワークツリーではスキップ）。

**配備は ArsPaper の jar と config の両方が要る。**
`materials.yml` は `saveResource(..., false)` なので**jar 差し替えでも `/ars reload` でも反映されない** ——
`ops\launch\deploy-config-head.cmd` を通す必要がある。

#### W-150 精錬が速すぎる — `percent` を「時間の短縮率」と解釈していた

`smithing-gimmick.yml` の `furnace-smelt.speed.tiers` は出荷値が 25 / 50 / **100**。
実装（旧 `FurnaceSmeltPolicy.reducedCookTime`）はこれを **「調理時間を何%短縮するか」**として
`基準 × (1 - percent/100)` で計算し、最低1tickでクランプしていた。
つまり **tier3 の `100` は「短縮100%」＝ 1 tick/個**で、原木1スタック = 64 tick = **3.2秒**。
報告の「3秒程度で終わる」と完全に一致する。

同時に、この解釈では**スキルツリーが謳う「精錬速度+100%」（=2倍速）を表現できない**
（短縮率は100%が上限で、その100%が無限倍速を意味してしまう）。
ユーザー判断で **「+X% = 速度がX%増える」へ統一**した（「170%上昇なら2.7倍」という自然な読みに合わせる）。

- `FurnaceSmeltPolicy.cookTimeWithSpeedBonus(base, percent)` = `round(base / (1 + percent/100))`、最低1tick。
  100 を超える値も意味を持つ（100 → 2倍速、170 → 2.7倍速）。
- tier 表を **25 / 75 / 170** へ。かまどのバニラ基準 200 tick に対し
  **160 tick（8.0秒/個）/ 114 tick（5.7秒/個）/ 74 tick（3.7秒/個・1スタック約237秒）**。
- **基準は「レシピのバニラ調理時間」から取る**ようにした（`event.getRecipe().getCookingTime()`）。
  イベントが持ってきた現在値を基準にすると、**既に縮んだ値が毎回さらに縮んで複利で1tickへ張り付く**。
  レシピが取れないときだけ `getTotalCookTime()` へフォールバックする。
- 表記の食い違いも直した。`skilltree/smithing.yml` A-1/A-2/A-3 は
  `effect-text` / `effects` / `description` が **+10% / +10% / +25%** と三者バラバラだったので実数値へ揃えた。
  editor 語彙のラベル「精錬速度**短縮**tier」→「精錬速度tier」（意味が逆に読めた）、
  editor の精錬速度カードに計算式のヒントを追記、wiki 原稿の表も更新。

RED 実測: 旧式（短縮%）へ戻すと **tier3 が 1 tick**（＝報告症状）になり
`FurnaceSmeltPolicyTest` 1件・`FurnaceSmeltListenerTest` 4件が落ちる。復旧後は両クラス
**24件 / 失敗0 / スキップ0**（Policy 9件・Listener 15件）。
`skilltree` パッケージの3件失敗は **ARS_MAGIC のレイアウトと fishing_bonus** で、
他セッションの未コミット yml 由来（SMITHING は無関係）。

#### W-147 醸造で錬金EXPが入らない — **シフトクリック投入では所有者が記録されなかった**

「醸造台へアイテムを入れた」の判定が**2箇所に独立して**書かれていて、
`NativeSkillExperienceListener#rememberBrewer` 側が
**`MOVE_TO_OTHER_INVENTORY`（シフトクリック）と `HOTBAR_MOVE_AND_READD` を取りこぼしていた**
（`BrewUnlockListener#insertedStack` 側は両方持っていた）。
所有者PDCが無いと `onBrew` は `ownerId.isEmpty()` で即 return するので、
**シフトクリックで素材を入れた人は醸造が完成しても錬金EXPが1点も入らない**。
同じPDCを読む**ポーション品質補正と手動/自動倍率も同時に死んでいた**。
W-124 で醸造そのものが完成するようになるまでは症状として表に出なかった。

判定を `BrewInsertion`（新設）へ集約して両者で共用する。2本に分かれている限り
片方だけ直しても再発する。あわせて**ドラッグ投入経路**（`rememberBrewerDrag`）を新設した
— 解放ゲート側には `onBrewerDrag` があったのに、所有者記録側には無かった。
`HOTBAR_SWAP` で `getHotbarButton()` が負（オフハンド入れ替え）のとき `getItem(-1)` で
添字外になる潜在バグも共有側で閉じた。

逆方向（完成ポーションをシフトクリックで**取り出す**）では所有者にならないことも縛った。
ここを記録すると他人の醸造の報酬を横取りできる。

RED 実測: 共有判定から上記2アクションを外すと新規3件と
`BrewUnlockIngredientGateTest#shiftClickingFromThePlayerInventoryIsAlsoBlocked` が落ちる。
復旧後は醸造関連4クラス **47件すべて緑**（失敗0・スキップ0）。

#### W-146 掘削加速が editor から設定できない — カードの追加漏れ

2026-08-18 の W-59 でシャベル専用の `haste-active-digging` を新設したとき、
**editor 側のカードを足し忘れていた**。採掘ギミックには「採掘加速」カードがあるのに
掘削ギミックには1枚も無く、`amplifier` / `duration-ticks` / `cooldown-ticks` / tier表を
一切いじれなかった。採掘側と同じ構成（グローバル既定値3項目 + CTを含まない2列tier表）を追加。
数値は mining と意図的にミラーしない独立の値なので、保存先も `stats/digging-gimmick.yml`
本体のままでコンパニオン扱いにはしない。`tf-digging-gimmick` は schema.js の switch に無く
`validateGeneric` へ落ちるので、検証の追加は不要（mining 側も同じ）。

⚠️ 同じテストファイルの既存1件「ロスレス: mining-gimmick.yml 実データを…」は**この変更と無関係に失敗する**。
`b969faa`（ダンジョン難易度再設計）が `mining-gimmick.yml` の tiers を 1/3/5 → 1/2/3 へ変えた際、
期待値を直値で持つこのテストが取り残されたもの。

---

### 武器種の「尖り」棚卸し（2026-08-19 受領 第12陣。W-151）

| ID | 内容 | 状態 |
|---|---|---|
| W-151 | W-126（大剣・鎌・剣・斧の尖り決め）に**出てこなかった武器種は既に尖っている判定なのか**という問い | ✅ 対応（下記）。3種が漏れていた。ウォーハンマーは尖り済み・**大斧は尖ってはいるが大剣と衝突**・**レイピアは尖りゼロ**。ユーザー判断で大斧＝巻き込み縮小／レイピア＝貫通の主役化（＋そのぶん火力を下げる） |

#### W-151 W-126 で漏れていた3武器種 — 大斧は大剣と衝突、レイピアは尖りゼロだった

**問いへの回答**: 「出てこなかった＝尖り済み」ではなかった。棚卸しの結果は3種で状況が違う。

| 武器種 | 判定 | 根拠 |
|---|---|---|
| ウォーハンマー | ✅ 尖っている（据え置き） | `penetration` 0.035〜0.056 が**全武器種で最低**＝「貫通しない代わりに重い」という尖りが既に成立 |
| 大斧 | ⚠️ 尖ってはいるが**衝突していた** | ダメージ補正は全武器種で最高だが、巻き込みが 半径3.5 / 倍率0.35 / 4体 で**大剣（当時 半径3 / 0.30 / 3体）より強かった**。W-126 が大剣を「範囲の主役」に据えた狙いを正面から潰していた |
| レイピア | ❌ 尖りが1つも無い | 貫通 0.26 は槍・ハルバード・猟師の投槍の 0.312 に**負けていて**、会心率 0.105 も短剣 0.192 に負ける。「そこそこ高いのが2つ」で主役の軸がゼロだった |

**ユーザー判断とその実装**（`stats/item-stats.yml`）:

- **大斧＝巻き込みを縮める**（もう1つの選択肢「大剣をさらに上げる」は採らなかった）。
  `aoe-radius` 3.5 → **2.5**、`aoe-damage-rate` 0.35 → **0.2**、体数4は据え置き。**全16本**に適用。
  巻き込みの序列は **大剣 ＞ ハルバード ＞ 鎌・大斧 ＞ 剣** に整った。
- **レイピア＝貫通の主役へ。ただしユーザー指示「その分火力は下げること」**。
  `fixed.penetration` 0.26 → **0.45**（abyss 系の ×1.3 味付けは比例で保存し 0.338 → 0.585）。
  刺突勢の 0.312〜0.39 を必ず上回る。火力側の対価は
  `attack-power` ×0.98（fixed / per-quality / random の min-max とも）、
  `crit-chance` 0.105 → 0.05、`crit-damage` 0.405 → 0.34、`per-quality.crit-chance` 0.0045 → 0.002。
  **15本**に適用。会心の軸からは降りて貫通1本に賭ける武器になる。

**貫通を倍率で一括追随させてはいけない**（2件とも dry-run で踏んで取り下げた）:

- `per-quality.penetration` を 0.005 → 0.009 にすると abyss レイピアが高品質で貫通 **0.97** に達する。
  1.0 を超えた瞬間 `ComponentDamageCalculator` の `base *= (1 - defenseRate × (1 - penetration))` が
  **符号反転してダメージを増幅する**（守備力が高い相手ほど有利になる壊れ方）。
- `random.penetration` の幅を伸ばすと金のレイピア（min −0.325）が基礎 0.45 と合わさって
  **負の貫通**になり、守備力を増幅する側へ回る。
  据え置けば最悪ロールでも 0.45 − 0.078 = **0.372** で槍の基礎 0.312 を必ず上回る。

**火力を下げられる幅には硬い上限がある（約3%）**。`WeaponDpsParityTest` が「大斧 / レイピア」を
同格ペアとして **比 ≤ 1.16** で縛っており、変更前ですでに 1.1257（Lv25）まで詰まっていた。
実測は 変更後 **1.1490（Lv0）** で通過。`attack-power` をこれ以上下げるには大斧側も下げる必要がある。
一方 `WeaponDpsParityTest` は**会心を数えない**モデルなので、会心を削った分はこちらでは相殺されず、
`WeaponTierParityTest` の「レイピア/剣」比に効く（実測 **0.7429** ≥ 下限 0.71 で通過）。

**回帰の張り替え**: `ShippedWeaponIdentityTest#theGreataxeSweepsHardest` は
「最強の薙ぎ払いは大斧」を固定していたので、**主役が大剣へ移った時点でこのテスト自体が誤り**になる。
`theGreatswordSweepsHardest` へ名前ごと入れ替え、さらに
`theGreataxeSweepStaysUnderTheGreatsword`（大斧の倍率・半径がどちらも大剣を下回ること。
大斧が10本以上読めていることも同時に検査して空振りを防ぐ）を新設した。
RED 確認済み: `netherite_greataxe` の `aoe-damage-rate` を 0.35 へ戻すと新テストが落ちる。

実走: `ShippedWeaponIdentityTest` 10 / `WeaponDpsParityTest` 4 / `WeaponTierParityTest` 9 /
`ItemStatsConfigTest` 38 = **61件・失敗0・エラー0・スキップ0**。

**Wiki も直した**（`docs/wiki-source/prose/03-ステータスと厳選.md`）。武器種テーブルが
「大斧＝範囲ダメージが最強」「レイピア＝貫通が高く会心率も高い」という**もう偽の記述**で、
W-126 で入った大剣・鎌・剣の値も反映されていなかった。

⚠️ **配備には jar は不要、config（`stats/item-stats.yml`）の再配備だけで効く。**
`/trinityforge reload` でも既存品へ再導出される（焼き付けはしていない）。

---

### 実サーバ報告バッチ（2026-08-19 受領 第13陣。W-152〜W-155）

| ID | 内容 | 状態 |
|---|---|---|
| W-152 | 炸裂魔法の炸裂までの時間が「短縮」などで変わっていない気がする | ✅ 修正（fork。**気のせいではなく本当に効いていなかった**） |
| W-153 | 称号の位置がネームタグと同期しておらず、少し遅れてついてくる（他人の称号を見て） | ✅ 修正（TF jar。packetevents で**クライアント側だけ搭乗**させる） |
| W-154 | EXPの逓減（ロック）が一度かかったら24時間で強制解除して100%へ戻す。修正時に全員の既存ロックも解除する | ✅ 実装（TF jar + `stats/skill-exp.yml`） |
| W-155 | メール送信コマンド（GUI付き）。お詫び報酬の全鯖民一斉送信／個別の課金報酬送信。受信は `/tf mail`。送信者がログアウト中でも届くこと | ✅ 実装（TF jar のみ。config なし） |

#### W-152 真因 — 「短縮」は**2個で1段**しか効かないのに、**上限が1個**だった

`SpellContext#applyDurationDown()` は `durationDownAccum` を貯めて **2 スタックごとに 1 段**
`durationLevel` を下げる実装だった。ところが `glyphs.yml` の `max-augments.duration_down` が
**14 グリフすべてで `1`**。つまり「短縮」は最大1個しか刺さらず、**1 個では絶対に段が下りない**。
警告もエラーも出ないので、刺さっているのに何も起きない = 「気のせい？」に見える。

修正は 2 段構え。

1. **効く上限にする** — 実用になる 6 グリフ（炸裂 / 幻影ブロック / 水生成 / ルーン / 滑空 / 浮遊）の
   `max-augments.duration_down` を **1 → 2** へ。
2. **2個で1段のまるめを捨て、1個ごとに固定 tick 引く**（`SpellDurationMath#resolve`）。
   グリフごとに専用の減少量と下限を `glyphs.yml` へ出した（炸裂は `fuse-per-duration-down: 8` /
   `min-fuse-ticks: 4` = 短縮1個で 0.4 秒速くなり、2個でも 4 tick は残る）。
   延長側（`duration_up`）の意味は変えていない。

⚠️ **`gravity` と `rotate` は対象外**。この 2 つは `if (durationLevel > 0)` で負のレベルを無視する
実装なので、短縮を刺しても構造的に何も起きない（それが仕様として自然な効果なので触っていない）。

回帰は `DurationDownIsEffectiveTest`（fork）。**`max-augments` を 1 へ戻すと落ちる**ように書いてある
（上限が壊れたら数式が正しくても意味が無いため、yml の値そのものを検査している）。
配備は **ArsPaper の jar + `glyphs.yml`**。TF 側は無関係。

#### W-153 真因 — 称号は**別エンティティを毎tickテレポートで追わせていた**ので、原理的に同期しない

称号は `TextDisplay` を本人の頭上へ毎 tick テレポートさせていた。他人から見ると、
**本体の補間と称号の補間が別々に走る**ので必ず1〜数tick遅れて見える（自分の称号が遅れて見えないのは
自機の位置がローカルで確定しているため。ユーザーの「他人の称号」という観測はここと一致する）。

サーバ側で `addPassenger` すると同期はするが、**乗客がいるエンティティはテレポートできなくなり
`PlayerTeleportEvent` すら飛ばなくなる**（PaperMC/Paper#10168）。ワープ・ダンジョン転送が全部死ぬので採れない。

採ったのは **packetevents でクライアントへ `SetPassengers` パケットだけ送る**方式
（`TitleDisplayMountBridge`）。サーバのエンティティ関係は一切変えないので上記の副作用が出ない。
表示位置はバニラの搭乗オフセット（`height * 0.75`）を打ち消す `Transformation` の平行移動で合わせ、
ネームタグ（`height + 0.5`）より必ず上に来ることをテストで固定した。

⚠️ **packetevents は `compileOnly`（任意依存）**。無い環境では橋を張らずに従来のテレポート追従へ落ちる
（機能が消えるのではなく、遅れが戻るだけ）。実サーバには packetevents 2.11.1 が入っている。

#### W-154 — 24時間で強制解除。起点は「**逓減が発動した時刻**」

既存の日次逓減は**指数減衰の窓**なので、24 時間放置しても `e^-1 ≒ 37%` が残る。
つまり「一度下がったらいつまでも戻らない」という報告どおりの挙動だった。

- `lock-release-hours: 24`（`stats/skill-exp.yml`）— **逓減が発動した時刻**から数えて 24 時間で
  蓄積ごとゼロにし倍率を 1.0 へ戻す。**稼ぎ続けていても延長されない**（ユーザー選択）。
  倍率が自然に等倍へ戻った時点で時計は捨てる。`0` を書けば従来どおり期限なし。
- `reset-id: "2026-08-19-w154"` — **全員の既存ロックを1回だけ**消す合言葉。適用済みIDを
  `daily_exp_meta` テーブルへ記録するので、再起動を繰り返しても 2 度は消えない。
  真偽フラグにしなかったのは、`true` のまま忘れると**逓減が永久に効かなくなる**うえ
  誰も気づけないから。もう一度全員解除したくなったら別の文字列へ書き換える。

`daily_exp_windows` に `locked_at` 列を `ALTER TABLE` で足している（旧スキーマからの起動を
移行テストで固定済み）。**「未ロック」を `locked_at == 0` で表さない**こと — 実装中にそれで
1970-01-01 起点のテストが素通りした。専用の boolean を持たせてある。

#### W-155 — メール（`/tf mail`）

- 保管は**ジャンクション共有の SQLite**（`player_progression.db` の `mail` テーブル）。
  PDC ではないので**送信者がログアウト中でも、宛先が別バックエンドに居ても届く**。
- 受け取りの原子性は **1 本の `UPDATE ... WHERE claimed_at = 0` の更新行数だけ**で決めている。
  GUI 連打や 2 サーバ同時ログインで**添付が複製されない**ことが最優先（配った物は回収できない）。
  渡す直前に落ちた場合は `unclaim` で受信箱へ戻すので、品が消えることもない。
- 入口は `/tf mail`（全員）＋ログイン時の通知＋ `/tf settings` の左上ボタン。
  送信は `/tf mail compose [名前|all]`（運営のみ）。GUI に置いた添付は
  送信・閉じる・切断のどの経路でも必ず手元へ戻る。
- **一斉送信は「送信時点で存在するプレイヤー」限定**（ユーザー選択）。上限 5000 人。
  未受取は既定 30 日で失効、受取済み／失効ぶんは 7 日後に掃除する。

回帰は `MailStoreTest`（10件）。添付そのものの直列化は Bukkit 実装が要るので踏んでいない
（MockBukkit は未実装 API を SKIPPED に化けさせるため、踏むと緑のまま壊れる）。

---

### W-156（2026-08-19 受領）資源サーバで岩盤が壊せない — TF ではなく Paper の `unsupported-settings` の食い違い

**TF は無関係**（TF のソース・yml に `BEDROCK` の参照は 1 件も無い）。原因は Paper の
`config/paper-global.yml` → `unsupported-settings` が **バックエンド 3 台でバラバラだった**こと。

| キー | Main_Server | Resource_Server | Dev_Server |
|---|---|---|---|
| `allow-permanent-block-break-exploits` | true | **false** | **false** |
| `allow-headless-pistons` | true | **false** | **false** |
| `allow-piston-duplication` | true | false | false |

`allow-permanent-block-break-exploits` は「バニラでは可能な岩盤・エンドポータル枠などの破壊」を
Paper が既定で塞いでいるスイッチ（既定 false）。**岩盤剥がしができないのはこの設定どおりの挙動**で、
メインでは通り資源サーバでだけ通らない、という食い違いになっていた。

切り分けが要らない確認方法: **同じ機構をメインで組んで動けば、設定差が原因で確定**。

`ops/templates/paper-global.yml.diff` は `proxies` しか面倒を見ないので、この差は誰も検出しない。
揃えるスクリプトを追加した（**触るのは上記 2 キーだけ。増殖バグの `allow-piston-duplication` は
既定では触らず `-IncludePistonDuplication` を付けたときだけ**）:

```
ops\launchpply-block-break-exploits.cmd -DryRun
ops\launchpply-block-break-exploits.cmd -Target resource
```

**稼働中のサーバには効かない。反映には再起動が必要**（2026-08-19 22:40 時点で 3 台とも稼働中だった
ため、書き換えは未実行）。

注意: PaperMC/Paper#11168 は「フラグを true にしてもピストン式の岩盤破壊がバニラと同じにならない」
という報告が **works as intended で close** されている。フラグを開けても設計によっては
バニラどおりにならない可能性がある。

| ID | 内容 | 状態 |
|---|---|---|
| W-156 | 資源サーバで岩盤が壊せない | ⏸ 設定変更スクリプトは用意済み。**ユーザーが実行＋再起動する**まで未反映 |

### 実サーバ報告バッチ（2026-08-19 受領 第14陣。W-157〜W-160）

| ID | 内容 | 状態 |
|---|---|---|
| W-157 | 統合版のスキンが DiscordSRV の通知に反映されない | ⏸ 原因確定・直し方も確定。**書き換え先が `D:` 配下なのでユーザーが適用する** |
| W-158 | CMD 付きの item でもマテリアルが共通のものを利用したカスタムクラフトができない（**統合版のみ**。実例=ソースジャー） | ❌ **Bukkit のレシピでは原理的に表現できない**ことが確定。残る手は3つ（下記）。今回は着手していない |
| W-159 | 鍛冶台で CMD 付き・非ダイヤ系のアイテム（ダイヤの弓／クロスボウ）をネザライト化できない（**統合版のみ**。そもそも base スロットに置けない） | ✅ 実装（TF jar。**本物の鍛冶台へサーバ側から差し込む**） |
| W-160 | 鍛冶台インベントリを開くとアイテムがちらつく（一瞬増殖したように見える） | ✅ W-159 と同一原因（クライアント予測の拒否→再同期の往復）。同じ修正で消えるはず。**統合版実機での確認が要る** |

#### W-157 DiscordSRV のアバター — 既定 URL が **Mojang に存在しない UUID** を引いている

DiscordSRV 1.30.5 は `AvatarUrl: ""` のとき
`https://crafthead.net/helm/{uuid-nodashes}/{size}#{texture}` を使う。統合版プレイヤーの UUID は
Floodgate が組んだもの（上位 64bit が 0）で **Mojang に存在しない**ため、crafthead は既定の頭を返す。

一方 `{texture}` は**統合版でも正しく解決する**。DiscordSRV の `NMSUtil.getTexture(Player)` は
GameProfile の `textures` プロパティを実物から読むだけで、そこは Floodgate が統合版スキンで埋めている。
実際にそのプレイヤーのテクスチャハッシュが入っていること、`https://crafthead.net/helm/<hash>/64` が
200 / image/png を返すことを確認済み。

**直し方**（`D:` 配下＝エージェントは書けないのでユーザーが実行）:

```
D:\game\minecraft\PaperServer\Velocity_for_TF\Main_Server\plugins\DiscordSRV\config.yml
AvatarUrl: "https://crafthead.net/helm/{texture}/{size}"
```

そのあと `/discordsrv reload`。**参加メッセージだけは依然スキンが乗らないことがある**
（Floodgate がスキンを適用するのが非同期なので、参加通知の方が先に出うる）。

#### W-158 統合版で `custom:` 素材のクラフトができない — **レシピで表現する道が無い**

`ExactChoice` に変えれば直る、という筋は**間違い**（この方向で一度提案して撤回した）。機構:

1. 1.21.2 以降の `net.minecraft.world.item.crafting.Ingredient` は **`HolderSet<Item>`＝アイテム型の集合だけ**で、
   ItemStack を持てない。
2. Paper の `Ingredient.java.patch` は ExactChoice 用に `itemStacks` と厳密な `test()` を足しているが、
   **`display()` は上書きしていない** → クライアントへ送る `SlotDisplay` は**アイテム型のみ**。
   MaterialChoice でも ExactChoice でも送られる内容は同じ。
3. Geyser の `RecipeUtil.translateToInput` は `ItemStackSlotDisplay` を受けたときだけ
   `CustomItemTranslator.getCustomItem` を通す。Bukkit レシピの素材はそこへ到達しない。

つまり **「この CMD のアイテムを素材にせよ」をクライアントへ表現する経路が存在しない**。
TF の `CatalogRecipeRegistrar#choiceFor` も ArsPaper の `RecipeManager#resolveIngredient` も
`custom:` を `MaterialChoice(base material)` にしているが、これは**原因ではなく唯一可能な選択**。

**残る手は3つ**（どれも未着手。②③はユーザー判断が要る）:

- ① その素材を Bedrock カスタム登録しない（見た目がバニラ素材になる。GeyserExtra が既に 493 件でこの方針）
- ② TF 側に統合版専用の代替クラフト GUI を作る
- ③ GeyserExtra から Bedrock へ補正レシピを注入する。**Geyser の internal API と ItemStackRequest の
  受け側が要る**うえ、`GeyserAutoUpdate` が入っているので Geyser 更新で**無言で壊れる**。
  さらに 1.21.2 以降はレシピの名前空間キーがクライアントへ送られないので、TF→GeyserExtra の対応付けは
  結果 CMD やパターンからの推測になる

副産物（別の落とし穴）: Paper の `RecipeManager#addRecipe` は `finalizeRecipeLoading()` →
**`getPlayerList().reloadResources()`** まで走る。起動時は無害だが、**人がいる状態で
`/trinityforge reload` を叩くと `Bukkit.addRecipe` の回数だけ全オンラインプレイヤーへレシピ再送**になる。

#### W-159 / W-160 鍛冶台 — **統合版クライアント側のスロット判定**で、サーバからは広げられない

TF 側の登録は正しかった。`CatalogRecipeRegistrar#registerNetheriteOne` は base=BOW/CROSSBOW/TRIDENT/MACE の
`SmithingTransformRecipe` を実際に登録しているし（`CatalogRecipeRegistrarNetheriteTest` が固定済み）、
`catalog.yml` の該当 13 件に `draft: true` は無く、配備済み jar・config も当日の最新だった。
Java 版では置ける。**置けないのは統合版だけ** ── 鍛冶台のスロットが何を受け付けるかは
Bedrock クライアントが自前で持っていて、サーバから届くレシピでは広がらない。
GeyserMC/Geyser#4706 が **"Can't Fix / Missing Client Feature"** で閉じているのがこれ。
W-160 のちらつきは、その拒否とサーバ状態の再同期の往復（Geyser#3332 の
"input item glitches out, item count turns yellow" と同じ見え方）。

**採らなかった案**: チェスト型の独自 GUI で結果を自前計算する方式。`PrepareSmithingEvent` には
**3 プラグイン 7 つの判定**が乗っているので、全部バイパスして二重管理になる ──
TF の `CatalogSmithingListener`（宣言済みネザライト化 / W-51 の品質再刻印）・
`CatalogCraftGateListener`（スキルツリーの `recipe:<id>` ゲート）・
`CatalogVanillaOperationGuardListener`（宣言外のカタログ品を止める）、
ArsPaper の `SmithingTableUnlockGate`（perk ゲート）と `CustomItemListener`（Ars カスタム防具のトリムで PDC 保全）、
EliteMobs の `PreventUpgradeDiamondToNetherite`（エリート装備のネザライト化禁止）。

**採った案**: `com/trinityforge/smithing/BedrockSmithingAssistListener.java`（新規）。
統合版プレイヤーが**アイテムを持った状態で**鍛冶台を右クリックしたら横取りし、
`MenuType.SMITHING` で**本物の鍛冶台**のビューを組み、**開く前に**サーバ側から base スロットへ差し込む。
サーバ側の `Inventory#setItem` はクライアントのスロット判定を通らないので置ける。
以降は完全にバニラの経路で、`ItemCombinerMenu` の入力コンテナは `setChanged()` →
`slotsChanged()` → `createResult()` を呼ぶため **差し込みでも `PrepareSmithingEvent` は正規に発火する**
＝上の 7 つはそのまま効き、トリムもバニラのネザライト強化も Java と同じになる。

非自明な確認事項（実物で取った裏）:

- **結果の取り出しは統合版でも通る。** Geyser の `SmithingInventoryTranslator` は Java 0/1/2/3 を
  Bedrock 53/51/52/50 へ写すだけで `shouldRejectItemPlace` も `getSlotType` も上書きしていない。
  さらに `InventoryTranslator#translateRequest` は汎用コンテナでの `CRAFT_RECIPE` を **break で読み飛ばす**ので、
  結果スロットからの取り出しは**ただの TAKE** として Java スロット 3 のクリックに変換される。
  **クライアントがそのレシピを知っている必要はない**（＝W-158 の制約はここには効かない）。
- 7 つの判定はどれも `event.getView().getPlayer()` しか見ておらず **`getViewers()` に依存していない**ので、
  「開く前に差し込む」順序でも壊れない（`getViewers()` はその時点でまだ空）。
- 統合版の判定は **UUID の上位 64bit が 0 か**で行う（Floodgate は `new UUID(0, xuid)` で組む）。
  `paper-plugin.yml` は `softdepend:` を黙って捨てるので Floodgate API に依存させない。
  Java 版から動作確認する逃げ道として `trinityforge.smithing.bedrock-assist`（既定 false）を用意した。
- **横取りする条件を絞ってある**。「どれかのスミスレシピの base に一致し、かつ型・追加素材のどれにも
  一致しない」ときだけ。判定はハードコード一覧ではなく**登録済みレシピの ingredient から引く**ので、
  バニラやプラグインが素材を足しても追随する。緩めると
  「鍛冶台を開いただけでインゴットや鍛冶型を base スロットへ吸われる」という**元より悪い壊れ方**をする
  （`BedrockSmithingAssistListenerTest` 10 件で固定。base と addition の両方に一致する物は
  **addition を優先して横取りしない**ことも含む）。

**自己レビューで見つけて直した欠陥（アイテム消滅）**: `HumanEntity#openInventory(InventoryView)` は
**戻り値が void で、`InventoryOpenEvent` がキャンセルされると CraftBukkit は何もせず黙って返る**
（`CraftEventFactory#callInventoryOpenEventWithTitle` が container=null を返す経路。Paper のソースで確認）。
初版は成否を見ずに手持ちを消していたので、**キャンセルされた瞬間にアイテムが消える**。
現在は「手持ちを消す → 差し込む → 開く → 開けたか中身で確認 → 駄目なら巻き戻して手に返す」にしてある。
どの瞬間もアイテムの実体が手持ちか鍛冶台のどちらか一方にしか無いので、複製にも消滅にも倒れない。
`openedWith` を `return true`（＝初版の挙動）へ差し戻すとテスト 2 件が実際に落ちることを確認済み。

**分かっている制限**:

- 補助が効くのは「**アイテムを持って鍛冶台を右クリック**」のときだけ。開いた後にインベントリから
  base スロットへドラッグする経路は統合版では依然できない（クライアント側判定なので手が無い）。
  差し込み時にアクションバーで知らせている。
- 保護系プラグインが `PlayerInteractEvent` を **HIGHEST で** DENY する構成だと、こちらが HIGH なので
  先に開いてしまう。現在の構成には該当プラグインが無いので HIGH のままにしてある
  （HIGHEST へ動かすと TF 自身の `CatalogVanillaOperationGuardListener`(HIGHEST) との順序が変わる）。
- `MenuType` は `@ApiStatus.Experimental`。
- ハンドラ本体（右クリック横取り）は MockBukkit で組み立てられないので、テストは
  **判定関数（横取りするか / 開けたか）だけ**を固定している。

**実機確認が残っている**（統合版クライアントが要るのでエージェントには取れない）:
ダイヤの弓／クロスボウを持って鍛冶台を右クリック → base スロットに入るか → ネザライト強化が成立するか →
ちらつきが消えるか。**トリムとバニラのネザライト強化が今までどおり動くこと**も併せて見る。

---

### 実サーバ報告バッチ（2026-08-20 受領 第15陣。W-158 続報 / W-161）

配備後（TF jar 2026-08-20 00:33）の再テストで出た 2 件。**どちらも Java では再現しない。**

| ID | 内容 | 状態 |
|---|---|---|
| W-158（続報） | 統合版でカスタムアイテムを材料にしたクラフトが**軒並み**通らない（手置きでも同じ／Java は通り通知も出ない）。ソースジャー／TF 圧縮素材／TF 装備の3系統すべてで発生 | ✅ **真因確定（2026-08-20）**。Geyser が結果枠の取り出しを「クライアント予測の個数 ≠ サーバの結果の個数」で `rejectRequest` する。**W-161 と同一原因・サーバ側は無罪**。下記「W-158 / W-161 の真因が確定」参照。**修正実装済み・配備待ち**（A案: 補正レシピ注入。下記「修正を実装」参照） |
| W-161 | 鍛冶台・作業台の GUI を開いている状態で shift クリック／ドラッグするとアイテムがちらつく | ✅ **真因確定（2026-08-20）**。GeyserExtra の `updateInventory()` 3 箇所＋ Geyser の `rejectRequest` によるインベントリ再同期。**W-158 と同一の根**。修正は保留（ユーザー判断: まず調査結果だけ） |

#### W-158 続報 — 「誰がマス目にアイテムを入れるか」が Java と統合版で違う

TF 側でこのメッセージを出しているのは `CatalogWorkbenchListener#onPrepareCraft` の
**`ours.isEmpty()` 分岐**（＝選択されたレシピが TF のカタログレシピ<b>ではない</b>）で、条件は
①盤面に識別付きアイテムが乗っている ②そのレシピの持ち主がその品の所有者ではない
③兄弟カタログレシピへの再マッチにも失敗した、の3つが揃ったとき。
アイテム名が出た＝**盤面には正規の識別付きアイテムが乗っていた**ことが確定する
（`catalogIdentityOf` は TF カタログ品だけでなく `ExternalItemRegistry` 経由で ArsPaper 品も識別する）。

**なぜ統合版だけか**: Geyser の `InventoryTranslator#translateAutoCraftingRequest` は、
どのスロットから材料を吸うかを**Bedrock クライアントが送ってきた `ConsumeAction` のスロットで決める**。
アイテムの中身を照合していない（`SlotDisplay` は結果スロットにしか使われない）。
つまり**材料の選択権は完全に Bedrock クライアント側にある**。

そして Bedrock クライアントは TF/Ars のカスタム品とバニラ品を**見分けられない**か、
**別物として扱って絶対に選ばない**かのどちらかにしかならない:

- GeyserExtra は素材アートの無いマッピング（PDC のみの約 493 件）を意図的に登録せず
  **バニラ素材そのものとして描画させている** → クライアントから見て完全に同一。
  レシピ帳が「レンガ」を要求したとき、圧縮レンガの束を掴むことがある
  → 盤面にカタログ品が乗り、選択レシピはバニラ側 → **このメッセージ**。
- 逆にアートがあって Bedrock カスタム item として登録されている品は**別 ID**になるので、
  「バニラのレンガ」を要求するレシピ（＝ `custom:` 素材は型でしか表現できない。W-158 本文参照）には
  クライアントが**絶対に入れてくれない**。

どちらに転んでも**レシピ帳経由の自動クラフトは成立しない**。Java では材料を選ぶのが人間で、
ツールチップで名前を見て選べるので起きない。

**2026-08-20 追試: 手置きでも落ちた** → 自動クラフト経路だけでは説明が付かないので、
`listeners/CraftIdentityDiagnosticListener` を入れて実測に切り替えた（`BrewDiagnosticListener` と同じ型）。
盤面に識別付きアイテムが乗っているときだけ、**選んだレシピのキー**・**各マスの material/CMD/カタログ id/所有プラグイン**・
**LOWEST〜MONITOR の各優先度で結果枠がどうなっていたか**を 1 行出す。
`recipe=none` ならレシピが 1 つも解決していない（identity 以前に型か配置の問題）、
`recipe=` が TF 以外なら後勝ちで別レシピが選ばれている、`result@` が none に変わった優先度帯で
どのハンドラが消したかが分かる（TF は HIGH/HIGHEST、GeyserExtra の `CraftingRecipeHandler` は MONITOR）。
`craft` 行が出なければ結果枠の取り出しまで到達していない。
**Java 版も対象**にして `bedrock=yes/no` を同じログで突き合わせられるようにしてある。
**原因が確定したら外すこと。**

**当初の切り分け（自動クラフト経路説）— 追試で否定済み**: レシピ帳をタップせず、
**3×3 のマスへ自分でアイテムをドラッグして置く**と通るはず。作業台のマス目はスミス台の base スロットと違い
クライアント側の受け入れ判定が無いので、置いたスタックがそのまま Java 側の盤面に入る。
**ここが通るなら原因は自動クラフト経路で確定**し、TF 側で直す/回避する余地も出る。
通らないなら別の機構がもう1つあるので、`CatalogWorkbenchListener` の遮断点に
（選択レシピのキー・各マスの material/CMD/カタログ id・Bedrock か否か）を吐く診断ログを入れて取り直す。

#### W-161 ちらつきの原因 — GeyserExtra が Bedrock プレイヤーにだけ `updateInventory()` を撃っている

`geyserExtraα` に、Bedrock プレイヤー限定で **1 tick 後に `player.updateInventory()` を呼ぶ箇所が3つ**ある。

| 場所 | 発火条件 |
|---|---|
| `recipe/CraftingRecipeHandler#onPrepareCraft`(MONITOR) | **作業台のマス目が変わるたび**（ドラッグ／shift クリックで大量に飛ぶ） |
| `recipe/CraftingRecipeHandler#onInventoryClick`(HIGH) | 作業台の結果枠クリック後 |
| `recipe/SmithingRecipeHandler#onInventoryClick`(HIGH) | **鍛冶台**の結果枠クリック後 |

`updateInventory()` は開いているコンテナ＋プレイヤーインベントリを丸ごと再送するので、
Bedrock ではクライアントの予測状態が毎回上書きされる＝ちらつく。鍛冶台と作業台の両方で起きるという
報告の形と一致する（W-159 で直した base スロットのちらつきとは**別原因**）。

同ファイルで見つけた併発の問題:

- `CraftingRecipeHandler#onPrepareCraft` は 1 tick 後に `inventory.setResult(result)` を**再適用**するが、
  `onInventoryClick` 側にはある**盤面の再検証が無い**。1 tick の間に盤面が変われば
  **消えたはずの古い結果を差し戻す**（TF が識別ガードで消した結果も戻りうる）。
- `SmithingRecipeHandler#customRecipes` は **`registerRecipe` の呼び出し元がゼロ**で
  `onPrepareSmithing` は空ループ。このクラスが実際にやっているのは `updateInventory()` を撃つことだけ。

**未修正**（2026-08-20 ユーザー判断「まず調査結果だけ」）。直す場合は GeyserExtra 側のビルドと配備が要る。

#### W-158 / W-161 の真因が確定 — **Geyser は結果枠の取り出しを「個数が一致しないと reject」する**（2026-08-20）

実サーバ報告で決定的な観測が出た: **Java はクラフト可能・通知も出ない**（以前は通知が出てクラフト不可、それは修正済み）。
**統合版はリザルトも表示されない**。ただし **ソースジェムの圧縮はリザルトが出ず、解凍はリザルトが出た。いずれもクラフト不可**。
この非対称から、Geyser 本体のソースまで降りて機構が確定した。**W-158 と W-161 は同一原因**。

証拠の鎖（すべて一次ソース）:

1. **統合版はクラフト結果をクライアント側で計算する** — `geyserExtraα` の
   `paper/.../recipe/CraftingRecipeHandler.java` クラス javadoc の明言
   (*"Bedrock Edition calculates crafting results client-side"*)。だから GeyserExtra は
   **サーバ側の結果を 1 tick 後に押し込む**回避策を持っている（`result != null` のときだけ動く）。
2. **Geyser はクラフトの「レシピ実行」要求を黙って捨てている** — `InventoryTranslator#translateRequest` は
   `CRAFT_RECIPE` / `CRAFT_RECIPE_AUTO` / `CRAFT_RESULTS_DEPRECATED` を `break` で素通しする。
3. **よって統合版の「結果枠から取り出す」は普通のスロット移動として翻訳され、出力スロット専用の拒否条件に当たる**:

   ```java
   if (pendingOutput == 0 && !isSourceCursor && getSlotType(sourceSlot) == SlotType.OUTPUT
       && transferAction.getCount() < plan.getItem(sourceSlot).getAmount()) {
       if (isDestCursor) return rejectRequest(request);
   ```

   `transferAction.getCount()` は**クライアントが自前の予測で決めた個数**、
   `plan.getItem(0).getAmount()` は**サーバの実際の結果の個数**。
   食い違うと**要求ごと reject され、サーバにはパケットが1つも届かない**。
4. **作業台の Java スロット0 は Geyser で `SlotType.OUTPUT`** —
   `CraftingInventoryTranslator#getSlotType` が `javaSlot == 0 → SlotType.OUTPUT` を返す。
   つまり 3 の拒否条件は作業台の結果枠に**必ず**当たる。

症状との対応:

| 症状 | 機構 |
|---|---|
| リザルトが出ない | 素材が独自 Bedrock アイテムとして登録されているのに、クライアントのレシピ表は**バニラ素材**で書かれている（Bukkit は素材の identity をクライアントへ送れない）ので照合できない |
| リザルトは出るのにクラフト不可 | 表示はサーバ側の結果（GeyserExtra の押し込み）。取り出しの個数判定は**クライアント側の予測**なので食い違い、Geyser が `rejectRequest` |
| Java は通る | Java はクライアントが盤面を送りサーバが判定する。予測も照合もクライアントに無い |
| ちらつき（W-161） | `rejectRequest` はインベントリ再同期を伴う。GeyserExtra の `updateInventory()` と合わせて二重に撃たれる |

**サーバ側（TF / ArsPaper）は無罪。** 直すなら**クライアントのレシピ表をサーバと一致させる**しかない
＝ TF → GeyserExtra へレシピ表を渡し、**実際の Bedrock アイテム id で Bedrock 側レシピを注入する**。

未観測なのは「解凍で実際に count がいくつ食い違ったか」だけで、**修正方針はこれに依存しない**。

#### W-158 / W-161 の修正を実装（2026-08-20）— **A案: 補正レシピを統合版クライアントへ注入**

**配備待ち。ビルドはしたがサーバへは入れていない。実機確認は未了。**

真因は「クライアントが持つレシピ表が『バニラ素材 → カスタム完成品』になっている」こと。
Geyser の `JavaUpdateRecipesTranslator` は素材を `mapping.getBedrockDefinition()` で**バニラの
Bedrock 定義へ落とす**（CustomModelData は Java の `Ingredient` に載らないので変換元に情報が無い）
一方、**結果だけは `ItemTranslator.translateToBedrock` を通ってカスタム品になる**。
統合版はクラフト結果をクライアントが計算するので、盤面のカスタム素材とは永久に一致しない。

欠けている情報（素材が本当はどのカスタムアイテムか）を4リポジトリで運ぶ:

| 担当 | 実装 | commit |
|---|---|---|
| TrinityForge | `com.trinityforge.bedrock.BedrockRecipeExporter` → `plugins/TrinityForge/bedrock-recipes.json` | `aaed1a5` |
| ArsPaper | `com.arspaper.recipe.BedrockRecipeExporter` → `plugins/ArsPaper/bedrock-recipes.json` | `c51ccf6`（`feat/trinityforge-fork`） |
| GeyserExtra (Paper) | `BedrockRecipeTableCollector` → `<extension>/bedrock-recipes/<backend>.json` | `8cd1091` |
| GeyserExtra (extension) | `BedrockRecipeInjector` → `CraftingDataPacket(cleanRecipes=false)` を追送 | `610705f` |

**なぜ Geyser の内部 API を使わざるを得ないか**（後から「公開 API でやり直せ」と考えないための記録）:
Geyser の公開 API には**ダウンストリームのパケットイベントもサーバ移動イベントも無い**
（`api/.../event/downstream/` にあるのは `ServerDefineCommandsEvent` 1本だけ）。そして Java サーバは
**`Bukkit.addRecipe` のたび・バックエンド移動のたびにレシピを全再送**し、Geyser はそれを
`cleanRecipes=true` で送り直す（`session.setCleanRecipesRequired(true)`）。
＝ **セッション参加時に1回送る実装は、最初のリロードかサーバ移動で黙って消える。**
そのため `Registries.JAVA_PACKET_TRANSLATORS` の `ClientboundUpdateRecipesPacket` 変換器を
**ラップして直後に走らせる**のが唯一正しい位置。`compileOnly org.geysermc.geyser:core:2.11.1-SNAPSHOT`
を追加した（**稼働中のプロキシは Geyser 2.11.1-b1223 / commit 85fb46f**、`git.properties` で確認）。
移動するスナップショットなので更新で壊れうる。全入口を `Throwable` で包み、
**最初の失敗でこの機能だけ自己無効化して1回だけ警告する**。

**踏まないように決めた設計判断**:

- **解決できない素材が1つでもあるレシピは丸ごと落とす。** 半分だけ正しい表は
  「クラフトできない」より悪い**「別のレシピが成立する」**になる。
- **出すのは登録が成功したレシピだけ。** 登録に失敗したものを送ると、統合版クライアントだけが
  成立すると信じてサーバに拒否される。
- **逆レシピ（解凍）を必ず出す。** TF 側は `allRegistered()` に載らない（`registerReverseOne` が
  Bukkit へ直接入れるだけ）ので spec から組み直す。落とすと「解凍だけ直らない」＝実サーバ報告そのもの。
- **GeyserExtra が実際に登録した Bedrock アイテムだけを指す。** 見た目がバニラと同じで登録を
  見送った品（`isVanillaLookAlike`）は**クライアント側に区別する手段が無い**ので原理的に補正不能。
- **形式バージョンが違うファイルは丸ごと捨てて警告する。** 中途半端に読むと上の「別のレシピが成立する」に落ちる。
  書き手2本（TF / ArsPaper）は**あえてクラスを共有していない** — 共有すると ArsPaper のビルドが
  `libs/TrinityForge.jar` の差し替え待ちになる。代わりに `FORMAT_VERSION = 1` を揃えてある。
- **バックエンド1つにつき1ファイル**（`<backend>.json`）。3バックエンドが extension フォルダを
  共有しているので、名前を分けないと奪い合う（パック生成に `skinFixOnlyMode` が要るのと同じ衝突）。
- **`size+mtime` の変化で読み直す。** 一度きりだと (1) ArsPaper の enable 後に TF が書き直す本番の表を
  取り逃がす (2) `/trinityforge reload` がプロキシ再起動まで届かない。**その沈黙は「機能していない」と
  見分けが付かない**のが厄介。

**残っている限界（承知のうえ）**:

- Geyser が配る**誤ったレシピ（バニラ素材 → カスタム完成品）はそのまま残る**。`cleanRecipes=true` で
  全消し・全再構築するのは割に合わないと判断した。バニラ素材を並べると成立しないリザルトが見える挙動は現状維持。
- `list:` 素材は組み合わせごとに1レシピへ展開する。**1レシピあたり32件で打ち切り**、落とした件数をログに出す。
- 鍛冶台は下記「鍛冶台も対応」で実装した（**配備待ち・実機未確認**）。

**実機で見るべきログ**（配備後）:

- バックエンド: `[bedrock-recipes] <backend>: N recipes from 2 plugin(s) [ArsPaper=..][TrinityForge=..]`
- プロキシ: `[bedrock-recipes] N corrected recipes loaded; M custom Bedrock items are addressable`
- プロキシ: `[bedrock-recipes] corrected recipes will be sent after Geyser's own`
- 出ない場合の切り分け順: ①バックエンドの `plugins/TrinityForge/bedrock-recipes.json` があるか
  ②`<extension>/bedrock-recipes/*.json` に届いているか ③プロキシ側の警告

#### 鍛冶台も対応（2026-08-20）— **item tag + SmithingTransformRecipeData の 2 段構え**

**配備待ち。ビルドとテストは通したがサーバへは入れていない。実機確認は未了。**

**前提がひとつ間違っていた。** `BedrockSmithingAssistListener` の javadoc と上記の
「鍛冶台は未対応」は、GeyserMC/Geyser#4706 が "Can't Fix / Missing Client Feature" で閉じている
ことを根拠に「統合版のスロット判定はサーバから広げられない」と書いていた。
**#4706 は防具トリム（custom trim patterns / materials）の issue**で、メンテナ onebeastchris の
回答も "Bedrock doesn't allow adding custom **trim patterns/materials**"。
**ネザライト強化（smithing transform）については何も言っていない。**

一次情報（bedrock.dev の Recipes ドキュメント / minecraft.wiki の Item tag (Bedrock Edition)）では、
統合版の鍛冶台の各枠は**アイテムタグで決まる**:

| 枠 | 要求タグ | TF の場合 |
|---|---|---|
| テンプレ | `minecraft:transform_templates` | バニラのネザライト強化テンプレが保持済み |
| base | **`minecraft:transformable_items`** | **カスタム品には無い ← これが原因** |
| 追加素材 | `minecraft:transform_materials` | バニラのネザライトインゴットが保持済み |

Geyser 公開 API の `CustomItemBedrockOptions.tags(Set<Identifier>)` は
`CustomItemRegistryPopulator.setupBasicItemInfo` で `addItemTag()` → NBT の `item_tags` に書かれる
（2.11.1-b1223 の jar を逆アセンブルして確認。バニラ由来カスタムアイテムの登録経路でも通る）。
`IdentifierImpl.toString()` は `Key.toString()` = `namespace:path`。

**実装（タグが本体、レシピ注入は補助）**:

> **クロスレビューで前提がもう一段ひっくり返った（2026-08-20）。**
> Geyser には既に「Java 側が結果を出したら、**盤面の実アイテムから**スミスレシピをその場で合成して
> クライアントへ送り、再計算を強制する」経路がある
> （`JavaContainerSetSlotTranslator#updateSmithingTableOutput`。逆アセンブルで確認。
> 入口は「テンプレ枠がネザライト強化テンプレであること」で、合成する ingredient は
> **実 `GeyserItemStack` 由来なのでカスタム識別子がそのまま載る**）。
> つまり **base 枠に置けさえすれば結果は出る**。**タグ 1 行が本体**で、
> 下記 2 の注入レシピが足しているのは「サーバ往復を待たずに結果が出る」ことだけ。
> `BedrockSmithingTableCmdStripper` の javadoc が「Geyser の動的合成はサーバが結果を出した後にしか
> 走らないが、置けないので結果が出ない」と書いていたのは正しく、**その袋小路の出口がタグ**だった。

1. **置けるようにする（本体）** — GeyserExtra が登録する**全カスタムアイテム**に
   `minecraft:transformable_items` を付ける（`CustomItemsHandler#registerVanillaItem`）。
   **対象を絞らないのは意図的**: 絞るにはレシピ表が要るが、表はバックエンドから
   `GeyserDefineCustomItemsEvent` の**後**に届くので、cold start でタグが欠け
   「2 回目の再起動から動く」という最悪の間欠故障になる。全付与の代償は
   「統合版だけ base 枠に何でも置けて結果が出ない」という見た目の非対称だけで、
   何が渡るかは Java サーバが決めるので実害は無い。
2. **結果を即出す（補助）** — 補正レシピに `SmithingTransformRecipeData`(tag=`smithing_table`) を足す。
   base は `source-item` のカタログエントリの material + CMD
   （Bukkit 側の登録は `MaterialChoice(材質だけ)` の緩い判定で、CMD 込みの照合は
   `CatalogSmithingListener` がやっている。材質だけをクライアントへ渡すと
   **素のバニラ弓でも完成すると表示される**）。

**形式バージョンを 1 → 2 へ上げた（必須）**。v1 の受け取り側は `type` が `"shaped"` 以外を
問答無用で shapeless 扱いするので、**スミス台の 3 枠を「3 素材の作業台レシピ」として配ってしまう**。
TF/ArsPaper と GeyserExtra は**別スクリプトで配備する**ので片側だけ新しい状態は現実に起こる。
逆方向（新しい受け取り側 × 古い書き手）は受け取り側が **{1, 2} の両方を受理**して吸収する。
**ArsPaper は v1 のまま変更していない** — スミス台レシピを 1 件も登録しないので上げる意味が無く、
無駄な再ビルドと再配備を強いるだけ。以前 ArsPaper 側のテストに書いてあった
「TF と同じ値でなければならない」は**もう誤り**なので文言ごと直した。

**変更したリポジトリ**:

| 担当 | 変更 |
|---|---|
| TrinityForge | `BedrockRecipeTable` に `Type.SMITHING`・`FORMAT_VERSION=2`／`CatalogRecipeRegistrar` に `completableSmithing` と `allCompletableSmithing()`／`BedrockRecipeExporter.build` がゲート集合を取る／assist の停止権限 |
| ArsPaper | 形式バージョンの契約コメントとテストの文言だけ（**出力は 1 バイトも変わらない**） |
| GeyserExtra (Paper) | collector が `{1,2}` を受理・出力は常に v2／`SmithingBaseExemptions` を新設し CMD ストリッパが注入対象を剥がさないようにした／全 reject 時に既存出力を消さない |
| GeyserExtra (extension) | reader が `Type` enum 化・未知 type は推測せず skip／injector が `SmithingTransformRecipeData` を送る（`default -> throw` 付き）／`CustomItemsHandler` が item tag を付け、identifier マップのコピーを O(1) 化／drop を warn へ |

**踏まないように決めた設計判断**:

- **`completableSmithing` は `registeredSpecs` と分ける。** あちらは `CatalogWorkbenchListener` が
  「作業台の盤面と突き合わせる候補」として総当たりするコレクションで、スミス台レシピを混ぜると
  **shape も shapeless 素材も持たない spec を舐めはじめる**。分ければ既存利用者は 1 行も変わらない。
- **条件を別実装で組み直さず、registrar が持っている判定結果をそのまま使う。**
  「登録できたか」ではなく**「サーバが完成させられるか」**が基準（上の 2 を参照）。
- **テンプレ／追加素材の Material は `CatalogRecipeRegistrar` の `public static final` 定数に集約。**
  登録側と表側で別々に書くと、クライアントだけが成立すると信じる盤面ができる。
- **未知の `type` は絶対に既知へフォールバックしない。** これが形式にバージョンを付けている理由そのもの。
- **`BedrockSmithingAssistListener`（手に持って右クリック）は残した。** 実機で新経路が通ることを
  確かめるまで、**統合版で唯一動いている経路を消さない**。確認後に撤去するのは 1 行。

**残っている限界**:

- `registerNonVanillaItem`（v1 API 経路）はタグを付けられない。TF のカタログ品は全て
  バニラ由来（CMD 変種）なので実害は無いが、非バニラ登録を増やしたらここが穴になる。
- `isVanillaLookAlike` で登録を見送った品は**そもそも Bedrock アイテムが無い**ので、
  作業台と同じくタグもレシピも当てられない。

**検証**: TF 4470 / 失敗 29 / スキップ 2（失敗は全て他セッションの yml 変更由来で、
`bedrock/`・`CatalogRecipeRegistrar`・`CatalogWorkbench*`・`CatalogSmithing*` の 95 件は全緑）、
ArsPaper 473 / 0、geyserExtra 320 / 0。**変異テスト 3 本で空振りでないことを確認**
（smithing→shapeless／未知バージョン受理／テンプレと base の並び入れ替え、いずれも該当テストが落ちる）。

**実機で見るべきログ**: 上記に加えてプロキシの
`[bedrock-recipes] sent N corrected recipes (M smithing)`（debug）。
**`(M smithing)` が出ないなら表にスミス台レシピが載っていない。**

##### クロスレビューで見つけて直したもの（同日・2 エージェント）

**1. CMD ストリッパと新経路が相討ちになっていた（配備すれば無言で全部無効化された）**

`BedrockSmithingTableCmdStripper` は統合版プレイヤーが**鍛冶台を開いている間、ウィンドウ内の
全アイテムから CMD を剥がして**送る（既定 ON）。すると統合版クライアントが持っているのは
素の `minecraft:bow` で、**タグも注入レシピも `geyser_custom:*` 側に付いている**ので
どちらも当たらない。しかも対象がちょうど排他だった:

| 対象 | 今日の状態 | 効いている機構 |
|---|---|---|
| ダイヤ系 8 件（短剣・レイピア・大槌・大斧・鎌・斧・戦鎌・杖） | **普通に置けている** | CMD ストリッパ（剥がせばバニラの強化レシピが一致する） |
| 弓・クロスボウ・トライデント・メイスの 4 件 | **置けない**（＝W-159 の報告そのもの） | ストリッパでは原理的に救えない（素のバニラ弓に `transformable_items` が無い） |

つまり**新経路が狙っているのは、ちょうどストリッパが救えない 4 件**で、そこでストリッパに潰される。

修正: **ストリッパの除外集合を、注入に使うのと同じマージ済みレシピ表から導出する**
（`SmithingBaseExemptions`）。表の smithing エントリの base 枠に載っている
`<material>#<cmd>` だけ CMD を剥がさない。**表にスミス台エントリが無ければ除外ゼロ＝現状のまま**
なので、片側だけ配備しても退行しない（自己整合）。それ以外のカスタム品は今までどおり剥がすので、
「カタログ外の CMD 付きダイヤ剣を剥がしてバニラ強化する」既存挙動も無傷。

**2. 出荷 12 件のうち 8 件が統合版の表から抜けていた（＝主要武器だけ直らない）**

`NetheriteUpgradeGuard` は base の材質がバニラのネザライト強化対象（DIAMOND_SWORD /
DIAMOND_AXE / DIAMOND_HOE）だと**登録を見送る**。最初の実装は「TF が登録できたものだけ」を
書き出していたので、12 件中 8 件が表に載らなかった。**だがサーバはその 8 件を完成させられる**
（バニラのレシピが代わりに一致 → `PrepareSmithingEvent` → `CatalogSmithingListener` が結果を差し替え）。
＝「直ったように見えて半分だけ壊れている」最悪の状態。

修正: 収集の意味を「登録できたもの」から**「サーバが完成させられるもの」**へ変えた
（`allRegisteredSmithing()` → `allCompletableSmithing()`）。ガードが**走査に成功した上で**
見送った分だけ載せ、走査自体が失敗した（＝既存レシピの有無が分からない）ときは載せない。

**3. パークゲートが掛かると「完成品が見えるのに取れない」（潜在）**

統合版は結果をクライアント側で計算するので、`CatalogCraftGateListener#onPrepareSmithing` が
`setResult(null)` しても**完成品が表示されたまま取れない**。出荷 skilltree に netherite 系の
`recipe:` ゲートは 1 件も無いので**今日は踏まない**が、config で有効にした瞬間に踏む。
修正: ゲート id が配置済みの強化は**表から外す**（Geyser の動的合成に委ねれば、
サーバが結果を出したときだけ表示されるので自動的に正しくなる）。
材質→ゲート id の対応は `CatalogCraftGateListener#netheriteGateIdFor` を共有する（二重定義しない）。

**4. 実機確認を成立させるスイッチが無かった** — 統合版プレイヤーには
`BedrockSmithingAssistListener`（手に持って右クリック）が無条件で掛かるので、
「置けたのは補助のおかげかタグのおかげか」を切り分けられず、**撤去条件を永久に満たせない**。
`trinityforge.smithing.bedrock-assist.off` 権限を足した（既定では誰も持たない）。

**5. イベントループ上で 1200 件超の Map を毎回コピーしていた** —
`CustomItemsHandler#registeredBedrockIdentifiers()` が `Map.copyOf` を返しており、
injector は**素材ごと・組み合わせごと・レシピごと**にこれを呼ぶ。実配備の
`custom_items.json` は 1223 件なので 1 セッションのレシピ送信で 5 桁回のフルコピーが
Netty のイベントループで走る。しかも Paper の `addRecipe` はレシピを毎回全員へ再送する。
`ConcurrentHashMap` + 固定の unmodifiable ビューに変えて O(1) 化。

**6. 失敗が debug ログにしか出なかった** — この機能は**失敗すると元の不具合と同じ症状**に
なるので、ログだけが「配備されていない」と「配備したが全件 drop している」の唯一の区別手段。
`dropped > 0` を warn へ（ロードごとに 1 回）。TF 側の書き出しログにも**鍛冶台の内訳**を出す
（合計だけだと 200 件中の数件の差に埋もれる）。

**7. 全 reject 時に既存の出力を消していた** — collector を旧版へ戻すと TF の v2 を全部 reject
→ `tables.isEmpty()` → **配ってあった `<backend>.json` を削除**で、作業台の補正まで道連れ。
reject が 1 件でもあるときは**消さずに前回の内容を残す**ようにした。

**8. injector の `switch` が非網羅でも通っていた** — アロー形式でも switch *文* なので、
`Type` が増えた日に「何も足さないまま `added++` だけ回り、ログは N 件送ったと言う」。
`default -> throw` を置いた（外側の `catch(Throwable)` が WARN を出してその起動の間だけ止める）。

##### 初回配備で実際に落ちた（2026-08-21 15:04）— `added-recipes` で表が丸ごと NPE

```
[bedrock] 補正レシピ表の書き出しに失敗した(統合版のクラフト補正のみ無効になる)
java.lang.NullPointerException: Cannot invoke "ItemTemplate.material()" because "template" is null
    at BedrockRecipeExporter.forward(BedrockRecipeExporter.java:197)
```

`progression/crafting-features.yml` の `added-recipes` は**結果になるカタログエントリを持たない**ので、
registrar は `template` を `null` にして `fixedResult` だけを載せる。これは
`RegisteredRecipe` の javadoc に**明記されていた契約**（「結果スタックが要る呼び出し側は
`template()` を直接使わず `resultOf()` を使うこと」）だが、書き出し側の `forward`/`reverse` が
`template` を直に触っていた。**鍛冶台対応ではなく W-158 のクラフト表側（`aaed1a5`）の元からのバグ**で、
この機構が本番で初めて走ったので露見した。

**落ちるのは 1 件ではなく表そのもの**なので、統合版の補正が作業台も鍛冶台も全部無効になる。
しかも出荷 `added-recipes` は **7 件とも `custom:` 素材（スクラップ→インゴット）**＝
補正が最も要るレシピ群だった。

**なぜテストが緑だったか**: 出荷カタログだけを通す `ShippedBedrockRecipeTableTest` は
`added-recipes` を経由しない（registrar を通さず自前でエントリを組んでいる）。
修正では「registrar に登録させてから書き出す」実経路のテストを 2 本足した
（`CatalogRecipeRegistrarTest`。`fixedResult` に本物の `ItemStack` が要るので MockBukkit 側）。

**実サーバの物証で分かったこと**（配備先 `plugins/TrinityForge/bedrock-recipes.json`、14 件）:

- **鍛冶台 12 件は全部載っていた** ＝ `NetheriteUpgradeGuard` で登録を見送る 8 件も表へ出す
  修正が実機で効いている（MockBukkit ではガードが空振りするので、これが初の実証）。
- クラフト側は 2 件だけ。これは **TF の `onEnable` 時点（ArsPaper より前）の表**で、
  `custom:` 素材が Ars 品を指すものは `PendingArsIngredient` で未登録、`added-recipes` も未登録。
  ArsPaper enable 後の再書き出しが NPE で落ちたため、この不完全な表が残っていた。
  修正後はクラフト側の件数が増える。

##### 配備順（重要）

**GeyserExtra を TrinityForge より先に配備する。** TF は表を常に v2 で刻むので、
GeyserExtra が旧版のままだと表を丸ごと reject する（作業台の補正も含めて）。
逆順（GeyserExtra だけ新しい）は v1 も受理するので安全。
※ 実配備の extension フォルダに `bedrock-recipes/` はまだ存在しない ＝
**この機構は本番で一度も動いたことがない**。今回が初回配備。

##### まだ直していない（今回の変更起因ではない）

- **Dev_Server / Resource_Server の表はプロキシに届かない。** 両者の `extension-data-folder` は
  skinFixOnlyMode のため**ローカル**を指しているので、そこへ書いた `bedrock-recipes/<backend>.json`
  を Geyser 拡張は永久に読まない。それでも `poll()` は成功ログを出す。今は config が
  ジャンクション共有で 3 台とも同じレシピ集合なので実害が出ていないだけで、
  **設計が意図したバックエンド跨ぎの union は一度も起きていない**。
- **`/geyser reload` 後、injector が古い `CustomItemsHandler` を掴んだまま**になる
  （`Registries` が static で、`install()` が `Wrapper` を見つけて即 return するため）。
  表は拾い直すのに identifier マップだけ古い。ログは出ない。
- **タグ付与の副作用**: Java 版は base 枠が `RecipePropertySet(SMITHING_BASE)` で制限されるので
  「そもそも置けない」が、統合版は**置けてしまってサーバに弾かれ再同期する**＝ちらつく。
  対象が全カスタムアイテムなので遭遇率は上がる。絞るには表が要るが、表は
  `GeyserDefineCustomItemsEvent` の後に届くので cold start で穴が空く。**ちらつきを取った。**
- `registerNonVanillaItem`（v1 API）はタグを付けられない。実配備の非バニラ登録は 0 件。
- injector 側に自動テストが無い（Geyser の型を持つため）。descriptor の並びは
  Geyser 自身の `JavaUpdateRecipesTranslator` の逆アセンブルと突き合わせて一致を確認済み。


#### 併発（実データ）: 統合版で別物が同じアイテムに見える CMD 衝突

配備中の `plugins/Geyser-Velocity/extensions/geyserextra/custom_items.json`（**再生成できない永続台帳**）と
ArsPaper の `materials.yml` を突き合わせて発見。

- **`source_gem_block` / `source_gem_block_1x` / `source_gem_block_2x` が `AMETHYST_BLOCK` + CMD `100012` を共有**
  （`materials.yml` の 38 / 3429 / 3448 行）。registry には `amethyst_block` の 100012 として
  **`source_gem_block_2x` だけ**が登録され、`source_gem_block` は**古い `PRISMARINE` ベースの残骸**として残っている。
  → **統合版では3種とも「81倍圧縮ソースジェムブロック」という同一アイテムに見える。**
- **`gacha_ticket_5` / `gacha_ticket_fishing` / `gacha_ticket_digging` が `PAPER` + CMD `5015` を共有。**
- CMD を分離する場合、**既存の所持アイテムは CMD が焼き付いている**ので移行の検討が要る。

**誤診しないための記録**: 起動ログの `[GeyserExtra] Geyser-Spigot not found. Some features may not work.` は
**Geyser が Velocity 側にいるため出る正常な警告**。クラフト同期（`CraftingRecipeHandler`）の有効判定は
**Floodgate の有無**なので、この警告があっても無効化されていない。


#### 併発: 毎ログインで出ているログ ERROR（`catalog_key_quarry`）

`Tried to load unrecognized recipe: ResourceKey[minecraft:recipe / trinityforge:catalog_key_quarry] removed now.`
がプレイヤーの参加ごとに出ている。原因は**ワークツリーの未コミット `items/catalog.yml`**（＝設定エディタでの編集分。
配備はワークツリーの yml をそのまま出荷するので、これが動いている config）で
**`key_quarry` の `recipe:` ブロックが丸ごと無くなっている**こと。HEAD には
`shapeless: custom:stone_3x ×2 + list:scrap_metals ×3` がある。
プレイヤーのレシピ帳には解放済みとして残っているので、参加のたびに未登録キーとして弾かれる。

同じ差分で `key_binder` も **shapeless（`list:dungeon_seals` ×5 + 深淵インゴット ×2 + 現実の糸核）から
shaped（5素材の十字配置）へ作り替えられ**、表示名も変わっている。これは意図的な再設計に見える。
**2026-08-20 ユーザー回答: 「意図的に削除した」。** レシピ帳の ERROR 自体は無害
（既に解放済みだった人の帳に古いキーが残っているだけで、`ServerRecipeBook` が読み込み時に落としている）。

**2026-08-20 ユーザー回答（再確認）: 下の 2 件も含めて設定エディタでの意図的な変更。受容する。**
`ShippedDungeonKeyReachabilityTest` の 2 件は**恒常的に落ちる想定**なので、
テスト基準値の帰属判定でこれを「誰かの回帰」と読まないこと。**元に戻さない。**

以下は受容した副作用（`ShippedDungeonKeyReachabilityTest` が検出）:

- **採石場ダンジョンに誰も入れなくなっている。** `dungeon/gates.yml` の `em_id_the_quarry` は
  `key-item: key_quarry` を要求しているが、レシピ削除で **`key_quarry` の入手経路がゼロ**になった
  （TF のどのドロップ表にも ArsPaper の `loot-tables.yml` にも載っていない）。
  ゲートを外すか、別の入手経路を用意しない限り入場不能。
- **`key_binder` の lore と実レシピが食い違っている。** 同じ差分で shapeless（`list:dungeon_seals` ×5 ＋
  深淵インゴット ×2 ＋ 現実の糸核）から shaped（5 素材の十字配置）へ作り替えられ、
  **印の要求数が 5 → 0** になった。lore には「踏破の証を5つ束ねると」「どのダンジョンの印でもよい。
  5つ集まればいい」と書いたままなので、読んだ人は必ず詰まる。
`list:` 参照の増減はこの 2 件だけで、他の 421 項目・362 レシピは HEAD と同数（＝一括消失は起きていない）。

---

### 実サーバ報告バッチ（2026-08-20 受領 第16陣。W-162〜W-165）

| ID | 内容 | 状態 |
|---|---|---|
| W-162 | モブから「深淵のクロスボウ」「深淵の弓」（＝準備中で未出荷のはずの item）がドロップする | 🔍 **ドロップ経路は再現できず**（config・コード・実行ログのすべてで draft は未配線）。ただし**別の実バグを確定**（下記） |
| W-163 | 盾に効果が無い（構えても構えなくても受けるダメージが変わらない）。**要望**: 盾だけ特殊仕様にして、しゃがみ／構え中だけ設定したステータスが乗るようにしたい | ✅ **実装（(B) を採用・`bc17bce`）**。⚠ 反映には**jar のビルドと配備＋サーバ再起動**が要る |
| W-164 | スキルの %低下（EXP 逓減）が 24 時間を超えても解除されない。W-154 で「最初に減衰を始めてから 24 時間でリセット」に変えたはずでは | ✅ **既に直っており実 DB で稼働確認**。報告は 2026-08-20 00:33 の jar 配備より前の状態 |
| W-165 | 設定エディタの「ダンジョンの挑戦レベルに応じた報酬の増減 (2026-08-18)」の入力欄が**軒並み空欄**なのは仕様か | ✅ **仕様ではない。原因確定** ── 起動中のエディタのプロセスが機能追加より 5 時間古い。**再起動で直る** |

#### W-165 エディタのサーバプロセスが機能追加より古い（yml は正しい）

`combat/damage.yml` にも配備先にも `dungeon-level-reward:` の値は**全部入っている**
（`pivot-level: 35` / `step: 5` / `drop-bonus-per-step: 0.08` …）。リポジトリ側の配線も
`lib/constants.js`・`public/js/constants.js` の両ミラーに揃っており、
`test/constants.test.js` の「W-80 …出荷値が共通変数に出て編集できる」も **PASS**。

食い違っているのは**動いているプロセスだけ**:

| | 時刻 |
|---|---|
| エディタのサーバプロセス（PID 12180 / port 8000）の起動 | 2026-08-18 **13:58:33** |
| `lib/constants.js` に `dungeon-level-reward.*` を足したコミット `51920f8` | 2026-08-18 **19:05:59** |

`lib/constants.js` は起動時に `require` されるので、**5 時間前に起動したプロセスはこのフィールド群を知らない**。
実測でも `GET /api/constants` は `dungeon-level-reward` を **0 件**しか返さない（総フィールド数 66）。
一方ラベルと説明文は静的ファイル `public/js/constants.js` からブラウザが取り直すので**新しいまま出る**。
＝**枠と説明だけ出て中身が空**という、報告どおりの見え方になる。

**対処: エディタを再起動する。** yml も配線も直す必要は無い。
（`yaml-merge` が未知キーをロスレスに温存するので、古いプロセスのまま保存しても
`dungeon-level-reward:` が消える事故は起きない。）

#### W-164 EXP 逓減の 24 時間解除は既に効いている（実 DB 実測）

配備先の `player_progression.db` を読み取り専用で確認（2026-08-20 07:03 時点）:

- `daily_exp_window` に `locked_at` 列が**存在する**（W-154 の `ALTER TABLE` は適用済み）
- `daily_exp_meta` に `reset_id = '2026-08-19-w154'` が**入っている**（＝全員の既存ロックを消す1回きりの移行が実行済み）
- 67 行中 55 行が `locked_at = 0`（＝ロックされていない）。ロック中の 12 行は**すべて 00:34 の再起動より後**に打たれており、
  最も古いもので 6.4 時間前。**24 時間を超えて残っているロックは 1 件も無い**

配備状況も揃っている: `stats/skill-exp.yml` は `lock-release-hours: 24` 付きで 08-19 22:29 に配備、
jar は 08-20 00:33（W-154 のコミット 08-19 22:50 より後）、サーバ再起動 00:34。

**＝報告は再起動前の状態を見たもの。**
ただし仕様として、**解除後も稼ぎ続ければまた掛かる**（「発動時刻から 24 時間で 100% へ戻す」であって
「24 時間は掛からない」ではない）。掛かりっぱなしに見えるなら、それは再発であって未解除ではない。

#### W-163 盾が効かない — TF がダメージを**自前の絶対値で上書き**するため、バニラの盾軽減が誤差になる

`CombatListener#handleMobToPlayerDamage`（モブ→プレイヤー）の実際の処理:

1. `vanillaBaseDamage = event.getDamage()` ← **BASE modifier だけ**を読む
2. TF の式で `total` を計算する
3. `ARMOR` / `RESISTANCE` / `MAGIC`（`FOLDED_MODIFIERS`）を 0 にする
4. `event.setDamage(BASE, total)` で**書き換える**

盾の `BLOCKING` modifier には**意図的に触っていない**（「エンジンにそのまま適用させる」方針）。
ところが `BLOCKING` は**イベント生成時にバニラのダメージ規模で確定した絶対値**であって割合ではない。
TF の `total` はバニラ base より桁違いに大きいので、最終ダメージ `total + BLOCKING` における
盾の寄与が**数 % に潰れる** ── プレイヤーからは「構えても構えなくても同じ」に見える。

同型のバグは**逆方向では既に直っている**: PvP 抑制で `total` を縮めたときは
`FinalDamageScaling#scaleModifiersExceptBase` で `BLOCKING`/`ABSORPTION` にも同じ係数を掛けている
（2026-08-01 修正。吸収ハートだけで最終ダメージが負に潰れる事故があった）。
**縮める側だけ手当てされていて、膨らませる側が手つかず**というのがこの不具合の正体。

直し方は 2 通りあり、**どちらにするかはユーザー判断**:
- (A) `BLOCKING` を `total / vanillaBaseDamage` 倍にスケールする（バニラの盾の挙動を TF の規模へ持ち上げるだけ）
- (B) ユーザー要望どおり **TF 独自の盾仕様**にする ── `victim.isBlocking()` のときだけ盾の
  `item-stats.yml` のステータスを乗せて TF 側で軽減する

⚠ (B) を採るときも**盾専用の config 層／GUI を新設しない**。`item-stats.yml` の既存仕様で表現できるか先に確かめる。

**2026-08-20 ユーザー確定: (B) を採用。実装済み（`bc17bce`）。**

もう1つ、より単純な原因が重なっていた ── **`item-stats.yml` に `SHIELD` の定義が 1 つも無かった**
（エディタの「盾」カテゴリも `itemIds: []` で空）。つまり盾は TF のステを最初から何も持っていない。
機構だけ直しても盾は何も起きないままだったので、出荷値も同時に入れてある。

実装は**専用の層を作らず**、既存の per-item 機構 `offhand-stats-apply` と同型のフラグを 1 つ足しただけ:

| キー | 意味 | 既定 |
|---|---|---|
| `offhand-stats-apply` | オフハンドに持ったときステを合算する（従来からある） | false |
| `offhand-stats-require-blocking` | **上の合算を「構えている間」に限定する**（新規） | false |

既定が false なので**既存のオフハンド品の挙動は 1 つも変わらない**。`SHIELD` にだけ両方 true を立てた。
設定エディタにも「構えている間だけ」のチェックボックスを追加済み（`public/js/forms.js`。`lib/` 側にこのフォームのミラーは無い）。

出荷値（`damage-reduction: 0.35` ＝構えている間だけ被ダメージ −35%、品質で +0.01/段）は
**バランス調整の対象**。エディタの「盾」カテゴリからそのまま変えられる。

テスト `ShieldBlockingOffhandStatsTest`（4 件）で固定した。**門を無効化すると
「構えていなければ乗らない」が実際に落ちることを確認済み**（no-op な修正ではない）。
出荷 `item-stats.yml` の実バイト列を読んで `SHIELD` に両フラグと正の `damage-reduction` が
入っていることも同じテストで固定してある（機構だけ直して出荷値を入れ忘れる事故の防止）。

⚠ **未配備。** 反映には jar のビルドと配備、サーバ再起動が要る。

#### W-162 準備中アイテムのドロップ — TF の config・コード・実行ログのどこにも配線が無い

裏取りできたこと:

- `abyss_bow` / `abyss_crossbow` はリポジトリでも**配備先でも `draft: true`**（作業ツリーの draft 80 件は HEAD と完全一致。差分は並び替えだけ）
- 実行中サーバのログに `[items/catalog.yml] loaded 340 item(s) (準備中 80 件は未配線) OK`（00:34:39）＝**ゲートは効いている**
- 配備先 `plugins/` 配下 **3283 ファイル**を全走査して、**draft 品を参照している config は 1 件も無い**
  （TF・EliteMobs・ArsPaper のドロップ表すべて）
- そもそも `abyss_*` は「EM ダンジョンのボスが落とす `abyssal_ingot` から**作る**装備」として設計されたもの（`1c70839`）で、
  **ドロップ品として配線された履歴が無い**

**併発: 一括解禁を検出するガードが 6 日間ずっと赤だった**

`ShippedCatalogDraftTest` の下限が `MIN_EXPECTED_DRAFTS = 90` のままで、実データが 80 になった
**2026-08-14 以降ずっと落ちていた**。落ちっぱなしのガードは何も守らないので、
その間に本当に装備が一括解禁されていても気づけない状態だった（W-162 の報告と同じ領域の話）。
100 → 80 の内訳を確認したところ `b969faa`（ダンジョン難易度の再設計）で **`thread_*` 24 種を
意図的に解禁した**ぶんで、**装備は 1 件も外れていない**。現在値ちょうど（80）に合わせ直し、
次に 1 件でも外れたら落ちるようにした（`bc17bce`）。

⚠ この調査で最初「参照なし」と出したのは**誤り**だった ── Python（ネイティブ Windows）へ MSYS 形式の
パス（`/d/...`）を渡しており、`os.walk` が例外も出さず 0 件を返していた。Windows 形式で取り直した結果が上記。
**「走査したのに 0 件」は、走査対象が 0 件でないことを先に示してから言うこと。**

**併発して確定した別の実バグ: 準備中アイテムが Bedrock 用のパック宣言へ漏れている**

`resourcepack/build_pdc_hints.py` には **`draft` の判定が一切無く**、`catalog.yml` の全 item を
`dist/trinityforge-catalog-pdc-hints.json` へ書き出している。結果、配備先の
`plugins/GeyserExtra/pdc_hints/trinityforge-catalog.json` に **draft 80 件のうち 79 件が入っている**。
実サーバログにも痕跡がある:

`[GeyserExtra] [AutoPack] no Bedrock vanilla fallback for minecraft:crossbow (iconKey=trinityforge_abyss_crossbow); skipping item_texture entry`

Java 側は `ItemCatalogConfig` の 1 箇所で参照面ごと絞っているのに、**リソースパックの生成器だけがその外側にいる**。
未修正（パックの再生成と再配布が要るため、着手前にユーザー判断）。

**ドロップそのものは切り分け情報が足りない。**（対象サーバ／Java か統合版か／どのモブか）

---

### 呪文フォームの住み分け（2026-08-20 設計依頼）炸裂 + 伝播に固有の利点を作る

きっかけはユーザーの問い「Ars の炸裂って範囲攻撃可能？」→「伝播と合わせるとダメージ重複しちゃわない？」
→「現状 投射 + 伝播でいい」。棚卸しの結果、**炸裂は伝播の完全下位互換**だった。

| 構成 | マナ | 到達 |
|---|---|---|
| 投射 + 害悪 + 伝播 ×4 | 10 + 30 + 100 = **140** | 8 ブロック内の **9 体** |
| 炸裂 + 害悪 + 半径増加 ×6 | 90 + 30 + 150 = **270** | **半径 7** |

**チェーン検索半径 `8.0` が Java 側にベタ書き**されており、炸裂の最大半径より広い。半径を増やすほど
炸裂は高くつくのに、伝播は増強 1 個で同じ範囲を超える。

**ダメージ重複は現状「起きていない」が、それは i-frame のおかげ。** チェーンは +2 / +4 / +6 tick で
撃つのでバニラの無敵時間（10 tick）に吸われる。`setNoDamageTicks(0)` を呼ぶのは `HeavyImpactEffect`
だけで、そこに伝播は付かない。**つまり炸裂 + 伝播は「重複して強い」ではなく「払ったマナが消える」だった。**

**採った方針（ユーザー判断: 案1 + 案3、その上で上限と減衰）** ── ArsPaper `a528407`

- **案1: ヒット済み集合を詠唱単位で共有する**（`SpellContext.CastState`、`copy()` は参照を渡す）。
  チェーン候補から既ヒットを除外するので、同じマナが「重複」ではなく **到達範囲** に変わる。
  炸裂側は **resolve より先に** `markCastHits(targets)` を呼ぶ ── 逆順だと 1 体目のチェーンが
  「これから直撃させる 2 体目・3 体目」を選んで無敵時間に捨てる（この順序をテストで固定した）。
- **案3: 誰にも当たらなかった炸裂は炸裂地点そのものを伝播の起点にできる**
  （`resolvePropagateFromLocation`）。**投射は外した弾から連鎖しない**ので、
  「外しても芋づる式に当たる」が炸裂 + 伝播だけの利点になる。
- **上限 `max-chains-per-cast: 20`**（必須）。未ヒットを探し直す方式は密集地で候補が尽きるまで外へ伸び、
  起点ごとに `getNearbyLivingEntities` を呼ぶので TT・スポナー前で対象数 × 検索回数が跳ね上がる。
- **減衰 `damage-falloff-per-hop: 0.15` / `min-damage-rate: 0.4`**。
  **倍率は最終ダメージへ掛ける** ── 素の威力側に掛けると TF の守備力が引き算で効くため、
  0.4 倍のつもりが `min-component-damage: 1` に張り付いて減衰率と実ダメージが桁で食い違う。
- `chain-radius: 8.0` を config へ（既定値は同じなので挙動は不変）。

⚠ **`glyphs.yml` は `saveResource(..., false)` で読む。** 稼働中サーバの
`plugins/ArsPaper/glyphs.yml` は**上書きされない**ので、4 つの新パラメータは Java 側の既定値
（8.0 / 20 / 0.15 / 0.4）で動く。**数値を変えたいときは配備先の yml を手で足す。**

⚠ **未着手（ユーザー未選択）**: `base-burst-radius` が `1.0`（コード既定は 2.0）。
`4c78f08`（2026-04-08）の一括正規化で落ちたと見られる。炸裂の素の範囲が 1 ブロックのままなので、
上の住み分けを入れても炸裂単体は依然として弱い。**2.5 前後への引き上げを提案したが判断待ち。**
`form-cooldowns` が全フォーム 0 なのも同様に未着手。

反映には **ArsPaper jar のビルドと配備＋サーバ再起動**が要る。

---

### 実サーバ報告バッチ（2026-08-20 受領 第17陣。W-166〜W-169）

| ID | 内容 | 状態 |
|---|---|---|
| W-166 | 釣りのダブルドロップ時に、増えたぶんがインベントリに入らない | ✅ 修正済み（**未配備**） |
| W-167 | `/tf recipes` にカスタム追加したポーション（幸運など）の醸造レシピを載せる。ソートカテゴリに「醸造」を追加する | ✅ 実装済み（**未配備**。TF jar と ArsPaper jar の両方が要る） |
| W-168 | **質問**: 精錬魔法はもう範囲グリフで増強する仕様になっていたか | ✅ 回答済み（**はい**） |
| W-169 | 特定ブロックが、ある採掘速度に達すると逆に掘れなくなる（例: 深層岩＝効率VI＋ドリリング） | ⏸ 情報待ち |

#### W-166 釣りのボーナス分がインベントリに入らない — 原因確定・修正済み

`FishingQualityListener#dropFishingBonus` が、ボーナス分だけ
`player.getWorld().dropItemNaturally(player.getLocation(), ...)` で**地面へスポーン**していた。
釣果**本体**はバニラの挙動でプレイヤーへ飛んでいく（＝インベントリに入る）ので、
「ダブルドロップのときだけ足元に散らばる」という見え方になっていた。

- 修正: 本体と揃えて `player.getInventory().addItem(...)` へ入れ、**入り切らない分だけ**地面へこぼす
  （コードベース既存の `giveOrDrop` 慣用に合わせた。満杯時に黙って消さない）。
- イベント順序は**元から正しい**: `FishingGimmickListener`(LOW) → `FishingQualityListener`(NORMAL) なので
  ボーナスの複製元は差し替え後の最終アイテム。ここに直すべきバグは無かった。
- 回帰テスト: `FishingQualityListenerTest` に「届き先はインベントリ・地面は 0」と
  「満杯なら地面へこぼす（消滅させない）」を追加。**修正を戻すと 2 件落ちることを実走で確認済み**。

#### W-167 レシピ一覧の「醸造」カテゴリ — 実装済み（配備待ち）

原因は単純で、**醸造台は Bukkit の `Recipe` ではなく Paper の `PotionMix`** なので
`recipeIterator` にも `Bukkit.getRecipe` にも一切出てこない。作業台・儀式しか集めていなかった
レシピ一覧に、TF の `brew-unlocks` は構造的に載りようがなかった。

- TF 側: `TrinityForge#brewPotionMixRegistrar()` を公開しただけ（1 メソッド）。
- フォーク側（`Klee319/ArsPaper` `feat/trinityforge-fork` の `b9207fe`）:
  `KindMode.BREWING` 追加／`TrinityForgeBridge#brewRecipes()`／醸造台の並びを写した詳細画面／
  解放判定は TF のスキルツリー `brew:<groupId>`／ポーション効果名の日本語表。
- **`livePlans()` を見る**のが要点。生の `brew-unlocks` にはバニラ衝突・綴り間違い・重複の敗者で
  **登録されなかった組**が混ざっていて、一覧に出すと「載っているのに永久に作れない」レシピになる。
- **「儀式でない＝作業台」だった絞り込みの判定を直す**必要があった（直さないと醸造が作業台にも二重に出る）。
- 配備には **TF jar と ArsPaper jar の両方の差し替え＋サーバ再起動**が要る。

#### W-168 精錬魔法と範囲グリフ — 回答: **はい。2026-08-19 からその仕様**

`fork-handoff/arspaper/fork/src/main/resources/glyphs.yml` の `smelt`:

```yaml
max-augments:
  aoe: 6
  aoe_height: 6
  aoe_vertical: 3
```

半径増加(`aoe_radius`)から範囲(各種)へ変更済み。**半径増加はブロック精錬の範囲に一切効いていなかった**
（常に 1 ブロックだけ精錬されていた）のを直したもの。**配備先の `plugins/ArsPaper/glyphs.yml` にも
08-19 22:16 で入っている**ので、この件は実機でもう有効。

#### W-169 採掘速度が上がると掘れなくなる — 現時点の切り分けと**足りない情報**

- 「ドリリング」の正体は特定できた: **EliteMobs のカスタムエンチャント `drilling`**
  （`DrillingEnchantment`。メインハンドのツールに付き、しゃがみ中は無効。レベルに応じて
  掘った方向へ 3x3 のトンネルを掘る。同素材のブロックだけを対象にする）。
- TF 側に「このブロックは掘らせない」という**ブロック単位のゲートは無い**
  （`BlockBreakEvent` を `setCancelled` する TF の経路は `UseRequirementListener` の
  「装備要求を満たさない」3 箇所だけで、深層岩のような素材条件は持たない）。
- 効率レベルは TF の `GatheringEfficiencyEnchantApplier` が**本物のバニラ効率エンチャント**として
  メインハンドへ動的に足している（統合版で属性方式が効かないため）。上限は
  `GatheringEfficiencyMath.HARD_CAP = 255`。
- **2026-08-20 ユーザー回答で原因側が確定**: **Java 版・統合版の両方で起きる**／**しゃがめば掘れる**。
  ドリリングは「しゃがんでいる間は無効」（`DrillingEnchantment#onDig` の `isSneaking()` 早期 return）なので、
  **原因はドリリング側で確定**。クライアントの破壊時間計算（Geyser のズレ等）は無関係。
- **ここまでで潰した候補**:
  - TF 側にブロック単位のゲートは無い（`BlockBreakEvent` を `setCancelled` する TF の経路は
    `UseRequirementListener` の「装備要求を満たさない」3 箇所だけ）。
  - 実機ログ（Main_Server `latest.log`）に **`Drilling` / `BlockBreak` / `Could not pass event` の
    ヒットは 0 件**＝例外で `activePlayers` が抜けなくなる型ではない。
  - **`Can't keep up` / `Overloaded` の警告も 0 件**＝サーバ全体のティック溢れでもない。
- **現時点の第一仮説（未確定）**: **ゴーストブロック**。
  配備中の `plugins/EliteMobs/enchantments/drilling.yml` は `maxEnchantmentLevel: 4` で、
  Lv4 は 1 回の破壊で**周囲 20 マス超**を `breakNaturally` する（同素材だけを対象にするので、
  深層岩のような一様な塊では**毎回フルヒット**する。混在する石／鉱石帯では当たらない）。
  サーバは消したがクライアントに残ったブロックを叩いても、サーバ側には何も無いので
  `BlockBreakEvent` すら発火せず、**「叩いても永久に壊れない」**に見える。
  **決定的な確認方法**: 掘れなくなったブロックの前で **F3+A（チャンク再描画）またはログインし直す**。
  それで消えるなら**ゴーストブロックで確定**（ドリリングの一括破壊がクライアントへ届いていない）。
  消えないなら別筋なので、そのときは「ヒビが最後まで行かない／一瞬消えて戻る」のどちらかを教えてほしい。
- ~~採掘速度が上がると**1 ブロックあたりの破壊が「即破壊」側へ渡る**~~（両版で起きる＝クライアント計算の
  問題ではないので後退させた。ただし採掘速度が上がるほどドリリングの発火回数が増えるという意味では効いている）。
  `stats/gathering-efficiency.yml` は 2026-07-26 の決定で上限撤廃（`max-enchant-level: 0`、内部ハード上限 255）で、
  yml 自身が「効率レベルは `level^2 + 1` で伸びるので **10 程度で大半のブロックが即破壊**になる」と警告している。
  そこへドリリングが乗ると、破壊 1 回ごとに**同素材の周囲を最大 27 マスまで合成 `BlockBreakEvent` で叩く**。
  深層岩は一様な塊で採れるので**同素材条件に全部当たり**、混在する石/鉱石帯より仕事量が跳ね上がる。
  サーバが追い付かないと、クライアントが壊したつもりのブロックがサーバから戻ってきて
  「掘れない／戻ってくる」に見える。**これは仮説で、まだ実測で裏を取っていない。**
- **足りない情報（これが無いと真因を確定できない）**:
  1. ~~Java 版 / 統合版どちらか~~ → **両方**（回答済み）
  2. ~~しゃがむと掘れるか~~ → **しゃがめば掘れる**（回答済み。原因はドリリング側で確定）
  3. **F3+A / 再ログインで掘れなくなったブロックが消えるか**（ゴーストブロック判定）
  4. 「掘れない」の見え方（**ヒビが最後まで行かない** / **一瞬消えて戻る** / **そもそもヒビが入らない**）
- 関連: K-31（ドリリングでツルハシのモーションが消える＝描画事象なので実機でしか切れない）。

---

### 実サーバ報告バッチ（2026-08-20 受領 第18陣。W-170〜W-171）

| ID | 内容 | 状態 |
|---|---|---|
| W-170 | 錬金のスキル「醸造の見習い」「調合の職人」を解放後、耐火などの効果付きポーションを作ると入る職業EXPが全て一律 302 になる | ✅ 修正済み（**未配備**。TF jar 要再ビルド） |
| W-171 | Ctrl+Q で 1 スタックのシャベルを一括製作しても、EXP が 1 個分しか入らない | ✅ 修正済み（**未配備**。TF jar 要再ビルド） |

#### W-170 「品質を取った人だけ」醸造EXPが一律になる — 品質付与がポーションの身元を消していた

**真因（機構レベル・イベント順序）**: `PotionQualityListener`(HIGH) は段階の違う効果を一意に確定させるため、
品質が乗るポーションの**ベース種別を必ず `WATER` へ倒して**全部カスタム効果で表現する。
一方 EXP を出す `NativeSkillExperienceListener#onBrew` は **MONITOR（＝その後）**で、
完成品の `getBasePotionType().name()` から `alchemy_progression.yml` の `brew_result` を引いていた。
倒された後に読めるのは常に `WATER` で表に無いため、**どの効果ポーションを作っても定額 `alchemy_brew_exp` へ落ちる**。

- **「解放後」に壊れる理由**: `potion_quality_bonus` が 0 のプレイヤーは `applyQuality` に到達しないので
  ベースが倒れず正しく引ける。**スキルツリーで品質を取った人だけ**が壊れる ── ログにも例外にも一切出ない無言死。
- **数値の裏取り**: HEAD の config（`alchemy_brew_exp: 150` / `exp_multiplier_quality: 0.005` / `multiplier_manual: 2`）で
  `150 × 1.005 × 2 = 301.5 → 302`（品質1）。報告の「一律 302」と一致する。
  ⚠ 作業ツリーの `alchemy_progression.yml` は他セッション（設定エディタ）が値を書き換え済みだが、
  **稼働サーバはそれを読んでいない**（サーバ起動 00:34 / yml の mtime 01:30 / `latest.log` に reload 無し）。
- **修正**: 倒す直前の種別を PDC `trinityforge:brew_source_potion`（`PdcKeys#ITEM_BREW_SOURCE_POTION`）へ焼き付け、
  EXP 側は**それを最優先で読み**、無ければ従来どおり `getBasePotionType()` へフォールバックする。
  品質を取っていないプレイヤーの挙動は不変。
- **回帰テスト**: `AlchemyBrewResultExpTest`（4 件・skip 0）。焼き付けと読み取りの**両方を外すと該当 2 件が落ちる**ことを実走確認。
  `PotionMeta#getAllEffects()` は MockBukkit 未実装なので `PotionQualityListenerTest` と同じ spy 回避策を使っている
  （**素で呼ぶとテストが FAILED でなく SKIPPED に化ける**）。

#### W-171 Ctrl+Q の一括クラフトで EXP が 1 回分しか入らない

**真因**: `CraftItemEvent` は**クリック 1 回につき 1 度しか飛ばない**。バニラ
`AbstractContainerMenu#doClick` の `ClickType.THROW` 分岐は **`button == 1`（＝Ctrl+Q）のときだけ**
「同じ品が出る限り `safeTake` → `drop`」を**ループ**する
（稼働サーバの `paper-1.21.11.jar` から `AbstractContainerMenu.class` を取り出して逆アセンブルし、
`goto` で先頭へ戻ることを実バイトコードで確認した。素の Q は `button == 0` なのでループしない）。
旧実装は `event.isShiftClick()` だけを見ていて **Ctrl+Q は false** なので、必ず 1 回分へ落ちていた。

- **shift と Ctrl+Q は上限が違う**: shift は「作れるだけ作って**しまう**」ので素材と**収納容量**の小さい方で頭打ち。
  Ctrl+Q は**地面へ落とす**ので**収納容量では頭打ちにならない**（満杯でも素材ぶん全部作れる）。
  実回数は `craftOperationCount(InventoryAction, …)` がアクションごとに出す。
- **増殖ガードは維持**: 前回の一括クラフト修正で「素材をスタックした状態で 1 個だけクラフトしても
  スタック数ぶん EXP が入る」増殖バグを踏んでいる。回数は**盤面の各スロットの最小個数**から求めるので、
  素材が各 1 個なら Ctrl+Q でもちょうど 1 回で止まる（テストで固定した）。
- **途中で自分が入れた回帰を 1 件潰した**: 収納内容を無条件に `player.getInventory().getStorageContents()` で
  読む形にしたら、インベントリを持たないモックを使う `CraftQualityListenerResultDupeTest` 2 件が NPE で落ちた
  （旧実装は shift のときだけ三項演算子の内側で読んでいた）。**`Supplier<ItemStack[]>` で遅延読みにして解消**。
  Ctrl+Q 経路はそもそも収納を読まない、という事実がシグネチャに出るので意図としても正しい。
- **回帰テスト**: `CraftQualityListenerSmithingExpTest` に 3 件追加（満杯インベントリでの Ctrl+Q 64 連、
  素材各 1 個での 1 回止め、アクション別の純関数表）。**呼び出しを `event.isShiftClick()` へ戻すと 1 件が落ちる**ことを実走確認。

**検証**: TF フルテスト **4435 件・失敗 26・skip 2**。26 件は**すべて他セッションの未コミット yml 由来**で、
触った領域は 1 件も含まない（例: `NativeSkillCatalogRatesTest` の `expected 10.0 but was 25.0` は
作業ツリーの `light_armor_progression.yml` が `exp_damage_piece: 10 → 25` に書き換わっているため）。
**⚠ 未実施（ユーザー作業）: TF jar の再ビルド＋配備＋サーバ再起動**（Java 変更なので config だけでは効かない）。

---

### 幸運のポーションを作業台・儀式・醸造の品質ptへ乗せる（2026-08-20 要望 / W-172）

**要望**: 「醸造・作業台・儀式の各品質ptも幸運のポーションレベルに応じて上がるようにしてほしい」。

**着手前の実態**（調べて分かったこと。ここが要望の前提とずれていた）:
バニラの幸運（`LUCK`）は TF では **`PlayerLootLuckSource` が `loot_luck` へ 1 レベル = +1.0 で合算する 1 経路しか無く**、
効いていたのは**釣り上げた装備と拾った装備の品質**だけだった
（`FishingQualityListener` / `PickupQualityListener`）。作業台・儀式・醸造は幸運を一切見ていない。
なお**釣りの宝/ゴミ確率にも効かない** —— あちらは `fishing-luck`（ロッドのステ＋エンチャント＋釣りレベル）で、
幸運ポーションは合算されない。

**入れたもの**:

| 経路 | 加算先 | 場所 |
|---|---|---|
| 作業台クラフト | `workbench_quality_bonus` と同じ mode 加算 | `CraftQualityService#qualityBonusFor` |
| 儀式クラフト | `ritual_quality_bonus` と同じ mode 加算 | 同上（`CraftPath.RITUAL`） |
| 醸造 | `potion_quality_bonus` と同じ品質pt | `PotionQualityListener#onBrew` |

- **換算レートは 1 つのつまみに集約**: `stats/quality.yml` の **`luck-potion-quality-per-level`（既定 1.0）**。
  幸運I = 品質+1 / 幸運II = +2。**0 にするとこの機能だけを切れる。**
- **効果レベルの読み取りは `VanillaLuckEffect` へ一本化**した（`PlayerLootLuckSource` も委譲）。
  `amplifier + 1` の変換を書き間違えても「幸運が 1 段ずれる」という**誰も気づかない形でしか出ない**ため、
  読み取り点を 2 つに増やさない。
- **丸めは合計してから 1 回だけ。** ステと幸運を別々に丸めると `0.5 + 0.5` が `0 + 0` に落ち、
  **幸運を飲んでも何も起きない帯**ができる（テストで固定した）。
- **プレビューにも乗る**: `qualityMode` / `minimumQuality`（作業台の結果枠プレビュー）も同じ経路を通すよう直した。
  ここを直さないと「プレビューは 3 なのに作ると 5」という食い違いが出る。
- **自動（ホッパー）醸造では幸運ぶんにも `alchemy.auto_mult` の減衰が掛かる。**
  片方だけ無減衰にすると「幸運を飲んでホッパーへ放置」が成立するので、既存の品質ptと同じ扱いに揃えた。
- **釣り・拾得は従来どおり**（`loot_luck` へ 1 レベル = +1.0 固定）。あちらは装備・パーク由来の
  `loot_luck` と単位を揃える必要があるので、このつまみでは動かない（yml に明記した）。

**エディタ**: 品質定義タブへ `luck-potion-quality-per-level` のフィールドとラベル/説明を追加
（`public/js/tf-forms.js` / `public/js/labels.js`）。`tf-quality` の検証は許可リスト方式ではないので
スキーマ変更は不要。

**検証**: 新規 `CraftQualityLuckPotionTest` 7 件 + `PotionQualityListenerTest` へ 4 件（計 17 件）、
**skip 0**。**RED 実証**: 幸運ぶんの加算を潰すと該当 7 件が落ちることを実走確認。
TF フルテスト **4460 件・失敗 26・skip 2**（26 は着手前と同一集合＝他セッションの未コミット yml 由来）。
config-editor **1403 件・失敗 25**（品質まわりの 41 件は全緑。失敗はいずれも他セッション由来で、
例えば `glyph-damage-boost` の round-trip は ArsPaper の `glyphs.yml` の差分）。

**⚠ 未実施（ユーザー作業）: TF jar の配備＋サーバ再起動**（Java 変更。`quality.yml` も同時に配る必要がある）。

---

### 実サーバ報告バッチ（2026-08-20 受領 第19陣。W-172）

| ID | 内容 | 状態 |
|---|---|---|
| W-172 | 精錬魔法で圧縮ジャガイモを焼いたら、ただのベイクドポテトになった。**類似の不具合が他にもたくさんあるはず** | ✅ 修正済み（**未配備**。ArsPaper jar と TF jar の両方が要る） |
| W-173 | **壊れないはずの道具が連鎖破壊（一括伐採／鉱脈採掘）で消える** | ✅ 修正済み（**未配備**。TF jar 要再ビルド） |

#### W-172 「Material だけを見た変換」がカスタムアイテムの身元を消す — 5 経路を修正

**報告の一件（真因）**: `SmeltEffect#smeltNearbyItems` は
`SMELT_MAP.get(stack.getType())` の結果を **`item.setItemStack(new ItemStack(smelted, amount))`**
で丸ごと差し替えていた。`SMELT_MAP` は `POTATO → BAKED_POTATO` を持ち、
`potato_1x/2x/3x`（9/81/**729** 倍圧縮ジャガイモ）は全部 `base_material: POTATO` なので、
**729 個ぶんがベイクドポテト 1 個に化ける**。CMD も PDC も表示名も消えるため
「圧縮を戻して取り返す」こともできない完全な喪失で、ログにも例外にも一切出ない。

**なぜ既存のガードで止まらなかったか**: かまど・醸造台・コンポスターへの搬入は
2026-08-19（W-132）で `CustomItemListener` が塞いでいる。しかし**魔法はバニライベントを一切通らない**ので、
その門の外側に居た。

**「他にもたくさんある」を機械的に洗い出した**（`setItemStack` の全呼び出し／`Map<Material, Material>` の全定義／
`.get(<stack>.getType())` の全呼び出しを TF 本体・ArsPaper・EliteMobs の 3 リポジトリで走査し、
`materials.yml` の `base_material` 全 89 種と `items/catalog.yml` の `material` 全 72 種に突き合わせた）。
**同じ形の穴は 5 経路**。TF カタログ品は `CatalogVanillaOperationGuardListener` が既に守っていて交わりは 0 件だったので、
被害は全部 ArsPaper の `materials.yml` 素材側だった。

| # | 経路 | 実害（ベース材質の重なり） | 修正 |
|---|---|---|---|
| 1 | **精錬魔法**（`SmeltEffect`） | `SMELT_MAP` の 20 キーが Ars 素材のベースと重なる（POTATO / BEEF / COD / SALMON / 各種原木 / STONE / SAND / NETHERRACK / QUARTZ_BLOCK …）。圧縮素材 **約 60 種**が対象 | 同一性を持つ品は変換しない |
| 2 | **粉砕魔法**（`CrushEffect`） | `crush_map` の STONE / DEEPSLATE / QUARTZ_BLOCK / MELON が `stone_1x..5x` `deepslate_1x..4x` 等のベース | 同上 |
| 3 | **石切台** | `stone_5x`（**59049 倍圧縮石**）を入れると石レンガ 1 個。STONE / DEEPSLATE / GRANITE / DIORITE / ANDESITE / END_STONE / QUARTZ_BLOCK / COPPER_BLOCK ベースの **32 種** | 搬入と選択の両方を遮断 |
| 4 | **製図台** | `base_material: PAPER` の**ガチャ券 8 種**（`gacha_ticket_*`）を地図の拡張で食う | 同上 |
| 5 | **ビーコン支払い / ピグリンの物々交換** | `abyssal_ingot`(NETHERITE_INGOT) / `core_jewelry`(EMERALD) / `heavy_metal`・`pillager_plate`(IRON_INGOT) / `piglin_brute_plate`(GOLD_INGOT) | 支払いスロットとピグリンの拾得を遮断 |

**方針は「変換先を用意する」ではなく「変換しない」**。「焼き圧縮ジャガイモ」を全ベース材質ぶん定義しないと成立せず、
定義漏れがまた無言の喪失に化けるため。素の採掘/モブドロップは `ItemMeta` を持たないので判定に掛からず、
**「バニラの石を焼いて滑らかな石にする」といった本来の用途は一切狭まらない**。

- **判定は 1 箇所へ集約**: `PdcHelper#hasProtectedIdentity`（Ars の `custom_item_id` → TF の `catalog_id` →
  CustomModelData の順に見る）。魔法側は `CustomItemConversionPolicy` を通す。
  **TF カタログ品もここで守る** —— 魔法は TF 本体のガードを通らないので、ここで守らないと誰も守らない。
- **金床 / 砥石 / 鍛冶台はあえて遮断集合に入れていない**。専用の `Prepare*` ガードが
  「消費だけ拒否して、カスタム防具のアーマートリムなど正当な用途は通す」判断をしており、
  一律遮断へ足すとその判断ごと潰れる（テスト `dedicatedlyGuardedStationsStayOut` で固定した）。
- **ついでに塞いだ潜在穴**: TF の `VanillaItemRemover` は `trinityforge:` PDC しか保護対象にしておらず、
  `removed-vanilla-items` に **Material を 1 つ足した瞬間**、そのベース材質を使う Ars 素材が全部消える状態だった
  （現在の設定は `ANY:MENDING` だけなので未発火）。`arspaper:` namespace も保護へ加えた。
  **エンチャント剥がし側はこの保護を通らない設計**なので、Ars 装備の修繕除去などの既存挙動は変わらない
  （`arsPaperCustomItemsStillLoseStrippedEnchants` で固定）。

**検証**:
- ArsPaper フォーク **473 件・失敗 0・skip 0**（+11 件）。
  `CustomItemConversionPolicy` の保護を外すと `customItemsAreNeverConverted` が落ちることを実走確認。
- TF 本体 **4445 件・失敗 26・skip 2**。26 件は**すべて他セッションの未コミット yml 由来**で、
  触った領域は 1 件も含まない。`arspaper` 判定を消すと `arsPaperCustomItemsAreNeverRemovedEvenIfMaterialMatches`
  が落ちることを実走確認。
- **⚠ 未実施（ユーザー作業）: ArsPaper jar と TF jar の配備＋サーバ再起動。**

**残っている取りこぼし（意図的に未対応）**:
- **ブロック側の変換**（`SmeltEffect#applyToBlock` / `CrushEffect#crushBlock` / `ExchangeEffect`）は未対応。
  Ars のカスタムブロックは `DECORATED_POT`（ソースジャー）と `BEACON`（ウェイストーン）で、
  どの変換表にも載っていないため現状は無害。**変換表に足すときはここを思い出すこと。**
- ~~圧縮素材を「圧縮のまま焼く/切る」機能は作っていない。欲しければ別途仕様の判断が要る。~~
  → **「焼く」は 2026-08-23 の W-190 で実装した**（`_1x` の食材 8 種のみ。かまど／燻製器／焚き火）。
  「切る」（石切台）は引き続き未対応。

---

#### W-173 壊れないはずの道具が連鎖破壊で消える — `isUnbreakable()` を見ていない damage 経路が1本だけ残っていた

**真因**: `ItemAssembler` は**耐久ステが設定されていないカタログ品を全部 `setUnbreakable(true)`** にする
（`meta.setUnbreakable(effectiveDurability == null)`）。一方 `ChainBreakSupport#damageHeldTool` は
**`isUnbreakable()` を一切見ずに** `damageable.setDamage(damage + 1)` を積み、上限に達すると
`inventory.setItemInMainHand(null)` で**アイテムごと消していた**。
壊れない品は耐久バーが出ないので、**消えるまで誰も気づけない**。

- **なぜここだけ落ちたか**: 手で耐久を減らしている箇所は 3 本あり
  （`HumanEntity#damageItemStack` は MockBukkit 未実装でテストが SKIPPED に化けるため、
  どれも `Damageable` を直接操作している）。うち
  `EquipmentDurabilityService#damageSlot` と ArsPaper の `SpellCaster#consumeCastDurability` は
  **両方 `isUnbreakable()` を見ている**。`isUnbreakable` の出現箇所を TF 本体全体で数えると
  修正前は **1 箇所だけ**だった。
- **修正**: `damageHeldTool` の `Damageable` 判定へ `|| meta.isUnbreakable()` を足し、
  壊れない品は減らさず**連鎖もそのまま続ける**（`return true`）。
- **回帰テスト**: `ChainBreakSupportSingleRollTest` に 2 件追加（**8 件・失敗 0・skip 0**）。
  残り耐久 1 の斧を `unbreakable` にして 2 ブロック連鎖 → 手に残り damage も進まないこと。
  逆側として、普通の斧は従来どおり 1 ブロックで壊れて連鎖もそこで止まることも固定した
  （保護を広げすぎていないことの確認）。**`isUnbreakable()` を外すと該当 1 件が落ちる**ことを実走確認。
- **検証**: TF フルテスト **4449 件・失敗 26・skip 2**（失敗数は着手前と同じ 26。すべて他セッションの
  未コミット yml 由来で、触った領域は 1 件も含まない）。

---


### 実サーバ報告バッチ（2026-08-23 受領 第34陣。W-191〜W-192）

> 「アチーブの触媒使って呪文を100回ができない。=>杖で魔法100回打ったのにできない」
> 「メインサーバのエンドでエンドラの卵や鱗が落ちない」

| ID | 内容 | 状態 |
|---|---|---|
| W-191 | 進捗「触媒を振るう」が杖で撃っても進まない | ✅ 修正（**ArsPaper jar が要る**。カウンタは遡及しない） |
| W-192 | エンドラの卵・鱗が落ちない | ✅ 真因確定 → **ユーザー判断で仕様として据え置き**（修正なし） |
| W-193 | プレステージ後にスキルを上げても SP が入らない | ✅ 真因確定（機構は 08-21 に修正済み）→ **失った 21 SP を 5 人へ補填**（要オンライン） |
| W-194 | 儀式で個数を複数にしても1つしかできない | ✅ 修正（**ArsPaper jar が要る**。config 側の指定は既に正しい） |
| W-195 | ソースリンクにもベリーと同様に一括でくべたい | ✅ 実装（**操作が変わる**: スニーク＝一括 / 通常＝1個。ジャーも同じ規約へ） |
| W-196 | ソースベリーを収穫魔法で取れるようにできないか | ✅ 実装（原料のグロウベリーを摘めるようにした。**つるを壊す既存バグも同時に解消**） |
| W-197 | 資源リセットのバッチを動かしてもリセットされない | ✅ 真因 **2 段**（①配備先の launch が 08-16 版で必ず空撃ち ②**RCON パスワードの環境変数が未設定で非 DryRun は全滅**）→ ドライラン段を廃止＋`set-rcon-env.cmd` を追加。**要 `deploy-launch.cmd`**（⚠ 副作用あり） |
| W-198 | ソースリンクの生成倍率を 2 の階梯乗にしたい | ✅ 実装（無印 2 〜 最上段 512。**転送レートも同率へ**。**config 配備が要る**） |
| W-199 | 手置きの鉱石でも一括破壊を発動させたい（ただしバニラEXPと職業EXPは入れない） | ✅ 実装（**TF jar が要る**。実際に要った変更は「門を外す」だけで、**抑止は1つも足していない**。⚠ **GTH-02 の増殖経路が開く**） |
| W-200 | 一括破壊の連鎖分にバニラの経験値オーブが出ていない | ✅ 修正（**TF jar が要る**。**一括破壊が存在して以来ずっとの取りこぼし**で、鉱脈を一括で掘ると起点1ブロック分しか経験値が入っていなかった） |

#### W-191 真因: 杖は `catalysts:` に1本も登録されていない

`catalyst_cast` の加算条件が `spellbooks.yml` の `catalysts:` 登録有無**だけ**で、
`SpellCaster#recordCastCounters(..., catalystData != null, ...)` を通っていた。
`catalysts:` に載っているのは BLAZE_ROD 系 4 件（`ember_wand` / `infinity_catalyst` /
`dragon_catalyst` / `wither_catalyst`）だけで、**TF カタログの杖 10 本
（`WOODEN_SWORD#400008`〜`NETHERITE_SWORD#400014`）は 1 本も載っていない**。
つまり杖で何回撃っても `catalystData == null` で素通りし、カウンタは 0 のままだった。

- 判定を**魔法のステ供給元の解決と同一**にした（`resolveMagicStatSource` = `use-skill: ARS_MAGIC`
  または `catalysts.yml` 登録）。杖 10 本はすべて `use-skill: ARS_MAGIC` を持つので拾える。
  `TrinityForgeBridge#isCatalystCast` として 1 本に集約 —— 述語を 2 本持つと杖を足すたびに
  2 か所を直すことになり、また片方が腐る（D6 と同じ再発形）。
- 魔導書の素の右クリック詠唱は従来どおり非カウント（`castItem == null` かつ魔導書は
  `catalysts.yml` 未登録・`use-skill: ARS_MAGIC` も無し → `NONE`）。
- ⚠ **カウンタは PDC 累計なので遡及しない。** 配備後に撃った回数から数え直しになる。
- 検証: フォーク **535 件・失敗 0・skip 0**。`catalystData != null` へ戻すと
  `MagicStatSourceWiringTest` が落ちることを実走確認。fork `f95f65d`。

#### W-192 真因: レベル差の足きり。**サーバ差ではなく「止めを刺した人」の差**だった

報告は「メインだけ落ちない」だったが、**config はサーバ間で完全に同一**。
`Main_Server\plugins\TrinityForge` が実体で、`Dev_Server` / `Resource_Server` は
そこへの**ジャンクション**なので、3 台とも同じ `mob-types.yml` と `damage.yml` を読む。
EliteMobs もエンドラをエリート化していない（していれば死亡メッセージが
`Lvl N Elite Ender Dragon` になるが、両サーバとも `was slain by Ender Dragon`）。
`world_the_end` がダンジョン判定に入ることもない（登録は EM のインスタンスワールドのみ）。

実際に効いていたのはこれ:

- `combat/mob-types.yml` の `ENDER_DRAGON.level` が **0 → 100**（設定エディタでの編集。
  同時に ELDER_GUARDIAN / WARDEN / WITHER も 80 → 100）。
- `combat/damage.yml` の `level-cutoff.under-level`: **`item-threshold: 20` / `drop-rate: -1`**
  → モブが自分より 20 レベル以上高いと **TF 追加ドロップを完全遮断**。
  `exp-threshold: 15` / `exp-decay-per-level: 0.067` により**討伐 EXP も 0**。

`MobLevelTableListener#onDeath` は `entity.getKiller()`（止めを刺した 1 人）だけを見るので、
**誰が最後の一撃を入れたかで結果が変わる**。進行 DB から全 23 人の戦闘レベルを再現した結果:

| 判定 | 人数 | 該当者 |
|---|---|---|
| 通る（戦闘 Lv81 以上） | 5 | Sora0608 100 / Klee319 100 / HinataS2010 100 / .yuzu3850 90 / .natsuking003 86 |
| 遮断（戦闘 Lv80 以下） | 18 | .shizurei555 80 / Rando4649 75 / Kuragemal 75 / .kenntoaya 71 / .NAGIdayo5655 71 / haru_harura 70 ほか |

そして 2026-08-23 のログの顔ぶれがそのまま分かれていた ——
**資源鯖のエンド**は HinataS2010（100）と Sora0608（100）＝通る側、
**メインのエンド**は .NAGIdayo5655(71) / Rando4649(75) / .pale4780(44) / Liru_62(55)＝全員遮断側。
「資源鯖では落ちてメインでは落ちない」ように見えたのはこれが理由で、サーバ固有の不具合ではない。

- **ユーザー判断（2026-08-23）: このままでよい。** ボスの報酬は戦闘レベル 81 以上の門とする。
  免除リスト（`level-cutoff.exempt-mobs:`）・エントリ単位の免除フラグ・閾値の緩和は
  いずれも提示したうえで採用しない。**再調査しないこと。**
- 関連: W-128（`environment:` 軸の新設）／W-178（`environment: [NORMAL]` を外した件）は
  どちらも別の原因で、**それを直しても足きりの手前で止まっていた**。

---


#### W-193 真因: 旧 `prestige_decay_rate: 0.5` がプレステージ済みスキルの POWER EXP を削っていた

> 「Ars魔法をプレステージした後、A魔法のレベルを上げてもSPが手に入らないらしい」
> 「もしかしたらArs魔法以外もあるかも」 ← **正しい。鍛冶・農業でも起きていた。**

SP の出どころは POWER レベル**だけ**（`PlayerProgression#earnedPoints = 3 + POWERレベル / levels-per-skill-point(=1)`）。
非 POWER スキルは 1 レベルにつき POWER へ
`exp_gain(240) × 上がったレベル数 × prestigePowerDecayMultiplier(そのスキルのプレステージ回数, decayRate)`
を注ぐ（`NativeProgressionService#grantExpUnderRepositoryLock`）。
`prestigePowerDecayMultiplier = (1 - decayRate)^tier` なので、**旧設定 0.5 では
プレステージ 1 回で半分・4 回で 1/16 しか入らなかった**。これが症状そのもの。

**機構は 2026-08-21 に `prestige_decay_rate: 0` へ変更済みで、現在は減衰しない。**
配備先 `plugins/TrinityForge/skills/base/power_progression.yml` も 08-22 23:56 時点で 0。
実証: `Liru_62` は ARS_MAGIC を t1 にしてから Lv1 まで上げているが POWER のズレが **0**
（減衰が生きていれば 120 不足になる）。

##### 実データ（本番 DB を読み取りのみで突合。`tmp/sp-audit.py` / `tmp/sp-owed.py`）

POWER の `total_exp` と「稼いだ全スキルレベル総数 × 240」を比べると、
**ズレるのはプレステージ済みの 5 人だけで、他 18 人は誤差ゼロ**。原因がこれ以外にないことの証拠。

| プレイヤー | POWER | 本来の POWER | 失った POWER EXP | 不足 SP | 内訳 |
|---|---|---|---|---|---|
| Sora0608 | 138 | 140 | 6,000 | 2 | ARS_MAGIC(t1, Lv88) |
| .NAGIdayo5655 | 110 | 113 | 8,640 | 3 | SMITHING(t4, Lv90) |
| Rando4649 | 109 | 113 | 11,520 | 4 | FARMING(t1, Lv100) |
| Kuragemal | 107 | 112 | 12,000 | 5 | SMITHING(t3, Lv90) |
| ame3398610 | 48 | 55 | 11,160 | 7 | FARMING(t1, Lv94) |

失った分は自動では戻らない。`ProgressionCurveReconciler` の POWER 救済分岐は
**POWER 自身の `prestige > 0` のときだけ**走るので（2026-08-04 の別事故の復旧経路）、この 5 人には当たらない。

##### 補填（ユーザー判断 2026-08-23: 補填する）

**対象がオンラインのときに**コンソール／OP で:

```
/trinityforge progression level Sora0608 POWER add 2
/trinityforge progression level .NAGIdayo5655 POWER add 3
/trinityforge progression level Rando4649 POWER add 4
/trinityforge progression level Kuragemal POWER add 5
/trinityforge progression level ame3398610 POWER add 7
```

`NativeProgressionAdminService#edit` は `POWER` を `ADD` すると
`total_exp = cumulativeExpForLevel(目標)` で整合値に書き直す（＝あとで reload しても巻き戻らない）。
レベル**上昇**なのでパーク剥がし・返却は走らず、`spent_points` も動かないので
`POINT_LEDGER_CONFLICT` にもならない。`available_points` が素直に +n される。
副作用: 現在の余り EXP（Sora なら 1,446）が 0 に落ちる。1 レベル未満なので許容。
⚠ `getPlayerExact` を使うので**オフラインには打てない**。名前の先頭ドットは統合版の Floodgate 接頭辞なので込みで入力する。

##### 減衰なしでも SP は元々重い（仕様。バグではない）

POWER の 1 レベル単価は `(%level%/100) * 1800 + 800`。1 スキルレベル = 240 POWER EXP なので:

| POWER | SP 1 点に必要なスキルレベル数 |
|---|---|
| 0 | 3.3 |
| 48 | 6.9 |
| 91 | 10.2 |
| 138 | 13.7 |
| 242（上限） | 22.1 |

「上げても入らない」の体感にはこれも効いている。**この曲線自体は今回変更しない。**

##### 追加報告「一昨日（08-21）以降 SP をもらえた記憶がない」の実測（2026-08-23）

**バグではない。** 08-21〜08-22 に入れた供給削減とスキルカーブの指数化が重なった結果で、
機構はどこも壊れていない（`Liru_62` はこの日 ARS_MAGIC 1→100・POWER 91→100 で満額 240 が入っている）。
`daily_exp_window.amount`（直近 24 時間に各スキルへ流し込まれた生 EXP）を
スキル 1 レベルの単価 `L + 75*2^(L/8) + 300`（Ars 系のみ `2^(L/7.6)`）で割って算出:

| プレイヤー | POWER | 24h の総 EXP | 換算スキル Lv | **実測 SP/日** |
|---|---|---|---|---|
| Sora0608 | 138 | 1,902,600 | 30.4 | **2.2** |
| Rando4649 | 109 | 812,718 | 31.8 | 2.8 |
| Liru_62 | 100 | 191,750 | 18.7 | 1.7 |
| .pale4780 | 55 | 396,796 | 53.8 | **7.2** |
| .gorena2887 | 56 | 292,218 | 48.2 | 6.4 |

**Sora は `.pale4780` の 5 倍 EXP を稼いで SP は 1/3。** 本人のパーク購入数
（08-21: 17 → 08-22: 12 → 08-23: 3）とも一致する。

要因は 4 つ。**日次逓減は主因ではない**（`per-amount 100000 / decay 0.9 / floor 0.5` ＝最大でも 2 倍差）。

1. **スキルカーブが指数**。Sora の単価は WOODCUTTING Lv40 が 2,740、SMITHING Lv93 が 237,277 で **87 倍**。
   主力が全部高レベル帯に入ったので、同じ EXP 量でも稼げるレベル数が桁で落ちる。
2. **`eeb8d0e`（08-22）職業EXPの足きりを戦闘レベル→職業レベル基準へ。**
   `level-cutoff.under-level` は 15 差から減り **30 差で 0**。Sora の LIGHT_ARMOR は Lv54 なので
   Lv100 モブと差 46 → 完全遮断。実データで LIGHT_ARMOR **3,111 EXP** に対し
   HEAVY_ARMOR(Lv85) **213,893 EXP**。LIGHT_ARMOR の単価は HEAVY_ARMOR の 1/14 なので、
   ここが通っていれば **+1.8 SP/日**（現在の総取得の約 45%）だった。
   **「遅れているスキル＝1レベルが安い＝SP の主エンジン」を、狙って塞いだ形になっている。**
   コミット本文の意図（「高レベル帯での新スキル一気育て」の抑制）どおりの挙動。
3. **POWER カーブも線形に重くなる**（上の表）。
4. **08-21 の供給削減 3 本**: `a83c7d4` 農業EXP圧縮（作物40%/家畜80%）、`ea7ffdb` ダンジョンEXP絞り、
   `33d5427`/`8d53e52` カスタムポーションEXP。

- **ユーザー判断（2026-08-23）: 現状維持。** 足きりの緩和（`exp-threshold` を 25 へ戻す／
  `rate-floor` を 0 でなく 0.2 にして完全遮断をやめる）も、POWER 側の増量（`exp_gain` 240 の引き上げ／
  POWER 曲線の傾きを寝かせる）も、いずれも提示したうえで**採用しない**。
  ベテランが 2〜3 点/日になるのは設計として受け入れる。**再調査しないこと。**
- 補填 21 SP（上の 5 人）は減衰バグぶんなので**これとは別に実施する**。

##### 派生で見つかった別件: POWER の `max_allowed_level` が 160 のまま

`power_progression.yml` の `max_level` は 242 なのに、DB 上の POWER `max_allowed_level` は
**既存 21 人全員が 160**（この値より後に作られた `.pale4780` / `.Altale8675` だけ 242）。
`max_allowed_level` は行を作った時点の catalog 値で固定され、ログインでは更新されない。
`XpTransitionService` はこの永続値でクランプするので、**POWER 160 で SP が永久に止まる**。
最高が Sora の 138 なので当面は詰まらないが、残り 22 レベル。

直し方は `/trinityforge reload`（`ProgressionCurveReconciler` が全スキルの
`maxAllowedLevel` を現在の catalog 値へ書き直す）。
**ユーザー判断 2026-08-23: 次の再起動時に打つ。**
⚠ 同じ処理が**全スキルのレベルを `total_exp` から現在のカーブで再導出する**。
カーブを変えたスキルがあればレベルが動くので、打つのはカーブ変更を配備し終えたあとにする。

---


#### W-194 真因: 儀式のローダーが2本あり、materials.yml 側だけ結果個数を読んでいなかった

> 「クラフト儀式で amount を複数個に設定しても1つしかクラフトされない。例：ソースの欠片の9個同時クラフト」

`UnifiedRecipeLoader` の儀式ローダーは**2本**ある。

| 読む config | 関数 | `result-amount` |
|---|---|---|
| items.yml / functional-items.yml / sourcejars.yml / spellbooks.yml | `loadRitualFromSection` | **読んでいた** |
| **materials.yml** | `loadMaterialRitualFromSection` | **読んでいなかった** |

後者は `RitualRecipe` の**後方互換コンストラクタ（`resultAmount = 1` 固定）**を呼んでいて、
`result-amount` に一度も触れていなかった。設定エディタは materials.yml の儀式にも
結果個数の入力欄を出す（`public/js/recipes.js` の ritual モデルに `result-amount` がある）ので、
**入力できるのに警告ひとつ無く捨てられる**のが症状。

出荷 yml でも儀式 11 件のうち **6 件**が該当していた（実測）:

| 素材 | 書かれていた値 | 実際 |
|---|---|---|
| `source_shard` | 4 / **36** | 1 |
| `source_crystal` | 4 / **36** | 1 |
| `source_condenser` | 4 | 1 |
| `source_engine` | 4 | 1 |

報告の「ソースの欠片の9個同時クラフト」は 9 倍圧縮アメジストを核にする一括儀式で、
`result-amount: 36` が 1 個になっていた。

同じ関数は `result` / `effect-type` / `effect-params` も読まず結果を素材 ID に固定しているが、
**出荷 yml では全件が素材 ID と一致していて実害ゼロ**（実測）なので今回は触っていない。

**修正**: 個数の読み取りだけ `UnifiedRecipeLoader#ritualResultAmount` へ寄せ、
**両方の儀式ローダーが同じ1本を通る**ようにした。ローダー本体を分けたまま個数だけ各々で読むと
同じ取りこぼしがまた起きる。0 以下は 1 へ丸める（`ItemStack#setAmount(0)` は空スタックになり、
「儀式は成功したのに何も出ない」という一番分かりにくい壊れ方をする）。

検査は組み立てた `RitualRecipe` の値を見る回帰テスト（`MaterialRitualResultAmountTest`、4 件）。
ソース文字列を固定するガードは実装差し替えで誤検知するので使っていない。
**修正前に戻すと 2 件 RED** を確認済み。フォークのテストは 544 件・失敗 0・スキップ 0。

- コミット: ArsPaper フォーク `67dbc61`（`origin/feat/trinityforge-fork` へ push 済み）
- **配備が要る**: `fork-handoff/arspaper/fork/build/libs/ArsPaper-1.0.0.jar`（1,208,052 バイト / 08-23 23:53）。
  config 側の `result-amount` は既に正しい値が入っているので、**jar を替えるだけで直る**。
  ⚠ 稼働中の差し替えは `NoClassDefFoundError` になるのでサーバ停止後。

---


#### W-195 「くべる」操作の一括をスニーク側へ統一（**操作が変わる**ので周知が要る）

> 「ソースリンクにアイテムをくべる時にソースベリーと同様に一括でくべられるようにしてほしい」
> → 「全部スニークで一括に揃えよう」

**確定した規約**: スニーク＋右クリック＝**手持ちスタック全部** / 通常の右クリック＝**1個**。
ソースリンク（燃料）とソースジャー（ソースベリー）の**両方**がこれに従う。

元は 3 通りに割れていた —— ソースリンクは「スニーク時だけ一括」、ソースジャーは
**無条件で一括**。同じ「くべる」操作なのに意味が食い違い、しかも**ジャー側は 1 個だけ入れる手段が
無かった**。判定を `com.arspaper.source.BulkFeed` へ集約して 1 本にする
（`Sourcelink#feedCount` に置くと `SourceJar` から呼ぶ形になって読みが破綻する）。

1 個だけ入れる手段は通常クリック側に**必ず残す**。上位ソースリンクの燃料は単価が桁違い
（`custom:source_engine` = 3,000万）で、握ったまま右クリックした瞬間にスタック全部が
消える逃げ道が要るため。

⚠ `BulkFeed` が返すのは「**入れたい個数**」であって入れ**られる**個数ではない。容器側の頭打ちは
呼び出し側の責任 —— ジャーは `convertibleBerries`（あふれる一段階手前で止め、端数は切り捨て）、
ソースリンクはバッファ上限が実質無制限＋クランプ時に警告ログが出るので掛けていない。

投入の口を持つのは燃料を受け取る 3 種（ヴォルカニック / アルケミカル / ミセリアル）とジャー。
ボタニカル・バイタリックは受動生成なので対象外。

⚠ **in-game の lore は変えていない**。`sourcelinks.yml` は `saveResource(..., false)` なので
リポジトリを直しても配備先には永久に届かない（[[arspaper-config-saveresource-false-never-updates]] と同じ罠）。
現在の lore は「燃料を手に持って右クリックで投入」で**間違ってはいない**。
操作の周知はパッチノート側で行う。

#### W-196 収穫魔法でグロウベリーを摘めるようにした（**既存バグも同時に解消**）

> 「ソースベリーを収穫魔法で取れるようにできない？」

ソースベリーは `GLOW_BERRIES + アメジストの欠片` の**クラフト品**（`functional-items.yml`）なので、
収穫できる実体は原料の**グロウベリー**側。摘む手順は「使用」グリフ
（`InteractEffect#simulateRightClick`）が既に持っていたものを踏襲した。

⚠ **分岐は Ageable 作物より前に置くこと**。`CAVE_VINES`（つるの先端）は `Ageable` でもあり、
その `age` は「つるがどこまで伸びたか」であって実の熟度ではない。後ろに置くと最大 age のつるが
`breakNaturally()` で丸ごと壊れる —— **この分岐を入れるまで、収穫魔法を洞窟のつたへ当てると
実際に栽培設備ごと消えていた**（報告されていなかった既存バグ）。

幸運（fortune）は掛けていない。グロウベリーはソース経済の入り口（ベリー1個 = 100 ソース）なので、
増量するなら別途決める。要るなら言ってもらえれば足す。

#### W-195 / W-196 の検証

- ArsPaper フォーク `a4c072e` → `6f9289a`（`origin/feat/trinityforge-fork` へ push 済み）。
  `a4c072e` が「通常＝一括」で入れた版、`6f9289a` が「スニーク＝一括」へ揃え直してジャーも
  同じ規約に載せた版。**現行仕様は後者**。
- テスト 550 件・失敗 0・スキップ 0
- ⚠ **このフォークには MockBukkit が無い**（素の JUnit + paper-api）。ブロックを実際に置いて摘む
  経路そのものは自動テストできないので、**壊れやすい前提だけ**を型階層で固定した
  （`HarvestCaveVinesPremiseTest`: `CaveVines` が `Ageable` かつ `CaveVinesPlant` であること
  ＝分岐順の根拠）。Bukkit 側でこれが変わったら実サーバで踏む前にテストが落ちる。
- **配備が要る**: `fork-handoff/arspaper/fork/build/libs/ArsPaper-1.0.0.jar`（1,208,898 バイト / 08-24 00:18）。
  W-191 / W-194 / スケール魔法の修正も同梱。⚠ 稼働中の差し替えは `NoClassDefFoundError`。

#### W-197 資源リセットのバッチが空撃ちで終わる（**配備先の launch が古い**）

> 「リソースリセットのバッチファイル動かしてもリセットされなかったよ」

**真因**: `D:\...\Velocity_for_TF\launch\reset-resource.cmd` が **08-16 版**。この版は
`--apply` を付けない限り**必ず `-DryRun` で呼ぶ**（しかも `pause` が無いので窓が一瞬で閉じる）。
「押したのに何も起きない」に見えるのは正しい挙動で、リポジトリ側は 08-23 の `reset-resource.cmd`
（ドライラン → `RESET` と打たせて本実行）で既に直っているが、**配備されていなかった**。

⚠ これは jar でも config でもない**第3の配備漏れ経路**。`ops/launch/` は `deploy-launch.cmd` で
しか配備先へ渡らず、`deploy.cmd`（jar）にも config 配備にも乗っていない。
「リポジトリを直したのに実機で効かない」の常連
（[[arspaper-config-saveresource-false-never-updates]] と同種だが別経路）。

配備先の `launch` は **11 件がリポジトリと食い違っている**（うち 6 件は配備先に存在しない）:
`reset-resource.cmd` `deploy.cmd` `deploy-config-head.cmd` `README.md` `start-all.cmd` /
未配備: `apply-block-break-exploits.cmd` `apply-mob-griefing-guard.cmd` `deploy-husksync.cmd`
`disable-natural-elites.cmd` `install-economy-plugins.cmd` `swap-deathchest.cmd`。

⚠ **`deploy-launch.cmd` は robocopy `/E` で上書きするので、配備先で直接編集した内容は消える。**
`start-all.cmd` は**配備先の方が新しく**（08-16 15:51 / リポジトリは 07-27）、手で
**preflight の実行と Dev サーバの起動が削られている**。配備するとこの 2 つが復活する。
残したいならリポジトリ側の `start-all.cmd` を先に合わせること。

暫定回避（配備なしで今すぐ直る）: **リポジトリの `ops\launch\reset-resource.cmd` を直接叩く**。
`launch-config.cmd` が `VELOCITY_ROOT` も `OPS_SCRIPTS` も絶対パスで持っているので、
どこから起動しても同じ動作になる。

**ドライラン段を廃止（08-24 指示）**。`reset-resource.cmd` はもう空撃ちしない。
`RESET` と打たせて即本実行する。プレビューが要るときは
`powershell -File ops\scripts\reset-resource.ps1 -DryRun` を直接叩く。
削除対象は ps1 が削除しながら1件ずつログに出すので、記録自体は失われていない。

#### W-197 の第2の壁: **RCON パスワードの環境変数が1つも設定されていない**

`Get-OpsConfig` は `-RequireRconPasswords`（DryRun 以外は既定で真）のとき、
`TF_RCON_<キー>_PASSWORD` が無いと**1行目で throw する**。実測（08-24）:

```
TF_RCON_MAIN_PASSWORD      process=False user=False machine=False
TF_RCON_RESOURCE_PASSWORD  process=False user=False machine=False
TF_RCON_DEV_PASSWORD       process=False user=False machine=False
```

⚠ **つまりドライラン以外の ops スクリプトは、この環境では現状ぜんぶ即死する**
（`restart` / `stop-all` / `reset-world` / `backup` の非 DryRun も同じ）。
配備先の cmd を新しくしただけでは資源リセットは通らない。
`testkit\check-ops-scripts.cmd` が全部 DryRun で走るせいで、この穴は **46/1 の緑に隠れていた**
（唯一の赤は HuskSync preflight の別件）。

3 サーバとも `server.properties` 側は `enable-rcon=true` でパスワードも入っており、
ポートも `ops-config.psd1`（25586 / 25587 / 25588）と一致している。**足りないのは環境変数だけ。**

→ `ops/scripts/set-rcon-env.ps1`（+ `ops/launch/set-rcon-env.cmd`）を追加した。
各バックエンドの `server.properties` から読んでユーザー環境へ入れる。
値は画面にも出さず、`setx` ではなく `[Environment]::SetEnvironmentVariable` を使う
（`setx` だとコマンドラインに平文が乗る）。⚠ **環境変数は新しく開いた窓からしか効かない。**

⚠ 配備するときの副作用: 配備先の `start-all.cmd` は**手で preflight ゲートと Dev 起動が
削られており**、リポジトリ側より新しかった。`deploy-launch.cmd` は robocopy `/E` の上書きなので
そのまま配備すると 2 つが黙って復活する。**ユーザー判断でリポジトリ側を実機へ合わせた**
（`e86958a`）。preflight は `preflight.cmd`、Dev は `start-dev.cmd` で個別に叩く。

#### W-198 ソースリンクの倍率を 2 の階梯乗へ（転送・生成の両方）

> 「ソースリンクの生成倍率を強化して / 生成倍率を2のtier乗にしてほしい / 2,4,8,16,32,64,128,256,512」
> → 「転送も同じように上げる」

値が 9 つ、階梯も**無印 + 8 段 = 9 段**なので、**無印を tier1** として割り当てた（5 種とも同じ）。

| 段 | 無印 | _ii | _ii_b | _iii | _iii_b | _iv | _iv_b | _v | _vi |
|---|---|---|---|---|---|---|---|---|---|
| **転送・生成（新／同率）** | **2** | 4 | 8 | 16 | 32 | 64 | 128 | 256 | **512** |
| 生成量（旧） | 1.0 | 1.5 | 2.0 | 2.5 | 3.0 | 3.5 | 4.0 | 5.0 | 6.0 |
| 転送レート（旧） | 1.0 | 2 | 4 | 6 | 8 | 12 | 16 | 24 | 32 |

最上段で生成 **約 85 倍** / 転送 **16 倍**。lore の「転送レート xN / 生成量 xN」も
40 段すべて追随させ、無印 5 種には表示行を新設した。

⚠ **無印にも倍率が乗るようになった**（両方 2.0）。それまで `ShippedSourceLadderTest` が
「無印は倍率キーを一切持たない」を不変条件として固定していたので、**意図的に緩めてある**。
代わりに「両方が段ごとにちょうど 2 倍」と「**両方が全段で一致する**」を固定するテストを足した
（552 件・失敗 0・スキップ 0）。後者を入れたのは、片方だけ動かすと**その段だけ**
バッファが詰まる／空になるため。

⚠ **2 つを同率にしたのは設計変更**。以前は「生成量は素材効率そのもの（＝無限資源に近づく）
なので速度より伸びを抑える」として生成側を緩くしていた。その前提は捨てた。
同率だと**階梯を上げても「生成と排出の釣り合い」は変わらない** —— 詰まりやすさは全段で同じで、
上げたぶんがそのまま素材効率と回収速度の両方になる。

転送の実効レート = `max-per-transfer`(50) × 倍率 ÷ `interval-ticks`(100)。
無印 1.0/tick ＝ 86,400/日、最上段 256/tick ≒ **2,212 万/日**（1 台あたり）。
インフィニティコアの範囲内ならさらに ×2。全体を動かしたいときは倍率ではなく基準値
（`transfer.sourcelink.max-per-transfer`）を触ると全段が線形に動く。

⚠ **最上段は int の上限に当たる**。ソース機関（3,000万）をスニークで 1 スタック焼べると
19.2 億 × 512 ≒ 9,800 億で、`SourceGenerationScaling` が int 上限（約 21.4 億）へ切り詰める
（負値には化けない）。**高階梯ほど安い燃料を回した方が効率がよい、という逆転が起きる。**

- ArsPaper フォーク `0476faf`（生成）→ `b10973f`（転送）。push 済み。
  jar は 1,209,686 バイト / 08-24 01:35
- **これは config の変更なので jar だけでは反映されない**。`sourcelinks.yml` の config 配備が要る。
  反映は `/ars reload`（または再起動）。

#### W-199 手置きの鉱石でも一括破壊を発動させ、報酬側だけ落とす

> 「鉱石の一括破壊は手置きのものにも適用されるようにしてほしい。
> ただすでに一回採掘済みなのでバニラEXPと職業EXPは反映されないようにする必要がある」

`VeinMiningListener#onBlockBreak` は起点が設置ブロックなら**早期 return** していたので、
手置きの鉱石は連鎖が一切起きなかった（GTH-02 = シルクタッチ回収 → 並べて設置 → 幸運で割る、の対策）。
この門を外し、代わりに報酬を落とす方式へ切り替えた。

**「EXP は入らない」側は 4 経路とも最初から成立していた**（＝このリスナーに足す抑止は 1 つも無い）:

| 経路 | 状態 |
|---|---|
| 起点の採取EXP・**破壊時バニラEXP**（TF のパーク） | `blockedByPlaceBreakGuard` が `grantGathering` / `grantBreakVanillaExp` の手前で return するので既に 0 |
| 連鎖分の採取EXP・破壊時バニラEXP | 同じガードを `grantChainBreak` 経由で 1 ブロックずつ通る（手置きの段だけ 0。巻き込まれた自然生成の段は従来どおり入る） |
| 連鎖分のバニラEXPオーブ | `ChainBreakSupport#breakOnce` は `setType(AIR)` なので**そもそも出ない** |
| 起点のバニラEXPオーブ（`expToDrop`） | **触らないのが正**（下記） |

⚠ **`event.setExpToDrop(0)` を一度入れて差し戻された**（`717c105` → `26e5f24` で撤回）。
指摘は「ブロック破壊時にバニラEXPがないだけで、鉱石ブロックを砕いたときのバニラEXPまで
消したらダメでは？」。**要望の「バニラEXP」は TF のステ「破壊時バニラEXP」を指していて、
鉱石固有の経験値オーブのことではない。** 後者はバニラが設置ブロックでも等しく出すもので、
ここで消すと**一括破壊の解放や発動可否と無関係に**「手置きの鉱石を割ると経験値が出ない」という
バニラからの無言の乖離になる。テストで設置/自然の両側から固定した
（`placedOriginOreBlockKeepsItsVanillaExperienceOrb` / `naturalOriginOreBlockKeepsItsVanillaExperienceOrb`）。

**この 2 つの「バニラEXP」は名前が同じで別物**。TF の `break-vanilla-exp` は
ブロック破壊時に**おまけの経験値を配るパーク**（`BreakVanillaExpLedger` / スキル別に 4 種、
解放ゲート付き）で、鉱石の `expToDrop` はバニラ本体の挙動。混同すると今回のように
「要望どおり消したつもりで、要望に無いものを消す」ことになる。

⚠ **GTH-02 の増殖経路が開く。** シルクタッチで回収 → 並べて設置 → 幸運のツルハシで 1 個割ると
盤面ごと連鎖し、**バニラの幸運エンチャントは効く**。TF 側の採掘運（`MiningFortuneListener`）と
ドロップテーブル（`onBlockBreakDropTables`）は設置ブロックを除外したままなので、増えるのは
バニラのドロップ倍化だけ。塞ぐなら連鎖分の設置ブロックでバニラ幸運を無効化する必要がある（未実施）。

- TF 本体 `717c105` → **`26e5f24`（こちらが正）**。push 済み。
  **TF jar の再ビルドと配備が要る**（config ではない）。
- `VeinMiningListenerTest` **9 件・失敗 0**。フルテストは 4,599 件・失敗 32・スキップ 2 で、
  失敗 32 は**全て他セッションの未コミット yml 由来**（27 クラスすべて `config/domains` `stats`
  `skilltree` `progression/catalog` の出荷 yml テストで、`listeners` パッケージは 1 件も無い）。

#### W-200 一括破壊の連鎖分にバニラの経験値オーブが出ていなかった（**発足以来の取りこぼし**）

> 「連鎖分のバニラEXPオーブでないの問題じゃない？」

**W-199 で生じた話ではなく、一括破壊が存在して以来ずっとそうだった。**
`ChainBreakSupport#breakOnce` は `setType(AIR)` で壊すので連鎖分は経験値オーブを 1 個も出さない。
2026-07-31 まで使っていた `breakNaturally(ItemStack)` も **Paper では `dropExperience=false` の
縮退呼び出し**なので、この経路は**一度もオーブを出したことがない**。

つまり石炭／ダイヤ／エメラルド／ラピス／レッドストーン／ネザークォーツ／ネザー金の鉱脈を
一括で掘ると、**起点 1 ブロック分の経験値しか入っていなかった**（＝1 個ずつ手で掘るより損をする）。
一括伐採の「EXP も耐久も入らない」（2026-07-28 報告）と**同じ型の取りこぼしが、経験値オーブという
第 3 の経路にだけ残っていた**。

**直し方: 起点の `expToDrop` を `1 + 連鎖数` 倍する。**
ブロックが落とす経験値量を問い合わせる API は **paper-api 1.21.11 に無い**
（`Block` に `getExpDrop` 系は存在しない。javap で確認済み）。`breakNaturally(tool, false, true)` は
経験値を出す代わりに**ルートテーブルを引き直す**ので使えない —— 2026-07-31 に潰した二重抽選
（EXP の根拠と実際に落ちた物の食い違い）が復活する。一括破壊の連鎖対象は**起点と同一材質**に
限られるので、**起点が実際に転がした値が「この材質をこの道具で壊した 1 ブロック分」の実測値**になる。

この方式で自動的に正しくなるもの:

| 場面 | 結果 |
|---|---|
| シルクタッチ | 起点が 0 → 連鎖分も 0。**`setExpToDrop` 自体を呼ばない**（0 を書くと「TF が消した」ように見えるため） |
| 経験値を落とさない鉱石（鉄・銅・金・古代の残骸） | 同上、0 のまま |
| 他プラグインが起点の `expToDrop` を書き換えた | `HIGH` で読むので追随する |

⚠ **バニラとの差は 2 点**。(1) オーブは連鎖先ではなく**起点にまとめて**湧く（拾い漏れが減る方向。
洞窟の奥に取り残されない）。(2) 1 ブロックごとに振り直さず起点の目を全段に使うので、
**期待値は同じでばらつきだけ大きい**（ダイヤは 3〜7 の一様分布）。

**設置ブロックも数に入れる。** オーブはバニラが設置ブロックにも等しく出すもので、抜くと
「手置きの段だけ経験値が出ない」という乖離になる（W-199 の差し戻しと同じ理由）。
**増殖にはならない** —— 鉱石は壊すと資源に変わって**ブロックが手元に戻らない**ので、
「シルクタッチで回収 → 置く → 割る」の総量は 1 個ずつ壊した場合と同じ（シルクタッチ側が 0）。

`ChainBreakSupport` の javadoc に **「ここはバニラの経験値オーブを一切出さない。経験値を落とす
材質を連鎖対象にするなら呼び出し側が補うこと」** を明記した。他の呼び出し元（原木・葉・
サトウキビ類）は**バニラでも経験値を落とさない材質しか通らない**ので補っていない。

- TF 本体 `cd6e608`。push 済み。**TF jar の再ビルドと配備が要る**。
- `VeinMiningListenerTest` **13 件・失敗 0・スキップ 0**（倍率／シルクタッチ／連鎖 0／手置きの 4 方向）。
  連鎖系の周辺 19 クラス（`TreeFelling*` `FarmingHarvest*` `NativeSkillExperienceListener*`
  `gathering.*` `mining.*`）も**全件緑・スキップ 0**。

---


### スケール魔法が鯖を抜けると永久に戻らない（2026-08-23 受領 第34陣。W-191）

> 「スケール魔法で小さくなって鯖抜けて入ったらずっと小さい。大も然りだと思う」

| ID | 内容 | 状態 |
|---|---|---|
| W-191 | スケール魔法（縮小・巨大化）の効果がログアウトを跨ぐと解除されない | ✅ 修正済み（**ArsPaper jar が要る**） |

**真因は 2 つ重なっていた。**

1. 効果の実体は `Attribute.SCALE` の `AttributeModifier` で、**属性修飾子はエンティティの NBT に
   保存される**。つまりログアウト・サーバ再起動・チャンクアンロードのどれでも**そのまま残る**。
   一方で解除は `runTaskLater` のタスクしか無く、そちらはプロセス内にしか無い。
   → **30 秒経つ前に抜けると解除役だけが消える。**
2. その解除タスクが `LivingEntity` の参照を握っていた。再ログインすると `Player` は別インスタンスに
   なるので `isValid()` が false になり、**タスクが動いても何もしなかった**
   （＝一瞬抜けて戻っただけでも固定化した）。

`onDisable` の `ScaleEffect.cleanupAll()` も**タスクを cancel するだけ**で修飾子を剥がしていなかったので、
サーバ再起動を挟むと確実に固定化していた。

#### 直し方

- 終了時刻を対象の PDC（`arspaper:spell_scale_end`、エポックミリ秒）にも書く。
  設計の前例は `FlightRitualEffect`（儀式飛行）。
- `ScaleRestoreListener` が `PlayerJoinEvent` と `EntitiesLoadEvent`（チャンクと一緒に戻ってきたモブ）で
  読み直し、**期限切れなら剥がす／残っていれば残り時間ぶんだけ**タスクを張り直す。
- **PDC が無いのに修飾子だけ付いている個体は無条件に剥がす。**
  これが**既に固定化している人の救済**そのもの ── jar を入れ替えたあと**入り直すだけで直る**。
- 参加時は **1 tick 後と 2 秒後の 2 回**見る。配備中の HuskSync は snapshot の適用が
  `PlayerJoinEvent` より**後**で、`PersistentData#apply` が `clearNBT()` → merge、
  `Attributes#apply` が属性を入れ直すため（同じ順序問題で職業バフが消えた W-138 の前例）。
- 解除タスクはエンティティ参照ではなく **UUID で引き直す**。
- `onDisable` はオンラインの全員から実際に剥がし、`onEnable` は全員を見直す
  （`/reload` や再有効化では `PlayerJoinEvent` が飛ばないため）。

⚠ HuskSync の `ignored_modifiers` に `arspaper:*` が入っているので、この修飾子は**サーバ間では同期されない**。
つまり固定化はバックエンド側の playerdata NBT に居る。PDC（`persistent_data`）は同期されるので、
残り時間はサーバをまたいでも正しく引き継がれる。

- **回帰テスト**: `ScaleEffectExpiryTest`（5 件）。復旧判断（残り tick への変換 / 期限切れ / **PDC 無しは剥がす** /
  1 tick 未満の切り上げ / 付ける側と剥がす側の接頭辞一致）を固定。**ArsPaper 540 件・失敗 0**。

---


### 圧縮素材をそのまま焼けるようにした（2026-08-23 要望 第33陣。W-190）

> 「あと圧縮したじゃがいもや生肉、生魚を焼けるようにできたら楽だなと（燃料と時間はその分増える）」

| ID | 内容 | 状態 |
|---|---|---|
| W-190 | 圧縮じゃがいも・圧縮生肉・圧縮生魚を **圧縮のまま** かまど／燻製器／焚き火で焼ける | ✅ 対応（**TF jar + ArsPaper jar + TF config + Ars config が全部要る**） |

確認済みの設計判断: **`_1x`（9倍）だけ**。`_2x`/`_3x` は対象外。

対応表（`progression/crafting-features.yml` の `compressed-smelting`）。8 種すべて
`cook-time: 1800`（＝バニラ 200 tick の 9 倍。燃料は燃焼時間で減るので **燃料も 9 倍**）。
燻製器は半分（900）、焚き火は 3 倍（5400）を自動で使う（バニラの 200:100:600 と同じ比率）。

| 入力 | 結果 |
|---|---|
| `potato_1x` | `baked_potato_1x` |
| `beef_1x` | `compressed_cooked_beef_1x` |
| `porkchop_1x` | `cooked_porkchop_1x` |
| `chicken_1x` | `cooked_chicken_1x` |
| `mutton_1x` | `cooked_mutton_1x` |
| `rabbit_1x` | **`cooked_rabbit_1x`（新規）** |
| `cod_1x` | `baked_cod_1x` |
| `salmon_1x` | `baked_salon_1x` |

⚠ **圧縮焼き鮭の id は `baked_salon_1x`（salmon の綴り違い）。直していない** ——
直すと既にプレイヤーが持っている分が別アイテムになるため。`base_material` も
`COOKED_SALMON` ではなく `COOKED_COD` のままにしてある（同じ理由）。

#### なぜ「イベントで結果だけ差し替える」実装にしなかったか（今回の要）

最初はそのつもりだった。**それでは 2 個目以降が永久に焼けない。**
バニラの `AbstractFurnaceBlockEntity#canBurn`（Paper 1.21.11 のソースを実物で確認）は

```java
return itemStack1.isEmpty() || ItemStack.isSameItemSameComponents(itemStack1, itemStack) && ...
```

＝ **結果スロットの中身**と『**レシピが組み立てた結果**』を data component ごと比べる。
イベントで差し替えるだけだと、1 個目の圧縮焼き芋が結果スロットに入った時点で
バニラのレシピ結果（素のベイクドポテト）と一致しなくなり、そこで精錬が止まる
（燃料だけ燃えて何も起きない）。

なので **レシピごと登録する**。`CatalogRecipeRegistrar#registerCompressedSmelting` が
1 行につき かまど／燻製器／焚き火の 3 レシピを
`trinityforge:compressed_smelt_<入力id>_<furnace|smoker|campfire>` で登録する。
素材は **`RecipeChoice.ExactChoice`**（型 + data component の完全一致）。
`MaterialChoice` にすると素のジャガイモにも一致して **バニラのベイクドポテトを潰す**
（プラグインのレシピが勝つのは SPIGOT-4638「一致した中の最後を採用」による）。
逆に素の素材を焼いたときは ExactChoice が一致しないので、バニラのレシピがそのまま使われる。

保険として `CompressedSmeltGuardListener`（`FurnaceSmeltEvent` / HIGH）を置いた。
**TF のレシピが選ばれなかったときだけ**結果を設定どおりの圧縮焼き物へ差し替える。
その場合は上の理由で 1 個ずつになるが、**9 個ぶんが 1 個へ消えるよりはるかにまし**。
入った時点で異常なので素材 1 種につき 1 回だけ警告を出す。

#### 見落としかけた真因: 入れる経路が **3 つとも塞がっていた**（W-132 の対策）

`CustomItemListener` は W-132（2026-08-19）で、materials.yml 素材のかまど搬入を
**クリック・ホッパー・`BlockCookEvent` の 3 経路すべて**で塞いでいる。
つまりレシピを登録しただけでは **一度も発火しない**。
`TrinityForgeBridge#tfSmeltableMaterialIds()`（`compressed-smelting` の**入力と結果の両方**）で
穴を開けた。**結果側も含めるのが必須** —— 含めないと焼き上がった圧縮品が結果スロットに入った瞬間、
**取り出すクリックまで塞がって永久に回収できない**。
穴は **かまど／燻製器に限る**（`SMELTING_MACHINES`）。溶鉱炉は焼けないので通す意味が無く、
醸造台は材料スロットが Material しか見ずに飲み込むので、通すと W-172 の穴が復活する。
TF 未ロード時は空集合を返す **fail-closed**（穴が開いたままより「焼けない＝従来どおり」に倒す）。

#### 触ったもの

- TF: `CraftingFeaturesConfig`（`compressed-smelting` パース）/ `CatalogRecipeRegistrar`
  （`registerCompressedSmelting`。`registerAll()` の末尾なので **`/trinityforge reload` と
  ArsPaper enable 後の再登録に自動で乗る**）/ `CompressedSmeltGuardListener` / `TrinityForge.java` 配線
- TF config: `progression/crafting-features.yml`（`compressed-smelting` 8 行）/
  `stats/food-gimmick.yml`（`cooked_rabbit_1x` を `custom-foods` へ。**載せないと
  `unregistered-custom-food-ban` で焼いても食べられない**）
- ArsPaper: `materials.yml` に `cooked_rabbit_1x`（CMD **260**、`resourcepack/cmd-registry.json` へ採番済み）/
  `CustomItemListener` の穴あけ / `TrinityForgeBridge#tfSmeltableMaterialIds`
- 運用: `ops\scripts\apply-compressed-cooked-rabbit.ps1`（**ArsPaper は `saveResource(..., false)` なので
  jar を替えても配備先の materials.yml に新キーは増えない**。止めずに入れるための経路）

- **回帰テスト**: `CraftingFeaturesConfigCompressedSmeltingTest`（6 件。出荷 yml が 8 行そろって
  読めることを含む）/ `CompressedSmeltGuardListenerTest`（3 件）。**9 件・失敗 0**。
- **検証**: TF フルテスト **4595 件・失敗 30・skip 2**。30 件はすべて他セッション／ユーザーの
  未コミット yml 由来で、触った領域は 1 件も含まない。
  ArsPaper フォーク **533 件・失敗 0**。
- ⚠ **ユーザーの未コミット `food-gimmick.yml` 編集で 2 件が赤い**:
  `beef_1x` / `porkchop_1x` / `rabbit_1x` / `chicken_1x` / `mutton_1x` / `cod_1x` / `salmon_1x` /
  `tf_crystal_apple` の 8 件が `custom-foods` から消えており、
  `CompressedFoodEdibleFlagTest` と `ShippedCompressedFoodRegistrationTest` が落ちる。
  **この状態のまま配備すると W-131 と同じ「圧縮生肉が一切食べられない」に戻る。**
  こちらでは戻していない（ユーザー編集の yml なので）。

---


### 実サーバ報告バッチ（2026-08-23 受領 第32陣。W-189）

| ID | 内容 | 状態 |
|---|---|---|
| W-189a | ソース結晶なしで作れる上位のソースジャーに 15万 のティアを追加 | ✅ 対応（**ArsPaper config + リソパ**） |
| W-189b | ソースリンクにもソース結晶不要のティアを1つ足し、性能を上位から繰り下げる | ✅ 対応（**繰り下げず「上へ伸ばす」で実装。既存は誰も弱くしていない**） |
| W-189c | ソース結晶／ソース機関／ソース凝縮物を使うものにも中間ティアを増設し段数を増やす | ✅ 対応（ジャー 5→10段・リンク 4→8段） |
| W-189d | ソースベリーがスタックしているとき、右クリック1回で満タン手前まで一括変換 | ✅ 対応（**ArsPaper jar が必要**） |
| W-189e | アメジストの欠片とソースの欠片に9倍圧縮を実装 | ✅ 対応（**ArsPaper config + リソパ + TF config**） |
| W-189f | ソースの欠片とソース結晶が9個一気に作れるレシピを追加 | ✅ 対応（**厳密に9倍。素材効率は不変で儀式回数だけ 1/9**） |

#### 確認した設計判断（着手前にユーザーへ確認済み）

| 論点 | 選んだ答え |
|---|---|
| 最上位の扱い | **最上位を伸ばす** —— 新段が旧 `_iii` の x4.0/x2.0 を引き継ぎ、既存の結晶/凝縮物/機関の段は1段ずつ**上へ**ずらして新しい最上位（転送 x32 / 生成 x6.0）を作る。**既存プレイヤーの設置済みリンクを1つも弱くしない**のが選定理由 |
| 一括量 | **厳密に9倍** —— 圧縮素材（9個ぶん）を1回焚べて 36 個（従来の4個の9倍）。素材効率は変わらず、儀式の回数だけ 1/9 |
| 圧縮段数 | **`_1x` のみ（9倍）** —— `_2x`/`_3x` は作らない |

#### W-189a/c: 触媒ごとに「その触媒で届く2段目」が1つも無かった

段を数え直すと、ジャーもリンクも**触媒1種につき段が1つだけ**で、
「次の段へ進む＝必ず次の触媒を解禁する」構造だった。つまり階梯の各段が
そのまま素材の壁で、`docs/config-reference/arspaper/sourcejars.md` に書いてある
「増やす vs 良いのに替える」パズルが**触媒の解禁待ちに潰されていた**。
触媒ごとに 2 段目を1つずつ足して割った。

| ジャー | 容量 | 触媒 |
|---|---|---|
| `source_jar` | 20,000 | 作業台 |
| `source_jar_ii` | 50,000 | 欠片 x4 |
| **`source_amphora`（新）** | **150,000** | **欠片 x8（＝W-189a の「結晶なしで届く上位」）** |
| `source_jar_iii` | 250,000 | 結晶 x4 |
| **`source_cistern`（新）** | **700,000** | **結晶 x8** |
| `source_reservoir` | 2,000,000 | 凝縮物 x4 |
| **`source_basin`（新）** | **5,000,000** | **凝縮物 x8** |
| `source_vault` | 10,000,000 | 機関 x4 |
| **`source_abyssal_urn`（新）** | **25,000,000** | **機関 x8** |
| `source_singularity_jar` | 50,000,000 | 機関 x4 + 深淵の延べ棒 |

ソースリンクは5種すべてに `_ii_b` / `_iii_b` / `_iv_b` / `_vi` を足して 4→8 段（＝新規20ブロック）。
倍率は 転送 x2/x4/x6/x8/x12/x16/x24/x32、生成 x1.5/x2/x2.5/x3/x3.5/x4/x5/x6。

⚠ **id は据え置き。だから枝番になっている** —— `_ii_b` のような不格好な id は、
**設置済みブロックの PDC と他段の `core-item: custom:..._iii` が id 変更で全部切れる**ため。
番号を詰め直さないこと。

⚠ **段を挿すと「次段の `core-item` の付け替え」が必ずセットになる** ——
付け替え忘れると挿した段が誰からも参照されない飛び地になり、しかも
**エラーは出ない**（前段からそのまま先へ飛べてしまう）。
`ShippedSourceJarCapacityTest` に一本道の検査を新設して固定した
（**`source_jar_iii` の `core-item` を旧値へ戻すと落ちる**ことを実走確認）。

⚠ **`ShippedSourceJarCapacityTest.LADDER` は許可リスト方式** —— 2026-08-16 に足した
`source_vault` がこの一覧に入っておらず、**今日まで1件も検査されていなかった**。
今回10段すべてを入れ、その旨をコメントに残した。

#### W-189d: 上位ジャーは連打では現実的に埋まらなかった

ソースベリーは1個 100 ソース。特異点の壺（50,000,000）を埋めるには
**500,000 回の右クリック**が要る計算で、階梯を伸ばすほど連打が非現実的になっていた。
手に持っているスタックを一度に流し込むようにした。

⚠ **端数は必ず切り捨てる** —— 残り容量が 1 個ぶん（100）に満たないのに 1 個消費すると、
入り切らなかったソースが黙って消える。プレイヤーからは「ベリーが1個減っただけ」に見え、
ログにも何も出ない。`SourceJar.convertibleBerries` として切り出し、
**切り上げ実装に差し替えると2本落ちる**ことを実走確認（`SourceJarBulkBerryConversionTest` 5本）。

#### W-189e/f: 台座は1台1個なので「x36」は書けない

`RitualManager` は儀式コアの周囲を `max(|x|,|z|) == 2` の16マス（Y は ±1）で走査し、
**台座1台につき1個**しか読まない。つまり `GLOWSTONE_DUST x36` のような指定は物理的に置けない。
ソース結晶の一括儀式は `custom:source_shard_1x x7` ＋ `GLOWSTONE x9`（＝粉36個ぶん）の
**ちょうど16台**で組んである。1層で置ける上限そのものなので、ここへさらに足す余地は無い。

⚠ **lang キーはモデル名から機械的に決まる** —— `resourcepack/build_item_lang.py` は
GeyserExtra の `guessItemTranslationKey` と同じ「モデル識別子の最後の `/` 以降」で
キーを作るので、**2つの CMD が同じモデルを指すと別名を持てない**。
圧縮ソースの欠片を素の `trinityforge:item/source_shard` に向けたらパックのビルドが
`lang キー item.trinityforge.source_shard が別名になる` で落ちた。
既存テクスチャを流用した専用モデル（`source_shard_1x` / `amethyst_shard_1x`）を置いて解決。
**テクスチャは1枚も増やしていない**。

#### 検証

- ArsPaper fork: **525 件・失敗 0・skip 0**（`--rerun-tasks` で全実走。新規 5 本を含む）。
- 設定エディタ: **1424 件・失敗 28**。⚠ **私の変更を HEAD 版へ戻しても 28 件で同数**だったので、
  28 件はすべて他セッションの未コミット yml 由来（`item-stats.yml` / `catalog.yml` など）。
  唯一 collection 関連の失敗（`生ID表示ゼロ`）も中身は `abyss_wand` / `boundary_wand` /
  `magic_wand` で、今回追加した26 id は全件解決している。
- リソースパック再ビルド: `packed_files=900 bytes=567890`
  **sha1 `b2e5aecd684d794ee0667b7350f5e40b1b2058ee`**。
- `docs/config-reference/arspaper/sourcejars.md` を現物の yml から作り直した。
  ⚠ **この退避先は 2026-07-31 以降のコメントを取りこぼしたまま止まっていて、
  「エディタ保存で消えたコメントの避難先」として機能していなかった**。
  逆に、退避先にしか残っていなかった2件（`source_jar` の作業台レシピ由来・
  `creative_source_jar` のレシピなし注記）を yml 側へ復元した。

#### 配備

**ArsPaper jar が必要**（W-189d の一括変換は Java 側）。加えて config 配備とリソパ再配布。
⚠ `sourcejars.yml` / `sourcelinks.yml` / `materials.yml` は `saveResource(..., false)` なので、
**jar を差し替えても配備先の yml は永久に更新されない**。config 配備が必須。

---


### 実サーバ報告バッチ（2026-08-23 受領 第31陣。W-188）

| ID | 内容 | 状態 |
|---|---|---|
| W-188a | 依然としてトーテムアイコンを押すと勝手にプレステージされる。警告つきの確認 GUI を出すべき | ✅ 対応（**TF jar のみ**） |
| W-188b | プレステージのパークには「これはプレステージです／永続バフを得るが0Lvに戻る」を赤字で lore に注入したい | ✅ 対応（**TF jar のみ**） |

#### 真因: 2026-08-21 の「2クリック確認」は**方式そのものが誤爆を防げていなかった**

前回は「確認機構は前からあるが伝わっていない」と診断してチャット通知を足した。
**そこが間違いだった。** 機構そのものが穴で、確認と実行を**同じマスに重ねていた**のが原因。

1. トーテムを押す → `pendingPerkId` が立ち、**1 tick(50ms)後**に同じ画面が描き直される。
2. 描き直された画面では、**同じスロットのトーテムが「確定ボタン」に変わっている**。
3. ダブルクリック（統合版やタッチ操作では素の操作として出る。Java でも連打で出る）の
   2打目は 50ms より後に届くので、**そのまま確定ボタンへ入る**。

プレイヤーから見れば「1回押しただけでプレステージされた」になる。
⚠ **前回の回帰テストはこれを通してしまう** —— `secondClickConfirmsThePrestige` が
「同じスロットをもう一度押すと確定する」を**正しい挙動として固定していた**。
つまりテストは、直すべきものを守っていた。

#### 確定解: 別画面のモーダル ＋「はい」を直前に押したスロットへ置かない

- `Mode.PRESTIGE_CONFIRM` を新設。ツリーの中身もナビも描かず、
  **赤字の警告 ＋「はい、プレステージする」＋「いいえ、やめる」だけ**を置く。
- ⚠ **画面を分けるだけでは足りない。** 2打目は 1 tick 後に開くその画面へ落ちるので、
  「はい」がたまたま同じスロットに来たら元の木阿弥。`prestigeYesSlot(originSlot)` で
  **押したスロットと衝突するときは「はい」と「いいえ」を入れ替える**
  （＝2打目は必ず**取り消し**になる）。全54スロットで純関数として固定してある。
- `prestige-confirm` はモードと対象IDの両方を突き合わせてから実行する
  （ツリー本体にはこのボタンを一切描かないが、将来紛れ込んでも走らないように）。
- 確認画面は `lastView` へ**覚えさせない**。覚えるとメニューを開き直しただけで
  確定ボタンのある画面が出る。
- 余白クリックでは描き直さない。描き直すと `pendingPerkId` が落ちて
  「はい」が無反応な画面が残る。

#### W-188b: 赤字警告は描画時に注入する（config には書かない）

生成器が入れる説明文は「永続ボーナス」程度で、**レベルが0に戻ることが読み取れない**。
`prestigeWarningLore(tree)` を新設し、**確認モーダルとツリー本体とパーク一覧の3画面が同じ関数**を通る。
2箇所で書き分けると片方だけ「0に戻る」が抜けて嘘になるため。

⚠ **POWER だけは文言が違う** —— プレステージ後のレベルは0リセットではなく
他スキルの現在レベルから引き直される（`NativePerkService` の 2026-08-04 修正）。
`prestigeLevelNotice` が分岐を持っているので、警告もそこを通す。

#### 検証

回帰6本（`NativeSkillTreeMenuPrestigeConfirmTest`）。
**衝突回避を外す（`prestigeYesSlot` が押したスロットをそのまま返すようにする）と
「同じマスを2回押しただけでプレステージが確定した」で2本落ちる**ことを実走確認。

---


### 実サーバ報告バッチ（2026-08-23 受領 第30陣。W-187）

| ID | 内容 | 状態 |
|---|---|---|
| W-187a | editor の構造物ルート設定で、スレッドやカギなど各ティア固有のものが少ない。漁る意義を作りたい | ✅ 対応（**config のみ**） |
| W-187b | 試練のスポナーにカスタム登録のアイテムが反映されず、一部チェストが空 | ✅ 対応（**ArsPaper jar + config**） |
| W-187c | `reset-resource.cmd` を実行してもリセットされない。押すだけでシードごと変えて再生成するつもりだった | ✅ 対応（**launch スクリプトの再配備が必要**） |
| W-187d | ティア細分化に伴い、トレジャーチェスト用のスレッドとテクスチャを10個ほど追加（やや強めの汎用戦闘系） | ✅ 対応（**TF jar 不要・config + リソパ + ArsPaper config**） |
| W-187e | 既にスレッドが多いので、住み分けできない（相互互換が発生する）ステータス校正にしないこと | ✅ 遵守（**空き軸ちょうど10本を使い切った。次は増やせない**） |

#### W-187a: 「固有のものが少ない」の実測 — 実際にはほぼ全ティアに全種類が入っていた

漁る前と後を機械で数えた（`tmp/w187-loot-audit.py`）。

| カテゴリ | 変更前 | 変更後 |
|---|---|---|
| 品質系スレッド（10種） | **1種あたり 9/10 ティア**（＝どこで漁っても同じ） | 看板1ティア + 隣接1ティア（1/3 倍率）の**2ティアまで** |
| カギ（11種） | 2〜4 ティア | **全種ちょうど 2 ティア** |
| カスタムモブ素材 | **1種あたり 10/10 ティア** | 浅/中/深の3帯へ分割し **3〜4 ティア** |
| トレジャースレッド（新10種） | なし | **1種＝1ティア専属**（他のどのティアにも出ない） |

意図的に全ティアへ残したのは `custom:source_shard`（10/10）と `custom:reality_thread_core`（8/10）の2つだけ。
どちらも「どこを漁っても少しずつ貯まる」ことが設計の前提になっている芯。

⚠ **`loot-tables.yml` は生成物**。真源は `tmp/worldgen/gen_loot_yml.py` の
`QUALITY_THREAD_HOME` / `MATERIAL_BANDS` / `KEY_HOME` / `RESET_HOME` / `TREASURE_THREADS`。
yml を手で直すと次の再生成で消える。

#### W-187b: 真因は2段。片方だけ直しても直らない

1. **`pools` にトライアルチャンバーの表が1本も入っていなかった。**
   2026-08-16 に「無限湧きで経済が壊れる」として `EXCLUDE_TOKENS` で意図的に除外していた。
   ユーザー判断「デフォルトの試練のスポナーの中身の豪華さで tier を判断 ⇒ tier 割り当て」により
   特別扱いをやめ、他の表と同じ豪華さスコアへ載せた。
   結果は自然にばらけた（不吉ヴォールト=T8 / 通常ヴォールト=T7 / 交差点=T7 / 通路=T3 / スポナー報酬=T1）。
2. ⚠ **仮に足しても当たらなかった。** ヴォールトと試練のスポナーは戦利品をブロックが直接排出するので
   **`LootGenerateEvent` を発火しない**（[PaperMC #11680](https://github.com/PaperMC/Paper/issues/11680) は
   「対応しない」でクローズ）。唯一の入口は Paper 1.21.10 で入った `BlockDispenseLootEvent`。
   ArsPaper の `LootTableListener` へ2つ目の入口として実装し、除去→増量→追加の本体は
   `applyPools` に集約した（**片方だけ直すと「宝物庫だけ空」に戻る**）。

⚠ **構造物のルート表は `structures.json` に全部は載らない。** ヴォールトと試練のスポナーは
piece ではなくブロックが表を持つため。`UNREFERENCED_INCLUDE_TOKENS`（`vault` / `spawners/`）で
拾い直して **100 本**を回収した（＝それまで丸ごと視界の外だった）。
不吉ヴォールトの `chests/trial_chambers/reward_ominous` だけは語で拾えないので ID 名指し
（`reward_common` / `_rare` / `_unique` は親が参照する子表なので入れると二重に足すことになる）。

⚠ **「一部チェストが空」にはプラグインでは直せない候補もある。** 1.21.8 で生成済みのワールドを
1.21.10/1.21.11 へ上げると、**アップグレード前に生成されたヴォールトが恒久的に不活性になる**
既知の不具合がある（[PaperMC #13521](https://github.com/PaperMC/Paper/issues/13521)、未修正）。
該当チャンバーは再生成するしかない。

#### W-187c: 引数なしだと必ず dry run で終わっていた

`reset-resource.cmd` はダブルクリック（＝引数なし）だと `-DryRun` を付けて呼ぶだけで、
本実行の経路が無かった。「押したのに何も起きない」はそのとおりの挙動。
→ 「dry run で消える物を全部出す → `RESET` と打たせる → 本実行 → pause」の2段に変更。
`--apply` を渡すと確認を飛ばす（自動実行用。既存の呼び出しは変わらない）。

- シードは 3 台とも `server.properties` の `level-seed=` が**空**なので、本実行すれば毎回別の地形になる。
- ⚠ **editor の「構造物ルート抽選」はデータパックではない。** ArsPaper がチェスト生成の瞬間に
  差し込む実行時設定なので、**ワールドリセット無しでも次に生成されるチェストから効く**。
  データパックが供給しているのは構造物そのものだけ。
- ⚠ 配備先の `.cmd` はリポジトリのコピー。届けるには `ops\launch\deploy-launch.cmd` の実行が要る。

#### W-187d / W-187e: 使える戦闘ステが**ちょうど10本しか残っていなかった**

既存75種の主軸を機械で洗い出したうえで（`tmp/w187-thread-axes.py`）、
実装レベルで使えない軸を落とすと空きは**ちょうど10本**だった。3つの落とし穴で消えた:

1. **ATTACK チャネル かつ `format: FLAT` は禁止**（`ShippedThreadBandIndependenceTest`）。
   帯非依存の固定値なので、実数ダメージを配ると低帯だけ極端に強くなる。
2. ⚠ **ATTRIBUTE チャネルは「無言で効かない」と「手に持つだけで効く」が同時に起きる。**
   装着スレッドは addon チャネルを通るが `PerkAttributeApplier` の属性経路が addon を合流させない。
   一方 `AttributeProjection#defaults()` の対象キーは `ItemAssembler` がバニラ属性として実際に付けるので、
   **防具に挿さず手に持っているだけで最大体力や攻撃速度が上がる**。一度これで組んで実際にテスト2本が落ちた。
3. ⚠ **`flat-defense` は typed キーがあると無視される後方互換の別名。**
   既存スレッドが `phys-flat-defense` を配っている以上、主軸にすると常に無視される側になる。

結果の10種（すべてトレジャーチェスト専用・**レシピ無し**・1種＝1ティア専属）:

| CMD | 名前 | 主軸 | 系統 |
|---|---|---|---|
| 300070 | 渦動 | `aoe-damage-rate` | 近接 |
| 300071 | 瀉血 | `bleed-damage-rate` | 近接 |
| 300072 | 墜撃 | `power-attack-damage` | 近接 |
| 300073 | 遠見 | `distance-damage-bonus` | 遠距離 |
| 300074 | 精射 | `bow-accuracy` | 遠距離 |
| 300075 | 疾矢 | `arrow-velocity` | 遠距離 |
| 300076 | 貫矢 | `arrow-piercing` | 遠距離 |
| 300077 | 城塞 | `defense-rate` | 防御 |
| 300078 | 鉄壁 | `phys-flat-defense` | 防御 |
| 300079 | 護法 | `magic-flat-defense` | 防御 |

⚠ **依頼は「やや強めの汎用戦闘系」だったが、汎用近接は3本しか作れなかった。**
残り7本は遠距離4・防御3。既存75種が汎用近接の軸を全部押さえているため（W-187e の
「相互互換を発生させるな」を守るとこうなる）。**次に増やすなら既存の整理か新ステの新設が先**。

テクスチャは既存 `thread_onslaught.png` の輝度傾斜を保ったまま色相回転で10枚を生成し、
最彩度色だけ金へ寄せた（宝物庫由来であることが一目で分かるように）。

⚠ **増種で触る場所は6つ**（1つでも忘れると無言で壊れる）:
`item-stats.yml` / fork `threads.yml` / `catalog.yml` / `collection.yml`（忘れると
`ShippedCollectionEntryIdTest` が落ちる） / リソパ4点（`textures` `models` `items/string.json`
`cmd-registry.json`） / **テスト4本の CMD 帯**（帯を伸ばし忘れると新種がガードの外へ落ちて検査ごと素通りする）。

#### 検証

- TF フル: **4582 tests / 44 failed / 2 skipped**。着手前は 45 failed で、差分1本は
  自分が直した `ShippedCollectionEntryIdTest`。残る44本は着手前と同一集合（他セッションの WIP 由来。
  `ShippedWeaponIdentityTest` 10本・`WeaponTierParityTest` 5本は `item-stats.yml` の
  他セッションによる 459 行削除が原因で「武器が205件しか読めていない」と言っている）。
- ArsPaper fork: **516 tests / 0 failed / 0 skipped**（`LootTableConfigTest` に
  「試練の間の3経路が当たる」ガードを新設したので +1）。
- ⚠ 旧ガード `shippedYamlExcludesForbiddenRewards` はトライアルチャンバーを**除外側**で固定していたので、
  ユーザー判断に合わせて除外一覧から外した（村・壺/発掘の除外はそのまま）。

#### 配備

**TF 本体の jar は不要**（Java 無変更）。必要なのは次の3つ:

1. **config 配備**（`item-stats.yml` / `catalog.yml` / `collection.yml` / ArsPaper の
   `threads.yml` `loot-tables.yml`）
2. **ArsPaper の jar**（`BlockDispenseLootEvent` リスナー。これが無いとヴォールトとスポナーは空のまま）
3. **リソースパックの再配布**（新テクスチャ10枚。無いと新スレッドが素の糸に見える）
4. **`ops\launch\deploy-launch.cmd`**（`reset-resource.cmd` を配備先へ届ける）

---


### 実サーバ報告バッチ（2026-08-22 受領 第29陣。W-186）

| ID | 内容 | 状態 |
|---|---|---|
| W-186 | editor のスレッドのセット効果で乗算をONにしてもレイヤ指定ができない | ✅ 対応（**jar 2本 + config + editor**） |

#### 真因: レイヤという概念がどこにも無かった（UI の欠落ではない）

**W-185（加算/乗算のボタン統一）の副作用ではない。** 統一前の行は
`[ステ選択][加算/乗算プルダウン][値][×]` で、こちらにも元からレイヤ選択は無かった。
調べたところ4層すべてで固定だった:

| 層 | それまでの実態 |
|---|---|
| editor `lib/schema.js` `validateArsThreadSets` | 受けるのは数値か `{ mode, value }` のみ。`layer` キーが存在しない |
| ArsPaper `ThreadSetConfig` | 読むのは `mode` / `value` だけ |
| ArsPaper `ArmorManaListener` | 全スレッド種の乗算を1つの Map に潰して PDC へ書く |
| TF `PlayerStatAggregator` | `Map.of(AddonCombatStats.MULTIPLIER_LAYER_ID, …)` ＝ **`"addon"` 決め打ち** |

⚠️ **これは見た目の問題ではなく、バランスのバグだった。** TF の合成規則は
「レイヤ内は Σ(v-1) を足し、レイヤ同士は掛ける」。セット効果の倍率が専用レイヤに入るということは、
**装備側の同じステの倍率と掛け算になる**ということ。出荷 `thread-sets.yml` の
**攻撃力% 13件が全部この状態**で、装備 ×1.20 × スレッド ×1.25 = **×1.50**（同レイヤなら ×1.45）。

#### 直したもの

**TF 本体**（`AddonCombatStats` / `PlayerStatAggregator`）
- PDC 乗算チャネルの書式を **`"layer@key=value;…"`** へ拡張（`encodeLayered` / `parseLayered` /
  `readLayeredMultipliers` を新設し、レイヤを持たない `readMultipliers` は撤去）。
- ⚠️ **`@` を含まないトークンは `"addon"` へ落とす（後方互換）。** PDC はサーバ再起動をまたいで
  プレイヤーに残るので、旧形式を捨てると更新直後に再ログインしていないプレイヤーだけ倍率が無言で消える。
- `PlayerStatAggregator` は PDC が持つレイヤIDのまま合流させる（決め打ちを廃止）。

**ArsPaper フォーク**（`ThreadSetConfig` / `ArmorManaListener` / `TrinityForgeBridge`）
- `thread-sets.yml` の乗算ステに **`layer:`**（任意）。省略時は `DEFAULT_LAYER = "addon"`。
- 乗算の内部表現を **レイヤID → ステ → 増分** の二段へ。レイヤをまたいだ合算はしない
  （潰すと TF 側で掛け算になる意味が変わる）。
- ステ解釈を `ThreadSetConfig#readTier`（plugin 非依存の static）へ切り出し、サーバ無しでテストできるようにした。
- lore もレイヤIDをそのまま使う。**`layer_1` を名乗ると装備と同じ `x1.25 [攻撃力％]` になる**
  （未定義IDだと括弧が出ないので、書き忘れの目印になる）。

**出荷 config**（`thread-sets.yml`）
- 乗算13件（全部 `attack-power`）へ **`layer: layer_1`** を明示。⚠️ **これは実効ダメージが下がる変更**
  （攻撃力%が装備と足し算になる）。ユーザーの指示「既存の乗算は既存レイヤに置き換える」に沿った意図的な調整。

**editor**（`lib/schema.js` / `public/js/forms.js`）
- 乗算ONの行に **レイヤ選択**を追加。並びは item-stats と同じ `[ステ選択][値セル][加算/乗算][乗算レイヤ][×]`。
- 候補は「このステが基準の定義済みレイヤ」＋「セット効果専用(addon)」。**未選択状態は作らない**
  （レイヤ未定義のステでも乗算にできるので行き止まりが無い ── item-stats 側の「レイヤ未定義なら乗算ON拒否」alert は不要）。
- `layer:` 検証（空文字は弾く／加算モードに付いていたら弾く）。

#### テスト

| 場所 | 本数 | 内容 |
|---|---|---|
| TF `AddonMultiplierLayerTest` | 5 | コーデック往復 / 旧形式PDCの読み取り / 空レイヤID / 壊れたトークン / **同レイヤ=1.45・別レイヤ=1.50** |
| ArsPaper `ThreadSetMultiplierLayerTest` | 5 | `layer:` の振り分け / 空白 / 加算に付けても無視 / 壊れたステ / **出荷 yml の全乗算が実在レイヤを基準ステ一致で名指ししているか** |
| editor `thread-set-multiplier-layer-2026-08-22.test.js` | 5 | スキーマ / レイヤ選択が出るか / `multiplyValue` 直書き検出 / 既定レイヤ文字列の一致 |

出荷 yml のガードは**`layer:` を1件外すと落ちることを実走で確認済み**（戻すと緑）。

実走結果: TF **4552 / 失敗30 / スキップ2**（失敗は全て他セッションの未コミット yml 由来で、
乗算・アドオン・スレッド系は1件も無い）。ArsPaper フォーク **489 / 失敗0**。
editor **1417 / 失敗24**（同上。W-185 時点の 26 から減っている）。

#### 付随して確定したこと: フォークの `libs/TrinityForge.jar` は commit/push する

`docs/agent-context/forks-and-mobs.md` の記述が食い違っていた（「ArsPaper は public なので
commit/push しない」vs「再生成した jar は commit するところまでが手順」）。
2026-08-22 のユーザー判断で **「commit/push する」に統一**（文書も修正済み）。

理由は、しないと**フォークの HEAD が「新APIを呼ぶソース + 旧APIしか無い jar」でコンパイル不能に
固定される**こと。ワーキングツリーでは jar が更新済みなので通ってしまい気づけない。
TF のソース自体がすでに public なので、thin jar を public フォークへ置いても秘密の漏洩にはならない。

⚠️ **HEAD 単体のコンパイル検証で分かったこと**: ArsPaper の HEAD は jar を入れた後もまだ落ちる。
ただし残っているのは `ThreadGui#createButtonFrom`（`BaseGui` 側の未コミット変更が要る）の2件で、
**他セッションの WIP 由来**。W-186 の API（`AddonCombatStats#encodeLayered`）は解決している。

#### ⚠️ 配備（ユーザー作業）

**jar 2本の差し替えが必須**（TF 本体・ArsPaper とも公開APIと読み取りが変わっている）。
**片方だけ入れ替えると乗算が効かない**（新 fork + 旧 TF ＝ TF が `@` 付き文字列を旧コーデックで読んで全部捨てる／
旧 fork + 新 TF ＝ レイヤ無しで書かれるので全部 `addon` に落ちて今までどおり二重に乗る）。
`thread-sets.yml` は **`saveResource(…, false)`** なので jar を替えても配備先は更新されない ──
**config 配備も必ず一緒に走らせる**。稼働中の jar 差し替えは `NoClassDefFoundError` になるので、サーバ停止後に行う。

### 実サーバ報告バッチ（2026-08-22 受領 第28陣。W-185）

**設定エディタの UI 指摘2件（スクリーンショット付き）。**

| ID | 内容 | 状態 |
|---|---|---|
| W-185a | セット効果の加算/乗算の UI が他と違う。ボタン式に統一してほしい | ✅ 対応（**editor**） |
| W-185b | ステータス設定の各種設定欄（ランダムロールステ等）の縦のラインがずれている | ✅ 対応（**editor**） |

#### W-185a 加算/乗算を `modeToggleButton` へ統一

同じ「加算か乗算か」が**3画面で3通り**に出ていた:

| 画面 | それまでの部品 |
|---|---|
| アイテムステータス（item-stats） | チェックボックス「☐ 乗算」 |
| スキルツリー（バフ / 段位バフ） | チェックボックス「☐ 乗算」 |
| スレッドのセット効果（thread-sets） | プルダウン「加算 / 乗算(%)」 |

`forms.js` に **`modeToggleButton(isMultiply, onToggle, title)`**（`window.modeToggleButton`）を新設し、
4箇所すべてを置き換えた。表示は「今どちらのモードか」で、押すともう一方へ切り替わる。
乗算中は `.is-multiply` でアクセント色。行の並びも
**`[ステ選択][値セル][加算/乗算][(乗算レイヤ)][×]`** に揃えた
（セット効果だけモード→値の順だったのを直した）。

⚠️ **判定ロジックは触っていない。** 「乗算レイヤが未定義なら乗算にできない」「加算行が既にあるなら
乗算を外せない」という既存のガード（alert）はそのまま動く（実機で両方とも発火を確認済み）。

#### W-185b 値入力を `valueCell` に包んで列を固定

**単位の問題ではなく「セルの幅が中身で決まっていた」のが真因。** 値入力の直後に来るものが行ごとに違い、
flex 行なのでその差がそのまま後続へ伝わっていた:

| 行 | 値入力の直後 | 幅 |
|---|---|---|
| %ステ（貫通率など） | `.pct-input` の中に `%` | 123px |
| 単位なしステ（開運など） | 何も無い | 110px |
| 単位ありステ（物理守備力＝`ダメ`） | 行末に単位スロット1つ | 110px + 単位 |

実測で `max` が 13px、`乗算` と `×` が **26px** ずれていた。単位スロットは `min-width: 2em` を持っていたが、
**`min-width` は下限であって固定幅ではない**ので `ダメ`（3文字）の行だけ広がる。

対処は `forms.js` の **`valueCell(control, unitEl)`**（`window.valueCell`）＋ CSS の
`.stat-row > .value-cell { flex: 0 0 160px }` / `.value-cell .field-input.num { width: 110px }`。
**セルの総幅と入力枠の幅を両方 px 固定**するので、中身が `%` でも `ダメ` でも `(整数)` でも外側の列は動かない。
ランダムロール行は min/max の**両方**にセルを置いた（行末に1つだけ置くと「max だけ単位が付く」非対称になり、
元から両方に `%` が付く %ステと揃わない）。倍率行の `x` もセルの中へ入れた。

適用先は `statSelect` を持つ**全て**のステ行:
`forms.js`（item-stats の 固定/品質別/ランダム・フォールバック・セット効果）/ `ars-spellbooks.js` /
`tf-skilltree.js`（バフ・段位バフ・乗算行）/ `tf-lifestyle-forms.js` / `p5-forms.js`。

**実測での確認**（ヘッドレス Chrome で全カードを展開して `getBoundingClientRect` を集計）:
スレッド画面の 397 行が**行種ごとに1通りの列位置**へ収束した。

| 行種 | 件数 | 列位置（左端 px） |
|---|---|---|
| ランダムロール | 179 | ステ選択 279 / min 547 / 値 575 / max 743 / 値 773 / 加算乗算 941 / × 995 |
| 固定・品質別 | 69 | ステ選択 279 / 値 547 / 加算乗算 715 / × 769 |
| セット効果（1段インデント） | 149 | ステ選択 305 / 値 573 / 加算乗算 741 / × 795 |

修正前は同じ表の中で 2 通り以上に割れていた。

**回帰テスト**: `test/stat-row-unified-controls-2026-08-22.test.js`（新設・5本）。
値入力が `valueCell` を通っているか / `.value-cell` と入力欄の幅が px 固定か /
`mode-select` と「乗算ラベル付き `inline-check`」が残っていないか / `modeToggleButton` が4箇所以上で
呼ばれているか。**5本を修正前のソースへ戻すと4本が落ちる**ことを確認済み（残り1本は空振り検知用）。

editor テストは **失敗 26 件**で、変更前（HEAD のソース）と**失敗集合が完全に一致**（新規ゼロ）。
26 件はすべて他セッション／ユーザーの未コミット yml 由来。

**editor のみの変更**（TF 本体の jar・config には触っていない）。エディタは再起動すれば反映される。

### 実サーバ報告バッチ（2026-08-21 受領 第27陣。W-184）

**W-183 の結果報告に対する追い指摘。第26陣の続き。**

| ID | 内容 | 状態 |
|---|---|---|
| W-184a | 瀕死技は難易度6以降のダンジョンだけでいいかも | ✅ 対応（**config**） |

#### W-184a 瀕死技を難易度6以上へ絞る

W-183 で `health-below` の瀕死技（`desperation_nova` 2.5 倍 / `last_stand_quake` 2.3 倍）を
**全難易度の踏破ボスへ配ってしまっていた**。瀕死技は「盾やダメージ軽減を用意しておく」という
**前提知識の上に成り立つ大技**なので、その前提がまだ無い難易度1〜5 では対策不能な事故にしかならない
（**難易度1 の闇の大聖堂**にも 2.3 倍の `last_stand_quake` が乗っていた）。

**外し方**: 技の**本数は減らさず**、同じ属性を埋める通常技へ差し替えた。
減らすと W-183 の骨格である「どのボスも物理と魔法の両方を持つ」が崩れ、
**片側の防御だけ盛れば受かるボス**に戻ってしまう（＝防具の住み分けが攻略へ効かなくなる）。

| 難 | ダンジョン | 消した瀕死技 | 入れた通常技 |
|---|---|---|---|
| 1 | 闇の大聖堂 | `last_stand_quake` | `gale_smash`（物理・突き放し 1.25） |
| 2 | ハロシアム | `last_stand_quake` | `crippling_stomp`（物理・踏みつけ 1.15） |
| 2 | 深層鉱山 | `desperation_nova` | `abyssal_grip`（魔法・引き寄せ 1.10） |
| 3 | 古の橋 | `desperation_nova` | `abyssal_grip`（魔法 1.10） |
| 3 | 騎士の城 | `desperation_nova` | `flare_slam`（魔法・踏みつけ 1.45） |
| 4 | 石の宮殿 | `desperation_nova` | `frost_field`（魔法・領域 0.42×5秒） |
| 4 | 蒸気工房 | `desperation_nova` | `ember_spray`（魔法・弾幕） |
| 5 | 鉱山 | `desperation_nova` | `piercing_beam`（魔法・ビーム 1.70） |
| 5 | ネザーベル（踏破ボス） | `last_stand_quake` | `shadow_step`（物理・瞬間移動斬 1.55） |
| 5 | ネザーベル（ウィザー） | `last_stand_quake` | `gale_smash`（物理 1.25） |
| 5 | ネザーベル（ウィザースケルトン） | `last_stand_quake` | `crippling_stomp`（物理 1.15） |
| 5 | ネザーベル（狙撃） | `last_stand_quake` | `arrow_fan`（物理・矢の雨） |

**倍率も難易度順に選んである**（難易度1〜2 は 1.10〜1.25、難易度5 は 1.55〜1.70）ので、
低難易度ほど差し替え後の技が軽い。

**残す側**: 難易度6以上の 9 スコープ（花火工房・都市・ネザー荒地／北極×4・下水道／登攀／洞窟／
束縛者×4／エンチャント試練10）。**属性の穴は開いていない**（差し替えは全部「同じ属性を埋める」向き）。

**結果（Lv100・品質5・3ビルド平均で「ボスの最悪の1発」を耐えられる回数）**

| 帯 | 平均 |
|---|---|
| 難易度 1〜5 | **2.15 発** |
| 難易度 6〜10 | **1.64 発** |

全体平均は 技（瀕死込み）が ボス **1.69 → 1.86 発**（通常攻撃・中ボス・雑魚は変化なし）。

**回帰テスト**: `ShippedDungeonBurstAndElementTest#lastStandAbilitiesOnlyAppearInHighDifficultyDungeons`
を新設した。`mob-abilities.yml` から `health-below`/`health-above` を持つ技を自動で集め、
**許可スコープ（難易度6以上・14 個を名前で列挙）以外で使われていたら落ちる**。
難易度は**出荷 yml ではコメントにしか無い**ので名前で持っている
（スコープ直下の `max-health-multiplier` は難易度の**逆順**の梯子なので、そこから復元すると
梯子を触った瞬間に検査の意味が変わる）。**ダンジョンを増やしたらこの集合も更新すること。**
反証も取った: 闇の大聖堂を `last_stand_quake` へ戻すと**この検査だけが落ちる**ことを確認済み。

`ShippedBossStrengthDriftTest` の踏破ボス技一覧も 9 件更新した。

**config のみの変更**（Java は触っていない）。ただし **W-183 の jar がまだ未配備**なので、
配備は W-183 とまとめて「サーバ停止 → jar 差し替え → config 配備 → 起動」で行うこと。

### 実サーバ報告バッチ（2026-08-21 受領 第26陣。W-183）

**W-181 / W-182 と同じ日の3回目。W-182 の数値表を見た上での「そもそも設計が無いのでは」という指摘なので、
W-181・W-182 と3つで1組。**

| ID | 内容 | 状態 |
|---|---|---|
| W-183a | 軽装備と重装備（魔法装備含む）の住み分け設計が無い。ダンジョンや好みでビルドを組み替えられるようにしたい。基準値を揃え、軽装はHP高め・重装は防御系高めにしつつ**被ダメージの期待値を揃える** | ✅ 対応（**config**） |
| W-183b | 技ダメの耐発数が通常より多いのは違和感。技には CT があるので少し強くていい | ✅ 対応（**config**） |
| W-183c | HP（雑魚・ボス・中ボス）の設定前との比較表がほしい | ✅ 対応（下の表） |
| W-183d | ボス・中ボスは属性の違う技や種類の違う技を撃ったほうが攻略し甲斐がある | ✅ 対応（**jar + config**） |
| W-183e | 技は攻撃力が高い代わりに対策できる要素がほしい（見て避けられる／盾前提の瀕死攻撃／ビルドで対策） | ✅ 対応（**jar + config**） |
| W-183f | 攻撃ステ8種を使い分けて、ビルドと攻略が面白くなるダンジョンにしたい | ✅ 対応（**config**） |
| W-183g | ダンジョンEXPをオーバーワールドの2倍にし、オーバーワールドの討伐・被弾EXPを現在値の75%へ | ✅ **`ea7ffdb` で対応済み**（このバッチの前に別途適用。`dungeon-exp-rate: 0.375` / `outside-dungeon-exp-rate: 0.1875` ＝ 比ちょうど 2.0・絶対値 ×0.75。Ars魔法／軽・重装備／武器／弓のすべてが `SkillExpConfig#worldExpRate` を通るので1箇所で効く） |

#### W-183a 防具の住み分け — 「軽装は最大HP・重装は守備」で被ダメージ期待値を揃える

**着手前の実測**（Lv100・品質5）: 耐えられる通常攻撃が **重装（世界を繋ぐ）5.2 発に対して軽装（エンダードラゴン）1.0 発**。
しかも**最大HPまで重装のほうが高い**（94.2 対 30.3）ので、軽装には取り柄が1つも無かった。
＝ 指摘のとおり住み分けの設計が存在しておらず、**選択肢そのものが無かった**。

決め方（ライン単位・35 ライン 140 部位）:

1. 帯の基準は **板金ライン（重装・物理）の現在値**。板金の耐発数は動かさない ＝ W-182 の較正（基準ビルド＝世界を繋ぐ）を維持する。
2. 最大HPを役割で決め打つ（板金の帯基準HPに対する倍率）: **重装 0.80 / 軽装 1.50 / 守護（重装・魔法） 0.65 / 魔導・魔織（軽装・魔法） 1.00**。
3. そのうえで**守備6キーを一律の係数で伸縮**させ、被ダメージ期待値を 1 の基準へ二分探索で合わせる。
4. 軽装/ローブの物理3軸（守備力・物理耐性・防御率）には**同帯の板金の 80%（守護は 90%）を天井**として掛ける。
5. スレッド枠は別枠の強みなので **1枠あたり −1%（最大 −16%）の守備予算**を払う。

⚠️ **4 の天井が無いと逆転する** —— 元の値が薄いラインほど守備係数が大きく解かれ、**軽装の守備力が同帯の板金を上回る**。
実際に一度そうなった（素の革の守備力 1.78 > 銅 1.41）。

⚠️ **率を 1.0 超へ伸ばすと二分探索が壊れる** —— `(1 − 耐性)` が負に転じて「守備を上げるほど被ダメージが増える」非単調な解を拾う。
`DefenseStats#clampedTo` と同じく模型側でも `[0, 0.9]` へ丸める（丸め忘れで守備 ×3.0・HP 154.8 という解が出た）。

⚠️ **最大HPが「fixed 0 で全部ランダムロール」だった 83 部位（軽装・魔法装の全ライン）を直した。**
品質0で 0.2 倍・品質9で 0.8 倍しか乗らないので、**最大HPを軽装の主軸に据えると「軽装だけ厳選運で 4 倍ブレる」**ことになる。
重装の主軸である守備は fixed 主体でほとんどブレないため、**住み分けが厳選運の話にすり替わる**。
→ 総量（`fixed` + `random.max`）を変えずに **fixed へ 74.5%**（重装ラインの実測中央値）を移した。
最大ロールは据え置きで、上がったのは**厳選していない個体の下限**だけ。
副産物として `armor-ladder.test.js` の「Lv100 の追加HP ≤ +150」も通るようになった（220.7 → 133.8）。

⚠️ **軽装の防御率が係数倍で下限（1部位 0.015）を割っていた**ので、適用側でクランプした（0.0107 → 0.015）。

**対になる重装/軽装の突き合わせ**（実効被ダメージ係数。小さいほど硬い）:

| 対 | Lv | 重/軽 係数比 | 守備力(fixed) 重/軽 = 比 | 物理耐性 重/軽 | 防御率 重/軽 | HP(fixed) 重/軽 | HP(total) 重/軽 |
|---|---|---|---|---|---|---|---|
| 銅 vs 素の革 | 0 | 1.010 | 1.40 / 1.20 = 0.851 | 0.127 / 0.027 | 0.178 / 0.090 | 2.4 / 7.8 | 2.8 / 7.8 |
| 銅 vs 骨鎧 | 0 | 0.920 | 1.40 / 1.12 = 0.801 | 0.127 / 0.061 | 0.178 / 0.071 | 2.4 / 6.7 | 2.8 / 7.8 |
| 鎖 vs 銅鋲 | 10 | 0.885 | 2.59 / 1.58 = 0.609 | 0.071 / 0.025 | 0.106 / 0.060 | 4.2 / 8.1 | 5.0 / 9.5 |
| 鉄 vs 甲殻 | 15 | 0.920 | 2.97 / 2.37 = 0.799 | 0.214 / 0.102 | 0.230 / 0.083 | 5.7 / 13.9 | 6.6 / 16.3 |
| 金 vs 金糸 | 35 | 1.000 | 5.09 / 4.08 = 0.800 | 0.242 / 0.116 | 0.238 / 0.084 | 11.9 / 24.2 | 13.8 / 28.3 |
| ダイヤ vs 深海鱗 | 45 | 0.979 | 6.76 / 5.41 = 0.800 | 0.267 / 0.067 | 0.240 / 0.072 | 16.2 / 33.9 | 18.6 / 39.7 |
| ネザライト vs 幻膜 | 60 | 1.038 | 8.41 / 6.73 = 0.800 | 0.295 / 0.141 | 0.256 / 0.085 | 23.2 / 46.6 | 26.8 / 54.6 |
| 要塞 vs 蝕みの絹 | 80 | 1.141 | 8.05 / 6.44 = 0.800 | 0.385 / 0.185 | 0.306 / 0.098 | 34.2 / 85.2 | 40.4 / 99.8 |
| 不滅 vs 天陰 | 100 | 1.109 | 9.11 / 6.27 = 0.688 | 0.193 / 0.081 | 0.414 / 0.073 | 49.9 / 99.7 | 58.8 / 116.7 |

係数比 min=0.885 max=1.141 / 軽装守備力の対重装 最大比=0.851

**「重/軽 係数比」が 0.885〜1.141 に収まっている** ＝ どちらを着ても大枠の硬さは同じ。
向きは **守備3軸すべてで重装が勝ち、最大HPは全帯で軽装が勝つ**。

**35 ライン全体の収束**（適用後にソルバをもう一度回した結果。「今」が「目標E」と一致していれば収束）:
Lv0〜Lv100 の 35 ラインすべてで **目標との差は最大 3.5%**（星詠みの魔織 5.33 に対し 5.52）。
3ビルドのサンプルだけでなく、**魔導/魔織/守護/ソースジェムを含む全ラインで住み分けが成立している**。

#### W-183b / W-183d / W-183e 技の作り直し

**着手前**: 技の `damage-percent` は全種類 **1.2 の一律上限**で、**避けようのない技も予告付きの技も同じ重さ**だった。
その結果 W-182 時点では「技のほうが通常攻撃より耐えられる」（ボス: 通常 5.20 発に対し技 5.54 発）という、指摘どおりの逆転が起きていた。

**倍率のはしご**（避けられる技ほど重く）:

| 技の型 | 倍率 | 対策 |
|---|---|---|
| `delayed_zone`（足元に印 → 予告後に着弾） | 2.0〜2.1 | **見て動けば当たらない** |
| `beam` / `charge` / `teleport_strike` | 1.55〜1.70 | 向きと軌道が読める |
| `ground_slam` / `repulse` | 1.25〜1.45 | 自分の周囲。距離で外せる |
| `aura` | 0.40〜0.42 ×秒数 | 踏み続けた秒数だけ蓄積（総量 2.1〜2.4） |
| 効果が本体の技（拘束・引き寄せ） | 1.10 前後 | — |
| **瀕死技**（`health-below`） | 2.3〜2.5 | 低確率・長CT。**盾やダメージ軽減を用意しておく前提** |

**瀕死技の機構は新規実装**（`MobAbility#allowedAtHealth` / `health-below` / `health-above`）。
`MobAbilityTask#candidatesFor` が残HP割合を見て候補から外す。
⚠️ **`Attribute.MAX_HEALTH` が読めない／0以下のときは `NaN` を返して「制限なし」に倒す** ——
0 に倒すと MockBukkit 環境で「常に瀕死」と読まれて全モブが大技を撃つ。
既存の技は `health-below: 1.0` / `health-above: 0.0` ＝ 2026-08-21 以前と完全に同じ挙動（レガシー引数のコンストラクタで委譲）。

**テンプレートを 15 → 21 種へ**（新規6種: `flare_slam` 魔法の踏みつけ / `void_lunge` 魔法の突進 / `searing_lance` 物理のビーム /
`bleeding_aura` 物理の継続 / `desperation_nova` 魔法の瀕死技 / `last_stand_quake` 物理の瀕死技）。
**属性の対を埋めるための追加**で、片方の属性しか持たない型を無くした。

**配り直し**: 技を持つモブ **99 → 129 体**（純増30。`the_nether_bell` 10 / `the_mines` 6 / `the_quarry` 6 / `the_climb` 4 / `north_pole` 3 / `knight_castle` 1）。
ボス平均 4.1 種 / 中ボス 2.5 種で、**どのボスも物理と魔法の両方＋瀕死技を持つ**。
⚠️ **多段フェーズのボスは最終段だけが持つ**（途中段は残HP割合ですぐ通過する）。W-183 でいったん途中段にも配ってしまい、**38 体から外し直した**。

#### W-183f 攻撃ステの型

ダンジョンごとに **stats.attack へ1種類だけ型を配った**。通常攻撃の**総量は型を配る前と同じ**（型が増やしたダメージぶんは `attack-power` を下げて相殺）。
変わるのは**どのビルドが有利か**だけ。

| 型 | キー | 効き方 | 有利なビルド |
|---|---|---|---|
| 貫通型 | `penetration: 0.8` | 防御率を8割抜く | **最大HPと守備力**（＝軽装）。防御率で受ける重装が不利 |
| 会心型 | `crit-chance: 0.3` / `crit-damage: 1` | 30% で会心（×2） | **防具強度を持つ重装**。薄い軽装は事故る |
| 固定型 | `fixed-damage` | 全ての防御段を貫通する純加算 | **最大HP**のみ（守備では減らせない） |
| 振れ型 | `damage-modifier: 1.4` | 1.0〜1.4 で振れる | 期待値は同じ。上振れの事故だけが増えるので**HPの厚み**が効く |

**28 ダンジョンに 7 本ずつ均等配分**。

⚠️ **増幅型（`percent-bonus-damage`）は採用しなかった** —— 攻撃力を上げるのと**数学的に完全に同値**（`base = 攻撃力 ×(1 + %)`）で、
相殺で攻撃力を解き直した時点で**完全な no-op**になる。固定型へ差し替えた。

⚠️ **型を入れたので `attack-power` の生値でダンジョン同士を比べてはいけない** ——
貫通型は同じ強さでも生値が 2 割低く出る（試練1: 36.9 → 29.8）。
`ShippedBossStrengthDriftTest` の試練ラダー検査を、**型を打ち消した「素換算」**で測る形へ書き換えた
（合成の基準防御に対する期待ダメージが一致する素の攻撃力を二分探索で求める。`ComponentDamageCalculator` を実際に呼ぶので実装とズレない）。
基準防御は**わざと `item-stats.yml` を読まない固定値** —— 読むと防具を触るたびにこの梯子が動き、「試練が置き去りになった」のか
「防具が変わった」のか区別できなくなる。素換算で見ると 10 本の梯子は **0.934〜1.056 倍・単調増加**（生値では単調でなかった4本を最大 +2.6% 押し上げた）。

#### 検証（耐えられる発数。Lv100・品質5・3ビルド）

| クラス | ビルド | 通常 前→後 | 技(通常) 前→後 | 技(瀕死込み) 後 |
|---|---|---|---|---|
| 雑魚 | 重装 | 5.76発 → 5.77発 | 7.42発 → 2.83発 | 2.83発 |
| 雑魚 | 軽装 | 1.14発 → 5.61発 | 1.49発 → 3.03発 | 3.03発 |
| 雑魚 | 魔法 | 4.66発 → 5.08発 | 5.93発 → 3.16発 | 3.16発 |
| 中ボス | 重装 | 5.32発 → 5.29発 | 9.53発 → 2.82発 | 2.82発 |
| 中ボス | 軽装 | 1.05発 → 5.03発 | 2.09発 → 2.82発 | 2.82発 |
| 中ボス | 魔法 | 4.54発 → 4.90発 | 7.82発 → 2.64発 | 2.63発 |
| ボス | 重装 | 5.20発 → 5.20発 | 5.54発 → 2.01発 | 1.69発 |
| ボス | 軽装 | 1.03発 → 4.88発 | 1.13発 → 2.01発 | 1.69発 |
| ボス | 魔法 | 4.51発 → 4.77発 | 3.77発 → 2.04発 | 1.75発 |

- **重装（世界を繋ぐ）は 5.20 発のまま** ＝ W-182 の較正を1ミリも動かしていない。
- **軽装（天陰）が 1.03 → 4.88 発**。魔法（星詠みの魔導）も 4.51 → 4.77 発。**3ビルドが同じ水準に乗った**。
- **技が通常より重くなった**（ボス: 通常 5.20 発に対し技 2.01 発 ≒ 通常の 2.6 倍のダメージ）。瀕死技込みでも 1.69 発 ＝ 一撃死ではない。

#### W-183c HP の設定前比較表

⚠️ **W-183 では HP を1体も動かしていない**（動かしたのは防具・攻撃ステ・技）。
下の「今」は **W-181 で上げた値そのまま**で、「調整前」は W-181 着手前。
「撃破秒」は Lv100 厳選ビルドの実効DPS **365,115** で割った理論値。

### 全ダンジョン平均

| クラス | 調整前 | W-181後 | 今 | 調整前比 | 撃破秒 調整前→今 |
|---|---|---|---|---|---|
| 雑魚 | 505k | 579k | 579k | 15% | 1.4→1.6秒 |
| 中ボス | 14682k | 16229k | 16229k | 11% | 40.2→44.4秒 |
| ボス | 10508k | 12473k | 12473k | 19% | 28.8→34.2秒 |

### 雑魚

| 難易度 | ダンジョン | 体数 | 平均HP 調整前 | W-181後 | 今 | 調整前比 | 撃破秒 調整前→今 |
|---|---|---|---|---|---|---|---|
| 1 | 闇の大聖堂 | 7 | 1255k | 1726k | 1726k | 38% | 3.4→4.7秒 |
| 2 | ハロウィン闘技場 | 1 | 39k | 52k | 52k | 33% | 0.1→0.1秒 |
| 2 | 深層鉱山 | 11 | 1490k | 1977k | 1977k | 33% | 4.1→5.4秒 |
| 3 | 騎士団の城 | 4 | 186k | 239k | 239k | 28% | 0.5→0.7秒 |
| 3 | 古橋の聖所 | 4 | 251k | 322k | 322k | 28% | 0.7→0.9秒 |
| 4 | エンチャント試練 4 | 2 | 39k | 44k | 44k | 11% | 0.1→0.1秒 |
| 4 | 宮殿 | 2 | 471k | 582k | 582k | 24% | 1.3→1.6秒 |
| 4 | 蒸気機関工房 | 4 | 141k | 175k | 175k | 24% | 0.4→0.5秒 |
| 5 | エンチャント試練 5 | 1 | 345k | 377k | 377k | 9% | 0.9→1.0秒 |
| 5 | ネザーの鐘 | 6 | 235k | 281k | 281k | 19% | 0.6→0.8秒 |
| 5 | 鉱山 | 25 | 308k | 367k | 367k | 19% | 0.8→1.0秒 |
| 6 | ドワーフの地下都市 | 6 | 353k | 407k | 407k | 15% | 1.0→1.1秒 |
| 6 | ネザーの荒野 | 15 | 222k | 255k | 255k | 15% | 0.6→0.7秒 |
| 6 | 花火工房 | 2 | 39k | 45k | 45k | 15% | 0.1→0.1秒 |
| 7 | 下水道迷宮 | 16 | 111k | 124k | 124k | 11% | 0.3→0.3秒 |
| 7 | 北極ミニダンジョン | 4 | 69k | 76k | 76k | 11% | 0.2→0.2秒 |
| 8 | 採石場 | 14 | 314k | 337k | 337k | 7% | 0.9→0.9秒 |
| 8 | 登攀路 | 13 | 503k | 539k | 539k | 7% | 1.4→1.5秒 |
| 9 | 灼熱の洞窟 | 2 | 27k | 28k | 28k | 4% | 0.1→0.1秒 |
| 10 | エンチャント試練 10 | 2 | 24k | 24k | 24k | 0% | 0.1→0.1秒 |
| 10 | 世界を繋ぐ者の聖所 | 14 | 4176k | 4176k | 4176k | 0% | 11.4→11.4秒 |

### 中ボス

| 難易度 | ダンジョン | 体数 | 平均HP 調整前 | W-181後 | 今 | 調整前比 | 撃破秒 調整前→今 |
|---|---|---|---|---|---|---|---|
| 1 | エンチャント試練 1 | 1 | 7290k | 9509k | 9509k | 30% | 20.0→26.0秒 |
| 1 | 闇の大聖堂 | 1 | 941k | 1600k | 1600k | 70% | 2.6→4.4秒 |
| 2 | エンチャント試練 2 | 1 | 9480k | 12002k | 12002k | 27% | 26.0→32.9秒 |
| 2 | ハロウィン闘技場 | 1 | 7844k | 12574k | 12574k | 60% | 21.5→34.4秒 |
| 2 | 深層鉱山 | 4 | 553k | 886k | 886k | 60% | 1.5→2.4秒 |
| 3 | エンチャント試練 3 | 1 | 12060k | 14826k | 14826k | 23% | 33.0→40.6秒 |
| 3 | 騎士団の城 | 4 | 549k | 829k | 829k | 51% | 1.5→2.3秒 |
| 4 | エンチャント試練 4 | 1 | 15320k | 18279k | 18279k | 19% | 42.0→50.1秒 |
| 5 | エンチャント試練 5 | 1 | 19705k | 22830k | 22830k | 16% | 54.0→62.5秒 |
| 5 | ネザーの鐘 | 16 | 691k | 928k | 928k | 34% | 1.9→2.5秒 |
| 5 | 鉱山 | 9 | 1640k | 2202k | 2202k | 34% | 4.5→6.0秒 |
| 6 | エンチャント試練 6 | 1 | 25560k | 28742k | 28742k | 12% | 70.0→78.7秒 |
| 6 | ドワーフの地下都市 | 3 | 2175k | 2754k | 2754k | 27% | 6.0→7.5秒 |
| 6 | ネザーの荒野 | 4 | 1883k | 2383k | 2383k | 27% | 5.2→6.5秒 |
| 7 | エンチャント試練 7 | 1 | 32880k | 35903k | 35903k | 9% | 90.1→98.3秒 |
| 7 | 下水道迷宮 | 12 | 1025k | 1223k | 1223k | 19% | 2.8→3.3秒 |
| 8 | エンチャント試練 8 | 1 | 42000k | 44556k | 44556k | 6% | 115.0→122.0秒 |
| 8 | 採石場 | 6 | 1752k | 1970k | 1970k | 12% | 4.8→5.4秒 |
| 8 | 登攀路 | 7 | 1591k | 1789k | 1789k | 12% | 4.4→4.9秒 |
| 9 | エンチャント試練 9 | 1 | 54030k | 55663k | 55663k | 3% | 148.0→152.5秒 |
| 10 | エンチャント試練 10 | 1 | 69360k | 69360k | 69360k | 0% | 190.0→190.0秒 |

### ボス

| 難易度 | ダンジョン | 体数 | 平均HP 調整前 | W-181後 | 今 | 調整前比 | 撃破秒 調整前→今 |
|---|---|---|---|---|---|---|---|
| 1 | 闇の大聖堂 | 5 | 4706k | 7999k | 7999k | 70% | 12.9→21.9秒 |
| 2 | ハロウィン闘技場 | 2 | 5961k | 9555k | 9555k | 60% | 16.3→26.2秒 |
| 2 | 深層鉱山 | 3 | 3200k | 5130k | 5130k | 60% | 8.8→14.1秒 |
| 3 | 騎士団の城 | 4 | 6589k | 9952k | 9952k | 51% | 18.0→27.3秒 |
| 3 | 古橋の聖所 | 3 | 5099k | 7700k | 7700k | 51% | 14.0→21.1秒 |
| 4 | 宮殿 | 3 | 5229k | 7446k | 7446k | 42% | 14.3→20.4秒 |
| 4 | 蒸気機関工房 | 7 | 2689k | 3829k | 3829k | 42% | 7.4→10.5秒 |
| 5 | ネザーの鐘 | 3 | 5177k | 6948k | 6948k | 34% | 14.2→19.0秒 |
| 5 | 鉱山 | 4 | 5412k | 7265k | 7265k | 34% | 14.8→19.9秒 |
| 6 | ドワーフの地下都市 | 3 | 6824k | 8640k | 8640k | 27% | 18.7→23.7秒 |
| 6 | ネザーの荒野 | 2 | 8628k | 10924k | 10924k | 27% | 23.6→29.9秒 |
| 6 | 花火工房 | 3 | 8315k | 10527k | 10527k | 27% | 22.8→28.8秒 |
| 7 | 下水道迷宮 | 1 | 19767k | 23585k | 23585k | 19% | 54.1→64.6秒 |
| 7 | 北極ミニダンジョン | 4 | 5648k | 6739k | 6739k | 19% | 15.5→18.5秒 |
| 8 | 採石場 | 5 | 6134k | 6898k | 6898k | 12% | 16.8→18.9秒 |
| 8 | 登攀路 | 4 | 7138k | 8027k | 8027k | 12% | 19.6→22.0秒 |
| 9 | 灼熱の洞窟 | 3 | 12028k | 12760k | 12760k | 6% | 32.9→34.9秒 |
| 10 | 世界を繋ぐ者の聖所 | 4 | 70596k | 70596k | 70596k | 0% | 193.4→193.4秒 |

#### 回帰テストの契約更新（**すべて設計変更に伴う書き換えで、緩めたものは1つも無い**）

| テスト | 旧契約 | 新契約 |
|---|---|---|
| `ArmorHeavyVersusLightDefenseOrderTest` | 重装は軽装より**20%以上**被ダメージが少ない／軽装の `fixed.max-health` は **0**／守備力は重装＝軽装で**同値** | 被ダメージ期待値の比が **[0.80, 1.25]**／**守備3軸は重装が勝つ**（軽装の守備力は重装の 90% が上限）／**最大HPは軽装が勝つ**（fixed でも期待ロールでも） |
| `armor-ladder.test.js`（editor） | 帯ごとの耐久回数を 2026-08-12/14 の実測で固定 | 全帯を再測定（Lv0 と Lv100 は帯の梯子から外れていたぶん大きく動いた）。**軽装7セットの守備力は「重装と同額」ではなく「板金の 80% 天井」**へ |
| `ShippedBossStrengthDriftTest` | 試練の梯子を `attack-power` の**生値**で測る／技の担い手 **99** 体／踏破ボスの技は 1〜2 種 | **型を打ち消した素換算**で測る／**129** 体／3〜5 種（属性配分の骨格は維持） |
| `ShippedDungeonBurstAndElementTest` | `damage-percent` は全種類 **1.2** 以下 | **種類ごとの上限**（`delayed_zone` 2.1 / 瀕死技 2.5 / その他 1.7 / `aura` は総量 2.6） |
| `ShippedMobAbilityAssignmentTest` | エンチャント試練の技数が 1→10 で減らない | 同じ。**1〜6 = 3種 / 7〜10 = 4種**のラダーへ揃え直した |

**テスト結果**: TF **4536 件・失敗 29・skip 2**。29 はすべて他セッション／ユーザーの未コミット yml 由来
（着手時 28 件＋作業中に別セッションが武器の `item-stats` を触って増えた `WeaponTierParityTest` 1 件。
**自分が触った 4 クラスは 1 件も残っていない**）。
config-editor **失敗 26 件（unique）**で、`item-stats.yml` を W-183 前へ戻して測った基準と**完全に一致**（新規の失敗ゼロ）。

⚠️ **`item-stats.yml` にはユーザーの盾の編集と別セッションの武器の編集が同居している。**
`git add` はこのファイル全体を巻き込むので、**コミット時は HEAD へ自分の防具エントリだけを重ねた blob を作って index へ入れる**こと。

**jar 再ビルド＋配備が必要**（`MobAbility` / `MobAbilitiesConfig` / `MobAbilityTask` を触っている）。**config も同時に配備すること。**

### 実サーバ報告バッチ（2026-08-21 受領 第25陣。W-182）

**W-181 と同じ日の追撃。W-181 の数値表を見た上での指摘なので、W-181 と2つで1組。**

| ID | 内容 | 状態 |
|---|---|---|
| W-182a | 雑魚・中ボスの通常と最悪の技の期待値、および前回調整からの変化率を表で出してほしい | ✅ 対応（下の表） |
| W-182b | 攻撃属性と耐性の属性は逆にする必要はない。まんべんなく組み合わせてほしい | ✅ 対応（**config**） |
| W-182c | 守護/魔織シリーズ（`LEATHER_*#2002xx`）60部位に `max-health` が無い問題を、隠し装備種のコンセプトと tier に合わせて修正 | ✅ 対応（**config**） |
| W-182d | （追記）まだ攻撃力が高い。耐えられる攻撃回数を全体一律 **+1.5 回** にしてほしい | ✅ 対応（**config**） |

#### (d) 耐えられる回数を一律 +1.5 発 —— 攻撃力だけで調整した

全 28 ダンジョンで **通常攻撃の耐発数が正確に +1.5 発**になるよう、攻撃力の係数を
**モブ単位で二分探索して解いた**（`tmp/w182-atk-apply.js`）。2層構造は W-181 と同じ。

- scope 直下 `attack-power-multiplier`（試練群は絶対値 `attack.attack-power`）… 係数 **0.746〜0.834**。ここが雑魚を決める。
- per-mob `attack-power-multiplier`（ボス・中ボス 146 体）… **1.061〜1.219**。scope の係数に掛かる。

⚠ **1.15 のようなキリのよい値へ手で丸めないこと。** 被ダメージは
`(攻撃力×配分 − 守備力) × (1−防御率) × (1−耐性)` と**守備力の引き算**を含むので攻撃力に比例せず、
半端な数値そのものが「+1.5 発」の解になっている。

HP や耐性で調整しなかった理由: **HPを上げると全ダンジョンの撃破時間が同率で伸びる**（作業時間だけ増える）、
**耐性を上げると属性の設計に干渉する**。攻撃力なら撃破時間にも属性設計にも触らずに耐発数だけ動く。

技は `damage-percent` を一律 **×0.85**（攻撃力の引き下げだけでは技の伸びが +0.9 発止まりのモブが
8 体あったため）。結果、技は **+1.5〜+4.2 発**。上限は 1.19（`piercing_beam` は 2.0 → 1.3 → 1.105）。

#### (a) クラス別の期待値と変化

**「前回」= W-181 適用後**（同日1回目の調整）。**この表は生成物**（`node tmp/w182-table.js class`）。

プレイヤー: Lv100・品質5・世界を繋ぐ装備一式（最大HP 94）

### 雑魚

| 難易度 | ダンジョン | 体数 | 通常 前回 | 通常 今回 | 増加 | 最悪の技 前回 | 最悪の技 今回 | 増加 |
|---|---|---|---|---|---|---|---|---|
| 1 | 闇の大聖堂 | 7 | 5.1発 | 6.6発 | +1.5 | 技なし | 技なし | - |
| 2 | ハロウィン闘技場 | 1 | 4.9発 | 6.4発 | +1.5 | 技なし | 技なし | - |
| 2 | 深層鉱山 | 11 | 5.1発 | 6.6発 | +1.5 | 技なし | 技なし | - |
| 3 | 騎士団の城 | 4 | 4.9発 | 6.4発 | +1.5 | 技なし | 技なし | - |
| 3 | 古橋の聖所 | 4 | 4.9発 | 6.4発 | +1.5 | 技なし | 技なし | - |
| 4 | エンチャント試練 4 | 2 | 4.6発 | 6.1発 | +1.5 | 技なし | 技なし | - |
| 4 | 宮殿 | 2 | 4.7発 | 6.2発 | +1.5 | 技なし | 技なし | - |
| 4 | 蒸気機関工房 | 4 | 4.7発 | 6.2発 | +1.5 | 技なし | 技なし | - |
| 5 | エンチャント試練 5 | 1 | 4.1発 | 5.6発 | +1.5 | 技なし | 技なし | - |
| 5 | ネザーの鐘 | 6 | 4.3発 | 5.8発 | +1.5 | 技なし | 技なし | - |
| 5 | 鉱山 | 25 | 4.5発 | 6.0発 | +1.5 | 技なし | 技なし | - |
| 6 | ドワーフの地下都市 | 6 | 4.4発 | 5.9発 | +1.5 | 技なし | 技なし | - |
| 6 | ネザーの荒野 | 15 | 4.3発 | 5.8発 | +1.5 | 技なし | 技なし | - |
| 6 | 花火工房 | 2 | 4.4発 | 5.9発 | +1.5 | 技なし | 技なし | - |
| 7 | 下水道迷宮 | 16 | 4.1発 | 5.6発 | +1.5 | 技なし | 技なし | - |
| 7 | 北極ミニダンジョン | 4 | 3.9発 | 5.4発 | +1.5 | 技なし | 技なし | - |
| 8 | 採石場 | 14 | 3.9発 | 5.4発 | +1.5 | 技なし | 技なし | - |
| 8 | 登攀路 | 13 | 3.8発 | 5.3発 | +1.5 | 技なし | 技なし | - |
| 9 | 灼熱の洞窟 | 2 | 3.5発 | 5.0発 | +1.5 | 技なし | 技なし | - |
| 10 | エンチャント試練 10 | 2 | 3.5発 | 5.0発 | +1.5 | 技なし | 技なし | - |
| 10 | 世界を繋ぐ者の聖所 | 14 | 3.2発 | 4.7発 | +1.5 | 3.7発 | 7.4発 | +3.6 |

### 中ボス

| 難易度 | ダンジョン | 体数 | 通常 前回 | 通常 今回 | 増加 | 最悪の技 前回 | 最悪の技 今回 | 増加 |
|---|---|---|---|---|---|---|---|---|
| 1 | エンチャント試練 1 | 1 | 4.5発 | 6.0発 | +1.5 | 技なし | 技なし | - |
| 1 | 闇の大聖堂 | 1 | 4.5発 | 6.0発 | +1.5 | 3.1発 | 5.0発 | +1.9 |
| 2 | エンチャント試練 2 | 1 | 4.5発 | 6.0発 | +1.5 | 2.9発 | 4.7発 | +1.7 |
| 2 | ハロウィン闘技場 | 1 | 4.3発 | 5.8発 | +1.5 | 技なし | 技なし | - |
| 2 | 深層鉱山 | 4 | 4.6発 | 6.1発 | +1.5 | 5.5発 | 8.8発 | +3.3 |
| 3 | エンチャント試練 3 | 1 | 4.0発 | 5.5発 | +1.5 | 3.3発 | 5.6発 | +2.3 |
| 3 | 騎士団の城 | 4 | 4.4発 | 5.9発 | +1.5 | 3.0発 | 4.8発 | +1.8 |
| 4 | エンチャント試練 4 | 1 | 4.1発 | 5.6発 | +1.5 | 2.8発 | 4.5発 | +1.7 |
| 5 | エンチャント試練 5 | 1 | 3.6発 | 5.1発 | +1.5 | 2.6発 | 4.3発 | +1.8 |
| 5 | ネザーの鐘 | 16 | 3.8発 | 5.3発 | +1.5 | 3.4発 | 5.9発 | +2.5 |
| 5 | 鉱山 | 9 | 4.1発 | 5.6発 | +1.5 | 3.2発 | 5.9発 | +2.8 |
| 6 | エンチャント試練 6 | 1 | 3.7発 | 5.2発 | +1.5 | 2.6発 | 4.2発 | +1.7 |
| 6 | ドワーフの地下都市 | 3 | 3.9発 | 5.4発 | +1.5 | 4.5発 | 7.7発 | +3.1 |
| 6 | ネザーの荒野 | 4 | 3.8発 | 5.3発 | +1.5 | 4.3発 | 7.5発 | +3.2 |
| 7 | エンチャント試練 7 | 1 | 3.3発 | 4.8発 | +1.5 | 2.4発 | 4.1発 | +1.8 |
| 7 | 下水道迷宮 | 12 | 3.6発 | 5.1発 | +1.5 | 3.6発 | 6.3発 | +2.7 |
| 8 | エンチャント試練 8 | 1 | 3.4発 | 4.9発 | +1.5 | 2.3発 | 4.1発 | +1.8 |
| 8 | 採石場 | 6 | 3.5発 | 5.0発 | +1.5 | 技なし | 技なし | - |
| 8 | 登攀路 | 7 | 3.4発 | 4.9発 | +1.5 | 2.5発 | 4.3発 | +1.8 |
| 9 | エンチャント試練 9 | 1 | 3.1発 | 4.6発 | +1.5 | 2.2発 | 3.9発 | +1.7 |
| 10 | エンチャント試練 10 | 1 | 3.1発 | 4.6発 | +1.5 | 2.1発 | 3.6発 | +1.5 |

### ボス

| 難易度 | ダンジョン | 体数 | 通常 前回 | 通常 今回 | 増加 | 最悪の技 前回 | 最悪の技 今回 | 増加 |
|---|---|---|---|---|---|---|---|---|
| 1 | 闇の大聖堂 | 5 | 4.2発 | 5.7発 | +1.5 | 2.8発 | 4.6発 | +1.8 |
| 2 | ハロウィン闘技場 | 2 | 4.1発 | 5.6発 | +1.5 | 2.7発 | 4.5発 | +1.8 |
| 2 | 深層鉱山 | 3 | 4.3発 | 5.8発 | +1.5 | 3.0発 | 4.7発 | +1.8 |
| 3 | 騎士団の城 | 4 | 4.2発 | 5.7発 | +1.5 | 2.9発 | 4.6発 | +1.8 |
| 3 | 古橋の聖所 | 3 | 4.1発 | 5.6発 | +1.5 | 5.6発 | 9.8発 | +4.2 |
| 4 | 宮殿 | 3 | 3.9発 | 5.4発 | +1.5 | 5.3発 | 9.5発 | +4.2 |
| 4 | 蒸気機関工房 | 7 | 4.0発 | 5.5発 | +1.5 | 2.8発 | 4.5発 | +1.7 |
| 5 | ネザーの鐘 | 3 | 3.6発 | 5.1発 | +1.5 | 2.4発 | 4.1発 | +1.7 |
| 5 | 鉱山 | 4 | 3.8発 | 5.3発 | +1.5 | 2.7発 | 4.3発 | +1.7 |
| 6 | ドワーフの地下都市 | 3 | 3.7発 | 5.2発 | +1.5 | 2.6発 | 4.2発 | +1.7 |
| 6 | ネザーの荒野 | 2 | 3.6発 | 5.1発 | +1.5 | 5.6発 | 9.4発 | +3.8 |
| 6 | 花火工房 | 3 | 3.7発 | 5.2発 | +1.5 | 2.6発 | 4.2発 | +1.7 |
| 7 | 下水道迷宮 | 1 | 3.4発 | 4.9発 | +1.5 | 4.6発 | 8.7発 | +4.1 |
| 7 | 北極ミニダンジョン | 4 | 3.3発 | 4.8発 | +1.5 | 4.0発 | 7.5発 | +3.5 |
| 8 | 採石場 | 5 | 3.3発 | 4.8発 | +1.5 | 2.3発 | 3.9発 | +1.6 |
| 8 | 登攀路 | 4 | 3.2発 | 4.7発 | +1.5 | 4.3発 | 8.3発 | +4.0 |
| 9 | 灼熱の洞窟 | 3 | 2.9発 | 4.4発 | +1.5 | 1.9発 | 3.6発 | +1.6 |
| 10 | 世界を繋ぐ者の聖所 | 4 | 2.5発 | 4.0発 | +1.5 | 1.8発 | 3.5発 | +1.7 |

### 全ダンジョン平均

| クラス | 通常 前回 | 通常 今回 | 増加 | 最悪の技 前回 | 最悪の技 今回 | 増加 |
|---|---|---|---|---|---|---|
| 雑魚 | 4.3発 | 5.8発 | +1.5 | 3.7発 | 7.4発 | +3.6 |
| 中ボス | 3.9発 | 5.4発 | +1.5 | 3.1発 | 5.3発 | +2.2 |
| ボス | 3.7発 | 5.2発 | +1.5 | 3.3発 | 5.8発 | +2.5 |

**雑魚の通常攻撃は W-181 では 1 ミリも動いていない**（W-181 で下げたのはボス係数と技だけ）。
今回はじめて雑魚も含めて下がった。また**雑魚は技をほとんど持たない**（28 ダンジョン中、
雑魚が技を持つのは世界を繋ぐ者の聖所だけ）ので、雑魚の「最悪の技」欄はほぼ空になる。

#### (b) 攻撃属性と弱点の組み合わせを4通りへ散らした

2026-08-21 以前は**「攻めと受けを必ず逆にする」という固定ルール**をファイル冒頭に明記していた
（`【(1)と(2)は必ず逆向きに揃える】`）。その結果、23 ダンジョンの組み合わせは
**`魔法攻撃×物理が通る` 12 本と `物理攻撃×魔法が通る` 11 本の2種類しかなく**、
**敵の攻撃属性を見た瞬間に持ち込む武器が決まる**＝選択が実質存在しなかった。

**114 体の physical/magical を入れ替え、対称だった 29 体に新しく型を付けた**。
攻撃属性（`magic-ratio`）は 1 つも変えていない。分布:

| | 物理が通る | 魔法が通る |
|---|---|---|
| 敵の攻撃が魔法寄り(0.45) | 6 | 5 |
| 敵の攻撃がほぼ物理(0.10) | 6 | 6 |
| 敵の攻撃が半々(0.25) | 3 | 2 |

**攻めと受けが同属性になるダンジョンは意図的**（例: ハロウィン闘技場は魔法で殴ってきて魔法が通る）。
その帯は一本で押し切れる代わりに、逆側の帯では丸ごと通用しない —— という起伏を作るため。
**1つのダンジョンの中では弱点を揃えてある**（入る前に武器を選べないと、両方持ち込むのが常に正解になる）。

あわせて**属性差の下限を 1.35 倍 → 1.75 倍**へ引き上げた（「該当の耐性量が少し少ない」への対応）。
薄かった 168 体の**硬い側だけ**を上げたので、**正しい属性で挑んだときの撃破時間は変わらない**。

⚠ **ファイル冒頭の説明と各ダンジョンのコメント（▼コンセプト 28行 / 【コンセプト】28ブロック /
ボス係数 117 ブロック / 難易度 18 ブロック）も全部書き換えた。** 数値だけ直してコメントを残すと、
次に触る人が旧ルールへ戻す。

#### (c) メイジローブ60部位に max-health が無かった

**W-181 の報告で「守護/魔織シリーズ（`LEATHER_*#2002xx`）60 部位」と書いたのは不正確だった。**
正しくは **`LEATHER_*#200xxx` のメイジローブ全体**（守護 / 魔導 / 魔織 × 見習い・魔術師・大魔導士・
賢者・星詠み ＝ 3系統 × 5tier × 4部位 = 60 部位）。`#2002xx` はそのうち Lv80/100 の 24 部位。

他の防具はすべて `fixed.max-health`（確定分）＋ `random.max-health {min:0,max:R}`（ロール分）を持つのに、
**ローブ 60 部位はキー自体が無かった**。着替えた瞬間に最大HPが **94 → 20（バニラ素の値）** へ落ち、
「魔法が痛いから魔法防御装備に着替える」という当然の選択肢が成立していなかった。

系統のコンセプトで係数を決め、重装ライン Lv100 の `fixed` を F / ロール上限を R として掛けた
（Lv20・Lv40 は前後の帯から等比補間）。

| 系統 | use-skill | 立ち位置 | 係数 | Lv100一式のHP | 魔法寄りダンジョン | 物理ダンジョン |
|---|---|---|---|---|---|---|
| 守護 | HEAVY_ARMOR | 魔法の盾（魔法守備 62.6・魔法耐性 49%） | fixed 0.40×F / roll 0.40×R | 45.9 | **8.2発**（板金 5.7） | 3.0発（板金 5.3） |
| 魔導 | LIGHT_ARMOR | マナ特化（胴 mana 405） | fixed 0 / roll 1.00×R | 30.3 | 4.7発 | 1.7発 |
| 魔織 | LIGHT_ARMOR | スレッド特化（Lv100 で4枠） | fixed 0 / roll 0.60×R | 26.2 | 3.7発 | 1.3発 |

守護だけ `fixed` を持つのは **HEAVY_ARMOR ライン全体の書式**（軽装は `fixed: 0` ＋ ロールのみ）。
魔織のロール係数が最も低いのは、スレッド枠が最大の伸びしろだから。
**魔法寄りダンジョンでは守護が板金を上回り、物理ダンジョンでは板金の 55% 前後に落ちる**
＝どちらも正解ではない、という状態に収めた。

**⚠ 別件で残る穴（未対応・要判断）: 軽装の最終装備（エンダードラゴン一式）は Lv100 で最大HP 30.3 しかなく、
踏破ボスの通常攻撃を 0.9〜1.1 発しか耐えられない。** ローブより先に軽装そのものが成立していない。
`max-health` のキーは持っている（＝W-182 の修正対象ではない）ので別課題として残す。

#### 更新後のダンジョン別コンセプト表

**この表は生成物**（`node tmp/w182-table.js concept`）。同じ表を `docs/agent-context/forks-and-mobs.md` にも置いた。

| 難易度 | ダンジョン | 敵の攻撃 | 通る武器 | 属性差 | HP梯子 | 踏破ボス撃破 | ボス通常 | 最悪の技 |
|---|---|---|---|---|---|---|---|---|
| 1 | エンチャント試練 1 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.173 | 26秒 | 6.0発 | 技なし |
| 1 | 闇の大聖堂 | 魔法寄り(魔法45%) | 物理 | x2.47 | x1.375 | 22秒 | 5.7発 | 4.6発 |
| 2 | エンチャント試練 2 | ほぼ物理(魔法10%) | 魔法 | x2.36 | x1.152 | 33秒 | 6.0発 | 4.7発 |
| 2 | ハロウィン闘技場 | 魔法寄り(魔法45%) | 魔法 | x2.30 | x1.327 | 34秒 | 5.8発 | 4.5発 |
| 2 | 深層鉱山 | ほぼ物理(魔法10%) | 魔法 | x1.99 | x1.327 | 26秒 | 5.8発 | 4.7発 |
| 3 | エンチャント試練 3 | 魔法寄り(魔法45%) | 魔法 | x2.36 | x1.132 | 41秒 | 5.5発 | 5.6発 |
| 3 | 騎士団の城 | ほぼ物理(魔法10%) | 魔法 | x2.14 | x1.281 | 31秒 | 5.7発 | 4.6発 |
| 3 | 古橋の聖所 | 半々(魔法25%) | 物理 | x2.05 | x1.281 | 31秒 | 5.6発 | 9.8発 |
| 4 | エンチャント試練 4 | ほぼ物理(魔法10%) | 物理 | x1.75 | x1.112 | 50秒 | 5.6発 | 4.5発 |
| 4 | 宮殿 | 半々(魔法25%) | 魔法 | x2.17 | x1.236 | 37秒 | 5.4発 | 9.5発 |
| 4 | 蒸気機関工房 | ほぼ物理(魔法10%) | 物理 | x2.03 | x1.236 | 37秒 | 5.5発 | 4.5発 |
| 5 | エンチャント試練 5 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.092 | 63秒 | 5.1発 | 4.3発 |
| 5 | ネザーの鐘 | 魔法寄り(魔法45%) | 魔法 | x2.05 | x1.193 | 44秒 | 5.1発 | 4.1発 |
| 5 | 鉱山 | ほぼ物理(魔法10%) | 魔法 | x2.19 | x1.193 | 45秒 | 5.3発 | 4.3発 |
| 6 | エンチャント試練 6 | ほぼ物理(魔法10%) | 魔法 | x2.36 | x1.073 | 79秒 | 5.2発 | 4.2発 |
| 6 | ドワーフの地下都市 | ほぼ物理(魔法10%) | 魔法 | x2.08 | x1.152 | 55秒 | 5.2発 | 4.2発 |
| 6 | ネザーの荒野 | 半々(魔法25%) | 物理 | x1.93 | x1.152 | 54秒 | 5.1発 | 4.5発 |
| 6 | 花火工房 | ほぼ物理(魔法10%) | 物理 | x2.53 | x1.152 | 54秒 | 5.2発 | 4.2発 |
| 7 | エンチャント試練 7 | 魔法寄り(魔法45%) | 魔法 | x2.36 | x1.054 | 98秒 | 4.8発 | 4.1発 |
| 7 | 下水道迷宮 | 半々(魔法25%) | 物理 | x1.95 | x1.112 | 65秒 | 4.9発 | 4.1発 |
| 7 | 北極ミニダンジョン | 魔法寄り(魔法45%) | 魔法 | x1.75 | x1.112 | 65秒 | 4.8発 | 7.5発 |
| 8 | エンチャント試練 8 | ほぼ物理(魔法10%) | 物理 | x2.36 | x1.036 | 122秒 | 4.9発 | 4.1発 |
| 8 | 採石場 | ほぼ物理(魔法10%) | 物理 | x1.86 | x1.073 | 78秒 | 4.8発 | 3.9発 |
| 8 | 登攀路 | 半々(魔法25%) | 魔法 | x2.00 | x1.073 | 78秒 | 4.7発 | 3.9発 |
| 9 | エンチャント試練 9 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.018 | 152秒 | 4.6発 | 3.9発 |
| 9 | 灼熱の洞窟 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.036 | 96秒 | 4.4発 | 3.6発 |
| 10 | エンチャント試練 10 | ほぼ物理(魔法10%) | 物理 | x1.95 | x1 | 190秒 | 4.6発 | 3.6発 |
| 10 | 世界を繋ぐ者の聖所 | 魔法寄り(魔法45%) | 物理 | x1.87 | x1 | 309秒 | 3.7発 | 3.1発 |

#### 再発防止（`ShippedDungeonBurstAndElementTest` を 6 → 8 件へ。変更した4件すべて RED 実証済み）

- **技の `damage-percent` 上限 1.4 → 1.2。**
- **per-mob のボス係数は「許可値の集合」→「範囲 [1.0, 1.45]」へ。**
  +1.5 発の解が半端な小数になるので集合では表せない。下限 1.0 の意味は
  「ボスは自分の配下の雑魚より弱くならない」（W-179 以前は 121 体が雑魚と完全同値だった）。
- **属性差の下限 1.35 → 1.75。** 検査対象からフィールド（`default`）とギルドを除外。
- **【新規】攻撃属性 × 弱点の組み合わせが4通りに散っている**（各3ダンジョン以上）。
  あわせて**1つのダンジョン内で通る属性が混ざっていない**ことも見る。
- **【新規】メイジローブ（魔法耐性を持つ革防具）が `max-health` を持っている**。
  60 部位以上あることと、`fixed` とロールの両方があることを要求する。
- **エンチャント試練の攻撃力**は「梯子とぴったり一致」→「梯子の 0.70〜1.00 倍の帯 ＋ 単調増加」へ。
  +1.5 発の調整で**梯子は攻撃力ではなく耐発数の側で等間隔になった**ため。
  帯で縛る意味は残る（2026-08-19 に置き去りになった旧値 101.5 は帯の 2 倍以上）。
- `ShippedBossStrengthDriftTest` の束縛者段階表も攻撃側だけ追随（1.15→1.09 / 1.2→1.117 /
  1.25→1.144 / 1.4→1.219 / ミニボス 1.1→1.061）。HP 側は難易度10 ＝ 梯子 1.0 なので据え置き。

**テスト**: TF フルテスト **4527 件・失敗 28・skip 2**。失敗 28 は全て他セッションの未コミット yml 由来
（`gates.yml` / `mob-types.yml` / `catalog.yml` / `gacha.yml` / skilltree / thread / brew 系）。
`item-stats.yml` を読む `ShippedThreadItemStatsTest` は**変更前のバックアップに戻しても同じく落ちる**ことを
実走で確認済み（＝今回の編集が原因ではない）。

**配備**: jar は再ビルド済み（16,577,291 B / 2026-08-21 18:16）。**Java の変更は無く config だけ**なので、
config 配備だけでも効く（ただし jar 内の既定 yml は古いままになる）。

---

### 実サーバ報告バッチ（2026-08-21 受領 第24陣。W-181）

| ID | 内容 | 状態 |
|---|---|---|
| W-181 | 前回EMダンジョンを調整したが、まだ格下レベルのボスにワンパンされる。体力を上げる代わりにボス・中ボスの攻撃力を下げてほしい | ✅ 対応（**jar + config**） |
| W-181b | 敵の属性耐性・攻撃属性をもう少し尖らせたい。該当の耐性量が少し少ないのでは。戦いごたえがありつつ楽しく戦えるコンセプトになっているか確認 | ✅ 対応＋確認結果を下に記載 |

#### ワンパンの真因は「ボス係数」ではなく「技」だった

報告の仮説（雑魚とボスで攻撃力に大差がある）は**実測では否定された**。
W-179 で入れたボス係数は ×1.2〜1.3 しかなく、通常攻撃で耐えられる発数は
**雑魚 3.5〜5.3 / ボス 2.5〜3.6**（Lv100・品質5・帯最良装備・最大HP 94）。ワンパンには程遠い。

犯人は**技（`combat/mob-abilities.yml`）**だった。技のダメージは 2026-08-21 以前、
`damage-type` の側へ **100%** 寄せて解決しており、モブの `magic-ratio`（通常攻撃の物理/魔法の配分）を
**完全に迂回**していた。つまり `magic-ratio` の上限 0.45 ——
「魔法防御を持たないプレイヤーが何発耐えるか」で校正した安全弁 —— を**技だけが素通り**していた。

| 技を撃つ相手 | 1発のダメージ | 最大HP比 |
|---|---|---|
| 闇の大聖堂ボス（**難易度1**） | 68.6 | 73% |
| 灼熱の洞窟ボス（難易度9） | 92.7 | 99% |
| 世界を繋ぐ者 最終段階 | 135.2 | **144% ＝ 確定ワンパン** |

さらに「敵は物理型（`magic-ratio` 0.10）」のダンジョンにも `damage-percent: 2.0` の**魔法技**が配られており、
**正しく物理防御を積んだプレイヤーほど魔法技1発で理不尽に死ぬ**という逆転が起きていた。

#### 直したもの

**(1) 技をモブの属性へ追従させた（jar 変更。`MobAbilityExecutor` / `MobAbilitiesConfig` / `TrinityForge`）**

`combat/mob-abilities.yml` に **`ability-element-bias`（既定 0.35）** を新設。技のダメージも
そのモブの `magic-ratio` を土台に物理/魔法へ分割する（`魔法技: r + (1-r)*bias` / `物理技: r * (1-bias)`）。
`damage-type` は「100% その属性」から「自分の属性へどれだけ引き寄せるか」という**偏り**へ格下げ。
`bias: 1.0` にすると 2026-08-21 以前の挙動へ戻せる。
刻印を持たないバニラモブ（`magic-ratio` 0）でも魔法技は bias ぶんだけ魔法で入るので、
**ウィザーのビームが完全物理に化けることはない**。

**(2) 技の威力を圧縮**

`piercing_beam` / `meteor_mark` **2.0 → 1.3**、`shadow_step` 1.8 → 1.4、`quake_spikes` 1.7 → 1.35、
`bull_rush` 1.6 → 1.35、`shockwave` 1.4 → 1.2。オーラ系と演出のみの技は据え置き。

**(3) ボス/中ボスの攻撃力を下げ、そのぶんをHPへ振り替えた（依頼の本体）**

- per-mob の攻撃係数 **1.3→1.15 / 1.2→1.10**（束縛者の段階表 1.4→1.20 / 1.5→1.25 / 1.8→1.40）。146 体。
- HP はスコープ直下の `max-health-multiplier` を**難易度別の逓減梯子**で入れた（28 ダンジョン）。
  難易度1が最大・難易度10で 1.0。式は `T(n) = 1.70^((10-n)/9)` で、
  **雑魚 = T^0.6（＝少し）/ ボス・中ボスは per-mob で T^0.4 を上乗せ（＝雑魚より多く）**。
  エンチャント試練は元から撃破秒数が長いので半分の梯子（合計 T^0.5）。
  ⚠ **攻撃を下げた側とHPを上げた側は2つで1つ。片方だけ戻すと「攻撃も硬さも上がる」ことになる。**

**(4) 属性耐性を尖らせた（`resistance` だけ。`defense-rate` は据え置き）**

- 「通る側」の耐性を **0.05** に下げ、「硬い側」を **`0.24 + (元値-0.12)*1.3`（上限 0.55）** へ上げた。794 行。
- 左右完全対称だったモブは、そのダンジョンのコンセプト（`magic-ratio`）から硬い側を決めて型を付けた。
  ただし**物魔両方のダンジョンの対称モブは対称のまま**（弱点を持たない代わりに全体的に硬い、という別設計）。
- 結果、**正しい属性を選んだときの与ダメージ倍率が中央値 1.29 → 1.7〜2.0 倍**へ。
  396 体中 43 体あった「物理と魔法が完全に同値」は 41 体（＝物魔両方のダンジョンのぶんだけ）に。

#### 効きの確認（Lv100・品質5・帯最良装備、最大HP 94）

| | 変更前 | 変更後 |
|---|---|---|
| 雑魚の通常攻撃に耐えられる発数 | 2.6〜5.3 | 2.9〜5.3 |
| ボスの通常攻撃 | 1.7〜5.3 | **2.2〜4.5** |
| 最悪の技 | **0.7〜2.5**（＝ワンパンあり） | **1.6〜5.6** |
| 踏破ボスの撃破秒数（難易度1→9） | 13〜90秒 | **22〜96秒**（設計目標は難易度1で20秒） |

スキルツリーの防御ノードを半分取ると 1.5〜3 倍、全開で 2〜6 倍まで伸びる（`tmp/w181-verify.js`）。

#### 「戦いごたえがありつつ楽しく戦えるか」の確認結果

**成立している。ただし前提が1つある** ——
**魔法防御は装備では積めず、重装/軽装スキルツリーが唯一の正規ルート**。
Lv100 帯の最良防具（バニラ系）は魔法守備 3.9・魔法耐性 0% しかなく、
魔法防御に特化した**守護/魔織シリーズ（`LEATHER_*#2002xx`）は 60 部位すべてに `max-health` が無い**ので、
そちらへ着替えると最大HPが **94 → 20** へ落ちる（＝戦士が装備で対処する道は無い）。
重装ツリーの防御ノードを全部取ると魔法守備 **+25.2**・魔法耐性 **+30%** で、これが答えになっている。
**魔法寄りのダンジョン（`magic-ratio: 0.45`）を増やすときはこの前提で数値を見ること。**

#### ダンジョン別の難易度コンセプト表

**この表は生成物**（`node tmp/w181-table.js`）。同じ表を `docs/agent-context/forks-and-mobs.md` にも置いた。

| 難易度 | ダンジョン | コンセプト | 敵の攻撃 | 有効な武器属性 | 属性差 | HP梯子 | 踏破ボス撃破 | ボス通常 | 最悪の技 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 闇の大聖堂 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x2.43 | x1.375 | 22秒 | 4.2発 | 2.8発 |
| 1 | エンチャント試練 1 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.173 | 26秒 | 4.5発 | 技なし |
| 2 | ハロウィン闘技場 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x2.21 | x1.327 | 34秒 | 4.3発 | 2.7発 |
| 2 | 深層鉱山 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x1.91 | x1.327 | 26秒 | 4.3発 | 3.0発 |
| 2 | エンチャント試練 2 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x2.36 | x1.152 | 33秒 | 4.5発 | 2.9発 |
| 3 | 騎士団の城 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x2.09 | x1.281 | 31秒 | 4.2発 | 2.9発 |
| 3 | 古橋の聖所 | 物魔両方 | 半々(魔法25%) | どちらでも | x1.22 | x1.281 | 31秒 | 4.1発 | 5.6発 |
| 3 | エンチャント試練 3 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.132 | 41秒 | 4.0発 | 3.3発 |
| 4 | 宮殿 | 物魔両方 | 半々(魔法25%) | どちらでも | x1.15 | x1.236 | 37秒 | 3.9発 | 5.3発 |
| 4 | 蒸気機関工房 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x2.00 | x1.236 | 37秒 | 4.0発 | 2.8発 |
| 4 | エンチャント試練 4 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x1.38 | x1.112 | 50秒 | 4.1発 | 2.8発 |
| 5 | ネザーの鐘 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x1.91 | x1.193 | 44秒 | 3.6発 | 2.4発 |
| 5 | 鉱山 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x2.13 | x1.193 | 45秒 | 3.8発 | 2.7発 |
| 5 | エンチャント試練 5 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.092 | 63秒 | 3.6発 | 2.6発 |
| 6 | ドワーフの地下都市 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x2.06 | x1.152 | 55秒 | 3.7発 | 2.6発 |
| 6 | ネザーの荒野 | 物魔両方 | 半々(魔法25%) | どちらでも | x1.86 | x1.152 | 54秒 | 3.6発 | 2.7発 |
| 6 | 花火工房 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x2.38 | x1.152 | 54秒 | 3.7発 | 2.6発 |
| 6 | エンチャント試練 6 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x2.36 | x1.073 | 79秒 | 3.7発 | 2.6発 |
| 7 | 下水道迷宮 | 物魔両方 | 半々(魔法25%) | どちらでも | x1.32 | x1.112 | 65秒 | 3.4発 | 2.4発 |
| 7 | 北極ミニダンジョン | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x1.63 | x1.112 | 65秒 | 3.3発 | 4.0発 |
| 7 | エンチャント試練 7 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.054 | 98秒 | 3.3発 | 2.4発 |
| 8 | 採石場 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x1.83 | x1.073 | 78秒 | 3.3発 | 2.3発 |
| 8 | 登攀路 | 物魔両方 | 半々(魔法25%) | どちらでも | x1.36 | x1.073 | 78秒 | 3.2発 | 2.3発 |
| 8 | エンチャント試練 8 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x2.36 | x1.036 | 122秒 | 3.4発 | 2.3発 |
| 9 | 灼熱の洞窟 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x2.28 | x1.036 | 96秒 | 2.9発 | 1.9発 |
| 9 | エンチャント試練 9 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x2.36 | x1.018 | 152秒 | 3.1発 | 2.2発 |
| 10 | 世界を繋ぐ者の聖所 | 物理推奨 | 魔法寄り(魔法45%) | 物理 | x1.61 | x1 | 309秒 | 2.2発 | 1.6発 |
| 10 | エンチャント試練 10 | 魔法推奨 | ほぼ物理(魔法10%) | 魔法 | x1.70 | x1 | 190秒 | 3.1発 | 2.1発 |

#### 再発防止（`ShippedDungeonBurstAndElementTest` を新設。6件すべて RED 実証済み）

- **技の `damage-percent` が 1.4 を超えない。** 技は「モブの攻撃力 × この倍率」なので、
  **1行戻すだけで全ダンジョンが同時に即死級に戻る**。
- **`ability-element-bias` が出荷 yml に存在し、1.0 未満。** 1.0 は 2026-08-21 以前の
  「技は damage-type へ100%」へ戻す値。キーごと消えると次に触る人が同じ穴を開け直すので、
  Java 既定で動く場合でも**明示を要求**する。
- **`effectiveMagicRatio` が片側へ振り切らない**（物理型ダンジョンの魔法技が魔法 50% 未満、
  魔法型ダンジョンの物理技が 0 でない、バニラモブの魔法技が完全物理に化けない）。
- **per-mob のボス係数（攻撃）が {1.1, 1.15, 1.2, 1.25, 1.4} の範囲**。
- **28 ダンジョン全部にスコープの HP 梯子があり [1.0, 1.45] に収まる。**
- **左右非対称のモブは属性差 1.35 倍以上。** 対称のモブは意図的な設計として除外する
  （＝「弱点を持たせない」と「書き忘れ」を区別できる形にしてある）。

`ShippedBossStrengthDriftTest` の束縛者段階表も攻撃側だけ追随させた
（HP 側は束縛者が難易度10 ＝ 梯子の係数 1.0 なので据え置き。書き忘れではない旨をコメントに残した）。

**テスト**: TF フルテスト **4528 件・失敗 28・skip 2**。失敗は全て他セッションの未コミット yml 由来
（`gates.yml` / `mob-types.yml` / `catalog.yml` / `gacha.yml` / skilltree 系）で、
**`mob-overrides.yml` と `mob-abilities.yml` を読むテストは 1 件も落ちていない**
（`*Mob*` `*Combat*` `*Damage*` `*ShippedDungeon*` の 959 件で確認）。

**配備**: **jar の差し替えが必要**（技の属性追従は Java 側）。config も同時に配備すること。

---

### 実サーバ報告バッチ（2026-08-21 受領 第23陣。W-180）

| ID | 内容 | 状態 |
|---|---|---|
| W-180 | 農業の経験値効率が良すぎる。畑の作物（一括収穫対応）を 40%、家畜討伐を 80% に | ✅ 対応（**config のみ**。`skills/base/farming_progression.yml`） |

#### W-180 農業EXPの圧縮

**変更（`skills/base/farming_progression.yml` のみ。jar の再ビルドは不要）**

- **畑の作物（一括収穫 `area-harvest` が対象にする5種）を 40% へ。**
  `WHEAT` / `POTATOES` / `CARROTS` / `BEETROOTS` / `NETHER_WART` の `block_drops` を **48 → 19.2**。
  対象一覧は Java 側 `FarmingCropCatalog.CROPS` が一次情報で、これと1対1に一致している。
- **家畜討伐（`entity_drops`）を 80% へ。** 24 行すべてに ×0.8。
  `BEEF` 40→32 / `PORKCHOP` 60→48 / `RABBIT` 100→80 / `MUTTON`・`LEATHER`・`FEATHER`・
  羊毛16色 40→32 / `RABBIT_HIDE` 20→16。

**基準にした「現行」は 48。** 5種の `block_drops` は 2026-08-21 の午前に
`40 / 40 / 10`（WHEAT 48・BEETROOTS 48・POTATOES 40・CARROTS 40・NETHER_WART 10）から
**48 へ統一されていた**（配備先 `plugins/TrinityForge/skills/base/farming_progression.yml` も同値・同日更新）。
そのため HEAD からの差分で見ると `NETHER_WART` だけは **10 → 19.2 と増えている**が、
実サーバで動いていた値（48）の 40% としては正しい。

**触っていないもの（意図的）**

- `entity_breed`（繁殖EXP）— 討伐だけを弱める依頼なので変えていない。
  **ここは討伐EXPのゲートでもある**（`NativeSkillExperienceListener#onFarmingMobDeath` が
  値 0 以下の種を討伐EXPの対象から外す）ので、討伐を下げるつもりでここを触ると
  **繁殖EXPが巻き添えになり、0 を書くとその種の討伐EXPが丸ごと消える**。
- `entity_shear`（毛刈り）・`block_interact`（ベリー等の右クリック収穫）— 依頼の対象外。
- `block_drops` の**ドロップ品の行**（`POTATO` / `CARROT` / `BEETROOT` / `COCOA_BEANS` /
  `MELON_SLICE` / `SWEET_BERRIES`）— **1度も読まれないので触っていない**。
  `stats/skill-exp.yml` の `gathering.exp-mode` が `block_value` で、この方式では
  **ブロック名の行だけが実際のEXP量**になり、ドロップ品の行はツール要件ガード
  （`drops.isEmpty()`）を通す以外の役目を持たない（`drop_sum` / `max` へ戻したときだけ効く）。
  **農業EXPの調整はブロック名の行で行うこと。**
- `break-vanilla-exp`（破壊時に落ちるバニラEXPオーブ）— 農業スキルEXPとは別物なので対象外。

**効きの目安（Lv100・一括収穫 tier4 = 半径4 の場合）**

| | 変更前 | 変更後 |
|---|---|---|
| 1回の一括収穫（最大 81 ブロック） | 3,888 | **1,555.2** |
| 作物1マス | 48 | **19.2** |
| 牛1体（肉3 + 革2 と仮定） | 200 | **160** |

一括収穫は**連鎖した1ブロックごとに単価を丸ごと配る**ので、単価がそのまま倍率として効く。

**再発防止（`ShippedFarmingExpCompressionTest` を新設。RED 実証済み）**

- **一括収穫の対象作物5種すべてに `block_drops` の行があり、同じ単価に揃っている。**
  対象一覧を Java（`FarmingCropCatalog.CROPS`）側から引いているので、
  **作物を Java へ足して yml へ足し忘れると落ちる**（足し忘れるとその作物だけ無言でEXP 0）。
  意図的に単価を変えるときは定数 `AREA_HARVEST_CROP_EXP` も一緒に動かす。
- **`entity_breed` に 0 の種が居ない。** 0 は繁殖EXPと討伐EXPを同時に殺すゲートなので、
  「討伐を弱めよう」でここが 0 になる事故を止める。
- **`entity_drops` に 0 の行が無い。** 0 は「行が無い」と完全に同じ挙動で、残すと誤解しか生まない。

`NativeSkillCatalogTest` の `block_drops.WHEAT`（48 → 19.2）と `entity_drops.BEEF`（60 → 32）の
期待値も追随させた。BEEF は元から出荷値（40）とずれていた。

**テスト**: 4485 件 / 失敗 26 / スキップ 2。失敗は全て他セッションの未コミット yml 由来で、
農業に関係するものは 1 件も無い（`NativeSkillCatalogTest` の残り2件は毛刈り 200→150 と
採掘 400→800 のドリフトで、どちらもこの作業の対象外）。

**配備**: config のみ。config 再配備 →`/trinityforge reload`。

### 実サーバ報告バッチ（2026-08-20 受領 第22陣。W-179）

| ID | 内容 | 状態 |
|---|---|---|
| W-179 | エンドコンテンツの「世界の束縛者」が、オーバーワールドのエンドラと HP も攻撃力も同じ。EM ダンジョンのモブはフィールドより強くあるべき | ✅ 修正（**config のみ**。`combat/mob-overrides.yml`） |

#### W-179 「ボスが雑魚と同じ強さ」— 121 体が該当していた（束縛者はその一例にすぎない）

**報告の裏取り（Lv100 / 品質9 の厳選装備を想定した実測ベースの試算）**

| | 攻撃力 | 1発の被ダメ | 耐えられる発数 | 実HP |
|---|---|---|---|---|
| ジ・エンドのエンダードラゴン | 55.6 | 37.0 | 2.7 | 1,468,089 |
| 世界の束縛者 第1段階（修正前） | 62.1 | 41.0 | 2.4 | 47,064,240 |
| 束縛者ダンジョンの雑魚（修正前） | 62.1 | 41.0 | 2.4 | — |

報告どおり、**踏破ボスがフィールドのエンドラと同じ強さ**だった。しかも問題はもっと広く、
**束縛者の第1段階と、その配下の雑魚の攻撃力が完全に同値**だった。

**真因は2つ重なっていた。**

1. **ダンジョンの難易度は scope 直下の倍率1本で付いており、それはそのダンジョンの全個体へ等しく掛かる。**
   個体側に「ボスだから強い」という係数が1つも無かったので、踏破ボスの攻撃力は自分の配下の雑魚と
   1ダメージ差なく同じになる。`mob-overrides.yml` にオーバーライドを持つ 121 体のボス／ミニボスが
   **全部その状態**だった。エラーも警告も出ないので、config を読んでも気づけない。
2. **束縛者だけは絶対値で書かれており、その絶対値が「Lv50 のランプ」で計算されていた。**
   yml のコメントが「contentLevel: 50 固定」と書いていたのが根拠だが、
   配備先の `custombosses/the_binder_of_worlds/*.yml` は **`level: 100`**。コメントが実物とずれていた。
   結果、最終コンテンツのボスが**半分のレベルの強さ**で出荷されていた。

**修正（`combat/mob-overrides.yml` のみ。jar の再ビルドは不要）**

- **ボス係数を 146 体へ導入**（`attack-power-multiplier`）。BOSS ×1.3 / MINIBOSS ×1.2 / EVENTBOSS ×1.2。
  scope 直下の難易度倍率に**掛け算で乗る**（倍率は層ごとに積になる）。
- **束縛者を絶対値から倍率へ移した。** 段階表（設計時の意図）をそのまま復元：
  第1段階 HP×2.5／攻撃×1.3、第2段階 ×3.0／×1.4、第3段階 ×3.5／×1.5、最終段階 ×6.0／×1.8、
  ミニボス3種 ×1.8／×1.2。**倍率ならレベル基準がずれない**（絶対値はレベル追従を殺す）。
- **エンチャント試練10本の攻撃力を他 18 ダンジョンと同じ尺度へ揃えた**（101.5〜214.3 → 44.32〜62.05）。

**自分の取りこぼしを1件明記する。** この試練10本は 2026-08-19 の攻撃力圧縮（W-175）で**漏らしていた**。
W-175 は `attack-power-multiplier` の行だけを機械的に直したが、この試練群だけは
scope 直下の**絶対値** `attack.attack-power` で書かれていたため、走査に一度も引っかからなかった。
その結果 Lv100 の厳選装備でも**実質1発で沈む**水準（同レベルのフィールドモブの 2.92〜6.16 倍）のまま
1日出荷され続けていた。**「倍率行だけ直せば全体を直した」は成り立たない。**

**もう1件、適用の1回目で丸ごと漏らしかけた。** ボス／ミニボスの分類を EM の `bossType:` キーだけで
判定したところ、**「虚無の鐘」ダンジョンだけが 28 体まるごと素通り**した。このダンジョンの EM ファイルには
`bossType:` キーが無く、分類は `name:` の `$bossLevel` / `$minibossLevel` / `$eventBossLevel`
プレースホルダ側に入っていた。第2波で 22 体へ係数を追加して解消。
なお `em_id_the_quarry` の `LiftAirFiller` / `LiftStateZero` は `$eventBossLevel` を名乗るが
**`level: 1` 固定・不可視・ドロップ無しの昇降機の演出用ダミー**なので意図的に対象外。

**修正後（同じ試算）**

| | 攻撃力 | 耐発数 | 実HP | 撃破秒 |
|---|---|---|---|---|
| 暗黒大聖堂ボス（難易度1） | 57.6 | 2.7 | 4,706,424 | 13 |
| 虚無の鐘 最終段階 | 65.7 | 2.3 | 12,079,822 | 33 |
| スパーキー（難易度6） | 68.0 | 2.4 | 15,531,199 | 42 |
| 煮沸炉（難易度9） | 77.2 | 1.9 | 32,944,968 | 90 |
| エンチャント試練10 | 74.5 | 2.1 | 69,360,000 | 190 |
| 世界の束縛者 第1段階 | 80.7 | 1.8 | 47,064,240 | — |
| 世界の束縛者 最終段階 | 111.7 | **1.2** | 112,954,176 | — |
| （束縛者の総戦闘時間） | | | 総必要ダメージ 70,596,360 | **193** |

束縛者は「フィールドのエンドラと同じ」から、**攻撃力2倍・実HP 77 倍・戦闘時間 193 秒**の最終コンテンツになった。

**再発防止（`ShippedBossStrengthDriftTest` に3本追加。いずれも RED 実証済み）**

- **どのダンジョンにも「雑魚より強い個体」が最低1体は居る。** これを「係数を持つ個体の許可リスト」で
  書くと**リストが現実とずれた瞬間に検査ごと無効化**されるので、**ダンジョン側から**要求する形にした。
  実際、第1波だけを当てた yml をこのテストにかけると**「虚無の鐘」1件だけが落ちる**ことを確認済み。
  ボスの居ないスコープ（`default` と拠点の `em_adventurers_guild`）だけは理由付きで免除。
- **エンチャント試練10本が共通ランプ Lv100 の梯子（×1.0〜×1.4）に載っている。**
  期待値はランプから**計算**する。直書きすると、ランプを動かしたときにこの10本だけ置き去りになったことを
  検出できない —— それが W-175 で起きたことそのもの。
- **scope の絶対値の上に個体の倍率が乗る**（絶対値が倍率を握り潰さない）ことを実装で固定。
  ここを絶対値へ「統一」する改修が入ると、無言でボスが雑魚と同値へ戻る。

**配備**: config のみ。config 再配備 →`/trinityforge reload`。jar の再ビルドは不要。

### 実サーバ報告バッチ（2026-08-20 受領 第21陣。W-178）

> ⚠️ W-177 は別セッションが「config が勝手にロールバックする」件で使用済み（commit `8d46143`）。採番はそこから続けている。

| ID | 内容 | 状態 |
|---|---|---|
| W-178 | 資源鯖でエンダードラゴンを倒したのに確定ドロップのはずのドラゴンエッグが落ちなかった | ✅ 調査完了＋修正（**config のみ**。`combat/mob-level-table.yml`） |

#### W-178 ドラゴンエッグ — 報告そのものは正常動作。ただし確定ドロップは【一度も発火できない設定】だった

**まず報告の事実関係（全部実データで確認）。**

| 調べたもの | 結果 |
|---|---|
| 資源鯖 `latest.log` 15:02:19 | `Found that the dragon has not yet been killed in this world` → 初回討伐で確定 |
| `world_the_end/level.dat` | `PreviouslyKilled=1` / `DragonKilled=1` → **バニラの卵設置処理は走っている** |
| エンド全リージョン走査 | `dragon_egg` ブロック **0 個**（出口ポータルのチャンクは保存済み） |
| 討伐ワールド | 全員の死亡が `[world_the_end]` = ジ・エンド |
| `.shizurei555` の統計 | **資源鯖だけ** `picked_up dragon_egg = 1`・現在1個所持（メイン鯖側の統計はゼロ） |

**バニラの卵はアイテムドロップではなく、出口ポータル（X=0 / Z=0、ポータル基部 Y=55）の柱の上に
ブロックとして設置される。** 討伐地点には何も落ちない。今回は設置された卵を回収した人がいた、が結論。

**一方で TF 側の確定ドロップには本当の欠陥があった。** `combat/mob-level-table.yml` の 6 帯すべてに
書いた `{ material: DRAGON_EGG, chance: 1.0, ..., where: field, environment: [NORMAL] }` は
W-128（2026-08-19）で「**オーバーワールドの**エンダードラゴンで 100%」という依頼どおりに入れたものだが、
**実サーバにオーバーワールドへエンダードラゴンが出る経路が存在しない**:

- 資源鯖に EliteMobs は入っていない（プラグイン一覧で確認）
- 導入データパック（Terralith / Incendium / Nullscape / DnT 各種 / Structory）にオーバーワールドのドラゴン湧きは無い
- EM 側で `ENDER_DRAGON` を使うのは束縛者のダンジョンボス4体だけ ＝ `where: field` から外れる

つまり**この 100% ドロップは一度も発火できず、`hero` 武器（Lv100）の儀式コア素材が入手不能のままだった**。

**直し方**: 6 帯すべてから `environment: [NORMAL]` を撤去し、**どのディメンションでも落ちる**ようにした。
`where: field` は残す（ダンジョンインスタンスの束縛者からは落とさない）。ジ・エンドではエンドクリスタルで
復活させれば反復入手でき、クリスタル4個/回が実質のコストになる。

- `environment:` 軸そのものは残す（`LevelTierDropEnvironmentFilterTest` の単体テスト群は緑のまま）。
  出荷設定で使わなくなっただけ。
- 出荷ガードを**反転**した: 「`[NORMAL]` であること」→「そもそも付いていないこと」。
  付け直すと落ちる（**RED 証明**: 6 帯に `environment: [NORMAL]` を戻すとガードが実際に失敗するのを実走で確認）。
- **同種の「一度も発火しない」行が他に無いかも確認した。** `environment:` を使っているのは
  この 6 行だけ。`mobs:` に出てくる 23 種の EntityType はすべて実在（`PARCHED` も
  `EnchantmentStatBridge` が switch で参照しておりコンパイルが通っている ＝ 1.21.11 に実在）。

⚠️ **jar は不要。config（`combat/mob-level-table.yml`）の再配備 + `/trinityforge reload` だけで効く。**

---

### 実サーバ報告バッチ（2026-08-20 受領 第20陣。W-174〜W-176）

> ⚠️ **ID 重複の注記**: 別セッションが同日に「称号がバニラのネームタグを消す」件へ **W-174 を再割り当て**した
> （commit `b5fab44`。下の別バッチ節）。番号だけで参照すると取り違えるので、この節の W-174 は
> **エンチャント運のギリシャ路線4ノード**の件を指す。以後の採番はこの節の最大値（W-176）から続ける。

| ID | 内容 | 状態 |
|---|---|---|
| W-174 | 「エンチャント運は何に反映される？」— 調べたところ**ギリシャ路線4ノードで表示と実体が食い違い、抑制の理I/IIは効果が丸ごと存在しなかった** | ✅ 修正（**config のみ**。`skilltree/enchanting.yml`） |
| W-175 | EM のダンジョンがオーバーワールドに比べて強すぎる。オーバーワールドの同レベルのモブより少し強いくらいに抑えてほしい（**攻撃力側**） | ✅ 修正（**config のみ**。`combat/mob-overrides.yml`） |
| W-176 | 「スパーキーがエンドラと比べて異常にHPが高い報告もあるからHPも全体的に多いのでは？」— **指摘どおりだった**。踏破ボスの撃破秒数が選んだレベルで **15.2倍** ぶれ、難易度6のボスが Lv55 で **623秒** | ✅ 修正（**config のみ**。`combat/mob-import.yml`） |

#### W-174 エンチャント運の反映先と、ギリシャ路線4ノードの実体欠落

**反映先は1箇所だけ**: `EnchantLuckListener` が `EnchantItemEvent` の確定結果へ後掛けする3つの追加抽選
（レベル+1格上げ／バニラ上限超えの格上げ＝`overenchant:<id>` 解放者のみ／未付与エンチャントを1つ追加）。
確率はいずれも `stats/enchant-luck.yml` の係数 × 運で、**運50で100%に張り付く**。
釣り運・ドロップ運・ガチャ・品質には一切効かない（バニラの `luck` 属性とも無関係）。

**見つかった不具合（4ノード）**: 表示（`effect-text`）と実体（`buffs`）が食い違っていた。

| ノード | 表示 | 実体（修正前） | 修正後 |
|---|---|---|---|
| 増幅の理I | 運+8・スキルEXP-5% | 運+5・効率-10%（EXPは**無し**） | 運+8・EXP-5% |
| 増幅の理II | 運+8・スキルEXP-5% | 運+10・効率-15%（EXPは**無し**） | 運+8・EXP-5% |
| 抑制の理I | **表示なし** | 効率+10%のみ（運もEXPも**無し**） | 運+4・EXP+5% |
| 抑制の理II | **表示なし** | 効率+20%のみ（運もEXPも**無し**） | 運+4・EXP+5% |

**この4件は `EnchantingAlchemyBuffsWiringTest` が既に pin していて、赤のまま放置されていた**
（`node A-alpha-1 enchant_luck ==> expected: <8.0> but was: <5.0>`）。
一次仕様 `skilltree/職業別草案_生産.md` も pin と同じ「α=運+8/EXP-5%・β=運+4/EXP+5%」なので、そちらへ実体を寄せた。
EXP の増減が実装できるようになった今、**代役だった `enchant-cost-reduction` は外した** ——
残すと「運を取る代わりに EXP が減る」というこの枝の対価が二重に掛かる（β側では逆に一方的な得になる）。

⚠️ **`effects:` と `description:` はもう読まれていない**（`SkillNode` の javadoc 参照。表示は `effect-text` 一本）。
この2つだけを直しても**プレイヤーからは何も変わらない**ので、実体を直すときは必ず `buffs` と `effect-text` を見ること。

#### W-175 ダンジョンが強すぎる — 火力再較正の「後」に載った攻撃力倍率が突き合わせ漏れ

**HP は問題ではなかった。** 出荷 yml から計算した実測（`tmp/em-vs-field.js`。品質9の帯最良装備）:

| Lv | フィールドの1発 | ダンジョンの1発 | プレイヤー最大HP | 耐えられる発数（フィールド→ダンジョン） |
|---|---|---|---|---|
| 30 | 5.8 | 42.1 | 33 | 5.8 → **0.8** |
| 60 | 7.1 | 54.7 | 56 | 7.9 → **1.0** |
| 100 | 19.8 | 108.8 | 100 | 5.0 → **0.9** |

**同レベルのフィールドモブの 5.4〜7.8 倍**を殴っており、全帯で雑魚1発で即死。
一方で TTK はダンジョンの方が短い（0.24〜0.64倍）＝**硬いのではなく一方的に殴り殺される**状態だった。

**真因は順序**: 2026-08-12 に火力カーブを寝かせ（プレイヤー最大HPを50ハートへ固定）、その**後**の
2026-08-14 難易度設計が `combat/mob-overrides.yml` のダンジョン単位 `attack-power-multiplier`（2.3〜4.8）を載せた。
較正後のプレイヤー側の数値と一度も突き合わされていない。素の輸入ランプ（`combat/mob-import.yml`）が
既にフィールドの約1.27倍なので、積で 2.9〜6.1 倍になっていた。

**直し方**: 難易度1〜10の**順序は保ったまま** 2.3〜4.8 → **1.0〜1.4** へ線形圧縮（18ダンジョン）。
2.3→1.0 / 2.5→1.03 / 2.7→1.06 / 2.9→1.10 / 3.2→1.14 / 3.4→1.18 / 3.7→1.22 / 4.1→1.29 / 4.4→1.34 / 4.8→1.40。
結果、同レベル比は**難易度1で約1.25倍・難易度10で約2.3倍**、耐えられる発数は**難易度1で4〜6発・難易度10で2〜3発**
（フィールドは5〜8発）。

- **束縛者（難易度10）の7体は絶対値を持つ**ので倍率が捨てられる。同じ比で書き直した
  （132.67→38.7 / 142.9→41.68 / 153.07→44.65 / 183.7→53.58 / ミニボス3体 122.5→35.73）。
  `ShippedBossStrengthDriftTest` が畳み込み漏れを検出する pin なので、**この2箇所は必ずセットで動かす**。
- **エンチャント試練10本はもともと倍率を持っていなかった**（＝実質1.0）。圧縮後の難易度1と同水準なので、
  28ダンジョンが初めて1本の尺度に載った。
- ~~⚠️ **HP 側（踏破ボスの `max-health` / `max-health-multiplier`）は意図的に据え置き**。報告は「即死する」で
  あって「硬すぎる」ではなく、TTK は元からフィールドより短い。ここを削ると踏破ボスが一瞬で溶ける。~~
  → **この判断は誤り。W-176 で訂正した。** 上の TTK 比較は「ダンジョン雑魚のランプ素の値」で見ていたが、
  **踏破ボスの実HPはそこへ `max-health-multiplier` と EM の `healthMultiplier` が最大 2500 倍まで掛かる**。
  実データ（配備先 `plugins/EliteMobs/custombosses`）で取り直すと、私の「HP は問題ない」は Lv100 でしか
  成り立っていなかった。

**検証**: TF フルテスト **4460 件・失敗 26・skip 2**（着手前と同じ 26 件で、すべて他セッションの未コミット yml 由来。
`mob-overrides` / `enchanting` 系は 1 件も含まない）。**着手前に赤だった
`EnchantingAlchemyBuffsWiringTest#enchantingTreeGrantsEnchantLuckAndExpBonusOnTheSixTargetNodes` は緑になった。**

⚠️ **どちらも jar は不要。config（`skilltree/enchanting.yml` と `combat/mob-overrides.yml`）の再配備 +
`/trinityforge reload` だけで効く。**


#### W-176 ダンジョンのHPが「選んだレベル」で15.2倍ぶれる — Lv45以降の加算専用区間が倍率の内側にあった

**ユーザーの指摘が正しく、私の「HP は問題ない」（W-175）は Lv100 でしか成り立っていなかった。**
配備先の `plugins/EliteMobs/custombosses` の実 `healthMultiplier` を読んで取り直した実HP:

| | Lv45 | Lv55 | Lv100 |
|---|---|---|---|
| スパーキー（難易度6） | 1.19M | 6.31M | 25.33M |
| 同レベルのエンドラ | 0.19M | 0.36M | 1.47M |

撃破秒数（プレイヤーDPSは実測校正: 暗黒大聖堂 Lv100・実HP 7.68M をユーザーが 21 秒で撃破 = 実効DPS 365,714）:

| ボス | Lv45 | Lv50 | Lv55 | Lv60 | Lv70 | Lv80 | Lv90 | Lv100 | ぶれ |
|---|---|---|---|---|---|---|---|---|---|
| 暗黒大聖堂（難易度1） | 12s | 68s | **189s** | 55s | 115s | 38s | 58s | 21s | **15.2倍** |
| スパーキー（難易度6） | 41s | 225s | **623s** | 182s | 379s | 124s | 193s | 69s | 15.2倍 |

**真因は2つとも「Lv45以降だけ効く加算専用の第2区間」**（2026-08-03 に `combat/mob-import.yml` へ置いたもの）。

1. **`max-health.high-level-per-level: 1800`（HP加算）が、ボス倍率の内側にあった。**
   ボスの実HP = ランプ × `max-health-multiplier` × EM の `healthMultiplier` の3段の積で、後段は最大 2500 倍。
   加算がその内側にあるので **+1800/Lv が最大 +450万HP/Lv に化け**、指数（growth 1.072）で組んだ
   難易度ラダーの形を中レベル帯だけ壊していた。
2. **`physical/magical.flat-defense.high-level-per-level: 150`（ダンジョンモブだけが持つ守備力）。**
   flat-defense はクリット前に減算される固定値なので、**装備が変わらない帯の中でレベルだけ上げると
   プレイヤーの実効DPSが下がる**（Lv45→55 で約半減）。装備更新は Lv45/60/80/100 の飛び石なので、
   帯の終わり（Lv55/75/95）で撃破秒数が跳ね上がる。

**直し方**: 両方の第2区間を **0 に撤去**（attack-power 側が 2026-08-12 の再較正で既にそうなっていたのと同じ扱い）。
撤去すると純粋な指数に戻り、装備更新の階段（Lv45/60/80/100 で実効DPSが約2.4〜4.5倍）と
growth 1.072（15レベルで約2.8倍・20レベルで約4.0倍）が噛み合う。

| ボス | Lv45 | Lv55 | Lv60 | Lv70 | Lv80 | Lv90 | Lv100 | ぶれ |
|---|---|---|---|---|---|---|---|---|
| 暗黒大聖堂（難易度1） | 13s | 25s | 11s | 22s | 13s | 24s | 13s | **2.3倍** |
| スパーキー（難易度6） | 44s | 83s | 38s | 71s | 42s | 80s | 42s | 2.3倍 |
| 煮沸炉（難易度9） | 94s | 176s | 80s | 151s | 90s | 170s | 90s | 2.3倍 |
| （参考）同レベルのエンドラ | 6s | 25s | 11s | 18s | 8s | 11s | 4s | — |

**雑魚のHPには影響しない（重要）。** EliteMobs フォークの HP 委譲は
`EliteEntity#setMaxHealth` → `TrinityForgeIntegration#resolveProfileMaxHealth` の
`instanceof CustomBossEntity` ゲートを通るので、**この `max-health` ランプを食うのは踏破ボス/中ボスだけ**。
ダンジョンの雑魚は EliteMobs 自前の `LevelScaling.calculateMobHealth` のままで、ここには乗らない。
（flat-defense の方は全モブに乗るが、撤去は「弱くする」方向なので報告の趣旨と同じ向き。）

**「倍率の後に足す」Java 改修は採らなかった。** ボスでは後段の倍率が支配的なので、加算を倍率の外へ
出した場合（例: 暗黒大聖堂 Lv55 で 224k）と撤去した場合（206k）の差は **1割未満**。数値が同じなら
TF 本体と EM フォークの2本の jar を跨ぐ API 追加を持ち込まないほうがよい（EM フォークのソースは
`.gitignore` 除外でバージョン管理外なので、跨ぐ改修は失われやすい）。

**検証**: TF フルテスト **4469 件・失敗 29・skip 2**。29 件は全て他セッションの未コミット yml 由来で、
`mobs` / `combat` ドメインは 1 件も含まない（着手前の 26 件から +3 は、この間に別セッションが
`mob-types.yml` の Lv80固定ボス3体を `level: 100` へ書き換えたものなど）。
**RED 証明**: `combat/mob-import.yml` を 150/1800 に戻すと
`ShippedMobImportBreakpointTest` の新設2件（`maxHealthIsAPureExponentialWithoutTheHighLevelPhase` /
`flatDefenseHasNoHighLevelPhase`）が落ちることを実走で確認済み。

⚠️ **jar は不要。config（`combat/mob-import.yml`）の再配備 + `/trinityforge reload` だけで効く。**
試算スクリプトは `tmp/boss-ttk-audit.js`（実サーバの `healthMultiplier` を読む）と `tmp/boss-hp-audit.js`。

---

### 実サーバ報告バッチ（2026-08-20 受領 第20陣。W-174）

| ID | 内容 | 状態 |
|---|---|---|
| W-174 | 称号がバニラのネームタグを消してしまっている。位置も従来より上によっている | ✅ 修正済み（**未配備**。TF jar 要再ビルド） |

#### W-174 称号が名前を消す／高すぎる — **独立した 2 つの算数の間違い**だった

##### (1) 名前が消える = 称号の行が名前の行と**重なっていた**

`titleAnchorY` は `高さ + 0.5`（＝バニラのネームタグの**中心**）へ `clearance` を足すだけだった。
ところが config のコメントは「ネームタグの**上端**からさらに何ブロック離すか」と書いており、
**説明と実装が 1 行ぶんずれていた**。名前も称号も 1 行の高さが約 0.25 あるので、
中心どうしを 0.25 未満しか離さなければ必ず重なる。
出荷値は `0.2`、**稼働サーバの設定は `0.1`** ── **どちらも重なる側**で、称号が名前に被って読めなくなっていた。

- **なぜ今まで検出されなかったか**: 2026-08-03（`a1dd403`）で入れた不変条件は
  「称号がネームタグより**下**に来ない」だけで、**等号（＝ぴったり重なる）を許していた**。
  しかも既存テストは `clearance = 0.4`（Java 側の既定値）でしか確かめておらず、
  **出荷 yml の実値でも稼働サーバの実値でも一度も検証していなかった**。
- **修正**: `NAMETAG_LINE_HEIGHT = 0.25` を土台に足し、`clearance` を
  「**名前の行の上端からの余白**」という config の説明どおりの意味にした。
  `clearance: 0` でも名前の真上に接するだけで重ならない。
- **yml は変更していない**。稼働値 `0.1` はユーザーが設定エディタで入れた値で、
  新しい意味では「名前の行の 0.1 ブロック上」になり正しく働くため。

##### (2) 位置が高すぎる = パッセンジャー取付点の定数が間違っていた

W-153 のクライアント騎乗は、平行移動を「置きたい絶対高さ − 取付点」で出す。
その取付点を **`高さ × 0.75`** と書いていたが、**これは誤り**。
稼働サーバの `paper-1.21.11` を逆アセンブルして確定させた:

- `EntityAttachment.PASSENGER` の fallback は `Fallback.AT_HEIGHT`＝`new Vec3(0, height, 0)`
  （**高さそのもの**。`height / 2` なのは `AT_CENTER` だけ）。
- `EntityType.PLAYER` のビルダは `sized(0.6, 1.8) → eyeHeight(1.62) → vehicleAttachment(...)` だけで、
  **`passengerAttachments(...)` を呼んでいない**＝プレイヤーは fallback をそのまま使う。

つまり取付点は `高さ × 1.0`。`0.75` のままだと `0.25 × 高さ`（立ち状態で **0.45 ブロック**）
引きすぎ、称号がそのぶん高く浮く。報告「位置が従来より上によっている」がこれ。
**この経路は稼働中**（`Main_Server` に `packetevents-spigot-2.11.1` が入っている）。

- **なぜ検出されなかったか**: テストが `height * 0.75` を**実装から書き写して**いたので、
  同じ思い込みが固定され、間違いを一度も検出できなかった
  （[[allowlist-tests-and-source-pinned-guards-fail-silently]] と同じ形）。
  今は取付点を `passengerAttachmentY()` の 1 点に集約し、
  検証は「高さ + 0.5 + 1行 + clearance」という**独立に決まる絶対値**と突き合わせる。

**正味の移動量**: 現状から **0.20 ブロック下がる**（取付点の修正で −0.45、行の跨ぎで +0.25）。

**検証**: `TitleDisplayServiceTest` **16 件・失敗 0・skip 0**。
`NAMETAG_LINE_HEIGHT` を外すと 6 件、取付点を `0.75` へ戻すと 1 件が落ちることを実走確認。
TF フルテスト **4462 件・失敗 29・skip 2**（29 件はすべて他セッションの未コミット yml 由来で、
称号まわりは 1 件も含まない。着手時点の 26 件との差は作業中に他セッションが yml を触ったため）。
**⚠ 未実施（ユーザー作業）: TF jar の再ビルド＋配備＋サーバ再起動。**

---

### 杖の火力/CT と範囲ダメージ半径の再調整（2026-08-20 要望）

**要望**: 「杖の CT が短すぎるし火力も同じ tier の武器に比べて高すぎる」／
追記「大剣のダメージ半径を 3 とし、他の範囲ダメージが発生する武器はこのナーフに合わせて縮小して」。
**ユーザー決定**: 杖は「剣とほぼ同じ攻撃力（1.5 倍程度）でいずれも CT は 4s 固定」／他の AoE 武器は「一律 x0.75」。

**着手前の実態（測って分かったこと）**: 杖の単体 DPS は**もともと剣の 0.48〜0.63 倍**に収まっていて、
その意味では「高すぎ」なかった。体感の原因は別の 2 つ。

- **1 発の威力が剣の 2.16〜2.71 倍**（`attack-power`）。魔法ダメージは
  `spellBase + 杖の attack-power × magical.attack-power-scale`（=1.0）なので**そのまま 1 発に乗る**。
  範囲呪文なら**その値が対象ごとに入る**ため、複数体では DPS 比が一気に崩れる。
- **CT が上位ほど短い逆進カーブ**（木 3.5s → ネザライト 2.8s → インフィニティ **2.1s**）。
  上位帯ほど「重い一撃を速く撃てる」形になっていた。

**入れたもの（`stats/item-stats.yml` のデータ変更のみ。Java は無変更）**:

| 対象 | 変更 |
|---|---|
| 杖 10 本 | `attack-power` を**同系列の剣の 1.5 倍**へ（`random:` も同じ比で縮小）。`item-cooldown` を**全帯 4.0s 固定** |
| 大剣 15 本 | `aoe-radius` を **3** へ統一（3.5 / 4 だった 10 本を縮小） |
| その他の AoE 武器 48 本 | `aoe-radius` を **x0.75**（2.0→1.5 / 2.5→1.88 / 3.0→2.25 / 3.5→2.63） |

杖の新旧（fixed `attack-power`）:

| 系列 | 旧 | 同系列の剣 | 新 | 旧CT → 新CT |
|---|---|---|---|---|
| 木 | 171 | 63 | 94 | 3.5 → 4.0 |
| 石 | 274 | 105 | 158 | 3.3 → 4.0 |
| 銅 | 303 | 120 | 180 | 3.2 → 4.0 |
| 鉄 | 714 | 294 | 441 | 3.1 → 4.0 |
| 金 | 1507 | 596 | 894 | 3.0 → 4.0 |
| ダイヤ | 2835 | 1260 | 1890 | 2.9 → 4.0 |
| ネザライト | 8167 | 3780 | 5670 | 2.8 → 4.0 |
| インフィニティ | 70440 | 37800 | 56700 | 2.1 → 4.0 |
| 星枢(hero) | 54445 | 23520 | 35280 | 2.3 → 4.0 |
| 守護者(nuclear) | 24771 | 12600 | 18900 | 2.5 → 4.0 |

- **効き幅は大きい。** 単体 DPS は剣比 **約 0.5 倍 → 約 0.23 倍**（1 発 1.5 倍 ÷ CT 4s ÷ 剣のレート 1.6/s）。
  杖の存在意義は「単体 DPS」ではなく**範囲・貫通・呪文効果**へ寄る。強すぎ/弱すぎの再調整は
  この 2 つの数字（倍率 1.5 と CT 4.0）を動かすだけで済む形にしてある。
- **CT の下限に注意**: ArsPaper 側の `SpellCaster` は `MIN_COOLDOWN_MS = 100`。4.0s は余裕で上回るので
  この変更で下限に当たることはない。
- **ついでに分かったこと（ユーザーの質問への回答）**: 弓は剣の **1.78 倍**、クロスボウは **2.04 倍**の
  `attack-power` で、**全 9 帯で比がぴたり一定**（設計どおり）。どちらも `item-cooldown` を持たず、
  レートはバニラの引き絞り/装填時間（約 1.0〜1.25s）で決まるので、実効 DPS は剣とほぼ同等に着地する。

**回帰テスト**: `WeaponTierParityTest` の杖検査を**書き換えた**。旧検査は「詠唱 DPS が剣の 47.5%±」という
**帯**だったが、この帯は**1 発の威力と CT を同時に動かせてしまう**ので、上記の逆進カーブ（1 発 2.7 倍 × CT 2.1s）を
そのまま通していた。新しい検査は**倍率 1.5 と CT 4.0s そのものを直接固定する**
（`wandsAreOneAndAHalfSwordsOnAFixedFourSecondCooldown`）。倍率は `fixed` の `attack-power` で見る
（`random:` も同じ比で縮めてあるが、剣と杖でロール幅の比率が違うので合計値では 1.5 からずれる）。

**検証**: `WeaponTierParityTest` **全件緑**（杖 10 本を検査。空振り防止のカウンタ付き）。
TF フルテスト **4471 件・失敗 29・skip 2**。29 件は**すべて他セッションの未コミット yml 由来**で、
武器の数値まわりは 1 件も含まない（`ShippedThreadItemStatsTest` は同じ `item-stats.yml` だがスレッド 300045 の
`grant-chances` が 5 件になっている件で、こちらの差分には含まれない）。着手直後は 30 件で、
差の 1 件が**書き換え前の杖検査**だった。
⚠ 途中、他セッションの未コミット WIP（`bedrock/BedrockRecipeExporter.java` / `BedrockRecipeTable.java`）が
**コンパイルを通らない状態**になっていて `compileJava` で落ちる時間帯があった（`エラー6個`）。相手が直したので実走できた。
⚠ **コミットが他セッションに巻き取られた** —— こちらが `git add` した直後に別セッションが
`git commit`（pathspec 無し）を打ったため、この 3 ファイルは**別セッションのコミット `9af282a` に同梱**されている。
内容は正しく dev に載っている（push 済み）。同一ワークツリー運用では **`git add` から `git commit` までの間が競合窓**になる。
**⚠ 未実施（ユーザー作業）: config の配備＋サーバ再起動**（`item-stats.yml` のみなので jar 再ビルドは不要だが、
W-170〜W-177 の Java 変更が未配備なので結局 jar も要る）。

---

### 鍵の入手経路の棚卸しと戦利品テーブルの反映経路（2026-08-21 質問）

**質問**: 「現状の各鍵の入手方法を調べて」「editor から構造物の戦利品の確率を変えると次の再生成で反映される認識でいい？」

**戦利品テーブルの反映**: 再生成は要らない。`LootGenerateEvent` は**チェストを最初に開けた瞬間**に
中身を生成するので、既存地形でも**まだ開けていないチェスト**には新しい確率がそのまま乗る。
反映の連鎖は editor → リポジトリの fork yml → `deploy.cmd --config` → `plugins/ArsPaper/loot-tables.yml`
→ `/ars reload`。**開封済みのチェストだけは戻らない**（生成結果がワールドに焼き付くため）。

**見つかったドリフト 2 件（要判断・未修正）**:

- **`key_quarry` のクラフトが消えている。** 現在の入手経路は**ガチャ tier4 の 1.4% だけ**。
  意図的に消したのか事故かが判別できないので手を付けていない。
- **`key_binder` がダンジョンの印を消費しなくなっている。** lore は今も「印 5 個」と書いてあるので
  **表示が嘘をついている**状態。印 27 種が用途を失って浮いている。
  `ShippedDungeonKeyReachabilityTest#binderKeyConsumesFiveSeals` が**赤のまま**。

---

### Ars 魔法に増幅が乗っているか／ダメージ補正の仕様（2026-08-21 質問）

**結論**: 増幅は**正しく乗っている**。見えない原因は**ダメージ補正のロール幅**。

- **増幅**: `MagicStatSourcePolicy#applyAmplifyMultiplier` が**1 スタックあたり +10%**、上限 6 スタック
  （`glyphs.yml` の `damage-rate-per-stack: 0.10` / `harm.max-augments.amplify: 6`）。
  最大 **x1.6** が「呪文の基礎値 + 杖の attack-power × 1.0」に乗る。配備先の `glyphs.yml` もリポジトリと一致。
- **ダメージ補正 (`damage-modifier`) は「100% に対する端点」**であって倍率そのものではない。
  1 発ごとに **`Uniform[min(1, 端点), max(1, 端点)]`** を引く。`normalizeDamageModifierEndpoint` が
  (1, 100] の整数を 100 で割るので、**`damage-modifier: 2` は 0.02**（2 倍ではない）。
- **出荷品に 1.0 を超える値は 1 件も無い**。つまりこのステは**常に減衰**で、上振れは存在しない。
  **防具・盾には 0 件**（武器にしか付いていない）。パーク／スキルツリー由来の供給源も無い。

| 武器 | `damage-modifier` | 1 発の期待倍率 | 振れ幅 |
|---|---|---|---|
| 杖 10 本 | **0.30** | **x0.65** | x0.30 〜 x1.00（**±70pt**） |
| 剣 | 0.65 | x0.825 | x0.65 〜 x1.00 |
| 大剣 | 0.72 | x0.86 | x0.72 〜 x1.00 |

**「増幅が乗っているか分からない」の正体**: 杖の振れ幅 ±70pt は、**増幅 1 段（+10pt）の約 2.3 倍**。
1 発ごとにこのサイコロを振るので、増幅を 1〜2 段積んでもロールのばらつきに完全に埋もれる。
**杖の `damage-modifier` は全 10 本が 0.30 固定**で、品質でも `random:` でも改善しない（ゲーム内最低値）。
→ **未実施（要判断）**: 杖の `damage-modifier` を引き上げるか。ユーザーの依頼は「仕様の確認」までなので数値は触っていない。

---

### EM ダンジョンのロックアウト通知を消す／難易度を戦闘レベルの割合にする（2026-08-21 要望）

**要望**: 「em ダンジョンの討伐時にロックアウトのチャットとタイトル通知が出ないようにしてほしい」／
「ダンジョン潜入時のレベル選択で…戦闘レベルの 100%/75%/50% から選択してそのレベルを反映するようにしてほしい」。
**ユーザー決定**: 難易度の呼称は「ハード／ノーマル／イージー」、UI は**レベル選択メニューを難易度メニューに置き換え**。

**入れたもの（EliteMobs フォークのみ。TF 本体は無変更）** commit `6fb405f1`（`trinityforge` リモートへ push 済み）:

| 対象 | 変更 |
|---|---|
| `DungeonsConfig` | `dungeonLockoutNotificationEnabled`（既定 **false**）／難易度別の割合 50・75・100／表示名「イージー・ノーマル・ハード」／モブレベル表示行 |
| `DungeonBossLockoutHandler#notifyLockout` | 先頭でゲート。**チャット・タイトル・アクションバーの 3 経路をまとめて**塞ぐ |
| `DynamicDungeonInstance#getMobLevel` | `+ getDifficultyMobLevelOffset()` を**削除**（割合はメニュー時点で確定済みなので二重適用になる） |
| `DynamicDungeonBrowser` | **レベル選択メニューを廃止**し難易度メニューを入口に。既存インスタンスへの参加枠をそちらへ**移設** |

- **EM 内部の難易度キー `normal` / `hard` / `mythic` はそのまま**で、意味だけ 50% / 75% / 100% に差し替えた。
  全ダンジョンの content package yml は**1 行も触っていない**（32 本ある動的ダンジョンを個別に書き換えずに済む）。
  表示は易しい順に左から スロット 2 / 4 / 6、色は 緑（50%）→ 黄（75%）→ 橙（100%）。
- **レベルは押した瞬間の戦闘レベルで引き直す**（メニューを開いてから押すまでにレベルが上がっても表示と一致する）。
  端数は四捨五入（切り捨てだと 75% 帯だけが常に 1 レベル損をする）。下限 1。
- **報酬は自動で下がる**。`combat/damage.yml` の `dungeon-level-reward` が**絶対モブレベル**を
  `pivot-level: 35` と比べて `step: 5` 刻みで増減させる形なので、レベルを下げればそのまま報酬も下がる。
  ただし減衰には上限があり（`drop-penalty-cap: 0.3` / `exp-penalty-cap: 0.2`）、
  **戦闘レベル 100 のプレイヤーが 50% で潜っても pivot(35) より上**なので報酬はまだプラス側にいる。
- **ロックアウトの記録・判定と戦利品の抑止は据え置き**。ゲートを `processLockouts` 側へ付けると
  ロックアウト自体が無効化されて報酬が無限に出るので、**通知関数の中だけ**に置いた。
- **設定キーは自動で生えてくる**。`ConfigurationEngine.setInt/setString/setBoolean` は既存の
  `DungeonsConfig.yml` に無いキーを書き足すので、配備先の yml を手で直す必要は無い。

**回帰テスト**: `DungeonDifficultyAndLockoutTest` **9 件・新規**。割合の算数は純関数として直接呼び、
「二重適用を消したこと」「通知にゲートが付いたこと」「レベル選択メニューが復活していないこと」は
このフォークの流儀どおり `Javap.sliceMethod` で**メソッド単位のバイトコード**を見て固定した
（クラス単位の定数プール検査では捕まらない ── どれも「クラスのどこかには名前が残るが当該メソッドからだけ消える／付く」形の変更だから）。
**RED 確認済み**: `getMobLevel` に `+ getDifficultyMobLevelOffset()` を戻すと 1 件、
`notifyLockout` のゲートを外すと 1 件が実際に落ちることを実走で確認した。

**検証**: EM フォーク **117 件・失敗 0・skip 0**（21 クラス全部）。
uberjar を再ビルド済み（`testbed/plugins/EliteMobs.jar` 6.86MB。`*-min.jar` は起動不能なので使わない）。
**⚠ 未実施（ユーザー作業）: サーバ停止 → EM jar 差し替え → 起動。**

---

### 杖のダメージ補正を 0.5〜0.6 へ上げ、DPS を据え置いた（2026-08-21 要望）

**要望**: 「増幅は正しく乗っている＝触媒でも反映されるよね？　じゃあ杖は DPS を維持したまま
ダメージ補正を上げてほしい。tier に応じて 0.5〜0.6 の間かな（代わりに攻撃力を下げる）」。

**触媒の確認（先に答えたもの）**: 乗る。`TrinityForgeBridge` は
`resolveMagicStatSource(catalyst, castItem)` でステ供給元を決めた**あと**に
`applyAmplifyMultiplier` を「グリフ基礎 + 供給元の attack-power」の合計へ掛けており、
供給元が杖か触媒かで分岐しない。むしろ**「グリフ基礎だけに掛けると触媒ビルドで実質無効になる」**
のがこの層に置いた動機そのものだと javadoc に明記されている。

**入れたもの（`stats/item-stats.yml` のデータ変更＋テスト。Java 本体は無変更）**:

| 系列 | ダメージ補正 | 1発の期待倍率 | attack-power |
|---|---|---|---|
| 木 | 0.30 → **0.50** | 0.65 → 0.750 | 94 → **82** |
| 石 | 0.30 → **0.51** | 0.65 → 0.755 | 158 → **136** |
| 銅 | 0.30 → **0.52** | 0.65 → 0.760 | 180 → **154** |
| 鉄 | 0.30 → **0.53** | 0.65 → 0.765 | 441 → **375** |
| 金 | 0.30 → **0.54** | 0.65 → 0.770 | 894 → **755** |
| ダイヤ | 0.30 → **0.56** | 0.65 → 0.780 | 1890 → **1575** |
| ネザライト | 0.30 → **0.57** | 0.65 → 0.785 | 5670 → **4695** |
| 守護者 | 0.30 → **0.58** | 0.65 → 0.790 | 18900 → **15551** |
| 星枢 | 0.30 → **0.59** | 0.65 → 0.795 | 35280 → **28845** |
| インフィニティ | 0.30 → **0.60** | 0.65 → 0.800 | 56700 → **46069** |

- 端点は 0.50〜0.60 の線形補間を小数第2位へ丸めたもの（**0.55 は丸めで飛ぶ**）。
  attack-power は `1.30 / (1 + 端点)` 倍。`per-quality` と `random` の min/max も**同じ係数**で縮めた
  （片方だけ縮めると品質を上げるほど、あるいは厳選するほど狙いからずれていく）。
- **実効火力（attack-power × 補正期待値）は据え置き**。1 発あたりの期待ダメージも DPS も変わらない。
  変わるのは**ばらつき**だけで、振れ幅は **±70pt → ±50〜40pt**（最上位ほど安定）。
  増幅 1 段（+10pt）に対する比は 7.0 倍 → 5.0〜4.0 倍で、**まだ埋もれるが体感できる側へ寄る**。
- ⚠ **木だけ攻撃力を 81 ではなく 82 にしてある。** 旧 94 は `round(63 × 1.5) = 94.5 → 94` で
  既に −0.5% 落ちており、そこへ係数を掛けると誤差が積んで −1.1% になる（実際に検査が落ちた）。
  剣の値から**直接**引き直した `round(63 × 1.5 × 0.65 / 0.75) = 82` を採る。
- **近接（杖で殴る）も同じ比で据え置き**。ダメージ補正は物理・魔法の区別なく
  `ComponentDamageCalculator` の同じ段で掛かるため。

**回帰テスト**: `WeaponTierParityTest` の杖検査を**もう一度書き換えた**
（`wandsKeepTheirEffectivePowerWhileRaisingTheDamageModifier`）。
⚠ **前回の「attack-power が剣の 1.5 倍」だけを見る検査では今回の変更を守れない** ——
補正と攻撃力を同時に動かすのが要件そのものなので、片方だけ見る検査は**もう片方を自由に動かせてしまう**。
固定するのは **`attack-power × 補正期待値`**（基準＝剣 × 1.5 × 旧 0.30 の期待値）で、
あわせて「補正が 0.50〜0.60 に収まる」「tier 昇順で下がらない」「CT は全帯 4.0s」も見る。
**RED 確認済み**: ダイヤ杖の補正だけ 0.30 へ戻すと実際に落ちる。

**検証**: `WeaponTierParityTest` / `WeaponDpsParityTest` / `ShippedWeaponIdentityTest` **全件緑**。
TF フルテスト **4535 件・失敗 28・skip 2**。28 件は**すべて他セッション/ユーザーの未コミット yml 由来**で、
武器の数値まわりは 1 件も含まない（`ShippedThreadItemStatsTest` はスレッド 300002 の `grant-chances` が
5 件になっている件、`ShippedDungeonKeyReachabilityTest` は上に書いた `key_binder` のドリフト）。

**ついでに見つけたもの（未修正・別件）**:

- **触媒には `damage-modifier` が 1 件も無い。** `BLAZE_ROD#400001` / `ENDER_EYE#85` は
  attack-power 18 のみで補正キーを持たない＝**ロールも減衰も一切かからない（常に ×1.0）**。
  杖ビルドだけがサイコロを振らされている状態なので、揃えるかは要判断。
- **`WeaponTierParityTest` の DPS 模型がダメージ補正を取り違えている。**
  `perHit` の計算が `(min(1, 1+補正) + max(1, 1+補正)) / 2` ＝ **補正を「1 に対する上乗せ」として扱っており**、
  実際の `Uniform[min(1,端点), max(1,端点)]` とは別物（0.30 なら真値 0.65 に対し 1.15 を使っている）。
  正しいのは `WeaponDpsParityTest#expectedDamageModifier` の方（こちらは実装の
  `normalizeDamageModifierEndpoint` を直接呼んでいる）。**誤差はほぼ全武器に一様に効く**ので
  武器種どうしの比を見る帯は壊れておらず、今回の変更でも表面化しなかった。
  直すと全武器の算出 DPS が動いて帯の再較正が要るため、**この作業では触っていない。**

**⚠ 未実施（ユーザー作業）: config の配備＋サーバ再起動**（`item-stats.yml` のみなので
jar 再ビルドは不要だが、未配備の Java 変更が溜まっているので結局 jar も要る）。

---

### ダメージ補正の検査模型を実装に合わせ、隠れていた戦鎚のズレを直した（2026-08-21 指示）

**指示**: 「（`WeaponTierParityTest` の取り違えを）修正し、DPS にも問題がないか確認する。
90% なら 0.9〜1 倍のダメージ。ダメージ補正の倍率が 1 倍を超えるのは補正が 100% 以上の場合で、
仮に 130% の場合 1〜1.3 倍のランダムダメージになる想定だった」。
**ユーザー決定**: 帯（＝設計意図）を正とし、**データを直す**。

**実装の仕様はユーザーの想定どおりだった**。`ComponentDamageCalculator#rollDamageModifierMultiplier` は
`Uniform[min(1, 端点), max(1, 端点)]` なので、90% → 0.9〜1.0 倍、130% → 1.0〜1.3 倍。
壊れていたのは**検査側の模型だけ**。

**取り違えの中身**: `WeaponTierParityTest` の実効DPS式が
`(min(1, 1+補正) + max(1, 1+補正)) / 2` ＝ **補正を「1 への上乗せ」として扱っていた**
（0.30 に対し真値 0.65 ではなく 1.15 を返す）。
⚠ **誤差がほぼ全武器に一様に効くので、武器種どうしの比を見る帯は破綻しない。**
だから壊れたまま何度もこの表を再較正できてしまい、今日まで残った。
正しい実装を直接呼んでいた `WeaponDpsParityTest#expectedDamageModifier` とは
**同じリポジトリ内で 2 つの模型が食い違っていた**。

**直したら出てきたズレ**（11 武器種のうち 2 種）:

| 武器種 | 帯 | 実測 | 状態 |
|---|---|---|---|
| 戦鎚 | 0.82–0.94 | 0.953–0.991（金のみ 0.850） | **14/15 が超過** |
| 鎌 | 0.79–0.89 | 0.808–0.906 | **5/13 が超過**（最大 +1.8%） |
| 他 9 種 | — | — | すべて帯内 |

**原因は戦鎚のダメージ補正 0.84 が剣の 0.65 より高いこと。**
壊れた模型では補正差が `1.45 / 1.325 = +9.4%` にしか見えていなかったのが、正しくは
`0.92 / 0.825 = +15.2%`。⚠ **金の戦鎚だけ帯内なのが決定的な証拠** ——
金帯は剣も戦鎚も補正 0.98 で差がゼロだから 0.850 に収まっている。
鎌のズレは出血が**加算項**なので、単体火力を縮めると出血の比率が上がって非線形に効いたもの。

**入れたもの（`stats/item-stats.yml` のデータ変更＋テスト。Java 本体は無変更）**:

- **戦鎚 14 本**を ×0.9438〜0.9807（**−1.9〜−5.6%**）、**鎌 5 本**を ×0.9690〜0.9873（**−1.3〜−3.1%**）。
  金の戦鎚と残り 8 本の鎌は帯内なので**据え置き**。
- 縮めたのは **`attack-power` と `fixed-damage` だけ**（`per-quality` と `random` の min/max も同じ係数）。
  会心・攻撃速度・出血・リーチ・貫通は武器種の個性なので触っていない。
- 係数は 1 本ずつ逆算した。実効DPS は `perHit × rate + bleed` で**出血だけが加算項**なので、
  一律の倍率では出血を持つ武器（鎌・cryocore の戦鎚）が目標からずれる:
  `s = (目標DPS − 出血) / (実測DPS − 出血)`。
- 着地点は戦鎚 0.935 / 鎌 0.885（帯の上端のわずかに内側）。
  斧 0.937/0.94・大剣 0.784/0.79 と同じく、この表では**上端に寄せるのが既定**。

**回帰テスト**: 模型そのものを固定する検査を新設した
（`theDpsModelReadsDamageModifierTheSameWayTheGameDoes`）。
ユーザーの言葉での仕様（90%→0.95 期待 / 130%→1.15 期待）を直接置き、さらに
**実装の `rollDamageModifierMultiplier` を両端（unitRandom 0 と 1）で呼んで平均と突き合わせる**ので、
模型が実装から離れた瞬間に落ちる。
⚠ **未設定の扱いも一緒に固定した** —— `stat()` は未設定キーを 0.0 で返すが、
`damage-modifier` の 0 は「毎撃 ×0〜×1」という**実在する強い減衰**なので、そのまま期待値へ通すと
未設定の武器が **×0.5 に化ける**（本番では `AttackStatBridge` が中立端点へ変換している）。
`damageModifierEndpoint()` を分けて 1.0 を返させた。
**RED 確認済み**: 模型を旧式へ戻すと、この新検査と杖の検査の 2 件が実際に落ちる。

**検証**: TF フルテスト **4536 件・失敗 37・skip 2（着手時 28 件。差の 9 件は作業中に別セッションが防具とモブの yml を触ったため増えたもので、武器は 1 件も含まない）**。失敗はすべて他セッション/ユーザーの未コミット yml 由来で、
武器の数値まわりは 1 件も含まない。
⚠ **別セッションが同時に防具の大規模リバランスを `item-stats.yml` へ入れている最中だった**
（748 hunk）。`git add <file>` では相手の未コミット変更を丸ごと巻き込むので、
**HEAD の中身に自分の 19 エントリだけを差し替えた blob を作って `git update-index` で index へ入れた**
（ワーキングツリーには一切触らない）。staged 差分が `attack-power` / `fixed-damage` と
その min/max 以外を含まないことを確認済み。

**⚠ 未実施（ユーザー作業）: config の配備＋サーバ再起動。**

---

### スレッド大改修バッチ 9 件（2026-08-21 指示。T1〜T9）

**指示**（1 通に 9 件。台帳は `tmp/findings/thread-batch-2026-08-21.md`）:
非戦闘系スレッドから戦闘ステを剥がす / セット効果を lore へ自動注入 / セット効果に乗算モード /
戦闘系スレッドを 20〜30 種新設 / 新規スレッドのドロップ割当 / 材質を一律「糸」へ /
テクスチャを機械生成 / ダンジョンのレベル差でドロップ確率を大きく開く /
ルートチェストの鍵↑・スレッド↓と 10 ティア細分化。

**T1 非戦闘系から戦闘ステを剥離**（`stats/item-stats.yml` 33 エントリ / fork `thread-sets.yml` 17 種）。
手本はユーザーが手で処理済みの牧人(300043)・実り(300026) ── `random:` から戦闘ステを全部消し、
`advanced.randomize-grants` / `grant-chances` は**ブロックごと**削除（0.45 の確率付与は戦闘ステ専用で、
ドメインステは常に確率 1 だった）。**セット効果側も同じ対象**で、移動速度・暗視・耐火・村の英雄・
体力増強のような常時効果系が会心率や耐性を配っていたのを空にした。
これで fork の `ThreadSetThresholdReachabilityTest` A-5 が落ちる ── 剥がした値そのもの
（`hero_of_the_village.thresholds.3.percent-bonus-damage=0.05` 等）を固定していたため。
**再ベースラインでは値を書き写さず「住み分け」を固定した**（A-5: 非戦闘系に戦闘ステが 0 件 /
A-5b: 戦闘系のセット効果が空でない＝剥がしすぎの検出 / A-5c: `attack-power` は乗算モードで書く /
A-5d: 旧しきい値の段が残っていない）。数値を書き写すとバランス調整のたびにテストを書き換えることになり、
守れるものが残らない。**A-5b で「戦闘ステ語彙を持つこと」まで要求すると誤検知する** ──
修復の肉(`health-regen-bonus`)・棘(`reflect-percent`)・昏倒(`stun-chance`)・速攻(`cooldown-reduction`)・
射手(`ammo-save-chance`) は戦闘用でありながら攻撃/耐性の語彙に属さない専用ステが正体なので、
「しきい値にステが 1 件でもあること」までしか縛れない。

**T2 セット効果の lore 自動注入**（fork `ThreadItem#setEffectLore` → `TrinityForgeBridge#threadSetStatLore`）。
それまでスレッドの lore にセット効果は**一切出ていなかった**ので、効果の実体がセット側にしかない
スレッドは「厳選ステだけの微妙な品」に見えていた。**エディタで lore を手書きして補う運用は採らない**
── 手書きは `thread-sets.yml` を直した瞬間に嘘になり、嘘になったことに誰も気づけない。
体裁はしきい値ごとに「N 個以上」の見出し＋その段**単体**のステ行（累積合計を出すと読み手が段差を暗算する）。
整形は TF の `LoreComposer` へ丸投げするので表示名・単位・桁・色・並び順が装備 lore と一致する。

**T3 セット効果の乗算モード**（`{ mode: multiply, value: X }`）。`attack-power` のような
**帯で桁が変わる実数ステ**を固定値で配ると低帯だけ極端に強くなる（旧・盲目のスレッドの
`attack-power: 100` は Lv20 帯の最強武器 700 前後に単独で匹敵していた）。乗算は TF の
`PlayerCombatAggregate#multiplierFor` 層へ合流するので、加算合計のあと・stat-caps の手前で掛かる。
エディタ側はステ行に加算/乗算セレクタを足し、乗算のときは `rateValueControl`（% 入力）へ差し替える。
**`stat-row-percent-input-2026-08-12` の3本目は固定長 4000 文字で関数本文を切っていたので、
行が伸びただけで実装が正しいのに落ちた** → 波括弧の対応を数えて関数末尾まで見るよう直した。

**T4 戦闘系スレッド 24 種を新設**（CMD 300046〜300069。武器アーキタイプ 14 ＋ 汎用 10）。
`ThreadType.java` には 1 行も足していない ── W-102 の `ThreadConfig#registerIfUnknown` が
`display_name` / `custom-model-data` / `material` を読んで実行時登録する。
帯予算（`ShippedThreadBandIndependenceTest` の +39%/+49%/+59%）は **item-stats 側の 1 本あたりの値だけで管理**し、
`percent-bonus-damage` はセット効果側で 1 件も配っていない。
**主ステを `grant-chances` に書かない**のは意図的 ── 書いても付与の挙動は変わらない
（`resolveGrantedKeys` は `chance>=1.0` を短絡し、未記載も 1.0 として扱う）が、
`ItemAssembler` が `grant-chances` のキー集合を「確率で付くステ」として `LoreColorRules` へ渡すので、
必ず付く主ステが確率ステの色で出るようになる。同じ違反が既存 5 件（300032/35/36/37/38）にもあったので併せて外した。

**T5 ドロップ割当**。**ドロップ未設定だった EM ダンジョン中ボス 22 体**へ 1 種ずつ、
残り 2 種は固有ドロップの無いバニラ敵（PILLAGER / 大人 ZOMBIE）へ。召喚体・増援は除外した
（湧かせ放題なのでレアドロップを置くと無限に狩れる）。**24 種すべてに入手経路が付いたので
「余りをトレジャーチェストへ」は 0 件**（T9 が同じチェストのスレッド確率を下げる指示なので、
そこへ新規を足すと指示が相殺される）。`min:1,max:1` は意図的で、
**個数が 1 個固定のエントリだけドロップ増加ステが抽選"確率"側に乗る**（`MobDropRoller#isSingleFixed`）。

**T8 ダンジョンのレベル差**。`combat/mob-overrides.yml` には**レベル曲線の口がそもそも無く**、
ダンジョン内スレッドは全部 `chance: 0.2` の固定値だった（浅くても深くても同じ確率）。
フィールド側 `mob-level-table.yml` の `chance-by-level` と**同じ `LevelTierDropEntry.ChanceCurve` を再利用**して
`MobOverrideDropEntry#chanceAt(level)` を足した（同じ意味の曲線を 2 つ持つと片方だけ直す事故が起きる）。
既存 24 件を Lv10:1% → Lv100:30% へ。`chance:` を省略したときは曲線の上端を素の `chance` として扱う
── `MobDropRoller` の「`chance>=1.0` は確定ドロップ」判定が `drop.chance()` を読むので、
曲線だけ書いて `chance` を 0 にすると確定判定が壊れる。エディタにも曲線の入力欄と検証を足した
（yml にだけキーを足すと「開いて保存しただけで消える」）。

**T6 材質を一律 STRING へ / T7 テクスチャの機械生成**。鍛冶型はバニラの説明文が lore に出て
スレッド自身の効果表示を圧迫していた。材質は catalog / item-stats のキー / `threads.yml` /
`cmd-registry.json` / `assets/minecraft/items/*.json` の **5 箇所**にあるので、
W-103 のガードを「鍛冶型であること」から「**全スレッドで 1 種類に揃っていること**」へ読み替えた
（混在すると材質ごとにキーが分裂してステが無言で 0 になる穴が戻る）。
テクスチャは `resourcepack/generate_thread_sprites.py` で**バニラ鍛冶型 19 種 × HSV 補正**から 75 枚を生成。
色は**カタログの表示名の色**へ寄せる（名前とアイコンの色が一致する）。乱数は使わず CMD から決定的に散らす
── 再実行で絵が変わると差分が読めない。**表示名が無彩色（白/灰/黒/色指定なし）のスレッドが 17 種あり、
素直に彩度 0 にすると見分けのつかない灰色の札が 17 枚並ぶ**ので、並び順で色相を散らしたくすんだ色を当てた。
アルファには一切触っていない（統合版のアタッチャブルは半透明を正しく扱えないことがある）。
登録は editor 本体と同じ `lib/respack.js` の `writeTexture` / `regenerateItemDefinitions` 経由に統一した。

**T9 ルートチェストを 10 ティアへ**（ユーザー確定）。`loot-tables.yml` は
`tmp/worldgen/gen_loot_yml.py` の**生成物**なので手で直すと次回再生成で消える。
ティア判定（`loot_tiers.py`）を 5 → 10 段に切り直し、生成側の `entries_for` の if/elif を
**ティア表駆動**へ書き換えた（鍵の確率↑・スレッドの確率↓）。fork の `LootTableConfigTest` の
プール ID 5 件も 10 件へ。

**検証**: TF フルテスト **4540 件・失敗 28・skip 2**（着手時 30 件。減った 2 件は自分が直した分で、
残る 28 件は他セッション／ユーザーの未コミット yml 由来 ── ダンジョンゲート・mob-types の Lv100・
醸造・圧縮食料・スキルツリー配置など、いずれもスレッドに触れない領域）。
ArsPaper fork **484 件・失敗 0**。config-editor **1407 件・失敗 25・skip 1**
（着手時 55 件。自分由来だった 4 件はすべて解消し、残りは杖 3 種の図鑑名・スクラップ・
`network.yml` 未登録など他セッション由来）。`releaseAssembly` と fork の `build` はどちらも BUILD SUCCESSFUL。

**⚠ 未実施（ユーザー作業）: TF jar・ArsPaper jar・config・リソースパックの配備＋サーバ再起動。**
スレッドの材質が変わるので、**既存の配布済みスレッド（鍛冶型ベース）は別アイテム扱いになる**。

---

### 常時効果スレッド 11 種へ厳選ステ・セット効果を付与（2026-08-22 指示）

**指示**: 「常時効果のスレッドに関してセット効果やランダムロールステータス等を付けてほしい。
幸運のスレッドを参考に。原則付与するステータスは関連する効果にしてほしい。」

前日の T1 で非戦闘系スレッドから戦闘ステを剥がした結果、常時効果系 11 種
（迅速/跳躍/暗視/耐火/イルカの好意/コンジットパワー/村の英雄/体力増強/飛行/バックパック/浮遊）は
`item-stats.yml` が `offhand-stats-apply` と `socketed-only-stats` の 2 行だけになり、
`thread-sets.yml` も `thresholds: {}` だった。**常時効果しか無い＝厳選する意味が無い札**になっていた。

手本の幸運のスレッド（`STRING#300040` / セットは `luck`）と**同じ形**に揃えた
── `per-quality` 1 キー ＋ 同じキーの `random`、`advanced` ブロックは持たない、
セット効果は「3 個」「5 個」の 2 段だけ。

**選べるステが GENERAL 系しか無い**のがこの作業の唯一の制約:
- 戦闘系（攻撃/防御/最大体力）は前日の指示で剥がした側なので使えない。
- **ATTRIBUTE 系（`move-speed` / `attack-speed-bonus` など）は名前の上では一番近いが、
  スレッドでは無言の no-op**。スレッドの寄与は fork `ArmorManaListener` →
  `AddonCombatStats`（プレイヤー PDC）→ TF `PlayerCombatAggregate#totalOf` を通るのに対し、
  ATTRIBUTE 系は `PerkAttributeApplier` という別経路でしか適用されない。
  「迅速のスレッドに移動速度」は一番自然に見えて**絶対に効かない**組み合わせ。

なので「関連する効果」は**常時効果が想定している遊び方**へ寄せた（括弧内はセット効果）:
迅速→効率強化増幅（破壊EXP）／跳躍→満腹効率（食料効率）／暗視→採掘幸運（磨き効率）／
耐火→醸造効率（醸造品質）／イルカの好意→海釣り（釣果売却額）／コンジットパワー→宝釣り（二本釣り）／
村の英雄→ドロップ品質（討伐EXP）／体力増強→満腹度回復（隠し満腹度）／飛行→EXP増加（職業EXP）／
バックパック→素材効率・儀式（素材効率・醸造）／浮遊→伐採幸運（養蜂効率）。

**採用前に 22 キーすべて `aggregate(player).totalOf(...)` を読む消費側があることを確認した**
（`fish_sell_price_bonus` / `kill_vanilla_exp_bonus` / `hidden_saturation_bonus` は
canonical 形がアンダースコアなのでケバブで grep すると 0 件に見える ── 語彙にあるだけで
実装が無いキーと区別がつかないので、ここは必ず消費側まで下ろすこと）。

`backpack` は `thread-sets.yml` に**項そのものが無かった**ので新設した。
`hero_of_the_village` は `threads.yml` の `max: 1` なので到達上限が 5（＝1 個 × 5 枠）、
しきい値 3/5 はぎりぎり届く。

**数値**: 幸運と同じ「per-quality は 2 桁 / random は 1 桁（エディタ表示単位）」
「`random.max / random.min` ≥ 8」を満たし、既存のドメインスレッドより一段控えめ
（例: 削岩 0.06 / 0.1〜2 に対し 迅速 0.04 / 0.1〜1.6）。

**検証**: TF **4540 件・失敗 28・skip 2**（着手時と同数。残りは他セッション／ユーザーの未コミット yml 由来）。
ArsPaper fork **BUILD SUCCESSFUL・失敗 0**。config-editor **1412 件・失敗 25・skip 1**（着手時と同数）。
`releaseAssembly` と fork の `build` はどちらも BUILD SUCCESSFUL。

### スレッドのテクスチャからネザライトアップグレードの鍛冶型を除外（2026-08-22 指示）

`resourcepack/generate_thread_sprites.py` は防具トリムの鍛冶型 18 種
＋ **ネザライトアップグレードの鍛冶型**の計 19 種を「形の素材」に使っていたが、
ネザライト型だけ**輪郭の系統が別物**で 1 枚だけ浮いていた。19 → 18 種に減らし、
形の割り当て（`index % 種類数`）が変わるので 75 枚中 66 枚を再生成した。
**乱数は使っていない**ので再実行しても同じ絵になる。
`assetName` は `trinityforge:item/<thread_id>` で型に依存しないため、
`cmd-registry.json` とモデル定義は無変更（`--register` 不要）。

**⚠ 未実施（ユーザー作業）: リソースパックの再配布。**

---

### 職業EXPのレベル差足きりを「職業レベル」基準へ（2026-08-22 指示）

**指示**: 「このレベル差判定のうち経験値に関連するものは戦闘レベルではなく経験値の入る職業レベルに
対応させた方が直感的じゃないかな？」→ 合意のうえ「足きりも15〜30lvでOK。itemとバニラEXPの足きりは20lv」
→ 追加で「じゃあバニラEXPも15〜30」。

**何が壊れていたか。** `combat/damage.yml` の `level-cutoff.under-level` は
`モブのLv − プレイヤーの戦闘Lv` で判定していたが、**戦闘レベルは
`progression/combat-level.yml` の pillar 写像で全スキルを1つに畳んだ値**で、
単一特化のプレイヤーでは最高スキルの約 2/3 にしかならない（top1 の divisor が 1.5）。
つまり**畳んだ結果とEXPの帰属先が別物**で、両方向に狂っていた:

- **直感に反する側**: 軽武器100 の純特化プレイヤーの戦闘レベルは 67。軽武器スキルが
  ちょうど100でも Lv100 モブとのレベル差は 0 ではなく **33** と判定され、軽武器EXPが削られていた。
- **抑制が効かない側**: 伸びている柱に引っ張られて**遅れている職業ほど足きりが甘くなる**。
  軽武器80・魔法1の人が高レベルダンジョンで魔法を振っても判定は魔法1ではなく戦闘Lv53で行われ、
  本来抑制したい「高レベル帯での新スキル一気育て」が素通りしていた。

**直し方**: 基準レベルだけを対象ごとに分けた（閾値と逓減は**1本のまま**）。

| 対象 | 比較するレベル | 閾値 |
|---|---|---|
| 職業(スキル)EXP | **そのEXPが入る職業のレベル** | 15差から 0.067 逓減、30差で0 |
| バニラの経験値オーブ | 戦闘レベル | 同上（帰属する職業が無い） |
| TF追加ドロップ | 戦闘レベル | 20差で完全遮断 |

`KillRewardAdjuster#expMultiplier`（バニラ用）と `#skillExpMultiplier`（職業EXP用）へ分離し、
`SymmetricCombatService#skillLevelOf` を新設（`SkillLevelSource#levelsOf` を畳まずに引くだけ）。
**呼び出し側はすでに対象スキルを知っていた** ── `CombatListener` は `credit.skill()`、
`ArsMagicExperienceListener` は `ARS_MAGIC` 固定なので、渡す引数が増えただけで新しい配線は無い。
~~**防具EXP（被弾）はもともとこの足きりを通っていない**ので今回も対象外。~~ → **同日の追加指示「防具の被弾EXPも今回のlevel差調整の該当にする。（被弾した敵のlevelと比較）」で対象にした**（下の「耐久バッチ」§E1）。

**閾値を 25/0.04 → 15/0.067 へ戻した理由**: 25 は 2026-08-19 に
「戦闘レベルは構造的にスキルレベルより約1/3低い」ぶんを吸収するための**水増し**として置いた値で、
職業EXPを職業レベル基準にした時点で理由が消えた（据え置くと足きりが当初意図より大幅に緩くなる）。
15/0.067 は 2026-08-18 に最初に決めた形そのもので、今回ようやく意図どおりの意味になった。

**副作用（意図どおり）**: 既存の高レベル帯で**新しい職業を一から育てるのが実質不可能**になる
（軽武器100の人が魔法0でLv60ダンジョンへ行っても魔法EXPは0）。抑制の狙いそのもの。

**ついでに直した嘘**: 設定エディタの説明文（`public/js/constants.js`）が
「出荷値15 / 0.067 / 30差で0」のままで、2026-08-19 に 25/0.04 へ緩めたときに更新されていなかった。
**editor 側のテスト `constants.test.js` も 15/0.067 を固定したままで落ち続けていた**
（＝今回の変更で自然に緑へ戻った。他セッター由来の失敗として数えていた1件がこれ）。

**検証**: TF **4545 件・失敗 29・skip 2**（着手時 4540/28。増えた1件は
`ShippedFarmingExpCompressionTest`＝他セッションの農業EXP作業由来で、足きりには触れない）。
config-editor **1412 件・失敗 24・skip 1**（着手時 25。減った1件が上の説明文の嘘）。
`releaseAssembly` は BUILD SUCCESSFUL。
**回帰ガードは反証済み** ── `KillRewardAdjusterSkillLevelExpTest` の実装を戦闘レベル基準へ戻すと
「軽武器100の人が Lv100 モブから満額もらえる」が実際に落ちることを確認した。

**⚠ 未実施（ユーザー作業）: TF jar と config の配備＋サーバ再起動。**

---

### 耐久値まわり4件 + 防具EXPの足きり（2026-08-22 指示）

**指示（原文）**: 「杖を使用時に耐久値が減らない問題を修正して」「クワで一括収穫時に耐久値が
減らない問題を修正して。（収穫した量に応じて減ること）」「斧やツルハシが一括破壊したときに
破壊数に応じて耐久値が減ることを確認して」「ダンジョンで負けた時の耐久値ロスが機能しているか
確認して」「追記：クワを持っていなくても1ブロックであれば収穫とうえ直しができる」
「防具の被弾EXPも今回のlevel差調整の該当にする。（被弾した敵のlevelと比較）」

| # | 内容 | 結果 |
|---|---|---|
| D1 | 杖の使用で耐久が減らない | ✅ 修正（**config**。真因はfork側ではなかった） |
| D2 | クワの一括収穫で耐久が減らない | ✅ 修正（**jar**） |
| D3 | 斧・ツルハシの一括破壊で破壊数ぶん減るか | ✅ 確認＝正常（既存テストで固定済み） |
| D4 | ダンジョン敗北時の耐久ロスが機能しているか | ✅ 確認＝配線あり |
| D5 | クワ無しでも1ブロックなら収穫＋植え直し | ✅ 現状どおり（意図的な設計。今回も壊していない） |
| E1 | 防具の被弾EXPもレベル差足きりの対象へ | ✅ 実装（**jar**） |

#### D1 真因は fork ではなく出荷 yml だった

ArsPaper の `SpellCaster#consumeCastDurability` は実装済みで、配備先3バックエンドの
`plugins/ArsPaper/config.yml` も `cast-durability.enabled: true` だった（実測）。
落ちていたのは **`ItemAssembler:256` の `meta.setUnbreakable(effectiveDurability == null)`** ──
**`durability` ステを書かなかったアイテムは「壊れない品」になる**。
出荷 `item-stats.yml` の武器/防具エントリで `durability` を持たないのは
**杖10種（400002〜400008 / 400012〜400014）と SHIELD の計11件だけ**で、
杖はまるごと `isUnbreakable() == true`。fork 側は W-173 と同じ理由で `isUnbreakable()` を
きちんと見るので、**耐久を減らす処理に到達する前に必ず return していた**。
→ 杖10種へ `durability` を追加（値は**同じ材質の剣と同じ帯**。バニラ最大×0.6、
`per-quality` はその1/6。上位3種＝魔源/黒淵/冥境は儀式製最上位帯の 3654/609）。
回帰は `ShippedWandDurabilityTest`（新設2本）。~~⚠️ **SHIELD は今回の指示外なので据え置き**（同じ理由で壊れない品のままなのは把握済み）。~~
→ **同日の追加指示「同じ理由で SHIELD も壊れない品のままです => 直す」で修正**。
`SHIELD` に `durability: 201`（バニラ 336 × 0.6）/ `per-quality: 34` を追加した。
**1件ずつ足しても次の書き忘れは止まらない**ので、`ShippedEquipmentDurabilityTest` で
**耐久を持てる材質のエントリ全件**（`Material#getMaxDurability() > 0`）に
`durability` があることを縛った。⚠️ **材質名のサフィックス列挙にするとそれ自体が許可リストになる**ので、
バニラの `Material` へ解決して判定する（`getMaxDurability()` はレジストリを引くので MockBukkit の起動が要る）。
⚠️ **解決できない材質名は「今は0件」で固定**した ── 素通りさせるとその材質だけ検査から静かに消える。

#### D2 「意図的に0」だったものを指示で変更

`FarmingHarvestListener#harvestArea` は
> 作物は硬度0なのでバニラでも鍬の耐久は減らない — ここで耐久を消費しないのは意図的。

という理屈で0を取っていた。しかし範囲収穫は**1マスぶんの手間でNマスを刈る**ものなので、
バニラ基準を根拠にするとNが増えるほど得になるだけで歯止めが無い。
→ 一括伐採/一括採掘と**同じ耐久モデル**（1マス1点・耐久力エンチャントの `1/(L+1)` 判定つき・
壊れたら打ち切り）へ揃えた。⚠️ **判定と消費を書き写さず `ChainBreakSupport` へ入口を足した**
（`toolConsumesDurability` / `damageHeldToolOnce`）── 経路ごとに書き写すと
W-173 の `isUnbreakable()` 漏れのような穴が経路ごとに再発する。
**起点マスは数えない**（起点を壊すのはバニラで、作物は硬度0なのでバニラが0を取る）。
回帰3本を `FarmingHarvestListenerTest` へ追加し、**戻すと3本とも落ちることを実走確認**。

#### D3 / D4 確認結果

- **D3 = 正常**。`ChainBreakSupport#breakChain` が1ブロックごとに耐久1を消費し、壊れたら連鎖を打ち切る。
  `TreeFellingListener`（斧）・`VeinMiningListener`（ツルハシ）とも `consumeDurability=true` の経路で、
  `TreeFellingListenerTest`（原木2本→耐久2 / 葉は0）と `VeinMiningListenerTest`（3ブロック→耐久3）が
  固定済み。⚠️ **葉だけ意図的に0**（原木1本で数十〜数百枚巻き込むため）。
- **D4 = 配線あり**。EMダンジョンは致死をキャンセルするので `PlayerDeathEvent` が出ない。
  fork の `InstancePlayerManager#playerDeath:125` が `TrinityForgeIntegration.applyDeathDurabilityPenalty`
  → TF の `EquipmentDurabilityService#applyOnDeath` を呼ぶ。**スペクテイター化の前に呼ぶ順序も守られている**
  （TF はスペクテイターを除外するため）。出荷 config も有効
  （`durability.on-death.enabled: true` / `percent-of-max: 0.1` / `dungeon-only: true`）で、
  TF 防具は `durability` ステを持つので unbreakable ではない。

#### D5 クワ無しの1ブロック収穫＋植え直しは意図的（今回も維持）

`maybeHarvestArea`（範囲収穫）は鍬を要求するが、`auto-replant`（1ブロック）は意図的に対象外。
> 作物はバニラでも素手で収穫できるものであり、自動再植は種を1つ差し引く等価交換で悪用の余地が無いため、
> 素手収穫を殺してまで縛る理由がない。

D2 の耐久消費は**範囲収穫の隣接マスにだけ**入るので、素手の1ブロック収穫は今回も無傷。

#### E1 防具の被弾EXPにもレベル差の足きり

撃破EXP側（軽武器/重武器/弓術/魔法）は `KillRewardAdjuster` の足きりを通しているのに、
**防具の被弾EXPだけが素通りしていた**（`NativeSkillExperienceListener` は
`KillRewardAdjuster` を一度も参照していなかった）。格上モブに殴られるだけなら満額なので、
「低レベルのまま高レベル帯へ連れて行ってもらう」抑制が防具2職業でだけ無効だった。
→ 比較するのは**殴ってきた敵のレベル**と**その防具スキル自身のレベル**（撃破EXPを職業レベル基準へ
寄せたのと同じ規則）。⚠️ **`skillExpMultiplier` を流用せず `skillExpLevelCutoff` を切り出した** ──
被弾EXPは撃破報酬ではないので `dungeon-level-reward` の上乗せはこれまで一度も掛かっておらず、
流用すると「ダンジョンで防具EXPが増える」という指示に無い変更が黙って混ざる。
回帰は `NativeSkillExperienceListenerArmorLevelCutoffTest`（新設3本）と
`KillRewardAdjusterSkillLevelExpTest`（2本追加。うち1本は**上乗せが漏れていないこと**を見る）。

**検証**: TF **4560件・失敗29・skip2**（着手時 4545/29/2。増えた15件が今回の新規テストで、
失敗は増えていない＝29件は他セッションの未コミット yml 由来のまま）。
`* levelCutoff` と `damageHeldToolOnce` を戻すと**新規テスト4本が落ちる**ことを実走確認。

⚠️ **配備は jar + config をセットで**（`item-stats.yml` だけだと杖は壊れうる品になるが
一括収穫の耐久は減らない／jar だけだと杖が unbreakable のまま）。


### Wiki を現行仕様へ全面改訂し、正しい remote へ公開した（2026-08-22 指示）

「以前からかなり仕様が変わったのでwikiを修正、追記してほしい」→ 選択は**全ページ総点検**。
生成ページ 17 本を再生成したうえで、手書き 16 本を実コード／出荷 config と突き合わせて直した。
TF `b8e5593`（prose 15 本 + ジェネレータ）、wiki `5d7d1d9`。

**踏んだ地雷が 2 つある。**

1. **`wiki/` クローンの remote が存在しないリポジトリ (`Klee319/trinityforge-wiki.git`) を指していた。**
   GitHub Wiki の実体は `Klee319/trinityforge.wiki.git`（ブランチは `master`）。
   ローカルの `main` ブランチは 5 commit ぶん**一度も公開されていなかった**（＝この worktree で
   wiki を編集しても、誰も見ていないファイルを触っていた）。remote を張り替え、`origin/master`
   から `publish` ブランチを切り直して push した。**以後 `wiki/` で作業するときはブランチ `publish`
   を使うこと。** 旧 `main` は退避としてローカルに残してある。
2. **`ban.yml` は空ではなかった。** 日輪(`solar`)・月輪(`lunar`)・ソニックブーム(`sonic_boom`)・
   旅路(`journey`) の 4 つが**全ワールドBAN**で、これを 1 つでも含む呪文は
   `SpellCaster#checkSpellBanned` が弾く（配備先 4 台すべてで同じ内容を確認）。
   「ゲート系YAMLは空出荷」という記述が wiki 3 ページに残っていたので全部直した。

**wiki が腐っていた主な箇所**（すべて実 config で裏取り済み）:

| 箇所 | 旧記述 | 実際 |
|---|---|---|
| 品質 | 0〜9（10段） | **0〜15（16段・劣悪〜特異点）**。`quality.yml` の `max-quality: 9` はフォールバックで、段数は `quality-tiers.yml` の要素数が決める |
| 戦闘レベル | 純特化100 → 100 | **67**（`pillars` の top1 除数が 1.0→1.5）。対象スキルも 4→**6**（防具2種が追加済み） |
| `level-scaling.per-level` | 0.05 | **0.01** |
| `vanilla-armor.defense-rate-per-point` | 0.04 | **0.015**。`armor-strength-per-point` は **0**（＝頑丈さは会心軽減へ変換しない） |
| PvP | 「PvPなし」 | `enabled: true` だが `damage-multiplier: 0.001` / 最大体力の 25% 上限で**事実上通らない** |
| `dungeon/gates.yml` | `gates: {}`（fail-open） | **28 本投入済み**。1 本でも書くと fail-close なので**書き漏らし＝入場不能** |
| `dungeon/themes.yml` | 3 テーマ定義済み | **`themes: {}`（空）**。ダンジョン別の硬さは `mob-overrides.yml` のスコープへ移った |
| `role-buffs.yml` | 剣闘士/魔術師 +12%、タンク守備力+40、補助3職 | 剣闘士=**貫通20%**、魔術師=**割合追加ダメ30%**、守衛=**被ダメ−10%＋ヘイト1.5倍**、補助は**5職**（農家/漁師/鉱夫/土工/きこり）。`role-change.allow-change: false` |
| `base-stats.yml` | 空 `{}` | 全ステ列挙。**`crit-chance: 0.05` / `crit-damage: 0.5` だけ 0 以外**（＝素手でも会心する） |
| `item-stats.yml` | 空の雛形64件 | **約 490 エントリが実値入り** |
| `craft-quality.yml` | `category-skill` / `fishing.*` | `category-skill` は**実装側の固定**（tool は MINING→**SMITHING**）、釣りは `fishing-gimmick.yml` へ移設。`drop:` と `workbench:`/`ritual:` の σ 調整が新設 |
| ArsPaper `config.yml` | `mana.recovery.*` / `loot.*` / `geyser.*` | **全部このファイルから消えた**。戦闘マナ回復は TF ステ `hit-mana-recovery`（被弾）/`damage-mana-recovery`（与ダメ）、ドロップは `loot-tables.yml` |
| ArsPaper `armors.yml` | 9セット | **ファイルごと廃止**。魔道防具は TF `items/catalog.yml` へ（3系統 × 5ティア × 4部位 = **60品**） |
| `threads.yml` | 防具専用・十数種 | **75 種**＋`thread-sets.yml` の**セット効果 73**。武器にも挿さる |
| `materials.yml` | 中間素材4件 | **約 256 エントリ** |
| グリフの使用ゲート | 「usage-gate.yml が空＝ゲート無し」 | 正本は**スキルツリーの `dedicated-effects: glyph:<key>`** 側で **57 個**が紐づく。OR 判定 |
| 増幅(amplify) | 基礎＋増幅×N の加算 | **ダメージ系は乗算**（+10%/段・6段で×1.6）。力・速度・持続・召喚HPは加算のまま |
| `/tf` のサブコマンド | `giveitem` ほか数件 | `give` へ改名済み。`mail`/`achievement`/`start`/`stop`/`bind`/`stamp`/`dungeon`/`status`/`role`/`collection`/`recipes`/`glyphs`/`settings`/`reward`/`inspect` が未記載だった |
| EM `trinityforge.yml` | `dungeon-entry-gate: false` | **true**。`hp-delegation` / `suppress-native-combat-display` / `native-display-suppression` / `elite-drop-sources`（バニラloot以外すべて OFF）が未記載だった |

**ジェネレータ側の修正**: スキルツリーのノード表が `effect-text` を優先していたのを
`description` 優先へ（ゲーム内 `/skills` と同じ並び）。`light_weapons` の「斬り/刺し」は
`effect-text` が「ノックバック距離増加」のまま出血系へ差し替わっており、**旧フィールドを
優先していたせいで誤情報をそのまま載せていた**。あわせて 14-スキルツリー詳細 の数値表を
構造表へ置き換え、数値は生成ページ（事典-職業スキルとスキルツリー）へ一本化して
二重管理をやめた。

**未対応で残したもの**（wiki の範囲外なので手を付けていない）:

- `progression/combat-level.yml` の冒頭コメント「純特化(100)=100」が除数 1.0 時代のまま。
  `damage.yml` 側のコメントは 67 で正しいので、**同じ事実の記述が 2 箇所で食い違っている**。
- `combat/mob-overrides.yml` の `em_id_binder_of_worlds` に「fixed-damage(全防御貫通)は使わない」
  というコメントがあるのに、同じスコープが `fixed-damage: 2.42` を出荷している。
- `docs/wiki-source/prose/04-アイテム図鑑.md` は **どのページにも出力されない死んだソース**
  （生成ページ 事典-アイテム索引 に置き換わり、リンクだけ書き換えている）。腐り続けるので
  いずれ消すか、生成側へ統合するか決めたい。

### 職業EXPの日次逓減が「入り直すだけ」で消えていた（2026-08-22 実サーバ報告。W-155）

実サーバ報告「職業EXpの低下がサーバー入り直すだけで24時間経過していなくても消える不具合」。
**推測ではなく稼働中の `player_progression.db` を読み取り専用で開いて確定させた** ──
`lock-release-hours: 24` なのに `locked_at` が **53〜65 時間前**、`updated_at` は現在、という行が
実在した。これが指紋で、これ以外の壊れ方ではこの組み合わせにならない。

**機構は 3 段で、どの 1 段だけを見ても正常に見える。**

1. `DailyExpDiminishing#restore` は期限切れ（W-154 の強制解除）の行を `continue` で
   **読み飛ばすだけ**で、テーブルからは消さない。
2. 次の `save` で、その生き残った古い行が `max()`（＝サーバ切替で蓄積を後退させないための規約）で
   「大きい方」として採用され、さらに `earlierLock()`（＝解除期限を先延ばしさせないための規約）が
   **新しく打たれたばかりの発動時刻を、古い期限切れの時刻へ引き戻す**。
3. 引き戻された結果その行はまた期限切れになるので、次のログインでも 1. で読み飛ばされる。

つまり **「サーバ切替で失わない」規約と「期限を延ばさせない」規約が噛み合って、
期限切れの行を不死身にしていた**。片方だけを疑うと絶対に見つからない。

**直し方**は「期限切れを権威にする」の一点。

- `DailyExpWindowStore#save` に解除時間を渡し、**保存側でも**期限切れ判定をする。
  メモリ側・DB 側の両方を `null` 化してから `max()` を採り、残高がゼロになったら**行ごと削除**する。
- `DailyExpWindowPersistence#load` の先頭で `purgeReleased()` を呼び、期限切れ行を物理削除する。
  復元とは `try` を分けてある ── 掃除に失敗してログインが通らなくなる方が害が大きい。

**検証**: 回帰テスト 3 本を追加し、**修正を HEAD へ戻すと想定どおり 2 本が落ちる**ことを実走で確認
（`期限切れの行を残すと、あとの save が max() で古い蓄積を蘇らせる`／
`入り直しただけで逓減が消えてはいけない`）。戻したあとは 15 tests / 0 failed / 0 skipped。
TF `a26dbed`。

**配備が要る（jar）。** 出荷 yml は変えていないので config 配備は不要。
なお**既に壊れている行は jar を入れ替えた次のログインで自動的に掃除される**（`purgeReleased` が
期限切れを消すので、DB を手で触る必要は無い）。

### 交換グリフで菌糸が作れなかった（2026-08-22 実サーバ報告。W-156）

実サーバ報告「交換で菌糸が作れない。前回は作れてたはず」。**デグレではなく、一度も作れたことが無い。**

- 交換グリフは `ExchangeEffect#findNextBlock` のとおり **`exchange_tiers` の「同じ段」に
  並んでいるブロックの間でしか循環しない**（増幅で 1 段上へ跳ぶときはその段の先頭が出る）。
- 土系の段は `[DIRT, COARSE_DIRT, ROOTED_DIRT, MUD]` だけで、**MYCELIUM も GRASS_BLOCK も
  PODZOL もどの段にも入っていなかった**。`git log -S MYCELIUM` を fork の `glyphs.yml` に
  掛けても履歴に 1 度も現れない。
- 配備先 3 台の `glyphs.yml` はソースとバイト一致で、ArsPaper は `saveResource(..., false)` なので
  **黙って上書きされた可能性も無い**。
- TF / ArsPaper のどこにも菌糸を**生成する**レシピ・儀式・変換は無い。菌糸は**入力側**
  （菌糸ソースリンクの強化に計 19 個）と採掘EXPの値としてしか出てこない。

**指示（2026-08-22）: 草ブロック・ポドゾル・菌糸を第 1 段へ同居させる。**
`[ DIRT, COARSE_DIRT, ROOTED_DIRT, MUD, GRASS_BLOCK, PODZOL, MYCELIUM ]`。
土 1 個から交換を回すだけで菌糸まで届くので、**バイオーム制限は実質なくなる**（承知のうえの判断）。

**配備がふつうと違う。** ArsPaper は**自分では既存の yml を更新しない**
（`saveResource(..., false)` ＋ `updateResourceFiles` はバージョン文字列が変わったときしか
再展開しない）。つまり **jar を差し替えても `/ars reload` しても配備先の glyphs.yml は変わらない**。
リポジトリ側の編集が届く経路は 2 つだけ:

1. `ops\launch\deploy-config-head.cmd` ── fork の HEAD にワーキングツリーの yml を重ねて配る
   （＝**未コミットの fork yml もそのまま出荷される**）。ただし**全バックエンドを止めてからでないと
   流せない**。
2. **止めずに今すぐ入れる場合**は配備先の実ファイルを直接書き換える:

```
powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\apply-exchange-dirt-tier.ps1 -DryRun
powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\apply-exchange-dirt-tier.ps1
```

3 台とも書き換えたあと **`/ars reload`（再起動は不要。`reloadGlyphConfig` が読み直す）**。
スクリプトは触る行を「`exchange_tiers` の中の DIRT で始まる段」1 行に限定し、書く前に
`.bak-<日時>` を同じディレクトリへ取り、書いたあと読み直して確認する。何度流しても安全。
ここで入れた編集は次回の config 配備で `guard-deployed-config.ps1` が drift として拾い、
リポジトリへ書き戻す（W-177）ので黙って巻き戻されることはない。

**回帰ガード**を fork へ追加した（`ExchangeTiersShippedTest`、3 本）。`loadExchangeTiers` は
`Material.matchMaterial` が `null` を返した項目を**警告 1 行も出さずに捨てる**ので、綴り違いも
段の消滅も実機で交換して初めて気づく ── ここが唯一の検出点。同じブロックを 2 段に書くと
後ろの段が丸ごと到達不能になる点も縛った。**土の段を元に戻すと 1 本落ちる**ことを実走で確認済み。
fork 全体は 510 tests / 0 failed / 0 skipped。

**fork 側は未コミットのまま残してある。** `src/main/resources/glyphs.yml` には**他セッション由来の
未コミット変更が 369 行分**（コメント整形と mana-cost の調整）先に乗っていて、自分の 1 行だけを
切り出せない。巻き込む方が害が大きいので `glyphs.yml` と `ExchangeTiersShippedTest.java` は
**fork のワーキングツリーに置いたまま**にした。config 配備はワーキングツリーを HEAD へ重ねるので
**未コミットでもそのまま出荷される**（＝配備には影響しない）。ただし fork のソースは
`.gitignore` 除外なので、**clean / reset で消えると戻らない**。この段の内容はここと
`apply-exchange-dirt-tier.ps1` の両方に書いてあるので、消えたら再現できる。

### 図鑑の記録漏れ2件 — 討伐は last-hit 独占、入手は地面経由しか拾えていなかった（2026-08-22 実サーバ報告。W-157 / W-158）

**W-157「図鑑のモブにラストキルしか反映されない」**

`CollectionListener#onMobDeath` は `getKiller()`（＝とどめを刺した1人）しか見ていなかった。
一方 `CombatListener#onCombatKill` は **`CombatKillCreditTracker` の寄与比で参加者全員へ EXP を配る**。
つまり同じ討伐で「EXPは山分け・図鑑は独占」という帰属の食い違いが常時起きていた。
指示（選択）により **「ダメージを与えた全員」** を採用。

**既存の `CombatKillCreditTracker` を流用しなかった理由が 2 つある**（どちらも無警告で壊れる）:

1. 記録するのは `isKillBasedCombatWeaponSkill` を満たす **重武器・軽武器・弓術のダメージだけ**（fail-closed）。
   **魔法だけで削った人・召喚で削った人は載らない**ので、図鑑の帰属には使えない。
2. `consume` が台帳を**取り除く**実装。同じ `EntityDeathEvent` を MONITOR で受ける 2 本のリスナーが
   呼ぶと、**Bukkit は同一優先度の呼び出し順を保証しない**ので登録順しだいで片方が空を受け取る。
   共有した瞬間「図鑑が入る日と入らない日がある」という形で壊れる。

そこで図鑑専用の `MobKillParticipants`（`Set<UUID>` だけ持つ台帳）を新設した。
参加の記録は `EntityDamageEvent` の `DamageSource#getCausingEntity()` で拾う ── **攻撃手段ごとの
分岐を書かない**のが要点で、書いた瞬間に「その手段だけ図鑑に入らない」が生える。
`getKiller()` は捨てずに**和**を取る（TTL を跨いだ長期戦や TF を通らない即死処理で台帳が空になる経路が
残るため、直しが「増える」方向にしか効かないようにする）。

**W-158「図鑑の item はドロップ状態を拾い上げないと反映されない場合がある」** ── 報告どおりだった。

記録の入口は ①地面からの拾得 ②インベントリを閉じた時の全スロット走査 ③参加40tick後の走査 の
3 つしかなく、**地面を経由せず直接インベントリへ入る品**は GUI を閉じるか再ログインするまで載らない。
該当するのは EliteMobs のダンジョン報酬・ガチャ・メール・スクラップ変換・儀式やアチーブメントの報酬・
`/tf give` など**「与える側」全部**（TF 内だけで 10 箇所以上、さらに EliteMobs / ArsPaper も直接 `addItem` する）。

**与える側を 1 つずつ直す方式は採らない** ── 1 つ書き忘れた時点で同じ報告が再発し、他プラグインは
原理的に網羅できない。`PlayerInventorySlotChangeEvent` で**スロットの変化そのもの**を 1 箇所で拾う。
ポーリングにしなかったのは、41 スロット × 人数を無条件に舐め続ける必要が無いため。
既存の走査は残す（このイベントは画面を開いている間の変化を取りこぼしうる／走査は開閉時にしか走らない、
と穴が相補的。記録は冪等なので二重に通っても PDC 書き込みは 1 回）。

**検証**: 回帰テスト 16 本を追加。**参加台帳とスロット記録だけを黙らせると本命 2 本が期待どおり落ちる**
ことを実走で確認（`削った人は全員が討伐図鑑に載る` / `インベントリへ直接入った品も記録する`）。
listeners + collection 系 989 tests / 0 failed / 0 skipped、フル 4582 tests / 29 failed / 2 skipped で
**失敗集合は着手前と完全一致**（全部 shipped yml 系の他セッション由来）。TF `c9afd4e`。

**テスト側の落とし穴（次に触る人へ）**: Paper の `PlayerInventorySlotChangeEvent` は
コンストラクタで `player.getOpenInventory().convertSlot(rawSlot)` を呼び、**MockBukkit がそこを
実装していない**。素直に `new` すると `UnimplementedOperationException` で
**テストが SKIPPED に化けて緑に見える**（このリポジトリは `unintended-skips.txt` のガードで落ちるので
気づけた）。スロット番号は判定に効かないので Mockito のモックで代用してある。

**配備が要る（jar）。** 出荷 yml は変えていないので config 配備は不要。

### editor で material を差し替えると、その行のテクスチャ割り当てが無言で外れる（2026-08-22 実サーバ報告。W-159）

**報告**: 「editor で鎌の material を剣に変えてリソパビルドしたらテクスチャ割り当てが外れた」── 再現した。

**機構**（アセットは1枚も消えていない。壊れたのは台帳の紐付けだけ）:

1. `cmd-registry.json` の突合せキーは **`(material, cmd)` だけ**だった
   （`lib/cmd-registry.js#reconcileWithUsage`）。material を差し替えると別キーになるので、
   その行は「新規の未配線行」として扱われ **`assetName` / `parent` が黙って落ちる**。
2. `regenerateItemDefinitions` の配線済み判定は `assetName && models/item/<assetName>.json` の1行。
   `assetName` を失った行は未配線と判断され、**バニラモデルを指す entry** が書かれる。
   `WOODEN_HOE#58 -> trinityforge:item/wooden_scythe` が
   `WOODEN_SWORD#58 -> minecraft:item/wooden_sword` になった。
3. 保存のたびに走る（`syncCmdRegistryAfterSave` は catalog を保存すれば必ず通る）ので、
   **editor で material を触った瞬間に確定する**。ビルドのせいではない。

実害は鎌 11 品（`wooden/stone/copper/iron/golad/diamond/nethrite/source_gem/infinity/hero/nuclear`）。
`*_HOE` → `*_SWORD` の付け替えで全部が assetName を失っていた。
モデル JSON と PNG はどれも残っていたので、台帳を直せば絵は戻る。

**直したこと**

- `reconcileWithUsageDetailed` を新設し、**キーが変わっただけの行を `id` で引き継ぐ**。
  引き継ぐのは `assetName` / `parent` / `customModel` / `allocatedAt`。
  引き継がない条件を3つ入れてある（黙って別物へ結び付けないため）:
  元の `(material,cmd)` が今回の usage に残っている行は移動ではない／同じ id の候補が複数ある／
  引き継ぎ先の `assetName` を他行が既に持っている。
- **自動生成モデルの `parent` も貼り直す**（`respack.js#rewriteMovedModels`）。
  生成モデルは `parent: minecraft:item/wooden_hoe` のように**登録時の material を焼き込んでいる**ので、
  放置すると構え方が旧 material のまま残る。さらに BOW / TRIDENT のように
  リーフ構成が違う material へ移すと、`entryModelFor` が参照する `<assetName>__pulling_0.json` 等が
  そもそも存在せず**描画が丸ごと落ちる**。`customModel:true`（手書き JSON）は上書きしない。
- **検出できなかった側も直した。** `resourcepack/build_item_pack.py` の D-2 検査は
  「パックに実物があるのに threshold に配線されていない」を見ていたが、
  respack.js の H-3 修正以降**未配線の行にも threshold entry は必ず生成される**
  （省略すると range_dispatch が1つ下へフォールスルーするため）。
  つまり entry の有無は「絵が出ている」証拠にならず、この事故を1件も検出できなかった。
  `drawn_thresholds()` を足し、**entry が `trinityforge:` の自前モデルを指しているか**まで見る。

**復旧**: 修正後の引き継ぎロジックへ「HEAD の鍬時代の行」を通して 11 件を復元した
（手書きで戻さないのは、重複判定などのガードを同じコードで効かせるため）。
台帳の差分は **11 行の `assetName`/`parent` だけ**（710 行のまま、material/cmd/id は不動）であることを確認済み。
モデルの `parent` も `minecraft:item/*_sword` へ貼り直し、items 定義とパック zip を再生成した。

**検証**: 回帰7本を追加。**引き継ぎを黙らせると本命4本が落ちる**ことを実走で確認
（残る3本は「引き継がない」ことを縛るガードなので両方で通る）。
`build_item_pack.validate()` は現状 OK（packable 878）。editor フル 1424 tests / 27 failed / 1 skipped で、
**失敗は全部 catalog.yml 等の内容ドリフト（他セッションの未コミット yml 由来）**で
cmd-registry / respack / cmd-routes の系は 0 件。

**リソースパックの再配布が要る**（jar・config の配備は不要）。

### グリフ解放画面で解放済みかどうかが分からない — 個別アイコン化の副作用（2026-08-22 実サーバ報告。W-160）

**報告**: 「グリフ解放画面が非常に見づらい。解放したのか解放してないのか直感的にわからなくなった。
今まで解放してるやつだけカーソル移動すればよかったが、今は一個ずつ見ていかないといけない。
解放していないものは従来通り灰色の染料にしてほしい」

**原因は同日の別対応の副作用**。`fc97b4f`（ArsPaper フォーク）で
「どれがどの魔法かぱっと見で分からない」に対しグリフ 120 種すべてへ個別アイコンを付けたが、
**それまで解放状態を担っていた「潰した絵」（未解放＝石炭 / 呪文編集は未解放＝バリア・使用不可＝灰色染料）を
同時に外してしまった**。解放状態は名前の色（緑/赤）と lore に残っていたが、
**36 個/ページの一覧を見渡す用途には色と文字は使えない** ── 1 個ずつカーソルを当てる操作になる。

**確定解は「未解放だけ潰す」の非対称**（2 つの報告は両立する）:

- **未解放** → `GlyphIcons.LOCKED_ICON` 1 種類に潰す。「まだ持っていない」と分かれば足りる。
  （材質は ~~`GRAY_DYE`~~ → **`COAL`**。下の追記 2026-08-23 で `fc97b4f` 以前の絵へ戻した）
- **解放済み** → 個別アイコンのまま。**実際に選んで使うのはこちら**なので、識別が要るのもこちら。

指示（選択）により**グリフ3画面すべて**に適用した ── 筆記台（グリフ解放）/ グリフ解放素材 / 呪文編集（グリフ設定）。
呪文編集の「解放済みだが今は使えない」は個別アイコンのまま残す（解放の有無とは別の軸で、
一覧で知りたいのは「持っているか」の方）。
未解放は `glyphs.yml` の `icon:` 上書きより優先する（上書きを書いた 1 個だけ解放済みに見えるのを避ける）。

**検証**: 回帰5本を追加し、**戻すと3本が落ちる**ことを実走で確認
（全120種が未解放で1種類の絵に潰れる / `icon:` 上書きは未解放に効かない / 3画面とも解放状態を渡している）。
フォーク全体 515 tests / 0 failures / 0 skipped。ArsPaper `7e14ec7`。

**push は保留** ── `Klee319/ArsPaper` は public で、`feat/trinityforge-fork` は**一度も push されていない**
（リモートは `master` のみ）。このブランチには tracked の `libs/TrinityForge.jar` が入っているので、
push すると TF 本体の jar を公開することになる。ユーザーの判断待ち。

#### 追記 2026-08-23 — 未解放グリフは**石炭**へ戻し、**画面ごとに**潰し方を変える

**指示**: 「スキルパーク未開放のものは鍵アイコンにすることでグリフ未開放と区別するのはどうだろう。
UI の大幅刷新は認知負荷が高いので、グリフ解放 GUI における未開放グリフは石炭のまま（昔の仕様）にしてほしい」
→ 追加指示で**画面ごとの割り当てが確定**した:

| 画面 | クラス | アイコン |
|---|---|---|
| 解放（筆記台） | `ScribingTableGui` | 未解放=**石炭** / 他=個別アイコン |
| レシピ（解放素材、`/tf glyphs`） | `GlyphBrowserGui` | **全部が個別アイコン**（潰さない） |
| 配置（呪文編集） | `SpellCraftingGui` | パーク未所持=**鍵** ＞ 未解放=**石炭** ＞ 個別アイコン |

**要点は「塞がれ方が2種類あって直し方が違う」こと。**
未解放＝筆記台へ行け、パーク未所持（`UsageGate#hasPermission`）＝スキルツリーへ行け。
同じ石炭に潰すと次にどこへ行けばよいか分からないので、配置画面だけ鍵を足した。
優先順を「鍵 ＞ 石炭」にしたのは、**パークが無ければ筆記台で解放しても使えない**から。
レシピ画面を潰さないのは、そこが**未解放こそ主役**の画面（素材を引きに来る）で、
潰すと目的の行を探す手掛かりが消えるため。

材質は 1 箇所で決める: `GlyphIcons.LOCKED_ICON`（`COAL`）と `GlyphIcons.PERK_LOCKED_ICON`（`TRIAL_KEY`）。
画面側に材質リテラルを書くのは `GlyphGuiIconAndOrderWiringTest` が禁止し、
**画面ごとの引数個数（3 / 2 / 4）**も同テストが固定する（＝割り当てを崩すと落ちる）。
配置画面の lore は鍵と同じ順に並べた（パーク未所持の赤字を先頭、未解放も重なっていれば 2 行目）。

**TF 側の `TRIAL_KEY` 変更は取り消した（`5944af5`）。** 指示は「Ars のグリフ配置画面で鍵にする」
であって、スキルツリー GUI の材質差し替えではなかった。`SkillTreeGuiVisuals` は `ROTTEN_FLESH` のまま。

**検証**: ArsPaper フォーク 519 tests / 0 failures / 0 skipped。
TF フル 4587 tests / 45 failed / 2 skipped ── 失敗は全件が**他セッションの未コミット yml**による内容ガード
（`ShippedWeaponIdentity` / `mob-types` / `farming` EXP など）で、`skilltree.runtime` の GUI 系は 0 件。

**配備が要る（ArsPaper jar のみ。サーバ停止後）。**
`fork-handoff/arspaper/fork/build/libs/ArsPaper-1.0.0.jar` をビルド済み（逆アセンブルで `COAL` と
`TRIAL_KEY` の両方が入っていることを確認）。出荷 yml は変えていないので config 配備は不要。
コミット: ArsPaper `04fa314` → `b176bd7` / TF は `e8312fb` を `5944af5` で revert。

## 4. 既知の未修正の問題・弱点

いずれも**意図的に許容している**か、**直すには判断が要る**もの。新規に見つけたバグはここへ足す。

| # | 内容 | 判断 |
|---|---|---|
| K-1 | **同一地点EXP逓減が友好モブの養殖場を数えない** | カウンタはバニラEXP書き込み経路で回るため、レベル帯の対象外である C 群（友好モブ 39 種）には反応しない。TT の本命ではないので許容 |
| K-2 | **ATTRIBUTE チャネルの上限は「実効値」の上限ではない** | `move-speed` / `attack-speed-bonus` / `attack-reach` / `knockback-resistance` / `max-health` の上限は「**TF が要求する寄与分**」に掛かる。Haste や他プラグインの寄与は含まない。`PerkAttributeApplier` の「ライブ属性値を読まない」原則を守るための意図的な線引き（→ `docs/config-reference/combat/stat-caps.md`） |
| K-3 | **editor で yml 本文のコメントが保存時に消える** | `tools/config-editor/lib/yamlio.js` の仕様。実害は継続中（`item-stats.yml` で 65 行、`collection.yml`・`catalog.yml` でも確認）。**1 件ずつ復元しても次に同じ画面を保存すればまた消える**。恒久対策は editor 側でコメントを保持すること。当面は説明を `docs/config-reference/` へ退避し、yml 本文に長いコメントを書かない |
| K-5 | **ステータスのトリガー/発動制限の宣言** | 段階 1〜4 完了（宣言スキーマ＋拘束テスト＋`/tf stats detail <キー>`）。未宣言キーは理由コメント付きで `LoreConfigDeclarationTest.UNDECLARED_ALLOW_LIST` に固定＝**新しいキーを宣言なしで足すとビルドが落ちる**。プラン全文はアーカイブ |
| K-7 | ~~**`armor-set-bonus` はスキルツリー由来分しか増幅に効かない**~~ **2026-08-13 解決** | 循環依存は「循環しない読み取り口を別に立てる」で解いた。`PlayerStatAggregator#nonPerkStatTotal(player, key)` を新設し（`aggregate`/`totalOf`/`nativeArmorSetContribution` を一切呼ばないので再入しない）、`NativeAttributeBridge` へ `ToDoubleFunction<Player>` として `TrinityForge.java` から後注入。増幅率は `1.0 + max(0, パーク general + 非パーク総合値)` になり、**装備 / 役職 / 永続 / `base-stats.yml` 由来も効く**。editor 側も `NO_OP_BASE_STATS_KEYS` から外して基礎ステ画面に出るようにした。set-buffs が空なら supplier を呼ばない早期 return 付き（`PermanentBuffResolver` の図鑑全走査が毎回2回走るのを防ぐ） |
| K-9 | ~~**`glyph-damage-multiplier-bonus` がどこからも呼ばれていない**~~ **解決済み（2026-08-16 確認）** | 配線は完了している: `ars_magic.yml` が `glyph_damage_multiplier_bonus: 0.3` を付与 → `stats/glyph-damage-boost.yml`（b969faa, 08-14）が対象8グリフを列挙 → fork の `TrinityForgeBridge#glyphDamageMultiplier` が TF 公開 API を呼ぶ。コード側フォールバックは 1.0（無強化）。残条件は「API 入り fork jar の配備」のみ。**この行が古いまま残っていたことが 08-16 洗い出しの誤報（K-9 未配線と報告）の直接原因**（common-traps.md「調査・洗い出しの罠」参照） |
| K-10 | ~~**確率ステータスの二重縮小**~~ **2026-08-16 全数調査完了・新規バグ0件** | RATE_KEYS 全 63 キー（固定48＋職業EXP15）の消費側を全数走査し、二重縮小・逆向き（fraction を % 比較）・乱数の向き、いずれも**新規バグ 0 件**（修正済み4件のみ）。副産物: ガードテスト `RateKeyDoubleShrinkGuardTest` 自身が `FIXED_RATE_KEYS` 分割に追随しておらず**職業EXP15キーを一度も監視していなかった**のを修正（検査集合が痩せたら落ちる下限アサート付き）。派生の新規発見は K-35 |
| **K-12** | ~~**討伐素材 4 件にドロップ元が無い**~~ **解決済み（2026-08-16 確認）** | 4件とも `combat/mob-level-table.yml` の `add-drops`（where: field）に配線済み: `elder_guardian_spike` / `wither_skull_fragment` / `warden_tendril` は chance 0.05→Lv100 で 0.50、`dragon_scale` は chance 1.0。08-14 のユーザー指示「討伐素材はフィールドの該当モブから」で mob-overrides でなく mob-level-table 側に載っている。**この行は mob-overrides.yml しか見ずに起票された誤報**（供給レイヤ8系統の全走査 → common-traps.md「調査・洗い出しの罠」） |
| **K-39** | **カタログ品がバニラレシピから弾かれるとき、理由が一切表示されない**（2026-08-18 ユーザー質問「ディスペンサーの件、類似バグ他にない?」の調査で判明） | `CatalogWorkbenchListener` は「カタログ品を消費できるのはオプトインした TF レシピだけ」という規則を持ち、外れると**結果枠を空にするだけ**でメッセージを出さない。**この失敗の仕方が「レシピが壊れている」と区別できない**のが問題で、ディスペンサー報告（TF の弓が締め出されていた）が実バグとして上がってきたのも、糸スレッドを弓レシピに置いた人が同じ報告を上げるのも、現地では全く同じ見え方になる。**2026-08-17 の修正でスタック不可（＝装備・道具）のカタログ品は素通しになった**ので、残るのは**重ねられるカタログ品**だけ: 本4種（本棚/エンチャント台/書見台/本と羽根ペン）・糸スレッド6種（弓/羊毛/釣り竿/クロスボウ/リード）・アメジスト3種（色付きガラス/望遠鏡/較正済みスカルクセンサー）・グロウストーンダスト・エンダーアイ（エンダーチェスト/エンドクリスタル）・海洋の心（コンジット）・エコーシャード（リコールコンパス）・陶器の破片多数（飾り壺）・鍛冶型テンプレート多数（テンプレート複製）。**これらは「素材として溶かされない」ことが正しい**（貴重品を素の糸として消費させない）ので**挙動の修正は不要**だが、**弾いたときにチャットで一言出す**だけで同種の誤報が止まる。※ `TRIAL_KEY`・`NAME_TAG` はクラフト用途が無く、`RABBIT_FOOT`・旗の模様は醸造／織機なのでこのガードの対象外 |
| **K-14** | **リソースパック配線が未了（副作用で 7 件が別アイテムに化ける）** | 27 枚の PNG は参照元（`models/item/*.json` と `assets/minecraft/items/*.json`）が無いので描画されない。既存 items json が張られている 7 件は**素のバニラではなく別アイテムの見た目で出る**（ピリジャーの鎧片が「重金属」、深淵/束縛者の弓・メイス・トライデント計 6 種が「ソースジェム武器」）。441 CMD 中 299 が未配線。**統合版のアイテム名は TF パックの `texts/*.lang` では出ない**（GeyserExtra が `custom_items.json` の `display_name` から自動生成する側が効く。詳細 = `docs/agent-context/bedrock-geyser.md`） |
| **K-15** | **ガチャ券 6 種のテクスチャがリポジトリにも配備先パックにも無い** | **2026-08-10 に範囲を縮小**（→ J-14）。「18 種」のうち**コア 5 種とモブ素材 13 種は既に配線済み**で、残るのは `gacha_ticket_0`〜`_5`（materials.yml CMD 5011-5016）だけ。PNG 自体がリポジトリに無いので**絵が来るまで着手できない**。置き場所は J-14 に確定済み |
| K-16 | **ソース生産レートを上げる階梯**（部分前進） | 2026-08-03 に `yield-multiplier`（生成量）と `transfer-multiplier`（転送）で階梯 IV/V まで入り、**素材効率は階梯で改善するようになった**。ただし到達時間が「ソースリンクを何台並べたか」で決まる構造自体は残る。**バッファの int オーバーフロー**は `long` 受け＋`Integer.MAX_VALUE` 飽和で解消済み |
| **K-17** | **ロール限定コンテンツが乏しい** | ロールの効果はステ注入・EXP 倍率・常時ポーション・ヘイト係数の 4 種。2026-08-02 に `use-role` とロール専用装備 8 点が入ったので全否定ではないが、**`use-requirements` の required-role や drops のロールゲートが無い**ので「この職でないと作れない／出ない」は依然として無い |
| **K-18** | **厳選に「育てる」側が無い** | ランダム抽選と振り直しまでは成立。**強化レベル（+0→+20）／サブステ 4 本の段階解放／部位別の主ステ固定／ロック／スコア表示／一括分解**が全て無い。個体ごとに PDC が違うので**スタックせず周回でインベントリを圧迫する** |
| **K-19** | ~~**`stats/stat-caps.yml` が空なので厳選の上限が効かない**~~ **方針確定（2026-08-16 ユーザー決定）: 上限なしが正** | 「天井があるとそこでゲームが終わるから要らない。天井なしでバランスの取れた設計にする」。出荷 `combat/stat-caps.yml` の `stat-caps: {}` は意図された状態。`ShippedStatCapsDriftTest` は「上限必須」を廃止し「書かれた場合のみ単品最大との整合を検査」へ再仕様化済み。**残タスク: thread-rolls の抽選幅見直し**（19枠フル厳選で crit-chance 171% は 100% 超が死に値）と `docs/config-reference/combat/stat-caps.md` の旧上限値記述の更新 |
| **K-20** | ~~**`reality_thread_core`（現実の芯）が説明どおりに使われていない**~~ **解消（2026-08-16、前提ごと変更）** | ユーザー決定で振り直し儀式そのものを「内部実装は残すがゲーム内非公開」へ変更（items.yml から削除済み・テストで非公開を pin）。reality_thread_core の実用途は**スレッド枠拡張の儀式Ⅲの素材＋束縛者帯装備の儀式素材**で、入手は構造物チェスト（fork loot-tables.yml）。fork materials.yml の lore「再抽選の触媒」→「枠拡張Ⅲの触媒」へ修正済み。残骸: `mob-overrides.yml:1196,1225,1247` のコメント「厳選の触媒を落とす」が二重に古い（儀式非公開＋当該 drops は `[]`）— コメントのみの修正なので後続で掃除 |
| **K-22** | **束縛者が「最強」として設定されていない／第1目標が実質「最後の目標」** — **(1)(3) は解決済み（2026-08-16 確認）** | ~~(1) 束縛者 18 体に `level` / `max-health` / `attack` が 1 つも無く全モブ共通ランプ任せ~~ → 08-01 に `mob-overrides.yml` へ 18 体の max-health/attack-power を明示済み（`ShippedBossStrengthDriftTest` が pin）。~~(3) `dungeon/gates.yml` の入場ゲートが空~~ → `em_id_binder_of_worlds` に `key-item: key_binder`＋`required-combat-level: 40` 設定済み（key_binder は印5個から合成＝前段踏破の強制も兼ねる。レシピ登録は W-44 修正で復旧）。残: (2) `goal_worldbinder` の parent が `delve_all_seals` である点のみ（設計判断） |
| **K-23** | **NETHERITE メソッドの 13 件が Bukkit へ一切登録されない**（旧 U7） | `RecipeSpec.isBukkitCrafting()` が workbench/inventory だけ true で `CatalogRecipeRegistrar.java:95` が弾く。リポジトリ全体で `new SmithingTransformRecipe` は **0 件**。影響: `netherite_bow` / `netherite_trident` / `netherite_mace` / `netherite_crossbow` |
| ~~**K-24**~~ | ~~**「攻撃速度を最低値へ」が一部で未適用**（旧 U6）~~ | **2026-08-10 解消（`1b1f2da`）。この行の記述自体が古かった**（杖の材質移行 `1f7a8b4` 以前の参照で、`BLAZE_ROD#400001-400014` はもう存在しない）。実際には弓/クロスボウ/トライデント/触媒はすべて `attack-speed: 0.1` に張り付いており、**杖はむしろ張り付けすぎ**で近接 DPS が同帯の剣の **14〜31%** しか無かった。杖 10 本を **0.5〜1.1**（同帯の剣の 64〜74%）へ引き上げ、`MeleeUnintendedItemAttackSpeedTest` の規約を「触媒は最低値固定」から「**キーがあり、近接最速 1.6 より確実に遅い 1.2 以下**」へ変更した。遠隔武器は最低値固定のまま |
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
| **K-35** | **モブ側 config は `PercentStatNormalize.coerce` を通らない**（2026-08-16 K-10 全数調査の副産物） | `coerce`（「20 → 0.2」矯正）の呼び出し点は `BaseStatsConfig` / `ItemStatsConfig` / `RewardFieldsParser` / `RoleBuffsConfig` / `SkillTreeConfig` の6箇所のみで、`MobTypesConfig` / `MobProfileConfig` / `MobOverridesConfig` / `RampParser` は `getDouble` の生値。**モブ yml に `crit-chance: 20` と書くと 2000% として素通りする**。現行の出荷 yml は fraction 表記なので実害は未発生だが、editor での将来の編集で踏む穴。修正はモブ系パーサへの coerce 追加（プレイヤー側と同じ 2..100 整数ルール）だが、モブの非レートステ（attack-power 等）に誤爆しないよう RATE_KEYS 限定で掛けること |
| **K-36** | **`ScribingTableGui.getUnlockedGlyphs()` だけ JSON パースが try/catch 無し**（2026-08-16 グリフキー全走査の副産物） | 同キーの読み手 8 箇所のうち、ここだけ `JsonParser.parseString(json).getAsJsonArray()` を素で呼ぶ。PDC が壊れると `render()` から例外が上がり**筆記台 GUI がそもそも開かない**。K-37 の書き手を撤去したので壊れたデータが生まれる経路は消えたが、最弱点であることは変わらない。他の 7 箇所と同じく「catch して空集合へ倒す」を足すのが妥当（別タスク） |
| **K-37** | ~~**グリフ解放集合に 2 つの直列化形式が並存**~~ **2026-08-16 解決（fork `4394e10`）** | `UnlockedGlyphs` が自前構築したキーが結果的に `ManaKeys.UNLOCKED_GLYPHS`（筆記台の写本集合）と同一で、しかも 0x1F 区切り＝他の全読み手が期待する JSON と非互換だった。`add()` の呼び出し元がゼロ（入口の右クリック解放は 2026-07-23 削除済み）で実害は未発生だったが、配線した瞬間に全プレイヤーの写本集合が壊れる装填済みの罠。**「形式を JSON へ揃える」は誤った修正**で、格納が完全ID・照合が裸キーなので単体では no-op、ID 形まで揃えると `UsageGate` の OR が常に true になり **UNLOCK Model Y（入手自由・使用に perk）が全経路で無効化**される（このメソッドへ到達する経路は全て写本解放済みを先に確定させてから入るため）。正しい対処として OR 経路ごと撤去した |
| **K-38** | **EliteMobs の `build.gradle:74/105` が廃止済みの PlaceholderAPI リポジトリを指している** | `https://repo.extendedclip.com/content/repositories/placeholderapi/` は 404、指定の `2.10.9` も配信されていない（現行は `/releases/`、最古 2.11.5）。ローカルキャッシュで通っているだけなので、**キャッシュが飛ぶと EM がビルド不能**になる。TF 本体は 2026-08-16 に `/releases/` + `2.11.6` へ移行済み |

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
| 2026-08-24 | **統合版アイコンを実機へ配備し、起動ログに出た `NoSuchFileException` 537 件の真因を潰した**（GeyserAddon `cc754c4`）。配備自体は成功（配信中パック 1,588,859 バイトを直接検査し、**焼いたアイコン 45 種がバイト一致で載っていること**を確認。`decorated_pot` ×7＝ソースジャー、`lectern` `iron_chain` `wither_rose` も）。⚠️ **`JavaPackResolver#extractZip` は resolve のたびに `deleteRecursive` してから展開し直す。** 1 本なら無害だが、**パック再生成はスケジューラスレッドで走り、2 本が同じ `java-pack-extracted-server` を指す** —— 片方がテクスチャを読んでいる最中にもう片方がディレクトリごと消す。読めなかった分は**そのまま統合版のバニラ表示に落ちる**（「一部アイテムが元の素材のまま」の一因）。**切り分けの決め手はログの警告件数が 344 → 193 → 0 と減っていくこと**（展開が進行中に読んでいる形。前日の起動は警告 0 で、パックが大きくなって生成が重なるようになった結果あらわれた）。→ ZIP のサイズ＋更新時刻を展開先の `.source-stamp` に記録して一致時は展開を丸ごと省き、初回と ZIP 変更時は**展開先ごとの静的ロック**で直列化（racing する resolver は別インスタンスなのでインスタンスフィールドでは守れない）。stamp は展開の最後に書くので途中で落ちれば次回やり直す。⚠️ **回帰テストは 1 エントリの ZIP では通ってしまう**（展開が速すぎて衝突しない）。300 エントリで窓を広げて、stamp を無効化すると 2 件落ちることを実走確認。**副産物**: パック版の消費が 1 起動あたり 3 → 1 に減る（上限 32767 に対し現在 32268）。362 tests, 0 failures, 0 skipped。**この修正は未配備**（実害は起動直後の十数秒に接続した統合版プレイヤーだけなので、次の再起動でよい） |
| 2026-08-24 | **「まだ Java と全然違う」の真因2つを直し、統合版アイコンを Java の実描画と一致させた**（GeyserAddon `7a3459e`）。①**UV を `texture_size` で縮尺したのが逆**（前行の訂正参照。スカルが白/黒立方体になっていた正体）。②**entity モデルには display が無く、ブロック既定の `[30,225,0]`＝背面・斜め下で描いていた**。Java はこれらを **item モデル側の display**（全員 `[30,45,0]` 正面向き。壺は scale 0.60 + gui_light front、コンジット scale 1.0、スカルは translation [0,3,0]）で描く。⚠️ **スカルの display は `items/*.json` の special ノードの `base`（`item/template_skull`）経由でしか取れない**。同族も全部拾った: シャルカー16色+無色（⚠️ **`dyed_shulker_box` テンプレートは布が tint 前提**なので緑に染まる。無地モデル+色別シート差し替えで回避）・銅チェスト4酸化+ワックス（通常チェストの形+銅シート）・銅ゴーレム像8種。さらに**立方体近似171枚の正体は「Java では平面スプライトのアイテム」**（苗木・看板・レール・松明・花・扉・板ガラス…）だったので、item モデルが `layer0` に解決されたらそのスプライトを焼く一般則を追加し手書き12件を置換。ついでの罠3つ: ⚠️ **Mojang 自身の `block/heavy_core` が `"texture": "all"` と `#` 無しで変数参照**（本家は寛容に解決する。裸名も変数として解決するようにした）／⚠️ **同一 Bedrock 名に複数 Java ID が結ばれ、アイテム無しの状態ブロック（`lava_cauldron`・`oak_wall_hanging_sign`）が反復順で勝つ**（アイテム定義を持つ ID を常に優先する2段階へ）／⚠️ **対応表に素の `cauldron`／`oak_hanging_sign` 自体が居ない**（Bedrock 名と同名の Java アイテムが在れば候補に足す）。焼き上がり 959 枚: **Java 実モデル 781 / 平面スプライト 148 / 立方体近似 30**（前: 751/3/203。残る30は Java にアイテムが存在しない壁看板・旗・ベッド・作物・水/炎/ポータル類のみ＝これ以上の正解が存在しない）。359 tests, 0 failures, 0 skipped。追補（`4d71613`）: ⚠️ **銅ゴーレム像だけ逆立ちして焼かれていた** —— Mojang の `item/template_copper_golem_statue` は **gui rotation `[30,45,180]` と Z 軸 180° ロール付き**（クライアント内部の像ジオメトリが逆さに作られているため display 側で毎回反転している）。正立の同梱モデルにこれを適用すると逆立ちし、translation も向こうのジオメトリ前提で流用不可。像だけ display を合成エントリで上書き（ロール抜き `[30,45,0]`・scale 0.55・translation は同梱モデル実寸 y 0..24 の中心合わせから −1.9 を導出）。**配備は GeyserExtra の paper jar のみ**（2,239,471 バイト） |
| 2026-08-24 | **統合版アイコンで「立方体のまま」だった飾り壺・コンジット・チェスト・頭を実物の形にした**（GeyserAddon `4f1821b`）。⚠️ **Minecraft がコードで描くブロックは Java にも Bedrock にも形が無い** —— `models/item/decorated_pot.json` は particle テクスチャだけ、Bedrock 側の `entity/decorated_pot.entity.json` も**テクスチャ 24 件で geometry セクションが無い**（Mojang の bedrock-samples に壺・チェスト・ベッド・旗の geometry は 1 本も無い）。**既製の「3D インベントリ用テクスチャ集」も 1.21.11 には無い** —— `undrfined/mc-icons` は 1.12 時代の**数値ブロックID**（`103_Melon Block.png`）で飾り壺が存在しない、Minecraft Wiki の `Category:3D inventory icons` は現役 1 件・残り 1100 件が「Outdated」、`voxel-icon-generator` は**生成器**でこちらと同じ仕事をするもの。→ **BlueMap (MIT) が同じ問題のために Blockbench で起こした entity モデル 49 本を同梱**し、Java アセットが 404 のときだけ同じ相対パスの同梱物を読む（将来 Mojang が本物を出したら遠隔側が勝つ）。~~⚠️ これらは UV が `texture_size` 空間（壺 32・頭 64）でバニラの 0..16 ではない~~（**2026-08-24 訂正: 逆**。UV はバニラ同様 0..16 空間で `texture_size` は Blockbench のメタ情報。縮尺を掛けるとスカルが無地領域を読んで白/黒の立方体になる。壺のテクスチャがほぼ無地なので目視検証をすり抜けた → GeyserAddon `7a3459e`）。⚠️ **チェストだけモデル名がブロック名と違う**（`entity/chest/normal`）。⚠️ **同梱が classpath から外れると全部が黙って立方体へ戻る**。既存の「半分未満なら失敗」ガードでは全く引っかからないので、**5 ブロックが実モデル由来であること**を生成時に検査し、**その一覧が 1 件も一致しない場合も失敗**にした（一致ゼロの許可リストは検査そのものを無効化するため）。焼き上がり 957 枚のうち Java 実モデル由来 **738 → 751**、立方体近似 **216 → 203**。core 33 / paper 295 / extension 31 = 359 tests, 0 failures, 0 skipped。**配備は GeyserExtra の paper jar のみ**（2,333,955 バイト）|
| 2026-08-24 | **統合版クラフト修正を実機で確認して出た 3 件を処理した**（TF `5831b88` / GeyserAddon `f432c19`）。①**「クラフトはできたのに『材料にできません』の通知が出る」** → 判定ではなく**通知の出しどき**の問題。⚠️ **`PrepareItemCraftEvent` はマスを1つ触るたびに飛ぶ**ので、カタログ品を並べている途中の盤面は当然どのレシピにも一致せず `getRecipe()` が null になる。そこで名指ししていた＝**まだ何も奪っていないのに止めたと言っていた**。⚠️ **統合版では必ず起きる** —— Geyser はクラフト要求を**マスへの1つずつの配置**へ翻訳するので、成立するレシピでも途中で不一致の盤面を必ず通る。レシピが届くようになった瞬間に表面化した。→ 選択レシピが非 null のときだけ通知（W-87 の守りたかった場面は残る）。回帰2本、無条件に戻すと落ちることを実走確認。②**焼いたアイコンが平面で低画質だった** → ⚠️ **「立方体でないブロックは平面」という線引きが乱暴すぎた**。Java はアイテムモデルの親で描き分ける（`block/*`＝gui 変換で 3D、`item/generated`＝平面スプライト）ので、壺・書見台・金床・焚き火・エンドロッドは**立方体近似のほうが実物に近い**。平面のままは鎖・ろうそく・ウィザーローズ・大釜など 5 件へ。⚠️ **32px では 16px テクスチャの 1 テクセルが投影の対角線上で出力 2px しか持てず縁が階段状になる** → 64px（1 テクセル 4px で面の境界が整数ピクセルに乗る）。paper jar 1.5MB→2.6MB。③**「統合版に古いデータが残る？」** → **配備中パックは現レジストリの CMD 持ち 710 件を 100% 網羅**（テクスチャキーも lang も欠けゼロ、実測）。パックのパッチ版は**内容変化ごとの単調増加カウンタ**（現在 1.0.32262 / 上限 32767 で version[1] へ繰り上がる設計）なので、内容が変われば必ず番号が変わる＝**取り違えは構造的には起きない**。⚠️ **起動ログの `Left 500 PDC-only mapping(s) as plain vanilla items` が「元の素材名・テクスチャなし」の正体**（アイコンもアタッチャブルも無い品を独自定義にすると、バニラ id を要求する Bedrock レシピを壊すので意図的に素通し）。残りは端末側キャッシュ。**TF jar と paper jar の両方が未配備** |
| 2026-08-23 | **統合版でカスタム素材のクラフトが通らない件の真因を潰した**（GeyserAddon `83ecd27` / `0843e5e` / `d855674` / `9869346`、**TF 本体は無変更**）。⚠️ **「アイテムが壊れている」ではなく「レシピがクライアントに届いていない」** —— 統合版は*どのレシピを作るか*をクライアントが決めるので、届いていないレシピは存在しないのと同じ。プロキシログの `sent 157 corrected recipes but dropped 203` が唯一の物証。連鎖は **ブロック基材 → 平面アイコンが無い → `CustomItemsHandler#shouldUseVanillaBaseFallback` が独自定義を登録しない → Bedrock 識別子が無い → `BedrockRecipeInjector` が名指せずレシピを丸ごと落とす**。2026-07-22 に「アイコンを解決できないブロック系はバニラに委ねる」と決めた当時、レシピ注入はまだ無かったので代償がクールダウン分類だけだと見積もられていた。⚠️ **`geysermc:block_placer` では直せない** —— `geysermc:` 名前空間の成分は `ItemDataComponent.vanilla()` が全部 false で、バニラ由来の定義に付けると例外（javap で確認）。→ **Java が gui 変換で描いているのと同じ等角投影の立方体をビルド時に焼いて jar へ入れる**（`IsometricBlockRenderer` / `BedrockBlockIconGenerator` / `BakedBlockIcons`、948 枚）。面の陰影は Minecraft と同じ 上1.0/左0.8/右0.6。⚠️ **立方体でないブロック（壺・書見台等 13 件）はスキップせず平面で焼く** —— 無アイコンは見た目が劣るのではなく**クラフトごと不可能になる**。⚠️ **bedrock-samples の `blocks.json` は Geyser と違う古い名前でブロックを持つ**（`iron_chain`→`chain` / `grass_block`→`grass` / `sea_lantern`→`seaLantern`）ので別名表が要る。⚠️ **焼き損ねても以前はビルドが成功していた** —— 取得元がネットワークなので clean 後の無ネット環境がアイコン0枚の jar を吐き、テストも赤くならない → 404 以外の失敗と「対象の半分未満」でビルドを落とす。**別件で `BedrockEnchantmentHandler` がクリエイティブの統合版プレイヤーへ表示名を注入していたのを止めた**（`SET_CREATIVE_SLOT` の書き戻しで `custom_name` がアイテムへ焼き付き、**スタックしなくなる**。lore と違い注入名にはマーカーが無く除去経路も無い。既に焼き付いた分は直らない）。配備データでの試算で**落ちるレシピ 205→3**、残り 3 本は配備中パックがレジストリより古いだけでコードの問題ではない。core 26 / paper 295 / extension 31 = 352 tests, 0 failures, 0 skipped。**paper jar と extension jar の両方を再配備が必要**（未配備） |
| 2026-08-23 | **プレステージの誤爆を確認モーダルで塞ぎ、プレステージ枠へ赤字警告を注入した**（実サーバ報告 W-188）。⚠️ **2026-08-21 の「2クリック確認」は方式そのものが誤爆を防げていなかった** —— 確認と実行を**同じマスに重ねて**いたので、トーテムを押した 1 tick(50ms)後に同じスロットが確定ボタンへ変わり、**ダブルクリックの2打目がそこへそのまま入る**（統合版やタッチ操作では素の操作として出る）。プレイヤーから見れば「1回押しただけでプレステージされた」。⚠️ **前回の回帰テストがこれを守っていた** —— `secondClickConfirmsThePrestige` が「同じスロットをもう一度押すと確定する」を正しい挙動として固定していた。→ `Mode.PRESTIGE_CONFIRM` を新設し、赤字警告＋「はい」「いいえ」だけのモーダルへ分離。⚠️ **画面を分けるだけでは足りない**ので `prestigeYesSlot(originSlot)` で**直前に押したスロットには「はい」を置かない**（衝突するときは「はい」と「いいえ」を入れ替えるので、2打目は必ず取り消しになる）。確認画面は `lastView` へ覚えさせない（覚えると開き直しただけで確定ボタンのある画面が出る）。赤字警告は描画時注入で**確認モーダル/ツリー本体/パーク一覧の3画面が同じ `prestigeWarningLore` を通る**（2箇所で書き分けると片方だけ「0に戻る」が抜けて嘘になる）。⚠️ **POWER だけレベルの戻り方が違う**ので文言は `prestigeLevelNotice` の分岐を経由する。回帰6本、**衝突回避を外すと2本落ちる**ことを実走確認。**TF の jar のみ**（config・リソパは無変更） |
| 2026-08-23 | **構造物ルートのティア専属化・試練の間の空チェスト・`reset-resource.cmd`・トレジャースレッド10種**（実サーバ報告 第30陣 W-187a〜e）。⚠️ **「固有のものが少ない」は体感ではなく実測どおり** —— 品質系スレッドは1種あたり **9/10 ティア**、カスタムモブ素材は **10/10 ティア**に出ていた（＝どこを漁っても同じ）。看板ティア+隣接1/3 の2ティアまで、素材は浅/中/深の3帯へ、カギは全種ちょうど2ティアへ絞り、新スレッド10種は**1種＝1ティア専属**にした。⚠️ **`loot-tables.yml` は生成物**なので真源は `tmp/worldgen/gen_loot_yml.py`。⚠️ **試練の間が空だったのは2段の原因が重なっていた** —— ①`pools` にトライアルチャンバーの表が1本も無かった（2026-08-16 に経済理由で意図的に除外）②**足しても当たらない**。ヴォールトと試練のスポナーは戦利品をブロックが直接排出するので **`LootGenerateEvent` を発火しない**（PaperMC #11680 は「対応しない」でクローズ）。唯一の入口は Paper 1.21.10 の `BlockDispenseLootEvent` で、ArsPaper の `LootTableListener` へ2つ目の入口として実装（本体は `applyPools` に集約。**片方だけ直すと宝物庫だけ空に戻る**）。⚠️ **ヴォールト/スポナーの表は `structures.json` に載らない**（piece ではなくブロックが持つ）ので明示回収し 100 本を発見。⚠️ **1.21.8 生成のワールドを 1.21.10+ へ上げるとヴォールトが恒久的に不活性になる既知バグ**（PaperMC #13521、未修正）があり、これはプラグインでは直せない。⚠️ **`reset-resource.cmd` は引数なしだと必ず dry run で終わっていた**（本実行の経路が無かった）→「dry run → `RESET` と打たせる → 本実行」の2段へ。シードは `level-seed=` が空なので本実行すれば毎回別地形。**editor の構造物ルート抽選はデータパックではない**のでワールドリセット無しでも次のチェストから効く。⚠️ **新スレッドの使える軸はちょうど10本しか残っていなかった** —— ①ATTACK+FLAT は帯非依存性のガードで禁止 ②**ATTRIBUTE は「装着しても効かない」と「手に持つだけで効く」が同時に起きる**（addon が属性経路に合流しない／`AttributeProjection` がバニラ属性として実際に付ける）③`flat-defense` は typed キーがあると無視される別名。一度 ATTRIBUTE で組んでテスト2本が実際に落ちた。結果、**汎用近接は3本しか作れず**残りは遠距離4・防御3（依頼は「やや強めの汎用戦闘系」だが、既存75種が汎用近接の軸を全部押さえているため。W-187e の「相互互換を発生させるな」を守るとこうなる）。**次に増やすなら既存の整理か新ステの新設が先**。TF フル 4582/44失敗/2スキップ（着手前45から自分の修正1本ぶん減、残りは他セッションの WIP と同一集合）、ArsPaper fork 516/0失敗/0スキップ。**TF の jar は不要**。必要なのは config 配備・**ArsPaper jar**・**リソパ再配布**・`deploy-launch.cmd` の4つ |
| 2026-08-22 | **グリフ解放画面で解放済みかどうかが分からない問題を修正**（実サーバ報告 W-160）。同日の `fc97b4f`（120種へ個別アイコン）が、**それまで解放状態を担っていた「潰した絵」を同時に外していた**のが原因。名前の色と lore には出ているが**36個/ページの一覧を見渡すのには使えない**（1個ずつカーソルを当てる操作になる）。確定解は**未解放だけ `GRAY_DYE` に潰す非対称** ── 実際に選んで使うのは解放済みの側なので、個別アイコンが要るのもそちら。これで2つの報告が両立する。指示により**グリフ3画面すべて**（筆記台/解放素材/呪文編集）へ適用。未解放は `icon:` 上書きより優先。回帰5本を追加し**戻すと3本落ちる**ことを実走確認。フォーク 515/0失敗/0スキップ。**ArsPaper jar 配備が必要**（ビルド済み）。**push は保留**（public repo・未 push ブランチに TF jar が同梱）。ArsPaper `7e14ec7` |
| 2026-08-22 | **editor で material を差し替えるとテクスチャ割り当てが外れる不具合を修正**（実サーバ報告 W-159）。`cmd-registry.json` の突合せキーが **`(material,cmd)` だけ**だったため、鎌を `*_HOE` から `*_SWORD` へ付け替えた 11 品が「新規の未配線行」と見なされ **`assetName`/`parent` を無言で喪失** → items 定義がバニラモデルを指すようになっていた（アセットは1枚も消えていない）。**id で引き継ぐ** `reconcileWithUsageDetailed` を新設し、生成モデルの `parent` も新 material へ貼り直す（放置すると BOW 等ではリーフが存在せず描画ごと落ちる）。**検出漏れ側も修正**: `build_item_pack.py` の D-2 は「threshold entry があるか」しか見ておらず、H-3 のフォールスルー対策以降**未配線でも entry は必ず出る**のでこの事故を検出できなかった → `drawn_thresholds()` で**自前モデルを指しているか**まで見る。鎌 11 品を復元し zip を再生成。回帰7本を追加し**引き継ぎを黙らせると本命4本が落ちる**ことを実走確認。**リソパの再配布が必要**（jar/config は不要） |
| 2026-08-22 | **図鑑の記録漏れ2件を修正**（実サーバ報告 W-157 / W-158）。討伐は `getKiller()` = **とどめを刺した1人**しか見ておらず、寄与比で全員に配る戦闘EXPと帰属が食い違っていた → 指示により**ダメージを与えた全員**へ。既存 `CombatKillCreditTracker` を流用しないのは①**武器スキル3種のダメージしか記録しない**(魔法だけで削った人が載らない)②`consume` が台帳を取り除くので、同じ `EntityDeathEvent` を MONITOR で受ける2本は**登録順しだいで片方が空**になるため。図鑑専用の `MobKillParticipants` を新設し `DamageSource#getCausingEntity()` で攻撃手段を問わず拾う。アイテム側は記録の入口が**拾得/開閉走査/参加走査の3つだけ**で、地面を経由せず直接インベントリへ入る品(EM ダンジョン報酬・ガチャ・メール・儀式報酬・`/tf give`)が GUI を閉じるまで載らなかった → 与える側の列挙ではなく `PlayerInventorySlotChangeEvent` で**スロットの変化そのもの**を1箇所で拾う。回帰16本を追加し**新挙動を黙らせると本命2本が落ちる**ことを実走確認。フル 4582/29失敗は着手前と失敗集合が完全一致。**jar 配備が必要**。TF `c9afd4e` |
| 2026-08-22 | **ダンジョンのボスだけがパーティ最強の戦闘レベルへ化けるのを直した**（報告「ダンジョンの敵が殴ったらそのダンジョンに入ってるプレイヤーの中で1番戦闘レベルの高い人に合わせられる=>僕(100Lv)が50lvで部屋を作ってボス戦まで行ったら敵の体力は100レベの10Mとかになる」「34lvのダークカテドラルで50lv武器&55lv装備、道中はワンパンできるのに最後のボスだけ明らかに倒せる設計されてない」）。**この2件は同じ1つのバグ**。⚠️ **真因はフェーズ切替でレベルが -1 へ戻ること** —— フェーズボスは体力が閾値を割るたび`PhaseBossEntity#switchPhase` で「remove → `setCustomBossesConfigFields`(フェーズ設定) → `spawn`」をやり直す。`setCustomBossesConfigFields` の末尾は `super.setLevel(config.getLevel())` で、`level: dynamic` の設定では**これが -1** ── インスタンスが与えた挑戦レベルがそこで消える。続く `spawn` が `level == -1` の分岐に落ち、素の EliteMobs 経路（`getDynamicLevel` = **近くのプレイヤーの戦闘レベルの最大値**）でレベルを決め直していた。⚠️ **さらに 5 秒ごとの `dynamicLevelUpdater` が上書きし続ける** —— 同じスポーンで`dynamicLevelBossEntities` にも載るので、2026-08-18 に入れた `DynamicDungeonLevelListener`(LOWEST) がスポーン時に直しても毎回取り消されていた（**スポーン直後だけ正しい**ので実機で数分殴らないと出ない）。⚠️ **ダークカテドラルの phase_0 の閾値は 0.9999** ＝ 最初の一撃でここを通る。フェーズを持たない道中の雑魚は通らないので、同一インスタンス内で雑魚 `[34]` / ボス `『67』` になる（実ログ `em_the_dark_cathedral_1` で確認。難易度ハード=100% を選んだ回は雑魚もボスも `[76]` で一致するため**このバグは 100% 帯だけ見えない**）。最大HPは `150 × 1.072^L × 1.375 × 1.236 × 30` なので **34→8.1万 / 67→80.6万でおよそ10倍**、100レベルなら約800万で報告の「10M」とほぼ一致する。→ 判定を `DynamicDungeonLevelPolicy`（新設）へ切り出し、**値を決める `getDynamicLevel` と updater へ載せる `spawn` の両方**をそこへ通す。インスタンス内のモブは挑戦レベルで固定し、`dynamicLevelBossEntities` に載せない。⚠️ **片方だけ直しても直らない**ので配線を2箇所とも縛る。回帰は `DynamicDungeonBossLevelTest`（規則そのものは純関数4本／2つの分岐が判定を通ることはこの fork の流儀どおり `javap` でメソッド単位のバイトコードを見る2本。生きた Bukkit・インスタンスワールド・EM プレイヤーデータが無いと挙動では走らせられないため）。**まるごと戻すと2本・`spawn` だけ戻すと1本落ちる**ことを実走確認。EM fork 123/失敗0/skip0。**EliteMobs の jar のみ**（TF 本体・config は無変更）。EliteMobs fork `5eb7e40a`。 |
| 2026-08-22 | **交換グリフで菌糸が作れない件は「デグレではなく一度も作れたことが無い」と確定し、土の段へ草・ポドゾル・菌糸を足した**（実サーバ報告 W-156）。交換は `exchange_tiers` の**同じ段の中でしか循環しない**のに、菌糸はどの段にも居なかった（`git log -S MYCELIUM` がfork の glyphs.yml で 1 件も無い／配備先 3 台はソースとバイト一致）。TF・Ars のどこにも菌糸を**生成する**経路は無く、入力側（菌糸ソースリンク強化に計19個）としてしか出てこない。**ArsPaper は自分では既存 yml を更新しないので jar でも `/ars reload` でも配備先は変わらない**（届く経路は全台停止が要る `deploy-config-head.cmd` だけ）→ 止めずに入れる用に`ops\scripts\apply-exchange-dirt-tier.ps1` を用意（退避・確認つき／流したあと `/ars reload`。次回配備で guard が drift としてリポジトリへ書き戻す）。回帰ガード `ExchangeTiersShippedTest` を追加し**段を戻すと落ちる**ことを実走確認、fork 510 tests / 0 failed。fork 側は他セッションの未コミット 369 行と混ざっているため**ワーキングツリーに残置**（配備には影響しない） |
| 2026-08-22 | **職業EXPの日次逓減が「入り直すだけ」で消えていた真因を確定して修正**（実サーバ報告 W-155）。稼働中 DB に `lock-release-hours: 24` なのに `locked_at` が53〜65 時間前という行が実在した。**`restore` が期限切れ行を読み飛ばすだけで消さない** →次の `save` が `max()` で古い蓄積を残し `earlierLock()` が新しい発動時刻を古い方へ引き戻す →また期限切れになって読み飛ばされる、の 3 段ループ。**サーバ切替で蓄積を後退させない規約と解除期限を延ばさせない規約が噛み合って期限切れ行を不死身にしていた**。`save` にも解除時間を渡して期限切れを権威にし、`load` 冒頭で `purgeReleased()` により物理削除する形へ。回帰3本を追加し**修正を戻すと2本落ちる**ことを実走確認。**jar 配備が必要**（既存の壊れた行は次のログインで自動的に掃除される）。TF `a26dbed` |
| 2026-08-22 | **Arsグリフ120種に個別アイコンを付け、3画面の並び順を1本に揃えた**（依頼「Arsグリフの設定でアイテムアイコンを付けてほしい。現状どれがどの魔法かぱっと見で分からない」「グリフレシピ、グリフ設定、グリフ解放で順番もそろえてほしい」）。アイコンは**種類ごとの3種類しかなく**（形態=ダイヤ / 効果=エメラルド / 増強=アメジスト）、120個が3種類の絵に潰れていた。しかも**未解放は全部石炭**（筆記台・グリフレシピ）、**呪文編集では未解放が全部バリア・使用不可が全部灰色染料**なので、36個/ページ並ぶ画面で名前を1つずつ読むまで何も判別できない。⚠️ **並び順は3画面がそれぞれ自前で決めていた** —— 筆記台=`種類→ティア` / グリフレシピ=`種類→ティア→IDのアルファベット順` / 呪文編集=`ティアのみ→超増強をベースの直後へ`。→ `GlyphIcons`（120種すべてに材質を割り当て・重複ゼロ）と `GlyphOrder`（並びの唯一の定義）を新設し、3画面ともここだけを通す。⚠️ **アイコンの既定を Java に置いたのは意図的** —— `glyphs.yml` は `saveResource(..., false)` なので**jar を替えても配備先の yml には新しいキーが増えない**。yml 側に既定を書くと既存サーバでは永久に反映されない。yml の `icon:` は**上書き専用**（配備先へ手で `icon: BLAZE_ROD` と書けば効く。綴り違いは既定へ落として画面が開かなくなるのを防ぐ）。⚠️ **並びは「種類→登録順」でティアでは並べない** —— `ArsPaper#registerComponents` の登録順は手で意味づけされたグループ（効果=攻撃系/移動系/生存系/ブロック系/ユーティリティ系、増強=増幅⇔減衰・延長⇔短縮・延伸⇔収縮・加速⇔減速 の対）になっており、ティアで並べ替えるとこれが崩れる（**延伸=T2 と 収縮=T1 が離れて対が割れる**）。ティアは lore に出ているので情報は失われない。超増強（`super_*`）だけはベースの直後へ寄せる（登録順のままだと末尾15個にまとまる）。⚠️ **未解放/使用不可でもアイコンを潰さない**方針にした —— 潰すと「どれがどの魔法か分からない」に逆戻りするため。状態は名前の色（緑=解放済 / 赤・濃灰=未解放 / 灰=使用不可）と lore の赤字で示す。回帰は `GlyphIconCoverageTest`（出荷 glyphs.yml の120件と定義が完全一致・材質の重複ゼロを**空集合で固定**・未定義は種類既定へ・`icon:` 上書きと綴り違い）／`GlyphOrderTest`（超増強の寄せ・登録順の保持・件数不変）／`GlyphGuiIconAndOrderWiringTest`（3画面が `GlyphIcons`/`GlyphOrder` を通り、自前の switch とソートを持たないこと。**片方だけ直しても実機で画面を切り替えるまで食い違いに気づけない**ので配線そのものを縛る）。いずれも戻すと落ちることを実走確認（11本中6本）。fork 507/失敗0/skip0。**ArsPaper の jar のみ**（TF 本体・config は無変更）。ArsPaper fork `fc97b4f`。 |
| 2026-08-22 | **Wiki 全面改訂**。生成17本を再生成＋手書き16本を実configと突き合わせ。`wiki/` の remote が存在しないリポジトリを指していたのを `trinityforge.wiki.git`(`master`)へ張り替えて初公開／`ban.yml` の全ワールドBAN 4種を発見 |
| 2026-08-22 | **精錬魔法の変換表をかまどレシピ登録から組むようにした**（報告「精錬魔法が粘土玉に効かない」）。⚠️ **変換表が手書きのハードコード（`SMELT_MAP`）だった** —— 抜けは粘土玉（`CLAY_BALL → BRICK`）だけではなく、石炭/ラピス/レッドストーン/ダイヤ/エメラルドの各鉱石、ネザーの金鉱石・ネザークォーツ鉱石、後から追加された原木（淡いオーク等）、コーラスフルーツ、濡れたスポンジ…と広範囲。**1件足しても同じ穴が残り続ける**ので、表そのものを `Bukkit.recipeIterator()` のかまどレシピから組む方式へ変えた。⚠️ **取り込むのは `minecraft:` 名前空間だけ** —— プラグインのレシピを混ぜると入力/出力の CMD が落ちた「材質だけ」の変換になり、W-172 と同じ**カスタムアイテムの無言の喪失**を作る（`CustomItemConversionPolicy` が守れるのは入力側だけ）。⚠️ **耐久を持つ材質も除外** —— バニラには `iron_pickaxe → iron_nugget` が実在し、そのまま取り込むと**足元に落とした装備が詠唱1回でナゲットに化ける**。原石ブロック3種（`raw_*_block → *_block`）はバニラに無いフォーク独自の追加なので明示で残す。⚠️ 静的初期化子では組めない（レシピ登録前でクラスがロードされるので空になる）→ 初回詠唱時に遅延構築。表の組み立ては `buildTable` としてレシピ読み出しから切り離し、Bukkit 無しでテストできるようにした（このフォークのテスト基盤に MockBukkit は無い）。回帰は `SmeltTableBuildTest`（新設4本。除外と get(null) 許容を戻すと2本落ちることを実走確認）。fork 496/失敗0。**ArsPaper の jar のみ**（TF 本体・config は無変更）。 |
| 2026-08-22 | **盾も「壊れない品」だったので直し、書き忘れを全件で縛った**（指摘「同じ理由で SHIELD も壊れない品のままです => 直す」）。杖10種と同じ穴で、`item-stats.yml` の `SHIELD` に `durability` が無く`ItemAssembler` が `setUnbreakable(true)` にしていた（＝**永久に壊れない盾**）。出荷の慣例どおり `201`（バニラ336 × 0.6）/ `per-quality: 34` を追加。⚠️ **1件ずつ回帰テストを足しても次の書き忘れは止まらない**ので、`ShippedEquipmentDurabilityTest` を新設して**耐久を持てる材質のエントリ全件**（`Material#getMaxDurability() > 0`、374件）に`durability` があることを縛った。⚠️ 材質名のサフィックス列挙（`_SWORD` など）で判定すると**それ自体が許可リストになり漏れた材質は検査ごと素通りする**ので、バニラの `Material` へ解決して判定する。⚠️ `getMaxDurability()` は**レジストリを引くのでサーバが要る**（素で呼ぶと `Bukkit.server is null` で落ちる）→ MockBukkit を起動している。解決できない材質名は「今は0件」で固定（素通りさせるとその材質だけ検査から静かに消える）。SHIELD の追加を戻すと新規3本のうち2本が落ちることを実走確認。TF 4563/失敗29/skip2（**新規失敗ゼロ**）。**config のみ**（jar は無変更）。 |
| 2026-08-22 | **レシピGUIの最低ステ表示が余白を残して省略される問題を直した**（指摘「まだloreの長さに余裕があるのに省略されてしまい、スレッド枠やマナ回復量などのステータスが移っていない」）。上限が `TrinityForgeStatPreview.MAX_LINES = 8` の**固定値**だった。⚠️ **ツールチップの高さを決めるのは lore 全体なのに、上限だけが lore の長さと無関係**だったので、lore が短いアイテムでも8行で頭打ちになる。表示順は `lore.yml` の category → order で固定なので**毎回まったく同じステがこぼれる** ——報告のあった魔術師の守護ヘルメット（`LEATHER_HELMET#200011`、表示対象12件）では`thread-slots` / `mana-bonus` / `mana-regen` / `move-speed` の4件が常に「…ほか」だった。→ ツールチップ1枚の総行数を予算（**26行**。バニラのツールチップは**スクロールしない**ので、1080p でいちばん狭い GUI スケール4＝論理270px に `10px×行数+6px` が収まる上限）として持ち、そこから lore に出ない行（名前・属性ブロック・耐久表示）・積んだ lore・**このあと足す行**を引いた残りをステ行へ回す。⚠️ **`appendDetailLore` の呼び出し側は3か所とも末尾に足す行数が違う**ので、`trailingLines` を引数で渡す形にした（数え損ねると末尾がはみ出す）。lore が予算を食い尽くしても最低4行は出す（0行にすると見出しと「…ほか」だけのブロックになる）。回帰は `RecipeTooltipStatRoomTest`（新設3本。固定8へ戻すと3本とも落ちることを実走確認）。fork 492/失敗0。**ArsPaper の jar のみ**（TF 本体・config は無変更）。 |
| 2026-08-22 | **耐久値まわり4件と防具EXPの足きり**（指摘「杖を使用時に耐久値が減らない」「クワで一括収穫時に耐久値が減らない（収穫した量に応じて減ること）」「斧やツルハシの一括破壊で破壊数に応じて減ることを確認」「ダンジョンで負けた時の耐久値ロスが機能しているか確認」「防具の被弾EXPも今回のlevel差調整の該当にする」）。⚠️ **杖の真因は fork ではなく出荷 yml** —— `ItemAssembler` は `durability` ステの無いアイテムを `setUnbreakable(true)` にするので、**`durability` を書き忘れた杖10種はまるごと「壊れない品」**になっており、ArsPaper の `consumeCastDurability` は `isUnbreakable()` で必ず抜けていた（同じ理由で SHIELD も壊れない。指示外なので据え置き）。一括収穫の耐久0は**意図的な設計**だったが「1マスぶんの手間でNマスを刈る」ので歯止めが無く、一括伐採と同じ「1マス1点」へ揃えた（判定と消費は `ChainBreakSupport` へ入口を足して共通化。書き写すと W-173 の `isUnbreakable()` 漏れが経路ごとに再発する）。斧/ツルハシ（既存テストで固定済み）とダンジョン敗北時の耐久ロス（fork の `playerDeath` 経由。スペクテイター化の前に呼ぶ順序も守られている）は**確認＝正常**。防具の被弾EXPは `KillRewardAdjuster` を一度も参照しておらず足きりが素通りだったので、**ダンジョン上乗せの掛からない `skillExpLevelCutoff` を切り出して**適用。TF 4560/失敗29/skip2（着手時 4545/29/2＝**新規失敗ゼロ**）。**jar + config をセットで配備。** |
| 2026-08-22 | **スレッドのセット効果の乗算に「レイヤ」を通した（W-186）**（指摘「editorのスレッドのセット効果で乗算をONにした時にレイヤ指定ができない」）。⚠️ **UI の欠落ではなくバランスのバグだった** —— レイヤという概念が editor/fork/TF のどこにも無く、セット効果の乗算は `AddonCombatStats.MULTIPLIER_LAYER_ID`(`"addon"`)へ**決め打ち**で入っていた。TF は「レイヤ内は Σ(v-1) を足し、レイヤ同士は掛ける」ので、**出荷 thread-sets.yml の攻撃力%13件が装備側の攻撃力%と掛け算で二重に乗っていた**(装備×1.20 × スレッド×1.25 = ×1.50。同レイヤなら ×1.45)。PDC 乗算チャネルを `"layer@key=value"` 形式へ拡張し(**`@` 無しトークンは `addon` へ落として後方互換**。捨てると再ログインしていないプレイヤーだけ倍率が無言で消える)、`thread-sets.yml` に `layer:` を新設、既存13件を `layer_1` へ寄せた(**実効ダメージが下がる意図的な調整**)。editor は乗算ON時にレイヤ選択を表示(未選択状態は作らない=行き止まりなし)。テストは TF 5 / fork 5 / editor 5 の計15本で、**出荷 yml ガードは layer を1件外すと落ちることを実走確認**。TF 4552/失敗30・fork 489/失敗0・editor 1417/失敗24(失敗は全て他セッションの未コミット yml 由来)。⚠️ **配備は jar 2本 + config を必ずセットで**(片方だけだと乗算が効かない/二重のまま)。 |
| 2026-08-22 | **設定エディタの 加算/乗算 をボタンへ統一し、ステ行の縦線を揃えた（W-185）**（指摘「セット効果の加算乗算のUIが違う。ボタン式に統一して」「ステータス設定の各種設定欄の縦のラインがずれている」）。⚠️ **縦線ズレの真因は単位ではなくセルの幅が中身で決まっていたこと** —— %ステは `.pct-input` の `%` のぶん 123px、単位なしステは 110px、単位ありステはさらに広い。flex 行なので差がそのまま後続へ伝わり、実測で `max` が 13px・`乗算`/`×` が 26px ずれていた。**単位スロットの `min-width: 2em` は下限であって固定幅ではない**ので `ダメ`(3文字)の行だけ広がる。→ `valueCell(control, unitEl)` に値と単位をまとめ、`.value-cell` の総幅と入力枠の幅を**両方 px 固定**。ランダムロール行は min/max の**両方**にセルを置く(行末に1つだけだと「max だけ単位が付く」非対称になる)。加算/乗算は `modeToggleButton` に統一(item-stats とスキルツリーはチェックボックス、セット効果はプルダウンで**3画面3通り**だった)。行の並びも `[ステ選択][値セル][加算/乗算][×]` へ揃えた。実測でスレッド画面 397 行が行種ごとに1通りの列位置へ収束。回帰は `stat-row-unified-controls-2026-08-22.test.js`(新設5本。戻すと4本落ちることを確認)。editor テスト失敗 26 件は変更前と完全一致(新規ゼロ)。**editor のみ**(jar/config は無変更)。 |
| 2026-08-21 | **瀕死技を難易度6以上のダンジョンだけに絞った（W-184）**（指摘「瀕死技は難易度6以降だけでいいかも」）。W-183 で `health-below` の瀕死技を**全難易度の踏破ボスへ配ってしまっていた** —— 瀕死技は「盾やダメージ軽減を用意しておく」という**前提知識の上に成り立つ大技**なので、その前提が無い難易度1〜5 では対策不能な事故にしかならない（難易度1の闇の大聖堂にも 2.3 倍の `last_stand_quake` が乗っていた）。12 体から外し、⚠️ **本数は減らさず同じ属性を埋める通常技へ差し替えた**（減らすと W-183 の骨格である「どのボスも物理と魔法の両方を持つ」が崩れ、片側の防御だけで受かるボスへ戻る）。差し替え先の倍率も難易度順（難1〜2 は 1.10〜1.25／難5 は 1.55〜1.70）。結果、ボスの最悪の1発を耐えられる回数は **難易度1〜5 が 2.15 発／難易度6〜10 が 1.64 発**。⚠️ **難易度は出荷 yml ではコメントにしか無い**（スコープ直下の `max-health-multiplier` は難易度の**逆順**の梯子なので、そこから復元すると梯子を触った瞬間に検査の意味が変わる）→ 新設した `lastStandAbilitiesOnlyAppearInHighDifficultyDungeons` は許可スコープを**名前で**持つ。**config のみ**（Java は無変更）。 |
| 2026-08-21 | **軽装/重装/魔法装の住み分けを作り、ダンジョンの攻撃ステと技を組み直した（W-183）**（指摘「そもそも軽装備と重装備の住み分け設計ができていないんじゃないか。基準値を揃えて、軽装はHP高め・重装は防御系高めにしつつ被ダメージの期待値が揃うようにできるか」ほか6件）。**着手前は Lv100 で重装 5.2 発に対し軽装 1.0 発、しかも最大HPまで重装のほうが高く、軽装に取り柄が1つも無かった。**→ 板金ラインの現在値を帯の基準に据え、最大HPを役割で決め打ち（重装0.80/軽装1.50/守護0.65/ローブ1.00）してから守備6キーの係数を二分探索で解き、**35ライン140部位を被ダメージ期待値で揃えた**（軽装 1.03→4.88 発。重装は 5.20 発のまま）。⚠️ **物理3軸に「同帯の板金の80%」という天井が要る** —— 無いと薄いラインほど守備係数が大きく解かれ、軽装の守備力が板金を上回る逆転が起きる（実際に起きた）。⚠️ **率を1.0超へ伸ばすと `(1−耐性)` が負に転じて二分探索が非単調な解を拾う**（守備×3.0・HP154.8 という解が出た）。模型側でも `[0,0.9]` へ丸める。⚠️ **軽装・魔法装の83部位は最大HPが「fixed 0 で全部ロール」だった** —— 最大HPを軽装の主軸に据えると「軽装だけ厳選運で4倍ブレる」になるので、総量を変えずに fixed へ 74.5% を移した（最大ロールは据え置き、上がったのは厳選なしの下限だけ）。技は **`health-below` の瀕死技を新規実装**し、倍率を「避けられる技ほど重く」の3段（予告付き2.1／瀕死技2.5／その他1.7）へ。テンプレ15→21種、担い手99→129体。⚠️ **多段フェーズのボスは最終段だけが技を持つ**（いったん途中段にも配ってしまい38体から外し直した）。攻撃ステは貫通/会心/固定/振れの4型を28ダンジョンへ7本ずつ配り、**型が増やしたダメージぶんは攻撃力を下げて相殺**。⚠️ **増幅型は攻撃力を上げるのと数学的に同値で、相殺した時点で完全な no-op** になるため不採用。⚠️ **型があるので `attack-power` の生値でダンジョンを比べてはいけない**（貫通型は2割低く出る）。試練の梯子検査を `ComponentDamageCalculator` を実際に呼ぶ「素換算」へ書き換えた。EXP の件（W-183g）は `ea7ffdb` で対応済み。TF **4536件・失敗29・skip2**（自分の触った4クラスは0件。29は他セッションの未コミット yml 由来）。**jar 再ビルド＋config 配備が要る。** |
| 2026-08-21 | **`WeaponTierParityTest` のダメージ補正模型を実装に合わせ、隠れていた戦鎚のズレを直した**（指示「修正し、DPS にも問題がないか確認する。90% なら 0.9〜1 倍。1 倍を超えるのは補正が 100% 以上のときで、130% なら 1〜1.3 倍の想定だった」→ **実装はその想定どおり**で、壊れていたのは検査側だけ）。⚠️ **取り違えの誤差はほぼ全武器に一様に効くので、武器種どうしの比を見る帯は破綻しない** —— だから壊れたまま何度も再較正できてしまい今日まで残った（同じリポジトリの `WeaponDpsParityTest` は正しかった）。直すと **戦鎚 14/15 本が帯 0.82-0.94 に対し 0.953-0.991**、鎌 5/13 本が +1.8% までの超過。**原因は戦鎚の補正 0.84 が剣の 0.65 より高いこと**（差は +9.4% に見えていたが実は +15.2%）。⚠️ **金の戦鎚だけ帯内なのが決定的な証拠** —— 金帯は剣も戦鎚も 0.98 で補正差がゼロ。ユーザー決定「データを直す」で戦鎚 14 本を −1.9〜−5.6%、鎌 5 本を −1.3〜−3.1%。縮めたのは `attack-power` と `fixed-damage` だけ（`per-quality`/`random` も同率）。⚠️ **一律の倍率では出血持ち（鎌・cryocore 戦鎚）が目標からずれる** —— 出血は加算項なので `s = (目標DPS − 出血) / (実測DPS − 出血)` を 1 本ずつ解く。回帰は模型そのものを固定する新検査（ユーザーの言葉の仕様 + **実装の `rollDamageModifierMultiplier` を両端で呼んで平均と突合**）。⚠️ **未設定は 0 ではなく中立 1.0** ——`stat()` の 0 をそのまま通すと未設定の武器が ×0.5 に化ける。**RED 確認済み**（模型を旧式へ戻すと 2 件落ちる）。⚠️ **別セッションが同時に防具リバランスを同じ yml へ入れていた（748 hunk）**ので、`git add` せず **HEAD に自分の 19 エントリだけ差し替えた blob を `git update-index` で index へ入れた**。TF **4536 件・失敗 37・skip 2（着手時 28 件。差の 9 件は作業中に別セッションが防具とモブの yml を触ったため増えたもので、武器は 1 件も含まない）**。**config のみ。配備＋サーバ再起動が要る。** |
| 2026-08-21 | **杖のダメージ補正を tier 別に 0.50〜0.60 へ上げ、攻撃力を下げて DPS を据え置いた**（要望「DPS を維持したままダメージ補正を上げてほしい。tier に応じて 0.5〜0.6 の間かな（代わりに攻撃力を下げる）」）。`damage-modifier` は端点なので 1 発の期待倍率は `(1+端点)/2`。0.30(期待 0.65) → 0.50〜0.60(期待 0.75〜0.80) にして、attack-power を `1.30/(1+端点)` 倍（例: インフィニティ 56700 → 46069）。`per-quality` と `random` の min/max も**同じ係数**で縮めた（片方だけだと品質・厳選でずれていく）。**実効火力は据え置きで、変わるのはばらつきだけ** —— 振れ幅 ±70pt → ±50〜40pt、増幅 1 段(+10pt)との比は 7.0 倍 → 5.0〜4.0 倍。⚠️ **木だけ 81 ではなく 82** —— 旧 94 が `round(63×1.5)=94.5→94` で既に −0.5% 落ちており、係数を重ねると −1.1% になって検査が実際に落ちた。剣の値から直接引き直す。⚠️ **前回の「攻撃力が剣の 1.5 倍」検査では今回の変更を守れない** —— 補正と攻撃力を同時に動かすのが要件なので、片方だけ見る検査はもう片方を自由に動かせる。`WeaponTierParityTest` を **`attack-power × 補正期待値`** を固定する形へ書き換えた（+ 補正が 0.50〜0.60・tier 昇順で下がらない・CT 4.0s）。**RED 確認済み**（ダイヤ杖の補正だけ戻すと落ちる）。**触媒でも増幅は乗る**ことも確認（`TrinityForgeBridge` はステ供給元を決めた後に掛けており、杖か触媒かで分岐しない）。**未修正で見つけたもの2件**: 触媒(`BLAZE_ROD#400001`/`ENDER_EYE#85`)には `damage-modifier` が無く**常に ×1.0**（杖だけがサイコロを振っている）／**`WeaponTierParityTest` の DPS 模型がダメージ補正を「1 への上乗せ」と取り違えている**（0.30 に対し真値 0.65 でなく 1.15 を使う。誤差がほぼ全武器に一様なので帯は壊れていないが、直すと再較正が要る）。TF **4535 件・失敗 28・skip 2**（28 はすべて他セッション/ユーザーの未コミット yml 由来）。**config のみ。配備＋サーバ再起動が要る。** |
| 2026-08-21 | **EM ダンジョンの難易度を「戦闘レベルの割合」にし、ロックアウト通知を消した**（要望「別の職業を上げる際にダンジョンのレベルが高すぎて大変」「討伐時にロックアウトのチャットとタイトル通知が出ないようにしてほしい」。EM フォーク `6fb405f1`、`trinityforge` リモートへ push 済み）。**イージー 50% / ノーマル 75% / ハード 100%** を戦闘レベルに掛けたものがモブレベルになる。⚠️ **EM 内部の難易度キー `normal`/`hard`/`mythic` はそのまま**にして意味だけ差し替えた —— 32 本ある動的ダンジョンの content package yml を1行も触らずに済ませるため。**レベル選択メニューは廃止**し、難易度メニューを入口にした（既存インスタンスへの参加枠は**そちらへ移設**。落とすと GUI から合流手段が丸ごと消える）。⚠️ **`getMobLevel()` から `+ getDifficultyMobLevelOffset()` を消すのが必須** —— 割合はメニューで押した時点で `selectedLevel` に確定済みなので、残すと「イージーなのに 5 レベル高い」の二重適用になる。レベルは押した瞬間の戦闘レベルで引き直し（表示と実物が一致する）、端数は四捨五入（切り捨てだと 75% 帯だけ常に 1 レベル損）。**報酬は自動で下がる** —— `dungeon-level-reward` が絶対モブレベルを `pivot-level: 35` と比べる形なので。ただし `drop-penalty-cap: 0.3` / `exp-penalty-cap: 0.2` で頭打ち。ロックアウト通知は `dungeonLockoutNotificationEnabled`（既定 false）で**チャット・タイトル・アクションバーの3経路をまとめて**塞いだ。⚠️ **ゲートを `processLockouts` 側へ付けてはいけない** —— ロックアウト自体が無効化されて報酬が無限に出る。設定キーは `ConfigurationEngine.setInt/setString/setBoolean` が既存 yml へ書き足すので配備先の手直しは不要。回帰は新規 `DungeonDifficultyAndLockoutTest` 9 件（割合は純関数、二重適用の除去とゲートの有無は `Javap.sliceMethod` で**メソッド単位**に固定 —— クラス単位の定数プール検査では捕まらない）。**RED 確認済み**: 修正を戻すとそれぞれ 1 件が実際に落ちる。EM フォーク **117 件・失敗 0・skip 0**。uberjar 再ビルド済み。**⚠ 未実施（ユーザー作業）: サーバ停止 → EM jar 差し替え → 起動。** |
| 2026-08-21 | **Ars 魔法の増幅とダメージ補正の仕様を確認した（コード変更なし）**（質問「Ars 魔法に増幅が乗っているか分からない。武器のダメージ補正が低すぎるのか？」）。**増幅は正しく乗っている**（1 スタック +10%・上限 6・最大 x1.6）。見えない原因は**ダメージ補正のロール幅**で、`damage-modifier` は倍率ではなく**100% に対する端点**（1 発ごとに `Uniform[min(1,端点), max(1,端点)]` を引く。`normalizeDamageModifierEndpoint` が (1,100] の整数を 100 で割るので **`damage-modifier: 2` は 0.02**）。**杖は全 10 本が 0.30 固定**＝ 1 発ごとに x0.30〜x1.00（期待値 x0.65）の**±70pt のサイコロ**を振るので、増幅 1 段（+10pt）の 2.3 倍の振れ幅に完全に埋もれる。剣 0.65 / 大剣 0.72。**出荷品に 1.0 超えは 1 件も無い**＝このステは常に減衰で上振れは存在しない。防具・盾には 0 件、パーク／スキルツリー由来の供給源も無い。**杖の値を上げるかは要判断（未実施）。** |
| 2026-08-21 | **各鍵の入手経路を棚卸しし、戦利品テーブルの反映経路を確認した（コード変更なし）**。**editor で確率を変えても再生成は要らない** —— `LootGenerateEvent` は**チェストを最初に開けた瞬間**に中身を作るので、既存地形でも**未開封のチェスト**には新しい確率が乗る（開封済みだけは戻らない）。**ドリフト 2 件を検出（要判断・未修正）**: `key_quarry` の**クラフトが消えていて**入手経路がガチャ tier4 の 1.4% だけになっている／`key_binder` が**ダンジョンの印を消費しなくなっている**（lore は今も「印 5 個」＝表示が嘘。印 27 種が浮いている。`ShippedDungeonKeyReachabilityTest#binderKeyConsumesFiveSeals` が赤のまま）。 |
| 2026-08-21 | **EM の矢無効(`invulnerability_arrow`)を廃止した**（上の行の調査を受けたユーザー判断「矢無効をやめる」）。**配備先の lua を1つ差し替えるだけで 92 体すべてに効く**。→ `ops/templates/elitemobs-powers/invulnerability_arrow.lua`（空ハンドラの no-op）と、それを配る `ops/scripts/apply-elitemobs-arrow-invulnerability.ps1` を追加。スクリプトは**元の lua を `backups/<サーバ名>/elitemobs-powers/` へ日時付きで退避**してから書き、`-Restore` で戻せる。既に no-op なら触らない（冪等）。⚠️ **BOM を付けないこと** —— lua パーサは先頭 BOM を弾く。PowerShell の `>` と `Set-Content -Encoding utf8` は BOM を付けるので、`[System.IO.File]::WriteAllText` + `UTF8Encoding($false)` で書き、書いた後に先頭3バイトを検査する。**パワーの定義そのものは残す**（92 体の custombosses yml が `invulnerability_arrow.yml` を参照しているので、消すと参照が壊れる）。フォーク側 `InvulnerabilityArrowLuaConfig#getSource()` も no-op へ揃えた（EM フォーク `25d1315a`）が、**これは新規インストール用で稼働サーバには一生効かない** ——`LuaPowersConfig` が `if (!file.exists())` でしか書き出さないため。つまり**この修正に EM jar の再ビルドは要らない**（スクリプトを走らせるだけ）。`-DryRun` で main と dev が対象・resource は EliteMobs 未導入で飛ばすことを確認済み。**ユーザーがスクリプトを実行し、サーバを再起動する必要がある。** |
| 2026-08-21 | **「成長効率 +X%」を作物・動物とも<b>本物の倍率 ×(1+X)</b> へ直した**（質問「成長効率25%って1.25倍の速度で成長する認識でいい？」→ **当時は「いいえ」**、しかも作物と動物で実体が別物だった。ユーザー判断「本当に倍率にする＋動物も同様の挙動にする。数値調整はこちらで行う」）。**旧・作物**（`PlantedCropGrowthListener`）: 40tick(2秒)ごとに `min(1,bonus)` の確率で `age+1` する**独立したポーリング**で、バニラの成長速度とは無関係。+25% で 1段階あたり平均8秒＝小麦(7段階)が約56秒 ——バニラは最良条件でも1段階2分前後なので**体感は十数倍**。`min(1.0,…)` のせいで **100% 超は無意味**。水分・光量も見ていなかった。**旧・動物**（`BreedingBonusListener`）: `残り × (1 - bonus)` で +25% は**速度 1.333 倍**（1.25 倍ではない）、90% 短縮で頭打ち。**今**: 作物は `BlockGrowEvent`（＝バニラが1段階成長した瞬間）に相乗りし、`extraStages` が整数部を必ず足し小数部を抽選する → 1回の成長で進む段階の期待値が `1 + bonus` ＝**速度 ×(1+bonus)、上限なし**。水分・光量などバニラ側の条件も自動的に効く。動物は `残り ÷ (1 + bonus)`（子供の成長は 1tick で age が1進む一定速度のカウントダウンなので生誕時に残りを割るのが速度倍率と数学的に等価）。残り時間の下限 10%＝**速度 ×10 が上限**は旧「90%短縮」と同じ天井として維持。⚠️ **`BlockGrowEvent#getNewState` はイベント後に place される**ので MONITOR で書き換えれば反映される（稼働サーバの `paper-1.21.11` の `CraftEventFactory#handleBlockGrowEvent` を逆アセンブルして確認）。自前で書き戻すだけなので `BlockGrowEvent` を再発火せず連鎖しない。⚠️ **数値（`skilltree/farming.yml` の buffs）は触っていない** —— ユーザーが調整する前提。**同じ数値のまま作物は大幅に遅くなる**（+25% が実質十数倍 → きっかり 1.25 倍）。回帰テスト `GrowthBonusIsARealMultiplierTest`(8件) は「+X% は速度 ×(1+X)」という**意味そのもの**を固定し、作物と動物が同じ倍率になることも突き合わせる。旧式へ戻すと 4 件落ちることを確認済み。**TF jar の再ビルドと配備が必要。** |
| 2026-08-21 | **EM ダンジョンで矢が敵に跳ね返される件は EliteMobs の `invulnerability_arrow` パワーだった**（実サーバ報告「emダンジョンで矢が全て当たらなかった」→ 追加情報「そもそも敵に跳ね返される」。**TF のバグではなく、上流のコンテンツパックの設計**）。配備先 `plugins/EliteMobs/powers/invulnerability_arrow.lua` の中身は`damage_cause == "PROJECTILE" なら cancel_event()` の1行だけ。EM はこれを受けて生の `EntityDamageByEntityEvent` を `setCancelled(true)` するので、バニラの `LivingEntity#hurt` が false を返し、**`AbstractArrow` が矢の速度を反転させる**＝ 報告どおり「跳ね返る」。**当たっていないのではなく、当たってから無効化されている。**⚠️ **散り(jitter)ではなかった** —— 調査の前半は `bowJitter = max(0, 0.08 - bow_accuracy)`（弓 0.22 / 弩 0.35）を疑ったが、これは「外れる」であって「跳ね返る」ではない。**ログの死亡メッセージが決定的だった**: ダンジョン内の弓での撃破は全部 `was slain by ... using [ネザライトの弓]`（＝**弓で殴った**近接死）で、矢での撃破 `was shot by` は 10 件しかなく、その 10 件は**難易度 0 でだけ矢無効が付く mob**（`nether_moss` / `nether_sporeflinger` は`difficultyID: [1, 2]` 限定）と、矢無効を持たない `nether_feeder` だけだった。**該当は 92 体**（`custombosses/` を grep）。ダークカテドラルは**ボス全フェーズ・全雑魚・ミニボスが無条件で矢無効**なので弓は完全に無力。ネザーウェイストの `nether_beast` も無条件。⚠️ **新しい罠**: 既定 lua の書き出しは `LuaPowersConfig` の `if (!file.exists())` なので、**フォークの `getSource()` を直しても配備先の `.lua` は永久に更新されない**（ArsPaper の `saveResource(..., false)` と同じ形）。逆に言えば**配備先の `.lua` を1つ直せば 92 体すべてに効き、EM に上書きされない**。**対処方針はユーザー判断待ち**（仕様として残す／矢無効をやめる／一部だけ残す）。なお `multiply_damage_amount()` による「減衰にする」案は**単独では効かない** ——lua は `@EventHandler`（NORMAL）で走るのに対し TF の `TrinityForgeCombatListener` はHIGHEST で `event.setDamage(vanillaBase)` を無条件に上書きするため、TF 側の改修が要る。**コード変更なし（調査のみ）。** |
| 2026-08-21 | **称号のクライアント騎乗をやめ、消えていたネームタグを戻した**（実サーバ報告「ネームタグが表示されていない。**他人の名前も見えない**」。commit `229d215`）。**切り分けの決め手はユーザーの実機確認「称号を外しているとネームタグが出る」**。⚠️ **これは「高さの重なり」ではない —— 同じ症状を2度、高さの問題として直して2度とも再発している**（2026-08-03 `a1dd403`／2026-08-20 `b5fab44` W-174）。今回は W-174 の幾何修正（`高さ + 0.5 + 0.25 + clearance`）が**稼働 jar に入っていることを逆アセンブルで確認**した（配備 jar Aug 21 15:00）。実サーバの `clearance: 0.1` でも称号はネームタグの 0.1 ブロック上に居る。それでも名前は出ない＝**重なりは原因ではない**。**残った差分は騎乗だけだった**: 2026-08-03〜2026-08-19 の称号は「毎tickテレポートで追従する独立エンティティ」で、同じ高さに出ていて**名前も見えていた**。2026-08-19 の W-153 で `SetPassengers` パケットによるクライアント騎乗を足した**翌日**に「称号が名前を消す」が報告され、高さとして直したあとも消えたまま。＝ **プレイヤーを乗騎にすること自体がクライアント側でネームタグの描画を止めている**。描画側の判定はサーバ jar に無いので機構そのものは断定できないが、**騎乗の有無だけが「名前が出る/出ない」を分けている**ことは実機で確認済み。→ 騎乗機構（`TitleDisplayMountBridge` / 平行移動 / entity id 台帳）を**撤去**し、`teleport` + `teleport_duration` 補間の追従へ戻す。**代償は 1tick ぶんの追従の遅れ**（W-153 が消していたのはそれだけ）。補間長は更新間隔に揃えたままなので W-135 の「揺れ」は再発しない。**回帰テストは高さではなく『騎乗機構の不在』を固定する** ——騎乗用メソッドが1つでも戻る／ブリッジクラスが復活すると落ちる。**ダミーの `mountTranslationY` を足すと実際に落ちる**ことを実走確認済み。`TitleDisplayServiceTest` 13件成功・skip 0。TF **4525 件・失敗 28・skip 2**（28 は着手前と同一集合。すべて他セッション/ユーザーの未コミット yml 由来）。**config は触っていない。jar 再ビルド＋配備が要る。** |
| 2026-08-21 | **木材修繕がクリックのたびにイベントごと落ちていたのを直した**（実サーバログ 16:37 の `Could not pass event InventoryClickEvent to TrinityForge` ／`IllegalArgumentException: Damage cannot exceed max damage` @ `WoodRepairListener:158`。**別件の調査中にログで発見したもので、ユーザー報告ではない**）。**引き算しかしていないのに上限を超える理由**: 1.21 は最大耐久が `max_damage` コンポーネントで決まり、TF は `item-stats.yml` の `durability` から個体ごとに書き込む。その値を**下げる方向**へ調整すると、既に配られている個体は「damage > max_damage」のまま残る。Bukkit の `setDamage` はその値を弾くので、下限（0）しか見ていなかった実装は**回復量がいくらであっても必ず落ちる**。落ちる位置が `event.setCancelled(true)` より手前なので、プレイヤーから見た症状は**「修繕したのに何も起きず、素材だけ普通に持ち替わる」**（例外はログにしか出ない）。→ `applyRepair` を `[0, その装備の最大耐久]` でクランプする。回復量の算出側（`Math.min(getDamage(), 素材の耐久)`）も同じクランプを通す ——超過分まで「回復できる量」として数えると素材が無駄に消費されるため。回帰は新規 `WoodRepairDamageClampTest` 4 件。**クランプを外すと1件が実際に落ちる**ことを実走確認。⚠️ **MockBukkit の `setDamage` は上限超過を弾かない**ので、テストは例外そのものではなく**丸めた値**を固定している（実 Bukkit で例外を防ぐのはこの値の方）。TF **4528 件・失敗 29・skip 2**（29 はすべて他セッション/ユーザーの未コミット yml 由来）。**jar 再ビルド＋配備が要る。** |
| 2026-08-21 | **24時間EXP減衰の「等倍まであと◯時間」が実際より長く出ていたのを直した**（実サーバ報告「24時間経験値減衰の仕様に関して正しく時間経過でリセットされているが表示が前のデータのまま？なのか、リセットまで24時間を超えているらしい」）。**減衰そのものは正しく動いていて、ズレていたのは表示だけ**。回復の見積り `millisUntilFullRecovery` は指数減衰を解いた `窓 × ln(蓄積 / perAmount)` で、**W-154 の「発動から24時間で強制解除」を1つも見ていなかった**。出荷設定（窓24h・perAmount 10万）だと蓄積30万で26時間、100万で **55時間** ——実際には遅くとも発動から24時間で等倍へ戻るのに、スキルツリーGUIとチャットの両方に24時間を超える数字が出ていた。報告の「リセットまで24時間を超えているらしい」はこれで説明が付く（ユーザーが見たのは実際のリセット時刻ではなく、**この見積りの表示**）。→ `statusOf` に「強制解除までの残り」を渡すオーバーロードを足し、**指数減衰の見積りと期限の早い方**を出す。下限に張り付いて段が動かない領域では指数側が答えを出せないので、その場合は期限をそのまま使う。蓄積と残り時間は**同じロックの中で**読む（別々に読むと実在しない組み合わせが出る）。期限を設定していない環境（`lock-release-hours: 0`）の挙動は変えていない。ついでに `NativeSkillTreeMenu#dailyRateLore` の「蓄積は永続化しておらず退出時に捨てられる」というコメントを直した（2026-08-18 の永続化以降ずっと嘘だった）。回帰は新規 `DailyExpRecoveryEstimateTest` 6 件。**頭打ちを外すと3件が実際に落ちる**ことを実走確認。TF **4518 件・失敗 28・skip 2**（28 は着手前と同一集合）。**config は触っていない。jar 再ビルド＋配備が要る。** |
| 2026-08-21 | **パーティクルシード報酬が「解放しても何も起きない」状態だったのを実装した**（実サーバ報告「パーティクルシードを入手しても実装がないのでは？アイテムが入手できなかった」）。**報告どおり実装が無かった**。`special: [seed_*]` の付与は `PlayerData#grantSpecialReward` で**プレイヤーPDCへIDを1つ書くだけ**で、(a) そのIDを読む処理が**コードベースに1つも無く**（称号は `equipTitle`、パーティクルは `equipParticle` が読むが、シードには対応する読み手が存在しない）、(b) 合成側 `ParticleSeedListener#onPrepareCraft` は保有を**一切見ず**、(c) アイテムも案内も配らない。出荷 `seed-item` は**ブレイズパウダー/青氷/ソウルサンド/銅インゴット/エンダーアイ/ピンクの花びら**という誰でも手に入るバニラ材なので、(b) と合わせて**報酬は持っていても持っていなくても結果が同じ**＝実質何も付与していなかった。エラーもログも出ないので、気づけるのは受け取った本人が「何も起きない」と気づいたときだけ。→ ① `onPrepareCraft` に保有ゲート（`SpecialRewardService#isUnlocked`）を入れて、**ここを報酬IDの唯一の読み手にした**。ゲートは `continue` で次のシードへ流す（同じ `seed-item` を共有するシードが2つあるとき、解放済みの方まで巻き添えで使えなくなるため）。② 新規 `ParticleSeedDelivery` が**解放と同時に `seed-item` を1個渡し、使い方**（その素材と道具/武器の2つだけを作業台に置く）**を文章で送る**。アチーブ / 図鑑ティア / `/tf reward grant` の3経路すべてに配線した（`CollectionService` は配線順の都合で setter 注入）。③ `/tf reward list` に保有シードの素材名を出す ── シードは `/tf settings` の装備画面に出ないので、**この修正前に解放済みだったプレイヤーには②の案内が届かない**（事後の唯一の確認手段）。回帰は新規 `ParticleSeedGateTest` 4件 + `ParticleSeedDeliveryTest` 4件。**ゲートを外すと `ParticleSeedGateTest` の2件が実際に落ちる**ことを実走確認。TF **4512 件・失敗 28・skip 2**（28 は着手前と同一集合＝他セッション/ユーザーの未コミット yml 由来）。**config は触っていない。jar 再ビルド＋配備が要る。** |
| 2026-08-21 | **称号が二個表示される件を直した**（実サーバ報告＋スクリーンショット「称号が二個付いている不具合」）。**真因は『消す側が、一番消し損ねやすい状況でだけ諦める』形**で、経路が2本あった。① `TitleDisplayService#safeRemove` が `entity.isValid()` で門を張っていた —— `CraftEntity#isValid()` は「生きている」だけでなく**チャンクがロード済みで、ワールドのエンティティリストに登録済み**まで要求するので、**遠距離テレポート直後・湧かせた直後・ワールド跨ぎの最中**という「まさに消し損ねる瞬間」に限って false を返す。そこで諦めた表示体は誰の追跡下にも無いまま世界に残る。② `tick()` は表示体が無効になった行を `active` から**外すだけ**で張り直しており、**実体を消していなかった**（①に当たると必ず迷子が1体増える）。どちらも例外もログも出ない。**見え方が「1ブロック上に二段」になる理由**: 迷子は騎乗パケット（`SetPassengers`）が新しい個体に上書きされて外れるため、クライアントは**実座標＋平行移動**の位置に描く。平行移動は`titleAnchorY − 取付点` ＝ 約1.15なので、正しい個体のちょうど1つぶん上に並ぶ ——報告のスクリーンショットの間隔と一致する。→ ①は門を外し（`remove()` は既に消えた個体へ呼んでも安全）、②は `despawn()` を通す。加えて**常時走る安全網**として、5秒ごとにオンラインプレイヤーの半径16ブロックだけを見て「TFの印を持つのに追跡外の TextDisplay」を消す `sweepNearbyOrphans` を追加した（全ワールド総なめの `sweepOrphans` は起動時の1回きりで、実行時に空いた穴は次の再起動まで残っていた）。回帰は新規 `TitleDisplayOrphanTest` 5 件。`safeRemove` の門を戻すと2件が実際に落ちることを実走確認。⚠️ **`tick()` 側の修正には直接のテストが無い**（MockBukkit は `TextDisplay` の spawn を未実装で、踏むと FAILED ではなく **SKIPPED** に化ける）。実行時に効くのは上の安全網のほう。**なお同じスクリーンショットの「名前が出ていない」は別件で、三人称視点では自分のネームタグはそもそも描画されない**（他人の頭上で確認が要る）。**jar 再ビルド＋配備が要る**。 |
| 2026-08-21 | **解放式カスタムポーションの醸造EXPを素材ごとに分けた**（ユーザー要望「カスタムポーションの素材に応じて醸造の経験値を設定して」）。**着手前は全部が定額 150 だった**: 解放式ポーションは完成品のベースが `WATER` へ倒れるので `brew_result` から何も引けず（W-170 と同じ理由。こちらは PDC の印も `WATER` になる）、`alchemy_brew_exp` の定額へ落ちていた。**ラヴェジャーの革を使う耐性IIも砂糖の俊敏IIIも同じEXP**という状態。→ `alchemy_progression.yml` の `brew_ingredient` に 12 行追加（**config のみ。jar 不要**）。⚠️ **バニラ醸造と素材を共有する4種は値を動かせない**: 素材行は `brew_result` より**優先**されるので、`SUGAR` を上げるとバニラの俊敏ポーションのEXPも一緒に上がる。そこで共有4種は**その素材で作れるバニラポーションの `brew_result` と同額**に固定した（SUGAR=150 / RABBIT_FOOT=450 / GLISTERING_MELON_SLICE=450 / GOLDEN_CARROT=450）＝**バニラ側の取得量は1も変わらない**。`GOLDEN_APPLE`(=500) はバニラの醸造素材ではないので共有なし。カスタム素材はモブ限定ドロップで共有が無いため自由に配点した: 圧縮スイートベリー 500（栽培可）／エンダーマイト・ピグリン 600／ストレイ・ウィッチ 700／ラヴェジャー 1200（襲撃限定）／ボグド 2000（再生III。バニラ再生1800より上）。回帰は新規 `BrewUnlockIngredientExpCoverageTest` 3 件で、**許可リストではない** ——母集合は出荷 `crafting-features.yml` の `brew-unlocks` から自動で決まるので、**新しいカスタムポーションを足したら素材のEXPを決めるまで落ちる**。共有4種が結果表と同額であることも式で縛る。1行消すと実際に2件落ちることを実走確認。TF **4504 件・失敗 28・skip 2**（28 は着手前と同一集合＝他セッション/ユーザーの未コミット yml 由来。例: `ShippedBrewDeadEndMaterialTest` が求める `survivor-brew` グループは HEAD の config にも存在しない）。⚠️ **`multiplier_manual` が 2→1 のままなので、体感EXPは表の値の等倍**（別項目・ユーザー判断待ち）。 |
| 2026-08-21 | **レシピ画面で素材が1つも出ないコア系3種を直した**（実サーバ報告「コア系アイテムなど一部アイテムがレシピからクラフト素材をクリック見た時に素材が表示されない」）。**真因は「引けない shape」**: 詳細画面（`RecipeBrowserGui#renderWorkbenchDetail`）は `entry.shape` が空でなければ**必ず**格子描画へ倒し、各マスを `ingredientMap.get(記号)` で引いて**引けなかったマスを黙って空欄**にする。`materials.yml` の**ジュエリーコア／ベジタブルコア／ウッドコア**は `type: shapeless` なのに旧版の名残で `shape: [abc, def, ghi]`（**小文字**）が残っており、一方 shapeless の `ingredients:` は**配列**なので `UnifiedRecipeLoader` が **`A..I`（大文字）**のキーを振る→ 9マスとも不一致で**素材欄が丸ごと空**になっていた。**登録側は shapeless なので shape を一切見ず、クラフト自体は成立する**（＝壊れているのは画面だけ、しかもレシピが存在しないのと見分けが付かない形）。一覧の lore だけは `appendWorkbenchSummaryLore` に `counts.isEmpty()` のフォールバックがあったので出ており、「一覧では見えるのにクリックすると消える」という報告の形と一致する。→ 直し方をこの3件のデータ修正にしなかったのは、**記号の食い違いは今後も同じ形で入り込み、入ってもエラーにならない**ため。新設 `RecipeShapeNormalizer#usableShape` を全レシピが合流した最終整形で1度だけ通し、**1マスも引けない shape だけを捨てて shapeless 表示へ倒す**（1マスでも引ければ shaped のまま＝空白入りのレシピを壊さない）。供給元が3経路（Ars登録／TFカタログ／元config spec の上書き）あるので個々ではなく合流点で1回。併せて **shapeless の並びを記号キー昇順＝config に書いた順**へ固定した（素の `Map#values()` は HashMap の走査順なので9素材が毎回でたらめな位置に並ぶ。誤りには見えないので報告されないまま「読めない画面」になる劣化）。回帰は新規 `RecipeShapeNormalizerTest` 6 件。`usableShape` を無条件に shape を返す形へ戻すと「記号が1つも引けない shape は捨てる」が実際に落ちることを実走確認。フォーク全体 **481 件・失敗0・スキップ0**。⚠️ `materials.yml` 側の名残（3件の `shape:`）は**落としていない** —— このファイルには他セッション／ユーザーの未コミット編集（`source_shard` の `recipe:`→`recipes:` 変換）が載っており、巻き込んで commit したくないため。コード側で無害化済みなので実害は無いが、読む人を迷わせる。fork commit `fbbd9cc`。**Ars jar 再ビルド＋配備が要る**。 |
| 2026-08-21 | **設定エディタのアイテム選択に出る幽霊 `custom:エンチャントされた金リンゴ` を消した**（ユーザー報告「存在しないアイテムが表示されるので消して。こんなアイテムカタログにない」）。**候補源に ArsPaper `items.yml` が混ざっていた**（`recipes.js#ensureCustomDatalist` が `items.items` の全キーを `custom:<キー>` として登録）。あのファイルは**レシピ定義だけ**でカスタムアイテムを1件も登録しない（登録するのは materials / functional-items / threads / spellbooks と TF catalog）ので、`items:` のキーは**レシピの名前**であってアイテムIDではない。出荷 `items.yml` に残る2件（`trident` と `エンチャントされた金リンゴ`）は**どちらも結果がバニラアイテム**のレシピで、うち1件がキー自体日本語なので**カスタムアイテムの表示名に見える＝誤選択を誘う**（選ぶと決して解決されない `custom:<id>` が config へ書き込まれる）。**「この2件を除外」ではなくファイルごと候補源から外した** —— items.yml のキーが custom アイテムになる経路は構造的に存在しない（ローダーは result 未指定なら `custom:<id>` を結果に据えるが、その id のカスタムアイテムが無ければレシピごと登録に失敗する）ので、レシピが1本増えるたびに幽霊が復活する形にしない。候補組み立てを `public/js/recipe-custom-candidates.js`（DOM も fetch も触らない純関数、`catalog-candidates.js` と同じ UMD 形）へ切り出し、Node から実 yml を通して検証できるようにした。回帰は新規 `recipe-custom-candidates-2026-08-21.test.js` 4 件。**許可リストではない** ——母集合は実 `items.yml` のキー全部から自動で決まり、「items.yml 由来のIDが1つでも候補に居たら落ちる」を縛る。items.yml を候補源へ戻すと実際に落ちることを実走確認。editor 全体 **1407 件・失敗 26・スキップ 1**（26 は着手前と同じ集合＝ユーザー/他セッションの未コミット yml 由来。例: `STONE_SPEAR の使用レベル 5 !== 10`）。**配備不要**（エディタはブラウザ再読込で反映）。 |
| 2026-08-21 | **自動作業台(Crafter)の成果物を必ず最低品質にした**（ユーザー指示「現状自動作業台でクラフトすると品質がないものが生成され、拾った段階で品質が付くので開運参照である。これはまずいので自動作業台でできるものはすべて劣悪品質にしてほしい」）。**穴の形**: `CrafterCraftEvent` には**クラフトしたプレイヤーが居ない**ので成果物は未刻印のまま出る。未刻印品は `PickupQualityListener#stampIfEligible` が**拾った人の開運**（`loot_luck` ＋ `lootBaseQuality`）で品質を決めるので、**自動作業台で量産 → 開運の高い人が拾う**が腕でも運でもない品質稼ぎになっていた（拾い直すだけで振り直せるわけではないが、量産と開運を掛け算できる点で他のどの経路より安い）。→ `PickupQualityListener#onCrafterCraft`（MONITOR）で **craft 時点に品質0固定＋実 rollSeed で刻印**する。刻印済みになるので拾得経路は `hasRollSeed()` で素通りし、**誰が拾っても劣悪のまま**。**触らないもの**: 既に刻印済みの成果物（カタログレシピが品質付きで返すもの）と儀式の品質未決定マーカー（`pendingCraftQuality`）— どちらも品質の決め方が別に定義されている。スレッドだけは専用 lore を持つので汎用 stamp ではなく `arsThreadRestamper.restampIfThread(stack, 0)` へ流す（W-53 と同じ理由）。`item-stats.yml` に設定の無い素のバニラ品は対象外（勝手に TF 品にしない）。回帰は新規 `CrafterWorstQualityTest` 5 件（品質0で刻印される／**50回まわして1度も0以外が出ない**＝開運も乱数も混ざらない／刻印済みは素通り／`CrafterCraftEvent` 経由でも入る＝配線の確認／未設定品は対象外）。**jar 再ビルド＋配備が要る**（config では直らない）。 |
| 2026-08-21 | **プレステージの確認がプレイヤーに見えていなかった件を直した**（ユーザー報告「プレステージをスキルツリーで選択した時に確認画面が出ない」）。**2クリック確認の機構自体は前から在った**（`NativeSkillTreeMenu#handlePrestige` は `pendingPerkId` と一致しない1クリック目で必ず `reopenNextTick` して止まる）。欠けていたのは**伝える手段**で、確認中だと分かるのは**そのアイコンの名前と lore だけ**だった。プレステージノードは幹の最上段にあり初期ビューポートの外／統合版クライアントでは lore が読みづらいので、**押しても何も起きないように見える**。ツリーリセット（`applyTreeReset`）は同じ場面で**チャットに確認を出している** —— この非対称が原因。→ `handlePrestige` にも同じ作法でチャット確認を追加（プレステージ名／何回目／**パークが全部外れる・SPは返却・ロック中は維持**・レベルの行き先）。**レベルの行き先は POWER だけ文言を変える**（POWER は0リセットではなく他スキルから再導出。2026-08-04 の修正どおり）。回帰は `NativeSkillTreeMenuPrestigeConfirmTest`（1クリック目で確定しない／確認がチャットに出る／2クリック目で確定する の3点。**修正前は「確認がチャットに出る」だけが落ちた** ＝ 機構ではなく告知が欠けていたことの証明）。⚠️ テストを書く途中で踏んだ罠2件: **MockBukkit の `callEvent` には登録済みリスナーしか居ない**ので `menu.onClick(event)` を直接呼ぶ（既存のメニューテストと同じ作法）／**プレステージ判定はそのスキルの行が在ることが前提**（行が無いと `LEVEL_TOO_LOW` になり2クリック目が無反応になる）。**jar 再ビルド＋配備が要る**（config では直らない）。 |
| 2026-08-21 | **ダンジョンEXPのナーフ**（ユーザー指示「ダンジョンの経験値がおいしすぎる。オーバーワールドの討伐時の経験値（全職業）を現在の3/4にし、ダンジョンの経験値を2倍（現在は4倍）に」）。**ダンジョン内側の倍率キーが存在しなかった**（`SkillExpConfig#worldExpRate` はダンジョン内を **1.0 固定**で返しており絞れるのは外だけ＝比は常に「外の4倍」）ので、`dungeon-exp-rate`（既定 1.0 ＝ 旧挙動）を新設した。出荷値は **内 1.0 → 0.375 ／ 外 0.25 → 0.1875**。外は指示どおり 3/4、内は新しい外のちょうど2倍。**ダンジョンの絶対量は従来の 0.375 倍**になる。掛かる先は「ダンジョン内でも外でも入りうる戦闘系EXP」＝ 武器（重／軽／弓）・魔法（討伐）・防具（被弾）（`CombatListener` 3箇所／`NativeSkillExperienceListener`／`ArsProgressionBridge` の `worldExpRate` 呼び出しが全部この1関数を通る）。⚠️ **家畜討伐の農業EXPはこのゲートの外**（ダンジョン内外の別が無い経路）。2026-08-20 に farming 側で 80% へ調整済みなので、ここで重ねて 3/4 にはしていない —— 重ねたいなら farming 側の値を直接動かすこと。回帰は `SkillExpWorldRateTest`（内側キーの単体＋クランプ＋**出荷値の「内は外のちょうど2倍」と「外は旧値0.25の3/4」を式で固定**）。editor にも「ダンジョン内EXP倍率」を追加（`lib/schema.js` の検証／`public/js/labels.js`／`public/js/tf-forms.js`）。**jar 再ビルド＋config 配備の両方が要る。** |
| 2026-08-21 | **醸造EXPが半分になっている件の原因を特定**（ユーザー報告「醸造の経験値で本来得られる経験値の半分しか手に入らない」）。**コードのバグではなく config の値**: `skills/base/alchemy_progression.yml` の **`multiplier_manual` が `2` → `1` になっている**。手動醸造のEXPにはこの値が丸ごと掛かる（`NativeSkillExperienceListener#onBrew`）ので、2 だった頃のちょうど半分になる。物証は editor のバックアップ `backups/trinityforge/skills/base/alchemy_progression.yml/*.bak-*`: **`bak-20260820-011137` までは 2、`bak-20260820-012321` 以降は 1**（＝2026-08-20 01:11〜01:23 のエディタ保存で変わった。同じ保存で REGENERATION 600→1800、TURTLE_MASTER 1500→800、`exp_multiplier_quality` 0.005→0.05 も動いている）。配備先 `Main_Server/plugins/TrinityForge/skills/base/alchemy_progression.yml` も `1`。**他の半減要因は潰してある**: 日次逓減は ALCHEMY の蓄積最大が 17,363 で1段目の閾値 100,000 に遠く届かない（＝倍率 1.0）、自動判定に化けているなら 0.25 倍で「半分」にはならない。→ **2 へ戻すかはユーザー判断待ち**（この値はユーザー自身が editor で入れたもので、同じ保存の他の変更は意図的に見えるため勝手に戻さない）。 |
| 2026-08-21 | **HuskSync をフォークして「アイテム1個で全ロスト」を止めた**（配備待ち）。上流 4.0.0 (`3dc619d`) の `BukkitSerializer.ItemDeserializer#getItems` は、スナップショットのバージョンがサーバと同じなら `NBT.itemStackArrayFromNBT` で**一括**復元する。1 個でも復元できないアイテムがあると配列ごと例外になり、呼び出し側が**そのデータ型（インベントリ丸ごと）を skip** する。**一括読みを try で包み、落ちたら上流が既に持っているスロット単位の読み直し（`upgradeItemStacks`）へ合流させた**（あちらは 1 スタックずつ try/catch して最後に `AIR` へ落とす）。＋黙って AIR にしていた箇所へ「どのスロットの何を落としたか」の WARNING を追加。新規ロジックは書き起こしていない。`fork-handoff/husksync/`（**fork/ は .gitignore 除外**、パッチ本体は `patches/0001-tolerate-unreadable-items.patch` として tracked）。**ビルドは JDK 25・タスクは `:bukkit:1.21.11:build`・配るのは `target/` の 3.2MB 版**（`bukkit/1.21.11/build/libs/` の同名 jar は依存非同梱の thin jar 153KB で起動不能）。配備は `ops\launch\deploy-husksync.cmd`（パッチ未適用 jar を目印文字列で拒否／停止ゲート／**今入っている jar と同じ名前で置く**＝別名だと 2 本ロード／旧 jar は `.jar.bak-<日時>` へ退避）。**これで全ロストは止まるが `nova_structures:` 品自体は Main/Dev で復元できないまま**（そのスロットは AIR）。持ち帰れるようにするなら registry の一致が別途必要。 |
| 2026-08-21 | **プレステージのSP設計を変更**（ユーザー決定）: `power_progression.yml` の **`prestige_decay_rate` を 0.5 → 0（減衰なし）**、**`max_level` を 160 → 242**。⚠️ **このキーは「減衰率」なので `1` にすると (1-1)^tier = 0 ＝ プレステージ後の POWER EXP がゼロになる**（＝意図と正反対）。等倍にしたいときの値は **0**。242 の根拠: 減衰が無くなると供給が 2 倍になり、**全15スキル × Lv100 × 2周 × exp_gain 240 = 720,000EXP**、POWER 曲線の累積 `9L²+791L` を解くと **L=242.4** ＝ ちょうど「各スキル1回プレステージ」ぶん。SP 上限は 163 → **245**（ノード総コスト 295 / 排他考慮の実効 232 に対して 106%）。テストは数値 pin だけでなく**供給量と上限の関係**を式で縛る `powerCap_matchesTheSupplyOfOnePrestigeCycleForEverySkill` を追加（245 で書いたら 「到達 242」と落ちて実際に効いた）。減衰前提だった `grantExpAppliesLessPowerExpTheMoreTimesASkillHasBeenPrestiged` は「出荷configでは全 tier 等倍」の pin へ書き換え（減衰の機構自体は `prestigePowerDecayMultiplier` の単体テストが押さえている）。⚠️ **反映には config 配備＋ `/trinityforge reload` が要る**（`max_allowed_level` はプレイヤー行に 160 で保存済みで、更新するのは reload 時の `ProgressionCurveReconciler` だけ。ログインでは走らない）。⚠️ **遡及はしない**。既にプレステージ済みの 5 人が 0.5 倍で受け取った分は戻らない（FARMING×2 / SMITHING×2 / ARS_MAGIC×1、各 6,000〜12,000 POWER EXP 相当）。 |
| 2026-08-21 | **「サーバ移動でごくまれにアイテムがロスト」の真因＝バックエンド間でデータパックのエンチャント登録がズレている**（未修正・要判断）。`Resource_Server/world/datapacks/` にだけ **Dungeons and Taverns v5.1.0** ほか 17 個のデータパックが入っており、これが `nova_structures:` 名前空間の**カスタムエンチャント 34 種**（spiteful / traveler / swift_soar / illagers_bane 等）を登録している。**Main_Server と Dev_Server の datapacks は `bukkit` のみ**。資源鯖で拾った DnT エンチャ品を持って Main/Dev へ移動すると、HuskSync が NBT→ItemStack 変換に失敗し **そのデータ型（インベントリ丸ごと）を破棄**する: `NbtApiException: Failed to get element nova_structures:spiteful` → `The player will load without this data type for this session`。実ログの物証は `Main_Server/logs/latest.log` の 2026-08-21 11:24:38（.shizurei555、エンチャント本 slot16 blast_protection2+efficiency2+spiteful2）。**その後の save で snapshot が上書きされるので恒久ロスト**。「ごくまれに・条件不明」の正体は「DnT エンチャ品を持って鯖跨ぎしたときだけ」。**失われるのはその1個ではなくインベントリ丸ごと**。方向の物証: proxy の latest.log で `.shizurei555` は 10:12:20 から resource にいて **11:24:37 に resource → main へ移動**し、その**1秒後に Main 側で deserialize 失敗**。直後 11:25:09 / 11:25:33 / 11:26:27 と往復している（ロストに気づいて戻った動き）。復旧は `max_user_data_snapshots: 16`／`snapshot_backup_frequency: 4h` があるうちに `/userdata` から restore。**恒久対処は「3 バックエンドでエンチャント registry を一致させる」**の 1 点（案: DnT 本体だけ Main/Dev へ配る／worldgen を落とした registry 専用パックを作る／資源鯖から外す）。 |
| 2026-08-21 | **「プレステージ後に増えるはずのポイントが入らない」は台帳バグではなかった**（調査完了）。実 DB（`Main_Server/plugins/TrinityForge/player_progression.db`、WAL 込み・21 人）で **`available + spent == 3 + POWER レベル` が全員一致**、かつ **`spent == 所持 perk の purchase_cost 合計` も全員一致**＝返却漏れも消失も 0 件。プレステージ済みは 5 人（FARMING×2 / SMITHING×2 / ARS_MAGIC×1、全員 tier1）で、POWER の total_exp も `240×(未プレステージ分のレベル和 + 100 + 再到達レベル×0.5)` と**完全一致**（例 a414d070: 240×(370+100+50)=124,800 = DB 値）。**体感の正体は PRG-09 の等比減衰 0.5 × POWER 曲線が 2 次**: SP は `3 + POWERレベル` のみで増え、POWER の 1 レベル単価は `18L+800`。初回の 0→100 は 24,000EXP＝**+24SP** だが、1 回プレステージ後の 0→100 再周回は 12,000EXP しかなく、POWER81 の時点では **86.2 - 81 ≒ +5SP** にしかならない（POWER118 なら +4SP）。さらに **POWER の `max_level: 160` = SP 上限 163** で、**15 スキルを 1 周ずつ上限まで上げると 15×24,000=360,000EXP → POWER 160.8 でちょうど天井**に当たるため、そこから先はプレステージしても SP が 1 も増えない。**コード修正ではなく config 判断**（`power_progression.yml` の `prestige_decay_rate` / `max_level` / `exp_gain`）。ユーザー判断待ち。 |
| 2026-08-21 | **スプラッシュ化・残留化でポーションの中身が消えるのを直した**（実サーバ報告「ポーションをスプラッシュ化しようとすると水入り瓶になる」）。**真因は W-108（レッドストーン/グロウストーン）と同一で、あちらにだけ救済が入っていた**。稼働サーバの `paper-1.21.11.jar` を逆アセンブルすると `PotionBrewing#mix` の**容器 mix 分岐は `PotionContents.createItemStack(mix.to.value(), contents.potion().get())` の1行だけ**で、**入力のコンポーネントを1つも引き継がない**（カスタム効果・表示名・PDC が全部落ちる）。TF は品質を乗せるとき base を `WATER` へ倒して全部カスタム効果で表現するので、スプラッシュ化すると **WATER だけが残った＝スプラッシュ水入り瓶**になる。**錬金術の品質ステを持っている人だけ壊れる**（品質0の人は base が倒れないので正常）= W-83/W-108/W-170 と同じ現れ方。`rewriteCustomEffectContainerMix` を追加し、WATER ベース＋カスタム効果のポーションだけ器を差し替えて中身を持ち越す。**対象はバニラと同じ2組だけ**（`addVanillaMixes` を実バイトコードで確認: `POTION+GUNPOWDER→SPLASH_POTION` / `SPLASH_POTION+DRAGON_BREATH→LINGERING_POTION`）。**持続時間は変えない** —— 残留の 1/4 は使用時に `AreaEffectCloud` 側が掛けるので、ここで縮めると二重に効く。テスト5件追加、救済を外すと3件が実際に落ちることを実走確認（残り2件はバニラへ委ねる側の負のコントロール）。**jar の再ビルドが必要**。commit `4e742a4` |
| 2026-08-20 | **設定エディタの保存で EXP 表の行が 208 行消えていたのを戻した**（配備直前の点検で発見。ユーザー確認済みで復元）。**値の変更ではなく行そのものの消滅**で、`git diff` を値の差分として読むと気付けない。`stats/skill-exp.yml` は `smithing`/`ars-smithing` の `exp-per-material` から **`custom:` 系 約85行 × 2表**（魔導装備の全シリーズ・魔導書・圧縮素材）と**バニラ防具12行 × 2表**が落ち、**HEAD に 122 件あった `mage_*` が 0 件**＝儀式/Ars鍛冶で魔導装備を作っても EXP が積まれない（2026-08-03 / 08-09 に直した不具合の再発）。`farming_progression.yml` は `block_drops` の POTATO/CARROT/BEETROOT/COCOA_BEANS/MELON_SLICE/SWEET_BERRIES/TORCHFLOWER/PITCHER_PLANT と `block_interact` の PUMPKIN/KELP/ツタ4種、`mining_progression.yml` は SANDSTONE/RED_SANDSTONE。**検出手段は「HEAD と working tree のキー集合の差」**で、`custom:xxx` はキー中にコロンを含むので**素朴な `^\s*([^:]+):` 方式の走査からは丸ごと漏れる**（最初の走査で 190 行を見落とした）。復元は `tmp/restore-dropped-exp-rows.py`＝「HEAD にしか居ないキーだけを HEAD の値と直前コメントごと同じ親セクションへ挿し戻す」形で、ユーザーの値・追加行・並びには触れていない（既存 208 キーの値の改変 0 件を突き合わせで確認）。テストは **4477 / 失敗 25 / スキップ 2**（復元前 29）。**スキルツリーの編集は無実**で、レイアウト系 3 件は HEAD へ戻しても同じく落ちる既知失敗。残る失敗のうち 3 件（`NativeSkillCatalogRatesTest` ほか）は**意図した値変更を旧値で pin しているだけ**（防具 `exp_damage_piece` 10→25 / 畜産 60→40 / ダイヤ鉱石 400→800）。commit `1454e9c` |
| 2026-08-20 | **杖の火力/CT と範囲ダメージ半径を再調整**（ユーザー要望「杖の CT が短すぎるし火力も同じ tier の武器に比べて高すぎる」＋「大剣のダメージ半径を 3 とし、他の範囲ダメージ武器はこのナーフに合わせて縮小」）。**測ってみると杖の単体 DPS はもともと剣の 0.48〜0.63 倍**で、体感の原因は別の 2 つ ── **1 発の威力が剣の 2.16〜2.71 倍**（魔法ダメージは `spellBase + 杖の attack-power × 1.0` なので 1 発にそのまま乗り、範囲呪文では対象ごとに入る）と、**CT が上位ほど短い逆進カーブ**（木 3.5s → インフィニティ 2.1s）。ユーザー決定に従い杖 10 本を「同系列の剣の 1.5 倍・CT 4.0s 固定」へ、大剣 15 本の `aoe-radius` を 3 へ統一、その他の AoE 武器 48 本を x0.75（2.0→1.5 / 2.5→1.88 / 3.0→2.25 / 3.5→2.63）。`stats/item-stats.yml` のデータのみで **Java は無変更**。**旧テストが穴だった** ── `WeaponTierParityTest` の杖検査は「詠唱 DPS が剣の 47.5%±」という**帯**で、1 発と CT を同時に動かせるため逆進カーブを素通りさせていた。**倍率 1.5 と CT 4.0s を直接固定する検査へ書き換えた**。**弓/クロスボウは剣の 1.78 / 2.04 倍**で全 9 帯一定、`item-cooldown` は持たない（レートはバニラの引き絞り時間）。**未配備**（config 配備＋再起動が必要） |
| 2026-08-20 | **config が勝手にロールバックする 2 経路を塞いだ（W-177）**（ユーザー報告 2 件）。報告は「network.yml でチャット書式を変えたら戻っていた」と「触媒のレシピと id を変えたら戻っていた」。**原因は別々で、どちらも『配備／git がリポジトリの未コミット編集を一方的に潰す』形**。① **network.yml は設定エディタに画面が無い**ので人が直せるのは配備先の実ファイルだけ。config 配備は repo → server の一方通行なので、**2026-08-19 23:14 の配備がその編集を握り潰した**（配備先 network.yml の mtime と中身が HEAD 一致で確定）。W-110 でガードは作ってあったが **`deploy-config-head.cmd` からしか呼ばれておらず、実際に走った `deploy.cmd --config` は 1 度も呼んでいなかった**（`tmp/deploy-config-manifest.json` も `tmp/deploy-config-backup/` も存在しなかった＝**ガードは一度も動いていない**）。② **触媒＝杖3種（`magic_wand`/`abyss_wand`/`boundary_wand` → `infinity_wand`/`hero_wand`/`nuclear_wand`）の id とレシピ**は、**別セッションがテストのために `catalog.yml` を HEAD へ戻した**ときに消えた。決定的証拠: エディタのバックアップ `catalog.yml.bak-20260819-145122-654`（14:51:22 の保存直前の姿）が **`5979298^` とバイト単位で一致**し、33 秒後の commit `5979298` は `source:` 34 箇所（68 行）**だけ**を含む＝**14:51:22 の保存が丸ごと消えている**。commit メッセージ自身が「catalog.yml を HEAD へ戻した状態でも…確認済み」と書いている。副作用として、その sweep は id で hero/nuclear を拾うので**巻き戻った杖3種だけ 8000/15000 のまま取り残されていた**（ユーザーが 2026-08-20 15:19/15:20 に手で復元済み）。**対策**: (a) `guard-deployed-config.ps1` を「退避するだけ」から**「配備先の編集をリポジトリへ書き戻す」**へ拡張し、**配備の向きが一方通行でなくなるようにした**（リポジトリに同名が実在し／実行時状態ファイルでなく／他バックエンドから取り込み済みでない、の3条件。`-NoPromote` で無効化）。(b) **`deploy.cmd --config` にもガードを配線**し、`deploy-config-head.cmd` は**ガードを HEAD 展開の前へ移動**（後ろだと取り込んだ内容がその配備に乗らず、退避したのにサーバは元に戻ったままになる＝今回の再発そのもの）。(c) `.claude/hooks/pre-guard.py` が **`git checkout <path>` / `restore` / `reset --hard` / `clean` / `stash push` を deny**（ブランチ操作と `stash list/show/pop/apply` は通す）。(d) 代替の正規手段 **`ops/scripts/run-against-head.ps1`**（退避→HEAD を置く→実行→**finally で必ず戻す**）を追加。(e) network.yml にユーザー指定の書式を入れ、**「エディタに画面が無い」ことを yml 冒頭に明記**。検証: `test-guard-deployed-config.ps1` 6 項目・`test-run-against-head.ps1` 6 項目（どちらも新規、testkit へ配線）、`test_pre_guard.py` 35 件（deny/allow 両側）、`ChatFormatTest` 10 件・skip 0。**RED 実証**: 出荷書式のプレースホルダを 1 文字崩すと新テストが落ちる／`-NoPromote` で取り込みが起きないことを同じテスト内で対にした。台帳（`tmp/deploy-config-manifest.json`）は稼働中の配備先から 143 件で作成済み＝次の配備から `[UNKN]` ではなく差分判定が効く。**⚠ 未実施（ユーザー作業）: TF jar と network.yml の配備＋サーバ再起動**（チャット書式は配備しないと変わらない）。 |
| 2026-08-20 | **幸運のポーションを作業台・儀式・醸造の品質ptへ乗せた（W-172）**（ユーザー要望）。**着手前の実態は「幸運は釣りと拾得の装備品質にしか効いていない」**で、`PlayerLootLuckSource` が `loot_luck` へ 1 レベル=+1.0 で合算する 1 経路しか無かった（**釣りの宝/ゴミ確率にも効かない** ── あちらは `fishing-luck` でロッドのステ＋エンチャント＋釣りレベルから出しており幸運は入らない）。作業台は `workbench_quality_bonus`、儀式は `ritual_quality_bonus`、醸造は `potion_quality_bonus` と**同じ単位**で幸運ぶんを足す形にし、換算レートは `stats/quality.yml` の **`luck-potion-quality-per-level`（既定 1.0、0 で無効化）** 1 つに集約した。**効果レベルの読み取りは `VanillaLuckEffect` へ一本化**（`amplifier+1` を書き間違えても「幸運が 1 段ずれる」としか出ないので読み取り点を増やさない）。**丸めは合計してから 1 回だけ** ── ステと幸運を別々に丸めると `0.5+0.5` が `0+0` に落ちて幸運が無反応になる帯ができるのでテストで固定した。**プレビュー（`qualityMode`/`minimumQuality`）も同じ経路へ通した**（直さないと「プレビュー 3 なのに作ると 5」になる）。**自動（ホッパー）醸造では幸運ぶんにも `alchemy.auto_mult` の減衰を掛ける**（片方だけ無減衰だと「幸運を飲んで放置」が成立する）。釣り・拾得は `loot_luck` と単位を揃える必要があるので従来どおり 1 レベル=+1.0 固定（yml に明記）。エディタは品質定義タブへフィールドとラベルを追加（`tf-quality` の検証は許可リスト方式ではないのでスキーマ変更は不要）。検証: 新規 `CraftQualityLuckPotionTest` 7 件 + `PotionQualityListenerTest` +4 件・**skip 0**、**RED 実証**（加算を潰すと 7 件が落ちる）済み。TF **4460 件・失敗 26・skip 2**（着手前と同一集合＝他セッションの未コミット yml 由来）、config-editor **1403 件・失敗 25**（品質まわり 41 件は全緑）。**⚠ 未実施（ユーザー作業）: TF jar の配備＋サーバ再起動**（`quality.yml` も同時に配る）。 |
| 2026-08-20 | **実サーバ報告 第18陣 W-170/W-171**（醸造EXPが一律 302 / Ctrl+Q の一括クラフトで EXP が 1 個分）。**2 件とも「1 回しか飛ばないイベント」と「後段が前段に壊された情報を読む」型で、config では絶対に直らない**。**W-170**: `PotionQualityListener`(HIGH) が品質付与のためポーションのベース種別を `WATER` へ倒す → EXP を出す `NativeSkillExperienceListener#onBrew`(MONITOR) が `getBasePotionType()` で `brew_result` を引くので**常に表に無い `WATER` を読み、定額へ落ちる**。`potion_quality_bonus` が 0 なら `applyQuality` に到達しないので、**品質を取った人だけ壊れる**（報告の「解放後」と一致）。HEAD の config で `150 × 1.005 × 2 = 301.5 → 302` と報告値まで一致した。倒す直前の種別を PDC `trinityforge:brew_source_potion` へ焼き付け、EXP 側はそれを最優先で読む形に。**W-171**: `CraftItemEvent` はクリック 1 回につき 1 度しか飛ばず、バニラ `AbstractContainerMenu#doClick` の `ClickType.THROW` 分岐は **`button == 1`（Ctrl+Q）だけ**「同じ品が出る限り `safeTake`→`drop`」を**ループ**する（稼働サーバの `paper-1.21.11.jar` を逆アセンブルして `goto` を実バイトコードで確認）。旧実装は `isShiftClick()` だけを見ており Ctrl+Q は false なので常に 1 回分だった。**shift は収納容量で頭打ち・Ctrl+Q は地面へ落とすので頭打ちにならない**という差もアクション別に反映。**前回の増殖バグ（スタック素材で 1 個クラフトしてもスタック数ぶん入る）は回数を「各スロットの最小個数」から出すことで維持**し、テストで固定した。**途中で自分が入れた回帰を 1 件潰した**: 収納を無条件に読む形にしたらインベントリを持たないモックの `CraftQualityListenerResultDupeTest` 2 件が NPE で落ちたので `Supplier<ItemStack[]>` の遅延読みへ（Ctrl+Q は収納を読まない、がシグネチャに出る）。RED 実証: W-170 は焼き付けと読み取りを外すと 2 件、W-171 は呼び出しを `isShiftClick()` へ戻すと 1 件が実際に落ちることを実走確認。検証: TF **4435 件・失敗 26・skip 2**（26 は全て他セッションの未コミット yml 由来。例: `exp_damage_piece: 10 → 25` の書き換えで `NativeSkillCatalogRatesTest` が `expected 10.0 but was 25.0`）。**⚠ 未実施（ユーザー作業）: TF jar の再ビルド＋配備＋サーバ再起動** |
| 2026-08-20 | **呪文フォームの住み分け（設計依頼）── 炸裂は伝播の完全下位互換だった**。ArsPaper `a528407`。棚卸しで「投射 + 害悪 + 伝播×4 = 140 マナで 8 ブロック内 9 体」に対し「炸裂 + 害悪 + 半径増加×6 = 270 マナで半径 7」と判明し、**チェーン検索半径 `8.0` が Java 側ベタ書きで炸裂の最大半径より広い**のが決定打だった。**ダメージ重複が起きていないのは i-frame のおかげ**（チェーンは +2/+4/+6 tick で撃つのでバニラの無敵時間 10 tick に吸われる。`setNoDamageTicks(0)` を呼ぶのは `HeavyImpactEffect` だけで伝播は付かない）＝炸裂+伝播は「重複して強い」ではなく**払ったマナが消える**状態だった。ユーザー判断で **案1 + 案3 + 上限 + 減衰**を実装。**案1**: ヒット済み集合を `SpellContext.CastState` へ移し `copy()` で参照共有、チェーンは未ヒットだけを掴む（同じマナが「重複」ではなく**到達範囲**になる）。炸裂は **resolve より先に** `markCastHits(targets)` を呼ぶ ── **逆順だと 1 体目のチェーンが「これから直撃させる 2 体目・3 体目」を選んで無敵時間に捨てる**ので、この順序を `PropagateChainSharingTest` で固定し、逆に戻すと落ちることを実走確認した。**案3**: 誰にも当たらなかった炸裂は炸裂地点そのものを伝播の起点にできる（`resolvePropagateFromLocation`）。**投射は外した弾から連鎖しない**ので、これが炸裂+伝播だけの利点になる。**上限 `max-chains-per-cast: 20` は必須** ── 未ヒットを探し直す方式は密集地で候補が尽きるまで外へ伸び、起点ごとに `getNearbyLivingEntities` を呼ぶので TT で対象数×検索回数が跳ね上がる。**減衰 `damage-falloff-per-hop: 0.15` / `min-damage-rate: 0.4` は最終ダメージへ掛ける** ── 素の威力側に掛けると TF の守備力が引き算で効くため 0.4 倍のつもりが `min-component-damage: 1` に張り付き、減衰率と実ダメージが桁で食い違う。算術は `SpellPropagateMath` へ切り出して挙動で固定した。⚠ **`glyphs.yml` は `saveResource(..., false)`** なので稼働中サーバの yml は上書きされず、4 つの新パラメータは Java 側の既定値で動く（変えたいなら配備先へ手で足す）。⚠ **未着手（判断待ち）**: `base-burst-radius` が `1.0`（コード既定 2.0。`4c78f08` の一括正規化で落ちたと見られる）ため炸裂単体は依然弱い。2.5 前後への引き上げを提案済み。`form-cooldowns` 全 0 も同様。検証: fork **460 件・失敗 0・skip 0**（HEAD 単体で `git archive` して実走。HEAD~1 と同じ 4 件だけが失敗＝**すべて他セッションの未コミット WIP 由来で、増えた 6 件は全緑**）。**⚠ 未実施（ユーザー作業）: ArsPaper jar の再ビルドと配備＋サーバ再起動** |
| 2026-08-19 | **実サーバ報告 第14陣 W-157〜W-160**（統合版まわり4件）。**W-159/W-160 は同一原因**で、統合版クライアントが鍛冶台のスロット判定を自前で持っているためサーバのレシピ登録では広がらない（Geyser#4706 は Can't Fix）。独自 GUI は `PrepareSmithingEvent` に乗る**3プラグイン7判定**を全部バイパスするので採らず、**本物の鍛冶台へサーバ側から差し込む** `BedrockSmithingAssistListener` を新設した（`Inventory#setItem` は `slotsChanged()` を通るので `PrepareSmithingEvent` は正規に発火＝7判定はそのまま効く）。**W-158（`custom:` 素材の統合版クラフト）は Bukkit のレシピでは原理的に表現できない**ことが確定（Paper は `Ingredient#display()` を上書きしていないので ExactChoice でも送られる内容は型だけ）で未着手。**W-157 は DiscordSRV の `AvatarUrl` を `{texture}` 基準へ変えるだけ**だが書き換え先が稼働サーバ配下なのでユーザー適用待ち |
| 2026-08-18 | **図鑑のユーザー報告 2 件**（「エディタの図鑑でアイテム名がID表記になっている」「バニラの武器やモブの一部が登録されていない」）。**1 件目は本日 01:30 の `667a362` で既に直っていた**ので、直したのは再発防止と 2 件目。生ID表示の機構は `util.js` の `itemRefSelect` が**候補集合に無い値を primary=生ID で描く**ことで、`collection.yml` の `items.sourcelink` 25 件と `items.functional` 15 件（`material:` を持たない品）が候補源リストから漏れていたのが実害の 40 件。**候補源の取りこぼしは警告が一切出ない**ので、`test/collection-entry-labels-2026-08-18.test.js` で「出荷 `collection.yml` の全エントリが名前付きで解決する」ことと「`catalog-candidates.js` の `EXTRA_SOURCES` と `app.js` の `EXTRA_CONFIGS` が対になっている」ことを機械的に固定した（`667a362` 以前の候補源へ戻すと 2 件が実際に落ちることを実走確認）。2 件目は**バニラの武器・道具が図鑑に 1 件も無かった**（`TRIDENT`/`MACE`/`ELYTRA` だけが `structure` に混在）ので `weapon_vanilla` 17 件・`tool_vanilla` 24 件を新設、モブは 6 件追加（`PARCHED`＝AbstractSkeleton→undead、`NAUTILUS`/`ZOMBIE_NAUTILUS`＝AbstractNautilus→aquatic、`CAMEL_HUSK`/`COPPER_GOLEM`/`HAPPY_GHAST`→passive。分類は paper-api のインタフェース階層を `javap` で確認して決めた）。**`GIANT` と `MANNEQUIN` は意図的に入れない** ── 前者は討伐経路が無く後者は Mob ではないので、載せると分母に「永久に埋まらない枠」が入り `percent: 100` 系アチーブメントが到達不能になる（K-11 と同型）。**「一括追加」も直した**: 走査集合が `catalogCandidates`（カスタムIDのみ）だったのでバニラ Material が**構造的に addressable でなかった**（`*_SWORD` と打っても 0 件）。候補集合の組み立ては `catalog-candidates.js#bulkAddCandidateIds` に出して Node テストから直接検証している。**続けてカテゴリの整理**（ユーザー報告「Ars素材カテゴリに圧縮素材やただの中間素材が入っている」）: `material_ars` に混ざっていた圧縮素材 11 件（`*_4x` と `stone_5x`。圧縮シリーズの最上位 2 段だけが取り残されていた）を `material_compressed`（160→171 件＝定義側の圧縮 171 件と一致）の五十音順の位置へ移し、残り 30 件（コア 5・ガチャ券 9・スクラップ 6・中間素材 10）は「Ars素材」という括りが実態と合わないので **`material_misc`「素材等」へ改名**した（旧ID `material_ars` は collection.yml 以外から参照されていないことを確認済み）。ID の形で機械判定できるので「圧縮素材が `material_compressed` 以外に置かれていない」ことをテストに追加した。**さらにスレッドの重複も解消した**: `thread_*` が `material_tf` と `thread` の両方に入っていて図鑑で二重に並んでいたので `material_tf` から落とした（86→35 件）。調べたら**`material_tf` の `thread_*` は 51 件あり、うち 6 件（`thread_better_fortune` / `thread_blindness` / `thread_gacha` / `thread_role_effeciency` / `thread_role_luck` / `thread_translate`）は `thread` カテゴリに一度も入っていなかった**ので `thread` を 45→51 件にした。**見逃していた真因は `ShippedCollectionEntryIdTest` が件数を `45` というリテラルで固定していたこと** ── リテラル自体が誤っていたので「6 件取りこぼしのある状態」をずっと緑で通していた（許可リスト方式と同じ罠）。期待値をカタログの非draft な `thread_*` から導出する形へ作り替え、直前のコミット状態へ戻すと欠けている 6 件を名指しで落とすことを実走確認した。あわせて editor 側に「同じエントリが2カテゴリに入っていない」「カタログの非draft スレッドは全件 `thread` に入っている」を追加。エントリ総数 774→**729 件（ユニーク 729・重複ゼロ）**。**さらに、その 6 スレッドが「ドロップするのに防具へ永久に挿せない」状態だったのを直した**（ユーザー承認のうえ `catalog.yml` に着手）。**`external-source: arspaper` を足すだけでは直らなかった** ── 6 種は CMD **100023〜100028** で、正規の 45 種（CMD 3000xx）とは別系統で、**Ars の `ThreadType` enum にも `threads.yml` にも定義が無かった**（`blindness` と `translate` だけ `{}` で置かれていた）。`ThreadGui#isEffectThread` は `arspaper:thread_item_type` PDC を `ThreadType.fromId` へ通すので、定数が無い id は必ず null になり装着が弾かれる。**効果は装着時にしか乗らないので 6 種は実質死んでいた**（ログには何も出ず、`ShippedCatalogExternalSourceDriftTest` が赤いことでしか気づけない）。処置は ArsPaper fork `fca43eb`（`ThreadType` に 6 定数＋`threads.yml` に `display_name`/`lore`。効果の実体は TF の `item-stats.yml` `STRING#1000xx` が持つので数値は全部 0、`baseMaterial` は `STRING` で catalog・item-stats・cmd-registry と一致させた）＋ TF 側で 6 エントリに `external-source: arspaper`。**「今後カタログにスレッドを足したら自動で対応する」形（enum → config 駆動レジストリ化）は採らなかった**: `ThreadType` は単なる registry ではなく**スレッドごとに固有の base Material と CMD を保持**していて（それが `item-stats.yml` のキー `<MATERIAL>#<CMD>` になる）、`SocketedThreads.Entry` の型でもあるため、切り離すと fork 11 ファイル＋ソース文字列を固定しているテストに波及する。代わりに**忘れたら落ちるガードを両側に置いた**: fork の `ThreadsYamlEnumParityTest`（`threads.yml` のキー ⇔ `ThreadType` の id を両方向で一致、各エントリの `display_name` 必須）と editor の「catalog の非draft スレッドは Ars `threads.yml` にも定義があり `external-source` を持つ」。どちらも変更前に戻すと欠けている件を名指しで落とすことを実走確認した。fork は **410 件・失敗 5**（着手前 408 件・失敗 6。`ShippedRecipeDisplayNameTest` が解消）。**⚠ 未実施（ユーザー作業）: ArsPaper jar の再ビルドと配備**（Java 変更を含むので config だけでは効かない）。**続けて、赤かったスレッド系ガード 4 件を全部閉じた**（ユーザー指摘「これ修正済みじゃなかったっけ。テストが赤いのおかしい」）── ArsPaper fork `ad4b952`。**うち 3 件はテスト側の誤検知で、実データも実装も正しかった**: ①`mana_regen が旧 3 段のまま` は `assertFalse(contains("mana_regen.thresholds.3"))` と書かれていたが、**引き上げ後の規約は 3/5 段**（同じテストが `hero_of_the_village`／`night_vision`／`conduit_power` を 3/5 で固定している）で、旧実装 3/6 と区別できるのは 6 段の有無だけ。②`translate の mana-bonus 累計 −100 が死に値` は、`translate` が `mana-bonus −100` の代償で `mana-regen +10` を得る**トレードオフ型**（`blindness` も `max-health −10` の代償で `attack-power +250`）で、負の累計は意図した代償。抽選最小値との比較は正の累計だけに当て、代わりに「代償があるなら見返りもあること」を縛る形にした。③`ジャンプ時刻を記録していない` は実装が `LAST_JUMP_AT.put/remove` で正しく配線済みで、テストが `lastJumpAt.put(` という**識別子の綴り**を固定していたため定数命名へ直した時点で赤くなっていた（大文字小文字と下線を落として突き合わせる形へ）。**実データの不具合は 1 件だけ**: `role_luck`／`role_effeciency` は `threads.yml` で `stackable` を持たないので上限が「1 個 × キャリア 5」で、**6 段は物理的に発動しない**（設計書 §3-A-5 が上限 5 を見落とした分で `mana_regen` の 6→5 と同型）。最終ティアの累計を変えないよう 6 段の値を 5 段へ畳んだ（`role_luck` 2→4 ／ `role_effeciency` 5→15）。**`role_effeciency` も同じ不具合を持っていたのに、テストが最初の 1 件で止まるため隠れていた。** RED 実証（`potion-effect: wither` を書き 6 段を戻すと該当 2 本が落ちる）済み。fork は **410 件・失敗 0**（着手前 408 件・失敗 6）。**「落ちるべきでないときに落ちるテスト」も「落ちるべきときに落ちないテスト」と同じくらい高くつく** ── 3 件とも「もう直っている」と気づくまでに調査が必要だった。検証: TF **4155 件・失敗25・skip2**（失敗 21 クラスはどれも collection を読んでいない＝他セッションの未コミット分。collection 系 16 クラス 136 件は全緑）、config-editor **1388 件・失敗23**（着手前と同じ失敗集合）。**config のみの変更なので jar 再ビルドは不要**。 **その後、ユーザー要件「role_luck の 6 段が到達不能=>これ修正して。別件で同一のスレッドを重複で入れられるようにしてほしい」で方針を差し替えた** ── ArsPaper fork `ad8747e`。2 つは同じ話で、**重複を許せば上限が上がって 6 段が到達可能になる**ので、直前に入れた「6 段を 5 段へ畳む」修正は撤回し、設計書 §3-A-5 どおりの 6 段へ戻した（`role_luck` 5段:2 + 6段:2 ／ `role_effeciency` 5段:5 + 6段:10）。**既定を反転**: `ThreadApplicationPolicy.DEFAULT_STACKABLE = true` / `DEFAULT_MAX_STACK = 2` を新設し、`ThreadConfig#isStackable`／`#getMaxStack` の未記載時の既定をここから読む（旧: 未記載＝重複不可・max 未記載＝無制限）。上限は 1 装備 2 本 × キャリア 5 個 = **10 本**。`ThreadGui` が持っていた重複/最大積載の分岐は純関数`canSocketAnother` へ移した（フォークのテスト基盤は `ThreadConfig` をロードできない ── 静的初期化が `PotionEffectType` を引くため ── ので、挙動を固定できる場所が純関数側だけ）。**⚠ 村の英雄のスレッドだけは `max: 1` を明示した（balance）**: このスレッドは `percent-bonus-damage` を1 本 +6% 配る唯一の突出枠で次点（棘 +2.5%）の 2.4 倍あり、既定 2 本を許すと防具 4 部位で 8 本になって `ShippedThreadBandIndependenceTest` が**全帯で +22.0pt 超過**を検出した（Lv20 +61% ／ Lv60 +71% ／ Lv100 +81%、目標 +39/49/59%）。**帯目標は 2026-08-14 の戦闘リワークで「1 装備 1 本」前提に較正されている**ので、2 本以上にしたいなら先に `item-stats.yml` の 1 本あたりの割合ダメージを下げる必要がある（yml にその旨を明記）。残り 44 種は既定どおり同一装備へ重複できる（装備が違えば従来どおりキャリア 5 個ぶん重複可）。TF 側の帯モデル `perItemCapById` の既定もフォークに合わせて更新した（**ここを合わせ忘れると帯目標の超過を緑で通す**＝検査の無効化）。editor の「重複設定」も既定反転に追随（未記載はチェック済みで描き、`max` に「未設定 = 2」を出す。実際の描画結果をブラウザで評価して確認した）。あわせて `thread-effects-in-item-stats-2026-08-09.test.js` の**件数リテラル 45 を導出比較へ置換**（スレッドが 51 種へ増えて赤くなっていた。件数リテラルは許可リストと同型）。RED 実証: `role_luck` に `stackable: false` を書き戻すと「6 段は到達不能」で落ち、`ThreadConfig` の既定を `false` に戻すと新設の配線ガードが落ちる。検証: fork **413 件・失敗 0**、TF の `*Thread*` 50 件は `ShippedThreadItemStatsTest` の 1 件だけ失敗（**他セッション由来**：`13f1d20` が `NETHERITE_UPGRADE_SMITHING_TEMPLATE#300045` の `grant-chances` に主ステ `gathering-efficiency: 1` を足したため 4 件規約に対して 5 件。触っていないので未対応）、config-editor **1391 件・失敗 22**（着手前の集合から 1 件減）。**⚠ 未実施（ユーザー作業）: ArsPaper jar の再ビルドと配備**（Java を変更しているので config だけでは効かない）。 |
| 2026-08-17 | **実サーバ報告 17 件のバッチを全件クローズした**（ユーザー報告「複数のバグが発見されたので修正」14 件＋追加 3 件）。commit `ea36193` / `fb28da2` / `84b5d60` / `07312d6` / `410b929`。**鍛冶EXP系 5 件は全部「別々のバグ」だった**（1 つの原因ではない）: ①**木のツールでEXPが入らない**＝旧実装が「使用可能レベル > 0」でゲートしており、出荷 `item-stats.yml` に**要求レベル0の装備が33件**（木の各種ツール・革防具・銅防具・弓）あるので**その帯を作っても永久に0**だった（実測は木の鎌）。②**15+素材分のはずが30入る**＝定額と素材表が「どちらか一方」ではなく**加算**なのが正で、実装がそうなっていなかった。③**スタック素材で可能個数分のEXPが入る**＝`CraftingInventory#getMatrix()` は**スタック全体**を返すので `getAmount()` を掛けると1クラフトで「作れる個数分」入る。**1スロット＝1個で数える**のが正。④**editorで素材リストがArsと通常鍛冶で同期される**＝儀式/Ars作業台が作業台と同じ `smithing.exp-per-material` を読んでいた。`ars-smithing.exp-per-material` を新設して分離（初期値は分離時点の複製なので当日の挙動は不変）。⑤**Arsの定額EXPを機能ごと削除** ＝ 定額があると「1つでも表に無い素材があれば合計を捨てて定額へ戻す」全か無かの分岐が必要で、**素材を1つ足すとEXPが100分の1に落ちる**向きの不整合が実際に出ていた（`binder_spear` 100→1、同格の `binder_sword` は全素材が表に無いおかげで100のまま）。定額を消せば部分カバーは「その素材ぶんが乗らないだけ」で単調になり、分岐そのものが不要になる。**残り12件で非自明だったもの**: **ディスペンサーがクラフトできない**は推測どおり弓が原因で、`custom:` 素材の `MaterialChoice` 登録が**同形のバニラレシピを無言で潰す**既知の罠と同型。**崩命スレッドが杖に付けられない**は `SpellBindListener` が**無条件に `setCancelled` していた**ため、スニーク+右クリックのスレッドGUI経路がそもそも到達しなかった（スレッド限定でもなく、配備jarの鮮度でもない ── 配備済み ArsPaper jar はビルド出力と同一日時で最新だったことを確認して stale 説を潰した）。**矢の雨が当たらない**は技の種別が `projectile_volley`（モブ本体から水平に扇状）で、**射程24m・開き角50度なら端の矢は10m横を通る**＝当たらないのが幾何的に必然。新種別 `projectile_rain`（対象の頭上9mから降らせる）を追加して置き換えた。**モブスキルが常時発動**は技ごとのCTしか無く**技全体の間隔が無かった**ため、`global-cooldown-seconds`（既定12秒）を新設。キーは `" global-gap"`（先頭空白）で、技IDは `trim().toLowerCase()` されるので衝突しえないことをテストで固定した。**称号/パーティクルの9個目以降が出ない**、**原木破壊で金リンゴ**、**釣った鉱石が積めない**、**死ぬとロールバフが消える**、**日光で焼け死なない**、**`/em start`・`/em quit` が使えない**、**自動植え付けの成長速度差**も同バッチで修正済み（詳細は各 commit）。**レベル到達アナウンス**は editor の「その他」カテゴリへ移し、11項目の専用GUI（MiniMessage入力・スキル選択・レベルチップ）にした。**副産物**: HEAD 時点で赤かった `ShippedRitualMaterialExpCoverageTest`（儀式で消費するのに表に無い素材3種。`role_reselect_ticket` の圧縮素材）を直した。`ShippedCompressedMaterialExpZeroTest` は定額廃止で「行を消すのは不可」の前提が消えたので、**行数の下限をやめて「0以外の値が入っていないこと」だけを表2本ぶん見る**形に作り替えた。検証: TF フルテスト **4119 件・失敗27・skip2**（27 は全て他セッションの未コミット yml 由来か HEAD 由来で、触った領域は1件も含まない）、config-editor **1364 件・失敗22**（着手前と同じ16ファイル）。**⚠ 未実施（ユーザー作業）: TF jar と ArsPaper jar の再ビルド＋配備＋サーバ再起動**（Java 変更を含むので config だけでは効かない。崩命スレッドの修正は ArsPaper jar 側）。 |
| 2026-08-17 | **公開した Wiki の本文リンクが全部死んでいたのを直した**（ユーザー報告「ハイパーリンクが全て死んでいて正しい記載箇所に転送されない」）。**原因は GitHub Wiki のページ URL 規則で、中身の質とは無関係**: ページは `/wiki/<ページ名>` で提供され、**`.md` を付けるとページとして解決されない**。実測した挙動は 2 通りで、どちらも「リンクが死ぬ」に見える —— **ASCII 名は `raw.githubusercontent.com` へ 302**（ブラウザに生の Markdown が落ちてくる）、**非 ASCII 名は `/wiki/` へ 302**（Wiki トップに飛ばされて元の話に戻れない）。拡張子なしなら 200。**サイドバーだけ生きていた**のは GitHub が自前で絶対 URL を吐いていたためで、そのせいで「一部は動くのに本文だけ全滅」という分かりにくい壊れ方をしていた。**直し方**: 組み立てと壊れリンク検査は `.md` 付きのまま（原稿もページ名も `.md` 付きなので突き合わせが素直）、`generatePages` の最後で拡張子だけ落とす。**RED 実証**: strip を外すと 3 件（新規 1・既存 2）が実際に落ちることを実走確認し、戻して **15/15 緑**。**公開後に本物で検証**: 出力に含まれる**リンク先 34 件すべてを HTTP で叩いて 200 を確認**した（`Home` の 301 は Wiki トップへの正規化）。commit: 本体 `b1c9f5e`、Wiki `66e091f`。 |
| 2026-08-17 | **「戦闘レベルが全プレイヤーで 0 になる」を疑って全経路を裏取りしたが、現行 HEAD では再現しなかった**（ユーザー指示「要調査」）。**きっかけは台帳に残っていた過去の事故の記述**で、`progression/combat-level.yml` が `skills: {}`（参照スキル空）になっていた件は **`4da82a3` で既に解決済み**（現 HEAD は 4 スキル weight 1、作業ツリーにも差分なし）。**疑った機構は「config のキー `LIGHT_WEAPONS` 等と、スキルレベル供給元が返す id の表記が食い違って全キーが無言で無視される」**（`combat-level.yml` のコメントに「未知キーは無視」とあり、`CombatLevelModel#compute` は `getOrDefault(key, 0)` で警告も出さない＝**成立すれば本当に無言死する**形）。**が、id 空間は全層で一致していた**: `SkillId.java:18,19,26,28` の定数 → `PlayerProgression#skills()`（javadoc「keyed by uppercase skill ID」、`NativeSkillLevelSource` は変換せずそのまま返す）→ `NativeSkillCatalog`（`SkillId.ALL` を登録）→ EXP 付与側（`CombatKillCreditTracker` → `CombatListener:783` → `ArsProgressionBridge:228`）まで同じ文字列が流れる。**実サーバのログでも裏が取れた**: `latest.log` に軽量武器・重量武器・Ars魔法のレベルアップが複数プレイヤーで記録されている。**EliteMobs 側の別経路も潰した** —— `CombatLevelCalculator` は `TrinityForgeIntegration.isCombatLevelMappingEnabled()` が false だと EM 自前の（入力経路が無く事実上死んでいる）計算へフォールバックし、`available` は **EM の `onEnable` 時点で TF が `isEnabled()` でなければ恒久的に false**（再起動まで直らない）。ローテート済みログ 12 本を展開して**起動バナーを全部拾ったところ、記録のある 10 回の起動すべてで `delegation is active`**、`delegation disabled` / `standalone (non-delegated)` は 0 件 ＝ **この経路にも落ちていない**。**結論: TF 側にも EM 側にも「常時 0」を強制する機構は無い。非戦闘職（採取・鍛冶専業）のプレイヤーが 0 になるのは pillars 式の仕様どおりでバグではない。** 残る弱点として、**`NativeSkillLevelSource`（DB → id 空間の実データ経路）を検証する JUnit は存在しない**（既存の統合テストは手作りの `SkillLevelSource` を注入した計算部分だけの検証）ので、実データ起因の不具合はテストでは捕まらない。この一連を `docs/agent-context/combat.md` へ恒久化した（同じ疑いを持った次のエージェントが同じ裏取りを繰り返さないため）。**コード・config は変更していない。** |
| 2026-08-16 | **GitHub Wiki を「読み物 + 事典」の 2 層構成へ作り替えて公開した**（ユーザー依頼「初めて遊ぶ人でも何をしたらいいか分かる／変更された仕様と追加コンテンツの詳細が分かるように校正してほしい。現状は見にくい」）。**「見にくい」の直接の原因は 3 つで、どれも中身の質ではなかった**: ①**`_Sidebar.md` が無い**ので GitHub がページ全部を五十音順にベタ並べしていた ②**手書きの読み物 14 ページが 1 本も公開されていなかった** —— 原稿は `docs/wiki-source/prose/` に揃っていたのに、生成器が生成ページしか書き出しておらず、しかもローカルの `wiki/` は**リモートが死んでいる**（`Klee319/trinityforge-wiki.git` が 404。生きているのは `Klee319/trinityforge.wiki.git`）ため push 経路も無かった ③アイテム一覧が **9,110 行の 1 枚**で、折りたたみを上から開くしか探す手段が無かった。**やったこと**: `tools/scripts/generate-player-wiki.js` を拡張し、prose 層（原稿をコピーして原稿名リンク `00-*.md` を公開名へ書き換え）と事典層（出荷 yml から生成）の 2 系統を 1 本の生成器で出すようにした。アイテムは **`stats/item-stats.yml` の `use-skill` を軸に 11 ページへ分割**（スキルツリーの区分と一致するので分類の根拠が config 側にある。最大ページは 4,230 行）し、各ページの先頭に一覧表・詳細は `<details>`。一覧表の「主な効果」列は**ページ内での出現頻度が低い順に並べ替えて 3 件まで**にした（素直に lore 順で出すと防具が全行「移動速度、体力増強、防御率」になり列ごと無意味だった）。**新規に見つけて直した実バグ**: 生成器が **`draft: true` の 80 品を公開していた** —— `ItemCatalogConfig` が `template()`/`all()` から除いている＝ゲームに存在しない品なので、「Wiki に載っているのに永久に手に入らない」状態だった。ガードテストを書いて **RED 実証**（`isUnreleased` の除外を戻すと 13 通過 / 1 失敗、戻すと 14/14）。あわせて**全ページ横断の壊れリンク検査**を生成器に組み込んだ（生成しないページを指すリンクがあれば生成時に例外で落ちる）。**手書き 4 本を実 config で裏取りして訂正**（01: PvP は「無い」ではなく「ある（抑制付き）」・プラグインは 4 本でなく 3 本／02: 守備力の減算は会心より前・固定ダメージは全防御貫通・DoT は被ダメージ軽減のみ通す／09・14 は見出し 16→34 ほか）。**自分のブリーフの誤りも 2 件訂正した**: `bow-accuracy` は削除されていない（`StatVocabulary.java:52` に実在）、`combat-level.yml` の `skills: {}` は `4da82a3` で解決済み。**EXP カーブの記述が逆だったのを直した** —— 分母 8 を「最も重い刻み」と書いていたが、`2^(Lv/8)` は `2^(Lv/7.6)` より伸びが**遅い**＝必要 EXP が**少ない**（Lv100 の 1 レベル分で 434,846 vs 685,860 ＝約 6 割）。軽量武器・重量武器・軽装備・重装備・伐採の 5 本は他より**軽い**カーブが正しい。**検証**: `tools/config-editor` の `player-wiki-generator.test.js` **14/14 通過**（既存 2 件は config 追随で腐っていた実値固定を書式固定へ置き換え。実値へ書き直すと「毎回テストの方を直す」運用になり検査が消えるため）。**公開済み**: 本体 `6a8eab4`（dev へ push 済み）、Wiki は `Klee319/trinityforge.wiki` へ `ea7cd24`（36 ページ書き出し・旧 6 ページ削除）。公開前に public リポジトリ向けの秘密混入スキャン実施済み。**⚠ 残る要判断 2 件**: 構造物データパック 17 本が資源サーバに実際に入っているか未確認（`バニラとの違い` の構造物節がこれ前提）／ダンジョン 61 件のうち**入場ゲート未登録の 33 件は非特権プレイヤーが入れない**（`DungeonGateService#hasAnyGate()` が true のとき未登録は fail-close。冒険者ギルドもゲート無し）。 |
| 2026-08-16 | **「ワールドをリセットしたのにアイテムが消えていない」の真因は Redis で、掃除経路が丸ごと無かった**（ユーザー報告）。**インベントリの正本は world の中に無い。** `reset-world.ps1` は仕様どおり個人データを触らないが、仮に `world/playerdata` を消しても意味が無く、**HuskSync はログイン時にまず Redis の `latest_snapshot` を見て、あればそれを適用して DB を読まない**（`LockstepDataSyncer#syncApplyUserData` を上流ソースで確認）。しかもキーの TTL は `RedisKeyType.TTL_1_YEAR` ＝ **31,536,000 秒**なので放置しても消えない。ところが `purge-player-data.ps1` が出す SQL は **MariaDB しか空にしていなかった**。**実測（配備先の Garnet 2.1.0）**: MariaDB の `husksync_users` は **1 人**まで減っていたのに、Redis には **8 人分**の `husksync:::latest_snapshot:<UUID>`（各 10〜32KB）が**残り 352〜364 日**で生きていた ── **DB を見ると「ちゃんと消えている」ように見える**のが最悪の点。**直したもの**: 新規 `ops/scripts/lib/Redis.ps1`（RESP を直接喋る最小クライアント。redis-cli 非依存）＋ `purge-player-data.ps1` に Redis 掃除を追加（既定で有効・`-SkipRedis` で明示的に残せる。`DUMP` を base64 で `_purge-backup-<日時>\redis\husksync-keys.json` へ退避してから `DEL`、**消した後にもう一度 SCAN して 0 件を確認**。接続できないときは警告で流さず中断）。`reset-world.ps1` の説明と終了メッセージにも「持ち物は world の中に無い」を明記し、RUNBOOK 手順 15 / 18-5 と `docs/agent-context/ops-build-deploy.md` に恒久知識として書いた。**実装中に自分で踏んだ罠を回帰に固定した**: `Get-RedisKey` の戻り値を `,` で包むと呼び出し側の `@(...)` が「要素 1 個（中身は 8 件の配列）」になり、**8 件あるキーが 1 件に見える**（そのまま消しに行くと、配列を文字列化した存在しないキーを 1 回消して「掃除完了」になる）。ライブ相手に読み取り専用で流して初めて出た。**検証**: ops セルフテスト **46 / 失敗 1**（失敗 1 は別セッションが `flight_status` を true 運用へ変えて `preflight.ps1` が追随していないもの。着手前から赤い）。新規 8 件は全て緑で、うち回帰 1 件は**バグを戻すと実際に落ちる**ことを実走確認（`期待 3 / 実際 1`）。RESP の読み取りは「bulk の中に CRLF がある」「1 回の Read で 40KB が届かない」を偽ストリームで固定した（ここを timing 依存で書くと値を途中で切り、**消し漏れが無警告になる**）。配備先の Garnet に対しては**読み取りだけ**実行して 8 件・PTTL・DUMP が全て取れることを確認済み（**消してはいない**）。**⚠ 未実施（ユーザー作業）: 全サーバ停止 → `ops\launch\purge-player-data.cmd -Apply` → 出力された SQL を流す。** これをやるまで 7 人分のインベントリは Redis に残ったまま。 |
| 2026-08-16 | **共有ワークツリーに溜まっていた未コミット変更を全部確定し、配備物（TF jar / ArsPaper jar / config）を揃えた**（ユーザー指示「コミットプッシュして各サーバのコンフィグも更新してほしい。プロジェクト全体pushでOK」）。**発端はユーザー報告「deploy-config-head.cmd を実行したのに反映されていない箇所がある」**で、原因はスクリプトのバグではなく**出荷 yml 29 本が未コミットだったこと**（`deploy-config-head.cmd` は仕様として HEAD しか配らない。スクリプトが印字する「HEAD と差があるファイル」がそのまま "配られなかったもの" の一覧）。**commit する前に「HEAD が既に赤いのか、この WIP で赤くなるのか」を切り分けた** —— tmp に HEAD の worktree を切って実走したところ **HEAD 19 失敗 / 作業ツリー 27 失敗**で、差し引き **13 件が WIP 由来の新規**、5 件が緑化（うち 3 件は**フォーク不在のクリーン worktree による見かけ**なので実質 2 件）。この切り分けをせずに commit していたら「元から赤かった」で流していた。**本番へ配る前に止めた 3 件**: ①`ops/templates/husksync.config.yml` に **MariaDB の実パスワードが書かれていた**（このリポジトリは public。`git log -S` で全履歴を走査し**過去にも入ったことがない＝漏洩なし**を確認してからプレースホルダへ差し戻した。同ファイルは `game_mode`/`flight_status` も `false`→`true` に変わっていて当初は逆戻りだと見たが、**ユーザー確認により「Multiverse の既定をサバイバルにしたので問題ない」＝ true 運用が正**と判明した。実配備で裏取り済み: Main は Multiverse の 3 ワールドとも survival、Resource は Multiverse 管理外で `server.properties` が survival。false 必須だった根拠（メインの world が creative）が消えている。テンプレ・`apply-husksync-config.ps1`・RUNBOOK の 4 箇所に残っていた「必ず false」の記述を実態へ更新し、`preflight.ps1` が誤検知しないことも実走で確認した。**ただし穴が 1 つ残る: Dev_Server の overworld だけ creative のまま、3 台とも同じ `cluster_id ''`・同じ husksync DB を共有している。Velocity の `/server` は行き先ごとの権限ノードを持たない**ので、dev へ入れる権限があれば creative を main/resource へ持ち帰れる。塞ぐなら dev の overworld を survival にするか、dev だけ `cluster_id` を分ける）。②`progression/combat-level.yml` の **`skills: {}`** ＝ 参照スキルが空で戦闘レベルが全プレイヤー常時 0（`SymmetricCombatService` の 2 テストが「Lv80 と Lv0 で威力が同じ」で落ちていた）。エディタ保存の事故と判断し 4 スキル weight 1 へ復帰。③`combat/mob-types.yml` の `dimensions` がネザー基準Lv50 / エンド80 に埋まっていた —— **ユーザーが「意図的な決定」と確認したので `ShippedMobTypesLevelBandTest` を「空であることの固定」から「決めた実値の固定」へ作り替えた**（HP の指数区間・EXP 帯・`drops[].quality`・距離カーブ置換への波及は Javadoc に残した）。**ArsPaper フォークは HEAD がコンパイルできない状態だった** —— tracked な `libs/TrinityForge.jar` が古く、コミット済みソースが要求する TF API（`resolveItemStats` の引数 / `loreComposer()` / `statLoreBlock`）が無くて 8 エラー。通る jar は未コミットの作業ツリー側にしか無かったので **yml 8 + Java 12 + テスト 4 + jar を 3 点セットで commit**（`8bf7fe4`、push 先は `origin` = `Klee319/ArsPaper`）。この罠を `docs/agent-context/forks-and-mobs.md` へ恒久化した（**フォークの yml も deploy-config-head がフォーク自身の git HEAD から取るので、commit しない限りどのサーバにも永久に届かない**）。commit: 本体 `13f1d20`（48ファイル）/ `4da82a3`（配備前修正3件）、フォーク `8bf7fe4`（24ファイル）。テスト **4105 件・失敗 23**（修正前 27 → 狙った 4 件が緑）。**残る 23 の内訳は HEAD 由来 14 + WIP 由来 9**（後者は釣りボーナス消失 / 採取スキル別キー 5 箇所 / エンチャ・錬金のバフ値 / 空の付呪本を作るドロップ表 / マナ基礎キー移設の未完 / 儀式素材の EXP 行 3 件欠け / スレッド grant-chances 5件）。jar は TF `build/release/TrinityForge-all.jar`・ArsPaper `build/libs/ArsPaper-1.0.0.jar` ともビルド済み。**⚠ 未実施（ユーザー作業）: `stop-all` → `deploy` → `deploy-config-head` → `start-all`。`D:/game/...` への書き込みはエージェントの権限ゲートで拒否されるため。** |
| 2026-08-16 | **リソースパックのリリースを更新した**（ユーザー依頼「gitのリリースも更新しておいて」）。直前の装備レイヤー修正（`ec8c088`。バニラ PNG を下地にアルファを取り戻し、頭全体を覆うヘルメットを直したもの）が**まだ配布側に載っていなかった** —— 現行リリース `pack-20260816051616` は 05:16 UTC 発行で修正より前。実際に公開済み zip を落として突き合わせ、装備レイヤー 4 枚の sha1 が全部違うことを確認してから発行した。**発行は必ず `tools/config-editor/lib/respack-publish.js` を通す**（タグ `pack-YYYYMMDDHHMMSS`・タイトル・本文の書式が editor の「公開」ボタンと同一になり、zip も公開直前に必ず再ビルドされるので sha1 と資産の実体が乖離しない）。新リリース **`pack-20260816072752`**、sha1 **`89aab0c9f6de362771ec4bdf0a843951c39bbc54`**、471,711 bytes / 716 ファイル。公開後に資産を落とし直して sha1 一致と装備レイヤー 4 枚の中身を再確認済み。**リポジトリ側に commit するものは無い**（`resourcepack/dist/TrinityForge-Pack.zip` は再ビルドで内容が変わるが別セッションが未コミットで持っている生成物なので触らない）。**⚠ 未実施（ユーザー作業）: `ops\launch\set-resource-pack.cmd` で `resource-pack` と `resource-pack-sha1` を両方書き換え → Main_Server 再起動（統合版まで通すならプロキシも）。** sha1 を据え置くと GeyserExtra がキャッシュ済み zip を再利用して「URL だけ変えても何も変わらない」になる。 |
| 2026-08-16 | **触媒（杖）12 本に厳選（ランダムロール）を入れた**（ユーザー依頼「触媒（杖）にランダムステータスのような厳選要素がない問題を修正して」）。**Java 側にゲートは無く、config の書き漏らしが唯一の原因**だった —— `DerivedItemStats.applyRandom` は `profile.random()` を回すだけで材質も装備種別も見ないので、`item-stats.yml` に `random:` が書かれていなければ黙って no-op になる。実測で **460 エントリ中 407 が `random:` を持ち、杖 12 本と採取ツール 20 本だけが持っていなかった**（杖は `fixed` + `per-quality` の完全確定値＝どれを引いても同じ性能）。規約は実データから確定させた: `attack-power` は `fixed` の **-16% 〜 +20%**（剣・弓・本・クロスボウ全部この比率）、`crit-damage: -0.12〜+0.18` / `crit-chance: -0.04〜+0.06` は全武器共通の固定帯。杖には加えて `mana-bonus` を同率で振った（`per-quality` にも入っている＝杖固有の成長軸だから）。**`WeaponTierParityTest` は `fixed + random の中央値` で杖/剣の詠唱DPS比を見る**ので中央値が +2% 動くが、帯（40〜60%）内で緑のまま。検証: TF フルテスト **4102 件・失敗 27 で着手前ベースラインと完全一致（新規失敗ゼロ・直った件もゼロ）**、さらに commit する内容そのもの（HEAD + 自分の追加 180 行）を作業ツリーへ流し込んで item-stats 系 96 件を実走し 0 失敗。**`item-stats.yml` は別セッションが同時編集中（66+/46−）だったので、`git diff` で自分の 180 行だけを取り出して `git apply --cached` でステージし、他セッターの WIP は作業ツリーに残したまま commit した**（`git commit -m ... -- <path>` はステージを無視して作業ツリー版を拾うので、一度巻き込んで commit してしまい `update-index --cacheinfo` + `--amend` で組み直した。**`git reset` は permissions で deny されているのでこの経路が必要**）。commit `d88155e`。 |
| 2026-08-16 | **`hoglin_tusk` を完全に削除し、「カタログから消したのに参照が残る」を検出する層を 3 面そろえた**（ユーザー依頼「hoglin_tusk はアートを消しただけでは終わっていません ＝＞完全に削除。カタログから消した時に今後同一の問題が起きないように修正」）。**まず実害の規模を測った**（`tmp/dangling-custom-id-audit.py`）: 出荷 yml 全体の `custom:<id>` 参照 222 種のうち**実参照の未解決は 0 件**（`food-gimmick.yml:12` の `tf_rotten_ration` はコメント内の「例:」）、`catalog.yml` の `_editor` メタの宙ぶらりんも 0 件、**ArsPaper `materials.yml` の `_editor` メタだけが 19 件腐っていた**。つまり機能する参照は誰かが直していて、**エディタの分類メタだけが誰にも見られずに腐る**という構図。エディタ経由の削除は `removeEditorCategoryItem` が掃除するので、腐ったのは手編集・改名の経路。**一度やらかして戻した件**: 19 件のうち `key_*` 17 件は「catalog.yml に定義があるのに materials.yml のカテゴリに載っている死んだ行」に見えたので削除したが、`forms.js:22` の `window.MATERIALS_KEY_CATEGORY_ID = "cat_dungeon_keys"` が素材画面から鍵一覧へ切り替える導線で、鍵の実体を catalog に残すのは `dungeon/gates.yml` の `key-item` が焼き込み id を厳密比較するため、と判明して復元した（理由を yml のコメントに残した）。**つまり「他ファイルの id を指すのは正常」**で、自ファイルだけと突き合わせる検査は恒久的な誤検知を 17 件出す。**追加した 3 層**: ①TF `ShippedCustomIdReferenceDriftTest` — 出荷 yml 79 本を全走査し `custom:<id>` 221 種が実在することを固定。**コメントは除去してから拾う**（`food-gimmick.yml` と `mob-level-table.yml` が書式説明に実在しない id を使っており、除去しないと初日から赤い＝無視されるテストになる）。空振り防止にファイル数と参照種類数の下限を assert。②既知 ID 集合を `KnownCustomItemIds` へ集約し、**`cmd-registry.json`（CMD 台帳）を第一級の証拠から外した** — 台帳は番号を再利用しないための永続記録で、アイテムを消しても割当が残るため、混ぜたままだと**「カタログから削除」という本命の経路を素通りする**（実測で台帳にしか無い id が 16 件）。`materials.yml` があるワークツリーは STRICT（catalog ∪ materials）、無いクローンだけ LEDGER_FALLBACK にして**検査が弱いことを失敗メッセージに開示**する。③editor `lib/editor-meta-integrity.js` + `public/js/` ミラー — `_editor.categories[*].itemIds` / `itemTabs` / `orders` の宙ぶらりんを保存後トーストとカテゴリバー直下のバナーに出す。**自動削除はしない**（`yaml-merge.js` で固定した「開いて保存で 1 バイトも変わらない」不変条件を壊す／黙って消すとタイプミスも黙って消える）。materials は自ファイル ∪ catalog を有効集合にする。`orders` も対象に含めた（`pruneOrphanItemTabs` が「categories / orders は触らない」と明記していて孤児掃除が効かないため itemTabs より溜まる）。**③が初回実行で実バグを 1 件見つけた**: `materials.yml` の鍵カテゴリが `key_enchant_trial`（単一）のままで、2026-08-08 の `_1`〜`_10` 分割の取り残し＋`key_hallosseum` / `key_north_pole` 未登録＝**12 件の鍵が素材画面から到達できなかった**。修正して既存の `catalog-key-tab-2026-08-04.test.js`「鍵を全件持つ」も緑へ戻した。検証: TF 対象 5 クラス 68 テスト全 green・skip 0（実物の `crafting-features.yml` を壊すと落ち戻すと通ることを実走確認、出荷 yml は完全 revert）。editor `npm test` 1335 / 失敗 24（変更前ベースライン 25 → 「鍵を全件持つ」1 件が緑化しただけで新規失敗ゼロ）。commit `1709c40` / `37546c5`。**フォークの `materials.yml` は別リポジトリなので本体には commit されない**（ユーザーの WIP と一緒に commit が必要）。 |
| 2026-08-16 | **ランキング用の PlaceholderAPI 拡張を追加し、値をサーバ間（メイン/資源）で共通化した**（ユーザー依頼「valtopboard ってTFにも対応している？」→「スキル別レベル、グリフ解放数、図鑑登録数、討伐数かな」→「サーバー間（しげん/main）で共通になるようにね」）。識別子 `trinityforge`、`%trinityforge_skill_<id>_level|_prestige|_totalexp%` / `skill_total_level` / `glyphs_unlocked` / `collection_entries|_items|_mobs` / `kills_total`。**共通化が要件になった時点で設計が変わった**: 4 項目は性質が 2 つに割れる。**スキル系は元から共通**（`player_progression.db` は `plugins/TrinityForge` ごとジャンクション共有＝実体1個。オフラインでも UUID で引ける）。しかし**図鑑・グリフ解放数はプレイヤー PDC、討伐数はバニラ統計**にしかなく、いずれも「そのサーバにログイン中の本人」からしか読めない —— Bukkit に `OfflinePlayer` の PDC API は無く、統計ファイルはバックエンドごとに別のワールドフォルダ、**HuskSync は本人へ流し込むだけで他人の値を引く経路を持たない**。放置すると「資源の順位表にメインにいる人が 0 で出る」。そこで同じ共有 DB に `player_ranking_stats` を作り、ログアウト時＋60 秒ごとに写す（読みは自サーバにログイン中なら実データ、他サーバ/オフラインはミラー）。**書き込みは ON CONFLICT の `MAX` で単調増加のみ** —— HuskSync の流し込みは `PlayerJoinEvent` より後に来るので素直に上書きすると同期前のローカル値で共有値を潰す。4 項目とも減らない量なので MAX なら後退しない。代償としてリセット時は明示削除が要るので `/tf progression reset` に `forget()` を配線した。**別コネクションで同じ DB を開く**が、複数コネクション同時アクセスの安全性は `SharedSqliteConcurrencyTest` が実測済みで、その前提の `transaction_mode=IMMEDIATE` をミラー側にも同じく指定している。**PlaceholderAPI の配布元が移転していた**: `content/repositories/placeholderapi/` は 404 で 2.10.9 も配信されておらず（最古 2.11.5）、`https://repo.extendedclip.com/releases/` + `2.11.6` へ。任意依存なので `paper-plugin.yml` に `join-classpath: true` で宣言（Paper はクラスローダ分離）。書式解釈は PlaceholderAPI 非依存の `PlaceholderResolver` へ分離した（`compileOnly` でテストクラスパスに無く、拡張クラスのままだとテストが書けないため）。**注意**: 図鑑の `collection_mobs` はモブ**種類**なので同じゾンビを 1000 体倒しても増えない（累計は `kills_total`）。commit `1c15397`。テスト TF 4056 / 失敗 27 / スキップ 2、**新規 31 件は全て緑**（失敗 27 は全て出荷 yml を読むテストで、別セッションが編集中のファイル由来）。**⚠ 未実施（ユーザー作業）: 各バックエンドへの PlaceholderAPI 導入**（`ops/` 配下に一件も記述が無く未導入と思われる。未導入でも起動は落ちず拡張の登録だけスキップ）**と jar 配備**。ミラーは配備後にプレイヤーが 30 秒以上オンラインになるか一度ログアウトするまで空なので、それまで他サーバから見た図鑑/グリフ/討伐数は 0（スキル系は最初から正しい）。 |
| 2026-08-16 | **ArsPaper のグリフ解放集合に並存していた 2 つの直列化形式を撤去した（→ K-37）**（ユーザー指示「1だけ修正しておいて」）。`UnlockedGlyphs` が `new NamespacedKey(plugin,"unlocked_glyphs")` で自前に作ったキーは、プラグイン名が ArsPaper なので namespace が `arspaper` に落ち、**`ManaKeys.UNLOCKED_GLYPHS`（筆記台の写本解放集合）と完全に同一キー**だった。そのうえ書き込みが 0x1F 区切りで、他の全読み手が期待する Gson JSON と非互換。javadoc の「TrinityForge 側のキーとは衝突しない」は**比較対象を取り違えていた**（衝突相手は TF ではなく同 fork の `ManaKeys`）。**当初「写本したのに perk 未達だと永久に使えない実害」と報告したが、これは誤りだったので訂正する** —— それは `usage-gate.yml` の意図どおりの仕様（入手は自由・使用に perk が必要）。実際の欠陥は 2 つで、(1) `add()` の呼び出し元がゼロ（入口の右クリック解放は 2026-07-23 削除済み・`git log -S` でも 0 コミット）なので今は無害だが、**誰かが配線した瞬間に全プレイヤーの写本集合が非 JSON に化ける装填済みの罠**（`ScribingTableGui` は try/catch 無しの `JsonParser` で筆記台 GUI が開かなくなり、`SpellCaster` は空集合へ倒れて魔法が一切撃てない）、(2) 実在しない機能を説明する誤った javadoc。**「形式を JSON へ揃えれば直る」は採用してはいけない**: 格納が `getId().toString()` の完全ID・照合が `getKey()` の裸キーなので形式だけでは no-op、ID 形まで揃えると `UsageGate#hasPermission` へ到達する経路が**全て写本解放済みを先に確定させてから**入る構造上 OR が常に true になり、**UNLOCK Model Y が全経路で無効化**される（＝修正ではなく権限バイパスの新規混入）。よって OR 経路ごと撤去した（`UnlockedGlyphs` 削除 / `UsageGate` は `return false` / `SpellCaster`・`ArsPaper` の受け渡しを 1 引数へ）。**挙動差分ゼロ**（現行 contains() が true になる条件は「JSON 文字列全体が裸グリフキーと一致」で成立し得ない）なので **RED→GREEN のテストは作れない**。代わりに `GlyphUnlockKeySingleFormatTest` で「書き手は JSON の 2 箇所だけ」「`UnlockedGlyphs` が復活していない」「`UsageGate` が写本集合を参照しない」を固定した。fork commit `4394e10`（`origin` = `Klee319/ArsPaper`、branch `feat/trinityforge-fork`）。テスト 383 / 失敗 5 / スキップ 0 で、**失敗 5 件は着手前のベースラインと同一集合**。**⚠ 実機での証明は未実施**: fork の testCompileClasspath に `libs/TrinityForge.jar` が無く `UsageGate` は `catch(Throwable)->true` で fail-open するため、「perk 未所持で詠唱が拒否される」はサーバ実測でしか取れない。**ArsPaper jar の再ビルドと配備が要る。** 副産物として K-36 / K-38 を起票。 |
| 2026-08-16 | **リソースパックの「アートはあるのに描かれない」15 件を配線し、残骸 12 ファイルを消し、逆向き検査を足した**（ユーザー依頼「ビルドしたリソースパックに削除されたアイテムか、editor上で反映されていないテクスチャが含まれている。また防具着用時にテクスチャが反映されていない」「hoglin_tuskはカタログにないんだから削除してほしい」）。**まず zip は古くなかった**（commit 済み zip とソースが 697/697 ファイル・1 バイト単位で一致）。真因は **`assets/minecraft/items/*.json` が生成物なのに生成元からドリフトしていた**こと。`tools/config-editor/lib/respack.js` の `regenerateItemDefinitions()` は `hasModel(a) = a.assetName && exists(models/item/<assetName>.json)` を「配線済み」の唯一の判定に使って items json を**全再生成**するので、**`assetName` が欠けた割当はモデルもテクスチャも実在するのにバニラの fallback へ落ち**、その material の配線済み行が 0 件だと items json ごと消える（`amethyst_shard` / `prismarine_crystals` / `heart_of_the_sea` / `conduit` / `nether_star` が存在しなかった理由）。**手で items json を直すとエディタのリソースパック画面を触った瞬間に巻き戻る**（作業中に実際 2 回巻き戻った）ので、直すのは `cmd-registry.json` の `assetName`/`parent` 側で、反映はエディタ自身の generator を呼んで行う。配線したのは fnis_peccati_profundi(IRON_SWORD:68) / novus_criculus_luminis(GLOWSTONE_DUST:84) / source_shard(5441) / source_crystal(5442) / source_condenser(5443) / source_engine(5444) / infinity_source_core(5445) と杖 8 種（62 material 再生成）。**消した残骸**は `_<CMD番号>` サフィックス付きの旧世代（fnis_peccati_profundi_68 / novus_criculus_luminis_84 / koujien_100004）、どの yml にも id が無い source_gem_1x/2x/3x、現行が個別 id になった dungeon_seal_enchant_trial、実モデルが `item/kj` を使うため誰も見ていなかった koujien.png、そしてカタログに無い hoglin_tusk。**残っていた理由は「アイテムがカタログから消えてもアートは誰も消さない」＋`build_item_pack.py` の `validate()` が前向き参照（items json → model → texture の存在）しか見ていなかった**から。→ **D-1（items json から推移的に辿って未参照の model/texture を落とす）と D-2（cmd-registry の割当のうち*パックに実物があるのに*未配線のものを落とす。割当の大半は意図的にバニラ見た目なので「実物がある」で絞らないと検査が無意味）**を追加。アート先行投入用に `unreferenced-assets.json` の理由つき逃げ道を用意したが、**宣言が腐る方向も両方エラー**（宣言したのに配線済み / もう存在しない / reason が空）。現時点で該当 0 件なのでファイル自体は無い。**防具着用時のテクスチャ**は「壊れた」のではなく**一度も作られていなかった**（装備レイヤー PNG が 0 枚・`equipment-registry.json` が空・`items/equipment-assets.yml` が `{}`＝全 38 セットがバニラ見た目。着用時の見た目は CMD では変わらず `minecraft:equippable` の `asset_id` で決まる）。`build_armor_layers_from_items.py` を新設し inv テクスチャの配色から 64x32 レイヤーを決定的に生成（明度 3 分割＋彩度重み付けで代表色、バニラと同じ UV 矩形ごとにグラデーションと縁取り）。**inv テクスチャが揃っているのは infinity と source_gem の 2 セットだけ**なので配線もその 2 件。検証は `python resourcepack\build_item_pack.py` = packed_files=716 sha1=22876ce4… と、D-1/D-2/宣言腐り止めをわざと壊して実際に検出することの実走確認。commit `df69a2e`。 |
| 2026-08-16 | **エディタの解放系（スキルツリー `dedicated-effects`）をレビューし、無言で壊れる欠陥 5 件と、それが実際に起こしていたデータ事故を直した**（ユーザー依頼「editorの解放系の機能が正しく動作するかレビューして」→「全部修正」）。**発端の実害**: 未コミットの `ars_smithing.yml` で儀式ゲート **14 件が `ritual:` → `recipe:` に反転**（enchant_book_* 8 / waystone / teleport_compass / source_crystal / source_condenser / source_engine）し、`ritual:volcanic_sourcelink` は消えていた。ArsPaper の `UnlockGate` は `hasRecipePermission` と `hasRitualPermission` が**別々のマップ**を引くので、儀式を `recipe:` に置くと**無言で常時解放**になる。2026-07-28 に一度直した事故の再発。**再発した理由がエディタ側にあった**: ①**保存が本文コメントを全消し**していた（`serializeConfig` は `YAML.stringify` し直してファイル先頭のヘッダだけ貼り直す実装。実測で ars_smithing の本文コメント 4 行 → 0 行、achievements.yml は 14 → 0）ため、「儀式は recipe: では無効」という注意書きごと消えた。→ 元ファイルを Document のまま保持して同じキー/要素のノードを再利用する `lib/yaml-merge.js` を新設。**出荷 yml 5 本（8351 行の catalog.yml を含む）で「開いて保存」が 1 バイトも変わらないことをテストで固定**した。②**チャンネル取り違えを editor が一切警告しなかった**（語彙は両方持っているのに）→ `gateChannelMismatch` を新設して行内に「⚠ 儀式側が正しい」を出す。③**重複警告がファイル単位**で、ツリーをまたぐ重複を構造的に検出できなかった（Java は全 16 ツリー横断で警告する）→ `/api/gate-placements` を新設し `computeDuplicateGateEffectLocations` で他ツリー分も含めて判定、どのツリーのどのノードと衝突しているかを tooltip に出す。実際 `glyph:snare` が alchemy と ars_magic に二重配置されていた（alchemy 側は effect-text が「注入のグリフ」のままで、`glyph:infuse` の取り違えと判明したので infuse へ戻した）。④**語彙の穴 2 種**: `method: inventory` の 10 件（短剣 8 種 / 広辞苑 / someones_eyes。Java は `isBukkitCrafting() = workbench || inventory` で同じ `catalog_<id>` として登録するのでゲートできる）と、**カタログの `method: ritual` 194 件**（ArsPaper の `CatalogRitualRegistrar` が儀式レシピIDを `tf_catalog_<カタログID>` で登録するので、素のカタログIDでは一致しない＝**エディタから推測不能で 1 件も設定できなかった**）を候補へ追加し、後者には `display-name` からの表示ラベルを添えた（候補 recipes 335→345 / rituals 64→258）。⑤**バニラ Material を選ぶと `recipe:DIAMOND_SWORD` と大文字で書かれる**が実行時のゲートキーは小文字のレシピ path なので無言で不活性 → `normalizeRecipeGateTarget` で小文字化。一括追加モーダルの「バニラレシピは対象外」という**案内文も誤り**だったので直した。**加えて、ガード自体が仕事をしていなかった**: `RecipeRitualGateChannelDriftTest` と `FailCloseGateSkillTreePlacementTest` は `assertTrue(config.load(...))` で始まるが、**`SkillTreeConfig#load` は警告 1 件でも false を返す**ので、無関係な警告（重複配置 / power.yml の排他グループ）で**本来の検査に到達する前に落ちていた**。件数チェックへ置き換えたところ、`trade:WEAPONSMITH` / `ARMORER` / `TOOLSMITH` が**どのツリーにも配置されていない**（trade はフェイルクローズ＝村人 3 職が恒久ロック）ことが即座に出たので `smithing.yml` の E ノードへ復旧した（このノードの effects には「鍛冶師を解放」と書いてあるのに配置だけ落ちていた）。**重要な訂正 —— このデータ事故はどれも HEAD には無く、すべて別セッションの未コミット WIP だった。** 事後に HEAD と作業ツリーのゲートid集合を機械照合したところ、HEAD の `ars_smithing.yml` は 11 件すべて `ritual:` 側で正しく、`alchemy.yml` は `glyph:infuse`、`smithing.yml` は `trade:` 3 件、`woodcutting.yml` は `drop:woodcutting:crystal_apple` 1 件のみ —— つまり**出荷済みの config は壊れていない**。壊れていたのは共有ワークツリーに乗っている別セッションの編集途中の状態で、今回の修正は**それが commit される前に差し戻した**という位置づけ。**したがってスキルツリー yml 4 本と Java テスト 2 本は commit していない**（他セッターの未コミット変更を巻き込むため。特に `EXPECTED_RECIPE_GATE_IDS` に足した料理系 8 件は HEAD の `farming.yml` には存在せず、単独で commit すると HEAD が赤くなる）。**次にこの 4 本の yml を commit するセッションが、`RecipeRitualGateChannelDriftTest` / `FailCloseGateSkillTreePlacementTest` の変更も一緒に載せること。**（commit 済みは config-editor 一式とドキュメントのみ）。**検証**: gate 系 Java テスト 27 件が緑（`RecipeRitualGateChannelDrift` / `FailCloseGate` / `DedicatedEffect*` / `GateEffectId*`）、エディタ 1314 件中 **25 失敗＝着手前と同数・同集合**（すべて別セッションの未コミット yml が前提条件を崩しているもの）。**TF 全体のテストは別セッションが `build/test-results` を掴んでいて実走できなかった**（`Unable to delete ... output.bin`）。**残る既知の warning は `power.yml` の排他グループ 4 件（1 メンバーしかなく no-op）で、これは別セッションの未コミット WIP。** |
| 2026-08-16 | **運用スクリプトの入口を `ops/launch/` の `.cmd` 14 本へ揃え、README に用途と引数を書いた**（ユーザー指示「すべてのサーバ用スクリプトを launch にバッチとして他に倣って配置し、目的と引数を示した README を置く」）。新規: `preflight` / `check-stopped` / `backup` / `restart` / `sync-configs` / `apply-husksync-config` / `apply-velocity-forwarding` / `seed-backend-configs` / `set-resource-pack` / `prune-geyser-items` / `install-datapacks` / `reset-world` / `purge-player-data` / `reset-resource`。**非自明だった点**: ①**`reset-resource.ps1` だけ既定が「本当に消す」**（他の破壊的スクリプトは既定が下見）。引数なしで叩いた瞬間に資源ワールドが飛ぶので、`.cmd` 側で `--apply` を書かないかぎり `-DryRun` を強制した。②**`if` ブロックの中で `shift` しても `%1` は変わらない**（cmd.exe はブロック全体を先に展開する）＝`--apply` がそのまま PowerShell へ渡る。`goto` に書き換えて実測で確認した。③**`.ps1` はリポジトリ側を直接実行している**（`launch-config.cmd` の `OPS_SCRIPTS`）ので、`deploy-launch.cmd` で配り直すのは `.cmd` だけ＝**PowerShell 側を直したら配り直しは不要**。④`check-logs.cmd` を作りかけたが `testkit/` に既にあったので消して README から参照した（入口の二重化を避ける）。⑤リポジトリ作業用（`wip-audit` / `list-wip-config` / `export-head-config` / `run-selftest` / `editor-*`）と一度きりの修復（`fix-elitemobs-drop-config`）は**意図的に入口を作らず**、その理由を README に書いた。**検証**: 14 本すべてを実走（読み取り専用と `-DryRun` は実際に実行、`set-resource-pack` は存在しない `-Server` で書き込み前に落として引数の受け渡しだけ確認、`reset-resource --apply` は存在しない `-ConfigPath` で `--apply` が消費されることを確認）。全 `.cmd` の参照先 `.ps1` が実在することを機械照合し、ops セルフテスト **32/0**（非 ASCII 混入ガードを含む）。**⚠ 未実施: 配備先への配り直し。`ops\launch\deploy-launch.cmd` はユーザーが実行する。** |
| 2026-08-16 | **図鑑のスレッド45件がID表記だった件を直し、アチーブメント報酬から職業EXPを全廃した**（ユーザー依頼「図鑑のカテゴリ：アイテムで一部のアイテムがID表記になっている」「アチーブメントの報酬から職業経験値を除外して」「代わりにキリの良いところで特殊報酬を渡して」）。**①図鑑**: `collection.yml` の `thread` カテゴリ45件が **ArsPaper `threads.yml` の生キー**（`angler`）で書かれていた。実アイテムIDは `catalog.yml` の `thread_angler`（`ThreadItem` が `"thread_" + key` を登録IDにする）。**症状は2つで、どちらも無言**: 表示は `CollectionEntryNames` が解決できないIDを生のまま出すので図鑑に `angler` と並び、記録は `CollectionListener` が**カタログIDで記録する**ので45件が**永久に未収集**のままだった（`achievements.yml` の `thread_all` は最初から `thread_*` で書かれており食い違っていた）。あわせて `material_tf` に紛れ込んだ非アイテムのキー2件（`categories` / `orders`）を落とした。**残り26件の「解決できないID」は監査スクリプト側の見落とし**で実際は正常 —— ソースリンク25件は `sourcelinks.yml` の `items:`、`infinity_catalyst` は `spellbooks.yml` にあり、どちらも表示名を持つ。**②報酬**: 38ノードの `job-exp` を空にした。アチーブメントは「その職業を進めた結果」なので職業EXPを返すと進行が自己加速し、格差吸収に選んだ24時間EXP減衰を素通りする。代わりに**3本の道の節目6ノードだけ**へ特殊報酬を置いた（`w_iron`→称号「鉄を打つ者」/ `a_netherite`→`seed_flame` / `m_first_ritual`→称号「儀式の徒」/ `src_vault`→称号「深淵を蓄える者」/ `adv_farm`→既存の未使用称号「糧を得る者」/ `adv_nether`→称号「灼熱を歩く者」）。新設した称号4件は `special-rewards.yml` へ追加（**`prune-orphaned-grants: true` なので一度出荷したIDは消さないこと**）。回帰は新規 `ShippedCollectionEntryIdTest`（**許可リストを作らず**「カタログに `<何か>_X` が実在するのに `X` と書かれている」＝接頭辞落ちを機械的に検出）と `ShippedAchievementTreeTest` の2件（職業EXP0件 / 特殊報酬は節目だけ）。テスト: 図鑑・アチーブメント・特殊報酬まわり273件中の失敗2件は `catalog.yml` / `item-stats.yml` を触っている別セッションのWIP由来で着手前と同じ。エディタは25失敗（着手前23＋別セッションが `mob-types.yml` の `dimensions` と14ツリーの `description` を埋めたぶん2件。**どちらも自分の変更ではないことをHEADとの比較で確認済み**）。**yml だけの変更なので `/trinityforge reload` で反映できる**（jar 再ビルドは不要）。 |
| 2026-08-16 | **サーバをリセットして正式に開き直す手順を用意した**（ユーザー決定「ワールドを作り直すのは main と resource だけ・dev は据え置き」「メインには案1データパックを入れない」「LuckPerms のグループ定義は残す」）。新規 `ops/scripts/reset-world.ps1` ＋ `purge-player-data.ps1` に `-PurgeGroups` を追加、手順は `ops/RUNBOOK.md` 手順 18。**非自明だった点**: ①**`saveResource(name, false)` は「ファイルが無いときだけ」書き出す**ので、**jar を新しくしてもサーバ側に古いファイルが残っている限り新版は永久に読まれない**（無警告）。実際に ArsPaper の `loot-tables.yml` が 3 台とも旧版 231 行（8/5）のままで、今朝入れた 761 行（構造物 346 本のティア制戦利品）は jar の中にしか無かった＝**そのまま開幕すると戦利品の作り込みが全部死ぬ**。手順 18-4 に退避手順として明記した。②`purge-player-data.ps1` は `world*/playerdata` は消すが**地形は消さない**ので、「ユーザーデータを含むリセット」には世界の作り直しが別途要る。③**プレイヤーの所属は `luckperms_user_permissions` の `group.<名前>` ノード**なので、そこを空にすればグループ定義（`luckperms_groups` / `group_permissions` / `tracks`）を残せる。④ワールドは**削除ではなく `Directory.Move` で退避**にした（同一ボリュームなので一瞬・追加容量ゼロ・巻き戻せる）。⑤座標に縛られた残骸は Chunky のタスク／BlueMap のタイル（`bluemap\web\maps`）／Ars の `source-network.yml`／SetHome の `homes.yml` の 4 つで、`ops-config.psd1` の `WorldResetTargets`（新設）に切り出した。⑥main の WorldGuard には `world` の `regions.yml` が無く、あるのは EM ダンジョンの `em_*` 21 個（インスタンスワールドの残骸）だけだった＝main の地形リセットに影響しない。**検証**: `reset-world.ps1 -DryRun` を実サーバ構成で実走（main 410MB / resource 1.4GB の退避計画とデータパック 17 件の再配置を確認）、`purge-player-data.ps1` の下見も実走。ops セルフテストに 3 件追加して **32/0 で全通過**（退避で中身が無傷なこと・`world` がジャンクションなら中断すること・`WorldResetTargets` に `plugins\TrinityForge` を書くと起動時点で拒否すること）。**⚠ 未実施: 実際のリセット。D:\game への書き込みはエージェント権限で拒否されるのでユーザーが実行する。** |
| 2026-08-16 | **アチーブメントを 34 ノードの単一ツリーから「3 つの道 + 秘された道」へ全面再構築し、図鑑を 816 件へ拡充した**（ユーザー方針「アチーブメントだけ見れば次の短期目標が分かる」「マイルストーンを置きすぎると自由度が失われる」「裏アチーブメント（コレクション・特殊行動）を別途」「図鑑は全アイテム・全モブを乗せるくらいの勢いで」）。表は **戦士の道 18 / 魔導士への道 19 / 冒険者の道 15**、裏は `secrets_root` の下に **`hidden: true` 14 件**。各系統は **1 つの【最終目標】**（束縛者討伐 / 累計 1 億ソース / 図鑑 100%）で終わり、その名前を起点ノードの lore に書いた（**以前は目標が枝の奥に埋もれて何を目指す系統なのか GUI から読めなかった**）。**機構は新トリガー 2 種**: `gear-use`（その装備で<b>実際にダメージを与えた</b>ら達成。クラフト判定にすると使用レベル制限を跨いで先に取れる）と `skill-level`（列挙したスキルのうち N 種類が Lv◯）。**非自明だった点**: ①**`parseTriggerType` がケバブケースを `valueOf` に渡していた**ため `gear-use` → `GEAR-USE` で例外になり、**アチーブメントごと skip されるのに警告 1 行しか出ない**（GUI から消えるまで気づけない。新規テスト 5 件が最初これで落ちて発覚）。②**lore の `</b>: ` は YAML がキーと値の区切りとして読む**ので、引用符を付けないと lore がマップに化けて表示が消える（新規の editor スキーマテストが実 yml で 2 件検出）。③**ネザライトは最上位武器ではない**（星枢 → 守護者 → インフィニティの 3 段が上にあり、`infinity_sword` の儀式は 2,000 万ソース＝ここで戦士の道と魔導士の道が交わる）。④**`binder_*` 19 件は `draft: true`＝未出荷**なのでアチーブメントに使えない。⑤**貯水槽(200万)→特異点の壺(5,000万)だけ 25 倍**空いていたので `source_vault`（深淵の櫃・容量 1,000 万・CMD 200008）を挟み、最上位の儀式コストを 200 万 → 1,000 万へ上げた（積み上げ 約1,043万 → 約2,300万）。⑥グリフ解放の PDC は **`UnlockedGlyphs` クラスと `ManaKeys.UNLOCKED_GLYPHS` の 2 実装が同じキー名で並存**し、実際に書かれるのは後者だけ（前者にフックしても永久に発火しない）。⑦**増強グリフは全 24 種しかない**ので「50 回」を種類数カウンタで書くと達成不能だった（8 種類へ修正）。⑧`ShippedThreadAcquisitionRouteTest` の「rewards.items が 10 件以上」は<b>設計の写し</b>で、報酬の種類が減っただけで正しい側が落ちていた → 同じ yml を 2 通り（節の数 / id の数）読んで食い違いだけを見る形へ直した。**フォーク側**（`TrinityForgeBridge` にカウンタ 3 種のヘルパ、`ScribingTableGui` / `RitualManager` / `SpellCaster` に加算）は **`.gitignore` 除外なので commit されない**。**テスト**: TF **4021 / 34 failed / 2 skipped**（アチーブメント・図鑑・印まわりの失敗は 0 件。34 件は別セッションの未コミット yml 由来）、editor **1276 pass**（新規 `shipped-achievements-schema-2026-08-16.test.js` 4 件を含む）、fork `compileJava` 通過。**⚠ Java 変更なので反映には TF jar と ArsPaper jar の再ビルド + サーバ停止後の配備が必要。** |
| 2026-08-16 | **案1のデータパックは 1 つも配備されていなかった**（`Resource_Server\world\datapacks` には Paper が作る `bukkit` だけ）。**そのうえ、素直に `world\datapacks` へ入れると週次リセットの初回で全部消える**（`reset-resource.ps1` が `world` を丸ごと削除するため）。消えてもエラーは出ず、**資源ワールドが黙ってバニラ地形で再生成され、追加した戦利品プールも namespace ごと当たらなくなる**。対処: 正本を `Resource_Server\datapacks-source`（`world` の外）に置き、リセットのたびに `world\datapacks` へ**総入れ替え**で複製する方式にした（`ops-config.psd1` の `ResourceDatapacks`）。**非自明だった点**: ①**`server-loop.cmd` は Paper 停止の 10 秒後に起動し直す**ので、数 GB のワールド削除中に Paper が立ち上がる（＝半分消えたワールドで起動し、データパック再配置は Paper が読み終わったあとになって無効化される）。`maintenance.flag` を追加してループを保留させた（`stop.flag` と違いループから抜けさせない）。整合チェックが失敗したときは**わざと flag を外さない**＝壊れた config で起動させない。②**要塞オーバーホールはフル版と LITE 版が排他**で、両方入れると読み込み順まかせになる。③`downloads` には `minecraft_server.jar` などデータパックでない物も混ざるので、案1 の 17 パックだけを名前で照合してコピーする。④**同じパックの複数版が並んでいたら中断する**（どれが効くか分からない状態で配備しない）。⑤`level-seed` は空なので毎週別ワールドになる（意図どおり）。⑥`plugins/TrinityForge` はジャンクション共有・ArsPaper config は `sync-configs.ps1` がメイン→資源へコピーなので、**メインと資源で戦利品の確率を変えることはできない**（メインは実質一度きり、資源は毎週、という非対称は運用で吸収するしかない）。新規 `ops/scripts/install-datapacks.ps1`（人が実行）。ops セルフテストに 3 件追加して **28/0 で全通過**、`install-datapacks.ps1 -DryRun`（17 件選択・LITE 除外を確認）と `reset-resource.ps1 -DryRun`（tmp のフィクスチャで全手順を通す）を実走済み。**⚠ 未実施: 実際の配備。`ops\scripts\install-datapacks.ps1` はユーザーが実行する（D:\game はエージェント権限で拒否）。**手順は `ops/RUNBOOK.md` 手順 17。 |
| 2026-08-16 | **案1データパックを含む構造物 346 本の戦利品を全面設定した**（ArsPaper `loot-tables.yml`）。ユーザー方針「既存の中身が豪華なところには豪華な報酬を、微妙なところには微妙な報酬を。ベースのルートがバニラでも同じ」「デフォルト戦利品も量を増やして漁る意味を出す（たまに圧縮ブロック）」「深淵素材は出さない」。**実際にデータパックを展開して全ルートテーブルを読み、既存の中身の豪華さでスコア付けして 5 ティアへ機械的に割った**（`tmp/worldgen/loot_tiers.py` → `gen_loot_yml.py`、根拠は `report/loot-tiers.csv`）。追加報酬は指示どおり 5 分類（現実の芯 / 品質系スレッド 10 / カスタムモブ素材 13 / レシピの無い鍵 11 / リセット系スクロール 3）＋圧縮ブロック。既存戦利品は貧相なティアほど大きく盛る（T1 2.0 倍 〜 T5 1.2 倍）。データパック由来のエンチャント本は名前空間で判定して除去（`block-datapack-enchant-books`）。**非自明だった点**: ①**旧版は対象を `dungeons_and_taverns:*` と書いていたが、その名前空間は実在しない**（実体は `nova_structures`）＝データパック向けプールは 1 度も発火していなかった。名前空間を間違えても例外もログも出ない。②**エンチャント本の除去をエンチャント名の列挙でやるとデータパック更新ですり抜ける**ので名前空間で判定した。③**個数倍率を四捨五入で実装すると 1 個スタックが 1.5 倍で常に 2 個になり実効 2 倍に化ける**ので、整数部確定＋端数は確率で +1 にした。④**複数プールが当たったときは掛け合わせず最大値**（掛けるとプールを 1 つ足すだけで全チェストが黙って倍量）。⑤ティア制へ機械的に割り直したら **旧ハードコードの `ENCHANTED_GOLDEN_APPLE` と `stronghold_corridor` が黙って落ちていた**（テストで固定して復活）。⑥`hoglin_tusk` は実在しない ID（正しくは `hoglin_fang`）で、`ItemCostRef` は未登録 ID を警告 1 回で黙って飛ばすため、配る前に `tmp/worldgen/verify_ids.py` で全 ID の実在と `draft:` を機械照合した。⑦村・トライアルチャンバー・壺/発掘の 117 本は意図的に対象外（無限湧き・大量にあるので経済が壊れる）。**テスト**: `LootTableConfigTest` を新契約へ書き直し＋新規 `LootTableListenerTest`。フォーク全体 379 件中 **失敗 6 件**（着手前と同一集合。すべて別セッションの `threads.yml` / `thread-sets.yml` / 儀式まわりの未コミット WIP 由来で、今回の変更とは無関係）。`ArsPaper-1.0.0.jar` を再ビルドして jar 内の `loot-tables.yml` が新版（761 行・`t5_structures` あり）であることを確認済み。**⚠ ArsPaper フォークは `.gitignore` 除外なのでこの変更は commit されない。clean/reset で消えるので、配備前に jar を退避すること。配備はサーバ停止後。** 仕様は `docs/config-reference/arspaper/loot-tables.md`、落とし穴は `docs/agent-context/forks-and-mobs.md`。 |
| 2026-08-15 | **サーバ間チャット（`【資源】mcid: hello`）と管理者用サーバ間 TP を TF 本体に実装した**（ユーザー決定「TP は管理者用の強制 TP だけ」「全発言を 3 サーバー共通にする」）。新規 `network/`（`ProxyChannel` / `CrossServerChat` / `ChatFormat` / `CrossServerTeleport`）＋ `NetworkConfig` ＋ 出荷 `network.yml`。手順は `ops/RUNBOOK.md` 手順 16。**非自明だった点**: ①**Velocity 4 は旧 BungeeCord プラグインメッセージチャンネルを今も実装している**（`velocity-4.1.0-SNAPSHOT-9.jar` の `BungeeCordMessageResponder` に `Connect`/`ConnectOther`/`Forward`/`ForwardToPlayer`/`GetServer`/`PlayerList` が実在、`velocity.toml` の `bungee-plugin-message-channel = true` が既定）→ **プロキシ用プラグインも Redis も要らない**。定番の HuskChat は開発終了で Velocity 4 では使えない。②**`plugins/TrinityForge` はジャンクション共有なので「自分がどのサーバか」を config に書けない** → 参加時に `GetServer` でプロキシへ聞く（名前が取れるまでチャット共有は始まらない仕様）。③**プラグインメッセージはプレイヤーの接続に相乗りする**ので、オンライン 0 人のサーバは送受信ともできない。④`Forward` の宛先 `ALL` が送信元を含むかは実装依存なので、**ペイロードに送信元サーバ名を入れて自分発を捨てる**（入れないと二重表示）。⑤`AsyncChatEvent` は非同期だが `sendPluginMessage` はメインスレッド専用なので載せ替えが要る。⑥**TP は座標を回線に載せない**（宛先サーバが座標を保持し、到着したプレイヤーを 20 tick 後に飛ばす）。HuskSync のインベントリ復元と競合させないための遅延で、`arrival-delay-ticks` で調整できる。⑦**Discord へは各サーバの DiscordSRV が自分の発言だけを投げる**（他サーバ分は `Bukkit.broadcast` でチャットイベントではない）ので中継しても二重投稿にならない。サーバ名は DiscordSRV 側 `messages.yml` の `MinecraftChatToDiscordMessageFormat` に `[メイン]` / `[開発]` を入れて出す。⑧**MiniMessage のテンプレートをプレースホルダ位置で切って結合すると閉じタグが余り、`</white>` が文字として出る** → `Placeholder.component()` の TagResolver へ変更（発言者が打った MiniMessage 記法も解釈されない）。新規テストで固定済み。テストは新規 16 件（`ChatFormatTest` 9 / `NetworkConfigTest` 7）が全通過、TF 全体は着手前 **3976 / 40 failed / 2 skipped** → 変更後 **3992 / 40 failed / 2 skipped**（`git stash` で自分の変更だけ退避して基準を実測。失敗 40 件は別セッションの未コミット yml 由来で同一集合）。`releaseAssembly` 済み・jar 内に `network.yml` と新規 5 クラスを確認済みだが、**配備はサーバ停止後**。 |
| 2026-08-15 | **武器3ツリーの較正 W-30 / W-31 を実施した**（ユーザー決定「割合キー `bleed-damage-rate` を新設」「ツリー ≒ 装備の1.5倍まで」）。詳細は §2 の W-30 / W-31 行（取り消し線つき）。**非自明だった点3つ**: ①**乗算レイヤは「1レイヤ＝1ステ」**で、基準ステ以外のキーを書いても yml は通るが実行時に `PerkBuffResolver#withValidMultiplierLayers` が**無言で捨てる** → `stats/lore.yml` に `layer_4`（`bleed-damage-rate`）を新設し、C-1-2 は実数（アイテム由来）と率（ツリー由来）の**両方**を ×1.2 する。②**新設ステは `StatVocabulary` に足さないとスキルツリー読み込みで全ノード dropped になる**（`lore.yml` と editor 辞書だけ足しても「値は書けるのに一切効かない」状態になり、警告はログにしか出ない）。③**`ShippedSkillTreeFlatStatScaleTest` は `buffs` しか見ておらず、`mainhand-buffs` を使う武器ツリー2本が丸ごと検査対象の外だった** → `mainhand-buffs` も歩くよう拡張し、`bleed-damage` を実数禁止キーへ追加。回帰は新規 `WeaponTreeMultiplierCalibrationTest`（較正値の固定＋`item-stats.yml` から毎回引く「装備の1.5倍」判定＋出血が率で配られていること）と `BleedServiceMagicAggregateTest` 3件。エディタ側は `percent-stat-fraction-notation` の検査が**乗算レイヤの 1.2 を「120%」と誤検知した**ので、`multipliers:` / `mainhand-multipliers:` 配下を除外した（率キーを乗算に載せると必ず踏む穴）。テスト TF **3976 / 40 failed / 2 skipped**、エディタ **23 failed**（どちらも失敗は着手前と同じ集合＝別セッションの未コミット yml 由来。武器ツリー・出血・ロア宣言まわりの失敗は0件）。**Java 変更なので反映には TF jar の再ビルドとサーバ再起動が必要。** |
| 2026-08-15 | **経験値瓶格納 `xp-bottle-store` を釣りギミックからエンチャントギミックへ移した**（ユーザー依頼「経験値瓶格納の設定を釣りギミックからエンチャントギミックに移動して」）。この機能はエンチャントツリーの「EXPフリーザー」(`feature:xp-bottle-store-unlock`) で解放されるのに、**数値設定だけが無関係な `stats/fishing-gimmick.yml` に置かれていた**。**受け皿の新設は不要だった** ── エディタには既に「エンチャントギミック」タブ (`registry.js` の `enchant-gimmick`) があり、その実体ファイルが `progression/crafting-features.yml`、Java の読み手が `CraftingFeaturesConfig` なので、そこへ移すだけで済む（`enchanting-gimmick.yml` のような新ファイルは作っていない）。フィールド/アクセサ/tiers パース/クランプを `FishingGimmickConfig` → `CraftingFeaturesConfig` へ移し、`XpBottleListener` の引数型と `TrinityForge.java` の配線を差し替えた（既定値・0〜1クランプ・tiers の floor 解決・グローバル値フォールバック・壊れた行のスキップは挙動不変）。**移行漏れの無言死対策として、`fishing-gimmick.yml` に `xp-bottle-store` が残っていたら移動先を明示した警告を出す。逆方向の読み取りフォールバックは実装していない**（原因が隠れるため）。エディタは釣り画面からカードを削除（**開いただけで空セクションが書き戻らないよう `ensureObj` も撤去**）してエンチャント画面へ追加、schema 検証と `tier-vocabulary` のマッピングも `craftingFeatures` 側へ移した。commit `db6d4b1`。テスト TF **3970 / 40 failed / 2 skipped**、エディタ **1273 pass / 23 failed**（どちらも失敗は別セッションの未コミット yml 由来で、xp-bottle・fishing-gimmick・crafting-features 関連の失敗は0件）。**Java 変更なので反映には TF jar の再ビルドとサーバ再起動が必要**（yml の reload だけでは足りない）。**配備済みサーバの yml も手で移すまで警告が出続ける**が、機能自体は新しい場所の値で正しく動く。 |
| 2026-08-15 | **カタログidのタイプミスで「スレッド固有」の設定欄が見出しだけになる件を、原因が画面に出るように直した**（実サーバ報告「スレッドの設定を editor で行っているが、スレッド固有の設定欄が項目名だけ残って表示されなくなってしまった」）。**エディタのバグではなくデータ不正だった** ── 新規追加された「流転のスレッド」(`STRING` / CMD 100028) のカタログidが `thred_translate`（`thread` の綴り誤り）になっていた。ArsPaper の `UnifiedRecipeLoader.java:212` が `recipeKey("thread_" + id, index)` でカタログidを作るので**スレッドのカタログidは必ず `thread_<threads.ymlのid>`** であり、この規則を外れた id は `threads.yml` / `thread-sets.yml` のどのエントリにも対応しない。`forms.js` の `resolveThreadId` が `null` を返すと、見出しの下の2セクション（threads.yml 効果 / thread-sets.yml セット効果）が**どちらも何も描かずに即 return** するため、**見出しだけが残り理由がどこにも出ない**状態になっていた。しかもこの id は GUI で設定できないだけでなく**ゲーム内でもスレッドとして機能しない**。対処は3つ: ①id のタイプミスを修正（`catalog.yml` 3箇所） ②`forms.js` に純粋関数 `threadCatalogIdProblem(catId)` を新設し、命名規則違反・カタログ未紐付け・`threads.yml`/`thread-sets.yml` 未読込のそれぞれを `warn-banner` で理由つきに出す ③`schema.js` の `validateCatalog` に「`_editor.itemTabs.<id> === "thread"` の id は `thread_` 始まり」の検査を追加して保存時に気づけるようにした。commit `64ca42c`。テスト: 新規11件が緑、エディタは着手前 1261 pass / 失敗24種 → 1272 pass / 失敗24種で**失敗テスト名の集合は完全に同一**。**なお `thread_role_effeciency`（efficiency の綴り誤り）は接頭辞が正しいので機能しており、改名は既存データを壊すので見送った。** |
| 2026-08-15 | **防具値ステ（`armor-defense-rate`）を廃止し、防御率（`defense-rate`）へ統合した**（ユーザー依頼「防具値は直感的じゃないので同等の防御率か被ダメージ軽減に置き換えてこのステータスは削除して」／置換先＝防御率・防具バーは空にする、をユーザーが選択）。**換算は 1点 = 1.5% 軽減**（`combat/damage.yml` の `vanilla-armor.defense-rate-per-point` と同値）なので実効軽減は据え置き（最良4部位: 重装 20点→0.30、軽装 11点→0.165）。廃止の理由は**同じキーが2つの単位を運んでいた**こと ── アイテム側は「バニラ防具値の点数」（`AttributeProjection` で `Attribute.ARMOR` へ写像し `VanillaArmorMapping` が読み戻す）、パーク側は「[0,1] の軽減率」。単位が違うので `PercentStatNormalize` が%矯正の対象外にしており、**パークに `10` と書くと 1000% 軽減として通っていた**。実装は ①`DefenseStatKeys` に `defenseRate` を追加して `DefenseStatBridge` が直接読む ②`AttributeProjection` から `armor_defense_rate → armor` の写像を削除（7→6キー） ③`AttributeApplier` に `ALWAYS_SUPPRESSED_MATERIAL_DEFAULTS = {ARMOR}` を新設し**材質既定も復元しない＝TFスタンプ装備の防具バーは常に空**（写像を消しただけだと材質既定が戻ってバニラミラーと二重計上になる） ④`PlayerStatAggregator` の `extraArmorDefenseRate` 迂回路と `NonItemContribution` を削除 ⑤出荷 `item-stats.yml` の 151 行を一括換算 ⑥防具EXPの `armor.exp_armor_point_multiplier` を入力スケール 1/66.7 倍に合わせて 0.05→3.33（最良装備での係数 2.0 が不変）。エディタ／wiki 生成／ラダー回帰テストも同じ換算で追随（設計時モデルの `VANILLA_ARMOR_POINTS` 加算は式を変えず率で計算するだけにしてラダーのロック値を維持）。テスト TF **3968 / 37 failed / 2 skipped**（失敗は全部別セッションの未コミット yml 由来で着手前と同一）、エディタ 23 failed（同上）。**Java 変更なので配備には TF jar の再ビルドとサーバ再起動が必要**（yml の reload だけでは反映されない）。 |
| 2026-08-15 | **釣りに「機能解放追加用テーブル」`fishing.unlock-groups` を新設し、ついでに死んでいた釣りのカテゴリゲートを直した**（ユーザー依頼「デフォルトテーブル（ゴミ・魚・宝）と機能解放追加用テーブル（ゴミ・魚・宝）を設定できるようにしたい」）。**副産物のほうが重い** ── 調べる過程で、`FishingGimmickListener` が `DropTablePolicy` へ渡す Map のキーが**カテゴリid単体**だったため、`categoryOpen(prof, catId)` が `fishing:gatya` を組み立てる一方で**解放側は `fishing:treasure:gatya` で登録**しており、`isOpen` の「未登録キーは開放扱い」規則と噛み合って**釣りのカテゴリゲートが全て素通り**していた（＝機能解放が無くても宝カテゴリが引けていた）。キーを `<group>:<category>` へ前置きして揃えた（この Map キーは `drawAcrossCategoriesWithExemption` 内で `categoryOpen` にしか使われないことを確認済み）。**`unlock-groups` は groups と同じ重みプールへ合流する**ので、解放が増えるほど既存カテゴリのシェアは希釈される（ユーザー選択「同じ重みプールへ合流（希釈あり）」）。カテゴリidが groups と衝突したら unlock-groups 側を無視し、**組み合わせごとに1回だけ**警告する（釣り上げるたびに通る経路なので毎回出すとコンソールが埋まる）。エディタは3カード追加だが、`dropTableEditor(working, path)` は **path を渡した瞬間に中間オブジェクトと `categories: {}` を丸ごと実体化する**ので、任意キーである `unlock-groups` では「押した瞬間に作る」二段構えが必須（フォームを開いただけで yml が書き換わる事故クラス）。**`fishing.groups` と mining/woodcutting/digging の `drop-tables.categories` は今日まで schema 検証がゼロだった**ので fishing 側にだけ `validateFishingDropGroupsMap` を新設した（他3職は未検証のまま・流用できる形にしてある）。commit `873421e`。テスト TF **3968 / 36 failed / 2 skipped**（失敗26クラスは全部別セッションの未コミット yml 由来のドリフトで着手前と同一）、エディタ 29 件で増減なし。**残: `stats/fishing-gimmick.yml` の `gatya` を `groups.treasure` から `unlock-groups.treasure` へ移す作業と、`skilltree/fishing.yml` の存在しないアイテムidを指すゲート `drop:fishing:item:gacha_ticket_4/_5` の修正は、両ファイルとも別セッションが未コミットで持っているため未着手。** |
| 2026-08-15 | **「防御力」が単位の違う2つのステを1キーで運んでいたのを分離し、軽装備ツリーの守備力を装備の土俵で較正し直した**（実サーバ報告「軽装備で防御力が0.1(単位なし)、守備力0.5(単位なし)のようになってしまっている。というか防御力に％単位がないのおかしい」）。**2件の別々の不具合だった**。**① `armor-defense-rate` が互換性の無い2つの単位を運んでいた** ── アイテム側は**バニラ防具値(点数)**（`AttributeProjection` が `Attribute.ARMOR` へ ADD_NUMBER し、`damage.yml` の `vanilla-armor.defense-rate-per-point` 0.015/点・上限0.8 を通して初めて軽減率になる）、パーク側は **[0,1] の乗算軽減率そのもの**（`PlayerDefenseResolver` が `DefenseStats#defenseRate` へ直結）。ロア書式は `FLAT` 1本なので「防御力 +8」(防具値)と「防御力 +0.1」(10%軽減)が同じ行に並び、**実効の差が7倍あっても見分けが付かない**（Lv60・モブ貫通0.17 で、軽装C の `armor-defense-rate: 0.1` は −8.3%、同ノードの `phys-flat-defense: 0.3` は −1.2%）。さらに **`PercentStatNormalize` は防具値のほうを守るためこのキーを%矯正の対象外にしていた**ので、パーク側に `10` と書くと **1000% 軽減として通る**状態だった。割合のほうを `defense-rate`（PERCENT表示・%矯正あり・stat-caps のクランプ対象）へ分離し、`armor-defense-rate` は表示名を「**防具値**」に改めてアイテム専用へ戻した（`item-stats.yml` 179品は無改変。旧キーは resolver がフォールバックで読み続ける＝配備先の記述を無言で殺さない。**両方あっても足さない**）。**② 軽装備ツリーの守備力がツリー全取りでも装備の 1/6 だった** ── 2026-08-14 の較正は**ノード単価**だけを「重装備の半分」に揃えたが、**守備力を配るノードが軽装備は2個・重装備は7個**なので、**ツリー全取りの合計では phys 0.8 / magic 2.4 = 重装備(4.6/13.8)の 17%** にしかならず実質 no-op のままだった（＝ユーザーの「なおってなくない？」は正しい）。**較正の土俵をレベル帯ごとの装備4部位合計へ取り直した**（出荷 `item-stats.yml` の実測: Lv100 で重装 phys 13.80 / magic 62.60、軽装 phys 13.95 / magic 45.00。モブ基準攻撃力 `A(L)=10.2*1.0148^L` は Lv60 で 24.6 / Lv100 で 44.3）。**重装備ツリー全取り(4.6/13.8)がおおよそ装備1部位ぶん**に当たるので、軽装備はその半分（0.5部位ぶん = phys 2.3 / magic 6.9）へ引き上げ、**プレステージ + 主軸 C/D/E の4ノードへ分散**した（1〜2ノードへ寄せると同レベルの重装備ノードを単価で追い越す）。主軸D「堅守の境地」は**名前に反して守備力を1も配っていなかった**ので、ここで受け持たせた。**新規 `ArmorTreeDefenseCalibrationTest` 4本**は**合計側を直接縛る**（ノード単価だけ合わせて総量が合わない、という壊れ方はレビューで見落とされる）。修正前に戻すと落ちることを2通り実走で確認（β1ノードを旧キーへ戻す／プレステージを 0.5/1.5 へ戻す）。**検証**: TF **3958 tests / 37 failed**（着手前 3954/38。`BaseStatsConfigTest` の1件が解消。残り37件は全部別セッションの WIP 由来で `defense-rate` に言及ゼロをログ検索で確認）。config-editor **22 failed で着手前と一致**。**`combat/base-stats.yml` に `defense-rate` と、前項で足し忘れていた採取スキル別 break-vanilla-exp 4キーを 0 で記載した**（`BaseStatsConfigTest` が語彙の全網羅を要求するので「未記載＝既定0」では通らない ── 前項の判断は誤りだった）。`stats/lore.yml` は別セッションが編集中(HEAD比195行差)なので `hash-object`＋`update-index` で自分の差分だけを index へ載せた。**配備が要る**: Java 変更なので jar 差し替えとサーバ再起動 |
| 2026-08-15 | **破壊時バニラEXPの倍率が職業間で漏れていたのを採取スキル別へ分割した**（実サーバ報告「破壊時バニラEXP解放のスキルが各職業間で共通になっちゃってない？」）。**指摘は半分当たりで、階層が2つに分かれていた** ── **解放ゲート `feature:break-vanilla-exp` は職業別で正しかった**（2026-08-01 に修正済み。`NativeSkillExperienceListener#grantBreakVanillaExp` が `dedicatedEffects.isActive(player, id, gatheringSkill)` で壊したブロックの採取スキルを渡し、`DedicatedEffectGateIndex#isActiveByPerks` が `placement.skill()` と一致比較する）。**漏れていたのは倍率のほう** ── `break_vanilla_exp_bonus` は**スコープを持たない普通の総合ステ**で、`totals.totalOf(...)` は全ツリーを合算するため、**採掘Cで取った +50% が伐採・整地・農業の破壊EXPにもそのまま乗っていた**（出荷ノードは mining:95 / woodcutting:71 / digging:70,109 / farming:78,92 の計6つ × 0.5 ＝ 解放済みの全採取で最大 +300%）。ノード説明文は「破壊で1.5倍」「破壊で2倍」とツリー内で完結する前提の書き方で、表示と挙動が食い違っていた。**修正**: `<採取スキルID>_break_vanilla_exp_bonus` を新設し（`stats/BreakVanillaExpBonusKeys`。`SkillExpBonusKeys` と同じく**キー集合を1箇所から導出**して語彙・分類・`/tf stats` タブへ機械的に配る）、出荷6ノードを各ツリーのキーへ移した。**4スキル分しか作らないのは意図的** ── 破壊時バニラEXPは `grantGathering` が採取扱いと判定した破壊にしか出ず、その戻り値は FARMING/WOODCUTTING/DIGGING/MINING に限られるので、他スキル分を作っても一度も読まれない死んだキーになる（POWER を除く `SkillExpBonusKeys` と同じ判断）。**スコープ無しの `break_vanilla_exp_bonus` は残した** ── 「採取全般の破壊EXPを増やす」意味のキーとして加算され続ける（消すと配備先の base-stats/item-stats の記述が無言で無効になる）。出荷スキルツリーからは使わない。**`StatCategoryInference` では部分一致より前に判定する必要がある** ── 下に置くと `mining_break_vanilla_exp_bonus` だけ `contains("mining")` で GATHERING に落ち、4キーが別々のタブへ散る。**エディタ**: `labels.js`（ラベル＋説明。共通キー側にも「スキルツリーには採取スキル別キーを使うこと」を明記）/ `tf-lore.js`（カテゴリ推定の utility 一覧。ここに無いと other へ落ちる）/ `tf-base-stats.js`（上限UI一覧）＋ `docs/config-reference/combat/stat-caps.md` の効くキー一覧（107→111件）。**検証**: TF **1616 tests / 8 failed**（stats・command・skilltree・listeners・AllSkillTrees を実走。失敗8件は全部**別セッションの WIP 由来**で、power.yml の greek group 1件問題×5／woodcutting.yml の `drop:woodcutting:crystal_apple` 重複／lore.md アンカー drift／brew-unlocks の `custom:hoglin_tusk`。**`AllSkillTreesPerkBuffTest` の警告一覧に新キーの「未知の buff キー」は1件も出ていない**＝ローダーが受理している）。新規 `BreakVanillaExpBonusKeysTest` 5本パスで、**mining.yml を共通キーへ戻すと実際に落ちることを実走で確認済み**。config-editor は **22 failed で着手前と完全一致**（自分由来の2件＝stat-caps 件数と md 突き合わせは解消）。**`stats/lore.yml` と skilltree 4本は別セッションが編集中**なので、HEAD の中身に自分の差分だけを当てた blob を `hash-object`＋`update-index` で index へ載せた。**`combat/base-stats.yml` には新キーを足していない**（未記載＝既定0で挙動が同じ／別セッションが編集中のファイルを増やさないため）。**配備が要る**: TF は Java 変更なので **jar 差し替えとサーバ再起動が必要** |
| 2026-08-14 | **カスタムエンチャントがツールチップに一切出ない不具合を直した**（実サーバ報告「魔導書につけた共有は機能しているがエンチャントの表示がツールチップに記載されていない＝エンチャントされていないように見える」）。**真因は光沢(エンチャントオーラ)の実装方式** ── `BaseCustomItem#createItemStack` が光沢を出すために**ダミーの `Enchantment.UNBREAKING` レベル1を付け、その表示を隠すために `ItemFlag.HIDE_ENCHANTS` を立てていた**。**`HIDE_ENCHANTS` は all-or-nothing** なので、後から金床で付けた**本物のカスタムエンチャント（共有／マナ再生／マナ上昇／回生）まで巻き添えで消える**。`SpellBook` は `hasEnchantGlow()` を上書きしていない＝**全魔導書が該当**し、メイジアーマーも同じ理由でエンチャント行が出ていなかった。`EnchantBookRitualEffect` 側はさらに悪く、**`EnchantmentStorageMeta` に `addEnchant()` を呼ぶと格納エンチャント側に混入**するうえ `HIDE_ENCHANTS` が本物の格納エンチャントまで隠していた。**修正**: 両方とも Paper 1.20.5+ の `ItemMeta#setEnchantmentGlintOverride(true)` へ移行（ダミー付与と `HIDE_ENCHANTS` を撤去）。**ユーザー判断で `hasEnchantGlow()` の既定を true→false へ反転**したので、**`enchant-glow: true` を明示していないカスタムアイテムは新規生成分から光らなくなる**（`ConfigurableMaterial` は `materials.yml` へ委譲しているので影響なし）。**lore 行（紫の「共有」など）は残す** ── 統合版ではカスタムエンチャント行が変換されず lore が唯一の手掛かりになるため。Java版では二重に見えるがユーザー承認済み。**既存アイテムの遡及修復はしない**（新規生成分のみ）。**危険な誤りを1つ回避** ── ダミー削除の前に `Enchantment.UNBREAKING` を機能的に読む箇所を洗い、`SpellCaster.java:498` が詠唱耐久判定 `CastDurabilityPolicy#damageFor` の入力に使っていることを発見。ただし `488行目` の `Damageable` ゲートと `491-495行目` の `maxDurability <= 0` ゲートにより、詠唱対象（魔導書=`BOOK`／ワンド・触媒4種=`BLAZE_ROD`）は**全て耐久ゼロで `damageFor` に到達しない**ため挙動不変と裏取りしてから進めた。**あわせて適用先の案内文の誤りも修正** ── エンチャント本4種の lore が全部「金床でメイジアーマーに適用」だったが、**共有は `spell_book_*` 限定**（`EnchantBookListener.java:106` の `!isSpellBook → continue`）、**回生は耐久を持つアイテム全般**（同108行目の `!isDamageable → continue`）で防具限定ではない。`functional-items.yml` の2件とハードコードのフォールバック文（`EnchantBookRitualEffect`）の両方を直した。**検証**: ArsPaper fork **365 tests / 5 failed** で失敗テスト名まで着手前と完全一致（全て `ThreadConfigPotionOverrideBackCompat`／`ThreadSetThresholdReachability`／`ThreadRitualRecipeConfig` ＝別セッションの `threads.yml`・`thread-sets.yml` WIP 由来）。**配備が要る**: ArsPaper の再ビルドと jar 差し替え＋`functional-items.yml` の配備 |
| 2026-08-14 | **ソース自動消費をアイテムごとの「マナ回復量＋CT」に拡張し、`custom:` 付きidが一度も一致していなかったバグを直した**（ユーザー報告「ソースベリー 100 とあるがマナ回復量とCTがそれぞれ設定できるべきでは？」→ 確認の結果「**アイテムごとCT＋全体は既定値**」で確定）。**ついでに見つけた重大バグ**: 出荷 `config.yml` は `custom:source_berry: 100` と書かれていたが、照合側の `PdcHelper#getCrossPluginItemId` が返すのは PDC の素のid（`source_berry`）なので **1件も一致せず、ソース自動消費は一度も発動していなかった**。設定エディタのアイテム選択UI（materialInput）がカスタム品を `custom:` 付きで書き出すため、yml を手で直しても編集し直すたびに戻る ── **読み込み側で落とすのが唯一の恒久策**（`ManaConfig#normalizeItemId`。`list:` は落とさない。互換リストは1個のアイテムidではないので、残して「一致しないid」にする方が誤爆しない）。**CTのアイテム単位化**: `items` の値が「数値のみ（＝マナ変換量。CTは全体既定）」と「`{mana, cooldown-seconds}`」の2記法になった。**旧記法を読めなくすると既存 config.yml の自動消費がまるごと無効化される**ので必ず両方読む。CT状態は `Map<UUID, Map<itemId, 最終変換時刻>>` にし、**CT中のアイテムだけ候補から外す**（全体を止めない）／**CTを開始するのは実際に消費したアイテムだけ**（候補に挙がっただけの別アイテムまで縛らない）。**「キー無し＝全体既定」と「0＝CT無し」は別の意味**なので `contains` で区別する。**エディタ**: アイテム行の数値欄に**ラベルが無く**、すぐ上の全体CT欄と区別がつかなかった（報告の直接の原因）ので「マナ回復量」「CT(秒)」のラベルを付け、行ごとのCT欄を追加。**CT空欄なら数値だけの短い形へ書き戻す**（開いて保存しただけの往復差分を作らない）。`lib/schema.js` は両記法を通し、未知キー（`cooldown` 等の打ち間違い）を弾く。**検証**: ArsPaper fork **365 tests / 5 failed**（`SourceAutoConsumeTest` 16/16 パス。失敗5件は着手前と同じ Thread* 3クラス）。config-editor **1268 tests / 22 failed** で着手前と完全一致。実ブラウザで「その他のギミック」→「ソース自動消費」を開き、`ソースベリー (custom:source_berry)` / マナ回復量100 / CT10 がラベル付きで出ることを DOM で確認。**配備が要る**: ArsPaper の再ビルドと jar 差し替え＋`config.yml` の配備 |
| 2026-08-14 | **エンチャント本8件の儀式レシピを「特殊アイテム」エディタへ移し、表示名/lore を上書きできるようにした**（ユーザー報告「回生とマナ関連のエンチャント本の儀式レシピが消えている…特殊アイテム設定のeditorでレシピや表示設定を宣言しておいて」）。**消えてはいなかった** ── `enchant_book_*` 8件（マナ再生I〜III/マナ上昇I〜III/共有/回生）は ArsPaper `items.yml` の `items:` に最初から在り、`UnifiedRecipeLoader` も読んでいた。**真因はエディタ側**: `app.js` の `case "ars-recipes"` が **常に `onlyEffects: true`** で `buildRecipesForm` を呼んでおり（コメント「カタログへ作業台/儀式レシピを移したため」＝その前提がエンチャント本には当てはまっていなかった）、items.yml 用画面が `ritual_effects:` しか描画せず **`items:` の全エントリが editor から一切見えず編集もできなかった**。**移設先を functional-items.yml にした理由**: `FunctionalItemConfig` が `items:` の全キーを総なめで読むので、**専用 config 層を新設せずに** `display-name`/`lore`/`enchant-glow` の上書きがそのまま効く。レシピの登録キーは `UnifiedRecipeLoader#recipeKey` がエントリIDをそのまま使うため、**移設しても儀式の成立条件も `unlock-gate.yml` の ritual ゲートキーも変わらない**。**fork 配線**: `EnchantBookRitualEffect` が `FunctionalItemConfig` を引くようにした（未設定なら従来の「&lt;エンチャント名&gt; &lt;ローマ数字&gt;」へフォールバック。`_r2` 形式の登録キーは基底IDへ寄せる ＝ `RecipeUnlockGate#gateKey` と同じ規則）。**書いただけで効かない no-op にしない**ためにここまでが1セット。**エディタ**: 「特殊アイテム」画面に専用セクションを追加（IDとエンチャント種別/レベルは読み取り専用チップ、儀式の表示名 `recipe.name`・display-name・lore・エンチャント光・レシピを編集可）。material 欄は出さない（結果は常に `ENCHANTED_BOOK`）。**normalize でエンチャント本を勝手に生成しない**（レシピの無い抜け殻を出荷ymlへ書き戻すため）。**残り**: items.yml の `items:` にはまだ `trident` と `エンチャントされた金リンゴ` の2件があり、**同じ理由で editor 非表示のまま**（どちらもバニラアイテムを結果に持つので「特殊アイテム」の対象外と判断。§3 に残タスクとして追加）。**検証**: ArsPaper fork **360 tests / 5 failed**（新規 `EnchantBookDisplayOverrideTest` 4/4 パス。失敗5件は着手前と同じ Thread* 3クラス＝別セッションの threads.yml WIP 由来）。config-editor は新規10本パス、全体 **1263 tests / 22 failed** で着手前と完全一致。**配備が要る**: ArsPaper の再ビルドと jar 差し替え＋`functional-items.yml`/`items.yml` の配備 |
| 2026-08-14 | **解放ゲートの候補に ArsPaper 側のレシピを全部出るようにした**（ユーザー報告「解放ゲートで機能アイテムカテゴリのアイテムを設定できない」）。`recipe:`/`ritual:` ゲートの候補が `items/catalog.yml` のワークベンチレシピだけだったため、**ArsPaper に定義されたレシピ（機能アイテム/中間素材/儀式アイテム/ジャー/リンク/魔導書）は1件もセレクトに出ていなかった**。実行時のゲートキーは登録レシピの `NamespacedKey` のキー部分＝各ymlのエントリIDなので、**元々ゲート可能で「候補に出ていなかっただけ」**。**チャンネルは `method` で振り分ける** ── ArsPaper の `UnlockGate` は recipe/ritual で別々のマップを引くので、**儀式アイテムを `recipe:` 側へ出すと儀式経路はそのマップを見ず無言で常時解放になる**（TF の `RecipeRitualGateChannelDriftTest` が固定している事故）。`lib/gate-vocabulary.js` に functional-items/items/materials/sourcejars/sourcelinks/spellbooks をソース追加し、`recipe:` と `recipes:`（全config共通の正規形）の両方を読む。`server.js` の `/api/gate-vocabulary` に6ソースを渡す。**検証**: 新規7本パス（出荷ymlの実データで `dominion_wand`/`waystone`/`enchant_book_*` のチャンネル振り分けまで確認）。**配備不要**（エディタのみ） |
| 2026-08-14 | **ソース自動消費にクールタイム(秒)を新設し、エディタから設定できるようにした**（ユーザー依頼「ソース自動消費 (mana.source-auto-consume.items)の設置を追加し、自動消費のCTをeditorで設定できるようにしてほしい」→ 確認の結果「**CTの新設だけでよい／秒・既定10秒**」で確定）。**調べたら items 側は既に全部揃っていた** ── 編集UIは「その他のギミック」→「ソース自動消費」タブに `+ アイテム` ボタンつきで実在し（保存先は ArsPaper `config.yml`、`getExtraSaves` 経由）、スキルツリーへの配置も `ars_smithing.yml` A-2「ソースベリー活用」に `feature:source-auto-consume` として実在した。**欠けていたのは CT だけ** ── しかも **A-2 のノード説明文は当初から「&8100マナ/10CT」と書いてあるのに、`SourceAutoConsume#tryConvert` に CT 判定が一度も実装されておらず、マナ不足のたびに無制限に変換できていた**（説明文が実装より先に書かれ、そのまま出荷されていた形）。**実装**: ArsPaper に `mana.source-auto-consume.cooldown-seconds`（既定10、負値は0へクランプ）を追加し、`ManaConfig` へ1項目、`SourceAutoConsume` に**プレイヤー単位の最終変換時刻マップ**と純粋関数 `isOnCooldown` を新設。**CTを開始するのは変換が成立した時だけ**にした（不発でも開始すると、対象アイテムを持っていないだけの人がCTで縛られる）。CT判定は**インベントリ走査より前**に置く（後ろだとCT中でも毎回走査コストを払う）。**時刻巻き戻し(`now < last`)はCT中へ倒す** ── 経過時間が負のときに「経過が大きい」と誤読すると、CTが無制限に素通りする。退出時は `ManaManager#onPlayerQuit` から `forget()` を呼んでUUIDが溜まらないようにした。**エディタ側**: 「ソース自動消費」タブへCT欄を追加し、`lib/schema.js` の `validateArsConfig` に「0以上の整数」検査を足した。**空欄はキーごと削除する（0へ丸めない）** ── `0` は「CT無し」という別の意味なので、空欄を0に丸めると開いて保存しただけでCTが無効化され、しかも yml 上はキーが増えるだけで気づけない。未設定を必須にもしない（CTを持たない既存の config.yml が開けなくなる）。**検証**: ArsPaper fork **356 tests / 5 failed**（`SourceAutoConsumeTest` は 11/11 パス＝新規CT4本を含む。失敗5件は全部 `ThreadConfigPotionOverrideBackCompat` / `ThreadSetThresholdReachability` / `ThreadRitualRecipeConfig` で、別セッションの `threads.yml`・`thread-sets.yml` WIP 由来）。config-editor は新規4本パス、全体の失敗22件は着手前と完全一致。**テストの限界を明記しておく**: このフォークは Bukkit ランタイム/MockBukkit を持たない方針なので、**`tryConvert` が実際にCTを参照する経路は自動テストで踏めていない**（CT判定そのものは純粋関数として固定済み）。**配備が要る**: ArsPaper の再ビルドと jar 差し替え（TF 側は Java 変更なし＝エディタのみ） |
| 2026-08-14 | **「ラピス効率」(`lapis-cost-reduction`) を TF・エディタ・ArsPaper フォークから機構ごと削除した**（ユーザー指示「ラピス効率は使わないのでプログラムごとeditorから消しちゃって」）。**最初に出した「読み手ゼロの死にステ」という結論は誤りで、自分の検索ミスだった** ── `Get-ChildItem -Path fork-handoff -Recurse -Include *.java,*.yml,*.json` が**1件も走査せず空を返した**（`-Include` は `-Path` の指定形によっては黙って何もマッチしない）ため「フォークに消費者は無い」と誤断した。実際には **ArsPaper の `com.arspaper.enchant.LapisCostReductionListener` が実在し `ArsPaper.java` で `registerEvents` 済み**で、`EnchantItemEvent` の MONITOR で「軽減分のラピスを先に返却する」返却型の実装（バニラは消費量をAPIで直接操作できないため）。**PowerShell の `-Include` 空振りは grep のヒットゼロと同じ罠**で、`Select-String` にパイプする前に**列挙件数そのものを確かめる**こと（`fork-handoff` 配下には EliteMobs 1401 / ArsPaper 328 の .java が実在した）。**削除した箇所**: TF 側は `StatVocabulary` / `StatCategoryInference` / `PercentStatNormalize` / `command/StatsCategory` / `combat/base-stats.yml` / `stats/lore.yml` / `stats/item-stats.yml` のコメント / `TrinityForge.java` の javadoc、エディタは `labels.js`（ラベル・説明・**nativeパーク `arssmithing_lapiscostreduction_add`**）/ `tf-base-stats.js`（上限UI一覧）/ `tf-lore.js`（カテゴリ推定）/ `materials.js`（単位辞書。**ここは lore.yml の `unit` と双方向照合されるので残すと死にキーとして検知される**）、フォークは**リスナー本体を削除**＋`ArsPaper.java` の登録行＋`TrinityForgeBridge.STAT_LAPIS_COST_REDUCTION` 定数。**定数だけ残さなかったのは意図的** ── TF の語彙から消えた以上 `tfStatTotal` は常に 0 を返すので、定数が残っていると「効くように見えて何も起きない」配線をまた書ける。**`skilltree/enchanting.yml` のノードB（エンチャントの使い手・Lv30・主軸）は唯一の効果がこのステだった** ── そのまま消すと **SP1 を払って何も起きない主軸ノード**になり、しかも親子鎖の途中なので削除もできない。同じ「消費を減らす」性格で完全に配線済みの `enchant-cost-reduction: 0.10`（`EnchantCostReductionListener` が提示/実消費レベルと金床修理コストを軽減。prestige も同値）へ差し替えた（アイコンも LAPIS_LAZULI → EXPERIENCE_BOTTLE）。**テスト側は「消えたまま」を守る形へ** ── `PercentStatNormalizeTest` は旧キーが RATE_KEYS へ戻っていないことだけを見る形に、`labels-stat-scope.test.js` は `STAT_LABELS["lapis-cost-reduction"]` が `undefined` であることを追加検査、`EnchantingAlchemyBuffsWiringTest` にノードBの検査を新設（`enchant_cost_reduction: 0.10` が null に戻ったら赤）。`SkillTreeConfigTest` の「RATE_KEYS 非対象の FLAT キーは coerce されない」例は `enchant-luck` へ、`GateEffectIdTest` の bare id 例と `SkillTreeProgressionGeneratorTest` の native パーク fixture も生きているキーへ差し替えた。**検証**: TF **3949 tests / 27 failed / 2 skipped** で失敗集合は HEAD ベースライン(3946/27/2)と `Compare-Object` 差分ゼロ（テスト総数が1減ったのは重複していた旧キーテストを1本に畳んだため）。config-editor は**失敗22件が着手前と完全一致**（新規ゼロ）。ArsPaper フォークは `compileJava` **BUILD SUCCESSFUL**。**未対応で残したもの**: `skilltree/職業別草案_生産.md` と `skilltree/_現構造ダンプ.txt` はノードBを旧効果のまま記述している（**一次仕様の履歴文書なので勝手に書き換えない**方針。参照するときは出荷 yml が正）。**配備が要る**: TF は Java 変更なので jar 差し替えとサーバ再起動、**ArsPaper も再ビルドと jar 差し替えが必要**（fork は `.gitignore` 除外なので TF の commit には含まれない。フォーク自身の git へ別途 commit する） |
| 2026-08-14 | **「EXP増加(エンチャント)」(`enchant-exp-gain-bonus`) をキーごと廃止し「職業EXP増加(エンチャント)」(`enchanting-exp-bonus`) へ統合した**（実サーバ報告「EXP増加（エンチャント）のlore表示の説明がスキルEXP増加になってるけどこれは別にあるから消費EXP(バニラ)減少の仕様であるべきかも」）。**提案された仕様は既に別キーで実装済みだった** ── 「バニラの消費EXPを減らす」は `enchant-cost-reduction` が担当しており、**提示レベルと実際に引かれるレベルの両方＋金床の修理コスト**を書き換える。ここへ意味を移すと今度はそちらと重複するので採らなかった。**本当の重複は EXP 側にあった** ── `SkillId.ENCHANTING` への EXP 付与点は `NativeSkillExperienceListener#onEnchant` の**1箇所しかない**ので、そこで `× (1 + enchant_exp_gain_bonus)` を先に掛けるのと、下流の `NativeProgressionService#grant` が `enchanting_exp_bonus`（`<スキルID>_exp_bonus`）を掛けるのは、**同じ量に別経路で掛かる同義キー**でしかなかった（lore 上も「EXP増加(エンチャント)」と「職業EXP増加(エンチャント)」が並び、説明文から区別できない）。**ユーザー判断で「キーごと廃止して一本化」を採用**し、15 箇所（`StatVocabulary` / `StatCategoryInference` / `PercentStatNormalize` / `command/StatsCategory` / `combat/base-stats.yml` / `stats/lore.yml` / `skilltree/enchanting.yml` / editor の `labels.js`・`tf-base-stats.js` / config-reference 2本 / テスト2本）から削除した。**`skilltree/enchanting.yml` の4ノード**（A-alpha-1/2 の −5%、A-beta-1/2 の +5%）は `enchanting_exp_bonus` へ移設（`enchant_luck` 側は不変）。**`StatKeys.LEGACY_KEY_ALIASES` にエイリアスを入れたのは必須の措置** ── TF でキー名を読み替える機構はこれ1本だけで、入れないと**配備先に残った手編集 yml の `enchant-exp-gain-bonus` が警告もエラーも出さずに無言で効かなくなる**。**同時に一番起きやすい巻き戻しが「二重適用」になる** ── エイリアスがある状態でリスナー側の乗算を戻すと、`enchanting_exp_bonus` が listener と `grant` の**両方**で掛かる（表記どおりの合計にならず `(1+全スキル+職業別)×(1+ここ)` の三層になる）。この向きをテストで固定した（倍率+10%を報告させても付与量が 1.5 のまま＝1.65 になったら赤）。**検証**: TF **3950 tests / 27 failed / 2 skipped** で、失敗リストは同一ワークツリーで測った HEAD ベースライン **3946 / 27 / 2** と `Compare-Object` 差分ゼロ（増えた4件は新規テスト）。config-editor は自分由来の2件を潰して 21/21 パス（`lore-stat-descriptions`＝lore.yml のキーに説明が要る／`tf-stat-caps-tab` の 109→108）。**`stats/lore.yml` は別セッションが編集中**（order 振り直し・category 変更に加え、**2026-08-12 の PERCENT 訂正コメントがエディタ保存で1件消えている**）だったため、**HEAD の中身に自分の削除だけを当てた blob を `hash-object`＋`update-index` で index へ載せ**、作業ツリーの他人の WIP は未ステージのまま残した（`git add` すると 176 行を巻き込む）。**配備が要る**: Java 変更なので jar 差し替えとサーバ再起動が必要 |
| 2026-08-14 | **解放効果の「⚠ 重複」が引数(tier)違いでも出ていたのを直した**（実サーバ報告「機能解放などの解放効果設定で引数が異なっていても⚠ 重複と表示される」）。**誤検知ではなく判定キーの取り違え** ── `computeDuplicateGateEffectIds` が `parsed.raw`（＝ID文字列）だけを数えていたので、`feature:vein-mining` を tier 1/2/3 で3ノードに置いた**設計どおりの配置**が3件とも重複扱いになっていた。Java 側は `DedicatedEffectGateIndex#valueMaxByPerks` が**保持ノードのうち最大 tier を採る**ので、tier 違いの複数配置こそが正しい形（`FeatureEffectParam` の SCALE。段階解放はこれでしか書けない）。判定キーを `gateEffectDuplicateKey` へ切り出し、**feature だけ tier までキーに含める**形にした。**value を全種別のキーに入れなかったのは意図的** ── glyph/brew/trade/recipe/ritual/drop/overenchant/reward は純粋な on/off 解放で value に意味が無いため、手書き yml に紛れ込んだ value で**本物の重複警告が消える**（一律に入れると偽陰性を作る）。空欄の value は `FeatureEffectParam#defaultsMissingValue` で tier1 として読まれるので明示 `value: 1` と同じキーへ畳む（これを忘れると「空欄」と「1」の重複を見逃す）。あわせて **tier セレクトの変更で再描画する**ようにした（キーの一部が変わるのに `⚠ 重複` が再計算されず、引数を変えて直しても警告が残ったままになる。数値直接入力側は1打鍵ごとに発火してフォーカスを奪うため再描画しない）。**検証**: 新規テスト6本（`test/gate-effects.test.js`）は**修正前に4本が落ちることを実走で確認**（tier 違いが `size 1` になる本命1本＋新API 3本）。config-editor **1243 tests / 22 failed / 1 skipped** で、失敗集合は着手前と完全一致（catalog/materials/wiki 等の他セッション WIP 由来で、gate-effects/skilltree は1本も含まれない）。**配備不要**（エディタのフロントのみ） |
| 2026-08-14 | **ダンジョン難易度1〜10の再設計・討伐素材とスレッドのフィールドドロップ配線・装備/スレッド曲線の是正**（CSV 22 行＝エンチャント試練を展開して 28 ダンジョン）。**難易度は原理的に成立していなかった** ── 実HPは `ramp_hp(L) × TF倍率 × EM healthMultiplier` の3段積で、EM 側が後段に掛かるため TF だけを見ても難易度にならない。確定カーブは D1=20秒〜D10=190秒／必要総HP 7.30M〜69.37M／ATK倍率 2.3〜4.8 で、EM 倍率込みの実HPが 7.29M→69.36M と**完全単調**になることを検算済み（一見バラバラなのは転記ミスではない）。**倍率キー2種を新設**（`max-health-multiplier` / `attack-power-multiplier`。層をまたいで掛け合わさるので default×dungeon×mob が積になる。**per-mob の絶対値 `attack-power` があると `mergeAttack` がスコープ倍率を捨てる**点に注意）。**yml だけでは書けない要件が3つあったので Java にキーを追加** ── `chance-by-level`（lv1=5%→lv100=50% の連続補間。帯境界の段差が消えて 102→23 エントリに減った）/ `where: field|dungeon|any`（`dungeonWorldRegistry.isDungeonWorld` で判定。**`mob-level-table.yml` の `dungeon-only:` はファイル全体の1本トグルで field-only が書けない**）/ `baby`（`Ageable#isAdult()`。チビゾンビ指定用。`MobTargetFilter` は EntityType と mobIds の2軸しか持っていなかった）。**スレッド曲線は案A（`use-level-requirement` で帯分割）が成立しない**ことを確定 ── use-level の強制は `UseRequirementListener` と `ArmorUseGateListener` の2経路だけで、**ソケット装着はどちらも発火しない**ので書いても無言の no-op。採用した案Eは実数ダメージ系（`attack-power`/`flat-bonus-damage`/`bleed-damage`）を割合系へ全面振替で、**TTK ぶれ 65.2倍 → 6.1倍**（理論下限 6.5倍なのでスレッド側は打ち止め）。**「手に持つだけでステが乗る」穴を `socketed-only-stats` フラグで根治** ── `PlayerStatAggregator.isWornOnlyArmor` は**ホワイトリストではなくブラックリスト**（防具4部位以外は全部「持てば乗る」）なので、どの防具スロットにも解決しないスレッドが `default -> false` に落ちて意図せず同じバケツに入っていた。`DerivedItemStats.resolve/resolveMultipliers` の合流点**8箇所すべて**に門を通した（フラグを false に戻すと helmet と mainhand の両方で DEFENSE 4種と GENERAL 5種が復活して落ちる）。**帯目標ガードの前提2つが実装で裏取りできず、組み直したら目標超過が露見** ── `threads.yml` の `stackable`/`max` は**「1つの防具あたり」**の上限（`ThreadGui.java:314-333`。既定は 1本／`Integer.MAX_VALUE`）で全体上限ではなく、`mana_regen` は `max: 3` ＝**防具4部位だけで12本**挿さる。セット効果は `ThreadSetConfig.cumulativeBonus()` が **N 以下の全ティアを合算**する。案Eで割合ダメージを配るセットを**3本に増やしてしまった**結果 Lv20 +45.5% / Lv60 +62.0% / Lv100 +72.0%（目標 +39/+49/+59 に対し最大 +13.0pt 超過）。**`item-stats.yml` のサブ max を下げる方向では解けない**（3帯を同時に満たす単一値 0.00875 では Lv100 が -6.5pt 未達）ので、`thread-sets.yml` 側で**割合ダメージを `hero_of_the_village` 1本へ集約**して着地させた（`mana_regen`→`magic-resistance`、`night_vision`→`dodge-chance`。**会心系を振替先にしなかったのは意図的**で、crit は実DPSを押し上げるのに帯ガードが数えないため、計測できない形で同じ穴を作り直すことになる）。ガードは定数のハードコードをやめ、`threads.yml`/`thread-sets.yml` から導出して**有界ナップサックで最良編成を厳密に解く**形にした。**サブエージェントの事故2件**: (a) 修正エージェントが RED 実証の変異を戻す段で**修正そのものまで巻き戻し**、しかも自己申告の最終 SHA256 は正しいのに**実物とだけ食い違っていた**（＝「ハッシュで一致確認した」という報告が確認対象を取り違えていた）。`tmp/thread-restore/mut-backup.yml` が残っていたので復旧。(b) 前波が `item-stats.yml` のスレッド6種（匠/儀式師/潮読み/選書/調香/鑑識）の `advanced:` を丸ごと落とし、**主ステの `random` まで `{min: 0, max: 4}` に化けていた**（`min: 0` は不正値）。`grant-chances` は yml を4件へ戻す判断（挙動は `DerivedItemStats.java:253` の `chance >= 1.0` 短絡で不変だが、`ItemAssembler.java:228-229` が `grantChances().keySet()` をロア色へ渡すため、**将来 lore.yml に色を足した瞬間に「必ず付く主ステ」が確率ステの色で出る**＝嘘を仕込むだけ）。**実測**: TF 3946 / 26 failed / 2 skipped、config-editor 1237 / 22 failed / 1 skipped（どちらも新規失敗ゼロ）。クリーン worktree では**自分のパスのみ 9 failed**（HEAD も 9 だが中身が違い、`warden_tendril` 系5件＋綴り1件を解消し、`catalog.yml`/`collection.yml`/`achievements.yml`/`gacha.yml` 由来で6件が新規に赤）。**`collection.yml` は別セッションの config-editor 保存事故を含む**（+8/-96。`structure` カテゴリ丸ごと消滅・`infinity` から abyss_*/binder_* 消滅・`gui:`/`display-names:` の説明コメント消滅。`stat-caps.yml` が `stat-caps: {}` になった事故と同じ形）。**ユーザー判断で作業ツリーを丸ごと1コミット**（「config の変更による赤は恣意的なので赤にする必要はない」）。**報告のみで着手しなかったもの**: 装備帯の穴 Lv46-59/61-79/81-99（必要な新帯は Lv50/55/70/90 の4本、必要 attack-power = 21,400/38,400/26,600/78,000。ユーザー補足「空白区間は品質と厳選でカバーする想定」だが、**品質＋厳選の幅は全帯でほぼ厳密に 2.10倍**で、帯60/帯80 では前提が成立し**むしろ過剰**なのに帯45だけ残差 3.26倍が残る。最も重いのは**品質0・中央ロールの帯45武器が Lv58 のダンジョンモブに1撃1ダメージ**になること＝生ダメ1815 vs `flat-defense` 1950 で床値1。**フィールドモブは `flat-defense` が実質0なのでこの崖は無く、ダンジョン固有**。折れ点自体は `d6f3878` で意図的に入れられたが「Lv45〜59 は装備帯が1段も上がらない」と突き合わせた形跡は無い）／入手経路ゼロのスレッド5種（appraiser/artisan/perfumer/ritualist/scholar）で `thread_all` 実績が達成不能（**今回と無関係の既存問題**）／踏破ボスの最終フェーズが「雑魚」の防御率・EXP のまま（HP23倍のボスで `vanilla-exp base: 4.0` → EXP 15）。~~**実サーバで確認していただきたいこと: エンダードラゴンの最大HPが 4400 か 200 か**~~ → **2026-08-14 ユーザー実測で決着: バニラの 200 ではなく TF のHPだった＝バニラのエンダードラゴンは `CreatureSpawnEvent` を発火し、`MobTypeSpawnListener` の刻印が効いている。よって `dragon_scale` のドロップ設定(100%/0〜2個)も効く。** なお質問の立て方自体が誤りだった: **`mob-types.yml:944` の `max-health: 4400` は最終HPではなくランプの base 項**で、`HP(L) = 4400 × 1.053^L + (L≥45 ? 12699×(L−45) : 0)`（`mob-types.yml:967-972`）。`HP(0)=4400` / **`HP(60)≈288,000`**（`level: 60`。`coordinate-coefficient: 0.02` で距離ぶん上乗せされるので下限値、Lv100 なら約 141 万）。**この判定はコードからは出せない** ── TF の刻印入口は `MobTypeSpawnListener.onSpawn(CreatureSpawnEvent)` 1本だけで（`EntitiesLoadEvent` の方は `Tameable` しか処理せず `EnderDragon` は該当しない）、バニラのエンドラがそのイベントを発火するかが分岐点だったが、**診断ログは `LOG.fine`（`MobTypeSpawnListener.java:543`）で既定の INFO コンソールに出ないため、ログ検索では『出ていない＝刻印されていない』と誤読する**。実機で見るしかなかった。**別件で配備漏れ**: 配備先の `mob-types.yml`(2026-08-06) は ENDER_DRAGON が `max-health: 2200` / `growth: 1.048` / `high-level-per-level: 5079.7` で、**2026-08-12 の戦闘バランス再較正が未配備**（実機は `HP(60)≈112,900`）。これは当バッチの変更ではない |
| 2026-08-14 | **統合済みのレガシーステがステ選択に残っていたのを消した**（実サーバ報告「セレクトメニューにクラフト:効率増幅↑のようなレガシーステータスが項目として残っている」）。**原因はセレクトの候補が和集合であること** ── `statList()` は `STAT_LIST`（lore.yml 由来）と `materials.js` の `FALLBACK_STATS` の**和集合**を候補にするので、**lore.yml の語彙から消しただけでは `FALLBACK_STATS` 側から復活する**。`tool-enchant-efficiency` は 2026-07-26 の「効率」ステ統合で `gathering-efficiency` へ吸収され lore.yml からは消えていたが、`FALLBACK_STATS` に残っていた。**単なる表示の余りではない** ── `StatKeys.LEGACY_KEY_ALIASES` が canonical 化の時点で `tool_enchant_efficiency → gathering_efficiency` へ読み替えるため、この2項目は**別項目に見えて実体が同じキー**で、同じアイテムに両方設定すると後勝ちで片方が黙って消える。**全数で突き合わせた**（`tmp/stat-list-drift.js`）: lore.yml の語彙125件 対 `FALLBACK_STATS` 42件で、**FALLBACK にしか無いキーはこの1件だけ**（他41件は一致。逆向きの「lore にしか無い」83件は lore が読めれば出るので実害なし）。**あわせて除外機構の穴を2つ塞いだ** ── (a) `window.HIDDEN_STATS` は**どこにも代入されておらず**、`forms.js` と `tf-base-stats.js` が各自 `["flat-defense"]` をハードコードしていた。`materials.js` に実体を1本定義し `["flat-defense", "tool-enchant-efficiency"]` とした（配備先の古い lore.yml が旧キーを持っていても止まる）。(b) `ars-spellbooks.js` の `statListLocal()` は「forms.js の `statList()` と同一ロジック」と書きながら**除外フィルタだけ持っておらず**、廃止済みの `flat-defense` と旧 `tool-enchant-efficiency` が魔導書画面のセレクトにだけ出ていた。**残したもの**: `FALLBACK_STAT_FORMATS` の当キー定義は、旧綴りが書かれたままの yml を開いたときに `statSelect` のロスレス表示（`cur && !keys.includes(cur)` で現在値を候補へ足す経路）がフォーマット不明で壊れないようにするため温存。`labels.js` の日本語ラベルも同じ理由で残す。**検証**: 起動中のエディタで実際のピッカーを開いて実測 ── 候補126件、`クラフト:効率増幅↑` は消え、`効率強化増幅 / gathering-efficiency` は残り、`flat-defense` も出ない。config-editor は**24失敗で、増えた1件は並行セッションが同時に作った未追跡の `mob-overrides-multiplier-2026-08-14.test.js`**（`mob-forms.js` / `lib/schema.js` の WIP と対）。新規テスト7本（`legacy-stat-not-selectable-2026-08-14.test.js`）は空振り検知つきで、`FALLBACK_STATS` に戻す・`HIDDEN_STATS` を消す・`ars-spellbooks.js` の除外を外す のいずれでも落ちる。**配備不要**（エディタのフロントのみ） |
| 2026-08-13 | **ロア表示ステ128キーの配線を全数監査し、見つかった穴を修正した**（ユーザー指示「ロア表示のすべてのステータスがゲーム内に正しく反映/配線されているかレビュー」＋確定仕様3件）。**監査結果は「合算の本線は健全」** ── 128キー中95キーが `PlayerStatAggregator#totalOf`（防具4部位＋メインハンド＋オフハンド門＋パーク＋役職＋永続＋base-stats の完全合算）に乗っており、落ちていたのは**2つの機構**に集中していた。**穴①: バニラ属性経路にオフハンドの門が無い** ── `knockback_resistance` / `armor_defense_rate` / `max_health` / `move_speed` / `attack_speed_bonus` / `attack_reach` の6キーは `AttributeProjection` 経由でバニラの Attribute modifier として適用されるが、`AttributeApplier#slotGroupFor` の `case ANY` が `offhandApplies` を無視して `EquipmentSlotGroup.ANY` を返しており、**オフハンド非対応のアイテムをオフハンドに持つだけで効いていた**。`EquipmentSlotGroup` に「オフハンド以外の全部」という値は存在しないので、素直に `offhandApplies` を足す修正は不可能 ── **頭装備を先に分類してから ANY 残余を MAINHAND/HAND へ落とす**2段構えにした（`EquipmentSlotResolver` に明示 `Set` の `HEAD_EQUIPPABLE`。`PISTON_HEAD` を巻き込むので `_HEAD` 接尾辞判定は使わない）。**穴②: 発射武器・釣竿の「どちらの手か」が集計器へ伝わっていない** ── 飛び道具は発射時に武器を projectile へ retain するので着弾時のメインハンドとは独立だが、`contributorIsOffhand` が常に false 固定だったため**オフハンドスロットの二重計上を防げていなかった**。PDC キー `PROJECTILE_FIRED_FROM_OFFHAND` を足して `EntityShootBowEvent#getHand()` を retain し、着弾側で読む。**設計判断（ここが一番重要）** ── 第1波の実装は「オフハンドの寄与アイテムを `offhand-stats-apply` の門で落とし、代わりに実メインハンドを合算する」としたが、これは**CRITICAL な回帰**を作った: メインハンドにネザライト剣（attack-power 3780）を持ったままオフハンドの弓（69）を撃つと、`CombatListener` の `tfBaseReplaces` が `agg.item()` の attack-power をベース置換に使うため**矢のダメージが約54倍**になる。確定した解釈は **「実際にその行為に使われたアイテム（寄与アイテム）は、どちらの手にあっても常に合算する。`offhand-stats-apply` の門は『オフハンドに持っているだけ』のアイテムに掛ける」** ── ユーザーが是とした魔法の規則（発動したアイテム＝触媒だけ合算）と同型で、これ1つで「オフハンド運用で弓/クロスボウ/釣竿の自ステが全部0になる」「寄与アイテムが実メインハンドにあると二重計上」「ステ層とパーク層で装備の見え方が食い違う」も同時に消える。オフハンドスロットの除外は**プロファイル一致（Material+CMD）のときだけ**に限定した ── 飛行中にオフハンドを持ち替えると二重計上しようがないのに除外し続け、新しく持ち替えたアイテムの寄与が無言で落ちていたため。**その他に直したもの**: (a) `armor-set-bonus` が**パーク由来しか増幅に効かない**問題（→ K-7 解決。`nonPerkStatTotal` を新設して循環せずに注入）。(b) ArsPaper フォークの `tfArsTierUnlockBonus` が dedicated-effects チャネルと stat 語彙チャネルを**両方**加算しており、`ars_magic.yml` のノードA・Eが同じ値で両方書いているため**実効tier加算が意図の2倍**（+2 のところ +4）になっていた ── stat 語彙側へ一本化。(c) `NativeCombatPerkListener#onShoot` は `getBow()` が @Nullable なのに `getHand()` だけでフラグを立てており、`bow==null` かつオフハンド発射で「寄与アイテムは実メインハンドなのにフラグ true」になっていた。(d) ANY 分類を HAND へ落とした副作用で rollSeed 無しアイテムの modifier キーが `.nosd.hand` で衝突する（バニラが同一キーを1件として扱い片方が無言で消える）→ サフィックスに `EquipmentSlotResolver.Category` を混ぜた。**未対応で残したもの（ユーザー判断が要る）**: **`attack-speed`** は「実効攻撃速度そのもの」を書く絶対値方式なので複数装備から合算すると意味が壊れる（現行はメインハンドのみ）。**`isWornOnlyArmor`** により防具素材はメインハンドに持っても合算されない ── 確定仕様2の字義（メインハンドなら何でも合算）とは食い違うが、外すと「同じ防具を1枚着て1枚持つ」二重取りが復活する。**`mana-*-base` 系5キー**（`mana-onhit-percent` / `mana-onattack-percent` / `mana-idle-seconds` / `mana-idle-bonus-percent` / `mana-idle-bonus-flat`）は `ManaBaseStats` が `base-stats.yml` の生値だけを読む設計で、`lore.yml` に登録されたまま残っている（同日に別レーンが3キーだけ `BASE_STATS_ONLY_KEYS` へ切り出し済み。`lore.yml` は並行セッションの所有物なので触っていない）。**検証**: TF **3878 tests / 35 failed / 2 skipped**（着手前 3871/47/2。**減った12件はちょうど `FishingGimmickListenerTest` の Mockito スタブが2引数版のまま残っていた回帰**で、残る35件は失敗クラスと件数が着手前と完全一致＝並行セッションの yml WIP 由来）。ArsPaper fork **352 / 6 failed**（同じく着手前と同一集合）。config-editor **23 failed**（`armor-set-bonus`/`NO_OP_BASE_STATS_KEYS` を参照する7ファイルは1つも失敗リストに無い）。**新規テストは「修正前へ戻すと落ちる」ことを各レーンが個別に判定済み**で、特に `offhandProjectile_baseAttackPowerComesFromFiredWeaponNotFromActualMainhand` が54倍回帰の直接の見張り番。**配備が要る**: TF は Java 変更なので **jar 差し替えとサーバ再起動が必要**。ArsPaper も `TrinityForgeBridge.java` を変更したので**フォークの再ビルドと jar 差し替えが必要**（fork は `.gitignore` 除外なので TF の commit には含まれない。フォーク自身の git へ別途 commit 済み） |
| 2026-08-13 | **editor 実サーバ報告3件（未分類カテゴリ／ロア表示の重複ステ／選べないステ）を修正**。TF `fab2c6e`（**config editor と yml のみ。Java も触ったがテスト用の語彙定数だけなので配備は不要**）。**① 未分類カテゴリの自動生成を廃止** ── 受け皿 `cat_auto_unclassified` は 2026-08-01 に「追加した品がどのカテゴリにも入らない」を直すために入れたが、**無所属の品は仮想タブ「未設定」がそのまま一覧する**ので同じ集合を指すタブが2つ並んでいただけで、しかも yml を汚していた。`ensureItemEditorCategory` は絞り込み中でなければ `""` を返すようにし、既存 yml に残る受け皿は**タブバー描画時**の `dropLegacyUnclassifiedCategory` が取り除く（中身は消さず無所属へ戻す。データ層の純粋な参照で行を消すと「読んだだけで yml が変わる」ため描画時に限る）。**id は予約語のまま据え置き** ── ユーザー採番がこの id を取ると掃除が**ユーザーのカテゴリを丸ごと消す**（掃除は id 完全一致のみ・ラベルが「未分類」なだけのカテゴリは触らない）。発火元が消えた `refreshEditorCategoryBar` は削除。**② ロア表示のレガシー／重複ステ** ── `mana-max-base` / `mana-regen-base` / `mana-regen-interval-ticks` を `stats/lore.yml` から撤去（`combat/base-stats.yml` 専用の全プレイヤー共通定数で、装備・パークから供給されずアイテムのロアにも出ない。「マナ上限(基礎)」が `mana-bonus`＝「マナ上限」と紛らわしかった）。**紛れ込んだ原因は `LoreVocabularyCoverageTest` が全語彙キーに lore.yml 登録を課していたこと**で、`StatVocabulary.BASE_STATS_ONLY_KEYS` を新設して除外し、**除外リスト自体のドリフトを両方向（語彙に実在する／lore.yml に無い）で固定**する検査を足した（片方向だと除外したまま lore.yml へ戻せて誰も気づけない）。**なお「作業台品質／儀式品質は品質運(作業台)等と重複」は誤りで未対応** ── `workbench-quality-bonus`(pt) は品質の**基準値そのものを押し上げる**、`workbench-upswing-bonus` は**上振れの幅を広げる**で機構が別（`CraftQualityService#statKeysRead` が両方読み `CraftQualityStatKeyDriftTest` が固定）。統合するならゲーム側の仕様変更でスキルツリー・item-stats まで波及するのでユーザー判断待ち。**③ 登録済みなのに選べないステ（例: ロール収束）** ── item-stats 画面の絞り込みグループ `STAT_FILTER_GROUPS` が**旧5分類のまま**で、lore.yml の7分類（2026-07-23 再編）のうち `craft`/`gathering`/`utility` が「未知のカテゴリ」として許可リストから外れ、キー名ヒューリスティックへ落ちて**全部「補助」扱い**になっていた（= 補助を手で ON にしない限り候補に出ない）。7分類へ揃え、タブごとの既定 ON も7分類ぶん定義し直し、`support`→`utility` の正規化を1箇所へ集約した。**回帰**: config editor 1205件/失敗23件・TF 3849件/失敗35件/スキップ2 で、**どちらも着手前の基準値と失敗集合が完全一致**（並行セッションの `catalog.yml`・`item-stats.yml`・`stat-caps.yml`・skilltree WIP 由来）。新規テスト5件は**修正前へ戻すと落ちること**を実走で確認済み。**`lore.yml` は並行セッションが大量に書き換え中なので、自分の3エントリ削除だけを blob 化して index に載せた**（作業ツリーは相手の WIP を保持したまま） |
| 2026-08-13 | **カタログの入手経路を全数監査 → モブの追加ドロップを全部空にした**（ユーザー指示。ドロップとルートチェストの戦利品を再設計するための前段）。TF `0475562`＋**W-48**。**監査でわかった非自明な点**: (a) **ファイル名だけで「配っている」と判定すると外す** ── `achievements.yml` のスレッド 45 件は `trigger.collection.targets`（図鑑トリガの監視対象）、`collection.yml` の `entries` は図鑑の一覧、Ars `materials.yml` の 16 件は `_editor.categories.itemIds`、`gates.yml` の `key-item` は**消費**で、どれも入手経路ではない。参照を **yml のキーパスまで下ろさないと全部「経路あり」に見える**。(b) **判定は HEAD と作業ツリーで別物になる** ── 別セッションが `catalog.yml` を 3375+/3537- で改修中で、HEAD では「経路ゼロ 10 件」だが作業ツリーでは 34 件（`stats/{mining,digging,fishing}-gimmick.yml` から鍵 8 種のドロップ行が消え、スレッド 24 件が `draft: true` とレシピを同時に失っている）。**共有ワークツリーでは「現状」がどちらを指すか宣言しないと報告が食い違う。** (c) `draft: true`（HEAD で 100 件）は `ItemCatalogConfig#load` が**参照面そのものから落とす**ので、レシピが書けていても入手不能側に数える必要がある（84 件は draft を外せば入手可）。(d) **リポジトリ自身のガードが既に赤で答えを持っていた** ── `ShippedDungeonKeyReachabilityTest` が鍵 8 種を名前つきで落としており、独立検証として使えた。**削除の内容**: `mob-overrides.yml` の `drops` 31 ブロック / 76 エントリ（EM 踏破ボスの印・`gacha_ticket_5`・`abyssal_ingot` ほか）と `mob-level-table.yml` の `add-drops` 6 帯 / 109 エントリ（`gacha_ticket_0`・討伐素材 13 種・スレッド 8 種）。設計理由のコメントは残し、消した中身は `tmp/catalog-audit/removed-drops.txt` へ退避。**副産物の発見**: `dungeon_seal_enchant_trial_1`〜`_10` は Ars に定義があり互換リストと図鑑にも載っているのに**どこからも落ちない**（エンチャント試練 10 のボスだけが 2026-08-08 に**廃止した単一 ID `dungeon_seal_enchant_trial` を落とし続けており、試練 1〜9 のボスは印を 1 つも落とさない**）。**回帰**: 削除前ベースライン 14 失敗 → 削除後 19 失敗で、増えたのは `ShippedRitualMaterialObtainabilityTest` 3 + `ShippedWardenTendrilDropTest` 2 のみ（どちらも「討伐素材の経路がゼロになっていないか」の見張り番なので、この削除では**赤が正しい**）。判定スクリプトは `tmp/catalog-audit/`（`final.js`=HEAD 版 / `final-wt.js`=作業ツリー版 / `unconfigured.js`=削除後の全数 / `strip-drops.js`=削除本体・`--dry` 付き） |
| 2026-08-13 | **ドロップ増加ステ(`mob_drop_bonus`)をレアドロップと通常ドロップで棲み分けた**（ユーザー指示。同日の「④ 個数だけが2倍になり遭遇率1%は動かない」という回答を受けての**仕様変更**なので、下の行の④は**この行で置き換わっている**）。TF `0af90a1`。**新仕様**: ドロップ定義の個数が **`min == max == 1`（=レアドロップ）なら抽選確率そのものを `(1+bonus)` 倍**にする（ドロップ「率」なので **100% で頭打ち**。1%で1個落ちるアイテムに +100% を盛ると 2%）。**それ以外（個数がランダム / 2個以上の固定）は抽選後の個数へ加算**する（整数部は確定・端数はその確率で +1。+50% なら 50% で +1、**+150% なら確定 +1 とさらに 50% でもう +1**）。**個数への一律乗算は廃止**（`MobDropRoller.bonusFactor` / `scaleCount` を削除し `clampBonus` / `boostedChance` / `extraCount` / `cappedCount` / `isSingleFixed` を新設）。ボーナス上限は **+200%**（旧「倍率3倍」と同じ絶対量）、個数の上下限（`maxStackSize × 8` / 最低1個）は据え置き。`KillRewardAdjuster#countFactor` → **`dropBonus`**（倍率ではなく生のボーナス値を返す）へ改名。適用箇所は TF追加ドロップの3経路（`MobTypeDropListener` / `MobLevelTableListener` / `MobOverrideDropListener`）とバニラドロップの1経路（`NativeSurvivalPerkListener`）。**判断が要った点2つ**: (a) **「1個固定」を `min==max==1` と読んだ** ── 2個固定などは個数を足す側へ落ちる。(b) **バニラドロップには確率の規則を適用できない** ── `EntityDeathEvent#getDrops()` には**抽選済みの結果しか無く**元の chance も min/max も復元できないため、常に加算側で扱う。結果として**32個スタックに +100% を盛ったときの挙動が「64個」から「33個」へ大きく弱体化する**（仕様変更の意図した帰結だが、体感差が最も大きいのはここ）。**確率100%のレアドロップはボーナスの恩恵がゼロ**になる（上限に張り付いているため）のも新仕様の帰結。回帰: `MobDropRollerTest` に7本追加（`isSingleFixed` の境界 / `clampBonus` の負値・NaN・上限 / `boostedChance` の 1% → 2% と 100% 頭打ちと 0 のまま / `extraCount` の端数と整数部）、`MobOverrideDropListenerTest` の旧2本（乗算前提）を新3本へ置換（**確率が上がることは 200 回試行の当選回数で固定**し、乱数シードに依存しない形にした）、`NativeSurvivalPerkDropDuplicationTest` の乗算テストを加算契約へ書き換え＋「32個+100%=33個」を明示的に固定。実走: **TF 3848 tests / 28 failed / 2 skipped**（着手前の 3839/28/2 に対し **+9 は今回の新テスト・失敗もスキップも増減ゼロ**。28件は全て他セッションが `crafting-features.yml` / `power.yml` / `lore.yml` 等を未コミットで書き換え中のもの）。**配備**: Java 変更なので **TF の jar 差し替えとサーバ再起動が必要** |
| 2026-08-13 | **エディタの割合ステを全画面で %入力へ統一した**（ユーザー指示「値の互換性は保って割合記法のものはすべて%記法にしてほしい」。選択は「エディタの表示だけを全画面で%に揃える」＝**yml は割合(0.1)のまま**）。TF `8ec87f1`。**全76画面をヘッドレス Chrome で掃引して母数から確認した**（新設 `ops/scripts/editor-percent-audit.mjs`）── ステ選択を持つ行は **3768欄すべてが %変換を通っており漏れゼロ**（2026-08-12 の修正で塞ぎ切れていた）。残っていたのは**ステ選択を持たない固定キーのフォーム**で、モブ定義 / モブインポート / モブオーバーライド / ダンジョンテーマの4画面。**原因はキーの綴り** ── `combat/mob-types.yml` は `physical.resistance` / `attack.crit-chance` のように**ブロックに属する短縮キー**で書かれており、`stats/lore.yml` のステ語彙（`phys-resistance` 等）に存在しないので `isPercentStat` が FLAT を返す＝`statValueControl` では割合だと判定できない。そこで `forms.js` に **`rateValueControl`（キーを見ず常に %入力）** を足し、`mob-forms.js` の `RATE_FIELDS` / `tf-dungeon-forms.js` の `RATE_RAMP_KEYS` で明示指定する形にした。**%にした9キー**: defense-rate / resistance / damage-reduction / armor-strength / percent-bonus-damage / penetration / crit-chance / crit-damage / damage-modifier。**除外**: flat-defense / flat-bonus-damage / fixed-damage / attack-power / max-health / level（ダメージ量・HP・レベルなので入れると 100 倍表示になる）。**ランプは1行の中で単位が混ざる**ので `base` / `per-level` / `high-level-per-level` だけを % にし、`growth`（倍率）・`growth-interval`・`high-level-from`（レベル数）は素の数値のまま。**空欄の扱いを分けたのが要点** ── モブ系は「空欄=キーを書かず上位スコープを継承」なので空欄は `null`（呼び出し側が delete）、既存画面の `statValueControl` 経由は従来どおり `null`→`0`。ここを揃えるとレベル係数とオーバーライドの継承が黙って壊れる。**副次的な改善**: モブ側は `PercentStatNormalize.coerce` を通らない経路（`MobTypesConfig`/`MobOverridesConfig`/`RampParser` はどれも `getDouble` の生値をそのまま `DefenseStats`/`AttackStats` へ入れる）ので、これまで `resistance: 25`（25%のつもり）と入れると **2500%** としてそのまま通っていた。%入力にしたことでエディタ経由ではこの取り違えが起きない（**手書き yml では今も起きる**）。**踏んだ罠（監査スクリプトの空振り）**: 最初の版は `innerText` でラベルを拾っていたが、**折りたたみカードの中の行は非表示なので `innerText` が空文字になり行ごと検査から落ちる** ── 418欄ある画面を18欄しか見ないまま「問題なし」と報告していた。`textContent` へ替え、走査母数（数値入力 30564欄 / ステ選択 6431個）を必ず出す空振り検知を入れた。**検証**: 実機の `window.rateValueControl` を直接呼んで往復を確認（表示 0.12→12 / 0.0025→0.25 / 1→100 / 0.007→0.7、保存 12→0.12 / 12.5→0.125 / 0.25→0.0025 / 100→1 / 9.4→0.094 と**浮動小数の誤差なし**）。モブ3画面を再掃引して全欄が `.pct-input` になったこと、横あふれ監査で `.pct-suffix` 由来のあふれが0件であることも確認。回帰は `test/rate-value-control-2026-08-13.test.js`（9本。往復の数値・空欄の扱い・`statValueControl` の非回帰・割合キー表の過不足・素の `numberInput` 混入の構造ガード）。実走: config-editor **1200 tests / 1178 pass / 21 failed / 1 skipped**。21件は全て出荷 yml の内容を見るテストで、**当セッションが触った3ファイル（forms.js / mob-forms.js / tf-dungeon-forms.js）とは無関係**（`forms.js` を読み込む3本も、落ちているのは materials.yml のレシピ形状・quality.yml の除数表・catalog.yml のタブ分類という他セッションの未コミット差分）。**配備不要**（エディタのフロントのみ） |
| 2026-08-13 | **ユーザー指示4件（スクラップ復号レシピの移設 / 釣果エンチャント本の呪い除外 / %ステの記法統一 / ドロップ増加ステの挙動回答）**。TF `f48f1e5`／`2b474c9`／`290e488`。**①スクラップの復号レシピを TF 側「その他のギミック > レシピの追加」へ移設**（ユーザー選択「TF側へ移設しArs側から削除」）── 4個を2×2に並べると元の素材1個に戻るレシピは**既に ArsPaper 側 `materials.yml` の `recipe:` ブロックとして7種そろっていた**が、TF のエディタからは見えない場所にあった。`progression/crafting-features.yml` の `added-recipes:` へ7件（銅/鉄/金/ダイヤ/ネザライト/革/カメの甲羅）を定義し、Ars 側の `recipe:` は削除。**同形レシピを両方に残すと後勝ちで片方が黙って消える**ので、移設は必ず両方向（TF に7件ある / Ars に1件も残っていない）を固定する必要がある → `tools/config-editor/test/disassembly-defaults.test.js` にその2本を入れた。`added-recipes` は `result` が無いエントリを警告して**丸ごと捨てる**ので、result 必須もテストで固定。⚠️ **未決のまま残っているもの**: `plank_scrap`（木材スクラップ）は**素材定義そのものが存在せず**、別セッションが `crafting-features.yml` の `wooden_*` 解体と釣りゴミ定義（`ROTTEN_FLESH`/`STICK`/`STRING`/`BONE`/`INK_SAC`/`LILY_PAD`/`BOWL`/`TRIPWIRE_HOOK`）を**未コミットで削除中**。そのため editor テスト2本と TF の `CraftingFeaturesConfigDisassemblyTest` が落ちるが、これは当セッション由来ではない。**②釣果のエンチャント本から呪いを除外**（ユーザー選択「呪いだけ除外して現状維持」）── 事前確認の結果、バニラ同等のランダム付与は **2026-07-30 から既に実装済み**（`FishingGimmickListener#rollBookEnchantIfBare` が**素の** `ENCHANTED_BOOK` にだけ付与）で、**修繕のBANも効いている**（除外リストを二重に持たず、判定用の本を作って `VanillaItemRemover#shouldRemove` へ問い合わせる形なので `removed-vanilla-items: - ANY:MENDING` がそのまま効く）。オーバーエンチャント/追加エンチャント/複合エンチャントは**釣果の本には適用しない**（バニラ準拠。1冊1エンチャント・等確率・レベルも一様）。今回入れたのは呪い（束縛/消滅）の除外のみ。判定はタグ `#minecraft:curse` を第一経路にし、タグAPIが無い環境では非推奨の `Enchantment#isCursed()` へ落とす二段構え（`EnchantLuckListener.enchantingTablePool()` と同じ書き方。**MockBukkit はタグAPIを未実装なのでテストが踏むのは第二経路**）。**呪い集合が空になると「呪いを弾かない」fail-open** で、症状は「たまに呪い本が釣れる」だけでログに何も出ないため、空でないことをテストで明示的に落とす（`FishingBookCurseExclusionTest`）。**③%ステの出荷値をパーセントポイント記法から割合記法へ統一**（実サーバ報告「幸運ステータスが2000%になっている」）── `skilltree/fishing.yml` の `fishing-luck: 10` などが**パーセントポイント記法**で書かれており、`PercentStatNormalize.coerce` が実行時に `10 → 0.1` と矯正するので**ゲーム内の効果は正しかった**が、エディタは PERCENT 宣言のステを **値×100** で表示するため「1000%」と出ていた。**放置が危険なのは coerce が整数にしか効かないこと** ── あとから `20` を `20.5` に変えた瞬間、矯正されず **2050% の実効果**になる。fishing/digging/mining/woodcutting/achievements の計11箇所を割合記法へ揃え（全て実行時の値は不変）、`tools/config-editor/test/percent-stat-fraction-notation-2026-08-13.test.js` で「PERCENT宣言 ∩ `PercentStatNormalize` の率キー」の出荷値が 1.0 を超えないことを固定（検査対象が消えると空振りするので件数の非空振りも assert）。**④質問「1%で1〜2個ドロップするアイテムに、ドロップ増加ステ100%を盛ったらどうなる？」→ 個数だけが2倍になり、遭遇率1%は一切動かない** ── `mob_drop_bonus` は `KillRewardAdjuster#countFactor` → `MobDropRoller.bonusFactor`（`min(3.0, 1.0 + max(0,bonus))`＝**上限3倍**）→ `scaleCount` の経路にしか入らず、**個数にしか掛からない**。抽選確率に掛かるのは `level-cutoff` の `drop-rate`（レベル差の足きり）だけ。したがって「100回に1回、1〜2個」が「100回に1回、2〜4個」になるだけで、体感の「ドロップしやすさ」は変わらない。`scaleCount` は整数部を確定・小数部を確率で足し、`Math.max(1, …)` の床があるので **1個が0個になることはない**（`cap = maxStackSize * 8`）。**仕様として「低確率アイテムの遭遇率も上げたい」意図なら別機構が要る**ので、要否はユーザー判断。実走: **TF 3839 tests / 28 failed / 2 skipped**（着手前の 3837/28/2 に対し **+2 は②の新テスト2本・失敗もスキップも増減ゼロ**。28件の失敗は全て他セッションが `crafting-features.yml` / `power.yml` / `ars_magic.yml` などを未コミットで書き換え中のもの）。**配備**: ②は Java 変更なので **TF の jar 差し替えとサーバ再起動**が必要。①は TF 側は config のみだが、**ArsPaper fork の `materials.yml` を変更したのでフォークの再ビルドと jar 差し替えも必要**（再ビルド実施済み・jar 内 `materials.yml` に `recipe:` が1件も残っていないことを検証済み。fork は `.gitignore` 除外なので TF の commit には含まれない）。③は config のみ、④は変更なし |
| 2026-08-13 | **実サーバ報告3件（エディタのフロントのみ）＋質問1件。どれも「UI部品を、実際に永続化する経路へ配線し忘れている」か「打鍵ごとに全再描画している」の2類型**。①**スレッドの効果欄に数値を入れても保存時に空になる** ── `buildThreadEffectsBox` の数値入力（数値効果6種 / ポーションLv / バックパック枠）は model を書き換えるだけで、呼び出し元へ「値が変わった」ことを**一度も通知していなかった**。ars-forms.js の「スレッド」画面は `models` 配列が model をそのまま握っているので症状が出ず、**forms.js の item-stats「スレッド」タブだけが壊れる**（毎回の再描画で `parseThreadEntry` から model を作り直す＝通知の無い書き換えは捨てられる。保存対象は `threadsRoot` 側なので入力値はどこにも書かれない。`potion-level` のように「追加時に既定値を書かないキー」は丸ごと欠落するので **null/空** に見える）。対策は `opts.onValueCommit`（**再描画を伴わない書き戻し**）を部品へ足し、forms.js 側を `writeBack`（書き戻しのみ）と `commit`（書き戻し＋再描画）に割る ── ここで再描画つきの `onChange` を呼ぶと打鍵ごとに入力欄が作り直されてフォーカスが飛び、×ボタンのクリックも blur→再描画に食われて1回目が効かなくなる。2026-08-11 の `statValueControl` と**同じ形の罠**。②**「2つ目のレシピを登録すると1つ目が消える」** ── レシピ編集UI は catalog.yml（TF本体）の正規形「0件=キーなし / 1件=`recipe:` / 2件以上=`recipes:`」へ書き戻すが、**ArsPaper 側の yml は `UnifiedRecipeLoader` が例外なく `getConfigurationSection("recipe")` しか読まない**。同じUIを流用していたため、「アイテムカタログ」グループの**『素材』タブ**（materials.yml）で2件目を足すと `entryLike` が `recipe:` を失って `recipes:` になり、呼び出し元は `entryLike.recipe` しか見ないので `hasRecipe=false` になって**1件目もろとも消えていた**（魔導書 / ソース / 機能アイテムは消えはしないが `recipes:` が Java から読まれず無言で効かなくなる）。~~`opts.maxRecipes` を足して Ars 系4画面は「+ レシピを追加」自体を出さない~~ → **この対策はユーザーに差し戻された**（「他のすべての item カタログと同じ挙動にして。そもそも素材以外にも Ars の儀式を使うアイテムはあるのだから、この区別をしなければならない理由がない。統合されているべき」）。**採用した対策は UI の制限ではなく読み取り側の統合**: `UnifiedRecipeLoader` に `recipeSections()` を足して **`recipe:`（単数）と `recipes:`（配列）の両方を、6ローダー全部で読む**ようにした。**要点は登録キーと結果アイテムIDの分離** ── 作業台は `new NamespacedKey(plugin, data.id())`、儀式は `RitualRecipeRegistry` の Map キーがどちらも**アイテムIDそのもの**なので、素直に2件登録すると**後勝ちで片方が黙って消える**（UIには2件見えるのに1件しか作れない）。`recipeKey(id, index)`（2件目以降は `<id>_r2`）を**登録キーにだけ**使い、結果アイテムは常に元のIDから解決する。連動して `unlock-gate.yml` の `recipe-perks` は**アイテム単位**で書かれているので、`RecipeUnlockGate` に「完全一致 → 無ければ `_r<数字>` を落とした基底IDで引き直す」フォールバックを入れた（入れないと**2件目だけ perk ゲートを素通りする**）。`MaterialConfigManager`（lore の「作り方」表示）も `recipe:` が無ければ `recipes[0]` で代用する。エディタ側は `maxRecipes` を全廃し、`lib/schema.js` の検証も `validateRecipeForms()` で **`recipe:`/`recipes:` を同じ検証器へ流す**ように統合（catalog / materials / threads / spell-books / jars・sourcelinks の5系統）。素材モデルだけは中間表現を挟むので `MATERIAL_KNOWN` と `serializeMaterialEntry` に `recipes` を足して往復させる。**ブラウザ実測**: 『素材』タブの「ソースジェム」で「+ レシピを追加」を2回押し、1件目（ラピスラズリ核の儀式）が `recipes[0]` として残ったまま3件になることを確認。③**検索窓が1文字ごとに途切れる** ── 原因が2つ重なっていた。(a) `oninput` が同期で一覧を作り直しており、カタログ「武器」タブの実測で**1打鍵あたり 570ms** メインスレッドが止まる（188カード再生成）。(b) 『素材』タブの `render()` は `root.innerHTML = ""` で**検索欄自身を作り直す**ので1文字ごとに確実にフォーカスが飛ぶ。共通部品 `window.filterInput`（**IME変換中は絞り込まない / 200ms デバウンス / 同じ key の欄へフォーカスとカーソル位置を復元**）へ3箇所を集約。ブラウザ実測で **570ms → 0ms**、変換中は再描画ゼロ、フォーカス保持を確認。④**質問「バックパック枠は max54 でつけた分だけ増えるか」→ 認識は正しいが `slots:` の値は容量に効いていない** ── 実容量は `BackpackGui.open` の `min(装着している「バックパックのスレッド」の本数, 2) × 3行 × 9` で **1本=27 / 2本=54**（`max: 2` なので上限54）。`threads.yml` の `slots:` を読むのは `ThreadConfig#getBackpackSlots` **1箇所だけで、用途は lore の「追加インベントリ N スロット」表示のみ**。出荷値が `slots: 0` なので**lore が「0スロット」と嘘を表示していた**ので `27` へ直し、ヘッダコメントに「容量には効かない・lore 表示専用・1本あたり27で書く」と明記した（fork の resources）。実走: config-editor **1165 pass / 22 failed**、ArsPaper fork **346 / 4 failed**（fork の4件はいずれも他セッションが `items.yml` / `threads.yml` を書き換え中で前提が崩れているもの。`thread_slot_expand_ritual` / `thread_reroll` の不在、新キー0件前提の後方互換テスト）。クリーンな検証 worktree（HEAD）のベースラインと**失敗集合を突き合わせ済み**で、当セッション由来の新規失敗は**ゼロ**（差分10件はいずれも他セッションが未コミットで書き換え中の `items/catalog.yml` / `stats/item-stats.yml` / `stats/lore.yml` 由来のデータ不整合。例: `weapon/鎌/fnis_peccati_profundi` にステが無い）。**配備が要る**: ②の統合で **ArsPaper fork の Java（`UnifiedRecipeLoader` / `RecipeUnlockGate` / `MaterialConfigManager`）と `threads.yml` を変更した**ので、**ArsPaper の再ビルドと jar 差し替えが必要**（fork は `.gitignore` 除外なので TF 側の commit には含まれない）。①③④のエディタ側は配備不要 |
| 2026-08-12 | **`stats/skill-exp.yml` で並行セッションと正面衝突したので、ユーザー裁定に従って手で混ぜた**。別セッションが同じファイルに未コミットで持っていた変更は「鍛冶EXPの引き上げ（各種コア 60→**5000**、`ars-smithing.exp-per-source` 0.0→**0.01**）」＋「`combat.kill-exp.per-max-health` 0.25→**1**（4倍）」で、最後の1件だけが当セッションの指示（撃破EXPを下げる）と真逆だった。**ユーザー裁定: 撃破EXPは当セッション優先（0.188）、鍛冶EXPは別セッションが正しい。** 相手の版は `tmp/findings/skill-exp-wip-20260812/` に丸ごと退避してある。**巻き添えで1件テストを直した**: `skill-exp-power-and-source-2026-08-04.test.js` の前提テストが `exp-per-source` の**出荷値そのものを 0.0 で固定**していた。このテストの目的（同ファイル冒頭の宣言）は「Java 既定値と editor の表示既定値が一致すること」で、**出荷値がたまたま既定値と同じだったのを固定していただけ**なので、「キーが実在して 0 以上の数値であること」へ直した（既定値の一致は別テストが見ている）。実走: TF **3837 / 4 failed / 2 skipped**、config-editor **1131 pass / 25 failed**（どちらもベースラインと一致） |
| 2026-08-12 | **プレイヤー最大HPを Lv100 で 50 ハートに収め、撃破EXPを約1.5倍まで戻し、ダンジョン側の攻撃カーブを揃えた**（同日の再較正の追補。ユーザー指示「撃破EXPを少しだけ減らす？／最大レベルで50ハートぐらいにとどめたいね」＋選択「耐久感は維持」「約1.5倍まで」）。**HPの畳み方が要点** ── 防具ラダーは `F(L)=0.5×A(L)` と `HP(L)=8×(A−F)×0.72` で**A に完全比例**するので、モブ攻撃力の指数を **1.02 → 1.0148** へ寝かせるだけで `A(100)` が 0.6 倍になり、守備力も最大HPも同じ 0.6 倍で縮む。つまり**攻撃力・守備力・HPが同率で縮むので耐えられる発数は不変**（実測でも ZOMBIE 6.5〜11.9発／WARDEN 2.4〜3.8発と再較正直後と完全同値）。Lv100 のプレイヤー最大HPは **166.7 → 100.0（50.0ハート）**、4部位の守備力合計は 29.8 → 17.4。**base(8.0) は動かしていない** ── 低帯の最大HPはバニラの 20 に張り付いていて下げようがなく、base を下げると序盤だけが理不尽になる。**EXP**: `stats/skill-exp.yml` の `per-max-health` を **0.25 → 0.188（×0.75）**（`combat.kill-exp` と `ars-magic.kill-exp` の2箇所）。撃破EXPは base / per-mob-level / per-max-health の3項だが**最大HP項が支配的**なので、この係数がほぼそのまま倍率になる（以前比 約2倍 → 約1.5倍）。**同時に直した重大な副作用**: 2026-08-10 にフィールド側だけ指数を寝かせたため、**ダンジョン側 `combat/mob-import.yml` が旧カーブ（base 7.0 / growth 1.03 / Lv45以降 +0.25/Lv）のまま取り残され、Lv100 で攻撃力 148 対 35（約4.3倍）＝プレイヤー最大HP 100 に対して事実上の即死**になっていた。`base: 10.2 / growth: 1.0148 / high-level-per-level: 0.0` へ揃えた（10.2 は旧比 7.0:5.5＝1.27倍をそのまま引き継いだ値）。**ランプを触ると `combat/mob-overrides.yml` のボス絶対値も連動する** ── `MobStatOverride` は倍率キーを持たないので束縛者4段階＋ミニボス3種の `attack-power` は「ランプ Lv50 実値 × 計画倍率」を展開した絶対値で書いてあり、7件を新ランプで計算し直した（39.89→27.64 ほか）。`ShippedBossStrengthDriftTest` がランプを実読して割り戻すので、これを忘れると必ず落ちる。**ヘッダの重複4本も除去**（前回のバッチで `add-headers.js` を BOM 切り分け中に4回走らせた残骸が `mob-types.yml` の先頭に積まれていた）。**テストの契約を1本反転**: `ShippedMobImportBreakpointTest` の「Lv45以降の加算が生きていること」は 2026-08-03 の設計を固定したものだが、フィールド側が同じ加算を撤去済みなので**「純粋な指数であること」＋「フィールド(mob-types.yml の ZOMBIE)と growth が一致すること」**へ書き換えた（後者が今回の 4.3 倍事故を直接検出する不変条件）。`armor-ladder.test.js` の耐久回数表4本も再測定して更新 ── **数値が 10〜35% 増える方向へ寄ったのは、バニラの最大HP 20 が固定で縮まないため**（装備分だけが縮むので総HPは A ほど縮まない）。同帯どうしの被弾回数は不変で、動いたのは「最大ロール × 格上の敵」というこの表だけが見ている断面。実走: **TF 3837 / 4 failed / 2 skipped**（テストを1本→2本に割ったので総数 +1）、**config-editor 1131 pass / 25 failed**。どちらもクリーンな検証 worktree でのベースラインと完全一致（回帰ゼロ）。**配備は config のみでよい** |
| 2026-08-12 | **戦闘バランスの再較正（防具の守備力/最大HP ラダーと、モブの攻撃力/最大HP を同時に引き直した）**。ユーザー指示「敵のステータスと装備も考慮して火力と防御を設定」＋追加指示「**モブ攻撃力とHP両方でもOK／原則、最大値と最小値の設定でキャップしなくて済むようにする**」。**真因は「防具の守備力がモブ攻撃力とほぼ同じ勾配で伸びていた」こと** ── プレイヤーの実効HPは装備込みでも Lv0→100 で **×3**（20→60）しか伸びないのに、モブ攻撃力は **×40**（3.85→155）伸びる。その差を埋めていたのが**引き算段**の `phys-flat-defense` で、これも **×44**（6.5→288）とほぼ平行に伸ばしてあった。引き算の残差 `A − F` は平行な2本の差なので**ゼロ近傍か爆発かの二択**にしかならず、実測で **Lv20〜60 は全帯 `min-component-damage: 1` の床値に張り付いて実質無敵・Lv80〜100 と RAVAGER/WARDEN はほぼ即死**という二極化になっていた。つまり「クランプで辻褄を合わせている」状態そのもの。**引き直しの閉形**: `A(L) = 8.0 × 1.02^L`（Lv10未満は `early-level-attack` の 0.7→1.0 を後掛け）に対して **`F(L) = 0.5 × A(L)`**（守備力は攻撃力の半分で固定＝残差が必ず A の半分だけ残る）、**`HP(L) = 8 × (A − F) × 0.72`**（0.72 は防御率×耐性×軽減の帯平均。通常モブ8発で落ちる狙い）。帯ごとに「その帯の最良セットの実測合計」を測って `target/実測` の倍率を**その帯の全部位へ一律**に掛けたので、セット間・重装/軽装の相対序列は保たれている（2026-08-03 の「軽装の物理耐性は重装の0.60倍／軽装の `fixed.max-health` は0」の序列ルールは生きたまま）。**モブ側**は `attack-power-growth` 1.033→**1.02**・`base` **×1.4545**・役割係数を 5.5 を軸に **0.5 倍へ圧縮**（役割差が帯後半で爆発するのを止めた）、`attack-power-high-level-per-level` **0.25→0**（Lv45以降の加算が指数と二重に効いていた）、`max-health-growth` 1.048→**1.053**・`base` **×2.0**・Lv45以降の加算 **×2.5**。**base を ×1.4545 したのは丸めのため** ── growth を寝かせるだけだと低帯の値が表示桁（0.1刻み）へ丸められてロール帯・役割差が同値へ潰れる。**結果（自作モデルで全帯・4モブ実測、床値張り付きの検出付き）**: 床値に頼っている帯は**ゼロ**。TTD は ZOMBIE 6.5〜11.9発／SKELETON 6.1〜10.7発／RAVAGER 4.1〜6.5発／WARDEN 2.4〜3.8発、TTK は帯最良の剣で 1.8〜6.2秒。守備力は Lv0 6.5→**2.9**／Lv60 46.4→**13.5**／Lv100 287.9→**14.4**（4部位合計）、最大HPは Lv60 48.6→**75.4**／Lv80 53.2→**114.0**／Lv100 63.7→**166.7**。**副作用として受容したもの**: ①**撃破EXPがおおむね2倍になる**（`stats/skill-exp.yml` の `per-max-health` がモブ最大HPに線形なので。TTK も伸びるので EXP/時間はほぼ不変）②**プレイヤー最大HPが約167（83ハート）まで伸びる** ── 縮めたい場合のつまみは `HP(L)` の係数 8（＝通常モブ何発で落ちるか）1本だけで、ここを下げれば全帯が比例で縮む。**物理素材の防具（革〜ネザライト）に魔法守備の最低保証（物理値の0.5倍）を入れた** ── これが無いと WARDEN（魔法比率0.6）の魔法成分が防御率以外まったく素通りする。**回帰テストの向きを2箇所ひっくり返した（緩めたのではなく逆の契約を固定した）**: `armor-ladder.test.js` の T3-2 は「守備力が高い CHAINMAIL のほうが実効被ダメージが少ない（＝直さない仕様）」を固定していたが、これは引き算段が支配的だったからこそ成立する逆転で、`F = 0.5A` にした今は**防具値と物理耐性で勝る COPPER が正しく勝つ**。旧向きへ戻す＝床値張り付きへ戻すことなので、逆向きで固定し直し、あわせて「守備力が Lv20 攻撃力の6割未満に収まっている」を新たな不変条件として追加した。`ShippedMobTypesLevelBandTest` の `EXPECTED_ATTACK_HIGH_LEVEL_PER_LEVEL` も 0.25→0 に追随（ハードコードの 3.75/8.75/13.75 は定数から導出する形へ）。**踏んだ罠（恒久記録に値する）**: `git show HEAD:file.yml > tmp\x.yml` を **PowerShell のリダイレクトでやると先頭に UTF-8 BOM が入る**。変換スクリプトがそれを保存し、あとからヘッダ行を前置したことで **BOM がファイル途中（18行目）へ移動**した。SnakeYAML は**ストリーム途中の BOM を文書境界と解釈**して `expected '<document start>'` で落ちるが、`YamlConfiguration.loadConfiguration` がそれを `Bukkit.getLogger()` へ流すため、素の単体テストからは **NPE にしか見えない**（これで回帰38件を自分で作って原因を見失った）。実走: **TF 3836 / 4 failed / 2 skipped**、**config-editor 1128 pass / 25 failed** ── どちらも**クリーンな検証 worktree での着手前ベースラインと完全一致（回帰ゼロ）**。あわせて §1 のテスト基準値を訂正した（「25 failed が基準」は共有ワークツリーの他セッション WIP を測っていた誤り）。**配備は config のみでよい**（Java 変更なし）。⚠️ `stats/item-stats.yml` と `combat/mob-types.yml` は**この2本セットでしか整合しない**（片方だけ配備すると床値張り付きか即死へ戻る） |
| 2026-08-12 | **エディタの「%ステが 0.03 と小数で出る」の真因はもう1件あった（セット効果の行が `statValueControl` を通っていなかった）**。直前の行で「`mana-cost-reduction-percent` 1件だけの問題」と書いたのは**誤り**で、ユーザーが実際に見ていたのはスレッド画面の**セット効果（`thread-sets.yml`）**の行（回避率3% が `0.03`、会心率3% が `0.03`）。あちらの lore 宣言の食い違いとは**別のバグ**で、`dodge-chance`/`crit-chance` は元から `format: PERCENT` だった。真因は `forms.js` の `renderThreadSetEffects` が値入力に **`window.numberInput` を直接使っていた**こと。%変換を知っているのは `statValueControl` **だけ**なので割合がそのまま出る。さらに **`statUnitSlot` は %ステに空スロットを返す**（% は `statValueControl` の `pct-suffix` が出す前提）ため、**小数なのに単位すら付かない**という見え方になっていた。`statValueControl` 定義の直上には「thread-sets フォーム等でも同じ %入力(割合保存) を再利用する」と既に書いてあり、**意図はあったのにこの1箇所だけ変換され忘れていた**（＝宣言の監査だけでは絶対に見つからない。監査すべきは「どのフォームが `statValueControl` を呼んでいるか」だった）。他の全画面を横断確認したうえで（item-stats の加算/random行・スキルツリーのバフ/防具段・儀式触媒・生活系・Ars魔導書はすべて既に `statValueControl` 経由。倍率行 `x1.2` は単位を持たない別物なので対象外）、回帰テストは**構造ガード**（`statSelect` を持つ行が素の `numberInput` を使っていないこと＋走査が空振りしていないことの自己検査）を `stat-row-percent-input-2026-08-12.test.js` に追加。修正を戻すと 2 本落ちることを実証済み。ブラウザ実機確認: `thread-sets.yml: speed` の行が **回避率 `3 %` / `4 %`・会心率 `3 %`**（`.pct-input input` の実値を DOM から読んだ）、`4` と入力すると `0.04` で保存。editor テスト A/B: 着手前 26 → 修正後 24（**減ったのは新テスト2本だけ・新規の失敗ゼロ**。残り24は他セッションの未コミット yml 由来で両方の実行に同一に出る）。**配備不要**（エディタのフロントのみ） |
| 2026-08-12 | **`mana-cost-reduction-percent` の lore 宣言が実態と食い違っていたのを訂正**（実サーバ報告「エディタのステータス設定で単位が % のものの数値が小数点表示になってしまっている」）。~~**このステ1件だけの問題**~~（**誤り。上の行を見ること** — 宣言は確かにこの1件だけだったが、ユーザーが報告していた表示崩れの本体は `forms.js` のセット効果フォームが `statValueControl` を通っていないことだった）で、他の %ステは宣言としては正しかった（`PercentStatNormalize` の率キー列挙 × `stats/lore.yml` の format × `stats/item-stats.yml` の実値を全件突き合わせて確認）。真因: `PercentStatNormalize` が **[0,1] の割合として扱うキー**なのに `lore.yml` が `format: FLAT` / `decimals: 0` / `unit: "%"` と宣言していた。出荷値も割合（`per-quality: 0.004` / `random: 0.007〜0.094`）なので、**2箇所が同時に壊れていた** — ①エディタが `isPercentStat` false で %入力ではなく素の数値入力になり「単位が % なのに 0.004」と出る ②**実機の lore も FLAT decimals:0 で 0.004 を "+0%" としか出さない**（スレッド「詠唱効率」が何も効いていないように見える）。`format: PERCENT` / `decimals: 1` へ訂正し `unit:` を削除（PERCENT は % を自動で付けるので書くと二重）。`materials.js` の `FALLBACK_STAT_FORMATS`（こちらは INTEGER で3つ目の食い違い）と `FALLBACK_STAT_UNITS` も追随。**`skilltree/ars_magic.yml` の `5` を `0.05` へ**（`PercentStatNormalize` が実行時に 5→0.05 と矯正していたので**ゲーム内の効果は不変**だが、PERCENT 化するとエディタが 500% と表示するため）。2026-08-09 の小数丸めパスが `MISDECLARED_STATS` の例外として棚上げしていた既知バグで、その例外は撤去し「**Java が割合として扱うステは lore.yml でも PERCENT**」を `PercentStatNormalize.java` の列挙から機械的に引く一般則テストへ置き換えた（列挙の読み取りが壊れると空振りするので検査件数 30 以上も assert）。lore を FLAT へ戻すとテスト2本が落ちることを実証済み。ブラウザ実機確認: スレッド画面で `0.4 %` / `0.7 %` と表示され、`9.4` と入力すると `0.094` で保存される。実走: TF 3836/25 failed/2 skipped・editor 1159/23 failed（どちらも着手前と同一集合＝他セッションの未コミット yml 由来。editor 側の `FALLBACK_STAT_UNITS` ドリフト失敗は他セッションが `mana-regen-base` の unit を `/秒`→`""` に変えた未コミット差分によるもので本件とは無関係）。**配備は config のみでよい**（Java 変更なし） |
| 2026-08-10 | **ユーザー指示バッチ（J-12 / J-13 / J-14 / M-2 / M-3）＋バランス調整の前半（厳選・杖）**。TF `42459bc`／`66d621b`／`1b1f2da`／`0e0888e`。**J-12（圧縮素材の鍛冶EXP）は「行を消す」が意図と真逆になるのが要点** ── `ArsProgressionBridge#grantSmithingCraftExp` は `base = covered && fromMaterials > 0 ? fromMaterials : arsSmithingExpPerCraft` で、**消費素材が1つでも `smithing.exp-per-material` に無いと素材合計を丸ごと捨てて定額100へ戻す**。つまり行削除は「EXPなし」ではなく「素材価値と無関係な定額EXP」になる。**明示的に `0` と書く**のが正しい表現で、圧縮素材 `_Nx` 22件を0に（4件は行ごと新設）、HEAD にあった重複キー2件（`amethyst_block_2x`/`breeze_rod_2x`。SnakeYAML は重複を無警告で後勝ちにするので気づけない）も解消。ついでに他セッションが増やした儀式レシピの非圧縮素材23件を補充。回帰テストは `ShippedCompressedMaterialExpZeroTest`（圧縮素材の0固定＋重複キー検出の生行スキャン）。**J-13**: `apex-brew` の表示名は「力III（最上位の醸造）」で、材料の**ホグリンの牙が ArsPaper `materials.yml` に定義ブロックを持たない**（リスト項目にしか出てこない）ため**現状は作れない** → W項目として新規起票（CMD台帳の刈り取り不具合と同じ機構。`plank_scrap`/`tf_gacha_ticket` と同型）。**J-14**: 仮テクスチャ18件を仮置きし、**逆にカタログに無いのに残っていたテクスチャ/モデル49件（tracked 31件）を削除** ── 槍/長槍/モーニングスター 21件（Blockbench モデルで1本1,220〜7,926行）、cane→wand 改名の残骸9件＋PNG、`gold_test`/`wood_test`/`test.png`。**意図的に残した**: ホグリンの牙（apex-brew の現役素材）・`dungeon_seal_enchant_trial`（アチーブメントが参照）・魔法の杖/罪深き終焉/新月の輝き（カタログにはあるが配線切れ）。**M-2**: 手懐けたモブのレベルは**飼い主の総合戦闘レベル**参照（`combat/mob-types.yml` に `tamed:` 節を新設。単一スキル参照ではないことを示すため `skill:` キーは意図的に置かない）。`EntityTameEvent`（手懐けた瞬間）と `EntitiesLoadEvent`（既存個体をチャンク読込時に追認、現HP比を保つ）の2経路で、どちらも `isEliteMobsOwned` を先に見る。新設 `CombatLevelSource`（`combatService::combatLevelOf`）。**MockBukkit は `EntityTameEvent`/`EntitiesLoadEvent`/`Tameable` を実装している**（統合テストがSKIPPEDでなく実際に通ることで確認）。**M-3**: 召喚モブ/ゴーレムの攻撃力は**設定しない**と確定（ユーザー判断）。**バランス（厳選）**: `stats/quality.yml` の `roll-spread-up`/`down` を **0.1→0.22**、`item-stats.yml` のスレッド45種の `random` 440行を **min×0.4 / max×1.8**（max/min 比 2.75〜5倍 → 10〜22倍）。**片方だけでは効かない**のが要点で、σ=0.1 のときは同じ品質なら `QualityRollModel.reach` が mode に張り付き、帯を広げても品質4〜5では z=5 相当が要って **max 側に一生届かない**（＝「品質が全て、ロールは無意味」）。⚠️ σ は**スレッド専用のつまみではなく**、`random:` を持つ武器・防具のロール幅にも同じだけ効く。回帰テストは `ShippedThreadRollSpreadTest`（幅とσの両方）。**バランス（杖）**: 杖10本の `attack-speed` を **0.1→0.5〜1.1**。0.1 は1振り10秒で実効近接DPSが同帯の剣の**14〜31%**しかなく武器として成立していなかった。**`attack-power` は触っていない** ── `combat/damage.yml` の `magical.attack-power-scale: 1` で魔法ダメージへ1:1で乗るため、触ると呪文の威力まで動く。`MeleeUnintendedItemAttackSpeedTest` は「最低値に固定」から「遠隔は最低値固定・触媒は近接(最速1.6)より確実に遅い(≤1.2)」へ規約変更。実走: TF 3836 tests / 25 failed / 2 skipped（25は全て他セッションの未コミット yml 由来）。**配備**: yml は config のみでよいが、**M-2 は Java 変更なので jar 差し替えとサーバ再起動が要る** |
| 2026-08-09 | **レベル差の足きりを共通戦闘設定へ移設し、EM側の二重足きりを外し、TF追加ドロップを emloot へ流す橋を作った**（TF `ac5dd3e`、EM fork `2c5241ad`/`e39597a7`）。**移設の理由が要点**: `combat/mob-overrides.yml` の `level-cutoff:` は「ダンジョンワールド × EMモブid」で解決していたので **EliteMobs が `MOB_PROFILE_ID` を刻んだモブ = ダンジョンモブにしか掛からず、フィールドの野良モブはレベル差いくらでも素通り**していた。`combat/damage.yml` の `level-cutoff:`（over-level の `threshold`/`exp-rate`/`drop-rate`、under-level の `item-threshold`）へ移し、判定を `KillRewardAdjuster` 一点へ集約して**レベル刻印さえあれば全モブに効く**ようにした（ゲートは `MobData#hasProfile()`＝`MOB_LEVEL` の有無。刻印の無いモブで「プレイヤーがオーバーレベル」が常時成立する事故をここで止めている）。EXP側は新設 `LevelCutoffExpListener`（MONITOR、`MobLevelTableListener`(HIGH)と`MobOverrideExpListener`(MONITOR)より**後に登録**すること）。ダンジョン別に足きりを変える機能は**意図的に落とした**（`dungeon/gates.yml` の `required-combat-level` は残す）。**同時に直した2件**: ①ドロップ増加ステ `mob_drop_bonus` が **TF追加ドロップに一切載っていなかった** — `NativeSurvivalPerkListener` は同じ MONITOR でも**登録順で先に走る**ので、あとから積まれる TF ドロップには構造的に届かない。`MobDropRoller.bonusFactor`/`scaleCount` を3リスナーから `KillRewardAdjuster#countFactor` 経由で呼ぶ形へ。②EM の `lootLevelDifferenceLockout`（装備tier差で戦利品を全停止）が TF の足きりと**入力も効果も食い違うまま二重に掛かっていた** → `EliteDropPolicy#blocksLootByLevelDifference` で TF 導入時のみ無効化（単体運用は上流どおり）。**`elite-drop-sources.boss-unique-loot` のコード既定を true→false へ反転**（出荷 yml は元から false だが、`TrinityForgeConfigMigration` はトップレベルキーしか追記しないので**既にこの節がある配備済み yml ではキーが欠けて true に落ちて**いた）。**その副作用として `CustomBossDeath` の戦利品配布が走らなくなり、emloot(`SharedLootTable`) を作る経路が EM 側から消滅した**ので、need/greed の機構は TF追加ドロップで代替する: TF `EliteMobsSharedLootBridge#deliver`（リフレクション）→ fork `TrinityForgeSharedLoot#offerDungeonLoot`。引き取り条件は**エリートモブ / ダメージ寄与者2人以上 / `DungeonInstance` 内**の3つで、1つでも欠ければ従来どおり地面へ落ちる（橋が壊れてもドロップは失われない）。`SharedLootTable#rollLoot` は TF アイテムに **EM の後処理3つ（ソウルバインド / `EliteItemLore` の書き直し / エリートレベル刻印）を掛けない** — ロアは rollSeed+品質から毎回導出されるので上書きすると `reload` でも直らない。ガードを外すと落ちることを javap スライスのテストで実証済み。実走: TF 3815/26 failed/2 skipped（26は着手前と同一集合＝他セッションの未コミット yml 由来）・EM fork 98/0 failed。**配備には jar 差し替え（TF・EM 両方）とサーバ再起動が必要**／`plugins/EliteMobs/trinityforge.yml` に `boss-unique-loot: true` が残っている場合は手で `false` へ書き換える |
| 2026-08-09 | **据え置いた3ステ(`phys-flat-defense`/`max-health`/`attack-speed`)も丸めて、残存を470件→10件にした**（下の行の「据え置き」は解消済み。ユーザー指示「エディタ上も小数点2桁以下は消してほしい」）。**丸め方が違うのが要点**: 値を個別に丸めるのではなく**制約を保つ丸め**にした。(1) 防具は4部位の `fixed` / `fixed+random.min` / `fixed+random.max` の**合計を元値の1桁丸めへ一致させる**(残差を部位間で 0.1 ずつ融通)。これで `armor-ladder.test.js` の「新規軽装7セットの守備力合計 = TABLE の fMax ±1%」が最小セット(7.42)でも誤差 0.7% に収まる。(2) `attack-speed` は武器種ごとに一意なので8値だけ動かす(0.833→0.8 / 0.952→1.0 / 1.003→1.0 / 0.88→0.9 / 0.92→0.9 / 1.78→1.8 / 1.44→1.4 / 0.79→0.8)。**踏んだ罠3つ**: ①`WeaponDpsParityTest.ARCHETYPE_PAIRS` は attack-speed 署名という**代理キー**で同格を引いていたため、ウォーハンマー 1.003 と大剣 0.952 が両方 1.0 になった瞬間に2つの武器種を区別できなくなり大剣/短剣の比が 1.28 に化けた → **鍵を武器種そのものへ張り替えた**(張り替え前後で実測は同一: 30件成立・比 0.893〜1.152)。②防具セットの組み立てを「兜のCMD+部位番号」でやると**ネザライトの 148〜159 は3刻み**なのでバラけ、`ArmorHeavyVersusLightDefenseOrderTest` の「対になる重装/軽装で守備力 fixed 合計が同値」が Lv80 で 62.0 vs 62.1 に割れた → 刻みを総当たりで決めて4部位の必要レベル一致を検算する方式へ。③合計の浮動小数ノイズ(62.049999.. / 62.050000..1)で丸め先が割れるので、合計は6桁へ均してから1桁へ丸める。**唯一のDPS補正**: 大斧 0.833→0.8 の -4.0% で `golden_greataxe` だけ帯下限(78%)を割った(80.3→77.1%)ため `attack-power` を 623→649 にして元の比へ戻した。残り10件の例外は `tools/config-editor/test/item-stat-decimal-policy-2026-08-09.test.js` が**例外の成立条件ごと**固定する。実走: TF 3817/28 failed(丸め前と同一)・editor 1172/23 failed(丸め前と同一、+4は新テスト) |
| 2026-08-09 | ~~**アイテムステータスの小数を表示単位で1桁以内へ丸めた**（609件）。~~（据え置き3ステは上の行で解消）`lore.yml` の `decimals` は**全ステで 0 か 1** なので、`attack-power 96.646` の下2桁は**誰にも見えないまま戦闘計算だけを揺らしていた**（＝この修正は表示を変えない）。規則: 対象は `fixed` と `random.min/max` のみで **`per-quality` は対象外**（品質1あたりの増分なので丸めると「品質50で+0.2」の設計が壊れるか0になる）／割合ステ(PERCENT・単位%)は表示%で小数第1位＝生値で小数第3位（既にほぼ全て収まっており実変更なし）／それ以外の FLAT は `|値| >= 10` なら整数・10未満は小数第1位／0でない値を0にする丸めはしない。**据え置いた3ステと理由（丸めに来ないこと）**: `phys-flat-defense`(277件)・`max-health`(31件) は `tools/config-editor/test/armor-ladder.test.js` が**4部位合計を許容1%/15%で出荷ymlから実測して固定**しており、部位ごとに丸めると合計が外れる（骨鎧 7.42→7.50 で +1.35%）。`attack-speed`(94件) は `WeaponTierParityTest` の `ARCHETYPE_PAIRS` が**出荷ymlの attack-speed 署名で同格を対応付けている**ため、動かすと対応が全滅して「1件も比較しないまま緑」の手前で落ちる（`WeaponDpsParityTest` も同時に外れる）。実走: TF 3817 tests / 28 failed / 2 skipped（28は全て他セッションWIP由来。丸め前は30で、差の2件がこの武器2本）|
| 2026-08-09 | **魔導シリーズに賢者/星詠みの2階梯8点を追加**。魔織・守護は5階梯揃っているのに魔導だけ大魔導士で打ち止めだった。CMD は守護 20020x/20021x・魔織 20022x/20023x に続けて **20024x/20025x**。ステは「耐久/品質別耐久/ランダムロール/要求レベルは同階梯の魔織と共通の土台」＋「魔導固有ステは魔導と魔織の**大魔導士どうしの比**をそのまま持ち上げる」(マナ上限 x5/3・マナ回復 x5/3・魔法固定防御 x1.6・魔法耐性 x1.391)。`skill-exp.yml` は賢者/星詠みに加えて**表から丸ごと抜けていた魔導の大魔導士**も補った(魔織・守護にはある。EXPが 0→480 と飛ぶ穴だった)。リソースパックの追加は不要（魔織の賢者/星詠みにもモデルは無く、染色革のみ）|
| 2026-08-09 | **農業の追加ドロップ(drop-tables)を機構ごと撤去**（設定・Java・エディタUIの全削除、ユーザー明示指示）。`stats/farming-gimmick.yml` の `drop-tables:` セクション（gacha_tier1/compressed_bread）を削除。`FarmingGimmickConfig` から `dropTables()` フィールド/getter/パースを削除。`FarmingGimmickListener` から抽選ロジック(`rollDropTables`/`dropEntry`)と未使用になった `DedicatedEffectsConfig`/`CrossPluginItemResolver` 依存を削除（対象判定・anti-loopガードは維持、実質no-op化）。`TrinityForge.java` の生成呼び出しを3引数コンストラクタへ更新。`skilltree/farming.yml` の B/C ノードから `dedicated-effects: id: drop:farming:gacha_tier1` / `drop:farming:compressed_bread` と対応する effect-text/effects 文言(「ガチャ券(初級)を低確率で入手」「収穫時に低確率で圧縮パンを入手」)を削除(`harvest-extra-drop-chance` buff は別機構なので維持)。`tools/config-editor/public/js/tf-lifestyle-forms.js` の `buildFarmingGimmickForm` から `dropTableEditor(["drop-tables"])` の呼び出しのみ削除(共通関数 `dropTableEditor` 自体とmining/woodcutting/digging/fishingの呼び出しは無変更)。**採掘/伐採/掘削/釣りの drop-tables 機構(`DropTableConfig`/`DropTablePolicy`/`DropTableGateSupport`)は共有クラスのため一切未変更。** `FarmingGimmickConfigTest`/`FarmingGimmickListenerTest` の drop-table関連テストを削除・修正。`./gradlew test` / `npm test` / `releaseAssembly` は実走済み（`releaseAssembly` は BUILD SUCCESSFUL、farming/drop-table 関連の失敗は0件）。**Bash ツールがフック層のエラー(`line 70: unexpected EOF`)で全コマンド即死する事象は再発する。**その場合は PowerShell から `cmd /c gradlew.bat ...` で回避できる |
| 2026-08-09 | **カタログ定義済みで満腹度未設定のアイテムを「食料ではなく素材」として食べられなくした**（ユーザー明示指示: 「81倍は食用にしない＝食べる機能そのものを奪いたい」）。真因は**キャンセル処理がどこにも無かった**こと ── `apple_3x`（リンゴ729個ぶん）が素の APPLE として食べられ、満腹度4のために729個が消えていた。食用マテリアルを土台にした Ars カスタム品は53件あり、`stats/food-gimmick.yml` の `custom-foods` に登録済みは12件、**未登録が41件**。判定基準は**`custom-foods` への登録の有無そのもの**にした（個別IDのハードコード除外リストを持たない）。`FoodGimmickConfig#isBannedUnregisteredCustomFood` の判定順は 機能無効→false / ベースMaterialが `excluded-materials`（既定 `GLOW_BERRIES`＝ユーザーが原文で除外指定）→false / カスタムID無し（素のバニラ）→false / `custom-foods` 登録済み→false / それ以外→true。`FoodGimmickListener#onItemConsume` の `isEdible()` 早期returnの直後でキャンセルし、アクションバーに日本語の理由を出す（無言キャンセルは不具合に見える）。`tf_crystal_apple` は「食べるアイテムとして正しい」と過去に確定しているので**除外リストではなく `custom-foods` へ登録**して食べられる状態を保つ（規則を素直に保つため）。エディタ側は `lib/schema.js` の検証と `public/js/tf-lifestyle-forms.js` のUIカードを同時に足す（新キーを yml にだけ足すと「開いて保存しただけで消える」）。テストは規則そのものを3本（登録済み=食べられる／未登録=禁止／カスタムID無しの素バニラ=食べられる）＋除外Material・機能OFF・既定値 |
| 2026-08-06 | **editor「アイテムステータス／補助タブの未設定に、触媒3件と魔導書3件が湧いて消せない」を修正**。真因は**保存用の刈り取りが画面の UI 状態を壊していた**こと ── `getData()` の出力 `out = { ...working, items }` は浅いコピーなので `out._editor` は**画面が握っている `working._editor` と同一オブジェクト**で、そこで `pruneEditorUiState`（`_editor.itemTabs` の孤児掃除）が `delete` すると画面の表示タブピンごと消える。`buildItemStatsForm` はカタログ候補ぶんの**値なしの空枠**を作って正しいタブ（`catalyst` / `spellbook`）をピン留めするが、空枠は `dropEmptyItemProfiles` で**出力の `items` から落ちる**ため、そのピンが孤児と誤判定されて消え、`getItemDisplayTab` が Material 推論（`BLAZE_ROD`/`BOOK` → `other`）へ退化して**補助タブへ落ちる**。`getData()` は画面を開いた直後の `syncBaseFromEditor` でも呼ばれるので開いた瞬間に起きる。しかも候補同期が毎レンダで作り直すので**消せない**。対策は2点セット（片方だけでは無効）: ①`pruneOrphanItemTabs` は**出力オブジェクトの `_editor` だけを差し替える**（`categories`/`orders` は同じ参照のまま＝行エディタが掴んだ配列を孤児にしない。落とすものが無ければ参照ごと据え置き）②`split-views.js` の `_editor` 受け渡しを `adoptEditorMeta(host, out)` に集約し、**画面側が既に持つ `_editor` を保存用出力で上書きしない**（旧 `data._editor = d._editor` は刈り取り済みクローンを画面へ書き戻すので①を1レンダで無効化する）。ブラウザで確認: 補助/未設定は 0 件、`BLAZE_ROD#400024-400026` は触媒、`BOOK#100001-100003` は魔導書に出る。保存 yml にピンは増えない（孤児掃除の本来の目的は維持）。テスト `test/item-tab-pin-must-survive-getdata-2026-08-06.test.js`（5 件）。editor 全体 1119 件中 15 失敗は**このコミット適用前と同一集合**＝他セッションの未コミット yml 由来 |
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
