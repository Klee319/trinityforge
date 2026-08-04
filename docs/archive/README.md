# docs/archive — 失効した設計文書（**正典ではない**）

ここにあるものは **もう仕様として参照してはいけない**。歴史的経緯・数式の由来をたどるためだけに残している。
現役の仕様は `docs/` 直下の `*_SPEC.md` と、**設定の一次情報**である `docs/config-reference/` と各 yml。
残タスク・既知バグは `reports/ACTIVE_RECORD.md`。

2026-08-04 の整理でここへ移した（旧パスは `docs/` 直下 or `docs/design/`）。

| ファイル | 失効した理由 |
|---|---|
| `IMPLEMENTATION_PLAN.md` | 着工前のマイルストーン計画。ValhallaMMO 無改造前提で書かれており、2026-07-22 の native 移行で前提ごと消えた。実装は完了済み |
| `VALHALLA_DEFAULT_SKILLS.md` | ValhallaMMO バニラツリーの転写。2026-07-22 に Valhalla 依存を撤廃したので上流参考情報としての価値しかない |
| `SKILL_TREE_TUNING.md` | 上記の転写を「改変ベースで」調整するための作業台帳。スキルツリーは 2026-07-26 に草案16ツリーを全面適用して置き換わった |
| `STAT_DICTIONARY_RECONCILIATION.md` | 「本書は決定しない」と自称する決定インプット。ステータス3分類は 2026-07-26 に確定・実装済み |
| `NATIVE_PROGRESSION_16_SKILL_MATRIX.md` | native 移行 Phase 1 の characterization。移行完了後は現状と対応しない |
| `NATIVE_PROGRESSION_WIRING_MATRIX.md` | 同じ移行の安定化スナップショット（2026-07-22 時点） |
| `ARS_MAGIC_SCALAR_SOURCES.md` | 調査のみの文書。ValhallaMMO ブリッジ前提 |
| `EDITOR_POWER_ELITEMOBS_PLAN.md` | 2026-07-21 の調査メモ＋次アクション。editor は以降何度も改修されている |
| `2026-07-23-stat-gate-overhaul.md` | 大改修の設計書。適用完了 |
| `2026-07-26-stat-scope-ux.md` | ステータス分類 UX の設計書。適用完了 |
| `2026-07-30-content-guidance-draft.md` | コンテンツ拡張の草案。確定版は `docs/design/2026-08-01-content-expansion-spec.md` |
| `GREENFIELD-REMAINING-TODO.md` | 2026-07-19 の未実装バックログ 11 件。**2026-08-04 に実コードで確認したら全件が実装済みか作り替え済み**だった（旧パスは `TrinityForge/docs/`。下に根拠） |

`GREENFIELD-REMAINING-TODO.md` を消した根拠（この種の台帳は放置すると「まだ残っている」と誤読されるので残す）:

- 実装済み — 武器コーティング → `WeaponCoatingListener`、ポーション統合 → `CraftingFeaturesConfig`
  の `potion-merge.tiers`、解体 → `DisassemblyListener`、圧縮木材修繕 → `WoodRepairListener`、
  オーバーエンチャント → `OverEnchantListener`、村人取引解放 → `VillagerTradeListener`（ゲートは
  `trade:<PROFESSION>` 方式に変わっており、台帳が書いていた `blacksmith-unlock` 等の id ではない）
- **前提ごと消滅** — 台帳が一次参照にしていた `dedicated-effects.yml` は 2026-07-23 に削除され、
  registry はスキル別 yml 16 本（`resources/skilltree/mining.yml` など）へ分割された。
  `food-compression` / `*-core-craft-unlock` / `craft-thread-unlock` / `*-potion-unlock` /
  `source-jar-link-unlock` は**出荷 yml にも Java にも 1 件もヒットしない**（= 効果 id 自体が現存しない）。
  再着手するなら台帳を復活させず、現在の 16 本の yml から作り直す。

`OPEN_DECISIONS.md` は **移していない**。LD-* が現行コード（`StatKeys` / `RoleBuffsConfig`）から参照されている
生きた決定台帳なので `docs/` 直下に残っている。
