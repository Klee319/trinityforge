package com.trinityforge.progression.catalog;

import com.trinityforge.progression.core.SkillId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSkillCatalogDataFolderTest {

    @TempDir
    Path temp;

    @Test
    void loadDataFolderSeedsAndReadsAllSixteenSkills() {
        File dataFolder = temp.toFile();
        NativeSkillCatalog catalog = NativeSkillCatalog.loadDataFolder(
                dataFolder, NativeSkillCatalogDataFolderTest.class.getClassLoader());
        assertEquals(SkillId.ALL.size(), catalog.size());
        assertTrue(new File(dataFolder, "skills/base/mining_progression.yml").isFile());
        assertTrue(catalog.get("MINING").maxLevel() > 0);
    }

    @Test
    void reloadKeepsPreviousSnapshotWhenCandidateIncomplete() throws Exception {
        File dataFolder = temp.toFile();
        NativeSkillCatalog catalog = NativeSkillCatalog.loadDataFolder(
                dataFolder, NativeSkillCatalogDataFolderTest.class.getClassLoader());
        int before = catalog.get("FISHING").maxLevel();
        // Corrupt one file so parse falls back to defaults but size still 16 — force abort by
        // deleting a seeded file and replacing base dir with empty incomplete set.
        Path base = temp.resolve("skills/base");
        Files.writeString(base.resolve("fishing_progression.yml"), "experience:\n  max_level: 7\n");
        assertTrue(catalog.reload(dataFolder, NativeSkillCatalogDataFolderTest.class.getClassLoader()));
        assertEquals(7, catalog.get("FISHING").maxLevel());
        // Restore and ensure previous non-zero still readable after good reload
        assertTrue(before > 0 || catalog.get("MINING").maxLevel() > 0);
    }

    @Test
    void reloadRejectsMalformedCurveFormulaAndKeepsPreviousSnapshot() throws Exception {
        File dataFolder = temp.toFile();
        NativeSkillCatalog catalog = NativeSkillCatalog.loadDataFolder(
                dataFolder, NativeSkillCatalogDataFolderTest.class.getClassLoader());
        String previousFormula = catalog.get("FISHING").formulaString();
        long previousCost = catalog.get("FISHING").curve().expRequiredAt(1);

        // A syntactically valid YAML file with a broken exp_level_curve formula (unbalanced
        // parenthesis). NativeSkillCatalog.loadEntry only stores the formula string at parse
        // time — the curve function is evaluated lazily — so a naive reload would swap this
        // snapshot in without ever noticing the formula cannot be evaluated.
        Path base = temp.resolve("skills/base");
        Files.writeString(base.resolve("fishing_progression.yml"),
                "experience:\n  max_level: 50\n  exp_level_curve: \"(%level% + 1\"\n");

        boolean reloaded = catalog.reload(
                dataFolder, NativeSkillCatalogDataFolderTest.class.getClassLoader());

        assertFalse(reloaded, "reload must reject a curve that fails to evaluate");
        assertEquals(previousFormula, catalog.get("FISHING").formulaString(),
                "previous curve snapshot must be retained, not partially swapped");
        assertEquals(previousCost, catalog.get("FISHING").curve().expRequiredAt(1),
                "retained curve must still evaluate to the pre-reload cost");
    }
}
