package dev.ufz.mcloud.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RFC 6455 WebSocket Frame Decoder that unwraps binary frames into raw ByteBuf streams for Minecraft.
 */
public class MCloudWebSocketFrameDecoder extends ByteToMessageDecoder {
    private static final long MAX_FRAME_SIZE = 32L * 1024L * 1024L; // 32 MB safety cap
    private final boolean maskControlReplies;

    public MCloudWebSocketFrameDecoder(boolean maskControlReplies) {
        this.maskControlReplies = maskControlReplies;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 2) {
            return;
        }

        in.markReaderIndex();
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();

        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long payloadLen = b1 & 0x7F;

        if (payloadLen == 126) {
            if (in.readableBytes() < 2) {
                in.resetReaderIndex();
                return;
            }
            payloadLen = in.readUnsignedShort();
        } else if (payloadLen == 127) {
            if (in.readableBytes() < 8) {
                in.resetReaderIndex();
                return;
            }
            payloadLen = in.readLong();
            if (payloadLen < 0 || payloadLen > MAX_FRAME_SIZE) {
                throw new CorruptedFrameException("WebSocket frame payload too large: " + payloadLen);
            }
        }

        int maskBytes = masked ? 4 : 0;
        if (in.readableBytes() < maskBytes + payloadLen) {
            in.resetReaderIndex();
            return;
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            in.readBytes(maskKey);
        }

        int intLen = (int) payloadLen;
        ByteBuf payload = ctx.alloc().buffer(intLen);
        in.readBytes(payload, intLen);

        if (masked) {
            MCloudWebSocketUtil.applyMask(payload, maskKey);
        }

        switch (opcode) {
            case 0x00, 0x01, 0x02 -> {
                if (payload.isReadable()) {
                    out.add(payload);
                } else {
                    payload.release();
                }
            }
            case 0x09 -> {
                // Ping -> reply with Pong (0x0A)
                sendPong(ctx, payload);
                payload.release();
            }
            case 0x0A -> {
                // Pong -> ignore
                payload.release();
            }
            case 0x08 -> {
                // Close frame
                payload.release();
                ctx.close();
            }
            default -> payload.release();
        }
    }

    private void sendPong(ChannelHandlerContext ctx, ByteBuf pingPayload) {
        int len = Math.min(pingPayload.readableBytes(), 125);
        ByteBuf pong = ctx.alloc().buffer(2 + (maskControlReplies ? 4 : 0) + len);
        pong.writeByte(0x8A); // FIN=1, Opcode=0x0A (Pong)
        if (maskControlReplies) {
            pong.writeByte(0x80 | len);
            int mask = ThreadLocalRandom.current().nextInt();
            byte[] maskKey = new byte[]{
                    (byte) (mask >>> 24),
                    (byte) (mask >>> 16),
                    (byte) (mask >>> 8),
                    (byte) mask
            };
            pong.writeBytes(maskKey);
            for (int i = 0; i < len; i++) {
                pong.writeByte(pingPayload.getByte(pingPayload.readerIndex() + i) ^ maskKey[i & 3]);
            }
        } else {
            pong.writeByte(len);
            pong.writeBytes(pingPayload, pingPayload.readerIndex(), len);
        }
        ctx.writeAndFlush(pong);
    }
}
