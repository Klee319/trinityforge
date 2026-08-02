# 配備手順: モブの魔法ダメージ機構 + 派生カスタムボス6体 (2026-08-02)

このドキュメントは「モブが魔法ダメージを与えられる」新機構(`attack.magic-ratio`)と、それを実証する
派生カスタムボス6体の配備手順を、実行者(ユーザー)向けにまとめたもの。**エージェントはサーバへの
書き込みができないため、この手順はすべてユーザーが実行すること。**

前提として `docs/agent-context/forks-and-mobs.md` を読んでおくこと(フォーク／モブ系の恒久知識)。

## 0. 何が変わったか(要約)

1. `combat/mob-types.yml` / `combat/mob-profiles.yml` / `combat/mob-overrides.yml` の `attack:` ブロックに
   新キー `magic-ratio` [0.0, 1.0] が追加された。モブの通常攻撃(近接/投射)のうち何割を魔法として
   解決するかを決める。既定 0.0 = 完全物理(**未設定の既存モブは1体も挙動が変わらない**)。
2. `FocusHpText`(モブ頭上のHPプレート)に、`magic-ratio > 0` のモブへ `[攻:魔]`(完全魔法)または
   `[攻:混]`(ハイブリッド)のタグが付くようになった。既存の耐性寄りタグ `[耐:物]`/`[耐:魔]` とは
   別軸の情報で、2行レイアウトのまま同じ名前行に並ぶ(3行目は増えない)。
3. `combat/mob-overrides.yml` の `overrides.default.mobs` に、`attack.magic-ratio` を実証する新規派生
   カスタムボス6体を追加した(既存41種の `mob-types.yml` エントリは一切書き換えていない — 既存モブの
   性質を変えると「プレイヤーが戦っている前提が無言で崩れる」ため、新規モブの追加だけで対応した)。

## 1. TF 側(このリポジトリ由来)の配備

TF本体プラグインを通常どおりビルド・配備すれば、上記1〜3は自動的に有効になる
(`combat/mob-overrides.yml` は生成/保護ファイルではなく、リポジトリの出荷内容がそのまま
`plugins/TrinityForge/combat/mob-overrides.yml` へコピーされる — 既にファイルが存在するサーバでは
自動上書きされないので、既存サーバでは手動で該当ブロックをマージすること)。

```powershell
cd TrinityForge
./gradlew releaseAssembly --offline "-Dorg.gradle.java.home=C:\Program Files\Java\jdk-21"
```

`TrinityForge/build/release/TrinityForge-all.jar` を各バックエンドの `plugins/TrinityForge.jar` へ配備する
(手順は `docs/agent-context/ops-build-deploy.md` 参照)。

## 2. 派生カスタムボス6体の配備(EliteMobs custombosses)

新規モブは EliteMobs のカスタムボス機能を使って作った、通常のダンジョン外でも `/em spawn` 等で
呼び出せる独立したモブ定義。YAML ファイルは以下に**リポジトリ内でステージング**してある
(まだ本番サーバへは配られていない):

```
ops/templates/elitemobs-custombosses/caster_zombie.yml           術者のゾンビ    (ZOMBIE,    magic-ratio 1.0)
ops/templates/elitemobs-custombosses/warlock_husk.yml            呪術師のハスク  (HUSK,      magic-ratio 0.6)
ops/templates/elitemobs-custombosses/frost_wraith_skeleton.yml   氷結の彷徨い骨  (SKELETON,  magic-ratio 0.7)
ops/templates/elitemobs-custombosses/abyssal_drowned.yml         深淵の溺者      (DROWNED,   magic-ratio 0.8)
ops/templates/elitemobs-custombosses/cursed_wanderer.yml         呪われた徘徊者  (ZOMBIE_VILLAGER, magic-ratio 0.5)
ops/templates/elitemobs-custombosses/shadow_spider.yml           影渡りの蜘蛛    (SPIDER,    magic-ratio 0.9)
```

各ファイルの中身は EliteMobs custombosses の最小構成(`isEnabled` / `entityType` / `name` / `level`)。
強さ・技・ドロップは TF 側の `combat/mob-overrides.yml`(`overrides.default.mobs.<id>`)が全部担当するので、
custombosses 側の数値はほぼ意味を持たない(意図的に最小)。

### 手順

1. 上記6ファイルを、EliteMobsが動くサーバの `plugins/EliteMobs/custombosses/` へコピーする
   (ファイル名がそのまま TF 側の mob id になるので、リネームしないこと)。
2. サーバを再起動するか `/em reload` を実行し、EliteMobs にカスタムボスを認識させる。
3. **(推奨だが必須ではない)** `/trinityforge importmobs plugins/EliteMobs/custombosses` を実行し、
   `combat/mob-profiles.yml`(生成ファイル)へこの6体の基礎値を取り込む。
   **省略しても動く理由**: `ConfigManager#resolveRuntimeProfileBase` は mob-profiles.yml に見つからない
   EliteMobsモブを `combat/mob-import.yml` のランプで自動合成する(`unknown-mobs.synthesize: true` が既定)。
   さらに `combat/mob-overrides.yml` の `default.mobs.<id>` にこの6体の `stats.attack`(attack-power / 
   magic-ratio 含む)・`abilities`・`drops` を全部明示済みなので、import を省略しても強さ・技・ドロップは
   正しく上書きされる。import を実行しておくメリットは、将来 override 側の値を消したときに
   フォールバック先(mob-profiles.yml)の基礎値が「その場しのぎの合成値」でなく「実際にimportされた
   固定値」になることだけ。
4. `/em spawn caster_zombie` 等で実際にスポーンさせ、PDCスタンプを確認する
   (下記「検証手順」参照)。

## 3. 検証手順(ユーザーが実サーバで行う。エージェントは検証できない)

1. サーバログに `TrinityForge detected — EliteMobs combat/loot/defense/targeting delegation is active.`
   が出ていること(統合が有効)。
2. `/em spawn caster_zombie` で「術者のゾンビ」をスポーンさせ、頭上のHPプレートに
   `[攻:魔]` タグが付いていることを目視確認する(耐性タグと混ざらず同じ行に並ぶこと)。
3. 「術者のゾンビ」に殴られたときのダメージが、こちらの**魔法防御**(耐性%/守備力等)で軽減され、
   **物理専用の防具**(例: 物理のみの守備力アイテム)では軽減されないことを確認する
   (逆になっていたら実装1のバグ — 「魔法モブなのに物理防具で受けられる」の再発)。
4. `warlock_husk`(magic-ratio 0.6)・`cursed_wanderer`(magic-ratio 0.5)のようなハイブリッドモブは、
   物理防具と魔法防御の**両方**である程度軽減されるが、どちらか片方だけでは軽減しきれないことを確認する。
5. 6体とも撃破時に想定ドロップ(`combat/mob-overrides.yml` の `drops:` 参照)が出ることを確認する。

## 4. 既存41体の mob-types.yml エントリについて(意図的な非変更)

タスク依頼元では「現状 魔法:物理 が約31.7%:68.3%、目標40%:60%」という比率が言及されていたが、
このリポジトリの `combat/mob-types.yml`(41種の hostile フィールドモブ)は生成・保護ファイルではなく
毎回書き換えれば影響が出る実ファイルであるため、**依頼の明示的な指示(「既存モブの性質を書き換えるより
新規の派生モブを優先する」)に従い、既存41体には magic-ratio を一切設定していない**。

比率シフトは今回追加した派生カスタムボス6体(すべて magic-ratio > 0)だけで実現している。
これらは EliteMobs 側の生成/保護ファイル(mob-profiles.yml)に取り込まれる新規モブなので、
「41体中◯体」という母数そのものに数えるべきかはドメインが異なり判断が割れる
(mob-types.yml は据え置きフィールドモブの母集団、custombosses は EliteMobs 側の独立した母集団)。
このドキュメントでは正確に検証できない「◯%→◯%」という主張はせず、「意図的に魔法寄りにした
新規モブが6体増えた」という事実だけを報告する。個々のモブの `entityType` と `magic-ratio` は
本ドキュメント冒頭の一覧を参照。
