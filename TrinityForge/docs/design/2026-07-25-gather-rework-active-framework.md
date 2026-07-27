# 採取系フィーチャ見直し（パラメータ化＋UX）＋汎用アクティブスキル基盤 設計書

- 作成: 2026-07-25
- 決定済みスコープ（ユーザー確定）:
  - 採取系見直し = **Lv2（レバーA パラメータ化 ＋ レバーB プレイヤー主体性/UX）**
  - 未実装アクティブ = **汎用アクティブ基盤を先に据える（framework-first）**。個別アクティブは基盤の上に後差し。
- 前提調査: 本セッションの2並列Explore（採取フィーチャ実装マップ／アクティブ棚卸し）。関連: `docs/design/2026-07-23-stat-gate-overhaul.md`、memory `session-2026-07-24-3bugs-6specs-review`。

---

## 0. 診断（なぜやるか）

「editor自由度が低い」「ゲーム内UXが悪い」の両方は、個々の機能の出来ではなく **`feature:` ゲートモデルの構造**が根本原因:

1. **二値フラグ＋グローバル固定値**: 採取系は全て `param: none`。ノードはON/OFFのみで、範囲/CT/上限/確率は `*-gimmick.yml` の全プレイヤー共通値1組。強度がノードに紐づかず、段階解放も不可（同一feature複数配置は重複警告）。割当UI（スキルツリー）と数値UI（ギミック）が別画面。
2. **ランタイム主体性ゼロ**: 全機能で発動フィードバック皆無。`haste-active-mining` は無告知の隠しコンボ（sneak+右クリック）。プレイヤーのON/OFF・強度選択なし。
3. **汎用アクティブ基盤が無い**: TF本体の能動発動は `HasteActiveMiningListener` 1個のハードコードのみ（CTは各自Map、コスト系なし、発動入口はsneak+右クリック1種で飽和）。fork `SpellCaster` が完成した能動エンジン（マナ/多層CT/権限ゲート/actionbar feedback）＝移植の参照実装。

---

## 1. レバーA — 採取フィーチャのパラメータ化

### 方針
`feature:` を「二値フラグ」から「**tier値を持つスケール型**」へ拡張し、強度をノード側に持たせる。既存 `DedicatedEffectsConfig.valueMax(player, effectId)`（複数配置のうち最大値を返す, 既存）を使い、**プレイヤーが解放済みの最高tierを採用**＝段階解放が自然に成立。

### 対象feature と値の意味
| feature | param | ノード値の意味 | 実装の型 |
|---|---|---|---|
| `vein-mining` | LEVEL(tier) | tier→`max-extra-blocks` | 単数値（tier表 or 直値） |
| `small-tree-fell`/`large-tree-fell` | LEVEL(tier) | tier→追加原木上限 | 単数値。※統合案は §1.4 |
| `area-harvest` | LEVEL(tier) | tier→radius | 単数値 |
| `haste-active-mining` | LEVEL(tier) | tier→{amplifier,duration,cooldown} | **複数値**＝tier表必須 |
| `auto-replant` | NONE 維持 | 二値でよい（種差引/ディレイは固定で妥当） | — |
| `spawner-silktouch-harvest` | NONE 維持（別途EntityType保持は将来課題） | 二値 | — |

### 設計詳細
1. **tier表方式に統一**: 各 `*-gimmick.yml` に任意の `tiers:` リストを追加。例:
   ```yaml
   # mining-gimmick.yml
   vein-mining:
     ore-blocks: [...]
     max-extra-blocks: 32        # 後方互換フォールバック（tiers未定義時に全tier共通）
     tiers:                       # 新規: tier→パラメータ（日本語コメント付き）
       1: { max-extra-blocks: 16 }
       3: { max-extra-blocks: 32 }
       5: { max-extra-blocks: 48 }
     haste-active:
       amplifier: 5; duration-ticks: 200; cooldown-ticks: 600   # 後方互換
       tiers:
         1: { amplifier: 3, duration-ticks: 160, cooldown-ticks: 600 }
         3: { amplifier: 4, duration-ticks: 200, cooldown-ticks: 450 }
         5: { amplifier: 5, duration-ticks: 240, cooldown-ticks: 300 }
   ```
2. **ランタイム**: 各listenerで `int tier = (int) valueMax(player, id).orElse(0)`。tier==0 は未解放（従来どおり無効）。`tier>=1` なら `tiers` から `floor` で該当行を引く（定義tierのうち `<=解放tier` の最大行）。`tiers` 未定義なら従来のグローバルscalarを使う（**完全後方互換**）。
3. **重複配置の許可**: scaleフィーチャに限り editor の unique 警告を緩和（`gate-effects.js`）。同一featureをtier違いで複数ノードに置ける（Lv1=tier1, Lv3=tier3…）。runtime は valueMax で最高tierを採る。
4. **editor**: `param==="level"` のノードは既に数値input表示（`tf-skilltree.js:756-758`）。scaleフィーチャを LEVEL 化すればUIは流用可。ラベルを「強度/tier」と明示。`gate-vocabulary.js` FEATURES の param を該当featureで `level` に変更（Java `FeatureEffectRegistry` と同期必須＝両更新）。gimmick.yml の `tiers` 編集UIをギミックタブに追加（`tf-lifestyle-forms.js`）。
5. **Java `FeatureEffectRegistry`**: 該当5featureの param を `NONE→LEVEL` に変更。`FeatureEffectRegistryTest` 更新。**gate-vocabulary.test.js は Java Registry とのパリティを検査していない**（前回レビューの残課題）ので、この機にパリティテストを足すと再発防止。

### §1.4 small/large 統合（任意・推奨）
Agent調査で「両方保有時は large 上限に縮退＝2ノード持つ意味が薄い」と判明。tier化すれば `tree-fell` 1本（tier1=8, tier3=32, tier5=64…）に統合でき、small/large の二重定義を解消できる。ただし既存 `woodcutting.yml` の配置・語彙・後方互換に波及するため、**互換を優先するなら現状の2feature維持のままtier化**でも可。統合は別判断（Open Q1）。

---

## 2. レバーB — プレイヤー主体性 / ゲーム内UX

### B-1. 発動/作動フィードバック層
- 小さな共有ヘルパ `GatherFeedback`（または §3 の共通 FeedbackLayer と統合）を新設。
- 各採取listenerが作動時に actionbar / 任意で音を出す。例:
  - vein-mining/tree-fell: 「一括破壊 x12」等の控えめな actionbar（config `feedback: subtle|off`）。
  - haste-active: 発動成功「採掘強化 発動！ (30s)」＋音、CT中は「クールダウン中 (残り Xs)」を actionbar（現状の**サイレントreturnを解消**）。
- config で verbosity を切替（既定 subtle）。スパム防止に per-player の短いthrottle。

### B-2. プレイヤートグル（/tf settings 拡張）
- `PlayerData`（`hideOthersCosmetics` と同型）に採取トグル prefs を追加: `veinMiningEnabled` / `treeFellEnabled` / `autoReplantEnabled` / `areaHarvestEnabled`（既定 全ON）。
- `SettingsGui` にトグルボタンを追加（`toggleButton` パターン流用, PDCキー方式）。
- 各listener冒頭で `if (!PlayerData.of(player).veinMiningEnabled()) return;` を perk判定の後に追加。
- 用途: 選択採掘したい/苗を残したい/特定木だけ伐りたい場面で個別に切れる。

---

## 3. 汎用アクティブスキル基盤（framework-first）

新パッケージ `com.trinityforge.active`。fork `SpellCaster`/`ManaManager` を**設計参照**（コード直移植ではなく概念移植）。

### コンポーネント
1. **`ActiveSkill`**（interface）:
   ```java
   interface ActiveSkill {
     String id();                       // 例 "haste-active-mining"
     String gateEffectId();             // dedicated-effect 解放ゲート（isActiveで判定）
     ActivationResult activate(Player player, ActiveContext ctx);
     // CT/コストは宣言的メタ（下記）で外部管理
   }
   ```
2. **`ActiveSkillRegistry`**: id→ActiveSkill。起動時登録。`/tf active` の候補列挙にも使用。
3. **`CooldownManager`**（共有）: per-(player, skillId) CT ＋ 任意 GCD。`remainingMillis(player,id)` / `tryConsume(player,id,cooldown)`。`HasteActive` の私製Mapを置換。永続はまずセッション（PDC永続は任意）。
4. **`ActivationDispatcher`**: 発動入口。
   - 一次入口 = **`/tf active <id>`**（Brigadier, `TrinityForge.java:708` のツリーに追加）。解放済みのみ補完候補。
   - 補助 = `/tf active`（GUI）で解放済みアクティブ一覧＋クリック発動＋CT残量表示。
   - 既存の sneak+右クリック（haste）は後方互換で温存可だが、複数アクティブでは飽和するため**正式トリガはコマンド/GUI**に寄せる（swap-hand/drop検知＋割当PDCは将来拡張, Open Q2）。
5. **`FeedbackLayer`**: actionbar 成功/拒否＋音、CT残量、（将来）コスト不足通知。**B-1 と共通化**（採取もアクティブも同じ層）。
6. **コスト層（フック止まり）**: TF本体に資源系は無い（mana は fork内在）。v1は `cost = none` 既定。`ActiveCost` インターフェースだけ用意し、将来スタミナ/マナ/通貨をプラグイン可能に（YAGNIで実体は作らない）。

### 移行（基盤の実証）
- **`HasteActiveMiningListener` を基盤上の最初の `ActiveSkill` として再実装**（ハードコードCT/フィードバック欠落を解消）。§1 の tier 化とも接続（tier→amplifier/duration/cooldown）。
- 解放判定は既存 `DedicatedEffectsConfig.isActive` に委譲（gate は据え置き）。

### 後差しされる未実装アクティブ（基盤完成後・本設計では枠のみ）
`potion-merge`（ポーション統合GUI）/ `dismantle-unlock`（装備解体, param:LEVEL）/ `weapon-coating-unlock`（武器コーティング）/ `wood-repair-unlock`（圧縮木材修繕, 前提=圧縮木材アイテム）。出典 `docs/GREENFIELD-REMAINING-TODO.md`。各々は「gateEffectId＋activate実装＋（必要なら）GUI」を登録するだけになる。

---

## 4. 実装ウェーブ（順序）

- **W1 基盤（C）**: `active` パッケージ（Registry/CooldownManager/Dispatcher/FeedbackLayer/ActiveSkill IF）＋ `/tf active` コマンド＆GUI。HasteActive を基盤へ移設（機能同等＝リグレッションなし）。← アクティブの土台。
- **W2 パラメータ化（A）**: tier表方式を gimmick config に導入、5featureの param を LEVEL 化（Java Registry＋gate-vocabulary.js 同期）、listener を valueMax tier 参照へ、unique 警告緩和、editor 数値input＆tier表UI。FeatureEffectRegistryパリティテスト追加。
- **W3 UX（B）**: FeedbackLayer を採取listenerへ配線、PlayerData トグル＋SettingsGui ボタン、各listener の pref チェック。
- 各Wでビルド＋テスト、W完了ごとにverifier。デプロイはユーザー側（両jar）。

W1→W2→W3 は概ね独立だが、FeedbackLayer は W1 で作り W3 で採取へ再利用するので **W1先行**が効率的。

---

## 5. プレモータム / リスク（枠を疑う）

1. **過剰設計リスク**: 直近で stat-gate-overhaul を実施済み。tier表方式は「単数値featureには過剰」に見えうる → 単数値は `tiers` 省略でグローバルscalar継続（後方互換）とし、tier表は**必要なfeatureだけ**任意採用。全featureへの強制はしない。
2. **Java↔editor語彙ドリフト**: param 変更は Java `FeatureEffectRegistry` と JS `gate-vocabulary.js` の**両方**を更新しないと壊れる（前回の break-vanilla-exp 欠落と同種事故）。→ パリティテストをW2で必須化。
3. **発動入口の飽和**: アクティブ増加で sneak+右クリックが破綻。→ 正式トリガをコマンド/GUIにし、comboは補助扱い（Open Q2）。
4. **トグルの抜け穴**: プレイヤートグルOFFでも他プラグイン/自動化で作動しないか。listener冒頭チェックで一貫。creative/spectator除外は既存パターン踏襲。
5. **段階解放とvalueMaxの意味論**: sum ではなく max を使うことが必須（sum だと複数ノードで青天井）。既存 `valueMax` を使う（sumは使わない）。
6. **small/large統合の互換**: 統合は配置データ移行が要る→既定は非統合tier化、統合は別判断。

---

## 6. Open Questions（2026-07-25 全て解決済み）

- **Q1 → 解決: `tree-fell` 1本へ統合**（ユーザー決定）。small/largeをtier化して統合（tier1=8 / tier3=32 / tier5=64…）。「両方保有=large縮退」の無駄を解消。**W2で `woodcutting.yml` の配置データ移行が必要**（旧2feature配置を新 `tree-fell` へ移行、語彙 `FeatureEffectRegistry`＋`gate-vocabulary.js` からsmall/largeを廃し `tree-fell` を追加、旧IDの後方互換 or マイグレーション処理を用意）。
- **Q2 → 解決: `/tf active <id>` ＋ GUI が正式トリガー**（ユーザー決定）。sneak+右クリック（haste）は補助として温存。swap-hand/drop割当PDCは将来送り（v1では作らない）。
- **Q3 → 解決: haste-active の tier初期値はこちらで妥当値を設定**（バランス後調整前提）。暫定案: tier1={amp3,dur160,cd600} / tier3={amp4,dur200,cd450} / tier5={amp5,dur240,cd300}。
- **Q4 → 解決: コスト層は v1「なし（フックのみ）」**。`ActiveCost` IFのみ用意し実体は作らない。将来スタミナ/通貨導入時に実装。
