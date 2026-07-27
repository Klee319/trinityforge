# 資源サーバ分離 作業書（RUNBOOK）

Velocity プロキシ + メインサーバ + 資源サーバ の 2 バックエンド構成へ移行する手順。
**この作業書はユーザーが自分で実行する前提で書いてある。** 各手順に検証コマンドと期待される出力を付けた。

- 対象: `D:\game\minecraft\PaperServer\TrinityForge`（Paper 1.21.11）
- 追加: `D:\game\minecraft\PaperServer\TrinityForge-Res`（新規）
- 前提の裏取り: [COST_AND_LICENSE.md](COST_AND_LICENSE.md) / [SECURITY.md](SECURITY.md) / [PERFORMANCE.md](PERFORMANCE.md) / [PLUGIN_MATRIX.md](PLUGIN_MATRIX.md)

---

## 0. 着手前に読む

### 0-1. この構成の要点

```
              [外部] TCP 25565 のみ / UDP 19132 のみ
                        |
              +---------v---------------------+
              |  Velocity 3.5.x  (1G)         |  Geyser + floodgate + Via*
              |  modern forwarding            |  Sonar / FallbackRouter / OneTimePack
              |  /server main | /server resource |  LuckPerms-Velocity
              +----+---------------------+----+
        127.0.0.1:25566            127.0.0.1:25567
   +---------v---------+      +----------v-----------+
   | main (既存) 8G     |      | resource (新規) 6G    |
   | 全ワールド 5000x5000|      | 週次で world 全削除    |
   +---------+---------+      +----------+-----------+
             |  plugins/TrinityForge <-- ディレクトリジャンクション（実体共有）
             +----------+---------------+
                +-------v--------+
                | MariaDB :3306   | LuckPerms / HuskSync
                | Redis   :6379   | HuskSync           <- 127.0.0.1 のみ / WSL2
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
| ジャンクション先を誤って消さないか | **削除ガードが必ず中断する。** 実際にジャンクションを作って実測 | [scripts/run-selftest.ps1](scripts/run-selftest.ps1) |

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
New-NetFirewallRule -DisplayName "Block RCON 25575-25576" -Direction Inbound -LocalPort 25575-25576 -Protocol TCP -Action Block
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

## 手順 2. WSL2 に MariaDB と Redis を入れる

Docker Desktop は使わない（理由は [COST_AND_LICENSE.md](COST_AND_LICENSE.md)）。

```bash
# WSL2 (Ubuntu) 上で
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

**127.0.0.1 のみで待ち受けさせる**（[SECURITY.md](SECURITY.md)）:

- `/etc/mysql/mariadb.conf.d/50-server.cnf` → `bind-address = 127.0.0.1`
- `/etc/redis/redis.conf` → `bind 127.0.0.1`

```bash
sudo systemctl enable --now mariadb redis-server
```

`%USERPROFILE%\.wslconfig` でメモリ上限を切る:

```ini
[wsl2]
memory=4GB
processors=4
```

**検証**（Windows 側から）:

```powershell
Test-NetConnection 127.0.0.1 -Port 3306   # TcpTestSucceeded : True
Test-NetConnection 127.0.0.1 -Port 6379   # TcpTestSucceeded : True
```

> WSL2 は `localhost` のポートを Windows 側へ転送する。外部からは到達しない。

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

1. `D:\game\minecraft\Velocity\` を作り、`velocity-3.5.x.jar` を置く
2. 一度起動して `velocity.toml` と `forwarding.secret` を生成させ、停止する
3. [templates/velocity.toml](templates/velocity.toml) の内容を反映する
   （`config-version` は生成された値を使う）
4. `forwarding.secret` の中身を控える（次の手順で使う）

起動スクリプト `D:\game\minecraft\Velocity\start.bat`:

```bat
@echo off
cd /d "%~dp0"
java -Xms1G -Xmx1G -XX:+UseG1GC -jar velocity-3.5.x.jar
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

### 6-3. ワールドボーダー

```
/execute in minecraft:overworld  run worldborder center 0 0
/execute in minecraft:overworld  run worldborder set 5000
/execute in minecraft:the_nether run worldborder center 0 0
/execute in minecraft:the_nether run worldborder set 5000
/execute in minecraft:the_end    run worldborder center 0 0
/execute in minecraft:the_end    run worldborder set 5000
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
$res  = "D:\game\minecraft\PaperServer\TrinityForge-Res"
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
rcon.port=25576
level-seed=
spawn-protection=16
```

### 7-2. paper-global.yml

メインと同じく `proxies.velocity` を有効化し、同じ secret を入れる。

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
D:\game\minecraft\PaperServer\TrinityForge-Res\  で paper を起動 → 起動しきったら stop
```

**この時点では TrinityForge の config が資源サーバ側に独自生成される。次の手順で捨てる。**

---

## 手順 8. ジャンクションを張る

**両サーバを停止した状態で行う。**

```
ops\scripts\setup-junction.cmd
```

このスクリプトは:

- メイン側の実体があることを確認する
- 資源側に実ディレクトリがあれば**削除せず退避**する（`TrinityForge.pre-junction-<日時>`）
- 既に同じリンク先のジャンクションなら何もしない（冪等）
- `mklink /J` でリンクを張る

**検証**:

```cmd
dir /al "D:\game\minecraft\PaperServer\TrinityForge-Res\plugins"
```

`<JUNCTION>  TrinityForge [D:\game\minecraft\PaperServer\TrinityForge\plugins\TrinityForge]`
のように表示されること。

---

## 手順 9. HuskSync を導入

### 9-1. ビルド

**現行の Paper 1.21.11 に対応した公式リリースは存在しない。** 詳細は
[COST_AND_LICENSE.md](COST_AND_LICENSE.md) の「HuskSync」節。

**推奨: master（4.0.0-dev）を特定コミットに固定してビルドする。**
`bukkit/1.21.11/` アダプタが公式に存在する。

```bash
git clone https://github.com/WiIIiam278/HuskSync.git
cd HuskSync
git checkout <コミットハッシュを固定>
./gradlew clean build
# bukkit/build/libs/ に jar ができる
```

ビルドしたコミットハッシュを必ず記録に残すこと（未リリースブランチのため）。

**先に試す価値のある代替**: 3.8.7 の配布 jar をそのまま入れてみる。
HuskSync は NMS を使っておらず Paper API のみでビルドされているため、動く可能性はある。
ただし**公式サポート範囲外**なので、**本番データでは試さない**。捨ててよいテスト環境で確認すること。

### 9-2. 設定

両バックエンドの `plugins/HuskSync/` に jar を入れ、一度起動して `config.yml` を生成させる。
生成されたファイルに対して [templates/husksync.config.yml](templates/husksync.config.yml) の
項目を反映する（**丸ごと上書きしない**。バージョンでキー名が変わるため）。

特に重要な 3 つ:

```yaml
synchronization:
  mode: LOCKSTEP
  features:
    persistent_data: true    # TF の図鑑・称号・Ars のマナ。これが層 B
    location: false          # 必ず false。true だと資源側で岩盤に埋まる
    game_mode: false         # 必ず false。メインの world は creative
```

**メインと資源で完全に同じ内容にすること。**

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

RCON パスワードは**環境変数で渡す**（設定ファイルに書かない）:

```powershell
setx TF_RCON_MAIN_PASSWORD     "<main の rcon.password>"
setx TF_RCON_RESOURCE_PASSWORD "<resource の rcon.password>"
```

### 11-2. スクリプトを空撃ちして確認する

```powershell
cd <repo>\ops\scripts
.\run-selftest.ps1                 # 削除ガードの実測。10/10 になること
.\sync-configs.ps1     -DryRun
.\restart-server.ps1   -DryRun -Target both
.\reset-resource.ps1   -DryRun
.\backup.ps1           -DryRun
```

**`reset-resource.ps1 -DryRun` の出力で、削除対象の絶対パスが想定どおりか必ず目視すること。**
`plugins\TrinityForge` が含まれていたら、そのまま実行してはいけない（スクリプトが自動で拒否するが）。

### 11-3. server-loop.cmd で起動するように切り替える

```bat
REM main
ops\scripts\server-loop.cmd "D:\game\minecraft\PaperServer\TrinityForge"     8G paper-1.21.11-132.jar

REM resource
ops\scripts\server-loop.cmd "D:\game\minecraft\PaperServer\TrinityForge-Res" 6G paper-1.21.11-132.jar
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
| 全員 `Unable to verify player details` で入れない | forwarding secret の不一致 | `velocity/forwarding.secret` と両バックエンドの `paper-global.yml` の `secret` を 1 文字ずつ照合 |
| サーバ移動でインベントリが消える / 増える | HuskSync の設定不一致、または `mode` が LOCKSTEP でない | 両サーバの `config.yml` を照合。`/reload` を使っていないか確認 |
| 資源サーバでスキル Lv が 0 | ジャンクションが張れていない | `dir /al` で確認。`sync-configs.ps1` も検出する |
| 資源サーバで岩盤に埋まる / 虚空に落ちる | HuskSync の `location: true` | `location: false` にする |
| 資源サーバでクリエイティブになる | HuskSync の `game_mode: true` | `game_mode: false` にする |
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
