# ☁️ MCloud — Cloudflare WebSocket Tunnel Engine for Minecraft (Fabric)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen.svg)](https://fabricmc.net/)
[![Fabric](https://img.shields.io/badge/Modloader-Fabric-lightgrey.svg)](https://fabricmc.net/)
[![Cloudflare](https://img.shields.io/badge/Network-Cloudflare%20Edge-F38020.svg)](https://cloudflare.com)

**MCloud** es una solución de red de alto rendimiento diseñada para superar de forma definitiva los bloqueos de **CGNAT**, IPs dinámicas y restricciones de firewalls en servidores caseros de Minecraft. Convierte el tráfico TCP binario de Minecraft en túneles **WebSocket seguros (WSS)** sobre el puerto estándar **443**, aprovechando la infraestructura global gratuita de Cloudflare Tunnels (`trycloudflare.com` y dominios personalizados) sin requerir que los jugadores instalen `cloudflared` ni clientes externos en sus PCs.

---

## 🌟 Características Principales

1. **Bypass Completo de CGNAT**: Conecta jugadores externos a tu servidor local alojado en Totalplay, Megacable, Starlink o redes universitarias sin abrir puertos en el módem (cero port-forwarding).
2. **Cero Clientes Externos para Jugadores**: Los jugadores solo necesitan tener el mod `mcloud.jar` en su carpeta `.minecraft/mods` y poner el dominio de Cloudflare (`wss://...` o `subdominio.trycloudflare.com`) en la pantalla de Multiplayer de Minecraft.
3. **Dual Multiplexing O(1) en Servidor**: El servidor escucha en el puerto habitual `25565` e inspecciona los primeros bytes de cada conexión:
   - Si detecta `GET / HTTP/1.1` con cabecera `Upgrade: websocket`, realiza el handshake RFC 6455 y conmuta a decodificación WebSocket.
   - Si detecta un paquete Minecraft TCP nativo (`0xFE` o paquete Handshake VarInt), deja pasar la conexión como TCP directo sin sobrecarga alguna.
4. **Baja Latencia Edge Local**: Enlace con los PoPs locales de Cloudflare en México (CDMX/Querétaro a ~5-8 ms), a diferencia de túneles alternativos (Playit.gg o ngrok) que enrutan tráfico por Dallas o Virginia (+35-50 ms).
5. **Gestor Autónomo de Túnel (`cloudflared`)**: En el servidor, MCloud puede arrancar, supervisar y reconectar automáticamente el binario de `cloudflared` mediante comandos en el chat (`/mcloud start`, `/mcloud status`).

---

## 📐 Arquitectura de Red

```
[ Jugador con MCloud ]
         │ (Minecraft Protocol envuelto en WebSocket binario RFC 6455)
         ▼
[ HTTPS / WSS:443 Cloudflare Edge PoP (CDMX / QRO ~5ms) ]
         │ (Túnel seguro Quick-Tunnel QUIC/HTTP2)
         ▼
[ cloudflared daemon (en Servidor Acer / Linux) ]
         │ (HTTP Upgrade / Loopback)
         ▼
[ Servidor Minecraft Fabric + MCloud Multiplexer (Puerto 25565) ]
         │
         ├─── Si WebSocket: Decodifica frames RFC 6455 -> Pipeline Minecraft
         └─── Si TCP Local (LAN): Conexión directa O(1) sin overhead
```

---

## 📦 Instalación

### Requisitos
- **Minecraft**: `1.20.1`
- **Mod Loader**: Fabric Loader `>= 0.16.0`
- **Fabric API**: Instalado en el cliente y servidor.
- **Java**: Java 17 o superior.

### En el Cliente (Jugador)
1. Coloca `mcloud-<version>.jar` dentro de tu carpeta `.minecraft/mods`.
2. Inicia Minecraft.
3. En la lista de servidores, introduce la URL del túnel (ejemplo: `subdominio-ejemplo.trycloudflare.com` o `wss://subdominio-ejemplo.trycloudflare.com:443`).
4. ¡Conéctate directamente! MCloud interceptará la resolución DNS y establecerá la sesión TLS + WebSocket de forma transparente.

### En el Servidor
1. Coloca `mcloud-<version>.jar` dentro de la carpeta `mods/` de tu servidor Fabric.
2. (Opcional) Instala `cloudflared` en el sistema operativo del servidor si deseas que MCloud gestione el túnel automáticamente.
3. Al encender el servidor, MCloud generará el archivo `config/mcloud.json`:

```json
{
  "enabled": true,
  "listenPort": 25565,
  "autoStartTunnel": true,
  "cloudflaredPath": "cloudflared",
  "tunnelDomain": "",
  "clientMasking": true,
  "pingIntervalSeconds": 30
}
```

---

## 🎮 Comandos de Servidor

- `/mcloud status`: Muestra el estado del multiplexor WebSocket, clientes conectados y la URL activa del túnel Cloudflare.
- `/mcloud start`: Arranca el proceso autónomo de `cloudflared` y obtiene una URL pública `trycloudflare.com`.
- `/mcloud stop`: Detiene el túnel activo.

---

## 🛠️ Compilación desde Código Fuente

El proyecto utiliza Gradle 9 y Fabric Loom:

```bash
git clone https://github.com/UFzDev/Mcloud.git
cd Mcloud
./gradlew build
```

El archivo compilado listo para producción se encontrará en `build/libs/mcloud-<version>.jar`.

---

## ⚖️ Licencia

Distribuido bajo la Licencia MIT. Consulta el archivo [LICENSE](LICENSE) para más detalles.
