package com.vlesscore.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class VlessEncoder {

    public static ByteBuf encodeResponseHeader() {
        return Unpooled.wrappedBuffer(VlessProtocol.RESPONSE_HEADER);
    }

    public static ByteBuf encodeResponse(byte[] data) {
        ByteBuf buf = Unpooled.buffer(2 + data.length);
        buf.writeBytes(VlessProtocol.RESPONSE_HEADER);
        buf.writeBytes(data);
        return buf;
    }
}