# TrinityForge — 作業の入口

Minecraft **Paper 1.21.11 / Java 21** 向けの大規模プラグイン群。TF 本体に加えて
**EliteMobs / ArsPaper の 2 つのフォーク**、Node.js 製の **GUI 設定エディタ**、
**Velocity 3 バックエンド構成の運用スクリプト**を含む。

## 最初に読むもの（順番厳守）

1. **`reports/ACTIVE_RECORD.md`** — 残タスク・既知バグ・作業履歴の**唯一の一次情報**。
   ここ以外（`reports/` の日付入りレポート、`docs/design/`）は stale。**新しいレポートを増やさない。**
2. **`docs/agent-context/README.md`** — 触る前に知らないと黙って壊す知識の索引。
   - どのドメインでも必読: `docs/agent-context/common-traps.md`（API とテストの罠）
   - コマンドを打つ前に必読: `docs/agent-context/ops-build-deploy.md`（ビルド／配備／git）
3. 触る領域のドメイン文書（`docs/agent-context/combat.md` など）

## 絶対に守ること

- **`git add -A` / `git commit -a` は禁止。** 同一ワークツリーで**複数のセッションが並行して作業する**運用なので、
  他人の未コミット変更を巻き込む。**自分が触ったパスだけを列挙して `git add` する。**
- **push 先は `dev`。`main` はユーザーの明示指示があるときだけ。**
- **このリポジトリは public（`Klee319/trinityforge`）。** jar・秘密・サーバ設定の実値を commit しない。
  push 前に `gh repo view --json visibility` で確認する。
- **フォークの push 先は `trinityforge` リモート。`origin` は上流（MagmaGuy/EliteMobs 等）なので絶対に push しない。**
- **稼働中サーバの jar を差し替えると必ず `NoClassDefFoundError` になる。** JVM 再起動以外に復旧手段は無い。
  配備はサーバ停止後に行う。
- **`D:/game/minecraft/...` への書き込みはエージェントの権限ゲートで拒否される。**
  配備スクリプトはエージェントが書き、**実行はユーザーが行う**。
- **`ops/` 配下の `.cmd` は ASCII のみ。** 非 ASCII を書くと cmd.exe が行の途中から実行を始める。
- **カレントディレクトリ外にファイルを作らない**（一時ファイルも `tmp/` 内で完結させる）。
- **yml 内のコメントは日本語で書く。**

## よく使うコマンド

TF 本体のビルド（**JDK を取り違えると Gradle が `25.0.4` だけ吐いて落ちる**ので `java.home` を必ず渡す）:

```bash
cd TrinityForge && ./gradlew releaseAssembly --offline "-Dorg.gradle.java.home=C:\Program Files\Java\jdk-21"
```

TF 本体のテスト:

```bash
cd TrinityForge && ./gradlew test --offline "-Dorg.gradle.java.home=C:\Program Files\Java\jdk-21"
```

設定エディタのテスト:

```bash
cd tools/config-editor && npm test
```

フォークのビルドと配備 jar の選び方は `docs/agent-context/forks-and-mobs.md`、
配備レイアウト（3 バックエンド／config はジャンクション共有）は `docs/agent-context/ops-build-deploy.md`。

## リポジトリの地図

| パス | 中身 |
|---|---|
| `TrinityForge/src/main/java/com/trinityforge/` | 本体。`combat/` `progression/` `skilltree/` `listeners/` `config/domains/` など |
| `TrinityForge/src/main/resources/` | **出荷 yml が真源**（generator 側は stale なことがある） |
| `TrinityForge/src/test/java/` | JUnit + MockBukkit |
| `fork-handoff/elitemobs/`, `fork-handoff/arspaper/` | フォーク。**ソースは `.gitignore` で除外** → クローンにも新しい worktree にも存在しない |
| `tools/config-editor/` | Node.js 製 GUI 設定エディタ。`lib/` と `public/js/` の**ミラー 2 本を必ず両方更新** |
| `ops/` | 運用スクリプト・RUNBOOK |
| `reports/ACTIVE_RECORD.md` | 残タスクの一次情報 |
| `docs/agent-context/` | 恒久知識ベース |
| `.claude/agents/` | ドメイン別サブエージェント定義 |
| `.claude/workflows/` | 反復ワークフロー（`triage-reports` / `audit-drift` / `verify-diff` / `parallel-implement`） |

## 並列作業

複数のエージェント／セッションで同時に実装する場合は **`docs/agent-context/parallel-worktrees.md`** に従う。
要点だけ: **worktree はワークツリー衝突を消すがマージ衝突は消さない**（避けるのはファイル所有権の分割）。
`reports/ACTIVE_RECORD.md` / `TrinityForge.java` の配線 / `config-editor` の `constants.js` 2 本は
**1 波に 1 人しか触れない**。**フォークは `.gitignore` 除外なので worktree には存在せず、並列化できない。**
この割り当てを自動でやるのが `.claude/workflows/parallel-implement.js`
（触るファイルを先に調べ、交わるタスクを同じレーンへ落としてから worktree で並列実装する）。

## 進め方の型

1. **実コードで裏を取る。** `ACTIVE_RECORD.md` の記述も腐る（棚卸しで 15 件が「既に実装済み」だった）。
2. **原因を特定してから直す。** このコードベースの不具合は「設定ミス」に見えて実際は
   配備漏れ・ビルド漏れ・イベント順序であることが多い。
3. **テストを実走して結果を貼る。** 「通ったはず」は通っていない。
   MockBukkit は未実装 API を **SKIPPED に化けさせる**ので、スキップ数も必ず見る。
4. **`reports/ACTIVE_RECORD.md` に追記して閉じる。** 項目を消さず取り消し線＋解決根拠を残す。
