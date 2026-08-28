# TrinityForge — 仕様意思決定ログ（Open Decisions）

- **作成日**: 2026-06-29
- **位置付け**: 実装によって浮き彫りになった「仕様の漏れ・未決の構造的決定」を壁打ちで埋めるための生きた台帳。決定したら `LD`（Locked Decision）へ移し、コード/仕様への反映先を記す。
- **正典方針**: 6月版（2026-06-26 以降）の仕様群を正典とする。5月以前の `DESIGN.md`・`ARS_SPEC.md` は破棄済み（superseded）。
- **メタパターン**: 仕様書は「数値」を調整フェーズへ後回ししているが、コードは仕様が空けたままの「構造的デフォルト」を黙って確定させている。本台帳が埋めるのはその構造的決定。

---

## 0. 確定コンセプト（壁打ち合意済み・2026-06-29）

- **構成**: 複合する3プラグイン = **EliteMobs**（mob/ダンジョン）/ **ArsPaper**（魔法）/ **TrinityForge native スキルツリー**（進行）。**TrinityForge** は接着剤＋進行の本体。ValhallaMMO への hard depend は失効（LD-10 失効）。
- **相補トリニティ**: **物理・魔法の2主柱（同格）** ＋ **囮ヘイトの補助極（おまけ）**。RPS循環ではない。攻撃源で属性確定（武器=物理 / 触媒=魔法）。
- **2つの難易度軸が対称パイプラインで衝突**:
  - ワールド脅威 = **スポーン座標からの距離でモブlevel上昇**（遠いほど高難易度）
  - プレイヤー戦力 = **TF native スキルツリーの進行**
- **目的**: 協力PvE・**PvP無し**・最高難易度ダンジョン制覇。人数は効率を変えるが**突破ゲートにしない**（持ち替え＋ペットでソロ成立）。
- **ロール = 行動駆動の条件付きバフ**（クラス縛りではない）。ロール非保持でも全スキル・全行動可能（例: 獣使いでなくてもペットを持てる）。

---

## 1. ロック済み決定（LD）

| ID | 決定 | 根拠 | 反映先（TODO） |
|----|------|------|----------------|
| LD-1 | 正典は6月版仕様群。5月以前は破棄 | ユーザー確定。`DESIGN.md`/`ARS_SPEC.md` 削除済み | （完了） |
| LD-2 | ArsPaper連携は**bridgeアーキテクチャが正典**（旧ArsAPI facade仕様は無効） | LD-1で `ARS_SPEC` 破棄。ギャップ`U2`決着 | U5/U6 の再解釈、coupling是認 |
| LD-3 | **ロール = 明示選択の条件付きバフ**（クラスで行動を封印しない）。ロール非保持でも全行動可。選択 UI は現コードが正 | ユーザー確定（2026-08-28: 明示選択のまま） | 現 `role-buffs` / 選択コマンド。LD-3 旧文の「選択制クラスではない」は「行動ロックしない」意味 |
| LD-4 | ワールドのモブlevel = **スポーン距離由来**。自然湧きeliteは距離levelを防御プロファイルに用いる（level0フォールバックは取りこぼし） | ユーザー確定（「座標が遠いほどレベルアップ」） | `D1` 実装方針、`mob-defaults` の area-baseline |
| LD-5 | コンセプト＝相補トリニティ（物理/魔法2主柱＋囮補助）、協力PvE/PvP無し | ユーザー確定 | 全ギャップの判断軸 |
| LD-6 | **初回マイルストーンの稼働範囲 = ペットとPvP以外すべて**。戦闘pipeline・ヘイト/タンク・ロールバフ・アンロック・アイテム・ダンジョン・進行を初手から動かす。**ペット(Pt1)とPvPのみ追加コンテンツ（後回し）** | ユーザー確定（R0） | Pt1=deferred。⚠ ソロのヘイト肩代わり手段（TRINITY §3）がペット前提のため、ペット無しの初回ではソロ高難易度のヘイト代替を別途決める必要（C5/R2と接続） |
| LD-12 | **C8 ダメージ分類フォールバック = 無属性(typeless)チャネル**: 攻撃を物理/魔法に分類し、それぞれ physical/magical `DefenseStats` で軽減（魔法→プレイヤーも同経路）。**分類不能時は「物理にも魔法にも属さない無属性」として同一ダメージをそのまま付与**（両DefenseStatsで軽減されず、cancelもvanilla移譲もせずTFパイプライン内で決定的に処理）。無属性は**フォールバック専用チャネルで、プレイヤーが育てる攻撃属性ではない**（LD-5の物理/魔法2主柱を侵さない）。 | ユーザー確定 | `SymmetricCombatService` に typeless 経路を追加（両DefenseStats bypass）。属性判定不能時の分岐 |
| LD-11 | **D5 mob-profiles 二層化**: importは `mob-profiles.generated.yml`（自動生成層・破壊的再生成OK）に書く。手調整は `mob-profiles.overrides.yml`（or `overrides/<dungeon>.yml` でper-dungeon分割）に置き、ロード時に overrides を generated へ **deep-merge**。**衝突時は overrides（手調整）優先**。**importは overrides に不可侵**。 | ユーザー確定（手調整ファイル優先） | `MobProfileConfig` ロードに二層deep-merge追加。`ImportMobsCommand` の書き出し先を generated に変更 |
| LD-10 | ~~**Pr2 Valhalla依存 hard depend**~~ **失効（2026-07 native 進行へ移行）**。`paper-plugin.yml` に Valhalla は無い。進行は TF スキルツリー | コードが正 | native skilltree |
| LD-9 | **不変条件: TF `SymmetricCombatService` が最終ダメージ/被ダメージの唯一の権威**。他PL（EliteMobs/Valhalla/Ars）は*入力*（プロファイル・stat・レベル）を供給するのみで、**独自のダメージ/防御レイヤーを別途適用しない**。帰結: **C4**=elite被弾はTF単一所有、**EliteMobs自前ダメージ改変を無効化**（フォークは湧き/AI/loot/`MobData`提供に専念）。**C3/C11**=武器perkの威力もTFパイプライン入力に集約し、バニラ`leveling_perks`等の別レイヤー乗算と**二重化させない**（防御のLD-8と同型）。 | ユーザー確定（C4=案1＋原則批准） | EliteMobsフォークのダメージ改変listener無効化、core/fork event priority整理。武器/防具perk→TF stat出力へ付け替え |
| LD-8 | **C1/I2/Q6 プレイヤー防御の源 = γ（skill＋item・単一オーナー）**: `DefenseStats = 防具スキルbaseline ＋ item上乗せ`（**加算合成**）。二重化対策=防具2スキルperkを「vanillaの damage-reduction を別レイヤー適用」から「**TF防御stat（armor-strength/defense-rate）を出力**」へ付け替え、防御はTFパイプライン**単一経路**。item側=防御系の**新rollキー**(armor-strength/armor-defense-rate/max-health/knockback-resistance/move-speed)→**I2解決**。攻撃側5キー(crit/crit-damage/penetration/flat・percent-bonus-damage)はPDC-only据置。残=phys/magic防御の振り分けは調整フェーズ。 | ユーザー確定（C1=γ） | 防具スキルツリー調整(G2/G4)に内包。`roll.yml`へ防御キー追加＋`attribute-map`一致。`SymmetricCombatService`のplayer-defender経路実装 |
| LD-7 | **combat level** = `max-of-top-N / divisor`（`progression/combat-level.yml` の `pillars`）。対象スキルは軽/重武器・弓術・Ars魔法・**軽/重防具**。装備は算入する（2026-08-19）。旧文の `max(物理柱, 魔法柱)×上限` と防具除外は失効 | コード＋出荷 yml が正（2026-08-28 確認） | `CombatLevelModel` / `combat-level.yml` |

> **実装現状注記（2026-08-28更新）**: combat-level.yml は `pillars` の max-of-top-N / divisor。防具2種も算入。LD-7 旧式（柱間 max・防具除外）は失効。
>
> **LD-13（防御 phys/magic typing・2026-08-28 コード追従）**: **守備力 flat は typed**（`phys-flat-defense` / `magic-flat-defense`）。耐性%も typed。防御率%・被ダメージ軽減%・防具強度・回避は type-independent。旧「flat は共通」は失効。

---

## 2. 未決ギャップ一覧（再優先度付け）

優先度: **P1**=ゲーム成立に関わる構造欠落 / **P2**=バランス・UX / **P3**=polish。
状態: ◻=未決 / ◐=方針確定・実装待ち / ✅=決定済（→LD）。

### Combat
| ID | P | 状態 | 論点 |
|----|---|---|------|
| C1 | P1 | ◐→LD-8/LD-13 | **決定=γ**。✅item側実装済。✅振り分け=**LD-13（flat も typed）**。防具スキル baseline は native パーク合算 |
| C1b | P1 | ◻ | **防具スキルbaseline → `DefenseStats.combine` 未結線**（γの残り半分）。Valhallaブリッジ（Q1=コンパニオンPL）依存。⚠結線時、vanilla armorと重複する`[0,1]`ステ（defenseRate等）を加算するとclamp飽和で全免疫になりうる→加算/乗算の重畳方式を要決定（`DefenseStats.combine` javadoc） |
| C2 | P1 | ✅ | **現行コードが正**: 魔法も combat-level でスケールする（出荷 `magical.scale-with-combat-level: true`）。旧「魔法は bypass」は失効 |
| C3 | P1 | ✅→LD-9 | **決定**: 武器perk威力もTFパイプライン入力に集約。バニラ`leveling_perks`等の別レイヤー乗算と二重化させない（単一所有）。残=具体係数は調整 |
| C4 | P1 | ✅→LD-9 | **決定**: TF対称パイプラインが全mobの最終ダメージ単一所有。EliteMobs自前ダメージ改変を無効化。実装=フォークのダメージlistener無効化＋event priority整理 |
| C5 | P1 | ◐ | **機構実装済み（※net-new）**: コードに`HateTable`は未存在だったため、リークセーフな`com.trinityforge.hate`サブシステムを新設（cap/LRU・TTL・sweep・全eviction経路・decay=既定off、テスト15green、`HateConfig`/`rates.yml`）。⚠**balanceは未実装でR2送り**（threat係数neutral 1.0、tank/獣使いのwall挙動なし）。⚠設計フェーズでnet-new実装した点は要確認（不要なら revert 可） |
| C6 | P2 | ◻ | ターゲット変更に閾値/ヒステリシス無し→近接ヘイトで振動。override margin と保持時間 |
| C7 | P2 | ◻ | C5新設で `EntityDamageByEntityEvent`(player→mob)全般からthreat記録済み（近接限定ではない、係数neutral1.0）。**未結線=要決定**: 非ダメージ源（heal/taunt/魔法詠唱/ペット）がヘイトを生むか、tank倍率をどこで乗せるか |
| C8 | P2 | ✅→LD-12 | **決定**: 魔法→プレイヤーは magical DefenseStats で軽減。分類不能時は無属性(typeless)で同一ダメージ素通し（cancel/vanilla移譲せず） |
| C9 | P2 | ◻ | クリティクス経路がデッド（全callerが`plain(0)`）。crit統計の供給元・mobもクリるか・[0,1]クランプ |
| C10 | P3 | ◻ | 成分ダメージ下限が per-type 設定可。集計下限が1超になりうる |
| C11 | P2 | ✅→LD-9 | **決定**: LD-9の単一所有原則に従い`base-coefficient`は攻撃側入力にのみ作用。pre-baked mob基礎の再乗算を排除 |
| C12 | P2 | ◻ | バニラのジャンプクリティカル（打撃×1.5）の扱いが対称パイプラインで未定義（`COMBAT_SYSTEM_SPEC.md` §5）。パイプラインに折り込むか、バニラ挙動のまま残す（§5のBLOCKING等と同様の対象外扱い）か未決 |

### Dungeon
| ID | P | 状態 | 論点 |
|----|---|---|------|
| D1 | P1 | ◐ | 自然湧きelite→距離levelで防御プロファイル付与（LD-4で方針確定、実装待ち） |
| D2 | P2→P1 | ◐ | **決定（Q4）＋実装済**: 入場ゲート=**combat level要求（主）＋キーアイテム消費（オプション）**。`DungeonGatePolicy`＋`DungeonGate`＋`DungeonGateConfig`(`dungeon/gates.yml`空=fail-open)＋`DungeonGateListener`(cross-world teleport)＋config/listener配線。テスト済。⚠残=実機で入場enforcement検証・非cancelableteleportでのkey消費edge（portal/in-place経路はD3で実装済） |
| D3 | P2 | ◐ | **実装済（2026-07-21）**: (a)インスタンスダンジョン=content-packageエイリアスでクローン前+参加時にゲート（フォーク`DungeonInstance`/`DynamicDungeonInstance`）、(b)ポータル=`PlayerPortalEvent`が`PlayerTeleportEvent`のHandlerList共有で既存ゲートに乗る、(c)in-place区画=`gates.yml`の`region:`(world+min/max AABB)で移動/テレポートの外→内跨ぎをゲート（`GateRegion`+`DungeonGateService.checkRegionEntry`二相評価+拒否メッセージ1.5sスロットル）。⚠残=実機検証。歩行出入りのたびにキー消費する仕様は要運用注意 |
| D4 | P2 | ◻ | テーマ適用がimport時・一律。逆張り例外モブ（DUNGEON §4）が表現できない |
| D5 | P1 | ✅→LD-11 | **決定**: generated（import）＋overrides（手調整）の二層deep-merge、衝突時overrides優先、importはoverrides不可侵。per-dungeon分割可 |
| D6 | P3 | ◻ | `MOB_DUNGEON_THEME` PDCタグの実行時consumerが無い。表示専用か真実源か |

### Items
| ID | P | 状態 | 論点 |
|----|---|---|------|
| I1 | P1 | ◐ | **機構修正済み**: `StatKeys.canonical` でkebab↔snakeを畳み込み一致（テストgreen）。⚠ただし現行 `roll.yml`(crit/penetration等)と `attribute-map.yml`(attack_power等)は**集合が交わらず実対応ゼロ** → 真の残課題は **I2（正典stat辞書）** へ移譲。`STAT_DICTIONARY_RECONCILIATION.md` 生成中 |
| I2 | P1 | ◐→LD-8 | **方向確定（LD-8）**: 防御系の新rollキー(armor-strength/armor-defense-rate/max-health/knockback-resistance/move-speed)を追加し`attribute-map`へcanonical一致。攻撃側5キー(crit等)はPDC-only据置。実装=`roll.yml`追加＋写像。残=各キーの具体係数・phys/magic振り分けは調整。孤立`damageModifier`/`fixedDamage`の扱いはC4/調整で別途 |
| I3 | P1 | ◐ | **単一軸enforce実装済**: `UseRequirementPolicy`（純粋・テスト済）＋`CombatListener`で近接武器の使用レベル未達時に攻撃をキャンセル＋actionbar通知。残=(a)弓/触媒はshoot/cast時gate（未）(b)防具のequip gate（未）(c)合成軸（複数スキル要求）は将来判断 |
| I4 | P1 | ◐ | **決定（Q2）**: 用語=「エンドコンテンツアイテム」。バインドは**アイテムごとにconfig**（`BindType` per-item）、**原則=使用レベル制限のみ＝取引可**（default `MATERIAL_TRADEABLE`＋I3 gate、実装済）。特定アイテムのみ`SOULBOUND`/`OWNER_BOUND`。残=soulbound/owner付与stampをdrop/craftフロー(fork)で執行 |
| I5 | P2 | ◻ | loot stampが無差別・均一品質。DUNGEON §3のsupplier routing未実装 |
| I6 | P2 | ◻ | catalogデフォルトbindがtradeable（仕様は完成品=soulbound） |
| I7 | P2 | ◐ | **機構実装済**: `stats/roll.yml` の各statに `applies-to`（weapon/armor）を追加、`ItemStatRoller.deriveForCategory`＋`EquipmentSlotResolver.statCategory`＋`ItemAssembler`で**item種別ごとに roll/lore/attribute を限定**（武器に防御stat・防具に攻撃statが乗らない）。テスト済。残=触媒(magic)カテゴリの導入・stat組合せの多様化balance |
| I8 | P2 | ◐ | **決定（Q3=推奨）**: 既存2ノブ`QualityModel`（ceiling上昇＋上振れbias、`StatDerivation`で実装済）を正式採用。必要な品質段のみ明示テーブルで上書き可のハイブリッド余地。残=数値をゲームバランス面白さに合わせ調整（数値フェーズ） |
| I9 | P2 | ◻ | 鍛冶/錬金の品質倍率未実装。craft vs drop の品質算出・rollSeed再現性 |
| I10 | P2 | ◻ | max-qualityが二重真実源（コード[0,5] vs `quality.yml`可変） |
| I11 | P3 | ◻ | 属性modifierが`EquipmentSlotGroup.ANY`。slotスコープ |

### Progression
| ID | P | 状態 | 論点 |
|----|---|---|------|
| Pr1 | P1 | ◐ | **方針確定（LD-7）**: 供給skill = LightWeapons/HeavyWeapons/Archery（物理柱）＋ Ars魔法スカラー（魔法柱）。防具2→C1、採取/生産/Power は**除外**。「釣りmaxで戦闘力高」の自己矛盾は解消。実装＝`combat-level.yml`書き換え。✅**Power除外の裏取り（転写）**: Powerは他スキルの*level-up回数*に応じEXP付与するメタ集計（`PowerSkill.onPlayerLevelUp`、max256）。combat算入は二重計上になるため除外が正しい |
| Pr2 | P1 | ✅→LD-10失効 | Valhalla hard depend は捨てた。native スキルツリー |
| Pr3 | P2 | ◻ | Valhalla reflection契約が未文書化（4署名hardcode・version pin無し） |
| Pr4 | P2 | ◐ | 集計法は**LD-7で確定**（柱間max・物理柱はbest+λ）。残るは曲線形状とλ・上限の**数値**（調整フェーズ）。`VALHALLA_DEFAULT_SKILLS.md`の `exp_level_curve`（`level+75*2^(level/7.6)+300`, max100）が物理側の素のカーブ |

### Roles（実装ほぼゼロ・LD-3で再定義）
| ID | P | 状態 | 論点 |
|----|---|---|------|
| R1 | P1 | ◐ | LD-3により「クラス選択」ではなく**ロール別バフ発火条件**を作る。`setRoles`保持モデルは不要の可能性。バフ注入箇所は `SymmetricCombatService` |
| R2 | P1 | ✅ | **決定（Q1=ハイブリッド3+4）**: 「ヘイト依存を下げる設計」＋「ペット前提維持」。**初回ローンチは専任タンク/挑発を必須にしない**（現行HateService neutralが整合＝追加実装不要）。tank/獣使いwall＋ペットアグロは後続。**ローンチブロッカー解除** |
| R3 | P1 | ✅ | **明示選択 UX が正典**（2026-08-28）。行動から創発する案は不採用 |

### Unlock / Magic（ArsPaper）
| ID | P | 状態 | 論点 |
|----|---|---|------|
| U1 | P1 | ◻ | `heldPerks`に書き手なし→アンロック判定が全員素通り。Valhalla perk→heldPerks の橋。真実はperkかPDC mirrorか |
| U2 | — | ✅ | LD-2で決着（bridgeが正典） |
| U3 | P2 | ◻ | 全ゲートがfail-open。本番でcontent-bypass可否、空ゲート時の起動warning |
| U4 | P2 | ◻ | perk→unlockマスタ表未設定。`external-unlock-only`未実装。perk命名が先決 |
| U5 | P2 | ◻ | mana modifier系（ARS旧仕様）→PDCキー合算モデルに置換。modifier/コスト削減は破棄か保留か |
| U6 | P2 | ◻ | mana既定値・on-hit/idle回復がコード発明。意図した機構か（balance spec要） |
| U7 | P2 | ◻ | prestige perk保持アイテム未実装。setHeldPerks呼び出し主体 |
| U8 | P3 | ◻ | mana ranking（`RankingCache`）が未文書化機能。本採用かtelemetryか |
| U9 | P3 | ◻ | vanilla mending廃止が別fork任せ・未検証。`extra-source-cost=0`の無限修繕loophole |
| U10 | P2 | ◻ | repair無効化がelite itemのrename/combineまで殺す。repairのみ対象にすべき |

### Pets
| ID | P | 状態 | 論点 |
|----|---|---|------|
| Pt1 | P3 | ◻ | **後回し確認（2026-08-28）**: ペットは現状維持（レベル刻印のみ）。連携・ヘイト代替は作らない |

---

## 3. 進め方（ラウンド）

- **R0 ✅**: 稼働範囲＋最小コアループ確定（LD-6）。
- **R1（進行中）**: 戦闘パイプラインの核。
  - **① combat level の構成 ✅ → LD-7**（C2/Pr1/Pr4 を◐へ前進）。
  - **①-b 魔法柱スカラーの源 ✅ 決定=A（使い込み軸）**: 魔法柱 = **カスタムValhalla `ARS_MAGIC` スキルのレベル**（実装予定・スキルツリー草案スライドに記載済み、`UNLOCK_SYSTEM_SPEC.md:158`/`combat-level.yml:20`と整合）。実装契約: (i)**bridgeがArsの詠唱/使用 → `ARS_MAGIC`へEXP付与**（EXP源の詳細は草案スライド/調整フェーズ）、(ii)combat-levelは既存 `ValhallaSkillLevelSource`(reflection)で `ARS_MAGIC` レベルを読む、(iii)魔法は単一スキルゆえ**柱内λは物理柱のみ**（再確認）、(iv)カーブは物理柱のmax到達速度に校正（不変条件）。⚠`getMaxMana`/`getManaRegenRate`は**使用禁止**（gear補正混入、MAGIC_BALANCE_SPEC §2違反）。却下: グリフ数比(広さ)=覚えただけで上がり使い込みを測れない。
  - 次: **C1**（プレイヤー被弾側の防御プロファイル源＝防具2スキルの行き先）→ C4（elite被弾の単一ダメージ所有者）→ C3/C11（物理level二重化の整理）→ Pr2（Valhalla不在フォールバック）。
- **R2**: コンテンツ/アイテム/アンロック（D5 / I1 / U1 …）。

> Top依存: combat level の実体（Pr1/Pr2）が決まらないと mobスケール・ダンジョンゲート・アイテム使用制限が宙に浮く。距離軸（LD-4）と合わせて R1 の起点にする。

---

## 4. 実装・仕様レビューで発覚した未決（2026-07-03）

赤チームレビュー（実装/仕様突き合わせ）で新たに発覚した論点。**答えは未決定**、論点のみ登録する。

1. プレイヤー自身のcombatレベルが与ダメに乗算される現実装（`DefaultDamageResolver`）の是非（仕様に根拠なし）
2. 魔法成分デフォルトダメージへのlevelスケール適用の是非（MAGIC §2①③との整合）
3. バインド発生タイミング（craft/drop/pickup/equip）とクラフト代行の可否 — 経済・分業構造を決める構造決定
4. `OWNER_BOUND`のowner UUID付与タイミング（未定義のため執行実装がブロック中）
5. ヘイトの適用範囲（EliteMobs限定かバニラmobのtarget差し替えも行うか）・減衰式・保持者ログアウト時挙動・チャンクアンロード揮発の可否
6. プレステージ直後の状態遷移（装備使用権・入場権・スロット超過スペルの挙動）ウォークスルー
7. 非プレイヤー詠唱（タレット/儀式類）のperk判定主体
8. ダンジョンのドロップ権貢献条件（Bedrock altファーム対策）
9. 修繕儀式の構造上限（回数上限/最大耐久逓減） vs 「耐久=有限リソース」記述の整合
10. typeless（分類不能）ダメージのDamageCause別割当カタログ（LD-12の素通し既定の範囲限定）
11. ワールド脅威（距離level）の次元補正・新規プレイヤー保護・パワーレベリング減衰
12. Bedrock非対称の許容範囲定義とM1/M2でのBedrock実機スモーク前倒し
13. form別CTのバースト抑制不全（form巡回で開幕バーストがform数倍）への二層CT等の対策
14. 解放維持アイテムのステperk保護によるスマーフィング（保護対象を解放系perkに限定するか）
15. 品質→抽選分布の正式変換表（破棄されたARS_SPECのdamage-bonus再マッピング規則が宙に浮いている）
16. スキルツリーロースターの一枚化（10職か15職か、combat算入・プレステージ対象の明確化）
17. ペット実装前のソロ向けヘイト肩代わり代替手段（LD-6の⚠、ローンチブロッカー）
