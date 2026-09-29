# Despliegue en Ubuntu 24.04 LTS — Hostinger VPS

## 0. Convivencia con el proyecto existente

El VPS ya tiene un proyecto en producción en `http://46.202.88.76:8080/`. Esta integración se
instala **al lado**, sin tocarlo, y todo lo que podría chocar lleva un nombre propio:

| Recurso          | Proyecto existente        | Esta integración (Siigo)          |
|------------------|---------------------------|-----------------------------------|
| Puerto           | `8080`                    | **`8081`**                        |
| Directorio       | el suyo (no se modifica)  | `/opt/minihotel-siigo`            |
| Servicio systemd | el suyo (no se modifica)  | `minihotel-siigo.service`         |
| Base H2          | la suya                   | `/opt/minihotel-siigo/data/`      |
| Logs             | los suyos                 | `/opt/minihotel-siigo/logs/`      |

> **Importante:** el puerto y la ruta de H2 están fijados en `application-prod.yml`
> (`server.port: 8081` y `/opt/minihotel-siigo/data/`). Sin `server.port`, Spring Boot
> arrancaría en **8080** y fallaría con *Port 8080 was already in use*.

Antes de empezar, confirma qué está ocupado en el servidor:

```bash
ss -tlnp | grep -E ':(8080|8081)\b'   # 8080 debe aparecer, 8081 debe estar libre
systemctl list-units --type=service | grep -i -E 'minihotel|java'
ls /opt
```

Si `8081` ya estuviera en uso, elige otro puerto libre (p. ej. `8083`), ponlo en
`server.port` de `application-prod.yml` y reemplázalo en todos los pasos donde aparece `8081`.

Los pasos 1–3 (actualizar el sistema, usuario `appuser`, Java 17) probablemente ya están hechos
por el proyecto existente: verifica con `java -version` e `id appuser` y sáltalos si es así.

---

## 1. Preparar el servidor

Conéctate por SSH y actualiza el sistema:

```bash
ssh root@46.202.88.76
apt update && apt upgrade -y
```

Crea un usuario no-root para la aplicación:

```bash
adduser appuser
usermod -aG sudo appuser
```

---

## 2. Instalar Java 17

```bash
apt install -y openjdk-17-jdk
java -version
# Debe mostrar: openjdk version "17.x.x"
```

---

## 3. Instalar Maven (opcional)

Solo necesario si vas a compilar directamente en el servidor. Lo recomendado es compilar localmente y subir el JAR.

```bash
apt install -y maven
mvn -version
```

---

## 4. Configuración: `application-prod.yml`

No se usa archivo `.env` ni variables de entorno: toda la configuración de producción viaja
**dentro del JAR**, en `src/main/resources/application-prod.yml`. Antes de compilar (paso 5),
revisa en ese archivo:

| Clave | Qué poner |
|-------|-----------|
| `server.port` | `8081` (el 8080 es del otro proyecto) |
| `spring.datasource.url` | `jdbc:h2:file:/opt/minihotel-siigo/data/sync_transactions;...` |
| `integrations.security.username/password` | Usuario y clave del panel y la API. Reemplaza `CAMBIAR_API_PASSWORD` |
| `integrations.siigo.auth.partner-id` | Partner-Id registrado en Siigo Nube |
| `integrations.hotels[]` | Por hotel: usuario MiniHotel, `username`/`access-key` de Siigo y parámetros de facturación |

Parámetros contables (comprobante, vendedor, centro de costo): descúbrelos con
`GET /api/catalogo/{hotelKey}/resumen` y ponlos en el bloque `facturacion` de cada hotel.

> Cualquier cambio de configuración implica **recompilar y volver a subir el JAR** (paso 12).
>
> Como las credenciales quedan dentro del JAR, restringe quién puede leerlo en el servidor:
> `chmod 600 /opt/minihotel-siigo/app.jar`.

---

## 5. Compilar la aplicación localmente

En tu máquina local, después de ajustar `application-prod.yml` (paso 4):

```bash
mvn clean install -DskipTests
```

El JAR se genera en:

```
target/minihotel-siigo-json-integration-0.0.1-SNAPSHOT.jar
```

---

## 6. Crear la estructura de directorios en el VPS

```bash
mkdir -p /opt/minihotel-siigo/data
mkdir -p /opt/minihotel-siigo/logs
chown -R appuser:appuser /opt/minihotel-siigo
```

La carpeta `data/` es donde H2 guardará el archivo `sync_transactions.mv.db`.

---

## 7. Subir el JAR al servidor

Desde tu máquina local:

```bash
scp target/minihotel-siigo-json-integration-0.0.1-SNAPSHOT.jar \
    appuser@46.202.88.76:/opt/minihotel-siigo/app.jar
```

---

## 8. Crear el servicio systemd

```bash
nano /etc/systemd/system/minihotel-siigo.service
```

```ini
[Unit]
Description=MiniHotel Siigo Integration (puerto 8081)
After=network.target

[Service]
User=appuser
WorkingDirectory=/opt/minihotel-siigo
ExecStart=/usr/bin/java \
    -Dspring.profiles.active=prod \
    -Xms256m -Xmx512m \
    -jar /opt/minihotel-siigo/app.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
StandardOutput=append:/opt/minihotel-siigo/logs/app.log
StandardError=append:/opt/minihotel-siigo/logs/app.log

[Install]
WantedBy=multi-user.target
```

Activa e inicia el servicio:

```bash
systemctl daemon-reload
systemctl enable minihotel-siigo
systemctl start minihotel-siigo
systemctl status minihotel-siigo
```

> Las dos apps son JVM independientes: entre ambas necesitas al menos ~1,2 GB de RAM libre.
> Revisa con `free -h`; si el VPS va justo, baja `-Xmx` a `384m`.

---

## 9. Abrir puertos en el firewall

Revisa primero el estado actual, para no dejar fuera al proyecto existente:

```bash
ufw status
```

```bash
ufw allow 22/tcp    # SSH (asegúralo SIEMPRE antes de habilitar ufw)
ufw allow 8080/tcp  # Proyecto existente — no lo quites
ufw allow 8081/tcp  # Esta integración
ufw enable          # solo si no estaba activo
ufw status
```

Si Hostinger tiene firewall en el panel del VPS, abre también allí el `8081/tcp`.

---

## 10. Verificar que funciona

```bash
# Logs en tiempo real
journalctl -u minihotel-siigo -f

# O directamente desde el archivo
tail -f /opt/minihotel-siigo/logs/app.log

# Health check de esta integración
curl http://localhost:8081/actuator/health

# El proyecto existente debe seguir respondiendo igual que antes
curl -I http://localhost:8080/
```

Desde tu máquina: `http://46.202.88.76:8081/` (esta app) y `http://46.202.88.76:8080/` (la
existente).

---

## 11. (Opcional) Nginx como reverse proxy

Si quieres exponer la app en el puerto 80/443. Con dos proyectos en el mismo VPS lo limpio es
**un subdominio por proyecto** (p. ej. `siigo.tu-dominio.com` → 8081 y `app.tu-dominio.com` →
8080), cada uno en su propio archivo de `sites-available`. Si Nginx ya existe por el otro
proyecto, no edites su archivo: agrega uno nuevo.

```bash
apt install -y nginx          # si no está instalado
nano /etc/nginx/sites-available/minihotel-siigo
```

```nginx
server {
    listen 80;
    server_name siigo.tu-dominio.com;

    location / {
        proxy_pass http://localhost:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
    }
}
```

```bash
ln -s /etc/nginx/sites-available/minihotel-siigo /etc/nginx/sites-enabled/
nginx -t
systemctl reload nginx
```

Para HTTPS con Certbot (requiere dominio apuntando al VPS):

```bash
apt install -y certbot python3-certbot-nginx
certbot --nginx -d siigo.tu-dominio.com
```

---

## 12. Script de actualización

El script se crea **una sola vez** en el servidor; después solo se ejecuta en cada despliegue.

### 12.1 Crear el archivo

Conéctate al VPS y crea el archivo con un *heredoc* (no hace falta abrir un editor). Copia y
pega el bloque completo, desde `sudo tee` hasta el `EOF` final:

```bash
ssh appuser@46.202.88.76
```

```bash
sudo tee /opt/minihotel-siigo/deploy.sh > /dev/null <<'EOF'
#!/bin/bash
set -e
echo "Deteniendo servicio..."
systemctl stop minihotel-siigo
echo "Actualizando JAR..."
cp /tmp/minihotel-siigo.jar /opt/minihotel-siigo/app.jar
chown appuser:appuser /opt/minihotel-siigo/app.jar
echo "Iniciando servicio..."
systemctl start minihotel-siigo
echo "Deploy completado."
EOF
```

> Las comillas en `<<'EOF'` evitan que la shell interprete el contenido al escribirlo.
> Si prefieres editor: `sudo nano /opt/minihotel-siigo/deploy.sh`, pega el script (sin las
> líneas `sudo tee` ni `EOF`) y guarda con `Ctrl+O`, `Enter`, `Ctrl+X`.

### 12.2 Darle permisos de ejecución y verificarlo

```bash
sudo chmod +x /opt/minihotel-siigo/deploy.sh
ls -l /opt/minihotel-siigo/deploy.sh   # debe mostrar -rwxr-xr-x
cat /opt/minihotel-siigo/deploy.sh     # confirma que el contenido quedó completo
```

Si al ejecutarlo aparece `/bin/bash^M: bad interpreter`, el archivo tiene saltos de línea de
Windows (CRLF). Corrígelo con `sudo sed -i 's/\r$//' /opt/minihotel-siigo/deploy.sh`.

### 12.3 Desplegar

Para futuros despliegues, desde tu máquina local:

```bash
# 1. Compilar
mvn clean install -DskipTests

# 2. Subir JAR
scp target/minihotel-siigo-json-integration-0.0.1-SNAPSHOT.jar \
    appuser@46.202.88.76:/tmp/minihotel-siigo.jar

# 3. Ejecutar deploy en el servidor (solo reinicia esta app; la del 8080 no se toca)
ssh -t appuser@46.202.88.76 "sudo /opt/minihotel-siigo/deploy.sh"

# 4. Verificar
ssh appuser@46.202.88.76 "systemctl status minihotel-siigo --no-pager; tail -n 50 /opt/minihotel-siigo/logs/app.log"
```

> El `-t` es necesario: `sudo` pide la contraseña de `appuser` y, sin terminal, falla con
> *sudo: a terminal is required to read the password*.

Si `deploy.sh` falla a mitad (por ejemplo, porque el JAR no llegó a `/tmp`), el servicio queda
detenido: corrige la causa y vuelve a ejecutarlo, o arráncalo con
`sudo systemctl start minihotel-siigo`.

---

## Referencia rápida

| Recurso        | Valor                                                    |
|----------------|----------------------------------------------------------|
| App (Siigo)    | `http://46.202.88.76:8081`                               |
| Proyecto existente | `http://46.202.88.76:8080` (no se modifica)          |
| Servicio       | `minihotel-siigo.service`                                |
| H2 Console     | Deshabilitada en `prod`                                  |
| JDBC URL (H2)  | `jdbc:h2:file:/opt/minihotel-siigo/data/sync_transactions` |
| Logs           | `/opt/minihotel-siigo/logs/app.log`                      |
| Peticiones HTTP | `/opt/minihotel-siigo/logs/access.AAAA-MM-DD.log`       |
| JAR            | `/opt/minihotel-siigo/app.jar`                           |
| Config         | Embebida en el JAR (`application-prod.yml`)              |
| Datos H2       | `/opt/minihotel-siigo/data/sync_transactions.mv.db`      |

