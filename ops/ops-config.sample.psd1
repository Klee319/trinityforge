#
# ops スクリプト共通の設定サンプル。
#
# 使い方:
#   1. このファイルを同じディレクトリへ ops-config.psd1 という名前でコピーする
#   2. パスとポートを実環境に合わせる
#   3. RCON パスワードは【ここに書かない】。環境変数で渡す:
#         setx TF_RCON_MAIN_PASSWORD     "<main の rcon.password>"
#         setx TF_RCON_RESOURCE_PASSWORD "<resource の rcon.password>"
#
# ops-config.psd1 は .gitignore 対象。サンプルであるこのファイルだけを版管理する。
#
@{
    Servers = @{

        Main = @{
            Name       = "main"
            # サーバのルート (paper jar と server.properties がある場所)
            Root       = "D:\game\minecraft\PaperServer\TrinityForge"
            RconHost   = "127.0.0.1"
            RconPort   = 25575
            # RconPassword は Get-OpsConfig が環境変数から埋める
        }

        Resource = @{
            Name       = "resource"
            Root       = "D:\game\minecraft\PaperServer\TrinityForge-Res"
            RconHost   = "127.0.0.1"
            RconPort   = 25576
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
