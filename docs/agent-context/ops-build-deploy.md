# ビルド・配備・運用の恒久知識

このリポジトリで TrinityForge（TF本体）およびフォーク（ArsPaper / EliteMobs）をビルド・配備・運用する際に、
毎回踏みうる落とし穴と、そのために存在する規約をまとめる。作業履歴ではなく、**今後も有効な不変条件**だけを載せる。

## Git 運用

### ⚠️ `git add -A` / `git commit -a` は禁止（並行セッション事故が実際に発生した）
このワークツリーは複数の Claude セッションが同時に触ることがある。`git add -A` はディスク上のその瞬間の全変更を、
`git commit -a` はインデックス内の全内容を対象にするため、**他セッションが編集中・stage 済みのファイルまで
自分のコミットに巻き込む**。実際に別セッションの編集ファイルが無関係なコミットへ混入した事例がある。
- 対策: 自分が触ったパスだけを明示して `git add <path1> <path2> ...` する。
- コミット自体もパス指定にする: `git commit -F <msgfile> -- <path1> <path2> ...`（インデックスの他の内容を無視できる）。
- 実行前に `git diff --cached --stat` で自分の意図したファイルだけか目視確認する。
- `git add --renormalize` も同じ理由でパス指定なしに使わない（`.gitattributes` 正規化目的でも glob を必ず指定し、
  `git diff --cached --ignore-all-space --name-only` で改行以外の差分が出たファイルは `git restore --staged` する）。

### ⚠️ パス指定の `git add` でも「同じファイル内の他人の編集」は防げない — HEAD が壊れる主因
上の対策は**ファイル単位**でしか効かない。`TrinityForge.java` のような choke file は
**1 つのファイルの中に複数セッションの編集が同居する**ので、自分の行だけを add することはできない。
結果として起きるのが「**呼び出し側だけ commit されて、実体（新規ファイル）は未追跡のまま残る**」状態。
作業ツリーはコンパイルできるので**誰も気付かない**が、**HEAD は壊れている** — クローンも CI も
git worktree も使えない（過去に 2 日・11 コミットにわたって HEAD がコンパイル不能だったことがある）。

- **新規ファイルを含む変更を commit したら、その場でクリーンな worktree にチェックアウトして
  `compileJava` を通すこと。** これが唯一の検出手段:
  ```bash
  git worktree add tmp/wt-check --detach HEAD
  cd tmp/wt-check/TrinityForge && ./gradlew compileJava --offline "-Dorg.gradle.java.home=C:\Program Files\Java\jdk-21"
  cd ../../.. && git worktree remove --force tmp/wt-check
  ```
- choke file を触ったときは、**その commit に必要な新規ファイルを `git status --porcelain | grep '^??'` で
  確認してから** add する。参照だけ入って実体が入らない事故はここで止まる。
- 壊れた後に「混入分だけ剥がす」のは**ほぼ不可能**。同じ文の中で編集が交ざるので、
  ハンク単位でも分離できない（実例あり）。**壊す前に検出するしかない。**

### ⚠️ `.gitattributes`（`text eol=lf`）を消さない
Windows 上の編集ツールがファイル全体を CRLF で書き戻すことがあり、実質数行の変更が「全行変更」の差分になって
レビュー不能かつ並行セッションと衝突しやすくなる。`*.java` / `*.js` / `*.yml` / `*.py` に `text eol=lf` を
指定してあるので、差分が異常に大きい（数十行の変更のはずが数百〜数千行）ときはまずこれが効いているか、
対象拡張子に含まれているかを疑う。`*.json` はリソースパック生成物が大半のため対象外にしてある。

### push 先の可視性は毎回確認する
```
gh repo view <owner>/<repo> --json visibility
```
過去に ArsPaper フォークが public だと知らずに push し、TF 本体の jar を意図せず公開した事故がある。
リポジトリ名や所有者から可視性を推測しない（同じ所有者でも public/private が混在する）。
`main` ブランチへの push はユーザーの明示指示があるときだけ。通常の作業ブランチは `dev`。

### フォーク（ArsPaper / EliteMobs）は別リポジトリ
`fork-handoff/arspaper/fork/` と `fork-handoff/elitemobs/elitemobs-fork/` はそれぞれ独自の `.git` を持つ別リポジトリ。
TF 本体リポジトリに含めようとすると gitlink（実体の無い参照）になるため、`.gitignore` で除外されている。
フォーク側の変更はフォーク自身のリポジトリでコミット・push する必要がある。

## ビルド

### ⚠️ JDK 取り違えで Gradle が意味不明なエラーで落ちる
Java が自動更新されると PATH の既定 `java` が新しい版（例: 25系）に奪われ、Gradle 8.x ベースの本プロジェクトは
起動不能になる。エラーはスタックトレースも「非対応の Java」という文言も出さず、以下の1行だけになる。
```
* What went wrong:
25.0.4
```
**バージョン番号だけの1行 = JDK 取り違えのサイン**。コード側やキャッシュ側を疑って時間を溶かさないこと。
回避は JDK 21 を明示指定する（マシンの `JAVA_HOME` 自体は空のまま触らない。他ツールへの影響を避けるため）。
```bash
gradlew.bat -p <project> -Dorg.gradle.java.home="C:\Program Files\Java\jdk-21" <task> --offline
```
「1 incompatible Daemon could not be reused」の直後に発症するのは、それまで動いていたデーモンが JDK 21 で
立っていただけ、という合図。Java が更新されるたびに再発しうる。

### ⚠️ `.cmd` / `.bat` に非ASCII文字を書かない
cmd.exe は UTF-8 のバッチファイルでファイル位置の計算を誤り、**行の途中から実行を始める**。`chcp 65001` でも
UTF-8 BOM でも直らない。小さいファイルだと再現しないことがあるため「動いているから大丈夫」は根拠にならない。
実際に日本語コメント入りの再起動ループスクリプトが、引数検査・存在確認・停止フラグ判定を全部素通りして
無限に空回りする事故があった。
- `.cmd`/`.bat` は ASCII のみで書く。日本語の説明・出力は `.ps1` か `.md` に置く（PowerShell は UTF-8 で問題ない）。
- 日本語を出力したい場合は `.cmd` を薄いラッパーにして本体を `.ps1` に書く。
- 日本語コミットメッセージを `.cmd` 経由で渡さない。別ファイルに書いて `git commit -F tmp/msg.txt` のように渡す。
- `find` / `timeout` は `%SystemRoot%\System32\...exe` で完全修飾する。GNU coreutils が PATH 前方にあると
  同名コマンドに乗っ取られ、Windows 構文を拒否して**チェックが黙って無効化される**。
- `echo` の中に `(` `)` を書くと `if (...)` ブロックが早期に閉じるバグを踏むことがある。

## 配備レイアウト

### ⚠️ 稼働中の jar 差し替えは必ず `NoClassDefFoundError` になる
JVM は未ロードのクラスを実行時に jar から読みに行くため、サーバ稼働中に jar を上書きした瞬間、次に新クラスへ
到達したタイミングで `NoClassDefFoundError`（例: `LoadResult` / `UseSkillDefaults` / `MaterialTier` 等）が発生する。
これはコードのバグではない。**`/reload` では直らず、JVM の完全な stop → start が唯一の復旧手段**。
発生箇所が進行データの保存経路（例: `ExecutorProgressionRepository.loadPerkIds`）だと、その間はデータ保存が
落ちたままになる。したがって:
- 配備スクリプトは jar のコピーを最初に行い、1本でも失敗したら config(yml) には触れずに中断する
  （config と jar がちぐはぐな状態を作らない）。
- jar を更新したら必ずフル再起動とセットにする。`/reload` で新クラスが増えるケースは救えない。
- `D:/game/minecraft/...` 配下への書き込みは権限ゲートで拒否される環境がある。配備スクリプトを書くところまでが
  実行側の仕事で、実際の適用（サーバ停止・起動）はユーザーに依頼する。

### 配備先の構成（3バックエンド、config はジャンクション共有、jar は実体ごとに複数回）
配備先は `Velocity_for_TF` 配下の `Main_Server` / `Resource_Server` / `Dev_Server` の3バックエンド。
- `Resource_Server` と `Dev_Server` の `plugins\TrinityForge` は **`Main_Server\plugins\TrinityForge` への
  NTFS ディレクトリジャンクション**。TF の yml 設定は **Main へ1回コピーすれば3台に反映される**。
- jar ファイルは3台とも**実体ファイル**なので、更新時は**3回コピー**が必要。`plugins\ArsPaper` も3台それぞれ
  実体ディレクトリなので、こちらの config（例: `unlock-gate.yml`）も3回コピーが必要。
- `tools/config-editor/tool-config.json` の `deployPaths` は Dev_Server 向き。TF はジャンクション経由で3台に
  効くが、**ArsPaper（`arspaper` 側）は Dev にしか届かない**ことに注意。
- ジャンクションに対して `rmdir /s` や `Remove-Item -Recurse` を実行すると**実体そのものが消える**。これが
  この構成で最も重大な事故ポイントなので、ディレクトリ削除は必ずジャンクション判定を行うヘルパー経由にする。

### EliteMobs フォークは必ず全同梱 uberjar を配備する
`build/libs/EliteMobs-*-min.jar` は shadow の最小化で MagmaCore 等が剥がされており、単体配備すると起動時に
`NoClassDefFoundError: com/magmaguy/magmacore/location/DungeonLocator` でプラグインごとロード失敗する。
配備には `testbed/plugins/EliteMobs.jar`（MagmaCore 全クラス + DungeonLocator 同梱の uberjar）を使う。
差し替え前に `unzip -l <jar> | grep -c magmacore` で数百クラス（min版は0）を確認してから配備すること。

## サーバ起動時の見落とし

### ⚠️ HuskSync の DB 接続失敗はサーバ起動を止めない
MariaDB / Redis への接続が失敗して `FailedToLoadException` → `ConnectException` が出ても、jar 自体は正常で
プラグインはロードされ config.yml も生成される。**起動プロセスとしては成功したように見える**ため、
ログを読まないと「インベントリ同期が効いていないまま運用する」事故になる。起動前に疎通・設定内容を
チェックするスクリプト（`ops/scripts/preflight.ps1` のような機械的な関門）を必ず通すこと。
`getRedisManager()` が null になる NPE が同時に出ることがあるが、これは初期化失敗時の後始末処理側のバグで
無害。原因ではないので追いかけない。

### Velocity の `/server` にバックエンドごとの権限ノードは無い
Velocity の `ServerCommand` が参照する権限文字列は `velocity.command.server` の1つだけで、行き先ごとの
ノード（`velocity.command.server.<name>` 等）は存在しない。判定は「未設定＝許可」なので、権限プラグインで
特定バックエンドだけを立入禁止にすることはできない。バックエンド側の `white-list=true` + `whitelist.json` が
唯一の手段。LuckPerms を使う場合、`config.yml` の `server: global` は「コンテキスト無しの永続付与」を意味し
**全サーバに効いてしまう**。per-server 権限を機能させるには `server` コンテキストにバックエンド名
（`main`/`resource`/`dev` 等）を個別に入れる必要がある。

## 残タスク・進捗の管理ルール

### ⚠️ 残タスク・バグ・作業履歴は `reports/ACTIVE_RECORD.md` だけを参照・追記する
日付入りの個別レポートを新たに増やさない。過去に47本のレポートに分散して「どれが現役か分からない」状態に
なり、判断待ちとして繰り越されていた項目の相当数が既に実装済みだったことがある。作業内容の詳細を別紙に
書く場合でも、**開いている項目の一次情報は必ず ACTIVE_RECORD へ反映**する。項目を閉じるときは行を消さず
取り消し線＋解決日と根拠で残す（同じ調査を二度やらないため）。**着手前に必ず実コードで裏を取ること**
（この記録自体も古くなりうる）。

### ユーザーへの報告は管理番号だけで済ませない
`W-8` / `J-7` / `K-5` のような ACTIVE_RECORD の管理番号だけを書いて報告しない。番号はこちらが記録を引くための
索引でしかなく、ユーザー側には意味が無い。「W-4: ars_magic のマナ系4キーが効いていない件」のように、
**番号＋タスク内容（日本語）** をセットで書く。

### 「機構は実装済みで config が未設定なだけ」は残タスクにしない
死んでいるように見えるステータス・設定項目は2種類に分けて扱う。
- 配線（実装）はあるが config に値が入っていないだけ → **残タスクにしない**（運用側の裁量なので握りつぶす）。
- config や設計に項目として存在するのに、配線（実装）自体が無い → **残す**（プレイヤーを騙す状態なので放置しない）。
起票前に「実装が無いのか、値が入っていないだけなのか」を実コードで確認すること。
一方でユーザーが報告する「UI が崩れる」は判断待ちの設計選択ではなく**実バグ**として扱う。

## 関連
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)
- [./forks-and-mobs.md](./forks-and-mobs.md)
