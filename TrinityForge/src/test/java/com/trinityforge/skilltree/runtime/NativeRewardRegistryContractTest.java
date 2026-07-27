package com.trinityforge.skilltree.runtime;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shipped skilltree YAML must use the unified {@code buffs:}/{@code dedicated-effects:} model. */
class NativeRewardRegistryContractTest {

    @Test
    void shippedSkilltreesContainNoNativeRewards() throws Exception {
        Path dir = Path.of("src/main/resources/skilltree");
        assertTrue(Files.isDirectory(dir), "skilltree resource dir missing: " + dir.toAbsolutePath());
        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> ymls = files.filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .filter(p -> !p.getFileName().toString().equals("dedicated-effects.yml"))
                    .sorted()
                    .collect(Collectors.toList());
            Yaml yaml = new Yaml();
            for (Path file : ymls) {
                try (InputStream in = Files.newInputStream(file)) {
                    Object root = yaml.load(in);
                    collectNativeKeys(root, found);
                }
            }
        }
        assertTrue(found.isEmpty(), "native rewards must be migrated to buffs/dedicated-effects: " + found);
    }

    @SuppressWarnings("unchecked")
    private static void collectNativeKeys(Object node, Set<String> out) {
        if (!(node instanceof Map<?, ?> map)) return;
        Object nativeSection = map.get("native");
        if (nativeSection instanceof Map<?, ?> nativeMap) {
            for (Object key : nativeMap.keySet()) {
                if (key instanceof String s && !s.isBlank()) out.add(s);
            }
        }
        for (Object value : map.values()) {
            if (value instanceof Map<?, ?> child) collectNativeKeys(child, out);
            else if (value instanceof List<?> list) {
                for (Object item : list) collectNativeKeys(item, out);
            }
        }
    }
}
