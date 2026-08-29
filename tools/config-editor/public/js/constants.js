"use strict";

// 共通変数(戦闘定数)エディタ。複数YAMLの横断定数を1画面で編集する。
// サーバの GET /api/constants から受け取った {fields, pillars, skills} を working に保持し、
// スカラーは直接編集、配列(pillars)/マップ(skills)は構造変更時のみ再描画する。
// getData() は working を返し、app.js が PUT /api/constants へ送る。

(function () {
  const h = window.h;

  // ============================================================================
  // 専用効果(dedicated-effects)カタログは廃止(2026-07-23)。解放効果はスキルツリーのノードで
  // プレフィックス付き動的ID(glyph:/brew:/trade:/recipe:/ritual:/drop:/feature:/overenchant:/
  // ars-tier/reward:)として直接設定する。語彙は /api/gate-vocabulary から供給する(tf-skilltree.js)。
  // window.DEDICATED_EFFECTS は他タブ(tf-crafting-features.js等)がフォールバック参照するため
  // 空配列として残す(Array.isArray チェックのみで使われる。中身は今後使わない)。
  // ============================================================================
  window.DEDICATED_EFFECTS = [];

  // スカラー定数のUIメタ情報 (id はサーバ FIELD_SPECS と一致必須)。
  const FIELD_GROUPS = [
    {
      title: "物理 (combat/damage.yml)",
      fields: [
        { id: "physical.base-coefficient", label: "基本係数", kind: "number", desc: "物理の基本ダメージ(gear非依存)全体に掛かる倍率。" },
        { id: "physical.min-component-damage", label: "下限クランプ", kind: "number", desc: "step7: 1コンポーネントがこの値を下回らない床。負値なら最終的に敵を回復し得ます。" }
      ]
    },
    {
      title: "近接チャージ (連打減衰)",
      fields: [
        { id: "melee-charge.enabled", label: "有効", kind: "boolean", desc: "バニラのチャージ攻撃(クールダウン中の連打による減衰)をTFのダメージパイプラインへ再導入するか。近接プレイヤー攻撃のみに適用(弓/クロスボウ/トライデント/魔法/モブ攻撃には適用しない)。" },
        { id: "melee-charge.min-multiplier", label: "下限倍率(t=0)", kind: "number", desc: "振った直後(未チャージ)のダメージ倍率の下限。既定0.1(2026-08-01調整、旧0.2=バニラ相当)。" },
        { id: "melee-charge.exponent", label: "指数", kind: "number", desc: "冷却後の攻撃強度割合に掛ける指数。既定1.6(2026-08-01調整、旧2.0=バニラ相当)。低いほどカーブがなだらかになる。" }
      ]
    },
    {
      title: "攻撃速度",
      fields: [
        { id: "attack-speed.min-effective", label: "実効速度下限", kind: "number", desc: "attack-speed(絶対値)とattack-speed-bonus(割合)を合成した後の最終実効速度が割り込まない下限クランプ。デバフ過多でも0/負値にならない安全弁。既定0.1。" },
        { id: "attack-speed.reconcile-interval-ticks", label: "再照合周期(ticks)", kind: "int", desc: "装備フィンガープリントの再照合周期(サーバtick、20=1秒)。既定10tick=0.5秒。" }
      ]
    },
    {
      title: "魔法",
      fields: [
        { id: "magical.base-coefficient", label: "基本係数", kind: "number", desc: "魔法(spell/触媒)基本ダメージに掛かる倍率。" },
        { id: "magical.min-component-damage", label: "下限クランプ", kind: "number", desc: "魔法コンポーネントの step7 下限。負値なら最終的に敵を回復し得ます。" },
        { id: "magical.scale-with-combat-level", label: "combatレベル倍率を適用", kind: "boolean", desc: "true: 魔法もレベル倍率で伸びる。false: bypass(グリフ/触媒のみ)。" },
        { id: "magical.attack-power-scale", label: "杖の攻撃力の加算係数", kind: "number", desc: "杖(触媒)の攻撃力を魔法の基礎ダメージへ何倍で加算するか。魔法基礎 = グリフ基礎ダメージ + 杖のattack-power×この係数。1.0=仕様どおり100%加算(近接と対等)。0で杖の攻撃力は魔法に一切乗らない。範囲0〜10。" }
      ]
    },
    {
      title: "武器基本火力式  base = 1 + (useLevel^a / b)",
      fields: [
        { id: "weapon-base-formula.enabled", label: "有効", kind: "boolean", desc: "武器の attack-power を使用可能lvから算出する式を使うか。" },
        { id: "weapon-base-formula.a", label: "指数 a", kind: "number", desc: "使用可能lvの伸びに対する火力カーブの鋭さ(0以上)。" },
        { id: "weapon-base-formula.b", label: "除数 b", kind: "number", desc: "厳密に正(>0)。大きいほど火力が緩やか。" }
      ]
    },
    {
      title: "レベルスケーリング",
      fields: [
        { id: "level-scaling.per-level", label: "レベル毎倍率", kind: "number", desc: "基本ダメージ = base * (1 + per-level * combatLevel)。" }
      ]
    },
    {
      title: "防御(安全弁)",
      fields: [
        { id: "defense.max-mitigation-rate", label: "軽減率上限", kind: "number", desc: "貫通不可の耐性%/被ダメ軽減%の上限(0..1)。0.9で最低10%は通る。防御率%(貫通可)も同じ上限でキャップされる。" },
        { id: "defense.max-dodge-chance", label: "回避率上限", kind: "number", desc: "回避率の上限(0..1)。0.9で最低10%は命中する(無敵回避防止)。" },
        { id: "defense.max-crit-reduction", label: "会心軽減率上限", kind: "number", desc: "防具強度(会心軽減率%)の上限(0..1)。既定1.0=キャップ無し(会心の増加分を最大100%軽減しうるが、相手の会心ダメージが0%未満へ反転することはない)。1.0未満で会心は必ず(1-上限)の増加を残す。" },
        { id: "defense.enchant-protection-scale", label: "防護エンチャント倍率", kind: "number", desc: "防護/プロジェクタイル防護エンチャントの再導出軽減率に掛ける倍率。1.0=バニラ準拠(防護IVフルセットで64%軽減)。既定0.5は意図的な調整値: 1.0だと軽減率上限(defense.max-mitigation-rate、既定0.9)の枠をこのエンチャント1種だけで71%も食い潰し、TF自前の防具ステ(守備力・耐性等)がほぼ無意味になるため半分に絞っている。" }
      ]
    },
    {
      title: "防御クランプ (旧: 戦闘ダメージタブ)",
      fields: [
        { id: "defense.min-rate", label: "下限(率)", kind: "number", desc: "被ダメージ軽減率の下限。負にすると防御が被ダメージを増幅する側に働く。" },
        { id: "defense.max-rate", label: "上限(率)", kind: "number", desc: "被ダメージ軽減率の上限。1を超えると全軽減を超え、最終ダメージが負(=回復)になりえる。" },
        { id: "defense.min-flat", label: "下限(守備力等flat)", kind: "number", desc: "守備力などflat値の下限クランプ。" },
        { id: "defense.max-flat", label: "上限(守備力等flat)", kind: "number", desc: "守備力などflat値の上限クランプ。" }
      ]
    },
    {
      title: "バニラ防具ミラー",
      fields: [
        { id: "vanilla-armor.defense-rate-per-point", label: "防御率/armor点", kind: "number", desc: "victim の vanilla armor 1点あたり付与する防御率%。" },
        { id: "vanilla-armor.defense-rate-max", label: "防御率上限", kind: "number", desc: "算出した防御率%の上限クランプ(0..1)。" },
        { id: "vanilla-armor.armor-strength-per-point", label: "防具強度(会心軽減率)/toughness点", kind: "number", desc: "vanilla armor_toughness 1点あたりの防具強度(会心軽減率%)。既定0=バニラ防具は会心軽減に寄与しない(後日config調整するフォールバック)。" }
      ]
    },
    {
      title: "出血 (Bleed DoT)",
      fields: [
        { id: "bleed.tick-interval-ticks", label: "適用間隔(ticks)", kind: "int", desc: "適用間のサーバtick(20=1秒)。" },
        { id: "bleed.ticks", label: "適用回数", kind: "int", desc: "出血が続く適用回数。" }
      ]
    },
    {
      title: "攻撃範囲 (AoE) 全体設定 (旧: 戦闘ダメージタブ)",
      fields: [
        { id: "aoe.hit-players", label: "他プレイヤーも巻き込む", kind: "boolean", desc: "有効にすると他プレイヤーもAoEの範囲ダメージ対象になる(PvP)。既定=モブのみ。半径/割合等は武器個別(item-stats)の aoe-radius・aoe-damage-rate・aoe-max-targets で指定する。" }
      ]
    },
    {
      title: "PvP (player→player 専用の抑制)",
      fields: [
        { id: "pvp.enabled", label: "PvP抑制を有効にする", kind: "boolean", desc: "出荷は抑制ON＋倍率0＝対人ダメージ無し。ここをOFFにすると対人もモブと同じ計算になり、Lv100帯では先に当てた方が確定で即死する。" },
        { id: "pvp.damage-multiplier", label: "ダメージ倍率", kind: "number", desc: "PvPダメージに掛ける倍率。出荷0＝対人ダメージ無し(PvP無し)。1に戻すと抑制は割合上限だけになる。" },
        { id: "pvp.max-damage-percent-of-max-health", label: "1発の上限(最大体力比)", kind: "number", desc: "1発で削れる量を被弾者の最大体力の何割までにするか。既定0.15=倒すのに最低7発かかる。倍率だけに頼らずこれを置いているのは、攻撃力が指数で伸びてもプレイヤーの体力はほぼ一定という構造が根本原因だから — 割合上限はスケールフリーなので攻撃カーブを触っても調整し直しが要らない。0で上限なし。" }
      ]
    },
    {
      title: "日光による炎上ダメージ (2026-07-28)",
      fields: [
        { id: "sunlight-burn.enabled", label: "最大HP割合へ置き換える", kind: "boolean", desc: "OFFでバニラ挙動(1発1.0固定)に戻る。TFのモブ最大HPはLv0のゾンビでも400あるため、バニラのままだと朝になっても敵が炎上で死なない(400秒以上燃え続ける)。" },
        { id: "sunlight-burn.damage-percent-of-max-health", label: "1発のダメージ(最大HP比)", kind: "number", desc: "日光で燃えている間の1発を被弾モブの最大HPの何割にするか。既定0.10=バニラの炎上は1秒に1回なので約10秒で焼き切れる。算出値がバニラより小さい場合はバニラ値のまま(下げる方向には働かない)。対象EntityTypeの一覧は damage.yml の sunlight-burn.mobs を直接編集する(既定は日光焼却される種別のみ。空にすると火属性エンチャントで着火しただけのボスまで溶ける)。" }
      ]
    },
    {
      title: "序盤モブの火力緩和 (2026-07-28)",
      fields: [
        { id: "early-level-attack.enabled", label: "緩和を有効にする", kind: "boolean", desc: "モブ→プレイヤーの基本ダメージに後掛けする倍率。mob-types.yml の attack-power 指数カーブ自体は触らないので、baseを下げたときのように中盤以降の校正がやり直しにならない。" },
        { id: "early-level-attack.until-level", label: "緩和が解けるレベル", kind: "int", desc: "このモブレベル以上は等倍(=従来どおり)。既定10。" },
        { id: "early-level-attack.level-0-multiplier", label: "Lv0の火力倍率", kind: "number", desc: "レベル0のモブに掛かる倍率。ここから「緩和が解けるレベル」に向かって線形に1.0へ戻る。既定0.7(Lv0で0.7倍、Lv5で0.85倍)。1.0で緩和なし。" }
      ]
    },
    {
      title: "装備の耐久ペナルティ (2026-07-30)",
      fields: [
        { id: "durability.dungeon-only", label: "ダンジョン内だけ適用", kind: "boolean", desc: "既定ON=EliteMobsのインスタンスダンジョンの中だけで効く。OFFにすると全ワールドで効く。ダンジョンは致死ダメージをEliteMobs側でキャンセルして「ダウン」へ移すためPlayerDeathEventが一度も発火せず、死亡ペナルティも致死の一撃分のバニラ防具耐久消費も両方失われていた — この節はそれを補うもの。" },
        { id: "durability.respect-unbreaking", label: "耐久力エンチャントを尊重する", kind: "boolean", desc: "ONで減少量を 1/(Lv+1) に縮める(端数は確率で切り上げ=バニラと同じ期待値)。OFFにすると耐久力エンチャントを無視して常に満額減る。" },
        { id: "durability.prevent-break", label: "このペナルティでは壊さない", kind: "boolean", desc: "既定ON=残耐久1で止まる(ペナルティだけで装備が消滅しない)。OFFにするとペナルティで装備が壊れる。バニラの通常使用による破壊はこの設定と無関係。" },
        { id: "durability.on-hit.enabled", label: "被弾時の上乗せを有効にする", kind: "boolean", desc: "被弾1回ごとに防具4部位(＋オフハンド)の耐久を追加で減らす。バニラの消費を置き換えるのではなく上乗せする。キャンセルされる致死の一撃はここでは減らさず、死亡ペナルティ側で回収する(二重取りにしない)。" },
        { id: "durability.on-hit.percent-of-max", label: "被弾1回の減少量(最大耐久比)", kind: "number", desc: "既定0.001=0.1%。ダイヤ胸当て(528)なら0.528→切り捨て0なので、実際は下の下限が効いて1減る。" },
        { id: "durability.on-hit.min-damage", label: "被弾1回の減少量の下限", kind: "int", desc: "割合が端数で0になる装備でも最低これだけ減らす。既定1。0にすると「割合が1点に届かない装備は減らない」設定になる。" },
        { id: "durability.on-hit.include-offhand", label: "被弾時にオフハンドも対象", kind: "boolean", desc: "既定ON(盾など)。メインハンドの武器は被弾では減らさない。" },
        { id: "durability.on-death.enabled", label: "死亡ペナルティを有効にする", kind: "boolean", desc: "死亡(ダンジョンのダウンを含む)時に防具4部位＋両手の耐久を減らす。EliteMobs自前のペナルティはEliteMobs製アイテムしか対象にしないため、これがOFFだとTF装備は死んでも無傷。" },
        { id: "durability.on-death.percent-of-max", label: "死亡1回の減少量(最大耐久比)", kind: "number", desc: "既定0.1=10%。ダイヤ胸当て(528)なら52減る=10回死ぬと壊れる手前まで行く。" },
        { id: "durability.on-death.min-damage", label: "死亡1回の減少量の下限", kind: "int", desc: "既定1。最大耐久が小さい装備で割合が0になる場合の保険。" },
        { id: "durability.on-death.include-hands", label: "死亡時に両手も対象", kind: "boolean", desc: "既定ON=メインハンドの武器とオフハンドも減る。OFFにすると防具4部位だけになる。" }
      ]
    },
    {
      title: "レベル差による足きり (2026-08-09)",
      fields: [
        { id: "level-cutoff.over-level.threshold", label: "低レベル狩り判定のレベル差", kind: "int", desc: "(プレイヤーの戦闘Lv - モブのLv) がこの値以上で発動する。つまり【プレイヤーのほうが高レベル】なときに効く側で、自分より弱いモブを狩り続ける行為を抑制するもの(逆向きの「高レベルモブ判定のレベル差」と対になる)。-1(既定)でこの足きりは無効。以前は combat/mob-overrides.yml にあり、EliteMobsが刻印したダンジョンモブにしか効かなかったが、ここへ移して全モブ共通になった。レベル刻印の無い野良モブは対象外。" },
        { id: "level-cutoff.over-level.exp-rate", label: "低レベル狩り時の経験値倍率", kind: "number", desc: "発動時に経験値へ掛ける倍率(0.0〜1.0)。1.0で無干渉、-1で経験値0(完全に入手不可)。バニラの経験値オーブとTFの戦闘スキルEXPの両方に掛かる。" },
        { id: "level-cutoff.over-level.drop-rate", label: "低レベル狩り時のドロップ確率倍率", kind: "number", desc: "発動時にTF追加ドロップの確率へ掛ける倍率(0.0〜1.0)。1.0で無干渉、-1でTF追加ドロップを一切付けない。バニラ本来のドロップには一切関与しない(モブトラップが完全に死ぬのを防ぐため)。" },
        { id: "level-cutoff.over-level.exp-decay-per-level", label: "経験値倍率の逓減量(レベル差1毎)", kind: "number", desc: "低レベル狩りの発動後、閾値を1レベル超えるごとに経験値倍率からこの値を引く(線形逓減)。既定0=従来どおり閾値到達で一律「低レベル狩り時の経験値倍率」に固定。" },
        { id: "level-cutoff.over-level.drop-decay-per-level", label: "ドロップ確率倍率の逓減量(レベル差1毎)", kind: "number", desc: "低レベル狩りの発動後、閾値を1レベル超えるごとにドロップ確率倍率からこの値を引く(線形逓減)。既定0=逓減なし。" },
        { id: "level-cutoff.over-level.rate-floor", label: "逓減の下限", kind: "number", desc: "経験値/ドロップ確率倍率が逓減し続けても、この値より下には下がらない下限。「低レベル狩り時の経験値倍率/ドロップ確率倍率」を-1にして完全遮断するのとは別軸(逓減を使わないなら無関係)。既定0。" },
        { id: "level-cutoff.under-level.item-threshold", label: "高レベルモブ判定のレベル差", kind: "int", desc: "(モブのLv - プレイヤーの戦闘Lv) がこの値以上で発動する。つまり【モブのほうが高レベル】なときに効く側(上の「低レベル狩り判定のレベル差」と逆向き)。-1でこの足きりは無効。低レベルのままハメ殺しやデスルーラーで高レベルのモブを狩る行為の抑制がこちら側の狙い。出荷値20。※こちらの基準は【戦闘レベル】。下の経験値側は 2026-08-22 から【そのEXPが入る職業のレベル】で判定するので、同じレベル差でも別の数を比べている。※キー名は item-threshold だが経験値側の既定の起点も兼ねる(配備済み設定の値が無言で既定に戻るのを避けるため改名していない)。※パーティでの同行は区別しない ─ 高レベルの人に連れて行ってもらった低レベルも同じだけ削られる(免除を入れると連れて行くだけで抑制を回避できるため)。" },
        { id: "level-cutoff.under-level.exp-rate", label: "高レベルモブを狩ったときの経験値倍率", kind: "number", desc: "発動時に経験値へ掛ける倍率(0.0〜1.0)。1.0で無干渉、-1で経験値0(完全に入手不可)。バニラの経験値オーブとTFの職業EXPの両方に掛かる(判定に使うレベルだけが別 ─ バニラは戦闘レベル、職業EXPはその職業のレベル)。出荷値は1.0で、下の逓減で徐々に削る形。" },
        { id: "level-cutoff.under-level.drop-rate", label: "高レベルモブを狩ったときのドロップ確率倍率", kind: "number", desc: "発動時にTF追加ドロップの確率へ掛ける倍率(0.0〜1.0)。1.0で無干渉、-1でTF追加ドロップを一切付けない(既定・2026-08-18以前の挙動と同じ)。バニラ本来のドロップには一切関与しない。" },
        { id: "level-cutoff.under-level.exp-threshold", label: "経験値だけの発動レベル差(高レベルモブ側)", kind: "int", desc: "経験値の逓減だけを、上の「高レベルモブ判定のレベル差」とは別の起点から始めたいときに書く。-1(既定)なら上の閾値をそのまま使う。出荷値15 ── TF追加ドロップは20差で完全遮断のまま、経験値は15差から絞り始めて30差で0になる(15差=満額 / 20差=0.67倍 / 25差=0.33倍 / 30差以上=0)。※職業EXPの「レベル差」は【そのEXPが入る職業のレベル】との差(2026-08-22 変更)。戦闘レベルは全スキルを畳んだ値なので、軽武器100の純特化でも67にしかならず、軽武器スキル100でもLv100モブとの差が33と判定されて削られていた。" },
        { id: "level-cutoff.under-level.exp-decay-per-level", label: "経験値倍率の逓減量(レベル差1毎・高レベルモブ側)", kind: "number", desc: "発動後、閾値を1レベル超えるごとに経験値倍率からこの値を引く(線形逓減)。出荷値0.067なので、経験値の閾値15から15レベル差が開いた30差で経験値0になる。0にすると逓減なし。" },
        { id: "level-cutoff.under-level.drop-decay-per-level", label: "ドロップ確率倍率の逓減量(レベル差1毎・高レベルモブ側)", kind: "number", desc: "発動後、閾値を1レベル超えるごとにドロップ確率倍率からこの値を引く(線形逓減)。既定0=逓減なし。ドロップ確率倍率が-1(完全遮断)ならこちらは無関係。" },
        { id: "level-cutoff.under-level.rate-floor", label: "逓減の下限(高レベルモブ側)", kind: "number", desc: "高レベルモブ側の経験値/ドロップ確率倍率が逓減し続けても、この値より下には下がらない下限。既定0=0まで絞れる。" }
      ]
    },
    {
      title: "ダンジョンの挑戦レベルに応じた報酬の増減 (2026-08-18)",
      fields: [
        { id: "dungeon-level-reward.enabled", label: "報酬の増減を有効にする", kind: "boolean", desc: "EMダイナミックダンジョンで選んだ挑戦レベル・難易度が高いほど報酬を良くし、低いほど少なくする。⚠効くのはダンジョンインスタンス内で倒したモブだけで、オーバーワールドのモブには一切効かない。判定は「倒したモブのレベル」(=選んだ挑戦レベル±難易度補正)で、プレイヤーとのレベル差は見ない。出荷値は有効。" },
        { id: "dungeon-level-reward.pivot-level", label: "等倍になるモブレベル", kind: "int", desc: "この帯のダンジョンがちょうど規定値(1.00倍)。これより低いダンジョンは規定値より少なく、高いダンジョンは多くなる。片側の上乗せだけにすると頭打ちの倍率を大きく取らないと差が出ないので、低い側を減らすことで頭打ちを下げている。出荷値35。" },
        { id: "dungeon-level-reward.step", label: "何レベルごとに1段変えるか", kind: "int", desc: "報酬は連続ではなくこのレベル数ごとの階段で変わる。EMの難易度 normal/hard/mythic はモブレベルを -5/±0/+5 動かすので、5にしておくと「難易度1段=報酬1段」で対応し、3つの難易度を選び分ける理由になる。0で既定の5扱い。出荷値5。" },
        { id: "dungeon-level-reward.drop-bonus-per-step", label: "TF追加ドロップ確率の増減(1段毎)", kind: "number", desc: "1段ごとにTF追加ドロップの確率へ足す/引く割合。0.08なら1段につき±8%。0でドロップ側の増減は無効。出荷値0.08。バニラ本来のドロップには一切関与しない。" },
        { id: "dungeon-level-reward.drop-bonus-cap", label: "ドロップ側の増加の上限", kind: "number", desc: "ドロップ確率の増加の頭打ち。0.5なら最大+50%(=1.5倍)。0にすると増加側は無効。出荷値0.5(=モブレベル70で頭打ち)。" },
        { id: "dungeon-level-reward.drop-penalty-cap", label: "ドロップ側の減少の下限", kind: "number", desc: "ドロップ確率の減少の頭打ち。0.3なら最小-30%(=0.7倍)。0にすると減少側は無効(=低レベルのダンジョンでも減らない)。出荷値0.3。" },
        { id: "dungeon-level-reward.exp-bonus-per-step", label: "撃破EXPの増減(1段毎)", kind: "number", desc: "1段ごとに撃破EXPへ足す/引く割合。EXPはモブレベル自体でも伸びるのでドロップより緩やかにしてある。0でEXP側の増減は無効。出荷値0.04。" },
        { id: "dungeon-level-reward.exp-bonus-cap", label: "EXP側の増加の上限", kind: "number", desc: "撃破EXPの増加の頭打ち。0.25なら最大+25%。0にすると増加側は無効。出荷値0.25。" },
        { id: "dungeon-level-reward.exp-penalty-cap", label: "EXP側の減少の下限", kind: "number", desc: "撃破EXPの減少の頭打ち。0.2なら最小-20%(=0.8倍)。0にすると減少側は無効。出荷値0.2。" }
      ]
    },
    // 攻撃ステキー対応 / 防御ステキー対応 の欄は撤去(2026-07-24)。2026-07-26 に Java 側の
    // config 経路も撤去され(CMB-31)、キー名は AttackStatKeys / DefenseStatKeys の定数が単一の真実。
    // config からは改名できないので、editor に欄を戻してはいけない。
    {
      title: "combatレベル カーブ (progression/combat-level.yml)",
      fields: [
        { id: "curve.scale", label: "スケール", kind: "number", desc: "combat level = round(max(pillarスコア) * scale)。" },
        { id: "curve.min-level", label: "最小レベル", kind: "int", desc: "クランプ下限。" },
        { id: "curve.max-level", label: "最大レベル", kind: "int", desc: "クランプ上限。" }
      ]
    },
    {
      title: "combatレベル キャッシュ",
      fields: [
        { id: "cache.ttl-seconds", label: "TTL(秒)", kind: "int", desc: "スキルレベル読取キャッシュの有効秒(0..300、0で無効)。" }
      ]
    }
  ];

  window.buildConstantsView = function buildConstantsView(constants) {
    const working = {
      fields: (constants && constants.fields) ? { ...constants.fields } : {},
      pillars: Array.isArray(constants && constants.pillars) ? constants.pillars.map((p) => ({ top: p.top, divisor: p.divisor })) : [],
      skills: (constants && constants.skills) ? { ...constants.skills } : {}
    };

    const root = h("div", { class: "constants-view" });

    for (const group of FIELD_GROUPS) {
      root.appendChild(renderFieldGroup(group, working));
    }
    root.appendChild(renderPillars(working));
    root.appendChild(renderSkills(working));

    return { element: root, getData: () => working };
  };

  function renderFieldGroup(group, working) {
    const body = h("div", { class: "const-body" });
    for (const field of group.fields) {
      body.appendChild(renderField(field, working));
    }
    return h("section", { class: "const-card" }, [
      h("div", { class: "const-card-title", text: group.title }),
      body
    ]);
  }

  function renderField(field, working) {
    const current = working.fields[field.id];
    let control;
    if (field.kind === "boolean") {
      control = window.checkboxInput(current, (v) => { working.fields[field.id] = v; });
    } else if (field.kind === "string") {
      control = window.textInput(current == null ? "" : String(current), (v) => {
        working.fields[field.id] = v.trim();
      });
    } else {
      control = window.numberInput(current, (v) => {
        // 空欄(null)は既存値を保持し、意図しない 0 上書きを避ける。
        if (v === null || v === "") return;
        working.fields[field.id] = v;
      }, { int: field.kind === "int" });
    }
    return h("div", { class: "const-field" }, [
      h("div", { class: "const-field-head" }, [
        h("span", { class: "const-label", text: field.label }),
        control
      ]),
      h("div", { class: "const-desc", text: field.desc })
    ]);
  }

  // pillars: {top:int, divisor:double} の配列 (追加/削除)。
  function renderPillars(working) {
    const card = h("section", { class: "const-card" });
    card.appendChild(h("div", { class: "const-card-title", text: "pillars (柱: 上位top個の合計 / divisor)" }));
    const body = h("div", { class: "const-body" });

    function render() {
      body.innerHTML = "";
      working.pillars.forEach((p, idx) => {
        const row = h("div", { class: "kv-row" }, [
          h("span", { class: "mini-label", text: "top" }),
          window.numberInput(p.top, (v) => { p.top = v == null ? 0 : v; }, { int: true }),
          h("span", { class: "mini-label", text: "divisor" }),
          window.numberInput(p.divisor, (v) => { p.divisor = v == null ? 0 : v; }),
          h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { working.pillars.splice(idx, 1); render(); } })
        ]);
        body.appendChild(row);
      });
      body.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ pillar 追加",
        onclick: () => { working.pillars.push({ top: 1, divisor: 1.0 }); render(); }
      }));
    }
    render();
    card.appendChild(body);
    return card;
  }

  // /api/skills が読めない場合のフォールバックID (server.js の FALLBACK_SKILLS と同一の15スキル)。
  const SKILL_FALLBACK_IDS = [
    "ALCHEMY", "ARCHERY", "ARS_MAGIC", "ARS_SMITHING", "DIGGING", "ENCHANTING",
    "FARMING", "FISHING", "HEAVY_ARMOR", "HEAVY_WEAPONS", "LIGHT_ARMOR",
    "LIGHT_WEAPONS", "MINING", "SMITHING", "WOODCUTTING"
  ];
  // スキルIDの候補一覧。app.js の loadSkills() が /api/skills から window.SKILLS([{id,...}])へ格納済み。
  function skillIdCandidates() {
    return (Array.isArray(window.SKILLS) && window.SKILLS.length)
      ? window.SKILLS.map((s) => s.id) : SKILL_FALLBACK_IDS.slice();
  }

  // skills: スキル名 -> weight のマップ (追加/削除)。
  function renderSkills(working) {
    const card = h("section", { class: "const-card" });
    card.appendChild(h("div", { class: "const-card-title", text: "skills (スキル名 -> weight)" }));
    const body = h("div", { class: "const-body" });

    // スキル名入力のサジェスト用 datalist(候補付き自由入力)。material等と同様に候補源(window.SKILLS)を提示しつつ、
    // 任意文字列の入力(リネーム)も許す。id はビュー内で一意にする。
    const dataListId = "skills-suggest-list";
    const dataList = h("datalist", { id: dataListId });
    for (const id of skillIdCandidates()) dataList.appendChild(h("option", { value: id }));
    card.appendChild(dataList);

    function render() {
      body.innerHTML = "";
      for (const name of Object.keys(working.skills)) {
        body.appendChild(renderSkillRow(name));
      }
      body.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ スキル追加",
        onclick: () => {
          let key = "NEW_SKILL", i = 1;
          while (Object.prototype.hasOwnProperty.call(working.skills, key)) key = `NEW_SKILL_${i++}`;
          working.skills[key] = 1.0;
          render();
        }
      }));
    }

    function renderSkillRow(name) {
      const nameInput = h("input", { class: "field-input", value: name, spellcheck: "false", list: dataListId, autocomplete: "off" });
      nameInput.addEventListener("change", (e) => {
        const nv = e.target.value.trim();
        if (!nv || nv === name) { e.target.value = name; return; }
        if (Object.prototype.hasOwnProperty.call(working.skills, nv)) { alert("同名スキルが既に存在します"); e.target.value = name; return; }
        const rebuilt = {};
        for (const k of Object.keys(working.skills)) rebuilt[k === name ? nv : k] = working.skills[k];
        working.skills = rebuilt;
        render();
      });
      return h("div", { class: "kv-row" }, [
        nameInput,
        h("span", { class: "mini-label", text: "weight" }),
        window.numberInput(working.skills[name], (v) => { working.skills[name] = v == null ? 0 : v; }),
        h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { delete working.skills[name]; render(); } })
      ]);
    }

    render();
    card.appendChild(body);
    return card;
  }
})();
