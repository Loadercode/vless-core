package com.vlesscore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_NAME = "config.yml";
    private final Path configPath;

    public ConfigManager(Path baseDir) {
        this.configPath = baseDir.resolve(CONFIG_NAME);
    }

    public AppConfig loadOrCreate() {
        if (!Files.exists(configPath)) {
            log.info("Файл конфигурации не найден. Создаю config.yml...");
            createDefault();
            log.info("Создан файл: {}", configPath.toAbsolutePath());
        }

        try (InputStream is = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            return AppConfig.fromMap(data);
        } catch (Exception e) {
            log.error("Ошибка чтения конфигурации", e);
            return new AppConfig();
        }
    }

    private void createDefault() {
        AppConfig defaults = new AppConfig();

        LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("server-name", defaults.getServerName());
        ordered.put("server-address", defaults.getServerAddress());
        ordered.put("port", defaults.getPort());
        ordered.put("tls-enabled", defaults.isTlsEnabled());
        ordered.put("tls-cert-path", defaults.getTlsCertPath());
        ordered.put("tls-key-path", defaults.getTlsKeyPath());
        ordered.put("database-file", defaults.getDatabaseFile());
        ordered.put("default-token-days", defaults.getDefaultTokenDays());
        ordered.put("worker-threads", defaults.getWorkerThreads());
        ordered.put("log-level", defaults.getLogLevel());
        ordered.put("silent-mode", defaults.isSilentMode());

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);

        Yaml yaml = new Yaml(opts);

        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            writer.write("# ===================================\n");
            writer.write("# VLESS Core — Конфигурация сервера\n");
            writer.write("# ===================================\n");
            writer.write("# silent-mode: true = не спамить логами подключений\n");
            writer.write("# Переключить в рантайме: /silent\n\n");
            yaml.dump(ordered, writer);
        } catch (IOException e) {
            log.error("Не удалось создать файл конфигурации", e);
        }
    }
}