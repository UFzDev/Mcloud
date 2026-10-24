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
     */
    public static void applyMask(ByteBuf buf, byte[] maskKey) {
        int start = buf.readerIndex();
        int len = buf.readableBytes();
        for (int i = 0; i < len; i++) {
            int idx = start + i;
            buf.setByte(idx, buf.getByte(idx) ^ maskKey[i & 3]);
        }
    }
}
