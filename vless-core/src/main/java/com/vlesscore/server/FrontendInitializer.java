package com.vlesscore.server;

import com.vlesscore.config.AppConfig;
import com.vlesscore.database.TokenDao;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

public class FrontendInitializer extends ChannelInitializer<SocketChannel> {

    private final AppConfig config;
    private final TokenDao tokenDao;

    public FrontendInitializer(AppConfig config, TokenDao tokenDao) {
        this.config = config;
        this.tokenDao = tokenDao;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast("idle", new IdleStateHandler(5, 5, 5, TimeUnit.MINUTES));
        pipeline.addLast("vless", new VlessServerHandler(config, tokenDao));
    }
}