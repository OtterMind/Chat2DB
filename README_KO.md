<div align="center">
  <img src="./icon.png" alt="Chat2DB" width="100">
  <p><strong>개발자, DBA, 분석가 및 데이터 팀을 위한 AI 기반 데이터베이스 클라이언트이자 SQL 워크스페이스입니다.</strong></p>
</div>

<div align="center">
  <a href="./README.md"><img alt="README in English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./README_CN.md"><img alt="简体中文版自述文件" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
  <a href="./README_JA.md"><img alt="日本語のREADME" src="https://img.shields.io/badge/日本語-d9d9d9"></a>
  <a href="./README_ES.md"><img alt="README en español" src="https://img.shields.io/badge/Español-d9d9d9"></a>
  <a href="./README_KO.md"><img alt="한국어 README" src="https://img.shields.io/badge/한국어-d9d9d9"></a>
</div>

## Chat2DB란 무엇인가요?

Chat2DB Community는 Windows, macOS, Linux를 지원하는 무료 크로스 플랫폼 데이터베이스 클라이언트입니다. 전적으로 사용자의 컴퓨터에서 실행되며, 완전한 기능을 갖춘 SQL 워크스페이스와 사용자가 직접 연결하는 자체 AI 모델 기반의 AI 어시스턴트를 결합했습니다.

- **30개 이상의 데이터베이스** — MySQL, PostgreSQL, Oracle, SQL Server, ClickHouse, MongoDB, Redis, SQLite, MariaDB, TiDB, Hive, DB2, Snowflake, BigQuery, Elasticsearch 등을 플러그인으로 지원합니다.
- **SQL 워크스페이스** — 편집, 자동 완성, 서식 지정, 실행, 저장된 SQL 및 실행 기록을 제공합니다.
- **AI 어시스턴트** — 자체 AI 모델을 연결하여 자연어로 SQL을 생성, 설명 및 최적화합니다.
- **데이터베이스 관리** — 메타데이터 탐색, 테이블 및 객체 관리(DDL/DML), 데이터 직접 편집을 지원합니다.
- **데이터 가져오기 및 내보내기**, **대시보드 및 차트**, 그리고 **[MCP를 지원하는 오픈 소스 CLI](https://github.com/OtterMind/Chat2DB-CLI)**를 제공합니다.

<div align="center">

[![SQL 편집기와 AI 어시스턴트를 갖춘 Chat2DB 워크스페이스 — 클릭하여 소개 영상 보기](https://cdn.chat2db-ai.com/website/img/first_video_cover.webp)](https://cdn.chat2db-ai.com/website/video/first_sceen_en.mp4)

</div>

### 스크린샷

| 대시보드 및 차트 | ER 다이어그램 |
| --- | --- |
| ![대시보드 및 차트](https://cdn.chat2db-ai.com/website/img/bi_dashboard.png) | ![ER 다이어그램](https://cdn.chat2db-ai.com/website/img/er_diagrams.png) |

| 시각적 데이터 관리 | 데이터 가져오기 및 내보내기 |
| --- | --- |
| ![시각적 데이터 관리](https://cdn.chat2db-ai.com/website/img/visual_data_mnagement_en.png) | ![데이터 가져오기 및 내보내기](https://cdn.chat2db-ai.com/website/img/import_export_data_en.png) |

## 빠른 시작

### 옵션 1: 데스크톱 앱

[GitHub Releases](https://github.com/OtterMind/Chat2DB/releases)에서 사용 중인 플랫폼용 설치 프로그램을 다운로드하여 설치한 다음 데이터베이스에 연결을 시작하세요. 추가 설정은 필요하지 않습니다.

### 옵션 2: Docker

요구 사항: Docker 19.03.0 이상, Docker Compose 2.0.0 이상(Compose V2, Compose 방식에만 필요), CPU 코어 2개 이상, RAM 4GiB 이상.

먼저 암호화 키를 생성하고(중요한 이유는 [암호화 키](#암호화-키)를 참조하세요), 그다음 컨테이너를 시작하세요.

```bash
# 저장소 체크아웃에서 한 번 실행하세요. 다시 실행하면 기존의 유효한 키를 재사용합니다.
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

그다음 브라우저에서 `http://localhost:10825`를 여세요.

또는 저장소에 포함된 Compose 정의를 사용할 수 있습니다.

```bash
./script/security/init-community-encryption-key.sh
docker compose --file docker/docker-compose.yml up --detach
```

참고 사항:

- 업데이트하려면 새 이미지를 가져오고 기존 컨테이너를 제거한 다음 시작 명령을 다시 실행하세요. 컨테이너를 다시 빌드할 때도 `~/.config/chat2db-community/encryption.key`를 유지하세요.
- `docker run` 예시는 애플리케이션 데이터를 `$HOME/.chat2db-community-docker`에 저장하고, Compose 정의는 `chat2db-community-data`라는 이름이 지정된 볼륨을 사용합니다. 두 위치의 데이터는 공유되지 않습니다.
- Chat2DB Community 5.3.0은 독립적인 `/root/.chat2db-community` 디렉터리를 사용하며, `/root/.chat2db`를 사용하던 이전 이미지의 데이터를 자동으로 마이그레이션하지 않습니다.

## 보안 참고 사항

Chat2DB Community는 단일 사용자용 로컬 우선 애플리케이션입니다. 사용자 계정이나
여러 사용자 사이의 권한 경계를 제공하지 않습니다. HTTP 서비스는
`127.0.0.1` 또는 `::1`에 바인딩된 상태로 유지하고, 다른 사용자나
신뢰할 수 없는 네트워크에 노출하지 마세요.

사용자 지정 JDBC 드라이버는 실행 가능한 Java 코드입니다. 신뢰할 수 있는
출처의 드라이버만 설치하세요. 가져온 구성 파일, 압축 파일, SQL 파일,
데이터베이스 내용과 AI 응답은 계속 신뢰할 수 없는 데이터로 취급해야 합니다.
전체 신뢰 경계와 취약점 신고 절차는 [보안 정책](SECURITY.md)을
참조하세요.

이 프로젝트가 유용하다고 생각되시면 Star ⭐️를 눌러주세요!

<div align="center">
  <a href="https://github.com/OtterMind/Chat2DB"><img src="https://cdn.chat2db.ai/g/Area.gif" alt="GitHub에서 Chat2DB에 Star 누르기" width="600"></a>
</div>

## 암호화 키

Chat2DB Community는 저장된 데이터 소스 비밀번호와 AI 모델 API 키를 설치별 키를 사용하여 AES-256-GCM으로 암호화합니다. 저장소 체크아웃에서 키를 한 번 생성하세요(`openssl`이 필요합니다).

```bash
./script/security/init-community-encryption-key.sh
```

키는 `~/.config/chat2db-community/encryption.key`에 기록됩니다. **이 파일을 별도로 백업하고 업그레이드와 컨테이너 재빌드 후에도 유지하세요.** 키를 교체하거나 분실하면 이전에 저장한 데이터 소스 비밀번호와 AI 모델 API 키를 읽을 수 없습니다. 유효한 키가 제공되지 않으면 웹/헤드리스 시작은 실패하며, Desktop 모드만 누락된 키를 자동으로 생성합니다.

<details>
<summary>키 구성 참조(사용자 지정 경로, 결정 순서, 유효성 검사)</summary>

키는 디코딩 시 정확히 32바이트가 되는 유효한 Base64여야 합니다. 제공되는 초기화 도구는 표준 패딩 형식, 즉 `=`으로 끝나는 44자의 Base64를 생성합니다. 이는 사람이 읽을 수 있는 비밀번호가 아니라 암호화 키 자료입니다. 데이터 소스 비밀번호와 AI API 키는 서로 다른 인증 AAD 값과 동일한 키를 사용하므로 한 용도의 암호문을 다른 용도로 복호화할 수 없습니다.

사용자 지정 경로를 사용하려면 스크립트에 경로를 전달하고 Chat2DB를 시작할 때 동일한 경로를 구성하세요.

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

스크립트의 키 파일 경로 우선순위는 위치 인수, `CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE`, 기본 경로 순입니다. 스크립트는 유효한 일반 파일을 재사용하고 심볼릭 링크와 일반 파일이 아닌 파일을 거부하며, 유효하지 않은 파일을 덮어쓰지 않습니다. 키는 Chat2DB 프로세스 소유자만 읽을 수 있도록 유지하세요.

키 구성은 다음 순서로 결정됩니다.

1. Base64 키가 포함된 JVM 속성 `chat2db.community.encryption-key`.
2. Base64 키가 포함된 환경 변수 `CHAT2DB_COMMUNITY_ENCRYPTION_KEY`.
3. 키 파일 경로가 포함된 JVM 속성 `chat2db.community.encryption-key-file`.
4. 키 파일 경로가 포함된 환경 변수 `CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE`.
5. 기본 파일 `~/.config/chat2db-community/encryption.key`.

처음 구성된 값이 최종 기준입니다. 빈 값, 잘못된 Base64, 디코딩 시 32바이트가 아닌 키 또는 유효하지 않은 키 파일이 있으면 다음 소스로 넘어가지 않고 시작에 실패합니다. 키 값을 프로세스 인수나 환경 변수에 직접 넣지 않을 수 있으므로 파일 기반 구성을 권장합니다.

키 파일 자동 생성 여부는 `chat2db.gui`가 아니라 `chat2db.mode`에 따라 결정됩니다. Community Desktop 모드(`chat2db.runtime.mode=community` 및 `chat2db.mode=DESKTOP`)에서는 인라인 키가 구성되지 않았고 선택한 키 파일이 없을 때 해당 파일을 생성합니다. 일반 웹/헤드리스 시작을 포함한 모든 비 Desktop 모드에서는 누락된 키를 절대 생성하지 않으며, 유효한 키가 제공되거나 초기화될 때까지 시작에 실패합니다. 확인된 키는 프로세스 수명 동안 캐시되므로 키 구성을 변경한 후에는 애플리케이션을 다시 시작해야 합니다.

</details>

## 소스에서 빌드

### 사전 요구 사항

- Java 런타임: <a href="https://adoptium.net/temurin/releases/?version=17" target="_blank">Eclipse Temurin 17</a>
- Node.js 18.17.0 이상
- Maven 3.8 이상

### 저장소 복제

```bash
git clone https://github.com/OtterMind/Chat2DB.git
```

### 프런트엔드

저장소에 포함된 잠금 파일과 함께 Yarn을 사용하세요.

```bash
cd Chat2DB/chat2db-community-client
yarn install --frozen-lockfile
yarn run start:community:hot
```

### 백엔드

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

### 로컬 Docker 이미지 빌드

```bash
./docker/docker-build.sh 5.3.0 chat2db/chat2db:5.3.0
```

## Community와 상용 에디션 비교

Community 에디션에는 사용자 지정 AI 모델 지원을 포함하여 위에서 설명한 로컬 데이터베이스 클라이언트의 전체 기능이 들어 있습니다. 상용 버전인 Pro와 Enterprise 에디션은 동일한 핵심 기능 위에 호스팅 AI 서비스, 사용자 계정, 클라우드 저장소 및 다중 기기 동기화, 팀 협업 및 거버넌스 기능을 추가합니다. 자세한 내용은 [chat2db.ai](https://chat2db.ai)를 참조하세요.

## 기여하기

커뮤니티의 버그 보고, 기능 요청, 문서 개선, 테스트 피드백 및 Pull Request를 환영합니다.

Issue를 열거나 Pull Request를 제출하기 전에 [기여 가이드](./CONTRIBUTING.md)를 읽어 주세요. 버그 보고, 개선 제안 및 유지 관리자가 기여 내용을 더 쉽게 검토할 수 있도록 하는 방법을 안내합니다.

- 버그 및 기능 요청은 [GitHub Issues](https://github.com/OtterMind/Chat2DB/issues)를 이용해 주세요.
- 질문, 설정 도움말 및 자유로운 논의는 [GitHub Discussions](https://github.com/OtterMind/Chat2DB/discussions)를 이용해 주세요.
- Pull Request가 Issue와 관련되어 있다면 PR 설명에 해당 Issue 링크를 포함해 주세요.

## 커뮤니티 및 지원

- GitHub Issues: [버그 보고 또는 기능 요청](https://github.com/OtterMind/Chat2DB/issues)
- GitHub Discussions: [질문 및 아이디어 공유](https://github.com/OtterMind/Chat2DB/discussions)
- Discord: [Discord 서버 참여](https://discord.gg/uNjb3n5JVN)
- 이메일: Chat2DB@ch2db.com

## 감사의 말

Chat2DB에 기여해 주신 모든 분께 감사드립니다.

<a href="https://github.com/OtterMind/Chat2DB/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=OtterMind/Chat2DB" alt="Chat2DB 기여자 목록" />
</a>

## 라이선스

Chat2DB Community 버전 5.3.0 이상에는
[이 저장소의 라이선스 조건](./LICENSE)이 적용됩니다. 이는 Apache License 2.0을
기반으로 추가 조건이 포함된 소스 공개 라이선스입니다. 버전 0.3.7과 그 이전의
과거 태그를 포함하여 5.3.0보다 먼저 게시된 Chat2DB 릴리스에는 Apache License
2.0이 계속 적용됩니다.
