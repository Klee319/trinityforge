# 監査レポート 06: ArsPaperフォーク差分・DPScheckerフォーク・TF統合レイヤ

- 担当領域: ArsPaperフォークの我々の改変分 / DPScheckerフォーク / TF側統合レイヤ・公開API
- IDプレフィクス: `INT-`
- 監査日: 2026-07-25（監査のみ、コード修正は一切行っていない）

## 対象確認

- ArsPaperフォーク: `fork-handoff/arspaper/fork/` — `git log --oneline`（baseline `4c78f08` 以降 6コミット）、
  `git diff 4c78f08..HEAD --stat`（56ファイル変更）、`git status --short`（未コミット差分 約90ファイル、
  防具システム撤去・触媒/魔導書ステ再設計・スレッド枠拡張等の大改修中）を確認し、変更ファイルを実読した。
- DPScheckerフォーク: `fork-handoff/dpschecker/fork/src/` 全体（gitなし、自作コード）を実読した。
- TF統合レイヤ: `TrinityForge/src/main/java/com/trinityforge/integration/`（`ars/ArsNativeBridge.java`,
  `ars/ArsProgressionBridge.java`, `TrainingDummies.java`）、`bridge/` は**存在しない**（ディレクトリなし。
  統合窓口はフォーク側の `TrinityForgeBridge.java` 2本と、TF公開API `TrinityForge.java` インスタンスメソッド群に
  集約されている）。
- ドキュメント: `docs/ADDON_INTEGRATION_SPEC.md`, `docs/ARS_MAGIC_SCALAR_SOURCES.md`,
  `docs/MAGIC_BALANCE_SPEC.md`, `fork-handoff/arspaper/ARSPAPER_FORK_SPEC.md`,
  `fork-handoff/dpschecker/DPSCHECKER_FORK_SPEC.md` を通読。
- **APIバージョン照合手法**: `javap -classpath libs/TrinityForge.jar -public <class>` で同梱jarの実際のpublicシグネチャを
  抽出し、フォークの呼び出し箇所・現在の TF ソース（`grep -n "public "`）と3方向で突き合わせた。

## 総評（重要度目安の前提）

同梱 `libs/TrinityForge.jar`（両フォーク同一・2026-07-25 16:59 ビルド）を `javap` で実際に検査した結果、
フォークが参照する全クラス（`TrinityForge`, `SymmetricCombatService`, `WeaponAttackStatResolver`,
`PlayerStatAggregator`, `CraftQualityService`, `DedicatedEffectsConfig`, `ItemStatsConfig`,
`CombatDamageConfig`, `DefenseStatBridge`, `MobData`, `VanillaArmorMapping`, `ItemData`, `BindType`,
`OwnerBindPolicy`, `CatalogRecipeRegistrar`, `RecipeSpec`, `AddonCombatStats`, `PdcKeys`, `StatKeys`,
`MaterialLists`, `MaterialTier`, `StatRange`, `ArsNativeBridge`, `BaseStatsConfig`, `DefenseStats`,
`AttackStats`, `CombatHitResult`, `DamageType`, `DefenseStatKeys` 等）で、**メソッドシグネチャの不整合は1件も
検出されなかった**。フォーク側のコード品質（fail-open設計・try/catch(Throwable)徹底）も非常に高い。
一方で、TF側の主力ファイル `TrinityForge.java` の最終更新時刻（18:19）が同梱jarのビルド時刻（16:59）より
**約80分新しい**（INT-09参照）ため、「今この瞬間は整合しているが、ビルド運用に構造的なタイムラグがある」状態。
最大の実害はAPI不整合ではなく、**DPSchecker表示専用GUIの実装がTF本体式の意味論とズレている点**（INT-01）だった。

---

## 所見一覧

| ID | 重要度 | 分類 | 場所 |
|---|---|---|---|
| INT-01 | HIGH | BUG | `fork-handoff/dpschecker/fork/src/main/java/com/github/klee319/dpschecker/dummy/TfDefenseStat.java` |
| INT-02 | HIGH | COMMENT | `fork-handoff/dpschecker/DPSCHECKER_FORK_SPEC.md` |
| INT-03 | MEDIUM | UNIMPLEMENTED | `fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/SpellBindListener.java` |
| INT-04 | MEDIUM | BUG | `fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/SpellCaster.java:396-400,257-273` |
| INT-05 | MEDIUM | BUG(要確認) | `TrinityForge/src/main/java/com/trinityforge/combat/DefenseStats.java:142-149` / `ComponentDamageCalculator.java:63` |
| INT-06 | LOW | UNIMPLEMENTED(要確認) | `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java:981-1009` |
| INT-07 | LOW | DEADCODE | `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java:1113-1124` |
| INT-08 | LOW | REDUNDANCY | `fork-handoff/dpschecker/fork/src/main/java/com/github/klee319/dpschecker/dummy/DummyDefenseProfile.java:81-94` |
| INT-09 | LOW | BUG(プロセスリスク) | `fork-handoff/arspaper/fork/libs/TrinityForge.jar` / `fork-handoff/dpschecker/fork/libs/TrinityForge.jar` |
| INT-10 | LOW | COMMENT | `docs/ARS_MAGIC_SCALAR_SOURCES.md` |
| INT-11 | LOW | DEADCODE(意図的) | `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java:805-811` |

---

### INT-01（HIGH／BUG）: DPSchecker「TF防御」GUIが防具強度(armorStrength)を誤った値域/刻みで編集させる

**場所**: `fork-handoff/dpschecker/fork/src/main/java/com/github/klee319/dpschecker/dummy/TfDefenseStat.java:13-20`,
`fork-handoff/dpschecker/fork/src/main/java/com/github/klee319/dpschecker/gui/TfDefenseGUI.java:76-77,182`

**事象**: TFの実際の戦闘式では `armorStrength`（防具強度）は **[0,1] の会心軽減率**（既定 `max-crit-reduction: 1`
でのみ [0,1] にクランプ）だが、DPScheckerのGUIは `percent()=false`（flat系）として扱っており、値域
`[0, 1000]`・刻み `±1/10/100/1000` の「桁ボタン」で編集させる。

**根拠**（TF本体の実装、`TrinityForge/src/main/java/com/trinityforge/combat/`）:
```java
// ComponentDamageCalculator.java:63
base *= (1 + attack.critDamage() * (1 - Math.min(1.0, defense.armorStrength())));
// DefenseStats.java:142-149 cappedCritReduction(): Math.min(armorStrength, cap) — cap は clamp01 済み
```
DPSchecker側:
```java
// TfDefenseStat.java:13-20
FLAT_DEFENSE("守備力", false),
ARMOR_STRENGTH("防具強度", false),   // ← percent=false のため max()=1000.0, step()=1.0
// TfDefenseGUI.java:77
noItalic(Component.text("flat系(守備力/防具強度)は ±1/10/100/1000", ...))
```

**影響/再現**: GUIで「防具強度」を選択し `+1` ボタンを1回押すだけで値が `1.0` になり、TFパイプライン側では
既にこの時点で会心ボーナスを100%相殺する最大効果に到達する（`cappedCritReduction`のcap既定値が1.0のため）。
それ以降 `+10`・`+100`・`+1000` を押しても表示上は `11.0`→`111.0`→`1111.0` と増え続けるが、実戦闘計算上の効果は
**1.0の時と完全に同一**（`Math.min(armorStrength, 1.0)` で頭打ち）。GUIの「範囲: 0.0〜1000.0」表示（`formatValue`）も
実効範囲と無関係な誤情報になる。DPS計測用ダミーで「防具強度」を細かく調整して火力曲線を検証したい運用者は、
0〜1の間を刻めず（最小刻みが1.0のため 0.1, 0.25, 0.5 等を設定する手段が無い）、実質「0」か「常時カンスト」の
2値しか作れない。DPSCHECKER_FORK_SPEC.md の目的（「実戦と同じTFステータス計算で火力検証できるようにする」）を
直接損なう。

**根本原因**: `TfDefenseStat` の7項目のうち `percent` フラグは `DEFENSE_RATE/PHYS_RESISTANCE/MAGIC_RESISTANCE/
DAMAGE_REDUCTION/DODGE_CHANCE` の5つが `true`（正しく%系）、`FLAT_DEFENSE/ARMOR_STRENGTH` の2つが `false`
（flat系）に分類されている。`FLAT_DEFENSE`（守備力、真のflat値・上限なし）は正しいが、`ARMOR_STRENGTH`
（防具強度）は同じグループに誤って括られている。DummyDefenseProfile.java 自身のJavadoc（`armorStrength(防具強度)
is a CRIT-REDUCTION RATE [0,1]`）とTfDefenseStat.javaの`percent=false`設定が同一クラス内で矛盾している。

---

### INT-02（HIGH／COMMENT）: DPSCHECKER_FORK_SPEC.mdの「armorStrengthはflat減算(step6)」記述が実装と正反対

**場所**: `fork-handoff/dpschecker/DPSCHECKER_FORK_SPEC.md:26-31`

**事象**: 仕様書は次のように明記している:
```
armorStrength(防具強度)は flat 減算(step 6: flat = 守備力 + 防具強度、
攻撃側の固定ダメージで相殺可)。旧「会心ダメ軽減率」仕様は廃止(§2.1 復元)。
```
しかし実際のTFコード（`ComponentDamageCalculator.java:63`、`DefenseStats.java:21-23`）は
「防具強度 = 会心軽減率%、`base *= (1 + critDamage*(1-armorStrength))` で会心の増加分だけを軽減」という、
仕様書が「廃止された」と明言している**その旧仕様のまま**動いている。step6でのflat減算を担っているのは
`flatDefense`（守備力）だけで、`守備力+防具強度` の合算という記述にあたる実装は存在しない
（`ComponentDamageCalculator.java:47` `double flat = defense.flatDefense();` のみ、`armorStrength`はここに現れない）。

一方、DPSchecker・ArsPaper両フォークのJavaコード側コメント（`TfDefenseStat.java`, `DummyDefenseProfile.java`,
`fork-handoff/dpschecker/fork/.../integration/TrinityForgeBridge.java:44-50`）は全て「armorStrengthは会心軽減率」
という**現行実装と一致する**記述になっている。つまりJavaコードはTF本体と整合しているが、
Markdown仕様書だけが乖離している。

**影響/再現**: 将来、この`.md`仕様書を正として「実装をspecに合わせよう」という修正が入ると、
現在正しく動いているコード（INT-01のバグを除けば整合済み）を壊す方向の変更を招く。あるいは逆に、
INT-01のバグ調査時にこの仕様書を読むと「flat化されているはず」という誤った前提で再現手順を組んでしまう。
`(要確認)`: どちらが「意図された最終仕様」なのか（会心軽減率のまま据え置きが正、または将来flat化する予定で
仕様書が先行しているだけ）は本レポートの範囲では判断できない。ただし現時点でコードとドキュメントが完全に
矛盾している事実は確定している。

---

### INT-03（MEDIUM／UNIMPLEMENTED）: 使用ゲート(α)「作成時ブロック」が未実装、β「発動時不発」のみで運用されている

**場所**: `fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/SpellBindListener.java`（バインド生成部
`bindSpell` 全体）、`fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/UsageGate.java`

**事象**: `ARSPAPER_FORK_SPEC.md` §1「使用ゲート α/β」は次のように明記している:
> (α) 作成時ブロック（**主**） | `block.impl.ScribingTable`（scribe）＋ `spell.SpellRecipe` / `spell.SpellRegistry` |
> スペル組成時、含まれる各glyphの perk所持を…確認。未許可があれば**組み込み不可（バインド拒否）**
> (β) 発動時不発（**保険**） | `spell.SpellCaster`（cast本体）… | cast時に未許可glyphを含むスペルは不発

実装を確認すると、`UsageGate.hasPermission()` / `SpellCaster.firstMissingPerkGlyph()` の呼び出しは
`SpellCaster.cast()`（β、詠唱成功パス）にのみ存在する。`SpellBindListener.bindSpell()`
（アイテムへのスペル組み込み＝バインド生成）は `canBind()`（アイテム種別チェック）と触媒の
`max-bind-tier` チェックのみを行い、**perk所持チェックを一切行っていない**。`ScribingTableGui.java` も
`hasGlyphPermission`/`hasPermission` を呼んでいない（`grep -rn "hasGlyphPermission\|firstMissingPerkGlyph"`
がヒットするのは `UsageGate.java`/`SpellCaster.java` の定義・呼び出し元1箇所のみ）。

**影響/再現**: プレイヤーは perk未所持のグリフを含むスペルでも自由にバインド（組み込み）でき、
バインド完了メッセージ・Lore表示（`スペル: <名前>`）が正常に出る。実際にダメージ等の効果が発動するのは
β（cast時）でブロックされるため、**ゲーム的な抜け道（無許可グリフの効果発動）は無い**（βが最終防衛線として
機能している）ためHIGHではなくMEDIUM。ただし仕様書が「主」と位置付けた経路が丸ごと欠落しており、
将来βの判定にバグが入った場合の二重防御が失われている。UXとしても「バインドは通るのに発動しない」という
分かりにくい失敗モードになる。

---

### INT-04（MEDIUM／BUG）: 触媒/魔導書別クールダウンのMapエントリがログアウト時にクリアされない

**場所**: `fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/SpellCaster.java:257-273,396-400`

**事象**: `SpellCaster.cast()` は3種類のキー空間でクールダウンを `cooldowns`（`ConcurrentHashMap<String,Long>`）
に記録する:
```java
String cooldownKey = caster.getUniqueId() + ":" + formKey;                                   // line 229
catalystCooldownKey = "catalyst:" + catalystData.id() + ":" + caster.getUniqueId();           // line 259
bookCooldownKey = "book:" + bookTierData.getItemId() + ":" + caster.getUniqueId();            // line 268
```
一方、ログアウト時にプレイヤーのCTを掃除する `clearCooldown()` は次の実装:
```java
public void clearCooldown(UUID playerId) {
    String prefix = playerId + ":";
    cooldowns.keySet().removeIf(key -> key.startsWith(prefix));   // line 397-398
```

**影響/再現**: `cooldownKey`（`"<uuid>:<formKey>"`）は `prefix`（`"<uuid>:"`）で始まるため正しく除去されるが、
`catalystCooldownKey`（`"catalyst:<id>:<uuid>"`）と `bookCooldownKey`（`"book:<id>:<uuid>"`）は
`"catalyst:"`/`"book:"` で始まり `<uuid>:` では始まらないため、`removeIf` の条件にマッチせず**ログアウト後も
Mapに残り続ける**。実害はゲームプレイには出ない（CT判定は `now - lastCast < cooldownMs` の時刻比較のみで、
古いエントリが残っていても実時間経過後は自然に無効化される）が、`ManaManager.onPlayerQuit()` から呼ばれる
`clearCooldown` が本来意図した「ログアウト時にプレイヤー関連の状態を掃除する」目的を満たせておらず、
触媒/魔導書の種類×延べプレイヤー数に比例してMapエントリが増加し続ける（サーバ再起動まで解放されない）
軽微なメモリリークになっている。

---

### INT-05（MEDIUM／BUG・要確認）: `armorStrength`の二重クランプ（configクランプ＋ハードコード1.0）は冗長かつ設定の意味を弱める

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/ComponentDamageCalculator.java:63`,
`TrinityForge/src/main/java/com/trinityforge/combat/SymmetricCombatService.java:285-289`,
`TrinityForge/src/main/java/com/trinityforge/combat/DefenseStats.java:142-149`

**事象**: `armorStrength` は `SymmetricCombatService.clampedDefense()` 内で既に
`cappedCritReduction(damageConfig.maxCritReduction())`（`combat/damage.yml` の `defense.max-crit-reduction`、
既定値`1`）によって `[0, maxCritReduction]` にクランプされている。にもかかわらず、
`ComponentDamageCalculator.compute()` はさらに独立して `Math.min(1.0, defense.armorStrength())` を適用する。

**影響/再現**: 現状は `max-crit-reduction` の既定値が `1` なので実害は出ないが、運営が
`defense.max-crit-reduction` を将来 `1.0` より大きい値（例えば防具強度スタッキングで会心ダメージを
"逆に増幅させる" 実験的設定、DefenseStats.javaのJavadocが示唆する負のarmorStrength運用の対称ケース）に
設定しようとしても、`ComponentDamageCalculator`側のハードコード`Math.min(1.0, ...)`が常に効いてしまい、
config側の意図（`cappedCritReduction`のJavadocが言う「デフォルト1.0は上限なしを意味する」という説明）と
矛盾する。二重クランプ自体はDPSchecker/ArsPaperフォークの管轄外（TF本体の設計）だが、
**DPSchecker側GUI(INT-01)の「実効上限はTF combat/damage.yml のdefense.*に従う」という案内文の正確性**に
直接影響するため、統合レイヤの観点で報告する。`(要確認)`: どちらのクランプが「最終防衛」として意図されたものかは
コード上のコメントだけでは判別できない。

---

### INT-06（LOW／UNIMPLEMENTED・要確認）: 触媒(catalysts.yml)のitem-stats動的登録がオフハンド適用を常に無効化する

**場所**: `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java:981-1009`
（`registerCatalystStats`）

**事象**: 触媒ステの動的登録呼び出しは常に `offhandApplies=false` を渡している:
```java
tf.config().itemStats().registerDynamic(
    CATALYST_NAMESPACE, material, cmd,
    fixed != null ? fixed : Map.of(),
    perQuality != null ? perQuality : Map.of(),
    tfRandom, null, false   // ← 最後の引数が固定 false
);
```
`ItemStatsConfig.offhandStatsApply(material, cmd)`（TF側）はこの登録済み `ItemStatProfile.offhandApplies`
を参照する。`catalysts.yml`/`CatalystConfig.java` を確認したが `offhand-stats-apply` に相当する設定キーは
存在しない（`grep -n "offhand" item/CatalystConfig.java` はヒット無し）。

**影響/再現**: 触媒をオフハンドに装備した場合、`ArmorManaListener.recalculateArmorBonus()` の
`offhandStatsApplies(offhand)` 判定が常に `false` を返すため、その触媒のマナ系item-stats
（`mana_bonus`/`mana_regen`等）はオフハンド経由では絶対に加算されない。触媒がメインハンド専用の設計
（ワンド/スペルブックは常に利き手で構える想定）であれば意図通りだが、二刀流的なオフハンド触媒運用を
将来サポートする構想があるなら、この固定`false`が障害になる。現状のゲームデザイン上の意図が
本レポート範囲では確認できないため`(要確認)`としてLOWで報告する。

---

### INT-07（LOW／DEADCODE）: `EFFECT_GLYPH_SLOT_PLUS` は自己申告のとおり永久に0を返す死んだゲートキー

**場所**: `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java:1113-1129`

**根拠**（コード自身のコメントで死んでいることが明記されている）:
```java
* 定義されていないため有効な変換先が無い" という理由で意図的に未解決のまま残している(TF側コード中に
* 同旨のSTOPコメントあり)。{@link com.trinityforge.skilltree.effects.GateEffectId#parse} は
* コロン無し・{@code ars-tier}以外の生idを受理しないため、このキーでの
* {@code dedicatedEffects.valueSum} は常に0を返す(死んでいるが、TF側の未確定仕様に追随して
* fork側もそのまま維持。指示なくstat等へ変換しない)。
*/
public static final String EFFECT_GLYPH_SLOT_PLUS = "glyph-slot-plus";
```
TF側で `grep -rn "glyph-slot-plus\|GLYPH_SLOT_PLUS" TrinityForge/src/main/java` を実行しても**一致なし**
（`GateEffectId`にこのキーへのハンドラが存在しない）ことを確認した。`tfGlyphSlotBonus(Player)`
（同ファイル1348-1351行）は `tfEffectValue(player, EFFECT_GLYPH_SLOT_PLUS)`（常に0）と
`tfNativeArsDouble(player, "glyphSlots")`（native側、こちらは生きている）を加算しているため、
実害は無い（native経路が実質的な供給源として機能している）。ブリーフの「既にreportsで修正済みと記録されて
いても残っていれば報告する」方針に従い、明示的に死んでいる定数として記録する。削除するかTF側で
`GateEffectId`にゲート種別を追加するか、いずれかの意思決定待ちの状態が続いている。

---

### INT-08（LOW／REDUNDANCY）: DummyDefenseProfileのPDC書込みで`tf_magic_*`系4キーが書くだけで一度も読まれない

**場所**: `fork-handoff/dpschecker/fork/src/main/java/com/github/klee319/dpschecker/dummy/DummyDefenseProfile.java:81-94`
(`writePdc`) と `:58-79`(`fromPdc`)

**事象**: `writePdc()`は共通フィールド（defenseRate/damageReduction/flatDefense）を
`tf_phys_*`系キーと`tf_magic_*`系キー（`tf_magic_def_rate`, `tf_magic_dmg_red`, `tf_magic_flat_def`）の
**両方**に書き込んでいる（コメント曰く「古い読み手互換のため」）。しかし`fromPdc()`の読み込みロジックは
`tf_phys_def_rate`/`tf_phys_dmg_red`/`tf_phys_flat_def`のみを読み、`tf_magic_def_rate`等は**一度も参照しない**
（`physResistance`/`magicResistance`は個別に読むが、共通フィールド3種はphys系キーのみが真実）。

**影響/再現**: ゲームプレイへの影響は無い（値は完全な複製で、読み込み側が一貫してphys系を優先している）。
ただし毎回のPDC書込みで4個の冗長なキー（`tf_magic_def_rate`/`tf_magic_dmg_red`/`tf_magic_flat_def`、
及び対応する読み取り不在）が発生しており、「古い読み手互換」を謳うコメントが指す実際の読み手が
コードベース中に存在しない（LD-13統一以前の仕様の残骸である可能性が高い）。整理対象。

---

### INT-09（LOW／BUG・プロセスリスク）: 同梱`libs/TrinityForge.jar`のビルド時刻がTF本体ソースの最終更新より古い

**場所**: `fork-handoff/arspaper/fork/libs/TrinityForge.jar`, `fork-handoff/dpschecker/fork/libs/TrinityForge.jar`
（両方とも同一バイナリ、mtime `2026-07-25 16:59:24`）／`TrinityForge/src/main/java/com/trinityforge/TrinityForge.java`
（mtime `2026-07-25 18:19:16`、jarビルドの**約80分後**に変更）

**事象**: `javap`での実検査（本レポート冒頭「総評」参照）では現時点のAPI不整合はゼロだったが、
これは結果論であり、jarのビルドタイムスタンプとTF本体ソースの最終更新タイムスタンプの間に構造的なズレが
存在する事実は変わらない。両フォークとも `compileOnly` で `libs/TrinityForge.jar` を参照しているため
（`ArsPaper fork/build.gradle.kts`、`DPSchecker fork build.gradle.kts` 確認）、TF側で今後
publicメソッドのシグネチャ変更・削除が入った場合、**jarを再生成してフォークへ配布し直すまで
気づかれない**（コンパイルは古いシグネチャで通り、実行時に`NoSuchMethodError`となる）。

**根拠**: `fork-handoff/arspaper/fork/` の直近コミットメッセージ自体が
`chore: libs/TrinityForge.jar 更新(魔法scale切替既定true・射撃武器保持修正・item-stats検証)`
であり、TF本体変更→jar再生成が手動フローであることを示している。`docs/`に記録されている過去メモ
（`tf-elitemobs-api-jar-refresh`と同種の運用注意）と同じ構造的リスクがArsPaper/DPSchecker側にも存在する。
現状は実害なしのため LOW とするが、CIやビルドスクリプトでの自動同期・ハッシュ突合の仕組みが無いままだと
将来的な回帰が起きやすい。

---

### INT-10（LOW／COMMENT）: `docs/ARS_MAGIC_SCALAR_SOURCES.md`が「未解決」と記した課題は既にTF側`ArsNativeBridge`で解決済み

**場所**: `docs/ARS_MAGIC_SCALAR_SOURCES.md:105-111`（「Explicitly avoid: Raw getMaxMana/getManaRegenRate…
装備由来ボーナスが混入するため生値は使うな」という2026-06-29時点の分析）

**事象**: このドキュメントは「ArsPaperのマナ最大値/回復速度の生値には装備由来ボーナスが混ざるため、
戦闘スコアのmagic pillarには使うな」と結論しているが、現在のコードでは
`TrinityForge/src/main/java/com/trinityforge/integration/ars/ArsNativeBridge.java`（`maxManaBonus`/`manaRegenBonus`、
`PerkBuffResolver`の`general()`チャネルのみを見る設計で装備分と意図的に分離済み）と、
ArsPaper側`SpellCaster`/`ManaManager`/`ManaBaseStats`が`combat/base-stats.yml`の`mana-max-base`等を
唯一のソースとして参照する構成（ArsPaper commit `6b9e66a feat: 触媒ステ連携・ARS_MAGIC EXP…厳選/マナ回復を実装`
以降）へと既に置き換わっており、ドキュメントが指摘した「クリーンなgetterが無い」問題は解消されている。

**影響/再現**: 実害なし（ドキュメントが古いだけ）。ただし本ドキュメントの「Open questions」節
（`ARS_MAGIC`スキルの実在確認、breadth vs power等）は未だ有効な論点も含むため、全体を陳腐化扱いにはできない。
該当セクションのみ現状追記または削除が望ましい。

---

### INT-11（LOW／DEADCODE・意図的）: `rollQualityWithOffset`の旧API後方互換フォールバックは現行jarでは到達不能

**場所**: `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java:805-811`

```java
private static int rollQualityWithOffset(CraftQualityService svc, Player crafter, ItemStack item) {
    try {
        return svc.rollArsSmithingQuality(crafter, item);
    } catch (NoSuchMethodError legacyTf) {
        return svc.rollArsSmithingQuality(crafter);
    }
}
```

**事象**: `javap`で確認した同梱jarの`CraftQualityService`には既に
`rollArsSmithingQuality(Player, ItemStack)`（新API、品質基準値オフセット対応）が存在するため、
`catch (NoSuchMethodError)`分岐は**現在のjarの下では到達しない**。設計意図（異なるバージョンのTF jarとの
互換性を保つ防御的コード）自体は妥当であり、削除を推奨する類のdeadcodeではない。参考情報として記録するのみ。

---

## 確認したが問題なしと判断した項目（メモ）

- **魔法ダメージのハイブリッド計算**（`ArsPaper fork TrinityForgeBridge#magicalFinalDamage` →
  `SymmetricCombatService#magicalFinalDamageResult`）: `spellBase`（グリフ基礎ダメージ、増強/減衰グリフを
  内包済み）＋触媒`attack-power`の加算、触媒の会心/貫通等は別レイヤー(`AttackStats`)供給という
  `MAGIC_BALANCE_SPEC.md §2`の層分離が実装と一致していることを`HarmEffect.java`/`SpellContext.java`で確認した。
  二重計上は検出されなかった。
- **負の最終魔法ダメージ→回復変換**（`SpellContext.dealSpellDamage`）: TF物理側`CombatListener`との対称実装
  （`healEntity`）が正しく配線されていることを確認した。
- **マナ回復の自己ループ防止**（`ManaRecoveryListener`/`ArmorManaListener`）: MAGIC原因ダメージを
  近接命中系マナ回復の対象から明示的に除外しており、「詠唱→自ダメージ回復→再詠唱」という無限ループの芽を
  正しく塞いでいることを確認した。
- **`source-auto-consume`のexploit対策**（`SpellCaster.java:305-334`、`ManaManager.consumeMana`）:
  Source補填分をキャンセル時のマナ返還から除外するロジックがあり、「不足分だけSource変換→詠唱を自己
  キャンセル→マナ全額返還」でSourceをマナへ実質無限変換するexploitが遮断されていることを確認した。
- **DPSchecker側 base fixes**（BossBar 1ヒットずれ補正、再起動後ゴースト化対策、undead時の仮想REGENERATION）:
  `DummyEntity.java`/`DamageListener.java`で仕様書どおりの実装を確認した。
