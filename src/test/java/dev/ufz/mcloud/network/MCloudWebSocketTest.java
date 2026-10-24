package dev.ufz.mcloud.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MCloudWebSocketTest {

    @Test
    @DisplayName("RFC 6455 computeAcceptKey calculates official test vector correctly")
    void testComputeAcceptKey() {
        // Official RFC 6455 Section 1.3 test vector
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

        assertEquals(expectedAccept, MCloudWebSocketUtil.computeAcceptKey(clientKey));
    }

    @Test
    @DisplayName("Address registry normalizes diverse multiplayer input formats")
    void testAddressNormalization() {
        MCloudAddressRegistry.clear();

        // 1. Full HTTPS trycloudflare URL
        assertEquals("quick-tunnel.trycloudflare.com",
                MCloudAddressRegistry.normalizeServerAddressInput("https://quick-tunnel.trycloudflare.com"));

        // 2. WSS with custom port
        assertEquals("mc.myserver.com:8443",
                MCloudAddressRegistry.normalizeServerAddressInput("wss://mc.myserver.com:8443"));

        // 3. WS unencrypted with custom port
        assertEquals("127.0.0.1:25565",
                MCloudAddressRegistry.normalizeServerAddressInput("ws://127.0.0.1:25565"));

        // 4. mcloud:// scheme
        assertEquals("play.mcloudserver.net",
                MCloudAddressRegistry.normalizeServerAddressInput("mcloud://play.mcloudserver.net"));

        // 5. Bare trycloudflare domain without scheme
        assertEquals("cool-tunnel.trycloudflare.com",
                MCloudAddressRegistry.normalizeServerAddressInput("cool-tunnel.trycloudflare.com"));

        // 6. Vanilla IP remains untouched
        assertEquals("192.168.100.211:25565",
                MCloudAddressRegistry.normalizeServerAddressInput("192.168.100.211:25565"));
    }

    @Test
    @DisplayName("Frame encoder and decoder roundtrip with and without masking")
    void testFrameCodecRoundtrip() {
        // Test client encoder (masked) -> server decoder (unmasked out)
        MCloudWebSocketFrameEncoder clientEncoder = new MCloudWebSocketFrameEncoder(true);
        MCloudWebSocketFrameDecoder serverDecoder = new MCloudWebSocketFrameDecoder(false);

        EmbeddedChannel clientChannel = new EmbeddedChannel(clientEncoder);
        EmbeddedChannel serverChannel = new EmbeddedChannel(serverDecoder);

        byte[] originalData = "Hello MCloud! Testing Minecraft packet transmission over WebSocket.".getBytes(StandardCharsets.UTF_8);
        ByteBuf inBuf = Unpooled.copiedBuffer(originalData);

        assertTrue(clientChannel.writeOutbound(inBuf));
        ByteBuf wireFrame = clientChannel.readOutbound();
        assertNotNull(wireFrame);

        // Verify MASK bit is set on client wire frame
        int b1 = wireFrame.getUnsignedByte(1);
        assertEquals(0x80, b1 & 0x80, "Client frame must have MASK bit set");

        assertTrue(serverChannel.writeInbound(wireFrame));
        ByteBuf outBuf = serverChannel.readInbound();
        assertNotNull(outBuf);

        byte[] receivedData = new byte[outBuf.readableBytes()];
        outBuf.readBytes(receivedData);
        outBuf.release();

        assertArrayEquals(originalData, receivedData);
    }

    @Test
    @DisplayName("Large packet (e.g. chunk/recipe > 65535 bytes) encodes and decodes accurately")
    void testLargePayloadCodec() {
        MCloudWebSocketFrameEncoder encoder = new MCloudWebSocketFrameEncoder(false);
        MCloudWebSocketFrameDecoder decoder = new MCloudWebSocketFrameDecoder(false);

        EmbeddedChannel sender = new EmbeddedChannel(encoder);
        EmbeddedChannel receiver = new EmbeddedChannel(decoder);

        byte[] largeData = new byte[120000]; // 120 KB
        new Random(42).nextBytes(largeData);

        assertTrue(sender.writeOutbound(Unpooled.copiedBuffer(largeData)));
        ByteBuf wire = sender.readOutbound();
        assertNotNull(wire);

        assertTrue(receiver.writeInbound(wire));
        ByteBuf decoded = receiver.readInbound();
        assertNotNull(decoded);

        byte[] result = new byte[decoded.readableBytes()];
        decoded.readBytes(result);
        decoded.release();

        assertArrayEquals(largeData, result);
    }

    @Test
    @DisplayName("Server multiplexer handles WebSocket upgrade and installs frame codecs")
    void testServerMultiplexerUpgrade() {
        MCloudServerMultiplexer multiplexer = new MCloudServerMultiplexer();
        EmbeddedChannel channel = new EmbeddedChannel(multiplexer);

        String clientKey = "x3JJHMbDL1EzLkh9GBhXDw==";
        String request = "GET /mcloud HTTP/1.1\r\n"
                + "Host: my-tunnel.trycloudflare.com\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + clientKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "CF-Connecting-IP: 201.140.50.88\r\n\r\n";

        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));

        // Multiplexer should output 101 Switching Protocols response
        ByteBuf response = channel.readOutbound();
        assertNotNull(response);
        String respStr = response.toString(StandardCharsets.US_ASCII);
        response.release();

        assertTrue(respStr.contains("HTTP/1.1 101 Switching Protocols"));
        assertTrue(respStr.contains("Sec-WebSocket-Accept: " + MCloudWebSocketUtil.computeAcceptKey(clientKey)));

        // Channel attribute should contain the CF client IP
        assertEquals("201.140.50.88", channel.attr(MCloudServerMultiplexer.CF_CLIENT_IP_ATTR).get());

        // Pipeline should now have the WebSocket frame decoder installed
        assertNotNull(channel.pipeline().get("mcloud_ws_decoder"));
        assertNotNull(channel.pipeline().get("mcloud_ws_encoder"));
    }

    @Test
    @DisplayName("Server multiplexer allows native Minecraft TCP handshake to bypass without alteration")
    void testServerMultiplexerNativeBypass() {
        MCloudServerMultiplexer multiplexer = new MCloudServerMultiplexer();
        EmbeddedChannel channel = new EmbeddedChannel(multiplexer);

        // Vanilla Minecraft Handshake packet starts with VarInt length (e.g. 0x10)
        byte[] vanillaHandshake = new byte[]{0x10, 0x00, (byte) 0xFD, 0x05, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't', 0x63, (byte) 0xDD, 0x02};
        channel.writeInbound(Unpooled.copiedBuffer(vanillaHandshake));

        ByteBuf passedThrough = channel.readInbound();
        assertNotNull(passedThrough);
        byte[] received = new byte[passedThrough.readableBytes()];
        passedThrough.readBytes(received);
        passedThrough.release();

        assertArrayEquals(vanillaHandshake, received);
        assertNull(channel.pipeline().get("mcloud_multiplexer"), "Multiplexer should remove itself for native TCP");
    }
}
