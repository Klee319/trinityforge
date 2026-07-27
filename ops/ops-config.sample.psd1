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
            # 消えたブロックを指し続ける Ars のソース系キャッシュ
            "plugins\ArsPaper\sourcejars.yml"
            "plugins\ArsPaper\sourcelinks.yml"
            "plugins\ArsPaper\ranking_cache.json"
            # 消えた地形の座標を残さない (資源サーバの home は毎週消える仕様)
            "plugins\SetHome\homes.yml"
        )
    }

    # sync-configs.ps1 が main -> resource へコピーする ArsPaper config。
    # ranking_cache.json / sourcejars.yml / sourcelinks.yml はサーバ固有の状態なので【対象外】。
    ArsPaperSync = @{
        ExcludeFiles = @(
            "ranking_cache.json"
            "sourcejars.yml"
            "sourcelinks.yml"
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
        # WSL2 上の MariaDB から dump する DB 名
        Databases = @("luckperms", "husksync")
        # mysqldump を実行するコマンド。WSL 経由で叩く。
        WslDistro = "Ubuntu"
    }
}
