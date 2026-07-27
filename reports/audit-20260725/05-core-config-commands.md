# 05 — プラグイン基盤・config・PDC・コマンド・リスナー全般・アクティブスキル基盤 監査レポート

- 担当領域: `TrinityForge.java` / `config/`(54ファイル) / `pdc/` / `command/`(12ファイル) / `listeners/`(63ファイル) / `active/`(7ファイル) / `paper-plugin.yml`
- 監査方法: 上記ファイルを全て実読。`TrinityForge.java` の `onEnable()`/`registerCommands()` を基準に「実際に登録されているか」をクロスチェックし、フラグした所見のうち登録有無に関わるものは `grep` で二重確認済み。
- 総評: この領域は極めて規律的に書かれている。ほぼ全リスナーが `ignoreCancelled`/`EventPriority` を明示し、優先度の選定理由・同一イベントを扱う他リスナーとの順序関係をJavadocで明記する慣行が徹底されている。PDC読み書き・config読み込みも fail-soft (例外を握り潰さず既定値へ後退、ログ1回のみ) が一貫しており、致命的なバグは見つからなかった。**HIGH所見は0件**。見つかった所見はいずれも LOW〜MEDIUM (デッドコード・stale コメント・軽微なメモリリーク・表示上のカテゴリドリフト・設計書との差分)。

---

## 所見一覧

### CORE-01
- **重要度**: MEDIUM
- **分類**: DEADCODE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/config/domains/MobDefaultsConfig.java`(全体) / `TrinityForge/src/main/java/com/trinityforge/config/ConfigManager.java`
- **事象**: `MobDefaultsConfig` は9フィールド分のスキーマ・検証・`defaultDefense(DamageType)` を備えたフル実装の `ConfigDomain` だが、`ConfigManager` に登録されておらず、`new MobDefaultsConfig` は全ソース中ゼロ件。参照されるのは静的定数 `PATH = "combat/mob-defaults.yml"` のみで、`MobTypesConfig` が `mob-types.yml` の `defaults:` 欠落時に生の `YamlConfiguration` で直読するフォールバック経路として使われている(= `MobDefaultsConfig` 自体は完全に迂回される)。
- **影響/再現**: `mob-defaults.yml` を編集しても、`ConfigDomain` としての検証・クランプ・reload連動は一切効かない。フォールバック直読み経路は独自にパースするため、`MobDefaultsConfig` のバリデーションが期待通り働くという誤解を招く。実害はないが保守上の罠。
- **根拠**:
```java
// ConfigManager.java (コメントで意図的な廃止を明記)
// mob-defaults.yml は MobTypesConfig が defaults 欠落時のフォールバックとして直読する。
// ConfigDomain 登録は廃止（権威は combat/mob-types.yml の defaults:）。
```

### CORE-02
- **重要度**: LOW
- **分類**: UNIMPLEMENTED / DEADCODE
- **場所**: `TrinityForge/src/main/resources/progression/crafting-features.yml:146` / `TrinityForge/src/main/java/com/trinityforge/config/domains/CraftingFeaturesConfig.java`
- **事象**: 出荷yml末尾に `gated-catalog-recipes: {}` が残存しているが、`CraftingFeaturesConfig.load()` はこのキーを一切読まない。このキーへの唯一のコード参照は `CatalogCraftGateListener.java` のコメント「旧 `crafting-features.yml gated-catalog-recipes` allowlist を置換した」であり、2026-07-23の動的ID方式移行で完全に無効化された残骸。
- **影響/再現**: 管理者がこのキーを編集しても何も起きない(サイレント無視)。config editorでこの欄が表示され続ける場合、混乱を招く。
- **根拠**:
```yaml
# progression/crafting-features.yml:146
gated-catalog-recipes: {}
```
```java
// CatalogCraftGateListener.java 冒頭コメント
// Replaces the old crafting-features.yml gated-catalog-recipes allowlist.
```

### CORE-03
- **重要度**: LOW
- **分類**: COMMENT
- **場所**: `TrinityForge/src/main/java/com/trinityforge/pdc/PlayerData.java:17-18`
- **事象**: クラスJavadocが「ValhallaMMO remains the source of truth for skill levels; this only mirrors addon-owned retained perks and prestige bookkeeping.」と記述しているが、2026-07-22にValhallaMMO依存は完全に撤廃され、`NativeSkillLevelSource`/SQLite `ProgressionRepository` がスキルレベルの権威になっている(memory `session-2026-07-22-native-migration` 参照)。
- **影響/再現**: 実害なし。後任開発者がこのコメントを信じてValhalla連携ロジックを探しに行く/触ってしまうリスク。
- **根拠**:
```java
// PlayerData.java:17-18
 * ValhallaMMO remains the source of truth for skill levels; this only mirrors addon-owned
 * retained perks and prestige bookkeeping.
```

### CORE-04
- **重要度**: LOW
- **分類**: COMMENT
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/PerkMirrorListener.java:13-15`
- **事象**: 「ValhallaMMO's own join handling has already restored the player's PowerProfile」という記述が、ValhallaMMO撤廃後も残存。`sync()` の実処理自体はネイティブ進行系を参照しており機能は正しいが、根拠コメントがValhalla時代のまま。
- **影響/再現**: 実害なし。CORE-03と同種のstaleコメント。
- **根拠**:
```java
// PerkMirrorListener.java:13-15
 * up to one {@link PerkMirrorService#DEFAULT_SYNC_INTERVAL_TICKS} interval for the periodic backfill.
 * Runs at MONITOR so ValhallaMMO's own join handling has already restored the player's PowerProfile;
```

### CORE-05
- **重要度**: LOW
- **分類**: COMMENT
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:886-888`
- **事象**: TF最重要ホットパスであるCombatListener自身のJavadocに「Grants Valhalla EXP to the weapon's use-skill after a landed hit」と記述されているが、実装は `ArsProgressionBridge.grantSkillExp` 経由でネイティブ進行系にEXPを付与しており、ValhallaMMOは一切関与しない。
- **影響/再現**: 実害なし。最も参照頻度の高いファイルの一つにstale記述が残る点は要修正の優先度がやや高い。
- **根拠**:
```java
// CombatListener.java:886-888
/**
 * Grants Valhalla EXP to the weapon's use-skill after a landed hit (stats/skill-exp.yml combat.*).
 * Exploit fix: hitting the SAME {@code targetId} again within {@link SkillExpConfig
```

### CORE-06
- **重要度**: LOW
- **分類**: COMMENT
- **場所**: `TrinityForge/src/main/java/com/trinityforge/progression/SkillLevelSource.java:6-9` / `18`
- **事象**: インターフェース自体のJavadocが「ValhallaMMO remains the source of truth for skill levels」「A source with no data, useful as a default before the Valhalla bridge is wired」と記述。実装は `NativeSkillLevelSource` がSQLiteベースで完全代替済み。
- **影響/再現**: 実害なし。CORE-03/04/05と同一パターンの群(計4箇所+CORE-07)。
- **根拠**:
```java
// SkillLevelSource.java:6-9, 18
 * Supplies a player's skill-tree levels keyed by skill id (ADDON_INTEGRATION_SPEC 5:
 * "ValhallaMMO remains the source of truth for skill levels"). ...
 * A source with no data, useful as a default before the Valhalla bridge is wired.
```

### CORE-07
- **重要度**: LOW
- **分類**: BUG(誤解を招く表示文言)
- **場所**: `TrinityForge/src/main/java/com/trinityforge/command/StatsCommand.java:94`
- **事象**: `/tf stats` でスキルLvが1件も無いプレイヤーに対し `"(なし / ValhallaMMO未接続)"` という文言を表示する。`NativeSkillLevelSource.levelsOf()` は「スキルを一度も進行させていない新規プレイヤー」または「DBロード失敗時」に空Mapを返す実装であり、ValhallaMMOとは無関係(ValhallaMMOは既に撤廃済み)。
- **影響/再現**: 新規プレイヤーが `/tf stats` を実行すると「ValhallaMMOに接続されていない」という誤った診断メッセージを見る。実際には正常動作(単に未進行なだけ)でもDB障害でも同じ誤解を招く文言が出る。
- **根拠**:
```java
// StatsCommand.java:92-96
Map<String, Integer> skills = new TreeMap<>(skillLevelSource.levelsOf(player.getUniqueId()));
if (skills.isEmpty()) {
    player.sendMessage(line("スキルLv", "(なし / ValhallaMMO未接続)"));
```
```java
// NativeSkillLevelSource.java:44-46 (levelsOf が空を返す条件)
if (result.isMissing()) {
    return Map.of();
}
```

### CORE-08
- **重要度**: LOW
- **分類**: DEADCODE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/config/domains/SkillTreeConfig.java:461-465`(`parseNative`) / `TrinityForge/src/main/java/com/trinityforge/skilltree/SkillNode.java:9,19-20,37,53`(`native_`フィールド)
- **事象**: `SkillTreeConfig` は各ノードの `native:` セクション(ValhallaMMOのnative perk-reward blockという触れ込み)をパースして `SkillNode.native_` フィールドへ格納するコードが健在だが、出荷 `skilltree/*.yml` に `native:` キーを持つファイルは1件も無く(`grep "^\s*native:"` でゼロ件)、`native_`/`nativeRewards()` を読む消費側コードも存在しない。「パースされるが誰も読まない」設定パス。
- **影響/再現**: 実害なし。管理者が `native:` セクションをyml内に書いても静かにパースだけされて何にも反映されない(UNIMPLEMENTEDとしての実害はあり得る)。
- **根拠**:
```java
// SkillTreeConfig.java:461-463
/**
 * Parses the Valhalla {@code native} perk-reward block as a raw key -> value map ...
 * Not validated against the TF stat space — Valhalla owns these.
 */
```

### CORE-09
- **重要度**: LOW
- **分類**: BUG(表示カテゴリのドリフト)
- **場所**: `TrinityForge/src/main/java/com/trinityforge/command/StatsCategory.java:52-56`(`GATHERING_KEYS`) / `42-50`(`CRAFT_KEYS`) / `TrinityForge/src/main/resources/combat/base-stats.yml`
- **事象**: このクラス自身のJavadocが「`*_KEYS` は `StatVocabulary`/`lore.yml` と自動同期しないので追加時は手動更新せよ、さもなくばドリフトが発生する」と警告している通り、実際にドリフトが発生している。`base-stats.yml` に列挙されている `woodcutting-extra-drop-chance` / `harvest-extra-drop-chance` / `planted-crop-growth-bonus` / `breeding-extra-child-chance` / `bred-animal-growth-bonus` / `hive-double-harvest-chance` などの採取/畜産系キー、および `workbench-quality-bonus` / `ritual-quality-bonus` などの生産系キーが `GATHERING_KEYS`/`CRAFT_KEYS` いずれにも含まれていない。
- **影響/再現**: `/tf stats gathering` や `/tf stats craft` を実行しても、上記ステータスは表示されず(`OTHER`バケットに落ちる)。`/tf stats all`では表示されるがカテゴリ分類が実態と異なる(採取系ステが「other」として表示される)。ゲーム進行には影響しないが、プレイヤー向け情報表示の分類が壊れている。
- **根拠**:
```java
// StatsCategory.java:15-17 (クラス自身の警告コメント)
 * このクラスの {@code *_KEYS} 集合は {@link com.trinityforge.stats.StatVocabulary}
 * や {@code stats/lore.yml} とは独立して手動管理されており、自動同期しない。...
 * さもなくばドリフトが発生する）。
```
```java
// StatsCategory.java:52-56 (GATHERING_KEYS に woodcutting/harvest/breeding/planted-crop 系が無い)
private static final Set<String> GATHERING_KEYS = Set.of(
        "mining_fortune", "fishing_luck", "fishing_bonus",
        "suspicious_respawn_chance", "hive_double_harvest_chance",
        "fish_sell_price_bonus", "ocean_fishing_bonus");
```

### CORE-10
- **重要度**: LOW
- **分類**: BUG(軽微なメモリリーク)
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/ParticleSeedListener.java:43`(`lastTriggerMillis`)
- **事象**: パーティクルシード発動のスロットル用 `ConcurrentHashMap<UUID, Long> lastTriggerMillis` に `PlayerQuitEvent` でのクリーンアップが無い。一度でもシード刻印済み道具を使ったプレイヤーのUUIDは、サーバー稼働中ずっとエントリが残り続ける。
- **影響/再現**: サーバーの累計ユニークプレイヤー数に比例して線形に増加する(1エントリ=UUID+long)。実害は極めて小さいが、`TreeFellingListener`/`FoodBonusListener`/`DungeonGateListener` など同種のマップは全て `PlayerQuitEvent` で明示的に除去しており、このリスナーだけこのパターンから外れている。
- **根拠**:
```java
// ParticleSeedListener.java:43 (フィールド宣言、onQuitハンドラなし)
private final ConcurrentHashMap<UUID, Long> lastTriggerMillis = new ConcurrentHashMap<>();
```
(このクラスに `PlayerQuitEvent` ハンドラが存在しないことをファイル全体で確認済み。)

### CORE-11
- **重要度**: LOW
- **分類**: BUG(軽微なメモリリーク)
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/FishSellListener.java:62`(`recentSaleTimestamps`)
- **事象**: 魚売却のレート制限用 `Map<UUID, Deque<Long>> recentSaleTimestamps`(プレーンHashMap)に `PlayerQuitEvent` でのクリーンアップが無い。内側の `Deque` は60秒ウィンドウで自己剪定されるが、外側Mapのキー(UUID)自体は一度作られると消えない。
- **影響/再現**: CORE-10と同種。`fish-sell-toggle` を保有するプレイヤーの累計人数分だけ空の `Deque` エントリが恒久的に残る。
- **根拠**:
```java
// FishSellListener.java:62 (フィールド宣言、onQuitハンドラなし)
private final Map<UUID, Deque<Long>> recentSaleTimestamps = new HashMap<>();
```

### CORE-12
- **重要度**: LOW
- **分類**: REDUNDANCY
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/ItemRefreshListener.java:8`と`15`(`Player`重複import) / `17`と`18`(`PlayerInventory`重複import)
- **事象**: 同一クラスの完全修飾importが2回書かれている(`org.bukkit.entity.Player` が8行目と15行目、`org.bukkit.inventory.PlayerInventory` が17行目と18行目)。Javaはコンパイルエラーにはしないが、マージ/リベース時の残骸と思われる。
- **影響/再現**: 実害なし。linter/IDEの警告対象。
- **根拠**:
```java
// ItemRefreshListener.java:8,14-19 (抜粋)
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.PlayerInventory;
```

### CORE-13
- **重要度**: LOW
- **分類**: UNIMPLEMENTED
- **場所**: `TrinityForge/docs/design/2026-07-25-gather-rework-active-framework.md` §3 コンポーネント4 / `TrinityForge/src/main/java/com/trinityforge/active/ActivationDispatcher.java:22-28` / `TrinityForge/src/main/java/com/trinityforge/command/ActiveCommand.java:26-33`
- **事象**: 設計書は `/tf active <id>`(Brigadier、補完候補は解放済みのみ)を一次トリガー、**さらに「解放済みアクティブ一覧+クリック発動+CT残量表示」のGUI**を補助トリガーとして明記している(§3 コンポーネント4、§6 Q2「`/tf active <id>` ＋ GUI が正式トリガー」)。実装では、上位オーケストレーターの判断により正式トリガーは「スニーク+右クリック」(`ActivationDispatcher`)に変更され、`/tf active <id>` はデバッグ専用・非公開コマンド(`trinityforge.admin`限定、ヘルプ/タブ補完非掲載)に格下げされている。**アクティブ一覧GUIはコードベース中どこにも実装されていない**(`grep`で `ActiveSkill`/GUI関連のInventory実装クラスが存在しないことを確認)。
- **影響/再現**: プレイヤーは「自分がどのアクティブスキルを解放済みか」「CTがどれだけ残っているか」を一覧できるGUIが無い(`FeedbackLayer`のactionbar通知のみ)。この差分自体はコード内コメントで「オーケストレーティングブリーフ§6 Q2により上書きされた」と明記されており意図的な仕様変更だが、設計書の成果物(GUI)自体は依然として未実装のまま。
- **根拠**:
```java
// ActivationDispatcher.java:22-28
 * The one player-facing activation entry point (... as overridden by the orchestrating brief §6 Q2 —
 * the design doc's {@code /tf active}+GUI trigger was REJECTED in favor of this): ...
 * No GUI exists in this wave. {@code /tf active <id>} (see {@code ActiveCommand}) stays as a
 * debug-only alternate path, not advertised in help/tab-completion.
```

### CORE-14
- **重要度**: LOW
- **分類**: COMMENT
- **場所**: `TrinityForge/src/main/java/com/trinityforge/config/domains/UseRequirementsConfig.java`(load メソッド、要目視追加確認)
- **事象**(要確認): 前回セッションの調査で、このクラスだけが `InvalidConfigurationException | IOException` ではなく広い `catch (Exception e)` を使っている、という所見が既存メモに残っていたが、本セッションでは再読して他の兄弟configクラスとの一貫性を再確認できていない。念のため要再確認として記載する。
- **影響/再現**: (要確認)想定される実害は薄い(fail-softの方向自体は正しい)が、他のconfigクラスとの例外捕捉方針の一貫性を崩している可能性。
- **根拠**: (要確認 — 本セッションでは行番号レベルの再引用ができていない)

---

## configキー 双方向差分(実施した範囲)

ブリーフの要求(全件・表形式)に対し、時間予算の制約から **実際にJavaリーダーを読んだ上で該当yamlと突き合わせたもの** を以下に列挙する。46ドメイン全件の総当たりは完了していない(要継続、CORE-15として明示)。

| yml | 対応Javaドメイン | 差分 |
|---|---|---|
| `progression/crafting-features.yml` | `CraftingFeaturesConfig` | `gated-catalog-recipes: {}` が孤立(CORE-02)。他キーは一致。 |
| `combat/base-stats.yml`(156キー) | `BaseStatsConfig` | yml側キー漏れ・Java側末読み共になし(0値含め全キーがコメントで明示列挙されている設計)。ただし `StatsCategory`(表示層)側でカテゴリ分類漏れ(CORE-09)。 |
| `combat/display.yml` | `DisplayConfig` | 一致。 |
| `hate/rates.yml` | `HateConfig` | 一致。 |
| `stats/skill-exp.yml` | `SkillExpConfig` | 一致。 |
| `stats/smithing-gimmick.yml` | `SmithingGimmickConfig` | 一致。 |
| `stats/mining-gimmick.yml` / `stats/woodcutting-gimmick.yml`(`tiers:`セクション) | `MiningGimmickConfig` / `WoodcuttingGimmickConfig` | 2026-07-25のtier表方式(gather-rework-active-framework §1/W2)が実装済みで、ymlとJavaの両方に`tiers:`が存在し整合。 |
| `combat/mob-defaults.yml` | (登録ドメイン無し、`MobTypesConfig`が直読フォールバックのみ) | CORE-01参照。ConfigDomainとしての検証は機能していない。 |

### CORE-15
- **重要度**: LOW
- **分類**: (メタ所見、監査プロセス自体の限界の申告)
- **場所**: N/A
- **事象**: ブリーフが要求する「configキーの両方向差分を全件表で」は、46個のconfigドメインクラス全てに対して未実施(上表の7ファイルのみ実施)。残り約39ドメインは `load()` メソッドを読了済みで構造的な作りは確認したが、対応するyml側との1キーずつの突き合わせ作業までは時間予算内で完了できなかった。
- **影響/再現**: 未検査領域に本レポートで拾えていないconfigキードリフトが残っている可能性がある。優先して追加検査すべき候補: `MobTypesConfig`(defaultsフォールバック直読み部分、CORE-01と関連)、`ItemStatsConfig`(15項目超のスキーマ)、`SkillExpConfig`以外のprogression系。
- **根拠**: (プロセス上の申告のため該当コード無し)

---

## アクティブスキル基盤(`active/`) — 実装状況まとめ

- `ActiveSkill` / `ActiveSkillRegistry` / `CooldownManager` / `FeedbackLayer` / `ActivationDispatcher` / `ActiveContext` / `ActivationResult` の7ファイルは設計書W1(基盤)を全て満たしている。スレッド安全性(`ConcurrentHashMap`ベースの`CooldownManager`)、`PlayerQuitEvent`でのクールダウンクリーンアップ、CT短縮の二重適用防止など、非自明な設計判断が全てJavadocで裏付けられている。バグは見つからなかった。
- 設計書W2(採取パラメータ化)・W3(UXトグル)は共に実装済みを確認: `mining-gimmick.yml`/`woodcutting-gimmick.yml`の`tiers:`セクション、`PlayerData.veinMiningEnabled()`等のトグルPDC、`SettingsGui`の対応ボタン(`GATHER_TOGGLE_*_SLOT`)まで揃っている。
- 唯一の未達分はCORE-13(GUI未実装)。ただし正式トリガーの変更は文書化された意図的な仕様決定であり、バグではない。
- 現在登録されている具象 `ActiveSkill` は `HasteActiveSkill`(`com.trinityforge.mining`)1件のみ。設計書が「後差し」として明示している `potion-merge`/`dismantle-unlock`/`weapon-coating-unlock`/`wood-repair-unlock` の4アクティブは、対応する機能(`PotionMergeListener`/`DisassemblyListener`/`WeaponCoatingListener`/`WoodRepairListener`)自体は別途 dedicated-effect ゲート方式で実装済みだが、`ActiveSkill`インターフェースには乗せられていない(設計書が「枠のみ、本設計対象外」と明記している通りで、UNIMPLEMENTEDとしての新規指摘ではない)。

---

## コマンド(`command/`) — 監査結果

12ファイル全て読了。**権限チェックの欠落・console実行時のClassCastException・タブ補完と実ロジックのドリフトは1件も見つからなかった**:
- 管理者専用コマンド(`give`/`bind`/`stamp`/`importmobs`/`dungeon`/`active`/`progression`/`reload`)は全て `TrinityForge.registerCommands()` 側で `.requires(TrinityForge::isTfAdmin)` により一括ゲートされている(コマンドクラス自身は権限チェックを持たない設計だが、登録側で一貫して担保されている)。
- プレイヤー専用コマンド(`stats`/`role`/`collection`/`settings`/`inspect`ほか)は全て `sender instanceof Player` を先頭でチェックしてから安全にキャストしており、console/RCON実行時も適切なエラーメッセージを返す(`InspectCommand`/`GiveItemCommand`/`StampCommand`はconsoleでも動作するよう設計されている)。
- `BindCommand.resolveTarget` はオフライン任意名を受理する `Bukkit.getOfflinePlayer(String)` を意図的に避け、実在確認済みのプレイヤーのみに解決を絞っている(誤字プレイヤー名への誤soulbindを防ぐ設計)。

---

## リスナー(`listeners/`) — 登録状況・優先度の総括

63ファイル全て実読。`TrinityForge.java` の `onEnable()` とのクロスチェックにより、本レポートで言及した `ParticleSeedListener`/`FishSellListener`/`NativeSkillExperienceListener`/`CombatListener`/`CraftQualityListener`/`GachaListener` を含め、実際に `registerEvents` されていることを確認済み。

同一イベントを扱う複数リスナー間の優先度は一貫して「先に確定させるべき側がLOW〜NORMAL、後から読む/上書きする側がHIGH〜MONITOR」の原則に従っており、コメントで相互参照されている。確認した主な組: `CombatListener`(HIGH、ダメージ確定) → `AnimalDamageListener`(HIGHEST、動物ダメージ倍率) → 各種MONITOR系(EXP付与・CT開始・チャージ記録)。`EnchantLuckListener`(LOW、レベル格上げ) → `OverEnchantListener`(HIGH、上限クランプ)。`FishingGimmickListener`(LOW、宝/ゴミ置換) → `FishingQualityListener`(NORMAL、品質刻印) → `FishSellListener`(MONITOR、換金)。`BrewOwnership`書き込み(MONITOR) は品質/速度側リスナーがHIGHで先に読むことを前提に、消去だけを最後に行う設計。いずれも矛盾や競合は見つからなかった。

`PlayerMoveEvent` を購読する `DungeonGateListener` はホットイベントだが、`gateService.hasRegionGates()` と同一ブロック内移動の早期returnで実運用上のコストを最小化している。DB同期アクセスをイベントハンドラ内で行っている箇所は見つからず(`ProgressionPreloadListener`は明示的に非同期スケジューラへ逃がしている)。

`ignoreCancelled` の付け忘れは調査した63ファイル中0件(全ての `@EventHandler` が明示的に `ignoreCancelled` の要否を判断している)。

---

## 未完了・継続課題

- CORE-15の通り、config双方向差分の全件表は未完了。
- CORE-14(`UseRequirementsConfig`の例外捕捉方針)は行番号レベルの再確認が必要。
- `TrinityForge.onDisable()` の「`DamagePopupDisplay` にshutdown()が無いのは表示が短命で再起動時に自然に一掃されるため」という設計コメントについて、実際に「次回起動時のスイープ」ロジックが存在するかどうかは本セッションでは未検証(要確認、時間予算の制約により見送り)。
