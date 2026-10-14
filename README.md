# Advancium

**Advancium** は、Minecraft Java Edition の進捗（実績）を集計し、達成率やランキングを表示する Paper / Purpur 向けプラグインです。Discord Webhook を使ったランキングの自動更新にも対応しています。

## 主な機能

- プレイヤー別の**解除数・達成率・順位**を表示
- 進捗解除時に、本人へ現在の達成率をチャット通知
- ゲーム内ランキング（`/adv top`）
- **Discord Webhook ランキング**：同じメッセージを自動編集して更新
- PlaceholderAPI 対応
- 保存済みのオフラインプレイヤーの進捗を取り込み
- SQLite / MariaDB 対応。複数バックエンドのランキングも統合可能
- サーバーに登録された進捗から集計対象の総数を自動取得

※ Discord への個別の実績解除通知は行いません。必要な場合は DiscordSRV などを利用してください。

## 動作環境

| 項目 | 要件 |
| --- | --- |
| Minecraft | Java Edition 26.3 |
| サーバー | Paper / Purpur |
| Java | 25 |
| PlaceholderAPI | 任意（プレースホルダーを使う場合） |
| データベース | SQLite（標準）または MariaDB |

> **動作確認状況：** GitHub Actions での Java 25 ビルドは成功していますが、Paper / Purpur 実機での起動・動作確認は未実施です。導入前にテストサーバーで確認してください。

## インストール

1. [GitHub Actions](https://github.com/Sanowo0390/Advancium/actions/workflows/build.yml) の成功したビルドから `Advancium-jar` を取得し、ZIP 内の JAR を `plugins/` に配置します。
2. サーバーを起動して `plugins/Advancium/config.yml` を生成します。初回起動時は JDBC ライブラリの取得のためインターネット接続が必要です。
3. 必要に応じてデータベースと Discord Webhook を設定します。
4. サーバーを再起動し、`/adv` と `/adv top` で集計結果を確認します。

単独バックエンドでは初期設定の **SQLite** をそのまま利用できます。

## コマンド

| コマンド | 説明 | 権限 |
| --- | --- | --- |
| `/adv` | 自分の解除数・達成率・順位を確認 | `advancium.use` |
| `/adv <プレイヤー名>` | 指定プレイヤーの進捗を確認 | `advancium.use` |
| `/adv top [ページ]` | ランキングを表示 | `advancium.use` |
| `/adv reload` | 設定と集計対象の進捗を再読み込み | `advancium.admin` |
| `/adv sync` | 保存済み進捗を再取り込み | `advancium.admin` |

`advancium.use` は全員、`advancium.admin` は OP にデフォルトで付与されます。

### PlaceholderAPI

| プレースホルダー | 内容 |
| --- | --- |
| `%advancium_done%` | 解除済み進捗数 |
| `%advancium_total%` | 集計対象の総数 |
| `%advancium_percent%` | 達成率（%付き） |
| `%advancium_rank%` | ランキング順位 |

## 設定

設定ファイル：`plugins/Advancium/config.yml`

全設定項目は [config.yml](src/main/resources/config.yml) を参照してください。

### Discord Webhook

```yaml
webhook:
  enabled: true
  url: 'https://discord.com/api/webhooks/WEBHOOK_ID/WEBHOOK_TOKEN'
  min-update-seconds: 30
  periodic-seconds: 300
  top-size: 10
  title: '🏆 MintyMC 実績ランキング'
```

Discord のチャンネル設定から Webhook URL を作成して設定します。初回はランキングを投稿し、以降は**投稿済みのメッセージを編集**します。

- 更新は短時間に何度も送らず、まとめて処理します。
- 投稿先メッセージの ID は `plugins/Advancium/webhook-message-id.txt` に保存されます。
- **Webhook URL は秘密情報です。** 設定済みの `config.yml` を公開リポジトリにコミットしないでください。

### 複数バックエンドでランキングを共有する場合

Velocity 配下などで複数の Paper / Purpur サーバーを運用する場合は、全バックエンドで**同じ MariaDB** を使用し、`server-id` はサーバーごとに変えてください。

```yaml
server-id: survival

storage:
  type: mariadb
  mariadb:
    host: 127.0.0.1
    port: 3306
    database: minty_advancements
    username: minty_advancements
    password: 'CHANGE_ME'
```

- 例えば別バックエンドでは `server-id: resource` を指定します。
- 同じ UUID が同じ進捗を複数サーバーで解除しても、ランキングでは **1件**として数えます。
- Discord Webhook の投稿は**1台のバックエンドだけ**で有効にしてください。
- 全バックエンドで Minecraft のバージョンと集計設定を揃えてください。
- MariaDB をインターネットへ直接公開しないでください。

## 進捗の集計ルール

達成率は `解除済み進捗数 ÷ 対象進捗数 × 100` で計算し、小数点第2位まで表示します。

デフォルトでは `minecraft` 名前空間のうち、表示情報のある進捗だけを集計します。レシピ解放などは対象外です。データパック等の進捗も含めたい場合は `advancements.include-custom` を変更してください。実績総数は固定値ではなく、起動中のサーバーが認識している進捗から取得します。

保存済みの進捗は起動時に取り込み、オンラインプレイヤーの進捗も定期的に同期します。複数バックエンドでは、それぞれで達成した進捗の**和集合**がランキングに反映されます。

## ビルド

JDK 25 と Gradle を使用します。

```bash
gradle clean build
```

出力先：`build/libs/`

GitHub Actions でも `main` ブランチへの push 時にビルドされ、成功すると Artifacts に JAR がアップロードされます。

