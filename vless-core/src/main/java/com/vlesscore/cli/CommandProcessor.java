package com.vlesscore.cli;

import com.vlesscore.config.AppConfig;
import com.vlesscore.database.Token;
import com.vlesscore.database.TokenDao;
import com.vlesscore.server.VlessServerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

public class CommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(CommandProcessor.class);

    private final TokenDao tokenDao;
    private final AppConfig config;

    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";

    public CommandProcessor(TokenDao tokenDao, AppConfig config) {
        this.tokenDao = tokenDao;
        this.config = config;
    }

    public boolean process(String input) {
        if (input == null || input.isBlank()) return true;

        String trimmed = input.trim();
        String[] parts = trimmed.split("\\s+");
        String command = parts[0].toLowerCase();

        return switch (command) {
            case "/new" -> handleNew(parts);
            case "/extend" -> handleExtend(parts);
            case "/silent" -> handleSilent();
            case "/list" -> handleList();
            case "/info" -> handleInfo(parts);
            case "/block" -> handleBlock(parts);
            case "/stats" -> handleStats(); // [MOD] новая команда
            case "/help" -> { printHelp(); yield true; }
            case "/stop", "/exit", "/quit" -> {
                println(RED + "Завершение работы..." + RESET);
                System.exit(0);
                yield false;
            }
            default -> {
                println(RED + "Неизвестная команда: " + command + ". Введите /help" + RESET);
                yield true;
            }
        };
    }

    // [MOD] новая команда /stats
    private boolean handleStats() {
        try {
            int total = tokenDao.countAll();
            int active = tokenDao.countActive();
            int blocked = tokenDao.countByStatus(Token.Status.BLOCKED);
            int expired = tokenDao.countByStatus(Token.Status.EXPIRED);

            println("");
            println(BOLD + "📊 Статистика ключей:" + RESET);
            println("  🟢 Активных:   " + active);
            println("  🔴 Заблокировано: " + blocked);
            println("  ⚪ Просрочено:  " + expired);
            println("  📦 Всего создано: " + total);
            println("");
        } catch (Exception e) {
            println(RED + "Ошибка: " + e.getMessage() + RESET);
        }
        return true;
    }

    private boolean handleNew(String[] parts) {
        int days = config.getDefaultTokenDays();
        if (parts.length >= 2) {
            try {
                days = Integer.parseInt(parts[1]);
                if (days <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                println(RED + "Использование: /new [дней]" + RESET);
                return true;
            }
        }

        try {
            Token token = tokenDao.createToken(days);

            String clientUuid = tokenToUuid(token.getToken());

            tokenDao.createMappedToken(clientUuid, token.getToken());

            String vlessLink = buildVlessLink(clientUuid, config.getServerName());

            String socks5Card = config.isSocks5Enabled()
                    ? buildSocks5Card(clientUuid, token.getToken())
                    : "";

            println("");
            println(GREEN + BOLD + "╔══════════════════════════════════════════════════════════════════╗" + RESET);
            println(GREEN + BOLD + "║                    ✓ НОВЫЙ КЛЮЧ СОЗДАН                          ║" + RESET);
            println(GREEN + BOLD + "╠══════════════════════════════════════════════════════════════════╣" + RESET);

            println(GREEN + "║ " + CYAN + "Токен: " + RESET + token.getToken());
            println(GREEN + "║ " + CYAN + "UUID:  " + RESET + clientUuid);
            println(GREEN + "║ " + CYAN + "Срок:  " + RESET + days + " дн. (до " + token.getExpiresFormatted() + ")");
            println(GREEN + "║" + RESET);

            println(GREEN + BOLD + "╠══════════════════════════════════════════════════════════════════╣" + RESET);
            println(GREEN + "║ " + BOLD + "VLESS (VPN) — вставь в v2rayN/v2rayNG:" + RESET);
            println(GREEN + "║ " + CYAN + vlessLink + RESET);
            println(GREEN + "║" + RESET);

            if (config.isSocks5Enabled()) {
                println(GREEN + "║ " + BOLD + "SOCKS5 (Proxy):" + RESET);
                println(GREEN + "║ " + CYAN + socks5Card.replace("\n", "\n║ " + CYAN) + RESET);
                println(GREEN + "║" + RESET);
            } else {
                println(GREEN + DIM + "║ SOCKS5 отключён (socks5-enabled: false)" + RESET);
            }

            println(GREEN + BOLD + "╚══════════════════════════════════════════════════════════════════╝" + RESET);
            println("");

        } catch (Exception e) {
            println(RED + "Ошибка создания токена: " + e.getMessage() + RESET);
            log.error("Ошибка создания токена", e);
        }

        return true;
    }

    // /extend <token> <days>
    private boolean handleExtend(String[] parts) {
        if (parts.length < 3) {
            println(RED + "Использование: /extend <token> <days>" + RESET);
            return true;
        }

        String tokenStr = parts[1];
        int days;
        try {
            days = Integer.parseInt(parts[2]);
            if (days <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            println(RED + "days должно быть положительным числом." + RESET);
            return true;
        }

        try {
            Token token = tokenDao.extendToken(tokenStr, days);
            if (token == null) {
                println(RED + "Токен не найден: " + tokenStr + RESET);
            } else {
                println(GREEN + "✓ Токен продлён на " + days + " дн." + RESET);
                println(GREEN + "  Новый срок: до " + token.getExpiresFormatted()
                        + " (осталось " + token.getRemainingDays() + " дн.)" + RESET);
            }
        } catch (Exception e) {
            println(RED + "Ошибка: " + e.getMessage() + RESET);
        }

        return true;
    }

    private boolean handleSilent() {
        VlessServerHandler.silentMode = !VlessServerHandler.silentMode;
        if (VlessServerHandler.silentMode) {
            println(YELLOW + "Тихий режим ВКЛЮЧЁН — логи подключений скрыты." + RESET);
        } else {
            println(GREEN + "Тихий режим ОТКЛЮЧЁН — логи подключений видны." + RESET);
        }
        return true;
    }

    private boolean handleList() {
        try {
            List<Token> tokens = tokenDao.listAll();
            if (tokens.isEmpty()) {
                println(YELLOW + "Нет созданных токенов." + RESET);
                return true;
            }

            println("");
            println(BOLD + "Список токенов (" + tokens.size() + "):" + RESET);
            println(DIM + "────────────────────────────────────────────────────────" + RESET);

            for (Token t : tokens) {
                String status = t.getStatus().name();
                String tokenShort = t.getToken().length() > 20 ? t.getToken().substring(0, 20) + "..." : t.getToken();

                println("• " + CYAN + tokenShort + RESET
                        + " | " + status
                        + " | до " + t.getExpiresFormatted()
                        + " | conn=" + t.getConnections());
            }

            println("");

        } catch (Exception e) {
            println(RED + "Ошибка: " + e.getMessage() + RESET);
        }
        return true;
    }

    private boolean handleInfo(String[] parts) {
        if (parts.length < 2) {
            println(RED + "Использование: /info <token>" + RESET);
            return true;
        }

        String tokenStr = parts[1];

        try {
            Token t = tokenDao.findByToken(tokenStr);
            if (t == null) {
                println(RED + "Токен не найден." + RESET);
                return true;
            }

            String clientUuid = tokenToUuid(t.getToken());
            String vlessLink = buildVlessLink(clientUuid, config.getServerName());
            String socks5Card = config.isSocks5Enabled()
                    ? buildSocks5Card(clientUuid, t.getToken())
                    : "";

            println("");
            println(BOLD + "Информация о токене:" + RESET);
            println("  Token:   " + CYAN + t.getToken() + RESET);
            println("  UUID:    " + CYAN + clientUuid + RESET);
            println("  Status:  " + t.getStatus().name() + (t.isExpired() ? " (expired)" : ""));
            println("  Expires: " + t.getExpiresFormatted() + " (" + t.getRemainingDays() + " дн.)");
            println("  Conn:    " + t.getConnections());
            println("  Traffic: ↑" + formatBytes(t.getBytesUp()) + " ↓" + formatBytes(t.getBytesDown()));

            println("");
            println(BOLD + "VLESS link:" + RESET);
            println("  " + CYAN + vlessLink + RESET);

            if (config.isSocks5Enabled()) {
                println("");
                println(BOLD + "SOCKS5:" + RESET);
                println("  " + CYAN + socks5Card.replace("\n", "\n  " + CYAN) + RESET);
            }

            println("");

        } catch (Exception e) {
            println(RED + "Ошибка: " + e.getMessage() + RESET);
        }

        return true;
    }

    private boolean handleBlock(String[] parts) {
        if (parts.length < 2) {
            println(RED + "Использование: /block <token>" + RESET);
            return true;
        }

        String tokenStr = parts[1];

        try {
            Token t = tokenDao.findByToken(tokenStr);
            if (t == null) {
                println(RED + "Токен не найден." + RESET);
                return true;
            }
            tokenDao.updateStatus(tokenStr, Token.Status.BLOCKED);
            println(YELLOW + "✓ Токен заблокирован." + RESET);
        } catch (Exception e) {
            println(RED + "Ошибка: " + e.getMessage() + RESET);
        }

        return true;
    }

    public void printHelp() {
        println("");
        println(BOLD + CYAN + "Доступные команды:" + RESET);
        println(CYAN + "/new [days]             — создать ключ" + RESET);
        println(CYAN + "/extend <token> <days>  — продлить ключ" + RESET);
        println(CYAN + "/list                   — список ключей" + RESET);
        println(CYAN + "/info <token>           — детали + ссылки" + RESET);
        println(CYAN + "/block <token>          — заблокировать" + RESET);
        println(CYAN + "/stats                  — статистика ключей" + RESET); // [MOD]
        println(CYAN + "/silent                 — скрыть/показать логи подключений" + RESET);
        println(CYAN + "/help                   — помощь" + RESET);
        println(CYAN + "/stop                   — остановить сервер" + RESET);
        println("");
    }

    private String buildVlessLink(String uuid, String name) {
        String security = config.isTlsEnabled() ? "tls" : "none";
        String safeName = (name == null ? "VLESS" : name).replace(" ", "%20");

        return String.format(
                "vless://%s@%s:%d?encryption=none&type=tcp&security=%s#%s",
                uuid,
                config.getServerAddress(),
                config.getPort(),
                security,
                safeName
        );
    }

    private String buildSocks5Card(String usernameUuid, String passwordToken) {
        return config.getServerAddress() + ":" + config.getSocks5Port() + "\n"
                + usernameUuid + "\n"
                + passwordToken + "\n"
                + "Socks5";
    }


    public static String tokenToUuid(String token) {
        return UUID.nameUUIDFromBytes(token.getBytes()).toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void println(String msg) {
        System.out.println(msg);
    }
}