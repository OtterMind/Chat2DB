<div align="center">
  <img src="./icon.png" alt="Chat2DB" width="100">
  <p><strong>開発者、DBA、アナリスト、データチーム向けの AI 搭載データベースクライアント兼 SQL ワークスペースです。</strong></p>
</div>

<div align="center">
  <a href="./README.md"><img alt="README in English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./README_CN.md"><img alt="简体中文版自述文件" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
  <a href="./README_JA.md"><img alt="日本語のREADME" src="https://img.shields.io/badge/日本語-d9d9d9"></a>
  <a href="./README_ES.md"><img alt="README en español" src="https://img.shields.io/badge/Español-d9d9d9"></a>
  <a href="./README_KO.md"><img alt="한국어 README" src="https://img.shields.io/badge/한국어-d9d9d9"></a>
</div>

## Chat2DB とは？

Chat2DB Community は、Windows、macOS、Linux に対応した無料のクロスプラットフォームデータベースクライアントです。すべてローカルマシン上で動作し、フル機能の SQL ワークスペースと、自分で用意したモデルに接続して使う AI アシスタントを組み合わせています。

- **30 以上のデータベース** — MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse、MongoDB、Redis、SQLite、MariaDB、TiDB、Hive、DB2、Snowflake、BigQuery、Elasticsearch など。プラグインによりさらに拡張できます。
- **SQL ワークスペース** — 編集、補完、フォーマット、実行、保存済み SQL、実行履歴。
- **AI アシスタント** — 自分の AI モデルを接続し、自然言語で SQL の生成・説明・最適化を行えます。
- **データベース管理** — メタデータの参照、テーブルとオブジェクトの管理（DDL/DML）、データのインライン編集。
- **データのインポートとエクスポート**、**ダッシュボードとチャート**、**MCP 対応の CLI**。

<div align="center">

[![SQL エディタと AI アシスタントを備えた Chat2DB ワークスペース — クリックして紹介動画を見る](https://cdn.chat2db-ai.com/website/img/first_video_cover.webp)](https://cdn.chat2db-ai.com/website/video/first_sceen_en.mp4)

</div>

### スクリーンショット

| ダッシュボードとチャート | ER 図 |
| --- | --- |
| ![ダッシュボードとチャート](https://cdn.chat2db-ai.com/website/img/bi_dashboard.png) | ![ER 図](https://cdn.chat2db-ai.com/website/img/er_diagrams.png) |

| ビジュアルデータ管理 | データのインポートとエクスポート |
| --- | --- |
| ![ビジュアルデータ管理](https://cdn.chat2db-ai.com/website/img/visual_data_mnagement_en.png) | ![データのインポートとエクスポート](https://cdn.chat2db-ai.com/website/img/import_export_data_en.png) |

## クイックスタート

### オプション 1: デスクトップアプリ

[GitHub Releases](https://github.com/OtterMind/Chat2DB/releases) からお使いのプラットフォーム向けのインストーラーをダウンロードしてインストールし、データベースへの接続を始めてください。追加のセットアップは不要です。

### オプション 2: Docker

要件: Docker 19.03.0 以上、Docker Compose 2.0.0 以上（Compose V2、Compose を使う場合のみ）、CPU 2 コア以上、RAM 4 GiB 以上。

最初に暗号化キーを作成し（その重要性については[暗号化キー](#暗号化キー)を参照）、その後コンテナを起動します：

```bash
# リポジトリのチェックアウト内で一度だけ実行します。再実行時は同じ有効なキーを再利用します。
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

その後、ブラウザで `http://localhost:10825` を開いてください。

代わりに、リポジトリに含まれる Compose 定義を使用することもできます：

```bash
./script/security/init-community-encryption-key.sh
docker compose --file docker/docker-compose.yml up --detach
```

注意事項：

- 更新する場合は、新しいイメージを取得し、古いコンテナを削除してから起動コマンドを再実行します。コンテナを再作成しても `~/.config/chat2db-community/encryption.key` は保持してください。
- `docker run` の例ではアプリケーションデータを `$HOME/.chat2db-community-docker` に保存し、Compose 定義は名前付きボリューム `chat2db-community-data` を使用します。両者のデータは共有されません。
- Chat2DB Community 5.3.0 は独立した `/root/.chat2db-community` ディレクトリを使用し、`/root/.chat2db` を使用していた旧イメージのデータを自動移行しません。

## セキュリティに関する注意

Chat2DB Community は、単一ユーザー向けのローカルファーストなアプリケーションです。
ユーザーアカウントや、複数ユーザー間の認可境界は提供しません。HTTP サービスは
`127.0.0.1` または `::1` にバインドしたままにし、他のユーザーや
信頼できないネットワークへ公開しないでください。

カスタム JDBC ドライバーは実行可能な Java コードです。信頼できる提供元の
ドライバーだけをインストールしてください。インポートした設定ファイル、
アーカイブ、SQL ファイル、データベースの内容、AI の応答は引き続き信頼できない
データとして扱います。完全な信頼境界と脆弱性の報告手順については、
[セキュリティポリシー](SECURITY.md)を参照してください。

このプロジェクトが役に立ったら、ぜひ Star ⭐️ をお願いします!

<div align="center">
  <a href="https://github.com/OtterMind/Chat2DB"><img src="https://cdn.chat2db.ai/g/Area.gif" alt="GitHub で Chat2DB に Star を付ける" width="600"></a>
</div>

## 暗号化キー

Chat2DB Community は、保存されたデータソースパスワードと AI モデル API キーを、インストールごとのキーを使って AES-256-GCM で暗号化します。リポジトリのチェックアウト内で一度だけ作成してください（`openssl` が必要です）：

```bash
./script/security/init-community-encryption-key.sh
```

キーは `~/.config/chat2db-community/encryption.key` に書き込まれます。**このファイルは別途バックアップし、アップグレードやコンテナ再作成後も保持してください。** キーを置き換えたり失ったりすると、保存済みのデータソースパスワードと AI モデル API キーを復号できなくなります。有効なキーが与えられていない場合、Web/headless 起動は失敗します。存在しないキーを自動生成するのは Desktop モードだけです。

<details>
<summary>キー設定リファレンス（カスタムパス、解決順序、検証）</summary>

キーは有効な Base64 で、デコード後に正確に 32 バイトでなければなりません。付属の初期化スクリプトは、末尾が `=` の 44 文字の Base64 からなる標準のパディング付き形式を生成します。これは暗号化用のキーマテリアルであり、人が読めるパスワードではありません。データソースパスワードと AI API キーは同じキーを使用しますが、認証された AAD 値がそれぞれ別であるため、一方の用途の暗号文をもう一方として復号することはできません。

カスタムパスを使用する場合は、スクリプトにそのパスを渡し、Chat2DB の起動時にも同じパスを設定します：

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

スクリプトが使用するキーファイルパスの優先順位は、位置引数、`CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE`、デフォルトパスの順です。スクリプトは有効な通常ファイルを再利用し、シンボリックリンクと通常ファイル以外を拒否し、無効なファイルの上書きを拒みます。キーは Chat2DB プロセスの所有者だけが読み取れる状態に保ってください。

キー設定は次の順序で解決されます：

1. Base64 キーを含む JVM プロパティ `chat2db.community.encryption-key`。
2. Base64 キーを含む環境変数 `CHAT2DB_COMMUNITY_ENCRYPTION_KEY`。
3. キーファイルのパスを含む JVM プロパティ `chat2db.community.encryption-key-file`。
4. キーファイルのパスを含む環境変数 `CHAT2DB_COMMUNITY_ENCRYPTION_KEY_FILE`。
5. デフォルトファイル `~/.config/chat2db-community/encryption.key`。

最初に設定されている値が優先されます。空の値、不正な Base64、デコード後に 32 バイトにならないキー、無効なキーファイルは、次の設定ソースへフォールバックせずに起動を失敗させます。キー値をプロセス引数や環境変数に直接置かずに済むため、ファイルベースの設定を推奨します。

キーファイルの自動生成は `chat2db.gui` ではなく `chat2db.mode` に依存します。Community Desktop モード（`chat2db.runtime.mode=community` かつ `chat2db.mode=DESKTOP`）は、インラインキーが設定されておらず、選択されたキーファイルが存在しない場合にそのファイルを作成します。通常の Web/headless 起動を含む Desktop 以外のモードは、存在しないキーを決して作成せず、有効なキーが提供または初期化されるまで起動に失敗します。解決されたキーはプロセスの存続期間中キャッシュされるため、キー設定を変更した場合はアプリケーションの再起動が必要です。

</details>

## ソースからビルド

### 前提条件

- Java runtime: <a href="https://adoptium.net/temurin/releases/?version=17" target="_blank">Eclipse Temurin 17</a>
- Node.js 18.17.0 以降
- Maven 3.8 以降

### リポジトリのクローン

```bash
git clone https://github.com/OtterMind/Chat2DB.git
```

### フロントエンド

リポジトリに含まれる Yarn lockfile を使用してください。

```bash
cd Chat2DB/chat2db-community-client
yarn install --frozen-lockfile
yarn run start:community:hot
```

### バックエンド

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

### ローカル Docker イメージのビルド

```bash
./docker/docker-build.sh 5.3.0 chat2db/chat2db:5.3.0
```

## Community 版と商用版の違い

Community 版には、カスタム AI モデルのサポートを含め、上記で説明したローカルデータベースクライアントの全機能が含まれています。商用の Pro 版と Enterprise 版は同じコアを基盤とし、ホスティングされた AI サービス、ユーザーアカウント、クラウドストレージとマルチデバイス同期、チームコラボレーションとガバナンス機能を追加します。詳細は [chat2db.ai](https://chat2db.ai) を参照してください。

## コントリビューション

コミュニティからのバグ報告、機能リクエスト、ドキュメント改善、テストフィードバック、Pull Request を歓迎します。

Issue の作成や Pull Request の送信前に、[コントリビューションガイド](./CONTRIBUTING.md)をお読みください。バグの報告方法、改善の提案方法、メンテナーがレビューしやすい形で貢献する方法を説明しています。

- バグや機能リクエストには [GitHub Issues](https://github.com/OtterMind/Chat2DB/issues) を使用してください。
- 質問、セットアップ支援、自由な議論には [GitHub Discussions](https://github.com/OtterMind/Chat2DB/discussions) を使用してください。
- Pull Request が Issue に関連する場合は、PR の説明に Issue へのリンクを含めてください。

## コミュニティとサポート

- GitHub Issues: [バグの報告や機能のリクエスト](https://github.com/OtterMind/Chat2DB/issues)
- GitHub Discussions: [質問やアイデアの共有](https://github.com/OtterMind/Chat2DB/discussions)
- Discord: [Discord サーバーに参加](https://discord.gg/uNjb3n5JVN)
- メール: Chat2DB@ch2db.com

## 謝辞

Chat2DB に貢献してくださったすべての方々に感謝します。

<a href="https://github.com/OtterMind/Chat2DB/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=OtterMind/Chat2DB" alt="Chat2DB のコントリビューター" />
</a>

## ライセンス

Chat2DB Community バージョン 5.3.0 以降は、
[このリポジトリのライセンス条項](./LICENSE)の下で提供されます。これは
Apache License 2.0 を基礎として追加条件を設けた Source Available
ライセンスです。バージョン 5.3.0 より前に公開された Chat2DB のリリース
（バージョン 0.3.7 およびそれ以前の履歴タグを含む）には、引き続き
Apache License 2.0 が適用されます。
