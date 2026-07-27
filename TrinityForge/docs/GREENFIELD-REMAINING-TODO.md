# スキルツリー専用効果 — 未実装(green-field)サブシステム バックログ

2026-07-19 時点。要件⑥「スキルツリー既定の専用効果」のうち、**既存の下地が無く、アイテム/GUI/レシピ/経済/バランスを新規発明する必要がある深いサブシステム**をここに集約する。ユーザ判断(2026-07-19)により、well-specifiedな効果は今回全実装・確定し、以下は推奨付きTODOとして残置(別途着手)。

一次spec = `trinityforge/skilltree/スライド1-12.JPG`。効果registry = `TrinityForge/src/main/resources/skilltree/dedicated-effects.yml`。ランタイム照会 = `config().dedicatedEffects().isActive(player,id)` / `valueSum(player,id)`(TF内直呼び)、fork側は `TrinityForgeBridge.tfEffectActive/tfEffectValue`(fail-open)。**effect id自体・ノード配置・エディタmirror・flag検出APIは既に存在**するので、各項目は「機能本体の新規実装」だけが残っている。

---

## 1. 武器コーティング(weapon-coating-unlock / coating-stack-increase)
- **spec(錬金C/E)**: ソースジェムで武器をコーティング。E で「コーティングのスタック数増加」。
- **現状**: fork に実体無し(`unlock-gate.yml`冒頭コメントで明記)。ValhallaのcoatingchargesはArs武器コーティングとは別物。
- **要新規**: コーティングアイテム/適用GUIまたは右クリック適用、コーティング効果(与ダメ属性付与等)、スタック管理PDC。
- **推奨**: fork。coating-stack-increase は `valueSum(player,"coating-stack-increase")`(int)で最大スタック加算。まず「1コーティング=N回分の追加属性ダメージ」MVPから。

## 2. ポーション統合(potion-merge)
- **spec(錬金D)**: 複数ポーションの統合を可能に。
- **現状**: 醸造レシピ解放の既存機構無し。ValhallaのpotioncombiningはArs系とは別。
- **要新規**: 2つのポーションを1つに統合するGUI/レシピ(効果マージ規則、上限)。
- **推奨**: fork or TF。`isActive(player,"potion-merge")`で醸造台/カスタムGUIでのマージを許可。効果重複時の統合規則が要spec。

## 3. 醸造レシピ解放(swiftness-jump-potion-unlock / healthboost-haste-potion-unlock)
- **spec(錬金C-1/C-2)**: 俊敏/跳躍、体力増強/採掘速度上昇 のポーションを利用可能に。
- **現状**: 対応する醸造レシピIDが未確認(dedicated-effects.yml で flag扱い)。
- **要新規**: 該当ポーションの醸造レシピ登録 + `BrewEvent`等でperk保有ゲート。レシピが実在すれば recipe-gate 化も可。
- **推奨**: fork。レシピ実体を作り、UnlockGate同様のperk-gateで解放。

## 4. 各種coreクラフト解放(wood/vegetable/meat/jewelry-core-craft-unlock)
- **spec(採掘E/伐採E/農業D/E)**: 各コアの**クラフト**解放。
- **現状**: コアアイテム自体は存在(`catalog.yml: tf_core_*`、glyph-unlock用)。しかし「コアをクラフトするレシピ」は無し(dedicated-effects.yml note明記)。
- **要新規**: 各coreのクラフトレシピ + 解放ゲート。
- **推奨**: TF(catalogレシピ機構 `CatalogRecipeRegistrar` が既にある)。レシピを定義し、`isActive`でクラフト許可ゲート。TF-A1のcatalogレシピ資産を流用可。

## 5. 村人取引解放(blacksmith-unlock / priest-unlock / librarian-trade-unlock)
- **spec(鍛冶E/錬金C/エンチャE)**: 鍛冶師・司祭・司書の取引解放。
- **現状**: 村人取引をいじるリスナー無し(`VillagerAcquireTradeEvent`/`MerchantRecipe` grep 0件)。
- **要新規**: 各職業村人に追加取引(MerchantRecipe)を、perk保有プレイヤーにのみ提示するリスナー層。
- **推奨**: TF。`VillagerAcquireTradeEvent` or インベントリ開時に `isActive`で追加取引注入。取引内容(入出力)が要spec。

## 6. 解体(dismantle-unlock) + 素材返却は実装済
- **spec(鍛冶C-2)**: 解体解放。装備を素材に戻す。
- **現状**: 機構無し(note: SMITHING B-3-upper未実装)。※material-refund-chance(Ars鍛冶C)は F3d で `RitualManager` に実装済み(別物)。
- **要新規**: 解体GUI/レシピ(装備→素材の返却規則)。
- **推奨**: TF or fork。`isActive(player,"dismantle-unlock")`で解体GUI/コマンドを許可。返却規則が要spec。

## 7. 圧縮木材修繕(wood-repair-unlock)
- **spec(伐採E)**: 耐久値を圧縮木材で回復可能に。
- **現状**: 「圧縮木材」アイテムが無い(food-compressionと同様、圧縮アイテム機構が未整備)。
- **要新規**: 圧縮木材アイテム定義 + 金床/GUIでの耐久回復(圧縮木材消費)。
- **推奨**: TF。圧縮木材catalogアイテム + `PrepareAnvilEvent`等で `isActive`時に修繕許可。回復量が要spec。

## 8. food-compression(食料圧縮)
- **spec(農業B)**: ご飯の圧縮を可能に。
- **現状**: TF-B3food で consumer は**未実装**(圧縮アイテム/レシピ機構が要る旨コード内Javadoc + dedicated-effects.yml note)。
- **要新規**: 圧縮食料アイテム(9→1等) + クラフトレシピ + 解放ゲート。
- **推奨**: TF。圧縮食料catalogアイテム群 + `isActive(player,"food-compression")`でレシピ許可。圧縮比/対象食料が要spec。

## 9. craft-thread-unlock(クラフトスレッド解放)
- **spec(Ars鍛冶D)**: クラフトスレッド解放(thread-slot-expansionとは別概念)。
- **現状**: スレッド入手は儀式のみ(ThreadRitualEffect)。「スレッドをクラフトする」レシピ無し。
- **要新規**: スレッドのクラフトレシピ(特殊効果系スレッドのみ、スライド備考「スレッドは特殊効果系のみクラフト、残りは厳選」)+ 解放ゲート。
- **推奨**: fork。ThreadItem生成を使うクラフトレシピ + `tfEffectActive(player,"craft-thread-unlock")`ゲート。どのスレッドをクラフト可能にするかが要spec。

## 10. over-enchant(over-enchant-1/2/3)
- **spec(エンチャC-1/2/3)**: 特定系統エンチャントのレベル上限を +1/+2(幸運は+1)突破。
- **現状(調査済)**: TF `ToolEnchant*` 機構は**アイテム品質ベースの固定ボーナス付与**でプレイヤー文脈が無く、over-enchantの差込点にならない。さらに耐久力/幸運/ドロップ増加系は既に**連続値ステシステム**(item-stats durability / MiningFortuneListener / FishingQualityListener)へ移行済みで、エンチャントレベルとして存在しない=「レベル上限を上げる」前提が半分崩れている。
- **設計衝突**: 耐久力/幸運を"上限緩和"として実現するか(既存ステ系)、エンチャント方式に巻き戻すか(移行メモは二重加算回避のため巻き戻し禁止)—**要ユーザ設計判断**。
- **要新規**: `EnchantItemEvent`/`PrepareItemEnchantEvent`/`PrepareAnvilEvent` の新規リスナー層 + 系統→エンチャントmapping config。ダメージ増加系/軽減系はエンチャ台に出現するため対応しやすいが、耐久力/幸運/ドロップ系は上記の設計衝突を解消してから。
- **推奨**: TF。まずダメージ増加系/軽減系のみ `EnchantItemEvent`後付与でMVP、耐久力/幸運系は設計判断後。

## 11. source-jar-link-unlock(上位ソースジャー/リンク解放) — 部分実装
- **spec(Ars鍛冶A-3)**: 上位ソースジャー・ソースリンク解放。
- **現状**: ソースリンク側は recipe-gate(`volcanic_sourcelink_craft`等)で F3a のgate-pullにより解放可能。**上位ソースジャー側は fork実体無し**(unlock-gate.yml note)。
- **要新規**: 上位ソースジャーのアイテム/機能 + レシピ。
- **推奨**: fork。ソースジャー機能拡張後にrecipe-gate追加。

---

## 補足: 今回スコープ外だが関連する残課題
- **luck-silktouch-glyph-unlock / multi-debuff-glyph-unlock**: スライドで文脈確定(エンチャD=幸運シルクタッチ, 錬金B-4=複数デバフ)したが、fork側に対応するグリフ実体(シルクタッチ相当SpellComponent / 複数デバフグリフ)が無い。fork glyph実装後に dedicated-effects.yml の `target: ""` を確定。
- **F1残**: catalog由来の新防具(tf catalog armor)の金床修理/エンチャント権限(旧 isMageArmor 判定に非該当)。
- **fish-sell-toggle の売却経済**: loot toggle(宝→ゴミ)は実装済みだが「魚を売る」通貨系は未実装(通貨/経済システムが無い)。
- **haste-active-mining の段階**: registry param:none のため tier別(採掘速度III/IV/V, CD60/45/30)が value で表現できず、config既定の単一値で実装。段階再現には param変更が必要。
- **繁殖2倍/成長速度UP/各種+%ドロップ/EXP+%**: dedicated-effects registry外(Valhallaネイティブperk-reward経由)。TF実装対象外。
