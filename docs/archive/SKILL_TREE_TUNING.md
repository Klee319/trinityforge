# スキルツリー調整ワークシート（戦闘5職）

- **作成日**: 2026-06-29
- **位置付け**: ValhallaMMOバニラ戦闘スキルツリー（`VALHALLA_DEFAULT_SKILLS.md` の無改変転写）を、本サーバの相補トリニティへ合わせて**改変ベースで**調整するための作業台帳。
- **前提決定**: [[OPEN_DECISIONS]] LD-5（相補トリニティ）/ LD-7（combat level構成）。
- **方針**: ゼロから作らず、バニラの完成された構造を**保持→部分改変**する。下記ガードレールを満たす限りバニラ値はそのまま採用してよい。

---

## 0. 調整ガードレール（LD由来・これに反する改変は不可）

| # | ガードレール | 出典 |
|---|------------|------|
| G1 | **物理3職（軽/重/弓）はサイドグレード関係を維持**（軽=速い/Parry・重=貫通/パワー・弓=遠隔/ステルス）。一強を作らない | LD-7 横balance不変条件 |
| G2 | **防具2職(Light/Heavy Armor)はプレイヤー防御（C1）の供給源**。combat levelには算入しない | LD-7 |
| G3 | combat levelに使うのは武器3職のみ（物理柱 = best+λ）。**採取/生産/Powerは戦闘力に一切寄与させない** | LD-7 / Pr1 |
| G4 | バニラの `starting_perks`(初期マイナス)＋`leveling_perks`(毎レベル加算) で**既に物理威力がレベルスケール**している。TFのcombat-level乗算と**二重化させない**（どちらか一方に寄せる） | C3/C11 |
| G5 | combo（クロススキル）パークは breadth 報酬。柱内λと役割が重複しうるので**λとcomboの二重breadthを点検** | LD-7 |
| G6 | 魔法柱はArs由来スカラー。武器ツリー側で魔法を扱わない | LD-7 / C2 |

---

## 1. perk単位 判断フォーマット

各パークを下表の形で評価する。`原値` は `VALHALLA_DEFAULT_SKILLS.md` から転記。

| 列 | 意味 |
|---|---|
| Perk ID | バニラのperk id |
| 原値 | バニラ効果（無改変。転写から転記） |
| 採否 | `keep`（そのまま） / `tweak`（数値/効果改変） / `cut`（無効化） / `replace`（別効果へ） |
| 改変案 | tweak/replace時の新効果 |
| 理由 | どのガードレール/設計意図に基づくか（G1〜G6 や TRINITY/COMBAT仕様を参照） |
| 依存 | TFコード/configのどこに反映が要るか（combat-level.yml / stat辞書 / DefenseStats 等） |

### 記入例（雛形・確定値ではない）

| Perk ID | 原値 | 採否 | 改変案 | 理由 | 依存 |
|---|---|---|---|---|---|
| lightweapons_perk_1 (Page) | damagemultiplier +0.1, bleedchance +0.1 | keep | — | 物理柱の素の立ち上がり。G1のサイドグレード差を壊さない | — |
| lightarmor_perk_* | （防御stat群） | keep/route | combat levelには非算入、DefenseStatsへ写像 | G2 | C1のプレイヤー防御写像表 |
| heavyweapons_perk_ng2 | ng2が誤って `lightarmor_perk_ng2` をremove参照（原文バグ） | tweak | 正しい参照に修正 | 原文不整合の是正 | progression.yml |

---

## 2. 横断的に先に決めるべき論点（転写から抽出）

perk単位の前に、ツリー全体に効く設計トグル。ここが決まると個別判断が速い。

| # | 論点 | 選択肢 | 影響範囲 |
|---|------|--------|----------|
| Q1 | **NG+（転生）パーク**を本サーバで採用するか | 採用 / 削除 / 改変（恒久ボーナス量・EXPペナルティ調整） | 全5職の ng1/ng2。プレステージ軸の有無 |
| Q2 | **starting_perks のマイナス補正**を残すか（G4の二重化対策） | 残す＋TF乗算を抑制 / 撤去しTF側に一本化 | 全武器職の低レベル体験・C3/C11 |
| Q3 | **Parry / Coating / PowerAttack / Adrenaline 等のサブ機構**を本戦闘pipelineで活かすか | 全採用 / 一部cut / TF側stat化 | 軽武器=Parry/Coating、重武器=PowerAttack/Coating、弓=Charge/Stealth |
| Q4 | **combo のクロススキル前提**（例: 軽武器→HEAVY_ARMOR:70）を維持するか | 維持（breadth報酬） / 付け替え / cut（λに一本化） | G5。柱内λとの役割分担 |
| Q5 | **bleed / crit / penetration** などバニラ固有statを、TFの対称ダメージパイプライン（DefenseStats）にどうマッピングするか | 物理component内で処理 / 無視 / 新stat化 | I1/I2 stat辞書、SymmetricCombatService |
| Q6 | **防具職stat（Parry, damage reduction, 無敵時間, ポーション耐性）→ プレイヤーDefenseStats** の写像規則 | 1:1写像 / 係数変換 / 一部のみ | C1の中核 |

---

## 3. 作業順（推奨）

1. **Q1〜Q6（§2）を壁打ちで確定** ← ここが最大のレバー。
2. その結論をG1〜G6と照合し、**§1の表を5職×55パーク分**埋める（埋め作業自体はエージェントに委譲可能）。
3. 確定値を `combat-level.yml` ＋ Valhalla側 `*_progression.yml` のサーバ配置版へ反映。
4. 数値（λ・上限・カーブ・各係数）は最後に調整フェーズで詰める。

> 補足: §1の55行テーブルは、Q1〜Q6が決まれば機械的に展開できるため、現時点では空のまま。設計判断（§2）が先。
