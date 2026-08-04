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

### `distance-damage-bonus`（弓術の距離ダメージ）は「発射地点↔着弾地点」で測る — 射手の現在地を使ってはいけない（2026-08-04）

`CombatListener` の距離ダメージは、矢/トライデント等 projectile の**発射地点**を
`ProjectileWeapon.storeLaunchLocation`（`CombatListener#onProjectileLaunch` が全 projectile 種別で
発火する `ProjectileLaunchEvent` 内で記録）から読み、着弾地点(victim の位置)との距離を
`CombatListener#launchDistanceBlocks`（純関数、ワールド不一致/null は0にフォールバック）で測る。

※かつては `attacker.getLocation().distance(victim.getLocation())`（着弾時点の射手の現在地）を使っていたが、
これは「矢を壁/トラップドア等に刺して停止させ、射手だけ遠方へ移動してから第三者に矢を再度落下・命中させる」
と、矢自体は1ブロックも飛んでいないのに"距離が離れた"ことになりダメージが跳ね上がる悪用を許していた
（実サーバ報告で確認、`CombatListenerDistanceDamageExploitTest` に固定）。

- **発射地点の記録は `ProjectileLaunchEvent` 一箇所に統一**（矢は `EntityShootBowEvent` → 同じ矢に対する
  `ProjectileLaunchEvent` の順で両方発火する vanilla の生成順を利用しており、専用リスナーを重ねる必要はない）。
  記録するのは `projectile.getLocation()`（この時点で実際にスポーン済みの発射座標そのもの）であり、
  射手の座標は一切参照しない。
- **記録が無い矢（プラグイン生成/ディスペンサー発射/サーバ再起動を跨いだ矢など）は距離ボーナス0
  （ボーナス無し）にフォールバックする。旧挙動（射手の現在地）へ戻すと同じ悪用が再開する**ので、
  この経路を触るときは絶対にフォールバック先を変えないこと。
- 記録は `PdcKeys.PROJECTILE_LAUNCH_LOCATION`（`"<worldUUID>|<x>|<y>|<z>"` 形式のSTRING、projectile
  自身のPDC）。異なるワールド間は `Location#distance` が例外を投げるため `launchDistanceBlocks` が
  明示的に0へ落とす（`launch.getWorld().equals(impact.getWorld())` を先に見る）。
- 回帰テストを書くときの罠: `EntityShootBowEvent`/`ProjectileLaunchEvent` を手動構築するテストでは
  **`arrow.setShooter(shooter)` を `onProjectileLaunch` 呼び出しより前に**やらないと、記録ガード
  （`firedProjectile.getShooter() instanceof Player`）が偽になり発射地点が一切記録されない ──
  この場合ボーナスは常に0になり、「射手の移動が結果に影響しない」ことを検証するテストが**偽陽性で
  緑になる**（実際に2026-08-04にこの順序ミスで一度この状態になった）。テストを書いたら必ず
  修正前のコードに戻して赤くなることを確認すること。

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

### FocusHpDisplay の耐性寄りタグは既存の防御configから導出、攻撃タイプタグは `attack.magic-ratio` から導出（2026-08-02〜、2行のまま両立）

`mob.FocusHpText.leanFrom(physical, magical)` はモブの `physical`/`magical` の
`defenseRate*0.4+resistance*0.4+damageReduction*0.2` 加重スコアを比較し、差が0.05未満なら
`ResistanceLean.NONE`（タグ非表示）にする。この防御プロファイルは `combat/mob-defaults.yml` /
`mob-import.yml` / `mob-profiles.yml` 系の既存キーがモブPDCへ焼いた値をそのまま読むだけで、
新しい設定面は増やしていない。**表示は名前行に追記する形（`[耐:物]`/`[耐:魔]`）で行数を2行のまま
固定**している（`DamagePopupDisplay` の浮遊ダメージ表示や隣接モブの名前ラベルとの重なりを避けるため、
3行目を新設しない設計判断）。

※2026-08-02までは「モブの攻撃タイプ（技が魔法か物理か）」のタグは意図的に出していなかった
（当時 TF の被ダメ判定は `TrinityForgeAbilityDamage.mark()` が立っている間だけ MAGICAL 扱いになる
限定的な経路しか無く、ほとんどの elite モブは近接攻撃(PHYSICAL固定)としか判定されなかったため）。
`attack.magic-ratio`(次項「モブの通常攻撃を魔法として解決するには…」参照、`forks-and-mobs.md`)の
新設により、モブの通常攻撃自体が魔法/ハイブリッドとして解決できるようになったので、
`FocusHpText.AttackLean`(NONE/HYBRID/MAGICAL、`attackLeanFrom(magicRatio)` で導出)として
`[攻:魔]`/`[攻:混]` タグを追加した。`ResistanceLean`(防御軸)とは別の enum・別の色系統（紫）で、
同じ名前行に両方並んでも混ざらない設計（`FocusHpText.format` の6引数オーバーロード）。
`magic-ratio<=0`（既定・大多数のモブ）は従来どおりタグなし。

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

### `random-roll-pools`（重み付き複数候補プールからの厳選抽選。2026-08-02 新設）

`item-stats.yml` の既存 `random:`(`StatRange`)層は「列挙した全ステを毎回ロールする」だけで、
**候補群から一部だけを重み抽選する・主ステとサブステで別プールを持つ・レア度で倍率を変える**
という「厳選」の要件を満たせない（`DerivedItemStats#applyRandom` は列挙した stat を無条件に全部ロールする）。
これを満たすため `com.trinityforge.stats.RandomRollPool`（`ItemStatsConfig#randomRollPoolFor`）を
新設し、`item-stats.yml` トップレベルの `random-roll-pools:` セクションに置いた。個々のアイテムは
`random-roll-pool: <poolId>` で紐付ける（`fixed`/`per-quality`/`random` とは独立な第4の層）。

- **元は ArsPaper 独自の `thread-rolls.yml`**（スレッド専用の厳選テーブル）だった。2026-08-02 に
  「エディタから設定できる／TF 側へ一本化」の依頼で `item-stats.yml` 側へ全面移設し、
  `ThreadRollConfig`/`thread-rolls.yml` はフォークから削除した。ArsPaper 側は
  `TrinityForgeBridge.rollThreadStats(material, cmd, quality)` → `RandomRollPool#roll` を叩き、
  戻り値の `RolledStats#encode()` は旧 `ThreadRoll` の PDC 文字列フォーマットとバイト互換
  （`<rarityId>|<mainKey>=<value>|<subKey>=<value>;...`）なので、**旧個体の PDC はそのまま
  `ThreadRoll#decode` で読める**（fail-open: プール未解決/例外時は `rollThreadStats` が null を返し、
  呼び出し側は「厳選なし」個体として扱う）。
- **`armor-defense-rate` を候補に入れてはいけない**。物理ダメージの守備力減算率そのものなので、
  ロール上限次第で物理ダメージを実質無効化できてしまう（`item-stats.yml` の
  `random-roll-pools` ヘッダコメントに同じ警告あり。プールへ足す前にこのコメントを消さないこと）。
- **decimal-step 量子化（roll した値を authored な min/max の小数桁に丸める）は 2026-08-02 の新規要件**。
  旧 `ThreadRollConfig#round`（2〜4桁固定丸め）や既存の `random:` 層（丸めなし・完全連続値）は
  どちらも「整数専用」ではなかった ── 「昔は整数刻みだったはず」という思い込みで調べずに実装すると、
  存在しない過去の挙動に合わせて壊す。`RandomRollPool.decimalsOf(StatDef)` は authored な min/max
  それぞれの小数桁数の**大きい方**(`BigDecimal.stripTrailingZeros().scale()`)を採用する
  （`min: 0.5, max: 2.0` → 0.1刻み／`min: 1, max: 10` → 整数刻み）。quality-spread で広がった
  実効レンジではなく、**authored な値から刻みを決める**点に注意（quality で刻みそのものは変わらない）。
- **quality-spread は quality (0-100) を線形補間して min/max の非対称な幅を広げる**
  （`low-shrink-at-max-quality` は下限をわずかに締め、`high-expand-at-max-quality` は上限を大きく
  伸ばす。「厳選の沼」演出。品質0では常に authored のまま）。
- config-editor 側は `tools/config-editor/public/js/p5-forms.js` の
  `window.buildRandomRollPoolsForm`（複数プール対応。旧 `buildThreadRollsForm` を汎用化した後継）が
  UI を持つ。`split-views.js` の `item-stats` 分岐で `itemCategory === "thread"` のときだけ
  `buildItemStatsForm` の上に合成表示する（同じ `data` 参照を直接編集するので、保存は
  `buildItemStatsForm#getData` の `{ ...working, items }` スプレッドに乗っかるだけで済み、
  追加の `extraGets`/マージ処理は不要）。**この合成パターンを他プールへ複製するときは
  `itemCategory` 名で分岐している箇所を必ず追う**（`__stats_thread__` 以外のタブに漏れ出さない
  ようにするゲートがここにある）。
- **item-stats.yml の `_editor.itemTabs`/`_editor.categories.<tab>`/`_editor.orders.<tab>` は
  「タブに出すための明示ピン」であり、`window.inferItemCategory` の材質名フォールバックは
  weapon/armor/tool/other の4種類しか返さない**（`catalyst`/`spellbook`/`thread` は
  フォールバック対象外）。新しいアイテムを `catalyst`/`spellbook`/`thread` タブに出したいなら、
  `_editor.itemTabs.<MATERIAL#CMD>: <tab>` のピンを明示的に足さないと、材質名フォールバックで
  黙って `other`（補助タブ）に落ちる。40件のスレッドアイテムを `item-stats.yml` に追加した際、
  このピンを付け忘れていたため `__stats_thread__` タブが「空に見える」不具合になっていた
  （実際は `items:` には存在するが、表示タブの解決が別レイヤーだったのが原因）。

## ステータス表示の桁数（lore / チャット / GUI 共通キャップ）

`LoreValueFormat#render`（item lore）と `StatValueRenderer#render`（`/tf stats` チャット・
`/tf status` GUI）は、**`decimals` に何が渡ってきても** `LoreValueFormat#cappedDecimals` で
フォーマット種別ごとの上限へ丸める（PERCENT=1桁、FLAT/SCALAR=2桁、INTEGER=無制限＝丸め自体が整数）。
`cappedDecimals` はパッケージ private で公開し、両レンダラーが同じ1メソッドを呼ぶ設計にしてある
（2箇所で別々に `Math.min` すると桁数規約がドリフトし、lore とチャット/GUIで表示桁が食い違う
既知の事故形になる）。**丸めるのは表示だけ**で、内部値（集計・キャップ判定に使う生値）は一切変えない。

※2026-08-02 の実サーバ報告「物理耐性が `5.1935` のように出る（`5.2%` であるべき）」への対応。
現行 `stats/lore.yml` の decimals 設定は全て PERCENT=0〜1・FLAT=0〜1 で運用されており、この
上限を追加しても既存の表示値は変わらない。**この上限追加はあくまで防御的措置**であり、
`5.1935%` を実際に出していたコード経路そのものは現行ソースからは特定できなかった
（`RandomRollPool`/`ItemFactory`/`LoreComposer`/ArsPaper 側 `ThreadRoll.format()` を確認したが、
いずれも `decimals<=1` かフォーマット済み文字列を返す実装だった）。古い(既に生成済みの)アイテムの
lore がキャッシュされたまま残っている可能性があるため、再現した場合は該当アイテムを
`/tf reload` 後に作り直して(再ロール/再生成して)再現するか確認すること。

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

`item-cooldown`(秒)は近接専用と誤解しやすいが、ArsPaperフォークの詠唱も同じキーを読む。
※かつて「`SpellCaster.attemptCast` が触媒(`catalystData!=null`)経由の詠唱だけをゲートし、
`cooldown_reduction`は触媒詠唱側では一切乗算されない」と書いていたが、両方とも2026-08-02の
改修で古い説になった（以下が現状）。実際のメソッド名は `SpellCaster#cast`
（`attemptCast`というメソッドは存在しない）。

- **ゲート/開始の権威は3経路あり、優先順位は 触媒 > 魔導書 > item-cooldown汎用**（`SpellCaster.java`
  の `catalystOwnsCt`/`bookOwnsCt` 変数で排他制御、同一詠唱で重ねがけしない）:
  ①触媒(`spellbooks.yml` の `catalysts:` に `cooldown:` 秒指定、または触媒自身が
  `item-cooldown`ステを持つ)、②魔導書(`spell-books:` の `cooldown:` 秒指定。現状全ティア0なので
  実質未使用)、③**item-cooldown汎用パス**(①②のどちらも自分のCTを持たない詠唱で、実際に
  右クリックしたアイテム——`castItem`優先、無ければ`catalyst`——の`item-cooldown`ステを見る)。
- **③が無いと何が起きるか**: TFカタログの杖10本(`BLAZE_ROD#400002〜400008`/`400012〜400014`)は
  `catalysts:` に未登録で、かつ`SpellBindListener`経由のバインド詠唱では`catalyst`引数が
  「バインド先の魔導書」に化ける(D6)ため、③が無ければ`item-stats.yml`の`item-cooldown`
  (3.0〜1.8秒)が一本も読まれず無視される（2026-08-02実装。詳細は
  `docs/agent-context/forks-and-mobs.md` の「`SpellCaster.cast` の `catalyst` 引数は…」節）。
- **`cooldown_reduction`は2026-08-02以降、`TrinityForgeBridge.startItemCooldown`(触媒経路・③汎用
  パス共通の唯一の開始関数)でも近接側と同じ規則で乗算適用される**: `tfStatTotal(player,
  "cooldown_reduction")`(装備+skilltree perk全ソース合算、`CombatListener`の
  `aggregator.aggregate(attacker).totalOf(...)`と同一値)を読み、
  `seconds *= Math.max(0.05, 1.0 - Math.min(0.9, reduction))`という近接側
  (`CombatListener.startItemCooldown`)と全く同じ下限クランプを踏襲する。触媒/杖に
  `cooldown_reduction`を盛るとCTが短縮される（「短縮されない」という以前の記述は誤りで上書き済み）。

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

### ⚠️ `dimensions.<ENV>.base-level` は「EliteMobs 所有モブへの早期 return」より前に足さないと丸ごと無効化される

`MobTypeSpawnListener#onSpawn` は `isEliteMobsOwned(data)` が真のモブ（`MOB_PROFILE_ID`/
`MOB_DUNGEON_THEME` を持つ、取り込んだ EM モブのほぼ全部）に対して `applyScaledProfile` を
一切呼ばずに `return` する（EM が刻んだ防御/攻撃/HP を守るための意図的な設計、上の
「EliteMobs連携: HPは `setMaxHealth()` 内でオーバーライドする」参照）。`dimensions.<ENV>.base-level`
の加算は `applyScaledProfile` の中でしか行われないため、**この早期 return より後ろに置いた実装は
EM 由来モブに一切効かない**（2026-08-02 に実際にこの順序で踏んだ：`dimensions:` を新設したのに
「ネザーのダンジョンモブだけレベルが上がらない」という報告になった）。

**How**: EM 所有モブ向けには専用の最小加算パス（`MobTypeSpawnListener#applyDimensionLevelBonus`）を
早期 return の直前に挟む。EM が既に `MOB_LEVEL` へ書いた値を読み、下駄を足した値を
`MobData.adjustLevel(holder, level)`（**`MOB_LEVEL` 1キーだけを上書きする**、`MobData#stamp`/
`#stampMobType` とは別の専用API）で書き戻す。`MobData#stamp` 系は防御9キーを毎回ゼロから
全部書き直すため、レベルだけ変えたいときに誤って使うと EM の防御値が消える。EliteMobs モブには
`coordinate-coefficient` の上書きも適用しない（EM は座標距離ではなく自前のダンジョンレベル/
`SpawnRadiusDifficultyIncrementer` で難度を決める別系統のため、混ぜる意味がない）。

同種の罠は `applyUntaggedDefaults` の `hasLeveling` ガード（`baseLevel > 0 ||
coordinateCoefficient != 0.0` のみを見ていた）にもあった。`dimensions:` の下駄だけを設定して
`defaults.level`/`defaults.coordinate-coefficient` を両方0のまま運用しようとすると、
このガードに巻き込まれて `applyScaledProfile` 自体が呼ばれず（PDC刻印すら発生しない）無効化される。
「下駄を計算に使う関数」を1箇所直しても、「そもそも下駄ありレベリングとして扱うか」を判定する
ゲート条件が別に存在する場合は両方直すこと。

## 45+難易度修正（2026-08-03）で確定した恒久知識

### ダンジョンモブ(mob-import.yml)の攻撃合成は `mob-types.yml` とは別ymlの別ランプ — 片方に足したステが自動でもう片方に反映されるわけではない

`unknown-mobs.synthesize: true` により、EliteMobsダンジョンモブは事実上**全て** `combat/mob-import.yml`
の `ConversionPolicy.AttackRamp`（`RampParser#attackRamp`）から攻撃/防御/HPを合成される
（出荷状態の `combat/mob-profiles.yml` は `profiles: {}` で空——焼き値のインポート結果ではなく、
このランプが唯一の実効ソース）。フィールドモブの `combat/mob-types.yml`（`MobLevelCoefficients`）
とは**別の Java 型・別の yml で独立に authored** されている。2026-08-03 以前、`AttackRamp` には
`magic-ratio` フィールド自体が存在せず（8フィールド構成）、**ダンジョンモブの通常攻撃は100%物理固定**
だった（フィールドモブは `mob-types.yml` 側で約41%が非ゼロ magic-ratio を持ち「4割魔法」設計に一致——
`ShippedMobMagicRatioTest` で保証済み——なので、この非対称にこれまで誰も気づいていなかった）。

**How**: `AttackRamp` を9フィールド化し `magic-ratio` を追加（8引数コンストラクタは後方互換で
`magicRatio=ZERO`）。`mob-import.yml` の `attack:` に `magic-ratio: {base: 0.35, per-level: 0.0}`
を追加し、全ダンジョンモブを一律35%ハイブリッド化した（モブ種別ごとの差は未実装、時間制約による
意図的な単純化）。**新しいモブ攻撃ステータスを足すときは、`mob-types.yml`(フィールド) と
`mob-import.yml`(ダンジョン) の両方に authored 値が要ることを都度確認すること**——
Java側の型を1回拡張しただけでは、もう片方の yml には何も入らない。

### `MobData.adjustLevel` は `MOB_LEVEL` だけを書く — HP属性(Bukkit Attribute)は追随しない

`MobData.adjustLevel(holder, level)` はPDCの `MOB_LEVEL` 1キーだけを上書きする専用API（`MobData#stamp`
系＝防御9キー全部を焼き直す処理とは別物）。`SymmetricCombatService` は攻撃力を **その都度 `MOB_LEVEL`
を読んで動的に**スケールするため `adjustLevel` 後すぐ反映されるが、最大HPは spawn 時（または
EliteMobs所有モブなら `EliteEntity.setMaxHealth()` 内オーバーライド時）に**一度だけ** Bukkit
Attribute へ焼かれた値のままで、`adjustLevel` を呼んでも一切再計算されない。

**How**: `MOB_LEVEL` を後から書き換える経路（例: `MobTypeSpawnListener#applyDimensionLevelBonus`）は、
`ConfigManager.resolveRuntimeProfile` などで同じプロファイルを新しいレベルで再解決し、
`MobStatScaling.scaleMaxHealth` を明示的に呼び直してHPも書き戻すこと
（`MobTypeSpawnListener#reapplyDimensionBonusMaxHealth`、2026-08-03追加）。
※出荷 `mob-types.yml` の `dimensions:` セクションは全モブで空（未設定）のため、この不具合自体は
2026-08-03時点で**本番影響ゼロの潜在バグ**だった（`dimensions.<ENV>.base-level` を実際に設定する
運用に入って初めて症状が出る）。「HPも上がっていたか」という棚卸し指摘への回答は「上がっていなかった
（コード上は追随しない）が、出荷config側でこの経路自体が未使用だったので現状の45+体感難易度とは無関係」。

### `ConversionPolicy.Ramp`(および`AttackRamp`)の「高レベル区間」は加算専用・`RampParser`が唯一のパース窓口

`Ramp` は `effective = (base + per*level) * growth^(level/interval)` に加え、
`level >= highLevelFrom` のとき `+ highLevelPerLevel * (level - highLevelFrom)` を**加算するだけ**の
第2区間を持てる（2026-08-03追加、既定は `highLevelFrom=+∞` で完全no-op）。**乗算による第2指数区間に
しなかったのは意図的**——base側カーブが0（例: mob-import.ymlの各種flat-defenseは低レベル帯でほぼ0）の
場所に乗算区間を足すと「0×何倍=0」で無力化するため。連続性も設計上保証されている
（`level==highLevelFrom` ちょうどでは加算項が0なので、効果は閾値の**次のレベルから**しか見えない
——「Lv45ちょうどでは変化なし、Lv46から効く」という体感は仕様どおり）。

`RampParser#ramp`/`#attackRamp` が全ての ramp 形状yml（`mob-import.yml` の max-health・
physical/magical の各 flat-defense・attack 全般）を読む唯一の窓口なので、`high-level-from`/
`high-level-per-level` を1回パーサへ足すだけで対象の全フィールドに波及する。**ただしフィールドモブ
(`mob-types.yml`) 側の `MobLevelCoefficients.DefenseCoeffs`(physical/magical の
defense-rate/resistance/damage-reduction/flat-defense) は今も purely-linear のまま
（`base + coeff*level`、growthも高レベル区間も無い）**——2026-08-03改修で高レベル区間を得たのは
field-mobの max-health(`MobLevelCoefficients.maxHealthHighLevelFrom/PerLevel`) だけで、
DefenseCoeffs 側への同種拡張は意図的に未着手（今後の難易度パスへの申し送り）。

### 難易度チューニングで「厳選/耐性投資を意味あるものにする」には flat-defense を使う——HP倍率や%軽減では差が出ない

HPを一律倍率で増やしても、最適化済み攻撃者と未最適化攻撃者のTTK比は変わらない（両者に同じ倍率が掛かり
比で相殺される）。defense-rate/resistance のような%軽減も同様に両者へ均等に効くため差が出ない。
一方 flat-defense は `ComponentDamageCalculator` のパイプライン上「会心判定より前の初回減算」
（本ファイル冒頭「守備力は初回減算」参照）であり、固定値の減算は**小さい方(未対策)のダメージ総量から
比例して大きな割合を削る**ため、対策の有無で被ダメ軽減率に実差が生まれる。実測（2026-08-03、
`combat/mob-import.yml` の物理/魔法flat-defenseへ Lv45+ `+150/level` を追加）: Lv60で未対策36.5%
カット・対策済み19.4%カット、Lv80で未対策22.7%・対策済み12.1%。今後「投資が効く」難易度調整をする際は
HP/%軽減ではなくflat-defense（またはこれに類する会心前の固定減算）を第一候補にすること。

### config-editor: scaling block へキーを1個足したら `pruneEmptyScalingBlock` 系の空判定も同じ変更で更新する

`tools/config-editor/public/js/mob-forms.js` の `pruneEmptyScalingBlock(host, key)` は
`level-coefficients` などのブロックを保存時に「全フィールドが空なら丸ごと削除」する。この空判定
（`topEmpty` 等）は**スキーマから自動導出されておらず、フィールド名を手で列挙したチェック**なので、
Java側（`RampParser`/`MobTypesConfig#parseLevelCoefficients` 等）に新しい任意キーを追加しても、
ここへ追記し忘れると「新フィールドだけ入力して保存」→「空扱いされてブロックごと消える」という
**サイレントなデータロス**になる（フィールドがエディタに出ないだけの不具合より発見しづらい——
一見保存できているように見える）。新しいscaling系キーを追加するときは、既存の
「config-editorミラー2本（`lib/`と`public/js/`）を両方更新」に加えて、**この空判定も対象キーに
含める**こと。

## ダンジョンのコンセプト（物理/魔法）で確定した恒久知識（2026-08-03）

### ⚠️ 高レベル帯では `magic-ratio` は「割合」ではなく事実上「素通りダメージの絶対量」になる

`magic-ratio` は「このモブの通常攻撃のうち魔法として解決する割合」だが、**分割された物理成分と
魔法成分はそれぞれ別の `flat-defense` で減算される**（本ファイル「守備力は初回減算」「守備力は
物理／魔法で分割済み」参照）。そして `stats/item-stats.yml` を全数確認すると、
**ネザライト/ダイヤ系防具は全変種・全部位が `phys-resistance` / `phys-flat-defense` のみで
`magic-flat-defense` を1つも持たない**（魔法防御を持つのは守護/魔織シリーズだけ）。

結果、Lv60（ネザライト一式で phys-flat-defense ≈ 49、最大HP ≈ 50、モブ攻撃力 ≈ 66）では:

- 物理成分は守備力にほぼ丸ごと吸われて下限へ落ちる（`0.75 × 66 = 49.5 − 48.8 ≈ 0`）
- 魔法成分は無対策なら**ほぼ無減算で通る**（`ratio × 66` がそのまま被ダメージ）

つまり `magic-ratio` を 0.1 上げることは「被ダメージを約6.6上げる」＝**最大HPの13%を毎発奪う**
のと同義で、率の直感（10%増）とは桁が違う。`0.60` にすると魔法成分だけで約40 = 実質2発以下になり、
**「対策すれば無傷・しなければ即死」の両極端**になる。

**判断基準**: `magic-ratio` を触るときは率で考えず、必ず
「**無対策のプレイヤーが何発耐えるか** = 最大HP ÷ (ratio × モブ攻撃力)」で確認すること。
出荷値の上限は 0.45（`MobOverridesConfigTest` にガードあり）。同じ理由で、被ダメージを上げたい
ときに `fixed-damage`（全防御貫通）を使うのは禁じ手 —— 対策手段が存在しなくなる。

### 魔法防御を持つ防具は「守護/魔織シリーズ」だけ（`LEATHER_*#2000xx` / `#2002xx`）

`stats/item-stats.yml` を全数確認した結果（2026-08-03）、`magic-flat-defense` /
`magic-resistance` を持つ**防具**はこのシリーズのみ。**バニラ素材の防具は Lv100 のネザライトまで
含めて全部位が `phys-*` だけ**を持つ。他に魔法防御を拾える経路はスレッド
（陶器の欠片／鍛冶テンプレート）のランダムロール（`magic-flat-defense: 0.6〜2`・付与確率45%）だけ。

- 段は **Lv20 見習い / Lv40 魔術師 / Lv60 大魔導士 / Lv80 賢者 / Lv100 星詠み**
  （Lv80・Lv100 は 2026-08-03 追加。追加前は Lv60 が打ち止めで、Lv61以降は
  「Lv60 の防具を着続けて物理を捨てる」か「魔法を捨ててネザライト」の二択だった）
- **`use-level-requirement` は下限**なので、上位帯のプレイヤーが下位段を着ること自体はできる。
  「Lv61以降は魔法防御ゼロ」ではなく「**レベル相応の魔法防御装備が無い**」が正しい
- **このシリーズは resourcepack にモデルを持たない**（`cmd-registry.json` にしか出てこない
  ＝ `color:` で染めた革防具）。段を足すときテクスチャ／モデルの作業は要らない
- `mage_arcane_*`（CMD 200031-200054）は **item-stats と cmd-registry にだけ存在し
  `catalog.yml` に定義が無い未配線シリーズ**。入手経路が無いので数に入れないこと

### `mob-overrides.yml` の scope 直下 `stats:`（ダンジョン全体の既定）

`level-cutoff:` と同じく `overrides.<worldName>.stats:` を `mobs:` と同階層に書ける（2026-08-03）。
キー体系は `mobs.<mobId>.stats` と完全に同一（同じ `parseStats` を通る）。ただし `level-cutoff` が
**ブロック単位の採用**なのに対し、`stats:` は**項目単位マージ**。解決は5層:

`base < default の scope直下 < default の mob単位 < world の scope直下 < world の mob単位`

**`mobs:` に1件も書いていないモブにも効く**のが存在意義（396体へ1体ずつ書かずにダンジョン全体へ
コンセプトを乗せるため）。`worldScopeKey` の候補は `scopes` だけでなく scope直下設定を持つ scope
との**和集合**を見る —— これが無いと「`mobs:` を書かず scope 直下だけ指定した scope」が無言で
一致しない（2026-08-03 以前は `level-cutoff` にも同じ潜在バグがあった）。

### ⚠️ scope 直下 `stats:` に守備ステ（耐性・defense-rate）を書いてはいけない

**項目単位マージ**＋**mob単位が後**という組み合わせの帰結として、
モブ個別が `resistance:` を1つでも持っていれば **scope 側の同じキーは必ず負ける**
（`MobStatOverride.mergeDefense`）。出荷 `mob-overrides.yml` のダンジョンモブは
**397/404 が自前の `physical`/`magical` を持つ**ので、scope 直下に耐性を書くと**ほぼ全数に効かない**。

しかも「効かない」ではなく「**`mobs:` に載っていない残り数体にだけ効く**」という中途半端な挙動になり、
per-mob の実データと矛盾する値をコメント代わりに書けてしまう
（2026-08-03 に実際にやった。28ダンジョン分の耐性設定が丸ごと no-op だった上、
per-mob の耐性型と真逆のラベルを9ダンジョンに付けていた）。

- **守り（どの属性が通るか）＝ per-mob の `stats.physical`/`stats.magical` だけで表現する。**
  各ダンジョン先頭の `# ▼コンセプト:` コメントがその要約で、**これが一次情報**
- **攻め（敵の攻撃の属性比）＝ scope 直下の `attack.magic-ratio` だけ。**
  per-mob 側がダンジョンでは1件も書いていないので、ここは実際に効く
- 両者は**必ず逆向きに揃える**（物理装甲が厚い敵＝魔法が通る＝攻撃はほぼ物理＝`magic-ratio` 低）。
  同じ向きにすると攻めも守りも同じ属性で足り、属性分けの意味が消える
- `MobOverridesConfigTest` が「出荷ymlの scope 直下 stats は `physical()==null && magical()==null`」
  を要求しているので、再混入するとテストが落ちる

## 関連

- [./progression-skilltree.md](./progression-skilltree.md)
- [./forks-and-mobs.md](./forks-and-mobs.md)
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)
- [./bedrock-geyser.md](./bedrock-geyser.md)
