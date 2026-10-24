# MCloud

High-performance Fabric mod providing native RFC 6455 WebSocket tunneling and Zero-Config edge routing for Minecraft over Cloudflare Tunnels (port 443).

## Abstract

Hosting dedicated Minecraft multiplayer servers behind residential Internet Service Providers (ISPs) frequently fails due to Carrier-Grade NAT (CGNAT, RFC 6598) and dynamic IP pools. Totalplay, Starlink, and mobile broadband providers do not assign globally routable IPv4 addresses to Customer Premises Equipment (CPE), making conventional port forwarding (TCP 25565) inoperative.

While Cloudflare offers free Edge Tunnels (`trycloudflare.com` and custom domains), the Cloudflare Free tier proxies only HTTP, HTTPS, and WebSocket (RFC 6455) protocols over port 443; raw TCP stream tunneling (`cloudflared access tcp`) requires every connecting client to install and configure local daemon software, adding unacceptable friction for multiplayer communities.

MCloud eliminates external client binaries by encapsulating the Minecraft binary protocol directly within secure WebSocket (`wss://`) frames at the Netty pipeline level. Client players simply input their tunnel address into Minecraft's native server list.

## Architecture

The system operates across three network tiers:

```
+-------------------------------------------------------------+
|                     Minecraft Client                        |
|                                                             |
|   ConnectionClientConnectMixin                              |
|   ServerAddressMixin: Normalizes URI (wss://, https://, etc)|
|   SslHandler: TLS 1.3 encryption (SNI + Standard CA Trust)  |
|   MCloudClientHandshakeHandler: RFC 6455 101 Handshake      |
|   MCloudWebSocketFrameEncoder / Decoder (Vectorized XOR)    |
+------------------------------+------------------------------+
                               |
                               | TLS / WSS (Port 443)
                               v
+-------------------------------------------------------------+
|                Cloudflare Global Anycast Edge               |
|                                                             |
|   DDoS Mitigation, Zero-RTT Session Resumption              |
|   Anycast Edge PoPs (CDMX, QRO, etc. ~5-8 ms RTT)           |
+------------------------------+------------------------------+
                               |
                               | QUIC / HTTP/2 Ingress Tunnel
                               v
+-------------------------------------------------------------+
|             Dedicated Server (Acer Server / Linux)          |
|                                                             |
|   cloudflared tunnel daemon (Local Ingress)                 |
|   ServerConnectionListenerInitMixin                         |
|   MCloudServerMultiplexer: Dual-Protocol O(1) Sniffer       |
|      |- HTTP 101 WebSocket Upgrade -> Frame Codecs          |
|      |- HTTP 200 JSON Probe -> Health check response        |
|      \- Native Minecraft TCP -> Direct zero-overhead bypass |
|   CloudflaredManager: Autonomous process supervision        |
+-------------------------------------------------------------+
```

### Key Technical Specifications

- **Dual Protocol Multiplexing ($O(1)$)**: The server multiplexer inspects the first 4 bytes of incoming connections. Native Minecraft TCP handshakes (`0xFE` or VarInt length) immediately detach the multiplexer without packet modification or memory allocation. HTTP Upgrade requests trigger a 101 Switching Protocols response and install RFC 6455 codecs.
- **High-Throughput Vectorized XOR Masking**: WebSocket client frames require 32-bit XOR masking under RFC 6455 Section 5.3. MCloud implements 64-bit vectorized unrolling (`buf.getLong(...) ^ maskLong`), processing 8 bytes per CPU cycle and eliminating single-byte Netty allocations.
- **Client Handshake Buffering**: During the TLS and HTTP 101 negotiation, initial outbound Minecraft handshake packets are held in a bounded Netty queue and flushed atomically once the WebSocket pipeline is marked active.

## Installation

### Client Requirements
- Minecraft 1.20.1
- Fabric Loader >= 0.15.0
- Fabric API

Place `mcloud-<version>.jar` into the `.minecraft/mods` directory. Enter your Cloudflare tunnel domain (e.g. `subdomain.trycloudflare.com` or `wss://minecraft.yourdomain.com`) in the Multiplayer Server Address input field.

### Server Requirements
- Minecraft 1.20.1 Dedicated Server (Fabric)
- Fabric API
- Java 17 or higher
- `cloudflared` binary available on system PATH or auto-downloaded by MCloud

Place `mcloud-<version>.jar` into the server `mods/` directory.

## Configuration

A configuration file is generated at `config/mcloud.json` on initial boot:

```json
{
  "enabled": true,
  "listenPort": 25565,
  "autoStartCloudflared": true,
  "autoDownloadCloudflared": true,
  "tunnelMode": "quick",
  "tunnelToken": "",
  "customCloudflareDomains": []
}
```

### Configuration Keys

| Parameter | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `true` | Enables or disables MCloud Netty handlers. |
| `listenPort` | integer | `25565` | Target local port for incoming Minecraft connections. |
| `autoStartCloudflared` | boolean | `true` | Spawns `cloudflared` on server startup. |
| `autoDownloadCloudflared` | boolean | `true` | Downloads official Cloudflare binaries if not found on PATH. |
| `tunnelMode` | string | `"quick"` | `"quick"` for temporary trycloudflare.com; `"token"` for Zero Trust custom domains. |
| `tunnelToken` | string | `""` | Zero Trust tunnel authentication token when using token mode. |
| `customCloudflareDomains` | list | `[]` | List of custom domain names routed through Cloudflare. |

## Commands

Server operators (permission level 2) have access to the `/mcloud` command suite:

- `/mcloud status`: Displays tunnel status, process health, and click-to-copy active tunnel endpoint.
- `/mcloud start`: Launches the `cloudflared` daemon process and logs the public hostname.
- `/mcloud stop`: Terminates the active tunnel daemon.
- `/mcloud restart`: Restarts the tunnel process and refreshes public endpoint binding.

## Building from Source

```bash
git clone https://github.com/UFzDev/Mcloud.git
cd Mcloud
./gradlew build
```

Compiled binaries are output to `build/libs/mcloud-<version>.jar`.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
