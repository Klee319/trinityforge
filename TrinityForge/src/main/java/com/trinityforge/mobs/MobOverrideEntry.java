package com.trinityforge.mobs;

import com.trinityforge.mobs.ConversionPolicy.Ramp;

import java.util.List;

/**
 * One {@code mobs.<mobId>} entry of a {@code combat/mob-overrides.yml} world (or {@code default}) block
 * (2026-07-26 ダンジョン×モブ単位オーバーライド新設): the "強さ" (strength) field-level override, the
 * "ドロップテーブル" (drop table) and the "経験値の式" (vanilla EXP ramp) for that mob in that scope.
 *
 * @param stats      strength override (項目単位マージ, may be {@link MobStatOverride#EMPTY})
 * @param drops      drop table; empty = this scope has none configured for this mob (does not by itself mean
 *                   "no drops" — {@code MobOverridesConfig#dropsFor} still falls through to a lower-priority
 *                   scope, see its javadoc for the full replace-not-merge resolution)
 * @param vanillaExp per-mob vanilla EXP ramp evaluated at the kill's stamped combat level (2026-07-26
 *                   「モブごとにレベルに応じた経験値の式を設定したい」), or {@code null} when this scope
 *                   configures none — same "fall through to the lower-priority scope" contract as
 *                   {@code drops}. Deliberately a ramp and not an absolute number: 265 of the imported
 *                   dungeon mobs are {@code level: dynamic} and follow the level the player picks on
 *                   entry, so an absolute EXP value would pay out the same at Lv1 and Lv100.
 * @param displayName human-readable (日本語) name for this mob shown by the config editor in place of the
 *                    raw EliteMobs id (2026-07-26 「モブの表示名もGUI/簡易モードで設定可能に」), or
 *                    {@code null}/blank when none is set. Presentation metadata only — nothing in the
 *                    combat pipeline reads it, and it never affects how a mob is matched or resolved.
 * @param abilities  このモブが撃つ特殊攻撃テンプレートID(2026-07-31、{@code combat/mob-abilities.yml} の
 *                   キー)。空リストはこのスコープが何も設定していないことを意味し、{@code drops} と同じく
 *                   下位スコープへフォールスルーする(「特殊攻撃なし」を意味しない)。
 *                   <b>ここに書けるのはIDだけ</b>で、数値はテンプレート側にある — モブは396体あるので、
 *                   個別に数値を書き下すとバランス調整のたびに396箇所を直すことになる。
 *
 * <p><b>「レベル差による足きり」({@code levelCutoff}) は 2026-08-09 に撤去した。</b> ダンジョン×モブ単位で
 * 持つ意味が無い設定だった — この足きりは EliteMobs のスタンプがあるモブにしか効かず、フィールドの
 * 野良モブを素通りさせていた。全モブ共通の {@code combat/damage.yml} の {@code level-cutoff:} へ移し、
 * 適用は {@code com.trinityforge.listeners.KillRewardAdjuster} が一手に引き受ける。
 */
public record MobOverrideEntry(MobStatOverride stats, List<MobOverrideDropEntry> drops, Ramp vanillaExp,
                                String displayName, List<String> abilities) {

    public MobOverrideEntry {
        stats = stats == null ? MobStatOverride.EMPTY : stats;
        drops = drops == null ? List.of() : List.copyOf(drops);
        displayName = displayName == null || displayName.isBlank() ? null : displayName;
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
    }

    /** Back-compat: an entry carrying no ability list (the pre-2026-07-31 shape). */
    public MobOverrideEntry(MobStatOverride stats, List<MobOverrideDropEntry> drops, Ramp vanillaExp,
                             String displayName) {
        this(stats, drops, vanillaExp, displayName, null);
    }

    /** Back-compat: an entry carrying no display name (the pre-2026-07-26 three-field shape). */
    public MobOverrideEntry(MobStatOverride stats, List<MobOverrideDropEntry> drops, Ramp vanillaExp) {
        this(stats, drops, vanillaExp, null, null);
    }

    /** Back-compat: an entry carrying no EXP ramp and no display name (the original two-field shape). */
    public MobOverrideEntry(MobStatOverride stats, List<MobOverrideDropEntry> drops) {
        this(stats, drops, null, null, null);
    }
}
