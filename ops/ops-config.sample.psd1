#
# ops スクリプト共通の設定サンプル。
#
# 使い方:
#   1. このファイルを同じディレクトリへ ops-config.psd1 という名前でコピーする
#   2. パスとポートを実環境に合わせる
#   3. RCON パスワードは【ここに書かない】。環境変数で渡す。
#      変数名は Servers のキー名から決まる (Main -> TF_RCON_MAIN_PASSWORD):
#         setx TF_RCON_MAIN_PASSWORD     "<Main_Server の rcon.password>"
#         setx TF_RCON_RESOURCE_PASSWORD "<Resource_Server の rcon.password>"
#         setx TF_RCON_DEV_PASSWORD      "<Dev_Server の rcon.password>"
#
# ops-config.psd1 は .gitignore 対象。サンプルであるこのファイルだけを版管理する。
#
# 下の値は 2026-07-27 時点の実環境
# (D:\game\minecraft\PaperServer\Velocity_for_TF\) に合わせてある。
#
@{
    # Velocity のルート (velocity.toml と forwarding.secret がある場所)。
    # preflight.ps1 が forwarding secret の一致確認に使う。
    VelocityRoot = "D:\game\minecraft\PaperServer\Velocity_for_TF"

    # Main と Resource は必須。それ以外のキーは足すだけで各スクリプトが扱える。
    Servers = @{

        Main = @{
            # velocity.toml の [servers] に書いた名前と揃えること
            Name       = "main"
            # サーバのルート (paper jar と server.properties がある場所)
            Root       = "D:\game\minecraft\PaperServer\Velocity_for_TF\Main_Server"
            RconHost   = "127.0.0.1"
            RconPort   = 25586
            # RconPassword は Get-OpsConfig が環境変数から埋める
        }

        Resource = @{
            Name       = "resource"
            Root       = "D:\game\minecraft\PaperServer\Velocity_for_TF\Resource_Server"
            RconHost   = "127.0.0.1"
            RconPort   = 25587
        }

        # 検証用。restart-server.ps1 -Target dev で個別に再起動できる。
        # 週次リセットの対象ではない (ResourceResetTargets は Resource にしか適用しない)。
        Dev = @{
            Name       = "dev"
            Root       = "D:\game\minecraft\PaperServer\Velocity_for_TF\Dev_Server"
            RconHost   = "127.0.0.1"
            RconPort   = 25588
        }
    }

    # 週次リセットで消すもの。resource サーバのルートからの相対パス。
    # ここに plugins\TrinityForge を絶対に足さないこと (メインと共有しているジャンクション)。
    ResourceResetTargets = @{
        Directories = @(
            "world"
            "world_nether"
            "world_the_end"
            # 消えたワールドを指し続ける Chunky のタスク状態
            "plugins\Chunky\tasks"
        )
        Files = @(
            # 消えたブロックを指し続ける Ars のソース系キャッシュ。
            # ⚠ 2026-08-08 訂正: ここは長らく sourcejars.yml / sourcelinks.yml を指していたが、
            #   その2つは読み取り専用の【定義ファイル】(容量・階梯・燃料点数)で、
            #   ブロック座標を書いているのは source-network.yml (SourceNetwork#saveSnapshot) の方。
            #   消す対象を取り違えていたので、リセットのたびに階梯の定義が飛んでいた。
            "plugins\ArsPaper\source-network.yml"
            "plugins\ArsPaper\ranking_cache.json"
            # 消えた地形の座標を残さない (資源サーバの home は毎週消える仕様)
            "plugins\SetHome\homes.yml"
        )
    }

    # reset-world.ps1 (正式開幕・仕切り直し) が world と一緒に消すもの。
    # サーバルートからの相対パス。週次の ResourceResetTargets とは別物で、
    # 【どのバックエンドにも適用する】。world 本体はスクリプト側が持っているのでここには書かない。
    #
    # ここに挙げるのは「消えた座標を指し続ける」データだけ。残しても例外は出ないが、
    # プリジェネが空振りしたり拠点へ飛べなかったりする形で静かに壊れる。
    WorldResetTargets = @{
        Directories = @(
            # 消えたワールドを指し続ける Chunky のプリジェネ タスク状態
            "plugins\Chunky\tasks"
            # 消えた地形の BlueMap タイル (core.conf の data + storages\file.conf の root)
            "bluemap\web\maps"
        )
        Files = @(
            # ソース網のブロック座標 (SourceNetwork#saveSnapshot)
            "plugins\ArsPaper\source-network.yml"
            # 消えた地形の拠点
            "plugins\SetHome\homes.yml"
        )
    }

    # 資源サーバに入れる構造物/地形データパック(案1)。
    #
    # ⚠⚠ **データパックを `world\datapacks` にだけ置くと、週次リセットの初回で全部消える。**
    #    上の ResourceResetTargets が `world` を丸ごと削除するため。しかも消えても
    #    エラーは出ず、【資源ワールドが黙ってバニラ地形に戻る】だけなので気づけない
    #    (追加した戦利品プールも namespace ごと当たらなくなる)。
    #    なので **正本は world の外 (Source) に置き、リセットのたびに Destination へ複製する。**
    #    ⚠ データパックを差し替えるときは Source を直すこと。Destination を直しても次のリセットで戻る。
    ResourceDatapacks = @{
        # resource サーバのルートからの相対パス。ここが正本。週次リセットでは消さない。
        Source      = "datapacks-source"
        # Paper がデータパックを読む場所 (level-name が world 以外なら合わせて直す)。
        Destination = "world\datapacks"
        # true にすると、Source が空/不在のときにリセットを中断する。
        # 【ワールドを消したあとでデータパック不在に気づく】のが最悪なので、停止前に検査する。
        Required    = $true
    }

    # sync-configs.ps1 が main -> resource へコピーする ArsPaper config。
    # サーバ固有の【実行時状態】だけを対象外にする。
    # ⚠ 2026-08-08 訂正: 以前は sourcejars.yml / sourcelinks.yml を除外していたが、
    #   その2つは全サーバで同じであるべき定義ファイル。除外すると資源サーバだけ階梯が古くなる。
    #   本当にサーバ固有なのは source-network.yml (リレー網の座標) と ranking_cache.json。
    ArsPaperSync = @{
        ExcludeFiles = @(
            "ranking_cache.json"
            "source-network.yml"
        )
        # バックアップの残骸をコピーして肥大化させない
        ExcludePatterns = @(
            "*.bak"
            "*.bak-*"
            "*.predeploy-*"
            "*.removed-*"
        )
    }

    Backup = @{
        # バックアップの出力先。世代管理はここで行う。
        Root      = "D:\game\minecraft\PaperServer\backup\ops"
        KeepDays  = 14
        # dump する DB 名
        Databases = @("luckperms", "husksync")

        # --- MariaDB が Windows ネイティブの場合（手順2 の既定） ---
        # MariaDB 同梱の dump コマンド。版番号はインストールしたものに合わせる
        # (2026-07-27 の実環境は 12.3.2)。
        MysqldumpPath     = "C:\Program Files\MariaDB 12.3\bin\mariadb-dump.exe"
        # 認証情報を書いたファイル。【パスワードをコマンドラインに置かない】ため。
        # 中身の例:
        #   [mariadb-dump]
        #   user=root
        #   password=<root のパスワード>
        # リポジトリの外に置き、NTFS の権限で自分だけ読めるようにすること。
        MysqlDefaultsFile = "D:\game\minecraft\PaperServer\backup\my-dump.cnf"

        # --- WSL2 構成の場合（手順2 の付録） ---
        # MysqldumpPath を消すと、こちらが使われる。認証は WSL 側の ~/.my.cnf。
        WslDistro = "Ubuntu"
    }
}
