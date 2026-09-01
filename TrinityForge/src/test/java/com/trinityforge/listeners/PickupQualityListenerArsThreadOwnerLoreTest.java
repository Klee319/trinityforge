package com.trinityforge.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ars スレッドの所有者刻印は、PDC だけで止めず専用 lore を再構築して表示へも反映する。
 */
class PickupQualityListenerArsThreadOwnerLoreTest {

    @Test
    @DisplayName("Arsスレッドへ所有者を刻むと専用lore再構築と所有者行追記を同じ操作で行う")
    void ownerStampRefreshesAndRepairsArsThreadLoreImmediately() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "trinityforge", "listeners", "PickupQualityListener.java"),
                StandardCharsets.UTF_8);

        int method = source.indexOf("boolean stampOwnerIfEligible");
        assertTrue(method >= 0, "所有者刻印メソッドが無い");
        int arsThreadBranch = source.indexOf("if (hasArsThreadMarker(meta))", method);
        assertTrue(arsThreadBranch >= 0, "Arsスレッドの専用分岐が無い");
        String branch = source.substring(arsThreadBranch,
                source.indexOf("itemFactory.stamp", arsThreadBranch));
        assertTrue(branch.contains("defaultArsThreadLoreRefresh(stack)"),
                "所有者PDCだけを書いてreturnしている。スレッド枠から戻った直後も表示を保つため専用loreを再構築すること");
        assertTrue(branch.contains("itemFactory.appendOwnerLoreIfMissing(stack)"),
                "専用lore再構築後に所有者行を戻していない。PDCと表示が不整合になる");
    }
}
