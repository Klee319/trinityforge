# 予告機構 UX クロスレビュー（Codex / 2026-09-04）

`codex exec`（読み取り専用）で設計正本・実装仕様・現行 `MobAbilityExecutor` を読ませた第三者レビューの原文。
**反映状況は末尾の表が正**。原文の行番号はレビュー時点（2026-09-04 朝、第1波コミット前）のもの。

## 反映状況（2026-09-04）

| # | 指摘 | 扱い |
|---:|---|---|
| 1 | 予告経路が本番に配線されていない | **第2波で配線**（実行部統合）。出荷テンプレートへの `cast-seconds` 記入は闇の大聖堂の割当表を作る段階で行う（数値と挙動の変更を切り分けるため） |
| 2 | 直線の見た目と判定幅の不一致・足元一点判定 | **採用**: 判定は BoundingBox 中心、描画は ±thickness の左右 2 レール |
| 3 | 輪郭だけ・5段バーの可読性は未保証 | **一部採用**: 固定地点の中心点滅、残り秒の数値併記、粒子は対象プレイヤーへ個別送信。**実機（統合版・暗所・第一人称）検証は未実施＝出荷前の必須項目** |
| 4 | 5色だけでは行動が分からない・yml の色誤割当 | **採用（縮小）**: 型→行動語（離れろ／床から退け／横へ／遮蔽へ／止めろ）をアクションバー先頭へ。`response:` キーは新設せず型から導出。色は型規約で一括付け直し済み（bull_rush=charge は赤のまま＝「敵中心」規約に従う。arrow_fan は紫） |
| 5 | アクションバーの所有権・情報消失 | **一部採用**: 予告終了時に空表示を送って残像を消す。EXP は捨てる（合算表示は見送り。既定は BossBar なので actionbar 設定時だけの話） |
| 6 | 2.6 b/s の机上式が遅延を扱えない | **見送り（設計側の課題）**: 実機で D95 を測ってから式を置き換える。今は据え置き |
| 7 | 毎 tick の向き固定はガクつく | **採用（幾何）**: CastSnapshot で描画と解決を固定。向きの書き戻しは残し、**実機で見た目を確認して不快なら外す** |
| 8 | 全技共通の LOS 不発は正解を壊す | **採用**: 型別。狙う技（beam/転移/投射）だけ主対象 LOS を見る。自分中心と床印は見ない |
| 9 | 予告と着弾の音・粒子が未分離 | **採用**: 予告フレーム無音、開始音 1 回、着弾 5tick 前の合図音、着弾で従来演出 |
| 10 | 予算が傍観者に効かない・`+1` に潰れる | **一部採用**: 脅威圏内の他プレイヤーにもベスト・エフォートで予約。原子的な全員予約は見送り |
| 11 | 半径 32 の告知が無関係者へ | **採用**: 受信者＝主対象＋脅威圏内＋anchor 水平 12m |
| 12 | 空振り硬直・選択リズム | **見送り（後段の機構）** |
| 13 | 例外・退出時の掃除が未保証 | **採用**: 一度だけ走る終端処理に集約、例外はレート制限ログ |

---

## Findings

1. **[高（Critical）] 現行の実行経路には予告フェーズが接続されていない** — [MobAbilityExecutor.java:135](../../TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityExecutor.java:135)、[mob-abilities.yml:67](../../TrinityForge/src/main/resources/combat/mob-abilities.yml:67)

   - Impact: 通常技は「技名表示→粒子塊→即時解決」のままで、予告形状・予算・回避判定を実プレイ検証できない。`delayed_zone` だけが旧式の予告を持つ。
   - Evidence: `execute()` は `announce()` と `playEffects()` の直後に型別処理へ入り、設定ファイルも第1波では `cast-seconds` を書かないと明記している。起動配線にも新しい予告部品が渡されていない。
   - Fix: 闇の大聖堂で使う全攻撃について `cast-seconds`、`lethal`、後述の `response` を明示し、`PREPARE → RESOLVE/CANCEL → CLEANUP` の統合経路を必須にする。未設定の危険技は即時発動へ黙ってフォールバックさせない。
   - 変更箇所: 実装仕様 [10行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:10)、[81行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:81)、[315行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:315)に「本番配線と統合テストを通るまで機構完成としない」を追加。

2. **[高（Critical）] 直線の見た目が当たり判定幅を示さず、足元一点判定も実際の身体と一致しない** — [実装仕様:53](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:53)、[実装仕様:158](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:158)、[MobAbilityExecutor.java:319](../../TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityExecutor.java:319)

   - Impact: プレイヤーは「中心線から何ブロック横へ動けば安全か」を読めない。さらに目線付近を通る光線と足元一点の3D距離判定では、見た目上は身体を貫いているのに外れる、または逆の不一致が起こり得る。
   - Evidence: 仕様は `thickness` を判定半径とする一方、描画は1m刻みの中心点だけ。対象座標は `player.getLocation()`、現行ビームは目から目へ照準している。
   - Fix: 判定は線分対プレイヤーBoundingBox、または膨張AABBとの交差にする。描画は中心線ではなく `±thickness` の左右2本の境界レールを主表示にし、必要なら疎な中心線を補助表示する。
   - 変更箇所: 設計正本 [83行](../../docs/design/2026-08-31-dungeon-concept-rework.md:83)、[304行](../../docs/design/2026-08-31-dungeon-concept-rework.md:304)、実装仕様 [42行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:42)、[65行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:65)を変更。

3. **[高（Critical）] 「輪郭だけ・1m刻み・5段バー」でJava版と統合版の可読性が保証されたとは言えない** — [設計正本:291](../../docs/design/2026-08-31-dungeon-concept-rework.md:291)、[設計正本:719](../../docs/design/2026-08-31-dungeon-concept-rework.md:719)、[実装仕様:155](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:155)

   - Impact: 第一人称で足元中央にいると円周が画面外になる。暗所・水中・プレイヤーや他エフェクトの密集では細い輪郭が埋もれる。固定地点は設計正本にあった「点滅する中心」が実装仕様で失われている。
   - Evidence: GeyserはJavaのComponentをBedrockのACTIONBARへ転送するが、文字幅やUIスケールに合わせた整形はしない。[Geyser action-bar translator](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/translator/protocol/java/title/JavaSetActionBarTextTranslator.java)。Bedrock粒子はクライアント側描画であり、低性能端末を含む実機検証が必要。[Bedrock particle documentation](https://learn.microsoft.com/en-us/minecraft/creator/documents/particleeffects?view=minecraft-bedrock-stable)
   - Fix: 固定地点には明滅中心＋円周上の短い縦線を戻す。粒子は対象プレイヤーだけへ送信し、近側の円弧を強調する。アクションバーは `横へ｜貫通光 1.3` のように数値時間を併記し、5段バーはBedrock向けにASCII `[###--]` フォールバックを用意する。
   - 変更箇所: 設計正本 [85行](../../docs/design/2026-08-31-dungeon-concept-rework.md:85)、[304行](../../docs/design/2026-08-31-dungeon-concept-rework.md:304)、[719行](../../docs/design/2026-08-31-dungeon-concept-rework.md:719)、実装仕様 [155行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:155)、[212行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:212)を変更。「Bedrockで1回」ではなく対象端末・低粒子設定・最大射程ごとの反復試験を受入条件にする。

4. **[高（Critical）] 5色は単独では直感的でなく、YAMLにも規約違反がある** — [設計正本:98](../../docs/design/2026-08-31-dungeon-concept-rework.md:98)、[mob-abilities.yml:197](../../TrinityForge/src/main/resources/combat/mob-abilities.yml:197)、[mob-abilities.yml:232](../../TrinityForge/src/main/resources/combat/mob-abilities.yml:232)

   - Impact: 赤「敵から離れる」と金「床から離れる」は色だけでは基準点が分からない。緑は一般に安全・回復にも使われ、赤緑系の識別困難にも弱い。技名だけでは初見プレイヤーが取るべき操作を判断できない。
   - Evidence: 設計は「色＋形＋文章」を要求するが、実装仕様の文章は技名のみ。`arrow_fan` は固定地点系なのに紫、`bull_rush` と [void_lunge:451](../../TrinityForge/src/main/resources/combat/mob-abilities.yml:451) は突進線なのに赤。
   - Fix: 色と表示名から独立した `response: OUT / LEAVE_GROUND / SIDESTEP / COVER / INTERRUPT` を設定に追加し、アクションバー先頭へ「外へ」「床から離脱」「横へ」「遮蔽へ」「中断」を必ず表示する。色は補助情報に降格する。
   - 変更箇所: 設計正本 [98行](../../docs/design/2026-08-31-dungeon-concept-rework.md:98)、[111行](../../docs/design/2026-08-31-dungeon-concept-rework.md:111)、実装仕様 [212行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:212)とYAMLスキーマを変更。

5. **[高（Critical）] 0.25秒更新そのものより、アクションバーの所有権と情報消失が問題** — [実装仕様:187](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:187)、[実装仕様:216](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:216)、[MobAbilityExecutor.java:527](../../TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityExecutor.java:527)

   - Impact: 1.5～2秒間の4Hz更新は許容範囲だが、EXPや装備警告などが消失・上書きすると「入力したのに反応しない」「報酬が入らなかった」感が出る。
   - Evidence: 仕様は40箇所超の直接送信を把握しつつ後回しにしている。現行 `announce()` も直接送信。EXPの既定はBossBarなので通常設定では衝突しないが、actionbar設定時は破棄される。部分実装の `telegraphEnd()` も最終表示を消さない。
   - Fix: 闇の大聖堂前に、戦闘中に発生し得る高頻度メッセージだけでも単一Routerへ移す。EXPはイベントごとに再生せず、詠唱終了後に合算して1回表示するか、詠唱中だけBossBarへ逃がす。予告終了時は次候補を即再計算し、なければ空表示を送る。
   - 変更箇所: 実装仕様 [194行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:194)、[207行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:207)、[223行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:223)を変更。

6. **[高（Critical）] `4.317 × 0.6 = 2.6b/s` はping・反応・tick遅延を扱えない** — [設計正本:176](../../docs/design/2026-08-31-dungeon-concept-rework.md:176)、[設計正本:681](../../docs/design/2026-08-31-dungeon-concept-rework.md:681)

   - Impact: 1.5秒技ほど、固定的な通知・認知・入力・通信遅延が移動可能時間の大半を占める。盾使用中、鈍足、壁際、Bedrock変換経由では机上半径が過大になり得る。逆にスプリントやジャンプを基準化すると初心者を前提から外す。
   - Evidence: 現式は遅延を速度への比例係数に変換しており、詠唱時間が短くても長くても同率で扱う。
   - Fix: `許容半径 ≤ 状態別速度 × max(0, 詠唱時間 − D95) − 安全余白` とする。`D95` はサーバー詠唱開始から最初の権威的な移動tickまでを実測し、通知配送・人間反応・入力返送・tick待ちをまとめて含める。通常歩行、スプリント、ジャンプ、盾/使用中、鈍足を別プロファイルにする。
   - 変更箇所: 設計正本 [176行](../../docs/design/2026-08-31-dungeon-concept-rework.md:176)～193の式を置換し、[684行](../../docs/design/2026-08-31-dungeon-concept-rework.md:684)の18/20判定をクライアント・ping・移動状態別に行う。

7. **[高（Critical）] 毎tickのyaw/pitch書き戻しはガクつきを作り、teleport代替はさらに悪い** — [実装仕様:103](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:103)

   - Impact: AIが向きを更新し、プラグインが戻す往復でJava版は頭・胴体が震え、Geyserでは補間を挟んだ揺れとして見える可能性が高い。ノックバック等で術者が移動すれば、方向だけ固定しても予告線自体がずれる。
   - Evidence: Paperの`setRotation`はAIに上書きされ得ると明記されている。[Paper 1.21.11 Entity Javadoc](https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Entity.html)。GeyserはJavaの位置・回転更新をBedrockの移動差分へ変換する。[Geyser movement translator](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/translator/protocol/java/entity/JavaMoveEntityPosRotTranslator.java)
   - Fix: 開始時に不変の `CastSnapshot(origin, direction, endpoint)` を作り、描画と解決の双方で使う。見た目は開始時に一度だけ `Mob#lookAt`、静止技のみナビゲーション停止を使う。同位置teleportによる固定は絶対移動・再同期になるため採用しない。
   - 変更箇所: 実装仕様 [103行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:103)～110をCastSnapshot方式へ変更。

8. **[高（Critical）] 全技共通の「主対象LOS切れで不発」は、色が示す正解を壊す** — [実装仕様:113](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:113)、[設計正本:92](../../docs/design/2026-08-31-dungeon-concept-rework.md:92)

   - Impact: 金の固定地点攻撃に対して「床から出る」以外に「柱へ隠れて詠唱を消す」という第2解が生まれる。自己中心AoEも主対象だけが隠れると他プレイヤーごと消え得る。
   - Evidence: 設計は固定地点を単一回答にするが、実装仕様は技種を問わず主対象のLOSを終了時に再検査する。
   - Fix: 固定地点は配置後に対象LOSを見ない。自己中心AoEは術者生存のみ。直線はスナップショット射線をブロックで切る。引寄せ・吹飛ばしは被害者ごとにLOS判定する。
   - 変更箇所: 設計正本 [29行](../../docs/design/2026-08-31-dungeon-concept-rework.md:29)、[92行](../../docs/design/2026-08-31-dungeon-concept-rework.md:92)、実装仕様 [113行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:113)に型別取消表を追加。

9. **[高（Critical）] 予告と着弾の粒子・音が実データ上で分離されていない** — [MobAbilityExecutor.java:426](../../TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityExecutor.java:426)、[MobAbilityExecutor.java:538](../../TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityExecutor.java:538)、[実装仕様:165](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:165)

   - Impact: `delayed_zone` は5tickごと、最大4回/秒で同じ爆発音等を鳴らし、着弾でも同じ音を出す。警報疲れと「もう当たったのか、まだ予告中か」の誤認を招く。
   - Evidence: `playEffects()` が粒子と音を常に同時再生し、予告ループと着弾の双方から呼ばれる。実装仕様も粒子種類は現行流用としている。Geyserでは例えばJavaの爆発粒子がBedrockの大きな爆発表現へ変換されるため、輪郭点への流用は危険。[Geyser particle mappings](https://github.com/GeyserMC/mappings/blob/master/particles.json)
   - Fix: `cast-start-sound`、着弾0.25秒前の共通ping、`impact-sound` を分離。予告フレームは原則無音にし、音は対象プレイヤーへHOSTILEカテゴリで送る。粒子もクライアント横断で確認した予告用allowlistと着弾用を分ける。
   - 変更箇所: 設計正本 [318行](../../docs/design/2026-08-31-dungeon-concept-rework.md:318)、実装仕様 [162行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:162)～168、YAML共通キー節を変更。

10. **[高（Critical）] 「プレイヤーごとの致命1・合計2」が傍観者には適用されず、二重予告も`+1`に潰れる** — [設計正本:608](../../docs/design/2026-08-31-dungeon-concept-rework.md:608)、[実装仕様:238](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:238)、[実装仕様:209](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:209)

   - Impact: 4人戦で別々の主対象へ撃たれたAoEが同じ傍観者へ重なり、実際には合計2を超える。しかもアクションバーは一方を`+1`としか示さず、異なる回避行動を伝えない。
   - Evidence: 実装仕様は主対象の枠だけ予約し、傍観者超過を明示的に許容している。
   - Fix: 詠唱開始時に形状と反応余白から `threatenedPlayers` を求め、全員分を原子的に予約する。闇の大聖堂では異なる`response`の同時予告を禁止し、原則合計1から開始する。同時2は、同じ回避方向か実機試験を通った組合せだけ許可する。
   - 変更箇所: 設計正本 [610行](../../docs/design/2026-08-31-dungeon-concept-rework.md:610)～619、実装仕様 [246行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:246)～266を変更。

11. **[中（Major）] 半径32のannounceが無関係なプレイヤーにも警報を出す** — [MobAbilityExecutor.java:41](../../TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityExecutor.java:41)、[MobAbilityExecutor.java:513](../../TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityExecutor.java:513)

   - Impact: 別部屋や上下階のプレイヤーに偽警報が出る。頻発するとアクションバー自体を信用しなくなる。
   - Evidence: 距離球ではなく32×32×32のAABB探索なので、水平対角で約45m、三次元対角で約55mまで含まれ、対象・形状・LOSを見ない。
   - Fix: `threatenedPlayers` と実際に支援判断が必要な近接味方だけを受信者にする。粒子も`World#spawnParticle`ではなくプレイヤー単位で送る。
   - 変更箇所: 実装仕様の機構4、[194行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:194)直後に「受信者選定」を新設。

12. **[中（Major）] 空振り硬直は報酬になるが、隠れた8秒制限と単純な予算抑制は読み合いを単調・不整合にする** — [設計正本:163](../../docs/design/2026-08-31-dungeon-concept-rework.md:163)

   - Impact: 同じように全員が避けても、ある時は2秒殴れて次は何も起きない。4人の集中攻撃では2秒硬直が強すぎる一方、予算で弾くだけのランダム選択は同じ紫・金の反復や長い無行動を生む。
   - Evidence: 設計正本には「2秒、8秒に1回」があるが、実装仕様には空振り判定の対象技、複数段攻撃の終了点、視覚フィードバック、スケジューラの反復抑制がない。
   - Fix: 高コミットの単発攻撃だけを対象にし、空振り時は毎回短い一定硬直を与えるか、硬直不能状態を見えるようにする。技選択は直近の`response`へ減衰重みを掛け、予算拒否回数と無行動時間を計測する。
   - 変更箇所: 設計正本 [163行](../../docs/design/2026-08-31-dungeon-concept-rework.md:163)～173を明確化し、実装仕様のデバッグアダプタ前に「機構7: 空振り回収と選択リズム」を追加。

13. **[中（Major）] 例外・退出・術者消滅時のUI／予算クリーンアップが証明されていない** — [MobAbilityExecutor.java:158](../../TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityExecutor.java:158)、[実装仕様:118](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:118)

   - Impact: 予告が画面に残る、予算枠が詰まる、技だけ無音で消える、といった「壊れた感」が残る。
   - Evidence: 現行`execute()`は`RuntimeException`を無記録で握り潰す。将来のtickタスク内例外は外側のcatchでは処理できず、仕様も正常終了時の解放しか規定していない。
   - Fix: 各詠唱に一度だけ実行される終端処理を設け、`finally`相当でタスク停止、全プレイヤーのUI解除、予算解放、粒子停止を行う。例外はレート制限付きで記録し、対象者には短い不発表示だけを送る。
   - 変更箇所: 実装仕様 [103行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:103)～120、[266行](../../docs/design/2026-09-02-telegraph-mechanics-spec.md:266)のテスト項目へ例外・退出・ワールド変更ケースを追加。

## Open Questions / Assumptions

- 使用予定のGeyserビルド、統合リソースパック、Bedrock側UIスケールと対象端末が未提示。現在の公式マッピングを前提に評価した。
- 闇の大聖堂の最終的な技割当、最大戦闘距離、柱・段差構成が未確定と仮定した。
- 作業ツリーには予告関連の未追跡・未配線クラスがあるため、現行挙動の評価はレビュー時点の起動配線を基準にした。
- EXP競合は既定のBossBar設定では起きず、`exp-display.mode: actionbar`を選んだサーバーで発生する。

## Change Summary

変更意図は「見て避ける戦闘」への転換だが、現状は形状・色・文章の三重化が成立していない。  
特に直線の表示と当たり判定、型別LOS、Bedrock実機可読性は仕様レベルで修正が必要。  
予告予算は主対象だけでなく、実際に危険へ入る全プレイヤーを保護すべき。  
アクションバー4Hz自体は許容できるが、UI・音・受信者の一元調停が前提になる。

## Verification

- 指定された4ファイルを全行確認し、起動配線、予告補助クラス、EXP表示設定も追加確認した。
- Paper 1.21.11 Javadoc、Geyserのアクションバー・移動・粒子変換、Bedrock公式粒子資料を照合した。
- `git diff --check` は成功。
- コード変更は行っていない。読み取り専用環境のためGradleテスト、lint、型検査、依存監査は未実行。
- Java／Bedrock実機、低性能端末、水中・暗所・4人戦でのプレイテストは未実施。この残余リスクだけでもリリース承認はできない。

## Decision

**Request changes**

### 最初の1ダンジョン「闇の大聖堂」を出す前に必ず直すべき上位5件

1. 直線をBoundingBox判定にし、実ヒット幅を左右境界線として描画する。
2. `response`を明示設定し、「横へ／床から離脱」など行動語を表示する。紫・金のYAML誤割当も修正する。
3. CastSnapshot方式へ変更し、毎tick回転固定と全技共通LOS取消を廃止する。
4. Bedrock実機で暗所・第一人称・群衆・低粒子設定を検証し、固定地点中心表示、ASCII／数値時間、予告専用粒子を確定する。
5. 全危険対象への予算予約、戦闘中アクションバー調停、段階別サウンドを統合したE2E経路を完成させる。