# セキュリティ — DDoS / DoS 対策とポート方針

**方針**: 層ごとに「無料でできること」と「無料では届かないこと」を分けて書く。
届かないものを届くように見せない。

---

## 0. DDoS 以前に塞ぐべき穴

### 0-1. config-editor が無認証で全公開設定になっている

[tools/config-editor/tool-config.json](../tools/config-editor/tool-config.json) の現在値:

```json
"external": { "enabled": true, "bindHost": "0.0.0.0", "auth": { "username": "admin", "password": "" } }
```

`external.enabled: true` かつ `bindHost: 0.0.0.0` かつ **パスワードが空**。
このポート (8000) に到達できる者は誰でも、認証なしでサーバ config の編集と配備ができる。

**ただしこれは事故ではなく、2026-07-26 にユーザーが明示的に選択した状態である。**
[server.js](../tools/config-editor/server.js) には元々「パスワードが空なら 127.0.0.1 へ強制する」
fail-safe があり、それを撤去した経緯がコメントに残っている
（`resolveBinding` の「2026-07-26 ユーザー判断により fail-safe を撤去」）。
警告は起動時に出続ける仕様になっている。

したがってここでの対策は「勝手に設定を戻す」ことではなく、**到達経路を塞ぐこと**:

| 対策 | 内容 | 影響 |
|---|---|---|
| **A. ファイアウォールで塞ぐ（必須）** | Windows Firewall で 8000 / 8787 のインバウンドを拒否 | なし。ローカルからは今まで通り使える |
| B. パスワードを付ける（推奨・config 変更不要） | 環境変数 `CONFIG_EDITOR_PASSWORD` を設定して editor を起動し直す | Basic 認証が有効になる |
| C. 外部公開自体をやめる | `external.enabled` を `false` に戻す | 外から触れなくなる。運用が変わるためユーザー判断 |

**A は無条件で実施する。B は config ファイルを一切変更せずに効くので、あわせて推奨する。**

### 0-2. RCON パスワードが平文

`server.properties` の `rcon.password=tf-local-rcon-2026` と `.rcon.env` に同じ値が平文で入っている。
RCON プロトコル自体もパスワードを平文で流す。

- **25575 / 25576 のインバウンドを Windows Firewall で拒否する**（ループバックは影響を受けない）
- 移行を機にパスワードを長いランダム文字列へ変更する
- ops スクリプトは環境変数 `TF_RCON_MAIN_PASSWORD` / `TF_RCON_RESOURCE_PASSWORD` から読む。
  スクリプトにも `ops-config.psd1` にも書かない

---

## 1. 攻撃面を減らす（無料・最も効果が大きい）

Windows Firewall のインバウンドで、**公開するのは 2 ポートだけ**にする。

| ポート | 用途 | 公開 | バインド |
|---|---|:---:|---|
| 25565/TCP | Velocity（Java 版） | **公開** | `0.0.0.0` |
| 19132/UDP | Geyser（Bedrock 版） | **公開** | `0.0.0.0` |
| 25566/TCP | main バックエンド | ✕ | `server-ip=127.0.0.1` |
| 25567/TCP | resource バックエンド | ✕ | `server-ip=127.0.0.1` |
| 25575/TCP | main RCON | ✕ | Firewall で拒否 |
| 25576/TCP | resource RCON | ✕ | Firewall で拒否 |
| 3306/TCP | MariaDB | ✕ | `bind-address=127.0.0.1` |
| 6379/TCP | Redis | ✕ | `bind 127.0.0.1` |
| 8000, 8787/TCP | config-editor | ✕ | Firewall で拒否（0-1 参照） |

### バックエンドを 127.0.0.1 に縛るのは「なりすまし対策」でもある

modern forwarding は「プロキシから来た」という署名を信頼してプレイヤー情報を受け取る仕組みで、
バックエンド側は `online-mode=false` で動く。
**バックエンドが外から直接叩ける状態だと、誰でも任意のプレイヤー名を名乗って入れる。**
`server-ip=127.0.0.1` と Firewall の両方で塞いで初めて安全になる。
forwarding secret の管理と同じくらい重要で、こちらのほうが見落としやすい。

`enable-query=false`（現状すでに false）は維持する。

---

## 2. アプリ層のボット・ログイン洪水対策（無料）

### Sonar（antibot）

GPL-3.0 の OSS。Velocity 対応。接続をキューイングし、バックエンドへ到達する前に
落下・衝突などの物理法則とパケット妥当性で検証してボットを落とす。
設定方針は [templates/sonar.yml](templates/sonar.yml)。

### Velocity 側のレート制限

[templates/velocity.toml](templates/velocity.toml) の `[advanced]` に設定済み。

| 設定 | 値 | 効果 |
|---|---|---|
| `login-ratelimit` | 3000 | 同一 IP からのログイン試行の最小間隔（ms） |
| `connection-timeout` | 5000 | ハンドシェイクを張ったまま放置する枯渇攻撃を切る |
| `read-timeout` | 30000 | 同上 |
| `ping-passthrough` | `DISABLED` | バックエンド構成を外から推測させない |

`prevent-client-proxy-connections` は **既定 false のまま**にしてある。
VPN/共有回線を弾く設定だが、正規プレイヤーの ISP が誤検知されると本人にはどうにもできない
締め出しになる。25 人規模では次のホワイトリストのほうが確実。

### ホワイトリスト運用（要ユーザー判断・無料で最も強力）

現状 `white-list=false`。**25 人規模なら、これが無料でできる対策として最も効く。**
Velocity 側で運用すれば、バックエンドに一切到達させずに弾ける
（Sonar の検証コストすら攻撃者に払わせずに済む）。

導入するかは運用方針の問題なのでユーザー判断。導入する場合は
バックエンド側ではなく **Velocity 側**で管理すること。

---

## 3. 帯域飽和攻撃（L3/L4）— 正直な限界

**家庭用回線でホストしている以上、回線を埋める攻撃はソフトウェアでは何もできない。**
上流で吸収するしかない。

| | Java 版 (TCP 25565) | Bedrock 版 (UDP 19132) |
|---|---|---|
| 無料で上流吸収 | **TCPShield Free プランで可能** | **無料の手段はない**（Premium 限定） |

したがって:

- Java 側は TCPShield Free を噛ませれば上流で吸収できる（無料）
- **Bedrock を無料で守る手段は現状ない。**
  攻撃を受けた場合の運用手順は「**Bedrock ポート (19132/UDP) を一時的に閉じ、Java だけで凌ぐ**」。
  Firewall のルールを無効化するだけで済むよう、あらかじめ専用のルールとして作っておく
- サーバの IP を直接晒さない。接続先は必ずドメイン経由にする
  （IP 直指定を配ってしまうと、TCPShield を入れても迂回される）

---

## 4. 2サーバ化で新しく増えるリスク

| リスク | 影響 | 対策 |
|---|---|---|
| **ジャンクション先の誤削除** | 全プレイヤーの進行データと全 config が消える | [Common.ps1](scripts/lib/Common.ps1) の `Remove-DirectorySafely` が必ず中断する。[run-selftest.ps1](scripts/run-selftest.ps1) で実測済み |
| **forwarding secret の漏洩** | 任意のプレイヤー名でバックエンドへ入れる | `forwarding.secret` と `paper-global.yml` の `secret` を git に入れない |
| **クラッシュ時のインベントリ複製** | 経済破壊 | HuskSync の `mode: LOCKSTEP` とスナップショット 16 世代。`/reload` は全面禁止。restart 系プラグインを使わない |
| **片方のサーバだけ config が古い** | Ars アイテムが片方で機能しない | [sync-configs.ps1](scripts/sync-configs.ps1) が SHA-256 照合し、不一致なら起動をブロック |

### `/reload` を使わない

プラグインの再読み込みはクラスローダを二重化し、HuskSync のような
「切断時に保存・接続時に読込」を行うプラグインで整合が壊れる。
config の反映は `/tf reload`（TF 自身の再読込）か、サーバの再起動で行う。

---

## 5. 実施チェックリスト

配備時に上から順に確認する。

- [ ] Windows Firewall のインバウンドで公開しているのが 25565/TCP と 19132/UDP だけである
- [ ] 25575 / 25576 / 3306 / 6379 / 8000 / 8787 が外部から到達できない
- [ ] 両バックエンドの `server-ip=127.0.0.1`
- [ ] 両バックエンドの `online-mode=false`（プロキシ側が `true`）
- [ ] MariaDB の `bind-address=127.0.0.1`、Redis の `bind 127.0.0.1`
- [ ] RCON パスワードをランダム文字列へ変更し、環境変数にも反映した
- [ ] `CONFIG_EDITOR_PASSWORD` を設定した（推奨）
- [ ] `forwarding.secret` が git に入っていない
- [ ] Sonar が Velocity で動いている
- [ ] Bedrock ポートを緊急停止するための Firewall ルールを作ってある
- [ ] `enable-query=false` のまま
