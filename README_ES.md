<div align="center">
  <img src="./icon.png" alt="Chat2DB" width="100">
  <p><strong>Un cliente de bases de datos y espacio de trabajo SQL con IA para desarrolladores, administradores de bases de datos, analistas y equipos de datos.</strong></p>
</div>

<div align="center">
  <a href="./README.md"><img alt="README in English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./README_CN.md"><img alt="简体中文版自述文件" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
  <a href="./README_JA.md"><img alt="日本語のREADME" src="https://img.shields.io/badge/日本語-d9d9d9"></a>
  <a href="./README_ES.md"><img alt="README en español" src="https://img.shields.io/badge/Español-d9d9d9"></a>
  <a href="./README_KO.md"><img alt="한국어 README" src="https://img.shields.io/badge/한국어-d9d9d9"></a>
</div>

## ¿Qué es Chat2DB?

Chat2DB Community es un cliente de bases de datos gratuito y multiplataforma para Windows, macOS y Linux. Se ejecuta por completo en su equipo y combina un espacio de trabajo SQL con todas las funciones con un asistente de IA que usted conecta a su propio modelo.

- **Más de 30 bases de datos** — MySQL, PostgreSQL, Oracle, SQL Server, ClickHouse, MongoDB, Redis, SQLite, MariaDB, TiDB, Hive, DB2, Snowflake, BigQuery, Elasticsearch y más mediante plugins.
- **Espacio de trabajo SQL** — edición, autocompletado, formato, ejecución, SQL guardado e historial de ejecución.
- **Asistente de IA** — use su propio modelo de IA para generar, explicar y optimizar SQL en lenguaje natural.
- **Gestión de bases de datos** — explore metadatos, gestione tablas y objetos (DDL/DML) y edite los datos directamente.
- **Importación y exportación de datos**, **paneles y gráficos**, y una **CLI con soporte de MCP**.

<div align="center">

[![Espacio de trabajo de Chat2DB con editor SQL y asistente de IA — haga clic para ver el vídeo de presentación](https://cdn.chat2db-ai.com/website/img/first_video_cover.webp)](https://cdn.chat2db-ai.com/website/video/first_sceen_en.mp4)

</div>

### Capturas de pantalla

| Paneles y gráficos | Diagramas ER |
| --- | --- |
| ![Paneles y gráficos](https://cdn.chat2db-ai.com/website/img/bi_dashboard.png) | ![Diagrama ER](https://cdn.chat2db-ai.com/website/img/er_diagrams.png) |

| Gestión visual de datos | Importación y exportación de datos |
| --- | --- |
| ![Gestión visual de datos](https://cdn.chat2db-ai.com/website/img/visual_data_mnagement_en.png) | ![Importación y exportación de datos](https://cdn.chat2db-ai.com/website/img/import_export_data_en.png) |

## Inicio rápido

### Opción 1: Aplicación de escritorio

Descargue el instalador para su plataforma desde [GitHub Releases](https://github.com/OtterMind/Chat2DB/releases), instálelo y empiece a conectarse a sus bases de datos. No se requiere ninguna configuración adicional.

### Opción 2: Docker

Requisitos: Docker 19.03.0 o posterior, Docker Compose 2.0.0 o posterior (Compose V2, solo para la variante con Compose), 2 o más núcleos de CPU, 4 GiB o más de RAM.

Primero cree la clave de cifrado (consulte [Clave de cifrado](#clave-de-cifrado) para saber por qué es importante) y, a continuación, inicie el contenedor:

```bash
# Ejecútelo una vez desde un checkout del repositorio. Las ejecuciones posteriores reutilizan la misma clave válida.
git clone https://github.com/OtterMind/Chat2DB.git && cd Chat2DB
./script/security/init-community-encryption-key.sh

docker run --detach \
  --name chat2db-community \
  --restart unless-stopped \
  --publish 127.0.0.1:10825:10825 \
  --volume "$HOME/.chat2db-community-docker:/root/.chat2db-community" \
  --env CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE=/run/secrets/chat2db-community-encryption.key \
  --volume "$HOME/.config/chat2db-community/encryption.key:/run/secrets/chat2db-community-encryption.key:ro" \
  chat2db/chat2db:latest
```

Después abra `http://localhost:10825` en su navegador.

Como alternativa, use la definición de Compose incluida:

```bash
./script/security/init-community-encryption-key.sh
docker compose --file docker/docker-compose.yml up --detach
```

Notas:

- Para actualizar, descargue la nueva imagen, elimine el contenedor anterior y ejecute de nuevo el comando de inicio. Conserve `~/.config/chat2db-community/encryption.key` entre reconstrucciones.
- El ejemplo de `docker run` almacena los datos de la aplicación en `$HOME/.chat2db-community-docker`; la definición de Compose usa el volumen con nombre `chat2db-community-data`. Estas ubicaciones no comparten datos.
- Chat2DB Community 5.3.0 utiliza el directorio independiente `/root/.chat2db-community` y no migra automáticamente los datos de imágenes anteriores que usaban `/root/.chat2db`.

## Notas de seguridad

Chat2DB Community es una aplicación local y para un solo usuario. No tiene
cuentas de usuario ni límites de autorización entre usuarios. Mantenga el
servicio HTTP enlazado a `127.0.0.1` o `::1` y no lo exponga a otros usuarios
ni a redes que no sean de confianza.

Los controladores JDBC personalizados son código Java ejecutable: instálelos
solo desde fuentes en las que confíe. Los archivos de configuración
importados, los archivos comprimidos, los archivos SQL, los contenidos de las
bases de datos y las respuestas de la IA siguen siendo datos no confiables.
Consulte la [Política de seguridad](SECURITY.md) para conocer el límite de
confianza completo y el proceso de notificación de vulnerabilidades.

Si este proyecto le resulta útil, ¡regálenos una estrella ⭐️!

<div align="center">
  <a href="https://github.com/OtterMind/Chat2DB"><img src="https://cdn.chat2db.ai/g/Area.gif" alt="Dar una estrella a Chat2DB en GitHub" width="600"></a>
</div>

## Clave de cifrado

Chat2DB Community cifra las contraseñas de fuentes de datos y las claves API de modelos de IA almacenadas con AES-256-GCM usando una clave por instalación. Créela una sola vez desde un checkout del repositorio (requiere `openssl`):

```bash
./script/security/init-community-encryption-key.sh
```

La clave se escribe en `~/.config/chat2db-community/encryption.key`. **Haga una copia de seguridad de este archivo por separado y consérvelo entre actualizaciones y reconstrucciones del contenedor**: si lo sustituye o lo pierde, las contraseñas de fuentes de datos y las claves API de modelos de IA almacenadas anteriormente dejarán de ser legibles. El inicio en modo web/headless falla cuando no se proporciona una clave válida; solo el modo Desktop crea automáticamente una clave que falte.

<details>
<summary>Referencia de configuración de la clave (rutas personalizadas, orden de resolución, validación)</summary>

La clave debe ser Base64 válido que se decodifique exactamente en 32 bytes. El inicializador incluido genera el formato estándar con relleno: 44 caracteres Base64 terminados en `=`. Es material criptográfico de clave, no una contraseña legible por humanos. Las contraseñas de fuentes de datos y las claves API de IA usan la misma clave con valores AAD autenticados independientes, por lo que el texto cifrado de un propósito no se puede descifrar como si perteneciera al otro.

Para usar una ruta personalizada, pásela al script y configure la misma ruta al iniciar Chat2DB:

```bash
./script/security/init-community-encryption-key.sh /secure/path/chat2db-community.key

java -Dloader.path=chat2db-community-server/chat2db-community-start/target/lib \
    -Dchat2db.runtime.mode=community \
    -Dchat2db.mode=WEB \
    -Dchat2db.gui=false \
    -Dchat2db.network.status=OFFLINE \
    -Dchat2db.community.encryption-key-file=/secure/path/chat2db-community.key \
    -Dserver.address=127.0.0.1 \
    -Dserver.port=10825 \
    -jar chat2db-community-server/chat2db-community-start/target/chat2db-community.jar
```

La prioridad del script para la ruta del archivo de clave es: el argumento posicional, `CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE` y, por último, la ruta predeterminada. El script reutiliza un archivo regular válido, rechaza enlaces simbólicos y archivos no regulares, y se niega a sobrescribir un archivo no válido. Mantenga la clave legible únicamente por el propietario del proceso de Chat2DB.

La configuración de la clave se resuelve en este orden:

1. Propiedad JVM `chat2db.community.encryption-key` que contiene la clave Base64.
2. Variable de entorno `CHAT2DB_COMMUNITY_ENCRYPTION_KEY` que contiene la clave Base64.
3. Propiedad JVM `chat2db.community.encryption-key-file` que contiene la ruta de un archivo de clave.
4. Variable de entorno `CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE` que contiene la ruta de un archivo de clave.
5. Archivo predeterminado `~/.config/chat2db-community/encryption.key`.

El primer valor configurado es el autoritativo. Un valor en blanco, Base64 mal formado, una clave que no se decodifica en 32 bytes o un archivo de clave no válido hacen fallar el inicio en lugar de continuar con el origen siguiente. Se recomienda la configuración basada en archivo porque evita colocar el valor de la clave directamente en los argumentos del proceso o en las variables de entorno.

La creación automática del archivo de clave depende de `chat2db.mode`, no de `chat2db.gui`. El modo Desktop de Community (`chat2db.runtime.mode=community` con `chat2db.mode=DESKTOP`) crea el archivo de clave seleccionado cuando no hay configurada una clave en línea y el archivo no existe. Cualquier modo que no sea Desktop, incluido el inicio web/headless normal, nunca crea una clave que falte y falla hasta que se proporcione o inicialice una clave válida. La clave resuelta se almacena en caché durante la vida del proceso, por lo que cambiar la configuración de la clave requiere reiniciar la aplicación.

</details>

## Compilar desde el código fuente

### Requisitos previos

- Entorno de ejecución de Java: <a href="https://adoptium.net/temurin/releases/?version=17" target="_blank">Eclipse Temurin 17</a>
- Node.js 18.17.0 o posterior
- Maven 3.8 o posterior

### Clonar el repositorio

```bash
git clone https://github.com/OtterMind/Chat2DB.git
```

### Frontend

Use Yarn con el archivo de bloqueo incluido en el repositorio.

```bash
cd Chat2DB/chat2db-community-client
yarn install --frozen-lockfile
yarn run start:community:hot
```

### Backend

```bash
cd Chat2DB
mvn -B clean package -Dmaven.test.skip=true -Dchat2db.finalName=chat2db-community \
    -f chat2db-community-server/pom.xml \
    -pl chat2db-community-start -am
./script/security/init-community-encryption-key.sh
java -Dloader.path=chat2db-community-server/chat2db-community-start/target/lib \
    -Dchat2db.gui=false \
    -Dchat2db.runtime.mode=community \
    -Dchat2db.mode=WEB \
    -Dchat2db.network.status=OFFLINE \
    -Dchat2db.community.encryption-key-file="$HOME/.config/chat2db-community/encryption.key" \
    -Dserver.address=127.0.0.1 \
    -Dserver.port=10825 \
    -Dspring.profiles.active=dev \
    -jar chat2db-community-server/chat2db-community-start/target/chat2db-community.jar
```

### Compilar una imagen de Docker local

```bash
./docker/docker-build.sh 5.3.0 chat2db/chat2db:5.3.0
```

## Ediciones Community y comerciales

La edición Community contiene el cliente local de bases de datos completo descrito anteriormente, incluido el soporte de modelos de IA personalizados. Las ediciones comerciales Pro y Enterprise se basan en el mismo núcleo y añaden servicios de IA alojados, cuentas de usuario, almacenamiento en la nube y sincronización entre dispositivos, así como funciones de colaboración y gobierno para equipos. Consulte [chat2db.ai](https://chat2db.ai) para más detalles.

## Contribuciones

Agradecemos los informes de errores, las solicitudes de funciones, las mejoras de documentación, los comentarios sobre pruebas y las pull requests de la comunidad.

Antes de abrir una incidencia o enviar una pull request, lea nuestra [Guía de contribución](./CONTRIBUTING.md). Explica cómo informar de errores, sugerir mejoras y facilitar la revisión de las contribuciones por parte de los mantenedores.

- Para errores y solicitudes de funciones, utilice [GitHub Issues](https://github.com/OtterMind/Chat2DB/issues).
- Para preguntas, ayuda con la configuración y debates abiertos, utilice [GitHub Discussions](https://github.com/OtterMind/Chat2DB/discussions).
- Si su pull request está relacionada con una incidencia, enlácela en la descripción de la PR.

## Comunidad y soporte

- GitHub Issues: [informe de un error o solicite una función](https://github.com/OtterMind/Chat2DB/issues)
- GitHub Discussions: [haga preguntas y comparta ideas](https://github.com/OtterMind/Chat2DB/discussions)
- Discord: [únase a nuestro servidor de Discord](https://discord.gg/uNjb3n5JVN)
- Correo electrónico: Chat2DB@ch2db.com

## Agradecimientos

Gracias a todas las personas que han contribuido a Chat2DB.

<a href="https://github.com/OtterMind/Chat2DB/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=OtterMind/Chat2DB" alt="Colaboradores de Chat2DB" />
</a>

## Licencia

La versión 5.3.0 y posteriores de Chat2DB Community están disponibles bajo los
[términos de licencia de este repositorio](./LICENSE). Se trata de una licencia
de código disponible basada en Apache License 2.0 con condiciones adicionales.
Las versiones de Chat2DB publicadas antes de la 5.3.0, incluida la 0.3.7 y las
etiquetas históricas anteriores, siguen estando bajo Apache License 2.0.
