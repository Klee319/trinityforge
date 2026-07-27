# Task

ValhallaMMOが生成したmaterial+CustomModelData一致アイテムへ、TrinityForgeの`items/catalog.yml`で設定した表示名を反映する。

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/stats/CatalogIdentity.java`
- `TrinityForge/src/test/java/com/trinityforge/stats/CatalogIdentityTest.java`
- `reports/20260721_2140_FixValhallaCatalogDisplayName.md`

# Details

- ValhallaMMO由来アイテムは`ItemFactory.buildIdentity()`を通らないため、カタログ照合時に表示名も適用するよう修正した。
- 初回のカタログidentity付与時は設定表示名を適用する。
- 既に刻印済みの旧アイテムは、現在名がcatalog IDまたはValhalla内部キーの場合だけ設定表示名へ修復する。
- 通常名や金床で変更した任意名は上書きせず、プレイヤーのリネームを維持する。
- MiniMessageをAdventure Componentへ変換し、Minecraft既定の斜体を無効化する既存`ItemFactory`の表示規則と揃えた。
- 回帰テストを先に追加し、修正前に3件中2件が失敗することを確認した。修正後は対象テストおよび全テストスイートが成功した。
- `TrinityForge/README.md`を確認し、本修正と矛盾する仕様記述や更新を要するセットアップ・利用手順はなかったため変更していない。
