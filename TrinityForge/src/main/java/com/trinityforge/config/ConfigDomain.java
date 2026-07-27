package com.trinityforge.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A single domain file (e.g. {@code combat/damage.yml}) backed by a {@link ConfigSchema}.
 * On {@link #load} the default is copied from the jar if absent, parsed, validated, and
 * exposed as an immutable {@link TypedConfig}. Reloading swaps the snapshot atomically.
 */
public final class ConfigDomain implements LoadableConfig {

    private final String resourcePath;
    private final ConfigSchema schema;
    private volatile TypedConfig config;

    public ConfigDomain(String resourcePath, ConfigSchema schema) {
        this.resourcePath = resourcePath;
        this.schema = schema;
        // フェイルソフトな初期値: saveResource()の失敗やload()が一度も成功しないまま
        // get()が呼ばれても IllegalStateException にならないよう、スキーマ宣言済みの
        // デフォルト値のみで解決した純デフォルトの TypedConfig を先に保持しておく
        // (レビュー H-2)。ConfigSchema#resolve は section が null でも各フィールドの
        // defaultValue() にフォールバックするため、これで安全に構築できる。
        this.config = new TypedConfig(schema.resolve(null, new ValidationResult()));
    }

    public String resourcePath() {
        return resourcePath;
    }

    public TypedConfig get() {
        return config;
    }

    /** Loads (or reloads) the file. Returns true when no validation issues were found. */
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            plugin.saveResource(resourcePath, false);
        }

        // YamlConfiguration.loadConfiguration(File) は構文エラーを内部で握り潰し、空の
        // configを返す。それをそのまま schema.resolve() に渡すと、全キーが「欠損」扱いで
        // 静かにデフォルトへ巻き戻り、load() は(検証エラーが無いため) true を返してしまう
        // ("サイレント設定破損")。ここでは自前で YamlConfiguration#load(File) を呼び、
        // 構文エラーを明示的に検知して fail-loud にする。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            // 直前に成功ロード済みのスナップショット(または初回失敗時はコンストラクタで
            // 設定した純デフォルト)を維持したまま、falseを返す。this.configには一切触れない。
            log.log(Level.SEVERE, "[" + resourcePath + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        ValidationResult result = new ValidationResult();
        this.config = new TypedConfig(schema.resolve(yaml, result));

        if (result.hasErrors()) {
            log.warning("[" + resourcePath + "] " + result.errors().size()
                    + " config issue(s); defaults applied where invalid:");
            for (String error : result.errors()) {
                log.log(Level.WARNING, "  - " + error);
            }
            return false;
        }
        log.info("[" + resourcePath + "] loaded OK");
        return true;
    }
}
