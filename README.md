<p align="center">
  <img src="assets/header.png?v=2" alt="MCloud Header" width="100%" />
</p>

# MCloud

A high-performance, zero-config Fabric mod that tunnels Minecraft TCP traffic through Cloudflare Tunnels (port 443) using RFC 6455 WebSockets over TLS.

---

## The Problem MCloud Solves

Hosting dedicated Minecraft multiplayer servers behind residential Internet Service Providers (ISPs) often fails due to **Carrier-Grade NAT (CGNAT)** or strict home routers that prevent opening port `25565`.

Traditional workarounds come with significant trade-offs:
- **Hamachi / Radmin VPN**: Every player must install a third-party VPN client and virtual network adapter, traffic is capped, and local subnets are exposed.
- **Playit.gg / Ngrok**: Free tiers enforce strict bandwidth/tunnel limits or add noticeable routing latency.
- **Standard Cloudflare Tunnels**: Cloudflare's free ingress tier only proxies **HTTP/HTTPS and WebSockets over port 443**. Running raw TCP (`cloudflared access tcp`) requires every single player to install command-line tools and configure local proxy ports.

**MCloud eliminates all external client software.** It bridges Minecraft packets directly into secure WebSocket (`wss://`) frames at the Netty pipeline layer. Players simply enter the Cloudflare tunnel domain into the vanilla Minecraft multiplayer list.

---

## How It Works

```
[ Minecraft Client ]
  |
  +-- ConnectionClientConnectMixin (Redirects tunnel to port 443)
  +-- SslHandler (TLS 1.3 encryption with standard CA trust)
  +-- MCloudClientHandshakeHandler (Performs RFC 6455 HTTP 101 upgrade)
  +-- ConnectionChannelActiveMixin (Flushes buffered packets upon handshake)
  +-- MCloudWebSocketFrameEncoder / Decoder (Vectorized 64-bit XOR masking)
  |
  v  (Encrypted WSS / Port 443)
[ Cloudflare Anycast Edge ]
  |
  +-- Free DDoS mitigation & edge caching
  +-- Masks home IP address completely
  +-- Low edge latency (5-15 ms RTT to nearest PoP)
  |
  v  (QUIC / HTTP2 Tunnel Ingress)
[ Dedicated Server (Host) ]
  |
  +-- cloudflared daemon (Supervised locally)
  +-- MCloudServerMultiplexer (O(1) protocol sniffer)
       |-- HTTP 101 Upgrade -> Strips WebSocket frames into raw packets
       \-- Native Minecraft TCP -> Direct zero-overhead passthrough
```

---

## Comparison

| Feature | MCloud | Hamachi / Radmin | Playit.gg / Ngrok | Direct Port Forward |
| :--- | :---: | :---: | :---: | :---: |
| **Port Forwarding Needed** | No | No | No | Yes |
| **Client VPN / Software** | No (Mod only) | Yes (Required) | No | No |
| **Bandwidth Limits** | Unlimited | Throttled | Limited on Free | ISP dependent |
| **IP Protection (Anti-DDoS)** | Cloudflare CDN | Exposed | Proxied | Home IP exposed |
| **Cost** | 100% Free | Free w/ limits | Paid for full speed | Free |

---

## Getting Started

### 1. Server Setup
1. Install **Fabric Loader (1.20.1)** on your server.
2. Place `mcloud-1.0.13.jar` into the `mods/` directory.
3. Start the server. MCloud will generate `config/mcloud.json` and can automatically supervise `cloudflared`.
4. Alternatively, start a quick tunnel manually in your terminal:
   ```bash
   cloudflared tunnel --url http://127.0.0.1:25565
   ```
5. Copy the generated domain (e.g. `your-domain.trycloudflare.com`).

### 2. Client Setup
1. Install **Fabric Loader (1.20.1)**.
2. Put `mcloud-1.0.13.jar` into your client `mods/` directory.
3. In Minecraft, open **Multiplayer** -> **Add Server**.
4. Paste the tunnel address in **Server Address**:
   ```text
   your-domain.trycloudflare.com
   ```
5. Click **Done** and connect. Server status, player count, MOTD, and gameplay work identically to vanilla.

---

## Configuration

The configuration file is located at `config/mcloud.json`:

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

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enables or disables MCloud Netty handlers. |
| `listenPort` | `25565` | Target local port for the Minecraft server. |
| `autoStartCloudflared` | `true` | Automatically spawns the cloudflared process on launch. |
| `autoDownloadCloudflared` | `true` | Downloads the official cloudflared binary if missing. |
| `tunnelMode` | `"quick"` | `"quick"` for temporary trycloudflare.com, or `"token"` for Zero Trust named tunnels. |
| `tunnelToken` | `""` | Authentication token when using Zero Trust named tunnels. |
| `customCloudflareDomains` | `[]` | List of custom domain names routed through Cloudflare. |

---

## In-Game Commands

Operators (level 2+) can manage the tunnel in-game:

- `/mcloud status` - View tunnel status, health, and active URL.
- `/mcloud start` - Start the cloudflared daemon.
- `/mcloud stop` - Stop the active tunnel daemon.
- `/mcloud restart` - Restart the tunnel daemon and refresh the endpoint.

---

## Building from Source

Requirements: **Java 17+**

```bash
git clone https://github.com/UFzDev/Mcloud.git
cd Mcloud
./gradlew build
```

The compiled mod JAR will be generated at `build/libs/mcloud-1.0.13.jar`.

---

## License

This project is licensed under the [MIT License](LICENSE).  
Created by **[UFzDev](https://github.com/UFzDev)**.