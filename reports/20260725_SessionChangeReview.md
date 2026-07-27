# 2026-07-25 セッション変更 敵対的レビュー

> **⚠️ 2026-07-27: この文書は残タスクの一次情報ではありません。**
> 残タスク・既知の問題・作業履歴は `reports/ACTIVE_RECORD.md` に集約しました。
> 本書はその日の作業記録として残していますが、記載されている「残タスク」「保留リスト」は
> 既に解決済みのものを多く含みます（棚卸しで15件が実装済みと判明）。参照しないでください。


レビュー担当: verifier (fresh context / 実装者ではない)
対象: 2026-07-25 に入った変更 A〜H（オーケストレータ提示のスコープ）
方針: **発見と報告のみ。ソースは一切修正していない。**

> ⚠ 本レビュー中、スキップガードの実効性検証とレシピ形状検証のために一時的なテストファイルを
> 2 回作成し、いずれも検証直後に削除済み。最終状態は下記「実行ログ」のとおり元通り（緑）。

---

## 0. 実行した検証（実出力）

### 0.1 Java テストスイート

```
$ ./gradlew test --offline
> Task :test
OfflineMobImportRunner > offline: convert a live custombosses tree into mob-profiles.yml via the production path SKIPPED
NativeProgressionStabilizationContractsTest > prestigeRefundUsesLiveYamlCost_characterizesMutableConfigRefund() SKIPPED
BUILD SUCCESSFUL in 27s
```

```
$ ./gradlew cleanTest test --offline   （2回実行、いずれも同一）
CLEAN-RUN   tests 1806 skipped 2 failures 0 errors 0
CLEAN-RUN-2 tests 1806 skipped 2 failures 0 errors 0
```

スキップ2件はいずれも**意図的**（`OfflineMobImportRunner` = `Assumptions` 環境変数ゲート、
`NativeProgressionStabilizationContractsTest` = `@Disabled("Pre-schema-v2: ...")`）。
MockBukkit 由来の素通りスキップは**ゼロ**。

### 0.2 config-editor JS テスト

```
$ npm test  (tools/config-editor)
ℹ tests 328  ℹ pass 328  ℹ fail 0  ℹ skipped 0
```

### 0.3 スキップガード実効性の実測（一時テストで検証 → 削除済み）

`UnimplementedOperationException` を投げるだけのテストを1件仕込んで実行:

```
> Task :test FAILED
ZzTempSkipGuardProbeTest > probeDirectUnimplemented() SKIPPED

* What went wrong:
Execution failed for task ':test'.
> Unintended test skip(s) detected (MockBukkit UnimplementedOperationException silently aborted a test
  instead of failing it — see ...\build\test-results\unintended-skips.txt):
  [engine:junit-jupiter]/[class:...ZzTempSkipGuardProbeTest]/[method:probeDirectUnimplemented()]
  :: probeDirectUnimplemented() :: aborted by
     org.mockbukkit.mockbukkit.exception.UnimplementedOperationException (verifier probe)
```

**判定: F のガードは実際に機能する。** ラップされた例外（`RuntimeException(cause=UOE)`）は
そもそも ABORTED にならず FAILED になるため、こちらもビルドは落ちる（二重に安全）。
残存する脆さは F-L1（後述）。

### 0.4 Bukkit のレシピ形状制約 実測（一時テストで検証 → 削除済み）

```
PROBE_RESULT: REJECTED java.lang.IllegalArgumentException: Crafting recipes must be rectangular
```
（paper-api 1.21.11 の `ShapedRecipe#shape("XX","XXX","XXY")` を MockBukkit 上で直接呼んだ結果）

---

## 1. 指摘一覧

| ID | 重大度 | 確信度 | ファイル:行 | 症状（1行） |
|---|---|---|---|---|
| **D-C1** | **CRITICAL** | **高** | `items/catalog.yml` 新軽装7胴 | 新規軽装の**胴7件のレシピが全て非矩形**で、Bukkit 登録時に例外→WARNログのみで無言スキップ（=作成不能） |
| **D-C2** | **CRITICAL** | **高** | `stats/item-stats.yml:1452-1935` | 新軽装の `random.phys-flat-defense.max` が `fixed` と同値（本来は `fMax-fMin` 配分）。Lv20/40/55/70/85 で最大ロール時に守備力が同Lvモブ攻撃力を上回り、**被ダメが常時 min-component-damage(=1) 床に張り付く** |
| **A-H1** | **HIGH** | **中** | `listeners/CombatListener.java:334-346` | `Player#getAttackCooldown()` を `EntityDamageByEntityEvent` 内で読んでいる。Paper は**攻撃強度タイマをダメージ前にリセット**するため、Paper 26.1.2+ では常に 0.1(素手)/0.04(剣) 固定 → **全近接ダメージが恒久的に約20%** になる |
| **A-H2** | **HIGH** | **高** | 同上 + `ComponentDamageCalculator` 経路 | `attack-power` 未定義アイテム（素手・CMD無しバニラ斧・ツルハシ等）は `vanillaBaseDamage`（**既にバニラ減衰済み**）に更に減衰を掛ける = **二重減衰**（t=0 で 0.04倍） |
| **A-H3** | **HIGH** | **中** | 同上 | Paper #11552（open）: ホットバー持ち替え直後の攻撃で `getAttackCooldown()` が 1.0 を返す → **チャージ減衰を完全回避するマクロ exploit** が成立する |
| **B-H1** | **HIGH** | **高** | `BaseStatsConfig.java:110-119` | ATTRIBUTE 5キーの意味変更に**移行措置ゼロ**。`saveResource(PATH,false)` のため既存サーバの yml は旧意味論のまま残り、静かに逆の効果になる |
| **D-H1** | **HIGH** | **高** | `resourcepack/cmd-registry.json` | **`reserved-avoid-mage-armor-regex-2026-07-25` の予約は存在しない**（台帳0件・`RESERVED_CMDS` にも無し）。ブリーフの主張が事実と異なる |
| **D-H2** | **HIGH** | **高** | `item-stats.yml` 新軽装 | 新軽装ラダー内部で数値逆転が3系統（Lv20 防具値0 < Lv10 の1 / Lv20 耐久 < Lv10 耐久 / Lv40 耐久39 << Lv30 耐久84） |
| **D-H3** | **HIGH** | **高** | `item-stats.yml:869-1030` | COPPER(Lv10) が CHAINMAIL(Lv20) を防具値・物理耐性の**両方で上回る**帯間逆転 |
| **D-H4** | **HIGH** | **中** | `item-stats.yml` 全帯 | 軽装 `phys-flat-defense` が同Lv重装と同値のうえ dodge 約3倍・移動速度プラス → **軽装が重装を全面的に支配** |
| **D-H5** | **HIGH** | **高** | `tools/config-editor/scripts/retune-armor-ladder.js` | 「the single source of truth for the numbers it writes」を自称するが、新28点も COPPER/CHAINMAIL の HEAVY 再分類も未反映。**再実行すると数値が巻き戻る** |
| **D-H6** | **HIGH** | **高** | `tools/config-editor/test/armor-ladder.test.js` | 新規28アイテムを**一切検証していない**（BANDS はバニラ素材のみ）。D-C2 も D-H2 もテストを素通り |
| **E-M1** | MEDIUM | 高 | `docs/COMBAT_SYSTEM_SPEC.md:40-44` | 仕様書 step2 に `base = max(base, 0)` が残存。実装は「意図的に0クランプしない」と明記 → **仕様書と実装が正面衝突**（負ダメ回復機能の根幹） |
| **E-M2** | MEDIUM | 高 | `docs/COMBAT_SYSTEM_SPEC.md` | melee-charge の後乗算（実質 step9）が仕様書に一切書かれていない |
| **A-M1** | MEDIUM | 高 | `CombatListener.java:339-343` | `catch (RuntimeException)` が広すぎる。`getAttackCooldown()` が将来別理由で投げても**無言で1.0**（減衰ゼロ）に落ちる |
| **A-M2** | MEDIUM | 中 | `CombatListener.java:257` | `ENTITY_SWEEP_ATTACK` にも乗算。バニラの薙ぎ払いダメージは既にチャージ込みで算出済み |
| **D-M1** | MEDIUM | 高 | `catalog.yml` 新軽装レシピ | 素材難度が use-level と釣り合わない（Lv40 = STRING×7 + GOLD_NUGGET×1、Lv20 = LEATHER×7 + COPPER_INGOT×1） |
| **D-M2** | MEDIUM | 高 | `item-stats.yml` 新軽装 | 厳選幅が既存重装（fixed の約65%）と非対称に約100%。C2 の直接原因でもある |
| **B-M1** | MEDIUM | 高 | `combat/base-stats.yml:19-20` / `tf-base-stats.js:161` | 「attack-reach に 6 と書けば実際のリーチが 6 になります(加算ではありません)」は**虚偽**。装備(+1)・light_weapons(+1,+1)等が上乗せされる |
| **B-M2** | MEDIUM | 高 | `tf-base-stats.js:38-44` | `VANILLA_ATTRIBUTE_DEFAULTS` が Java の手動ミラーなのに**パリティテストが無い**（本プロジェクトには同種のパリティテストが3本ある） |
| **B-M3** | MEDIUM | 中 | `VanillaAttributeDefaults.java:30-39` | 自己整合チェックは「ATTRIBUTEキーが実際に yml に書かれた時だけ」発火。専用テストも無く、`fail fast` の主張は条件付き |
| **C-M1** | MEDIUM | 高 | `FishingGimmickConfig.java:283-305` | `fish-sell.prices` のキー検証撤廃 + **大小文字完全一致**。旧実装は `Material.matchMaterial`(大小無視)だったため、`cod: 5` 等の既存記述が**無言で失効** |
| **C-M2** | MEDIUM | 高 | `FishSellListener.java:62` | `recentSaleTimestamps` に PlayerQuit 掃除が無い（`Map<UUID,Deque>` が退出後も残る）。E ではリーク対策したのに C では未対応 |
| **C-M3** | MEDIUM | 中 | `FishSellListener.java:94-99` | 上限が「**回数**」であって「金額」でない。`fish` グループで `amount: 64` を設定すると 10回/分で 640個分が換金される |
| **H-M1** | MEDIUM | 高 | `BaseStatsConfig.java:106-108` + `ManaBaseStats.java` | `0` が「未設定」として捨てられるため、`mana-onhit-percent: 0` 等を書いても**フォールバック(0.03)に戻る**＝マナ回復を設定で無効化できない |
| **H-M2** | MEDIUM | 高 | `tf-base-stats.js:88-95` | mana-* 10キーは lore.yml 未登録のため**エディタ画面に一切出ない**。「エディタで管理するために TF へ移設した」という移設目的と不整合 |
| **G-M1** | MEDIUM | 高 | `tools/config-editor/lib/yamlio.js:1-10` | 本文コメント消失の実影響 = **編集可能79ファイル中56ファイル / 1303行**（内訳は §2 G-M1 参照） |
| **G-M2** | MEDIUM | 高 | `test/item-stat-coverage.test.js:105,117` | `#2000\d{2}` 決め打ちは**何の緩和もされていない**（D-H1 の「予約」が存在しないため） |
| **A-M3** | MEDIUM | 中 | `CombatDamageConfig.java:118` vs `damage.yml:51` | schema 既定が 0.04 のまま、出荷 yml だけ 0.015。キー欠落時は 0.04 に戻る |
| **C-M4** | MEDIUM | 中 | `FishingGimmickConfig.java:67` vs `fishing-gimmick.yml:47` | `DEFAULT_FISH_SELL_MAX_PER_MINUTE = 20` のまま、出荷 yml だけ 10 |
| **C-M5** | MEDIUM | 低 | `FishingGimmickConfig.java:123-130` | `fish` だけ設定すると `dropTablesEmpty()` が false になり、**legacy junk-to-scrap が丸ごと無効化**される |
| **D-M3** | MEDIUM | 高 | `item-stats.yml` Lv20 LIGHT 帯 | Lv20 の LIGHT 防具が3系統併存（#200031/#200061/#200128）でステの形が全く異なる |
| **A-L1** | LOW | 高 | `stats/fishing-gimmick.yml:4` | 「fish-sell-toggle(#5修正により恒久no-op)」が**復活後も残存**する嘘コメント |
| **A-L2** | LOW | 高 | `CombatDamageConfig.java:91-92` | 「既定 false = bypass」とコメントしているが `SchemaField.of(..., true)` |
| **E-L1** | LOW | 高 | `SkillExpFeedbackService.java:179` / `TitleDisplayService.java:156` | `PlayerQuitEvent` に `ignoreCancelled = true`（`Cancellable` ではないので無意味） |
| **E-L2** | LOW | 高 | `TitleDisplayService.java:128` | javadoc「非有限値は既定 0.75 相当のフォールバック無しで単に0扱いにしない」が意味不明・実装(0.75返す)と噛み合わない |
| **F-L1** | LOW | 高 | `UnintendedSkipGuardListener.java:37,45` | マーカーパスが**テストJVMのCWD相対**。`workingDir` 変更や `maxParallelForks>1` で無言に無効化/競合する |
| **B-L1** | LOW | 高 | `combat/base-stats.yml:48-49` | 新既定が `crit-chance: 0.05`/`crit-damage: 0.5`（旧既定は `base-stats: {}`）。新規インストールと既存インストールで挙動が分岐 |
| **B-L2** | LOW | 高 | `test/base-stats-schema.test.js:14` | `"max-health": 4` を妥当例として使用（旧意味論のまま。今は「合計4HP」の意味） |
| **D-L1** | LOW | 高 | `retune-armor-ladder.js:5-7` | 「companion test が同じ TABLE/WEIGHTS を複製している」は事実と異なる（テストは目標値20/6のみ） |
| **D-L2** | LOW | 高 | `item-stats.yml` DIAMOND/NETHERITE#148 | 既存の帯間逆転: DIAMOND(Lv55) 物理耐性 0.0137 < IRON(Lv30) 0.0226 / NETHERITE#148(Lv100) 0.0161 < #150(Lv85) 0.0391 |
| **D-L3** | LOW | 高 | `item-stats.yml` GOLDEN 帯 | GOLDEN(Lv40) 耐久 46-67 < IRON(Lv30) 99-144。武器の有限耐久化で顕在化する |
| **X-L1** | LOW | 高 | — | ブリーフの `tests=1801` が再現しない。clean run は **1806**（failures 0 は一致） |

---

## 2. 各指摘の詳細と「実際に壊れるシナリオ」

### D-C1 (CRITICAL) 新規軽装の胴7件がクラフト不能

**証拠**

`catalog.yml` の shaped レシピ 161 件のうち、**非矩形なのは新規軽装の胴7件だけ**:

```
bone_guard_chestplate      ["YY","XXX","XXX"]
copper_stud_chestplate     ["XX","XXX","XXY"]
carapace_mail_chestplate   ["XX","XXX","XXY"]
gilded_thread_chestplate   ["XX","XXX","XXY"]
abyssal_scale_chestplate   ["YY","XXX","XXX"]
phantom_shroud_chestplate  ["XX","XXX","XXY"]
withered_silk_chestplate   ["XX","XXX","XXY"]
```

paper-api 1.21.11 の `ShapedRecipe#shape` は矩形を要求する（実測: §0.4 の
`IllegalArgumentException: Crafting recipes must be rectangular`）。
`CatalogRecipeRegistrar.java:267` は `recipe.shape(spec.shape().toArray(new String[0]))` に生の行をそのまま渡す。

**壊れるシナリオ**

1. サーバ起動 → `CatalogRecipeRegistrar#registerOne` が上記7件で `IllegalArgumentException`。
2. `catch (RuntimeException ex)`（197行付近）が拾って `Level.WARNING` を1行出すだけ。
3. 起動は成功し、他の154件は普通に登録される → **管理者は気づかない**。
4. プレイヤーは「銅鋲の革鎧の胴だけ作れない」状態になる。7セット全ての胴（＝最も重要な部位）が入手不能。

**なぜ既存の検証を全部すり抜けたか**
- Java 側 `RecipeSpec.validateShaped`（125-149行）は「行数 ≤ 3」「各行の長さ ≤ 3」しか見ない。矩形チェックなし。
- editor 側 `lib/schema.js` の `validateShape`（384-397行）も同様に矩形チェックなし。
- `armor-ladder.test.js` はレシピを見ない。

**推奨対応**: 胴のシェイプを `["X X","XXX","XXX"]` 形（空白でパディング）へ修正。あわせて
`RecipeSpec.validateShaped` と editor `validateShape` に矩形チェックを追加（同種再発の恒久防止）。

---

### D-C2 (CRITICAL) 新軽装の厳選上限が壊れており、最大ロールで実質無敵

**証拠**

`retune-armor-ladder.js` の設計は「fixed = fMin × 部位重み」「random.max = **(fMax − fMin)** × 部位重み」。
実際、既存重装はこの通り（CHAINMAIL_HELMET: fixed 0.96 / random.max 0.63、Lv20 の fMin 6.4 / fMax 10.6 と一致）。

ところが新軽装は **`random.phys-flat-defense.max` が `fixed` と同値**:

```yaml
LEATHER_HELMET#200128:      # Lv20
  fixed: { phys-flat-defense: 0.96, ... }
  random: { phys-flat-defense: { min: 0, max: 0.96 } }   # ← 本来 0.63
LEATHER_HELMET#200136:      # Lv40
  fixed: { phys-flat-defense: 1.82 }
  random: { phys-flat-defense: { min: 0, max: 1.82 } }
```

結果、4部位合計の理論最大守備力が **設計値 fMax ではなく fMin×2** になる:

| Lv | 設計 fMax | 実データ flatMax | A(Lv)=7×1.03^Lv | 判定 |
|---|---|---|---|---|
| 20 | 10.6 | **12.80** | 12.64 | **超過** |
| 30 | 13.6 | 13.38 | 17.06 | ぎりぎりOK |
| 40 | 19.2 | **24.22** | 22.80 | **超過** |
| 55 | 31.1 | **45.20** | 35.61 | **超過** |
| 70 | 48.8 | **73.60** | 55.42 | **超過** |
| 85 | 77.7 | **124.22** | 86.32 | **超過** |

`ComponentDamageCalculator` は step2 で `base -= flat` を無クランプで行い、step7 の
`physical.min-component-damage`（既定 **1.0**）が唯一の床。

**壊れるシナリオ**

Lv85 の「枯絹の装束」フルセットを最高品質・上振れロールで揃えたプレイヤーは、
守備力合計 124.2 に対し同Lvモブの基準攻撃力 86.3 なので step2 で base が負になり、
step7 で 1.0 に切り上げられる。**同レベル帯のモブから受けるダメージが1発あたり常に 1.0 固定**。
最大HP(20 + 装備分 ≈ 33)なので 33発耐える。設計目標「S理論値 = 20発」を大幅に超えるうえ、
より低い Lv20/40/55/70 でも同じ現象が起きる（Lv20 の時点で成立してしまう）。

さらに軽装は dodge も持つため、実質的に「同Lv帯コンテンツが無効化」される。
D-C1 で胴が作れないうちは顕在化しないが、D-C1 を直した瞬間に有効になる。

**推奨対応**: 新軽装28件の `random.phys-flat-defense.max` を
`(TABLE[lv].fMax − TABLE[lv].fMin) × 部位重み` に再計算。あわせて
`armor-ladder.test.js` の BANDS に軽装7帯を追加（D-H6）。

---

### A-H1 (HIGH / 確信度 中) `getAttackCooldown()` の読み取り位置

`CombatListener#meleeChargeMultiplier` は `EntityDamageByEntityEvent` ハンドラの中で
`attacker.getAttackCooldown()` を読む。バニラ `Player#attack` は

```
f *= 0.2F + h*h*0.8F;          // ダメージにチャージ減衰を適用
this.resetAttackStrengthTicker();   // ← ここでタイマをリセット
... target.hurt(...)                // ← Bukkit イベントはこの中で発火
```

の順で、**イベント発火時点ではタイマが既にリセット済み**。

PaperMC issue #13884（Paper 26.1.2-63、**closed as "works as intended"**）は、
`EntityDamageByEntityEvent` 内の `getAttackCooldown()` が
「素手は常に 0.1、剣は常に 0.039999995」を返すと報告している。
これは `0.5 / ticksToFullCharge`（素手 attack-speed 4.0 → 0.5/5 = 0.1、
剣 1.6 → 0.5/12.5 = 0.04）とぴったり一致し、リセット後の値であることを裏づける。

**壊れるシナリオ**

サーバを Paper 26.1.2 以降へ更新した瞬間、`t ≈ 0.04` 固定になり
`multiplier = 0.2 + 0.04² × 0.8 ≈ 0.2013`。**全プレイヤーの全近接ダメージが常時 約20%** になる。
Paper 側は「仕様」と結論しているので戻らない。

**確信度が「中」の理由**: 本プロジェクトの依存は `paper-api:1.21.11-R0.1-SNAPSHOT` であり、
#13884 が「PR #13856 以降で発生」と述べていることから、**現行の 1.21.11 では正しい値が返る可能性が高い**。
ただし更新1回で全近接が壊れる時限爆弾であり、
**実サーバで一度も目視確認されていない**（テストは Mockito で `getAttackCooldown()` をスタブしているため、
実 Bukkit の挙動を一切検証していない）。

**推奨対応**: 実サーバで「フルチャージ攻撃と連打攻撃のダメージ差」を目視確認する。
恒久的には `PlayerAnimationEvent` / `PlayerInteractEvent`（既に `onArmSwing` / `onMissSwing` がある）で
スイング直前の値をキャッシュし、ダメージイベントではそのキャッシュを読む方式が安全。

---

### A-H2 (HIGH / 確信度 高) 二重減衰

`CombatListener:243-247`:

```java
if (tfBaseReplaces) {                       // アイテムに attack-power がある
    baseDamage = adjustedWeapon + ...;      // TF独自ベース → バニラ減衰なし → 1回だけ掛かる = 正しい
} else {
    baseDamage = vanillaBaseDamage + ...;   // event.getDamage() = 既にバニラ減衰済み
}
...
total *= meleeChargeMultiplier(attacker);   // ← ここで2回目
```

`tfBaseReplaces` は `agg.item().containsKey("attack_power")`。
`item-stats.yml` で `attack-power` を持つのは 325 件中 177 件。
**素手・CMD無しのバニラ斧（`IRON_AXE` 等は未定義。定義済みは `IRON_AXE#80` のみ）・
ツルハシ・シャベル・棒・ブロック**などは全て `false` 側に落ちる。

**壊れるシナリオ**: 素手で連打すると t≈0 で `1.0（バニラ減衰済み） × 0.2 = 0.2`。
バニラなら 0.2 のところ **0.04 相当**（バニラ比 5分の1）。
バニラ鉄斧（CMD無し）でモブを連打した場合も同様。フルチャージ時は 1.0×1.0 なので差が出ず、
**「連打だけが極端に弱い」という気づきにくい形**で現れる。

**テストが素通りする理由**: `CombatListenerMeleeChargeTest` は `DIAMOND_SWORD` に
`attack-power: 1.0` を設定しており、`tfBaseReplaces=true` の経路しか通っていない。
素手経路のテストが存在しない。

---

### A-H3 (HIGH / 確信度 中) 持ち替え exploit

PaperMC issue #11552（**open**）: 攻撃速度属性を持たないアイテム → 持つアイテムへ素早く持ち替えて攻撃すると、
`getAttackCooldown()` が 1.0 を返す。報告者は明示的に
「マクロで `player.getAttackCooldown()` チェックを全て無効化できる」と警告している。

**壊れるシナリオ**: プレイヤーがマクロで「土ブロック↔剣」を高速に持ち替えながら攻撃すると、
TF は常に t=1.0 と読み、チャージ減衰が一切かからない。B2 の目的（連打を最適解にしない）が完全に破られ、
**マクロ勢だけが最大5倍のDPSを出す**。

---

### B-H1 (HIGH) ATTRIBUTE 5キーの意味変更に移行措置がない

`BaseStatsConfig#load` は `saveResource(PATH, false)`（上書きしない）なので、
**既存サーバのデータフォルダにある `combat/base-stats.yml` は旧ファイルのまま**残る。
旧出荷版（`backups/trinityforge/combat/base-stats.yml/base-stats.yml.bak-20260724-163650-651`）は
`base-stats: {}` だが、**ヘッダに使用例として `max-health: 4  # 基礎体力 +4 (ハート2つ分)` と明記されていた**。

**壊れるシナリオ**: この例に従って `max-health: 4` と書いていた既存サーバでは、
新コードが `4 − 20 = −16` を加算量として保持 → **全プレイヤーの最大HPが 4（ハート2つ）** になる。
同様に `move-speed: 0.05`（+50%のつもり）は `0.05 − 0.1 = −0.05` → **移動速度が半分**。
`attack-reach: 1`（+1ブロック）は `1 − 3 = −2` → **リーチ1ブロック**。

ファイル内にバージョンマーカーも移行検出も無いため、**警告すら出ない**。

**推奨対応**: (a) デプロイ前に実サーバの `base-stats.yml` を目視確認する、
または (b) ファイルに `schema-version` を導入し、旧版なら起動時に SEVERE を出して変換を促す。

---

### D-H1 (HIGH) 存在しない「予約」

`resourcepack/cmd-registry.json` の 372 件の allocation を全走査したが、
`reserved` / `reserved-avoid-mage-armor-regex-2026-07-25` を含むエントリは **0件**。
`tools/config-editor/lib/cmd-registry.js:27-34` の `RESERVED_CMDS` にも 200085-200099 は入っていない
（入っているのは 100011,100012 / 200001-200007 / 300001-300016 / 400001）。

**評価**: 「予約してある」という主張は**事実に反する**。ただし実害は限定的で、
`nextCmd`（265-288行）は「同一 material の既存最大値 + 1」から始めて
`globalUsed` と衝突する限り前進のみするため、**自動採番が 200085-200099 を後から埋めることは無い**。
危険なのは手動で CMD を書いた場合のみ。

**推奨対応**: 「予約が要る」と判断するなら `RESERVED_CMDS` に実際に追加する。
そうでなければ、そもそもの原因である `item-stat-coverage.test.js:105,117` の
`/^LEATHER_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)#2000\d{2}$/` を
「魔法防具の実 ID 一覧（36件を明示列挙）」へ置き換えるほうが恒久的。
どちらを採るかは**ユーザー判断**として保留する（→ §5）。

---

### D-H2 / D-H3 / D-H4 数値ラダーの逆転と支配関係

`item-stats.yml` から機械的に抽出した全109防具エントリの表（`use-skill` / `use-level-requirement` 順）から:

**D-H2 新軽装ラダー内部の逆転（今回導入）**

| 項目 | Lv10 (#200124-27) | Lv20 (#200128-31) | Lv30 (#200132-35) | Lv40 (#200136-39) |
|---|---|---|---|---|
| 防具値 兜/胴/脚/靴 | 1/2/1/1 | **0**/1/1/**0** | 1/2/2/1 | 1/2/2/1 |
| 耐久 兜/胴/脚/靴 | 89/130/122/105 | **84/122/115/99** | 84/122/115/99 | **39/57/54/46** |

- Lv20 の兜・靴の `armor-defense-rate` が **0**。素の `LEATHER_HELMET`(Lv0) ですら 1 なので、
  **Lv20装備が Lv0装備よりバニラ防具値で劣る**。
- Lv20 の耐久が Lv10 より低い（89→84 等）。
- Lv40 の耐久が Lv30 の **半分以下**（84→39）。GOLDEN 防具のバニラ耐久をそのまま流用した副作用と見られる。

**壊れるシナリオ**: プレイヤーが Lv10 の「骨守の革鎧」から Lv20 の「銅鋲の革鎧」へ乗り換えると、
物理守備力は上がるが**防具値と耐久は下がる**。Lv30 → Lv40 では耐久が半減し、
「上位装備なのにすぐ壊れる」という体験になる。

**D-H3 重装ラダーの帯間逆転（今回の再分類で顕在化）**

| | COPPER (Lv10) | CHAINMAIL (Lv20) |
|---|---|---|
| 防具値 兜/胴/脚/靴 | **2/4/3/2** | 1/2/2/1 |
| 物理耐性 兜/胴/脚/靴 | **0.0137/0.0364/0.0273/0.0137** | 0.007/0.0186/0.014/0.007 |

Lv10 の銅が Lv20 のチェーンを**両方の指標で上回る**。
（`retune-armor-ladder.js` は両方を `weight: "light"` として計算した名残であり、
HEAVY へ再分類したのに数値を再計算していない = D-H5 と同根）

**D-H4 軽装が重装を支配**

Lv30 で比較（IRON=重装 vs #200132-35=軽装）:

| | IRON (重装) | #200132-35 (軽装) |
|---|---|---|
| phys-flat-defense 合計 | 6.69 | **6.69（同値）** |
| phys-resistance 合計 | 0.1279 | **0.1279（同値）** |
| max-health 合計 | +10 | **+10（同値）** |
| dodge 合計 | 0.030 | **0.100（3.3倍）** |
| move-speed 合計 | −0.008 | **+0.010** |
| 防具値 合計(バニラ込) | 29 | 13 |

`vanilla-armor.defense-rate-per-point` が 0.04 → **0.015** に下がったことで、
防具値差 16 点の価値は 24% 軽減差に縮んだ一方、dodge 差 +7% は「完全回避」なので
実効的にはより強い。**「重装の唯一の優位が最も価値の低いステータス」**という構図になっている。
ブリーフの「同値という設計」は設計判断だが、`defense-rate-per-point` の同時引き下げにより
バランスが軽装側へ大きく振れている点は認識されていないと思われる。

---

### D-H5 生成スクリプトの陳腐化

`retune-armor-ladder.js:5-7` は自らを
「This script is the single source of truth for the numbers it writes」と宣言するが:

- `PHYS_GROUPS`（61-73行）に新規28点（#200124-200151）が**存在しない**。
- COPPER(Lv10) / CHAINMAIL(Lv20) は `weight: "light"` のままで、
  `flipSkillTo: "HEAVY_ARMOR"` は DIAMOND(Lv55) にしか付いていない。

**壊れるシナリオ**: 将来「ラダーを再調整したい」と誰かが `node scripts/retune-armor-ladder.js` を実行すると、
COPPER/CHAINMAIL の数値が light プロファイルへ巻き戻り、新規28点は**一切更新されずに取り残される**
（結果として D-C2 の不整合も温存される）。memory の
「generator152行stale・孤児CMD再生成不可」と同じ罠の再発。

---

### E-M1 仕様書と実装の正面衝突（0クランプ）

`docs/COMBAT_SYSTEM_SPEC.md` step2:

```
2. 守備力 : flat = 守備力[該当type]（防具強度は含まない）
            base -= flat
            base = max(base, 0)     ← ここでの0クランプは「0未満」にのみ効く。
```

`ComponentDamageCalculator.java:48-57`:

```java
//    この減算で base は負になり得るが、ここでは意図的に0クランプしない。負の base は
//    そのまま次段(会心/%軽減)を素通りし、step7 の minClamp まで届く。これは
//    physical.min-component-damage を負値に運用する構成(#6 Part B: ...)を成立させるために必須で
double flat = defense.flatDefense();
base -= flat;
```

**壊れるシナリオ**: 将来この仕様書を根拠に誰か（人でもエージェントでも）が
step2 の 0 クランプを「実装漏れ」として追加すると、**出荷済みの「負ダメージ＝回復」機能が黙って死ぬ**。
まさにブリーフが「ゼロクランプを入れてはいけない」と警告している事故が、
仕様書側から誘発される状態になっている。

---

### G-M1 コメント消失の実影響範囲（実測）

`lib/registry.js` の79エントリを `tool-config.json` の `basePaths` で解決し、
先頭ヘッダブロック以降に現れるコメント行 + 行末インラインコメント行を数えた結果:

```
編集可能ファイルのうち本文/インラインコメントを持つもの: 56 / 79
失われうるコメント行 合計: 1303 行
```

上位:

| 行数 | ファイル |
|---|---|
| 599 | `arspaper/glyphs.yml` |
| 73 | `trinityforge/stats/fishing-gimmick.yml` |
| 50 | `trinityforge/skilltree/ars_smithing.yml` |
| 48 | `arspaper/spellbooks.yml` |
| 41 | `trinityforge/skilltree/smithing.yml` |
| 38 | `arspaper/sourcelinks.yml` |
| 37 | `trinityforge/skilltree/alchemy.yml` |
| 33 | `trinityforge/skilltree/ars_magic.yml` |
| 30 | `trinityforge/gacha.yml` / `skills/base/smithing_progression.yml` |
| 26 | `trinityforge/stats/skill-exp.yml` |

**壊れるシナリオ**: 管理者が config-editor で `stats/fishing-gimmick.yml` を1箇所だけ変更して保存すると、
「`fish:` 未設定の理由（バニラ再抽選になる旨）を説明した 30行のコメント」や
「exploit対策としての `max-sells-per-minute` の根拠」など **73行が一度に消える**。
バックアップからは復元できるが、**バックアップ生成後の編集を差し戻す形になる**ため
「値の変更を保ちつつコメントだけ戻す」手作業が必要になる。
`arspaper/glyphs.yml` は 599 行と桁違いで、一度保存すると事実上復旧不能。

---

### H-M1 mana ベース値を 0 に設定できない

`BaseStatsConfig#load:106-108` は `raw == 0.0` を無条件で `continue` する。
`statOrDefault(key, fallback)` は「キーが無ければ fallback」。
`ManaBaseStats` は fallback に**移設前の ArsPaper 既定値**を持つ。

**壊れるシナリオ**: 管理者が「被弾時マナ回復を無くしたい」と考えて
`base-stats.yml` に `mana-onhit-percent: 0` と書く。
→ ロード時に 0 が捨てられる → `manaBaseStat("mana-onhit-percent", 0.03)` が **0.03 を返す** →
**設定したのに 3% のまま**。ログにも何も出ない。
`mana-regen-base: 0`（自然回復を止めたい）も同様に 5 に戻る。

---

## 3. 問題なしと確認した観点（網羅性の証明）

以下は実際に読み・実行したうえで**問題を検出しなかった**もの。

| 観点 | 確認内容 |
|---|---|
| **A: 矢消費（弓）** | `onEntityShootBow` はブロック時のみ `setConsumeArrow(false)` + `setConsumeItem(false)`。非ブロック時は一切触らないため矢の複製経路なし。`RangedUseRequirementConsumptionTest` に「ブロック時消費しない」「許可時は従来どおり消費」の両方がある |
| **A: 矢消費（クロスボウ）** | `EntityLoadCrossbowEvent` でのゲートは装填時消費という Paper の実挙動に正しく合致。shoot 側も defense-in-depth として残置。テスト4件あり |
| **A: トライデント複製** | `restoreBlockedTrident` は「メインハンドに `isSimilar` な残存がある場合は何もしない」。トライデントは最大スタック1でオフハンド投擲時もメインハンドが別アイテムになるため、二重付与の成立シナリオを構成できなかった。テスト2件（返却/非重複）あり |
| **A: 負ダメージ回復** | `ComponentDamageCalculator` に途中クランプが無いことを step1〜8 全段について確認。`fixedDamage` は step8 で無条件加算、step7 の床のみが唯一のクランプ |
| **A: ArmorStand 除外** | `DamagePopupDisplay:67` / `FocusHpDisplay:190,204` の両方で除外済み。`FocusHpDisplay#pruneInvalidTargets` は追跡済みエンティティのみを扱うので追加除外は不要。DPSChecker のダミーは Zombie（`TrainingDummies` javadoc）なので巻き込みなし |
| **B: 加算レイヤとの合流** | `PerkAttributeApplier:152-159` は ATTRIBUTE チャネルのみを `attrs` へ merge、`PlayerStatAggregator:183-192` は item マップ側へ。二重計上なし。負の addend も `AttributeModifier.ADD_NUMBER` としてそのまま流れ、下流に破綻なし |
| **B: リロード二重変換** | 変換は `load()` 内で `yaml` から読んだ生値に対してのみ行い、`this.stats` を再入力にしない。`/tf reload` を何度打っても再変換は起きない |
| **B: PercentStatNormalize との順序** | ATTRIBUTE 5キーはいずれも `RATE_KEYS` に含まれないため `coerce` は恒等。減算→正規化の順序による事故なし |
| **B: 変換テスト** | `BaseStatsConfigTest` に「既定超→正の addend」「既定と同値→破棄」「既定未満→負の addend」「0→未記載扱い」「max-health 40 → +20」「move-speed 0.2 → +0.1」「knockback-resistance は既定0なので素通し」「非ATTRIBUTEキーは変換しない」の8件。カバレッジ十分 |
| **C: 釣り後方互換** | `fish` 未設定時は `replaceWithDropTable` の `NORMAL_FISH` 分岐で即 return（宝フラグPDCも書かない）。テスト `normalFishOutcomeLeavesCatchUntouchedWhenFishGroupIsUnset` あり |
| **C: junk-to-scrap の波及** | `applyJunkToScrap = GROUP_JUNK.equals(groupId)` で `fish` を明示除外。テスト `normalFishOutcomeIsNeverScrapSwappedByJunkToScrapEvenWhenActive` あり |
| **C: 宝フラグ** | `fish` 経路は `treasureFlag=false` を書き込む。テストあり |
| **C: 売却 exploit の骨格** | ①`deposit` 成功時のみ `caught.remove()`（失敗時はアイテム維持）②インベントリを経由せず `Item` エンティティを直接除去 ③1分窓の回数上限。3点とも実装・テスト（6件）とも健在。過去の恒久no-op化の再発条件は再導入されていない |
| **C: 価格解決順** | PDC `catalog_id` 優先 → Material 名フォールバック。`Optional.or` の使い方も正しい。テスト3件 |
| **E: 称号表示の消去規律** | 死亡/リスポーン/ワールド移動/テレポート/ログアウト/シャットダウンの6経路すべてで `despawn`。`sweepOrphans` + 非永続 + PDCタグも健在。今回の変更（オフセットのconfig化）は `spawn` 内の1値だけで、規律に触れていない |
| **E: ボスバーのリーク** | `onQuit` でタイマー cancel + BossBar hide + Map エントリ削除。`hideOne` も skillId 単位で両マップから除去。`evictOldestIfAtCapacity` も hide + 両マップ除去。**リークは検出できなかった**。リスナー登録も `TrinityForge.java:265` で確認 |
| **E: 純粋関数の保存** | `progressRatio(int,int,double,double)` / `isMilestoneLevel(int,int)` はシグネチャ・分岐（maxLevel→1.0 / span<=0→1.0 / clamp / `titleEvery>0 && level>0 && 倍数`）とも変更なし |
| **F: スキップガード** | §0.3 のとおり**実測でビルドが落ちることを確認**。意図的スキップ（`@Disabled` / `Assumptions`）は落とさないことも既存2件で確認 |
| **F: fail-close ゲートテスト** | `FailCloseGateSkillTreePlacementTest` は対象idを config から**動的に列挙**しており、ハードコードなし。fail-open 種別（recipe/ritual/glyph/drop）を対象外にした判断も正しい。16本のスキルツリー名は固定配列だが `SkillTreeConfig.DIR` 配下の実ファイルと一致 |
| **G: バックアップ退避先** | `backupDir` 既定 `../../backups` はリポジトリルート直下 = `src/main/resources` の外なので jar に同梱されない。`COPYFILE_EXCL` + 連番リトライで同秒衝突も安全。`backupKeep <= 0` は「無制限」＝削除しない安全側 |
| **G: 編集フォームの値保全** | `tf-base-stats.js` は `working["base-stats"]` を**その場編集**するため、画面に出ないキー（mana-* 等）が保存で消えることはない（→ 表示されない問題は H-M2 として別途指摘） |
| **G: labels.js のカバレッジ** | `lore.yml` の 87 ステータス**全件**にラベルあり（欠落0）。14文字上限・`<ドメイン>:<説明>` 形式もテストで担保 |
| **JS テスト** | 328件全緑、skipped 0 |

---

## 4. ワーカー主張の監査

| 主張 | 判定 | 根拠 |
|---|---|---|
| `tests=1801 skipped=2 failures=0 errors=0` で安定 | **部分的に不正確** | clean run は **1806**（2回とも同値で安定）。failures/errors 0 と skipped 2 は一致。1801 は古いXMLに基づく数値と思われる |
| スキップガードがビルドを落とす | **正** | §0.3 で実測確認 |
| CMD 200085-200099 を予約してある | **誤（虚偽）** | 台帳・`RESERVED_CMDS` とも該当エントリ0件（D-H1） |
| `retune-armor-ladder.js` が数値の single source of truth | **誤（陳腐化）** | 新28点・HEAVY再分類とも未反映（D-H5） |
| `armor-ladder.test.js` は「データの本物の回帰チェック」 | **部分的に誤** | 重装ラダーには当てはまるが、今回の主成果物である軽装28点を1件も検証していない（D-H6） |
| 新規軽装28セット×4部位を追加した | **正（データは存在）** | catalog 28件・item-stats 28件・cmd台帳 28件すべて整合。ただし胴7件がクラフト不能（D-C1） |
| `fish` 未設定ならバニラ釣果を一切変更しない | **正** | コード・テストとも確認 |
| `fish-sell` の exploit 対策3点が健在 | **正** | 実装・テストとも確認（ただし C-M2/C-M3 の穴あり） |
| ボスバーのリークを潰した | **正** | quit/FIFO/タイマーすべて確認、リーク未検出 |
| 称号の消去規律を壊していない | **正** | 6経路すべて健在 |
| `tf-base-stats.js` の「別要素で」コメント不整合を修正した | **正** | 148-171行で実際に `.field-unit` の別 span になっている |

---

## 5. 判断に迷った点（勝手に決めず報告）

1. **A-H1 の対処方針** — 現行 Paper 1.21.11 では正常に動く可能性が高い。
   (a) 実サーバで目視確認して問題なければ現状維持＋Paper更新時の要再確認としてメモ、
   (b) 今のうちにスイング時キャッシュ方式へ作り替える、のどちらを採るか。
   **実サーバ確認は私にはできない**ため、ここで止める。

2. **A-H2 の直し方** — 「素手/バニラ武器はバニラ側の減衰で十分」として
   `if (tfBaseReplaces)` 条件を追加するのが最小修正だが、
   「TF の減衰カーブ（config可変）を素手にも効かせたい」意図なら
   逆に `vanillaBaseDamage` を減衰前へ復元する必要があり、設計判断が変わる。

3. **D-H4（軽装＝重装の守備力）** — 「同値」は明示された設計判断なので数値を勝手に否定しない。
   ただし `defense-rate-per-point` を 0.04→0.015 にした結果、
   重装の唯一の優位（防具値）が大幅に減価している点は同時変更の副作用として要判断。
   dodge 差を縮める / 軽装の phys-flat を係数掛けする / 重装に固有の別軸を足す、のいずれか。

4. **D-H1 の恒久対策** — `RESERVED_CMDS` に 200085-200099 を実際に足すのか、
   `item-stat-coverage.test.js` の正規表現を実IDの明示列挙へ置き換えるのか。
   後者のほうが「CMD帯に意味を持たせる」設計依存を減らせるが、テストの変更範囲が広がる。

5. **D-M1（素材難度）** — 「軽装は素材が軽い（＝早期に揃うが伸びない）」という設計なのか、
   単なる調整漏れなのか判断材料が無い。特に Lv40 の `STRING×7 + GOLD_NUGGET×1` は
   同帯の GOLDEN 重装（金インゴット8個）と比べて桁違いに安い。

6. **G-M1（コメント消失）** — 影響が 1303 行と大きい。
   `yaml` パッケージは Document API（`YAML.parseDocument` + ノード単位編集）でコメント保持が可能なので
   「全面リライトではなく差分適用」への作り替えが技術的には可能。
   ただし editor の保存経路全体（3-way merge を含む）に波及するため、独立タスク化の判断が要る。

---

## 6. 検証していない範囲（明示）

- **実サーバでの動作確認は一切していない**（`D:/game/minecraft/PaperServer/` 配下は指示により不可）。
  A-H1/A-H3 は Paper の issue と仕様に基づく推論であり、実測ではない。
- **jar のビルド／デプロイは行っていない**。よって「配備済みか」は未確認。
- `fork-handoff/` 配下は**読み取りのみ**。ArsPaper / EliteMobs のフォーク側変更
  （`materials.yml`, `items.yml`, `SpellCaster`, `EliteEntity` 等）は
  H セクションで指定された `functional-items.yml` 周辺と mana 移設のみを確認し、
  それ以外（`ManaManager` / `ManaRecoveryListener` / `SourceBerry` / `UnifiedRecipeLoader` 等の実装差分）は
  **未レビュー**。
- 本日変更された270ファイルのうち、ブリーフ A〜H に含まれない早朝〜午前の変更
  （`active/*` アクティブスキル基盤、`mobs/MobLevelBandTable` 系、`skilltree/effects/TierTable`、
  `MiningGimmickPolicy` / `TreeFellingListener` / `VeinMiningListener` の採取系リワーク、
  `progression/SettingsGui` 等）は**スコープ外として未レビュー**。
- resourcepack のモデル/テクスチャ（`assets/**`）の整合性は未確認。
  新規28点は `color` 染色前提でカスタムモデルを持たない想定だが、
  リソパ生成側が CMD 200124-200151 に対して何かを出力しようとしないかは**未検証**。
- `tools/config-editor/server.js` の HTTP 経路（認証・パストラバーサル等）は
  今回の変更点（バックアップ退避先・世代数）以外を見ていない。
- パフォーマンス（`FishSellListener` が釣果ごとに `aggregator.aggregate()` を回す等）は
  正しさの観点のみで見ており、負荷測定はしていない。
