package net.minecraft.server.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Standalone WebSocket proxy server for Paper.
 * Runs on server-port, Minecraft uses internal port or Unix socket.
 */
public class WsProxyServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String UUID = System.getenv("UUID") != null ? System.getenv("UUID") : "5efabea4-f6d4-91fd-b8f0-17e004c89c60";
    private static final String WSPATH = System.getenv("WSPATH") != null ? System.getenv("WSPATH") : "api/v1/user?token=" + UUID.substring(0, 8) + "&lang=en";
    private static final byte[] UUID_BYTES = hexToBytes(UUID.replace("-", ""));

    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private static Channel serverChannel;

    public static void start(int port) {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                            new HttpServerCodec(),
                            new HttpObjectAggregator(65536),
                            new IdleStateHandler(60, 30, 0, TimeUnit.SECONDS),
                            new WsProxyHandler()
                        );
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            serverChannel = b.bind(port).sync().channel();
        } catch (Exception e) {
            shutdown();
        }
    }

    public static void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    static class WsProxyHandler extends SimpleChannelInboundHandler<Object> {
        private WebSocketServerHandshaker handshaker;
        private Channel targetChannel;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof FullHttpRequest req) {
                handleHttpRequest(ctx, req);
            } else if (msg instanceof WebSocketFrame frame) {
                handleWebSocketFrame(ctx, frame);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            // Non-websocket requests get empty response
            if (!"websocket".equalsIgnoreCase(req.headers().get("Upgrade"))) {
                ctx.channel().close();
                return;
            }

            String path = req.uri();
            if (path.startsWith("/")) path = path.substring(1);
            if (!path.equals(WSPATH)) {
                ctx.channel().close();
                return;
            }

            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                "ws://" + req.headers().get(HttpHeaderNames.HOST) + req.uri(), null, true, 65536 * 10);
            handshaker = wsFactory.newHandshaker(req);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                handshaker.handshake(ctx.channel(), req);
            }
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof CloseWebSocketFrame) {
                if (targetChannel != null) targetChannel.close();
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
                return;
            }
            if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
                return;
            }
            if (!(frame instanceof BinaryWebSocketFrame)) return;

            ByteBuf buf = frame.content();
            if (targetChannel == null) {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                processFirstPacket(ctx, data);
            } else {
                targetChannel.writeAndFlush(buf.retain());
            }
        }

        private void processFirstPacket(ChannelHandlerContext ctx, byte[] data) {
            if (data.length > 17 && data[0] == 0 && matchUuid(data, 1)) {
                handleVless(ctx, data);
            } else if (data.length >= 58) {
                handleTrojan(ctx, data);
            } else {
                ctx.close();
            }
        }

        private void handleVless(ChannelHandlerContext ctx, byte[] msg) {
            int version = msg[0] & 0xFF;
            int optLen = msg[17] & 0xFF;
            int i = 18 + optLen + 1;
            int port = ((msg[i] & 0xFF) << 8) | (msg[i + 1] & 0xFF);
            i += 2;
            int atyp = msg[i++] & 0xFF;

            String host = parseHost(msg, atyp, i);
            i += getHostLength(msg, atyp, i);

            ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{(byte) version, 0})));

            byte[] remaining = i < msg.length ? Arrays.copyOfRange(msg, i, msg.length) : null;
            connectTarget(ctx, host, port, remaining);
        }

        private void handleTrojan(ChannelHandlerContext ctx, byte[] msg) {
            String expectedHash = sha224(UUID);
            String receivedHash = new String(msg, 0, 56, StandardCharsets.UTF_8);
            if (!expectedHash.equals(receivedHash)) {
                ctx.close();
                return;
            }

            int offset = 56;
            if (offset + 1 < msg.length && msg[offset] == 0x0d && msg[offset + 1] == 0x0a) offset += 2;
            if (msg[offset] != 0x01) {
                ctx.close();
                return;
            }
            offset++;

            int atyp = msg[offset++] & 0xFF;
            String host = parseHost(msg, atyp, offset);
            offset += getHostLength(msg, atyp, offset);

            int port = ((msg[offset] & 0xFF) << 8) | (msg[offset + 1] & 0xFF);
            offset += 2;
            if (offset + 1 < msg.length && msg[offset] == 0x0d && msg[offset + 1] == 0x0a) offset += 2;
            byte[] remaining = offset < msg.length ? Arrays.copyOfRange(msg, offset, msg.length) : null;
            connectTarget(ctx, host, port, remaining);
        }

        // VLESS: 1=IPv4, 2=域名, 3=IPv6
        // Trojan: 0x01=IPv4, 0x03=域名, 0x04=IPv6
        private String parseHost(byte[] msg, int atyp, int offset) {
            if (atyp == 0x01 || atyp == 1) { // IPv4
                return (msg[offset] & 0xFF) + "." + (msg[offset+1] & 0xFF) + "." + (msg[offset+2] & 0xFF) + "." + (msg[offset+3] & 0xFF);
            } else if (atyp == 0x03 || atyp == 2) { // 域名
                int len = msg[offset] & 0xFF;
                return new String(msg, offset + 1, len, StandardCharsets.UTF_8);
            } else if (atyp == 0x04 || atyp == 3) { // IPv6
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < 16; j += 2) {
                    if (j > 0) sb.append(':');
                    sb.append(Integer.toHexString(((msg[offset+j] & 0xFF) << 8) | (msg[offset+j+1] & 0xFF)));
                }
                return sb.toString();
            }
            return "";
        }

        private int getHostLength(byte[] msg, int atyp, int offset) {
            if (atyp == 0x01 || atyp == 1) return 4; // IPv4
            if (atyp == 0x03 || atyp == 2) return 1 + (msg[offset] & 0xFF); // 域名
            if (atyp == 0x04 || atyp == 3) return 16; // IPv6
            return 0;
        }

        private void connectTarget(ChannelHandlerContext ctx, String host, int port, byte[] initialData) {
            resolveHost(ctx.channel().eventLoop(), host, resolved -> {
                Bootstrap b = new Bootstrap();
                b.group(ctx.channel().eventLoop())
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext tctx, ByteBuf msg) {
                                    if (ctx.channel().isActive()) {
                                        ctx.writeAndFlush(new BinaryWebSocketFrame(msg.retain()));
                                    }
                                }
                                @Override
                                public void channelInactive(ChannelHandlerContext tctx) {
                                    ctx.close();
                                }
                                @Override
                                public void exceptionCaught(ChannelHandlerContext tctx, Throwable cause) {
                                    tctx.close();
                                    ctx.close();
                                }
                            });
                        }
                    });

                b.connect(resolved, port).addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        targetChannel = future.channel();
                        if (initialData != null) {
                            targetChannel.writeAndFlush(Unpooled.wrappedBuffer(initialData));
                        }
                    } else {
                        ctx.close();
                    }
                });
            });
        }

        private void resolveHost(EventLoop eventLoop, String host, java.util.function.Consumer<String> callback) {
            if (host.matches("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")) {
                callback.accept(host);
                return;
            }

            try {
                SslContext sslCtx = SslContextBuilder.forClient().build();
                Bootstrap b = new Bootstrap();
                b.group(eventLoop)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                sslCtx.newHandler(ch.alloc(), "dns.google", 443),
                                new HttpClientCodec(),
                                new HttpObjectAggregator(8192),
                                new SimpleChannelInboundHandler<FullHttpResponse>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse resp) {
                                        String json = resp.content().toString(StandardCharsets.UTF_8);
                                        ctx.close();
                                        callback.accept(parseDnsResponse(json, host));
                                    }
                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                        ctx.close();
                                        callback.accept(host);
                                    }
                                }
                            );
                        }
                    });

                b.connect("dns.google", 443).addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        String uri = "/resolve?name=" + URLEncoder.encode(host, StandardCharsets.UTF_8) + "&type=A";
                        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
                        req.headers().set(HttpHeaderNames.HOST, "dns.google");
                        req.headers().set(HttpHeaderNames.ACCEPT, "application/dns-json");
                        req.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        future.channel().writeAndFlush(req);
                    } else {
                        callback.accept(host);
                    }
                });
            } catch (Exception e) {
                callback.accept(host);
            }
        }

        private String parseDnsResponse(String json, String fallback) {
            if (json.contains("\"Status\":0") && json.contains("\"Answer\":")) {
                int idx = json.indexOf("\"type\":1");
                if (idx > 0) {
                    int dataIdx = json.indexOf("\"data\":\"", idx);
                    if (dataIdx > 0) {
                        int start = dataIdx + 8;
                        int end = json.indexOf("\"", start);
                        return json.substring(start, end);
                    }
                }
            }
            return fallback;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (targetChannel != null) targetChannel.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
            if (targetChannel != null) targetChannel.close();
        }
    }

    private static boolean matchUuid(byte[] data, int offset) {
        for (int i = 0; i < 16; i++) {
            if (data[offset + i] != UUID_BYTES[i]) return false;
        }
        return true;
    }

    private static String sha224(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-224");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
