# 戦闘・ステータス領域の恒久知識

このコードベースに初めて触るAIエージェント／開発者向けに、戦闘計算・ステータス集約・モブ連携まわりの「今後も踏みうる落とし穴」と「設計上の不変条件」だけをまとめる。作業履歴・日付経緯は扱わない（それは `reports/ACTIVE_RECORD.md` の役割）。

## 全ステ合算アーキテクチャ

### プレイヤーのステータスは単一の合算パイプラインを必ず経由する

`PlayerStatAggregator.aggregate()` が防具4部位＋メインハンド（または発射武器）＋オフハンド（アイテムごとの `offhand-stats-apply`。グローバルトグルは廃止済み）＋スキルツリーパーク＋役職バフ＋永続バフ＋`base-stats`（config駆動の全員一律加算）＋アドオン（スレッド）を1つの `item` マップへ集約する唯一の生成者。攻撃側は `CombatListener`、防御側は `PlayerDefenseResolver` が両方ともこのマップを `totalOf()` 経由で読む。新しいステ供給源を足すときはこの1マップへ merge するだけで戦闘・採集・クラフト・Ars連携まで一括で効く。

- 攻撃／防御の振り分けは `AttackStatBridge`/`DefenseStatBridge` のキーフィルタが担当。属性写像ステ（max-health/move-speed/attack-speed/attack-reach 等）が同じ集約マップに入っていても、フィルタで拾われない側には流れないので二重適用にはならない。
- `armor-strength` は `DefenseStatBridge` が常に0固定にする。バニラ armor 属性のミラー（`SymmetricCombatService.vanillaArmorDefense` 系）経由で別途計上されるため。集約側の供給源が増えてもブリッジがdropするので二重計上にならない。
- **防御率（`defense-rate`）は 2026-08-15 に一本化された。** それ以前はアイテム側だけ `armor-defense-rate`（バニラ防具値の点数）で書き、`Attribute.ARMOR` へ写像して同じミラーから読み戻していたが、「防具値は直感的でない」というユーザー判断で防具値ステを廃止し、**1点=1.5%軽減（`combat/damage.yml` の `vanilla-armor.defense-rate-per-point`）で換算して `defense-rate` へ統合**した。いまは `DefenseStatBridge` が他の防御ステと同様この率を直接読む。**TFスタンプ装備の `Attribute.ARMOR` は `AttributeApplier` が材質既定ごと常に抑止する（＝防具バーは常に空）** ので、ミラー寄与は0でありTFの防御率と二重計上にならない。ミラー自体は素のバニラ防具（TF未スタンプ）用に残してある。
- 属性系ステ（max-health 等）だけは item マップがバニラ属性へ自動反映されないので、`PerkAttributeApplier.apply()` が `channelOf==ATTRIBUTE` のものだけを Attribute へ merge する。ここが Haste 等の他プラグインと衝突しないよう「ライブ属性値を一切読まない」設計になっている（読むと相殺事故を起こす）。

### ⚠️ オフハンドの規則は「持っているだけ」と「実際に使った」で別（2026-08-13 確定）

`aggregate(Player, ItemStack mainhandContributor, boolean contributorIsOffhand)` の**寄与アイテム
（`mainhandContributor`）は、どちらの手にあっても常に合算する**。`offhand-stats-apply` の門は
**「オフハンドに持っているだけ」のアイテム**（＝`armorAndOffhandStats` が掃くオフハンドスロット）
にしか掛からない。ユーザーが是とした魔法の規則（発動したアイテム＝触媒だけ合算）と同型。

`contributorIsOffhand=true` を渡す呼び出し元は3経路しかなく、**いずれも寄与アイテムは
「今まさに使ったアイテム」**（釣り＝振った竿／飛び道具＝発射時に projectile へ retain した武器）。
「オフハンドに持っているだけ」の状況では `contributorIsOffhand` は立たない。

**ここを取り違えると 54 倍のダメージ事故になる。** 2026-08-13 に一度
「寄与アイテムを門で落とし、代わりに実メインハンドを合算する」という実装を入れて踏んだ:
`CombatListener` の `tfBaseReplaces` は `agg.item()` の `attack-power` を**ベース置換**に使うので、
メインハンドにネザライト剣（3780）を持ったままオフハンドの弓（69）を撃つと
**矢のダメージが剣の値になる**。`item` マップは「総合ステ」と「武器を定義するステ」を兼ねており、
`attack-power` だけは置換の意味論を持つ ── **`item` へ「使っていない武器」を混ぜてはいけない。**

オフハンドスロットの除外は `contributorIsOffhand` だけでは決めず、**プロファイル一致
（Material + CMD）のときだけ**にする。飛び道具は発射から着弾まで秒単位あり、その間に
オフハンドを持ち替えていれば二重計上は起こり得ないので、除外すると新しいアイテムの寄与が無言で落ちる。
参照比較・`equals` は使えない（Craft 実装がスロット読み取りごとに新しいミラーを返す／
`equals` は「同一設定の別アイテム」まで誤って一致させる）。

`PROJECTILE_FIRED_FROM_OFFHAND`（PDC）はこの除外判定にしか使われない。よって
**トライデントが `EntityShootBowEvent` を発火するか否かに関わらず結果は正しい**
（発火する＝オフハンドのトライデントで正しく除外される／しない＝除外なしでも二重計上は無い）。

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

### ⚠️ `mob-types.yml` の `max-health:` は最終HPではなく**ランプの base 項**

`max-health: 4400` を見て「そのモブのHPは 4400」と読むと桁を2つ間違える。実際の適用値は
`MobTypeSpawnListener.java:516-524` が `MobStatScaling.scaleMaxHealth` に渡した結果で、同じブロックの
`level-coefficients` で変形される（`ConversionPolicy.Ramp.at`）:

```
HP(L) = (base + level-coefficients.max-health × L) × max-health-growth^(L / max-health-growth-interval)
        + (L ≥ max-health-high-level-from ? max-health-high-level-per-level × (L − max-health-high-level-from) : 0)
```

ENDER_DRAGON の例（`mob-types.yml:941-972`）: base 4400 / growth 1.053 / high-level-from 45 /
high-level-per-level 12699、`level: 60`。
**`HP(0) = 4400` だが `HP(60) ≈ 288,000`。** さらに `coordinate-coefficient` でワールドスポーンからの
距離ぶんレベルが乗る（`MobLevelScaling.java:34-40`、`max-level` 既定100でクランプ）ので、`level:` の値は下限。
**数値を見積もるときは必ず `level:` と `level-coefficients:` をセットで読むこと。**

### バニラのエンダードラゴンは `CreatureSpawnEvent` を発火する（＝TFの刻印が効く）

2026-08-14 に実サーバで確認済み。エンドの初回生成／エンドクリスタル復活のどちらでも、HPはバニラの 200 ではなく
TF のスケール値になっていた。したがって `mob-types.yml` の `ENDER_DRAGON` 定義は適用され、
戦闘レベルも刻まれるので **`mob-level-table.yml` の `add-drops`（`dragon_scale` など）も効く**。

**これはコードからは判定できない。** 分岐点は「バニラのエンドラがそのイベントを発火するか」だけで、
それは Paper 側の実装依存なのでリポジトリ内には答えが無い。

> **2026-08-18 追記（W-61）**: 「刻印の入口は `onSpawn(CreatureSpawnEvent)` の1本だけ」は
> **もう正しくない**。下記「構造物に最初から置かれているモブ」の受け皿として
> `EntitiesLoadEvent` と `EntityAddToWorldEvent` からも刻印が走るようになった。
> ただし**未刻印（`MOB_LEVEL` キーが無い）個体だけ**が対象なので、この節の結論は変わらない。

**ログで確かめようとして誤読しないこと。** 診断行 `[mob-types] spawn ...` は
`LOG.fine`（`MobTypeSpawnListener.java:543`）なので**既定の INFO コンソールには出ない**。
`latest.log` を grep して 0 件でも「刻印されていない」証拠にはならない。実機でHPを見るのが唯一の確実な方法
（`data get entity @n[type=ender_dragon] Health`）。

### ⚠️ モブHPの上限は `spigot.yml` 設定に依存する

`MobTypeSpawnListener.applyMaxHealth` はバニラの max_health 属性上限に当たると**無言で縮む**（例外を捕まえて `min(value, attr.getValue())` に落とすだけ）。このサーバーの `spigot.yml` は `settings.attribute.maxHealth.max` を `Double.MAX_VALUE` 相当に設定して運用する前提で高レベルモブのHP設計をしている。この設定が既定値へ戻されると、全高レベルモブのHPが静かに1024へ崩れる。触るときは検出用WARNINGログの有無も確認すること。

### ⚠️ 「モブが Lv0」はコードだけでは2通りに区別できない（2026-08-18 調査、W-61）

`MobData.level()`（`pdc/MobData.java:29,70-71`）は `MOB_LEVEL` PDC が**無いときの既定値も 0**
（`DEFAULT_LEVEL = 0`）。つまり表示上の「Lv0」は次の2通りが区別できない:

1. **本当に距離ベースで0と計算された**（`MobLevelScaling.effectiveLevel` は
   `base + floor(distance × coordinate-coefficient)`。多くのフィールドモブは `level: 0` +
   `coordinate-coefficient: 0.02` なので、**ワールドスポーンから50ブロック未満**だと
   `floor(49×0.02)=0` でちょうど0になる。これは仕様どおり）。
2. **`CreatureSpawnEvent` 自体が一度も発火せず、`MobTypeSpawnListener` がそのモブに一度も
   触っていない**（PDCキーが皆無なので `level()` は既定の0を返すだけ）。距離が数千ブロックあっても
   同じ「Lv0」に見える。

距離は `entity.getLocation().distance(entity.getWorld().getSpawnLocation())`
（`listeners/MobTypeSpawnListener.java:715-722`、ワールド不一致は0にフォールバック）で、
**ワールドの `getSpawnLocation()`** を使う。datapack が別ディメンション（`World.Environment.CUSTOM`）
を追加している場合、`dimensions:` セクションは NETHER/THE_END しか設定が無く（`mob-types.yml:244-250`）
CUSTOM環境は下駄0のまま、かつそのワールド自身の spawn 起点からの距離になる（オーバーワールドの
拠点からどれだけ離れたかとは無関係）。

**切り分け方**: (a) 実際のブロック座標とそのワールドの `getSpawnLocation()` の距離を測る、
(b) そのモブに `MOB_LEVEL` PDC キー自体があるかを確認する（無ければ②、有って値が0なら①）。
コードの静的解析だけでは判定できないので、実機（`/data get entity <mob> PersistentData` や
一時的な `LOG.fine` 有効化）での確認が必須（`[mob-types] spawn ...` は既定 INFO では出ない、
前掲の「ログで確かめようとして誤読しないこと」と同じ罠）。

### ⚠️ 構造物に最初から置かれているモブは `CreatureSpawnEvent` を発火しない（2026-08-18 修正、W-61）

上の②の**具体的な原因がこれ**。構造物テンプレートの NBT に焼かれているモブ
（データパックの構造物、バニラの前哨基地・海底神殿など）は、**チャンクが生成された時点で
既に存在している**のでスポーン処理そのものを通らない。Bukkit の
`CreatureSpawnEvent.SpawnReason.CHUNK_GEN` 自体が
「no longer called, as chunks are generated with entities already existing」として
**非推奨（1.14 以降・1.21 では削除予定）**になっている。**データパック固有の問題ではない。**

受け皿は `MobTypeSpawnListener#backfillUnstampedProfile` で、
`EntitiesLoadEvent` と `com.destroystokyo.paper.event.entity.EntityAddToWorldEvent` の
**両方**から呼ぶ。片方に寄せていないのは、`EntitiesLoadEvent` の javadoc が
"Called when entities are loaded." としか書かず**新規生成チャンクを含むか明言していない**ため。
**冪等性は `MobData#hasProfile()`（＝`MOB_LEVEL` キーの有無）が担保する。**
ここを緩めるとチャンク読み込みのたびにステが再計算され、HP が張り直される。

**⚠️ 受け皿の対象は `org.bukkit.entity.Mob` に限定すること。**
`EntityAddToWorldEvent` は `LivingEntity` なら何にでも飛ぶので、
`CreatureSpawnEvent` の母集団より**広い**。とくに**アーマースタンド**は
LivingEntity でありながら `CreatureSpawnEvent` を発火しない＝これまで TF が一度も
触っていなかった実体で、絞らないと `defaults`（`max-health: 800` + 防御ステ）を刻まれ、
`MOB_TYPE_STAMPED` まで付いて**ドロップ処理の対象にまで入る**。
ホログラム・装飾・他プラグインの表示用アーマースタンドを巻き込む事故になる。

### ⚠️ モブ特殊攻撃の走査は「プレイヤーの周囲を舐めるだけ」— 交戦条件は自前で書く（2026-08-18、W-62）

`MobAbilityTask#run` はオンラインの各プレイヤーの半径 32m 以内の全 `LivingEntity` を走査し、
**射程とクールダウンしか見ない**。モブ側の AI 状態は一切参照しないので、
条件を足さない限り**索敵していないモブも壁を挟んだモブも等しく抽選対象**になる
（「壁の裏から矢の雨が降ってくる」の正体）。

現在の交戦条件は `mob-abilities.yml` の `require-target` / `require-line-of-sight`（既定 true）。

- **追跡条件は `org.bukkit.entity.Mob` にだけ課す。** AI を持たない `LivingEntity` には
  `getTarget()` の概念自体が無いので、一律に課すと AI を切ったボスや実体だけのギミックモブが
  **技を一生撃たなくなる**。
- **視線判定はレイトレースなので、追跡条件を通った相手にだけ引く。**
- **判定本体は純関数（`MobAbilityTask#engagementAllows`）に切り出してある。**
  MockBukkit は `LivingEntity#hasLineOfSight` も `Mob#getTarget` も実装しておらず、
  実体経由でテストを書くと `UnimplementedOperationException` が SKIPPED に化けて
  **一度も検証されないまま緑になる**。
  ただし純関数だけでは「判定は正しいが呼ばれていない」no-op 修正を見逃すので、
  `tryFire` をパッケージ非公開にして配線ごと固定する試験を必ず併置すること
  （視線条件を切れば MockBukkit でも `setTarget`/`getTarget` は動く）。

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

### モブ → モブのダメージは TF が一切触っていない（味方モブを調整するとき最初に知ること）

`CombatListener#onEntityDamageByEntity` は「被害者が Player」（→ `handleMobToPlayerDamage`）か
「加害者が Player」のどちらでもなければ、`resolveAttacker` が null を返して**即 return** する。
したがって `MobData.stampAttack` で刻んだ `attack-power` / 会心 / 貫通は
**モブがプレイヤーを殴るときにしか読まれない**。

ここを知らないと、味方モブ（手懐けた狼・召喚モブ・ゴーレム）を強くしようとして
`mob-types.yml` の `attack.attack-power` を上げる、という**必ず失敗する直し方**をする:

- 対モブ戦は**1 ミリも変わらない**（TF がその経路を通らないので、与ダメはバニラのまま）
- 一方で**対プレイヤーだけが強くなる**（mob→player は TF 経路を通る）
  ＝ペットや召喚モブが PvP の代理攻撃手段になる、という別の穴が開く

さらに TF はモブの HP を大きく持ち上げている（`(base + coeff×lv) × growth^lv` ＋ Lv45 以降の加算区間。
SKELETON なら Lv0 で 340・Lv45 で 2,805）。**HP だけ持ち上げて mob→mob の与ダメはバニラ**なので、
モブ同士の戦闘はバニラの数十〜数百倍の時間がかかる。狼（バニラ与ダメ 4）が Lv0 のスケルトンを
倒すのに約 85 回、Lv45 なら約 700 回殴る計算になる。

味方モブを実戦で意味のあるものにしたいなら、触るべきは attack ステの数値ではなく
**「味方モブ → 敵モブ」の経路を作るかどうか**。味方判定に使える手掛かりは
`arspaper:summoner_uuid`（召喚モブ）／`Tameable#getOwner()`（ペット）／
`IronGolem#isPlayerCreated()`（村の自然湧きと作ったゴーレムを区別できる唯一の API）。
設計案と却下理由は `reports/ACTIVE_RECORD.md` の M-2 / M-3 を参照。

## マナ回復ステータス

### ⚠️ キー名は直感と逆向き

`hit-mana-recovery` = **被弾時**（`ArmorManaListener#onPlayerDamaged` 相当。「hitを受ける」の意）。`damage-mana-recovery` = **自分が近接で与ダメした時**（`ENTITY_ATTACK`/`ENTITY_SWEEP_ATTACK` 限定。射撃・魔法では発動しない）。同じ向きが `combat/base-stats.yml` 専用の `mana-onhit-*` / `mana-onattack-*` にも適用される。表示名・skilltree の `effect-text`・config-editor の `labels.js` など複数箇所に誤解が伝播しやすいので、この系統を触るときは lore.yml 単体の修正で終わらせず、`effect-text` / labels.js / 各種参照表まで確認すること。

### ⚠️ 同じ回復効果を2つのリスナーで二重加算しない

`hit-mana-recovery` は base-stats を含む全供給源を拾う `mana-onhit-flat` の上位互換であり、両方を別々のリスナーで加算すると二重計上になる（過去に実際に発生した）。マナ系ステータスを触る前は、被弾／与ダメの各イベントに複数のハンドラが載っていないかを先に確認すること。`mana-onhit-percent`/`mana-onattack-percent`（最大マナの%）は flat とは計算式が別なので統合しない。

`mana-bonus` / `mana-regen` は yml の行を消せない仕様になっている（`BaseStatsConfig.missingVocabularyKeys` が「StatVocabulary の全キーが base-stats.yml に存在すること」を強制するテストになっているため）。UI上で隠したい場合は editor 側の除外リストで対応し、config側の行は残す。

## スレッド（ArsPaperフォーク）の品質・常時効果・数値ステ（2026-08-17調査）

### ⚠️ スレッドは「作られた瞬間」に rollSeed+quality=0 を自己刻印する — `/tf give` の quality 引数もピックアップ時の開運(loot-luck)ロールも二度と効かない

`ThreadItem#createItemStack()`（引数なし版、`ArsItemGiveBridge.create`/gacha/mob-drop 等が呼ぶ経路）は
内部で `createItemStack(null)` → `TrinityForgeBridge.stampThreadIdentity(item, null)` を呼び、
crafter が null（生成者不明経路: ルートチェスト/ダンジョンドロップ/**管理コマンド付与も含む**）のときは
**quality を必ず 0 に固定したまま `ItemFactory.stamp()` で rollSeed を発番する**（
`TrinityForgeBridge.currentArsSmithingQuality` が `crafter==null` を見て即 0 を返す設計、
`fork-handoff/arspaper/fork/.../ThreadItem.java:51-56`, `TrinityForgeBridge.java:1360-1381`)。

この「作成時に rollSeed 済み」が2箇所を同時に殺す:

1. **`/tf give thread_xxx <quality>` の quality 引数が無視される**: `GiveItemCommand#buildOne` の
   再刻印ゲート `stampable = ArsItemGiveBridge.isQualityStamped(itemId) || MaterialTier.of(built.getType()).isEquipment()`
   は、スレッドの `isQualityStamped()`(`BaseCustomItem` 既定 false, `ThreadItem` は override しない)も
   `isEquipment()`(スレッドの素材＝防具トリム型/陶器の欠片/旗の模様はどれも非装備材質)も false になるため
   **常に false**。よってコマンド側の `factory.stamp(built, ..., quality)` 再刻印が一度も走らず、
   `ArsItemGiveBridge.create()` が内部で既に焼いた quality=0 がそのまま残る。**これは既知の
   「品質の門を `isEquipment()` で書くと非装備TF品が常に品質0になる」落とし穴と同型**
   （`GiveItemCommand.java:508-530`）。
2. **開運(loot-luck)によるピックアップ時品質ボーナスが一生発動しない**: `PickupQualityListener#stampIfEligible`
   は `data.hasRollSeed()==true` の時点で（`PreviewRollSeeds` でなければ）即 `return false`
   する（`PickupQualityListener.java:215-224`）。スレッドは1で述べたとおり生成時点で既に
   rollSeed 済みなので、`quality.lootBaseQuality() + lootLuck.qualityModeBonus(...)` を計算する
   分岐（開運ステの唯一の品質適用点）に**スレッドが到達することは構造的に無い**。開運を盛っても
   ドロップ/ガチャ/give由来のスレッド品質は一切変わらない。
   儀式クラフトでできたスレッドだけは別経路（`ThreadItem#createItemStack(Player)` → 実クラフター指定で
   `currentArsSmithingQuality` → `ARS_SMITHING` スキル + `ritual_quality_bonus` パーク由来）で品質が付くが、
   これも loot-luck とは無関係な別ロジック。

**How（修正するなら）**: `/tf give` 側でスレッドを検出して stampable 判定へ含める、または
Ars 側に「admin 指定 quality で作る」専用エントリを新設して `writeItemRoll` + `ThreadItem.fullLore`
で組み直す。**`ItemFactory.stamp()` をそのままスレッドへ適用する場合は lore を必ず
`ThreadItem.fullLore`/`equipmentStyleRollLore` で作り直すこと**（stamp 自身の汎用装備lore
アセンブラは thread 専用の lore 体裁と食い違う）。

### ⚠️ スレッドの「常時効果」（ポーション/飛行/バックパック）は着用防具でしか発動しない — 仕様（2026-07-31 F2 確定）

`ThreadApplicationPolicy.appliesAmbientEffects(origin)` は `origin == WORN_ARMOR` のときだけ true。
数値ステ（`appliesNumericStats`）は防具/メインハンド/オフハンド全部で true だが、
ポーション効果・滑空・バックパックは**着用防具のみ**。理由は「事故に見えるから」（javadoc、
剣を握っただけで滑空/ポーション点滅は不具合として受け取られる）。GUI（`ThreadGui`）は対象が防具でない
ときスロットの lore に `※この装備では常時効果は発動しません`（`ThreadGui.java:423-427`、条件は
`!targetIsArmor && ThreadApplicationPolicy.isAmbientOnlyEffect(type)`）を出す。**これは意図した仕様で
バグではない。** 幸運(LUCK)/迅速/暗視/耐火/イルカの好意/体力増強/浮遊/村の英雄/コンジットパワー/飛行/
バックパックの各スレッドが対象。マナ系(mana_regen/mana_boost 等)・数値のみのスレッドはこの制約を受けない。

### 開運(loot-luck)はドロップ数/確率を一切増やさない — 効くのは「品質」だけ

`PlayerLootLuckSource.totalLuck()`（`loot_luck` 装備+perk合算 + バニラ `PotionEffectType.LUCK`
効果レベル）の唯一の消費者は `qualityModeBonus()` で、呼び出し元は
`PickupQualityListener`（一般ドロップ/チェスト/クリエイティブ取得品の品質ロール mode 加算）と
`FishingQualityListener`（釣果の品質ロール mode 加算）の2つだけ。ドロップ**数・確率**を増やす経路
（`mob_drop_bonus`=モブ撃破時のTF追加ドロップchance倍率、`mining-fortune`=採掘追加ドロップ期待値、
`fishing_luck`=釣り追加ドロップ期待値、`hive_harvest_fortune`=養蜂、`woodcutting_extra_drop_chance`/
`harvest_extra_drop_chance`=伐採/収穫）はどれも別ステータス・別リスナーで、開運とは加算されない。
「幸運系ステが多すぎて紛らわしい」相談を受けたら、まず「品質を上げるもの」と「個数/確率を上げるもの」
で軸を分けて説明すること。

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
- **`defense-rate`（防御率。2026-08-15 に廃止した `armor-defense-rate` の統合先）を候補に入れてはいけない**。物理ダメージの守備力減算率そのものなので、
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

### 武器種の校正は「同系列の剣に対する実効DPS比」で見る（2026-08-05 W-32/W-34/W-35）

**実効DPS = `perHit × min(attack-speed, 2.0)` + 出血の定常値**。導出の要点:

- `min(attack-speed, 2.0)`。被弾側の無敵時間は `LivingEntity.invulnerableDuration(20) / 2 = 10 tick` で
  TF はここを触っていないので、**同一の敵へ入る有効打は毎秒2発が上限**。
  `attack-speed` に 2.0 超を書いても表示だけで実効は伸びない（出荷には 4.12 が4本あった）。
- `MeleeChargeMultiplier` はフルチャージで 1.0 になるので、最適な振り間隔は `T = 20/AS`。
  出荷 `melee-charge`（min 0.1 / exp 1.6）では**連打の方が得になるのは `attack-speed < 0.28` の
  遠隔勢だけ**なので、近接は上の式で足りる（`AS = 0.408` でも full charge が最適）。
- 比の分母は必ず**同じ系列の剣**。Lv100 には binder / infinity / emberforge / abyss / cryocore / hero
  の6系列が並び、系列ごとに強さが違う（hero は infinity の約6割）。「同レベル帯で最強の剣」を分母に
  すると弱い系列の武器を系列内の剣より強く見積もる（一度これで hero_mace を 1.5 倍にした）。

**⚠ `combat/stat-caps.yml` の上限が設計制約になる。** 上限は**最終合算値**に効くので、超過分は
「表示だけ上がって実効は伸びない死に設定」になる。特に:

- `attack-power: 127500`（出荷の単品最大 = `fixed + per-quality×9 + random.max`）。
  **`attack-speed` が遅い武器は、同じDPSを出すのに `attack-power` が反比例で必要になる**ので、
  遅すぎる武器は上限と両立しない。メイスは `AS 0.408` のままパリティに載せると上限の **2.09 倍**
  必要になったので、`AS` を 0.88 へ上げて解決した（下げるべきは上限ではなく武器の遅さ側だった）。
- `bleed-damage: 4500`。出血は `SymmetricCombatService#bleedFinalDamageFlat` 経由で
  **防御側ステをほぼ全部無視して体力へ直接入る**（`BleedService#applyOneTick`）ので、この上限が
  絶対の天井。「総DPSの15%」で設計すると Lv100 では上限の 5.9 倍になり、超過分は無言で消える。
  → 鎌の出血は「15%、ただし 4500 で打ち切り」。Lv100 では総DPSの 3% にしかならないが、
  `max-mitigation-rate: 0.9` の相手には**軽減後DPSの2割超**になるので死に設定ではない。

**接尾辞での武器種判定は名前付き一点物を取りこぼす。** `Winter_Grim_Reaper`（鎌）/
`fnis_peccati_profundi`（鎌）は id に武器種を持たないので、`endsWith("scythe")` 方式の検査を
**丸ごと素通り**していた（`attack-speed 4.12` / reach 0.1 / 出血2% のまま残っていた）。
正しい所属は `catalog.yml` の `_editor.categories.weapon` が持っているが、**このカテゴリ表は
未整備で信用できない**（剣が4件しか入っておらず 54 件が「準備中」）ので、
接尾辞 + 明示エイリアス表の併用が現実的。出荷 id の綴り違い（大剣 = `grate_sword`、
鎌 = `nethrite_scythe` / `golad_scythe`）も同じ理由で名前一致を壊す。

**遠隔武器の近接 `attack-speed` は 0.1 で揃える**（2026-08-02 決定「殴るための武器ではない」）。
`revolution_bow` だけこの一斉変更から漏れて 1.6 のまま残り、**弓が同レベル帯で最強の近接武器
（剣の119%）**になっていた。一斉変更をやったら必ず「その武器種の全件が同値か」を機械で確認する。

**防具の防御力（同じ棚卸しの防具側）は外れ値なし。** 34 系列すべてが
`helmet:chestplate:leggings:boots = 15:40:30:15` で揃い、`turtle` だけ 100%（バニラ同様ヘルメット
専用なので正しい）。レベル逆転に見える 18 組はすべて**魔法防具 vs 物理防具**で、
魔法防具は `phys-flat-defense` を落とす代わりに物理防具が 0 の `magic-flat-defense`
（25〜1,068）を持つ設計上のトレード。物理軸だけ見て「弱い」と判定してはいけない。

回帰は `WeaponTierParityTest`（9件。武器種ごとの帯 / 剣を超えない / AS 統一 / AS 2.0 上限 /
遠隔0.1 / リーチ表 / 鎌の出血 / 出血の一番手は鎌 / 杖の詠唱DPS）と
`WeaponDpsParityTest`（重武器 vs 軽武器の H/L 比）で固定してある。
帯は「値の一覧」ではなく**同系列の剣に対する比**なので、武器を1本足しても勝手に守られる。

### ⚠️ リーチと火力は2本セットでしか動かせない（2026-08-18 W-72）

`attack-reach` を武器種ごとに ±2.0 まで広げた（短剣 −1.5 / 槍・ハルバード +2.0 /
猟師の投槍 +2.2。実効 = バニラ 3.0 への加算）。**リーチだけ動かすと間合いの有利がそのまま
強さの差になる**ので、同時に「リーチ 1.0 につき実効DPS 5%」を符号反転して掛けてある
（短剣 +5% 〜 槍 −5%）。掛け先は `attack-power` と `fixed-damage` の2キーだけ。

- **`bleed-damage` は掛けてはいけない。** 上位段の鎌は `stat-caps` の上限 4,500 に張り付けてあり、
  下げると「上限張り付き」判定（`atCap`）が外れて `scytheBleedIsMeaningfulUpToTheCap` の
  出血比率チェック（8〜25%）が発動し、**総DPSが大きい上位段だけ 2.5〜4.7% で一斉に落ちる**。
  実際に一度これで6本落とした。
- **`MELEE_BAND`（剣に対する比の許容帯）も一緒に動かす。** 帯は比なので、片側だけ動かすと
  必ず外れる。丸めは**外側へ**（下限は切り捨て・上限は切り上げ）。
- 一括適用は `item-stats.yml` を**行単位で**書き換えるのが安全（YAML を再シリアライズすると
  日本語コメントが全部飛ぶ）。`fixed` / `per-quality` / `random.min|max` の3か所を漏らさないこと。

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
- 防御率（`defense-rate`）に**専用経路は無い**。2026-08-15 の防具値廃止までは `armor-defense-rate` だけが base-stats/permanent の合算を `extraArmorDefenseRate` へまとめて perkDefense へ直接渡す迂回路を持っていた（item マップ側は常に0固定＝`DefenseStatBridge` 回避）が、その迂回路ごと削除した。いまは他のステと同じく item マップへ merge される。
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

## 2026-08-10 火力/防御の再較正で確定した恒久知識

### フィールドモブの `attack-power-high-level-per-level` は 0（ダンジョン側の 0.25 とは意図的に非対称）

`combat/mob-types.yml` の各エントリが持つ `level-coefficients.attack.attack-power-high-level-from/-per-level`
（2026-08-03 要件#63 で「HPだけ高レベル加速があって攻撃力に無い」不整合を消すために新設）は、
2026-08-10 の火力/防御再較正で **`per-level` が全エントリ 0 へ撤去された**（`from: 45` キー自体は残るが無干渉）。
理由は、base の `attack-power-growth` を 1.033→1.02 へ寝かせつつ素の `attack-power` を ×1.4545 底上げし、
`max-health-high-level-per-level` を ×2.5 する形で「HP側だけに高レベル加速を残す」設計へ変更したため。

- ~~**ダンジョン側 `combat/mob-import.yml` の同キーは今回の対象外で `0.25` のまま**~~
  → **2026-08-12 に撤回した。揃えるのが正しい。** フィールドとダンジョンは別ランプだが、
  **同じプレイヤーの同じ最大HPを相手にする**ので、攻撃力の「伸び」が割れると
  `(片方のgrowth / もう片方のgrowth)^Lv` がそのまま強さの差になる。実際、2026-08-10 に
  フィールドだけ寝かせた結果 **Lv100 でダンジョンモブの攻撃力が 148 対 35（約4.3倍）** になり、
  プレイヤー最大HP 100 に対して事実上の即死だった。2026-08-12 に mob-import.yml も
  `base: 10.2 / growth: 1.0148 / high-level-per-level: 0.0` へ揃えた。
  **`base` の比（ダンジョン 10.2 : フィールド 8.0 ＝ 約1.27倍）で「ダンジョンの方が強い」を表現し、
  `growth` は必ず一致させる**、というのが今の規約。
- **ランプを触ったら `combat/mob-overrides.yml` のボス絶対値も一緒に引き直すこと。**
  `MobStatOverride` は倍率キーを持たないので、束縛者4段階＋ミニボス3種の `attack-power` は
  「ランプを Lv50 で評価した実値 × 計画倍率」を**展開した絶対値**で書いてある。
  `ShippedBossStrengthDriftTest` はランプを実読して割り戻すので、ランプだけ触ると必ず落ちる
  （落ちるのは正しい。7 個の絶対値を新しいランプで計算し直すのが対処）。
- 固定テストは `ShippedMobTypesLevelBandTest`（field側、`per-level==0` を50エントリ全数で縛る）と
  `ShippedMobImportBreakpointTest`（dungeon側、**純粋な指数であること＋フィールドと growth が
  一致すること**を縛る。2026-08-12 に「`0.25` が生きていること」から契約を反転させた）の 2 本。

### 防具の絶対値は再較正で動くが、重装/軽装の構造的不変条件は再較正でも保たれている（既存知識の再確認）

「防具の防御力は外れ値なし」節（2026-08-05）が固定した重装/軽装の関係（同帯で `phys-flat-defense` は
重装/軽装同値・軽装の `fixed.max-health` は必ず0・軽装の `phys-resistance` は重装以下）は、
**攻撃力カーブに合わせて防具の絶対値を丸ごと引き直す再較正でも、崩さずに保てる**（2026-08-10 に実際に
このパターンで全面改訂し、`ArmorHeavyVersusLightDefenseOrderTest` は無傷だった）。この4条件は絶対値では
なく重装/軽装の**比較**なので、両者を同じ関数（同じ帯の `A(L)` から同じ係数で導出）で同時に引き直せば
自動的に保たれる。片方だけ触ると即座に壊れるので、防具の絶対値再較正は「重装と軽装を同じ式に通す」形で
実装すること。なお絶対値そのもの（S理論値20発/厳選なし6発 等）を固定するテストは TF 本体（Java）ではなく
`tools/config-editor/test/armor-ladder.test.js`（JS 側）にあるため、Java の `gradlew test` だけでは
この軸の drift は検出できない。

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

## モブの特殊攻撃（mob-abilities.yml / mob-overrides.yml の `abilities:`）

### ⚠️ 未定義のテンプレートIDは「エラー」ではなく「技を1つも撃たないボス」になる

`MobAbilityTask#candidatesFor` は `abilitiesConfig.ability(id)` が null のとき `continue` する
（ロード順に依存させないための意図的な設計。`combat/mob-abilities.yml` の読み込みが
`mob-overrides.yml` より後でも壊れないようにするため）。したがって `abilities: [shockwaev]` の
ような綴り違いは**ログにも出ず、そのモブだけ静かに無技化**する。400 体近いモブへ手で貼る運用では
必ず起きるので、突き合わせは `ShippedMobAbilityAssignmentTest`（出荷 yml 同士を機械で照合）で落とす。
同テストは「定義したのにどのモブにも貼られていないテンプレート」も落とす（死にテンプレート防止）。

### ⚠️ yml の `knockback`（0〜5）はそのまま速度に使えない／ゼロベクトルを正規化すると操作不能になる

Bukkit の速度は**ブロック/tick** なので、`knockback: 5` を `setVelocity` にそのまま渡すと毎秒 100
ブロック＝場外まで吹き飛ぶ（バニラのノックバックは約 0.4）。`REPULSE`/`VORTEX_PULL` は
`MobAbilityExecutor#repulseVelocity` / `#pullVelocity`（どちらも純関数・テスト済み）で水平 0.4 倍・
垂直 0.18 倍（上限 0.9）へ換算する。垂直に天井を置くのは、打ち上げ高度を上げると**落下ダメージだけで
殺せてしまい防具の投資が無意味になる**ため。加えて、攻撃者と被害者が同一座標だと差分ベクトルが
ゼロになり `Vector#normalize()` が **NaN 速度**を返す ── 例外もログも出ないまま**プレイヤーが
操作不能**になるので、両関数ともゼロ長を先に分岐して真上ベクトルへ落としている。
なお `GROUND_SLAM` 等が使う `pushAway` は Y=0.35 固定・現在速度への**加算**なので、強度を上げても
真横に滑るだけ。「強く吹き飛ばす」を作るなら `REPULSE`（速度の置き換え）を使う。

### 多段ボスの技は「最深段」に置く（HP で通過するだけの段に置くと本番が無技になる）

EliteMobs の phase ボスは `p2: 0.80 / p3: 0.50 / p4: 0.30` のように**HP 割合で次段へ移る**ので、
中間段は数秒で通過し、最後の段が一番長い戦闘になる。`mob-overrides.yml` の `abilities:` を
中間段（例 `*_p3`）に書くと、実際に戦う最終段（`*_p4`）が無技のままになる。出荷 yml は
`the_climb_undead_beastmaster` / `the_castle_charlemagne` / `wood_league_wave_50_boss` などで
最深段に置く形で統一してある。段の閾値はフォーク側 `custombosses/*.yml` の `phases` が一次情報。

### 技の `damage-type` は「そのモブの耐性の逆」に揃える／雑魚には付けない

per-mob の `stats.physical`/`stats.magical` が「どの属性が通る敵か」を表す（`mob-overrides.yml` の
▼コンセプト行が要約）。物理装甲が厚い敵＝魔法が通る＝**敵の攻撃は物理**、が既存の規約
（本ファイル「⚠️ scope 直下 `stats:` に守備ステを書いてはいけない」節の攻め／守りの向き）。
テンプレートの `damage-type` もこれに合わせないと、攻めも守りも同じ属性で足りて属性分けが消える。
**雑魚（`# 雑魚` 分類のモブ）には貼らない**: 同時湧きの頭数ぶん AoE が重なり、波の被ダメージが
設計不能になる。出荷の割り当ては全てボス／ミニボス／節目の波ボスに限定してある。

### `DELAYED_ZONE` の `duration-seconds` は AURA の持続とは別物（0 を即着弾にしない）

`AURA` では `durationTicks()`（持続時間）、`DELAYED_ZONE` では `delayTicks()`（印を置いてから
着弾までの予告時間、10〜100 tick へ丸め・**未設定は既定 30 tick**）と、同じ yml キーを別の意味で
読む。`delayTicks()` が 0 を「即着弾」にすると、**予告を書き忘れた瞬間に「避けられる高倍率技」が
「回避不能な高倍率技」へ静かに化ける**ので既定値へ落としてある（`MobAbility#delayTicks`）。
印は発動時の足元に固定し追尾させない（追尾すると回避手段が射程外へ逃げるだけになる）。
倍率は既存最大（`piercing_beam` の 2.0）を超えさせない ── 避けられるからと上げると被弾1回が
実質ワンショットになり、防具の投資が意味を失う。

### ⚠️ 特殊攻撃は視線判定もターゲット追跡判定も一切持たない（2026-08-18 全走査で確認、W-62）

`MobAbilityTask#run`/`#tryFire`/`#candidatesFor`（`combat/MobAbilityTask.java`）は
「オンラインプレイヤーの周囲 `SCAN_RADIUS`(32m) 以内にいる `LivingEntity`」を無条件に相手役として
使い、`candidatesFor` は**距離（`ability.range()`）とクールダウンしか見ない**。`MobAbilityExecutor`
（同パッケージ）側にも `hasLineOfSight` / `getTarget()` の類は一切無い（`grep -rn
"hasLineOfSight|getTarget\(\)" combat/` で package 全体・`java` ツリー全体を確認、ヒットは無関係な
2ファイルのみ）。つまり **壁の向こうにいても・そのモブが実際にはそのプレイヤーを敵視/追跡していなくても、
距離と乱数だけで技が飛ぶ**。

- **LOS が要るもの**（狙って飛ばす/瞬間移動する型）: `PROJECTILE_VOLLEY` / `PROJECTILE_RAIN` /
  `CHARGE` / `TELEPORT_STRIKE` / `BEAM` / `DELAYED_ZONE`（対象の**現在地**へ印を置く＝発射時点で
  対象を捕捉できている前提の技）。
- **LOS が要らない（むしろ入れると壊れる）もの**: `GROUND_SLAM` / `AURA` / `REPULSE` /
  `VORTEX_PULL`（自分中心の近接AoE。至近距離が前提なので壁越し判定はほぼ意味を持たない）／
  `SUMMON`（対象を狙わず自分の周囲に湧かせるだけ）。
- **ターゲット追跡（非追跡状態での発動抑止）はどちらの分類にも共通で未実装**。対応するなら
  `LivingEntity instanceof org.bukkit.entity.Mob m` の `m.getTarget()` が実際にその `target` と
  一致する場合のみ `candidatesFor`/`tryFire` を通す形が候補（EliteMobs 由来モブが Bukkit の
  `Mob#getTarget()` を実際に維持しているかは未検証 ── フォーク自前AIが素通りしている可能性がある
  ため、導入時は EM モブでの実機確認が要る）。

### ⚠️ 予兆（テレグラフ）があるのは `DELAYED_ZONE` だけ／他は演出と着弾が同時（W-63）

`MobAbilityExecutor` の各型は `playEffects`（パーティクル/音）を**発動と同時**に鳴らし、そのまま
同一 tick か直後で `applyHit` する。唯一の例外が `delayedZone()`: `TELEGRAPH_INTERVAL_TICKS`(5tick)
ごとに着弾地点で予告パーティクルを出しながら `delayTicks()`（既定 30tick=1.5秒、10〜100tickに丸め）
待ってから着弾する。`charge()` も突進〜着地判定まで 12tick の間があるので部分的な予兆になるが、
`playEffects` 自体は発動と同時に鳴る点は同じ。`GROUND_SLAM`/`PROJECTILE_VOLLEY`/`AURA`（初撃）/
`TELEPORT_STRIKE`/`BEAM`/`REPULSE`/`VORTEX_PULL`/`SUMMON` は**発動を告げる演出が無く、
気づいたときには着弾している**（`announce()` の action bar 表示も `playEffects` と同時発火）。
「予兆時間」を追加するなら `DELAYED_ZONE` の `TELEGRAPH_INTERVAL_TICKS` 方式（`BukkitRunnable` で
着弾前に一定間隔パーティクルを出す）を他の型にも展開する形が自然（config には既に
`duration-seconds`/`delay` 相当のキー体系があるので新規レイヤは不要）。

### ⚠️ 技のクールダウンはモブ**個体ごと**に独立 — 同時湧きの頭数分だけ体感頻度が上がる

`MobAbilityCooldowns`（`combat/MobAbilityCooldowns.java`）は `Map<UUID, Map<String, Long>>` で
モブの `UniqueId` をキーにする。同じテンプレートを持つモブが 5 体同時にいれば、各個体が独立して
`chance`/`cooldown-seconds`/`global-cooldown-seconds` を持つため、**プレイヤー視点の体感発動頻度は
単純にモブの頭数倍**になる（複数体の技をまとめて抑える仕組みは無い）。
2026-08-17 に `MobAbilityTask.GLOBAL_GAP_KEY` + `mob-abilities.yml` の `global-cooldown-seconds`
（既定12秒）が追加済みで、これは**1体が複数の技を交互に連発する**頻度は抑えるが、
**複数体が同時に技を持つ**ケースには一切効かない（別UUIDなので別台帳）。「頻度が高すぎる」報告が
この2026-08-17の修正後も続く場合、原因はほぼ確実に後者（同時湧きの頭数）。

## 「戦闘レベルが全プレイヤーで0になる」を疑ったら先に確認すること（2026-08-17 調査）

`progression/combat-level.yml` の `skills:` キー空間は、`SkillId`定数（`LIGHT_WEAPONS`/
`HEAVY_WEAPONS`/`ARCHERY`/`ARS_MAGIC`）／`PlayerProgression#skills()`のキー（`NativeSkillLevelSource`が
そのまま返す。javadoc に明記の「keyed by uppercase skill ID」）／`NativeSkillCatalog`の登録キー
（`SkillId.ALL`）／EXP付与の呼び出し側（`CombatKillCreditTracker`→`CombatListener#onCombatKill`→
`ArsProgressionBridge.grantSkillExp`）が使う文字列、の**全層で一致している**（2026-08-17 に
全経路をコードで裏取り済み）。実サーバ `Velocity_for_TF/Main_Server/logs/latest.log` にも
「軽量武器」「重量武器」「Ars魔法」（=LIGHT_WEAPONS/HEAVY_WEAPONS/ARS_MAGIC）のレベルアップ
メッセージが複数プレイヤーで実際に記録されている。**「combat-level.yml のキーと SkillLevelSource が
返す id が食い違っていて常時0になる」という仮説は、少なくとも現行 HEAD では再現しない。**
同じ疑いを持ったら、この一致を再度コードで裏取りするのではなく、まず以下を見ること:

1. `/tf status`（`StatsCommand`/`StatusGui`、`stats/status/StatusGui.java:175`,
   `command/StatsCommand.java:126`）は両方とも `SymmetricCombatService#combatLevelOf`
   （`CombatListener` が読むのと同一実体）を直接呼ぶ。ここで0が出るなら、そのプレイヤーが
   本当に LIGHT_WEAPONS/HEAVY_WEAPONS/ARCHERY/ARS_MAGIC のどれも育てていない（採取・鍛冶等専業）
   だけの可能性が高い ── **pillars 式の設計上、非戦闘プレイヤーの戦闘レベルが0になるのは
   仕様どおりでバグではない**。
2. EliteMobs 側のダンジョン入場条件・モブ難易度は**別のフォールバック経路**を持つ
   （`fork-handoff/elitemobs/elitemobs-fork/.../skills/CombatLevelCalculator.java`）。
   `TrinityForgeIntegration.isCombatLevelMappingEnabled()`（`available && combat-level-mapping`
   設定、既定true）が真の間だけTFの `combatLevelOf` へ委譲し、false なら EliteMobs 自前の
   （現在は入力経路が存在せず事実上死んでいる）スキルXPシステムへフォールバックして計算する。
   `available` は EliteMobs の `onEnable` 時に TrinityForge プラグインが `isEnabled()` でなければ
   **恒久的にfalseのまま**（再起動まで直らない、`TrinityForgeIntegration.java:111-141`）。
   **この経路で見えている「0」は TF 側の計算バグではない**。ログの
   `TrinityForge combat-level mapping failed` / `... delegation disabled` /
   `... delegation is active` で判別すること。EliteMobs は旧形式 `plugin.yml` の
   `softdepend: [TrinityForge]` で読み込み順自体は保証されている（`paper-plugin.yml` と違い
   `softdepend:` は無視されない）ので、通常運用ではこの分岐に落ちないはずだが、TF側の起動失敗
   （例外/設定エラーで `onEnable` が完走しない）が起きるとここに落ちる。
   **2026-08-17 に実測済み**: ローテート済みログ 12 本を展開して起動バナーを全部拾ったところ、
   記録が残っている 10 回の起動すべてで `TrinityForge detected — ... delegation is active.` が出ており、
   `delegation disabled` / `standalone (non-delegated)` は 1 件も無い。**この経路は現状ヒットしていない。**
3. `NativeSkillLevelSource` 自体（DB→id空間の実データ経路）を検証する専用の JUnit テストは
   存在しない（`SymmetricCombatServiceMagicalIntegrationTest` 等は手作りの `SkillLevelSource`
   を注入した「計算部分」のみの検証）。実データに起因する不具合はユニットテストで検出できない
   ── 疑うときは `player_progression.db` を直接クエリするか、上記ログを見ること。

## レベル差による経験値・ドロップ減衰(level-cutoff)は線形の傾斜を持つ(2026-08-18 W-60実装)

`combat/damage.yml` の `level-cutoff:`(`CombatDamageConfig#levelCutoff` → `MobLevelCutoff`)は、
バニラ経験値オーブ(`LevelCutoffExpListener`)・TF戦闘スキルEXP(`CombatListener#onCombatKill`)・
TF追加ドロップ3系統(`MobOverrideDropListener`=ダンジョンモブ個別／`MobTypeDropListener`=フィールド
モブ／`MobLevelTableListener`=レベル帯add-drops)を**すべて `KillRewardAdjuster` 一点に統一済み**
(2026-08-09)。「EXP側とドロップ側で別実装かもしれない」という懸念は現行 HEAD では成立しない。

※2026-08-17時点では「閾値到達で有効化される単一の固定レート」というステップ関数で、超過量
(`diff - threshold`)を一切見なかった。2026-08-18(W-60)で `over-level` に線形減衰を追加した:
`overLevelExpDecayPerLevel`/`overLevelDropDecayPerLevel`(超過1レベルごとに基準レートから引く量)
と `overLevelRateFloor`(減衰後の下限)。既定は全て `0.0` で、この場合は従来のステップ関数と完全に
一致する(後方互換)。計算式は `rate = clamp(floor, 1.0, 基準rate − max(0, diff-threshold) × decay)`。
`exp-rate`/`drop-rate` が `-1`(完全遮断)のときは減衰計算に入る前に無条件で0/ブロックを返す
(-1 と decay は排他、-1 が常に優先)。実体は `MobLevelCutoff`(`mobs/MobLevelCutoff.java`)、
既存4引数コンストラクタは残しており(7引数版へ委譲、新3フィールドは0.0固定)、呼び出し元を
一切変えずに済んでいる。

**`under-level`(モブがプレイヤーより高レベル)側は 2026-08-18(W-72)に over-level と完全対称にした。**
それ以前は `item-threshold` しか持たず、効果は「TF追加ドロップを一切付けない」の全か無かだけで
**EXPには一切影響しなかった**(`expMultiplier` が `isOverLevelActive` でしか分岐していなかった)。
つまり**低レベルのままハメ殺し/デスルーラーで高レベルのモブを倒すと、バニラの経験値オーブも
TFの戦闘スキルEXPも満額入っていた** ── 撃破EXPはモブのレベルで伸びるので、ここが最大の抜け穴だった。
現在は `exp-rate`/`drop-rate`/`exp-decay-per-level`/`drop-decay-per-level`/`rate-floor` を
under 側にも持ち、計算式・`-1` の優先・`excess==0` で減衰なし、まで over-level と同型。
両方が同時に発動したら小さいほうを採る(起きるのは閾値が両方0で同レベルのときだけ)。
**閾値のキー名は `item-threshold` のまま**(リネームすると配備済み config の値が無言で既定に化ける)。
**パーティでの同行は区別しない**(レベル差だけで判定。免除を入れると低レベルを連れて行くだけで回避できる)。
新キーの既定値は対称化前の挙動(`exp-rate: 1.0` / `drop-rate: -1` / decay・floor は 0)なので、
under-level のキーを書いていない config の意味は変わらない。
**出荷 `damage.yml` では under-level だけが有効**(`item-threshold: 20`、EXPは 0.1/Lv で逓減して30差で0。
over-level は `threshold: -1` = 無効のまま)。値は `ShippedLevelCutoffTest` が固定している。

先例(over-levelの線形減衰を設計する際に参照した3つ、いずれも
「threshold/per-amount + 1段あたりの減衰量 + floor」という共通の骨格を持つ):
`LocationExpDiminishing`(同一地点逓減、線形 `decay-per-kill`)/`DailyExpDiminishing`(日次逓減、
乗算 `decay-per-amount`)/`SkillExpDiminishingCurve` + `FormulaParser`(プレイヤー自身のレベルに
対する `%level%` 式、`level-diminishing.formula`)。

## EliteMobsフォークは「レベル差によるEXP減衰」の別実装を独自に持つ(TFのlevel-cutoffと無関係、TFの有無も見ない)

`fork-handoff/elitemobs/.../skills/SkillXPHandler.java`(`EliteMobDeathEvent` 購読)は EliteMobs 独自の
武器/防具スキルレベリング(レベルアップでタイトル表示・全体アナウンス・防具スキルはHPボーナス付与、
`PlayerData` 独自ストレージ)を持ち、`skills.yml` の `skillSystemEnabled`(既定 `true`)だけで
有効/無効が決まる──**`TrinityForgeIntegration.isAvailable()` 等、TFの有無を見るゲートが一切無い**
(`EliteDropPolicy` の他の全ゲートとは対照的)。内部の `FarmingProtection.getXPMultiplier`/
`getEffectiveMobLevelForXP` がレベル差±5(`MAX_LEVEL_DIFFERENCE`、yml設定不可のハードコード定数)で
TF側とは別の減衰を掛ける(over-level側=連続的な比率減衰、under-level側=閾値超えで0固定)。

対象はEliteMobs管理下のエリートモブのみ(フィールドモブは対象外)。`CombatLevelCalculator` 経由で
TFの戦闘レベルを読むためレベル値自体はTFと一致するが、**判定式・閾値・付与するステータス(EM独自の
武器/防具スキルXP)はTFの`level-cutoff`/`ArsProgressionBridge.grantSkillExp`と完全に別物**。
`skillSystemEnabled: true` のまま運用していると、エリート撃破のたびにTFのHEAVY_WEAPONS等のEXPと
EM独自の武器/防具スキルEXPが同時に加算される(意図した二重進行かどうかは運用側の判断次第だが、
「level-cutoffを拡張しても効かない体感」の原因になりうるので、レベル差減衰まわりの実サーバ報告が
出たら真っ先に疑うこと)。

## TFのlevel-cutoffはTF追加ドロップにしか及ばず、EliteMobsネイティブ戦利品には無防備

`level-cutoff`(`KillRewardAdjuster`)が触るのは `mob-overrides.yml drops:` / `mob-types.yml drops:` /
`mob-level-table.yml add-drops:` という**TFが追加した**ドロップ3系統だけ。EliteMobs自身のランダム
戦利品・特殊アイテム・エリートスクロール等ネイティブ機構は対象外で、かつEliteMobs自身の
`lootLevelDifferenceLockout` は `EliteDropPolicy.blocksLootByLevelDifference` が
`TrinityForgeIntegration.isAvailable()` の間ずっと `false` 固定で無効化している
(`fork-handoff/elitemobs/.../trinityforge/EliteDropPolicy.java`)。したがってTF導入後は
**EliteMobsネイティブ戦利品にレベル差抑制が一切掛からない**(旧ロックアウトを消した代替がTF側に
無い)。判定軸の違い(旧ロックアウト=装備tier平均、TF=戦闘レベル)を理由にした意図的な撤去だが
(javadocに説明あり)、「低レベル狩りの抑制」を謳うなら考慮漏れになりうる残課題。

## 関連

- [./progression-skilltree.md](./progression-skilltree.md)
- [./forks-and-mobs.md](./forks-and-mobs.md)
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)
- [./bedrock-geyser.md](./bedrock-geyser.md)
