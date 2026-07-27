# 監査レポート 04 — モブ・ダンジョン・ヘイト・EliteMobsフォーク差分

対象領域: `TrinityForge/src/main/java/com/trinityforge/{mob,mobs,dungeon,hate}`, `TrinityForge/src/main/resources/{dungeon,hate}`(+関連する `combat/mob-*.yml`), `TrinityForge/src/main/java/com/trinityforge/listeners/` のモブ関連リスナー, `fork-handoff/elitemobs/elitemobs-fork` の未コミット差分全体, `docs/DUNGEON_SPEC.md` / `docs/integration-elitemobs-tf-stats.md` / `docs/EDITOR_POWER_ELITEMOBS_PLAN.md`。

IDプレフィクス: `MOB-`

---

## 所見一覧(サマリ)

| ID | 重要度 | 分類 | 概要 |
|---|---|---|---|
| MOB-01 | HIGH | BALANCE / BUG | 敵HPの指数スケーリングがBukkit標準の`max_health`属性上限を、フィールドモブは徒歩数分の距離で、ダンジョンボスも中位レベルで超過する。EliteMobs側には例外への防御がない |
| MOB-02 | HIGH | BUG | プレイヤー→EliteMobsモブへのダメージが、TF汎用`CombatListener`とフォークの`TrinityForgeCombatListener`の2箇所で二重に計算・二重ミティゲーションされる |
| MOB-03 | MEDIUM | BUG / BALANCE | `TrinityForgeRepairListener`のアンビル修理禁止が、修理を伴わない純粋なリネーム操作まで巻き込んでブロックする |
| MOB-04 | MEDIUM | UNIMPLEMENTED / BALANCE | `HateService`は全Mobの被弾ヘイトを記録するが、実際にターゲティングへ反映されるのはEliteMobsモブのみ。通常モブに対してはtankのヘイト倍率が完全に無意味 |
| MOB-05 | MEDIUM | UNIMPLEMENTED | フォーク`trinityforge.yml`の`dungeon-entry-gate.required-combat-level`/`message`はロードされるが、どこからも参照されない死に設定 |
| MOB-06 | LOW | COMMENT | `DungeonWorldRegistry`のJavadocが「配線は将来課題」と書いているが、実際には既に`CombatListener`から参照されている(記述が古い) |
| MOB-07 | LOW | COMMENT | `docs/integration-elitemobs-tf-stats.md`の設定例が、2026-07-25 v2改訂後の実際の`mob-import.yml`(線形→指数)と食い違っている |
| MOB-08 | LOW(要確認) | BUG | TrinityForge本体のみが再起動された場合、`DungeonWorldRegistry`の再同期がEliteMobs側のトリガー任せで行われない可能性 |
| MOB-09 | LOW(要確認) | BALANCE | `DUNGEON_SPEC.md`§6の「人数スケーリングでソロ両立」要件が、TFのHP delegation導入後も維持されているか未検証(上流EliteMobsコード依存) |

---

## MOB-01 [HIGH] [BALANCE/BUG] 敵HPの指数スケーリングがBukkit属性上限を実運用レベルで超過する

**場所**:
- `TrinityForge/src/main/resources/combat/mob-import.yml:76-77`
- `TrinityForge/src/main/resources/combat/mob-types.yml:63-74, 103-111, 132-135`
- `TrinityForge/src/main/java/com/trinityforge/listeners/MobTypeSpawnListener.java:181-198`
- `fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/mobconstructor/EliteEntity.java:374-399`

**事象**: `max-health`のランプ式は`(base + per-level*level) * growth^(level/growth-interval)`という指数式で、フィールドモブ(`mob-types.yml`)・EliteMobsダンジョン輸入モブ(`mob-import.yml`)の両方に採用されている。しかしBukkitの`Attribute.MAX_HEALTH`(vanilla `generic.max_health`)は標準で上限が存在し(要確認: 本リポジトリ内にMagmaCoreの`AttributeManager`実装がなく直接確認できていないが、vanilla Minecraftの`generic.max_health`属性はサーバ側で0〜1024の範囲を持つのが標準)、この上限を超える`base value`は少なくともBukkit標準API経路では設定できない/`LivingEntity#setHealth`が例外を投げる。

**影響/再現(具体的な数値)**:
- **フィールドモブ**: `mob-types.yml`の`ZOMBIE`は`level:0, coordinate-coefficient:0.02, max-health:380, max-health-growth:1.055`。`effectiveLevel = floor(distance * 0.02)`なので、ワールドスポーンから**約926ブロック**(徒歩数分、エリトラなら数十秒)で`effectiveLevel=19`に達し、`380 * 1.055^19 ≈ 1051`となり、既に1024を超える。設計コメント(`mob-types.yml:55-74`)は「Lv55+フィールドはエンド装備より柔らかめ」等、はるかに高いレベル帯まで見据えた指数カーブを謳っているが、実際にはLv19付近(スポーンから1km未満)で頭打ちになる。
- **EliteMobsダンジョンボス(TF HP delegation)**: `mob-import.yml`は`max-health: {base:150.0, growth:1.072}`。`150 * 1.072^L = 1024`を解くと`L≈27.6`、つまり**Lv28**で早くも上限超過。設計コメント自身が「L50≈4900, L100≈150000」を明記しており(`mob-import.yml:76`)、意図された数値の大半(Lv28〜Lv100)が到達不能。
- **防御の有無が非対称**: `MobTypeSpawnListener.applyMaxHealth()`(`:181-198`)は`entity.setHealth(value)`を`try/catch(IllegalArgumentException)`で保護し、失敗時は`attr.getValue()`(=クランプ済みの実際値)にフォールバックする実装になっている(コメントで明示的に "Never clamp to a stale getValue()" と意識している)。一方、EliteMobsフォーク側の`EliteEntity.setMaxHealth()`(TF HP delegationの適用箇所、`:389`で`livingEntity.setHealth(maxHealth)`を直接呼ぶ)には同等の`try/catch`が存在しない。`AttributeManager.setAttribute`(MagmaCore、本リポジトリ外)がBukkit標準の`AttributeInstance`経由で内部的に値をクランプするタイプであれば、続く`setHealth(maxHealth)`(クランプ前の非常に大きい値)が`IllegalArgumentException`を投げ、**モブのスポーン/全回復/フェーズリセットのたびに未捕捉例外が発生しうる**(要確認: MagmaCoreの実装次第で、NMS経由でクランプを完全にバイパスしている可能性もある)。

**根拠**:
```java
// MobTypeSpawnListener.java:191-197 (フィールドモブ側は防御あり)
attr.setBaseValue(value);
try {
    entity.setHealth(value);
} catch (IllegalArgumentException ex) {
    entity.setHealth(Math.max(1.0, Math.min(value, attr.getValue())));
}
```
```java
// EliteEntity.java:389-391 (EliteMobs側は同等の防御なし)
if (livingEntity != null) AttributeManager.setAttribute(livingEntity, "generic_max_health", maxHealth);
if (health == null) {
    if (livingEntity != null) livingEntity.setHealth(maxHealth);
```

---

## MOB-02 [HIGH] [BUG] player→eliteモブのダメージがTFの2つの経路で二重処理される

**場所**:
- `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:159-260`(特に166-176, 198, 259)
- `TrinityForge/src/main/java/com/trinityforge/combat/SymmetricCombatService.java:71-76`(`physicalFinalDamageResult`は`combatLevelOf`で再スケールする)
- `fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/api/EliteMobDamagedByPlayerEvent.java:1006-1009, 1104-1156`
- `fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/trinityforge/TrinityForgeCombatListener.java:42-52, 133-148`

**事象**: プレイヤーがEliteMobs管理下のモブ(通常のバニラEntityTypeとして存在)を近接/遠隔攻撃すると、同一の`EntityDamageByEntityEvent`が以下の順で2つの独立した経路を通る。

1. **NORMAL優先度**: EliteMobs本体の`EliteMobDamagedByPlayerEvent.EliteMobDamagedByPlayerEventFilter#onEliteMobAttacked`(`@EventHandler(ignoreCancelled = true)`=デフォルトNORMAL)がバニラ軽減修正子を全部0にし(`:1006-1009`)、自前でダメージを計算後、内部イベント`EliteMobDamagedByPlayerEvent`を発火。フォークの`TrinityForgeCombatListener#onEliteDamagedByPlayer`(内部イベントバスのHIGHEST)が同期的に`combat.physicalFinalDamageFlat()`でエリートの防御(defense-rate/resistance/flat-defense/armor-strength)を**1回目**適用し、その結果を`event.setDamage(BASE, damage)`(`:1156`)で本物のBukkit `EntityDamageByEntityEvent`に書き戻す。
2. **HIGH優先度**: TF本体の`CombatListener#onEntityDamageByEntity`が同じBukkitイベントを処理する。このメソッドには「victimがEliteMobs管理エンティティかどうか」の除外チェックが一切無く(`:166-176`の分岐は「攻撃者がプレイヤーか」だけを見る)、`vanillaBaseDamage = event.getDamage()`(`:198`)で**手順1が既にエリートの防御を適用した後の値**を読み取り、これを「素のバニラダメージ」として扱って`PlayerStatAggregator`によるステ集計・武器ステ解決を行い、`combatService.physicalFinalDamageResult(...)`(`:259`)を呼ぶ。この`physicalFinalDamageResult`は`SymmetricCombatService.java:71-76`の通り**攻撃者のcombatレベル/`physical.base`係数で再スケール**した上で、`componentResult()`内でエリートの防御(defense-rate/resistance/flat-defense/armor-strength)を**2回目**適用する。

つまりエリートの防御ステータスが二重に適用され、加えて「既に確定済みの最終ダメージ」に対してcombatレベルスケールが不要に1回余分にかかる。フォーク側の`TrinityForgeCombatListener`が`physicalFinalDamageFlat`(スケールなし、防御のみ)を意図的に選んでいる理由をJavadocが明記しているにもかかわらず(「#6: base is EliteMobs' already-finalized damage → FLAT entry point … WITHOUT re-scaling」)、TF本体の汎用リスナー側にはその除外が実装されていない。

**影響/再現(具体的な数値、概算)**: 例として素の鉄剣(`attack-power`未定義=`tfBaseReplaces=false`)でLv0エリート(`mob-import.yml`既定の`physical.resistance=0.12`, `defense-rate=0`)を攻撃した場合:
- 手順1後: 20ダメージ → 防御適用後 ≈ `20 * (1-0.12) = 17.6`。これが`event.getDamage()`に書き戻される。
- 手順2: `baseDamage ≈ 17.6`(perk/addonなし)を`physicalFinalDamageResult`に渡す → 内部で同じ`resistance=0.12`が再度掛かり `17.6 * (1-0.12) ≈ 15.49`。さらに`resolver().physicalDefaultDamage(17.6, combatLevelOf)`によるcombatレベルスケール(`physical.base`係数)が余分にもう1回乗る。

結果として、最終的にエリートへ与えられるダメージは「意図された1回分の防御適用」より一貫して低く(または`physical.base`係数が1.0を超える設定なら逆に高く)なり、値が予測不能にズレる。この不具合は`session-2026-07-24`台のメモリ(「宿題1=player→elite二重ダメージ=CONFIRMED既存バグ…修正案=fork ThreadLocalマーカ+TFスキップ2点、go/no-go保留」)で既に把握されていたが、現時点のコードにも修正は入っていない(要確認なし、`git diff`実物とTF本体ソースの両方を実読して確認済み)。

**根拠**:
```java
// CombatListener.java:166-176 (victim側のEliteMobs除外チェックが存在しない)
if (event.getEntity() instanceof Player victim) {
    LivingEntity mobAttacker = resolveMobAttacker(event);
    ...
}
Player attacker = resolveAttacker(event);
if (attacker == null) { return; }
```
```java
// CombatListener.java:198, 259 (既にエリート側で防御済みの値をvanillaBaseDamageとして再利用)
double vanillaBaseDamage = event.getDamage();
...
CombatHitResult hit = combatService.physicalFinalDamageResult(
        attacker.getUniqueId(), victim, baseDamage, weaponStats);
```
```java
// EliteMobDamagedByPlayerEvent.java:1156 (フォーク側の1回目の結果を同じイベントへ書き戻す)
event.setDamage(EntityDamageEvent.DamageModifier.BASE, damage);
```

---

## MOB-03 [MEDIUM] [BUG/BALANCE] リペア禁止がリネーム専用アンビル操作まで巻き込む

**場所**: `fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/trinityforge/TrinityForgeRepairListener.java:32-45`

**事象**: `repair-disabled`(既定`true`)有効時、`onAnvilPrepare`は「1番or2番スロットのどちらかがエリートアイテムか」だけを見て`event.setResult(null)`する。修理素材の組み合わせかどうか(2番スロットの有無・種別)を判定していないため、**エリートアイテムを1番スロットに入れて名前だけ変更する操作(修理を一切伴わない)もアンビル結果が空になる**。

**影響/再現**: プレイヤーがエリート武器/防具を単にリネームしようとすると、2番スロットが空のままでも`event.setResult(null)`が実行され、結果スロットが常に空になり、名前変更が一切できなくなる。「修繕廃止」という設計意図(耐久を有限資源にする)を大きく超えて、無関係な機能(命名)まで巻き込む。

**根拠**:
```java
ItemStack first = event.getInventory().getItem(0);
ItemStack second = event.getInventory().getItem(1);
if (!ItemTagger.isEliteItem(first) && !ItemTagger.isEliteItem(second)) return;
event.setResult(null);
```

---

## MOB-04 [MEDIUM] [UNIMPLEMENTED/BALANCE] ヘイト記録は全Mob対象だが、ターゲティング反映はEliteMobs限定

**場所**:
- `TrinityForge/src/main/java/com/trinityforge/hate/HateListener.java:38-56`
- `TrinityForge/src/main/resources/hate/rates.yml:1-8`(「hate-rate balanceは別途R2」との明記)
- `fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/trinityforge/TrinityForgeTargetingListener.java`全体

**事象**: `HateListener#onEntityDamageByEntity`は`victim instanceof Mob`であれば(EliteMobsか否かを問わず)無条件に`hateService.recordDamage(...)`を呼び、role由来の`hateThreatMultiplier`も反映される。しかしTFのソース全体を検索した限り、`HateService#topAttacker`を実際に読んでMobの索敵対象を書き換えている箇所は`fork-handoff/.../trinityforge/TrinityForgeTargetingListener`(EliteMobsの`EliteMobTargetPlayerEvent`経由)のみで、TF側には**通常の`EntityTargetLivingEntityEvent`を購読してtopAttackerへ誘導する汎用リスナーが存在しない**。

**影響/再現**: 「tank」役職(hateThreatMultiplier上昇)は、EliteMobsダンジョン内のエリートモブに対してのみ効果を持つ。フィールドの通常ゾンビ・スケルトン等(`mob-types.yml`でレベル/HPだけ刻印されているモブ)を殴っても、`HateTable`に記録される計算コスト(sweepタスクの走査対象増加含む)だけが発生し、ゲームプレイ上のターゲティング効果はゼロ。プレイヤーから見ると「tank装備なのにフィールドモブが自分を狙ってくれない」という体感の齟齬になりうる。

**根拠**:
```java
// HateListener.java:45-56
Entity victim = event.getEntity();
if (victim instanceof Mob) {
    double mult = roleBuffResolver.contributionFor(attacker).hateThreatMultiplier();
    hateService.recordDamage(victim, attacker, event.getFinalDamage() * mult);
}
```
`grep -rn "EntityTargetLivingEntityEvent" TrinityForge/src/main/java/com/trinityforge` の結果、該当イベントを購読するTF側リスナーは存在しない(フォークの`TrinityForgeTargetingListener`はEliteMobs独自イベントのみ購読)。

---

## MOB-05 [MEDIUM] [UNIMPLEMENTED] `dungeon-entry-gate.required-combat-level`/`message`は読み込まれるが未使用

**場所**: `fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/trinityforge/TrinityForgeIntegration.java:49-52, 88-91, 162-178, 412-423`

**事象**: `TrinityForgeIntegration`は`trinityforge.yml`の`dungeon-entry-gate.required-combat-level`(ダンジョンID→必要レベルのマップ)と`dungeon-entry-gate.message`を`loadConfig()`でパースし、`requiredCombatLevel(String)`/`dungeonGateMessage()`という公開アクセサまで用意している。しかし`fork-handoff/elitemobs/elitemobs-fork`配下を全文検索しても、これらのアクセサを呼び出している箇所は存在しない。実際のダンジョン入場ゲート(`TrinityForgeDungeonGateListener`、`DungeonInstance`/`DynamicDungeonInstance`からの直接呼び出し)は全て`TrinityForgeDungeonGateListener.checkDungeonEntryAllowed()`→`TrinityForge`本体の`DungeonGateService.checkEntry()`(TF側`dungeon/gates.yml`が真の設定源)を経由しており、`TrinityForgeIntegration`側のマップは完全に読み捨てられている。

**影響/再現**: 管理者が`trinityforge.yml`の`dungeon-entry-gate.required-combat-level`にダンジョンごとのレベルを設定しても、設定はパースこそされるが実際のゲート判定には一切反映されない(実効ゲートはTF本体の`dungeon/gates.yml`のみ)。ドキュメント/コメントを読んで設定した管理者が「効いているはず」と誤認する典型的なphantom configである。

**根拠**:
```java
// TrinityForgeIntegration.java:412-423 (定義とロードのみ、呼び出し元ゼロ)
public static int requiredCombatLevel(String dungeonName) { ... }
public static String dungeonGateMessage() { ... }
```
`grep -rn "requiredCombatLevel\|dungeonGateMessage" fork-handoff/elitemobs/elitemobs-fork/src/main/java/` の結果、定義・代入・getter宣言以外のcall siteは存在しない。

---

## MOB-06 [LOW] [COMMENT] `DungeonWorldRegistry`のJavadocが古い

**場所**: `TrinityForge/src/main/java/com/trinityforge/dungeon/DungeonWorldRegistry.java:13-19`、対比: `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:896-901`

**事象**: クラスJavadocに「Out of scope: このクラスはレジストリの格納/参照のみ。`isDungeonWorld`を消費する実際のリスナー/ゲートの配線(例: `dungeon-only-exp` EXPゲート)は別の将来タスクであり、意図的にここでは行っていない」と書かれているが、実際には既に`CombatListener#expAllowedInWorld`が`dungeonWorldRegistry().isDungeonWorld(...)`を呼び、`dungeon-only-exp`を実現済み。

**影響/再現**: 実害はないが、後任者がこのJavadocを読んで「まだ配線されていない」と誤解し、二重実装や誤った設計判断をする恐れがある。

**根拠**:
```java
// DungeonWorldRegistry.java:17-19
* <p><b>Out of scope:</b> this class only stores and exposes the registry. Wiring any actual
* listener/gate logic that consumes {@link #isDungeonWorld(UUID)} ... is a separate, future task
* and is intentionally not done here.
```
```java
// CombatListener.java:896-901 (実際には既に消費している)
private boolean expAllowedInWorld(World world) {
    if (!skillExp.dungeonOnlyExp()) { return true; }
    return TrinityForge.getInstance().dungeonWorldRegistry().isDungeonWorld(world.getUID());
}
```

---

## MOB-07 [LOW] [COMMENT] `docs/integration-elitemobs-tf-stats.md`の設定例が実装と食い違う

**場所**: `docs/integration-elitemobs-tf-stats.md:32-44`(最終更新2026-07-24と自己記載)、対比: `TrinityForge/src/main/resources/combat/mob-import.yml:18-93`(2026-07-25 v2改訂)

**事象**: ドキュメントの設定例は
```yaml
max-health:           { base: 20.0, per-level: 5.0 }
attack:
  attack-power:         { base: 4.0, per-level: 0.5 }
```
という**線形**ランプだが、実際に出荷されている`mob-import.yml`は2026-07-25付けで「攻撃力も指数化」に全面改訂されており、
```yaml
max-health:         { base: 150.0, per-level: 0.0, growth: 1.072, growth-interval: 1.0 }
attack:
  attack-power:         { base: 7.0, per-level: 0.0, growth: 1.03, growth-interval: 1.0 }
```
と**指数**ランプに置き換わっている。ドキュメントの改訂が追いついていない。

**影響/再現**: このドキュメントを読んで`mob-import.yml`を編集しようとする運用者が、現在の指数設計を理解せずに線形へ戻してしまう、あるいは既存の指数設計の意図(装備帯ギャップの強調)を誤解するリスクがある。EXTERNALIZE/更新方針としては、docs側もmob-import.ymlのコメント(既に十分詳細)への参照1行に短縮するか、v2設計の要点を転記するべき。

**根拠**: 上記コード引用の通り、両ファイルの`max-health`/`attack.attack-power`のランプ形状が完全に異なる。

---

## MOB-08 [LOW / 要確認] [BUG] TrinityForge単独再起動時に`DungeonWorldRegistry`が再同期されない可能性

**場所**: `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java:205`(`onEnable`内でのみ`new DungeonWorldRegistry()`)、`fork-handoff/.../trinityforge/TrinityForgeIntegration.java:102-147`(リプレイは`initialize()`、すなわちEliteMobs側の`onEnable`からしか呼ばれない)

**事象**: `DungeonWorldRegistry`は`TrinityForge#onEnable`でのみ新規生成される(`/trinityforge reload`では触られないので通常の設定リロードでは問題ない)。しかし、もしプラグイン管理ツール等でTrinityForgeプラグイン単体だけを disable/enable した場合(EliteMobs自体は再起動しない)、`dungeonWorldRegistry`は空の新インスタンスに置き換わる。既存ダンジョンワールドの再登録はEliteMobs側の`TrinityForgeIntegration.initialize()`(EliteMobsの`onEnable`からのみ呼ばれる、`EliteMobsWorld.getAllWorldUUIDs()`をリプレイする経路)に依存しているため、EliteMobsを再起動しない限り再登録のトリガーが無い。

**影響/再現**: 通常運用(サーバ全体再起動、または`/trinityforge reload`)では問題にならないが、TrinityForgeだけを個別に reload/restart する運用(pluginmanコマンド等)を行うと、既存のダンジョンインスタンスワールドが`dungeon-only-exp`等のダンジョン判定から一時的に外れる可能性がある。運用実態(そのようなツールを使っているか)が確認できていないため`(要確認)`とする。

---

## MOB-09 [LOW / 要確認] [BALANCE] 人数スケーリング要件とTF HP delegationの整合性が未検証

**場所**: `docs/DUNGEON_SPEC.md:71-77`(§6「人数スケーリング…ソロでもクリア可能なHP/火力カーブ」)、`docs/integration-elitemobs-tf-stats.md:135-136`(「healthMultiplierは残す」)、`fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/mobconstructor/EliteEntity.java:387-391`

**事象**: `DUNGEON_SPEC.md`は「EliteMobsのプレイヤー数スケーリングを使い、ソロでもクリア可能なHP/火力カーブにする」ことを設計要件としている。TF側のHP delegationは`this.maxHealth = calculatedHealth * healthMultiplier;`という形で`healthMultiplier`(EliteMobs側のper-instance変動、ミニボス/増援スケール等)を維持する設計になっている(`integration-elitemobs-tf-stats.md`にも明記)。この`healthMultiplier`にEliteMobsのプレイヤー数スケーリング(パーティ人数に応じたHP補正)が実際に含まれているかどうかは、EliteMobs本体(未改変の上流コード、本監査のスコープ外)側の実装次第であり、本タスクのスコープ(フォーク差分のみ)では確認できていない。もし`healthMultiplier`が人数スケーリングを含まない/含む経路が別に存在する場合、TFのHP delegationがそれを無効化してしまっている可能性がある。

**影響/再現**: 未検証。人数スケーリングが失われている場合、ソロプレイヤーが本来ソロ想定でないHPのボスに当たる、または逆にマルチパーティが人数分の恩恵を受けられずダンジョンが単調に難化/易化する可能性がある。上流EliteMobsコードの実読による追加確認が必要。

---

## 検証したが問題なしと判断した主な論点(参考)

- **個体ばらつき(±15%)の適用**: `ConfigManager#applyVariance`(`TrinityForge/src/main/java/com/trinityforge/config/ConfigManager.java:438-457`)は`resolveRuntimeProfile(profileId, level, rollSeed)`呼び出しのたびに必ず1回だけ適用され、`rollSeedFor`(`TrinityForgeIntegration.java:248-256`)がPDCに焼いた値を最初の呼び出し元が確定させるため、HP解決経路(`resolveProfileMaxHealth`)とスポーンスタンプ経路(`TrinityForgeSpawnListener`)のどちらが先に走っても同じ乗数になる。二重適用・適用漏れは確認されなかった。
- **`DungeonTheme.toPolicy`のmaxHealth/variance伝播**: 過去セッションで指摘されていたHIGHバグ(themeがmaxHealth/varianceをリセットしてしまう)は、現在のコード(`DungeonTheme.java:41-45`)で`base.maxHealth()`/`base.variance()`を明示的に転送する形に修正済みで、再発は確認されなかった。
- **ヘイトテーブルのメモリリーク(TF本体側)**: `HateTable`(`TrinityForge/src/main/java/com/trinityforge/hate/HateTable.java`)はグローバル/per-mob capとTTL/decay付きsweepを備え、`HateListener`が死亡/エンティティ除去/プレイヤー死亡/quit/ワールドアンロードの全経路でエビクションを行っている。リークは確認されなかった。
- **フォークのヘイト二重計上**: 過去に指摘されていた「TF稼働中もフォークのローカル`HateTable`へ二重記録される」問題は、`TrinityForgeTargetingListener.onEliteDamagedByPlayer`(`:66-78`)が`tfHateService() != null`のとき記録をスキップする形で修正済み。
- **`EliteMobDamagedByPlayerEvent`のcrit倍率撤去**: crit判定変数(`criticalHit`)自体はダメージポップアップ/スキル発動判定用に保持されたまま、ダメージ倍率(旧×1.5)だけが正しく撤去されている。デッドコード化はしていない。
