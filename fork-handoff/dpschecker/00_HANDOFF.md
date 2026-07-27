# DPSChecker TF Fork — Handoff

## 何を作ったか

`fork/` に TrinityForge 統合版 DPSChecker を配置。

- 本体 `DPSchecker` のバグ修正済みソースをベース
- `integration/TrinityForgeBridge.java` で防御プロファイルを TF PDC に同期
- ビルド成果物名: `DPSChecker-TF`

## ビルド手順

1. `TrinityForge` を `./gradlew jar` でビルド
2. `TrinityForge/build/libs/TrinityForge-*.jar` を `fork/libs/TrinityForge.jar` にコピー
3. `fork/` で `./gradlew build`

## デプロイ

Paper 1.21.11 サーバーに **TrinityForge を先に**、`DPSChecker-TF-*.jar` を後から配置。
