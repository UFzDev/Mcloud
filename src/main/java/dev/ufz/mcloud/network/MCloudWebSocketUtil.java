package dev.ufz.mcloud.network;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * RFC 6455 WebSocket cryptographic and framing utilities.
 */
public final class MCloudWebSocketUtil {
    public static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private MCloudWebSocketUtil() {}

    /**
     * Computes the RFC 6455 Sec-WebSocket-Accept header value for a given Sec-WebSocket-Key.
     */
    public static String computeAcceptKey(String secWebSocketKey) {
        if (secWebSocketKey == null) {
            throw new IllegalArgumentException("secWebSocketKey cannot be null");
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((secWebSocketKey.trim() + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

    /**
     * Generates a random 16-byte Base64-encoded Sec-WebSocket-Key for client handshakes.
     */
    public static String generateClientKey() {
        byte[] nonce = new byte[16];
        SECURE_RANDOM.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    /**
     * Applies XOR masking/unmasking in-place on a ByteBuf between readerIndex and writerIndex.
     * Uses vectorised 64-bit (long) processing followed by residual bytes for maximum Netty throughput.
     */
    public static void applyMask(ByteBuf buf, byte[] maskKey) {
        if (maskKey == null || maskKey.length != 4) {
            return;
        }

        int start = buf.readerIndex();
        int len = buf.readableBytes();
        if (len == 0) {
            return;
        }

        // Precompute 64-bit repeated mask key [m0, m1, m2, m3, m0, m1, m2, m3]
        long m0 = maskKey[0] & 0xFFL;
        long m1 = maskKey[1] & 0xFFL;
        long m2 = maskKey[2] & 0xFFL;
        long m3 = maskKey[3] & 0xFFL;
        long maskLong = (m0 << 56) | (m1 << 48) | (m2 << 40) | (m3 << 32)
                      | (m0 << 24) | (m1 << 16) | (m2 << 8)  | m3;

        int longBlocks = len >>> 3; // divide by 8
        int idx = start;

        for (int i = 0; i < longBlocks; i++) {
            long val = buf.getLong(idx);
            buf.setLong(idx, val ^ maskLong);
            idx += 8;
        }

        int remainder = len & 7;
        int offset = (len - remainder);
        for (int i = 0; i < remainder; i++) {
            int currentIdx = idx + i;
            int maskIdx = (offset + i) & 3;
            buf.setByte(currentIdx, buf.getByte(currentIdx) ^ maskKey[maskIdx]);
        }
    }
}
