# 資源サーバ分離 作業書（RUNBOOK）

Velocity プロキシ + メインサーバ + 資源サーバ の 2 バックエンド構成へ移行する手順。
**この作業書はユーザーが自分で実行する前提で書いてある。** 各手順に検証コマンドと期待される出力を付けた。

- 移行元: `D:\game\minecraft\PaperServer\TrinityForge`（Paper 1.21.11・現行の単体サーバ）
- 移行先: `D:\game\minecraft\PaperServer\Velocity_for_TF\`
  - `Main_Server`（25566 / RCON 25586）
  - `Resource_Server`（25567 / RCON 25587）
  - `Dev_Server`（25568 / RCON 25588）— 検証用。定期再起動と週次リセットの対象外
- 前提の裏取り: [COST_AND_LICENSE.md](COST_AND_LICENSE.md) / [SECURITY.md](SECURITY.md) / [PERFORMANCE.md](PERFORMANCE.md) / [PLUGIN_MATRIX.md](PLUGIN_MATRIX.md)

> **2026-07-27 時点の実環境**: 上の 3 バックエンドと Velocity（`velocity-4.1.0-SNAPSHOT-9.jar`）が
> 既に作成済みで、Geyser / floodgate / Via\* はプロキシへ移設済み。Main_Server と Resource_Server の
> plugins も配置済み（Resource_Server は [PLUGIN_MATRIX.md](PLUGIN_MATRIX.md) の推奨構成どおり）。
> **導入済み**: 2-1（MariaDB 12.3.2）／2-2（Garnet 2.1.0・稼働中）／
> 6-2・7-2 の `proxies.velocity`（3 台とも `enabled: true` で secret 一致）。
> **未実施**: MariaDB の DB とユーザー作成（root パスワードが要るため利用者作業）／
> 手順6 のワールドボーダー／手順8（ジャンクション）／手順9-2（HuskSync の config 配布）／
> 手順11（スケジューラ。Garnet の起動時登録を含む）。
> 現状は [scripts/preflight.ps1](scripts/preflight.ps1) を実行すれば機械的に判定できる。

---

## 0. 着手前に読む

### 0-1. この構成の要点

```
              [外部] TCP 25565 のみ / UDP 19132 のみ
                        |
              +-------------------------------+
              |  Velocity 4.1.0-SNAPSHOT (1G) |  Geyser + floodgate + Via*
              |  modern forwarding            |  Sonar / FallbackRouter / OneTimePack
              |  /server main | /server resource |  LuckPerms-Velocity
              +----+---------------------+----+
        127.0.0.1:25566            127.0.0.1:25567      (+ dev 25568)
   +---------v---------+      +----------v-----------+
   | Main_Server 8G     |      | Resource_Server 6G    |
   | 全ワールド 5000x5000|      | 週次で world 全削除    |
   | RCON 25586         |      | RCON 25587            |
   +---------+---------+      +----------+-----------+
             |  plugins/TrinityForge <-- ディレクトリジャンクション（実体共有）
             +----------+---------------+
                +-------v--------+
                | MariaDB :3306   | LuckPerms / HuskSync
                | Garnet  :6379   | HuskSync (Redis 互換) <- 127.0.0.1 のみ
                |                 | いずれも Windows ネイティブ
                +-----------------+
```

データ同期は 3 層に分かれる。**新規に書く Java コードはゼロ。**

| 層 | 中身 | 手段 |
|---|---|---|
| A | インベントリ / エンダーチェスト / 経験値 / 体力 / 効果 | **HuskSync** |
| B | プレイヤー PDC（図鑑・実績・称号・天井・採取トグル・Ars のマナとグリフ解放） | **HuskSync の `persistent_data`** |
| C | スキル Lv / ポイント / パーク（SQLite） | **`plugins/TrinityForge` のディレクトリジャンクション** |

**共有しないもの**: EliteMobs のデータとダンジョン（メインのみ）／SetHome の home（要件どおりサーバごとに独立）。

### 0-2. 事前検証で分かっていること

サーバを立てずに確認済みの事項。前提が崩れていないことの根拠。

| 検証 | 結果 | 出力 |
|---|---|---|
| EliteMobs なしでモブのレベル推移と報酬が機能するか | **機能する。** 89 種 × Lv0〜100 で全帯埋まっている | [reports/resource-server-mob-simulation.md](reports/resource-server-mob-simulation.md) |
| 2 プロセスから同じ SQLite を触って壊れないか | **壊れない。** ただし本体の修正が 1 件必要だった（適用済み） | [reports/shared-sqlite-concurrency.md](reports/shared-sqlite-concurrency.md) |
| PDC が HuskSync で同期できる型か | **全て primitive 型。** 将来崩れたらテストが落ちる | `PlayerPdcPrimitiveTypeAuditTest` |
| ジャンクション先を誤って消さないか | **削除ガードが必ず中断する。** 実際にジャンクションを作って実測 | [scripts/run-selftest.ps1](scripts/run-selftest.ps1)（24/24） |

**起動する前に [scripts/preflight.ps1](scripts/preflight.ps1) を流す。** 手順2・9-2・6-2 の
やり残しを機械的に検出する（MariaDB / Garnet が実際に応答しているか、HuskSync の既定資格情報、
`location` / `game_mode` / `persistent_data`、`trinityforge:*` の除外、
全バックエンドでの設定一致、forwarding secret の一致）。
**HuskSync は enable に失敗してもサーバの起動を止めない**ため、
ログを読まないと「同期されていないことに気付かないまま運用する」事故が起きる。

### 0-3. 【最重要】ジャンクションの取り扱い

`plugins/TrinityForge` は**メイン側の実体を資源サーバから参照しているだけ**。実体は 1 つしかない。

- **`rmdir /s` や `Remove-Item -Recurse` を資源側の `plugins\TrinityForge` に向けない。**
  リンク先（＝全プレイヤーの進行データと全 config）が消える。**本構成で最も重大な事故ポイント。**
- リンクを外すときは `rmdir "...\plugins\TrinityForge"`（`/s` を付けない）
- ops スクリプトは全て [Common.ps1](scripts/lib/Common.ps1) の `Remove-DirectorySafely` を経由し、
  ジャンクションとその配下を検出したら**必ず中断**する
- **同一マシン・同一ローカルディスク限定。** ネットワーク共有上の SQLite は破損する
- **起動順は必ず main → resource**（初回スキーママイグレーションの同時実行を避ける）
- config を editor で保存したら**両サーバで `/tf reload`**（ファイルは共有だがメモリは別）
- `stats/skill-exp.yml` も共有されるので、**資源サーバだけ EXP レートを変える運用はできない**。
  資源サーバはダンジョン外扱い（`outside-dungeon-exp-rate: 0.25`）でメインの地上と同じ挙動になる

### 0-4. 所要時間の目安

| フェーズ | 目安 |
|---|---|
| 手順 1〜3（バックアップ・DB・LuckPerms 移行） | 1〜2 時間 |
| 手順 4〜6（Velocity・Geyser 移設・ボーダー） | 2〜3 時間 |
| 手順 7〜10（資源サーバ構築・HuskSync） | 2〜4 時間（HuskSync のビルド次第） |
| 手順 11〜12（自動化・受け入れ確認） | 1〜2 時間 |

**一度に全部やらない。** 手順 6 まで（＝プロキシ化のみ、資源サーバなし）で一旦運用に戻せる区切りがある。

---

## 手順 0. config-editor と RCON を塞ぐ

**最初にやる。** 詳細と背景は [SECURITY.md](SECURITY.md) の §0。

```powershell
# 管理者 PowerShell
New-NetFirewallRule -DisplayName "Block config-editor 8000" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Block
New-NetFirewallRule -DisplayName "Block config-editor 8787" -Direction Inbound -LocalPort 8787 -Protocol TCP -Action Block
New-NetFirewallRule -DisplayName "Block RCON 25586-25588" -Direction Inbound -LocalPort 25586-25588 -Protocol TCP -Action Block
```

あわせて config-editor に Basic 認証を付ける（config ファイルは変更しない）:

```powershell
setx CONFIG_EDITOR_PASSWORD "<十分に長いランダム文字列>"
```

**検証**: 別マシンから `http://<サーバのIP>:8000/` へアクセスして到達しないこと。

---

## 手順 1. バックアップ

```powershell
# サーバを停止してから
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$dst = "D:\game\minecraft\PaperServer\backup\pre-proxy-$stamp"
New-Item -ItemType Directory -Path $dst -Force

$src = "D:\game\minecraft\PaperServer\TrinityForge"
Copy-Item "$src\plugins" -Destination "$dst\plugins" -Recurse
Copy-Item "$src\world"           -Destination "$dst\world" -Recurse
Copy-Item "$src\world_nether"    -Destination "$dst\world_nether" -Recurse
Copy-Item "$src\world_the_end"   -Destination "$dst\world_the_end" -Recurse
Copy-Item "$src\server.properties" -Destination $dst
Copy-Item "$src\config"          -Destination "$dst\config" -Recurse
```

**検証**: `Get-ChildItem $dst` に上記が揃っていること。
`$dst\plugins\TrinityForge\player_progression.db` が 0 バイトでないこと。

> この時点ではまだジャンクションが無いので `-Recurse` で問題ない。
> **ジャンクションを張ったあとは、この方法でバックアップしてはいけない。**
> 以後は [backup.ps1](scripts/backup.ps1) を使う。

---

## 手順 2. MariaDB と Redis 互換サーバを用意する（Windows ネイティブ）

Docker Desktop は使わない（企業利用が有料。理由は [COST_AND_LICENSE.md](COST_AND_LICENSE.md)）。

**この構成では WSL2 ではなく Windows ネイティブを既定にする。** 理由:

- WSL2 は VM なのでメモリを別枠で確保する。同じマシンで 8G + 6G の JVM が走る本構成では、
  取り合いになる分がそのまま無駄
- **Windows サービスとして自動起動する**ので、タスクスケジューラ運用（手順11）と揃う。
  WSL は「誰かがログオンしないと上がらない」事故が起きうる
- `wsl --install` の初回起動に UNIX アカウント作成の対話が挟まらない
- localhost 転送という中間層が消えるので、切り分けが 1 段減る

WSL2 でやる場合は末尾の【付録】を見ること（動作はする）。

### 2-1. MariaDB（Windows ネイティブ）

<https://mariadb.org/download/> から **MSI インストーラ**（Windows x86_64）を入れる。
インストーラのウィザードで:

- root パスワードを設定する
- **`Enable networking` は有効のまま、ポート 3306**
- **`Install as service` を有効**（サービス名は既定の `MariaDB`）
- UTF8 を既定にするチェックがあれば入れる

インストール後、DB とユーザーを作る（`MariaDB Command Prompt` から）:

```sql
CREATE DATABASE luckperms CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE husksync  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'luckperms'@'127.0.0.1' IDENTIFIED BY '<パスワード1>';
CREATE USER 'husksync'@'127.0.0.1'  IDENTIFIED BY '<パスワード2>';
GRANT ALL ON luckperms.* TO 'luckperms'@'127.0.0.1';
GRANT ALL ON husksync.*  TO 'husksync'@'127.0.0.1';
FLUSH PRIVILEGES;
```

> ホスト部を `localhost` ではなく **`127.0.0.1`** にしている。Windows では JDBC が
> `localhost` を IPv6 の `::1` として引くことがあり、`localhost` で作ったユーザーだと
> `Access denied` になる場合がある。

**127.0.0.1 のみで待ち受けさせる**（[SECURITY.md](SECURITY.md)）。
`C:\Program Files\MariaDB <版>\data\my.ini` の `[mysqld]` に:

```ini
bind-address = 127.0.0.1
```

```powershell
Restart-Service MariaDB
```

### 2-2. Redis 互換サーバ = Garnet（Windows ネイティブ）

**Redis 本体に公式の Windows 版は無い。** 選択肢を比較した結果 **Garnet** を採る。

| 候補 | 判定 |
|---|---|
| **Garnet**（Microsoft・MIT） | **採用。** ネイティブ Windows・無料・活発に開発中（v2.1.0 = 2026-07-24） |
| Memurai | **不可。** Developer 版は**稼働 10 日上限かつ本番利用禁止**。本番は有料 |
| tporadowski/redis | 非推奨。Redis 5.0 相当で更新が止まっている |
| WSL2 + Redis | 可。ネイティブに拘らないならこれ（付録） |

**HuskSync が使う Redis コマンドは `PING` / `SET` / `SETEX` / `GET` / `DEL` / `KEYS` /
`PUBLISH` / `SUBSCRIBE` / `INFO` の 9 つだけ**（`common/.../redis/RedisManager.java` を実読）。
いずれも Garnet の API 互換表で対応済み。pub/sub も既定で有効（`--no-pubsub` で切るオプションが
あることが裏返しの根拠）。

**2026-07-27 に導入済み。** 以下は再現手順。

1. <https://github.com/microsoft/garnet/releases> から `win-x64-based-readytorun.zip` を取得
   （v2.1.0 / 48,245,050 バイト / SHA-256 `b810ee55…3569c`）
2. `D:\game\minecraft\Garnet\` へ展開する。中身は `net8.0\` と `net10.0\` の 2 つ
3. **`net8.0` を使う。** この zip は**自己完結ではなく .NET ランタイムを要求する**
   （`readytorun` は事前 JIT であって自己完結の意味ではない）。この環境には
   **.NET 8.0.21 が既に入っている**ので net8.0 はそのまま動く。net10.0 は .NET 10 が要る
4. 起動用の `garnet.cmd` を置く（正本は [templates/garnet.cmd](templates/garnet.cmd)）

```bat
@echo off
set GARNET_HOME=D:\game\minecraft\Garnet
"%GARNET_HOME%\net8.0\GarnetServer.exe" ^
  --bind 127.0.0.1 ^
  --port 6379 ^
  --memory 1g ^
  --index 64m ^
  --checkpointdir "%GARNET_HOME%\data" ^
  --logger-level Warning
```

**`--memory` を必ず指定する。既定は 16g。** 指定しないとメインログ用に 16GB を
抱えに行き、8G + 6G の JVM とメモリを取り合う。HuskSync はスナップショットの
一時キャッシュとしてしか使わない（正本は MariaDB）ので 1g で足りる。
**`--bind` も必須**（既定は any = 外部から到達しうる）。

5. **タスクスケジューラで「コンピューターの起動時」に登録する**（要管理者）:

```powershell
schtasks /create /tn "Garnet" /tr "D:\game\minecraft\Garnet\garnet.cmd" ^
  /sc onstart /ru SYSTEM /rl HIGHEST /f
```

**検証**: `preflight.ps1` が `OK   Redis 互換 127.0.0.1:6379 (Garnet 2.1.0)` を出すこと。

> Garnet は Redis の**再実装**であって Redis そのものではない。9 コマンドしか使わないので
> 実害が出る見込みは薄いが、**Dev_Server で先に検証してから** Main / Resource へ広げること
> （3 台目が用意されているのはこういうときのため）。
> 問題が出たら付録の WSL2 + Redis へ切り替えれば、HuskSync 側の設定は 1 文字も変わらない。

### 2-3. 検証

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File <repo>\ops\scripts\preflight.ps1
```

**ポートが開いているかではなく、何が応答しているかまで判定する。** 期待する出力:

```
[INFO] 外部データストアを確認します
[INFO]   OK   MariaDB 127.0.0.1:3306 (11.x.x-MariaDB)
[INFO]   OK   Redis 互換 127.0.0.1:6379 (Garnet 2.1.0)
```

3306 が「開いているが MySQL プロトコルで応答しない」、6379 が「開いているが RESP で
応答しない」場合も区別して報告する（別のプロセスがポートを掴んでいるケース）。

### 【付録】WSL2 で用意する場合

ネイティブを使わない場合はこちら。**この環境には Linux ディストロが入っていない**
（`wsl --list --verbose` に `docker-desktop` しか出ない）ので、まずディストロから:

```powershell
wsl --install -d Ubuntu
```

初回起動で **UNIX ユーザー名とパスワードの作成を求められる**（対話が必要）。

```bash
sudo apt update
sudo apt install -y mariadb-server redis-server

sudo mysql -e "CREATE DATABASE luckperms CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sudo mysql -e "CREATE DATABASE husksync  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sudo mysql -e "CREATE USER 'luckperms'@'localhost' IDENTIFIED BY '<パスワード1>';"
sudo mysql -e "CREATE USER 'husksync'@'localhost'  IDENTIFIED BY '<パスワード2>';"
sudo mysql -e "GRANT ALL ON luckperms.* TO 'luckperms'@'localhost';"
sudo mysql -e "GRANT ALL ON husksync.*  TO 'husksync'@'localhost';"
sudo mysql -e "FLUSH PRIVILEGES;"
```

- `/etc/mysql/mariadb.conf.d/50-server.cnf` → `bind-address = 127.0.0.1`
- `/etc/redis/redis.conf` → `bind 127.0.0.1`

```bash
sudo systemctl enable --now mariadb redis-server
```

`%USERPROFILE%\.wslconfig` でメモリ上限を切る（**切らないと 2 つの JVM と取り合う**）:

```ini
[wsl2]
memory=4GB
processors=4
```

WSL2 は `localhost` のポートを Windows 側へ転送する。外部からは到達しない。
検証は 2-3 と同じ `preflight.ps1` でよい（`Redis 8.x.x` と表示される）。

---


## 手順 3. LuckPerms を H2 → MariaDB へ移行

```
# メインサーバのコンソールで
lp export luckperms-backup
```

`plugins/LuckPerms/config.yml`:

```yaml
storage-method: mysql
data:
  address: 127.0.0.1:3306
  database: luckperms
  username: luckperms
  password: '<パスワード1>'
# ネットワーク間で権限変更を即時反映する
messaging-service: redis
redis:
  enabled: true
  address: 127.0.0.1:6379
  password: ''
```

サーバを再起動してから:

```
lp import luckperms-backup
```

**検証**: `lp user <自分の名前> info` で移行前と同じグループ・権限が出ること。
`lp info` の Storage が `MySQL` になっていること。

---

## 手順 4. Velocity を導入

1. `D:\game\minecraft\PaperServer\Velocity_for_TF\` を作り、`velocity-4.1.0-SNAPSHOT-9.jar` を置く
2. 一度起動して `velocity.toml` と `forwarding.secret` を生成させ、停止する
3. [templates/velocity.toml](templates/velocity.toml) の内容を反映する
   （`config-version` は生成された値を使う）
4. `forwarding.secret` の中身を控える（次の手順で使う）

起動スクリプト `D:\game\minecraft\PaperServer\Velocity_for_TF\start.bat`:

```bat
@echo off
cd /d "%~dp0"
java -Xms1G -Xmx1G -XX:+UseG1GC -jar velocity-4.1.0-SNAPSHOT-9.jar
pause
```

**検証**: 起動ログに `Listening on /0.0.0.0:25565` が出ること。
この時点ではバックエンドがまだ 25565 を使っているので、**メインを止めてから**起動すること。

---

## 手順 5. Geyser / Floodgate / ViaVersion をプロキシへ移設

**Bedrock プレイヤーの締め出しに直結するので、この手順だけ独立して行い、その場で接続確認する。**

1. メインの `plugins/` から下記を退避（削除ではなく移動）:
   - `Geyser-Spigot (5).jar`
   - `geyserExtra-1.0.0-SNAPSHOT.jar`
   - `ViaVersion-5.11.1-SNAPSHOT.jar`
   - `ViaBackwards-5.11.0.jar`
2. Velocity の `plugins/` に入れる:
   - `Geyser-Velocity.jar`
   - `floodgate-velocity.jar`
   - `ViaVersion.jar` / `ViaBackwards.jar`
3. **`plugins/floodgate/key.pem` をメインから Velocity へコピーする。**
   これを引き継がないと、既存の Bedrock プレイヤーが全員別人扱いになる
4. Floodgate のリンク済みアカウント DB も引き継ぐ
5. `geyserExtra` は Geyser の `extensions/` へ置く
6. **Floodgate はバックエンドにも残す**（スキン表示と Bedrock 判定 API のため）

**検証**:

- Java 版で `<サーバのIP>:25565` へ接続できる
- **Bedrock 版で `<サーバのIP>:19132` へ接続できる**（ここで必ず実機確認する）
- Bedrock プレイヤーの名前が今までと同じ（`.` プレフィックス等）である

> ここで Bedrock が繋がらない場合、先に進まずに戻すこと。
> 資源サーバの構築より優先度が高い。

---

## 手順 6. メインをプロキシ配下へ入れ、ワールドボーダーを張る

### 6-1. server.properties

[templates/main.server.properties.diff](templates/main.server.properties.diff) のとおりに変更。要点:

```properties
server-port=25566
server-ip=127.0.0.1
online-mode=false
```

### 6-2. paper-global.yml

[templates/paper-global.yml.diff](templates/paper-global.yml.diff) のとおり:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: '<forwarding.secret の中身>'
```

**手で貼らずにスクリプトで揃えるほうが安全。** secret が 1 文字でも違うと
全員が `Unable to verify player details` で入れなくなるため:

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File ops/scripts/apply-velocity-forwarding.ps1 -DryRun
```

`-DryRun` を外すと反映する。`enabled` と `secret` の 2 行だけを書き換え、
書く前に同じディレクトリへ退避を取り、**secret は画面に出さない**。
既に一致しているサーバは 1 バイトも触らない（冪等）。**反映には再起動が必要。**

### 6-3. ワールドボーダー

```
execute in minecraft:overworld  run worldborder center 0 0
execute in minecraft:overworld  run worldborder set 10000
execute in minecraft:the_nether run worldborder center 0 0
execute in minecraft:the_nether run worldborder set 5000
execute in minecraft:the_end    run worldborder center 0 0
execute in minecraft:the_end    run worldborder set 5000
```

`level.dat` に永続化されるので 1 回でよい。`em_adventurers_guild` は対象外。

**検証**: 各ワールドで `/worldborder get` が `5000` を返す。再起動後も維持されていること。

#### ネザーの座標換算（要確認・ユーザー判断）

ネザーは座標が 8 倍。**「一律 5000」だとネザーとオーバーワールドのボーダーが対応しない。**

| ネザー座標 | 地上換算（×8） | 地上ボーダー（±2500）内か |
|---:|---:|:---:|
| 0 | 0 | ○ |
| ±100 | ±800 | ○ |
| ±200 | ±1600 | ○ |
| **±312** | **±2496** | ○（ここが限界） |
| ±313 | ±2504 | ✕ → ボーダーへクランプ |
| ±500 | ±4000 | ✕ → クランプ |
| ±1000 | ±8000 | ✕ → クランプ |
| ±2500（ネザー端） | ±20000 | ✕ → クランプ |

つまり **ネザーの ±312 より外にポータルを作ると、地上側の出口は全部ボーダー際に寄せられる**。
ネザーを歩き回ること自体はできるが、ネザーハイウェイで遠くへ行く利点は ±312 で頭打ちになる。
ネザーの面積のうち portal 移動として意味があるのは 625×625 ＝ **全体の約 1.5%**。

**選択肢**:

- **A. 一律 5000 のまま（現在の決定）** — ネザーは広く歩けるが、
  遠方ポータルの出口はボーダー際に集中する。動作としては壊れない
- **B. ネザーだけ 625 にする** — `worldborder set 625` を the_nether に対して実行。
  地上と正確に対応し、「ボーダー際に人が集中する」現象も起きない

後からいつでも変更できる。B にする場合はプレイヤーへの周知が必要
（既存のネザー拠点がボーダー外になる可能性がある）。

### 6-4. Chunky で事前生成

```
/chunky world world
/chunky center 0 0
/chunky radius 2500
/chunky start
```

3 ワールド分行う。完了までかなり時間がかかるので、プレイヤーが居ない時間帯に。

### 6-5. 検証

- Velocity 経由でログインでき、`/server` で `main` に居ることが分かる
- **`<サーバのIP>:25566` へ直接接続すると拒否される**（`server-ip=127.0.0.1` が効いている）
- 既存のプレイヤーデータ（スキル Lv・インベントリ・権限）が全て無事

> **この時点で一旦運用に戻せる。** 資源サーバなしのプロキシ構成として完結している。
> 問題が出たら手順 7 以降へ進まず、ここで様子を見てよい。

---

## 手順 7. 資源サーバを作る

```powershell
$main = "D:\game\minecraft\PaperServer\TrinityForge"
$res  = "D:\game\minecraft\PaperServer\Velocity_for_TF\Resource_Server"
New-Item -ItemType Directory -Path $res -Force

# Paper 本体（メインと完全に同一ビルド）
Copy-Item "$main\paper-1.21.11-132.jar" -Destination $res
Copy-Item "$main\eula.txt"              -Destination $res
Copy-Item "$main\config"                -Destination "$res\config" -Recurse
Copy-Item "$main\bukkit.yml","$main\spigot.yml" -Destination $res
```

**`spigot.yml` を必ずコピーすること。**
モブシミュレーションで判明したことだが、Lv100 で **50 種のモブが最大体力 1024 を超える**
（最大は WARDEN の 482148）。Spigot の既定上限は 1024 なので、
コピーを忘れると資源サーバだけモブが弱くなる。該当設定:

```yaml
settings:
  attribute:
    maxHealth:
      max: 1.7976931348623157E308
```

詳細は [reports/resource-server-mob-simulation.md](reports/resource-server-mob-simulation.md) の §6-1。

### 7-1. server.properties

[templates/resource.server.properties](templates/resource.server.properties) を使う。要点:

```properties
server-ip=127.0.0.1
server-port=25567
online-mode=false
rcon.port=25587
level-seed=
spawn-protection=16
```

### 7-2. paper-global.yml

メインと同じく `proxies.velocity` を有効化し、同じ secret を入れる。
6-2 の `apply-velocity-forwarding.ps1` は `Servers` の全サーバをまとめて処理するので、
資源サーバと Dev_Server も 1 回の実行で揃う。

### 7-3. プラグインをコピー

[PLUGIN_MATRIX.md](PLUGIN_MATRIX.md) の表に従う。**外すもの**:
EliteMobs / BlueMap / Backuper / DiscordSRV / Multiverse-Core / Multiverse-Portals / WorldGuard /
Geyser-Spigot / geyserExtra / ViaVersion / ViaBackwards。

```powershell
# 一旦全部コピーしてから外す方が漏れがない
Copy-Item "$main\plugins\*.jar" -Destination "$res\plugins\" -Force
Remove-Item "$res\plugins\EliteMobs.jar","$res\plugins\bluemap-*.jar",`
            "$res\plugins\Backuper-*.jar","$res\plugins\DiscordSRV-*.jar",`
            "$res\plugins\multiverse-*.jar","$res\plugins\worldguard-*.jar",`
            "$res\plugins\Geyser-Spigot*.jar","$res\plugins\geyserExtra-*.jar",`
            "$res\plugins\ViaVersion-*.jar","$res\plugins\ViaBackwards-*.jar" -ErrorAction SilentlyContinue
```

### 7-4. 一度だけ起動して plugins ディレクトリを作らせる

```
D:\game\minecraft\PaperServer\Velocity_for_TF\Resource_Server\  で paper を起動 → 起動しきったら stop
```

**この時点では TrinityForge の config が資源サーバ側に独自生成される。次の手順で捨てる。**

---

## 手順 8. ジャンクションを張る

**全サーバを停止した状態で、管理者コマンドプロンプトで行う。**
引数はバックエンドのディレクトリ名。**dev にも張る**（dev は main と同じ HuskSync DB を
使う構成なので、張らないと「同期された図鑑と dev 独自のスキル Lv」が混ざって書き戻る）。

```bat
ops\scripts\setup-junction.cmd Resource_Server
ops\scripts\setup-junction.cmd Dev_Server
```

このスクリプトは:

- メイン側の実体があることを確認する
- `Main_Server` を指定したら拒否する（実体側をリンクにしてはならない）
- 対象側に実ディレクトリがあれば**削除せず退避**する（`TrinityForge.pre-junction-<日時>`）
- 既に同じリンク先のジャンクションなら何もしない（冪等）
- `mklink /J` でリンクを張る

**検証**:

```cmd
dir /al "D:\game\minecraft\PaperServer\Velocity_for_TF\Resource_Server\plugins"
```

`<JUNCTION>  TrinityForge [D:\game\minecraft\PaperServer\TrinityForge\plugins\TrinityForge]`
のように表示されること。

---

## 手順 9. HuskSync を導入

### 9-1. ビルド — **済み**

**現行の Paper 1.21.11 に対応した公式リリースは存在しない**（3.8.7 は ≤1.21.8、3.9.0 は 26.1.2 のみ）。
`bukkit/1.21.11/` アダプタを持つのは master（4.0.0・未リリース）だけ。詳細は
[COST_AND_LICENSE.md](COST_AND_LICENSE.md) の「HuskSync」節。

**2026-07-27 にビルド済み。** そのまま使える。

| 項目 | 値 |
|---|---|
| 成果物 | `tmp/husksync-dist/HuskSync-Bukkit-4.0.0-3dc619d+mc.1.21.11.jar` |
| サイズ | 3,247,291 バイト |
| SHA-256 | `4E047339EFD25DD1DC776CF3E8D9F8AA007C54E35B77534367C8479274E82466` |
| 元コミット | `3dc619d5f641ee909004925dbbda2d507b7127c2`（master、2026-07-23） |
| プラグイン版 | `4.0.0-3dc619d` / `api-version: 1.21` / `folia-supported: true` |
| コンパイル対象 | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`（解決を実測確認） |
| ビルド JDK | **21 で通る**（ルートの `javaVersion=25` はこのバリアントには効かない。`bukkit/1.21.11/gradle.properties` が `java_version=21`） |

**jar は git に入れていない**（このリポジトリは public。`tmp/` は `.gitignore` 対象）。
両バックエンドの `plugins/` へコピーして使う。

再現手順:

```bash
git clone https://github.com/WiIIiam278/HuskSync.git
cd HuskSync
git checkout 3dc619d5f641ee909004925dbbda2d507b7127c2
./gradlew :bukkit:1.21.11:shadowJar
# target/HuskSync-Bukkit-4.0.0-3dc619d+mc.1.21.11.jar
```

`./gradlew build` だと 26.1.2 / 26.2 バリアントと Fabric も巻き込んで JDK 25 が要る。
**`:bukkit:1.21.11:shadowJar` だけを指定すること。**

> **未リリースブランチである点は理解して使うこと。** 上げ直すときは必ず新しいコミットハッシュを
> 記録し、[COST_AND_LICENSE.md](COST_AND_LICENSE.md) の表と本節を更新する。

### 9-2. 設定

**手順2（MariaDB + Redis）を先に終わらせること。** 繋ぐ先が無い状態で起動すると
`FailedToLoadException` → `Connection refused` で enable に失敗する
（それでもサーバ自体は起動してしまうので気付きにくい）。

両バックエンドの `plugins/` に jar を入れ、一度起動して `config.yml` を生成させる。
生成されたファイルに対して [templates/husksync.config.yml](templates/husksync.config.yml) の
項目を反映する（**丸ごと上書きしない**。バージョンでキー名が変わるため）。

生成直後の既定値は**必ず接続に失敗する**ので、まず資格情報を直す:

```yaml
database:
  type: MARIADB               # 既定は MYSQL
  credentials:
    host: 127.0.0.1           # 既定は localhost
    database: husksync        # 既定は HuskSync
    username: husksync        # 既定は root
    password: '<手順2 で作ったパスワード>'   # 既定は pa55w0rd
```

同期設定で特に重要な 4 つ:

```yaml
synchronization:
  mode: LOCKSTEP
  features:
    persistent_data: true    # TF の図鑑・称号・Ars のマナ。これが層 B
    location: false          # 必ず false。true だと資源側で岩盤に埋まる
    game_mode: false         # 【生成時の既定は true】必ず false。メインの world は creative
  attributes:
    ignored_modifiers:
    - minecraft:effect.*
    - minecraft:creative_mode_*
    - trinityforge:*         # 追記。TF が付け直す modifier を同期させない
    - arspaper:*             # 追記
```

`trinityforge:*` を追記する理由: TF はステータスを `trinityforge:perk_attr_<stat>` と
`trinityforge:statmod.<name>` という `AttributeModifier` として付与し、join と装備変更のたびに
config から**再計算して付け直す**。スキル Lv はジャンクションで既に共有されているので
同期する必要が無く、残すと再計算前の一瞬だけ別サーバの値が乗る。

**メインと資源で完全に同じ内容にすること。** 手で 3 台ぶん編集すると必ずどこかずれるので、
スクリプトで 1 つの正本から配る:

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File ops/scripts/apply-husksync-config.ps1 -DryRun
```

`-DryRun` を外すと実行する。**MariaDB のパスワードは引数で渡さず実行時に入力する**
（コマンド履歴にもプロセス一覧にも残さないため）。正本は `-BaseFrom` のサーバの
**生成済み** config.yml で、テンプレートの丸写しではないので版が変わってもキーがずれない。

反映後に `preflight.ps1` を流せば、既定値の残りと `game_mode` と除外漏れ、
さらに**サーバ間の設定差分**まで機械的に検出できる。

### 9-3. Dev_Server は別 DB に分ける

Dev_Server も HuskSync を入れるなら、**本番のプレイヤーデータを触らせないこと。**

**`cluster_id` を変えるだけでは足りない。** `cluster_id` は Redis のキーと
メッセージチャンネルにしか効かず（`RedisManager.java` を実読）、**スナップショットの
保存先である MySQL のテーブルは同じまま**なので、dev での実験が本番の
スナップショット履歴を書き換えてしまう。

分けるなら **DB を別に作る**:

```sql
CREATE DATABASE husksync_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL ON husksync_dev.* TO 'husksync'@'127.0.0.1';
```

```yaml
# Dev_Server/plugins/HuskSync/config.yml
database:
  credentials:
    database: husksync_dev
cluster_id: dev
```

これでも **Garnet / MariaDB / HuskSync の経路はすべて本番と同じものを通る**ので、
互換性の検証としては十分に意味がある。

逆に「3 台目のバックエンドとして本番同様に同期させたい」場合は
main / resource と同じ DB・同じ `cluster_id`・同じ `features` にする。
その場合 dev は本番ネットワークの一部なので、**壊す実験には使えない。**

---

## 手順 10. プロキシ用プラグインを入れる

Velocity の `plugins/` へ:

| プラグイン | 目的 |
|---|---|
| **Sonar** | antibot。設定方針は [templates/sonar.yml](templates/sonar.yml) |
| **FallbackRouter** | バックエンド停止時に在席者を自動退避（再起動・リセットの要） |
| **OneTimePack** | サーバ移動のたびにリソパを再送させない |
| LuckPerms-Velocity | 権限のネットワーク共有 |

**検証**: `/server resource` で移動でき、リソースパックのダウンロードが**走らない**こと。

---

## 手順 11. 自動化を登録する

### 11-1. ops-config.psd1 を作る

```powershell
Copy-Item "<repo>\ops\ops-config.sample.psd1" "<repo>\ops\ops-config.psd1"
# パスとポートを実環境に合わせる
```

RCON パスワードは**環境変数で渡す**（設定ファイルに書かない）。
変数名は `Servers` のキー名から決まる:

```powershell
setx TF_RCON_MAIN_PASSWORD     "<Main_Server の rcon.password>"
setx TF_RCON_RESOURCE_PASSWORD "<Resource_Server の rcon.password>"
setx TF_RCON_DEV_PASSWORD      "<Dev_Server の rcon.password>"
```

`Servers` にサーバを足せばスクリプト側の変更は不要
（`restart-server.ps1 -Target dev` のように名前で指せる）。

### 11-2. スクリプトを空撃ちして確認する

`launch\testkit\check-all.cmd` が下記を全部まとめて流す（[launch/testkit/README.md](launch/testkit/README.md)）。
個別に流すなら:

```powershell
cd <repo>\ops\scripts
.\run-selftest.ps1                 # 削除ガードの実測。25/25 になること
.\preflight.ps1                    # 実環境の起動前チェック。0 件になること
.\apply-velocity-forwarding.ps1 -DryRun   # 「変更なし」が全サーバで出ること
.\apply-husksync-config.ps1     -DryRun   # 差分と配布先を確認する
.\sync-configs.ps1     -DryRun
.\restart-server.ps1   -DryRun -Target both
.\stop-network.ps1     -DryRun     # 逆順・main が最後になっていること
.\reset-resource.ps1   -DryRun
.\backup.ps1           -DryRun
.\show-status.ps1                  # 何が上がっているか（launch\status.cmd と同じ）
.\check-logs.ps1                   # 起動後に流す。既知の症状を拾う
```

**`reset-resource.ps1 -DryRun` の出力で、削除対象の絶対パスが想定どおりか必ず目視すること。**
`plugins\TrinityForge` が含まれていたら、そのまま実行してはいけない（スクリプトが自動で拒否するが）。

### 11-3. server-loop.cmd で起動するように切り替える

`launch\start-*.cmd` が `server-loop.cmd` 経由で起動するので、**手順 13 の
`launch\start-all.cmd` を使えばこれは満たされる**。単体で叩くなら:

```bat
REM main
ops\scripts\server-loop.cmd "D:\game\minecraft\PaperServer\Velocity_for_TF\Main_Server"     8G paper-1.21.11-132.jar

REM resource
ops\scripts\server-loop.cmd "D:\game\minecraft\PaperServer\Velocity_for_TF\Resource_Server" 6G paper-1.21.11-132.jar
```

**起動順は main → resource。**

### 11-4. タスクスケジューラ

| タスク | スケジュール | コマンド |
|---|---|---|
| 日次再起動 | 毎日 05:00 | `powershell -NoProfile -ExecutionPolicy Bypass -File <repo>\ops\scripts\restart-server.ps1 -Target both` |
| 週次リセット | 毎週 月 04:00 | `powershell -NoProfile -ExecutionPolicy Bypass -File <repo>\ops\scripts\reset-resource.ps1` |
| 日次バックアップ | 毎日 04:30 | `powershell -NoProfile -ExecutionPolicy Bypass -File <repo>\ops\scripts\backup.ps1` |

週次リセットは日次再起動より前に置く（リセット後に再起動が走ると二度手間）。
タスクは「ユーザーがログオンしているかどうかにかかわらず実行する」にし、
環境変数 `TF_RCON_*` がそのアカウントに設定されていることを確認する。

---

## 手順 17. 資源サーバへ構造物データパック（案1）を入れる

### ⚠⚠ 先に読む: 週次リセットと正面衝突する

週次リセット（`reset-resource.ps1`）は **`world` ディレクトリを丸ごと削除する**。
Minecraft がデータパックを読むのは `world\datapacks` なので、
**そこにだけ置くと初回の月曜 04:00 で全部消える。**
しかも消えても**エラーは一切出ない**。資源ワールドが黙ってバニラ地形で再生成され、
ArsPaper が足している戦利品プール（`nova_structures:*` など）も namespace ごと当たらなくなる。

そのため **正本は `Resource_Server\datapacks-source`（`world` の外）に置き、
リセットのたびにスクリプトが `world\datapacks` へ複製し直す。**
設定は `ops-config.psd1` の `ResourceDatapacks`。

```powershell
# 1. 何が入るかを先に見る（コピーしない）
powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\install-datapacks.ps1 -DryRun

# 2. 正本へ配置する（資源サーバが停止していれば world にも即反映される）
powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\install-datapacks.ps1
```

zip の置き場は既定で `<repo>\tmp\worldgen\downloads`。`-SourceDir` で変えられる。

### 入るもの（案1 = 17 パック）

DnT 本体 + オーバーホール 10 種、Structory、Structory Towers、Towns and Towers、
Terralith、Incendium、Nullscape、要塞オーバーホール 1 種。

- **要塞オーバーホールはフル版と LITE 版の排他**。両方入れると同じ構造物を 2 つのパックが
  上書きし合い、どちらが勝つかは読み込み順まかせになる。既定はフル版
  （`-StrongholdVariant Lite` で切り替え）。
- スクリプトは**案1 に無い zip をコピーしない**。`downloads` には `minecraft_server.jar` の
  ようなデータパックでない物も混ざっている。
- 同じパックの複数版が `downloads` に並んでいたら**中断する**（どれが効くか読み込み順まかせになるため）。

### 反映のタイミング

| 状況 | 反映 |
|---|---|
| 資源サーバ停止中に実行 | `world\datapacks` へ即入る。ただし**新しい構造物は未生成のチャンクにしか出ない** |
| 資源サーバ稼働中に実行 | 正本だけ更新。`world` へは次の週次リセットで入る |
| 週次リセット | 毎回、正本から**総入れ替え**で入れ直す（正本から外したパックは配置先にも残らない） |

既存ワールドに後から入れると生成済み範囲との継ぎ目ができるので、
**行き渡らせたいなら `reset-resource.ps1` でワールドごと作り直す**のが正しい。

### メインサーバには入れない

案1 は「資源だけ」の決定。メインは既存ワールドが育っているので、
地形データパック（Terralith / Incendium / Nullscape）を後入れすると継ぎ目ができる。
ArsPaper の `loot-tables.yml` はバニラのルートテーブルもティア分けしてあるので、
**メイン側もデータパック無しのまま戦利品の底上げだけは効く。**

### `maintenance.flag`（リセット中の再起動の保留）

`server-loop.cmd` は Paper が落ちると 10 秒後に起動し直す。数 GB のワールド削除は
10 秒では終わらないので、そのままだと**削除の途中で Paper が起動する**。
`reset-resource.ps1` は停止前に `maintenance.flag` を置いてループを保留させ、
削除とデータパック再配置が済んでから外す。

- `stop.flag` と違い、**ループから抜けさせない**（消せばそのまま起動し直す）。
- 整合チェック（`sync-configs.ps1`）が失敗した場合は**わざと外さない**。
  壊れた config のまま起動させないため。指摘を直してから
  `Resource_Server\maintenance.flag` を手で消せば、`server-loop.cmd` が自動で起動する。
- リセットが異常終了して flag が残っていないかは、朝いちで確認する
  （残っていると**資源サーバが上がらないまま**になる）。

---

## 手順 12. 受け入れ確認

上から順に、実際にプレイして確認する。

| # | 確認項目 | 対応する層 |
|---|---|---|
| 1 | Java / Bedrock の両方でプロキシ経由ログインできる。バックエンドへの直結が拒否される | — |
| 2 | `/server resource` で移動でき、**スキル Lv とパークが引き継がれている** | C |
| 3 | 図鑑・実績・称号・採取トグル・Ars のマナと解放済みグリフが引き継がれている | B |
| 4 | TF 装備を持って往復し、**Lore とステータス表示が 1 文字も変わらない**（前後をスクショ比較） | — |
| 5 | 資源サーバで採掘して採取スキル EXP が入り、メインへ戻っても保持されている | C |
| 6 | 資源サーバで拾ったアイテムがメインのインベントリに存在する | A |
| 7 | **資源サーバで `/sethome` → `/home` が動き、リセット後に home が消えている** | — |
| 8 | メインが creative の `world` に居てもサーバ移動で gamemode が変わらない | A |
| 9 | 3 ワールドで `/worldborder get` が 5000 を返し、再起動後も維持される | — |
| 10 | 資源サーバでモブを倒して、レベルに応じた HP・攻撃力・EXP になっている（EliteMobs 無しで） | — |
| 11 | リセットを手動で 1 回完走し、在席者が自動退避され、**`plugins/TrinityForge` の実体が無傷** | — |
| 12 | 日次再起動が動き、再起動中も他方のサーバへ退避されてネットワークから切断されない | — |
| 13 | 移行前後の spark レポートを比較し、メインのチャンク生成負荷が下がっている | — |

**4 が崩れた場合**: アイテムのステータスは焼き込まれず `roll_seed` + `quality` から
サーバ側 yml で毎回導出される。両サーバの `item-stats` 系 config が一致していないと崩れる。
ジャンクションが正しく張れていれば起こらないはずなので、まず手順 8 の検証をやり直す。

**7 の後半（リセットで home が消える）**: `plugins/SetHome/homes.yml` は
[reset-resource.ps1](scripts/reset-resource.ps1) の削除対象に入っている。
ワールドが消えたのに home 座標だけ残ると、次のワールドの無関係な地点へ飛ばされるため。
資源サーバの `max-homes` は 3 程度に下げることを推奨（毎週消える前提なので 15 は多い）。

---

## 手順 13. 日々の起動と Windows 再起動後の手順

### 13-1. 何が自動で上がり、何が上がらないか

| コンポーネント | Windows 再起動後 | 根拠 |
|---|:---:|---|
| **MariaDB** | **自動で上がる** | Windows サービス（`Install as service`） |
| **Garnet** | 手順 11 の起動時タスクを登録すれば自動。**未登録なら上がらない** | `garnet.cmd` は常駐プロセスであってサービスではない |
| **Velocity / 各バックエンド** | 13-4 の `TF Network` タスクを登録すれば自動。**未登録なら上がらない** | `launch\start-all.cmd` は手動起動 |
| **config-editor** | 手動 | Node のプロセス |

`launch\status.cmd` で「上がっているつもり」を潰せる（プロセスの有無だけでなく、
3306 / 6379 に**何がいるか**をプロトコルで確かめる）。

### 13-2. 起動順（これを守る）

```
1. MariaDB      … 自動。Get-Service MariaDB が Running であること
2. Garnet       … バックエンドより【先】に上げる
3. preflight.ps1 … ここで 0 件になってから 4 へ進む
4. main         … 最初に上げる
5. resource     … main が起動しきってから
6. dev          … 最後
7. Velocity     … バックエンドが揃ってから
```

理由:

- **Garnet と MariaDB がバックエンドより先**。繋ぐ先が無いと HuskSync は enable に失敗し、
  しかも**サーバの起動は止まらない**ので、同期されないまま運用する事故になる
- **main → resource の順**。`plugins/TrinityForge` は実体を共有しているので、
  初回スキーママイグレーションを同時に走らせない
- **Velocity は最後**。先に上げるとプレイヤーが「繋がるが飛べない」状態を踏む

停止するときは**逆順**（Velocity → dev → resource → main → Garnet）。
Velocity を先に落とせばプレイヤーが切断されてから保存が走る。

### 13-3. Windows 再起動後の手順

`D:\game\minecraft\PaperServer\Velocity_for_TF\launch\` に一式置いてある（正本は
[ops/launch/](launch/)、配り直しは `ops\launch\deploy-launch.cmd`）。**通常はこれ 1 本**:

```bat
D:\game\minecraft\PaperServer\Velocity_for_TF\launch\start-all.cmd
```

MariaDB → Garnet → preflight → main → resource → dev → Velocity の順に待ちを挟んで起動し、
**preflight が 1 件でも検出したらサーバを上げずに中断する**。

起動したら**必ず**ログを見る（HuskSync の enable 失敗はサーバ起動を止めないため）:

```bat
D:\game\minecraft\PaperServer\Velocity_for_TF\launch\testkit\check-logs.cmd
```

停止は `launch\stop-all.cmd`（逆順・`stop.flag` を置く）、
現況確認は `launch\status.cmd`。詳細は [launch/README.md](launch/README.md)。

サーバ 4 台は **Windows Terminal の 1 ウィンドウにタブ**でまとまる（ウィンドウ名 `TrinityForge`）。
あとから 1 台だけ上げ直しても同じウィンドウにタブが増える。
**Garnet はウィンドウを出さず**、出力は `D:\game\minecraft\Garnet\logs\garnet.log` に落ちる。

> **`.cmd` に日本語を書かないこと。** cmd.exe は UTF-8 のバッチファイルを正しく読めず、
> マルチバイト文字があるとファイル位置の計算がずれて**行の途中から実行を始める**。
> 実際に `server-loop.cmd` が日本語コメント入りだった間、引数検査も `stop.flag` 判定も
> 素通りして**空回りする無限ループ**になっていた（2026-07-27 に実測）。
> 説明は `.ps1` と `.md` に置く（PowerShell は UTF-8 で問題ない）。
> `run-selftest.ps1` が `ops` 配下の全 `.cmd` を走査して非 ASCII を落とす。

### 13-3b. 初回起動の【あと】でないとできないこと

config が生成されてからでないと触れないものを、ここにまとめる。
どれも `launch\testkit\check-logs.cmd` が症状として拾うので、起動後に必ず流す。

| やること | いつ | 中身 |
|---|---|---|
| **BlueMap の Web ポート** | main と dev の**両方を初めて上げた直後** | 既定は両方 8100 で、後から上げた方が `Address already in use` で失敗する。dev 側の `plugins\BlueMap\webserver.conf` の `port` を **8101** に変える |
| **ワールドボーダー** | 各ワールドが生成された直後 | 3 ワールドで `/worldborder center 0 0` → `/worldborder set 5000`。resource は毎週再生成されるので、リセット後にも必要（`reset-resource.ps1` の起動後コマンドに入れてある） |
| **LuckPerms の権限** | `storage-method: mariadb` へ切替後 | 下記 |

> **LuckPerms を h2 から切り替えると、h2 に入っていた権限は見えなくなる。**
> ファイル自体は `Dev_Server\plugins\LuckPerms\luckperms-h2-v2.mv.db` に残っているので、
> 引き継ぎたい場合は **一度 `storage-method: h2` へ戻して `/lp export perms` → `mariadb` に戻して
> `/lp import perms`**。作り直すなら不要。詳しくは手順 14。

### 13-5. ソースを変えたときの配備（ビルド → 3 バックエンドへ jar を配る）

`launch\deploy.cmd` 1 本で、**TF 本体 / ArsPaper フォーク / EliteMobs フォークのうち
「前回ビルド以降にソースが変わったもの」だけをビルドして、3 バックエンドへ jar を配る**。

```bat
D:\game\minecraft\PaperServer\Velocity_for_TF\launch\deploy.cmd
```

**まず `--dry-run` で計画を見るのを勧める**（ビルドもコピーも削除も一切しない）。

| オプション | 何をするか |
|---|---|
| なし | 変わったものをビルド → jar を配る。**サーバが動いていたら何もせず中断** |
| `--dry-run` | 何をビルドし、どこへ何を上書きするかだけを出す。**書き込みゼロ** |
| `--build-only` | ビルドまで。サーバ側のファイルには一切触らない |
| `--config` | jar のあとにリポジトリの yml も配る（既定は配らない。理由は後述） |
| `--restart` | `stop-all` → 全台の停止を待つ → 配備 → `start-all` を一括で行う |
| `--force` | **稼働中でも配る。** 原則使わない（後述の警告） |

正本はリポジトリの `ops/launch/deploy.cmd`。配置先の `launch\` は
**ジャンクションではなく実体のコピー**（`dir /al` に出ない普通のディレクトリ）なので、
**リポジトリ側を直したら `ops\launch\deploy-launch.cmd` で配り直す**
（`deploy.cmd` = プラグイン jar、`deploy-launch.cmd` = 起動スクリプト。別物）。

##### 初回だけ: `D:` 側へ持っていく

`D:\...\launch\deploy.cmd` は 2026-07-31 まで「launch フォルダを配るスクリプト」だった。
新しい `deploy.cmd`（プラグインをビルドして配る方）を置くには、**リポジトリ側から 1 回だけ**
これを実行する（エージェントは `D:` 配下へ書けないので、ここはユーザーが実行する）:

```bat
C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\trinityforge\ops\launch\deploy-launch.cmd
```

以後は配置先の `D:\...\launch\deploy.cmd` を叩けばよい。まずは書き込みゼロの下見から:

```bat
D:\game\minecraft\PaperServer\Velocity_for_TF\launch\deploy.cmd --dry-run
```

#### 何をどこへ配るか（ジャンクションの都合で回数が違う）

| もの | ビルド | 成果物 | 配り先 | 回数 |
|---|---|---|---|---|
| TF 本体 | `gradlew releaseAssembly --offline` | `TrinityForge\build\release\TrinityForge-all.jar` | 各 `<backend>\plugins` | **3 回**（実体ファイル） |
| ArsPaper | `gradlew jar --offline` | `fork-handoff\arspaper\fork\build\libs\ArsPaper-1.0.0.jar` | 各 `<backend>\plugins` | **3 回** |
| EliteMobs | `gradlew shadowJar --offline` | `fork-handoff\elitemobs\elitemobs-fork\testbed\plugins\EliteMobs.jar` | main / dev | **2 回**（後述） |
| TF の yml（`--config`） | — | `TrinityForge\src\main\resources\**\*.yml` | `Main_Server\plugins\TrinityForge` | **1 回**（ジャンクション共有） |
| ArsPaper の yml（`--config`） | — | `fork-handoff\arspaper\fork\src\main\resources\*.yml` | 各 `<backend>\plugins\ArsPaper` | **3 回**（実体ディレクトリ） |

- **TF の yml は 1 回だけ。** `Resource_Server` と `Dev_Server` の `plugins\TrinityForge` は
  `Main_Server` 側への NTFS ディレクトリジャンクションなので、main へ置けば 3 台に効く
  （実測: `dir /al` でリンクとして見える）。**jar は 3 台とも実体ファイル**なので 3 回必要。
- **ArsPaper の yml は 3 回。** `plugins\ArsPaper` はどの台も実体ディレクトリ
  （だから `sync-configs.ps1` が main → resource のコピー同期を持っている）。
- **EliteMobs は Resource_Server に入れない。** `ops/PLUGIN_MATRIX.md` の方針どおり実機にも
  置かれていないので、`deploy.cmd` は `[SKIP] Resource_Server: EliteMobs is not installed here`
  と出して**新規インストールはしない**。配備先は「既にその jar があるバックエンド」だけ。
- **上書き先のファイル名は「今入っている名前」を使う。** ステージされる成果物は
  `TrinityForge-all.jar` だが実機に入っているのは `TrinityForge-0.1.0-SNAPSHOT-all.jar`。
  成果物名でコピーすると同じプラグインの jar が 2 つ並び、Paper が
  `Ambiguous plugin name` で起動不能になる（ArsPaper で実際に起きた事故）。
  同じパターンに 2 つ以上一致したら、配らずにエラーで止める。
- **EliteMobs の配布 jar は必ず `testbed/plugins/EliteMobs.jar`（全同梱 uberjar）。**
  `build/libs/EliteMobs-*-min.jar` は MagmaCore が剥がされていて
  `NoClassDefFoundError: com/magmaguy/magmacore/location/DungeonLocator` で起動不能。
  `deploy.cmd` は `shadowJar` を叩き、成果物として uberjar 側だけを見る。
- jar を置き換えたら `plugins\.paper-remapped\<同名>` を消す（あれば）。Paper が古い
  remap キャッシュを再利用しないようにするため。EliteMobs は remap 対象、
  `paper-plugin.yml` 方式の TF / ArsPaper はそこに現れないので何もしない。

#### 更新判定の方式と、その限界

判定は**タイムスタンプ 1 本**。「監視パス配下の**最新更新時刻** > 成果物 jar の更新時刻」なら
ビルドし、そうでなければスキップして理由を表示する（内容ハッシュも依存グラフも見ない）。

監視パス:

| 対象 | 監視するもの |
|---|---|
| TF 本体 | `TrinityForge\src\main` / `build.gradle.kts` / `gradle.properties` |
| ArsPaper | `fork\src\main` / `build.gradle.kts` / `gradle.properties` / **`fork\libs\TrinityForge.jar`** |
| EliteMobs | `elitemobs-fork\src\main` / `build.gradle` / **`elitemobs-fork\libs\TrinityForge.jar`** |

- **`src\test` は見ない。** `releaseAssembly` はテストを走らせないので、テストだけを直しても
  配備物は変わらない。テストを流すのは `gradlew test` の仕事で、配備の仕事ではない。
- **`libs\TrinityForge.jar` を監視対象に入れているのは意図的。** これはフォークが
  `compileOnly` で参照する TF の ABI なので、これが新しくなったらフォークは必ず再ビルドが要る。
- **限界 1: 「古いファイルに戻す」変更は検出できない。** `git checkout` で**更新時刻ごと古い内容に
  戻した**ような場合、最新更新時刻が jar より古くなり「変更なし」と判定する。
  こういうときは成果物 jar を消してから実行する（`deploy.cmd` は jar が無ければ必ずビルドする）。
- **限界 2: 時計が巻き戻ると同じことが起きる。** 判定は UTC の更新時刻で行う。
- **限界 3: 余分なビルドは起きうる（無害）。** ファイルを開いて保存し直しただけでもビルドする。
  Gradle 自身の up-to-date 判定が効くので数秒で終わる。**危ないのは「スキップしすぎ」だけ**で、
  それが起きるのは上の限界 1 / 2 のときだけ。
- **TF を再ビルドすると 2 フォークも必ず再ビルドされる。**
  `releaseAssembly` が両フォークの `libs/TrinityForge.jar` を毎回書き直すので、
  監視対象の更新時刻が必ず進む。ABI が実際に変わったかまでは見ていない（意図的に安全側）。
- TF をビルドしなかった場合だけ、`libs/TrinityForge.jar` を**内容ハッシュ**で TF の thin jar と
  比べて、違っていれば差し替えてからフォークをビルドする（誰かが `gradlew jar` だけを
  叩いた後に、フォークが古い TF ABI に対してコンパイルされるのを防ぐ）。
  タイムスタンプで比べない理由は「`releaseAssembly` が毎回書き直すので必ず差が出て意味を持たない」。

> **`fork-handoff/*/libs/TrinityForge.jar` は tracked（版管理下）。** `deploy.cmd` が差し替えても
> **commit してはいけない**。このリポジトリは public なので、TF 本体の jar を公開する事故になる。

#### 稼働中の jar 差し替えは禁止（このスクリプトが止める）

**稼働中サーバの jar を上書きすると必ず `NoClassDefFoundError` になる。**
JVM は未ロードのクラスを実行時に jar から読みに行くので、上書きした瞬間ではなく
「次にその新クラスへ到達したとき」に落ちる。**`/reload` では直らず、JVM の完全な stop → start が
唯一の復旧手段**。落ちた場所が進行データの保存経路だと、その間の保存が丸ごと失われる。

`deploy.cmd` は配備の前に `ops\scripts\check-servers-stopped.ps1` を通し、
1 台でも動いていたら**ビルドもコピーもせずに中断**する。判定は 2 系統:

1. RCON ポート（25586 / 25587 / 25588）が LISTEN しているか
2. `<backend>\world\session.lock` が排他ロックされているか

> **java.exe のコマンドラインでは判定できない。** `server-loop.cmd` は
> `cd /d <root>` してから `java -jar "paper-....jar" nogui` を叩くので、コマンドラインに
> サーバ名も Root も現れない。**同じ理由で `launch\status.cmd`（`show-status.ps1`）の
> main / resource / dev の行は、実際には稼働中でも「停止」と出る**（2026-07-31 実測）。
> 起動の有無は `check-servers-stopped.ps1` で見ること。

`--force` を付けると稼働中でも配る。**通常は使わない。** 使った場合は警告が出て、
**3 台すべてを完全に停止 → 起動し直すまで、サーバは壊れた状態のまま**になる。

停止 → 配備 → 起動を一括でやりたいときは `--restart`:

```bat
launch\deploy.cmd --restart
```

`stop-all.cmd`（RCON。`TF_RCON_MAIN_PASSWORD` などの環境変数が必要）→ 全台が落ちるのを
最大 300 秒待つ → 配備 → `start-all.cmd`。**停止しきらなければ何も配らずに中断する。**
既定は「停止しない（＝停止していなければ中断）」のままにしてある。

失敗したら**その時点で止まり、非ゼロで終了する**。jar → yml の順に処理するので、
jar が 1 本でも失敗したら yml には触らない（config だけ新しい状態を作らない）。

#### なぜ `--config` は既定で off なのか

- `--config` は**ワーキングツリー**をコピーするので、共有ワークツリーでは他セッションの
  編集途中がそのまま出荷される。**HEAD だけを配る `deploy-config-head.cmd` を使うこと。**

> **⚠ 2026-08-08 訂正（1 週間ぶんのソース階梯が届いていなかった原因）**
>
> ここには以前「`sourcejars.yml` / `sourcelinks.yml` は稼働中のサーバが書き込む実状態
> （ブロック座標）なので必ず除外する」と書いてあったが、**これは取り違え**だった。
> その 2 本は**読み取り専用の定義ファイル**（ジャー容量・上位ソースリンクの階梯・燃料の点数）で、
> `SourceJarConfig` / `SourcelinkConfig` は `loadConfiguration` と `saveResource(name, false)` しか
> 呼ばない。**ブロック座標を書いているのは `source-network.yml`**（`SourceNetwork#saveSnapshot`）で、
> こちらはプラグインの resources に無いので配備の対象にそもそも入らない。
>
> この誤除外が `deploy.cmd` / `deploy-config-head.cmd` / `deploy-config-skip-wip.cmd` /
> `ops-config.psd1` / `seed-backend-configs.ps1` / `sync-configs.ps1` の 6 箇所に広がっており、
> 2026-08-02〜04 に追加した**上位ソースリンク II〜V・上位ジャー・階梯触媒の焼べ値が
> 1 台にも届いていなかった**（lore に「焼べると 500 ソース」と書いてある物を焼べても無反応）。
>
> **プラグイン側の自己修復も効かない。** `ArsPaper#updateResourceFiles` は
> **プラグインのバージョン文字列が変わったときだけ**同梱 yml を展開し直すが、フォークは
> ずっと `0.1.0-SNAPSHOT` なのでこのゲートは一度も開いていない。
> **ArsPaper の yml 変更がサーバへ届く経路は配備スクリプトだけ。**
- yml の配布はもともと **config-editor が保存時にミラー**する仕組みを持っている
  （`tools/config-editor/tool-config.json` の `deployPaths`。ただし向き先は Dev_Server だけ）。
- コピーは**上書きのみで削除はしない**ので、プラグインが自分で生成した yml
  （`armors.yml` / `world_settings.yml` など）は残る。
- yml を配った後は各サーバで `/tf reload`（ArsPaper は `/ars reload`）が必要。
  ファイルは共有でも**メモリ上の config は共有されない**。

> ⚠️ **配備される ArsPaper jar には、他セッションの未コミット作業が焼き込まれることがある。**
> ビルドは常に**作業ツリー**から行う（そうでないと自分の変更が配備されない）。
> しかも `fork-handoff/arspaper/fork/` は `.gitignore` 除外なので、**フォークの変更は
> このリポジトリの commit に一切残らない**。同一ワークツリーで並行セッションが動く運用なので、
> 「配備した jar の中身 = 直前の commit」とは限らない。配備前に
> `git status`（TF 本体側）と、ArsPaper については**誰が何を編集中かの確認**をすること。
> `deploy.cmd` の挙動としてはこれで正しいので、スクリプト側では何も変えていない。

#### `.cmd` を編集するときの追加ルール（ASCII だけでは足りない）

- **非 ASCII を書かない**（13-3 の枠のとおり）。
- **`goto` / `call :label` を使う `.cmd` は改行コードを CRLF にする。**
  LF のみだと cmd.exe がラベルを見つけられず
  `The system cannot find the batch label specified - <name>` で落ちる（2026-07-31 実測。
  `deploy.cmd` を LF で書いて実際に踏んだ）。`.gitattributes` は `*.cmd` を
  `text eol=lf` の対象に**していない**ので、CRLF のままコミットされる。
  実際 `ops` 配下で `goto` を使っているのは `server-loop.cmd` と `deploy.cmd` の 2 本だけで、
  どちらも CRLF になっている。
- **`shift` は `%0` も一緒にずらす。** オプション解析で `shift` を使うと、その後の `%~dp0` は
  スクリプトの場所ではなく「消費した引数の文字列をカレントディレクトリ基準で解いたもの」になる。
  `deploy.cmd` は先頭で `set "SELF=%~dp0"` を取り、`shift /1` を使っている。

---

## 手順 14. LuckPerms の設定（dev = 管理者専用）

### 14-1. まず `server:` を分ける — **これが無いと per-server 権限が全部 global になる**

`plugins\LuckPerms\config.yml` の `server:` は、そのサーバに居るプレイヤーへ自動で付く
`server` コンテキストの値になる。**`global` のままだとこの値が付かず**、
`/lp user X permission set foo true server=dev` のような指定が
「どのサーバでも一致しない」ではなく「コンテキスト無しの永続付与」として効いてしまう。
dev だけの権限のつもりが main でも効く、という壊れ方をする。

| サーバ | `config.yml` の `server:` |
|---|---|
| Main_Server | `main` |
| Resource_Server | `resource` |
| Dev_Server | `dev` |

**設定済み（2026-07-28）。** 変更は再起動で反映される。
`include-global: true` はそのままでよい（コンテキスト指定の無い権限は全サーバで効く、が期待どおり）。

`messaging-service: redis` ＋ `sync-minutes: -1` も現状のままでよい。
Redis で push されるので、どのサーバで `/lp` を叩いても即座に 3 サーバへ伝わる。
`sync-minutes` を正の値に戻すのは、messaging が壊れているときの保険としてだけ。

### 14-2. dev を管理者専用にする — **LuckPerms だけでは実現できない**

Velocity の `/server` コマンドが見る権限ノードは
**`velocity.command.server` の 1 個だけ**（`velocity-4.1.0-SNAPSHOT-9.jar` の
`ServerCommand.class` を逆アセンブルして確認済み。行き先ごとのノードは存在しない）。
しかも判定は `getPermissionValue(...) != Tristate.FALSE` なので、
**UNDEFINED（＝未設定）は「許可」**として扱われる。

つまり:

- ノードを否定すると `/server` そのものが使えなくなる（main ⇄ resource の移動も止まる）
- そもそも**プロキシに LuckPerms が入っていない**ので、今は全員が `/server dev` を実行できる

したがって dev の入口はバックエンド側で閉じる。

```properties
# Dev_Server\server.properties
white-list=true
```

`Dev_Server\whitelist.json` に入れた相手だけが接続できる。
弾かれた側は「You are not white-listed on this server!」でプロキシに残るだけなので、
main に居るプレイヤーが巻き込まれることはない。

> `ops\scripts\purge-player-data.ps1` が `-KeepOps` のメンバーで
> `whitelist.json` と `white-list=true` をまとめて書く。あとから足すときは
> dev のコンソールで `whitelist add <MCID>`（Bedrock は先頭のドット込み）。

### 14-3. グループを作る

MariaDB へ切り替えた直後は `default` しか無い。dev のコンソール（またはゲーム内）で:

```
/lp creategroup admin
/lp creategroup moderator

/lp group admin setweight 100
/lp group moderator setweight 50
/lp group moderator parent add default
/lp group admin parent add moderator
```

管理者へ:

```
/lp user Klee319 parent add admin
```

### 14-4. 権限を割り当てる

**全サーバで効かせるもの（コンテキスト指定なし）**

```
/lp group admin permission set luckperms.* true
/lp group admin permission set trinityforge.elitemobs.commands true
/lp group admin permission set husksync.command.husksync true
/lp group admin permission set bukkit.command.op false
```

- `trinityforge.elitemobs.commands` … TF 本体の `EliteMobsCommandGateListener` が
  `/em` `/elitemobs` `/ag` を全ブロックしている。管理者だけ解禁するためのノード
- `bukkit.command.op` を明示的に `false` にしておくと、
  ops.json 経由の全能と LuckPerms の管理を混ぜずに済む

**dev でだけ効かせるもの**

```
/lp group admin permission set minecraft.command.gamemode true server=dev
/lp group admin permission set worldedit.* true server=dev
```

`server=dev` が効くのは 14-1 を済ませてあるからで、そこを飛ばすと全サーバに付く。

**確認**

```
/lp user Klee319 info
/lp user Klee319 permission check trinityforge.elitemobs.commands
/lp group admin permission info
```

### 14-5. ops はできるだけ使わない

`ops.json` の level 4 は LuckPerms の外側にあり、否定ノードでも止められない。
dev の検証で `/gamemode` などが要るだけなら、14-4 の `server=dev` 付き権限で足りる。
**op は「LuckPerms 自体が壊れたときの復旧経路」として、自分 1 人だけに残す。**

### 14-6. `velocity.toml` の `try` から dev を外す

```toml
try = ["main"]
```

`try` は**接続してきたプレイヤーを最初に送る先**（と、落ちたときの避難先）の順番。
`["main", "resource", "dev"]` のままだと、main が落ちている間に来た人が
resource や dev に着地する。dev を管理者専用にする以上ここに置いてはいけない。
resource も、資源集めは `/server resource` で自分から行く場所なので入れない。

**設定済み（2026-07-28）。**

---

## 手順 15. テストプレイのデータを消す

dev で試した分のインベントリ・実績・権限を消して、まっさらから始めるとき。

```powershell
cd C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\trinityforge\ops\scripts

# 1. まず下見（何も消さない）
.\purge-player-data.ps1

# 2. 3 バックエンドを停止してから実行
.\purge-player-data.ps1 -Apply

# 3. 出力された SQL を流す（パスワードは対話入力）
& 'C:\Program Files\MariaDB 12.3\bin\mariadb.exe' -u root -p -e "source D:/game/minecraft/PaperServer/Velocity_for_TF/purge-player-data-<日時>.sql"
```

> **PowerShell では `< file` が使えない**（`演算子 '<' は将来の使用のために予約されています`）。
> `Get-Content | mariadb.exe` も標準入力がパスワード入力と食い合うので不可。
> クライアント組み込みの `source` に渡すこと。パスは `/` 区切りで書く。

押さえておくこと:

- **サーバが起動していると中断する。** 起動中に消しても、停止時に Paper が書き戻し、
  HuskSync が MariaDB から復元するので消えない
- **SQL を飛ばすと元に戻る。** インベントリの実体は `husksync_user_data`、
  権限の実体は `luckperms_*` にある。ファイルを消すだけでは不十分
- `plugins\TrinityForge\player_progression.db`（スキル Lv / SP / パーク）は
  3 サーバでジャンクション共有している実体なので、消すと 3 サーバ全部から消える。
  残すなら `-KeepProgression`
- 消す前の内容は `Velocity_for_TF\_purge-backup-<日時>\` に丸ごと退避される
- ワールドの地形・config・jar には触らない

---

## 手順 16. サーバ間チャットと管理者 TP

TrinityForge 本体の機能（`plugins\TrinityForge\network.yml`）。プロキシ用プラグインは要らない。

### 16-1. 何に乗っているか

Velocity の**旧 BungeeCord プラグインメッセージチャンネル**。
`velocity-4.1.0-SNAPSHOT-9.jar` の `BungeeCordMessageResponder` を逆アセンブルして
`Connect` / `ConnectOther` / `Forward` / `ForwardToPlayer` / `GetServer` / `PlayerList` が
実装されていることを確認済み。有効化は `velocity.toml` の
`bungee-plugin-message-channel = true`（既定で true）。

> **定番の HuskChat は開発終了しており Velocity 4 では使えない。**
> プロキシ側プラグインを足す道は塞がっているので、バックエンド（＝ TF 本体）側で実装している。

**前提: プラグインメッセージはプレイヤーの接続に相乗りする。**
オンラインが 0 人のサーバは送ることも受け取ることもできない。
チャットも TP も「相手が居る」ことが前提なので実害は無いが、
「無人のサーバには何も届かない」は仕様として覚えておく。

### 16-2. 自分がどのサーバかは config に書かない

`plugins\TrinityForge` は main の実体を resource / dev がジャンクションで指しているので、
**サーバごとに違う config を置けない。** そこで自分の名前はプロキシに `GetServer` で聞き、
`network.yml` には「プロキシ上のサーバ名 → 表示名」の対応表だけを置いている。

```yaml
chat:
  servers:
    main: "メイン"
    resource: "資源"
    dev: "開発"
```

`velocity.toml` の `[servers]` に名前を足したら、ここにも足す。
書き忘れるとサーバ名がそのまま出る（空にはしない＝設定漏れが目に見える）。

### 16-3. チャット

3 台のどこで喋っても全台に `【資源】mcid: hello` の形で流れる。
**自分のサーバの発言も同じ書式に書き換える** ので、1 つの画面に 2 種類の書式が混ざらない。

書式は `chat.format`（MiniMessage）。`%server%` / `%player%` / `%message%` が使える。
**本文と名前は文字列置換していない** ので、プレイヤーが `<click:run_command:'/op me'>` と
打っても解釈されない（`ChatFormatTest` で固定してある）。

### 16-4. 管理者 TP

| コマンド | 動き |
|---|---|
| `/tpto <player>` | 実行者が相手のところへ飛ぶ |
| `/tphere <player>` | 相手を実行者のところへ引っぱる |

権限は `trinityforge.admin`。同じサーバに相手が居れば普通のテレポート、
別サーバなら「探す → 見つかったサーバへ移動 → 到着後に座標へ飛ばす」。

**座標はネットワークに流していない。** 行き先の座標は必ず行き先のサーバ自身が控え、
相手には「誰が行く／誰を寄こす」だけを伝える。

到着後の実テレポートは `teleport.arrival-delay-ticks`（既定 20 = 1 秒）だけ待つ。
HuskSync がインベントリを復元し終える前に動かすと座標が巻き戻ることがあるため。

### 16-5. Discord（DiscordSRV）

**まだ動かない。** 3 台とも `BotToken: "BOTTOKEN"` /
`Channels: {"global": "000000000000000000"}` のままで、DiscordSRV は起動時に自分を無効化している。

設計は「**各サーバの DiscordSRV が自分の発言だけを投げる**」。
TF が他サーバから受け取った分は `Bukkit.broadcast` で出していて
チャットイベントではないため、DiscordSRV には拾われない ＝ 二重投稿にならない。

サーバ名の接頭辞は `plugins\DiscordSRV\messages.yml`（ジャンクション共有ではないので各サーバ別）:

```yaml
MinecraftChatToDiscordMessageFormat: "**%primarygroup%** [メイン] %displayname% » %message%"
MinecraftChatToDiscordMessageFormatNoPrimaryGroup: "[メイン] %displayname% » %message%"
```

main = メイン / dev = 開発 は設定済み。残りは:

1. `config.yml` の `BotToken` と `Channels` を 3 台（resource を含めるなら 3 台）に入れる
2. **resource には DiscordSRV が入っていない。** 資源サーバの発言も Discord へ出すなら
   jar と config を配って `seed-backend-configs.ps1` の `$ResourceExcluded` から外す
3. **参加/退出メッセージを main 以外で切る。** サーバ間移動は各バックエンドから見ると
   「退出」と「参加」なので、3 台とも有効だと移動のたびに Discord が 2 行流れる

### 16-6. 反映

`network.yml` は初回起動時に jar から書き出される。jar の差し替えが要るので
**`/trinityforge reload` では入らない。フル再起動が必要。**

いずれも**管理者 PowerShell**で 1 回だけ。

```powershell
# Garnet 単体（バックエンドより先に上がっていれば start-all.cmd 側では何もしない）
schtasks /create /tn "Garnet" /tr "D:\game\minecraft\Garnet\garnet.cmd" /sc onstart /ru SYSTEM /rl HIGHEST /f

# ネットワーク一式（起動順と preflight を内包しているので /delay は不要）
schtasks /create /tn "TF Network" /sc onstart /ru SYSTEM /rl HIGHEST /f ^
  /tr "D:\game\minecraft\PaperServer\Velocity_for_TF\launch\start-all.cmd"
```

> **`server-loop.cmd` と併用すること。** `stop` 後に自動で起動し直す口が無いと、
> 定期再起動（手順 11）が「落ちたまま」になる。`launch\start-*.cmd` は
> `server-loop.cmd` 経由で起動するので、そのまま使えばこの条件を満たす。

---

## 資源サーバのプレイヤー周知文（案）

> **資源ワールドについて**
> - `/server resource` で移動できます。`/server main` で戻れます
> - **毎週月曜の早朝、資源ワールドは完全にリセットされます**（地形も建築も全て消えます）
> - **持ち帰りたい物はエンダーチェストへ入れてください。** インベントリとエンダーチェストは
>   両サーバで共有されているので、そのままメインへ持ち帰れます
> - 資源サーバでも `/sethome` が使えますが、**home もリセットで消えます**
> - スキル・レベル・パーク・図鑑は両サーバ共通です。資源サーバで採掘した経験値もそのまま入ります

---

## トラブルシューティング

| 症状 | 原因 | 対処 |
|---|---|---|
| 起動時に `Error occurred while enabling HuskSync` → `FailedToLoadException` → `ConnectException: Connection refused: getsockopt` | **MariaDB / Garnet が動いていない**（手順2 が未実施、またはサービスが止まっている）。jar の不具合ではない — この時点でプラグイン自体は読み込まれ、config も生成されている | 手順2 を実施する。`preflight.ps1` が 3306 / 6379 の到達性を先に判定する |
| 上と同時に `Error occurred while disabling HuskSync` → `getRedisManager()` が null | 初期化が途中で失敗したときの **HuskSync 側の shutdown 経路のバグ**。無害な副作用で、原因は 1 つ上の行 | 無視してよい。DB 接続を直せば出なくなる |
| `HuskSync` が「無効」なのに気付かないまま運用してしまう | enable 失敗はサーバ起動自体を止めない | 起動前に必ず `preflight.ps1`。起動後は `/plugins` で HuskSync が緑か確認 |
| 全員 `Unable to verify player details` で入れない | forwarding secret の不一致 | `velocity/forwarding.secret` と両バックエンドの `paper-global.yml` の `secret` を 1 文字ずつ照合（`preflight.ps1` が照合する） |
| サーバ移動でインベントリが消える / 増える | HuskSync の設定不一致、または `mode` が LOCKSTEP でない | 両サーバの `config.yml` を照合。`/reload` を使っていないか確認 |
| 資源サーバでスキル Lv が 0 | ジャンクションが張れていない | `dir /al` で確認。`sync-configs.ps1` も検出する |
| 資源サーバで岩盤に埋まる / 虚空に落ちる | HuskSync の `location: true` | `location: false` にする |
| 資源サーバでクリエイティブになる | HuskSync の `game_mode: true` | `game_mode: false` にする |
| **サーバ移動でゲームモードは戻るのに飛行状態だけ引き継ぐ**（サバイバルなのに飛べる） | HuskSync の `flight_status: true`。`flight_status` → `game_mode` の依存は **optional** なので、`game_mode` を false にしても飛行状態は同期され続ける（`Identifier.java`） | `flight_status: false` にする（`game_mode` と必ず同値）。`preflight.ps1` が検出する。既に飛んでいるプレイヤーは `/gamemode survival` を撃ち直せば解除される |
| 起動時に `Ambiguous plugin name` | 同名 jar の二重配置 | `sync-configs.ps1` が検出する。古い方を退避 |
| 資源サーバのモブが弱い | `spigot.yml` の `maxHealth.max` 未設定 | 手順 7 の記載どおりコピー |
| リセット後に Ars アイテムが機能しない | ArsPaper config のコピー漏れ | `sync-configs.ps1` の SHA-256 照合で検出される |
| パーク解放が「できません」と出る（ポイントはある） | 共有 SQLite のロック競合 | `SEVERE` ログを確認。[reports/shared-sqlite-concurrency.md](reports/shared-sqlite-concurrency.md) の §5 |

---

## 将来の拡張パス（今回は実装しない）

サーバを**別マシン**へ分けるなら、ディレクトリジャンクションは使えない
（ネットワーク共有上の SQLite は破損する）。

そのときは
[`ProgressionRepository`](../TrinityForge/src/main/java/com/trinityforge/progression/repository/ProgressionRepository.java)
に MariaDB 実装を足すことになる。既存の `SqliteProgressionRepository` から方言のシームを抜き出す形。

**注意点**: `unlockPerk` / `prestige` の check-then-act は、InnoDB では `SELECT … FOR UPDATE` が必須。
SQLite 側では `BEGIN IMMEDIATE` で同じ問題を解決してある（[reports/shared-sqlite-concurrency.md](reports/shared-sqlite-concurrency.md) の §5）が、
InnoDB は既定の分離レベルが違うので同じ対策は効かない。
