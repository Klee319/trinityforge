package com.trinityforge.listeners;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-07-26 魔法破壊ガードのドリフト検知。
 *
 * <p>ArsPaperフォークとTFはコンパイル時に結合しない方針なので、合成 {@code BlockBreakEvent} に立てる
 * block metadata キーは {@link SpellBreakGuard#METADATA_KEY} と
 * {@code com.arspaper.spell.effect.SpellBreakMarker.METADATA_KEY} に<b>文字列として二重定義</b>されている。
 * 片方だけを変更するとコンパイルもテストも通ったまま<b>ガードが静かに全不発</b>になり、農業ギミック×
 * 破壊グリフの永久機関（本ガードの主目的）が復活する。値が一致していることを機械的に固定する。
 *
 * <p>TFのテストは作業ディレクトリが {@code TrinityForge/} なので、フォークのソースは
 * {@code ../fork-handoff/...} で参照する（{@code LegacyValhallaRuntimeContentTest} と同じ規約）。
 */
class SpellBreakMarkerDriftTest {

    private static final Path FORK_MARKER = Path.of(
            "../fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/effect/SpellBreakMarker.java");

    private static final Pattern KEY = Pattern.compile(
            "METADATA_KEY\\s*=\\s*\"([^\"]+)\"");

    @Test
    void forkAndTrinityForgeAgreeOnTheMarkerKey() throws IOException {
        assertTrue(Files.exists(FORK_MARKER),
                "フォーク側のSpellBreakMarker.javaが見つからない(移動したならこのテストの相対パスも直すこと): "
                        + FORK_MARKER.toAbsolutePath());

        Matcher matcher = KEY.matcher(Files.readString(FORK_MARKER));
        assertTrue(matcher.find(), "フォーク側のMETADATA_KEY定義を読み取れなかった: " + FORK_MARKER);

        assertEquals(SpellBreakGuard.METADATA_KEY, matcher.group(1),
                "フォークとTFでマーカーキーが食い違っている。片側だけ変えるとガードが静かに全不発になり、"
                        + "農業ギミック×破壊グリフの永久機関が復活する。必ず両側を同時に直すこと。");
    }

    @Test
    void forkActuallySetsAndRemovesTheMarkerAroundCallEvent() throws IOException {
        // セット/発火/finally除去は共通ヘルパーに集約する。各エフェクトへ直接コピーすると、新しい
        // 破壊経路だけ片方を欠くドリフトが再発するため、ここではヘルパー自身の契約を固定する。
        String markerSource = Files.readString(FORK_MARKER);
        assertTrue(markerSource.contains("setMetadata(METADATA_KEY"),
                "SpellBreakMarker が魔法破壊マーカーを立てていない");
        assertTrue(markerSource.contains("callEvent(event)"),
                "SpellBreakMarker がマーカー中に合成BlockBreakEventを発火していない");
        assertTrue(markerSource.contains("finally"),
                "SpellBreakMarker の除去がfinallyで保護されていない");
        assertTrue(markerSource.contains("removeMetadata(METADATA_KEY"),
                "SpellBreakMarker がマーカーを除去していない");

        // 魔法で実ブロックを破壊する全経路が、上の例外安全な共通ヘルパーを通ることも固定する。
        for (String file : new String[] {
                "AdvancedBreakEffect.java",
                "BreakEffect.java",
                "ExplosionEffect.java",
                "FellEffect.java",
                "CrushEffect.java",
                "CutEffect.java",
                "HarvestEffect.java",
                "SmeltEffect.java"
        }) {
            Path path = Path.of(
                    "../fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/effect/" + file);
            assertTrue(Files.exists(path), "フォーク側の破壊エフェクトが見つからない: " + path.toAbsolutePath());

            String source = Files.readString(path);
            assertTrue(source.contains("SpellBreakMarker.callMarkedBreakEvent("),
                    file + " が共通マーカーヘルパーを通していない"
                            + "(TF側の採取系ガードが全不発、またはマーカー残留になる)");
        }
    }
}
