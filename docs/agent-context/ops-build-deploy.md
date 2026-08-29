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

### ⚠️⚠️ `git stash push -- <path>` はbaseline比較のつもりでも他セッションの未コミット変更を巻き込む

**2026-08-20 (W-177) から `.claude/hooks/pre-guard.py` がこれを deny する。**
HEAD 基準でテストを走らせたいなら `ops/scripts/run-against-head.ps1`（必ず戻す）を使う。
理由と実害は `common-traps.md` の「ワーキングツリーの未コミット yml は…ユーザーの設定」。
「自分の変更前後で失敗数が変わるか」を確かめる目的で対象ファイルを `git stash push -- <paths>` すると、
そのpathに**同時進行中の別セッションの未コミット編集が同居していた場合、それも一緒にHEADへ巻き戻す**
（`git add -A` と同じ「pathは絞れてもファイル内の他人の編集は防げない」問題がstashにも当てはまる）。
`git stash pop` で復元できるので即時破棄ではないが、stash中の一時的な状態でビルド/テストが走る間、
他セッションの意図した変更（例: 61件あったはずのダンジョンゲートを28件に縮小する未コミットWIP）が
消えた状態になる。**同一ワークツリー上でHEAD基準のbaselineが欲しいだけなら `git stash` を使わず
`git show HEAD:<path>` で読むか、`git diff HEAD -- <path>` で差分だけ確認する。** stashしてしまった場合は
即座に `git stash pop` し、`git diff stash@{0}` で退避内容が本当に自分の変更だけだったかを必ず確認する
（2026-08-08、ダンジョンの鍵分割作業で `dungeon/gates.yml` 等6ファイルをbaseline比較用にstashした際、
作業ツリーがHEAD比で33ダンジョン少ない縮小版だったことに気づかず、復元し忘れれば他セッションの
作業内容を消していた）。

### yml の choke file に他人の編集が乗っているとき、自分の追加分だけを commit する手順
`items/catalog.yml` のような大きな yml は、**別セッションの config-editor 保存によるコメント削除や
`_editor:` カテゴリの入れ替えが先に乗っていることがある**（editor の保存は yml を再シリアライズするため）。
そのまま `git add <path>` するとその削除ごと commit してしまう。**自分の追記が独立したブロックなら**
以下で分離できる（2026-08-04 に実際に使用。`git apply --cached` は改行や文脈のずれで通らないことが多いので、
blob を組み立てて index へ直接書くほうが確実）。

```bash
git show HEAD:<path> > tmp/head.yml
# tmp/head.yml に自分の追加行だけを挿入して tmp/staged.yml を作る（アンカー行で位置を固定する）
HASH=$(git hash-object -w tmp/staged.yml)
git update-index --cacheinfo 100644,$HASH,<path>
git diff --cached --numstat -- <path>   # 追加行数だけ / 削除0 を必ず確認する
```

- **`git commit -- <path>` を使ってはいけない。** pathspec 付き commit は index ではなく
  **作業ツリーの内容**を commit するので、せっかく分離した index が捨てられて他人の削除が入る。
  index をそのまま commit する `git commit -F <msgfile>`（pathspec なし）を使うこと。
- 作業ツリー側は他人の変更を乗せたまま残る（`git status` で `MM` になる）。**これが正しい状態**で、
  持ち主のセッションが処理する。

#### ⚠️⚠️ pathspec なし commit は「index にあるもの全部」を取る — 直前に必ず index を掃除する（2026-08-20）

上の手順は `git commit -F <msgfile>`（pathspec なし）を使うが、**共有ワークツリーでは index も共有**なので、
**別セッションが `git add` した分がそこに乗っている**。2026-08-20 に実際に踏んだ:
自分の 2 ファイルを add してから commit するまでの短い間に、別セッションが
`stats/item-stats.yml`・`WeaponTierParityTest.java`・`ACTIVE_RECORD.md` を stage しており、
**その 3 つが自分の commit に丸ごと入って push された**（内容は失われないが、他人の作業が
自分のコミットとして dev に載り、未完成の config が出荷対象になる）。

```bash
git diff --cached --name-only          # ← 自分のパスだけか確認する
git restore --staged <他人のパス>       # ← 乗っていたら降ろす（作業ツリーは触らない）
```

- **確認と commit を `&&` で 1 行に繋がない。** 繋ぐと一覧は出るが、見て止める余地が無い
  （実際にこれで通してしまった）。**別々のコマンドとして実行し、目で見てから commit する。**
- `git restore --staged` は index だけを戻すのでフックも通す（`git restore <path>` は deny）。

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

### `.claude/launch.json` に使い捨てエントリを溜めない
Bash が使えない状況では `.claude/launch.json` にエントリを足して `preview_start` で任意のコマンドを
走らせられる（実際にそうやって復旧した経緯がある）。ただし**そのエントリは `tmp/*.cmd` を指しており、
`tmp/` は `.gitignore` 除外**なので、**クローンした人の環境では 1 つも動かない**。
過去に 126 エントリ（744 行）まで溜まり、実質すべてがこのマシン専用の死んだ設定になっていた。
- 使い捨てエントリは**使い終わったら消す**。版管理に残すのは `tmp/` を参照しない恒久エントリだけ
  （現在は `config-editor` の 1 本のみ）。
- 一時的に足す場合も、同じセッション内で片付ける前提で扱う。

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

### ⚠️ `goto` / `call :label` を使う `.cmd` は CRLF でなければラベルが見つからない
LF のみの改行で書くと cmd.exe がラベルを解決できず
`The system cannot find the batch label specified - <name>` で落ちる（2026-07-31 実測。
`ops/launch/deploy.cmd` を LF で書いて踏んだ）。**`.gitattributes` は `*.cmd` を `text eol=lf` の
対象にしていない**ので、CRLF のままコミットされる（＝直した状態を保てる）。ラベルを使わない
`.cmd` は LF でも動くため、`ops` 配下は LF と CRLF が混在している。この混在は事故ではなく、
「ラベルを使う 2 本（`server-loop.cmd` / `deploy.cmd`）だけが CRLF」という状態が正しい。
既存 `.cmd` に `goto` を足すときは、同時に CRLF へ変換すること。

### ⚠️ エージェントのシェルから `start-all.cmd` を叩くと、サーバ間の待ち時間がゼロになる
`start-all.cmd` は `timeout.exe /t <秒> /nobreak` でバックエンドの起動間隔を空けている。
ところが**エージェントの非対話シェルは stdin がリダイレクトされている**ため、`timeout.exe` は
`ERROR: Input redirection is not supported, exiting the process immediately.` を出して**即座に終了する**。
`||` でも `errorlevel` でも拾っていないので、スクリプトは何事もなかったように次のサーバを起動し、
**main → resource → dev → Velocity が数秒以内に全部立ち上がる**（2026-08-06 実測）。

- 起動順そのものは守られるが、`start-all.cmd` のコメントが挙げている 2 つの前提が崩れる:
  ①`plugins/TrinityForge` は 3 台でジャンクション共有なので、初回のスキーマ移行が同時に走りうる
  ②Velocity がバックエンドより先に上がると「繋がるが移動できない」状態が一瞬できる
- 2026-08-06 の実測ではどちらも実害は出なかった（DB エラー無し、全台 `Done (…)!`、Velocity も正常）が、
  **無人で回すなら待ちを別手段にする**（`timeout.exe` を `powershell -NoProfile -Command "Start-Sleep -Seconds N"`
  に置き換えるか、各サーバのログに `Done (` が出るまで待つ）。
- 実行後は必ず 3 台とも `Done (` が出ているか、`[TrinityForge]` の WARN/ERROR が増えていないかを確認する。

### ⚠️ `.cmd` の `shift` は `%0` もずらす — その後の `%~dp0` はスクリプトの場所ではない
オプション解析ループで `shift` を使うと `%0` が消費した引数に置き換わり、以降の `%~dp0` は
「その引数文字列をカレントディレクトリ基準で解決したパス」になる。結果、
`call "%~dp0stop-all.cmd"` のような兄弟スクリプト呼び出しが**引数を 1 つ渡した瞬間に壊れる**
（引数なしでは動くので気付きにくい）。対策は 2 つとも入れる: 先頭で `set "SELF=%~dp0"` を取る、
`shift` ではなく `shift /1` を使う。

### ⚠️ java.exe のコマンドラインでは「どのバックエンドが動いているか」を判定できない
`server-loop.cmd` は `cd /d <server root>` してから `java -jar "paper-....jar" nogui` を実行するため、
プロセスのコマンドラインにサーバ Root もディレクトリ名も現れない。したがって
`show-status.ps1` / `purge-player-data.ps1` のコマンドライン照合は当たらず、
**`launch\status.cmd` の main / resource / dev は稼働中でも「停止」と表示する**
（2026-07-31 実測。RCON ポートと `session.lock` では 3 台とも稼働中だった）。
稼働判定は `ops/scripts/check-servers-stopped.ps1` の 2 系統
（RCON ポートの LISTEN ／ `world\session.lock` の排他ロック）を使う。
「java.exe があるか」で判定するのも不可 — Gradle デーモンも java.exe なので、
ビルドを走らせた直後は永久に配備できなくなる。

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
- ビルドから配備までを 1 本にしたのが `ops/launch/deploy.cmd`（更新のあるものだけビルド →
  実機 jar と SHA256 が違うものだけコピー）。EliteMobs はビルド SKIP でも
  `testbed/plugins/EliteMobs.jar` が実機と違えば main / dev へ配る。詳細と更新判定の限界は
  `ops/RUNBOOK.md` 手順 13-5。
  `launch` フォルダ自体を配置先へコピーするのは別スクリプト `ops/launch/deploy-launch.cmd`。
- **配備先のファイル名は「今入っている名前」に合わせる。** ステージされる成果物は
  `TrinityForge-all.jar` だが実機は `TrinityForge-0.1.0-SNAPSHOT-all.jar`。成果物名でコピーすると
  同一プラグインの jar が 2 つ並び、Paper が `Ambiguous plugin name` で起動不能になる。
- **EliteMobs は Resource_Server には入れない**（`ops/PLUGIN_MATRIX.md`）。配備は
  「既にその jar があるバックエンドだけ上書きする」方式にして、新規インストールはしない。
- jar を置き換えたら `plugins/.paper-remapped/<同名>` を消す。EliteMobs は remap 対象で、
  `paper-plugin.yml` 方式の TF / ArsPaper はそこに現れない。
- ジャンクションに対して `rmdir /s` や `Remove-Item -Recurse` を実行すると**実体そのものが消える**。これが
  この構成で最も重大な事故ポイントなので、ディレクトリ削除は必ずジャンクション判定を行うヘルパー経由にする。

### EliteMobs フォークは必ず全同梱 uberjar を配備する
`build/libs/EliteMobs-*-min.jar` は shadow の最小化で MagmaCore 等が剥がされており、単体配備すると起動時に
`NoClassDefFoundError: com/magmaguy/magmacore/location/DungeonLocator` でプラグインごとロード失敗する。
配備には `testbed/plugins/EliteMobs.jar`（MagmaCore 全クラス + DungeonLocator 同梱の uberjar）を使う。
差し替え前に `unzip -l <jar> | grep -c magmacore` で数百クラス（min版は0）を確認してから配備すること。

### バグ報告を見たら「デプロイ済み jar のタイムスタンプ」と「該当修正コミットの時刻」を先に突き合わせる
ユーザー報告の症状が「もう直したはずの不具合」に見えるとき、まず配備先 jar の `LastWriteTime` と、
関連コミットの `git log --format=%ad` を突き合わせる。配備先が権限ゲートで書き込み不可でも**読み取りは可能**
（`Get-ChildItem` でタイムスタンプだけ見れば十分、中身を展開する必要はない）。
```powershell
Get-ChildItem 'D:\game\minecraft\PaperServer\Velocity_for_TF\Main_Server\plugins' -Filter '<Plugin>*.jar' |
  Select-Object Name, LastWriteTime
```
配備 jar の時刻が該当修正コミットより古ければ、報告された症状は「未配備」で説明が付き、コード側を
再調査する前に切り分けが終わる。フォーク（ArsPaper 等）は commit 時刻とビルド時刻がほぼ一致する
（`gradlew build` は commit 直後に走らせる運用のため）ので、フォーク側は `fork-handoff/.../fork` の
`git log --format="%h %ad %s" --date=iso` と突き合わせるだけでよい。TF本体側は配備が別タイミングで
走るため、`reports/ACTIVE_RECORD.md` の配備記録（「配備完了」エントリの時刻）と合わせて確認すること。

### ⚠️ ArsPaper の `materials.yml` は `deploy.cmd --config` でだけ反映される（jar 差し替え・`/ars reload` では反映されない）

`MaterialConfigManager#load` は
```java
File file = new File(plugin.getDataFolder(), "materials.yml");
if (!file.exists()) plugin.saveResource("materials.yml", false);
```
だけで、**独自マージ機構が無い**。`saveResource(..., false)` は「既存ファイルがあれば何もしない」
標準 Bukkit 挙動なので、`plugins\ArsPaper\materials.yml` が既に在るサーバでは
**新しい jar を入れ替えても `/ars reload` しても、追加した素材は1件も現れない**。
**2026-08-05 訂正: ここには以前「TF 本体の `TrinityForgeConfigMigration.appendMissingKeys` に相当する
追記機構は Ars 側に存在しない」と書いてあったが、そのクラスは TF にも存在しない**
（`grep 'class .*Migration|appendMissing' TrinityForge/src/main/java` が 0 件。
TF の全 `ConfigDomain` も `saveResource(PATH, false)` だけを呼ぶ）。
つまり **TF 側も同じ**で、既にファイルがあるサーバでは jar に入っている yml は一切読まれない。
この性質は配備の副作用として有用: **jar をワーキングツリーからビルドしても、
そこに混ざっている他セッションの未コミット yml がサーバの config へ漏れることはない**
（config は下記 `deploy-config-head.cmd` の経路だけで変わる）。

反映させる唯一の経路は `ops\launch\deploy.cmd --config`。`:deploy_config_ars` が
`fork-handoff/arspaper/fork/src/main/resources` から `plugins\ArsPaper` へ `*.yml` を
robocopy で**上書き**する（除外は `paper-plugin.yml` / `sourcejars.yml` / `sourcelinks.yml` の3つだけ。
後2つは稼働サーバが書く LIVE STATE なので意図的に除外されている）。

- したがって **`--config` を付け忘れると「素材を追加したのに実機に出てこない」が無言で起きる。**
  `materials.yml` を触った変更は配備手順に必ず `--config` を含めること。
- 逆に `--config` はサーバ側 `materials.yml` を丸ごと上書きするので、
  サーバ側を直接手編集していた場合はその編集が消える（editor はリポジトリ側を編集するので通常は問題ない）。

### ⚠️ config を配るときは `deploy-config-head.cmd`（HEAD を配る）を使う（2026-08-05）

このワークツリーは複数セッションで共有しているので、`resources` 配下の `*.yml` には
**常に他セッションの編集途中が混ざっている**。config 配備の手段は 3 つあるが、正しいのは 3 番目だけ。

| スクリプト | 配るもの | この状況での外れ方 |
|---|---|---|
| `deploy.cmd --config` | ワーキングツリー | **他セッションの未完成 yml をそのまま出荷する** |
| `deploy-config-skip-wip.cmd` | ワーキングツリー − 未コミットのファイル | **自分の変更と他人の WIP が同じファイルに乗っていると自分の変更も落ちる**／**フォークの WIP は検出できない** |
| **`deploy-config-head.cmd`** | **HEAD（フォークは自前リポジトリの HEAD）** | — |

`skip-wip` の 2 つの外れ方は 2026-08-05 に両方とも実際に起きていた。

- 落ちる側: `stats/lore.yml` は「職業EXP増加 12 件の表示定義（コミット済み）」と
  「別セッションの `name:` 改名（未コミット）」が同居していた。ファイル単位の除外なので、
  除外すると**コミット済みの 12 件も配備されず、ステータス画面に一切出ない**。
- 検出できない側: `fork-handoff/arspaper/fork` は外側リポジトリから `.gitignore` 除外なので
  `git status -- fork-handoff/...` は**常に空**を返す。そのとき `materials.yml` には
  別セッションの未コミットのレシピ変更（`stone_1x` を workbench→ritual 等）が乗っていた。
  除外リストが空なので、そのまま出荷されていた。

`deploy-config-head.cmd` は「除外する」のをやめて**配る内容を HEAD と定義する**。
`ops/scripts/export-head-config.ps1` が `git archive` の tar 経由で HEAD を
`tmp/deploy-head/{tf,ars}` へ展開し（`git show` 経由だと PowerShell の文字列化で UTF-8 の
日本語コメントと改行が壊れるので tar が必要）、robocopy はそこから配る。
フォークは自前リポジトリなので `git -C <fork> archive` で別途取り出す。
**「今回配らなかった未コミット yml」の一覧を毎回表示する**ので、取り残しが目に見える。

#### 2026-08-18（W-106）既定は「HEAD ＋ ワーキングツリーの yml を重ねる」に変わった

上の「HEAD だけを配る」には実運用で致命的な副作用があった。**設定エディタ
（`tools/config-editor`）の保存先はリポジトリの `src/main/resources`** なので、
**エディタで直しただけの変更は未コミット**であり、次の config 配備で HEAD の内容に
無言で上書きされる。ユーザーからは「設定が勝手にロールバックした」としか見えない
（実例: ヴォルカニックソースリンクの解放ゲートを外したのに配備のたび復活した = W-101）。

そこで既定を **「HEAD を土台にして、ワーキングツリーの yml を上から重ねる」** に変えた。
- **未コミットの yml はそのまま出荷される。** ワーキングツリーに残ってよい未コミット yml は
  **人がエディタで編集したものだけ**。**エージェントが config を直接編集したら、その作業のコミットに必ず含める。**
- 重ねたファイルは**毎回一覧表示される**。身に覚えのないファイルがあれば他セッションの WIP。
- 従来どおりコミット済みだけを配りたいときは `--head-only`。

#### 2026-08-19（W-110）配備先で直接編集された yml を上書き前に退避する

W-106 のオーバーレイが守るのは**リポジトリのワーキングツリーだけ**で、配備は今も
**リポジトリ → サーバの一方向**。そして**エディタは全ての yml に画面を持っていない**
（`network.yml` には無い）。画面が無い yml を直す唯一の方法は「配備先の実ファイルを直接編集する」で、
その編集は次の配備で**無言で上書きされ、バックアップも無かった**（2026-08-19 に `network.yml` で発生）。

`ops/scripts/guard-deployed-config.ps1` を追加し、`deploy-config-head.cmd` のステップ 3/4 に入れた。

- **判定方法**: 配備の最後に `-Mode Record` で「配備直後の配備先 yml の SHA-256」を
  `tmp/deploy-config-manifest.json` に残す。次回の `-Mode Check` がそれと現物を突き合わせ、
  **前回配備の後に変わったもの＝サーバ側で編集されたもの**を `[DRIFT]` として名指しする。
- **退避**: `[DRIFT]` は上書き前に `tmp/deploy-config-backup/<日時>/` へコピーされる。
- **台帳が無いとき**（初回・`tmp` を消した後）は `[UNKN]` を出し、**現物を丸ごと退避**して安全側に倒す。
- **配備は止めない。** 退避してあるので失われないし、止めると通常運用まで巻き込む。
- 前回配備の後に**増えた** yml は `[NEW]` として出す（配備では消えない。プラグインが自分で書くものも来る）。
- TF は `TF_CONFIG_HOST` の1箇所だけ見る（他はジャンクションで同じ実体）。ArsPaper は3台とも見る。

#### 2026-08-20（W-177）配備先の編集を**リポジトリへ書き戻す**（一方通行をやめた）

W-110 の「退避する」だけでは足りず、**同じ報告が 1 日で再発した**（`network.yml` のチャット書式）。
退避しても配備先は HEAD で上書きされるので、**人から見れば設定は元に戻ったまま**だからである。
さらに悪いことに、W-110 のガードは **`deploy-config-head.cmd` からしか呼ばれていなかった**。
実際に走ったのは `deploy.cmd --config` で、こちらは**ガードを 1 度も呼んでいない**
（`tmp/deploy-config-manifest.json` も `tmp/deploy-config-backup/` も存在しなかった＝**一度も動いていない**）。

変えたのは 3 点。

1. **`guard-deployed-config.ps1` が `[DRIFT]` をリポジトリへコピーし直す**（`[PROMOTED]`）。
   書き戻したファイルは W-106 のオーバーレイで**そのまま同じ配備に乗る**ので、
   ロールバックが構造的に起きなくなる。書き戻す条件は次の全部で、1 つでも欠けたら退避と報告だけ:
   - リポジトリ側に**同じ相対パスのファイルが実在する**（無いものはプラグインの生成物）
   - `world_settings.yml` / `source-network.yml` のような**実行時に書き換わる状態ファイル**でない
   - 同じ相対パスを**別のバックエンドから取り込み済みでない**（ArsPaper は配備先が 3 つ。食い違ったら後勝ちにせず報告）
   - **リポジトリ側が前回配備から動いていない**

   最後の条件が要る理由: **設定エディタは保存のたび配備先にもミラー書き込みする**
   （`tool-config.json` の `deployPaths`）。つまり「配備先が変わった」＝「サーバ側で直接編集された」
   **ではない**。エディタ保存の後にエージェントがリポジトリ側をさらに直していると、素朴に取り込むと
   **新しいリポジトリを古い配備先の内容で潰す** ── 直したかった事故と同じことをする。
   判定は台帳を増やさずにできる: 配備の最後に `-Mode Record` を撮った時点では
   **配備先の中身＝リポジトリの中身**なので、台帳のハッシュは「前回配備時点のリポジトリの姿」でもある。
   これと今のリポジトリを比べればよい。両方が別々に動いていたら取り込まず、退避して名指しする。
   両方が**同じ内容に**動いていたら（＝エディタのミラー）静かに素通しする。

   書き戻した分は**未コミット変更として残る**ので commit すること。`-NoPromote` で無効化できる。
2. **`deploy.cmd --config` にもガードを配線した**（Check → コピー → Record）。
   これで**どの経路から配っても**サーバ側編集は退避され、書き戻される。
3. **`deploy-config-head.cmd` のガードを HEAD 展開の「前」へ移した**（ステップ 2/4）。
   **後ろだと書き戻した内容がその配備に乗らない** ── 退避はできているのにサーバは元に戻ったまま、
   という W-110 の再発そのものになる。

**台帳が無いとき**（`[UNKN]`）は**書き戻さない**。「配備先が新しい」のか「リポジトリが新しい」のかを
区別する材料が無く、書き戻すとリポジトリ側の未配備の変更を巻き戻してしまうから。
代わりに、リポジトリと中身が違うものだけを `[DIFF]` として名指しする。

自己テスト: `ops/scripts/test-guard-deployed-config.ps1`（`testkit/check-ops-scripts.cmd` から走る）。
**取り込み・非取り込みの両側**を偽リポジトリで実測している。

**それでも最初からリポジトリを直すのが確実。** 書き戻しは「配備を 1 回走らせるまで」起きない。

### ⚠ 「3 台で共有」には 2 種類ある — ファイル共有（TF だけ）と DB 共有（それ以外）
`plugins` 配下で NTFS ジャンクション共有されているのは **`plugins\TrinityForge` だけ**
（`ops/launch/launch-config.cmd` の `TF_CONFIG_HOST`）。LuckPerms / HuskSync / Jecon のような
外部プラグインは **`plugins\<名前>` が 3 台それぞれの実体**で、共有しているのは
**MariaDB 側のデータベース**のほう。したがって:
- その手の config は **3 台へ同じ内容を 3 回書く**（1 台だけ直すと、その台だけ別の DB を見に行く）。
  配布は必ずスクリプト経由にする（`ops/scripts/apply-husksync-config.ps1` /
  `ops/scripts/install-economy-plugins.ps1` はどちらも全バックエンドへ同一内容を書く形になっている）。
- 逆に **TF の yml は Main へ 1 回**。「3 台に配ったつもりが同じファイルを 3 回上書きしただけ」も、
  「1 台だけ直して 2 台が古いまま」も、どちらもエラーを出さない。

### ⚠ `paper-global.yml` は 3 台バラバラになる — 揃っているのは forwarding secret だけ
`ops/templates/paper-global.yml.diff` と `apply-velocity-forwarding.ps1` が面倒を見ているのは
**`proxies.velocity` だけ**。それ以外のキーは 3 台とも「Paper が生成した既定値＋誰かが手で触った値」で、
**揃っている保証がまったく無い**。実際 2026-08-19 に `unsupported-settings` が食い違っていた:

| キー | Main | Resource | Dev |
|---|---|---|---|
| `allow-permanent-block-break-exploits` | true | false | false |
| `allow-headless-pistons` | true | false | false |
| `allow-piston-duplication` | true | false | false |

`allow-permanent-block-break-exploits` は **バニラでは可能な岩盤・エンドポータル枠の破壊を
Paper が既定（false）で塞いでいる**スイッチ。この差のせいで「メインではできる岩盤剥がしが
資源サーバでだけできない」という、プラグインのバグにしか見えない症状になっていた。

**プラグイン側を疑う前に 3 台の `config\paper-global.yml` を diff する。**
揃えるスクリプトは `ops/scripts/apply-block-break-exploits.ps1`（触るキーを限定・冪等・退避あり）。
**config を書き換えても稼働中のサーバには効かない**（再起動が要る）ので、
「直したのに変わらない」と誤診しやすい。

### DB ごとの文字コードは MariaDB に接続せずに読める
データディレクトリは `D:\game\minecraft\PaperServer\Velocity_for_TF\TrinityForge_DB\DB`
（`同\my.ini` の `datadir`）。各データベースの既定文字コードは `<DB名>\db.opt` にそのまま入っており、
**読み取りだけなら権限ゲートに引っかからない**ので、root パスワードなしで既存 DB に合わせられる。
2026-08-16 実測: `luckperms` / `husksync` とも `default-character-set=utf8mb4` /
`default-collation=utf8mb4_unicode_ci`。新しい DB を足すときはこれに揃える。

## サーバ起動時の見落とし

### ⚠️ HuskSync の DB 接続失敗はサーバ起動を止めない
MariaDB / Redis への接続が失敗して `FailedToLoadException` → `ConnectException` が出ても、jar 自体は正常で
プラグインはロードされ config.yml も生成される。**起動プロセスとしては成功したように見える**ため、
ログを読まないと「インベントリ同期が効いていないまま運用する」事故になる。起動前に疎通・設定内容を
チェックするスクリプト（`ops/scripts/preflight.ps1` のような機械的な関門）を必ず通すこと。
`getRedisManager()` が null になる NPE が同時に出ることがあるが、これは初期化失敗時の後始末処理側のバグで
無害。原因ではないので追いかけない。

### ⚠ Jecon の `lazyWrite: true` は複数サーバ構成で残高を無言でずらす
Jecon は既定（`lazyWrite: true`）で残高をメモリに溜めてから遅延書き込みする。単体サーバなら
書き込み削減だが、**3 台が 1 つの `jecon` DB を見る構成では移動先が古い残高を読んで書き戻す**。
Jecon 同梱 config の英文コメント自身が「複数サーバで DB を共有する場合、残高が一時的に不正確になる」
と警告している。**例外もログも 1 行も出ず、プレイヤーの申告でしか気づけない。**
出荷の正本は `ops/templates/jecon.config.yml` で `false` 固定にしてあり、
`ops/scripts/run-selftest.ps1` の「Jecon の正本は 3 台共有の前提を満たし…」が `true` への回帰を落とす。
同じ理由で `database.type` を `sqlite` へ戻すと**サーバごとに別の財布**になる（これも無言）。

### ⚠️⚠️ プレイヤーの持ち物は world の中に無い — ワールドを消しても Redis から丸ごと戻る
**2026-08-16 の実報告「ワールドリセットされたがプレイヤーのアイテムが消えていなかった」の真因。**

インベントリの正本は `world/playerdata` ではなく **HuskSync** で、しかも保管先が **2 段ある**:

| 保管先 | 消し方 | 罠 |
|---|---|---|
| `world*/playerdata` | `reset-world.ps1` で world ごと消える | **正本ではない。**ログイン時に HuskSync が上書きする |
| MariaDB `husksync.husksync_user_data` | `purge-player-data.ps1` が出す SQL | 流し忘れやすい（手作業なので） |
| **Redis / Garnet `husksync:<cluster>:latest_snapshot:<UUID>`** | `purge-player-data.ps1` が自分で消す | **DB より先に読まれる。TTL は 1 年** |

**HuskSync はログイン時にまず Redis を見て、あればそれを適用して DB を読まない**
（`LockstepDataSyncer#syncApplyUserData`）。キーの TTL は `RedisKeyType.TTL_1_YEAR`
＝ 31,536,000 秒なので、放っておいても消えない。
つまり **SQL を流してワールドを作り直しても、Redis にキーが残っている人は全部戻る。**

**気づきにくい形**: 実測時、MariaDB の `husksync_users` は 1 人だけになっていたのに、
Redis には 8 人分（各 10〜32KB）が残り 352〜364 日で残っていた。
**DB を見にいくと「ちゃんと消えている」ように見える。**

- 現状の掃除経路は `ops/scripts/purge-player-data.ps1`（`-SkipRedis` で明示的に残せる）。
  実装は `ops/scripts/lib/Redis.ps1`（RESP を直接喋る。redis-cli には依存しない）。
- **消えたことを DEL の戻り値で判断しない。** 消した後にもう一度 SCAN して 0 件を確認する。
- 退避は `_purge-backup-<日時>\redis\husksync-keys.json`（`DUMP` の base64 + TTL）。
  戻すときは `RESTORE <key> <pttl> <payload>`。
- **`Get-RedisKey` の戻り値を `,` で包まない。** 包むと呼び出し側の `@(...)` が
  「要素 1 個（中身は配列）」になり、**何件あっても 1 件に見える**。
  そのまま消しに行くと存在しないキーを 1 回消して「掃除完了」になる（実際に踏んだ）。
  回帰は `run-selftest.ps1` の「`Get-RedisKey` の戻り値は `@()` で包んでも件数が変わらない」。

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
なり、判断待ちとして繰り越されていた項目の相当数が既に実装済みだったことがある（**その47本は 2026-08-04 に
削除した。必要なら git 履歴から掘れる**）。作業内容の詳細を別紙に
書く場合でも、**開いている項目の一次情報は必ず ACTIVE_RECORD へ反映**する。項目を閉じるときは行を消さず
取り消し線＋解決日と根拠で残す（同じ調査を二度やらないため）。**着手前に必ず実コードで裏を取ること**
（この記録自体も古くなりうる）。

**2 ファイル構成（2026-08-04 以降）**: 現役分が `reports/ACTIVE_RECORD.md`、過去分が
`reports/ACTIVE_RECORD_ARCHIVE.md`。**追記するのは現役側の §7 だけ**で、アーカイブには書かない。
分割した理由は、履歴が 5300 行に達して**毎セッション最初に読む文書の冒頭（現在の状態）が
1 週間前のまま自己矛盾していた**こと（「失敗0は現在成立しない」と表の中で打ち消していた）。
§2〜§4 が長くなったら閉じた行をアーカイブの §A へ移す。**消さない。**

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

## ローカル生成物を掃除するときの罠（2026-08-04 に実施して踏んだもの）

- **`rm -rf` と `git rm` は permissions で deny されている**（`.claude/settings.local.json`）。
  代替は PowerShell の `Remove-Item -Recurse -Force`、追跡ファイルの削除は
  **「ファイルを消してから `git add -- <path>`」**（`git add` は削除もステージする）。
- ⚠️ **Gradle の生成物は MAX_PATH を超えるので普通の削除が失敗する。**
  `build/tmp/compileTestJava/compileTransaction/stash-dir/<長いテスト名>.class.uniqueId0` が該当し、
  `git worktree remove` が `error: failed to delete '...': Filename too long` で止まる。
  抜け道は `\\?\` を付けた .NET 呼び出し:
  ```powershell
  [System.IO.Directory]::Delete("\\?\C:\path\to\dir", $true)
  ```
- ⚠️ **複合的な `Remove-Item` を書くとハーネスの保護に引っかかる**
  （`Remove-Item on system path '/' is blocked`）。ループの中で `cmd /c rd /s /q` を呼んだり、
  複数の削除を 1 スクリプトに詰め込むと誤検知する。**1 コマンド 1 目的に割るか、上の .NET 呼び出しを使う。**
- ⚠️ **`TrinityForge/build` を丸ごと消さない。`build/release/` に配備用 jar が居る。**
  さらに**別セッションが同時にビルドしている可能性**がある（2026-08-04 の整理では 17:42 のビルドと重なった）。
  `.gradle/`（約 95MB）も再生成物だが、消すと次のビルドがフルになる。
- **`tmp/` にはゴミに見えて参照が生きているものがある。**
  `tmp/findings/`（ACTIVE_RECORD アーカイブの各所と `WeaponAttackStatResolver.java:111` から参照されている
  調査記録）と `tmp/user-requests-all.md`（全依頼の原文）は消さない。
  逆に `tmp/*.log` / `*.cmd` / `*-verify*` は使い捨て。
- **消す前に「その日に別セッションが作ったもの」を除外する。** 同一ワークツリーを共有しているので、
  当日の scratch は動いているセッションの作業物である可能性が高い。

## 「サーバが停止しているか」の判定を cmd で自作しない（2026-08-02）

`deploy.cmd` が jar を上書きする前の関門は `ops\scripts\check-servers-stopped.ps1` **だけ**を使う。
cmd から `world\session.lock` を直接叩く次の定番イディオムは、**このリポジトリの環境では
稼働中のサーバを「停止中」と誤判定する**ことを実測で確認した。

```cmd
2>nul (call ) >>"%LOCK%" || (set "RUNNING=1")
```

cmd の追記オープンは Paper が握っているロックと共存できる共有モードで開くため、**開けてしまう**。
PowerShell 側は `[System.IO.File]::Open($p,'Open','ReadWrite','None')`（共有なし）で開くので検出できる。
加えて ps1 は RCON ポートの LISTEN も見るので、ワールドを開く前の起動途中も拾える。

**誤って「停止中」と答えることの代償が非対称**（稼働中の jar 差し替え＝ `NoClassDefFoundError`、
JVM 再起動以外に復旧手段なし）なので、軽い判定に置き換えてはいけない。台数を絞りたいときは
`check-servers-stopped.ps1 -Server <backend...>` を使う。

## PowerShell: param 名とループ変数名が衝突すると型制約で黙って壊れる（2026-08-02）

`check-servers-stopped.ps1` に `[string[]] $Server` を足した瞬間、**フィルタを使わない従来の経路まで**
`The property 'RconPort' cannot be found on this object` で落ちた。原因は既存のループ変数 `$server`。

- PowerShell の変数名は**大文字小文字を区別しない**ので `$Server` と `$server` は同一変数。
- param の**型制約は変数に residual に残る**ため、`$server = $config.Servers[$name]`（ハッシュテーブル）が
  `[string[]]` へ**黙って変換**され、以降のプロパティ参照が全部失われる。
- エラーは代入行ではなく**参照行**で出るので、原因が param 追加だと気づきにくい。

対処は片方の改名（このファイルではループ変数を `$backend` にした）。**param を追加したら、同名（大小無視）の
ローカル変数がスクリプト内に無いか必ず grep すること。**

## deploy.cmd --server で配備先を絞る（2026-08-02）

`deploy.cmd --server dev` のように**配る先を限定**できる。`Main_Server` でも `main` でも通る
（一致しなければ `_Server` を足して再照合するので、4台目を `TF_BACKENDS` に足しても短縮形が自動で効く）。

絞られるのは **jar のコピー / ArsPaper の config コピー / 停止判定**の3つ。
**TrinityForge の yml だけは絞れない** — 他のバックエンドの `plugins\TrinityForge` は
`TF_CONFIG_HOST` へのジャンクションなので、1回書けば3台に届く。
そのため `--config` 併用時は、対象に config ホストが含まれなければ **TF の yml をスキップ**する
（黙って書くと「触るなと言われたサーバ」を変えてしまうため）。

**`--restart` とは併用不可**（エラーで停止）。`stop-network.ps1` に台別の選択が無く、
`stop-all` / `start-all` はネットワーク全体が単位なので、併用すると対象外のサーバを止めたまま放置する。

## 関連
- [./config-editor.md](./config-editor.md)
- [./common-traps.md](./common-traps.md)
- [./forks-and-mobs.md](./forks-and-mobs.md)

## ⚠️⚠️ バックエンドごとにデータパックが違うと HuskSync がインベントリを丸ごと捨てる（2026-08-21）

**症状**: 「サーバ移動でごくまれに（条件不明）アイテムがロストする」。

**機構**: HuskSync は snapshot の NBT を ItemStack へ戻すときに、その鯖の registry を引く。
**登録されていないエンチャント／コンポーネントが 1 個でも混ざると ItemStack 変換が例外**になり、
HuskSync は例外を握って **そのデータ型（＝インベントリなりエンダーチェストなり）を丸ごと skip** する:

```
[HuskSync] Failed to deserialize %s data for snapshot %s; skipping it.
           ... The player will load without this data type for this session.
NbtApiException: Failed to convert NBT to ItemStack.
           DataResult.Error['Failed to get element nova_structures:spiteful ...
```

**1 個のアイテムのせいでインベントリ全部が来ない。** しかもその後の save で snapshot が上書きされるので恒久ロスト。

**この構成での実際のズレ**: `Resource_Server/world/datapacks/` にだけ 17 個のデータパック
（**Dungeons and Taverns v5.1.0** ほか DnT オーバーホール 10 種・Terralith・Incendium・Nullscape・Structory 等）が入っており、
DnT 本体が `data/nova_structures/enchantment/*.json` で**カスタムエンチャント 34 種**を登録している。
**Main_Server / Dev_Server の `world/datapacks/` は `bukkit` だけ。**
→ 資源鯖で拾った DnT エンチャ品を持って Main へ移動した瞬間に発火する。

**確認手順**（推測しない。実物を見る）:

```bash
V=/d/game/minecraft/PaperServer/Velocity_for_TF
for s in Main_Server Resource_Server Dev_Server; do ls "$V/$s/world/datapacks/"; done
grep -a "Failed to deserialize" "$V/Main_Server/logs/latest.log"
```

**原則: HuskSync で結ぶバックエンドは、registry を足すデータパックを全台で一致させる。**
worldgen を変えたくない鯖には「registry だけ入った版」を作って配る。
プラグイン jar のバージョン差より**データパックの差のほうが先に事故る**。

**復旧**: `max_user_data_snapshots: 16` / `snapshot_backup_frequency: 4h` の範囲内なら
`/userdata list <player>` → `/userdata restore` で戻せる。**ローテーションで消える前に動く。**

## ヴォールトと試練のスポナーは `LootGenerateEvent` を発火しない（2026-08-23、W-187）

ユーザー報告「試練のスポナーにカスタム登録のアイテムが反映されず、一部チェストが空」の真因は2段だった。

1. `loot-tables.yml` の `pools` に **トライアルチャンバーの表が1本も入っていなかった**
   （2026-08-16 に「無限湧きで経済が壊れる」として意図的に除外していた）。
2. 仮に足しても当たらない。**ヴォールト（vault）と試練のスポナー（trial spawner）は戦利品を
   ブロックが直接排出するので `LootGenerateEvent` を発火しない**
   （[PaperMC #11680](https://github.com/PaperMC/Paper/issues/11680) は「対応しない」でクローズ）。

唯一の入口は Paper 1.21.10 で入った
[`BlockDispenseLootEvent`](https://jd.papermc.io/paper/1.21.11/org/bukkit/event/block/BlockDispenseLootEvent.html)。
`getDispensedLoot()` / `setDispensedLoot(List)` / `getLootTable()` / `getPlayer()`（**試練のスポナーの
報酬排出では null**）/ `Cancellable`。ArsPaper の `LootTableListener#onBlockDispenseLoot` がこれを受ける。

⚠ **試練の間の「普通のチェスト」（`minecraft:chests/trial_chambers/*`）は `LootGenerateEvent` 側**。
つまり試練の間だけで**入口が2本に割れている**。片方だけ直すと「ヴォールトだけ空」に戻るので、
共通処理は `LootTableListener#applyPools` に集約してある。

### 構造物のルート表は structures.json に全部は載らない

`tmp/worldgen/loot_tiers.py` は `report/structures.json`（構造物 piece が参照する表）を基に集めるが、
**ヴォールトと試練のスポナーは piece ではなくブロックが表を持つので載らない**。
`UNREFERENCED_INCLUDE_TOKENS`（`vault` / `spawners/`）で拾い直している。
2026-08-23 の実測でこれが 99 本あった（＝それまで丸ごと視界の外だった）。

### 「一部チェストが空」のもう1つの候補

1.21.8 で生成済みのワールドを 1.21.10/1.21.11 へ上げると、**アップグレード前に生成された
トライアルチャンバーのヴォールトが恒久的に不活性になる**既知の不具合がある
（[PaperMC #13521](https://github.com/PaperMC/Paper/issues/13521)、未修正）。
この場合はプラグイン側では直せないので、該当チャンバーを再生成する（WorldEdit の `//regen` 等）か、
資源ワールドのリセットで作り直すしかない。

## `reset-resource.cmd` はダブルクリックで完結する（2026-08-23 変更）

以前は**引数なしだと必ず dry run で終わっていた**ので、「押したのに何も起きない」と読めた。
2026-08-23 から「dry run で消える物を全部出す → `RESET` と打ち込ませる → 本実行」の2段になった。
`--apply` を渡すと確認を飛ばす（自動実行用）。

- 資源ワールドのシードは `server.properties` の `level-seed=` が**空**なので、
  本実行すれば毎回新しい地形になる（「シードごと変わる」という期待どおり）。
- ⚠ **editor の「構造物ルート抽選 (loot-tables)」はデータパックではない。**
  ArsPaper がチェスト生成の瞬間に差し込む実行時設定なので、**ワールドリセットなしでも
  次に生成されるチェストから効く**。データパックが供給しているのは構造物そのものだけ。
- ⚠ 配備先（`Velocity_for_TF\launch\`）の `.cmd` はリポジトリのコピーなので、
  変更を届けるには `ops\launch\deploy-launch.cmd` を実行する必要がある。

### `loot-tables.yml` の生成元はリポジトリの外側に片足を置いている

`fork-handoff/arspaper/fork/src/main/resources/loot-tables.yml` は**生成物**で、
真源は `tmp/worldgen/loot_tiers.py`（表を豪華さでティアへ分ける）と
`tmp/worldgen/gen_loot_yml.py`（どのティアに何を出すかの割り当て表）。
**yml を手で直すと次の再生成で消える。**

⚠ `tmp/` は `.gitignore` 対象なので、この2本だけ `git add -f` で追跡している。
一方それらが読む `tmp/worldgen/report/*.json`（バニラ＋データパックのルート表ダンプ、約1MB）は
**追跡されていない＝新しいクローンには存在しない**。別のマシンで再生成するには
先に `tmp/worldgen/fetch_vanilla.py` などでダンプを作り直す必要がある。
割り当て表（＝手で決めた設計そのもの）は追跡されているので、そこだけは失われない。
