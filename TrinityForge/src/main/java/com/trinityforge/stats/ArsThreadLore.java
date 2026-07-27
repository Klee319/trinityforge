package com.trinityforge.stats;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

final class ArsThreadLore {

    private static final NamespacedKey KEY = new NamespacedKey("arspaper", "thread_lore");
    private static final Gson GSON = new Gson();
    private static final int MAX_LINES = 32;
    private static final int MAX_PAYLOAD_LENGTH = 32_768;

    private ArsThreadLore() {
    }

    static void appendTo(ItemMeta meta, List<Component> destination) {
        String payload = meta.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (payload == null || payload.isBlank() || payload.length() > MAX_PAYLOAD_LENGTH) {
            return;
        }
        try {
            List<String> serialized = GSON.fromJson(payload, new TypeToken<List<String>>(){}.getType());
            if (serialized == null) {
                return;
            }
            List<Component> parsed = new ArrayList<>();
            for (String value : serialized.stream().limit(MAX_LINES).toList()) {
                if (value != null) {
                    parsed.add(GsonComponentSerializer.gson().deserialize(value));
                }
            }
            destination.addAll(parsed);
        } catch (RuntimeException ignored) {
            // Malformed foreign PDC must not prevent TrinityForge from rebuilding its own lore.
        }
    }
}
