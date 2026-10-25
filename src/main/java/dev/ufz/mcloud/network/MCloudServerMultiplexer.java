package dev.ufz.mcloud.network;

import dev.ufz.mcloud.MCloudMod;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.AttributeKey;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dual-Protocol Netty Multiplexer on the Minecraft server port.
 * Inspects the first incoming bytes:
 * - If HTTP WebSocket Upgrade ('GET /...'), performs RFC 6455 101 handshake and installs WebSocket frame codecs.
 * - If standard HTTP GET (browser/healthcheck), responds with 200 OK JSON status.
 * - If native Minecraft TCP handshake (VarInt or 0xFE), removes itself in O(1) with zero overhead.
 */
public class MCloudServerMultiplexer extends ByteToMessageDecoder {
    public static final AttributeKey<String> CF_CLIENT_IP_ATTR = AttributeKey.valueOf("mcloud_cf_client_ip");
    private static final int MAX_HTTP_HEADER_SIZE = 8192;
    private boolean upgraded = false;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (upgraded) {
            // Already upgraded to WebSocket; forward any remaining bytes to next handler
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
            return;
        }

        if (!in.isReadable()) {
            return;
        }

        int firstByte = in.getUnsignedByte(in.readerIndex());
        // 'G' (0x47) for GET or 'H' (0x48) for HEAD
        if (firstByte != 'G' && firstByte != 'H') {
            // Native Minecraft TCP connection (LAN / direct). Remove multiplexer cleanly.
            ctx.pipeline().remove(this);
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
            return;
        }

        // Wait until we have at least 4 bytes to confirm "GET " or "HEAD"
        if (in.readableBytes() < 4) {
            return;
        }

        int rIdx = in.readerIndex();
        boolean isGet = in.getByte(rIdx) == 'G'
                && in.getByte(rIdx + 1) == 'E'
                && in.getByte(rIdx + 2) == 'T'
                && in.getByte(rIdx + 3) == ' ';
        boolean isHead = in.getByte(rIdx) == 'H'
                && in.getByte(rIdx + 1) == 'E'
                && in.getByte(rIdx + 2) == 'A'
                && in.getByte(rIdx + 3) == 'D';

        if (!isGet && !isHead) {
            ctx.pipeline().remove(this);
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }

        // Search for end of HTTP headers (\r\n\r\n)
        int headerEndIndex = findHeaderEnd(in);
        if (headerEndIndex == -1) {
            if (in.readableBytes() > MAX_HTTP_HEADER_SIZE) {
                throw new CorruptedFrameException("HTTP header exceeds maximum allowed size");
            }
            return;
        }

        int headerLength = (headerEndIndex - in.readerIndex()) + 4;
        CharSequence headerSeq = in.readCharSequence(headerLength, StandardCharsets.US_ASCII);
        String rawHeaders = headerSeq.toString();

        Map<String, String> headers = parseHeaders(rawHeaders);
        String upgrade = headers.getOrDefault("upgrade", "");
        String wsKey = headers.get("sec-websocket-key");

        if ("websocket".equalsIgnoreCase(upgrade) && wsKey != null && !wsKey.isBlank()) {
            try {
                String cfIp = headers.getOrDefault("cf-connecting-ip", headers.getOrDefault("x-forwarded-for", "unknown"));
                ctx.channel().attr(CF_CLIENT_IP_ATTR).set(cfIp);
                MCloudMod.LOGGER.info("[MCloud] Upgrading incoming Cloudflare WebSocket connection (Client IP: {})", cfIp);

                String acceptKey = MCloudWebSocketUtil.computeAcceptKey(wsKey);
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + acceptKey + "\r\n"
                        + "Server: MCloud/1.0\r\n\r\n";

                ByteBuf responseBuf = Unpooled.copiedBuffer(response, StandardCharsets.US_ASCII);
                ctx.writeAndFlush(responseBuf);
                this.upgraded = true;

                // Defer pipeline mutation to event loop to avoid modifying pipeline inside decode()
                final ChannelHandlerContext currentCtx = ctx;
                final ByteBuf remainder = in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : null;

                ctx.channel().eventLoop().execute(() -> {
                    try {
                        if (currentCtx.pipeline().context(MCloudServerMultiplexer.this) != null) {
                            currentCtx.pipeline().addBefore(currentCtx.name(), "mcloud_ws_encoder", new MCloudWebSocketFrameEncoder(false));
                            currentCtx.pipeline().replace(MCloudServerMultiplexer.this, "mcloud_ws_decoder", new MCloudWebSocketFrameDecoder(false));
                            MCloudMod.LOGGER.info("[MCloud] WebSocket frame encoder and decoder successfully installed on server channel");
                            if (remainder != null) {
                                currentCtx.fireChannelRead(remainder);
                            }
                        }
                    } catch (Exception e) {
                        MCloudMod.LOGGER.error("[MCloud] Failed to install WebSocket codecs on server pipeline", e);
                    }
                });
            } catch (Exception e) {
                MCloudMod.LOGGER.error("[MCloud] Exception handling WebSocket upgrade", e);
                ctx.close();
            }
        } else {
            // Regular HTTP visit (browser or health probe)
            String body = "{\"status\":\"online\",\"mod\":\"MCloud\",\"version\":\"1.0.4\",\"protocol\":\"RFC6455-WebSocket\",\"info\":\"Use Minecraft 1.20.1 with the MCloud mod to join this server.\"}";
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String httpResponse = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n"
                    + "Server: MCloud/1.0\r\n\r\n";

            ByteBuf respBuf = ctx.alloc().buffer(httpResponse.length() + bodyBytes.length);
            respBuf.writeCharSequence(httpResponse, StandardCharsets.US_ASCII);
            if (!isHead) {
                respBuf.writeBytes(bodyBytes);
            }
            // Discard any remaining readable bytes
            in.skipBytes(in.readableBytes());
            ctx.writeAndFlush(respBuf).addListener(ChannelFutureListener.CLOSE);
        }
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

    private static Map<String, String> parseHeaders(String rawHeaders) {
        Map<String, String> map = new HashMap<>();
        String[] lines = rawHeaders.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = line.substring(colon + 1).trim();
                map.put(key, val);
            }
        }
        return map;
    }
}