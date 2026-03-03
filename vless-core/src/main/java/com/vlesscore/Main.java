package com.vlesscore;

import com.vlesscore.cli.CommandProcessor;
import com.vlesscore.cli.ConsoleReader;
import com.vlesscore.config.AppConfig;
import com.vlesscore.config.ConfigManager;
import com.vlesscore.database.DatabaseManager;
import com.vlesscore.server.ApiServer;
import com.vlesscore.server.ProxyServer;
import com.vlesscore.server.Socks5Server;
import com.vlesscore.server.VlessServerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static final String VERSION = "1.1.0";
    public static final String BANNER = """
            
            ╔══════════════════════════════════════════╗
            ║     __   __ _     _____ ____ ____        ║
            ║     \\ \\ / /| |   | ____/ ___/ ___|      ║
            ║      \\ V / | |   |  _| \\___ \\___ \\      ║
            ║       | |  | |___| |___ ___) |__) |     ║
            ║       |_|  |_____|_____|____/____/      ║
            ║                                          ║
            ║         VLESS Core Engine v%s         ║
            ║         + API + SOCKS5                  ║
            ╚══════════════════════════════════════════╝
            """.formatted(VERSION);

    public static void main(String[] args) {
        System.out.println(BANNER);

        Path baseDir = Paths.get(".").toAbsolutePath().normalize();

        log.info("Загрузка конфигурации...");
        ConfigManager configManager = new ConfigManager(baseDir);
        AppConfig config = configManager.loadOrCreate();
        log.info("Конфигурация загружена: port={}, tls={}", config.getPort(), config.isTlsEnabled());

        log.info("Инициализация базы данных...");
        DatabaseManager dbManager = new DatabaseManager(baseDir, config);
        dbManager.initialize();
        log.info("База данных готова.");

        VlessServerHandler.silentMode = config.isSilentMode();
        if (config.isSilentMode()) {
            log.info("Тихий режим ВКЛЮЧЁН (silent-mode: true). Команда: /silent");
        }

        CommandProcessor cmdProcessor = new CommandProcessor(dbManager.getTokenDao(), config);

        ProxyServer proxyServer = new ProxyServer(config, dbManager.getTokenDao());
        Thread vlessThread = new Thread(() -> {
            try {
                proxyServer.start();
            } catch (Exception e) {
                log.error("Ошибка запуска VLESS сервера", e);
                System.exit(1);
            }
        }, "proxy-server");
        vlessThread.setDaemon(false);
        vlessThread.start();

        ApiServer apiServer = null;
        if (config.isApiEnabled()) {
            try {
                apiServer = new ApiServer(config, dbManager.getTokenDao());
                apiServer.start();
            } catch (Exception e) {
                log.error("Ошибка запуска API сервера", e);
            }
        }

        Socks5Server socks5Server = null;
        if (config.isSocks5Enabled()) {
            try {
                socks5Server = new Socks5Server(config, dbManager.getTokenDao());

                Socks5Server finalSocks5Server = socks5Server;
                Thread socksThread = new Thread(() -> {
                    try {
                        finalSocks5Server.start();
                    } catch (Exception e) {
                        log.error("Ошибка запуска SOCKS5 сервера", e);
                    }
                }, "socks5-server");
                socksThread.setDaemon(false);
                socksThread.start();

                log.info("SOCKS5 включён. Порт: {}", config.getSocks5Port());
            } catch (Exception e) {
                log.error("Ошибка инициализации SOCKS5 сервера", e);
            }
        }

        cmdProcessor.printHelp();

        ApiServer finalApiServer = apiServer;
        Socks5Server finalSocks5Server1 = socks5Server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Завершение работы...");
            try {
                proxyServer.stop();
            } catch (Exception ignored) {}

            try {
                if (finalApiServer != null) finalApiServer.stop();
            } catch (Exception ignored) {}

            try {
                if (finalSocks5Server1 != null) finalSocks5Server1.stop();
            } catch (Exception ignored) {}

            try {
                dbManager.close();
            } catch (Exception ignored) {}

            log.info("Остановлено.");
        }, "shutdown-hook"));

        ConsoleReader consoleReader = new ConsoleReader(cmdProcessor);
        consoleReader.start();
    }
}