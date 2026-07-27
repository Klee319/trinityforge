# 完了報告 — ステータス再分類 / 効率統合 / editor タブ再編 / スキルツリー草案

> **⚠️ 2026-07-27: この文書は残タスクの一次情報ではありません。**
> 残タスク・既知の問題・作業履歴は `reports/ACTIVE_RECORD.md` に集約しました。
> 本書はその日の作業記録として残していますが、記載されている「残タスク」「保留リスト」は
> 既に解決済みのものを多く含みます（棚卸しで15件が実装済みと判明）。参照しないでください。


作成: 2026-07-26 10:00
対象セッション: ステータス3分類の整理、効率キー統合と上限撤廃、ArsPaper フォークのマナ配線、
config-editor のタブ再編とティアテーブルUI、職業別スキルツリー草案（16ツリー）

---

## 0. このセッションの終了条件

`/goal` の終了条件は「収束するか確認せずに進めるとリスクのあるタスクのみが残ったら報告書を作成して終了」。
安全に自走できる残タスクは消化し切ったため、本報告書をもって終了する。
残っているものは全て §5（ユーザー判断待ち）と §6（配備）に集約した。

---

## 1. 検証結果（自分で実行して確認した数字）

| 対象 | 結果 |
|---|---|
| TrinityForge Java テスト | 274 クラス、**failures 0 / errors 0**、skipped は正規の2件のみ（`OfflineMobImportRunner` / `NativeProgressionStabilizationContractsTest`） |
| config-editor テスト | **514 tests / 512 pass / 2 fail / skipped 0** |

config-editor の失敗2件は `test/item-stat-coverage.test.js` の既知stale
（`weapon/特殊武器/wooden_halberd`、`other/サブウェポン/novus_criculus_luminis`）。
セッション開始時から数も内容も不変で、今回の変更とは無関係。

**MockBukkit の罠についての確認**: `UnimplementedOperationException` は `TestAbortedException` を継承するため
JUnit が FAILED ではなく SKIPPED として報告する。正規のスキップは上記2件のみで、この数が増えていない
＝隠れた失敗は無い、という判定基準を今回も適用し、増加が無いことを確認した。

---

## 2. 完了した作業

### 2-1. ステータス3分類の整理

`StatVocabulary`（総合ステ）から、アイテム固有であるべき2キーを外した。

- 除外: `coating_charges`, `attack_speed`
- 追加（総合ステへ昇格）: `armor_strength`, `aoe_radius`, `aoe_max_targets`, `aoe_damage_rate`,
  `hit_mana_recovery`, `damage_mana_recovery`, `mana_cost_reduction_flat`, `mana_cost_reduction_percent`
- `combat/base-stats.yml` に21キーを値0で追加（プレイヤー全員一律の基礎値をここから配れるようにした）

**副作用として見つけて直したバグ**: 軽量武器・重量武器の greek ノード4件が effect-text に「攻撃速度」と
表示しているのに、実際の buffs が死んだ `attack_speed` を参照していて**何も起きていなかった**。
生きている `attack-speed-bonus` へ差し替えた（軽量 0.08 / 重量 0.04、重い武器は意図的に低く）。

### 2-2. 効率キーの統合と上限撤廃

`tool-enchant-efficiency` を `gathering-efficiency` へ統合（`StatKeys` のエイリアスで吸収）。
表示名も「採集効率」→「**最終効率**」へ変更した。上限は `max-enchant-level: 0`（0以下＝無制限）で撤廃。

**注意点をコードコメントに残した**: バニラの採掘速度は `効率レベル² + 1` で伸びるため、上限を外すと
レベル10前後で採掘が一瞬になる。運用で上限を戻せるよう `stats/gathering-efficiency.yml` を editor に
登録済み（「最終効率の上限」タブ）。

また、**採掘速度を `MINING_EFFICIENCY` / `BLOCK_BREAK_SPEED` 属性で実装するのは禁止**（Geyser未対応で
統合版がゴーストブロックになる）。効率強化エンチャントのレベル操作が唯一の互換手段である旨も明記した。

**同一キー二重定義の事故を防ぐテストを追加**: `base-stats.yml` / `lore.yml` に旧キーと新キーが両方書かれると
正規化後に衝突して片方が黙って消える。`BaseStatsConfigTest` に衝突検出テストを足した。

### 2-3. ArsPaper フォークへのマナ配線

`ArsNativeBridge` を拡張し、装備以外の寄与（パーク一般 + 永続バフ + ロールバフ + 基礎ステ）を
**単一の供給源**にまとめた。フォーク側は `tfNonItemStatTotal` 経由で読む。

**訂正**: セッション中に私は「Ars魔法ツリーのマナ成長がフォーク側で読まれていない」と述べたが、これは誤り。
`ManaManager` は既に `ArsNativeBridge`（パーク＋永続）を読んでいた。実際に欠けていたのは
**ロールバフと基礎ステの2経路だけ**。前提が誤っていたため、そのまま実装していれば二重計上になっていた。

二重計上防止のガードテストを新設（`ArmorManaListenerManaBonusGuardTest`）。`mana_bonus` / `mana_regen` を
`tfNonItemStatTotal` へ渡していないことをソーススキャンで検証する。

### 2-4. config-editor タブ再編（ユーザー指示4項目 + 追撃1項目）

| 指示 | 対応 |
|---|---|
| その他ギミックからエンチャント関連を切り出し | 「エンチャントギミック」タブ新設（over-enchant + **エンチャント運**） |
| その他ギミックから醸造関連を切り出し | 「醸造ギミック」タブ新設（potion-merge / brew-unlocks + **ポーション品質換算**） |
| 食事ギミックを農業ギミックへ統合 | 農業ギミック画面に内包、食事タブはサイドバーから非表示 |
| 木材修繕をその他ギミック→伐採ギミック | 伐設ギミック画面にコンパニオン表示 |
| 精錬ギミック→鍛冶ギミックへリネーム＋解体を移動 | ラベル変更＋鍛冶ギミック画面に解体セクション |

**太字の2項目は追撃で足したもの**。1回目の作業では「迷ったら寄せない」という判断で
その他ギミックに残されていたが、ポーション品質換算は醸造関連そのもの、エンチャント運はエンチャント関連
そのものであり、指示の文言に反していたため移設した。

結果として「その他ギミック」は 概要／スレッド枠／コーティング／バニラレシピ削除／レシピ追加／
ソース自動消費 の6タブに縮小し、追加保存先も `ars-config` だけになった。

**複数タブが1ファイルを共有する設計**: `progression/crafting-features.yml` は5タブが共有する。
各タブはファイルを丸ごと読み、自分の担当サブツリーだけ書き換え、他キーは素通しする。
サーバ側の楽観ロック（`fileRevision`）は物理パス基準なので、タブが増えても競合検知は正しく効く。
ロスレス性は「ロスレス:」プレフィックスのテスト群で固定した。

### 2-5. ティアテーブルUI（採掘加速・一括伐採・範囲収穫）

スキルツリーのノード value → `valueMax` → tier → gimmick yml の `tiers:` テーブル、という参照構造に
合わせて、editor 側にティア単位で変数を設定するテーブルカードUIを追加した。
対象: vein-mining / haste-active-mining / tree-fell / area-harvest。
`cooldown-ticks` は意図的にティア列から除外（ティア別に変える設計ではないため）。
死にキー `small-max-extra-logs` / `large-max-extra-logs` も併せて掃除した（実サーバ側に汚染が無いことは確認済み）。

### 2-6. editor から触れないキーの穴埋め

出荷ymlに存在するのに editor のどのフォームからも編集できなかった4グループにUIを追加。

| yml | キー | 追加したUI |
|---|---|---|
| `stats/skill-exp.yml` | `exp-display` | mode選択（bossbar / actionbar）、表示秒数、同時表示上限 |
| `stats/skill-exp.yml` | `level-up` | チャット通知、効果音ON/OFF、効果音名、タイトル表示間隔 |
| `stats/mining-gimmick.yml` | `suspicious-block-respawn.loot-tables` | ブロック種別→ルートテーブル名の行編集 |
| `combat/mob-import.yml` | `unknown-mobs.synthesize` | チェックボックス＋yml内コメントを要約した説明 |

`exp-display.mode` の選択肢は `SkillExpConfig.java:176-177` を読んで `bossbar` / `actionbar` の2つと確定。

**調査のやり直しで判明した誤検知**: 以前「editor未登録yml 3件」として挙げた
`combat/damage.yml` / `progression/combat-level.yml` は「共通変数（戦闘定数）」画面が担当済み、
`combat/mob-defaults.yml` は `FACADES.md` に deprecated と明記されていた。私のスクリプトが
registry.js の rel しか見ていなかったための誤検知。

### 2-7. 職業別スキルツリー草案（16ツリー）

- `skilltree/職業別草案_戦闘.md`（6ツリー）
- `skilltree/職業別草案_生産.md`（10ツリー）
- `skilltree/_草案作成ブリーフ.md`（共通ルール）
- `skilltree/_現構造ダンプ.txt`（現行16ツリーの機械ダンプ 838行）

戦闘側は当初ツリー本体をコードフェンスで囲っておらず、全角スペースの字下げが Markdown 描画で潰れて
階層が読めない状態だった。生産側の体裁（凡例／コードフェンス／方針まとめ表）に揃えて修正済み。

**草案作成中に見つけた既存バグ**: effect-text で数値を約束しているのに buffs / feature / commands が
一つも無い「文言だけのノード」が7ツリーに存在した。特に `ars_magic` の B-1-1〜C-4-3 の9ノードは
`dedicated-effects` 自体が無く、グリフ解放が完全に未配線だった。

---

## 3. 自分の誤りの訂正（記録として残す）

このセッション中、私自身の主張のうち以下は誤りだったので訂正済み。

1. **「ギリシャ路線が排他になっていない」** → 誤り。15ノード全てに `group: A-greek`〜`E-greek` が付いており
   排他は成立している。本当の問題は**粒度**で、`parent: B` になっているためレーンが固定されないこと。
2. **「ポーション品質 / 醸造速度 / 残留時間 / エンチャント運 / グリフ威力ブーストは未実装」** → 5件とも
   実装済みかつ yml に配線済みだった。改訂案の該当4節を訂正バナー付きで書き直した。
3. **「Ars魔法ツリーのマナ成長がフォーク側で読まれていない」** → §2-3 の通り誤り。
4. **「editor未登録yml 3件」** → §2-6 の通り自作スクリプトの誤検知。
5. **ブリーフのルール4の記述** → 「`0.2` と書くと 20% の 1/100 になる」は不正確。
   実際の `PercentStatNormalize.coerce` は「`1.0 < |v| ≤ 100` かつ整数」のときだけ `/100` する。
   1.0以下は素通し。`crit-damage` は RATE_KEYS に入っていないため `30` と書くと 3000% になる。

---

## 4. 品質面で注意している点（今回持ち込みではないが記録）

- **競合マージUIの穴**: `applyMergedToEditor` が、コンパニオン（追加保存先）のIDを特別扱いしていない。
  同時編集で409が出たとき、コンパニオン側だけUIへ再描画されない。マージ処理自体とベース版数の管理は
  正しく動く。`ars-config` などで以前からある制約で、今回の変更で持ち込んだものではない。
- **テストファイル1件がバックアップ無しで全面書き換えされた**: `test/tf-crafting-features-companion-tabs.test.js`。
  このリポジトリは git 管理外のため旧内容は復元できない。ただし旧テストが検証していたのは今回廃止した
  配置そのもので、同じ契約（getExtraSaves・既定値）は新しい配置で検証し直されている。
- **私が指示していない作業が並行して着地している**: `MobOverridesConfig` / `MobOverrideExpListener` /
  `MobOverrideEntry` / `combat/mob-overrides.yml` と、対応する editor テスト2件。
  内容は自己整合的でテストも緑だが、**私はレビューしていない**。

---

## 5. ユーザー判断待ち（着手前に確認が要るもの）

### 5-1. スキルツリー草案の適用可否

草案は16ツリー分できているが、**yml への適用はしていない**。適用は主軸/枝の parent 付け替えと
buffs 差し替えを含む大きな構造変更なので、草案の承認が要る。

### 5-2. 生産草案の未決4件

1. **伐採 C-2 の `mining-fortune: 45`** — C-1 が `5` なのに対して9倍。異常値と判断して 15 に直したが、
   意図的な設計かもしれない。
2. **鍛冶 主軸A〜E の「品質+1」が未配線** — 全ノードで文言だけになっている。意図的な設計の可能性あり。
3. **スコープ外だった「buffs空」の穴埋め**（釣り A/C、農業 C/D、採掘・伐採・掘削 C）— 巻き戻すかどうか。
4. **エンチャント プレステージ「消費経験値量-10%」に対応するステが無い** — `enchant_exp_gain_bonus` へ
   読み替えたが、これは**獲得量が増える**という逆方向の意味。要意図確認。

### 5-3. 重量武器 D帯「スタン時間」

現行 effect-text は「スタン時間増加」だが、`stun-duration` 相当のステキーは存在しない。
新規実装を避けて「スタン率」へ文言を寄せる提案にしてあるが、本来「時間を伸ばす」意図だった場合は
Java 側の新規実装が要る。

### 5-4. ValhallaMMO 時代の死んだデータ

`skills/base/*_progression.yml` 全16ファイルに `messages` / `perks` / `leveling_perks` /
`starting_coordinates` が `<lang.…>` プレースホルダのまま残っている。
2026-07-22 のネイティブ移行で読まれなくなったはずだが、**ローダーがまだ黙って読んでいる可能性**があるため、
消すなら Java 側の確認が先。config データの削除なので独断では行わない。

### 5-5. 消費者ゼロのステキー

`tree_fell_cooldown_reduction` が `StatVocabulary` / `StatsCategory` に登録されているのに実コードの
消費者が1つも無い。将来機能のための先行登録かもしれないため、削除の是非は判断待ち。

### 5-6. lore.yml に表示定義が無いステ30件

`StatVocabulary` にあるが `lore.yml` に表示エントリが無いキーが30件ある。
`LoreComposer.specsInCategory` は lore.yml 定義のみを走査するため、**機能はするがアイテムに一切表示されない**。
30件分の表示名・文言はゲームデザインの判断なので、こちらでは決めない。

---

## 6. 配備（ユーザー操作が必要）

配備先への書き込みは権限ゲートで私からは実行できないため、コマンドはそちらで実行していただく必要がある。

**成果物の時刻**

| 成果物 | ビルド時刻 |
|---|---|
| `TrinityForge-0.1.0-SNAPSHOT-all.jar` | 07-26 09:47 |
| `ArsPaper-1.0.0.jar` | 07-26 09:10 |

**実サーバと内容が違う config（8件）**

```
combat/base-stats.yml
combat/mob-import.yml
skilltree/alchemy.yml
skilltree/heavy_weapons.yml
skilltree/light_weapons.yml
stats/gathering-efficiency.yml
stats/item-stats.yml
stats/lore.yml
```

**配備時の注意**

- `combat/mob-profiles.yml` は **絶対に上書きしないこと**。268KB の `importmobs` 生成物で、
  リポジトリ側の内容で潰すとダンジョンモブ定義が飛ぶ。
- EliteMobs フォークの配備は必ず全同梱 uberjar（`testbed/plugins/EliteMobs.jar`）を使う。
  `build/libs/*-min.jar` は MagmaCore が剥離されていて `DungeonLocator` の `NoClassDefFound` で起動不能。
- config だけでなく jar も変わっているため、**reload では足りず フルの再起動が必要**。
- config-editor 側の変更は Node プロセスの再起動で読み込まれる（jar 配備とは独立）。

---

## 7. バックアップ

git 管理外のため、変更前のコピーを以下に保存してある。

```
backups/20260726_skilltree/                         スキルツリー草案（戦闘）
backups/2026-07-26-tab-restructure/                 editor タブ再編 1回目（6ファイル）
tools/config-editor/backups/2026-07-26-tab-restructure-2/   タブ再編 追撃（3ファイル）
tools/config-editor/backups/2026-07-26-coverage/    キー穴埋め（4ファイル）
backups/2026-07-26-efficiency/                      効率統合
backups/2026-07-26-fork-mana/                       フォークのマナ配線
backups/2026-07-26-statscope/                       ステータス3分類
backups/2026-07-26-tier-ui/                         ティアテーブルUI
```

**例外1件**: `ArsNativeBridge.java` は作業手順の漏れでバックアップ無しに編集された。
**例外2件目**: §4 の通り `test/tf-crafting-features-companion-tabs.test.js` もバックアップ無しで書き換えられている。
