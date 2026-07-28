package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.trinityforge.integration.ars.ArsGlyphBrowserBridge;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /tf glyphs} — ArsPaper のグリフ解放素材GUI({@code GlyphBrowserGui})を開く(2026-07-28)。
 *
 * <p>解放コストは筆記台の lore にしか出ておらず、筆記台の前に立たないと「何を集めればよいか」を
 * 確認できなかった。{@link RecipesCommand} と同じ形(誰でも使えるプレイヤー専用コマンド・
 * {@code requires} は付けない)で入口を TF 側に置く。実体は
 * {@link ArsGlyphBrowserBridge} へのリフレクション委譲。
 */
public final class GlyphsCommand {

    public GlyphsCommand() {
    }

    /** {@code glyphs} リテラル。 */
    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("glyphs")
                .executes(ctx -> open(ctx.getSource().getSender()));
    }

    /** package-private for direct unit testing (mirrors {@link RecipesCommand#open}'s idiom). */
    int open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行できます。", NamedTextColor.RED));
            return 0;
        }
        if (!ArsGlyphBrowserBridge.isAvailable()) {
            player.sendMessage(Component.text("ArsPaper が利用できないため実行できません", NamedTextColor.RED));
            return 0;
        }
        if (!ArsGlyphBrowserBridge.open(player)) {
            player.sendMessage(Component.text("グリフ一覧を開けませんでした", NamedTextColor.RED));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }
}
