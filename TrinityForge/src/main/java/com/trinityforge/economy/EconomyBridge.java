package com.trinityforge.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Soft-dependency bridge to Vault's {@link Economy} service (経済連携: 「vaultが入っている前提で
 * (なくても効果がないだけで運用可能)」ユーザー要件). Vault is declared {@code softdepend} in
 * {@code paper-plugin.yml} — never {@code depend} — so TrinityForge must start normally whether or
 * not it is installed.
 *
 * <p>{@link #resolve(Plugin)} looks up the {@link Economy} service exactly once at startup and logs a
 * single INFO line either way. When no provider is found, {@link #available()} stays {@code false} and
 * every other method degrades to a no-op ({@code false}/{@code 0.0}) instead of throwing — callers
 * (fish-sell, disassembly-return buffs, etc.) never need to null-check or catch for Vault absence.
 */
public final class EconomyBridge {

    private final Economy economy;

    private EconomyBridge(Economy economy) {
        this.economy = economy;
    }

    /**
     * Resolves the Vault {@link Economy} provider at startup. Logs one INFO line describing the
     * outcome; never throws, never repeats the log on later calls (callers should call this once and
     * hold the returned instance, e.g. in the plugin's {@code onEnable}).
     */
    public static EconomyBridge resolve(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Logger log = plugin.getLogger();
        Economy economy = null;
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
                RegisteredServiceProvider<Economy> registration =
                        Bukkit.getServicesManager().getRegistration(Economy.class);
                if (registration != null) {
                    economy = registration.getProvider();
                }
            }
        } catch (Throwable ignored) {
            // Vault present but its service registration is broken/unstable at this point in startup
            // ordering — degrade to "unavailable" rather than letting TrinityForge fail to enable.
            economy = null;
        }
        if (economy != null) {
            log.info("[economy] Vault economy provider detected (" + safeName(economy)
                    + "); currency-dependent features are enabled.");
        } else {
            log.info("[economy] Vault economy provider not found; currency-dependent features "
                    + "(fish-sell-toggle, etc.) stay disabled — this is expected when Vault is not installed.");
        }
        return new EconomyBridge(economy);
    }

    /** Always-unavailable instance, for tests and any call site that must run with Vault absent. */
    public static EconomyBridge unavailable() {
        return new EconomyBridge(null);
    }

    /** Wraps an already-resolved {@link Economy} provider (tests / DI). */
    public static EconomyBridge of(Economy economy) {
        return new EconomyBridge(economy);
    }

    private static String safeName(Economy economy) {
        try {
            String name = economy.getName();
            return name == null ? "unknown" : name;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** {@code true} only when a Vault economy provider was resolved and is usable. */
    public boolean available() {
        return economy != null;
    }

    /** Deposits {@code amount} into {@code player}'s Vault balance. No-op ({@code false}) when unavailable. */
    public boolean deposit(Player player, double amount) {
        if (!available() || player == null || !Double.isFinite(amount) || amount <= 0) {
            return false;
        }
        try {
            return economy.depositPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Withdraws {@code amount} from {@code player}'s Vault balance. No-op ({@code false}) when unavailable. */
    public boolean withdraw(Player player, double amount) {
        if (!available() || player == null || !Double.isFinite(amount) || amount <= 0) {
            return false;
        }
        try {
            return economy.withdrawPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code player}'s current Vault balance, or {@code 0.0} when unavailable. */
    public double balance(Player player) {
        if (!available() || player == null) {
            return 0.0;
        }
        try {
            return economy.getBalance(player);
        } catch (Throwable t) {
            return 0.0;
        }
    }
}
