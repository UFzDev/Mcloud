<p align="center">
  <img src="assets/header.png?v=2" alt="MCloud Header" width="100%" />
</p>

# MCloud

A high-performance, zero-config Fabric mod that tunnels Minecraft TCP traffic through Cloudflare Tunnels (port 443) using RFC 6455 WebSockets over TLS.

---

## The Problem MCloud Solves

Hosting Minecraft multiplayer servers from home or playing with friends often fails due to **Carrier-Grade NAT (CGNAT)** or strict home routers that prevent opening port `25565`.

Traditional workarounds come with significant trade-offs:
- **Hamachi / Radmin VPN**: Every player must install third-party VPN clients and virtual network adapters, traffic is capped, and local subnets are exposed.
- **Playit.gg / Ngrok**: Free tiers enforce strict bandwidth/tunnel limits or add noticeable routing latency.
- **Essential Mod**: Heavy bloatware with invasive monetization, cosmetics, and account requirements.
- **e4mc**: Relies on community-hosted proxy relays that frequently suffer from lag, queues, and downtime.
- **Standard Cloudflare Tunnels**: Cloudflare''s free ingress tier only proxies **HTTP/HTTPS and WebSockets over port 443**. Running raw TCP (`cloudflared access tcp`) requires every single player to install command-line tools and configure local proxy ports.

**MCloud eliminates all external client software.** It bridges Minecraft packets directly into secure WebSocket (`wss://`) frames at the Netty pipeline layer. Players simply enter the Cloudflare tunnel domain into the vanilla Minecraft multiplayer list.

---

## Key Features

- **Instant "Open to LAN" Public Worlds**: Turn any Singleplayer survival world into an online public multiplayer server in 3 seconds just by clicking **Open to LAN**. MCloud generates a public `trycloudflare.com` link directly in your in-game chat with 1-click clipboard copying.
- **Zero Client Software**: Friends do not need to install `cloudflared`, VPNs, or configure proxy daemons. They only need the mod.
- **Dedicated Server Support**: Works seamlessly on dedicated servers (Linux / Windows) with autonomous daemon supervision (`/mcloud start`).
- **Free Global Anycast CDN**: Powered directly by Cloudflare''s edge network, completely hiding your home IP and protecting you from DDoS attacks.
- **Automatic DNS TXT Resolution**: Custom domains (e.g. `mc.yourdomain.com`) automatically activate the WebSocket bridge if an `_mcloud` DNS TXT record is present, without forcing players to type `wss://`.

---

## How It Works

```
[ Minecraft Client / Joining Player ]
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
[ Host: Dedicated Server OR Singleplayer "Open to LAN" ]
  |
  +-- cloudflared daemon (Supervised locally)
  +-- MCloudServerMultiplexer (O(1) protocol sniffer)
       |-- HTTP 101 Upgrade -> Strips WebSocket frames into raw packets
       \-- Native Minecraft TCP -> Direct zero-overhead passthrough
```

---

## Comparison

| Feature | MCloud | e4mc / Essential | Hamachi / Radmin | Direct Port Forward |
| :--- | :---: | :---: | :---: | :---: |
| **Instant "Open to LAN"** | Yes (Cloudflare Edge) | Yes (Custom Relays) | Manual setup | No |
| **Client Software Needed** | No (Mod only) | No (Mod only) | Yes (Heavy VPN) | No |
| **Network Infrastructure** | Global Cloudflare CDN | Community servers | P2P / Relays | Home ISP |
| **Bloatware / Cosmetics** | 100% Clean & Free | In-game store (Essential) | Third-party app | None |
| **IP Protection (Anti-DDoS)** | Cloudflare CDN | Proxied | Subnet exposed | Home IP exposed |
| **Bandwidth Limits** | Unlimited | Throttled | Throttled | ISP dependent |

---

## Getting Started

### Option A: Play with Friends from Singleplayer ("Open to LAN")
1. Open your Singleplayer world.
2. Press `Esc` and click **Open to LAN**.
3. Choose your settings and click **Start LAN World**.
4. MCloud will automatically provision a secure Cloudflare tunnel and print a clickable link in your chat:
   ```text
   [MCloud] PUBLIC WORLD ONLINE!
   Share this address with your friends:
   >>> swift-rabbit-123.trycloudflare.com <<<
   (Click link to copy directly to clipboard)
   ```
5. Click the link to copy it, send it to your friends, and they can join immediately! When you exit the world, the tunnel closes automatically.

---

### Option B: Dedicated Server Setup
1. Install **Fabric Loader (1.20.1)** on your server.
2. Place `mcloud-1.1.0.jar` into the `mods/` directory.
3. Start the server. MCloud will generate `config/mcloud.json` and can automatically supervise `cloudflared`.
4. Alternatively, start a quick tunnel manually in your terminal:
   ```bash
   cloudflared tunnel --url http://127.0.0.1:25565
   ```
5. Copy the generated domain (e.g. `your-domain.trycloudflare.com`).

---

### Joining a Server (Clients)
1. Install **Fabric Loader (1.20.1)** and place `mcloud-1.1.0.jar` in your `mods/` directory.
2. In Minecraft, open **Multiplayer** -> **Add Server**.
3. Paste the tunnel address in **Server Address**:
   ```text
   your-domain.trycloudflare.com
   ```
4. Click **Done** and connect. Server status, player count, MOTD, and gameplay work identically to vanilla.

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

Operators (level 2+) can manage the tunnel on dedicated servers:

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

The compiled mod JAR will be generated at `build/libs/mcloud-1.1.0.jar`.

---

## License

This project is licensed under the [MIT License](LICENSE).  
Created by **[UFzDev](https://github.com/UFzDev)**.