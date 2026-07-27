package com.trinityforge.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.trinityforge.config.domains.DungeonThemeConfig;
import com.trinityforge.config.domains.MobImportConfig;
import com.trinityforge.config.domains.MobProfileConfig;
import com.trinityforge.mobs.ConversionPolicy;
import com.trinityforge.mobs.DungeonTheme;
import com.trinityforge.mobs.EliteMobsImporter;
import com.trinityforge.mobs.EliteMobsImporter.ImportResult;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * {@code /trinityforge importmobs <sourceDir>} and {@code /trinityforge importmobs theme <theme>
 * <sourceDir>} — bulk-converts a folder of EliteMobs custom-boss files into
 * {@code combat/mob-profiles.yml} (concern: convert distributed dungeon mobs + author a new
 * dungeon), then reloads the profile table. With a {@code theme} (dungeon/themes.yml) the dungeon's
 * mobs get that theme's defender bias and tag; without one the global {@code combat/mob-import.yml}
 * policy is used. The parent command gates on {@code trinityforge.admin}.
 *
 * <p>{@code sourceDir} is a greedy trailing argument so a path with spaces or Windows separators
 * needs no quoting. The folder scan and YAML parse run off the main thread (async scheduler); the
 * profile-table reload and the player feedback are hopped back onto the main thread.
 *
 * <p>Only one import may run at a time ({@link #running}); a second invocation while one is already
 * in flight is rejected rather than letting two runs race on the same output file.
 */
public final class ImportMobsCommand {

    private final Plugin plugin;
    private final MobImportConfig importConfig;
    private final MobProfileConfig profileConfig;
    private final DungeonThemeConfig themeConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ImportMobsCommand(Plugin plugin, MobImportConfig importConfig,
                             MobProfileConfig profileConfig, DungeonThemeConfig themeConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.importConfig = Objects.requireNonNull(importConfig, "importConfig");
        this.profileConfig = Objects.requireNonNull(profileConfig, "profileConfig");
        this.themeConfig = Objects.requireNonNull(themeConfig, "themeConfig");
    }

    /**
     * The {@code importmobs} subtree to attach under the {@code trinityforge} root. Brigadier tries
     * literal children before argument children regardless of {@code .then()} order, so
     * {@code theme <name> <source>} resolves before the bare {@code <source:greedyString>} fallback.
     */
    public LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("importmobs")
                .then(Commands.argument("source", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "source"), null)))
                .then(Commands.literal("theme")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(this::suggestThemeNames)
                                .then(Commands.argument("source", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, "source"),
                                                StringArgumentType.getString(ctx, "name"))))));
    }

    private CompletableFuture<Suggestions> suggestThemeNames(CommandContext<CommandSourceStack> ctx,
                                                              SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String name : themeConfig.names()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private int run(CommandSender sender, String sourcePath, String themeName) {
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(Component.text(
                    "An import is already running; please wait for it to finish.", NamedTextColor.RED));
            return 0;
        }
        boolean dispatched = false;
        try {
            File sourceDir = new File(sourcePath.trim());
            if (!sourceDir.isDirectory()) {
                sender.sendMessage(Component.text("Not a directory: " + sourceDir.getPath()
                        + " (point this at an EliteMobs custombosses folder).", NamedTextColor.RED));
                return 0;
            }

            ConversionPolicy policy = importConfig.policy();
            if (themeName != null) {
                Optional<DungeonTheme> theme = themeConfig.theme(themeName);
                if (theme.isEmpty()) {
                    sender.sendMessage(Component.text("Unknown theme '" + themeName + "'. Available: "
                            + availableThemes() + " (see dungeon/themes.yml).", NamedTextColor.RED));
                    return 0;
                }
                policy = theme.get().toPolicy(policy);
            }

            File output = new File(plugin.getDataFolder(), MobProfileConfig.PATH);
            ConversionPolicy resolvedPolicy = policy;
            sender.sendMessage(Component.text("Importing mob profiles from " + sourceDir.getPath()
                    + " (running off-thread)...", NamedTextColor.GRAY));
            dispatched = dispatchImport(sender, sourceDir, output, resolvedPolicy, themeName);
            return Command.SINGLE_SUCCESS;
        } finally {
            if (!dispatched) {
                running.set(false);
            }
        }
    }

    /**
     * Runs the import off the main thread (the folder scan and YAML parse only touch the
     * filesystem, no Bukkit/world state) and hops both the success and failure outcome back onto the
     * main thread, mirroring each other so the sender always gets feedback from the main thread and
     * {@link #running} is always released exactly once.
     */
    private boolean dispatchImport(CommandSender sender, File sourceDir, File output,
                                   ConversionPolicy resolvedPolicy, String themeName) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                ImportResult result =
                        new EliteMobsImporter(resolvedPolicy, plugin.getLogger()).importFrom(sourceDir, output);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        applyImport(sender, result, themeName);
                    } finally {
                        running.set(false);
                    }
                });
            } catch (Exception ex) {
                // Broad on purpose: importFrom can also fail with an unchecked exception (e.g. a
                // malformed policy or an unexpected config shape), and the sender must be told either
                // way rather than the async task dying silently.
                plugin.getLogger().log(Level.SEVERE, "Mob import failed", ex);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        sender.sendMessage(Component.text("Import failed; see console for details.",
                                NamedTextColor.RED));
                    } finally {
                        running.set(false);
                    }
                });
            }
        });
        return true;
    }

    /** Reloads the profile table and reports the outcome. Must run on the main thread. */
    private void applyImport(CommandSender sender, ImportResult result, String themeName) {
        boolean loadOk = profileConfig.load(plugin);
        String themeNote = themeName == null ? "" : " with theme '" + themeName + "'";
        String reloadNote = loadOk ? "" : " (reload reported parse issues; see console)";
        String truncatedNote = result.truncated() ? ", file cap reached (some files ignored)" : "";
        NamedTextColor color = loadOk ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
        sender.sendMessage(Component.text("Imported " + result.imported() + " mob profile(s) ("
                + result.replaced() + " replaced existing)" + themeNote + " ("
                + result.skipped() + " skipped (non-mob or invalid), "
                + result.errors() + " YAML error(s), "
                + result.duplicates() + " duplicate id(s)" + truncatedNote
                + ") into " + MobProfileConfig.PATH + reloadNote + ".", color));
    }

    private String availableThemes() {
        return themeConfig.names().isEmpty() ? "(none defined)" : String.join(", ", themeConfig.names());
    }
}
