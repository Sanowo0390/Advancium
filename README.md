# Advancium 1.0.0 — Minecraft Java 26.3

MintyMC向けのPaper / Purpur実績管理プラグインです。**DiscordSRVの実績解除通知はそのまま**使用し、このプラグインからDiscordに個別の実績解除通知は送信しません。

> **検証状況:** 現時点ではPaper/Purpur 26.3実機での起動・動作は未検証です。正式なビルドはJava 25＋Gradleの環境、またはGitHub Actionsで実行してください。テスト用サーバーで検証する前に本番環境へ導入しないでください。

## 機能

- Minecraft 26.3が読み込んだ進捗を走査して総数を自動計算（デフォルトは`minecraft`名前空間＋表示あり）。レシピの解放を除外し、隠し進捗は表示がある限り含めます。
- 進捗達成時にプレイヤー本人へ **現在の解除数 / 全実績数（%）** をチャット表示。
- `/adv` — 自分の達成状況。
- `/adv <プレイヤー名>` — 指定プレイヤーの状況。
- `/adv top [ページ]` — 実績解除ランキング。
- `/adv reload` — 設定・対象実績の再読込（管理者）。
- `/adv sync` — オフラインファイルの再インポート（管理者）。
- PlaceholderAPI: `%advancium_done%`、`%advancium_total%`、`%advancium_percent%`、`%advancium_rank%`。
- `world/players/advancements/<uuid>.json`を起動時に読み込み。旧`world/advancements/`にも対応。
- SQLiteまたはMariaDBに保存。ランキングは同じUUID・進捗IDの重複を排除。
- Discord WebhookにランキングTOP10などをEmbedで投稿。保存したメッセージIDを使い、**以降は既存のメッセージをPATCH編集**。
- DB処理やHTTP通信は専用I/Oスレッドで行い、Minecraftのメインスレッドをブロックしません。

## インストール（単独バックエンド）

1. **PaperまたはPurpur 26.3** を使用してください。Java 25が必要です。
2. GitHub Actionsのビルド成功時に生成される正式な`Advancium-1.0.0.jar`を対象サーバーの`plugins/`に入れ、サーバーを起動します。Paperの`plugin.yml`ライブラリ取得でSQLite JDBCとMariaDB JDBCの2つを初回取得します（初回起動時にMavenへの通信が必要）。
3. `plugins/Advancium/config.yml`が生成されます。`server-id`をバックエンドごとの固有IDにします。単独バックエンドなら`storage.type: sqlite`のままで使えます。
4. Discordの「チャンネル設定 → 連携サービス → ウェブフック」からURLを取得し、設定ファイルに記入、`webhook.enabled: true`にします。
5. サーバーを再起動し、`/adv`・`/adv top`・DiscordのEmbedを確認します。

`plugins/Advancium/webhook-message-id.txt`にメッセージIDが記録されます。**このファイルを消すと新しいランキング投稿が作られます**。Webhook URLは秘密情報なので公開しないでください。

## 複数のPurpurバックエンド（Velocity配下）

複数サーバーでの共有ランキングには`storage.type: mariadb`を指定し、**全バックエンドのDB接続先を同一**にしてください。各バックエンドで`server-id`を`survival`、`resource`など別々にします。

例えばMariaDBで（パスワードは必ず変更）:

```sql
CREATE DATABASE minty_advancements CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'minty_advancements'@'10.20.30.%' IDENTIFIED BY 'STRONG_RANDOM_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX ON minty_advancements.* TO 'minty_advancements'@'10.20.30.%';
```

IP/ネットワークは実際のDB到達元に合わせてください。例の`10.20.30.%`は環境に応じて制限してから使います。**DBをインターネットに公開しないでください**。

```yaml
server-id: survival
storage:
  type: mariadb
  mariadb:
    host: 10.20.30.100
    port: 3306
    database: minty_advancements
    username: minty_advancements
    password: 'STRONG_RANDOM_PASSWORD'
```

各プレイヤーの進捗は**(server-id, UUID, 実績キー)**として保存されます。ランキングでは各UUIDが解除した**実績キーの集合（UNION）**を数え、例えば同じ`minecraft:story/mine_stone`を2サーバーで解除していても1つとして数えます。

`webhook.enabled: true`は**ランキング投稿を担当するサーバー1台だけ**で設定してください。他のサーバーのWebhook機能は`false`。MariaDBを共有しない各SQLiteデータを自動合算することはできません。

> 注意: 複数バックエンド間で「未解除に戻した」実績も厳密に同期したい場合、他サーバーに残っている解除済み状態との和集合となるため、そのサーバー側の記録が残ります。「どこかのバックエンドで達成したことがある実績」を記録する設計です。

## 設定と計算ルール

`advancements.include-custom: false`なら`minecraft`名前空間に絞り、`only-with-display: true`なら表示設定のある進捗だけを集計します（非表示のレシピ解放を除くため）。隠し進捗も表示情報があれば含みます。進捗数は起動したサーバーの登録一覧に基づき、ソースに126などの数字を固定していません。**全バックエンドで同じMinecraftバージョンと集計設定にする必要があります**。

解除率の計算: `解除済み進捗数 ÷ 対象進捗数 × 100`（小数点2桁）。

- ゲーム内の更新: 解除後約1秒、連続解除時はまとめて表示。
- Webhookの更新: 解除後約30秒以内のまとめ処理を基本とし、最短更新間隔で制限。さらに5分に1度同期。
- ランキング再集計: 標準60秒ごと（解除後にも更新）。
- オンライン進捗全件同期: 標準300秒ごと。`/adv sync`で再取込。
- オフラインプレイヤー名: サーバーの`usercache.json`から復元。記録がなければUUIDの先頭8文字で表示し、本人の参加後に名前を更新します。
- 進捗が取り消されると、そのバックエンドの次回スナップショット保存時に反映します。

`/adv reload`は表示・実績対象などを再読込します。**DB接続先、server-idおよび定期タイマー間隔の変更は再起動が必要**です。

## ソースからの正式ビルド

JDK 25とGradleを用意したうえで:

```bash
gradle clean build
```

出力: `build/libs/Advancium-1.0.0.jar`（さらに`-sources.jar`も生成）。依存するPaper API (`26.3.build.178-stable`) と PlaceholderAPI API (`2.11.7`) はビルド時のみ取得し、配布JARには含めません。JDBCドライバーはPaperの`plugin.yml` `libraries`機能でサーバー起動時に取得します。

## 権限

- `advancium.use`: 一般プレイヤー向け（デフォルトtrue）。
- `advancium.admin`: `reload`/`sync`（デフォルトOP）。

## 制限事項

- ソースコードは**Paper 26.3実機試験済みではありません**。本番利用の前にテスト環境で動作を確認してください。
- Java版進捗APIを使用するため、Bedrockプレイヤーの扱いはGeyser/Floodgate環境で個別の検証が必要です。
- データパックにより進捗構成が変わった場合は`/adv reload`で集計対象を再取得してください。
- オフラインファイルの内容は、初回起動時に読み込まれるワールドの進捗に依存します。異なるバックエンドのデータはMariaDB共有で統合します。

## GitHub Actionsでのビルド

`main`にコードをpushすると`.github/workflows/build.yml`がGradleビルドを試行します。成功時にActionsのArtifactsから`Advancium-jar`を取得できます。**CIビルド成功は実サーバーでの動作確認を保証しません。**

## 名称

- プラグイン名: `Advancium`
- メインクラス: `si.mintymc.advancium.Advancium`
- PlaceholderAPI: `%advancium_done%`, `%advancium_total%`, `%advancium_percent%`, `%advancium_rank%`
- 権限: `advancium.use` / `advancium.admin`

旧名 `MintyAdvancements` から名称を変更したため、旧ディレクトリの設定やSQLiteデータは自動移行されません。既存インストールがあれば新しい `plugins/Advancium/` への移行が必要です。
