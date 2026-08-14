# スキルツリー正典spec（SKILL_TREE_SPEC）

- **作成日**: 2026-07-13
- **位置付け**: `skilltree/skilltree/スライド1〜17.PNG`（完成版スキルツリー草案）の**構造化転写＋正典化**。設計の「絵」と config/コード（`combat-level.yml` / `roll.yml` / unlock / ValhallaMMO設定）の橋渡し。
- **正典方針**: スライドが正典。本書はスライドを忠実に写し、実装に必要な分類（combat算入 / stat写像 / グリフ解放 / ValhallaMMOネイティブ機構踏襲）を付す。
- **関連**: `OPEN_DECISIONS.md`（LD-7/LD-8/U1/U4/item16）、`COMBAT_SYSTEM_SPEC.md`、`STAT_DICTIONARY_RECONCILIATION.md`、`VALHALLA_DEFAULT_SKILLS.md`。
- **⚠ 暗黙知マーカー**: スライドはユーザーの暗黙知（ValhallaMMO既存機能の踏襲）を含む。本書で確証の無い箇所は **【要確認 Q-n】** を付し、末尾§6に集約する。

---

## 0. ロースター確定（item16 解決）

スキルツリー = **15種**（索引スライド1・備考スライド17を除く）。**combat level 算入は LD-7 準拠で戦闘6種のうち物理3＋魔法1**。

| # | ツリー | スライド | 分類 | combat level | ValhallaMMO |
|---|---|---|---|---|---|
| 1 | 軽量武器 | 12 | 戦闘・攻撃 | **物理柱** best候補 | ネイティブ(LightWeapons) |
| 2 | 重量武器 | 13 | 戦闘・攻撃 | **物理柱** best候補 | ネイティブ(HeavyWeapons) |
| 3 | 弓術 | 14 | 戦闘・攻撃 | **物理柱** best候補 | ネイティブ(Archery) |
| 4 | Ars魔法 | 10 | 戦闘・魔法 | **魔法柱**（単一スカラー） | **カスタム(ARS_MAGIC)** 【要確認 Q1】 |
| 5 | 軽装備 | 15 | 戦闘・防御 | **非算入**→C1(LD-8) | ネイティブ(LightArmor) |
| 6 | 重装備 | 16 | 戦闘・防御 | **非算入**→C1(LD-8) | ネイティブ(HeavyArmor) |
| 7 | 採掘 | 2 | 生産 | 非算入 | ネイティブ(Mining) |
| 8 | 伐採 | 3 | 生産 | 非算入 | ネイティブ(Woodcutting) |
| 9 | 農業 | 4 | 生産（作物＋畜産） | 非算入 | ネイティブ(Farming) |
| 10 | エンチャント | 5 | 生産 | 非算入 | ネイティブ(Enchanting) |
| 11 | 切削 | 6 | 生産 | 非算入 | ネイティブ(Digging) |
| 12 | 鍛冶 | 7 | 生産 | 非算入 | ネイティブ(Smithing) |
| 13 | 錬金 | 8 | 生産 | 非算入 | ネイティブ(Alchemy) |
| 14 | 釣り | 9 | 生産 | 非算入 | ネイティブ(Fishing) |
| 15 | Ars鍛冶 | 11 | 生産（魔法クラフト） | 非算入 | **カスタム** 【要確認 Q1】 |

> ✅ **Power除外**（Pr1）: スライドにPowerツリーは無い。転写と整合（Powerは他スキルのメタ集計＝combat二重計上のため除外が正）。

---

## 1. 横断機構（全ツリー共通）

| 機構 | 内容 | 実装上の含意 |
|---|---|---|
| **ギリシャ文字路線の排他** | 「ギリシャ文字路線はいずれかのみ解放可能」。ノードの α/β/γ 分岐は **1系統のみ**取得可 | ValhallaMMOの排他分岐機能で表現できるか要確認【要確認 Q2】 |
| **グリフ解放 = 使用条件** | グリフ（Ars Nouveau）はいつでも解放可能だが、**使用条件としてスキルノード解放が必要**（備考17） | **U1/U4 の橋**。ノード→グリフ対応が本書§2/§3で確定 |
| **プレステージ** | 各ツリーに「プレステージ I」。プレステージしてもグリフ素材は再取得不要。プレステージ時に**スキル引き継ぎアイテム**を追加（備考17） | プレステージ perk は永続ボーナス。U7の保持アイテムと接続 |
| **ゴール量産・質は非明示** | ゴールを明示的に増やしコンテンツ量を担保。質（優位）は明示しない（備考17） | バランス設計の指針 |
| **修繕廃止** | 修繕は消す（備考17、DESIGN方針と整合） | 耐久＝有限リソース。鍛冶/錬金の耐久回復系が調整弁 |
| **スレッド** | 特殊効果系のみクラフト、残りは厳選要素（備考17） | Ars鍛冶/鍛冶のスレッド欄と接続【要確認 Q4】 |

### 参照される独自アイテム/システム（本書範囲外・別spec要）【要確認 Q4】
- **ガチャ券 1〜5**（切削・釣りで入手）
- **各種コア**: ウッドコア（伐採E）/ ジュエリーコア（採掘E）/ ベジタブルコア（農業E）/ ミートコア（農業E, A-4前提）
- **スレッド / スレッド欄**（鍛冶・Ars鍛冶）
- **グリフ**（Ars Nouveau。解放はノード紐付け＝§2/§3）

---

## 2. 戦闘ツリー詳細転写（実装critical）

表記: ベース段=A〜E（横列）。子ノード=`X-n` / 排他路線=`X-α/β/γ-n`。**ステ効果はTFステ辞書（COMBAT §3）へ写像**。

### 2.1 軽量武器（物理柱・スライド12）
| ノード | 効果 | TFステ / 機構 |
|---|---|---|
| A | ダメージ増加・一定確率で**出血**付与 | attack_power↑ / 出血【要確認 Q3】 |
| B | 攻撃速度・会心率増加 | attack_speed↑ / crit-chance↑ |
| C | **受け流し**解放・会心ダメージ増加・出血確率UP | Parry機構 / crit-damage↑ / 出血 |
| D | 攻撃速度・出血量増加 | attack_speed↑ / 出血 |
| E | クリティカル（ジャンプ斬り）で確定出血付与・受け流し強化 | ジャンプクリ(C12)＋出血＋Parry |
| C-1-1 / C-1-2 | ノックバック距離増加 | KB距離（vanilla機構）【要確認 Q3】 |
| D-1-1 / D-1-2 | 攻撃距離増加 | attack reach（Valhalla attackreachbonus） |
| ★-α-1 | ダメージ増加 | attack_power↑ |
| ★-β-1 | 会心率増加 | crit-chance↑ |
| ★-γ-1 | 会心ダメージ増加 | crit-damage↑ |
| プレステージ I | ダメージ増加・会心率増加・会心ダメージ増加（永続） | — |

### 2.2 重量武器（物理柱・スライド13）
| ノード | 効果 | TFステ / 機構 |
|---|---|---|
| A | ダメージ増加・**チャージアタック**で一定確率で**スタン** | attack_power↑ / Charge＋Stun【要確認 Q3】 |
| B | 攻撃速度・貫通率増加 | attack_speed↑ / penetration↑ |
| C | スタン確率UP・**固定ダメージ**増加 | Stun / fixedDamage↑（roll源の穴を埋める＝I2補足） |
| D | 攻撃速度・スタン時間増加 | attack_speed↑ / Stun時間 |
| E | スタン確率UP・チャージアタックのダメージ増加 | Stun / Charge威力 |
| C-1-1 / C-1-2 | ノックバック距離増加 | KB距離 |
| D-1-1 / D-1-2 | 攻撃範囲増加 | attack範囲（sweep/AoE）【要確認 Q3】 |
| ★-α-1 | ダメージ増加 | attack_power↑ |
| ★-β-1 | 貫通率増加 | penetration↑ |
| ★-γ-1 | 固定ダメージ増加 | fixedDamage↑ |
| プレステージ I | ダメージ・貫通率・固定ダメージ増加（永続） | — |

### 2.3 弓術（物理柱・スライド14）
| ノード | 効果 | TFステ / 機構 |
|---|---|---|
| A | ダメージ増加・射撃精度アップ・**石の矢**レシピ解放 | attack_power↑ / accuracy / craft解放 |
| B | 射撃距離ボーナス・一定確率で**ホーミング**・銅/金の矢レシピ | 射距離 / homing【要確認 Q3】 / craft |
| C | 射撃精度アップ（バニラ同等）・射撃速度アップ・鉄の矢レシピ | accuracy / 速度 / craft |
| D | チャージ速度短縮・ダメージ増加・ダイヤの矢レシピ | charge / attack_power↑ / craft |
| E | 2弾チャージで確定ホーミング・節約率増加・ネザライトの矢／**防御無視矢**レシピ | homing / 節約 / craft |
| C-1-1 / C-1-2 | ノックバック距離増加 | KB距離 |
| D-1-1 / D-1-2 | 矢の貫通レベル増加・節約率増加 | penetration↑ / 節約 |
| ★-α-1 | ダメージ増加 | attack_power↑ |
| ★-β-1 | 会心率増加 | crit-chance↑ |
| ★-γ-1 | 会心ダメージ増加 | crit-damage↑ |
| プレステージ I | ダメージ・命中精度・節約率増加（永続） | — |
| 補足 | 弓の命中率初期値を「バニラより少し低い」に引き上げ | 命中の基準調整【要確認 Q3】 |

### 2.4 軽装備（防御・C1/LD-8・スライド15）
| ノード | 効果 | TFステ / 機構 |
|---|---|---|
| A | 移動速度ペナルティ減少・空腹になりにくい | move_speed / 満腹 |
| B | セット効果量UP・**3部位**でセット成立 | セット機構【要確認 Q3】 |
| C | **回避率**アップ・守備力増加・**アドレナリン**発動可能 | dodge / flat-defense↑ / Adrenaline |
| D | 防御率・物理耐性UP・**2部位**でセット成立 | defense-rate↑ / phys-resistance↑ |
| E | 被ダメージ軽減上昇・アドレナリン発動時バフ強化 | damage-reduction↑ / Adrenaline |
| C-1-1 / C-1-2 | 魔法耐性増加 | magic-resistance↑ |
| D-1-1 / D-1-2 | 回避率増加 | dodge↑【要確認 Q3】 |
| ★-α-1 | 守備力増加 | flat-defense↑ |
| ★-β-1 | 防御率増加 | defense-rate↑ |
| ★-γ-1 | 被ダメージ軽減増加 | damage-reduction↑ |
| プレステージ I | **守備力・防御率・被ダメージ軽減 の永続増加**（Q5=スライドのテンプレ流用ミスと確定し読替済み） | flat-defense/defense-rate/damage-reduction↑ |

### 2.5 重装備（防御・C1/LD-8・スライド16）
| ノード | 効果 | TFステ / 機構 |
|---|---|---|
| A | 移動速度ペナルティ減少・空腹になりにくい | move_speed / 満腹 |
| B | セット効果量UP・3部位でセット成立 | セット機構 |
| C | ノックバック耐性アップ・守備力増加・**憤怒(Rage)**発動可能 | knockback-resistance↑ / flat-defense↑ / Rage |
| D | 防御率・物理耐性UP・2部位でセット成立 | defense-rate↑ / phys-resistance↑ |
| E | 被ダメージ軽減上昇・憤怒発動時バフ強化 | damage-reduction↑ / Rage |
| C-1-1 / C-1-2 | 魔法耐性増加 | magic-resistance↑ |
| D-1-1 / D-1-2 | ノックバック耐性増加 | knockback-resistance↑ |
| ★-α-1 | 守備力増加 | flat-defense↑ |
| ★-β-1 | 防御率増加 | defense-rate↑ |
| ★-γ-1 | 被ダメージ軽減増加 | damage-reduction↑ |
| プレステージ I | **守備力・防御率・被ダメージ軽減 の永続増加**（Q5確定・読替済。重装らしくKB耐性追加も可） | flat-defense/defense-rate/damage-reduction↑ |

### 2.6 Ars魔法（魔法柱・スライド10）
排他路線なし＝**単一スカラー進行**（LD-7①-bの「魔法柱は柱内λ無し」と整合）。ステはマナ/Tier/グリフ配置。**威力は持たない**（C2: 魔法ダメージはcombat-level curveをbypass）。

| ノード | 効果 |
|---|---|
| A | マナ最大値+50・発動時バニラEXP少量・利用可能Tier+1 |
| B | マナ回復量+10%・グリフ配置数+1 |
| C | マナ回復量+10%・マナ最大値+50 |
| D | グリフ配置数+1・発動時バニラEXP獲得 |
| E | 利用可能Tier+1・グリフ配置数+1 |
| A-1-1 騎馬召喚 / A-1-2 滑空・疾風 / A-1-3 指向 / A-1-4 瞬間移動 | 各グリフ解放 |
| A-2-1 仮想ブロック・設置 / A-2-2 スケール | グリフ解放 |
| B-1-1 焦熱 / B-1-2 雷撃 / B-2-1 凍裂 / B-2-2 拘束 / B-3-1 烈風 / B-3-2 引寄 | 各グリフ解放 |
| B-3 | 害悪が強力に |
| C-1-1 残留・伝播 / C-2-1 炸裂 | グリフ解放 |
| C-4-1 狼召喚・生命化 / C-4-2 不死召喚 / C-4-3 囮・妖精召喚 | 召喚グリフ解放 |
| E-1 | 超増強系グリフ解放・照射グリフ解放 |
| プレステージ I | 利用可能Tier永続+1・マナ回復速度永続+10%・マナ最大値+50・グリフ配置数永続+1 |

> **U1/U4 直結**: 上記グリフ解放ノードが「perk→heldPerks→グリフ使用ゲート」のマスタ表になる。

---

## 3. 生産ツリー要約転写（グリフ解放・アイテム参照が主眼）

| ツリー | 主perk（ベースA〜E＋代表子） | グリフ/アイテム解放 |
|---|---|---|
| **採掘**(2) | 高速破壊I-III(採掘速度)・精密破壊(ドロップ+15/20/25%)・鉱脈破壊・黄金の国(幸運+1)・自動化 | 光源/採掘グリフ・ジュエリーコアのクラフト |
| **伐採**(3) | 効率破壊(一括-3s)・精密製材(+1)・リンゴ生活/金/クリスタル・木一括破壊 | 伐採グリフ・ウッドコア |
| **農業**(4) | 作物: 健康食/完全食(満腹)・範囲収穫。畜産: 鶏/牧場/養蜂/屠殺(与ダメ4倍) | 満腹/収穫/促進グリフ・ベジタブルコア・ミートコア(A-4前提) |
| **エンチャント**(5) | エンチャントポイント・オーバーエンチャント(レベル+1/+2)・EXPジェネレーター(EXP入手+) | 幸運・シルクタッチグリフ・司書取引解放 |
| **切削**(6) | 高速破壊・切削速度・考古学者(怪しいブロック復活・古代のがれき)・縁の下の力持ち(耐久消費でEXP+) | 粉砕・自動回収グリフ・ガチャ券1-3入手 |
| **鍛冶**(7) | クラフト品質+50・クラフト開放(石→ネザライト)・精錬速度/ボーナス・解体・ランダムステ品質(下振れ減/最大+1) | 精錬魔法・制作魔法解放 |
| **錬金**(8) | 品質・醸造速度・ポーション統合・武器コーティング・呪詛 | 注入/衰弱/交換/色変換グリフ・司祭解放 |
| **釣り**(9) | 同時ヒット・宝確率・スクラップ変換・海洋スレッド | 吸水・水源・回収グリフ・ガチャ券4-5 |
| **Ars鍛冶**(11) | ソースジェム一括・品質+1連鎖・素材返却・クラフトスレッド・儀式解放 | エンチャントI-III/召喚/天候/修繕・飛行の儀式・Waystone |

---

## 4. combat level への影響（LD-7 反映方針）

現行 `progression/combat-level.yml`（Q6=暫定修正済）は **LIGHT/HEAVY_WEAPONS・ARCHERY・ARS_MAGIC の等重み加重平均**（防具2種は除外済・ARS_MAGIC追加済）。残る乖離は「加重平均 vs LD-7の max-of-pillars」の構造差のみ：

| 項目 | LD-7（正） | 現実装（暫定修正後） | 残差分 |
|---|---|---|---|
| 物理柱 | best(軽量,重量,弓)+λ×残り | 3種を等重み平均に混ぜる | best/λ構造が未表現 |
| 魔法柱 | Ars魔法(ARS_MAGIC)スカラー | ✅追加済（平均の1項） | 単一柱化は未表現 |
| 防具2種 | **非算入**（C1へ） | ✅除外済 | なし |
| 合成 | max(物理柱, 魔法柱)×上限 | 加重平均 | モデル構造が別物（希薄化） |

→ **完全移行には `CombatLevelModel` のコード変更（max-of-pillars／best-of-within）が必要**。λ・上限は数値フェーズ。ARS_MAGICのruntime実在検証後（§6.1 Q6）。

---

## 5. stat辞書への影響（LD-8 / Q5 の確定材料）

スキルツリーが**実装前提で出した**新ステにより、`STAT_DICTIONARY_RECONCILIATION.md` の Q5/Q6候補が「切り捨て候補」から「実装必須」へ格上げ：

- **防御ステの供給源が確定**（LD-8/C1）: 守備力(flat-defense)・防御率(defense-rate。2026-08-15 に防具値 armor-defense-rate を廃止して統合)・被ダメージ軽減(damage-reduction)・魔法/物理耐性(resistance)・回避率(dodge) が防具ツリーperkとして実在。
- **固定ダメージ(fixedDamage)のroll源が判明**: 重量武器C/γ。従来「穴」だったフィールドに設計上の供給元がついた。
- **8step式にスロットが無い新機構**（要決着＝Q3）: 出血/スタン/受け流し/回避/アドレナリン/憤怒/攻撃距離・範囲/ホーミング。多くは**ValhallaMMOネイティブ機構**の踏襲と推測。

---

## 6. 要確認事項（暗黙知の確認・§0マーカー集約）

| Q | 論点 | なぜ必要か |
|---|---|---|
| **Q1** | Ars魔法/Ars鍛冶はValhallaMMOの**カスタムスキル機能**で作れる前提か、別アドオン/datapackが要るか。15ツリー全体はValhalla標準ツリーを**config再構成**する想定か | ARS_MAGICが実体化できないと魔法柱(combat-level)・グリフゲートの土台が立たない。実装方式の根幹 |
| **Q2** | ギリシャ文字路線の**排他（α/β/γいずれかのみ）**はValhalla標準の排他分岐で表現できるか、TF/fork側制御が要るか | ツリー実装方式が決まる |
| **Q3** | 新戦闘機構（出血/スタン/受け流し/回避/アドレナリン/憤怒/攻撃距離・範囲/ホーミング）は「**Valhallaネイティブ挙動をそのまま使う**」踏襲か、TFステに写像して対称パイプライン(LD-9単一所有)に載せるか。各機構ごとに (a)Valhalla任せ / (b)TFステ吸収 / (c)新規実装 | LD-9との整合。8step式にスロットが無いため決着必須 |
| **Q4** | ガチャ券/スレッド/各種コア（ウッド/ジュエリー/ベジタブル/ミート）は既に別途定義済みの独自システムか、本プロジェクトで新設か。参照先specはあるか | アイテムカタログ・クラフト系の実装範囲 |
| **Q5** | 軽装備・重装備の**プレステージ表記**が弓術と同一（ダメージ/命中精度/節約率増加）＝スライドのテンプレ流用ミスか、意図通りか | 防具プレステージの効果確定 |
| **Q6** | combat-level を LD-7（max-of-pillars＋best/λ）へ**今移行**するか、当面は暫定（防具除外・ARS_MAGIC追加の加重平均）に留めるか。ARS_MAGICの**runtime実在**が未検証な点も踏まえ | `CombatLevelModel` コード変更の要否・タイミング |

---

### 6.1 回答ログ（2026-07-13 壁打ち）
- **Q1** → ✅**調査完了**（Athlaeos/ValhallaMMO 公式ソース一次確認）。結論=**本体非改造で実現可能**。詳細§6.3。
- **Q3** → ✅**確定**（機構ごと）。§6.2 参照。damage関与の"Valhalla踏襲"機構は *仕様=Valhalla・数値はTF経由*（LD-9）で再構成。中身の暗黙知(アドレナリン/憤怒/セット/受け流し)は**Valhallaデフォルト踏襲**。
- **Q4** → ガチャ券/スレッド/各種コアは**本プロジェクトで新設**（後続タスク・別spec化）。
- **Q6** → **暫定修正のみ**。`combat-level.yml` を防具2除外＋ARS_MAGIC追加（加重平均のまま・コード変更なし）で反映済み。完全LD-7移行は後日。
- **Q5** → ✅**確定**。軽装備・重装備の**プレステージ I = 守備力・防御率・被ダメージ軽減 の永続増加**（テンプレ流用ミスを読替）。§2.4/§2.5反映済。
- **防御typing（LD-13, 2026-07-14）** → ✅**確定**。**耐性% のみ typed（物理/魔法別）／防御率%・守備力(flat)・被ダメージ軽減%・防具強度・回避 は共通（両方に効く）**。帰結: vanilla armor/toughness が魔法も軽減する。魔法耐性は追加特化層。詳細=`OPEN_DECISIONS` LD-13。

### 6.2 新戦闘機構の扱い（Q3・確定）
**原則（LD-9）**: *ダメージに触れる機構はTFが単一所有*／*触れない機構はValhallaネイティブでよい*。
**重要な帰結**: "Valhalla踏襲"でも**ダメージ/軽減の数値はTFパイプライン経由で適用**する（Valhallaに二重適用させない＝LD-9/LD-8）。Valhallaが担うのは*仕様・発動判定・非ダメ効果*まで。

| 機構 | ダメ関与 | 確定方針 | 実装メモ |
|---|---|---|---|
| 出血(Bleed) | ○DoT | **(c) TF経由DoT** | ✅**実装済**: `BleedInstance`（純粋・テスト済）＋`BleedService`（1tick=`physicalFinalDamage`経由→post-pipeline値をsetHealthで適用＝LD-9単一所有、二重軽減回避）。武器の`bleed-chance/bleed-damage`(roll.yml/weapon)で発動、間隔/回数は`damage.yml bleed.*`。スタック=duration更新。⚠**実機スモーク要**（setHealth適用はknockback/absorption/totemを迂回） |
| スタン(Stun) | ×CC | **(a) Valhalla**（実装おまかせ・仕様充足） | ネイティブ`stunchance`。⚠EliteMobs/ボスへの適用可否は実機確認。スタンロック対策は調整で |
| 受け流し(Parry) | ○軽減 | **仕様=Valhalla踏襲 / 数値=TF経由(b)** | 発動判定・軽減仕様はValhalla、軽減値はTF damage-reductionとして適用（二重軽減回避） |
| 回避(Dodge) | ○無効化 | **(c) TF防御経路**。①攻撃全体を回避 ②魔法も回避可（Q3=はい/はい） | ✅**実装済（機構＋item供給）**: `DodgeResolver`＋`SymmetricDamagePipeline`が攻撃単位で1回判定→成立で全成分0化。プレイヤーは装備防具の`dodge-chance`roll集約（`PlayerDefenseResolver`）、mobはPDC`mob_dodge_chance`。⚠残=防具スキルbaseline（Valhallaブリッジ） |
| アドレナリン | 一部○ | **仕様=Valhalla踏襲 / ダメ関与分TF経由** | 速度等の非ダメバフ=ネイティブ。damage-resistance分はTF damage-reductionへ |
| 憤怒(Rage) | 一部○ | **仕様=Valhalla踏襲 / ダメ関与分TF経由** | 攻撃バフはTF攻撃ステへ(C3単一所有) |
| 攻撃距離/範囲 | × | **(a) 実装おまかせ**。バニラsweep範囲の拡大イメージ | reach=`attackreachbonus`、範囲=vanilla薙ぎ範囲を拡張。各対象の与ダメは通常攻撃経路でTF通過 |
| ホーミング | × | **(c) 新規実装** | Valhalla/Ars非対応前提。軽い軌道補正を自作 |
| ノックバック距離 | × | **(a) vanilla**（無記入=既定受諾） | vanilla KB強度 |
| セット効果 | ステ供給 | **仕様=Valhalla踏襲 / 防御値TF経由** | 2部位/3部位成立判定=ネイティブ、防御ステ分はTF DefenseStatsへ |
| チャージアタック | ○威力 | **(a)+(b) 実装おまかせ** | 溜め機構=ネイティブ、威力はTF攻撃ステへ(C3) |

### 6.3 実装アーキテクチャ（Q1調査結論・ValhallaMMO非改造）
出典: Athlaeos/ValhallaMMO masterソース一次確認（`SkillRegistry`/`ProfileRegistry`/`Perk`/`Skill`/イベント）。

| 論点 | 結論 | 実装手段 |
|---|---|---|
| カスタムスキル追加 | **条件付き可**（YAML単体不可・fork不要） | `SkillRegistry.registerSkill()`＋`ProfileRegistry.registerProfileType()` はpublic。`Skill`/`Profile`継承クラスを**コンパニオンプラグイン**で登録→ツリーYAML・EXP報酬・DB永続化・PAPIが自動配線 |
| 標準13スキル | **純YAML運用で完結** | スキル種別はハードコード列挙だが、採掘〜弓術・防具はネイティブ。ツリー/perkはYAML設定 |
| Ars魔法/Ars鍛冶 | **薄いブリッジPL（数百行）が必要** | 上記APIでARS_MAGIC/ARS_SMITHING を登録。**TrinityForge本体に同居させるのが自然**（既にValhalla reflection済み） |
| 排他分岐(α/β/γ択一) | **専用機能なし→疑似実装** | perk報酬 `perks_locked_add`（`permanentlyLockedPerks`へ追加）で相互ロック配線。α取得時にβ/γをロック |
| グリフ解放=使用ゲート(U1/U4) | **可** | perk YAMLの `commands`（コンソール任意コマンド）で `/ars ...` 等を実行。Ars側コマンドが無ければブリッジPLで受ける |
| ARS_MAGICのEXP源（魔法詠唱） | **API可**（汎用YAMLトリガは無い） | ブリッジPLがArsのキャストイベントを購読→`Skill.addEXP(p, amt, false, PLUGIN)` |
| combat-level のARS_MAGIC読取 | **裏取り成功** | ARS_MAGICが実Profile化されれば既存 `ValhallaSkillLevelSource`(reflection)で読める。§6.1 Q6の暫定修正が生きる |
| 対象バージョン | **可** | ValhallaMMO 1.19-1.21.11・Java 21 サポート範囲内 |

**Q3への波及**: (a)Valhallaネイティブ機構＝標準13スキルのperk YAMLで表現。ダメージ関与分(b/c)のTFステ出力・出血DoT・回避判定は、**ブリッジPL/TrinityForge本体**が担う。→ Q3テーブル(§6.2)の実装ビークルが確定。

> ⚠留保: ValhallaMMO公式APIドキュメントwikiは未整備で、上記はソース読解に基づく。β更新でAPI安定性は変わり得る。実装時は対象版ソースで再確認。

### 6.4 ブリッジPL実装計画（2026-07-14・**失効**）

> ⚠**この節は失効している。** 2026-07-22 に ValhallaMMO 依存を撤廃し、進行系は TF 本体の native 実装
> （`skilltree/` + `progression/`）へ全面移行した。ここに書かれた `Skill`/`Profile` 実装も
> Valhalla データフォルダへの config 配置も**もう存在しない**。`external/ValhallaMMO` と
> `source/*.jar` も 2026-08-04 の整理で破棄した。残しているのは perk 転写元としての設計意図のみ。

- **（旧）依存配線**: `external/ValhallaMMO`（source, 参照用）＋ `source/ValhallaMMO_1.9.3.jar` を `compileOnly` に追加していた。どちらも現在は存在しない。
- **テンプレ確定**（`PowerSkill.java` 準拠）: `Skill` の必須override = `loadConfiguration()`/`isLevelableSkill()`/`getProfileType()`/`getSkillTreeMenuOrderPriority()`＋`Skill(String)`。`Profile` の必須override = `getTableName()`/`getSkillType()`/`getBlankProfile(UUID)`＋`Profile(UUID)`。EXP付与 = `addEXP(player, amt, false, reason)`。
- **要実装コンポーネント**: ①`ArsMagicSkill`＋`ArsMagicProfile`（＋`ArsSmithingSkill`/Profile）②`SkillRegistry.registerSkill()`/`ProfileRegistry.registerProfileType()` の登録（ValhallaMMOロード後の正しいタイミング）③skills/ars_magic(_progression).yml をValhallaデータフォルダへ配置（スキルツリー§2.6/§3をperk転写）④ArsPaperのキャストイベント購読→`skill.addEXP(...)`（魔法柱EXP源）。
- **⚠実機検証必須**: 登録タイミング（Valhalla hard depend・LD-10）、config配置先（Valhallaデータフォルダ）、ArsPaperイベント名は**動作サーバでの検証が必要**。Java クラス自体はjarに対しコンパイル可能。
- ✅**ARS_MAGIC クラス実装済（コンパイル緑）**: `com.trinityforge.bridge.valhalla` に `ArsMagicProfile`（mana/tier/glyph stat）・`ArsMagicSkill`（`loadConfiguration`/levelable/profile型）・`ArsBridge`（`registerArsMagic`・ValhallaMMO不在/失敗をguard）。`TrinityForge.onEnable` で登録呼出（guarded・graceful degradation）。`paper-plugin.yml` を **hard depend（required:true, LD-10）** に更新。**残（実機/追加）**: ①`skills/ars_magic*.yml`（スキルツリーperk転写）をValhallaデータフォルダへ配置 ②登録タイミングの実機検証 ③魔法柱EXP源（ArsPaperキャストイベント→`Skill.addEXP`）④ARS_SMITHING（同テンプレ）⑤ArsPaper側 `magicalFinalDamage` 連携。

---

## 7. 本書が解錠した既存Open Decisions

| ID | 効果 |
|---|---|
| item16 | ✅ ロースター＝15ツリー・combat算入6（物理3＋魔法1）で確定材料が揃った |
| Pr1/LD-7 | 物理柱=軽量/重量/弓、魔法柱=Ars魔法 を裏取り。防具2は非算入で整合 |
| C1/LD-8 | 防御ステの供給源perkが実在（守備力/防御率/被ダメ軽減/耐性/回避） |
| U1/U4 | グリフ解放ノードが「perk→使用ゲート」マスタ表として確定（§2.6/§3） |
| I2 | fixedDamage の roll源（重量武器）が判明。damageModifier は依然穴 |
| Q5/Q6(stat辞書) | 新ステが実装必須へ格上げ。決着はQ3 |
</content>
</invoke>
