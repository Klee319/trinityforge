# git worktree で実装を並列化するときの規則

複数のエージェント／セッションで同時に実装を進めるための運用。**「ブランチを分ければ衝突しない」は誤り**で、
このリポジトリには構造的に衝突する少数のファイル（choke file）があり、そこだけを規則で捌く必要がある。

## worktree が解決する問題と、しない問題

**解決する**: 同一ワークツリーを複数セッションが共有すると、`git add -A` はもちろん `git status` の解釈すら
互いに壊れる（他人の未コミット変更が自分の差分に混ざる）。worktree ごとに作業ディレクトリが分かれるので
**この事故は構造的に消える**。テストも worktree ごとに独立して走る（`build/` は worktree ローカル）。

**解決しない**: マージ時の衝突。**衝突を避けるのはブランチ分離ではなく「ファイル所有権の分割」**。
下の choke file を 2 人以上が同時に触れば、worktree を使っていても必ず衝突する。

## ⚠️ worktree で「できない」作業

- **フォークの変更（EliteMobs / ArsPaper）。**
  `fork-handoff/arspaper/fork/` と `fork-handoff/elitemobs/elitemobs-fork/` は `.gitignore`（28〜32 行目）で
  除外された**別リポジトリ**なので、**新しい worktree には存在しない**。
  → フォーク作業は**メインのワークツリーで、直列に**行う。
- **配備・運用（`ops/`, `tmp/*.cmd` の実行）。** 配備先は 1 セットしかない。並列化する意味が無く、危険。
- ⚠️ **worktree で `releaseAssembly` を走らせても、フォーク用の compileOnly jar は本物のフォークに届かない。**
  `TrinityForge/build.gradle.kts` の `releaseAssembly` は `rootProject.projectDir.resolve("../fork-handoff/…/libs")`
  へコピーする。worktree にフォークは存在しないので、当然そこへは配れない。
  **TF の public API を変えたら、メインのワークツリーで `releaseAssembly` を打ち直すこと。**
  （かつては直前に無条件 `target.mkdirs()` していたため、**空の `fork-handoff/…/libs/` を新規作成して
  そこへ jar を置くだけ**という無言の失敗になっていた。現在は「フォークが実在するときだけ配る」に修正し、
  配れなかった場合は警告を出す。worktree だと検出できたときは「メインのワークツリーで打ち直せ」も併記される。）
- ⚠️ **worktree では 3 つのテストが必ず落ちる（フォークのソースを直接読んでいるため）。**
  `LegacyValhallaRuntimeContentTest`（`../fork-handoff/arspaper/fork/src/main/resources/materials.yml` を読む）と
  `SpellBreakMarkerDriftTest` の 2 件。**worktree で作業するエージェントに「テストを全緑にせよ」と
  指示してはいけない** — 直せない失敗を直そうとしてテストを壊す。
  ベースラインを先に取り、この 3 件は除外して比較すること。
- ⚠️ **`dev` の HEAD が単体でコンパイルできる状態でないと、worktree は使えない。**
  worktree は `dev` から切るので、メインのワークツリーの**未コミット変更に依存してビルドが通っている**状態だと、
  切った瞬間にコンパイルエラーになる（呼び出し側だけ commit されて新規ファイルが `git add` されていない、が典型）。
  並列化を始める前に `git worktree add` して `compileJava` が通るかを 1 回確かめること。

## choke file（所有者を 1 人に固定するもの）

| ファイル | なぜ衝突するか | 規則 |
|---|---|---|
| `reports/ACTIVE_RECORD.md` | 全員が末尾に追記する | **worktree では触らない。** マージ後に統合役が 1 回だけ書く |
| `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java` | 全機能の配線（`registerEvents`）が集まる | 各担当は**配線を書かず**、必要な配線を報告に書く。統合役がまとめて入れる |
| `config/domains/*Config.java` | 1 ドメイン 1 ファイルに `SchemaField` が並ぶ | ドメイン単位で所有者を 1 人に |
| `tools/config-editor/lib/constants.js` ＋ `public/js/constants.js` | **2 本セットで 1 単位**。しかも全設定が集まる | この 2 本は**常に同じ 1 人が**触る |
| `TrinityForge/src/main/resources/**/*.yml` | 同じ yml に複数人が節を足す | **yml ファイル単位で所有者を割る**（キー単位では割らない） |
| `docs/agent-context/*.md` | 全員が知見を足したくなる | マージ後に統合役が 1 回だけ |
| `.gitattributes` | `text eol=lf` を消すと全ファイルが差分化する | **触らない** |

`ACTIVE_RECORD.md` を `merge=union` にすれば機械的に両方残せるが、順序が壊れて履歴が読めなくなるので採用していない。

## 手順

```bash
# 1. dev を最新にしてから、作業ごとに worktree を切る（ディレクトリはリポジトリの外に置かない）
git worktree add ../tf-wt-combat  -b work/combat  dev
git worktree add ../tf-wt-tree    -b work/skilltree dev
git worktree add ../tf-wt-editor  -b work/editor  dev
```

```bash
# 2. 各 worktree で作業してコミット（触ったパスだけを add。-A は使わない）
git add TrinityForge/src/main/java/com/trinityforge/combat/Foo.java
git commit -m "fix(combat): ..."
```

```bash
# 3. 統合役が直列にマージする（rebase して 1 本にする。各段でテストを走らせない）
git switch dev
git merge --no-ff work/combat
git merge --no-ff work/skilltree
git merge --no-ff work/editor
```

```bash
# 4. 統合後に「1 回だけ」全テストを走らせる（ここが唯一の品質ゲート）
cd TrinityForge && ./gradlew test --offline "-Dorg.gradle.java.home=C:\Program Files\Java\jdk-21"
cd ../tools/config-editor && npm test
```

```bash
# 5. 片付け（worktree を消してからブランチを消す）
git worktree remove ../tf-wt-combat && git branch -d work/combat
```

## 割り当ての設計（ここが本質）

**機能で割らない。ファイルで割る。** 「装備耐久」と「スキルツリー描画」は機能としては独立でも、
両方が `TrinityForge.java` と `damage.yml` を触るなら並列化の利益は出ない。

割る前に必ずやること:
1. 各タスクが**触るファイルを先に列挙**する（実装前に。grep で当たりを付ける）。
2. リストが重なったタスクは**同じ worktree に入れる**（＝直列にする）。
3. choke file に触るタスクは**1 波に 1 つだけ**通す。
4. フォークを触るタスクは worktree に出さない。

## 自動化: `parallel-implement` ワークフロー

上の 1〜4 を自動でやるのが `.claude/workflows/parallel-implement.js`。タスク説明の配列を渡すと:

1. **Scout** — タスクごとに読み取り専用エージェントが「触るファイル」を調べる（実装はしない）。
2. **分割** — ファイル集合の**連結成分**でレーンを決める。ここは**モデルに判断させず JS でやる**
   （union-find。交わるタスクは同じレーンへ落ちる＝自動的に直列化される）。
   `fork-handoff/` `ops/` に当たるタスクは実装せず「直列送り」として差し戻す。
3. **Implement** — レーンごとに worktree を切って並列実装し、`work/wave-N` ブランチへコミットする。
   各エージェントには**自分が所有するファイルのリスト**を渡し、それ以外を触らせない。
   リスト外を触る必要が出たら、変更せず報告させる。
4. **Verify** — レーンごとに差分を反証レビューする。

返ってくるのは `mergeOrder`（マージすべきブランチの順序）と、各レーンの `wiringNeeded`（`TrinityForge.java`
の配線として統合役が書くべき内容）。**マージと `ACTIVE_RECORD.md` への追記は自動化していない** —
choke file を機械に触らせないための意図的な設計。

3 タスクが全部 `TrinityForge.java` を触るなら 1 レーンにまとまる。つまり
**「並列化しても無駄」であること自体が結果として出てくる**（無理に 3 本走らせて衝突させない）。

## 現実的な効き方（誇張しないこと）

- **効く**: 独立した Java パッケージ＋別々の yml＋テストが分かれている 3〜4 本。調査フェーズの並列化。
- **効かない**: 5 本以上。統合役のマージとテスト再実行が直列なので、そこが上限になる。
- **逆効果**: 同じ listener / 同じ yml / `TrinityForge.java` の配線を複数人が触る構成。
  マージ衝突の解決コストが実装コストを超える。

エージェント経由で使う場合、Agent ツールの `isolation: "worktree"` が同じことを自動でやる
（変更が無ければ worktree は自動削除される）。**ただし上の「worktree でできない作業」はそのまま当てはまる。**

## 中断からの復帰（レート制限で落ちたあと）

**波を始める前と、レート制限などで中断したあとは必ずこれを走らせる。**

```
powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\wip-audit.ps1
```

2026-08-01 に実際に踏んだ 3 つの事故を機械で検出する。どれも**人間の記憶では防げず、
気づくのが遅れると作業ごと捨てることになった**もの。

| 検出するもの | 実際に起きたこと |
|---|---|
| **持ち主のいない未コミット変更** | レート制限で落ちたエージェントが出荷 yml 17 本を書き換えたまま残し、それが「他セッションの WIP」だと誤認された。ドリフト検知テストが TF 5 件・editor 3 件落ち続け、`--config` 配備も半日封じられた。**中身は editor 保存によるコメント欠落と機能の巻き戻しで、追加ではなく破壊だった。** |
| **未マージブランチ同士の担当ファイル衝突** | 「ステ語彙の整理」を 2 つの波が独立に実装し、片方を捨てた。先に走っていたブランチの存在に気づいていなかった。 |
| **分岐点が古いブランチ・worktree** | worktree がリポジトリのほぼ初期スナップショット（dev から 187 コミット前）に作られ、その上での `grep` が「該当コードは存在しない」と出た。**調査結果そのものが誤り**になり、ユーザーへ誤った訂正を報告して往復した。 |

### 判明したときの手順

- **未コミット変更の持ち主が分からない**: 消す前に必ず退避する。`git stash push` はパスを指定できるので、
  他人の作業を巻き込まずに済む。
  ```
  git stash push -m "orphan-<日付>: <経緯>" -- <paths>
  ```
  **`git checkout --` で直接捨てない。** 今回は「破壊的な変更」に見えたものの中に、
  ユーザーが依頼した仕様変更の途中経過が混ざっていた。
- **担当ファイルが重なっている**: 後発のレーンを止めて、先行ブランチを先にマージする。
  両方走らせてから直すのが一番高くつく。
- **分岐点が古い**: そのブランチ上で出した**調査結果を全部破棄する**。直して使い回さない。
  エージェントに調査させるときは `git log --oneline -1` で自分の立ち位置を最初に言わせる。

## 関連

- [ops-build-deploy.md](./ops-build-deploy.md) — git 運用ルール、ビルド、配備
- [forks-and-mobs.md](./forks-and-mobs.md) — フォークがなぜ worktree に無いのか
- [config-editor.md](./config-editor.md) — ミラー 2 本セットの理由
