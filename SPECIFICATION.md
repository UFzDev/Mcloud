# Especificacion Tecnica (SDD) - MCloud (Minecraft Cloudflare WebSocket Engine)

- Nombre del Proyecto: MCloud (mcloud)
- Repositorio Oficial: https://github.com/UFzDev/Mcloud
- Plataforma Objetivo: Minecraft 1.20.1 - Fabric Loader (>=0.15.0) - Java 17+ / 21+
- Tipo de Distribucion: Mod Universal Single-JAR (environment: "*", funciona en Cliente, Servidor Dedicado y LAN Singleplayer)
- Dependencias Externas: Cero (implementacion determinista RFC 6455 nativa sobre io.netty.buffer.ByteBuf y io.netty.handler.ssl.SslHandler incluidos en Minecraft).

---

## 1. Problema de Ingenieria y Motivacion

1. Bloqueo por CGNAT: Proveedores residenciales (como Totalplay, Megacable, Izzi o Starlink) emplean Carrier-Grade NAT (CGNAT, RFC 6598), impidiendo abrir puertos TCP/UDP entrantes (25565).
2. Alta Latencia en Tuneles de Terceros: Servicios gratuitos como Playit.gg enrutan el trafico hacia datacenters lejanos (ej. Dallas/Chicago desde Latinoamerica), multiplicando la latencia (de ~6 ms a ~45-60 ms).
3. Restriccion TCP de Cloudflare: Cloudflare posee nodos Edge locales dentro de cada pais (en Mexico: CDMX, Queretaro, Guadalajara, Monterrey con ~5.8 ms de RTT), pero su capa gratuita bloquea conexiones TCP crudas en el puerto 25565. Sin embargo, Cloudflare permite trafico WebSocket (WSS sobre TLS 443 y WS sobre 80) de forma 100% gratuita, incluyendo tuneles efimeros sin cuenta ni dominio (*.trycloudflare.com) y tuneles con dominio propio.

---

## 2. Arquitectura del Sistema

```
[ Cliente Minecraft + MCloud Mod ]
       | 1. Intercepta ServerAddress (*.trycloudflare.com, wss://, ws://, mcloud://)
       | 2. Abre tunel TLS (SNI) + RFC 6455 WebSocket Upgrade (Puerto 443)
       | 3. Empaqueta paquetes Netty de Minecraft en BinaryWebSocketFrames (0x82 + MASK)
       v
[ Nodo Edge Local de Cloudflare (ej. CDMX / QRO - ~5.8 ms) ]
       | 4. Proxy nativo gratuito de WebSockets (sin requerir Cloudflare Spectrum)
       v
[ Agente cloudflared en el Servidor (Auto-gestionado por MCloud) ]
       | 5. Reenvia el flujo HTTP/WebSocket a http://127.0.0.1:25565
       v
[ Servidor Minecraft + MCloud Mod (Puerto 25565 Dual-Protocol) ]
       |-- Si el primer byte es 'G' ('GET /...'):
       |     Realiza Handshake RFC 6455 (101 Switching Protocols),
       |     desempaqueta BinaryWebSocketFrames y entrega ByteBuf puros a Minecraft.
       \-- Si el primer byte es Handshake Vanilla (VarInt / 0xFE):
             Remueve el multiplexor en O(1) y procesa TCP nativo (LAN 0 ms overhead).
```

---

## 3. Especificacion de Modulos Atomicos

### Modulo 1: Configuracion y Registro de Direcciones (MCloudConfig & MCloudAddressRegistry)
- Archivo de configuracion JSON en config/mcloud.json:
  - enabled (boolean, default true): Activa el multiplexor WebSocket en el servidor.
  - autoStartCloudflared (boolean, default true en servidor dedicado): Inicia automaticamente cloudflared al arrancar el servidor.
  - autoDownloadCloudflared (boolean, default true): Descarga automaticamente el binario oficial de cloudflared si no existe en el sistema o en config/mcloud/bin/.
  - tunnelMode ("quick" | "token", default "quick"):
    - "quick": Ejecuta cloudflared tunnel --url http://127.0.0.1:<serverPort> generando un dominio *.trycloudflare.com gratuito sin cuenta ni dominio.
    - "token": Ejecuta cloudflared tunnel run --token <tunnelToken> para dominios personalizados permanentes.
  - tunnelToken (String, default ""): Token de Cloudflare Zero Trust opcional.
  - customCloudflareDomains (List<String>): Lista opcional de dominios propios que usan WebSocket sin prefijo.
- Normalizacion inteligente de direcciones en el cliente (MCloudAddressRegistry):
  - Soporta direcciones pegadas directamente como:
    - https://ejemplo.trycloudflare.com
    - wss://mc.midominio.com
    - ws://127.0.0.1:25565
    - mcloud://mc.midominio.com
    - ejemplo.trycloudflare.com (deteccion automatica por sufijo .trycloudflare.com o .cfargotunnel.com).

### Modulo 2: Codec RFC 6455 Determinista (MCloudWebSocketFrameCodec)
- Decodificador (ByteToMessageDecoder):
  - Lee cabeceras de trama RFC 6455 (2 a 14 bytes).
  - Maneja longitudes de 7 bits (<=125), 16 bits extendidos (126) y 64 bits extendidos (127).
  - Desenmascara cargas utiles (MASK=1) mediante clave XOR de 4 bytes con desenrollado de 64 bits.
  - Emite ByteBuf puros al pipeline de Minecraft para opcodes 0x02 (Binary) y 0x00 (Continuation).
  - Responde automaticamente a tramas 0x09 (Ping) enviando 0x0A (Pong).
  - Cierra ordenadamente el canal ante trama 0x08 (Close).
- Codificador (MessageToByteEncoder<ByteBuf>):
  - Envuelve cada ByteBuf saliente de Minecraft en una trama 0x82 (FIN=1, Opcode=Binary).
  - En modo cliente (maskPayload = true), aplica mascara XOR de 4 bytes vectorizada a 64 bits conforme a la seccion 5.3 del RFC 6455.
  - En modo servidor (maskPayload = false), envia la trama sin mascara para maxima velocidad de CPU.

### Modulo 3: Multiplexor Dual TCP + HTTP/WebSocket en Servidor (MCloudServerMultiplexer)
- Se inyecta al inicio del ChannelPipeline de cada conexion entrante en ServerNetworkIo.
- Inspecciona los primeros 4 bytes sin consumirlos si es trafico Minecraft:
  - Si coincide con "GET " (0x47 0x45 0x54 0x20) o "HEAD":
    - Lee la peticion HTTP completa hasta \r\n\r\n.
    - Si contiene Upgrade: websocket y Sec-WebSocket-Key:
      - Calcula Sec-WebSocket-Accept = Base64(SHA-1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")).
      - Extrae CF-Connecting-IP (si esta presente) y lo guarda en un AttributeKey del canal.
      - Envia HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ...\r\n\r\n.
      - Reemplaza el multiplexor por codecs de frame WebSocket y dispara los bytes restantes al pipeline de Minecraft.
    - Si es una peticion HTTP normal (navegador web o healthcheck):
      - Devuelve HTTP/1.1 200 OK con un payload JSON de estado ({"status":"online","mod":"MCloud","protocol":"RFC6455-WebSocket"}) y cierra la conexion HTTP limpiamente.
  - Si el primer byte NO es 'G' ni 'H':
    - Es un cliente Minecraft TCP tradicional (LAN). Se remueve del pipeline (ctx.pipeline().remove(this)) y delega el buffer intacto al siguiente handler.

### Modulo 4: Conector WebSocket + TLS en Cliente (MCloudClientHandshakeHandler)
- Mediante Mixins en ServerAddress y ClientConnection:
  - Cuando el jugador conecta o hace ping en la lista de servidores a un destino MCloud:
  - Ajusta el puerto de conexion TCP a 443 (para wss:// / .trycloudflare.com) o al puerto explicito indicado.
  - Inserta SslHandler (con SNI activado y protocolo HTTP/1.1) si la conexion es wss.
  - Inserta MCloudClientHandshakeHandler + MCloudWebSocketFrameEncoder / Decoder al inicio del pipeline.
  - Retiene el evento channelActive hacia ClientConnection hasta recibir HTTP/1.1 101 Switching Protocols, garantizando que el HandshakeC2SPacket de Minecraft se envie unicamente cuando el tunel WebSocket este establecido.

### Modulo 5: Gestor Autonomo de cloudflared y Comandos (/mcloud)
- Gestiona el ciclo de vida del subproceso cloudflared en un hilo demonio seguro con cierre automatico en ServerStoppingEvents.
- Registra el comando /mcloud:
  - /mcloud status: Muestra el estado del tunel, modo (quick / token), y la URL activa clicable para copiar al portapapeles.
  - /mcloud start / /mcloud stop / /mcloud restart: Controla el tunel en vivo sin reiniciar el servidor de Minecraft.

---

## 4. Plan de Verificacion y Pruebas (Cero Vibe Coding)

1. Pruebas Unitarias JUnit 5:
   - Prueba de roundtrip del codec WebSocket (mensajes pequenos, medianos >126 bytes y grandes >65535 bytes, con y sin mascara XOR).
   - Prueba de calculo RFC 6455 (vector de prueba dGhlIHNhbXBsZSBub25jZQ== -> s3pPLMBiTxaQ9kYGzzhZRbK+xOo=).
   - Prueba de multiplexado del servidor (HTTP Upgrade vs TCP directo sin alteraciones).
   - Prueba de normalizacion de direcciones de Cloudflare.
2. Compilacion Limpia:
   - Gradle 9.8.2 + Loom generando el artefacto build/libs/mcloud-1.0.0.jar sin errores.
3. Despliegue en Entorno de Produccion:
   - Binario verificado en Servidor Acer (mc:192.168.100.211) y en paquete cliente.
4. Control de Versiones Git:
   - Historial semantico en repositorio https://github.com/UFzDev/Mcloud.
