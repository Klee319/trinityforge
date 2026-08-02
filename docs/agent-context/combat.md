# 戦闘・ステータス領域の恒久知識

このコードベースに初めて触るAIエージェント／開発者向けに、戦闘計算・ステータス集約・モブ連携まわりの「今後も踏みうる落とし穴」と「設計上の不変条件」だけをまとめる。作業履歴・日付経緯は扱わない（それは `reports/ACTIVE_RECORD.md` の役割）。

## 全ステ合算アーキテクチャ

### プレイヤーのステータスは単一の合算パイプラインを必ず経由する

`PlayerStatAggregator.aggregate()` が防具4部位＋メインハンド（または発射武器）＋オフハンド（トグル）＋スキルツリーパーク＋役職バフ＋永続バフ＋`base-stats`（config駆動の全員一律加算）＋アドオン（スレッド）を1つの `item` マップへ集約する唯一の生成者。攻撃側は `CombatListener`、防御側は `PlayerDefenseResolver` が両方ともこのマップを `totalOf()` 経由で読む。新しいステ供給源を足すときはこの1マップへ merge するだけで戦闘・採集・クラフト・Ars連携まで一括で効く。

- 攻撃／防御の振り分けは `AttackStatBridge`/`DefenseStatBridge` のキーフィルタが担当。属性写像ステ（max-health/move-speed/attack-speed/attack-reach 等）が同じ集約マップに入っていても、フィルタで拾われない側には流れないので二重適用にはならない。
- `armor-defense-rate` / `armor-strength` は `DefenseStatBridge` が常に0固定にする。これらはバニラ armor 属性のミラー（`SymmetricCombatService.vanillaArmorDefense` 系）経由で別途計上されるため。集約側の供給源が増えてもブリッジがdropするので二重計上にならない。
- 属性系ステ（max-health 等）だけは item マップがバニラ属性へ自動反映されないので、`PerkAttributeApplier.apply()` が `channelOf==ATTRIBUTE` のものだけを Attribute へ merge する。ここが Haste 等の他プラグインと衝突しないよう「ライブ属性値を一切読まない」設計になっている（読むと相殺事故を起こす）。

### 武器CTだけが「メインハンド単体」を読む例外

`weapon-cooldown` は `agg.mainhand()`（メインハンド単体）からのみ読む。armor/offhand を混ぜると Material 単位の CT が誤発火するため。**逆に bleed と AoE は `agg.item()`（全ソース合算）から読むのが意図通り**（防具の攻撃系ステも攻撃に乗ることを許容する設計）。この非対称性はコードのjavadocだけでは気づきにくいので、CT・出血・AoE周りを触るときは毎回どちらの集約を読んでいるか確認すること。

### ⚠️ 出血/固定ダメージなど「確定済み値」を再スケールしてはいけない

bleed の per-tick ダメージや EM（EliteMobs）委譲時の base ダメージのような「既に確定した値」は、攻撃者のコンバットレベルでも `physical.base` 係数でも再スケールしてはならない。防御と回避だけを適用する。`SymmetricCombatService.physicalFinalDamageFlat(victim, flatBase, attack)` がこの専用経路で、`resolver().physicalDefaultDamage`（レベル/係数スケールあり）を通さず `component()` へ直接渡す。`BleedService.applyOneTick` と EM 側 `TrinityForgeCombatListener.applyPhysical` がこれを使う。

## ダメージ計算パイプライン（守備力・会心・固定ダメの位置）

### 守備力は「初回減算」、会心の前に置く

被弾式は `被弾 = (敵攻撃A − 守備力F) × %軽減m` の形で、守備力Fは%軽減より**先**（会心判定の前）に引く。守備力は通常攻撃も会心も等しく削り、防具強度（armor-strength、下記）は会心の上乗せ分だけを削る、という役割分離になっている。

※かつて守備力(flat)を最終段（%軽減後）で減算する実装だったが、これは「%軽減後の小さな値から大きな固定値を引く」形になり、帯適正防具＝被弾が床値1に張り付いて実質無敵／防具を外すと一撃死、という二値状態を生んでいたため位置を修正した。

### 固定ダメージ（fixed-damage）は全防御ステータス貫通の純加算

「flatが削った分だけ還付する」という旧解釈は誤り。fixed-damage は防御を一切考慮しない純加算として扱う。特定武器種の個性付け（例: 槍系）に使う場合は、その分 attack-power を相応にナーフしてバランスを取る。

### 防具強度（armor-strength）は flat 防御ではなく会心軽減率

会心式は `base *= (1 + max(0, critDamage) * (1 - r))`、r = armorStrength を [0,1] にクランプしたもの。**会心の増加分のみ**を軽減し、通常ダメージには干渉しない。会心が回復に反転しない不変条件は `ComponentDamageCalculator` で保証されている。`DefenseStatBridge` が derived stat map から直読する専用キーで、`AttributeProjection` の `armor_strength→armor_toughness` 写像は二重計上防止のため撤去済み（=バニラ toughness からの写像に頼らない）。上限は共通変数 `defense.max-crit-reduction`（既定 1.0 = キャップ無し、ユーザー明示選択）。

※かつて armor-strength は flat 防御として扱われていたが、会心軽減率へ役割変更済み。

### 守備力は物理／魔法で分割済み

flat-defense は type 別（`phys-flat-defense` / `magic-flat-defense`）に分かれている。旧「型非依存の flat-defense」は物理・魔法の両方に同値で効いていたため、移行時は同値複製で挙動を維持している。新しい守備系ステータスを追加するときはどちらの型か明示すること。

### ⚠️ 出血=被ダメージ軽減のみ／貫通=防御率のみダウン

出血（bleed）は被ダメージ軽減以外の手段では軽減できない。貫通は防御率だけを下げる。この2つの意味論を混ぜて実装すると軽減の二重適用や無効化が起きる。

### ⚠️ `bleed-damage` は率ではなく「出血1tickあたりの実ダメージ（flat）」

`combat/damage.yml` の `bleed.ticks`（既定 5）回、`bleed.tick-interval-ticks`（既定 20 = 1秒）
ごとに適用される。つまり**1回の出血発動の総ダメージ = `bleed-damage` × `bleed.ticks`**。

`0.4` のような小数を書くと総 2 ダメージで実質無効、`50` なら総 250 ダメージになる。
`bleed-chance` は [0,1] の率なので**同じブロックに率と flat が並ぶ**点に注意
（`PercentStatNormalize` の対象は `bleed-chance` だけ）。
yml の値をレビューするときは単位を必ず突き合わせること
（軽量武器ツリーには 0.4 系と 50 系の両方が歴史的に混在していた）。

### 負ダメージは物理・魔法どちらも回復扱い

`total < 0` になった場合、物理は `CombatListener` が BASE=0 とし victim を回復させる（`setDamage` はキャンセルしない）。魔法は Ars 連携側の `SpellContext.dealSpellDamage` + `TrinityForgeBridge.healEntity` が対応する。

### AoE・ダミー系

近接主命中（ENTITY_ATTACK）のみが AoE 対象。`applyingAoe` 再入ガードにより、スプラッシュの `target.damage()` は自ハンドラを早期returnしてバニラ防具のみで軽減される（AoEのAoEは発生しない、主命中の再計算による上書きもない）。`aoe.hit-players` は既定 false（モブのみ）。

### PvP抑制は「倍率」だけで実装してはいけない

`combat/PvpDamagePolicy` + `combat/damage.yml` の `pvp:` セクション。根本原因は「攻撃力は指数で伸びるがプレイヤー最大体力はほぼ一定」という構造なので、倍率だけだと攻撃カーブを触るたびに PvP 倍率調整が必要になり、調整漏れ＝即死ゲーへ逆戻りする。`max-damage-percent-of-max-health`（1発で最大体力の何%まで、というスケールフリーな上限）を併用することで、攻撃力が変動しても「最低◯発は耐える」が構造的に保たれる。適用点は `total` 算出後・`setDamage` 直前の1点（出血DoT・AoEは同じ `total` を読むので自動追随）。ただし**AoEスプラッシュだけは別途**同じ抑制を明示的に置く必要がある（`applyingAoe` ガードで主パイプラインを通らないため）。

## モブ（EliteMobs/フィールド）のスケーリング

### 指数成長させるのはプレイヤー火力とモブHPだけ

プレイヤー最大HPは60で頭打ち（バニラ20＋防具4部位で+40）という確定仕様がある以上、モブ攻撃力と守備力は準線形に圧縮しないと式が解けない。`被弾 = (敵攻撃A − 守備力F) × %軽減m` で F/A ≒ 0.8 を全帯で保つのが要点。線形カーブ（`A=8+0.75L`）は帯間のギャップが守備力の伸びより遅く、1帯下の理論値装備が同帯の厳選なし装備より硬いという順位逆転を起こしたため、モブ攻撃・HPは指数カーブ（例: `A(Lv)=7.0×1.03^Lv`）で確定している。防具の防御系ステータスには `random` ロールが一切無い（durabilityのみ品質可変）ことが前提になっている点にも注意。

### ⚠️ モブHPの上限は `spigot.yml` 設定に依存する

`MobTypeSpawnListener.applyMaxHealth` はバニラの max_health 属性上限に当たると**無言で縮む**（例外を捕まえて `min(value, attr.getValue())` に落とすだけ）。このサーバーの `spigot.yml` は `settings.attribute.maxHealth.max` を `Double.MAX_VALUE` 相当に設定して運用する前提で高レベルモブのHP設計をしている。この設定が既定値へ戻されると、全高レベルモブのHPが静かに1024へ崩れる。触るときは検出用WARNINGログの有無も確認すること。

### FIRE_TICK は日光炎上と火属性着火を区別できない

`EntityDamageEvent.DamageCause.FIRE_TICK` は「燃えている間の継続ダメージ」でしかなく、着火原因（日光／火属性エンチャント／溶岩／火打石）を一切保持しない。Bukkit に「日光で燃えた」専用イベントも存在しない。モブHPが巨大なためバニラ固定ダメージでは焼却が機能せず、最大HP割合へ置き換える必要があるが、原因を区別せず全モブへ適用すると野外・昼間の火属性着火だけでボスが毎秒10%溶ける＝火属性エンチャントが最強の攻撃手段になってしまう。

**How**: 近似は「バニラの焼却条件＋EntityType種別ホワイトリスト」で行う（`combat/damage.yml` の `sunlight-burn`: 通常世界／`world.isDayTime()`／`!hasStorm && !isThundering`／立っているブロックの `getLightFromSky() == 15`／`mobs:` に列挙した種別のみ）。置換は上げる方向にのみ働かせる（算出値がバニラ未満ならバニラのまま）。実装は `listeners/SunlightBurnListener`。

### EliteMobs連携: crit は TF側へ一本化

EliteMobs 側の crit chance は装備の `CriticalStrikes` エンチャント由来で生きているため、単純に crit 経路を削除すると elite 相手の crit が完全に消滅する。**How**: EM側の固定倍率（旧 `×1.5`）は削除し、`TrinityForgeCombatListener.onEliteDamagedByPlayer` でプレイヤーmainhandの実 AttackStats を TF 側 (`weaponAttackStats().forItem()`) から解決して渡す。これにより crit だけでなく貫通・bonus_damage も elite 相手に作用するようになる（＝elite被ダメが増える方向の副作用）。

### EliteMobs連携: HPは `setMaxHealth()` 内でオーバーライドする

spawn listener 後付けでHPを上書きすると、EliteMobs が全回復・フェーズ再ヒール時に何度も `setMaxHealth` を呼ぶため戻ってしまう。`EliteEntity.setMaxHealth()`/`setNormalizedMaxHealth()` 内部でプロファイル値へ上書きする方式が必須（`TrinityForgeIntegration.resolveProfileMaxHealth`）。適用範囲は CustomBoss 限定（profileId=custombossファイル名）で、natural elite は対象外。

### EliteMobsダンジョンの `level: dynamic` は静的刻印できない

フォークの刻印処理は import 時に `base + perLevel×level` を数値として焼く仕組みだが、`level: dynamic`（ダンジョン内のほぼ全モブ）はコンバットレベル依存の実行時値であり import 時点では数値化できない。**How**: `MobProfile.dynamic` フラグ＋`ConfigManager.resolveRuntimeProfile(id, runtimeLevel)` で、dynamic なプロファイルは実レベルで都度再構築する。固定ダンジョン（level が数値）は従来どおり焼き値のまま。武器tierの成長は指数的（剣の attack-power が木→インフィニティで約600倍）なので、モブ側の `ConversionPolicy.Ramp` も `growth`/`growth-interval` を持つ指数式に対応している（省略時 growth=1.0 で従来の線形と一致、後方互換）。

### FocusHpDisplay の耐性寄りタグは既存の防御configから導出、攻撃タイプは意図的に出していない

`mob.FocusHpText.leanFrom(physical, magical)` はモブの `physical`/`magical` の
`defenseRate*0.4+resistance*0.4+damageReduction*0.2` 加重スコアを比較し、差が0.05未満なら
`ResistanceLean.NONE`（タグ非表示）にする。この防御プロファイルは `combat/mob-defaults.yml` /
`mob-import.yml` / `mob-profiles.yml` 系の既存キーがモブPDCへ焼いた値をそのまま読むだけで、
新しい設定面は増やしていない。**表示は名前行に追記する形（`[耐:物]`/`[耐:魔]`）で行数を2行のまま
固定**している（`DamagePopupDisplay` の浮遊ダメージ表示や隣接モブの名前ラベルとの重なりを避けるため、
3行目を新設しない設計判断）。

意図的に「モブの攻撃タイプ（技が魔法か物理か）」のタグは出していない: TFの被ダメ判定は
`TrinityForgeAbilityDamage.mark()` が立っている間だけMAGICAL扱いになるが、これは限られた条件でしか
立たない（次項参照）。ほとんどのeliteモブは近接攻撃(PHYSICAL固定)としか判定されないため、
静的な「攻撃タイプ」アイコンは大半のモブで無意味・誤解を招くと判断して見送った。

## マナ回復ステータス

### ⚠️ キー名は直感と逆向き

`hit-mana-recovery` = **被弾時**（`ArmorManaListener#onPlayerDamaged` 相当。「hitを受ける」の意）。`damage-mana-recovery` = **自分が近接で与ダメした時**（`ENTITY_ATTACK`/`ENTITY_SWEEP_ATTACK` 限定。射撃・魔法では発動しない）。同じ向きが `combat/base-stats.yml` 専用の `mana-onhit-*` / `mana-onattack-*` にも適用される。表示名・skilltree の `effect-text`・config-editor の `labels.js` など複数箇所に誤解が伝播しやすいので、この系統を触るときは lore.yml 単体の修正で終わらせず、`effect-text` / labels.js / 各種参照表まで確認すること。

### ⚠️ 同じ回復効果を2つのリスナーで二重加算しない

`hit-mana-recovery` は base-stats を含む全供給源を拾う `mana-onhit-flat` の上位互換であり、両方を別々のリスナーで加算すると二重計上になる（過去に実際に発生した）。マナ系ステータスを触る前は、被弾／与ダメの各イベントに複数のハンドラが載っていないかを先に確認すること。`mana-onhit-percent`/`mana-onattack-percent`（最大マナの%）は flat とは計算式が別なので統合しない。

`mana-bonus` / `mana-regen` は yml の行を消せない仕様になっている（`BaseStatsConfig.missingVocabularyKeys` が「StatVocabulary の全キーが base-stats.yml に存在すること」を強制するテストになっているため）。UI上で隠したい場合は editor 側の除外リストで対応し、config側の行は残す。

## アイテムステータス（item-stats.yml）

- **出荷済み `item-stats.yml` が真源**。generator（自動生成スクリプト）は乖離が発生しうるので、真源とgeneratorのどちらが正しいか都度確認してから編集すること。出荷ymlへ直接サージカルな変換をかけるほうが安全な場合がある。
- 武器には有限の耐久が設定されている（かつては全武器が耐久無限だった）。real weapon（damage-modifier を持つもの）には `fixed.durability`/`per-quality.durability` が設定される。
- 攻撃力の厳選幅（random ロール）と、武器種別ごとのダメージ補正係数（0.50〜0.90帯への線形マッピング。短剣が最低、大斧が最高）は明示的な設計値であり、「短剣が弱い/大斧が強い」ように見えても実装ミスではない。
- ツールには `use-skill` が明示登録されている（pickaxe→MINING / axe→WOODCUTTING / shovel→DIGGING / hoe・shears→FARMING / fishing_rod→FISHING）。`use-level` が無ければ USE ロックは掛からない（level0扱い、lore表示のみ）。

### ⚠️ `UseSkillDefaults` を採取ツール判定に流用してはいけない

`stats.UseSkillDefaults` は装備ゲート用のテーブルで、`_AXE` 系を `HEAVY_WEAPONS` に分類する。これを採取（一括伐採・一括破壊等）のツール判定に流用すると、**素のバニラの斧で一括伐採ができなくなる**（斧＝伐採道具、という採取文脈の直感と矛盾する）。採取用には別の推論表（`gathering/GatheringToolMatcher`: `_PICKAXE`→MINING / `_AXE`→WOODCUTTING / `_SHOVEL`→DIGGING / `_HOE`→FARMING）を使う。判定規則は「use-skill タグがあればタグに従う／タグ無しの素のバニラ道具はマテリアル推論で許可」。

## プレイヤー基礎ステータス（base-stats.yml）

`combat/base-stats.yml` は全プレイヤーへの一律加算をconfigで定義する（空欄=バニラのまま）。`BaseStatsConfig`（`PercentStatNormalize.coerce` で RATE_KEYS のみ %→fraction 変換）が読み、`PlayerStatAggregator.aggregate()` の item マップへ role-buffs/permanent-buffs と同じ流儀で1レイヤ merge される。属性系（max-health等）は `PerkAttributeApplier` が ATTRIBUTE チャネルのみ attrs へ反映する。

- `attack-power` を base-stats に設定すると「武器ベース置換を誘発する」副作用がある（武器のベース値がこの値に置き換わりエンチャント無効化などが起きうる）ため、既定は空欄推奨。設定するなら影響範囲を理解した上で行うこと。
- `armor-defense-rate` は base-stats/permanent 両方の合算を `extraArmorDefenseRate` へまとめ、1回だけ perkDefense へ渡す（item マップ側は常に0固定＝`DefenseStatBridge` を回避する専用経路）。
- 綴りミス・未知キーは `StatVocabulary.isKnown(key)` が false の場合ログ警告を出す仕組みがある（無警告no-opを避けるため）。

## 統合版（Bedrock/Geyser）互換の制約

### 採掘速度は属性で実装してはいけない

`Attribute.MINING_EFFICIENCY` / `Attribute.BLOCK_BREAK_SPEED` は Bedrock 側に存在しない属性で、Geyser の採掘時間計算にも含まれない。実装するとサーバー判定とクライアント表示がずれ「素手速度に戻ってはまた通常に戻る」「ゴーストブロック」が発生する（GeyserMC/Geyser#6266, #3113）。「バニラの機構だから安全」という判断は誤り——バニラ機構であることと Geyser が翻訳できることは別問題。

**How**: 効率強化（efficiency）エンチャントのレベル操作で実装する。Geyser の採掘時間計算には効率強化が含まれるため統合版でも効く。ただし効率強化6以上は別の既知不具合（統合版が連打で異常に速く掘れる）を踏むため上限5を既定にする。実行時にレベルを足す実装はアイテムを実際に書き換えるため、付与量のPDC記録・インベントリを開いた瞬間の剥がし（金床焼き付き防止）・ログイン時の復旧走査が必須になる。クロスボウの装填時間短縮も同じ理由で `QUICK_CHARGE` エンチャントのレベル操作を使う（Attribute による直接実装は不可）。

クラフト時にツールへ効率強化を刻む既存の `tool-enchant-efficiency` ステータスは統合版でも正常動作するので、こちらは温存してよい。

## ネイティブ効果とステータスの線引き

「ネイティブ効果（`NativePerkRewardResolver` 経由の付与）」と「ステータス（`PlayerStatAggregator.totalOf()` が読む合算値）」は別の機構であり、すべてをstat化できるわけではない。

- **stat化不可**: 永続アンロック型（glyph-gate、recipe/ritual gate、村人取引、醸造解放、xp-bottle等の各種「習得」系）は、装備条件化するとレシピブック／取引／呪文書同期が壊れるため対象外。管理キー（`perks_locked_add` 等）や、唯一の発動型である純アクティブ（例: haste-active-mining）も対象外。
- **stat化可能**: 弓系・スタン・パリィ・採集ギミック%・食料・伐採・ドロップ/EXP倍率など大半の効果はstat化可能で、consumer側を `NativePerkRewardResolver` から `PlayerStatAggregator.totalOf()` 参照へ切り替えるだけで対応できる。

新しい効果を追加するときは、まず「これは永続アンロックか、瞬間的な数値効果か」を切り分けること。前者を無理にstat化しない。

## アイテムCT(item-cooldown)は触媒詠唱もゲートする — 「近接専用」ではない

`item-cooldown`(秒)は近接専用と誤解しやすいが、ArsPaperフォークの触媒詠唱も同じキーを読む。
`SpellCaster.attemptCast`（`fork-handoff/arspaper/fork/.../spell/SpellCaster.java:274-284,388-391`）は
詠唱前に `TrinityForgeBridge.itemCooldownSeconds(catalyst) > 0` でCT設定の有無を判定してゲートし、
詠唱成功後に `TrinityForgeBridge.startItemCooldown` → `WeaponAttackStatResolver.itemCooldownSeconds`
→ `Player#setCooldown` の順で開始する。近接側（`CombatListener.startItemCooldown`）とは**別の解決経路**
（`DerivedItemStats.resolve(item,...)` をそのアイテム単体に対して呼ぶだけで、`PlayerStatAggregator` は経由しない）
であり、**`cooldown_reduction`（アイテムCT短縮ステ）は近接側だけが乗算適用し、触媒詠唱側の
`TrinityForgeBridge.startItemCooldown` はこの乗算を一切行わない**（2026-08-02時点）。つまり触媒に
`cooldown_reduction` を盛ってもCTは短縮されない — 「短縮ステでCTが0になる」心配は今は無いが、
将来フォーク側に同じ乗算を足す場合は近接側と同じ下限クランプ
（`CombatListener.startItemCooldown`: `seconds *= Math.max(0.05, 1.0 - Math.min(0.9, reduction))`）を
必ず入れること（クランプが無いと理論上0まで縮む）。

## モブのディメンション別基準レベルは `World.Environment` で引く（ワールド名ではない）

`combat/mob-types.yml` の `dimensions:`（`MobTypesConfig#parseDimensions`）は
`effectiveLevel = (level + dimensions.<ENV>.base-level) + floor(距離 × coordinate-coefficient)`
の形でディメンション別の基準レベル下駄を足す。ネザー/エンドのワールド名は構成依存で一致せず、
EliteMobsのインスタンスワールドは毎回名前が変わる（forks-and-mobs.md 既知の罠と同根）ため、
キーは必ず `World.Environment`（NORMAL/NETHER/THE_END/CUSTOM）で引くこと。未設定
（`dimensions:` セクション自体が無い、または該当Environmentのキーが無い）は必ず下駄0・
coordinate-coefficientもモブ側の値のまま、という「従来どおり無干渉」に完全一致する
（`MobTypesConfig#dimensionBaseLevel`/`#dimensionCoordinateCoefficient` は未設定時にそれぞれ
`0`/`OptionalDouble.empty()` を返す設計）。

**ネザーの距離換算(1/8)は自動補正しない**: `MobTypeSpawnListener#applyScaledProfile` は「そのワールド内の
生のブロック距離」に `coordinate-coefficient` を掛けるだけで、オーバーワールド換算のための8倍などは
一切行わない（据え置き）。ネザーでも通常世界と同じ体感の距離スケーリングにしたい場合は、
`dimensions.NETHER.coordinate-coefficient` を明示的に大きい値（例: オーバーワールド値の8倍程度）へ
上書き設定して運用側で補正すること。

## 関連

- [./progression-skilltree.md](./progression-skilltree.md)
- [./forks-and-mobs.md](./forks-and-mobs.md)
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)
- [./bedrock-geyser.md](./bedrock-geyser.md)
