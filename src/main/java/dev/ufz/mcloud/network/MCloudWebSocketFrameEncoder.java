package dev.ufz.mcloud.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.concurrent.ThreadLocalRandom;

/**
 * RFC 6455 WebSocket Frame Encoder that wraps raw Minecraft ByteBuf packets into Binary WebSocket frames (0x82).
 */
public class MCloudWebSocketFrameEncoder extends MessageToByteEncoder<ByteBuf> {
    private final boolean maskPayload;

    public MCloudWebSocketFrameEncoder(boolean maskPayload) {
        this.maskPayload = maskPayload;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int len = msg.readableBytes();
        if (len == 0) {
            return;
        }

        out.writeByte(0x82); // FIN=1, Opcode=2 (Binary Frame)
        int maskFlag = maskPayload ? 0x80 : 0x00;

        if (len <= 125) {
            out.writeByte(maskFlag | len);
        } else if (len <= 65535) {
            out.writeByte(maskFlag | 126);
            out.writeShort(len);
        } else {
            out.writeByte(maskFlag | 127);
            out.writeLong(len);
        }

        if (maskPayload) {
            int mask = ThreadLocalRandom.current().nextInt();
            byte m0 = (byte) (mask >>> 24);
            byte m1 = (byte) (mask >>> 16);
            byte m2 = (byte) (mask >>> 8);
            byte m3 = (byte) mask;
            byte[] maskKey = new byte[]{m0, m1, m2, m3};
            out.writeBytes(maskKey);

            int readerIdx = msg.readerIndex();
            for (int i = 0; i < len; i++) {
                out.writeByte(msg.getByte(readerIdx + i) ^ maskKey[i & 3]);
            }
        } else {
            out.writeBytes(msg, msg.readerIndex(), len);
        }
    }
}
