# ArsPaper fork — 引き継ぎ（fork作成セッション向け）

> 先に `../README.md`（共通前提・TrinityForge契約・PDCスキーマ）を読むこと。本書はArsPaper固有の手順とコンテキスト。

## ゴール
ArsPaper（自作Arsポート）を改造し、**魔法ダメージをTrinityForgeの対称パイプラインへ流す**＋**使用ゲート/form別CT/マナ改竄**を実装する。詳細は同フォルダ `ARSPAPER_FORK_SPEC.md`。

TrinityForge側は **M2受け口 `combatService.magicalFinalDamage(...)` が ready**。ArsPaperは「スペルの基礎ダメージ＋触媒ステを作って呼び、返ったダメージを適用する」だけで魔法が対称パイプラインに乗る。

## このフォルダの資産
- `ARSPAPER_FORK_SPEC.md` — 実装仕様（最重要）
- `ArsPaper-original.jar` — fork対象バイナリ（中身は `com.arspaper.**`）
- `lib/TrinityForge.jar` — compileOnly用API
- `specs/` — MAGIC_BALANCE / ARS_SPEC / COMBAT / ADDON / UNLOCK / SELECTION / IMPLEMENTATION_PLAN

## 着手前の必須確認（ブロッカー・重要）
1. **ビルド可能なArsPaperソースがリポジトリ内に無い**（自作ポートのため）。`ArsPaper-original.jar`（バイナリ）と decompile済み `decompile/extract_ars/com/arspaper/**` のみ。
   → **ユーザーにビルド可能ソースの所在を必ず確認**してから本格forkに入ること。
2. 署名確認は javap:
   ```bash
   JAVAP="/c/Program Files/Java/jdk-21/bin/javap.exe"
   "$JAVAP" -p -cp <repo>/decompile/extract_ars com.arspaper.<package>.<Class>
   ```
3. `ARS_SPEC.md`（specs/）に既に `getCastForm()`（form別CT用）や `ArsSpellCastEvent#setManaCost`（マナ消費前cancel/改竄）の前提が記録済み。**実jarにこれらが本当に在るかを javap で先に確認**すること。

## TrinityForgeへの委譲ポイント（ArsPaper側で繋ぐ箇所）
| ArsPaper内の対象 | 委譲先 / やること |
|---|---|
| 魔法ダメージ適用（SpellEffect/SpellContext） | スペルの基礎ダメージ算出後、`combatService.magicalFinalDamage(casterUuid, victim, spellBase, attackStats)` を呼び、**返り値を実ダメージとして適用**。`attackStats` は触媒ステ（会心/貫通/補正等）を `AttackStats` に積んだもの（無ければ `AttackStats.plain(0)`） |
| 増強/減衰グリフ（SpellAugment） | デフォルト魔法ダメージの内側に隔離（COMBAT §8 二重計上回避）。MAGIC_BALANCE §グリフ層分離 参照。最終的に `spellBase` に内包させてから `magicalFinalDamage` へ |
| form別CT（SpellForm/SpellCaster/SpellContext） | `getCastForm()` でform判定→form独立トラッカー。詠唱成立時起点、`ArsSpellCastEvent` でマナ消費前 cancel |
| マナ消費低下（Ars杖） | `ArsSpellCastEvent#setManaCost` で改竄（ARS_SPEC §2-4） |
| 使用ゲートα/β（ScribingTable/SpellCaster/GlyphConfig） | scribe自由・使用にperk必要。perk真実は `PlayerData.heldPerks()`（TrinityForge PDC）を参照 |
| ItemQuality + rollSeed（BaseCustomItem） | Ars装備生成時に `ItemAssembler.assemble(meta, rollSeed, quality)` を使い、TrinityForgeのPDC/写像に統一 |
| soulbind（SoulboundListener） | `ItemData.bindType()`/`owner()`（`BindType.SOULBOUND` 等） |
| recipe/ritual perkゲート、修繕儀式許可 | perk参照は `PlayerData`。数値はconfig外出し |

## 実装の進め方（推奨）
1. ArsPaperビルド可能ソースを確保（ユーザー確認）。TrinityForge.jar を compileOnly、`paper-plugin.yml` softdepend（README 1章）。
2. まず **魔法ダメージ供給**を `magicalFinalDamage` 接続（M2の核）。次に form別CT→マナ改竄→使用ゲート→item(quality/rollSeed/soulbind)。
3. ビルド→実機（Paper1.21.11＋TrinityForge）で詠唱→ダメージが対称パイプライン経由になるか確認。

## 検証観点
- 同一スペルでも装備tierで素の魔法ダメージが変わらない（gear非依存。COMBAT §2.2）。
- 三すくみ（物理/魔法/耐性）でmobの魔法耐性が効く（`MobData.defenseFor(MAGICAL)`）。
- form別CTが独立し、マナ消費前cancelで詠唱不発時にマナ/CTを消費しない。

## 注意 / 落とし穴
- TrinityForgeの `magicalFinalDamage` は **メインスレッド前提**（同期）。非同期spell処理から呼ぶ場合はメインへ戻す。
- クラスローダ分離: `join-classpath: true` 必須（README 1章）。
- グリフ増減は「デフォルト魔法ダメージの内側」に隔離してから渡すこと（外側で足すとCOMBAT §8 の二重計上になる）。
