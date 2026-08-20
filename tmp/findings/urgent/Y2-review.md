# Y2 (U17: エンチャントテーブルで treasure 系が出る) 敵対的レビュー

- レビュー対象: `work/y2-enchant-pool-u17` / `4e1ba6e`（親 `aa97412`）
- レビュー worktree: `.claude/worktrees/wf_01789c6c-3ea-2`
- **判定: ACCEPT_WITH_NOTES**（HIGH 指摘なし。修正は正しく、テストは実際にバグを捕まえる）

## 0. 自分で実走して裏を取った事実

報告のコピペではなく、レビュアーが本セッションで実行した結果のみを記載する。

| 検証 | 結果 |
|---|---|
| `git diff --stat aa97412..work/y2-enchant-pool-u17` | 3 ファイルのみ（listener / test / tmp findings）。他セッションの WIP 巻き込みなし |
| 対象クラス単体（`--rerun-tasks` で強制再実行） | `tests="15" skipped="0" failures="0" errors="0"` |
| 全体テスト | `2988 tests completed, 3 failed, 2 skipped` |
| 失敗3件の内訳 | `LegacyValhallaRuntimeContentTest` 1 / `SpellBreakMarkerDriftTest` 2 — いずれも「フォークのソースが worktree に無い」ことによる。エラーメッセージで確認済み（`fork-handoff/arspaper/fork/.../SpellBreakMarker.java` not found） |
| skipped 2件の内訳 | `OfflineMobImportRunner`（env ゲート） / `NativeProgressionStabilizationContractsTest`。**U17 とは無関係** |
| `unintended-skips.txt` | 未生成（`find build -name unintended-skips.txt` が空） |
| **ミューテーション検証（レビュアー自身が実施）** | `isTreasure()` フィルタを `if (true)` に潰して再実行 → `enchantingTablePoolExcludesTreasureEnchants`（`minecraft:mending` 混入）と `extraEnchantNeverGrantsTreasureEnchantAcrossManySeeds`（`minecraft:vanishing_curse` 付与）の**2件が実際に FAILED**。検証後 `git checkout --` で復元し worktree clean を確認 |
| `compileJava` が UP-TO-DATE でなく実走 | → `io.papermc.paper.registry.keys.tags.EnchantmentTagKeys.IN_ENCHANTING_TABLE` / `Registry#hasTag` / `Registry#getTagValues` が paper-api 1.21.11 に**実在することがコンパイル成功で裏取り済み** |

**結論: 「テストが実質何も縛っていない」系の問題はない。** 報告されたミューテーション結果は再現した。

## 1. 指示した設計からの逸脱 — なし（確認済み）

- **config キーの追加なし。** 差分に yml は 1 本も含まれない。`EnchantLuckConfig` も未変更。指示「設定で緩められるようにはしない」を遵守。
- **`rollSeed` の引き直しなし。** `pickCompatibleEnchant` の第4引数には呼び出し側のフィールド `random` がそのまま渡っている（`EnchantLuckListener.java:114-115`）。旧実装もフィールド `random` を内部で使っていたので、**乱数の消費列は完全に同一**。新しい `Random` の生成も再シードもない。
- **タグを第一経路にした**（指示どおり）。ハードコードのブラックリストに逃げていない。
- **`isDiscoverable()` を使わなかった判断は正しい。** javadoc は「テーブルで見つかるか」だが実体は `on_random_loot` 相当で修繕が `true`。ここを踏んでいたら無言で素通りしていた。
- treasure が 6 件でなく **7 件**（`wind_burst` 追加）という訂正も妥当。

## 2. MEDIUM 指摘

### M1. `canEnchantItem` が `supported_items` を見るので「テーブルでは出ない組み合わせ」がまだ残る（同一症状の再発リスク）

`EnchantLuckListener.java:139`（`!candidate.canEnchantItem(item)`）

母集団はタグで絞ったが、**アイテム側の適合判定は `canEnchantItem` のまま**。Bukkit の
`canEnchantItem` は vanilla の `supported_items` にマップされる一方、**バニラのエンチャント
テーブルが実際に提示するのは `primary_items`** のほう。両者が食い違うエンチャントが存在する。

代表例が **Thorns（棘の鎧）**: `primary_items` = チェストプレートのみ、`supported_items` = 防具全般。
つまりバニラのテーブルでヘルメット／レギンス／ブーツに Thorns が付くことは**ない**が、
本修正後も TF の追加抽選では付き得る。ユーザーから見た症状は U17 と同じ
（「バニラで出ないエンチャントが出る」）なので、**U17 が再報告される余地が残っている**。

重要なのは、**この穴は塞げる**という点: 本セッションで paper-api 1.21.11 を `javap` した結果、

```
public abstract io.papermc.paper.registry.set.RegistryKeySet<org.bukkit.inventory.ItemType> getSupportedItems();
public abstract io.papermc.paper.registry.set.RegistryKeySet<org.bukkit.inventory.ItemType> getPrimaryItems();
```

と **`getPrimaryItems()` が実在する**。`canEnchantItem` の代わりに（または追加で）
`getPrimaryItems()` に対象アイテムの `ItemType` が含まれるかを見れば完全一致になる。

ただし本件は**実装担当の逸脱ではない**（引き継ぎの確定設計が `in_enchanting_table` タグのみを
指示していた）。設計側の穴。MockBukkit が `getPrimaryItems()` を実装しているかは未確認なので、
着手するならテスト可否の確認から。

### M2. 本番で実際に通る経路（タグ経路）には自動テストが 1 本も無い

`EnchantLuckListener.java:150-159` / `EnchantLuckListenerTest.java`（`poolKeys()` の注記）

MockBukkit 4.110.0 が `hasTag`/`getTagValues` 未実装のため、テスト中の `enchantingTablePool()` は
**必ず `isTreasure()` フォールバック側**を通る。つまり緑になっている 2 本の回帰テストが縛っているのは
フォールバック経路であって、**実サーバが通るタグ経路の正しさは 1 行も検証されていない**。

実装担当はこれを正直に javadoc とテストコメントに明記しており、隠蔽ではない。また
バニラでは `#minecraft:in_enchanting_table` = `#minecraft:non_treasure` なので両経路は
意味的に一致するはず＝リスクは低い。とはいえ**「タグが空/別物を返しても誰も気づかない」構造**
なので、U17 をクローズする前に**実サーバで 1 回だけ実地確認**（enchant_luck を盛って
剣を数十回エンチャントし修繕が出ないこと）を推奨。

### M3. 同じ罠を持つ姉妹経路が未修正のまま残っている（U17 スコープ外の別バグ）

`TrinityForge/src/main/java/com/trinityforge/listeners/FishingGimmickListener.java:243-248`

```java
for (org.bukkit.enchantments.Enchantment candidate : org.bukkit.Registry.ENCHANTMENT) {
    if (isRemovedEnchant(candidate)) { continue; }
    pool.add(candidate);
}
```

釣りで引いた**空のエンチャント本**に中身を入れる処理が、修正前の `EnchantLuckListener` と
**まったく同じ「レジストリ全走査」**をしている。除外は `removed-vanilla-items`（既定 `ANY:MENDING`）だけ。

バニラの釣り宝の `enchant_randomly` は `#minecraft:on_random_loot` タグから引くので、
**束縛の呪い / 消滅の呪い / 魂の速さ / 忍び歩き / 風の衝撃**は釣りでは出ない。現状の TF はこれらを
釣り本として出し得る。しかも `chosen.getMaxLevel()` まで振るので上限レベルで出る。

- U17（エンチャントテーブル）とは**別の報告・別の経路**なので、本ブランチで直さなかったこと自体は妥当。
- ただし引き継ぎの「他3経路は反証済み」に**この経路は含まれていなかった**（見落とし）。
- 直すときのタグは `in_enchanting_table` では**なく** `#minecraft:on_random_loot`（釣り宝には修繕が出るのが正しい）。`enchantingTablePool()` をそのまま流用してはいけない。
- **別タスクとして起票を推奨。**

### M4. `ACTIVE_RECORD.md` / `common-traps.md` 未追記のため U17 が形式上クローズされていない

実装担当が choke file の並列衝突を避けて意図的に見送った判断は**妥当**（CLAUDE.md の並列ルールどおり）。
ただし CLAUDE.md「進め方の型」4 は追記して閉じることを要求しているので、**統合担当が必ず実施すること**。
追記すべき内容は 2 件:

1. **`isDiscoverable()` は信用できない**（javadoc は「エンチャントテーブルで見つかるか」だが修繕が `true`。実体は `on_random_loot`）。テーブル抽選の母集団は `#minecraft:in_enchanting_table` タグで引く。
2. **`Enchantment` 定数をテストの static フィールドに置くと検証が消える**（static 初期化が `MockBukkit.mock()` より先に走り、registry が返す別インスタンスになる。`equals`/`hashCode` が同一性ベースなので `contains` が永久に false ＝ `assertFalse` が常に緑）。比較は必ず `getKey().toString()` で行う。これは実装担当が実際に踏んで気づいた罠で、**再発防止価値が高い**。

## 3. LOW 指摘

### L1. タグ経路の失敗が完全に無言（`EnchantLuckListener.java:158`）

```java
} catch (RuntimeException | LinkageError ignored) {
```

fail-open していない点は正しい（そこは高く評価できる）。ただし実サーバでタグ経路が想定外に
失敗してフォールバックへ落ちた場合、**ログが 1 行も出ないので永久に気づけない**。
`isTreasure()` はバニラでは等価なので実害は小さいが、`WARN` を 1 回だけ出す価値はある。

### L2. `isTreasure()` は deprecated **for removal**

`@SuppressWarnings("deprecation")` で抑止しているが、Paper が実際に削除するとビルドが落ちる。
フォールバック経路なので、削除時は素直に消せる設計にはなっている（情報として記録）。

### L3. 追加テストは 9 件でなく 10 件

`aa97412` 時点の `@Test` は 5 件、現在 15 件。報告の「9 件追加」は誤り。実害なし。

### L4. `tmp/findings/` に関する報告の事実誤認

報告は「過去にどのレーンも `tmp/findings/` を commit していない」としているが、
`git log --all -- tmp/findings` の結果、**同じ波の姉妹レーンが既に commit している**:

- `8cbfb6b` (U4 グラインドストーン)
- `3af1f4f` (U9 サトウキビ)
- `4e1ba6e` (本ブランチ)

つまり `git add -f` は本波の慣行と**一致**しており、逸脱ではない。申告自体は誠実だが前提が誤り。
機密なし・解析テキストのみで public repo に出て困る内容もない。**そのままで問題ない。**

### L5. `treasureEnchantWouldHaveBeenApplicableUnderOldFilters` だけ静的定数を直接使っている

`EnchantLuckListenerTest.java` の当該テストは `Enchantment.MENDING.canEnchantItem(sword)` と
実装担当自身が戒めた静的定数を使っている。ただし**肯定側アサーション**なので、
インスタンス不一致が起きれば緑のまま素通りするのではなく**落ちて気づける**。許容範囲。

## 4. 明示的に「問題なし」と確認した項目

- **他セッションの WIP 汚染なし。** `catalog.yml` / `item-stats.yml` / `lore.yml` / `resourcepack/` / `ops/reports/` はいずれも commit に含まれない（`git show --stat 4e1ba6e` で確認）。`ops/reports/*.md` を `git checkout --` で戻したという申告も差分と整合。
- **`git add -A` 相当の巻き込みなし。** 3 ファイルちょうど。
- **黙ったゲームバランス変更なし。** 母集団が縮んだ結果、通常エンチャントの相対出現率は上がるが、これは修正の意図どおりの帰結。既存アイテムに付いている修繕は剥がれない（マイグレーション不要）。プレイヤーが失うのは「テーブル＋enchant_luck で修繕を無料入手する」経路だけで、これがまさに U17 の是正対象。
- **他の EnchantItemEvent 経路の再確認（引き継ぎの反証を追試）**:
  - `OverEnchantListener.java:45` — `enchants.replaceAll(...)` なので**キーを追加できない**。レベルのみ。反証は正しい。
  - `EnchantCostReductionListener` — コスト/オファーのみ。
  - `NativeSkillExperienceListener:463` — EXP 付与のみ。
- **公開 API 面の変更なし**（`private` → package-private static のみ）。`TrinityForge.java` の配線変更が不要という判断は正しい。
- **jar のみの配備で直る**という申告は正しい（yml 差分ゼロ）。ただし稼働中の差し替えは `NoClassDefFoundError` になるので**サーバ停止後に配備**。

## 5. 統合担当への申し送り（優先度順）

1. **M4**: `ACTIVE_RECORD.md` に U17 のクローズを追記（取り消し線＋根拠）、`common-traps.md` に上記 2 件の罠を追記。
2. **M2**: 実サーバで 1 回だけ実地確認してから U17 を完全クローズ。
3. **M3**: 釣りエンチャント本の全走査を**別タスクとして起票**（`on_random_loot` タグで絞る）。
4. **M1**: `getPrimaryItems()` による厳密化を検討タスクとして起票（Thorns 等の残存ケース）。
