package com.trinityforge.skilltree.runtime;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-08-01 実サーバ報告の回帰テスト —
 * <b>「ツルハシのスキルツリーで取ったノードでシャベルの採掘が速くなる」</b>。
 *
 * <p>{@code gathering-efficiency} の適用先である
 * {@link com.trinityforge.gathering.GatheringEfficiencyEnchantApplier} は、メインハンドの
 * {@code use-skill} が FARMING / MINING / WOODCUTTING / DIGGING の<b>いずれか</b>であれば
 * 合算値をそのまま効率強化エンチャントへ変換する。つまりこのステータスの値自体には
 * 「どのツリーで得たか」が残らない。
 *
 * <p>したがって {@code gathering-efficiency} を常時加算({@code buffs:})で配ると、
 * <b>採掘ツリーで取った +1 がシャベルにも斧にも鍬にも乗る</b>。
 * 唯一の防ぎ方は、ツリーの {@code skill:} と手持ちの {@code use-skill} が一致したときだけ効く
 * {@code mainhand-buffs:}(={@code PerkBuffResolver#matchesMainHandSkill})に置くこと。
 *
 * <p>このテストは出荷 yml が真源であることを前提に、
 * <b>ノードにもプレステージにも、常時 {@code buffs:} 側の {@code gathering-efficiency} が
 * 1つも存在しない</b>ことを縛る。yml は GUI エディタからも編集されるので、
 * 「エディタで開いて保存したら常時に戻っていた」を検出する網でもある。
 */
class GatheringEfficiencyMainhandOnlyContractTest {

    private static final String STAT = "gathering-efficiency";

    @Test
    void shippedSkilltreesNeverPlaceGatheringEfficiencyInAlwaysOnBuffs() throws Exception {
        Path dir = Path.of("src/main/resources/skilltree");
        assertTrue(Files.isDirectory(dir), "skilltree resource dir missing: " + dir.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> ymls = files.filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .collect(Collectors.toList());
            Yaml yaml = new Yaml();
            for (Path file : ymls) {
                try (InputStream in = Files.newInputStream(file)) {
                    collect(yaml.load(in), file.getFileName().toString(), "", offenders);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                STAT + " を常時 buffs: に置くと他系統のツール(シャベル/斧/鍬)にも乗る。"
                        + "mainhand-buffs: へ移すこと -> " + offenders);
    }

    /** {@code buffs:} 直下に {@code gathering-efficiency} を持つ地点を、到達パス付きで集める。 */
    private static void collect(Object node, String file, String path, List<String> out) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        if (map.get("buffs") instanceof Map<?, ?> buffs && buffs.containsKey(STAT)) {
            out.add(file + (path.isEmpty() ? "" : " " + path) + ".buffs." + STAT);
        }
        // mainhand-buffs は正しい置き場なので走査対象から外す(その配下に buffs は現れない)。
        Map<Object, Object> children = new LinkedHashMap<>(map);
        children.remove("buffs");
        children.remove("mainhand-buffs");
        children.forEach((key, value) -> {
            String childPath = path.isEmpty() ? String.valueOf(key) : path + "." + key;
            if (value instanceof Map<?, ?>) {
                collect(value, file, childPath, out);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    collect(item, file, childPath, out);
                }
            }
        });
    }
}
