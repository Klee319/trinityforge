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
- ⚠️ **worktree で `releaseAssembly` を走らせると、フォーク用の compileOnly jar が本物のフォークに届かない。**
  `TrinityForge/build.gradle.kts` の `releaseAssembly` は `rootProject.projectDir.resolve("../fork-handoff/…/libs")`
  へコピーし、その直前に `target.mkdirs()` する。worktree では**空の `fork-handoff/…/libs/` を新規作成して
  そこへ置くだけ**で、エラーも警告も出ない。TF の public API を変えたら**メインのワークツリーで
  `releaseAssembly` を打ち直す**こと。

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

## 現実的な効き方（誇張しないこと）

- **効く**: 独立した Java パッケージ＋別々の yml＋テストが分かれている 3〜4 本。調査フェーズの並列化。
- **効かない**: 5 本以上。統合役のマージとテスト再実行が直列なので、そこが上限になる。
- **逆効果**: 同じ listener / 同じ yml / `TrinityForge.java` の配線を複数人が触る構成。
  マージ衝突の解決コストが実装コストを超える。

エージェント経由で使う場合、Agent ツールの `isolation: "worktree"` が同じことを自動でやる
（変更が無ければ worktree は自動削除される）。**ただし上の「worktree でできない作業」はそのまま当てはまる。**

## 関連

- [ops-build-deploy.md](./ops-build-deploy.md) — git 運用ルール、ビルド、配備
- [forks-and-mobs.md](./forks-and-mobs.md) — フォークがなぜ worktree に無いのか
- [config-editor.md](./config-editor.md) — ミラー 2 本セットの理由
