package com.trinityforge.listeners;

import org.bukkit.block.Block;

/**
 * 魔法(Ars)破壊マーカー共通ヘルパー(2026-07-26)。
 *
 * <p>ArsPaperフォーク({@code com.arspaper.spell.effect.AdvancedBreakEffect}/{@code BreakEffect})は、
 * 保護プラグイン互換のために合成 {@link org.bukkit.event.block.BlockBreakEvent} を発火する際、対象
 * {@link Block} へ block metadata "{@value #METADATA_KEY}" を立てる(callEvent直前にセットし、finally
 * で必ず除去する)。TF側の採取系リスナーは全てこのマーカーを検知したら即returnし、魔法破壊にTFの
 * 採取恩恵(ギミック/スキルEXP/ドロップテーブル)を一切与えない。
 *
 * <p>メインハンドが杖かどうかでの判定は採用しない — ArsPaperの{@code SpellBindListener}は任意アイテム
 * にスペルをバインドできるため、ピッケルへバインドすれば判定をすり抜け「正しいツールを持ったまま魔法
 * 破壊」というフルドロップ+一括破壊+耐久ゼロの上位互換exploitになる(2026-07-26設計判断)。
 *
 * <p>フォークとTFはコンパイル時に結合しない方針(jarのロード順/API再生成問題を避ける)なので、このキー
 * 文字列はフォーク側 {@code com.arspaper.spell.effect.SpellBreakMarker} に個別定義されている —
 * 変更する場合は必ず両側を同時に直すこと。
 */
public final class SpellBreakGuard {

    public static final String METADATA_KEY = "trinityforge:spell-break";

    private SpellBreakGuard() {
    }

    /** @return true ならこの破壊は魔法(Ars)由来の合成イベントなので、採取系ギミックを一切発動しない。 */
    public static boolean isSpellBreak(Block block) {
        return block != null && block.hasMetadata(METADATA_KEY);
    }
}
