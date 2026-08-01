"use strict";

// 表示専用の日本語ラベル辞書。
// ここは「表示層」だけを担当する。保存されるYAMLのキー/値は一切変えない。
// 画面には日本語ラベルを主表示し、元の英字キーはツールチップ(title)や補助テキストで併記する。
// 辞書に無いキーは英字のままフォールバックする。

(function () {
  // ---- ステータスキー -> 日本語 (item-stats.yml / lore.yml のキー) ----
  const STAT_LABELS = {
    "attack-power": "戦闘:攻撃力↑",
    "attack-speed": "戦闘:攻撃速度↑",
    "attack-speed-bonus": "戦闘:攻撃速度加算↑",
    "attack-reach": "戦闘:リーチ↑",
    "aoe-radius": "戦闘:範囲半径↑",
    "aoe-damage-rate": "戦闘:範囲ダメ↑",
    "aoe-max-targets": "戦闘:対象上限↑",
    "item-cooldown": "戦闘:CT",
    "crit-chance": "戦闘:会心率↑",
    "crit-damage": "戦闘:会心ダメ↑",
    "penetration": "戦闘:貫通率↑",
    "flat-bonus-damage": "戦闘:追加ダメ(実数)↑",
    "percent-bonus-damage": "戦闘:追加ダメ率↑",
    "damage-modifier": "戦闘:ダメ補正",
    "bleed-chance": "戦闘:出血率↑",
    "bleed-damage": "戦闘:出血ダメ↑",
    "fixed-damage": "戦闘:固定ダメ↑",
    "armor-defense-rate": "防御:防御力↑",
    "armor-strength": "防御:防具強度↑",
    "max-health": "防御:追加体力↑",
    "knockback-resistance": "防御:KB耐性↑",
    "phys-resistance": "防御:物理耐性↑",
    "magic-resistance": "防御:魔法耐性↑",
    "flat-defense": "防御:守備力↑",
    "phys-flat-defense": "防御:物理守備↑",
    "magic-flat-defense": "防御:魔法守備↑",
    "damage-reduction": "防御:被ダメ↓",
    "dodge-chance": "防御:回避率↑",
    "reflect-flat": "防御:反射ダメ(実数)↑",
    "reflect-percent": "防御:反射ダメ率↑",
    "health-regen-bonus": "防御:自然回復↑",
    "move-speed": "移動:移動速度↑",
    "gathering-efficiency": "採集:採集効率↑",
    "mining-fortune": "採掘:採掘運↑",
    "fishing-luck": "釣り:釣り運↑",
    "fishing-bonus": "釣り:釣りボーナス↑",
    "fish-sell-price-bonus": "釣り:売却額↑",
    "ocean-fishing-bonus": "釣り:海釣り加算↑",
    "disassembly-return-bonus": "解体:戻り量↑",
    "mana-bonus": "Ars:最大マナ加算↑",
    "mana-regen": "Ars:時間マナ回復加算↑",
    "hit-mana-recovery": "Ars:被弾マナ回復(実)↑",
    "damage-mana-recovery": "Ars:攻撃マナ回復(実)↑",
    "mana-cost-reduction-flat": "Ars:マナ消費(実)↓",
    "mana-cost-reduction-percent": "Ars:マナ消費率↓",
    "mana-max-base": "Ars:マナ上限(基礎)",
    "mana-regen-base": "Ars:マナ回復(基礎)",
    "mana-regen-interval-ticks": "Ars:マナ回復間隔",
    "mana-onhit-percent": "Ars:被弾マナ%↑",
    "mana-onattack-percent": "Ars:攻撃マナ%↑",
    "mana-idle-seconds": "Ars:待機マナ秒数",
    "mana-idle-bonus-percent": "Ars:待機マナ%↑",
    "mana-idle-bonus-flat": "Ars:待機マナ(実数)↑",
    "ars-tier-bonus": "Ars:Tier追加↑",
    "glyph-slot-bonus": "Ars:グリフ枠↑",
    "glyph-damage-multiplier-bonus": "Ars:グリフ倍率↑",
    "bow-accuracy": "弓:精度↑",
    "ammo-save-chance": "弓:矢節約率↑",
    "distance-damage-bonus": "弓:距離ダメ↑",
    "arrow-piercing": "弓:矢貫通↑",
    "arrow-velocity": "弓:矢速度↑",
    "arrow-knockback": "弓:矢KB↑",
    "melee-knockback": "近接:追撃KB↑",
    "stun-chance": "近接:スタン率↑",
    "stun-duration-bonus": "近接:スタン時間",
    "power-attack-damage": "近接:空中ダメ↑",
    "power-attack-radius": "近接:空中半径↑",
    "cooldown-reduction": "戦闘:アイテムCT↓",
    "haste-active-mining-cooldown-reduction": "採掘:高速破壊CT↓",
    "tree-fell-cooldown-reduction": "伐採:一括伐採CT↓",
    "coating-charges": "近接:コート回数↑",
    "coating-charges-bonus": "生産:コート上限↑",
    "hunger-save-chance": "食事:空腹節約率↑",
    "food-save-chance": "食事:食料節約率↑",
    "food-restore-bonus": "食事:満腹回復量↑",
    "hidden-saturation-bonus": "食事:隠し満腹↑",
    "mob-drop-bonus": "ドロップ:討伐増加↑",
    "skill-exp-bonus": "EXP:スキルEXP↑",
    "loot-luck": "ドロップ:幸運↑",
    "mob-drop-quality": "ドロップ:品質↑",
    "gacha-rate-bonus": "ドロップ:ガチャ率↑",
    "suspicious-respawn-chance": "ドロップ:怪しいブロ復活↑",
    "hive-harvest-fortune": "ドロップ:養蜂幸運↑",
    "woodcutting-extra-drop-chance": "ドロップ:伐採追加↑",
    "harvest-extra-drop-chance": "ドロップ:収穫追加↑",
    "ritual-quality-bonus": "クラフト:儀式品質↑",
    "workbench-quality-bonus": "クラフト:作業台品質↑",
    "workbench-upswing-bonus": "クラフト:作業台上振れ↑",
    "workbench-downswing-reduction": "クラフト:作業台下振れ↓",
    "ritual-upswing-bonus": "クラフト:儀式上振れ↑",
    "ritual-downswing-reduction": "クラフト:儀式下振れ↓",
    "craft-roll-up-bonus": "クラフト:ロール上振れ↑",
    "craft-roll-down-reduction": "クラフト:ロール下振れ↓",
    "craft-roll-inset": "クラフト:ロール収束",
    "lapis-cost-reduction": "クラフト:ラピス消費↓",
    "source-cost-reduction": "クラフト:ソース消費↓",
    "material-refund-chance": "クラフト:素材返還↑",
    "ingredient-save-chance": "クラフト:材料節約↑",
    "thread-slots": "クラフト:スレ枠数↑",
    "tool-enchant-efficiency": "クラフト:効率増幅↑",
    "durability": "クラフト:耐久値",
    "kill-vanilla-exp-bonus": "EXP:討伐時↑",
    "break-vanilla-exp-bonus": "EXP:破壊時↑",
    "vanilla-exp-bonus": "EXP:常時↑",
    "breeding-vanilla-exp-bonus": "EXP:繁殖時↑",
    "breeding-extra-child-chance": "繁殖:追加子供率↑",
    "bred-animal-growth-bonus": "繁殖:動物成長↑",
    "planted-crop-growth-bonus": "繁殖:作物成長↑",
    "armor-set-bonus": "装備:セット効果↑",
    "enchant-luck": "エンチャ:運↑",
    "enchant-exp-gain-bonus": "エンチャ:EXP↑",
    "enchant-cost-reduction": "エンチャ:費用↓",
    "potion-quality-bonus": "錬金:品質↑",
    "brew-speed-bonus": "錬金:速度↑"
  };

  // 実装の消費箇所を確認した説明。lore.yml に表示定義を持つキーは必ずここにも説明を持たせる。
  const STAT_DESCRIPTIONS = {
    "attack-power": "基準値0の通常攻撃ダメージへ加算される（武器レベル式を有効にしている場合はその結果にも加算）。",
    "attack-speed": "メインハンド装備時の実効攻撃速度そのもの(加算ではなく絶対値)。※item-stats.ymlの武器欄"
      + "限定。メインハンドが未定義の場合、TFはバニラの挙動へ一切干渉しない(素手やツルハシ等の攻撃速度は"
      + "バニラのまま)。0以下を著者が指定した場合は警告のうえ4.0へフォールバックする。",
    "attack-speed-bonus": "全ソース(装備/パーク/base-stats等)から集計される攻撃速度の割合ボーナス"
      + "(0.10=+10%)。メインハンドのattack-speedが未定義でも常に適用される(例: 素手4.0 × 1.10 = 4.4)。"
      + "複数ソースの合計を1つの倍率としてまとめて掛ける。",
    "attack-reach": "バニラの攻撃リーチ属性へ加算される。",
    "aoe-radius": "通常攻撃の範囲攻撃で探索する半径。",
    "aoe-max-targets": "範囲攻撃で追加ヒットできる対象数の上限。",
    "aoe-damage-rate": "範囲攻撃の追加対象に与えるダメージ倍率。",
    "percent-bonus-damage": "最終計算前の攻撃ダメージへ割合で加算される。",
    "damage-modifier": "攻撃ダメージへ乗算する補正値。",
    "item-cooldown": "この武器を使った後にだけ設定される再使用待機時間。プレイヤー総合ステータスには合算されない。",
    "crit-chance": "攻撃時に会心が発生する確率。",
    "crit-damage": "会心発生時のダメージ倍率への加算。",
    "penetration": "対象の防御・耐性計算を貫通する割合。",
    "bleed-chance": "攻撃時に出血を付与する確率。",
    "bleed-damage": "出血状態で与えるダメージ量。",
    "armor-defense-rate": "基準値0の防具値へ加算される。",
    "armor-strength": "防具強度=会心軽減率(0..1)。受ける会心の増加分をこの割合だけ軽減する(通常ダメージには影響しない)。加算後 [0,1] にクランプ。",
    "max-health": "バニラの最大体力属性へ加算される。",
    "knockback-resistance": "基準値0のノックバック耐性へ加算される。",
    "move-speed": "バニラの移動速度属性へ加算される。",
    "gathering-efficiency": "装備/防具/装飾品/パーク/base-stats等から通常通り合算される総合ステ。"
      + "メインハンドの道具が農業/採掘/伐採/切削(use-skillがFARMING/MINING/WOODCUTTING/DIGGING)のいずれか"
      + "のときだけ、その道具へ「効率強化」エンチャントのレベルとして反映される(floor、上限は"
      + "stats/gathering-efficiency.ymlのmax-enchant-level、既定5)。バニラ属性ではなく実在のエンチャント"
      + "を使うため統合版(Bedrock/Geyser)でも正しく効く。装備を外す/持ち替えると付与分は自動で剥がれる"
      + "(プレイヤー総合ステータスへの合算は装備中かどうかを問わない — 効くのはメインハンド適用先だけ)。"
      + "クラフト時にツール自身へ刻まれる tool-enchant-efficiency(クラフト:効率増幅)とは別物 — "
      + "そちらはアイテムを他人に渡しても消えない永続付与で、こちらは装備中だけ効く一時的な上乗せ。",
    "durability": "このアイテム自身の最大耐久値。未設定なら耐久値なし（無限）、設定時はその値で上書き。プレイヤー総合ステータスには合算されない。",
    "phys-resistance": "物理ダメージに対する耐性として防御計算に使われる。",
    "magic-resistance": "魔法ダメージに対する耐性として防御計算に使われる。",
    "damage-reduction": "受けるダメージを割合で軽減する。",
    "dodge-chance": "被弾時にダメージを回避する確率。",
    "reflect-flat": "被弾時にこの固定値分だけ攻撃者へダメージを反射する。棘の鎧の代替(バニラの棘の鎧ダメージは抑止され、耐久消費のみ維持)。",
    "reflect-percent": "被弾時に受けたダメージの割合を攻撃者へ反射する。棘の鎧レベル(装備合計)は1Lvにつき10%をこの値へ寄与する。",
    "mining-fortune": "対象鉱石(採掘ギミックの fortune-blocks)を壊したときの追加ドロップの増加率。+15% なら1ブロックあたり期待値+0.15個(整数部は確定、小数部はその確率で+1個)。MINING Lv による増加分と合算する。シルクタッチとは共存しない。",
    "fishing-luck": "釣りの幸運値に加算される。",
    "fishing-bonus": "釣果に対する追加ボーナス。",
    "tool-enchant-efficiency": "このツール自身へ効率強化エンチャントとして適用される(ツールチップに表示され、譲渡しても効果が付いてくる)。プレイヤー総合ステータスには合算されない。装備中だけ効く集計ステとしての採集効率は gathering-efficiency(採集:採集効率) を使う。",
    "glyph-damage-multiplier-bonus": "特定グリフ(現状: 害悪)のダメージに乗るfraction倍率ボーナス。harmに決め打ちしない汎用stat(フォーク側がどのグリフに適用するか選ぶ)。",
    "mana-bonus": "Ars の最大マナへ加算される。装備分は Ars 側で別途集計される。全員一律の初期値は「マナ上限(基礎)」で設定する(プレイヤー基礎ステ画面には出ない)。",
    "mana-regen": "Ars のマナ自然回復へ加算される。装備分は Ars 側で別途集計される。全員一律の初期値は「マナ回復(基礎)」で設定する(プレイヤー基礎ステ画面には出ない)。",
    "hit-mana-recovery": "この装備の所持者が被弾したときのマナ回復量。キー名 hit- は「hit を受ける=被弾」の意。",
    "damage-mana-recovery": "この装備で近接攻撃を命中させたときのマナ回復量。射撃/魔法では発動しない。",
    "thread-slots": "このアイテムにだけ設定されるスレッド装着枠数。プレイヤー総合ステータスには合算されない。",
    "mana-cost-reduction-flat": "Ars の消費マナを固定値で減らす。",
    "mana-cost-reduction-percent": "Ars の消費マナを割合で減らす。",
    "phys-flat-defense": "物理ダメージに対する固定防御値。",
    "magic-flat-defense": "魔法ダメージに対する固定防御値。",
    "fixed-damage": "防御計算と別枠で加える固定ダメージ。",
    "bow-accuracy": "正の値ほど射撃時の弾道ぶれを小さくする。",
    "ammo-save-chance": "射撃時に通常の矢を消費しない確率。",
    "distance-damage-bonus": "射手と対象の距離に応じて増える射撃ダメージ補正。",
    "arrow-piercing": "射出した矢の貫通レベルに加算される。",
    "arrow-velocity": "チャージ射撃時の矢速度倍率に加算される。",
    "arrow-knockback": "高速のチャージ射撃が対象に与えるノックバック量。内部的には対象の速度への加算(値1=初速0.4 blocks/tick)で、"
      + "空中の水平減衰込みだと値1で約4.4m飛ぶ。ここに書く値は内部値で、表示だけが m へ換算される(1m ≒ 0.23)。",
    "melee-knockback": "近接攻撃時に追加で与えるノックバック量。内部的には対象の速度への加算(値1=初速0.35 blocks/tick)で、"
      + "空中の水平減衰込みだと値1で約3.9m飛ぶ。ここに書く値は内部値で、表示だけが m へ換算される(1m ≒ 0.26)。",
    "stun-chance": "近接攻撃時にスタンを付与する確率。",
    "stun-duration-bonus": "stun-chance発動時のスタン(鈍化/採掘速度低下/移動凍結)継続時間。base-statsの初期tickへ装備・パーク値を加算します。"
      + "初期値は25tick、加算後は5秒(100tick)を絶対上限としてクランプされます。",
    "power-attack-damage": "空中での近接攻撃ダメージを増やす割合。",
    "power-attack-radius": "空中近接攻撃の追加範囲ダメージ半径。",
    "cooldown-reduction": "CombatListener.startItemCooldown が扱う武器の物理CT(item-cooldownの秒数)を"
      + "割合で短縮する。アクティブスキルの待機時間には一切影響しない(そちらは <スキルid>-cooldown-reduction、"
      + "例: haste-active-mining-cooldown-reduction)。",
    "haste-active-mining-cooldown-reduction": "ActivationDispatcher/ActiveCommand が扱う「高速破壊」"
      + "(haste-active-mining)専用の待機時間を割合で短縮する(符号反転で負の値ならCTが増える)。武器の物理CT"
      + "(item-cooldown)にも他のアクティブスキルにも一切影響しない(そちらは cooldown-reduction、または"
      + "対象アクティブスキル専用の別キー)。",
    "tree-fell-cooldown-reduction": "TreeFellingListener が扱う「一括伐採」専用の待機時間を割合で短縮する"
      + "(符号反転で負の値ならCTが増える)。武器の物理CT(item-cooldown)にも他のアクティブスキルにも一切"
      + "影響しない(そちらは cooldown-reduction、または対象アクティブスキル専用の別キー)。",
    "health-regen-bonus": "自然回復で回復する体力を増やす。",
    "coating-charges": "コーティングの所持・使用回数として扱われる。",
    "coating-charges-bonus": "武器コーティングの実効上限スタック数への加算(全ソース合算、WeaponCoatingListener消費)。"
      + "アイテム固有の coating-charges(メインハンド武器単体)とは別枠で加算される。",
    "hunger-save-chance": "空腹度を消費しない確率。",
    "mob-drop-bonus": "モブ討伐時の追加ドロップ補正。",
    "skill-exp-bonus": "スキル経験値の獲得量を増やす。",
    "loot-luck": "戦利品抽選の幸運補正。",
    "mob-drop-quality": "モブドロップの品質抽選を上げる。",
    "gacha-rate-bonus": "ガチャの当選確率を加算する。",
    "food-save-chance": "食料アイテムを消費しない確率。",
    "suspicious-respawn-chance": "怪しげな砂・砂利の再出現確率。",
    "hive-harvest-fortune": "ハニカム・ハチミツの採取量が増える確率。バニラの幸運と同じ考え方で、100%を超えると確定で追加ドロップする。",
    "ritual-quality-bonus": "儀式クラフトの品質基礎値を増やす。",
    "workbench-quality-bonus": "作業台クラフトの品質基礎値を増やす。",
    "workbench-upswing-bonus": "作業台クラフトの品質抽選で上振れ側の広がりを増やす。儀式クラフトには効かない。",
    "workbench-downswing-reduction": "作業台クラフトの品質抽選で下振れ側の広がりを抑える。儀式クラフトには効かない。",
    "ritual-upswing-bonus": "儀式クラフトの品質抽選で上振れ側の広がりを増やす。作業台クラフトには効かない。",
    "ritual-downswing-reduction": "儀式クラフトの品質抽選で下振れ側の広がりを抑える。作業台クラフトには効かない。",
    "craft-roll-up-bonus": "クラフト時のロール結果を上方向へ補正する。",
    "craft-roll-down-reduction": "クラフト時のロール結果の下振れを抑える。",
    "craft-roll-inset": "クラフト時のロール結果を中央へ収束させる。",
    "lapis-cost-reduction": "Ars 系処理のラピス消費を軽減する。",
    "material-refund-chance": "儀式(ペデスタル)専用。台座の素材は通常どおり消費されたうえで、この確率で1個だけインベントリへ返却される(満杯なら足元へドロップ)。消費をスキップする材料節約率とは別の機構で、醸造台やアルケミカルソースリンクには効かない。",
    "ingredient-save-chance": "素材の消費自体をスキップする確率(返却ではない)。バニラ醸造台の材料投入とArsアルケミカルソースリンクへの素材投入の両方に適用される。儀式(ペデスタル)には効かない(そちらは素材返還率)。",
    "source-cost-reduction": "Ars のソース消費を軽減する。"
    // ---- 2026-07-26 タスク3: lore.yml表示追加に伴うSTAT_DESCRIPTIONS未登録12キーの補完 ----
    ,"flat-bonus-damage": "最終ダメージへ加算される固定値。item-stats.ymlへ直接記述することはできず、"
      + "パーク/base-stats/モブ攻撃・コーティング内部経路からのみ供給される。"
    ,"flat-defense": "物理/魔法に分かれていない旧アイテム向けの互換キー(汎用の守備力)。"
      + "設定した値は適用時に物理守備力・魔法守備力のどちらかへ振り分けられ、"
      + "上限もその振り分け先のキーの上限に従う。新しく設定するときは物理守備力/魔法守備力を直接使うこと"
      + "(この画面からは既定で隠している)。"
    ,"mana-max-base": "base-stats.yml専用のマナ初期値(基礎)。ArsPaperのmana.default-max相当の値をここで設定し、"
      + "フォークはTrinityForgeBridge.manaBaseStat経由で読む。通常アイテムには付与されない。"
    ,"mana-regen-base": "base-stats.yml専用のマナ自然回復(基礎)。フォークはTrinityForgeBridge.manaBaseStat"
      + "経由で読む。通常アイテムには付与されない。"
    ,"mana-regen-interval-ticks": "base-stats.yml専用のマナ自然回復の間隔(tick)。フォークは"
      + "TrinityForgeBridge.manaBaseStat経由で読む。通常アイテムには付与されない。"
    ,"mana-onhit-percent": "被弾時マナ回復量への割合ボーナス(%)。ManaBaseStats.onHitPercent経由でフォークが読む。"
    ,"mana-onattack-percent": "攻撃時マナ回復量への割合ボーナス(%)。ManaBaseStats.onAttackPercent経由でフォークが読む。"
    ,"mana-idle-seconds": "待機(未行動)何秒でマナ回復ボーナスが発生するかの秒数。"
      + "ManaBaseStats.idleSeconds経由でフォークが読む。"
    ,"mana-idle-bonus-percent": "待機マナ回復ボーナスの割合分(%)。ManaBaseStats.idleBonusPercent経由でフォークが読む。"
    ,"mana-idle-bonus-flat": "待機マナ回復ボーナスの固定分。ManaBaseStats.idleBonusFlat経由でフォークが読む。"
    // ---- 非戦闘系: EXP / 追加ドロップ / 満腹度 / 繁殖・成長 (2026-07-24 新規13キー) ----
    ,"kill-vanilla-exp-bonus": "MOB討伐時に得るバニラ経験値を増やす(%)。"
    ,"break-vanilla-exp-bonus": "ブロック破壊時に得るバニラ経験値を増やす(%)。前提: 機能解放『破壊時バニラEXP入手』が必要。"
    ,"vanilla-exp-bonus": "あらゆる経路のバニラ経験値を増やす(%)。"
    ,"breeding-vanilla-exp-bonus": "動物の繁殖時に得るバニラ経験値を増やす(%)。"
    ,"woodcutting-extra-drop-chance": "伐採時に確率で追加ドロップを得る(%)。"
    ,"harvest-extra-drop-chance": "作物の収穫時に確率で追加ドロップを得る(%)。"
    ,"food-restore-bonus": "食事で回復する満腹度を増やす(%)。"
    ,"hidden-saturation-bonus": "食事で回復する隠し満腹度(saturation)を増やす(%)。"
    ,"breeding-extra-child-chance": "繁殖時に確率で追加の子供が生まれる(%)。"
    ,"bred-animal-growth-bonus": "自身が繁殖させた動物の成長を速める(%)。"
    ,"planted-crop-growth-bonus": "自身が植えた作物の成長を速める(%)。"
    ,"ars-tier-bonus": "Ars で利用できる Tier を増やす。"
    ,"glyph-slot-bonus": "Ars のグリフ配置可能数を増やす。"
    ,"armor-set-bonus": "スキルツリーのset-buffs(装備部位3/4段の条件バフ)で宣言した段の値全体に" +
        " ×(1+この値) を掛ける。負値は0扱い。段は3と4のみで、成立している最大の段だけが採用される" +
        "(3と4の両方が同時に加算されることはない)。軽装/重装で共通の1キー。"
    // ---- 経済連携 (2026-07-25、Vault対応: T3/T4新規3キー) ----
    ,"fish-sell-price-bonus": "fish-sell-toggle保持者が釣った魚を自動売却する際の基準売却額に乗る倍率(%)。"
    ,"disassembly-return-bonus": "装備解体(dismantle-unlock)の戻り量に乗る追加倍率(%)。既存のグローバル設定値(解体%/ルール倍率)の上に乗算で加算される。"
    ,"ocean-fishing-bonus": "釣り位置が海洋系バイオームのときだけ fishing-bonus の期待値へ加算される追加分(%)。"
    // ---- エンチャント/ポーション品質 (2026-07-25、実行者限定ステ反映: 新規4キー) ----
    ,"enchant-luck": "エンチャントテーブル使用時、確定したエンチャントのレベルを格上げ抽選する確率(luck1.0あたり)に使うポイント。overenchant系を解放していれば上限突破側の出現率にも乗る。"
    ,"enchant-exp-gain-bonus": "エンチャント実行時に得るENCHANTINGスキルEXPを増減する(符号付き、%)。既存のグローバル設定(enchant.level_cost_multiplier)とは別枠でプレイヤー単位に乗算加算される。"
    ,"potion-quality-bonus": "醸造したポーションの効果時間・強度(amplifier)へ換算されるポイント。強度は切り捨てで整数化される(alchemy-quality.yml)。"
    ,"brew-speed-bonus": "醸造時間を割合で短縮する(%)。ホッパー式の自動醸造には alchemy.auto_mult で減衰した値が適用される。"
    // ---- 2026-07-26 新規2キー ----
    ,"enchant-cost-reduction": "エンチャントテーブルの提示/実消費レベルコストと、金床の修理コスト(経験値レベル)を"
      + "割合で軽減する(%)。ランタイム側で0〜90%にクランプされ、軽減後も最低1レベルは必ず残る"
      + "(0コストへの到達=無限エンチャント/修理という経済破壊を防ぐため)。"
  };

  // ---- config フィールドキー -> {label, desc} ----
  // desc は短い説明 (ツールチップ/ヘルプに使う)。
  const FIELD_LABELS = {
    // catalog.yml
    "material": { label: "素材(Material)", desc: "アイテムの元になるバニラMaterialのID。例: DIAMOND_SWORD" },
    "display-name": { label: "表示名", desc: "アイテム名。MiniMessage記法(<red>等)が使えます。" },
    "custom-model-data": { label: "カスタムモデルデータ(CMD)", desc: "リソースパックのテクスチャを割り当てる整数ID。0以上。" },
    "bind-type": { label: "バインド種別", desc: "アイテムの取引/所有の縛り方。" },
    "use-level-requirement": { label: "使用可能レベル", desc: "装備/使用に必要なレベル。0以上の整数。" },
    "quality-mode-offset": { label: "品質基準値", desc: "このアイテムの品質抽選の中心(mode)をずらすオフセット。クラフト、モブドロップ、拾得ルート、釣りで得る装備に共通して適用される。+1ならmodeが+1、-1ならmodeが-1。空欄は0。" },
    "use-skill": { label: "使用スキル", desc: "紐づくスキル系統。例: HEAVY_WEAPONS" },
    // armors.yml
    "display_name_prefix": { label: "表示名の接頭辞", desc: "防具名の先頭に付く文字列。" },
    "name_color": { label: "名前の色", desc: "&d などの色コード、または #RRGGBB。" },
    "color": { label: "革の色", desc: "革防具 (LEATHER_*) の染色色。#RRGGBB。パレットまたはカラーピッカーで指定。" },
    "custom_model_data_base": { label: "CMD基準値", desc: "部位ごとのCMDの基準になる整数。0以上。" },
    "mana_bonus": { label: "マナ上限ボーナス", desc: "最大マナへの加算。" },
    "mana_regen": { label: "マナ自然回復", desc: "時間経過によるマナ回復量。" },
    "hit_mana_recovery": { label: "被弾時マナ回復", desc: "ダメージを受けたときのマナ回復量。" },
    "damage_mana_recovery": { label: "攻撃時マナ回復", desc: "近接攻撃を当てたときのマナ回復量。" },
    "thread_slots": { label: "スレッド枠数", desc: "装着できるスレッドの数。0以上の整数。" },
    "durability": { label: "耐久値", desc: "防具の耐久。0以上の整数。" },
    "enchantable": { label: "エンチャント可", desc: "エンチャントできるか。" },
    "toughness": { label: "防具強度(toughness)", desc: "バニラの防具強度。" },
    "helmet": { label: "ヘルメット", desc: "頭防具の防御値。" },
    "chestplate": { label: "チェストプレート", desc: "胴防具の防御値。" },
    "leggings": { label: "レギンス", desc: "脚防具の防御値。" },
    "boots": { label: "ブーツ", desc: "足防具の防御値。" },
    "lore": { label: "説明文(lore)", desc: "アイテムに表示する説明行。&色コード対応。" },
    "stats": { label: "ステータス", desc: "防具セットが付与するステータス群。" },
    "defense": { label: "防御値(部位別)", desc: "頭/胴/脚/足それぞれの防御値。" },
    "applies-to": { label: "適用対象", desc: "このステが乗るアイテム種別。空なら全種別。" },
    // item-stats.yml
    "fixed": { label: "固定ステ", desc: "常に適用される固定のステータス(stat -> 値)。" },
    "random": { label: "ランダムロールステ", desc: "レンジ{min,max}を「範囲=1」として品質に応じたロール分布(quality.yml の roll-spread-up/roll-spread-down による上下非対称の正規分布)で抽選し、fixed/per-qualityの上に加算する範囲ステ。抽選値はアイテムの rollSeed で決定的(物理魔法共用)。" },
    "per-quality": { label: "品質別上昇値", desc: "品質が1上がるごとにこのステへ加算される増分(stat -> 値)。fixed/randomで決まった値の上に加算する(上書きしない)。" },
    "min": { label: "最小", desc: "" },
    "max": { label: "最大", desc: "" },
    // items.yml (ars-recipes)
    "method": { label: "方式", desc: "作業台 / 儀式 / 合成 / ネザライト化。" },
    "type": { label: "レシピ種別", desc: "定形(shaped) か 不定形(shapeless) か。" },
    "shape": { label: "配置", desc: "作業台の3×3配置。空欄は空きマス。" },
    "ingredients": { label: "素材割当", desc: "配置文字とアイテムの対応。" },
    "amount": { label: "個数", desc: "クラフト結果の個数。" },
    "core-item": { label: "コアアイテム", desc: "儀式の中央に置くアイテム。" },
    "pedestal-items": { label: "台座アイテム", desc: "台座に置く素材。\"NAME xN\" で個数指定。" },
    "source": { label: "必要ソース", desc: "儀式に必要なソース量。0以上の整数。" },
    "source-item": { label: "合成元", desc: "合成は金床の左スロット、ネザライト化は鍛冶台ベース。カタログID。" },
    "addition-item": { label: "合成対象", desc: "金床の右スロット側カタログID。合成元＋合成対象＝合成先(このアイテム)。" },
    "combine-exp": { label: "合成経験値", desc: "合成成功時に付与する ARS_SMITHING 経験値。" },
    "inherit-source-quality": { label: "合成元品質引継ぎ", desc: "ONで合成元の品質を継承。OFF(既定)は合成元と合成対象の品質平均。" },
    "advanced": { label: "高度なオプション", desc: "付与ステ種類のランダム化など。" },
    "randomize-grants": { label: "付与ランダム化", desc: "割り当て済みステのうち実際に付与する種類を確率で決める。" },
    "grant-chances": { label: "付与確率", desc: "ステキーごとの付与確率(0.0〜1.0)。" },
    "result": { label: "結果アイテム", desc: "生成されるアイテム。custom: で本プラグインのアイテム。" },
    "result-amount": { label: "結果個数", desc: "儀式結果の個数。" },
    "name": { label: "表示名", desc: "儀式/エフェクトの表示名。" },
    "effect-type": { label: "エフェクト種別", desc: "儀式のワールド効果種別。既定は craft(アイテム生成)。" },
    "effect-params": { label: "エフェクト設定", desc: "エフェクト種別ごとのパラメータ。" },
    "enchantment": { label: "エンチャント", desc: "付与するエンチャントID。" },
    "level": { label: "レベル", desc: "エンチャントのレベル。" },
    "mode": { label: "天候", desc: "clear(晴れ)/rain(雨)/thunder(雷雨)。" },
    "duration": { label: "継続時間", desc: "効果の継続時間(tick/秒)。" },
    "count": { label: "召喚数", desc: "召喚するモブ/動物の数。" },
    "group": { label: "グループ", desc: "召喚モブのグループ。" },
    // skill-exp.yml / skills/base/*_progression.yml
    // 鍛冶 / Ars鍛冶 で意味が違うため、EXP設定画面ではスキルごとの説明で上書きしている
    // (tf-forms.js SECTION_FIELD_OVERRIDES)。ここは他画面用の中立な説明。
    "exp-per-craft": { label: "クラフト1回EXP", desc: "アイテムを1回クラフトしたときに付与する経験値。個数に関わらず1クラフトにつき1回分。素材別EXPが1行でも設定されている場合はそちらが優先され、この値は使われない。" },
    "exp-per-material": { label: "素材別EXP", desc: "クラフト盤面に置いた素材1個あたりの鍛冶EXP。3x3の全マスを合計し、完成品の使用可能レベル倍率を掛ける。ここに無い素材は0。完成品に使用可能レベルが設定されていない場合はEXPを付与しない(解体で素材へ戻せるアイテムの作り直しによる無限EXP対策)。" },
    "kill-exp": { label: "討伐EXP設定", desc: "敵の種類・レベル・最大体力に応じて討伐時に付与するスキルEXP。" },
    "block-break-exp": { label: "ブロック破壊EXP設定", desc: "魔法でブロックを破壊したときに採取系の素材EXPを参照して付与する設定。" },
    "base": { label: "基礎値", desc: "計算式へ最初に加える基礎値。マップの場合はスキル等の種類別に指定する。" },
    "per-mob-level": { label: "モブレベル1あたりEXP", desc: "討伐対象のモブレベル1につき加算するEXP。" },
    "per-max-health": { label: "最大体力1あたりEXP", desc: "討伐対象の最大体力1につき加算するEXP。" },
    "entity-type-multipliers": { label: "敵種類別EXP倍率", desc: "EntityTypeごとに討伐EXPへ掛ける倍率。未指定の種類は1倍。" },
    "source-multiplier": { label: "採取EXP換算倍率", desc: "採掘・伐採・掘削・農業の素材EXPを魔法EXPへ換算するときの倍率。" },
    "HEAVY_WEAPONS": { label: "重量武器", desc: "重量武器スキルの討伐EXP基礎値。" },
    "LIGHT_WEAPONS": { label: "軽量武器", desc: "軽量武器スキルの討伐EXP基礎値。" },
    "exp_level_curve": { label: "レベル曲線式", desc: "TF数式形式。%level% が現在Lv。^ は累乗。例: (%level% + 75 * 2^(%level%/7.6)) + 300" },
    "max_level": { label: "最大レベル", desc: "このスキルのレベル上限。1以上。" },
    "alchemy_brew_exp": { label: "醸造EXP基礎値", desc: "醸造結果・素材別EXP表に一致しないポーションを作ったときの基礎EXP。" },
    "fishing_catch_exp": { label: "釣果EXP基礎値", desc: "釣果別EXP表に一致しないアイテムを釣り上げたときの基礎EXP。" },
    "bow_exp_base": { label: "弓EXP基礎", desc: "弓射撃1回あたりの基礎EXP。" },
    "crossbow_exp_base": { label: "クロスボウEXP基礎", desc: "クロスボウ射撃1回あたりの基礎EXP。" },
    "damage_exp_bonus": { label: "与ダメージEXP加算率", desc: "弓・クロスボウの与ダメージ1点ごとに加算するEXP倍率。" },
    "distance_exp_multiplier_base": { label: "距離EXP基礎倍率", desc: "至近距離で命中したときの弓術EXP倍率。" },
    "distance_exp_multiplier": { label: "遠距離EXP加算倍率", desc: "射手と対象の距離10ブロックごとに加算する弓術EXP倍率。" },
    "distance_limit": { label: "距離EXP計算上限", desc: "遠距離ボーナスの計算へ使用する最大距離（ブロック）。" },
    "infinity_multiplier": { label: "無限エンチャントEXP倍率", desc: "無限エンチャント付きの弓で獲得する弓術EXPの倍率。" },
    "spawner_spawned_multiplier": { label: "スポナー産EXP倍率", desc: "スポナー由来の敵から獲得する弓術EXPの倍率。" },
    "max_health_limitation": { label: "最大体力によるEXP制限", desc: "対象の最大体力を基準に弓術EXPを制限するか。" },
    "pvp_multiplier": { label: "PvP EXP倍率", desc: "プレイヤーを対象にしたときの獲得EXP倍率。" },
    "is_chunk_nerfed": { label: "同一地点EXP逓減", desc: "同じ場所で繰り返し獲得する防具EXPへ地点ベースの逓減を適用するか。" },
    "exp_damage_piece": { label: "被ダメEXP(1部位)", desc: "防具スキル: ダメージを受けたときのEXP。" },
    "exp_damage_piece_min_damage": { label: "被ダメEXP 最低ダメージ", desc: "この値未満の最終ダメージ(矢の掠り等)では防具EXPを付与しない(semi-AFK farm対策)。" },
    "exp_damage_piece_cooldown_seconds": { label: "被ダメEXP CD(秒)", desc: "同一攻撃者からの被弾EXPは(被害者,攻撃者)単位でこの秒数に1回まで。" },
    "exp_multiplier_point": { label: "防具値1点あたりEXP倍率", desc: "装備中の防具値1点ごとに被弾EXPへ加算する倍率。" },
    "pvp_multiplier_exponent": { label: "PvP EXP倍率指数", desc: "プレイヤーから被弾した際のPvP倍率を何乗して防具EXPへ適用するか。1はそのまま、2は倍率の2乗。" },
    "entity_exp_multipliers": { label: "敵種類別EXP倍率表", desc: "攻撃元のEntityTypeごとに防具EXPへ掛ける倍率。未指定の種類は既定倍率を使う。" },
    "exp_multiplier_mine": { label: "採掘EXP倍率", desc: "通常採掘でのEXP倍率。" },
    "exp_multiplier_blast": { label: "爆破採掘EXP倍率", desc: "爆発経由の採掘でのEXP倍率。" },
    "exp_multiplier_quality": { label: "品質EXP倍率", desc: "錬金など: 品質1あたりのEXP加算倍率。" },
    "multiplier_manual": { label: "手動倍率", desc: "手動行動のEXP倍率。" },
    "multiplier_automated": { label: "自動倍率", desc: "自動装置経由のEXP倍率。" },
    "brew_result": { label: "醸造結果EXP表", desc: "完成したポーション等の種類ごとの錬金術EXP。" },
    "brew_ingredient": { label: "醸造素材EXP表", desc: "醸造に使用した素材の種類ごとの錬金術EXP。" },
    "mining_break": { label: "採掘時EXP表", desc: "破壊したブロックまたは得た素材ごとの採掘EXP。" },
    "digging_break": { label: "掘削時EXP表", desc: "破壊したブロックまたは得た素材ごとの掘削EXP。" },
    "archaeology_brush": { label: "考古学ブラシEXP表", desc: "ブラシで発掘したアイテムごとの掘削EXP。" },
    "woodcutting_break": { label: "伐採時EXP表", desc: "破壊した原木・木材ごとの伐採EXP。" },
    "woodcutting_strip": { label: "樹皮剥ぎEXP表", desc: "斧で樹皮を剥いだ結果ブロックごとの伐採EXP。" },
    "block_interact": { label: "ブロック操作EXP表", desc: "収穫など、ブロックを操作したときの農業EXP。" },
    "block_drops": { label: "ブロック収穫EXP表", desc: "農作物などのブロック・ドロップ素材ごとの農業EXP。" },
    "entity_breed": { label: "繁殖EXP表", desc: "繁殖させた動物の種類ごとの農業EXP。" },
    "entity_kill": { label: "家畜討伐EXP表", desc: "討伐した動物の種類ごとの農業EXP。" },
    "entity_drops": { label: "家畜ドロップEXP表", desc: "家畜から得た素材ごとの農業EXP。" },
    "entity_shear": { label: "毛刈りEXP表", desc: "毛刈りした動物の種類ごとの農業EXP。" },
    "fishing_catch": { label: "釣果EXP表", desc: "釣り上げたアイテムごとの釣りEXP。" },
    "exp_gain": { label: "エンチャントEXP設定", desc: "消費EXP換算と、エンチャント・レベル・種類・対象アイテム別の倍率表。" },
    "experience_spent_conversion": { label: "消費EXP換算率", desc: "エンチャントで消費したバニラEXPをスキルEXPへ換算する倍率。" },
    "enchantment_base": { label: "エンチャント基礎EXP表", desc: "エンチャント種類ごとの基礎EXP。" },
    "enchantment_level_multiplier": { label: "エンチャントレベル倍率表", desc: "付与レベルごとのEXP倍率。" },
    "enchantment_type_multiplier": { label: "素材種別倍率表", desc: "装備素材の種類ごとのEXP倍率。" },
    "enchantment_item_multiplier": { label: "アイテム種別倍率表", desc: "武器・道具・防具部位などの種類ごとのEXP倍率。" },
    "prestige_decay_rate": { label: "プレステージ減衰率", desc: "プレステージ後の総合スキル進行へ適用する減衰率。" },
    // skill-exp.yml 直下スカラー・追加セクション (2026-07-27 タスク1: ID表示バグ修正)
    "outside-dungeon-exp-rate": { label: "ダンジョン外EXP倍率", desc: "dungeon-only-exp: false のとき、ダンジョン外で得る戦闘スキルEXP(武器・魔法=討伐、防具=被弾、弓術=命中)に掛かる倍率。ダンジョン内は常に1.0。1.0=ダンジョンと同率、0.25(既定)=ダンジョンの1/4、0.0=完全遮断。dungeon-only-exp: true のときは参照されない。" },
    "exp-mode": { label: "採取EXP算出方式", desc: "MINING/FARMING/WOODCUTTING/DIGGING共通。drop_sum(既定)=ドロップ品(素材側)の値の合計。block_value=ブロックそのものの値をそのまま使う。max=両者の大きい方。" },
    "radius": { label: "同一地点判定半径", desc: "spot-diminishing: 直近window-seconds秒のあいだにこの半径(ブロック)以内で得たEXP回数を数える。" },
    "window-seconds": { label: "判定時間窓(秒)", desc: "spot-diminishing: この秒数のあいだの獲得回数を同一地点判定に使う。" },
    "threshold": { label: "逓減開始回数", desc: "spot-diminishing: 判定時間窓のあいだにこの回数を超えた分から、1回ごとにdecay-per-killずつ倍率を下げる。" },
    "decay-per-kill": { label: "逓減幅(1回あたり)", desc: "spot-diminishing: threshold超過1回ごとに倍率から差し引く量。" },
    "floor": { label: "倍率下限", desc: "これ未満には下がらない倍率の下限値(spot-diminishing/level-diminishing共通のキー)。" },
    "exempt-dungeon-worlds": { label: "ダンジョン内を除外", desc: "trueのとき、EliteMobsダンジョンのインスタンスワールド内ではspot-diminishingの逓減を適用しない(既定true)。" },
    "gathering-enabled": { label: "採取スキルへ適用", desc: "level-diminishing: MINING/FARMING/WOODCUTTING/DIGGING(採取系)へレベル逓減カーブを適用するか。" },
    "combat-enabled": { label: "戦闘スキルへ適用", desc: "level-diminishing: HEAVY_WEAPONS/LIGHT_WEAPONS/ARCHERY/HEAVY_ARMOR/LIGHT_ARMOR/ARS_MAGIC(戦闘系)へレベル逓減カーブを適用するか。" },
    "formula": { label: "逓減カーブ式", desc: "level-diminishing: exp_level_curveと同じ書式(%level%が現在レベルの四則演算/べき乗式)。評価結果がそのままEXP倍率になる(1.0=減衰なし)。" },
    // materials.yml (ars-materials)
    "base_material": { label: "ベース素材", desc: "アイテムの元になるバニラMaterialのID。例: PRISMARINE_SHARD" },
    "custom_model_data": { label: "カスタムモデルデータ(CMD)", desc: "リソースパックのテクスチャを割り当てる整数ID。0以上。" },
    "display_name": { label: "表示名", desc: "アイテムの表示名。&色コードが使えます。" },
    "enchant_glow": { label: "エンチャント光沢", desc: "エンチャントしていなくても光らせるか。" },
    // threads.yml (ars-threads)
    "stackable": { label: "重複可能", desc: "同じ防具に同じスレッドを複数セットできるか。" },
    "regen-bonus": { label: "マナ回復速度ボーナス", desc: "マナ回復速度への加算(/tick)。" },
    "mana-bonus": { label: "最大マナボーナス", desc: "最大マナへの加算。" },
    "recovery": { label: "マナ回復量", desc: "被弾/攻撃時のマナ回復量。" },
    "cost-reduction": { label: "スペルコスト軽減率", desc: "スペルコスト軽減率(%)。全装備合計は内部で上限あり。" },
    "slots": { label: "収納スロット数", desc: "バックパックのスロット数。" },
    // ---- P4: lore.yml (tf-lore) bind (所有者・使用制限行) ----
    "show-owner": { label: "所有者行を表示", desc: "アイテムのlore に所有者行を表示するか。" },
    "owner-line": { label: "所有者行テンプレート", desc: "MiniMessage文字列。<owner>=所有者名。" },
    "show-use-requirement": { label: "使用制限行を表示", desc: "アイテムのloreに使用可能Lv/スキルの要求行を表示するか。" },
    "use-requirement-line": { label: "使用制限行テンプレート", desc: "MiniMessage文字列。<level>=使用可能レベル / <skill>=使用スキル。" },
    // ---- P4: lore.yml (tf-lore) ----
    "line-template": { label: "行テンプレート", desc: "MiniMessage文字列。<icon><name>：<value> の順（ステータス名：値）。" },
    "score-line-template": { label: "スコア表示テンプレート", desc: "品質スコア行のMiniMessage文字列。<tier>=ティア色付き【ティア名】 / <tier-name>=ティア名 / <score>=品質スコア値。" },
    "positive-color": { label: "プラス値の色", desc: "正の値に付く色 (MiniMessage色名 または #RRGGBB)。" },
    "negative-color": { label: "マイナス値の色", desc: "負の値に付く色 (MiniMessage色名 または #RRGGBB)。" },
    "header": { label: "ヘッダ行", desc: "ステ表示の前に付ける固定行。" },
    "footer": { label: "フッタ行", desc: "ステ表示の後に付ける固定行。" },
    "icon": { label: "アイコン", desc: "行頭に付くアイコン文字列 (リソースパックのPUAグリフ等)。" },
    "format": { label: "表示形式", desc: "FLAT(加算)/PERCENT(%)/INTEGER(整数)/SCALAR(x1.50)。" },
    "decimals": { label: "小数桁数", desc: "表示する小数の桁数。0以上の整数。" },
    "order": { label: "並び順", desc: "同じステータスカテゴリ内での表示順。小さいほど上。同じカテゴリ内で重複させないこと（同値だとキー名の辞書順で並ぶため記述順と食い違う）。" },
    "display-scale": { label: "表示換算係数", desc: "内部値→表示値の換算係数（既定1.0＝換算なし）。単位が内部値の単位と一致しないステだけに使う校正値で、Java側の係数と対応させる必要があるため通常は編集しない（例: ノックバックは velocity 加算なので m 表示へ 3.9 / 4.4 倍している）。base-stats・item-stats・上限値・PDC は内部値のまま。" },
    "category": { label: "ステカテゴリ", desc: "攻撃 / 守備 / 補助 / Ars / その他。Lore の区切りと editor 候補の並び基準。" },
    "lore-default": { label: "デフォルト表示", desc: "ONならこのカテゴリのフォールバック固定ステを、値が0でも Lore に表示する（hide-when-zero をバイパス）。" },
    "show-sign": { label: "符号表示", desc: "プラス値に + を付けるか。" },
    "hide-when-zero": { label: "0のとき隠す", desc: "値が0の行を表示しないか。" },
    // ---- P4: craft-quality.yml (tf-craft-quality) ----
    "skill-levels-per-quality": { label: "品質1段あたりのLv", desc: "スキルLvがこの数上がるごとに期待品質+1。" },
    "base-quality": { label: "基準品質", desc: "スキルLv0のときの品質mode。" },
    "loot-base-quality": { label: "ルート基準品質", desc: "拾得・ルート装備の品質mode基準値。幸運とアイテム個別の品質基準値に加算され、品質分布で抽選される。" },
    "fishing-base-quality": { label: "釣り基準品質", desc: "釣りで得る装備の品質mode基準値。釣りスキル・幸運とアイテム個別の品質基準値に加算され、品質分布で抽選される。" },
    "strength-per-quality": { label: "敵の強さ1段あたり", desc: "敵レベルがこの数上がるごとに期待品質(mode)+1。0以下でmode固定。" },
    "enabled": { label: "有効", desc: "この設定機能をON/OFFする。" },
    "ars-gear": { label: "Ars装備スキル", desc: "Ars魔法装備の品質を駆動するスキル (ARS_SMITHING等)。" },
    // 2026-08-01 分離: craft-quality.yml の workbench/ritual 節 (作業台/儀式で別々のばらつき補正)。
    // 既定値 scale=1.0 / flat=0.0 は Java の CraftQualityConfig.SpreadTuning.IDENTITY と一致。
    "upswing-scale": { label: "上振れ増加の倍率", desc: "プレイヤーステ「品質の上振れ増加(craft-upswing-bonus)」をこの経路で何倍にして効かせるか。1.0=そのまま(分離前と同じ)、0=この経路には効かせない。" },
    "upswing-flat": { label: "上振れσ加算", desc: "この経路にだけ無条件で足す上振れσ。ステとは無関係に効く。0=加算なし(分離前と同じ)。" },
    "downswing-reduction-scale": { label: "下振れ抑制の倍率", desc: "プレイヤーステ「品質の下振れ抑制(craft-downswing-reduction)」をこの経路で何倍にして効かせるか。1.0=そのまま(分離前と同じ)、0=この経路には効かせない。" },
    "downswing-reduction-flat": { label: "下振れσ減算", desc: "この経路にだけ無条件で下振れσから引く値。ステとは無関係に効く。0=減算なし(分離前と同じ)。σは0未満にはならない。" },
    // ---- P4: quality.yml (tf-quality) ----
    "max-quality": { label: "最大品質(フォールバック)", desc: "品質ティアが無い場合の最大品質。範囲[1,100]。" },
    "spread-up": { label: "上振れσ", desc: "品質抽選(正規分布)の上振れ側の標準偏差σ。大きいほど高品質へ跳ねやすい。クラフト・敵ドロップ共通。" },
    "spread-down": { label: "下振れσ", desc: "品質抽選(正規分布)の下振れ側の標準偏差σ。大きいほど低品質へ落ちやすい。クラフト・敵ドロップ共通。" },
    "roll-spread-up": { label: "ロール上振れσ", desc: "ランダムロール層(段2)の上振れ側σ。到達割合[0,1]を品質比例の中心modeから上下非対称の正規分布で抽選する際、max寄り(上)の広がり。大きいほど高い到達率が出やすい。0でその側はmode固定。" },
    "roll-spread-down": { label: "ロール下振れσ", desc: "ランダムロール層(段2)の下振れ側σ。到達割合[0,1]を品質比例の中心modeから抽選する際、min寄り(下)の広がり。大きいほど低い到達率が出やすい。0でその側はmode固定。" },
    "roll-center-inset": { label: "ロール中心インセット", desc: "ランダムロール層(段2)の中心modeを[0,1]の両端から内側へ押し込む量。範囲[0,0.5)。0なら中心=品質比(最低品質はmin端・最高品質はmax端に張り付く)。上げるほど極端な品質でも中心が端から離れ、裾が両側に残る山形になる。mode = inset + 品質比*(1 - 2*inset)。" },
    "give-default-quality": { label: "give既定品質", desc: "give で品質省略時に使う品質。" },
    // ---- crafting-features / use-requirements ----
    "bonus-damage-per-stack": { label: "スタックあたり追加ダメージ", desc: "コーティング1スタックごとの flat-bonus-damage 加算。" },
    "base-max-stacks": { label: "基本最大スタック", desc: "コーティングの基本スタック上限。" },
    "gem-catalog-id": { label: "ジェム catalog ID", desc: "コーティング素材のカタログ ID。" },
    "durability-per-compressed-wood": { label: "圧縮木材あたり耐久回復", desc: "圧縮木材1個で回復する耐久値。" },
    "compressed-wood-catalog-id": { label: "圧縮木材 catalog ID", desc: "修繕に使う圧縮木材のカタログ ID。" },
    "max-effects": { label: "最大効果数", desc: "ポーション統合時の効果上限。" },
    "max-duration-seconds": { label: "最大持続秒", desc: "ポーション統合時の持続時間上限(秒)。" },
    "fortune-cap-bonus": { label: "幸運追加上限", desc: "オーバーエンチャ時の Fortune 上限加算。" },
    "enforce": { label: "使用制限を有効化", desc: "use-level / use-skill ゲートをランタイムで強制するか。" },
    "max-by-category": { label: "カテゴリ別上限", desc: "装備カテゴリごとのスレッド枠上限。1以上を推奨。0にするとスレッド機構が丸ごと無効になり(lore の枠表示・装着GUI・装着済みスレッドの効果が全て消える)、TF の出荷 yml では禁止されています。" },
    // ---- P4: tool-enchants.yml (tf-tool-enchants) ----
    "enchant": { label: "エンチャント", desc: "minecraft エンチャントキー (efficiency/unbreaking/fortune...)。" },
    "quality-thresholds": { label: "品質しきい値", desc: "到達ごとに+1レベル (例: [3,6,9])。" },
    // ---- P4: attribute-map.yml (tf-attribute-map) ----
    "attribute": { label: "バニラ属性", desc: "vanilla attribute キー (attack_damage/max_health...)。" },
    "operation": { label: "演算", desc: "ADD_NUMBER / ADD_SCALAR / MULTIPLY_SCALAR_1。" },
    "scale": { label: "係数", desc: "素のステ値に掛ける倍率 (modifier量になる)。" },
    // ---- P4: glyphs.yml (ars-glyphs) ----
    "tier": { label: "ティア", desc: "スペルブックのティア制限 (1=Novice/2=Apprentice/3=Archmage)。" },
    "mana-cost": { label: "マナコスト", desc: "スペル発動時のマナ消費量。" },
    "params": { label: "パラメータ", desc: "エフェクト固有の数値パラメータ。" },
    "max-augments": { label: "増強の最大数", desc: "各増強の最大スタック数。" },
    "unlock-cost": { label: "解放コスト", desc: "グリフ解放に必要なレベルと素材。" },
    "materials": { label: "必要素材", desc: "Material名 / custom:アイテムID → 個数。" },
    // ---- spellbooks / item-stats (触媒・魔導書・スレッド固有) ----
    "name-color": { label: "名前の色", desc: "表示名の色 (#RRGGBB形式のHex)。" },
    "max-slots": { label: "魔法保存数", desc: "魔導書に保存できる魔法(スペルスロット)の数。" },
    "max-glyphs": { label: "グリフ設定可能数", desc: "1魔法に並べられるグリフの最大数。" },
    "max-glyph-tier": { label: "設定可能グリフ最大ティア", desc: "この魔導書で使えるグリフの最大ティア。" },
    "max-bind-tier": { label: "設定可能グリフ最大ティア", desc: "この触媒にバインド可能なスペルの最大グリフティア。" },
    "set-effects": { label: "セット効果", desc: "スレッドのセット効果。閾値(個数)ごとにステータスを付与。" },
    "special-effects": { label: "特殊効果", desc: "暗視・飛行など、スレッドの既定特殊効果。" },
    "upgrade-from": { label: "アップグレード元", desc: "アップグレード元の魔導書id。最下位ティアは(なし)。items.ymlの儀式定義との整合性は起動時に自動検証される。" },
    "cooldown": { label: "発動CT(秒)", desc: "0または未設定で追加ゲートなし。" },
    "coordinate-coefficient": { label: "座標係数", desc: "ワールドスポーン地点からの距離1ブロックあたりのレベル加算。effectiveLevel = level + floor(距離×係数)。" },
    "max-health": { label: "最大HP", desc: "モブの最大体力。省略時はバニラのまま。実効 = 基準 + レベル係数×effectiveLevel。" },
    "level-coefficients": { label: "レベル係数", desc: "レベル1あたりの各ステ加算。実効 = 基準 + 係数×effectiveLevel。" },
    "capacity": { label: "容量", desc: "ソースジャーの蓄積上限。-1 で無限。" },
    "jars": { label: "ソースジャー定義", desc: "source_jar / creative_source_jar などの見た目と容量。" },
    "volcanic": { label: "ヴォルカニック", desc: "燃料投入でソースを生成するソースリンク。" },
    "mycelial": { label: "マイセリアル", desc: "食料投入でソースを生成するソースリンク。" },
    "alchemical": { label: "アルケミカル", desc: "醸造素材投入でソースを生成するソースリンク。" },
    "materials": { label: "投入マテリアル", desc: "Material名 / custom:アイテムID → ソースポイント値。" },
    "armor-strength": { label: "防具強度", desc: "会心軽減率(0..1)。受ける会心の増加分をこの割合だけ軽減する(物理/魔法で共有)。" },
    "defense-rate": { label: "防御率", desc: "0.0〜1.0。" },
    "resistance": { label: "耐性", desc: "0.0〜1.0。" },
    "damage-reduction": { label: "被ダメージ軽減", desc: "0.0〜1.0。" },
    "flat-defense": { label: "守備力", desc: "0以上。" },
    "attack-power": { label: "攻撃力", desc: "物理ベースダメージ。0より大きいとバニラ攻撃力を置換。" },
    "flat-bonus-damage": { label: "固定追加ダメージ", desc: "最終ダメージに加算(モブ攻撃/コーティング内部用)。アイテムステータスには設定できません。" },
    "percent-bonus-damage": { label: "追加ダメ", desc: "0.0〜1.0。敵に与えた最終ダメージ(全防御考慮後)のこの割合を追加で与える。" },
    "penetration": { label: "貫通", desc: "0.0〜1.0。相手の防御を無視する割合。" },
    "crit-chance": { label: "クリティカル率", desc: "0.0〜1.0。" },
    "crit-damage": { label: "クリティカル倍率", desc: "1.0以上。クリ時のダメージ倍率。" },
    "damage-modifier": { label: "ダメージ補正", desc: "最終段の乗算補正。" },
    "fixed-damage": { label: "固定ダメージ", desc: "計算を経ず加算される固定値。" },
    "attack": { label: "攻撃", desc: "モブの物理攻撃ステ。プレイヤーへの近接/飛び道具命中に適用。" },
    "chance": { label: "ドロップ確率", desc: "1死亡あたり[0,1]。" },
    "physical": { label: "物理", desc: "物理の防御率/耐性/被ダメージ軽減/守備力。" },
    "magical": { label: "魔法", desc: "魔法の防御率/耐性/被ダメージ軽減/守備力。" },
    "drops": { label: "追加ドロップ", desc: "省略可。敵撃破時の追加ドロップ一覧。" },
    "material": { label: "素材", desc: "ドロップするアイテムの Material 名。" },
    "level": { label: "基準戦闘レベル", desc: "モブの基準戦闘レベル。0以上の整数。" }
  };

  // ---- 列挙値 -> 日本語 ----
  const ENUM_LABELS = {
    "bind-type": {
      "SOULBOUND": "魂縛(所有者以外使用不可)",
      "TRADEABLE": "取引可(バインドなし)",
      "OWNER_BOUND": "所有者固定(コマンド設定)"
    },
    "applies-to": {
      "weapon": "武器",
      "armor": "防具",
      "tool": "道具",
      "other": "その他"
    },
    // armors.yml material (見た目素材)
    "armor-material": {
      "LEATHER": "革", "IRON": "鉄", "DIAMOND": "ダイヤモンド",
      "NETHERITE": "ネザライト", "CHAINMAIL": "チェーン", "GOLD": "金"
    },
    // catalog.yml use-skill (スキル系統)。/api/skills の label が取得できれば loadSkills で補完される。
    "use-skill": {
      "ALCHEMY": "錬金", "ARCHERY": "弓術", "ARS_MAGIC": "Ars魔法", "ARS_SMITHING": "Ars鍛冶",
      "DIGGING": "切削", "ENCHANTING": "エンチャント", "FARMING": "農業", "FISHING": "釣り",
      "HEAVY_ARMOR": "重装備", "HEAVY_WEAPONS": "重量武器", "LIGHT_ARMOR": "軽装備",
      "LIGHT_WEAPONS": "軽量武器", "MINING": "採掘", "SMITHING": "鍛冶", "WOODCUTTING": "伐採"
    },
    // items.yml (ars-recipes)
    "method": { "workbench": "作業台", "ritual": "儀式", "combine": "合成", "netherite": "ネザライト化" },
    "type": { "shaped": "定形", "shapeless": "不定形" },
    "effect-type": {
      "craft": "アイテム生成", "weather": "天候操作", "thread": "スレッド付与", "flight": "飛行",
      "moonfall": "月落とし(夜へ)", "sunrise": "日の出(朝へ)", "repair": "修復",
      "animal_summon": "動物召喚", "mob_summon": "敵モブ召喚", "enchant_book": "エンチャント本",
      "thread_slot_expand": "スレッド枠付与",
      "thread_reroll": "スレッド厳選の振り直し"
    },
    "weather-mode": { "clear": "晴れ", "rain": "雨", "thunder": "雷雨" },
    "mob-group": { "default": "通常", "raid": "襲撃", "nether": "ネザー", "variant": "変異" },
    // ---- P4 ----
    // lore.yml format
    "lore-format": { "FLAT": "加算(FLAT)", "PERCENT": "割合%(PERCENT)", "INTEGER": "整数(INTEGER)", "SCALAR": "倍率(SCALAR)" },
    // attribute-map operation
    "operation": { "ADD_NUMBER": "加算(ADD_NUMBER)", "ADD_SCALAR": "スカラー加算(ADD_SCALAR)", "MULTIPLY_SCALAR_1": "乗算(MULTIPLY_SCALAR_1)" },
    // item-categories / tool-enchants のステカテゴリ
    "stat-category": { "weapon": "武器", "armor": "防具", "tool": "道具", "other": "その他", "ars-gear": "Ars装備" },
    // glyphs tier
    "glyph-tier": { "1": "T1 (Novice)", "2": "T2 (Apprentice)", "3": "T3 (Archmage)" },
    // skilltree.yml node role (レイアウト・配置に影響)
    "skill-role": { "main": "主軸", "intermediate": "中間", "branch": "分岐", "greek": "排他(ギリシャ)" },
    "achievement-trigger": { "statistic": "統計", "advancement": "進捗", "static": "図鑑登録", "counter": "累計カウンタ" },
    // 累計カウンタID(trigger.counter)。Java/フォーク側が実際に加算しているものだけを並べる。
    "achievement-counter": { "source_spent": "儀式で消費した累計ソース" },
    // ---- 2026-08-01: 日本語化の取りこぼし ----
    // mob-abilities.yml ability.type。mob-abilities-form.js が
    // selectLabeledInput(..., "mob-ability-type", ...) で引いていたのに、この辞書に
    // グループ自体が無く**セレクトが生ID表示**になっていた(フォーム内のフォールバック
    // <select> だけが日本語を持っていた=到達しないコード)。
    "mob-ability-type": {
      "ground_slam": "全方位AoE (ground_slam)",
      "projectile_volley": "扇状の投射 (projectile_volley)",
      "charge": "突進 (charge)",
      "aura": "持続オーラ (aura)",
      "teleport_strike": "背後へ転移して斬る (teleport_strike)",
      "beam": "直線ビーム (beam)",
      "summon": "増援召喚 (summon)"
    },
    "mob-ability-damage-type": { "physical": "物理", "magical": "魔法" },
    // thread-rolls.yml rarities.<id>.color (Bukkit ChatColor 名)。日本語名は colors.js の
    // MC_COLORS(&コード表)と同じ表記に揃える。
    "rarity-color": {
      "BLACK": "黒", "DARK_BLUE": "濃い青", "DARK_GREEN": "濃い緑", "DARK_AQUA": "濃い水色",
      "DARK_RED": "濃い赤", "DARK_PURPLE": "濃い紫", "GOLD": "金", "GRAY": "灰色",
      "DARK_GRAY": "濃い灰色", "BLUE": "青", "GREEN": "緑", "AQUA": "水色",
      "RED": "赤", "LIGHT_PURPLE": "明るい紫", "YELLOW": "黄", "WHITE": "白"
    }
    // dedicated-effect-category / dedicated-effect-param (専用効果カタログUI) は廃止(2026-07-23)。
    // 解放効果の種別ラベルは window.GATE_EFFECTS.gateEffectTypeLabel(type) を使う (gate-effects.js)。
  };

  // ---- TF native rewards (skilltree native) ----
  // 専用効果(dedicated-effects)と意味が重なる数値報酬はこちらを正とする。
  // unit: 数値入力横に出す単位ヒント。
  const NATIVE_PERK_META = {
    // Ars鍛冶
    "arssmithing_craftqualitybonus_add": { label: "Ars鍛冶: クラフト品質上限+", unit: "点" },
    "arssmithing_craftupswingbonus_add": { label: "Ars鍛冶: 上振れボーナス+", unit: "割合(0〜1)" },
    "arssmithing_craftdownswingbonus_add": { label: "Ars鍛冶: 下振れボーナス+", unit: "割合(0〜1)" },
    "arssmithing_craftrollupbonus_add": { label: "Ars鍛冶: ステロール上振れ+", unit: "割合(0〜1)" },
    "arssmithing_craftrolldownreduction_add": { label: "Ars鍛冶: ステロール下振れ減+", unit: "割合(0〜1)" },
    "arssmithing_threadslots_add": { label: "Ars鍛冶: スレッド枠+", unit: "枠" },
    "arssmithing_lapiscostreduction_add": { label: "Ars鍛冶: ラピス消費軽減+", unit: "割合(0〜1)" },
    "arssmithing_sourcecostreduction_add": { label: "Ars鍛冶: ソース消費軽減+", unit: "割合(0〜1)" },
    "arssmithing_unlockedtier_add": { label: "Ars鍛冶: 解放ティア+", unit: "ティア" },
    // バニラ鍛冶
    "smithing_craftqualitybonus_add": { label: "鍛冶: クラフト品質上限+", unit: "点" },
    "smithing_craftupswingbonus_add": { label: "鍛冶: 上振れボーナス+", unit: "割合(0〜1)" },
    "smithing_craftdownswingbonus_add": { label: "鍛冶: 下振れボーナス+", unit: "割合(0〜1)" },
    "smithing_craftrollupbonus_add": { label: "鍛冶: ステロール上振れ+", unit: "割合(0〜1)" },
    "smithing_craftrolldownreduction_add": { label: "鍛冶: ステロール下振れ減+", unit: "割合(0〜1)" },
    // Ars魔法
    "arsmagic_unlockedtier_add": { label: "Ars魔法: 解放ティア+", unit: "ティア" },
    "arsmagic_manaregenbonus_add": { label: "Ars魔法: マナ自然回復+", unit: "割合(0〜1)" },
    "arsmagic_maxmanabonus_add": { label: "Ars魔法: 最大マナ+", unit: "マナ" },
    "arsmagic_glyphslots_add": { label: "Ars魔法: グリフ枠+", unit: "枠" },
    // 軽量武器
    "lightweapons_attackspeedmultiplier_add": { label: "軽量武器: 攻撃速度倍率+", unit: "割合(0〜1)" },
    "lightweapons_knockbackmultiplier_add": { label: "軽量武器: ノックバック倍率+", unit: "割合(0〜1)" },
    "lightweapons_attackreachbonus_add": { label: "軽量武器: リーチ+", unit: "ブロック" },
    "lightweapons_coatingcharges_add": { label: "軽量武器: コーティング回数+", unit: "回" },
    "lightweapons_coatingunlocked_toggle": { label: "軽量武器: コーティング解放", unit: "0/1" },
    // 重量武器
    "heavyweapons_stunchance_add": { label: "重量武器: スタン確率+", unit: "割合(0〜1)" },
    "heavyweapons_stunduration_add": { label: "重量武器: スタン時間+", unit: "tick" },
    "heavyweapons_attackspeedmultiplier_add": { label: "重量武器: 攻撃速度倍率+", unit: "割合(0〜1)" },
    "heavyweapons_chargeattackdamage_add": { label: "重量武器: チャージ攻撃ダメージ+", unit: "割合(0〜1)" },
    "heavyweapons_knockbackmultiplier_add": { label: "重量武器: ノックバック倍率+", unit: "割合(0〜1)" },
    "heavyweapons_attackrangebonus_add": { label: "重量武器: 攻撃範囲+", unit: "ブロック" },
    "heavyweapons_coatingcharges_add": { label: "重量武器: コーティング回数+", unit: "回" },
    "heavyweapons_coatingunlocked_toggle": { label: "重量武器: コーティング解放", unit: "0/1" },
    // 軽装/重装
    "lightarmor_movespeedpenalty_reduce": { label: "軽装: 移動速度ペナルティ軽減", unit: "割合(0〜1)" },
    "heavyarmor_knockbackresistance_add": { label: "重装: ノックバック耐性+", unit: "割合(0〜1)" },
    "heavyarmor_movespeedpenalty_reduce": { label: "重装: 移動速度ペナルティ軽減", unit: "割合(0〜1)" },
    // 弓術
    "archery_accuracy_add": { label: "弓術: 精度+", unit: "割合(0〜1)" },
    "archery_savechance_add": { label: "弓術: 矢温存確率+", unit: "割合(0〜1)" },
    "archery_shootdistance_add": { label: "弓術: 射程+", unit: "割合(0〜1)" },
    "archery_homingchance_add": { label: "弓術: ホーミング確率+", unit: "割合(0〜1)" },
    "archery_shootspeedmultiplier_add": { label: "弓術: 射撃速度倍率+", unit: "割合(0〜1)" },
    "archery_chargespeedmultiplier_add": { label: "弓術: チャージ速度倍率+", unit: "割合(0〜1)" },
    "archery_knockbackmultiplier_add": { label: "弓術: ノックバック倍率+", unit: "割合(0〜1)" },
    "archery_arrowpiercing_add": { label: "弓術: 矢貫通+", unit: "体" },
    // 伐採 (製材ボーナス = woodcuttingdrops)
    "woodcutting_woodcuttingdrops_add": { label: "伐採: 製材ボーナス(追加ドロップ)+", unit: "割合(0〜1)" },
    "woodcutting_woodcuttingspeedbonus_add": { label: "伐採: 伐採速度ボーナス+", unit: "割合(0〜1)" },
    "woodcutting_woodcuttingluck_add": { label: "伐採: 伐採幸運+", unit: "点" },
    "woodcutting_woodcuttingexpmultiplier_add": { label: "伐採: EXP倍率+", unit: "割合(0〜1)" },
    "woodcutting_blockexperiencerate_add": { label: "伐採: ブロックEXP率+", unit: "割合(0〜1)" },
    "woodcutting_treecapitatorunlocked_set": { label: "伐採: 一括伐採解放", unit: "0/1" },
    "woodcutting_treecapitatorcooldown_add": { label: "伐採: 一括伐採クールダウン", unit: "tick" },
    "woodcutting_treecapitatorlimit_add": { label: "伐採: 一括伐採ブロック上限+", unit: "個" },
    "woodcutting_instantgrowthrate_add": { label: "伐採: 即時成長率+", unit: "倍率点" },
    // 総合(power)
    "power_healthbonus_add": { label: "総合: 最大体力+", unit: "点" },
    "power_luckbonus_add": { label: "総合: 幸運+", unit: "点(釣り/拾得の品質mode+1相当。ドロップ/クラフトには効かない)" },
    "power_mobdropbonus_add": { label: "総合: mobドロップボーナス+", unit: "点(mobドロップ装備の品質mode+1相当)" },
    "power_healthregenerationbonus_add": { label: "総合: 体力自然回復+", unit: "割合(0〜1)" },
    "power_hungersavechance_add": { label: "総合: 満腹度消費節約確率+", unit: "割合(0〜1)" },
    "power_knockbackresistancebonus_add": { label: "総合: ノックバック耐性+", unit: "割合(0〜1)" },
    "power_cooldownreduction_add": { label: "総合: クールダウン短縮+", unit: "割合(0〜1)" },
    "power_allskillexpmultiplier_add": { label: "総合: 全スキルEXP倍率+", unit: "割合(0〜1)" },
    "power_entitydropmultiplier_add": { label: "総合: エンティティドロップ倍率+", unit: "割合(0〜1)" },
    // 軽装(light_armor) 本日新設含む
    // 2026-07-27(armor-set-buffs全面移行): lightarmor_setdodgechance_add/lightarmor_setamount_add は
    // スキルツリーのset-buffsスキーマ(装備部位3/4段の条件バフ)+ armor-set-bonus(共通増幅率)へ統一され廃止。
    // 2026-07-31: 移行先の light-/heavy-armor-move-speed-per-piece を廃止したため
    // LEGACY_NATIVE_TO_BUFF からは外した(旧 native データは移行せずそのまま残す方針)。
    // 旧データを画面に出したときのラベルとしてここは残す。
    "lightarmor_movementspeedperpiece_add": { label: "軽装: 装備部位ごとの移動速度+", unit: "割合(0〜1)" },
    // 重装(heavy_armor)
    // 2026-07-27: heavyarmor_setknockbackresistance_add/heavyarmor_setamount_add も同様に廃止。
    "heavyarmor_movementspeedperpiece_add": { label: "重装: 装備部位ごとの移動速度+", unit: "割合(0〜1)" }
  };

  // DEDICATED_HIDDEN_PREFER_NATIVE / DEDICATED_PARAM_UNITS / DEDICATED_EFFECT_UNITS
  // (専用効果カタログUI向け単位/隠しフィルタ辞書) は廃止(2026-07-23)。

  // Material名 -> 日本語 (サーバの /api/material-labels で埋める)。既定は空。
  window.MATERIAL_LABELS = {};

  // ---- 参照ヘルパー (辞書に無ければ元キーを返す) ----
  // lore.yml の name（STAT_META）を最優先。無いときだけ静的 STAT_LABELS。
  function stripMcColor(s) {
    return String(s || "").replace(/&[0-9a-fk-or]/gi, "").replace(/§[0-9a-fk-or]/gi, "").trim();
  }
  // STAT_LABELS はkebab-case(lore.yml準拠)で登録されているが、スキルツリーのbuffsキーは
  // snake_caseで書かれる(例: "loot_luck")。Java側 StatKeys.canonical と同じく '-'/'_' を
  // 区別しない正規化を挟むことで、casing違いだけで英字キーへフォールバックする表示崩れを防ぐ。
  function normalizeStatKey(key) {
    return String(key || "").toLowerCase().replace(/_/g, "-");
  }
  function statLabel(key) {
    if (!key) return "";
    const meta = window.STAT_META && window.STAT_META[key];
    if (meta && typeof meta.name === "string" && meta.name.trim()) {
      return stripMcColor(meta.name);
    }
    if (STAT_LABELS[key]) return STAT_LABELS[key];
    const normalized = normalizeStatKey(key);
    if (normalized !== key && STAT_LABELS[normalized]) return STAT_LABELS[normalized];
    return key;
  }
  // T5(2026-07-25): 「効果範囲を示す接頭辞+方向記号」ラベルがselectメニューから見切れないための上限。
  // gate-vocabulary/stat-labels のテスト側で全STAT_LABELSエントリがこれを超えないことを固定する。
  const MAX_STAT_LABEL_LENGTH = 14;
  function statDescription(key) {
    return STAT_DESCRIPTIONS[key] || "このステータスの実装上の説明は未登録です。";
  }
  function nativePerkLabel(key) {
    const m = key && NATIVE_PERK_META[key];
    return (m && m.label) || key || "";
  }
  function nativePerkUnit(key) {
    const m = key && NATIVE_PERK_META[key];
    return (m && m.unit) || "";
  }
  function fieldLabel(key) {
    const e = FIELD_LABELS[key];
    return e ? e.label : key;
  }
  function fieldDesc(key) {
    const e = FIELD_LABELS[key];
    return e ? e.desc : "";
  }
  function enumLabel(group, value) {
    const g = ENUM_LABELS[group];
    return (g && g[value]) || value;
  }
  // 2026-07-29: 村人職業の日本語名。tf-lifestyle-forms.js が持っていたものを、
  // スキルツリーの trade: ゲート(職業セレクトが英字 enum のままだった)からも引けるよう
  // labels.js へ一本化する。内部キー(英語)は保存値なので変えない。
  const VILLAGER_PROFESSION_LABELS_JA = {
    WEAPONSMITH: "武器鍛冶", ARMORER: "防具鍛冶", TOOLSMITH: "道具鍛冶",
    CLERIC: "聖職者", LIBRARIAN: "司書", FARMER: "農民", FISHERMAN: "漁師",
    SHEPHERD: "羊飼い", BUTCHER: "肉屋", CARTOGRAPHER: "地図職人",
    FLETCHER: "矢師", LEATHERWORKER: "革細工師", MASON: "石工",
    NITWIT: "能無し", NONE: "職業なし"
  };
  function professionLabel(id) {
    return VILLAGER_PROFESSION_LABELS_JA[id] || id || "";
  }
  function materialLabel(mat) {
    if (!mat) return "";
    return window.MATERIAL_LABELS[mat] || "";
  }
  // タスク8 (2026-07-26): 醸造ギミックタブ(potion-merge/brew-unlocks)の材料欄が Material の
  // 生ID(例: NETHER_WART)のまま表示されていた問題向け。既存の MATERIAL_LABELS 辞書
  // (/api/material-labels、materialLabel() 参照)は未登録キーに対して空文字を返す仕様だが、
  // 呼び出し側で「辞書に無ければ生IDへフォールバック表示」したい場面向けに薄いラッパーを用意する。
  // 新しい辞書は作らない(既存 MATERIAL_LABELS をそのまま参照するだけ)。
  function materialLabelWithFallback(mat) {
    if (!mat) return "";
    const key = String(mat);
    return window.MATERIAL_LABELS[key] || key;
  }
  // 2026-07-28: エンチャントIDの日本語名。vocab-1.21.11.js の ENCHANT_LABELS_JA は
  // 小文字キー(sharpness)だが、config 側は大文字(SHARPNESS)や `minecraft:sharpness` の形でも
  // 現れるため、ここで表記ゆれを吸収する。辞書は増やさず既存のものを引くだけ。
  // 1.21.2 でレジストリIDが改名されたエンチャント。TF の出荷 yml は旧IDと新IDの両方に
  // 同じ値を書いて互換を取っているので(enchanting_progression.yml の sweeping / sweeping_edge)、
  // 旧IDのほうも生IDのまま表示されないようここで新IDへ寄せる。
  const ENCHANT_ID_ALIASES = { sweeping: "sweeping_edge" };
  function enchantLabel(id) {
    if (!id) return "";
    const raw = String(id).trim();
    const key = (raw.includes(":") ? raw.slice(raw.indexOf(":") + 1) : raw).toLowerCase();
    const map = window.ENCHANT_LABELS_JA;
    if (!map) return "";
    return map[key] || map[ENCHANT_ID_ALIASES[key]] || "";
  }
  /** 辞書に無ければ生IDへフォールバックする版(セレクトの主表示用)。 */
  function enchantLabelWithFallback(id) {
    if (!id) return "";
    return enchantLabel(id) || String(id);
  }

  // ---- PotionType(醸造のベース/結果) id -> 日本語 ----
  // 2026-07-28: 「醸造結果EXP表」の行見出しが AWKWARD / SWIFTNESS の生IDのままだったため追加。
  // 醸造ギミックの「ベース」セレクト(tf-crafting-features.js)と同じ語彙なので、こちらを唯一の
  // 辞書にして両方から引く。
  const POTION_TYPE_LABELS_JA = {
    AWKWARD: "奇妙なポーション", MUNDANE: "ありふれたポーション", THICK: "濃厚なポーション",
    WATER: "水入り瓶", NIGHT_VISION: "暗視", INVISIBILITY: "透明化", LEAPING: "跳躍",
    FIRE_RESISTANCE: "耐火", SWIFTNESS: "俊敏", SLOWNESS: "鈍足", WATER_BREATHING: "水中呼吸",
    HEALING: "治癒", HARMING: "負傷", POISON: "毒", REGENERATION: "再生", STRENGTH: "力",
    WEAKNESS: "弱化", LUCK: "幸運", TURTLE_MASTER: "鈍足耐性(タートルマスター)",
    SLOW_FALLING: "落下耐性", INFESTED: "蟲の巣", OOZING: "滲出", WEAVING: "細工",
    WIND_CHARGED: "ウィンドチャージ"
  };
  /**
   * PotionType の日本語名。LONG_/STRONG_ 接頭辞つき(延長/強化ポーション)も接尾で表す。
   * 辞書に無ければ空文字を返す(呼び出し側で生IDへフォールバックする)。
   */
  function potionTypeLabel(id) {
    if (!id) return "";
    const key = String(id).trim().toUpperCase();
    if (POTION_TYPE_LABELS_JA[key]) return POTION_TYPE_LABELS_JA[key];
    const m = /^(LONG|STRONG)_(.+)$/.exec(key);
    if (m && POTION_TYPE_LABELS_JA[m[2]]) {
      return `${POTION_TYPE_LABELS_JA[m[2]]}(${m[1] === "LONG" ? "延長" : "強化"})`;
    }
    return "";
  }

  // 「日本語 (英字)」の併記文字列を作る。日本語が無ければ英字のみ。
  function withKey(jaLabel, key) {
    if (!jaLabel || jaLabel === key) return key;
    return `${jaLabel} (${key})`;
  }

  // ---- グリフ増強(augment) id -> 日本語 (glyphs.yml max-augments のホバー説明) ----
  const AUGMENT_LABELS = {
    "amplify": "増幅 — 効果の威力を強める",
    "dampen": "減衰 — 効果の威力を弱める(コスト減)",
    "aoe": "範囲拡大 — 効果範囲を広げる",
    "aoe_height": "範囲拡大(高さ) — 縦方向の範囲を広げる",
    "aoe_vertical": "範囲拡大(垂直) — 垂直方向の範囲を広げる",
    "aoe_radius": "範囲拡大(半径) — 半径を広げる",
    "extend_time": "持続延長 — 効果時間を延ばす",
    "duration_down": "持続短縮 — 効果時間を縮める",
    "accelerate": "加速 — 弾速や動作を速める",
    "decelerate": "減速 — 弾速や動作を遅くする",
    "pierce": "貫通 — 対象を貫通してヒットを増やす",
    "split": "分裂 — 弾や効果を複数に分ける",
    "extract": "抽出 — シルクタッチ相当の採取",
    "fortune": "幸運 — 幸運相当のドロップ増加",
    "randomize": "ランダム化 — 効果の対象や結果を乱す",
    "rapid_fire": "速射 — 発射回数を増やす",
    "delay": "遅延 — 効果の発動を遅らせる",
    "linger": "滞留 — 効果をその場に残す",
    "propagate": "伝播 — 効果を周囲へ連鎖させる",
    "trace": "追跡 — 弾が対象を追尾する"
  };
  function augmentLabel(id) {
    return AUGMENT_LABELS[id] || "";
  }

  // ---- グリフ params キーの日本語ヒント生成 ----
  // params キーは実装固定で数が多いため、単語辞書 + 接頭/接尾規則から説明を組み立てる。
  // 完全一致辞書 (規則で読みにくいもの) を優先し、それ以外はトークン変換。
  const GLYPH_PARAM_EXACT = {
    "mana-cost": "マナ消費量",
    "hit-cooldown-ticks": "同一対象への再ヒット間隔(tick)",
    "hit-interval-ticks": "ヒット間隔(tick)",
    "hit-count": "ヒット回数",
    "hit-radius": "ヒット判定半径(ブロック)",
    "damage-interval": "ダメージ間隔(tick)",
    "fire-ticks": "炎上時間(tick)",
    "fire-multiplier": "炎上時の倍率",
    "wet-bonus-damage": "濡れた対象への追加ダメージ",
    "water-multiplier": "水中での倍率",
    "high-level-threshold": "高レベル扱いになる閾値",
    "horizontal-friction": "水平方向の減衰",
    "spread-angle-step": "拡散角の刻み(度)",
    "fang-spacing": "ファングの間隔(ブロック)",
    "food-cost": "食料消費量",
    "per-stack": "スタックごとの効果量",
    "targets-per-stack": "スタックごとの対象数",
    "reach-bonus": "リーチ加算(ブロック)",
    "reach-speed-bonus": "リーチ増強ごとの速度加算",
    "multiplier-base": "基礎倍率",
    "multiplier-max": "最大倍率",
    "multiplier-per-amplify": "増幅ごとの倍率加算"
  };
  const GLYPH_PARAM_WORDS = {
    base: "基礎", max: "最大", min: "最小", bonus: "ボーナス", extra: "追加",
    damage: "ダメージ", heal: "回復量", health: "体力", hp: "体力", hardness: "硬度",
    radius: "半径", range: "射程", distance: "距離", height: "高さ", depth: "深さ",
    duration: "持続時間", lifetime: "存在時間", ticks: "(tick)", tick: "(tick)", seconds: "(秒)",
    interval: "間隔", cooldown: "クールダウン", delay: "遅延", fuse: "起爆",
    count: "数", amount: "量", stars: "星の数", orbs: "オーブ数", fangs: "ファング数", fang: "ファング",
    speed: "速度", velocity: "初速", power: "威力", force: "力", volume: "体積", growth: "成長量",
    level: "レベル", amplifier: "効果レベル", amplify: "増幅", aoe: "範囲拡大", reach: "リーチ",
    blocks: "ブロック数", block: "ブロック", entity: "エンティティ", summon: "召喚", summons: "召喚数",
    spawn: "スポーン", wolf: "ウルフ", wolves: "ウルフ", firework: "花火", flight: "飛行",
    food: "食料", poison: "毒", slowness: "鈍化", resistance: "耐性", freeze: "凍結",
    soaked: "濡れ", removal: "解除", lock: "拘束", snare: "拘束", shocked: "感電",
    scale: "大きさ", step: "刻み", angle: "角度", threshold: "閾値", multiplier: "倍率",
    chance: "確率", percent: "(%)", water: "水", wind: "風", gravity: "重力",
    trail: "軌跡", zone: "ゾーン", circle: "円", burst: "バースト", trigger: "起動",
    aim: "照準", push: "押し出し", down: "下方向", vision: "暗視", night: "夜",
    pickup: "回収", per: "ごとの", extend: "延長", lunar: "月", solar: "太陽"
  };
  function glyphParamHint(key) {
    const k = String(key || "");
    if (!k) return "";
    if (GLYPH_PARAM_EXACT[k]) return GLYPH_PARAM_EXACT[k];
    const tokens = k.split("-");
    // "X-per-Y" 系: 「Yごとの X」に並べ替える
    const perIdx = tokens.indexOf("per");
    let ordered = tokens;
    if (perIdx > 0 && perIdx < tokens.length - 1) {
      ordered = tokens.slice(perIdx + 1).concat(["per"]).concat(tokens.slice(0, perIdx));
    }
    const parts = [];
    for (const t of ordered) {
      if (!t) continue;
      parts.push(GLYPH_PARAM_WORDS[t] || t);
    }
    const text = parts.join("");
    return text && text !== k ? text : "";
  }

  window.LABELS = {
    STAT_LABELS, STAT_DESCRIPTIONS, FIELD_LABELS, ENUM_LABELS, NATIVE_PERK_META,
    AUGMENT_LABELS, MAX_STAT_LABEL_LENGTH,
    statLabel, statDescription, nativePerkLabel, nativePerkUnit,
    fieldLabel, fieldDesc, enumLabel, materialLabel, materialLabelWithFallback, withKey,
    enchantLabel, enchantLabelWithFallback,
    POTION_TYPE_LABELS_JA, potionTypeLabel,
    VILLAGER_PROFESSION_LABELS_JA, professionLabel,
    augmentLabel, glyphParamHint, normalizeStatKey
  };
})();
