# 進行系（スキルツリー/EXP/パーク/アチーブメント）の恒久知識

このファイルは、進行系（スキルツリー・EXP・パーク・アチーブメント・採取ギミック）に初めて触るエージェント／開発者向けの恒久知識ベースです。作業履歴や日付付きの経緯は載せません（それは `reports/ACTIVE_RECORD.md` の役割）。ここにあるのは今後も踏みうる落とし穴と設計上の不変条件だけです。

## アーキテクチャの前提

### TFの進行系はValhallaMMOに依存しない独自(native)実装

TFの進行系（スキル経験値・レベル・パーク・スキルツリーGUI）は `com.trinityforge.progression` 配下に完結した独自実装であり、**main側にvalhallammoへの参照は無い**。中心は `NativeProgressionService` / `NativePerkService` / `NativeSkillExperienceListener` / `NativeExperienceDispatcher` / `catalog.NativeSkillCatalog`。永続化はSQLite（`Cached → Executor → Sqlite` の三段構成、専用スレッドで直列化、`plugins/TrinityForge/player_progression.db`）で、読込失敗はfail-closed。スキルツリーGUIも `/tf skills` としてTF内蔵の54スロットGUIで完結する。

※かつて「TFは独自GUIを持たずValhallaに描画させる」「native perk_rewardはValhalla側のprogression ymlデプロイ経路が必要」という設計だったが、これはValhalla依存撤廃で全面的に置き換わっている。GUI描画は `NativeSkillTreeCanvas` が `SkillTreeProgressionGenerator` → `SkillTreeLayout` を都度呼んで自前生成する。

### スキルツリーの座標はconfigに存在しない・Javaが起動ごとに再生成する

`skilltree/*.yml` は構造（parent/level/role/cost/group/icon等）だけを持ち、GUI上の座標は持たない。座標は起動ごとに `NativeSkillTreeCanvas` が `SkillTreeProgressionGenerator` → `SkillTreeLayout` で再生成する。したがって**配置の崩れ（ノードがずれる/消える/くっつく）はjarの修正だけで直り、config側の値をいじる必要はない**。`skills/base/*_progression.yml` はEXP曲線であって座標ではないので同様に触らなくてよい。

### ⚠️ 排他グループ(group)は「同じ親を持つ兄弟」にしか効かない

実装は `NativePerkService`（`filter(other -> Objects.equals(node.parent(), other.parent()))`）と `SkillTreeProgressionGenerator` の2箇所、検証は `SkillTreeConfig.validateGraph`（`groupCounts` のキーが `parent + NUL + group`）。そのため**路線を`parent`の付け替えで固定（lane-lock）すると、それより下の帯の`group:`は兄弟ゼロになり静かにno-opになる**。`AllSkillTreesLoadTest` は「読み込み時に警告ゼロ」を要求するため、複数のE2E/設定テストが連鎖的に落ちる。

- 正しい書き方: 路線の固定は`parent`連鎖だけで担保し、`group`は**親が共通のA帯にだけ**置く。
- 例外: 全帯が同じgroup名（例 `A-greek`）を使う書き方なら、有効な組がA帯に1つあれば警告は出ない。帯ごとに名前を分けた場合だけ問題になる。
- ギリシャ路線の`parent`を変更したときは必ず`group`の置き場所を見直すこと。

### スキルツリー座標生成の3規則（`SkillTreeLayout`）

1. **排他グループは片側の連続レーンにまとめる。** 兄弟を並び順で左右交互に振ると、排他兄弟同士が主軸や通常分岐を挟んで散り、GUIから排他関係が読み取れなくなる。単独の子（排他でない）は従来通り`trunkFanOrdinal`で左右交互のままにする。
2. **主軸の列（`startX`）はMAIN/INTERMEDIATEとプレステージ専用に予約する。** 空きセル探索が横方向しか見ないと分岐ノードが主軸列へ着地する。プレステージ座標は`layoutAll()`の後に確保されるため、先に主軸列がふさがれるとプレステージが1セル隣にずれて「くっついて見える」。
3. **8近傍に既存ノードがあるセルは使わない**（コネクタ1セル分の隙間を必ず残す）。探索コストは「横レーン数＋上へずらす段数」の小さい順、同コストなら上へ深くを優先。

不変条件は `AllSkillTreesProgressionTest` に全ツリー分の拘束テストとして入っている。

BRANCH（右半平面, +SIDE_STEP）とGREEK（左半平面, -SIDE_STEP）は完全に平面分離されており、単純な左右交互配置では実ツリーで衝突が出るためこの分離が必須（過去の90°回転前レイアウトからの変更点）。

### コネクタのCMD対応表（`gui/connection/*` / `GridConnectorRouting`）

スキルツリーGUIのコネクタは`<COLOR>_DYE:1172[7/8/9]<2桁>`形式のCustomModelDataトークンで色（ロック状態: GRAY=locked/ORANGE=unlockable/LIME=unlocked）と向きを表現する。縦=00/10/11/06、横=07/08/09、角=NE`└`{12,16,20}/SE`┌`{13,17,21}/NW`┘`{14,18,22}/SW`┐`{15,19,23}。この経路探索・形状コード・合成のロジックは`skilltree/generator`の`GridConnectorRouting`に集約済みで、新しいノード/分岐GUIを作るときは**このコネクタ実装を作り直さない**こと。前提としてリソースパックに`1172700`〜`1172723`のCMDが入っている必要がある。

### 解放済みノードのアイコン表示ポリシー

`SkillTreeGuiVisuals.node()` で、**解放済みノードは共通「解放済み」モデル（フロッピー見た目）に差し替えず、editorで指定されたperkアイコン（configured Material）をそのまま表示する**。解放済みかどうかは名前色（緑）とlore「解放済み」で区別する。locked/pending/connectorは専用モデルのまま。回帰ガードは`SkillTreeGuiVisualsTest.pendingNodeUsesConfirmTextureButUnlockedKeepsConfiguredIcon`。

## スキルツリーのカスタムコンテンツ：何が効いて何が効かないか

`skilltree/*.yml`の各ノードには複数の記述フィールドがあるが、実行時に効くものと効かないものがはっきり分かれている。

- **`buffs`は実装済み。** TF独自の数値ステとして`PerkBuffResolver`/`SkillPerkStatSource`経由で即座に適用される。`group`排他・`parent`前提・`level`/`cost`・prestigeも全て実装済み。
- **`effects`は未実装（パースのみ）。** `SkillNode.effects()`にパースされるが消費箇所は0件。SkillNode自体のコメントも"wiki display tags"と明記している。ノードの説明文に「圧縮木材で耐久回復」等が書いてあっても、対応するJava実装が存在しなければ効果はない。
- **`commands`は未実装（`tf skilltree unlock <nodeId>`という存在しないコマンドへの自己参照プレースホルダ）。** ノード解放自体はperkシステム（`perks_locked_add`/group排他/parent）が既に処理しているので本来不要。
- **「○○を解放」系の効果は、ArsPaper fork側のゲート機構（`spell/UsageGate.java`+`usage-gate.yml`、`recipe/UnlockGate.java`+`unlock-gate.yml`、`RitualManager`）を通して実際に機能する。** ゲートは`com.trinityforge.pdc.PlayerData.of(p).heldPerks()`を参照する。`PerkMirrorService`（`skilltree/runtime`）がjoin時＋定期的にunlockedPerkIdsをPDCへミラーしてこの経路を成立させている。ただし`usage-gate.yml`/`unlock-gate.yml`のマッピングは空のままだと当然機能しない（実perk IDの形式は`PerkNaming.perkId` = `<compact>_perk_<node>`、例 `arsmagic_perk_c`）。
- **武器コーティングはValhalla語彙の機構ではなく、`lightweapons_coatingunlocked_toggle`等のnativeキーで表現する。** ソースジェムのコーティングはArsPaper側に実体が無い。
- **村人取引解放（司書取引ゲート等）は完全にspec-only。** `VillagerAcquireTradeEvent`/`MerchantRecipe`を扱うコードはTF・両fork含め0件。
- **修繕本無効化はEliteMobs製アイテム限定。** `TrinityForgeRepairListener`が`PlayerItemMendEvent`/金床/砥石をcancelするのは`ItemTagger.isEliteItem`対象のみで、バニラ/TFカタログ品は通常通り修繕できる。

config-editorのskilltree UIには専用フォーム（`buildSkillTreeForm`, `public/js/tf-skilltree.js`）があり、位置/構造/buffs数値/native/名前は編集可能だが、`effect-text`/`effects`/`commands`は読取専用（ノード増減不可）。

### ⚠️ `dedicated-effects` の解放ゲートIDは「参照される側」と必ず突き合わせる

`dedicated-effects` の `id:` は自由文字列で、**存在しない先を指していても起動時に何も言わない**。
逆方向も同じで、**どのノードからも参照されていない解放グループは永久ロックになる**
（`BrewUnlockListener` は未参照グループを解放しない仕様）。片側だけ改名すると機構が丸ごと死ぬ。

実際に踏んだ例（いずれも 2026-07-31 まで死んでいた）:

| ゲートID | 参照される側 | 何が起きていたか |
|---|---|---|
| `overenchant:lv1`/`lv2`/`lv3` | `crafting-features.yml` の `over-enchant` プロファイルキーが `attack_1`/`attack_2`/`util_lv1` | 過剰エンチャント機構が全レベルで無効 |
| `brew:swiftness-jump` | `crafting-features.yml` に該当グループが**存在しなかった** | 錬金 C-1-upper(Lv40) が空振り |
| （なし） | `brew:healthboost-haste-2` グループがどのノードからも未参照 | 体力増強II 系が永久ロック |

**yml を触ったら両側を grep して突き合わせること。** `FailCloseGateSkillTreePlacementTest` が
一部を守っているが、網羅ではない。

### スライドが唯一の一次specである点への注意

`trinityforge/skilltree/スライド1-12.JPG`が採取・エンチャント・鍛冶・錬金・Ars等の「green-field効果」の唯一の設計原本。多くの値は「落ちやすく/低確率/まれに」など曖昧な表現＝config値は設計段階の暫定値であり、実装時に調整が必要という前提がある。実装オーナーの分担は、TFが直接ドロップ/ギミックを処理するもの（採取・ガチャ券・リンゴ・食料・over-enchant等）と、`TrinityForgeBridge.tfEffect*`経由でforkが処理するもの（遺跡/海洋スレッド、武器コーティング、ポーション統合等）に分かれる。

## 47ノード「機構ゼロ」問題からの確定設計判断

スキルツリーのノードの一部は`buffs`/`mainhand-buffs`/`dedicated-effects`のいずれも持たない（「設定のみ」＝yml側で数値を書けば動くタイプと、「機構なし」＝Java実装が必要なタイプがある）。以下は「機構なし」を実装するにあたってユーザーが確定した仕様判断で、コードを読んでも分からない部分。

- **エンチャントポイント（enchanting）＝「エンチャント運」を独自実装する。** ⚠️ バニラのエンチャントテーブル抽選にはプレイヤー側の運パラメータが存在しない（効くのは本棚数・アイテム固有enchantability・重み付き抽選のみ）。したがって「バニラ機構の踏襲」ではなく、`PrepareItemEnchantEvent`（提示レベル）＋`EnchantItemEvent`（確定内容）を操作してTF独自に候補の重みを上振れさせる実装が必要。オーバーエンチャント解放済みなら上限突破結果の重みも同じポイントで押し上げる。
- **ポーション品質（alchemy）＝効果時間・強度の割増に読み替える**（新品質ティアの移植はしない）。強度は切り捨て整数キャスト（例: 0.5設定で「2品質ごとに+1」）。
- **fishing の「海で釣ると同時ヒット確率UP」＝バイオーム判定を新設する**（条件を外した一律加算にはしない）。
- **alchemy「素材を消費しない確率UP」＝既存`ingredient_save_chance`をバニラの`BrewEvent`にも配線して拡張する**（新規stat不要）。
- **`feature:break-vanilla-exp`（破壊時バニラEXP解放）はスキルツリー側に一切配置されていない場合がある。** `FeatureEffectRegistry`への登録・`NativeSkillExperienceListener.grantBreakVanillaExp`の消費側実装は揃っていても、ツリーの各ノードに配置されていなければ丸ごと無効。配置は「設定のみ」の範囲としてユーザー側管理。`grantBreakVanillaExp`は`amount = BASE × (1 + bonus)`の連続値なので、段階的な強化表現（I/II/III）が可能。

## use-skill / 採取EXP表の落とし穴

### ⚠️ `use-skill`はアイテムの分類マーカーではない

`stats/item-stats.yml`は採取ツール（斧・ツルハシ等）にも`use-skill: WOODCUTTING`/`MINING`/`DIGGING`/`FARMING`/`FISHING`を持たせている（使用可能レベルのゲート用）。**「メインハンドのuse-skillをそのまま付与先スキルにする」実装は、採取ツールで殴っただけで採取スキルEXPが入るバグになる**（`CombatListener#maybeGrantCombatSkillExp`で実際に発生）。戦闘EXPの付与先は**必ずHEAVY_WEAPONS/LIGHT_WEAPONS/ARCHERYの3つへ明示的に絞る**こと（`CombatListener#isCombatWeaponSkill`）。`ARS_MAGIC`は`grantMagicExp`という別経路なので混ぜると二重付与になる。「道具で殴ったら代わりに戦闘スキルを与える」フォールバックは意図的に入れない。

### ⚠️ `skills/base/*_progression.yml` はサーバ上では「無ければ種まき」専用。resources を直しても既存サーバには効かない

`NativeSkillCatalog`（`progression/catalog/NativeSkillCatalog.java`）の javadoc に明記の通り、**実行時の真源は
`plugins/TrinityForge/skills/base/*.yml`（データフォルダ）** で、jar 同梱の `skills/base/*.yml` は
`seedDefaults()` が**そのファイルが存在しないときにだけ**コピーする一度きりの初期値。既に一度起動した
サーバはデータフォルダ側のコピーを使い続けるため、**resources 側の値を直して再ビルド・再配備しても、
既存プレイヤーが載っているサーバの挙動は変わらない**（対象ファイルを手動削除して再生成させるか、
値を手動でマージする必要がある）。「resources を直したのに実機で再現する」と報告されたら、まず
配備先の実ファイルが古いままでないかを疑うこと（`reload(dataFolder, classLoader)` は既存ファイルの
値をそのまま使う経路で、seed はしない）。※ただしこれは「値が古い」場合の話であり、**配備先が
resources と完全一致している（mtime/サイズ一致）のに実機で再現するなら、この節は原因ではない**
——2026-08-03 のジャガイモEXP0報告はこのパターンで、値は最初から正しく、実行経路側のバグ
（下記「置く→壊すガードが成熟作物を巻き込む」）が真因だった。

### ⚠️⚠️ `PlacedBlockTracker` の「置く→壊すEXPファーム対策」が成熟作物を巻き込むと、自作の畑が恒久的に0EXPになる

**症状**: config（`skills/base/farming_progression.yml`）の値が完全に正しく、配備先ファイルも
resources と一致しているのに、ジャガイモ/ニンジン/コムギ等を収穫しても農業EXPが一切入らない。
`CropMaturity` の成熟判定も正しく動く。**「config は合っているのにEXPが0」というときは config を
疑う前にこの節を疑うこと。**

**機構**: `PlacedBlockTracker`（`listeners/PlacedBlockTracker.java`）は `BlockPlaceEvent` を通った
座標を「設置ブロック」としてチャンクPDCへ記録し、`NativeSkillExperienceListener#onBlockBreak`
（および `grantChainBreak`/`onEntityExplode`）は破壊のたびに `clearIfPlaced(block)` を呼んで、
一致すれば**その破壊を丸ごとEXP対象外にする**——これは「石を置いて壊すだけの採掘EXPファーム」
「原木を置いて壊すだけの伐採EXPファーム」を防ぐには正しい設計。しかし**成熟ガード対象の作物
（`CropMaturity.MATURITY_GATED` = WHEAT/CARROTS/POTATOES/BEETROOTS/NETHER_WART/COCOA/
SWEET_BERRY_BUSH/TORCHFLOWER_CROP/PITCHER_CROP/MELON_STEM/PUMPKIN_STEM）は必ずプレイヤーが
種を植えることでしか存在しない**——種まきそのものが `BlockPlaceEvent` を通るため、**自分で育てて
収穫した完熟作物は例外なく「設置ブロック」としてマークされ、農業スキルの主経路（単体破壊）が
常時0EXPになっていた**（2026-08-03、ユーザー報告「ジャガイモ収穫でEXPが上がらない」の真因。
コムギ/ニンジン/ビートルート/ネザーウォート/ココア/スイートベリー/カボチャ/スイカの茎も同型で
同じ穴を持っていた）。

**なぜ見落とされていたか**: 同種のガードを先に実装していた `FarmingGimmickListener`（drop-table
抽選、2026-08-01新設）は最初からこの罠に気づいて分岐していた
（`passesFarmingAntiLoopGuard`: 成熟ガード対象の作物は設置マークを見ず、代わりに完熟だけを要求する。
それ以外は従来通り `isPlaced` で弾く）。しかし**同じ回避策が本体のEXP付与経路
（`NativeSkillExperienceListener`）には一度もバックポートされておらず**、ドロップテーブルの
ボーナスは出るのにEXPは出ない、という食い違った状態のまま出荷されていた。「別リスナーに正しい
実装がある」ことは、それがコピーされているとは限らない一例。

**修正**: `NativeSkillExperienceListener` に共通ヘルパー `blockedByPlaceBreakGuard(Block)` を作り、
`onBlockBreak`/`grantChainBreak`/`onEntityExplode` の3経路すべてで
`placedBlockTracker.clearIfPlaced(block)` を直接使うのをやめ、これを経由させた
（`FarmingGimmickListener#passesFarmingAntiLoopGuard` と同じ規則: `CropMaturity.isMaturityGated`
なら設置マークがあってもEXPを止めない。マーク自体は毎回消費するのでPDCが際限なく溜まることはない）。
成熟ガード対象でない作物（サトウキビ/竹/コンブ等、`age`が周回するタイプ）は**従来通り設置マークで
弾かれる**——これらは完熟の概念が無く壊すと手元に戻るので「置く→壊す」がノーコストで回るため、
このガードを外してはいけない。回帰は `NativeSkillExperienceListenerCropMaturityTest`
（`matureCropPlacedByPlayerStillGrantsFarmingExp`/`immatureCropPlacedByPlayerStillGrantsNothing`/
`placedSugarCaneStillBlockedByPlaceBreakGuard`）が固定している。

**教訓**: `PlacedBlockTracker` を新しい採取系リスナーに配線するときは、対象ブロックが
「プレイヤーが種を植えることでしか存在しない（＝設置マークが必ず付く）」タイプかどうかを
`CropMaturity.isMaturityGated` で確認すること。確認せずに掘削/採掘/伐採と同じ `isPlaced`/
`clearIfPlaced` を素で使うと、そのブロックの主経路が恒久的に無効化される。

### ⚠️ 縦積み植物（サトウキビ/竹/コンブ等）の根元を壊すと、上に育った段のEXPが連鎖破壊ごと消える

**症状**: 「サトウキビの根元を壊すとEXPが入らない」報告（2026-08-03）。根元1段だけなら
`blockedByPlaceBreakGuard`で0になるのは仕様どおりだが、**上に育った段（設置マークの無い自然成長分）
まで巻き込んで0になっていた**。

**機構**: サトウキビ/竹/コンブ/サボテン/ねじれツタ/泣きツタは支持ブロック(下、泣きツタのみ上)を
失うとバニラの物理挙動で連結した段がまとめて消える。この消滅は`BlockBreakEvent`を伴わない自動破壊
なので、`NativeSkillExperienceListener#onBlockBreak`が一度も呼ばれず、育った段のEXPがまるごと
失われる——`Block#breakNaturally`が連鎖破壊イベントを発火しないのと同型の穴（`ChainBreakSupport`の
javadoc参照）。

**なぜ根元の設置マークガード自体は外してはいけないか**（要検討だった設計判断）: `PlacedBlockTracker`
は`clearIfPlaced`でマークを消費し、`BlockPlaceEvent`のたびに`markPlaced`で無条件に再付与する——
**根元位置には一切のクールダウンが無い**。よって根元のガードを外すと「植えて壊す」を待ち時間ゼロで
無限に回せる置く→壊すEXPファームが即座に開く。育った段には「成長に時間がかかる」というレート制限が
自然にあるが、**根元そのものの再設置サイクルにはその制限が効かない**ため、根元は引き続き弾く必要が
ある。

**修正**: `onBlockBreak`の冒頭（`blockedByPlaceBreakGuard`判定より前）でバニラが崩す前に自前で
連鎖対象ブロックを確定し（`StackingPlantChain`が連結方向＋同一系統Materialを保持）、
`ChainBreakSupport.breakChain`で1段ずつ崩してEXPを付与する（`grantChainBreak`を再利用するので
各段が個別に`blockedByPlaceBreakGuard`を通る＝手植えで積み上げた場合は各段が個別マークを持つため
従来どおり全段0のまま——ファームの抜け道にはならない）。回帰は
`NativeSkillExperienceListenerStackCollapseTest`（新設、MockBukkit使用）が固定している。

**テスト構築上の罠**: `ChainBreakSupport.breakChain`は`World#getGameRuleValue(GameRules.BLOCK_DROPS)`
を読むため、`GameRules`クラスの静的初期化にBukkitレジストリ（=起動中のサーバ）を要求する。
生Mockitoモックの`World`/`Block`だけでは`ExceptionInInitializerError`になる。かといって
`MockBukkit.mock()`を呼ぶだけでも足りない——**実ワールドを1つも作らないまま`GameRules`を触ると
`IncompatiblePaperVersionException`/`NullPointerException("The rule can't be null!")`
（`MockBukkitInternalAPIBridge.legacyGameRuleBridge`内）になる**（`MockBukkit.mock()`直後に
`.addSimpleWorld(...)`等で実ワールドを1つ作らせてからでないと、`GameRule`レジストリのデータが
読み込まれない）。さらに、たとえ`GameRules`が正常初期化できても**MockBukkitの`Block#getDrops(tool,
player)`は無言で空コレクションを返す**（`TreeFellingListenerTest`に既知の罠として記載済み）ため、
`gatheringExp`の「ドロップが空なら0」ガードに常に落ちてEXPが検証できない。結局、連鎖崩壊のEXP量を
検証するテストは「`MockBukkit.mock()`+実ワールド1つ（`GameRules`用）」と「対象ブロック自体は
生Mockitoモック（`getDrops`を明示的にスタブ）」を併用する必要がある
（`NativeSkillExperienceListenerStackCollapseTest`が実例）。

### ⚠️ `block_interact` と `block_drops` はゲート方式が違う（1行完結 vs ブロック行=ゲート/ドロップ行=実量の2行制）

`gatheringExp`（drop_sum モード）が読む `block_drops` は「ブロック名の行=ゲート（値>0か）、実際の
EXPはドロップ材質名の行の合計」という2行制（下記節）。一方 `onFarmingInteract` が読む
`block_interact`（右クリック収穫、`CAVE_VINES`/`CAVE_VINES_PLANT`＝グロウベリー摘み取り、
`SWEET_BERRY_BUSH`、`BEEHIVE`/`BEE_NEST` 等）は**単一行が丸ごとEXP量**（ドロップ材質側の行を別途
足す必要はない）。この2つの方式を混同して「ブロック行はゲート専用だから0でよい」と誤って
`block_interact` 側を0のままにすると、`isHarvestableFarmingInteraction` の判定自体は true を返すのに
EXPが常に0になる（2026-08-03、グロウベリーの `CAVE_VINES`/`CAVE_VINES_PLANT` が実際にこれで
出荷 yml 上0のまま長期間出荷されていた）。値を触るときは、その action が
`NativeSkillExperienceListener` のどちらの経路（`gatheringExp`系 or `dropActionExp`/直接`expFor`系）
で読まれているかを先に確認すること。回帰は `NativeSkillCatalogTest#farmingRightClickHarvestGatesAreConfigured`
が固定している。

### ⚠️ 家畜討伐EXP（`entity_breed`ゲート）も Monster/Animals の罠を踏む——`entity_breed`は「配合可能」であって「非敵対」ではない

`NativeSkillExperienceListener#onFarmingMobDeath`（`entity_drops`＝家畜討伐でドロップ材質に応じて
FARMING EXPを配る経路）は、討伐対象が`entity_breed`表に載っているか（値>0）だけをゲートに使う。
`entity_breed`はValhalla由来の「配合可能な生物」一覧であって「非敵対」の一覧ではないため、
**HOGLIN のようにクリムゾン菌糸で配合できる(=`Animals`)が実際は Paper の `Enemy`(敵対)でもある種**は
討伐しただけで農業EXPが入っていた(2026-08-03)。修正は `event.getEntity() instanceof Enemy` なら
即除外（`AnimalDamagePolicy`と同じ「敵対判定は必ずPaperの`Enemy`」原則の適用）。`entity_breed`表に
新しい生物を足すときは、配合可能かどうかだけでなく Paper API で実際に `Enemy` を実装していないか
（`javap`で確認、記憶に頼らない）を必ず確認すること。ZOGLIN は現状この表に未掲載のため実害は無いが、
`Monster`（→`Enemy`を継承）を実装するため将来同表に足すと同じ穴を踏む。

### ⚠️ 採取EXP表（`skills/base/*_progression.yml`）はブロック行だけでは効かないことがある

出荷既定の`exp-mode: drop_sum`では、「ブロック名の行」はゲート判定（値>0か）専用であり、**実際に付与されるEXPはドロップしたアイテムの材質名に対する値の合計**。ブロックとドロップ品が同じ場合（大半）は1行で足りるが、`SEA_LANTERN`→`PRISMARINE_CRYSTALS`のように違う材質を落とすブロックは、**ドロップ側の行も足さないとEXP0のまま**（テラコッタが表から丸ごと抜けていて掘っても0だった実例あり）。ブロックを追加するたびに、実際のドロップ品目を確認して行を足す必要がある。シルクタッチ無しで何も落とさないブロック（氷等）はEXP0が正しい挙動。

### ⚠️ `instanceof Ageable` は「成熟する作物」の判定にならない（成熟ガードは必ずホワイトリスト）

Bukkitの`Ageable`は「`age`プロパティを持つブロック」という意味しか持たず、**`age`の意味はブロックごとに違う**。小麦・ニンジン・ジャガイモ・ビートルート・ネザーウォート・カカオ・松明花・ピッチャー・ベリーは`age`が成熟度で`age == maximumAge`が収穫適期だが、**サトウキビ・コンブ・サボテン・ねじれツタ・泣きツタ・光ツタの`age`は「次の1段を伸ばすまでのカウンタ」で、最大値(15や25)に達した瞬間に新しい段を生やして0に戻る**（竹の`age`は太さ0/1にすぎない）。したがって`instanceof Ageable && age < maximumAge`で未成熟を弾くと、**収穫できる状態のサトウキビはほぼ常に「未成熟」と判定され、農業EXPが永久に0になる**。実際に`NativeSkillExperienceListener#grantGathering`がこれで壊れており、しかも判定が`return false`だったため**破壊時バニラEXP解放（`break-vanilla-exp`）まで道連れで無効**だった（サトウキビ/コンブ/竹/ねじれツタ/泣きツタ/光ツタの6種が該当。2026-08-01 U9で修正）。判定は`com.trinityforge.farming.CropMaturity`に一元化してあり、**「成熟しないと収穫できない作物」のホワイトリスト方式**にしてある。除外リスト方式にすると、Paperが新しい`Ageable`ブロックを追加するたびに同じ「無言でEXP0」が再発するため、未知のブロックは常に収穫可能として素通しする（fail-open）。Paper 1.21.11で`Ageable`を実装するMaterialは22種で、`CropMaturity`のjavadocに全件の分類がある（Paperを上げたら`Material`のブロックデータクラスを走査して再確認する）。

なお`FarmingHarvestListener`（auto-replant/area-harvest）と`PlantedCropGrowthListener`は`FarmingCropCatalog.isCrop`で先に5作物へ絞っているのでこの罠を踏んでいない。一方**`GatheringExtraDropListener`の`harvest_extra_drop_chance`は素の`Ageable && age == maximumAge`のままなので、サトウキビ/コンブ/竹等では追加ドロップが一切抽選されない**（逆にコーラスフラワーや凍結氷が「成熟した作物」に化ける）。これは収穫量＝バランスに触る変更になるので未修正のまま残してある。

## EXPシステムの構造

### EXP経路は3系統＋バニラ経験値オーブの計4系統

- **武器スキルEXP**＝命中トリガ（`CombatListener`）。同一ターゲットに対するクールダウンあり。
- **防具スキルEXP**＝被弾トリガ（`NativeSkillExperienceListener.onArmorDamage`、`getFinalDamage()`由来）。同じ場所に立って殴られ続けるだけで稼げる経路なので、EXP系の対策（AFK・TT対策等）を入れるときにここを見落とすと穴になる。
- **魔法EXP**＝詠唱（`ArsProgressionBridge.grantMagicExp`）。
- **バニラ経験値オーブ**＝完全に別パイプライン。`MobLevelTableListener`（HIGH、レベル帯の`vanilla-exp`）→`MobOverrideExpListener`（MONITOR、モブ個別の`vanilla-exp`ランプ）が後勝ちで上書きする。モブ個別設定が無ければ`setDroppedExp`は一切呼ばれない＝**「未設定」は0EXPではなく無干渉**（この差は意図的な設計）。

**ワールドゲート・倍率などの合成規則を新しいEXP経路に足すときは、必ず`SkillExpConfig.worldExpRate(boolean inDungeonWorld)`のような一点集約を経由すること。** 呼び出し側が個別にフラグと倍率を見る書き方をすると、片方を忘れた経路が静かに全額付与になり、テストにもエラーにも出ない（Ars魔法EXPがワールドゲート未適用のまま長期間素通りしていた実例あり）。

### ⚠️ モブEXPの`growth`をHPと同じ値にしてはいけない

HP/攻撃の指数成長率は「同帯装備なら所要時間がほぼ一定になる」よう校正された値。一方EXPの用途（エンチャ・金床のコスト）はプレイヤーレベルにしか依存しないため、HPと同じgrowthを使うと同じ手間で報酬が桁違い（1000倍等）になり経済が破綻する。EXPのgrowthはHPより大幅に緩やかな値（実装例: 全モブ共通1.008程度）にし、役割係数（雑魚/中ボス/ボス）で規模だけ振る設計にすること。モブ個別のvanilla-expはPDCに刻印されたキル時のレベルで評価するため、`level: dynamic`（入場時にレベルへ追従するモブ）でも正しく追従する。

### ⚠️ モブの分類にBukkitの`Monster`/`Animals`は使えない

1.21.11時点で`HOGLIN`は`Animals`判定だが実際は敵対。**Paperの`Enemy`マーカーが正しい敵対判定**。名前に反する非敵対の例として`CAMEL_HUSK`・`ZOMBIE_NAUTILUS`がある。`MANNEQUIN`は唯一`Mob`を実装しない`LivingEntity`（ArmorStand側の置物）なので戦闘モブ表からは意図的に除外する。友好モブは`max-health`を書かずバニラHPのままにしてよいが、**`attack: damage-modifier: 1`は明示が必須**（省略すると0扱いで与ダメージが半減する）。実際のEntityType一覧は記憶に頼らず`paper-api`から`EntityType.values()`を列挙して確認すること。

### TT（同一地点連続狩り）対策は「撃破のみ」でカウンタを増やす

⚠️ 半径・時間窓・しきい値方式の逓減カウンタは、**撃破イベントでのみ加算する**（読み取りは何度呼んでもよい）。被弾のたびに加算する設計にすると、少数のモブに複数回殴られるだけでしきい値に達し、正常なプレイでもEXPが減り始める誤爆になる。倍率は武器/防具/バニラEXPの3経路すべてが読む必要がある（経路ごとに別勘定にすると経路を混ぜるだけで回避されてしまう）。下限を0にしないのは「完全に潰す」のではなく「割に合わなくする」のが狙いであるため。

## アチーブメントのノード化

### ⚠️ バニラ進捗（advancement）の解除を止められるイベントは1つしかない

`com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent`だけがバニラ進捗の解除を抑止できる（`io.papermc.paper.event.player.*`ではない）。`PlayerAdvancementDoneEvent`は`Cancellable`を実装していないため抑止には使えない。`minecraft:recipes/`配下の進捗はレシピ本の解禁を配っている隠し進捗なので、これを塞ぐと新レシピが一切解放されなくなる。

### ⚠️ `PlayerAdvancementDoneEvent`は1回しか飛ばない

前提未達成のときにこのイベントだけで判定すると、`type: advancement`のアチーブメントは前提が後から満たされても永久に取れなくなる。**`player.getAdvancementProgress(...).isDone()`を定期的にポーリングして読み直す回収パスが必須**（バニラが完了状態を永続化しているのがこの後追いを可能にする唯一の手段）。ポーリングは`while (progressed)`で回すこと（1パスだと親が解けた周期で子は次の周期まで待たされる）。

### ⚠️ `Bukkit.getAdvancement()`自体が例外を投げる実装がある（MockBukkit）

try節の外に置くとテストが「失敗」ではなく「中断（SKIPPED）」になり静かに素通りする。MockBukkitのSKIPPED素通りは他の箇所（`damageItemStack`未実装等）でも再発する既知の罠なので、テストを書くときは必ず結果が実際にPASSしているか（SKIPPEDでないか）を確認すること。

### ⚠️ `type: advancement` は `vanilla-advancements.disabled: true` と同時には成立しない

`disabled: true`（既定）だと `PlayerAdvancementCriterionGrantEvent` がキャンセルされるので、
`type: advancement` のアチーブメントは**永久に達成不能**になる。起動時に WARNING は出るが
それだけで、達成不能な定義がそのまま生き残る。バニラ進捗を抑止する運用では
**`type: statistic` + `statistic-qualifier` で書き直す**のが正解
（例: `minecraft:story/mine_stone` → `statistic: MINE_BLOCK` / `statistic-qualifier: STONE`）。

### アチーブメントの確定仕様

- 前提（`parent`単一/`parents-any`複数）は「達成そのものを縛る」。前提未達成の間はトリガー条件を満たしても達成にならず報酬も出ない。`parent`と`parents-any`は両方書いてもOR（どちらか1つで開く）。
- editorでIDを改名/削除したら、他ノードの`parent`/`parents-any`を必ず張り替える。放置すると「存在しない前提」になり、その枝が丸ごと達成不能になる。
- `PlayerData#revokeSpecialReward`は直接付与リストにあるIDしか見ない。スキルツリーの`reward:<id>`perk経由で装備しただけの報酬（`equippedTitle`/`equippedParticle`等）は付与リストに入らないため、剥奪処理は**別経路で個別に掃除**しないと残り続ける（＝孤児化）。
- 壊れたYAMLを「全部未定義」と誤読して全員の報酬を消す事故を防ぐため、`load()`はエントリが1件でもskipされた回を失敗扱いにする安全弁がある。

### ⚠️ `collection.scope: all` の母数と `reward-tiers` のしきい値は**別の数**を数えている

同じ「図鑑の進捗」に見えるが集計元が違う。混同すると「50%で達成」と書いたつもりが実際には
まったく違う地点になる。

| 経路 | 数えているもの | 現在の母数 |
|---|---|---|
| アチーブメント `type: static` / `scope: all`（`CollectionService#progress`） | `collection.yml` の `categories` に**明記されたエントリだけ**の和 | 140 |
| `collection.yml` の `reward-tiers`（`grantPendingTiers`） | プレイヤーPDCの**登録総数**（カタログ品286種・敵性EntityTypeの討伐・参照されたバニラ品を含む） | 約360 |

`scope: category` のしきい値は**そのカテゴリの `entries` 件数以下**でなければ永久に未達成になる
（分母を超えても警告は出ない）。`scope: item` / `mob` は `threshold` を省略すると「列挙した件数」
＝全部そろったら達成が既定。`scope: category` / `all` はこの既定が効かないので必ず書く。

### ⚠️ `scope: item` に書いたIDは「図鑑に記録される対象」を増やす副作用がある

`CollectionListener#watchedConfigIds` は図鑑カテゴリだけでなく**アチーブメントの
`collection.scope: item` の対象IDも監視集合に入れる**。つまりカテゴリに載せていない
ArsPaper 側アイテム（スレッド `thread_*` など）でも、アチーブメントから参照するだけで
記録が始まる。便利だが、記録が増えると `reward-tiers` の登録総数も動く点は意識しておく。

### ⚠️ `draft: true`（準備中）の catalog ID は図鑑の分母から個別に除外しないと永久に100%へ届かない

`CollectionService#progress` の候補集合（分母）は `collection.yml` の `categories.items`/`mobs` の
列挙をそのまま使う。`items/catalog.yml` を `draft: true` にしても `collection.yml` 側の列挙は
（editor 内参照を残す仕様なので）**消えない**ため、放置すると理論上入手不可能な draft ID が
分母に残り続け、`scope: all`/`category` の図鑑進捗が永久に満数へ届かない（2026-08-02、
abyss_* 13件 + binder_* 17件 = 30件で実際に発生）。対策は `CollectionService` が候補へ足す前に
`CrossPluginItemResolver#isDraft`（`itemResolver` 注入時のみ判定可、未注入なら fail-open で
従来どおり含める）で弾くこと。`collection.yml` 自体は触らなくてよい（触るべきでもない）。

### アチーブメントの整合性は `ShippedAchievementTreeTest` が機械で縛っている

このファイルの間違いは**どれも起動時の警告1行で済み、ゲーム内では「そのノードが無いだけ」に
見える**。34ノードを目視で確かめ続けるのは無理なので、出荷 yml を実際に `parse` に通して
固定してある（skip 0件 / 前提の全解決と非循環 / 起点1つ / `rewards.special` のIDが
`special-rewards.yml` に実在 / `category` のしきい値が候補数以下 / `item` の対象が実在ID /
`counter` は加算実装のあるIDだけ / `type: advancement` を1件も使わない）。
**`permanent-buffs` を持つノードが `goal_worldbinder` ただ1つ**であることも固定している
（2026-07-31 ユーザー確定「束縛者だけ縦強化、他は称号/コスメ」。ここが緩むと格差吸収に
選んだ3本＝24h EXP減衰／指数コスト／横の選択肢が全部意味を失う）。

### `counter` トリガは加算側が別プラグインにある

`trigger.type: counter` は `PlayerData#lifetimeCounter` を読むだけで、**加算するコードは
TF 本体に無い**。現在唯一の実装 `source_spent` は ArsPaper フォークの `RitualManager` が
`TrinityForgeBridge.recordSourceSpent` 経由で `trinityforge:counter_source_spent` PDC へ直接
加算している（TF のクラスを参照しないよう**キーを文字列で組んでいる**ので、TF 側は
`LifetimeCounterKeyTest` でその綴りを固定している）。カウンタIDを増やすときは
加算側・`AchievementsConfig` の許可・editor の `ACHIEVEMENT_COUNTER_IDS`（`lib/schema.js` と
`public/js/tf-rewards-forms.js` の2本）を同時に足すこと。1つでも欠けると
「条件を満たしようがないアチーブメント」が静かにできる。

### ステータス表示（`/tf stats` / `/tf status`）は1経路に集約する

表示整形をGUI側で独自に書き直すとチャット表示と食い違う。合算は`PlayerCombatAggregate#combined()`、整形は`StatValueRenderer`の1経路に集約してある。丸めは切り捨て（負値も絶対値側）。四捨五入にすると表示が実効値より有利側へ振れるので使わない。`StatsCategory#includes`は排他ではないため、同じキーが複数カテゴリに一致しうる＝最初に一致した1つだけに載せないと合計が合わなくなる。GUIのカテゴリ枠は入力で数を変えない（値0のステは落としてよいが、セクション数が変わるとスロット配置が崩れる）。

再利用できる資産として`GridConnectorRouting`（コネクタ経路探索）と、`AchievementCanvas`/`StatusGuiModel`（Bukkit非依存の純関数）がある。新しいGUIを作るときはこの「純粋な投影＋薄いBukkit層」の分け方に倣うこと。

## AFK・ノードロック・その他の設計判断

- **⚠️ AFK判定に素振り・素クリックを含めてはいけない。** 含めるとオートクリッカーで機構全体が無効化される。活動判定は移動（座標と視点回転）・チャット・コマンド・インベントリ操作・ドロップ・スニークのみ。
- **AFK中のバニラEXP停止は`PlayerExpChangeEvent`のLOWESTの1箇所だけに集約する。** 討伐オーブも同じイベントを通るため、`MobLevelTableListener`の`vanilla-exp`側で二重に止めてはいけない。同リスナーは`add-drops`のみ止め、報酬でない`remove-drops`は通す。
- **⚠️ ノードロックしたパークはプレステージ時に「返却せずに」無償再付与する。** 返却してから再付与すると、実際には払っていないSPが増える＝実質SP無限増殖になる。逆に`resetTree`（振り直し）はロックも含めて全解除する（ロックを保護するとSPが二度と戻らなくなる）。
- ツリーリセットは新しいDBスキーマを増やす必要はなく、`saveAdminProgressionEdit(prestigePerkPrefix=null)`にレベル・プレステージ段をそのまま渡せば実現できる。
- モブ名はサーバ側で日本語化できない（`Component.translatable("entity.minecraft.*")`はクライアント翻訳＝Geyserでも効く）。日本語で検索/ソートしたい場合のみ`collection.yml`の`display-names.mobs`で上書きする。図鑑の検索は未発見エントリを名前で引けないよう除外する。
- 解体（分解）でクラフトレシピを持たないアイテム（釣りのゴミ等）を扱う唯一の手段は`base-amount`。`input`はレシピを引く関係で材料数が必ず0になり、戻りが発生しない。
- 横断的なゲート（drop gate / sell gate等）を既存リスナーに追加するときは、コンストラクタを変えず任意セッター注入（`setDropGate`等）にする。これにより既存テストのコンストラクタ呼び出しを壊さずに済む。述語の例外は全てfail-open（判定不能なら通す）にすること。

## 採取アクティブスキルの確定設計（フレームワーク先行方針）

採取系ギミック（一括破壊・一括伐採・採掘ブースト等）とアクティブスキル全般について、以下がユーザー確定の設計方針。

- 一括破壊/一括伐採等の`feature:`は、二値フラグ＋グローバル固定値ではなく、`tiers:`表（tier→params）で段階的にパラメータ化する。`tiers`未定義の場合はグローバルscalarとして後方互換動作する。
- 伐採の「small-tree-fell」「large-tree-fell」は`tree-fell`1本へ統合し、tierごとに処理量を変える設計（tier1=8本、tier3=32本のように段階化）。
- アクティブスキルの正式トリガーは`/tf active <id>`＋GUI。sneak+右クリックのような暗黙トリガー（haste等）は補助として温存してよいが、新設スキルの主経路にはしない。
- コスト層（mana等の消費）はv1では実体を作らず、インターフェースのみ用意する。
- **Java側の`FeatureEffectRegistry`とeditor側の`gate-vocabulary.js`のFEATURES配列は語彙パリティを保つ必須ペア。** 片方にだけ追加すると、実装済みなのにeditorから配置できない（またはその逆）という事故になる（`break-vanilla-exp`が過去に実際にこれで踏まれた）。

### ⚠️ `drop:<prof>:<categoryId>` は「自動反転規則」でfail-open。配置ゼロ=無条件開放

`DropTablePolicy.isOpen`（`TrinityForge/src/main/java/com/trinityforge/stats/DropTablePolicy.java`）は
「`dropGatePerks` に未参照のカテゴリ＝全員に開放」という設計（`glyph:`/`recipe:`/`ritual:`と同じfail-open族。
対照的に`brew:`/`trade:`/`overenchant:`はfail-close）。したがって、`stats/*-gimmick.yml`に
`drop-tables.categories`を新設したら、**対応する`drop:<prof>:<categoryId>`をどこかのスキルツリーノードの
`dedicated-effects`に必ず置くこと**。置き忘れるとレベル0のプレイヤーから無条件で引ける
（2026-08-01 実例: 農業に新設した`gacha_tier1`/`compressed_bread`の2カテゴリが未配置のまま出荷されかけた）。
`FailCloseGateSkillTreePlacementTest`はこの`drop:`系を意図的に対象外にしている
（fail-openなので未配置は「壊れている」ではなく「正常」というのがそのテストの前提）。
つまり**`drop:`の配置漏れを検出する自動テストは無い**ので、`drop-tables.categories`を足すたびに
手動で「対応するdedicated-effectsが実在するツリーに存在するか」をgrepで確認すること。
参考: `drop:mining:gacha_tier1`等は自ツリー(mining.yml)ではなく`digging.yml`側に配置されている
（採掘の景品を「切削の進行」でも解放する設計）。`drop:<prof>:`の`prof`はノードが属するツリーの
スキル名と一致している必要はなく、`stats/*-gimmick.yml`側の`drop-tables`を読むリスナーの
`PROF_*`定数と一致していればどのツリーに置いてもよい。

### `registerEvents`配線漏れの検出テストは存在する（`RegisterEventsDriftTest`）が、限界がある

`TrinityForge/src/test/java/com/trinityforge/listeners/RegisterEventsDriftTest.java`
（2026-08-01新設）は`com.trinityforge.listeners`配下の全`implements Listener`クラス名が
`TrinityForge.java`の本文にテキストとして1度でも現れるかだけを見る。**「importされている/他クラスの
コンストラクタに渡されているだけで、そのListener自身はregisterEventsされていない」という中間状態は
検出できない**（class名の単語境界一致だけを見る簡易実装のため）。新しいListenerを追加したら、
このテストが緑でも`getServer().getPluginManager().registerEvents(...)`まで実際に到達しているかを
目で確認すること。意図的に配線しないListenerが出た場合は同ファイルの`ALLOWED_UNREGISTERED`に
理由コメント付きで追加する（2026-08-01時点では空＝全Listener実装が実際に配線済み）。

## Ars鍛冶（儀式）EXP

### ⚠️ 品質刻印の可否とEXP付与の可否を同じ門にしてはいけない（ArsPaperフォーク）

**症状**: 「Ars鍛冶の経験値が入らない」報告（2026-08-03）。`ArsProgressionBridge.grantSmithingCraftExp`
自体（素材トークン合計→定額フォールバック）は正しく実装されているのに、儀式で作った特定の品目には
EXPが一切入らなかった。

**機構**: `fork-handoff/arspaper/fork/.../ritual/RitualManager.java`（儀式完了処理）と
`.../integration/TrinityForgeBridge.java`（`finalizeCatalogRitualResult`/旧`finalizeArsSmithingResult`）は、
**EXP付与の呼び出しそのものを「品質を刻める結果か」の門の内側に置いていた**
（`MaterialTier.isEquipment() || isArsQualityStamped(item)`、または
`ItemRegistry.get(id).filter(BaseCustomItem::isQualityStamped)`）。装備（武器/防具）はこの門を通るので
問題なかったが、**「品質という概念自体が意味を持たない」結果——ソースジェムの系譜
（source_gem→source_shard→…→singularity_proof）・エンチャント本（mana_regen/boost/share/soulbound）・
ウェイストーン・テレポートコンパス・そして`items/catalog.yml`の`thread_*`系40件（スレッド。
`external-source: arspaper`、アイコンは`*_ARMOR_TRIM_SMITHING_TEMPLATE`で`MaterialTier.isEquipment()`は
常にfalse）——は、この門で弾かれて**EXP付与へ一度も到達しなかった**。`BaseCustomItem#isQualityStamped()`
は既定`false`で、`SpellBook`/`CatalystItem`だけが`true`へ上書きする（`ConfigurableMaterial`は
上書きしない）ため、この集合は常に0EXPだった。

**なぜ無限EXPにならずに門を開けられるか**: `RitualManager`/`RitualRecipe`には分解・逆儀式の概念が
**一切無い**（grep確認済み）。唯一「作って壊して作り直す」が成立するのは
`RecipeManager`（**バニラ作業台レシピ**、`materials.yml`の`reversible: true`、圧縮素材の
compress/decompress）だが、これは儀式とは別系統のパイプラインで、`CraftItemEvent`経由
（`CraftQualityListener`）を通る。そちらは「完成品に使用可能レベルが無ければEXPを一切出さない」
という別の反ファームゲートで既に保護されている（圧縮/解凍素材は使用可能レベルを持たないため
0EXPのまま——これは意図どおりで、儀式側の修正とは無関係）。

**修正**: 品質刻印とEXP付与を別関数に分離した。`TrinityForgeBridge#grantArsSmithingExpOnly`
（新設）は品質を刻まずEXPだけを付与し、`isQualityStamped`/`isEquipment`が偽の分岐でこちらを呼ぶ
（`finalizeArsSmithingResult`は品質を刻める結果専用のまま残し、内部で
`stampCraftedQuality`→`grantArsSmithingExpOnly`の順で呼ぶよう再構成）。
`RitualManager`のネイティブ結果分岐・`TrinityForgeBridge#finalizeCatalogRitualResult`の
tfcatalog分岐の両方に同じ分離を適用した。回帰は
`fork-handoff/arspaper/fork/src/test/java/com/arspaper/ritual/RitualQualityExpGateSeparationTest.java`
（フォークの既存流儀に合わせ、MockBukkitを使わずソーステキスト走査で固定）。

**副産物**: `TrinityForgeBridge`側の`catch (Throwable t) {}`（TF側API不整合を握り潰す安全弁）が
**完全に無言**だったため、フォークの`libs/TrinityForge.jar`が古い等でEXP付与が例外落ちしても
誰にも気付けなかった。`grantArsSmithingExpOnly`では最低限の警告ログを残すよう変更した——
TF/フォーク境界の`catch (Throwable)`を新設・変更するときは、フェイルセーフのために握り潰すのは
よいが**必ず警告ログだけは残す**こと（さもないと同種の不具合が今後も無症状のまま埋没する）。

### `items/catalog.yml` は儀式レシピを2種類のキーで書ける（`recipe:`単数 と `recipes:`複数）

両方とも`ItemTemplate#recipes()`（TF側）に統合され、`CatalogRitualRegistrar`/`RitualManager`
（フォーク側）から見て完全に同じ扱いになる。**しかし`ShippedRitualMaterialExpCoverageTest`
（`smithing.exp-per-material`の網羅性を固定するテスト）は単数`.recipe`キーしか走査しない**ため、
`recipes:`（複数、主にスレッド`thread_*`40件が使用）の消費素材は網羅チェックの対象外——
表に無い素材があっても警告も落ちるテストも無い。儀式のカバレッジ関連テストを触るときは、
この2キーが両方とも実際に登録される点を踏まえること（`ItemTemplate`のjavadocに明記あり）。

## 関連

- [./combat.md](./combat.md)
- [./forks-and-mobs.md](./forks-and-mobs.md)
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)
- [./bedrock-geyser.md](./bedrock-geyser.md)
