<p align="center">
  <img src="assets/header.png?v=banner-flagship" alt="MCloud Header" width="100%" />
</p>

# MCloud

A high-performance, zero-config Fabric mod that tunnels Minecraft TCP traffic through Cloudflare Tunnels (port 443) using RFC 6455 WebSockets over TLS.

---

## Supported Minecraft Versions

MCloud is fully maintained and tested across three release tracks:

| Minecraft Version | Release Track | Git Branch | Recommended Binary | Java Requirement |
| :--- | :--- | :--- | :--- | :--- |
| **Minecraft 1.20.1** | Long-Term Support (LTS) | [`main`](https://github.com/UFzDev/Mcloud/tree/main) | `mcloud-1.1.0-mc1.20.x.jar` | Java 17+ |
| **Minecraft 1.21 – 1.21.11** | Modern Track | [`1.21`](https://github.com/UFzDev/Mcloud/tree/1.21) | `mcloud-1.1.0-mc1.21.x.jar` | Java 21+ |
| **Minecraft 26.x (26.1 – 26.3+)** | Next-Gen / Calendar Versioning | [`26.x`](https://github.com/UFzDev/Mcloud/tree/26.x) | `mcloud-1.1.0-mc26.x.jar` | Java 21+ |

*Both the Host/Server and joining Clients only need the corresponding MCloud jar file in their `mods/` folder.*

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

### Option A: Play with Friends from Singleplayer
1. Open your Singleplayer world.
2. Open the world to your friends:
   - **On 1.20.x & 1.21.x**: Press `Esc` and click **Open to LAN** -> **Start LAN World**.
   - **On 26.x**: Press `Esc` and open the world via **World Options...** / **Opciones del mundo...**, or simply run `/mcloud start` in chat.
3. MCloud will automatically provision a free, secure Cloudflare tunnel in the background and print a clickable link directly in your chat:
   ```text
   MCloud » Free Cloudflare tunnel online!
   Share: abc-def-123.trycloudflare.com
   [COPY LINK]
   ```
4. Click **[COPY LINK]** to copy the address to your clipboard, send it to your friends, and they can join immediately! When you exit the world or close the server, the tunnel shuts down automatically.

---

### Option B: Dedicated Server Setup
1. Install **Fabric Loader** on your server (matches your Minecraft version: 1.20.1, 1.21.x, or 26.x).
2. Place the matching `mcloud-1.1.0-mc<version>.jar` into the `mods/` directory.
3. Start the server. MCloud will generate `config/mcloud.json` and can automatically supervise `cloudflared`.
4. Alternatively, start a quick tunnel manually in your terminal:
   ```bash
   cloudflared tunnel --url http://127.0.0.1:25565
   ```
5. Copy the generated domain (e.g. `your-domain.trycloudflare.com`).

---

### Option C: Using a Custom Domain (Cloudflare Zero Trust)

If you own a domain (e.g. `yourdomain.com`) managed on Cloudflare, you can host your server with a clean persistent address (e.g. `mc.yourdomain.com`):

#### 1. In Cloudflare Zero Trust Dashboard:
1. Navigate to **Networks** -> **Tunnels** -> **Create a Tunnel**.
2. Select **Cloudflared** and follow the steps to obtain your Tunnel Token.
3. Under **Public Hostnames**, configure:
   - **Subdomain**: `mc` (or your preferred subdomain)
   - **Domain**: `yourdomain.com`
   - **Type**: `HTTP`
   - **URL**: `127.0.0.1:25565`

#### 2. In Cloudflare DNS Dashboard:
Add this TXT record so the MCloud client automatically recognizes your domain without requiring players to type `wss://`:

| Type | Name | Content | TTL |
| :--- | :--- | :--- | :--- |
| **TXT** | `_mcloud.mc` | `"mcloud=true"` | Auto |
| **CNAME** | `mc` | `<your-tunnel-id>.cfargotunnel.com` | Auto (Proxied) |

#### 3. In `config/mcloud.json` (Server Host):
```json
{
  "tunnelMode": "token",
  "tunnelToken": "your_cloudflare_zero_trust_tunnel_token_here"
}
```

---

### Joining a Server (Clients)
1. Install **Fabric Loader** on your client matching the server's Minecraft version.
2. Place the matching `mcloud-1.1.0-mc<version>.jar` in your `mods/` directory.
3. In Minecraft, open **Multiplayer** -> **Add Server** (or Direct Connection).
4. Paste the tunnel address in **Server Address**:
   ```text
   your-domain.trycloudflare.com
   ```
   *(or your custom domain like `mc.yourdomain.com`)*
5. Click **Done** and connect. Server status, ping, player count, MOTD, and gameplay work identically to vanilla.

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

Select the branch for your target Minecraft version:

```bash
git clone https://github.com/UFzDev/Mcloud.git
cd Mcloud

# For Minecraft 1.20.1 LTS:
git checkout main
./gradlew build

# For Minecraft 1.21.x:
git checkout 1.21
./gradlew build

# For Minecraft 26.x:
git checkout 26.x
./gradlew build
```

Compiled mod JARs will be found in `build/libs/`.

---

## Disclaimer & Terms of Service Notice

This project is an independent, open-source experimental software developed for educational and networking research purposes.

* **No Affiliation:** This project is not affiliated, associated, authorized, endorsed by, or in any way officially connected with **Cloudflare, Inc.**, **Mojang Studios**, **Microsoft Corporation**, or any of their subsidiaries or affiliates.
* **Third-Party Services:** This mod interacts with Cloudflare infrastructure via WebSocket tunneling. Users are solely responsible for ensuring that their network traffic, bandwidth consumption, and usage patterns comply with [Cloudflare''s Terms of Service](https://www.cloudflare.com/terms/) and [Self-Serve Subscription Agreement](https://www.cloudflare.com/clean-slate-terms/).
* **Limitation of Liability:** The authors and contributors of this software accept no responsibility for any temporary or permanent restrictions, tunnel terminations, account suspensions, or service disruptions imposed by third-party network providers resulting from the use of this software.
* **"As-Is" Warranty:** This software is distributed under the MIT License on an "AS IS" basis, without warranties or conditions of any kind, either express or implied.

---

## License

This project is licensed under the [MIT License](LICENSE).  
Created by **[UFzDev](https://github.com/UFzDev)**.