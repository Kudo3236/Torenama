# Torenama

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/SpringBoot-3.x-green)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Database-blue)
![Render](https://img.shields.io/badge/Deploy-Render-purple)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

Torenama は Qiita API から記事を取得し、  
以下のスコア計算式でトレンドランキングを生成するサービスです。
score = likes * 0.6 + stocks * 0.4

生成されたランキングは PostgreSQL に保存され、  
`/mock/trends` API から常に最新データを取得できます。

また Slack Slash Command を利用して手動更新することも可能です。

---

# デモ

APIサーバー

https://torenama-api-server.onrender.com

ランキング取得例

https://torenama-api-server.onrender.com/mock/trends

---

# システム構成
Slack
│
│ /torenama-items
▼
slack-bolt
│
│ POST /admin/update
▼
api-server
│
│ Qiita API 呼び出し
▼
Qiita API
│
▼
PostgreSQL
│
▼
GET /mock/trends


---

# 主な機能

- Qiita API から記事情報を取得
- likes / stocks を用いたスコア算出
- トレンドランキング生成
- PostgreSQL へのデータ保存
- `/mock/trends` API 提供
- Slack Slash Command による手動更新
- `@Scheduled` による1日1回自動更新
- ログ出力
- 例外処理
- レートリミット対応
- Render へのデプロイ対応

---

# API仕様

|メソッド|エンドポイント|説明|
|---|---|---|
|POST|/admin/update|Qiita記事を取得しランキングを更新|
|GET|/mock/trends|最新ランキングを取得|
|GET|/healthz|APIサーバーの状態確認|

---


# リポジトリ構成

```
Torenama
├─ README.md
├─ .env
│
├─ api-server
│ ├─ pom.xml
│ ├─ Dockerfile
│ └─ src
│ └─ main
│ ├─ java
│ │ └─ com
│ │ └─ sample
│ │ └─ api
│ │ ├─ controller
│ │ │ └─ TrendsController.java
│ │ ├─ service
│ │ │ ├─ QiitaTrendService.java
│ │ │ └─ SchedulerService.java
│ │ └─ ApiApplication.java
│ │
│ └─ resources
│ └─ application.yml
│
└─ slack-bolt
├─ pom.xml
├─ Dockerfile
└─ src
└─ main
└─ java
└─ com
└─ sample
└─ slack
└─ AppMain.java
```

---

# 必要環境

- Java 17
- Maven 3.9以上
- PostgreSQL
- Slack App
- Qiita API Token

---

# 環境変数

## api-server

|変数名|説明|
|---|---|
|QIITA_TOKEN|Qiita API トークン|
|SPRING_DATASOURCE_URL|PostgreSQL JDBC URL|
|SPRING_DATASOURCE_USERNAME|DBユーザー|
|SPRING_DATASOURCE_PASSWORD|DBパスワード|

例
- SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/torenama
- SPRING_DATASOURCE_USERNAME=postgres
- SPRING_DATASOURCE_PASSWORD=postgres

---

## slack-bolt

|変数名|説明|
|---|---|
|SLACK_SIGNING_SECRET|Slack Signing Secret|
|SLACK_BOT_TOKEN|Slack Bot OAuth Token|
|API_BASE_URL|api-server のURL|

例
API_BASE_URL=http://localhost:8080

---

# ローカル実行方法

## api-server 起動
cd api-server
mvn clean package
java -jar target/api-server.jar

PowerShell の例
$env:QIITA_TOKEN="your_token"
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/torenama"
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="postgres"

java -jar target/api-server.jar

起動確認
http://localhost:8080/healthz

---

## slack-bolt 起動
cd slack-bolt
mvn clean package
java -jar target/slack-bolt.jar

PowerShell の例
$env:SLACK_SIGNING_SECRET="xxxx"
$env:SLACK_BOT_TOKEN="xoxb-xxxxx"
$env:API_BASE_URL="http://localhost:8080"

java -jar target/slack-bolt.jar

---

# Slack連携

Slash Command
/torenama-items

Request URL
https://<slack-bolt-url>/slack/events

このコマンドを実行すると  
`api-server` の `/admin/update` が呼び出され、ランキングが更新されます。

---

# 定期更新

ランキングは `@Scheduled` により1日1回自動更新されます。
(毎日 09:00 に更新)

---

# データベース

テーブル名
trend_snapshots

テーブル定義
create table if not exists trend_snapshots (
    id bigserial primary key,
    payload text not null,
    created_at timestamptz not null default now()
);

---

# ログ出力
以下のイベントをログに記録します。

- 更新開始
- 更新成功
- 更新失敗
- Qiita API 呼び出し結果
- レートリミット検知
- DB保存結果

---

# レートリミット対応
Qiita API が HTTP 429 を返した場合

- 一定時間待機
- 再試行
- 最大リトライ回数制御

---

# Render デプロイ
api-server

Root Directory
- api-server

Environment
- Docker

Environment Variables
- QIITA_TOKEN
- SPRING_DATASOURCE_URL
- SPRING_DATASOURCE_USERNAME
- SPRING_DATASOURCE_PASSWORD

slack-bolt
Root Directory
- slack-bolt

Environment
- Docker

Environment Variables
- SLACK_SIGNING_SECRET
- SLACK_BOT_TOKEN
- API_BASE_URL

---

# 制約事項
- Render Freeプランではコールドスタートが発生する
- Slack Slash Command は3秒以内に応答する必要がある

---

# 今後の改善
- APIレスポンス高速化

---

# セキュリティ
SlackトークンやDB接続情報などの機密情報は
環境変数で管理し、リポジトリには含めていません。

---
=======
# Torenama

Qiita API から記事一覧を取得し、トレンドスコア  
`likes * 0.6 + stocks * 0.4`  
を算出してランキング化するアプリです。

本プロジェクトは以下の2サービス構成です。

- `api-server`
  - Qiita API 取得
  - トレンドスコア算出
  - PostgreSQL へ保存
  - `/mock/trends` 提供
  - `@Scheduled` による定期更新

- `slack-bolt`
  - Slack Slash Command `/torenama-items` を受付
  - `api-server` の `/admin/update` を呼び出して手動更新

---

## システム構成

```text
Slack
  │
  │ /torenama-items
  ▼
slack-bolt
  │
  │ POST /admin/update
  ▼
api-server
  │
  │ GET /api/v2/items
  ▼
Qiita API
  │
  ▼
PostgreSQL
  │
  ▼
GET /mock/trends


---

# Features

- Qiita API から記事取得
- トレンドスコア算出
- PostgreSQL 永続保存
- `/mock/trends` API 提供
- Slack Slash Command 更新
- `@Scheduled` による1日1回更新
- 例外処理
- レートリミット対応
- Render デプロイ対応

---

# Requirements

- Java 17
- Maven 3.9+
- PostgreSQL
- Slack App
- Qiita API Token

---

# Environment Variables

## api-server

|Name|Description|
|---|---|
|QIITA_TOKEN|Qiita API Token|
|SPRING_DATASOURCE_URL|PostgreSQL JDBC URL|
|SPRING_DATASOURCE_USERNAME|DB Username|
|SPRING_DATASOURCE_PASSWORD|DB Password|

---

## slack-bolt

|Name|Description|
|---|---|
|SLACK_SIGNING_SECRET|Slack Signing Secret|
|SLACK_BOT_TOKEN|Slack Bot OAuth Token|
|API_BASE_URL|api-server URL|





