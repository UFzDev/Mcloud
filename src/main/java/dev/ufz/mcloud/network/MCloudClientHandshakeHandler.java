package dev.ufz.mcloud.network;

import dev.ufz.mcloud.MCloudMod;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-side handler that initiates the RFC 6455 HTTP WebSocket handshake.
 * Holds channelActive downstream until the HTTP 101 Switching Protocols response is received,
 * buffering any initial outbound Minecraft packets or ByteBufs until the tunnel is ready.
 */
public class MCloudClientHandshakeHandler extends ChannelDuplexHandler {
    private final String host;
    private final String path;
    private String expectedAcceptKey;
    private boolean handshakeComplete = false;
    private final ByteBuf cumulationBuffer = Unpooled.buffer(2048);
    private final List<QueuedWrite> pendingWrites = new ArrayList<>();

    private record QueuedWrite(Object msg, ChannelPromise promise) {}

    public MCloudClientHandshakeHandler(String host, String path) {
        this.host = host;
        this.path = (path == null || path.isBlank()) ? "/mcloud" : path;
        String clientKey = MCloudWebSocketUtil.generateClientKey();
        this.expectedAcceptKey = MCloudWebSocketUtil.computeAcceptKey(clientKey);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        MCloudMod.LOGGER.info("[MCloud] Initiating WebSocket upgrade handshake to {}{}", host, path);
        String clientKey = MCloudWebSocketUtil.generateClientKey();
        this.expectedAcceptKey = MCloudWebSocketUtil.computeAcceptKey(clientKey);

        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + clientKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: MCloud/1.0 (Minecraft Fabric)\r\n\r\n";

        ByteBuf reqBuf = Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII);
        ctx.writeAndFlush(reqBuf);
        // Do not call ctx.fireChannelActive() yet: wait for 101 Switching Protocols!
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (handshakeComplete) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (msg instanceof ByteBuf buf) {
            cumulationBuffer.writeBytes(buf);
            buf.release();

            int headerEnd = findHeaderEnd(cumulationBuffer);
            if (headerEnd != -1) {
                int headerLen = (headerEnd - cumulationBuffer.readerIndex()) + 4;
                String headerStr = cumulationBuffer.readCharSequence(headerLen, StandardCharsets.US_ASCII).toString();

                if (!headerStr.contains("101 Switching Protocols") && !headerStr.contains("101 Web Socket Protocol Handshake")) {
                    MCloudMod.LOGGER.error("[MCloud] WebSocket handshake failed! Server returned:\n{}", headerStr);
                    ctx.close();
                    return;
                }

                MCloudMod.LOGGER.info("[MCloud] WebSocket tunnel established successfully to {}", host);
                handshakeComplete = true;

                // Remove handshake handler first so it doesn't interfere
                ctx.pipeline().remove(this);

                // Install WebSocket codecs directly at the wire level (immediately adjacent to SSL)
                // Netty Inbound flow: wire -> mcloud_ssl -> mcloud_client_ws_decoder -> timeout -> splitter -> decoder -> packet_handler
                // Netty Outbound flow: packet_handler -> encoder -> prepender -> mcloud_client_ws_encoder -> mcloud_ssl -> wire
                if (ctx.pipeline().get("mcloud_ssl") != null) {
                    ctx.pipeline().addAfter("mcloud_ssl", "mcloud_client_ws_encoder", new MCloudWebSocketFrameEncoder(true));
                    ctx.pipeline().addAfter("mcloud_ssl", "mcloud_client_ws_decoder", new MCloudWebSocketFrameDecoder(true));
                } else {
                    ctx.pipeline().addFirst("mcloud_client_ws_encoder", new MCloudWebSocketFrameEncoder(true));
                    ctx.pipeline().addFirst("mcloud_client_ws_decoder", new MCloudWebSocketFrameDecoder(true));
                }

                MCloudMod.LOGGER.info("[MCloud] Client pipeline after installing codecs: {}", ctx.pipeline().names());

                // If any remaining bytes came after \r\n\r\n, forward them into the new WebSocket decoder BEFORE channelActive
                if (cumulationBuffer.isReadable()) {
                    ByteBuf remainder = cumulationBuffer.readRetainedSlice(cumulationBuffer.readableBytes());
                    ctx.pipeline().context("mcloud_client_ws_decoder").fireChannelRead(remainder);
                }

                // Notify Minecraft that connection is now ready to send/receive packets
                ctx.fireChannelActive();

                // Flush any writes that were buffered
                if (!pendingWrites.isEmpty()) {
                    MCloudMod.LOGGER.info("[MCloud] Flushing {} queued outbound packets through pipeline", pendingWrites.size());
                    for (QueuedWrite qw : pendingWrites) {
                        ctx.channel().write(qw.msg(), qw.promise());
                    }
                    pendingWrites.clear();
                    ctx.channel().flush();
                }
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!handshakeComplete) {
            pendingWrites.add(new QueuedWrite(msg, promise));
        } else {
            ctx.write(msg, promise);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cumulationBuffer.release();
        for (QueuedWrite qw : pendingWrites) {
            ReferenceCountUtil.safeRelease(qw.msg());
        }
        pendingWrites.clear();
        super.channelInactive(ctx);
    }

    private static int findHeaderEnd(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = buf.writerIndex() - 3;
        for (int i = start; i < end; i++) {
            if (buf.getByte(i) == '\r'
                    && buf.getByte(i + 1) == '\n'
                    && buf.getByte(i + 2) == '\r'
                    && buf.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }
}