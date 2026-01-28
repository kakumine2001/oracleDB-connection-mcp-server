package com.example.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * MCPサーバー実装: Oracle DatabaseへのSELECTクエリ実行エンドポイント
 * 
 * JSON-RPC 2.0プロトコルで通信し、標準入出力を使用。
 * クライアントからのリクエストを受け取り、Oracleへクエリを実行して結果を返す。
 */
public class Main {

  private static final ObjectMapper M = new ObjectMapper();

  // MCPサーバーのメタデータ
  private static final String SERVER_NAME = "oracle-mcp";
  private static final String SERVER_VERSION = "0.1.0";

  private final BufferedReader in;   // 標準入力（クライアントからのリクエスト）
  private final PrintWriter out;     // 標準出力（クライアントへのレスポンス）
  private final PrintWriter err;     // 標準エラー出力（ログ）

  private final HikariDataSource ds; // Oracleコネクションプール

  /**
   * コンストラクタ: I/Oストリームの初期化とOracleコネクション確立
   * 
   * 環境変数から接続情報を取得してHikariCPでコネクションプールを設定。
   * 起動ログをstderrに出力。
   */
  public Main() {
    this.in = new BufferedReader(new InputStreamReader(System.in));
    this.out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)), true);
    this.err = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.err)), true);

    // 環境変数から接続情報を必須取得
    // 不足していれば IllegalStateException をスロー
    String url  = mustEnv("ORACLE_URL");
    String user = mustEnv("ORACLE_USER");
    String pass = mustEnv("ORACLE_PASSWORD");

    // HikariCP設定: コネクションプール管理
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(url);
    cfg.setUsername(user);
    cfg.setPassword(pass);
    cfg.setMaximumPoolSize(3);                              // 最大3つの同時接続
    cfg.setConnectionTimeout(TimeUnit.SECONDS.toMillis(5)); // 取得時に5秒待機
    cfg.setValidationTimeout(TimeUnit.SECONDS.toMillis(3)); // バリデーション3秒
    cfg.setIdleTimeout(TimeUnit.MINUTES.toMillis(1));       // 1分でアイドル接続を閉じる
    cfg.setMaxLifetime(TimeUnit.MINUTES.toMillis(5));       // 接続の最大生存時間5分

    this.ds = new HikariDataSource(cfg);

    err.println(ts() + " booted " + SERVER_NAME + " " + SERVER_VERSION);
  }

  public static void main(String[] args) throws Exception {
    new Main().loop();
  }

  /**
   * メインループ: 標準入力からリクエストを読み込んで処理し続ける
   * 
   * 1行ごとにJSONリクエストを期待。無効なJSONはスキップしてログに出力。
   * EOFまで続行（標準入力が閉じられるまで）。
   */
  private void loop() throws Exception {
    String line;
    while ((line = in.readLine()) != null) {
      line = line.trim();
      if (line.isEmpty()) continue; // 空行スキップ

      JsonNode req;
      try {
        req = M.readTree(line);
      } catch (Exception e) {
        // JSON解析失敗時はログに出力して次のリクエストを待つ
        err.println(ts() + " invalid json: " + e.getMessage());
        continue;
      }

      handle(req);
    }
  }

  /**
   * JSON-RPC 2.0リクエストハンドラ
   * 
   * リクエスト形式:
   * {
   *   "jsonrpc": "2.0",
   *   "id": <数値 | 文字列 | null>,
   *   "method": <メソッド名>,
   *   "params": <パラメータオブジェクト>
   * }
   * 
   * @param req パースされたJSONリクエスト
   */
  private void handle(JsonNode req) {
    String jsonrpc = text(req, "jsonrpc");
    JsonNode id = req.get("id"); // ID は数値、文字列、null のいずれかあり得る
    String method = text(req, "method");
    JsonNode params = req.get("params");

    // JSON-RPC 2.0の検証: jsonrpc="2.0" と method が必須
    if (!"2.0".equals(jsonrpc) || method == null) {
      // idがあれば、エラーレスポンスを返す
      // (idがnullなら notification として返却不要)
      if (id != null && !id.isNull()) {
        sendError(id, -32600, "Invalid Request");
      }
      return;
    }

    try {
      // メソッド別処理
      switch (method) {
        case "initialize" -> sendResult(id, initializeResult());
        case "tools/list" -> sendResult(id, toolsListResult());
        case "tools/call" -> sendResult(id, toolsCallResult(params));
        default -> sendError(id, -32601, "Method not found: " + method);
      }
    } catch (Exception e) {
      // ハンドラー内の予期しない例外
      err.println(ts() + " handler error: " + e);
      sendError(id, -32000, "Server error: " + e.getMessage());
    }
  }

  /**
   * initialize メソッドの応答を構築
   * 
   * MCPクライアントがサーバーを初期化するときに呼ばれる。
   * サーバー情報（名前、バージョン）とケーパビリティ（対応機能）を通知。
   * 
   * @return initialize結果オブジェクト
   */
  private ObjectNode initializeResult() {
    ObjectNode res = M.createObjectNode();
    
    // serverInfo: 基本情報
    ObjectNode serverInfo = res.putObject("serverInfo");
    serverInfo.put("name", SERVER_NAME);
    serverInfo.put("version", SERVER_VERSION);

    // capabilities: サーバーが対応している機能
    // tools が空オブジェクトでも、存在することを示す
    ObjectNode caps = res.putObject("capabilities");
    caps.putObject("tools"); // tools 機能をサポート

    return res;
  }

  /**
   * tools/list メソッドの応答を構築
   * 
   * サーバーが提供する全ツール一覧をスキーマ付きで返す。
   * MCPクライアントはこれを参照して、ユーザーに選択肢を提示。
   * 
   * @return ツール一覧（配列）
   */
  private ObjectNode toolsListResult() {
    ObjectNode res = M.createObjectNode();
    ArrayNode tools = res.putArray("tools");

    // ツール1: oracle_select (SELECTクエリ実行)
    ObjectNode t = tools.addObject();
    t.put("name", "oracle_select");
    t.put("description", "Run a SELECT query against Oracle (SELECT-only, row-limited).");

    // inputSchema: ツールが受け取るパラメータの定義
    // JSON Schema形式
    ObjectNode inputSchema = t.putObject("inputSchema");
    inputSchema.put("type", "object");
    ObjectNode props = inputSchema.putObject("properties");

    // パラメータ1: sql (必須)
    props.putObject("sql")
        .put("type", "string")
        .put("description", "SELECT SQL to run.");
    
    // パラメータ2: maxRows (オプション、デフォルト200)
    props.putObject("maxRows")
        .put("type", "integer")
        .put("description", "Max rows to return (default 200).");

    // required フィールド: 必須パラメータリスト
    ArrayNode req = inputSchema.putArray("required");
    req.add("sql");

    return res;
  }

  /**
   * tools/call メソッドの応答を構築
   * 
   * クライアントがツール実行を要求したときの処理。
   * ツール名と引数を受け取り、対応するハンドラーを呼び出す。
   * 
   * @param params リクエストパラメータ
   *               { "name": "oracle_select", "arguments": { "sql": "..." } }
   * @return ツール実行結果
   * @throws Exception 不正なツール名など
   */
  private ObjectNode toolsCallResult(JsonNode params) throws Exception {
    String name = params != null ? text(params, "name") : null;
    JsonNode args = params != null ? params.get("arguments") : null;

    if (name == null) throw new IllegalArgumentException("tools/call missing params.name");

    // ツール名でディスパッチ
    return switch (name) {
      case "oracle_select" -> oracleSelect(args);
      default -> throw new IllegalArgumentException("Unknown tool: " + name);
    };
  }

  /**
   * oracle_select ツール実装
   * 
   * クライアント指定のSQLクエリをOracleで実行。
   * SELECTのみ許可し、破壊的操作（DELETE, UPDATE等）は拒否。
   * 実行結果をJSON形式で返す。
   * 
   * セキュリティ対策:
   * - SELECT で始まるかチェック
   * - 禁止トークン（DELETE, UPDATE等）を検出
   * - クエリ実行時間を5秒でタイムアウト
   * - 返却行数を maxRows で制限
   * 
   * @param args ツール引数
   *            { "sql": "SELECT ...", "maxRows": 200 }
   * @return クエリ実行結果（カラムと行データ）
   * @throws Exception SQL実行エラーやセキュリティ違反
   */
  private ObjectNode oracleSelect(JsonNode args) throws Exception {
    if (args == null) throw new IllegalArgumentException("oracle_select missing arguments");

    // パラメータ抽出
    String sql = text(args, "sql");
    int maxRows = intOr(args, "maxRows", 200);

    // === セキュリティチェック ===
    // (簡易的な実装; 本格運用ならSQLパーサを導入推奨)
    
    if (sql == null) throw new IllegalArgumentException("sql is required");
    String norm = sql.trim().toLowerCase(Locale.ROOT);

    // チェック1: SELECTで始まることを確認
    if (!norm.startsWith("select")) {
      throw new SecurityException("Only SELECT is allowed");
    }
    
    // チェック2: 破壊的操作のトークンを検出
    // (空白で囲むことで部分一致を避ける; 例: "address" を "delete" として誤検知しない)
    List<String> banned = List.of(
        " delete ", " update ", " insert ", " merge ",
        " drop ", " truncate ", " alter ",
        " grant ", " revoke ", " begin ", " declare ", ";"
    );
    for (String b : banned) {
      if (norm.contains(b)) throw new SecurityException("Forbidden token detected");
    }

    // === クエリ実行 ===
    
    ArrayNode rows = M.createArrayNode();     // 結果行を格納
    ArrayNode columns = M.createArrayNode();  // カラム名を格納

    try (Connection c = ds.getConnection();
         Statement st = c.createStatement()) {

      st.setQueryTimeout(5);     // 5秒でタイムアウト
      st.setMaxRows(maxRows);    // 返却行数を制限

      try (ResultSet rs = st.executeQuery(sql)) {
        ResultSetMetaData md = rs.getMetaData();
        int cc = md.getColumnCount();

        // カラム情報を抽出
        for (int i = 1; i <= cc; i++) {
          columns.add(md.getColumnLabel(i));
        }

        // 各行をJSONオブジェクトに変換
        while (rs.next()) {
          ObjectNode row = M.createObjectNode();
          for (int i = 1; i <= cc; i++) {
            String col = md.getColumnLabel(i);
            Object v = rs.getObject(i);
            if (v == null) {
              row.putNull(col);
            } else {
              // すべて文字列化して格納（型情報は失う）
              row.put(col, String.valueOf(v));
            }
          }
          rows.add(row);
        }
      }
    }

    // === 結果をMCP形式でパッケージ化 ===
    // MCPのツール戻り値は "content" 配列で返すのが標準
    // (各要素は type と text/image/etc を持つオブジェクト)
    
    ObjectNode res = M.createObjectNode();
    ArrayNode content = res.putArray("content");

    ObjectNode item = content.addObject();
    item.put("type", "text");
    
    // ペイロード: カラムと行データとカウント
    ObjectNode payload = M.createObjectNode();
    payload.set("columns", columns);
    payload.set("rows", rows);
    payload.put("rowCount", rows.size());

    // ペイロードを整形したJSON文字列として格納
    item.put("text", M.writerWithDefaultPrettyPrinter().writeValueAsString(payload));

    return res;
  }

  // ========== JSON-RPC レスポンスヘルパー ==========

  /**
   * 成功結果をレスポンスとして返す
   * 
   * idが null の場合は notification 扱いで返却しない。
   * 
   * @param id リクエストID
   * @param result 結果オブジェクト
   */
  private void sendResult(JsonNode id, ObjectNode result) {
    if (id == null || id.isNull()) return; // notification なら返却スキップ
    
    ObjectNode resp = M.createObjectNode();
    resp.put("jsonrpc", "2.0");
    resp.set("id", id);
    resp.set("result", result);
    
    out.println(resp.toString());
  }

  /**
   * エラーをレスポンスとして返す
   * 
   * JSON-RPC 2.0のエラーフォーマットに準拠。
   * idが null の場合は notification 扱いで返却しない。
   * 
   * @param id リクエストID
   * @param code エラーコード（JSON-RPC標準: -32700～-32600、-32000～-32099）
   * @param message エラーメッセージ
   */
  private void sendError(JsonNode id, int code, String message) {
    if (id == null || id.isNull()) return;
    
    ObjectNode resp = M.createObjectNode();
    resp.put("jsonrpc", "2.0");
    resp.set("id", id);

    ObjectNode errObj = resp.putObject("error");
    errObj.put("code", code);
    errObj.put("message", message);

    out.println(resp.toString());
  }

  // ========== ユーティリティメソッド ==========

  /**
   * JsonNode からテキスト値を安全に抽出
   * 
   * @param node 対象ノード（nullでも安全）
   * @param field フィールド名
   * @return テキスト値、存在しなければ null
   */
  private static String text(JsonNode node, String field) {
    if (node == null) return null;
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) return null;
    return v.asText();
  }

  /**
   * JsonNode から整数値を安全に抽出（デフォルト値付き）
   * 
   * @param node 対象ノード
   * @param field フィールド名
   * @param def デフォルト値（フィールド不在や非整数時）
   * @return 整数値、またはデフォルト値
   */
  private static int intOr(JsonNode node, String field, int def) {
    if (node == null) return def;
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) return def;
    return v.asInt(def);
  }

  /**
   * 環境変数を必須取得
   * 
   * @param key 環境変数名
   * @return 環境変数の値
   * @throws IllegalStateException 環境変数が不在または空白の場合
   */
  private static String mustEnv(String key) {
    String v = System.getenv(key);
    if (v == null || v.isBlank()) {
      throw new IllegalStateException("Missing env: " + key);
    }
    return v;
  }

  /**
   * ISO 8601形式のタイムスタンプを取得
   * 
   * @return 現在時刻（例: 2026-01-28T12:34:56.789Z）
   */
  private static String ts() {
    return Instant.now().toString();
  }
}
