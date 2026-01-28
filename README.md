# oracleDB-connection-mcp-server
Oracle Database connection MCP server with JSON-RPC 2.0 protocol support
# 1) 推奨プロジェクト構成（Ant用）

```
oracle-mcp-ant/
  build.xml
src/
    com/example/mcp/Main.java
  lib/
    jackson-core-2.17.2.jar
    jackson-annotations-2.17.2.jar
    jackson-databind-2.17.2.jar
    HikariCP-5.1.0.jar
    ojdbc11-23.x.x.jar
    （必要ならslf4j-api.jar等）
  dist/
  build/
```

- `src/` はソース
- `lib/` は依存jar置き場（Antは基本これ）
- `build/` はコンパイル成果物
- `dist/` は配布jar

---

# 2) Ant `build.xml`（そのまま使えるやつ）

fat jar（依存もまとめた1本）を作る版。

※ fat jar しないと、VS Codeから起動した時に classpath 地獄になりやすい。

[Antソース](https://www.notion.so/Ant-2f6987fd08548022bdfef36f37568829?pvs=21)

### メモ（fat jarの罠）

- `zipgroupfileset` で依存Jarを全部突っ込めるけど、**META-INFの署名ファイルが衝突**しやすい。
- 上の例では雑に回避しようとしてるけど、ガチるなら `jarjar` や `one-jar` みたいな手段を使う。とはいえまずは動かせ。

[Javaソース](https://www.notion.so/Java-2f6987fd085480b386d3f7226e2852b6?pvs=21)

# 4) ビルドと実行

## ビルド

```bash
ant clean dist
```

成果物：

```
dist/oracle-mcp-server.jar
```

## 単体起動（VS Codeより先に必ずやれ）

```bash
ORACLE_URL="jdbc:oracle:thin:@//localhost:1521/ORCLPDB1" \
ORACLE_USER="APP" \
ORACLE_PASSWORD="*****" \
java -jar dist/oracle-mcp-server.jar

```

やり方は単純で、`.vscode/mcp.json` をこうする：

```json
{
  "servers": {
    "oracle-mcp": {
      "command": "C:\\Program Files\\Java\\jdk-17\\bin\\java.exe", // jdk17のパスを指定
      "args": ["-jar", "oracle-mcp-server.jarのパス"],
      "env": {
        "ORACLE_URL": "jdbc:oracle:thin:@MYDB",  //@の後ろにtns名
        "ORACLE_USER": "hlc01",
        "ORACLE_PASSWORD": "hlc01",
        "TNS_ADMIN": "C:\\oracle\\network\\admin"  //tnsnames.oraパスを書く
      }
    }
  }
}
```

- **PATHが1.8でも関係ない**。VS Codeがこの `java.exe` を直接叩く。
- Windowsならこの方式が一番事故らない。
