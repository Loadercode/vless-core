package com.vlesscore.server;

import com.vlesscore.cli.CommandProcessor;
import com.vlesscore.config.AppConfig;
import com.vlesscore.database.Token;
import com.vlesscore.database.TokenDao;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

public class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    private final AppConfig config;
    private final TokenDao tokenDao;
    private HttpServer server;

    public ApiServer(AppConfig config, TokenDao tokenDao) {
        this.config = config;
        this.tokenDao = tokenDao;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.getApiPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        server.createContext("/api/keys/create", this::handleCreate);
        server.createContext("/api/keys/extend", this::handleExtend);
        server.createContext("/api/keys/freeze", this::handleFreeze);
        server.createContext("/api/keys/unfreeze", this::handleUnfreeze);
        server.createContext("/api/keys/delete", this::handleDelete);
        server.createContext("/api/keys/info", this::handleInfo);
        server.createContext("/api/keys/list", this::handleList);
        server.createContext("/api/ping", this::handlePing);

        server.start();
        log.info("HTTP API запущен на порту {}", config.getApiPort());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private boolean checkAuth(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.equals("Bearer " + config.getApiSecret())) {
            sendJson(ex, 403, "{\"error\":\"forbidden\"}");
            return false;
        }
        return true;
    }

    private void handleCreate(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        try {
            String query = ex.getRequestURI().getQuery();
            int days = config.getDefaultTokenDays();
            long expiresAt = 0;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length == 2) {
                        if (kv[0].equals("days")) {
                            days = Integer.parseInt(kv[1]);
                        } else if (kv[0].equals("expires_at")) {
                            expiresAt = Long.parseLong(kv[1]);
                        }
                    }
                }
            }

            Token token;
            if (expiresAt > 0) {
                token = tokenDao.createTokenWithExpiry(expiresAt);
            } else {
                token = tokenDao.createToken(days);
            }

            String clientUuid = CommandProcessor.tokenToUuid(token.getToken());
            tokenDao.createMappedToken(clientUuid, token.getToken());

            String vlessLink = buildVlessLink(clientUuid);

            String json = String.format(
                    "{\"success\":true,\"token\":\"%s\",\"uuid\":\"%s\",\"vless_link\":\"%s\","
                            + "\"expires_at\":%d,\"days\":%d,\"server_name\":\"%s\",\"address\":\"%s\",\"port\":%d}",
                    token.getToken(), clientUuid, vlessLink,
                    token.getExpiresAt(), days,
                    config.getServerName(), config.getServerAddress(), config.getPort()
            );
            sendJson(ex, 200, json);
            log.info("[API] Создан ключ: {} на {} дней", token.getToken().substring(0, 20) + "...", days);

        } catch (Exception e) {
            log.error("[API] Ошибка создания ключа", e);
            sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleExtend(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        try {
            var params = parseQuery(ex.getRequestURI().getQuery());
            String tokenStr = params.getOrDefault("token", "");
            int days = Integer.parseInt(params.getOrDefault("days", "30"));

            Token result = tokenDao.extendToken(tokenStr, days);
            if (result == null) {
                sendJson(ex, 404, "{\"error\":\"token not found\"}");
                return;
            }

            sendJson(ex, 200, String.format(
                    "{\"success\":true,\"token\":\"%s\",\"new_expires_at\":%d,\"remaining_days\":%d}",
                    result.getToken(), result.getExpiresAt(), result.getRemainingDays()
            ));
            log.info("[API] Продлён ключ: {} на {} дней", tokenStr.substring(0, Math.min(20, tokenStr.length())), days);

        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleFreeze(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        try {
            var params = parseQuery(ex.getRequestURI().getQuery());
            String tokenStr = params.getOrDefault("token", "");

            Token t = tokenDao.findByToken(tokenStr);
            if (t == null) {
                sendJson(ex, 404, "{\"error\":\"token not found\"}");
                return;
            }
            tokenDao.updateStatus(tokenStr, Token.Status.BLOCKED);
            sendJson(ex, 200, "{\"success\":true,\"status\":\"BLOCKED\"}");
            log.info("[API] Заморожен ключ: {}", tokenStr.substring(0, Math.min(20, tokenStr.length())));

        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleUnfreeze(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        try {
            var params = parseQuery(ex.getRequestURI().getQuery());
            String tokenStr = params.getOrDefault("token", "");

            Token t = tokenDao.findByToken(tokenStr);
            if (t == null) {
                sendJson(ex, 404, "{\"error\":\"token not found\"}");
                return;
            }
            tokenDao.updateStatus(tokenStr, Token.Status.ACTIVE);
            sendJson(ex, 200, "{\"success\":true,\"status\":\"ACTIVE\"}");

        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleDelete(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        try {
            var params = parseQuery(ex.getRequestURI().getQuery());
            String tokenStr = params.getOrDefault("token", "");

            Token t = tokenDao.findByToken(tokenStr);
            if (t == null) {
                sendJson(ex, 404, "{\"error\":\"token not found\"}");
                return;
            }
            tokenDao.deleteToken(tokenStr);
            sendJson(ex, 200, "{\"success\":true}");
            log.info("[API] Удалён ключ: {}", tokenStr.substring(0, Math.min(20, tokenStr.length())));

        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleInfo(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        try {
            var params = parseQuery(ex.getRequestURI().getQuery());
            String tokenStr = params.getOrDefault("token", "");

            Token t = tokenDao.findByToken(tokenStr);
            if (t == null) {
                sendJson(ex, 404, "{\"error\":\"token not found\"}");
                return;
            }

            sendJson(ex, 200, String.format(
                    "{\"token\":\"%s\",\"status\":\"%s\",\"expires_at\":%d,"
                            + "\"remaining_days\":%d,\"connections\":%d,"
                            + "\"bytes_up\":%d,\"bytes_down\":%d}",
                    t.getToken(), t.getStatus().name(), t.getExpiresAt(),
                    t.getRemainingDays(), t.getConnections(),
                    t.getBytesUp(), t.getBytesDown()
            ));

        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleList(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        try {
            List<Token> tokens = tokenDao.listAll();
            StringBuilder sb = new StringBuilder("{\"keys\":[");
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format(
                        "{\"token\":\"%s\",\"status\":\"%s\",\"expires_at\":%d,\"connections\":%d}",
                        t.getToken(), t.getStatus().name(), t.getExpiresAt(), t.getConnections()
                ));
            }
            sb.append("],\"count\":").append(tokens.size()).append("}");
            sendJson(ex, 200, sb.toString());

        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handlePing(HttpExchange ex) throws IOException {
        sendJson(ex, 200, "{\"status\":\"ok\",\"server\":\"" + config.getServerName() + "\"}");
    }

    private String buildVlessLink(String uuid) {
        String security = config.isTlsEnabled() ? "tls" : "none";
        String name = config.getServerName().replace(" ", "%20");
        return String.format(
                "vless://%s@%s:%d?encryption=none&type=tcp&security=%s#%s",
                uuid, config.getServerAddress(), config.getPort(), security, name
        );
    }

    private void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private java.util.Map<String, String> parseQuery(String query) {
        var map = new java.util.HashMap<String, String>();
        if (query == null || query.isEmpty()) return map;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}