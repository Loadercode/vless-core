package com.vlesscore.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class TokenDao {

    private static final Logger log = LoggerFactory.getLogger(TokenDao.class);

    private static final String TOKEN_PREFIX = "vpn_";
    private static final int TOKEN_RANDOM_LENGTH = 32;
    private static final String TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Connection conn;

    public TokenDao(Connection conn) {
        this.conn = conn;
    }


    public static String generateTokenString() {
        StringBuilder sb = new StringBuilder(TOKEN_PREFIX);
        for (int i = 0; i < TOKEN_RANDOM_LENGTH; i++) {
            sb.append(TOKEN_CHARS.charAt(RANDOM.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }


    public synchronized Token createToken(int days) throws SQLException {
        String tokenStr = generateTokenString();
        long now = System.currentTimeMillis();
        long expires = now + (long) days * 24 * 60 * 60 * 1000;

        String sql = "INSERT INTO tokens (token, status, created_at, expires_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tokenStr);
            ps.setString(2, Token.Status.ACTIVE.name());
            ps.setLong(3, now);
            ps.setLong(4, expires);
            ps.executeUpdate();

            Token token = new Token(tokenStr, Token.Status.ACTIVE, now, expires);

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    token.setId(keys.getInt(1));
                }
            }
            return token;
        }
    }

    public synchronized Token createTokenWithExpiry(long expiresAt) throws SQLException {
        String tokenStr = generateTokenString();
        long now = System.currentTimeMillis();

        String sql = "INSERT INTO tokens (token, status, created_at, expires_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tokenStr);
            ps.setString(2, Token.Status.ACTIVE.name());
            ps.setLong(3, now);
            ps.setLong(4, expiresAt);
            ps.executeUpdate();

            Token token = new Token(tokenStr, Token.Status.ACTIVE, now, expiresAt);

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    token.setId(keys.getInt(1));
                }
            }
            return token;
        }
    }

    public synchronized Token findByToken(String tokenStr) throws SQLException {
        String sql = "SELECT * FROM tokens WHERE token = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tokenStr);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
                return null;
            }
        }
    }

    public synchronized void createMappedToken(String uuid, String tokenRef) throws SQLException {
        String sql = "INSERT OR REPLACE INTO token_uuid_map (uuid, token_ref) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, tokenRef);
            ps.executeUpdate();
        }
    }

    public synchronized String resolveUuidToToken(String uuid) throws SQLException {
        String sql = "SELECT token_ref FROM token_uuid_map WHERE uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("token_ref");
                return null;
            }
        }
    }


    public synchronized boolean validateToken(String uuidOrToken) throws SQLException {
        String mappedToken = resolveUuidToToken(uuidOrToken);
        String actualToken = mappedToken != null ? mappedToken : uuidOrToken;

        Token token = findByToken(actualToken);
        if (token == null) return false;

        if (token.getStatus() == Token.Status.ACTIVE && token.isExpired()) {
            updateStatus(actualToken, Token.Status.EXPIRED);
            return false;
        }

        return token.isUsable();
    }


    public synchronized Token extendToken(String tokenStr, int days) throws SQLException {
        Token token = findByToken(tokenStr);
        if (token == null) return null;

        long additionalMs = (long) days * 24 * 60 * 60 * 1000;
        long newExpiry = token.isExpired()
                ? System.currentTimeMillis() + additionalMs
                : token.getExpiresAt() + additionalMs;

        String sql = "UPDATE tokens SET expires_at = ?, status = ? WHERE token = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newExpiry);
            ps.setString(2, Token.Status.ACTIVE.name());
            ps.setString(3, tokenStr);
            ps.executeUpdate();
        }

        token.setExpiresAt(newExpiry);
        token.setStatus(Token.Status.ACTIVE);
        return token;
    }

    public synchronized void updateStatus(String tokenStr, Token.Status status) throws SQLException {
        String sql = "UPDATE tokens SET status = ? WHERE token = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, tokenStr);
            ps.executeUpdate();
        }
    }


    public synchronized void incrementConnections(String uuidOrToken) throws SQLException {
        String mappedToken = resolveUuidToToken(uuidOrToken);
        String actualToken = mappedToken != null ? mappedToken : uuidOrToken;

        String sql = "UPDATE tokens SET connections = connections + 1 WHERE token = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, actualToken);
            ps.executeUpdate();
        }
    }

    public synchronized void addTraffic(String uuidOrToken, long bytesUp, long bytesDown) throws SQLException {
        String mappedToken = resolveUuidToToken(uuidOrToken);
        String actualToken = mappedToken != null ? mappedToken : uuidOrToken;

        String sql = "UPDATE tokens SET bytes_up = bytes_up + ?, bytes_down = bytes_down + ? WHERE token = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bytesUp);
            ps.setLong(2, bytesDown);
            ps.setString(3, actualToken);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteToken(String tokenStr) throws SQLException {
        String sql1 = "DELETE FROM token_uuid_map WHERE token_ref = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql1)) {
            ps.setString(1, tokenStr);
            ps.executeUpdate();
        }

        String sql2 = "DELETE FROM tokens WHERE token = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setString(1, tokenStr);
            ps.executeUpdate();
        }
    }

    public synchronized List<Token> listAll() throws SQLException {
        List<Token> tokens = new ArrayList<>();
        String sql = "SELECT * FROM tokens ORDER BY created_at DESC";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                tokens.add(mapRow(rs));
            }
        }
        return tokens;
    }

    public synchronized int countActive() throws SQLException {
        String sql = "SELECT COUNT(*) FROM tokens WHERE status = 'ACTIVE' AND expires_at > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized int countAll() throws SQLException {
        String sql = "SELECT COUNT(*) FROM tokens";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized int countByStatus(Token.Status status) throws SQLException {
        String sql = "SELECT COUNT(*) FROM tokens WHERE status = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private Token mapRow(ResultSet rs) throws SQLException {
        Token t = new Token();
        t.setId(rs.getInt("id"));
        t.setToken(rs.getString("token"));
        t.setStatus(Token.Status.valueOf(rs.getString("status")));
        t.setCreatedAt(rs.getLong("created_at"));
        t.setExpiresAt(rs.getLong("expires_at"));
        t.setBytesUp(rs.getLong("bytes_up"));
        t.setBytesDown(rs.getLong("bytes_down"));
        t.setConnections(rs.getInt("connections"));
        return t;
    }
}