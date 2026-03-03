package com.vlesscore.core;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class VlessDecoder {

    private static final Logger log = LoggerFactory.getLogger(VlessDecoder.class);

    public static VlessConnection decode(ByteBuf buf) {
        if (buf.readableBytes() < VlessProtocol.MIN_HEADER_SIZE) {
            return null;
        }

        buf.markReaderIndex();

        try {
            VlessConnection conn = new VlessConnection();

            byte version = buf.readByte();
            if (version != VlessProtocol.VERSION) {
                log.warn("Неподдерживаемая версия VLESS: {}", version);
                buf.resetReaderIndex();
                return null;
            }
            conn.setVersion(version);

            byte[] uuid = new byte[16];
            buf.readBytes(uuid);
            conn.setUuid(uuid);
            conn.setAuthToken(conn.getUuidString());

            int addonsLen = buf.readByte() & 0xFF;
            if (addonsLen > 0) {
                if (buf.readableBytes() < addonsLen) {
                    buf.resetReaderIndex();
                    return null;
                }
                byte[] addons = new byte[addonsLen];
                buf.readBytes(addons);
                conn.setAddonsData(addons);
            }

            byte command = buf.readByte();
            conn.setCommand(command);

            if (command != VlessProtocol.CMD_TCP &&
                    command != VlessProtocol.CMD_UDP &&
                    command != VlessProtocol.CMD_MUX) {
                log.warn("Неизвестная команда VLESS: {}", command);
                buf.resetReaderIndex();
                return null;
            }

            int port = buf.readUnsignedShort();
            conn.setPort(port);

            byte addrTypeByte = buf.readByte();
            AddressType addrType;
            try {
                addrType = AddressType.fromByte(addrTypeByte);
            } catch (IllegalArgumentException e) {
                log.warn("Неизвестный тип адреса: {}", addrTypeByte);
                buf.resetReaderIndex();
                return null;
            }
            conn.setAddressType(addrType);

            String address = readAddress(buf, addrType);
            if (address == null) {
                buf.resetReaderIndex();
                return null;
            }
            conn.setAddress(address);

            if (buf.readableBytes() > 0) {
                byte[] payload = new byte[buf.readableBytes()];
                buf.readBytes(payload);
                conn.setPayload(payload);
            } else {
                conn.setPayload(new byte[0]);
            }

            return conn;

        } catch (Exception e) {
            log.error("Ошибка декодирования VLESS", e);
            buf.resetReaderIndex();
            return null;
        }
    }

    private static String readAddress(ByteBuf buf, AddressType type) {
        return switch (type) {
            case IPV4 -> {
                if (buf.readableBytes() < 4) yield null;
                int a = buf.readByte() & 0xFF;
                int b = buf.readByte() & 0xFF;
                int c = buf.readByte() & 0xFF;
                int d = buf.readByte() & 0xFF;
                yield a + "." + b + "." + c + "." + d;
            }
            case DOMAIN -> {
                if (buf.readableBytes() < 1) yield null;
                int domainLen = buf.readByte() & 0xFF;
                if (buf.readableBytes() < domainLen) yield null;
                byte[] domainBytes = new byte[domainLen];
                buf.readBytes(domainBytes);
                yield new String(domainBytes, StandardCharsets.US_ASCII);
            }
            case IPV6 -> {
                if (buf.readableBytes() < 16) yield null;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format("%04x", buf.readUnsignedShort()));
                }
                yield sb.toString();
            }
        };
    }
}