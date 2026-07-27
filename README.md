# TrinityForge

Paper 1.21.11 向けの Minecraft サーバー統合改造プロジェクト。
独自プラグイン `TrinityForge` を中核に、周辺プラグインのフォークと GUI コンフィグエディタで
戦闘・ステータス・採取・スキルツリー・ダンジョンを一体で運用する。

## リポジトリ構成

| パス | 内容 |
|---|---|
| `TrinityForge/` | 本体プラグイン（Java / Gradle）。`src/main/resources/` に出荷 yml |
| `tools/config-editor/` | Node 製の設定エディタ（全 yml をブラウザから編集） |
| `resourcepack/` | 配布用リソースパックのソース |
| `skilltree/` | スキルツリーの設計資料・スライド |
| `docs/` | 設計書と config リファレンス |
| `reports/` | 作業記録。**現役の残タスク・既知バグは `reports/ACTIVE_RECORD.md` のみが一次情報** |
| `fork-handoff/` | 周辺プラグインのフォーク引き継ぎ資料（後述の通りソース本体は対象外） |

## ビルド

`gradlew` ラッパースクリプトは使わず、wrapper の main を直接叩く（Windows 環境の都合）。

```bash
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build --offline
```

テストがロックで固まる場合は `cleanTest test` を使う。
config-editor は `tools/config-editor/` で `npm install && npm test`。

## このリポジトリに含まれないもの

- **`fork-handoff/arspaper/fork/` と `fork-handoff/elitemobs/elitemobs-fork/`**
  それぞれ独立した git リポジトリのため除外している。ArsPaper は
  [Klee319/ArsPaper](https://github.com/Klee319/ArsPaper) で別管理、
  EliteMobs フォークは upstream (MagmaGuy/EliteMobs, GPL-3.0) からの派生。
- `wiki/` — GitHub wiki の別クローン。
- `backups/`, `backups.zip` — git 導入以前の手動バックアップ。
- ビルド成果物・`node_modules/`・ベンダー jar（`source/`）。
- `.cursor-rcon.py` — ローカル絶対パスを含む運用スクリプト。
