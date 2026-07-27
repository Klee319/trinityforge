# 監査レポート 03: 採取系・生活系・経済系

- 担当領域: `mining/`, `digging/`, `farming/`, `fishing/`, `food/`, `woodcutting/`, `smithing/`, `gacha/`, `economy/` 配下のJava、および関連listeners、`economy/`・`gacha.yml`・`items/`・gimmick系configのyml
- IDプレフィクス: `GTH-`
- 総所見数: 14 (HIGH 4 / MEDIUM 6 / LOW 4)

読了ファイル一覧(実読):
- `TrinityForge/src/main/java/com/trinityforge/mining/{MiningGimmickPolicy,VeinMiningAlgorithm,HasteActiveSkill}.java`
- `TrinityForge/src/main/java/com/trinityforge/digging/DiggingDurabilityExpPolicy.java`
- `TrinityForge/src/main/java/com/trinityforge/farming/{AreaHarvestPolicy,DropAdjustment,FarmingCropCatalog,AnimalDamagePolicy}.java`
- `TrinityForge/src/main/java/com/trinityforge/fishing/{FishingGimmickPolicy,XpBottlePolicy}.java`
- `TrinityForge/src/main/java/com/trinityforge/food/FoodGimmickPolicy.java`
- `TrinityForge/src/main/java/com/trinityforge/woodcutting/WoodcuttingMaterials.java`
- `TrinityForge/src/main/java/com/trinityforge/smithing/FurnaceSmeltPolicy.java`
- `TrinityForge/src/main/java/com/trinityforge/gacha/{GachaDraw,GachaEntry,GachaPool,GachaRateUp,GachaTicket}.java`
- `TrinityForge/src/main/java/com/trinityforge/economy/EconomyBridge.java`
- `TrinityForge/src/main/java/com/trinityforge/listeners/{VeinMiningListener,MiningGimmickListener,MiningFortuneListener,PlacedBlockTracker,FarmingHarvestListener,BreedingBonusListener,BeekeepingListener,PlantedCropGrowthListener,AnimalDamageListener,FishingGimmickListener,FishingQualityListener,FishSellListener,XpBottleListener,FoodGimmickListener,TreeFellingListener,GatheringExtraDropListener,FurnaceSmeltListener,GachaListener,DiggingGimmickListener,DiggingDurabilityExpListener,PickupQualityListener,CraftQualityListener,PotionQualityListener,PotionMergeListener,BrewOwnership,BrewUnlockListener,GrindstonePreserveListener,WoodRepairListener,VillagerTradeListener}.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSurvivalPerkListener.java`(EXPイベント競合の裏取りのため)
- `TrinityForge/src/main/resources/{gacha.yml, economy/villager-trades.yml, stats/mining-gimmick.yml, stats/fishing-gimmick.yml, stats/food-gimmick.yml, stats/digging-gimmick.yml, stats/farming-gimmick.yml, stats/woodcutting-gimmick.yml}`
- `TrinityForge/docs/design/2026-07-25-gather-rework-active-framework.md`

---

## GTH-01 [HIGH][BUG/BALANCE(exploit)] MiningFortuneListenerに設置ブロック除外がなく、クラフト可能ブロックの周回で資源が無限増殖する

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/MiningFortuneListener.java:56-109`、config `TrinityForge/src/main/resources/stats/mining-gimmick.yml:76-105`

**事象**: `mining-fortune`の追加ドロップは`fortune-blocks`(鉱石+GLOWSTONE/GILDED_BLACKSTONE/NETHER_WART/作物4種)に該当するブロックなら誰でも(手渡しGameMode.SURVIVALでさえあれば)対象になるが、`PlacedBlockTracker.isPlaced`によるプレイヤー設置ブロック除外が一切行われていない。同じ「追加ドロップ」系統の兄弟実装である`VeinMiningListener#onBlockBreakDropTables`・`TreeFellingListener#onBlockBreakDropTables`・`DiggingGimmickListener#onBlockBreak`・`GatheringExtraDropListener#onBlockBreak`は全て`placedBlockTracker.isPlaced(block)`で設置ブロックを明示的に除外しており(`TreeFellingListener`のコメントには「原木設置→破壊のリンゴ量産等の無限ループ対策」と明記)、`MiningFortuneListener`だけがこの対策を欠いている。

**影響/再現**: `GLOWSTONE`は`fortune-blocks`に含まれ(`mining-gimmick.yml:96`)、かつバニラのクラフトレシピ(グロウストーンダスト4個→ブロック1個)が生きている(`items/catalog.yml`等に削除記録なし)。グロウストーンブロックはツール不問でバニラ平均2-4個(≒3個)のダスト+`expectedExtraRate`分のTFボーナスドロップを産む。プレイヤーは
1. ダスト4個でブロックをクラフト
2. サバイバルで素手/任意ツールで破壊(バニラ~3個 + TFボーナス)
3. 得たダストで再度クラフト
を繰り返すだけで、`mining-fortune`合計期待値が損益分岐(概ね+1個/破壊)を超えた時点からダストが無限に増殖する。`MiningFortuneListener`はシルクタッチのみを除外しており、ツール種別も不問なため実行コストはほぼゼロ。

**根拠**:
```java
// MiningFortuneListener.java:61-73 (抜粋)
if (!gathering.fortuneBlocks().contains(event.getBlockState().getType())) return;
ItemStack tool = player.getInventory().getItemInMainHand();
if (hasSilkTouch(tool)) { return; }
```
```yaml
# mining-gimmick.yml:96 (fortune-blocks)
    - GLOWSTONE
```
placedBlockTrackerへの参照がファイル全体に存在しない(grep該当なし、対して`VeinMiningListener`等7ファイルには存在)。

---

## GTH-02 [HIGH][BUG/BALANCE(exploit)] VeinMiningListenerの連鎖破壊トリガー自体にも設置ブロック除外とクールダウンが無い

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/VeinMiningListener.java:76-97`(`onBlockBreak`)、`:121-145`(`handleVeinMining`)。対比: `:100-112`(`onBlockBreakDropTables`、こちらは`placedBlockTracker.isPlaced`あり)

**事象**: `vein-mining`のチェーン破壊本体(`onBlockBreak`→`handleVeinMining`)は、同ファイル内でボーナスドロップテーブル用に実装されている`placedBlockTracker.isPlaced`除外を一切参照しない。さらに`TreeFellingListener`が実装しているプレイヤー毎クールダウン(`lastFellMillis`, 既定10秒)と同等の仕組みが`VeinMiningListener`には存在しない。

**影響/再現**: シルクタッチで入手した鉱石ブロックを最大32個(`max-extra-blocks`)まで並べて設置し、フォーチュン付きツールで先頭1個を殴ると、GTH-01と同じ理屈でその場にある同種ブロックが連鎖破壊されて全てフォーチュン適用ドロップになる。クールダウンが無いため直後に次の32個グリッドへ移動して即再トリガーでき、同じ「本来はシルクタッチ(温存)かフォーチュン(増量)のどちらか一方しか選べない」制約を、1回シルクタッチで温存→再設置→フォーチュンで割ることで二重取りできる。ダイヤモンド/エメラルド/古代のがれき等の希少資源で特に価値が大きい。

**根拠**:
```java
// VeinMiningListener.java:76-97 (onBlockBreak、チェーン破壊のトリガー本体)
if (!gimmickConfig.oreBlocks().contains(type)) return;
if (!PlayerData.of(player).veinMiningEnabled()) return;
OptionalDouble tier = dedicatedEffects.valueMax(player, EFFECT_VEIN_MINING);
if (tier.isPresent()) { handleVeinMining(player, block, type, (int) tier.getAsDouble()); }
```
`placedBlockTracker`はこのメソッド内で一切読まれない(除外は`onBlockBreakDropTables`のみ、L108)。クールダウン用フィールドも本クラスに存在しない(`TreeFellingListener.java:64`の`lastFellMillis`に相当するものが無い)。

---

## GTH-03 [HIGH][BUG] PotionMergeListenerがカーソル/スロットのItemStackを直接mutateするだけで明示的な再設定を行わない(複製/消滅の疑い、要確認)

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/PotionMergeListener.java:66-70`

**事象**: `mergePotions`成立後、`slot.setAmount(slot.getAmount() - 1)` / `cursor.setAmount(cursor.getAmount() - 1)`とその場でアイテム参照のamountを書き換えるだけで、`event.setCurrentItem(...)`や`player.setItemOnCursor(...)`のような明示的な再設定を一切呼んでいない。さらに減算後の量が0になる典型ケース(ポーションは通常スタック上限1)でも、0になったスタックをnull化する処理が無い。

同じファイル群内の他の「1個だけ消費する」実装(`XpBottleListener#consumeOneAndGive`、`GachaListener#consumeOneTicket`、`WoodRepairListener#onInventoryClick`)は全て「残量<=1ならスロット/カーソルをnullにする」「明示的にsetItemInMainHand/setItemOnCursorを呼ぶ」という一貫したパターンを踏んでおり、本リスナーだけがこの安全策を欠く。

**影響/再現**: (要確認: 実サーバでの検証が必要) `InventoryClickEvent#getCursor()`が返す参照がPaper実装上ライブなカーソル実体そのものかどうかに依存する。ライブ参照でなければ、カーソル側のポーションが消費されずに残ったまま(=同じ1本のカーソルポーションで何度でも統合できる実質的な複製)になる。ライブ参照であっても、amount=0のItemStackがそのままスロットに残ると幽霊アイテム(空スロットに見えるが内部的にPOTION amount 0が居座る)によるGUI表示異常の温床になる。

**根拠**:
```java
// PotionMergeListener.java:66-70
event.setCancelled(true);
slot.setAmount(slot.getAmount() - 1);
cursor.setAmount(cursor.getAmount() - 1);
Map<Integer, ItemStack> leftover = player.getInventory().addItem(merged);
```
対比(同種の消費処理で明示的nullガードを行う既存パターン):
```java
// GachaListener.java:180-189 consumeOneTicket
int remaining = heldStack.getAmount() - 1;
if (remaining <= 0) { player.getInventory().setItemInMainHand(null); return; }
```

---

## GTH-04 [HIGH][BUG/UNIMPLEMENTED] 怪しい砂利/砂ブロックの復活処理がloot tableを引き継がず、復活後は永久にルート抽選が空になる(要確認)

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/MiningGimmickListener.java:79-94`(`handleSuspiciousRespawn`)

**事象**: `suspicious-block-respawn`は「破壊した怪しい砂/砂利ブロックを1tick後に一定確率で同じブロックとして復活させる」ギミックだが、実装は`current.setType(type)`でMaterialを差し替えるのみで、バニラの`BrushableBlock`(考古学ルートテーブル)が保持する`lootTable`は一切設定・復元していない。コードベース全体を検索しても`LootTable`/`Lootable`/`BrushableBlock`関連APIへの参照はゼロ。

**影響/再現**: (要確認: Paper 1.21.11実行時の`BrushableBlockEntity`挙動)。バニラでは`Block#setType(SUSPICIOUS_SAND)`のようなAPI経由の設置はワールド生成時のストラクチャ処理で設定される`lootTable`フィールドを持たず、以後ブラシで完成させても何もドロップしない(空のBrushableBlockとして扱われる)というのが既知の挙動。これが正しければ、この機能は「確率で復活してもう一度発掘できる」という説明文どおりには機能せず、復活後のブロックはブラシ完了しても報酬ゼロの見た目だけのブロックになる — つまりUNIMPLEMENTEDに近いBUG。

**根拠**:
```java
// MiningGimmickListener.java:86-93
Bukkit.getScheduler().runTaskLater(plugin, () -> {
    Block current = world.getBlockAt(location);
    if (current.getType() == Material.AIR) {
        current.setType(type);
    }
}, RESPAWN_DELAY_TICKS);
```
`LootTable`/`BrushableBlock`関連APIへの参照は本ファイル・プロジェクト全体で0件(grep確認済み)。

---

## GTH-05 [MEDIUM][BALANCE] vanilla_exp_bonusとdigging-durability-vanilla-expが同一PlayerExpChangeEventに対して乗算で二重に効き、コメントの「独立加算」という主張と食い違う

**場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSurvivalPerkListener.java:100-107`、`TrinityForge/src/main/java/com/trinityforge/listeners/DiggingDurabilityExpListener.java:75-82`

**事象**: 両クラスとも`PlayerExpChangeEvent`を`EventPriority.HIGH`で購読し、それぞれ`event.setAmount((int) Math.round(amount * (1.0 + bonus)))`という「今の`event.getAmount()`に対して乗算」の処理を行う。Bukkitの同一優先度ハンドラは登録順で直列実行されるため、2つ目のハンドラは1つ目が既に増幅した後の値へさらに乗算する。結果は加算(1+x+y)ではなく乗算((1+x)(1+y))で複利的に膨らむ。

**影響/再現**: 例えば`vanilla_exp_bonus`=+50%、`digging-durability-vanilla-exp`(上限50%)も+50%の場合、意図(コメント上の想定)は+100%(2倍)だが実際は(1.5×1.5=2.25倍)=+125%になる。将来同種のPlayerExpChangeEvent系consumerが増えるたびにこの複利効果はさらに拡大する。

**根拠**:
```java
// NativeSurvivalPerkListener.java:100-106
public void onVanillaExpGain(PlayerExpChangeEvent event) {
    int amount = event.getAmount();
    double bonus = aggregator.aggregate(event.getPlayer()).totalOf(VANILLA_EXP_BONUS);
    event.setAmount((int) Math.round(amount * (1.0 + bonus)));
}
```
```java
// DiggingDurabilityExpListener.java:76-82
public void onVanillaExpGain(PlayerExpChangeEvent event) {
    int amount = event.getAmount();
    double bonus = vanillaExpBonusFraction(event.getPlayer());
    event.setAmount((int) Math.round(amount * (1.0 + bonus)));
}
```
コメント(DiggingDurabilityExpListener.java:36-38)は「vanilla_exp_bonus/kill_vanilla_exp_bonusとは完全に独立した別レイヤーとして加算される」と主張しているが、実装は加算ではなく直列乗算である。

---

## GTH-06 [MEDIUM][BALANCE] ANCIENT_DEBRISがfortune-blocksに含まれ、バニラでは効かないはずのフォーチュン恩恵をTF側で新設している

**場所**: `TrinityForge/src/main/resources/stats/mining-gimmick.yml:94`(fortune-blocks内`ANCIENT_DEBRIS`)、`TrinityForge/src/main/java/com/trinityforge/listeners/MiningFortuneListener.java:56-109`

**事象**: バニラのAncient Debrisはツール/エンチャント種別に関わらず常に自身をそのまま1個ドロップし、Fortuneエンチャントの影響を受けない仕様(意図的にネザライトの希少性を保つ設計)。しかしTFの`fortune-blocks`にはこの`ANCIENT_DEBRIS`が含まれており、`MiningFortuneListener`のロジックはブロック種別のみで判定するため、バニラでは存在しない「Ancient Debrisにフォーチュン相当のボーナスがかかる」状態になる。

**影響/再現**: MINING Lvが上がるほど(`fortune-per-level`=0.01、期待値=(fortune+Lv×0.01)×0.30)、そしてツール由来のmining-fortuneステが高いほど、古代のがれき1個の破壊あたりに追加ドロップが発生する確率が増える。ネザライト装備の入手ペースがバニラ設計の想定から外れて速くなる。GTH-01/GTH-02のvein-mining(最大32ブロック同時破壊)と組み合わさっても実害は薄い(Ancient Debrisは単独生成のため連鎖はほぼ発生しない)が、単独でも意図的な終盤資源の希少性を損なう。

**根拠**:
```yaml
# mining-gimmick.yml:94
    - ANCIENT_DEBRIS
```
上記が`fortune-blocks`セクション(`mining-gimmick.yml:76-105`)に含まれ、`MiningFortuneListener`はブロック種別のみで判定し古代のがれき固有の除外を行わない。

---

## GTH-07 [MEDIUM][BALANCE] area-harvest(auto-replant無し)がmining-fortuneの追加ドロップと連動し、auto-replant併用時とで収穫量が大きく非対称になる

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/FarmingHarvestListener.java:140-154`(`harvestNeighbor`)、`TrinityForge/src/main/java/com/trinityforge/listeners/MiningFortuneListener.java:56-65`

**事象**: `area-harvest`保有時、`auto-replant`を併用していない場合は周囲の成熟作物を`block.breakNaturally(tool)`で処理する。`breakNaturally`はバニラの自然な`BlockDropItemEvent`を発火するため、周囲の全作物それぞれに対して`MiningFortuneListener`の追加ドロップ抽選(`fortune-blocks`にWHEAT/CARROTS/POTATOES/BEETROOTS/MELON/PUMPKINが含まれる)が独立に乗る。一方`auto-replant`を併用している場合は`block.setType(Material.AIR)`+手動`dropAll`で処理しており、`BlockDropItemEvent`を経由しないため`MiningFortuneListener`のボーナスは一切乗らない。

**影響/再現**: 同じ`area-harvest`ノードでも「植え直しを選ばない」プレイヤーだけが、範囲内の全作物ぶんmining-fortuneボーナスの複利的な恩恵を受け、「植え直しも選ぶ」プレイヤーは損をする。UIやドキュメント上この非対称性は説明されておらず、プレイヤー体験としても不整合(トグルを増やすほど得になるはずが、逆に損をする組み合わせが存在する)。

**根拠**:
```java
// FarmingHarvestListener.java:140-146
private void harvestNeighbor(Block block, Material type, ItemStack tool, boolean replantActive) {
    if (!replantActive) {
        block.breakNaturally(tool);  // BlockDropItemEventが発火 → MiningFortuneListenerが乗る
        return;
    }
    // replantActive時はsetType+手動drop、BlockDropItemEvent不発火
```

---

## GTH-08 [MEDIUM][BUG] FishSellListener.recentSaleTimestampsがプレイヤー退出時に掃除されず無制限に増え続ける

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/FishSellListener.java:62`(フィールド)、`:109-121`(`consumeSellQuota`)

**事象**: `recentSaleTimestamps`は`fish-sell-toggle`を1度でも使用したプレイヤーUUIDをキーに、直近1分の売却タイムスタンプ`Deque`を保持する`HashMap`。ウィンドウ内の古いタイムスタンプは`consumeSellQuota`内で除去されるが、キー自体(UUIDエントリ)がプレイヤー退出時や空Dequeになった時点で除去されることは一切ない。

**影響/再現**: サーバーの累計プレイヤー数(過去に一度でもこの機能を使った人数)に比例して`HashMap`のエントリ数が増え続け、稼働期間が長いサーバーほど微小なメモリリークが蓄積する。1エントリのサイズは小さいがゼロコストではなく、`PlayerQuitEvent`等での掃除処理が存在しない設計上の抜け(`TreeFellingListener`は同種の`ConcurrentHashMap`に対して`onQuit`で明示的に`remove`している=既知の対処パターンが存在するのに本クラスは踏襲していない)。

**根拠**:
```java
// FishSellListener.java:62
private final Map<UUID, Deque<Long>> recentSaleTimestamps = new HashMap<>();
```
`PlayerQuitEvent`ハンドラは本ファイルに存在しない(grep該当なし)。対比: `TreeFellingListener.java:115-118`の`onQuit`が同種のクリーンアップを実装済み。

---

## GTH-09 [MEDIUM][BALANCE] BrewUnlockListenerの解放判定が「8ブロック以内の誰か1人」で成立し、未解放プレイヤーがミュールに便乗できる

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/BrewUnlockListener.java:254-274`(`playerHasEffectNear`)、`:216-230`(`hasUnlockedMatch`/`hasEffect`)

**事象**: カスタム醸造レシピの解放判定`playerHasEffectNear`は、醸造台のビューア(GUIを開いている人)だけでなく、醸造台から8ブロック以内にいる「ワールド上の任意のプレイヤー」が対象effectを保有していれば`true`を返す。醸造の実行者本人が未解放でも、解放済みの別プレイヤーがただ近くに立っているだけで、醸造自体は成立し完成ポーションを受け取れる。

**影響/再現**: `brew:<groupId>`ゲートは「スキルツリーで個別に取得すべき進行ゲート」という前提のはずだが、8ブロック以内という緩い判定のため「解放済みの高レベルプレイヤーが醸造台の隣に突っ立っているだけ」で、未解放の複数人が次々にカスタムポーションを醸造できてしまう。特にPvP鯖やパーティプレイでは、1人だけ進行を進めれば実質全員が解放済み相当になり、個別ゲーティングの意味が薄れる。

**根拠**:
```java
// BrewUnlockListener.java:266-273
for (Player player : loc.getWorld().getPlayers()) {
    if (player.getLocation().distanceSquared(loc) <= 64.0 // 8 blocks
            && dedicatedEffects.isActive(player, effectId)) {
        return true;
    }
}
```

---

## GTH-10 [LOW][DEADCODE/UNIMPLEMENTED] EconomyBridge#withdrawがコードベース全体で一度も呼ばれておらず、TF独自のVault通貨シンクが存在しない

**場所**: `TrinityForge/src/main/java/com/trinityforge/economy/EconomyBridge.java:99-109`(`withdraw`)

**事象**: `EconomyBridge`のJavadoc(L21)は「fish-sell、disassembly-return buffsなど」を通貨連携の利用例として挙げているが、実際に`economyBridge.deposit`/`economyBridge.withdraw`を呼んでいるのは`FishSellListener.java:100`の`deposit`一箇所のみ(grep確認)。`withdraw`はどこからも呼ばれておらず、`DisassemblyListener.java`にも`Economy`関連の参照は無い(grep確認)。

**影響/再現**: TF本体が提供するVault通貨の出口(source)は魚売却のみで、TF側に組み込みのシンク(消費先)が実装されていない。ユーザー要件の「Vaultが無くても運用可能」自体は満たされているが、Vaultを導入した場合でも通貨は貯まる一方でTF内で使い道が無く、外部プラグイン(ショップ等)の存在に完全依存する設計になっている。Javadocの「disassembly-return buffs」という例示は現状のコードに対応する実装が無く、古い設計メモの残骸(stale doc)の可能性がある。

**根拠**:
```java
// EconomyBridge.java:21 (javadoc)
 * (fish-sell, disassembly-return buffs, etc.)
```
grep結果: `economyBridge\.(deposit|withdraw)`のヒットは`FishSellListener.java:100`(deposit)のみ。`withdraw`呼び出しは0件。

---

## GTH-11 [LOW][BUG] PlantedCropGrowthListener.ownedCropsが恒久的にアンロードされたチャンクの作物エントリを永遠に保持し続ける

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/PlantedCropGrowthListener.java:65-93`(`tickOwnedCrops`)、特に`:74-77`

**事象**: `ownedCrops`(in-memoryの`ConcurrentHashMap`)は40tick毎の`tickOwnedCrops`でエントリを掃除するが、対象チャンクが未ロードの場合は`return false`(=削除せず維持)して次周期へ持ち越すだけで終わる。プレイヤーが植えた場所のチャンクがその後一度も再ロードされなければ(遠方へ移動して二度と戻らない等)、そのエントリは永久に残り続ける。

**影響/再現**: 長期稼働サーバーで多数のプレイヤーが広範囲に`planted_crop_growth_bonus`ステ保持状態で作物を植えた場合、訪問されないチャンクの分だけ`ownedCrops`が単調増加し続ける。1エントリは軽量(文字列キー+UUID)なため即座に問題化はしないが、境界条件の設計漏れとして残る。

**根拠**:
```java
// PlantedCropGrowthListener.java:74-77
if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
    return false; // 未ロード → 削除されず維持
}
```

---

## GTH-12 [LOW][BALANCE] DropAdjustment.subtractOneは種のドロップが0本だった場合でも植え直しを行い、実質「種を消費せず植え直す」抜け道になる

**場所**: `TrinityForge/src/main/java/com/trinityforge/farming/DropAdjustment.java:32-54`

**事象**: `subtractOne`は`drops`の中に`seedMaterial`が見つからない(またはフォーチュン等で該当スタックの個数が既に0)場合、減算をスキップして`drops`をそのまま返す仕様(コード上「要調整」と自己申告あり)。呼び出し元の`FarmingHarvestListener#replantOriginBlock`/`harvestNeighbor`はこの結果に関わらず`scheduleReplant`を呼んで無条件に植え直す。

**影響/再現**: 種ドロップが偶然0本だった稀なケース(コメントが言及する通り)で、`auto-replant`保持者は種を1つも消費せずに植え直しが成立してしまう。単体では発生確率が低く実害も小さいが、繰り返し発生すれば種の消費なしで農地を維持できる小さな抜け道になる。

**根拠**:
```java
// DropAdjustment.java:36-39
public static List<DropStack> subtractOne(List<DropStack> drops, Material seedMaterial) {
    if (seedMaterial == null) {
        return List.copyOf(drops);
    }
```
コメント(L32-35)「一致するスタックが無い場合... 減らせないなら減らさず、植え直し自体は行う設計」と実装側が明言。

---

## GTH-13 [MEDIUM][UNIMPLEMENTED] spawner-silktouch-harvestは元のEntityTypeを保持せず、常に無印(ピッグ相当)スポナーになる

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/MiningGimmickListener.java:37-42`(javadoc)、`:96-108`(`handleSpawnerHarvest`)

**事象**: シルクタッチでスポナーを回収する機能は、実際にはスポナーの`EntityType`(何のモブ用スポナーだったか)を一切保持せず、常に無印の`Material.SPAWNER`アイテムを1個ドロップする。コード自身のJavadocで「要調整」と明記されているが、実装側から見ると「シルクタッチでスポナーを回収する」という機能名から期待される主要な価値(スポナーの種類を持ち帰って再設置する)が実現できておらず、UNIMPLEMENTEDに近い状態。

**影響/再現**: プレイヤーがダンジョン等でゾンビ/スケルトン等の特定モブ用スポナーをシルクタッチで回収しても、再設置すると常にピッグスポナー相当(バニラ既定のEntityType)になり、収集した意味がない。既に自己申告のTODOではあるが、監査時点でも実際に問題が残っている(過去レポート記載の有無に関わらず)ため報告する。

**根拠**:
```java
// MiningGimmickListener.java:105-107
event.setDropItems(false);
event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(),
        new ItemStack(Material.SPAWNER, 1));
```

---

## GTH-14 [LOW][COMMENT(EXTERNALIZE候補)] 一部リスナーに長文の「過去バグ経緯/exploit対策」javadocが埋め込まれており、docs/への切り出し候補になる

**場所(代表例)**:
- `TrinityForge/src/main/java/com/trinityforge/listeners/FishSellListener.java:28-51`(class javadoc、exploit対策3点の説明)
- `TrinityForge/src/main/java/com/trinityforge/listeners/FurnaceSmeltListener.java:32-58`(class javadoc、所有者追跡設計の詳細)
- `TrinityForge/src/main/java/com/trinityforge/listeners/CraftQualityListener.java:130-141`(`onCraftResultClick`、複製バグ修正の経緯説明)
- `TrinityForge/src/main/java/com/trinityforge/listeners/BrewOwnership.java:12-34`(class javadoc、読み取り順序の保証説明)

**事象**: これらはいずれも「なぜそうしたか」「非自明な制約」を説明しており、ブリーフのKEEP基準(Why/罠/契約/順序依存の理由)には合致するため単純なDELETE対象ではない。ただし個々が10行超のパラグラフになっており、ブリーフのEXTERNALIZE基準(「長い仕様解説・設計背景」)にも該当する規模。コード読解時の可読性(9,771行規模のコメント総量削減という目的)を優先するなら、各クラスに1行の要約+`docs/design/`または新設の`docs/notes/exploit-fixes.md`的な場所への参照リンクへ圧縮する余地がある。

**影響**: 機能面の問題ではないが、同種の長文javadocが本監査対象領域だけでも4件以上見つかっており、他領域にも同様のパターンが多数存在する可能性が高い(ブリーフの「9,771行」規模感と整合)。全リスナーを個別に列挙するのではなく、パターンとして報告する。

**根拠**: 上記4ファイルの該当行数はいずれも実測(class javadocまたはメソッドjavadocが10行以上、実装コードより長い)。

---

## 補足: 確認したが問題なしと判断した設計(ネガティブチェック結果)

- `GachaListener`: 券消費は景品解決成功後にのみ行われ、天井(pity)カウンタの更新も同様に成立後のみ(`GachaListener.java:136-138`)。dupe/損失のいずれの経路も無い。
- `VillagerTradeListener`: GUIを開くたびに`setRecipes`で再構築すると使用回数がリセットされ無限購入exploitになる問題を認識し、注入済みマーカー(PDC)で1回限りに制御済み(`VillagerTradeListener.java:85-113`)。
- `FurnaceSmeltListener`/`PotionQualityListener`: かまど/醸造台の「本人だけに恩恵」をPDC所有者追跡+優先度順序(HIGH読み取り→MONITOR消去)で保証しており、EventPriority理解も正確。
- `GatheringExtraDropListener`: 設置ブロック除外のタイミング(HIGHで読み取り、MONITORでの消去前)をコメントで正確に説明し、実装も一致。GTH-01/02の欠落と対照的に模範的。
- `BreedingBonusListener`の`giveExp`直接付与は`PlayerExpChangeEvent`を発火しないため、GTH-05のような多重適用は起きない(コード上の想定通り)。
