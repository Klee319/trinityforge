# クロスレビューバックログ HIGH+MEDIUM 全19件修正

**Task:** 7/22 8並列クロスレビューで確定したバックログのうち HIGH 6件 + MEDIUM 13件を修正（5並列implementer + orchestrator統合 + verifier敵対監査）。

## HIGH（ゲーム根幹破壊）
1. **armor-strength衝突** — `ComponentDamageCalculator` をCOMBAT_SPEC §2.1完全準拠に復元: 割合追加ダメ=step1（default×%、負値可・step7床で保護）、step2会心のクリ軽減流用を廃止、step6 flat=守備力+防具強度、step8固定ダメは両方を相殺。
2. **武器EXP無限farm** — 新規 `AttackerTargetCooldown`（per-(attacker,target) in-memory CD）。`skill-exp.yml combat.same-target-cooldown-seconds: 10`。矢/AoE/sweepの迂回なし（verifier確認済）。
3. **分解dupe** — fraction≤1.0クランプ（等価回収まで）。
4. **パリィ逆効果** — 攻撃側適用を削除し防御側（isBlocking時被ダメ軽減、上限95%）へ。verifier指摘F1により **EventPriority.HIGHEST**（CombatListenerのpipeline上書き後）に配置。
5. **coating三重計上** — 武器クラス一致キーのみ+汎用キー加算で+3準拠。verifier指摘F2によりalchemy.ymlノードEのdedicated値重複も削除（+20→+10）。
6. **死にdedicated効果** — `luck-silktouch-glyph-unlock`→`luck-glyph-unlock`(fortune)+`silktouch-glyph-unlock`(extract)に分割（fork ExtractAugment発見による）、`multi-debuff-glyph-unlock` target=hex確定。gate-index end-to-end配線確認済。

## MEDIUM
7. **完全無敵** — `cappedMitigation`がdefenseRateもcap（0.9）、`defense.max-dodge-chance: 0.9`新設+`DodgeResolver.capped`。
8. **point-ledgerレース** — 新規 `PlayerLockRegistry` を NativeProgressionService / AdminService(editUnderRepositoryLockを実ロック化) / NativePerkService.prestige（read→write全域）で共有。デッドロックなし（verifier確認）。
9. **壊れcurve** — reload時に代表レベルで事前評価・拒否+dispatcherのRuntimeException隔離リトライ（バッチ喪失防止）。
10. **軽装セット死にperk** — セット効果自体が未実装と判明→**回避率0.2**（旧Valhalla設計値）をC/D-1-1/D-1-2に分散実装+setamount増幅+PlayerStatAggregator経由で実回避判定に配線（buffs:/native:はdisjoint=二重計上なし）。「3部位」→「2部位」テキスト修正（軽重両方）。
11. **防具EXPパッシブfarm** — min-damage閾値(1.0)+per-attacker CD(10s)+0付与ガード。
12. **釣りトレジャー複製** — treasureMaterialsをfishing-bonus複製ロールから除外。
13. **fish-sell-toggle純損** — 下振れ変換削除しno-op化（通貨実装までの暫定、TODO明記）。
14. **mana-regen無上限** — fork ManaManager native regen経路にmaxPercentCap（external側はConfig構造が別物のため対象外=stale確認）。
15. **gacha天井** — CR-9安全弁②実装: `pity.threshold`（全6プール、15〜30回）、PDC per-pool永続カウンタ、rate-up併用時はレア判定を元プール固定（2プール版drawWithPity）、カウンタ保存は券消費成立後。
16. **Bind偽UUID** — getPlayerExact→getOfflinePlayerIfCachedのみ、捏造なし。
17. **LoreConfig張りぼて** — bind.use-requirement-*をLoreComposerへ配線（default=旧ハードコード文言で表示互換）。
18. **RoleBuffs無上限** — ±400(flat)/±1.0(%)/×2(crit_damage・damage_modifier)/hate[0,15]/exp-multiplier[1,10]。verifier指摘F3反映。

## 検証
- `./gradlew test`: **1129テスト全緑**（3回連続再現）
- verifier（フレッシュコンテキスト敵対監査）: ACCEPT-WITH-FIXES → F1/F2/F3修正済み。F4-F10は設計受容/低影響として記録（本レポート下記）。

## 残記録（LOW、未対応・受容）
- F4: 材料1個レシピのcraft→分解EXPループ（素材純増はなし）
- F5: curve検証はreloadのみ（初回起動ロードは隔離リトライ頼み）
- F6: ダメ比例防具EXPは閾値対象外（受容済み）
- F7: DB遅延時にplayerロック最長15s保持のlatencyリスク
- F8: lore.yml「必要Lv:」とBindLore default「使用可能レベル:」の文言不一致（既存）
- F10: 武器EXP CDはスキル非依存（保守側誤差）

## ビルド/配備（2026-07-22 21:58）
- TF `releaseAssembly`: TrinityForge-all.jar(15,269,626B) + thin jar 4フォークlibs同期
- ArsPaper fork再ビルド: ArsPaper-1.0.0.jar(934,055B)
- EliteMobs/DPSChecker fork: 新thin jarに対しコンパイル緑（ソース無変更=再配備不要）
- 配備: RCON save-all flush→stop→wal/shm消滅確認→DBバックアップ(`player_progression.db.20260722-2158.bak`)→旧jar退避(`backups/jars-20260722-2158/`)→TF+ArsPaper jar差替→config 10ファイル同期（旧版は`backups/config-20260722-2158/`、管理者ドリフトなし確認済）
- **サーバ再起動はユーザー側で未実施**
