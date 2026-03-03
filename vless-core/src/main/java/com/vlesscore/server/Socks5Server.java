package com.vlesscore.server;

import com.vlesscore.config.AppConfig;
import com.vlesscore.database.TokenDao;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class Socks5Server {

    private static final Logger log = LoggerFactory.getLogger(Socks5Server.class);

    private final AppConfig config;
    private final TokenDao tokenDao;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public Socks5Server(AppConfig config, TokenDao tokenDao) {
        this.config = config;
        this.tokenDao = tokenDao;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();

                        p.addLast("idle", new IdleStateHandler(5, 5, 5, TimeUnit.MINUTES));

                        p.addLast("socks5InitialDecoder", new Socks5InitialRequestDecoder());
                        p.addLast("socks5Encoder", Socks5ServerEncoder.DEFAULT);

                        p.addLast("socks5Handler", new Socks5ProxyHandler(config, tokenDao));
                    }
                });

        channel = b.bind(config.getSocks5Port()).sync().channel();
        log.info("SOCKS5 сервер запущен на порту {}", config.getSocks5Port());

        channel.closeFuture().sync();
    }

    public void stop() {
        log.info("Останавливаю SOCKS5 сервер...");
        try {
            if (channel != null) channel.close();
        } catch (Exception ignored) {}

        try {
            if (bossGroup != null) bossGroup.shutdownGracefully();
        } catch (Exception ignored) {}

        try {
            if (workerGroup != null) workerGroup.shutdownGracefully();
        } catch (Exception ignored) {}
    }
}