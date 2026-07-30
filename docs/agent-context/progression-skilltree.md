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

### ⚠️ 採取EXP表（`skills/base/*_progression.yml`）はブロック行だけでは効かないことがある

出荷既定の`exp-mode: drop_sum`では、「ブロック名の行」はゲート判定（値>0か）専用であり、**実際に付与されるEXPはドロップしたアイテムの材質名に対する値の合計**。ブロックとドロップ品が同じ場合（大半）は1行で足りるが、`SEA_LANTERN`→`PRISMARINE_CRYSTALS`のように違う材質を落とすブロックは、**ドロップ側の行も足さないとEXP0のまま**（テラコッタが表から丸ごと抜けていて掘っても0だった実例あり）。ブロックを追加するたびに、実際のドロップ品目を確認して行を足す必要がある。シルクタッチ無しで何も落とさないブロック（氷等）はEXP0が正しい挙動。

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

## 関連

- [./combat.md](./combat.md)
- [./forks-and-mobs.md](./forks-and-mobs.md)
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)
- [./bedrock-geyser.md](./bedrock-geyser.md)
