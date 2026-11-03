# AiChatBuddy 1.6.0-mcp — 汎用MCPクライアント

## 構成

AiChatBuddyがGoogle AI StudioのGemma/Geminiへ直接接続し，モデルが選択したMCPツールをプラグイン自身が実行します．BlueMap AI Gatewayの `/v1/chat/completions` は不要です．既存の `prompt-template`，Web検索，Wiki，memoryは維持できます．

```text
Youer / AiChatBuddy ── generateContent ── Google AI Studio / Gemma
       │
       └── MCP tools/list・tools/call ── BlueMap MCP / 他のMCPサーバー
```

## 対応環境と導入

JARはJava 21，Paper API 1.21.1に対してビルドしています．Youer 1.21.1でBukkit/Paperプラグインとして使用する構成を対象とします．実Youerでの導入確認とGoogle APIへの有料・認証付き実通信は利用環境で行ってください．

1. `plugins/AiChatBuddy/` と旧JARをバックアップします．
2. Minecraft/Youerを停止します．旧AiChatBuddy JARをpluginsディレクトリの外へ移動し，`AiChatBuddy-1.6.0-mcp.jar`をpluginsへ配置します．新旧のJARを同時配置しないでください．
3. 既存の `plugins/AiChatBuddy/config.yml` を編集します．`prompt-template` とGoogle APIキーを消さず，`provider: gemini`へ戻します．
4. `examples/bluemap-mcp.yml` の `tools.mcp` を既存の `tools:` に統合します．既存ファイルを例で丸ごと置換しないでください．
5. `token`へBlueMap MCP側 `.env` の `MCP_TOKEN` の値を設定します．`GATEWAY_TOKEN`ではありません．
6. Youerを起動します．JARを交換した場合は `/aichat reload` だけでは更新されません．設定のみ変更したときは `/aichat reload` で再接続できます．

Google接続は従来の `providers.gemini.api-key` と `providers.gemini.api-url` を使います．現在のGemmaモデルURLはそのまま維持できます．`providers.ollama`は残しても選択されません．

BlueMap MCPは同じDockerネットワーク `minecraft-youer-01_default` 上の `http://bluemap-mcp:8765/mcp` を使用します．MCP本体は稼働させたまま，Gatewayを他で使っていなければ `GATEWAY_ENABLED=false` にできます．その場合はMCPコンテナーを再作成して反映します．

## 確認コマンド

管理者またはコンソールで実行します．

```text
/aichat mcp status
/aichat mcp tools bluemap
/aichat mcp refresh
/aichat mcp call bluemap bm_vehicles {"loaded_only":false,"limit":1}
/aichat mcp call bluemap bm_vehicles {"loaded_only":true,"limit":1}
```

`call`はAIを通さず，本物のMCPツールを呼び出す読み取り専用診断です．Googleの利用枠を消費しません．正常時の例は `bluemap: connected protocol=... tools=14/14` です．サーバー側の有効機能や許可リストにより件数は変わります．

その後，ゲーム内で質問します．

```text
!ask 自己紹介をして
!ask Sableの乗り物の登録総数とロード中の台数を教えて
!ask THE-EUZHという名前の乗り物の場所を調べて
```

モデルへ渡すツール名は `mcp_bluemap_bm_vehicles` のようにサーバー名で名前空間を分けます．元のツール名は説明文に残し，実際のMCP呼び出しでは元の `bm_vehicles` を使用します．長い名前や使用できない文字はハッシュ付きの名前に変換します．

```bash
docker logs --since=5m minecraft-youer01 2>&1 | grep -E '\[MCP\]|Invoking tool|Using Gemini|Provider error'
```

`[MCP] server=bluemap tool=bm_vehicles result=ok` が実行確認になります．AIが台数を答えたことだけをツール実行の証拠にしないでください．

## 汎用サーバーの追加

`tools.mcp.servers` の下に別IDを追加します．サーバーIDは英数字・`_`・`-`で1〜32文字です．接続は起動時と設定再読み込み時にバックグラウンドの専用ワーカーで確立し，Minecraftメインスレッドでネットワークを待ちません．

```yaml
tools:
  mcp:
    enabled: true
    max-tools: 48
    refresh-seconds: 300
    servers:
      reference:
        transport: streamable-http
        url: "https://mcp.example.invalid/mcp"
        token-env: REFERENCE_MCP_TOKEN
        permission: aichatbuddy.mcp.admin
        read-only: true
        allow-tools: [search, lookup]
        read-only-tools: [search, lookup]
      local:
        transport: stdio
        command: /usr/bin/python3
        args: ["-u", "/opt/my_mcp/server.py"]
        working-directory: /opt/my_mcp
        env:
          SERVICE_TOKEN: "${LOCAL_SERVICE_TOKEN}"
        read-only: true
        allow-tools: [search]
        read-only-tools: [search]
```

stdioの実行ファイル・パス・環境変数は**Youerの実行環境内**のものです．管理者が明示した実行ファイルを起動し，暗黙のshell実行はしません．プロセスはMCPサーバーごとに1つで，無効化・再読み込み時に終了します．PATH/HOME等の限定した環境変数と明示設定だけを子プロセスへ渡します．子の標準エラーは秘密情報の漏出防止のため破棄します．

## 設定項目

| 設定 | 既定・動作 |
|---|---|
| `transport` | `streamable-http`，または `stdio` |
| `protocol-version` | `auto`．2026-07-28を検出し，旧サーバーではinitialize方式へ切替 |
| `url` | HTTP/SのMCPエンドポイント．リダイレクトは追跡しません |
| `allow-insecure-http` | false．localhost以外の平文HTTPは管理者の明示許可が必要 |
| `token` / `token-env` | Bearer認証．token-env指定時は環境変数を優先 |
| `headers` | 追加認証ヘッダー等．`${ENV_NAME}`を展開．予約ヘッダーは変更不可 |
| `timeout-seconds` | 20．1〜120秒 |
| `max-response-bytes` | 2MiB．HTTP/SSE/stdioの受信上限 |
| `max-result-chars` | 12000．モデルへ返す結果が超過した場合は切り詰めを明示 |
| `max-tools` | サーバーごと64．全体は `tools.mcp.max-tools` で既定48 |
| `allow-tools` | 名前または `*` パターンによる許可リスト．空なら読み取り認定されたもののみ |
| `deny-tools` | 常に許可より優先 |
| `read-only` | true．読み取りと認定できないツールは公開しません |
| `read-only-tools` | 管理者が読み取り専用と確認した名前のリスト |
| `trust-read-only-hints` | false．true時のみ接続先のreadOnlyHintを信用 |
| `permission` | `aichatbuddy.mcp.use`．ツール一覧と実行時の両方で検査 |
| `write-permission` | `aichatbuddy.mcp.write`．非読み取りツールでは追加検査 |
| `guidance` | 管理者が設定するデータの意味や用途．キャラクター設定は先頭に維持 |

環境変数が未設定の場合は空の認証で接続せず，該当サーバーを無効にします．`token`を文字列で保存する場合はconfig.ymlの読み取り権限を制限してください．HTTPヘッダー・トークン・ツール引数・取得内容をMCP通常ログへ出力しません．

## 権限と書き込み

`aichatbuddy.mcp.use`は既定で全プレイヤー，`aichatbuddy.mcp.admin`と`aichatbuddy.mcp.write`は既定でOPです．機密データを扱うサーバーは `permission: aichatbuddy.mcp.admin` 等に変更してください．

**readOnlyHintはセキュリティ境界ではありません．** 初期設定はヒントを信用せず，BlueMap例では読み取り14ツールを明示列挙しています．読み取り用の認証情報とMCPサーバー側の権限設定を併用してください．

非読み取りツールには `read-only: false`，`allow-tools`への明示登録，プレイヤーのwrite-permissionのすべてが必要です．別の対話式承認UIは実装していないため，自動実行を許可できない操作は有効化しないでください．診断コマンド `call` から書き込みツールは実行できません．

同じプレイヤーの並列質問を抑止し，全体の同時実行数を `max-concurrent-questions` で制限します．ツール実行表示は問い合わせたプレイヤーだけに送信します．最終回答の公開範囲は従来どおり `private-questions` に従います．

## 対応範囲・制限

- MCP toolsの発見・ページ送り・実行に対応します．HTTPではJSON応答とSSE応答の両方を受け取ります．
- MCP 2026-07-28のserver/discover，per-request metadata，Mcp-Method/Name，x-mcp-headerを扱います．
- 旧2025-11-25／2025-06-18／2025-03-26のinitialize・initialized・セッションIDを扱います．2024-11-05はstdioのみ対応します．
- 旧HTTP+SSEの2エンドポイント方式，OAuthの対話ログイン／自動トークン更新，resources/prompts UI，sampling，elicitation，tasks拡張は対象外です．Bearer/API-keyヘッダー等を管理者が設定する形式です．
- ツールリストは定期refreshと管理コマンドで更新します．独立したGET/SSE購読や2026 subscriptions/listenによる常時通知は使用しません．
- SSEが最終RPC応答前に切断された場合はエラーとして扱います．旧プロトコルのLast-Event-IDによる再開は行いません．
- タイムアウトしたtools/callを勝手に再実行しません．失敗前に実処理が完了している可能性があるためです．
- 画像・音声のbase64はテキストモデルへ転送せず，省略したことを結果に記載します．
- リモートサーバーのinstructionsをシステムプロンプトへ自動注入しません．管理者の `guidance` のみを使用します．
- GeminiにはJSON Schemaを `parametersJsonSchema` で渡します．古いエンドポイントでは `providers.gemini.schema-format: openapi` の限定変換を利用できます．
- Geminiのモデルターンを保持し，Function Calling中のthoughtSignatureとfunctionCall IDを次の呼び出しへ返します．推論タグは表示・永続会話履歴から除去します．
- MCPを提示することと，AIモデルが正しく選択し事実に忠実に回答することは別です．`/aichat mcp call`と実行ログで個別に検証できます．Sable専用の強制取得や固定台数の処理は含みません．

## トラブルシューティング

| 状態 | 確認 |
|---|---|
| statusにサーバーがない | `tools.mcp.enabled`，YAML重複，環境変数，起動ログのInvalid server configuration |
| HTTP 401/403 | `MCP_TOKEN`の値，BlueMapのMCP_ALLOWED_HOSTS，ネットワークと認証設定 |
| HTTP 307/308 | 最終的な正しいMCP URLを指定．資格情報保護のためリダイレクトしない設計 |
| tools=0/N | allow-tools/read-only-tools/deny-toolsまたは権限で非公開になっていないか |
| 接続不可 | YouerとMCPが同じDockerネットワークか，MCP本体の待受アドレスか |
| Gemini HTTP 400 | schema-formatと使用モデルのFunction Calling対応を確認 |
| Gemini HTTP 429 | AIサービスの利用枠・レート制限．MCP疎通とは別の問題 |
| tool error後の回答がおかしい | MCPエラーを含む結果をモデルへ返しているので，実行ログと診断callで検証 |

## ビルドと検証

Java 21で `bash gradlew clean build` を実行します．依存JSONライブラリはJARに同梱し，Paper APIは同梱しません．GitHub Actionsでも同じビルドとテストを実行します．

テストは実際のローカルHTTPサーバーとstdio子プロセスを使用し，新旧プロトコル，SSE，認証，セッション，ページ送り，タイムアウト，レスポンス上限，権限，異常時の会話継続，Geminiの履歴形式を確認します．認証付きGoogle APIと実Minecraftサーバーを使うテストは別途必要です．

MCP仕様参照：2026-07-28 basic/transports/streamable-http，server/discover，2025-11-25 basic/lifecycle・server/tools．元リポジトリの著作権・ライセンス条件は引き継ぎ，この変更で新たな包括ライセンスを付与するものではありません．
