<div align="center">
  <img src="./icon.png" alt="Chat2DB" width="100">
  <p><strong>面向开发者、DBA、分析师和数据团队的 AI 驱动数据库客户端与 SQL 工作空间。</strong></p>
</div>

<div align="center">
  <a href="./README.md"><img alt="README in English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./README_CN.md"><img alt="简体中文版自述文件" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
  <a href="./README_JA.md"><img alt="日本語のREADME" src="https://img.shields.io/badge/日本語-d9d9d9"></a>
  <a href="./README_ES.md"><img alt="README en español" src="https://img.shields.io/badge/Español-d9d9d9"></a>
  <a href="./README_KO.md"><img alt="한국어 README" src="https://img.shields.io/badge/한국어-d9d9d9"></a>
</div>

## Chat2DB 是什么?

Chat2DB Community 是一款免费的跨平台数据库客户端,支持 Windows、macOS 和 Linux。它完全运行在你自己的机器上,提供功能完整的 SQL 工作空间,并可接入你自己的 AI 模型作为智能助手。

- **30+ 种数据库** —— MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse、MongoDB、Redis、SQLite、MariaDB、TiDB、Hive、DB2、Snowflake、BigQuery、Elasticsearch 等,通过插件扩展。
- **SQL 工作空间** —— SQL 编辑、补全、格式化、执行、SQL 收藏与历史记录。
- **AI 助手** —— 接入自定义 AI 模型,用自然语言生成、解释和优化 SQL。
- **数据库管理** —— 元数据浏览、表和对象管理(DDL/DML)、在线编辑数据。
- **数据导入导出**、**Dashboard 与图表**,以及支持 **[MCP 的开源 CLI](https://github.com/OtterMind/Chat2DB-CLI)**。

<div align="center">

[![Chat2DB 工作台:SQL 编辑器与 AI 助手 —— 点击观看介绍视频](https://cdn.chat2db-ai.com/website/img/first_video_cover.webp)](https://cdn.chat2db-ai.com/website/video/first_sceen_en.mp4)

</div>

### 产品界面

| Dashboard 与图表 | ER 图 |
| --- | --- |
| ![Dashboard 与图表](https://cdn.chat2db-ai.com/website/img/bi_dashboard.png) | ![ER 图](https://cdn.chat2db-ai.com/website/img/er_diagrams.png) |

| 可视化数据管理 | 数据导入导出 |
| --- | --- |
| ![可视化数据管理](https://cdn.chat2db-ai.com/website/img/visual_data_mnagement_en.png) | ![数据导入导出](https://cdn.chat2db-ai.com/website/img/import_export_data_en.png) |

## 快速开始

### 方式一:桌面应用

从 [GitHub Releases](https://github.com/OtterMind/Chat2DB/releases) 下载对应平台的安装包,安装后即可连接数据库使用,无需其他配置。

### 方式二:Docker

系统要求:Docker 19.03.0+、Docker Compose 2.0.0+(Compose V2,仅 Compose 方式需要)、CPU 2 核以上、内存 4 GiB 以上。

先创建加密密钥(用途见[加密密钥](#加密密钥)一节),再启动容器:

```bash
# 在仓库目录中首次执行一次;重复执行会复用同一把合法密钥。
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

然后在浏览器中访问 `http://localhost:10825`。

也可以使用仓库自带的 Compose 配置:

```bash
./script/security/init-community-encryption-key.sh
docker compose --file docker/docker-compose.yml up --detach
```

注意事项:

- 更新时先拉取新镜像、删除旧容器,再重新执行启动命令。容器重建时必须保留 `~/.config/chat2db-community/encryption.key`。
- `docker run` 示例将应用数据保存在 `$HOME/.chat2db-community-docker`;Compose 配置使用名为 `chat2db-community-data` 的命名卷。两处存储不会自动共享数据。
- Chat2DB Community 5.3.0 使用独立的 `/root/.chat2db-community` 目录,不会自动迁移旧镜像 `/root/.chat2db` 中的数据。

## 安全须知

Chat2DB Community 是单用户、本机优先的应用,不提供用户账号或多用户之间的
权限边界。HTTP 服务必须绑定到 `127.0.0.1` 或 `::1`,不要暴露给其他用户或
不可信网络。

自定义 JDBC Driver 是可执行 Java 代码,只应安装来自可信来源的驱动。导入的
配置文件、压缩包、SQL 文件、数据库内容和 AI 响应仍属于不可信数据。完整信任
边界和漏洞报告流程请参阅[安全策略](SECURITY.md)。

如果你觉得这个项目对你有用,帮我们点个 Star ⭐️ 吧!

<div align="center">
  <a href="https://github.com/OtterMind/Chat2DB"><img src="https://cdn.chat2db.ai/g/Area.gif" alt="给 Chat2DB 点个 Star" width="600"></a>
</div>

## 加密密钥

Chat2DB Community 使用 AES-256-GCM 加密保存的数据源密码和 AI 模型 API Key,每个安装实例使用独立密钥。在仓库目录中执行一次以下命令创建密钥(依赖 `openssl`):

```bash
./script/security/init-community-encryption-key.sh
```

密钥会写入 `~/.config/chat2db-community/encryption.key`。**请单独备份该文件,并在升级和容器重建时保留** —— 替换或丢失密钥会导致已保存的数据源密码和 AI 模型 API Key 无法解密。Web/headless 方式启动时缺少合法密钥会直接启动失败;只有 Desktop 模式会自动创建缺失的密钥。

<details>
<summary>密钥配置参考(自定义路径、解析优先级、校验规则)</summary>

密钥必须是合法的 Base64,且解码后恰好为 32 字节。仓库提供的初始化脚本会生成标准带填充格式,即 44 个 Base64 字符并以 `=` 结尾。它是加密密钥材料,不是用户自行输入的普通口令。数据源密码和 AI API Key 使用同一把密钥,但使用不同的认证 AAD,因此一种用途的密文不能作为另一种用途解密。

如需使用自定义路径,应在初始化脚本和 Chat2DB 启动参数中指定同一路径:

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

脚本选择密钥文件路径的优先级依次为位置参数、`CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE` 和默认路径。脚本会复用已有的合法普通文件,拒绝符号链接和非普通文件;如果已有文件不合法,脚本会报错且不会覆盖。密钥文件应当只允许 Chat2DB 进程所属用户读取。

密钥配置按以下优先级解析:

1. JVM 参数 `chat2db.community.encryption-key`,值为 Base64 密钥。
2. 环境变量 `CHAT2DB_COMMUNITY_ENCRYPTION_KEY`,值为 Base64 密钥。
3. JVM 参数 `chat2db.community.encryption-key-file`,值为密钥文件路径。
4. 环境变量 `CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE`,值为密钥文件路径。
5. 默认文件 `~/.config/chat2db-community/encryption.key`。

第一个已配置的值具有最高优先级。空值、非法 Base64、解码后不是 32 字节的密钥或非法密钥文件都会直接导致启动失败,不会静默回退到下一项。推荐使用密钥文件,避免把密钥值直接暴露在进程参数或环境变量中。

密钥文件是否自动创建只取决于 `chat2db.mode`,与 `chat2db.gui` 无关。Community Desktop 模式(`chat2db.runtime.mode=community` 且 `chat2db.mode=DESKTOP`)会在未配置内联密钥且所选密钥文件不存在时自动创建该文件。任何非 Desktop 模式(包括常规 Web/headless 启动)都不会创建缺失的密钥,必须提前初始化或显式配置合法密钥,否则启动失败。解析后的密钥会在进程生命周期内缓存,因此修改密钥配置后必须重启应用。

</details>

## 从源码构建

### 环境要求

- Java 运行环境:<a href="https://adoptium.net/temurin/releases/?version=17" target="_blank">Eclipse Temurin 17</a>
- Node.js 18.17.0 或更高版本
- Maven 3.8 或以上版本

### 克隆仓库

```bash
git clone https://github.com/OtterMind/Chat2DB.git
```

### 前端

请使用仓库中的 Yarn lockfile。

```bash
cd Chat2DB/chat2db-community-client
yarn install --frozen-lockfile
yarn run start:community:hot
```

### 后端

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

### 构建本地 Docker 镜像

```bash
./docker/docker-build.sh 5.3.0 chat2db/chat2db:5.3.0
```

## 社区版与商业版

社区版包含上述完整的本地数据库客户端能力,包括自定义 AI 模型支持。商业版 Pro 和 Enterprise 在同一核心之上增加官方 AI 服务、账号体系、云端存储与多设备同步,以及团队协作和治理能力。详情请见 [chat2db.ai](https://chat2db.ai)。

## 参与贡献

我们欢迎社区提交 Bug、功能建议、文档改进、测试反馈和 Pull Request。

创建 Issue 或提交 Pull Request 前,请先阅读[贡献指南](./CONTRIBUTING.md)。其中说明了如何报告问题、提出建议,以及如何让维护者更高效地审查贡献。

- Bug 和功能建议请使用 [GitHub Issues](https://github.com/OtterMind/Chat2DB/issues)。
- 使用问题、配置帮助和开放讨论请使用 [GitHub Discussions](https://github.com/OtterMind/Chat2DB/discussions)。
- 如果 Pull Request 与某个 Issue 相关,请在 PR 描述中附上对应链接。

## 社区与支持

- GitHub Issues:[报告 Bug 或提出功能建议](https://github.com/OtterMind/Chat2DB/issues)
- GitHub Discussions:[提问与交流](https://github.com/OtterMind/Chat2DB/discussions)
- Discord:[加入我们的 Discord 服务器](https://discord.gg/uNjb3n5JVN)
- Email:Chat2DB@ch2db.com

## 致谢

感谢所有为 Chat2DB 贡献力量的同学们。

<a href="https://github.com/OtterMind/Chat2DB/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=OtterMind/Chat2DB" alt="Chat2DB 贡献者" />
</a>

## 许可证

Chat2DB Community 5.3.0 及后续版本适用本仓库的
[LICENSE](./LICENSE)。该许可基于 Apache License 2.0 并附加了使用条件,
属于 Source Available 许可。Chat2DB 5.3.0 之前发布的所有版本,包括 0.3.7
以及更早的历史版本,继续适用 Apache License 2.0。
