# EliteMobs fork 実装仕様書（個別リポジトリ用）

- **作成日**: 2026-06-27
- **対象**: EliteMobs を fork した個別リポジトリで行う改変。本書は「どのクラスをどう変えるか」を実装可能レベルで示す
- **クラス参照元**: `extract_all/com/magmaguy/elitemobs/**`（逆コンパイル済み。**正確なメソッド署名は実装時に当該リポジトリの実ソースで確認**。本書はクラス責務と改変方針を確定する）
- **上位文書**: `ADDON_INTEGRATION_SPEC.md`（§2 EliteMobs fork 改変点）、`COMBAT_SYSTEM_SPEC.md`、`DUNGEON_SPEC.md`、`ROLE_SYSTEM_SPEC.md`、`DESIGN.md` v0.4
- **大原則**: 数値・倍率・ドロップ率・テーマ配分・ゲート閾値は **fork に焼き込まず、統合アドオンの設定から供給**（fork は中立化＋委譲に徹する）

---

## 0. 改変の全体方針

EliteMobs は「装備tier×レベルでダメージ/防御を算出し、独自に最終ダメージを適用する」。本サーバは **gear非依存＋統合アドオンの対称パイプラインに一本化**するため、forkでは:

1. EliteMobs 内部のダメージ算出を**中立化**（gear倍率を 1.0 に短絡）
2. 最終ダメージ適用を**統合アドオンへ委譲**（Bukkit API Event 経由 or 直接呼び出し）
3. combatレベル・ドロップ・targeting・ダンジョン・耐性プロファイルの**入力ソースを差し替え/フック**

---

## 1. ダメージ算出の中立化（gear非依存・COMBAT §1 / DESIGN v0.4）

| クラス（`combatsystem.`） | 現状の責務 | 改変 |
|---|---|---|
| `WeaponOffenseCalculator` | 装備tierベースの与ダメ倍率 | **倍率を 1.0 に短絡**（gear非依存。設定で無効化フラグ） |
| `ArmorDefenseCalculator`（+ `DamageType` enum） | 装備tierベースの被ダメ軽減 | **軽減を 1.0 に短絡**。`DamageType` は物理/魔法の成分判定に**流用**（統合アドオンの成分分解と対応付け） |
| `LevelScaling` | mob levelによる与ダメスケール | **唯一の物理生与ダメ源として残す**（「バニラ基準 × mob levelスケール」） |
| `PotionCombatModifierCalculator` | Strength/Weakness/Resistance 反映 | Strength/Weakness はそのまま（attackDamageに内包）。**バニラResistanceは無効化**し統合アドオンの `防御率%` に統合（COMBAT §5・二重軽減防止） |
| `DamageBreakdown` | ダメージ内訳の保持 | **物理/魔法成分フィールド＋本サーバ新規ステを保持**できるよう拡張（表示・委譲のキャリア） |
| `CombatSystem` | 戦闘オーケストレーション | 最終ダメージ適用を**統合アドオンのパイプラインへ委譲**（§2） |
| `ScaledCombatRewardResolver` | 戦闘報酬スケール | 報酬の**1人あたり一定**化に合わせ調整（§5/§7） |

---

## 2. パイプライン統合（最終ダメージの委譲）

統合アドオンの8step対称パイプライン（COMBAT §2）に最終計算を渡す。フック候補（`api.` package、いずれもBukkit Event）:

| Event | 用途 |
|---|---|
| `EliteMobDamagedByPlayerEvent` | プレイヤー→エリート。与ダメをパイプラインで再計算 |
| `PlayerDamagedByEliteMobEvent`（`DefensiveSkillResult` 内包） | エリート→プレイヤー。被ダメをパイプラインで再計算 |
| `EliteMobDamagedByEliteMobEvent` | mob間（ペット含む味方mob）。対称式を適用 |
| `EliteDamageEvent` / `EliteMobDamagedEvent` | 汎用の最終フック |

- 方針: forkは**デフォルトダメージ（中立化後の生値）＋攻撃源/被弾者コンテキスト**を統合アドオンへ渡し、返ってきた最終ダメージを適用
  - **LD-9改訂注記（2026-07-03）**: 上記「forkが最終ダメージを適用」という記述は、`OPEN_DECISIONS.md` LD-9（TrinityForgeの`SymmetricCombatService`が最終ダメージ/被ダメージの唯一の権威。EliteMobs自前のダメージ改変は無効化し、forkの責務は湧き/AI/loot/`MobData`提供に専念）が正典。本節はLD-9確定前の草案表現であり、実装（イベントのcancel/適用主体）はLD-9の原則に従うこと
- `EliteMobGenericDamagedHandler` / `EliteMobDamagedByEliteMobHandler` が実適用箇所 → ここを委譲呼び出しに置換

---

## 3. combatレベル写像（DUNGEON §2・重要）

- EliteMobs はプレイヤーの「combatレベル/装備tier合計」で動的難易度を決める。本サーバは **gear非依存**のため入力を差し替える
- **combatレベル ＝ 戦闘職スキルツリーのレベル平均**（ValhallaMMOから読取）に置換
- 改変対象: combatレベル/動的難易度の解決器（候補: `combatsystem.LevelScaling` および `playerdata.PlayerData` が保持する難易度値）。**実ソースで「item tier 合算箇所」を特定し、入力をValhalla戦闘職平均に差し替える**
- 平均の取り方・変換カーブは統合アドオン設定（数値フェーズ）

---

## 4. ヘイト/targeting（ROLE §4・新規ヘイト値システム）

- 統合アドオンが**ヘイトテーブル**を保持。forkは**ターゲット選択時に参照**して敵視を制御
- **主フック**: `api.EliteMobTargetPlayerEvent`（`EliteMobTargetPlayerEventFilter`）→ ヘイト値最上位の対象へ**ターゲットを上書き/誘導**
- 補助: `api.EliteMobEnterCombatEvent` / `EliteMobExitCombatEvent` で戦闘状態を同期（ヘイト初期化/減衰トリガ）
- タンク（ヘイト上昇）・ペット（常時稼ぎ）・獣使い（能動挑発）は同一テーブルへ寄与（PET §3.2）

---

## 5. 供給loot（DUNGEON §3）

| クラス（`items.`） | 用途 |
|---|---|
| `customloottable.CustomLootTable` / `CustomLootEntry` | ドロップ定義の本体。転職/厳選/コア/エンドアイテムのエントリを追加 |
| `customloottable.EliteCustomLootEntry` / `ItemStackCustomLootEntry` / `CommandLootTable` / `VanillaCustomLootEntry` | エントリ種別。**厳選装備/触媒は統合アドオンのroll導出（rollSeed＋品質）を呼ぶ新エントリ種**として実装 |
| `customloottable.SharedLootTable`（`PlayerTable`） | **既存の「プレイヤー別テーブル」を流用**→ ドロップ期待値を1人あたり一定に（DUNGEON §6） |
| `DefaultDropsHandler` | 既定ドロップ。バニラ/エリート共通の供給制御 |

- フック: `api.EliteMobDeathEvent`（討伐ドロップ）、`api.QuestRewardEvent`（クエスト報酬）
- 供給元マッピング: 転職=②反復ダンジョンのボス枠／厳選=②通常+ボス／コア=①+②雑魚／エンドアイテム=③（所有者バインド付与）

---

## 6. ダンジョン3層＋Guildランク入場（DUNGEON §1-2）

| クラス（`dungeons.`） | 役割 | 3層対応 |
|---|---|---|
| （非インスタンスのワールド） | 常設フィールド | **①フィールド** |
| `WorldInstancedDungeonPackage` / `DynamicDungeonPackage` | 反復可能なインスタンス | **②反復ダンジョン**（供給主動線） |
| `WorldDungeonPackage` / 高tier instanced | 高難度やり込み | **③エンドコンテンツダンジョン** |
| `Dungeon` / `EMPackage` / `MetaPackage` | パッケージ基盤 | 共通 |
| `DungeonBossLockout(Handler)` | ボス周回ロックアウト | 反復クールダウンに流用 |

- **入場ゲート**: `api.PlayerJoinDungeonEvent` / `api.PlayerPreTeleportEvent` をフックし、**スキルツリーレベル（＝combatレベル写像）でブロック/許可**
- Adventurer's Guild ランク帯と②の解禁帯の対応表は統合アドオン設定（数値フェーズ）

---

## 7. 耐性2層プロファイル（DUNGEON §4 / TRINITY §2）

- **mob個別**: CustomBoss/mob 設定に物理/魔法の耐性・守備力を持たせる（統合アドオンPDCへ）
- **ダンジョン大枠（属性テーマ）**: ダンジョンごとの既定耐性プロファイルを spawn 時に適用
- フック: `api.EliteMobSpawnEvent` → 統合アドオンが「ダンジョンテーマ既定 ＋ mob個別上書き」で stat プロファイルをスタンプ
- 逆張り例外モブはmob個別上書きで表現

---

## 8. 既存機能の再利用（重要・新規実装を増やさない）

| 既存クラス | 本サーバでの再利用 |
|---|---|
| `items.GearRestrictionHandler` | **装備使用レベル制限（PROGRESSION §4）**へ拡張。要求軸をスキルツリーレベルに |
| `items.customenchantments.SoulbindEnchantment` / `UnbindEnchantment` | **バインド/所有権（PROGRESSION §3）**へ流用（厳選完成品=ソウルバインド、エンドアイテム=所有者付与） |
| `items.customenchantments.RepairEnchantment` | **修繕廃止（COMBAT §7）**: バニラ相当の修繕を無効化（Ars儀式修繕のみ許可は ArsPaper fork 側） |
| `items.customenchantments.SummonWolfEnchantment` | ペット的召喚の参考（ただしペット本体は既存ペットPL・PET_INTEGRATION） |
| `playerdata.PlayerData` (DB) | combatレベル写像値・ヘイト関連・所有権の永続化先候補 |
| `combatsystem.displays.BossHealthDisplay` | ダメージ表示（成分内訳の可視化に拡張可・Bedrock配慮） |

---

## 9. 設定外出し（ハードコード禁止・統合アドオン設定へ）

forkが参照する以下は全て統合アドオン設定から供給（fork内に定数を置かない）:
- gear中立化フラグ、LevelScalingカーブ
- combatレベル写像（戦闘職平均の取り方・変換カーブ）
- ヘイト各レート、ターゲット上書き閾値
- 供給loot のドロップ率・エントリ構成・1人あたり一定の配分
- ダンジョン3層の解禁レベル・Guildランク対応・周回クールダウン
- 耐性2層（ダンジョンテーマ既定・mob個別・例外混入率）
- 人数スケーリングのHP/火力カーブ

---

## 10. 実装順（M5 連動・IMPLEMENTATION_PLAN）

1. ダメージ中立化（§1）＋パイプライン委譲（§2）— 物理が統合式で解決することを確認
2. combatレベル写像（§3）
3. 耐性2層 spawn スタンプ（§7）＋供給loot（§5）
4. ダンジョン3層＋入場ゲート（§6）
5. ヘイト/targeting（§4）
6. 既存機能流用の接続（§8）

---

## 11. 未決（実装時に実ソースで確認）

- [ ] combatレベル/動的難易度の**実際の算出クラスとメソッド**（item tier 合算箇所の特定）
- [ ] `EliteMobDamagedByPlayerEvent` 系で**最終ダメージを書き換え可能か**（cancellable/setDamage の有無）
- [ ] `EliteMobTargetPlayerEvent` で**ターゲット上書きが可能か**
- [ ] Adventurer's Guild ランク管理クラスの所在（入場ゲート連携）
- [ ] `SharedLootTable.PlayerTable` の per-player 配分の正確な挙動
