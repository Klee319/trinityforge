package com.trinityforge.stats;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Objects;

/**
 * One named quality tier (ITEM_ECONOMY_SPEC 5, ValhallaMMO quality-name 踏襲): a display {@code name}
 * and a MiniMessage {@code color} tag (e.g. {@code "gold"}, {@code "#c56bff"}, or a
 * {@code "gradient:#ffd76a:#ff7a3c"}). The ordered tier list ({@code stats/quality-tiers.yml}) has one
 * entry per quality level, so its length also defines the number of quality steps (config-variable).
 *
 * @param name  the tier's display name (e.g. 名匠)
 * @param color a MiniMessage tag applied to the name; left unclosed so it also works for gradients
 */
public record QualityTier(String name, String color) {

    public QualityTier {
        Objects.requireNonNull(name, "name");
        color = color == null ? "" : color;
    }

    /**
     * The tier rendered as a lore/name Component: {@code <color>【name】} via MiniMessage (the tag is
     * left unclosed so both {@code <color:..>} and {@code <gradient:..>} apply to the whole label), with
     * italics off to match item lore styling.
     */
    public Component label() {
        String open = color.isBlank() ? "" : "<" + color + ">";
        return MiniMessage.miniMessage().deserialize(open + "【" + name + "】")
                .decoration(TextDecoration.ITALIC, false);
    }
}
