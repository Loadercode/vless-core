package com.vlesscore.server;

import com.vlesscore.config.AppConfig;
import com.vlesscore.database.TokenDao;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class Socks5ProxyHandler extends SimpleChannelInboundHandler<Socks5Message> {

    private static final Logger log = LoggerFactory.getLogger(Socks5ProxyHandler.class);

    private final AppConfig config;
    private final TokenDao tokenDao;

    public Socks5ProxyHandler(AppConfig config, TokenDao tokenDao) {
        this.config = config;
        this.tokenDao = tokenDao;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5Message msg) throws Exception {

        if (msg instanceof Socks5InitialRequest req) {
            boolean needAuth = config.isSocks5AuthEnabled();

            if (needAuth) {
                ctx.pipeline().addFirst(new Socks5PasswordAuthRequestDecoder());
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(
                        Socks5AuthMethod.PASSWORD));
            } else {
                ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
                ctx.writeAndFlush(new DefaultSocks5InitialResponse(
                        Socks5AuthMethod.NO_AUTH));
            }
            return;
        }

        if (msg instanceof Socks5PasswordAuthRequest authReq) {
            String user = authReq.username();
            String pass = authReq.password();

            boolean ok = validateSocks5Auth(user, pass);
            if (!ok) {
                ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(
                        Socks5PasswordAuthStatus.FAILURE));
                ctx.close();
                return;
            }

            ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
            ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(
                    Socks5PasswordAuthStatus.SUCCESS));
            return;
        }

        if (msg instanceof Socks5CommandRequest cmdReq) {
            if (cmdReq.type() != Socks5CommandType.CONNECT) {
                ctx.writeAndFlush(new DefaultSocks5CommandResponse(
                        Socks5CommandStatus.COMMAND_UNSUPPORTED,
                        Socks5AddressType.IPv4, "0.0.0.0", 0));
                ctx.close();
                return;
            }

            String host = cmdReq.dstAddr();
            int port = cmdReq.dstPort();

            if (!VlessServerHandler.silentMode) {
                log.info("[SOCKS5] {} → {}:{}", ctx.channel().remoteAddress(), host, port);
            }

            connectToTarget(ctx, host, port);
        }
    }

    private boolean validateSocks5Auth(String username, String password) {
        try {
            return tokenDao.validateToken(username) ||
                    tokenDao.validateToken(password);
        } catch (Exception e) {
            log.error("Ошибка проверки SOCKS5 аутентификации", e);
            return false;
        }
    }

    private void connectToTarget(ChannelHandlerContext ctx, String host, int port) {
        Channel inbound = ctx.channel();

        Bootstrap b = new Bootstrap();
        b.group(inbound.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.AUTO_READ, false)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new RelayHandler(inbound,
                                VlessServerHandler.silentMode));
                    }
                });

        b.connect(new InetSocketAddress(host, port))
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        Channel outbound = future.channel();

                        // Отвечаем клиенту: соединение установлено
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(
                                Socks5CommandStatus.SUCCESS,
                                Socks5AddressType.IPv4,
                                "0.0.0.0", 0
                        )).addListener(f -> {
                            if (f.isSuccess()) {
                                // Переключаемся в режим relay
                                ctx.pipeline().remove(Socks5ProxyHandler.this);
                                ctx.pipeline().addLast(new RelayHandler(outbound,
                                        VlessServerHandler.silentMode));
                                outbound.read();
                            }
                        });
                    } else {
                        ctx.writeAndFlush(new DefaultSocks5CommandResponse(
                                Socks5CommandStatus.FAILURE,
                                Socks5AddressType.IPv4, "0.0.0.0", 0));
                        ctx.close();
                    }
                });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!VlessServerHandler.silentMode) {
            log.error("SOCKS5 ошибка: {}", cause.getMessage());
        }
        ctx.close();
    }
}