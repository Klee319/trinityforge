# Task

TrinityForgeのcatalog由来弓など、ValhallaMMOの`BOW_STRENGTH`実値を持たない弓で矢の初速がゼロになる不具合を修正する。

# Files Changed

- `external/ValhallaMMO/core/src/main/java/me/athlaeos/valhallammo/listeners/ProjectileListener.java`
- `reports/20260721_2212_FixCatalogBowVelocity.md`

# Details

- ValhallaMMOは`RANGED_VELOCITY_BONUS`を矢の速度倍率として直接使用する。
- Valhalla処理済みの弓は`BOW_STRENGTH=1`を実値として持つが、外部プラグインやTrinityForgeのcatalogが生成した弓には実値がなく、集計結果`0`が初速へ乗算されていた。
- 発射弓が`BOW`または`CROSSBOW`で、実値`BOW_STRENGTH`が存在しない場合だけ基準倍率`1.0`を加算するよう修正した。
- 正常なValhalla弓の`BOW_STRENGTH=1`、明示的な`BOW_STRENGTH=0`、速度ボーナスおよびデバフの意味は変更しない。
- Mavenでparent + coreをコンパイルし、`BUILD SUCCESS`を確認した。coreにはテストソースが存在しないため、Mavenテストはスキップされた。
- TrinityForgeおよびValhallaMMOのREADMEを確認し、この内部互換修正と矛盾する仕様記述や更新を要する利用手順はなかったため変更していない。
