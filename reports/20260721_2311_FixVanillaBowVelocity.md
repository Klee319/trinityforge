# Task

ValhallaMMO環境で通常の弓から発射した矢の速度が0になり、射手の足元へ落ちる問題を修正・配置する。

# Files Changed

- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/listeners/ProjectileListener.java`
- サーバ配置物: `plugins/ValhallaMMO_1.9.3.jar`

# Details

- Valhalla属性を持たない通常弓・TFカタログ弓では `RANGED_VELOCITY_BONUS` が0から始まる。
- 実際の `BOW_STRENGTH` 属性が存在しない弓・クロスボウに限り、バニラ基準倍率 `1.0` を加える既存修正をコンパイルした。
- 明示的な `BOW_STRENGTH: 0` は意図的なゼロ速度として維持する。
- TrinityForge側の発射イベントは矢速度を変更しておらず、変更対象外。
- READMEに矢速度計算の仕様記載はなく、更新を必要とする矛盾はなかった。

# Verification

- ValhallaMMO core（808ソース）のコンパイル成功。
- 全世代一括distは、配布終了済みMinecraft 1.19 Spigot snapshot依存を取得できず停止した。
- 1.21.11で使用中の既存1.9.3 jarへ、コンパイル済み `ProjectileListener.class` のみを差し替えた。
- 配置jar内に `BOW_STRENGTH` フォールバック参照が含まれること、および配置前後のSHA-256一致を確認した。
- 元jarを `ValhallaMMO_1.9.3.jar.bak-20260721-2310` として保存した。
- サーバ再起動は行っていない。
