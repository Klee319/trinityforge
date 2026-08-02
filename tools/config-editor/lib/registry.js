"use strict";

// 編集対象config の論理定義。
// - id      : APIとURLで使う論理ID (英数/ハイフンのみ)
// - label   : サイドバー表示名 (日本語)
// - group   : "TrinityForge" | "ArsPaper" | "TrinityForgeSkill" (出自プラグイン。互換のため維持)
// - section : サイドバー表示用のドメイン区分キー。出自を跨いで機能単位でまとめる。
//             "quality" | "recipes-magic" | "skilltree" | "skill-defs" | "mobs-dungeon"
//             サイドバー側は section 未指定/未知の値を "skill-defs" にフォールバックする。
// - base    : tool-config.json の basePaths のキー
// - rel      : base からの相対パス (POSIX区切り。OS依存解決は path.join で行う)
// - schema  : フロントのフォーム種別 ("item-stats"|"catalog"|"generic")
//
// パストラバーサル対策として、サーバは常にこの registry の rel のみを解決する。
// リクエストの id が registry に無ければ 404。ユーザ入力のパスは一切受け付けない。

const REGISTRY = Object.freeze([
  // ---- TrinityForge ----
  // NOTE: item-stats/catalog/materials はスプリットビュー専用(SPLIT_HIDDEN_IDS)でサイドバーには出ないが、
  // section は実在キー(quality=ステータス定義)にしておく — 旧 items-equipment セクションは削除済みのため。
  { id: "item-stats", label: "アイテム個別ステ (item-stats)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "stats/item-stats.yml", schema: "item-stats" },
  { id: "quality", label: "品質定義 (quality)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "stats/quality.yml", schema: "tf-quality" },
  // craft-quality (クラフト/ドロップ品質) は品質定義タブへ統合表示。サイドバーには出さず、
  //   品質定義(quality)ビュー内で mode/drop を編集し、保存時に app.js が一緒に PUT する。
  //   ars-smithing EXP は skill-exp.yml (下) へ分離済み。
  { id: "craft-quality", label: "品質定義（クラフト/ドロップ） (craft-quality)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "stats/craft-quality.yml", schema: "tf-craft-quality" },
  { id: "skill-exp", label: "スキルEXP獲得", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "stats/skill-exp.yml", schema: "tf-skill-exp" },
  // Progression curve SoT (skills/base/*_progression.yml). Hidden from sidebar; edited as companions
  // on the skill-exp screen (gain rates + curves). These are TF's native progression source.
  { id: "progression-alchemy", label: "曲線: alchemy", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/alchemy_progression.yml", schema: "generic" },
  { id: "progression-archery", label: "曲線: archery", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/archery_progression.yml", schema: "generic" },
  { id: "progression-digging", label: "曲線: digging", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/digging_progression.yml", schema: "generic" },
  { id: "progression-enchanting", label: "曲線: enchanting", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/enchanting_progression.yml", schema: "generic" },
  { id: "progression-farming", label: "曲線: farming", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/farming_progression.yml", schema: "generic" },
  { id: "progression-fishing", label: "曲線: fishing", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/fishing_progression.yml", schema: "generic" },
  { id: "progression-heavy_armor", label: "曲線: heavy_armor", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/heavy_armor_progression.yml", schema: "generic" },
  { id: "progression-heavy_weapons", label: "曲線: heavy_weapons", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/heavy_weapons_progression.yml", schema: "generic" },
  { id: "progression-light_armor", label: "曲線: light_armor", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/light_armor_progression.yml", schema: "generic" },
  { id: "progression-light_weapons", label: "曲線: light_weapons", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/light_weapons_progression.yml", schema: "generic" },
  { id: "progression-mining", label: "曲線: mining", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/mining_progression.yml", schema: "generic" },
  { id: "progression-power", label: "曲線: power", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/power_progression.yml", schema: "generic" },
  { id: "progression-smithing", label: "曲線: smithing", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/smithing_progression.yml", schema: "generic" },
  { id: "progression-woodcutting", label: "曲線: woodcutting", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/woodcutting_progression.yml", schema: "generic" },
  { id: "progression-ars_magic", label: "曲線: ars_magic", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/ars_magic_progression.yml", schema: "generic" },
  { id: "progression-ars_smithing", label: "曲線: ars_smithing", group: "TrinityForge", section: "skilltree", base: "trinityforge", rel: "skills/base/ars_smithing_progression.yml", schema: "generic" },
  { id: "quality-tiers", label: "品質ティア (quality-tiers)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "stats/quality-tiers.yml", schema: "tf-quality-tiers" },
  // ポーション品質(potion_quality_bonus stat)の時間/強度換算 + エンチャント運(enchant_luck stat)の
  // 重み付け(2026-07-25、かまど/エンチャント/ポーション実行者限定ステ反映)。
  { id: "alchemy-quality", label: "ポーション品質換算 (alchemy-quality)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "stats/alchemy-quality.yml", schema: "generic" },
  { id: "enchant-luck", label: "エンチャント運 (enchant-luck)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "stats/enchant-luck.yml", schema: "generic" },
  // 採集効率(gathering_efficiency stat。旧称:最終効率)を効率強化エンチャントのレベルへ換算する設定。
  // 2026-07-26: tool-enchant-efficiency を統合し上限を撤廃(max-enchant-level: 0 = 無制限)したため、
  // 上限を戻したい運用者が editor から触れるよう登録。
  // T8 (2026-07-26): 「設定1個のためだけの独立カテゴリ」というユーザー指摘により、サイドバー単独表示を
  // 廃止し「プレイヤー基礎ステータス」画面内の「上限」タブへ統合表示する(app.js の
  // STAT_CAPS_COMPANION_IDS 経由、HIDDEN_CONFIG_IDS でサイドバーから隠す)。ファイル自体・保存先
  // キーパス(max-enchant-level)は不変。旧ファイルは combat/stat-caps.yml の
  // gathering-efficiency-max-enchant-level が未設定の間、後方互換として引き続き読み込まれる。
  { id: "gathering-efficiency", label: "採集効率の上限 (gathering-efficiency)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "stats/gathering-efficiency.yml", schema: "generic" },
  { id: "lore", label: "ロア表示 (lore)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "stats/lore.yml", schema: "tf-lore" },
  { id: "player-base-stats", label: "プレイヤー基礎ステータス (base-stats)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "combat/base-stats.yml", schema: "tf-base-stats" },
  // T8 (2026-07-26新設): 総合ステータス上限(combat/stat-caps.yml)。「プレイヤー基礎ステータス」画面の
  // 「上限」タブから編集する専用コンパニオン(getExtraSaves 経由)。サイドバーには出さない
  // (HIDDEN_CONFIG_IDS 経由)。schema は generic のまま(専用バリデータ tf-stat-caps を別途登録)。
  { id: "stat-caps", label: "ステータス上限 (stat-caps)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "combat/stat-caps.yml", schema: "tf-stat-caps" },
  // tool-enchants: 廃止。ツールエンチャントは item-stats の補助ステ(整数閾値: per-quality小数を加算し
  //   整数化した分だけ上昇=切り捨て)へ統合。属性マップ(attribute-map)/アイテム分類(item-categories)は
  //   Java側でハードコード化したため config から削除。
  { id: "catalog", label: "アイテムカタログ (catalog)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "items/catalog.yml", schema: "catalog" },
  { id: "external-items", label: "外部カスタムアイテム", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "items/external-items.yml", schema: "external-items" },
  // 素材互換リスト: レシピ素材欄の list:<id> トークンの解決先。カタログ画面の
  // 「互換リスト」モーダルから編集する (SPLIT_HIDDEN_IDS でサイドバー非表示)。
  { id: "material-lists", label: "素材互換リスト (material-lists)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "items/material-lists.yml", schema: "tf-material-lists" },
  { id: "gacha", label: "ガチャ (gacha)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "gacha.yml", schema: "tf-gacha" },
  // gathering (採集・ドロップ) は2026-07-23廃止。採掘欄(fortune-*)は mining-gimmick タブへ、
  // 釣り欄(skill-id/luck-per-level/bonus-per-level)は fishing-gimmick タブへ統合済み。
  // stats/gathering.yml 自体の削除・値移行は別ウェーブ (TF側変更と同時に実施)。
  { id: "mining-gimmick", label: "採掘ギミック (mining-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "stats/mining-gimmick.yml", schema: "tf-mining-gimmick" },
  // 単一float(ホッパー自動投入時の精錬速度/ボーナス減衰倍率, 0..1)の専用フォームで編集する。
  // T6 (2026-07-26): タブ名を「精錬ギミック」→「鍛冶ギミック」へリネーム。加えて crafting-features.yml の
  // disassembly(解体)サブツリーをこのタブへコンパニオン表示する(保存先は変わらず crafting-features)。
  { id: "smithing-gimmick", label: "鍛冶ギミック (smithing-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "stats/smithing-gimmick.yml", schema: "tf-smithing-gimmick" },
  // T6 (2026-07-26): crafting-features.yml の wood-repair(圧縮木材修繕)サブツリーをこのタブへ
  // コンパニオン表示する(保存先は変わらず crafting-features)。
  { id: "woodcutting-gimmick", label: "伐採ギミック (woodcutting-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "stats/woodcutting-gimmick.yml", schema: "tf-woodcutting-gimmick" },
  { id: "digging-gimmick", label: "掘削ギミック (digging-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "stats/digging-gimmick.yml", schema: "tf-digging-gimmick" },
  { id: "farming-gimmick", label: "農業ギミック (farming-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "stats/farming-gimmick.yml", schema: "tf-farming-gimmick" },
  // T6 (2026-07-26): 「食事ギミック」単独タブは廃止し、農業ギミック(farming-gimmick)タブへ表示統合。
  // ファイル自体(stats/food-gimmick.yml)は不変。サイドバー個別一覧からは隠す
  // (app.js の FARMING_GIMMICK_COMPANION_IDS 経由、farming-gimmick 画面内から getExtraSaves で保存)。
  { id: "food-gimmick", label: "食事ギミック (food-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "stats/food-gimmick.yml", schema: "tf-food-gimmick" },
  { id: "fishing-gimmick", label: "釣りギミック (fishing-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "stats/fishing-gimmick.yml", schema: "tf-fishing-gimmick" },
  // T6 (2026-07-26): 「その他ギミック」(crafting-features)から over-enchant / potion-merge+brew-unlocks を
  // 独立タブへ切り出し。物理ファイルは同じ progression/crafting-features.yml のまま(丸ごと読み込み・
  // 担当サブツリーのみ描画・保存時は丸ごと書き戻す方式)。fileRevision() はファイルパス基準のため、
  // 複数タブ(crafting-features / enchant-gimmick / brew-gimmick / smithing-gimmick / woodcutting-gimmick の
  // コンパニオン経由)が同じ物理ファイルを編集しても楽観ロックは正しく機能する。
  { id: "enchant-gimmick", label: "エンチャントギミック (enchant-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "progression/crafting-features.yml", schema: "tf-enchant-gimmick" },
  { id: "brew-gimmick", label: "醸造ギミック (brew-gimmick)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "progression/crafting-features.yml", schema: "tf-brew-gimmick" },
  { id: "hate-rates", label: "ヘイト倍率 (hate/rates)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "hate/rates.yml", schema: "tf-hate-rates" },
  { id: "combat-display", label: "頭上表示 (combat/display)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "combat/display.yml", schema: "tf-combat-display" },
  { id: "crafting-features", label: "その他のギミック (crafting-features)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "progression/crafting-features.yml", schema: "tf-crafting-features" },
  // 使用制限スイッチは「ステータス定義」(section: quality) メインカテゴリへ移動（旧「装備・制限」廃止）。
  { id: "use-requirements", label: "使用制限スイッチ (use-requirements)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "progression/use-requirements.yml", schema: "tf-use-requirements" },
  // AFK(離席)判定(afk.yml)は独立タブを作らず「使用制限スイッチ」画面内へ AFK セクションとして
  // コンパニオン表示する(2026-07-27新設)。app.js の USE_REQUIREMENTS_COMPANION_IDS 経由で
  // サイドバーから隠し、use-requirements 画面の getExtraSaves で一緒に保存する。
  { id: "afk", label: "AFK(離席)判定 (afk)", group: "TrinityForge", section: "quality", base: "trinityforge", rel: "afk.yml", schema: "tf-afk" },
  { id: "villager-trades", label: "村人取引 (villager-trades)", group: "TrinityForge", section: "skill-gimmicks", base: "trinityforge", rel: "economy/villager-trades.yml", schema: "tf-villager-trades" },
  { id: "role-buffs", label: "ロールバフ (role-buffs)", group: "TrinityForge", section: "other", base: "trinityforge", rel: "progression/role-buffs.yml", schema: "tf-role-buffs" },
  // 特殊報酬レジストリ(称号/パーティクル/パーティクルシード)。skilltree/図鑑/アチーブの3箇所から
  // reward:<id> / special:[] で共通参照する (設計書 2026-07-23-stat-gate-overhaul.md §6.1/§6.7)。
  { id: "special-rewards", label: "特殊報酬 (special-rewards)", group: "TrinityForge", section: "other", base: "trinityforge", rel: "progression/special-rewards.yml", schema: "tf-special-rewards" },
  { id: "achievements", label: "アチーブメント (achievements)", group: "TrinityForge", section: "other", base: "trinityforge", rel: "progression/achievements.yml", schema: "tf-achievements" },
  { id: "collection", label: "図鑑 (collection)", group: "TrinityForge", section: "other", base: "trinityforge", rel: "progression/collection.yml", schema: "tf-collection" },
  { id: "dungeon-gates", label: "ダンジョンゲート (dungeon/gates)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "dungeon/gates.yml", schema: "tf-dungeon-gates" },
  { id: "dungeon-themes", label: "ダンジョンテーマ (dungeon/themes)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "dungeon/themes.yml", schema: "tf-dungeon-themes" },
  { id: "mob-types", label: "モブ定義 (mob-types)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "combat/mob-types.yml", schema: "tf-mob-types" },
  { id: "mob-level-table", label: "レベルテーブル (mob-level-table)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "combat/mob-level-table.yml", schema: "tf-mob-level-table" },
  { id: "mob-profiles", label: "モブプロファイル (mob-profiles)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "combat/mob-profiles.yml", schema: "tf-mob-profiles" },
  // ダンジョン(ワールド)×EliteMobsモブid単位の強さ/ドロップオーバーライド(2026-07-26新設)。
  // mob-profiles.yml(自動生成・直接編集禁止)の上に重ねる層。
  { id: "mob-overrides", label: "モブオーバーライド (mob-overrides)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "combat/mob-overrides.yml", schema: "tf-mob-overrides" },
  { id: "mob-abilities", label: "敵の特殊攻撃 (mob-abilities)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "combat/mob-abilities.yml", schema: "tf-mob-abilities" },
  { id: "mob-import", label: "モブインポート (mob-import)", group: "TrinityForge", section: "mobs-dungeon", base: "trinityforge", rel: "combat/mob-import.yml", schema: "tf-mob-import" },
  // combat-damage (旧: 戦闘ダメージタブ) は削除。combat/damage.yml の各フィールドは
  // 「共通変数（戦闘定数）」ビュー (__constants__, /api/constants) から編集する。

  // ---- ArsPaper ----
  { id: "thread-sets", label: "スレッドセット効果 (thread-sets)", group: "ArsPaper", section: "recipes-magic", base: "arspaper", rel: "thread-sets.yml", schema: "ars-thread-sets" },
  // 2026-08-02: スレッド厳選(旧 thread-rolls.yml)は TrinityForge の item-stats.yml へ
  // 全面移設した。専用GUI(random-roll-pools 専用セクション)は同日中に撤去済みで、
  // スレッドは他アイテムと同じ fixed/per-quality/random/advanced フォームで編集する
  // (サイドバーの「アイテムステータス > スレッド」タブ、__stats_thread__、item-stats 画面)。
  // このフォークの thread-rolls.yml 自体を削除したので、config id/schema ごと除去する
  // (残すと「開くとファイルが無くてエラーになる死んだタブ」になる)。
  { id: "items", label: "儀式エフェクト (ritual effects)", group: "ArsPaper", section: "recipes-magic", base: "arspaper", rel: "items.yml", schema: "ars-recipes" },
  // 機能アイテム(ワンド/コンパス/台座/儀式の核/筆記台/ウェイストーン/ソースベリー)の表示名 +
  // レシピ(items.yml/catalog.yml側の該当recipeへ配線)をまとめて編集するタブ。
  // 2026-07-27: TF の特殊アイテム2件(skill_node_lock/skill_tree_reset, catalog.yml)も統合したため
  // 画面名を「特殊アイテム」へリネーム(このconfig自体のid/labelキーは functional-items のまま。
  // サイドバー表示は label 括弧部分が落ちる仕様(app.js)を利用して「特殊アイテム」に見せる)。
  // section も「魔法」から新設の「機能アイテム」カテゴリ(functional-items)へ移動。
  { id: "functional-items", label: "特殊アイテム (表示名/レシピ) (functional-items)", group: "ArsPaper", section: "functional-items", base: "arspaper", rel: "functional-items.yml", schema: "ars-functional-items" },
  { id: "materials", label: "中間素材 (materials)", group: "ArsPaper", section: "quality", base: "arspaper", rel: "materials.yml", schema: "ars-materials" },
  { id: "threads", label: "スレッド (threads)", group: "ArsPaper", section: "recipes-magic", base: "arspaper", rel: "threads.yml", schema: "ars-threads" },
  { id: "glyphs", label: "グリフ (glyphs)", group: "ArsPaper", section: "recipes-magic", base: "arspaper", rel: "glyphs.yml", schema: "ars-glyphs" },
  // グリフダメージブースト対象一覧 (TrinityForge base)。グリフ(glyphs)画面のコンパニオンとして編集する
  // (サイドバー非表示、HIDDEN_CONFIG_IDS 経由)。
  { id: "glyph-damage-boost", label: "グリフダメージブースト (glyph-damage-boost)", group: "ArsPaper", section: "recipes-magic", base: "trinityforge", rel: "stats/glyph-damage-boost.yml", schema: "tf-glyph-damage-boost" },
  { id: "spellbooks", label: "魔導書ティア (spellbooks)", group: "ArsPaper", section: "recipes-magic", base: "arspaper", rel: "spellbooks.yml", schema: "ars-spellbooks" },
  { id: "ars-config", label: "ArsPaper 全体設定 (config)", group: "ArsPaper", section: "recipes-magic", base: "arspaper", rel: "config.yml", schema: "ars-config" },
  { id: "ban", label: "グリフBANリスト (ban)", group: "ArsPaper", section: "recipes-magic", base: "arspaper", rel: "ban.yml", schema: "ars-ban" },
  // 2026-07-27: section を「魔法」から新設の「機能アイテム」カテゴリ(functional-items)へ移動
  // (TF/Ars統合の一環。特殊アイテムと合わせてサイドバーの同じグループへ集約)。
  { id: "sourcelinks", label: "ソースリンク (sourcelinks)", group: "ArsPaper", section: "functional-items", base: "arspaper", rel: "sourcelinks.yml", schema: "ars-sourcelinks" },
  { id: "sourcejars", label: "ソースジャー (sourcejars)", group: "ArsPaper", section: "functional-items", base: "arspaper", rel: "sourcejars.yml", schema: "ars-sourcejars" },
  // 2026-07-31新設: 構造物ルートチェストへの追加抽選。ダンジョン/構造物の話なので section は
  // モブダンジョン側へ置く(base は arspaper)。データパック(Dungeons and Taverns 等)の
  // ルートテーブルを namespace ワイルドカードで対象にできる。
  { id: "loot-tables", label: "構造物ルート抽選 (loot-tables)", group: "ArsPaper", section: "mobs-dungeon", base: "arspaper", rel: "loot-tables.yml", schema: "ars-loot-tables" },

  // ---- TrinityForge スキルツリー (skilltree/*.yml) ----
  // TF独自の「単一の真実」。TFネイティブ進行とGUIが直接読み込む。
  // ノード木(name/level/role/parent/group/buffs/native/commands 等)が複雑なため汎用(構造)エディタで編集する。
  // dedicated-effects(解放効果)はプレフィックス付き動的ID。各ノードのdedicated-effects配列内で直接編集する
  // (専用効果カタログUIは廃止済み。語彙は /api/gate-vocabulary から供給)。
  // skills/ars_*.yml はARSスキルの補助表示メタのみ（display_name/icon/levelbar）。エディタ価値が薄いため非掲載。
  // ファイル自体は jar リソースとして残し、必要なら直接 YAML 編集。
  { id: "skilltree-light-weapons", label: "スキル: 軽量武器 (light_weapons)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/light_weapons.yml", schema: "tf-skilltree" },
  { id: "skilltree-heavy-weapons", label: "スキル: 重量武器 (heavy_weapons)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/heavy_weapons.yml", schema: "tf-skilltree" },
  { id: "skilltree-archery", label: "スキル: 弓術 (archery)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/archery.yml", schema: "tf-skilltree" },
  { id: "skilltree-light-armor", label: "スキル: 軽装備 (light_armor)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/light_armor.yml", schema: "tf-skilltree" },
  { id: "skilltree-heavy-armor", label: "スキル: 重装備 (heavy_armor)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/heavy_armor.yml", schema: "tf-skilltree" },
  { id: "skilltree-ars-magic", label: "スキル: Ars魔法 (ars_magic)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/ars_magic.yml", schema: "tf-skilltree" },
  { id: "skilltree-ars-smithing", label: "スキル: Ars鍛冶 (ars_smithing)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/ars_smithing.yml", schema: "tf-skilltree" },
  { id: "skilltree-smithing", label: "スキル: 鍛冶 (smithing)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/smithing.yml", schema: "tf-skilltree" },
  { id: "skilltree-mining", label: "スキル: 採掘 (mining)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/mining.yml", schema: "tf-skilltree" },
  { id: "skilltree-digging", label: "スキル: 切削 (digging)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/digging.yml", schema: "tf-skilltree" },
  { id: "skilltree-woodcutting", label: "スキル: 伐採 (woodcutting)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/woodcutting.yml", schema: "tf-skilltree" },
  { id: "skilltree-farming", label: "スキル: 農業 (farming)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/farming.yml", schema: "tf-skilltree" },
  { id: "skilltree-fishing", label: "スキル: 釣り (fishing)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/fishing.yml", schema: "tf-skilltree" },
  { id: "skilltree-alchemy", label: "スキル: 錬金 (alchemy)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/alchemy.yml", schema: "tf-skilltree" },
  { id: "skilltree-enchanting", label: "スキル: エンチャント (enchanting)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/enchanting.yml", schema: "tf-skilltree" },
  { id: "skilltree-power", label: "スキル: 総合 (power)", group: "TrinityForgeSkill", section: "skilltree", base: "trinityforge", rel: "skilltree/power.yml", schema: "tf-skilltree" }
]);

function findById(id) {
  return REGISTRY.find((entry) => entry.id === id) || null;
}

module.exports = { REGISTRY, findById };
