# MCloud ??

Zero-Port-Forwarding, Zero-Client-Daemons, Free Cloudflare Tunnel & WebSocket Bridge for Minecraft (Fabric 1.20.1).

---

## ? What is MCloud?

Hosting dedicated Minecraft servers from home frequently fails due to **Carrier-Grade NAT (CGNAT)**, mobile broadband, or strict firewall routers that prevent port forwarding (25565).

Traditional alternatives suffer from critical pain points:
- **Hamachi / Radmin VPN**: Requires all friends to install virtual network adapters, exposes local subnet traffic, and caps network speeds.
- **Playit.gg / Ngrok**: Bandwidth limits or high subscription fees.
- **Vanilla Cloudflare Tunnels**: Cloudflare's free tier only proxies **HTTP/HTTPS and WebSockets (port 443)**. Raw TCP tunneling (cloudflared access tcp) requires every player to install CLI daemons and configure custom client proxies.

**MCloud solves this natively at the Netty pipeline layer.** It encapsulates Minecraft’s binary packet stream into secure RFC 6455 WebSocket (wss://) frames over port 443. Connecting players simply paste the Cloudflare tunnel URL into their normal Minecraft multiplayer screen—**no VPNs, no router modifications, and zero external software for clients.**

---

## ??? Architecture

`
+---------------------------------------------------------------+
|                      Minecraft Client                         |
|                                                               |
|  - ConnectionClientConnectMixin: Routes to Port 443           |
|  - ServerAddressMixin: Normalizes URI (*.trycloudflare.com)  |
|  - SslHandler: Standard CA TLS 1.3 Encryption                 |
|  - MCloudClientHandshakeHandler: RFC 6455 101 Handshake       |
|  - ConnectionChannelActiveMixin: Flushes queued game packets  |
|  - MCloudWebSocketFrameEncoder / Decoder (Vectorized XOR)     |
+-------------------------------+-------------------------------+
                                |
                                | TLS 1.3 / WSS (Port 443)
                                v
+---------------------------------------------------------------+
|                 Cloudflare Global Edge Anycast                |
|                                                               |
|  - Free Anycast CDN & Anti-DDoS Shield                        |
|  - Hides your real home IP address completely                 |
|  - Zero-RTT Session Resumption (5-15 ms edge latency)         |
+-------------------------------+-------------------------------+
                                |
                                | QUIC / HTTP2 Ingress Tunnel
                                v
+---------------------------------------------------------------+
|              Dedicated Server (Linux / Mac / Windows)         |
|                                                               |
|  - cloudflared tunnel daemon (Local Ingress)                  |
|  - ServerConnectionListenerInitMixin                          |
|  - MCloudServerMultiplexer: O(1) Zero-Copy Protocol Sniffer   |
|     * WebSocket Upgrade -> Strips frames & injects to Netty   |
|     * Native Minecraft TCP -> Direct zero-overhead bypass     |
|     * HTTP /mcloud Probe -> Health check response             |
|  - CloudflaredManager: Autonomous tunnel supervisor           |
+-------------------------------+-------------------------------+
`

---

## ?? Feature Comparison Matrix

| Feature | MCloud ?? | Hamachi / Radmin | Playit.gg / Ngrok | Classic Port Forwarding |
| :--- | :---: | :---: | :---: | :---: |
| **Port Forwarding Required** | ? **No** | ? **No** | ? **No** | ? Yes (Needs Public IP) |
| **Friends Must Install VPN/Client** | ? **No (Mod only)** | ?? Yes (Heavy VPN) | ? No | ? No |
| **Bandwidth / Cap Limits** | ? **Unlimited (CDN)** | ?? P2P Throttled | ?? Throttled on Free | ?? ISP Dependent |
| **IP Protection / Anti-DDoS** | ??? **Full Cloudflare Shield** | ?? Subnet Exposed | ??? Proxied | ? Home IP Exposed |
| **Cost** | ?? **100% Free** | ?? Free with limits | ?? Paid for full speed | ?? Free |

---

## ?? Quick Start Guide

### For the Server Host
1. Install **Fabric Loader** (1.20.1) and place mcloud-1.0.13.jar into your mods/ directory.
2. Start the server. By default, MCloud will generate config/mcloud.json and automatically supervise cloudflared.
3. Alternatively, run an ad-hoc tunnel manually:
   `ash
   cloudflared tunnel --url http://127.0.0.1:25565
   `
4. Copy the generated public URL (e.g., your-tunnel-name.trycloudflare.com).

### For Joining Players (Clients)
1. Install Fabric Loader (1.20.1) and put mcloud-1.0.13.jar in your client mods/ folder.
2. In Minecraft, navigate to **Multiplayer** -> **Add Server**.
3. In **Server Address**, paste your Cloudflare tunnel domain:
   `	ext
   your-tunnel-name.trycloudflare.com
   `
4. Hit **Done** and join! Ping, MOTD, player counts, skins, and gameplay synchronize automatically.

---

## ?? Configuration (config/mcloud.json)

`json
{
  "enabled": true,
  "listenPort": 25565,
  "autoStartCloudflared": true,
  "autoDownloadCloudflared": true,
  "tunnelMode": "quick",
  "tunnelToken": "",
  "customCloudflareDomains": []
}
`

| Key | Type | Default | Description |
|---|---|---|---|
| enabled | boolean | 	rue | Enables or disables MCloud Netty pipeline hooks. |
| listenPort | integer | 25565 | Target local port where Minecraft listens. |
| utoStartCloudflared | boolean | 	rue | Automatically spawns the tunnel process on boot. |
| utoDownloadCloudflared | boolean | 	rue | Downloads official Cloudflare binaries if not found on PATH. |
| 	unnelMode | string | "quick" | "quick" for temporary trycloudflare.com; "token" for Zero Trust custom domains. |
| 	unnelToken | string | "" | Zero Trust Tunnel token for persistent custom domains. |
| customCloudflareDomains | list | [] | List of custom domain names routed through Cloudflare. |

---

## ??? In-Game Commands

Operators with permission level 2 have access to /mcloud:

- /mcloud status: Displays tunnel process status, edge latency, and click-to-copy active domain.
- /mcloud start: Launches the background cloudflared tunnel daemon.
- /mcloud stop: Terminates the active tunnel daemon.
- /mcloud restart: Restarts the tunnel and refreshes the public endpoint.

---

## ?? Building from Source

Requires **JDK 17** or higher:

`ash
git clone https://github.com/UFzDev/Mcloud.git
cd Mcloud
./gradlew build
`

Compiled jar will be located at uild/libs/mcloud-1.0.13.jar.

---

## ?? License

MCloud is licensed under the [MIT License](LICENSE).
Created with ?? by **[UFzDev](https://github.com/UFzDev)**.
