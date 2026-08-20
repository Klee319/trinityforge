# agent-context — エージェント／新任開発者向けの恒久知識ベース

このディレクトリは **「このリポジトリで作業するときに知らないと黙って壊す知識」** だけを集めた場所です。
以前はこの知識が特定の開発マシンのローカル（Claude のメモリ）にしか無く、**クローンした人・別の AI
エージェントは毎回同じ落とし穴を再発見していました**。それをリポジトリ内へ移したものがここです。

## この文書群の役割（他の文書との線引き）

| 置き場所 | 中身 | 更新タイミング |
|---|---|---|
| **`docs/agent-context/`（ここ）** | **恒久的な落とし穴・設計上の不変条件・確定仕様**。「なぜそうなっているか」と「どうすべきか」 | 新しい落とし穴を踏んだとき／設計が変わったとき |
| `reports/ACTIVE_RECORD.md` | **残タスク・既知バグ・現在の状態の唯一の一次情報**（207 行に絞ってある） | 作業ごと（毎回） |
| `reports/ACTIVE_RECORD_ARCHIVE.md` | 過去の作業記録と解決根拠。**残タスクの一次情報ではない** | 現役側の §7 が長くなったときだけ移す |
| `docs/*.md`（SPEC 系） | 機能仕様書。決定台帳は `OPEN_DECISIONS.md`（LD-* をコードが参照している） | 仕様変更時 |
| `docs/design/` | 設計判断のスナップショット（日付入り） | 設計時のみ（後から直さない） |
| **`docs/archive/`** | **失効した設計文書。正典として読んではいけない**（理由は同ディレクトリの README） | 増えるだけ |
| `docs/config-reference/` | yml キーのリファレンス。**yml 本文コメントは editor 保存で消えるのでこちらが正** | キー追加時 |

**「今どうなっているか」を知りたいときは `reports/ACTIVE_RECORD.md`。
「触る前に何を知っておくべきか」を知りたいときはここ。** 混ぜないでください。

## 読む順番

1. **[common-traps.md](./common-traps.md)** — Paper/Bukkit API とテストの共通落とし穴。**どのドメインを触る場合でも必読**
   （MockBukkit がテストを「失敗」ではなく SKIPPED に化ける件はここ）
2. **[ops-build-deploy.md](./ops-build-deploy.md)** — ビルド／配備／git 運用ルール。**コマンドを打つ前に必読**
   （JDK 取り違え・稼働中の jar 差し替え・`git add -A` 禁止）
3. 触る領域の文書:
   - [combat.md](./combat.md) — 戦闘・ステータス（ダメージ式、守備力、マナ、属性、モブスケール）
   - [progression-skilltree.md](./progression-skilltree.md) — スキルツリー、EXP、パーク、アチーブメント、採取
   - [forks-and-mobs.md](./forks-and-mobs.md) — EliteMobs / ArsPaper フォークとモブ系
   - [config-editor.md](./config-editor.md) — `tools/config-editor` の GUI 設定エディタ
   - [bedrock-geyser.md](./bedrock-geyser.md) — 統合版（Bedrock / Geyser / リソースパック）
4. 複数エージェント／セッションで同時に実装するなら
   **[parallel-worktrees.md](./parallel-worktrees.md)** — git worktree での並列化と衝突しないための所有権規則

## 書き足すときのルール

- **作業履歴は書かない。** 「誰がいつ何を直したか」は `reports/ACTIVE_RECORD.md` の役割。
  ここに書くのは **次に同じ場所を触る人が知らないと壊すこと** だけ。
- 1 項目 = 「何が起きるか」→「なぜそうなるか（機構）」→「どうすべきか」。3〜8 行。
- 黙って壊れる／すでに再発したものだけ見出しに `⚠️` を付ける。付け過ぎると意味を失う。
- コードの位置は `ClassName#method` / `path/to/File.java` の形で添える。**推測で書かない。**
- 古い記述と矛盾する事実が判明したら **新しい方に書き換え、古い説は「※かつて〜と誤診した」1 行だけ残す**
  （同じ誤診の再発防止になるので消さない）。
- 失効した内容は消す。「昔はこうだった」を残すのは誤診の記録だけ。

## 関連

- 作業の入口 → リポジトリルートの [CLAUDE.md](../../CLAUDE.md)
- ドメイン別サブエージェント → [.claude/agents/](../../.claude/agents/)
- 反復ワークフロー → [.claude/workflows/](../../.claude/workflows/)
