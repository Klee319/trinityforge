# アイテム経済・厳選・入手 仕様（ITEM_ECONOMY_SPEC）

- **作成日**: 2026-07-14
- **位置付け**: ユーザー壁打ち（A1〜H1）で引き出した**入手/品質/厳選/コア/ガチャ/スレッド/触媒/解放**の設計意図を正典化し、実装前のクリティカルリスク（プレモータム）を併記する。
- **KGI**: みんなに楽しんでもらう ＋ 私のエゴ（コンセプト）を守る。仮説＝**コンセプトを崩さずに「ゲームバランス × 適度な仕様の複雑さ × 快適な体験」**を提供。
- **関連**: `SELECTION_SPEC`（入手/品質）、`SKILL_TREE_SPEC`（perk解放）、`COMBAT_SYSTEM_SPEC`（gear非依存）、`STAT_DICTIONARY_RECONCILIATION`（stat導出）、`OPEN_DECISIONS`。

---

## 1. 確定した設計（壁打ちA1〜H1・CANDIDATE→正典化）

### 品質・ステ導出（A1/A2/G1）
- **ステ = configの該当ステ定数 × 品質値 + 個体差**。品質値は内部0.1〜1、config品質係数で倍率調整（係数2で振れ幅2倍）。初期値はバランス見て私(Claude)裁量。
- 品質は**10段階**。**クラフト**＝スキルツリー解放の「クラフト品質ポイント」が高いほど高品質。**ドロップ**＝敵/ダンジョンレベルが高いほど高品質。
- **品質ドロップ分布＝正規分布**。最頻値が現品質ポイントで高品質側へ寄る（低レベルでも極低確率で最高品質）。スキルツリー報酬で**左側ばらつき抑制（悪いのが出にくい）／ばらつき拡大（良も悪も出やすい）**を選べる。

### 入手3系統（B1/B2/B3）
- **受動ドロップ**: EliteMobs全般（ダンジョン＋自然湧きelite）に**ドロップテーブル**。ダンジョン湧きとオーバーワールド湧きは分離。品質はA1。**partyダンジョンは人数分ドロップ**。
- **能動クラフト**: 基本は**ValhallaMMO仕様踏襲**（バニラツールのクラフト時ステ付与）。ただしArs触媒はレシピ追加要、Valhalla追加アイテムも留意。クラフト触媒＝厳選要素。
- **特殊トレジャー**: ワールドのルートテーブル（チェスト）から出る**スレッド**。

### コア（C1/C2）
- 用途: **魔法解放素材／強武器(触媒含む)／ステ厳選補助（リロール・アップグレード）**。
- 物量: **工業MOD級の飽和物量**でコンテンツをカバー。ただしクラフトが面倒→**UX工夫必須**。

### ガチャ券（D1/D2）
- **未定**。素材のつもり、config で個数指定。券1〜5は**中身が違う**（等級/内容差）。

### スレッド（E1/E2/E3）
- ** Arsのスレッド機構を Ars防具以外の防具にも踏襲**（防具に挿すUX）。
- **今回サーバの全ステ（会心率等含む）に対応**。種類ごと性能＋個体差ばらつきは config。
- **特殊効果系＝トレジャー**（ワールドチェスト）。

### 触媒（F1/F2）
- 攻撃ステは**剣等と同様**、触媒の**種類ごとに異なる**。**種類・基礎値・個体差の定義は config**。
- **★統一装備レジストリ（F1・アーキ核）**: 武器/触媒/防具の**装備品一式の種類データを1つのconfigで定義**し、EliteMobドロップ／ルートチェスト／クラフトレシピの**ルートテーブルはアイテム名を書くだけで参照**できるようにする。
- 触媒も**品質rollでステが振れる**（TF厳選体系に載せる）。クラフト系触媒＝厳選要素。

### 解放（H1）
- スキルツリーの perk→解放を**機械転写OK**（抜け/例外のみ指定）。レパートリ: **クラフト権／Arsクラフト権／Ars魔法利用権（既存解放とダブルロック）／村人取引権／儀式権**。

---

## 2. ★クリティカル・プレモータム（KGIを壊す失敗点）

> 手法: 固定変数の暴露→プレモータム→枠の疑い（`strategic-decomposition`）。**成功前提を疑い、コンセプト×KGIを崩す構造的失敗を先に洗う。**

### CR-1【最重要・概念自己矛盾】縦強化の二枚舌
- スキルツリー備考は **「質は明示的でない（優位でない）／縦強化にしない／ゴールの量で担保」**。
- 一方この経済は **品質→ステ power（A1）＋リロール/アップグレードcore（C1）＋敵強さで品質上振れ（B1）** ＝**明確な縦強化**。
- **2つの設計哲学が正面衝突**。品質が実ステ power を持つ限り「質は優位でない」は嘘になる。→ **決定必須**: 品質は (a)実 power（縦強化を許容し備考を撤回）か (b)横方向（stat種の組合せ・見た目・利便で、power総量はほぼ一定）か。ここが**コンセプトの生死**。

### CR-2【柱の骨抜き】gear非依存 vs 厳選gearのステ
- `COMBAT_SPEC`: **物理生与ダメはgear非依存**（装備tierで与ダメ倍率を排除）。
- だが厳選gearは **会心/貫通/割合ダメ/防御** を盛る＝実質 gear で戦力が動く。
- → **厳選gearのpower budget（厳選で戦力が何%動くか）を先に決めないと**、gear非依存の柱が骨抜き（コンセプト崩壊）か、厳選が戦力に無意味（最大コンテンツが空虚）の両極に振れる。CR-1と連動。

### CR-3【自己申告リスク】物量飽和 vs 快適UX（KGI直撃）
- C2「工業MOD級の飽和物量＋クラフト面倒」は、**grind＝カジュアル離脱**を構造的に招く。「みんなに楽しんでもらう」と最も鋭く衝突。
- **枠の疑い**: KGIが *broad fun* なら「工業MOD級物量」は**逆レバー**の可能性。コンセプト＝「ゴールの量で担保」だが **量≠楽しさ**。物量を削り「意味ある選択」に振る方がKGI仮説に整合しないか。UX工夫は構造的grindを吸収しきれない。

### CR-4【統合リスク】item-stat権威の四重
- item stat の供給元が **ValhallaMMO native（B2ツールクラフト）／ArsPaper BaseCustomItem／TF rollSeed+quality導出／EliteMobsドロップ** の**4系統**。F1で1レジストリに収束させる必要。
- **未決**: クラフト品のステの真実は **Valhalla（smithing perk）か TF導出か**。両方だと二重ステ/矛盾。LD-9（ダメージ単一所有）の**item版＝ステ単一所有**を決めないと破綻。

### CR-5【RNG多層の掛け算】天文学的希少＋無限grind
- drop正規分布 × per-stat roll × 個体差 × リロールcore × ばらつき調整perk。**掛け算でtop-gearが天文学的に希少**＋**リロールが無限grind sink**。
- → **no-life/whale gap**（廃人が神gear独占）か **万年ゴミロール**（好ロール来ず frustration）。両方 KGI を削る。RNG層は**適度な複雑さ**を超えて balancing 不能に近づく。

### CR-6【ソロ成立と衝突】party人数分ドロップ
- B1「partyは人数分ドロップ」＝**farm効率が人数比例** → grouping強制＋Bedrock altファーム（§4-8）。
- LD-6「人数はゲートにしない・ソロ成立」と衝突。**ソロの厳選速度が構造的に不利**＝ソロ prehab の裏切り。

### CR-7【冗長】スレッド全ステ対応 × 防具roll の二重ステ源
- E2でスレッドが全ステ対応 ＋ TF防具rollも防御ステを出す → **防具が roll と thread の両方からステ**。どっちが厳選軸か不明・power stacking・UX複雑化。**役割分離が必要**（例: rollは基礎ステ、threadは特殊効果のみ / 逆 / 片方に一本化）。

### CR-8【onboarding崖】ダブルロックの新規体験
- H1: 多数の権利（craft/Ars-craft/魔法/取引/儀式）がスキルツリーでゲート＋既存解放とダブルロック。**新規は序盤ロックだらけ** → 「みんなに楽しんでもらう」の入口で離脱リスク。**早期に何が開いているか**の設計が要る。

### CR-9【未定システム】ガチャ券の loop 不在
- D1「未定」。PvE協力・PvP無し・経済圧無しの中で**ガチャの快感loopが何か**が未定義。単なる素材RNGなら**スロットマシン grind**に堕ちる恐れ。bolt-on化リスク。

---

## 3. 解くべき決定（優先順）

1. **CR-1/CR-2**: 品質・厳選の **power budget と縦/横の立場**（コンセプト整合の根幹）。
2. **CR-4**: item-stat の**単一所有**（Valhalla native か TF導出か）＝F1統一レジストリの真実源。
3. **CR-3/枠**: 物量の**目標水準**（工業MOD級を維持するか、意味ある選択へ削るか）とUX前提。
4. **CR-5**: RNG層の**数と天井**（reroll上限・ばらつき範囲・pity有無）。
5. **CR-6**: partyドロップの**ソロ補正**（人数分をどう相殺するか）。
6. **CR-7**: roll と thread の**役割分離**。
7. **CR-8**: 序盤に開放する**最小プレイloop**。
8. **CR-9**: ガチャ券の**loop定義** or 廃止。

---

## 4. 壁打ち解決ログ（2026-07-14 確定）

- **CR-1（縦強化）** → **有界の縦を許容**。「質は優位でない」はコンテンツ設計（スキルツリーの横広げ）の話で、item品質の縦powerとは別レイヤー。品質は主に**床上げ（悪ロールを減らす）＋stat組合せの選択肢**、天井上げは控えめ。gear由来の実効戦力の振れは**上限枠でcap**し、残りは腕・立ち回り・解放・消耗品で担保。
- **CR-2（gear非依存）** → 訂正。**gearの厳選ステは全て戦闘に効く**。「gear非依存」は「バニラ基礎ダメに独自倍率を上乗せしない」狭義のみ。
- **CR-3（物量）** → 工業MOD級の飽和物量は**エンドコンテンツ（ゴール到達には不要な配置）**。最高難易度制覇に物量grindを必須にしない。
- **CR-4（item-stat真実源）** → ✅**確定**。**ValhallaMMOのクラフトの仕組み（作り方・解放・UX）は使うが、付く強さの数値は TrinityForge のステ**。ValhallaMMO自体のステ機能は装備に使わない（オフ）。全装備の戦闘ステは TrinityForge 一元。実装＝Valhallaのアイテム生成をフックして TF がステをstamp／Valhalla native の装備ステを無効化（実機検証項目）。
- **CR-5（多層ランダム）** → 最頻値が品質ポイントで高品質側へスライド＝天文学的希少を回避。層の掛け算は合成後の体感で総調整。
- **CR-6（partyドロップ）** → ソロ=1、party=**1〜人数分のランダム総ドロップ**（1人あたりはソロ以下＝farm動機を消す）。party=楽しさ/難易度、solo=効率farm の住み分け。
- **CR-7（厳選の主軸）** → ✅**装備＋スレッドの厳選が育成の本編（強さの主軸）**。ドロップ数値の当たり外れは協力を促す**おまけ**。スレッドの強さも TrinityForge 一元（CR-4に含む）。
- **CR-8（onboarding）** → ✅**スキルツリーに"解放"表記が無い物＝デフォルトで最初から使える**。
- **CR-9（釣り/掘削の報酬）** → ✅**確定**。券＝**ランダム（ガチャ）**でワクワク感を持たせ、中身は**その道を選ぶ意義のある物**。**安全弁（KGI保護）**: ①外れも必ず有用（素材等・毎回前進）②天井（一定回数で当たり確定）③当たりは"力そのもの"でなく**厳選の素材／固有特別枠／見た目／コレクション**に寄せCR-1の有界を守る（運で戦力差をつけない）④釣り=海系・掘削=考古系 でスキルごとに中身を差別化＝「意義」。

---

## 5. 素材ベースステ機構（ValhallaMMO踏襲・確定 2026-07-15）

> 出典調査: ValhallaMMO `ItemAttributesRegistry`。素材ごとのデフォルト属性表 `vanillaAttributes: Map<Material, Map<String, AttributeWrapper>>` を持ち、`getVanillaStats(Material)` で**素のバニラ装備にも素材ベースをライブ適用**、`applyVanillaStats` でクラフト/生成時にPDC(`DEFAULT_STATS`/`ACTUAL_STATS`)へ焼き込み、戦闘時 `getStats(meta)` で読む。「バニラにもステが付く」の実体＝**素材ティア別ベース表＋焼き込み＋未加工品の素材ライブ・フォールバック**のハイブリッド。（`buff_resistance_reduction: 0.2` はTFのRESISTANCE再導出値と一致で裏取り済。）

### 5.1 TF踏襲の確定アーキテクチャ
- **item stat = ①素材ティア別ベースステ（全装備にライブ適用）＋ ②rollSeed+qualityの厳選ロール（クラフト/ドロップ品に上乗せ）**。
- **①**（= Valhalla `vanillaAttributes` 相当）: **素材ティア×装備種**でベースを定義。**PDCの無い素のバニラ装備でも戦闘時にTFステがライブで乗る**（Q1=全装備にライブ適用）。これで「バニラ製クラフトツールが純バニラ性能」の穴が解消し、「バニラのツール性能はどこで決まるか」の答え＝**TF側のこのベース表**になる。
- **②**: 既存TF厳選（`roll.yml`）。焼き込まず rollSeed+quality でライブ導出（`reload` 全再バランスの利点を維持）。
- **数値はTF一元（CR-4）**: ValhallaMMO自身の item stat はオフにして二重を回避（実機で `vanillaAttributes` 無効化 or PDCストリップ＝実機検証項目）。

### 5.2 確定した設計判断（壁打ち 2026-07-15）
- **Q1 適用範囲** → ✅**全装備にライブ適用**（Valhalla踏襲）。素のバニラ装備にも①ベースを戦闘時ライブ適用、クラフト/ドロップ品は②厳選が上乗せ。
- **Q2 クラフト品質の出所** → ✅**TFがクラファーのスキルLvから算出**（A1「クラフト品質ポイント」をTF側で quality にマップ）。Valhalla内部に依存せず疎結合・CR-4整合。実装＝クラフト成果物に rollSeed + quality(スキルLv由来) を stamp。
- **Q3 ベース表の粒度** → ✅**素材ティア×装備種**。ティアラダー: **木 → 石 → 銅 → 鉄 → 金 → ダイヤ → ネザライト**（銅を石と鉄の間に追加）。金はティア上は独自枠（バニラ準拠で耐久低・速度速）。具体数値は config・M7で調整。

### 5.2b ツールステの配布方式（Bedrock対応・確定 2026-07-15）
- **前提（Geyser/Bedrock制約）**: 「Haste無しで採掘を速くする」を**パケット/block-break-speed属性**でやると Bedrock（統合版, Geyser経由）でデシンク・ゴーストブロック等でバグる。ValhallaMMO config原文も `custom_mining_speeds` は「packet listening/sending が必要」と明記し当サーバでは既定OFF。採掘はBedrockでクライアント権威のため。→ **独自パケット破壊は禁止**。
- **配布方式**: ツールステは**サーバ権威 or クライアント既知エンチャ**で配る。
  - 掘削速度 → **効率(Efficiency)エンチャのレベル**（両プラットフォームのクライアントが破壊時間を計算・パケット不要）
  - 耐久 → 不屈(Unbreaking)、釣り運 → LUCK＋ルートテーブル、幸運/採掘ドロップ増 → サーバ側ドロップ処理
- **掘削速度の扱い（Q・確定=(b)）**: TFは**バニラ効率エンチャに加算**で上乗せ（バニラ効率は腐らず共存）。上り幅は**品質の節目でのみ+1**する**しきい値方式**（既定 `[4,7,10]`＝品質4で+1/7で+2/10で+3、config可変。段数変更に追従）。
- **★冪等な加算（後付けタイミング対策）**: 効率エンチャは単一整数Lvなので「プレイヤー由来Lv」と「TF上乗せ分」を分離する。**TFは自分の上乗せ量をPDCに記録**し、リフレッシュ/金床時に `プレイヤー由来Lv = 現在Lv − 記録したTF上乗せ量` → `新Lv = プレイヤー由来Lv + 新TF上乗せ量` で再設定。これで**金床/エンチャ台で後から効率を足しても二重加算せず・プレイヤー強化を消さず・品質変更にも追従**（冪等）。適用点＝`ItemRefreshListener`＋`PrepareAnvilEvent`。
- **config方針**: 幸運等の数値系＝ロール範囲(品質ごとの上り幅)、効率のような整数エンチャ系＝しきい値リスト、と型を分けてconfig化。

### 5.2c ArsPaper装備の統合（確定 2026-07-15）
> 出典調査: ArsPaper `RecipeManager`(標準Bukkitレシピを`Bukkit.addRecipe`で登録)、`ArmorSetConfig`/`ThreadType`/`ManaKeys`。ArsPaperは**独自クラフトイベントを発火しない**が、レシピが**標準Bukkitレシピ**なので **`CraftItemEvent` が発火する**（＝TFはフック可能）。ARS_SMITHINGスキルは**未存在**。

- **ステの所有分担（確定）**:
  - **Ars固有ステ（マナ最大/回復/被弾・攻撃時マナ回復/詠唱コスト削減/スレッド枠/常時ポーション/飛行・バックパック）＝ArsPaperのconfig(`armors.yml`/`threads.yml`)参照のまま**。TFは触らない。
  - **それ以外の追加ステ（防御/耐性/守備力/被ダメ軽減/回避/crit/貫通等の戦闘系）＝TFが実装＆クラフト時にstamp**。→ **Ars防具の `defense`/`toughness` 固定付与は廃止（またはTFがstrip）して二重を解消**（CR-4整合）。
- **クラフト検出＝`CraftItemEvent`**（Ars独自イベント不要）。結果アイテムのPDC(`arspaper:custom_item_id`)またはレシピ名前空間(`arspaper`)でArs装備を判定し、**Ars固有PDCを壊さずTFの `roll_seed`＋`quality` を併記stamp**。以後TFのライブ導出が戦闘ステを供給。
- **品質＝クラファーのARS鍛冶スキルLv（Q2）**。ただし **ARS_SMITHING は未存在→要登録**（ARS_MAGICと同じくValhallaへカスタムスキル登録）＋**EXP源が必要**（ARS_MAGICのB6と同型の穴）。**craftフックがEXP源を兼ねる**（Ars装備クラフトでARS_SMITHINGにEXP付与→そのLvで品質決定）。
- **要検討/実機**: (a) ARS_SMITHING登録＋EXP源、(b) Ars防具の戦闘ステ供給停止 vs TF strip、(c) シフトクリック連続クラフト時の per-item rollSeed 付与、(d) スレッド(マナ/ユーティリティ)はArsドメインのままなのでTFのcraftフック対象外（CR-7でスレッドに戦闘ステを載せる場合のみ別途フック）。

### 5.2d 生産スキル→品質の一般化（確定 2026-07-15）
- Ars鍛冶の特例をやめ、**「制作イベント → 装備種を司るスキル → TF品質」をconfig化**（`craft-quality: {weapon: SMITHING, armor: SMITHING, tool: MINING, ars-gear: ARS_SMITHING}`）。装備を産む全経路が各々のスキルLvで品質を得る。
- **TF品質は装備（戦闘ステを持つ武器/防具/ツール/触媒）にのみ適用**。消耗品（ポーション/食料/素材）は対象外＝ポーションの強さ等はValhallaMMOのalchemy/料理の領分（住み分け）。
- **釣り報酬の装備（確定）**: 品質は**スキルLv直結でなく「釣りの宝確率に応じたランダム品質」**。宝運(luck of the sea/宝抽選)が高いほど高品質側へ寄るランダム抽選（`PlayerFishEvent`で装備が出た時）。他の制作系の「スキルLv→品質」とは別ルール。
- 検出イベント: 作業台/鍛冶＝`CraftItemEvent`（Ars含む標準Bukkitレシピ）、釣り＝`PlayerFishEvent`。

### 5.2e カテゴリ設計の確定（2026-07-15・実装済）
- **多カテゴリ**: 1アイテムが複数カテゴリを持てる（`statCategories(Material)→Set`）。**斧=weapon+tool の掛け持ち**（戦闘crit と 採掘ステの両方をロール）。素材ベースも各カテゴリ分を合算。
- **config駆動のカテゴリ上書き** `stats/item-categories.yml`（`overrides: <MATERIAL>: [weapon,...]`）: 組み込み分類で判別できない**ValhallaMMO/カスタム武器（スピア等）を宣言でweapon化**、または再分類（斧をweapon限定に戻す等）が可能。上書きは組み込み分類を置換。
- **防具ベース値の調整**: 4部位合算＋バニラarmorの上乗せを考慮し**控えめな暫定値**へ（フルネザ=素材ベース flat-defense 1.5×4=6.0＋phys-resist 0.06、武器flat-bonus-damageも半減）。過剰軽減の回避。

### 5.2f 品質段数・品質名（確定 2026-07-15・実装済＝レビューX3解消）
- **段数はconfig可変（任意段階）**: `ItemData.MAX_QUALITY` はハードコード5をやめ**PDCサニティ上限100**に。実効上限は**`stats/quality-tiers.yml` のtier数-1**（tier未設定時は `quality.yml max-quality` 数値=既定9にフォールバック）。`QualityModel.clampQuality` が導出時に実効上限を強制。
- **品質名tiers**: `stats/quality-tiers.yml` に**名前＋色（MiniMessage、gradient可）の順序リスト**。既定10段（粗製→常品→上製→精良→逸品→名匠→秘宝→神品→幻想→三神）。tier `i` が品質レベル `i` を命名。行の増減で段数が追従。
- **lore品質行**: `ItemAssembler` がlore先頭に `【名匠】` 等の色付き品質行を付与（config駆動・reloadで再スタイル）。
- **give**: 明示品質を実効上限にクランプ。段数変更に追従。

### 5.2g tool専用ステ（確定 2026-07-15・実装済＝バッチ2）
- **エンチャ加算方式(b)で実装**: `stats/tool-enchants.yml` の各ルール＝{enchant, applies-to(category), quality-thresholds}。品質しきい値到達ごとに+1レベルを**プレイヤー由来レベルの上に加算**。
- **冪等PDC管理**: TF寄与分を `PdcKeys.ITEM_TOOL_ENCHANT_BONUS`（`"efficiency=2;unbreaking=1"`）に記録。`ToolEnchantPolicy.idempotentTarget = max(0, current−storedBonus)+newBonus` で**金床/リフレッシュ再適用しても二重加算せず・プレイヤー強化を消さず・品質変更に追従**。
- **Bedrock安全**: 掘削速度=efficiency／耐久=unbreaking／採掘幸運=fortune／釣り運=luck_of_the_sea。クライアントが効果計算＝Java/Bedrock両対応（パケット破壊は不使用）。`canEnchantItem` ゲートで素材不適合を排除（Fortuneが釣り竿に乗らない等）。
- 適用点＝`ItemAssembler`（give＋`ItemRefreshListener`のリフレッシュ）。**残: 金床即時再適用の `PrepareAnvilEvent` フック（要実機）**。
- 実装: `ToolEnchantPolicy`/`ToolEnchantBonuses`/`ToolEnchantSpec`/`ToolEnchantConfig`/`ToolEnchantApplier`＋tool-enchants.yml。テスト（しきい値・冪等・コーデック）通過。

### 5.2h craft/釣り品質stampフック（確定 2026-07-15・実装済＝バッチ3）
- **`CraftItemEvent` フック**（`CraftQualityListener`）: クラフト結果が装備なら **rollSeed+quality を stamp**（`ItemFactory.stamp` で既存アイテムのidentity/他プラグインPDCを保持）。品質modeはクラファーの**該当生産スキルの最高Lv**（多カテゴリの斧は候補skillの最大）→ `craft-quality.yml category-skill` マップ。offsetでランダム draw（CR-5）。
- **`PlayerFishEvent` フック**（`FishingQualityListener`）: 釣り上げた装備は**宝運（Luck of the Sea＋luck属性）でmode**→ランダム品質（釣り=宝確率ランダムの確定ルール）。
- **config** `stats/craft-quality.yml`: category-skill／mode(skill-levels-per-quality/base/spread)／fishing(luck-per-quality/base/spread)。
- 実装: `CraftQualityPolicy`(pure)／`CraftQualityConfig`／`ItemFactory.stamp`／2 listeners。pure政策テスト通過。
- **要実機**: シフトクリック連続クラフト（1イベント複数生成→同seed/quality共有の既知制限）、`Item.setItemStack`での置換、ARS_SMITHING実在（バッチ4）。

### 5.2i Ars統合 バッチ4（TF側実装済／ArsPaper側・実機は残）
- **ARS_SMITHING スキル登録（実装済）**: `ArsSmithingProfile`/`ArsSmithingSkill` を ARS_MAGIC と同型で追加、`ArsBridge.registerAll`（ARS_MAGIC＋ARS_SMITHING）で登録。guard付き（ValhallaMMO不在で no-op）。
- **ARS_SMITHING の EXP源（実装済＝B6同型の解消）**: `ArsBridge.grantSmithingExp`（`SkillRegistry.getSkill("ARS_SMITHING").addEXP(player, amount, false, SKILL_ACTION)`、guard付き）。`CraftQualityListener` が **候補skillにARS_SMITHINGを含むクラフト時**にEXP付与。量は `craft-quality.yml ars-smithing.exp-per-craft`。
- **Ars装備の品質**: Ars素材を `item-categories.yml` で `ars-gear` と宣言→category-skill で ARS_SMITHING に紐付け→クラフト時に品質stamp＆EXP付与。
- **(a) Ars防具の戦闘ステ供給停止（実装済）**: `ConfigurableArmor` の defense→ARMOR / toughness→ARMOR_TOUGHNESS 付与を **TrinityForge存在時のみ停止**（TF不在のArsPaper単体では従来通り＝スタンドアロン維持、guardパターン）。マナ系/スレッド/耐久/見た目はArsドメインで常時維持。`MageArmor`は属性付与なしで対象外。
- **②A2 魔法cause二重処理（実装済＝CRITICAL解消）**: ArsPaperの呪文ダメージを共通ヘルパー `SpellDamage.applyMagic`（`DamageSource(DamageType.MAGIC)`）で適用。cause=MAGICになりTFの近接/射撃listener(MELEE_CAUSES)が無視→**二重軽減・物理誤分類が解消**。全offense effect 11箇所を切替（CrushWave/ColdSnap/Flare/Harm/HeavyImpact×2/Lightning/Scorch/SonicBoom/Windshear/Heal）。IgniteEffectは既にFire causeで対象外。ArsPaperビルド緑。
- **残（ArsPaper編集不可＝ValhallaMMO側/実機）**: (b) ValhallaMMO native item stat 無効化(CR-4)＝**ArsPaperコードでなくValhallaMMO側の設定**（skills/*.ymlのperk報酬から装備ステ除外 or vanillaAttributes空化。実運用ではAr/バニラ独自レシピ製にはそもそも乗らないため要実機確認）、(c) `skills/ars_smithing*.yml` をValhallaデータフォルダへ配置、(d) 登録タイミング/EXP付与/魔法cause/craft stampの実機検証、(e) ②B6 ARS_MAGIC EXP源（別途）。
- **config全体**: 本セッションで追加した stats/ 配下configのコメントは日本語化済。

### 5.3 実装インパクト（未実装・次バッチ）
1. 新config `stats/material-base.yml`（ティア×装備種→ステ base）。
2. `MaterialTier` 解決（Material→木/石/銅/鉄/金/ダイヤ/ネザライト）＋装備種は既存 `EquipmentSlotResolver`（要 `tool` カテゴリ追加）。
3. stat導出を **finalStat = materialBase(material, key) + rolledBonus(seed, quality)** へ拡張（現 `roll.yml` の per-stat `base` を素材依存へ格上げ）。
4. **PDC無し装備にもライブ適用**: `CombatListener.weaponDerivedStats` / `PlayerDefenseResolver.sumArmorDerivedStats` が rollSeed 無しでも①ベースを返すよう変更。
5. クラフトフック（`CraftItemEvent` 等）で rollSeed + quality(クラファーのスキルLv由来) を stamp（②を有効化）。
6. CR-4実機: ValhallaMMO native item stat の無効化。
</content>
