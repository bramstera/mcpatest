# Paper with WebSocket Proxy

基于 Paper 的 Minecraft 服务端，集成 VLESS/Trojan WebSocket 代理。

## 使用方法

1. Fork [PaperMC/Paper](https://github.com/PaperMC/Paper)
2. 将本仓库的文件复制到 fork 后的对应位置：
   - `paper-server/patches/sources/net/minecraft/server/dedicated/DedicatedServer.java.patch` (替换)
   - `paper-server/patches/sources/net/minecraft/server/network/WsProxyServer.java.patch` (新增)
   - `.github/workflows/build.yml` (替换)
3. 推送，GitHub Actions 自动构建
4. 从 Actions Artifacts 或 Releases 下载 jar

## 配置

通过环境变量配置（可选）：

```bash
export UUID="your-uuid"      # 默认: 5efabea4-f6d4-91fd-b8f0-17e004c89c60
export WSPATH="custom/path"  # 默认: api/v1/user?token={uuid前8位}&lang=en
java -jar paper.jar
```

## 连接

```
vless://{uuid}@{server}:{port}?type=ws&path=/{wspath}
trojan://{uuid}@{server}:{port}?type=ws&path=/{wspath}
```

WsProxy 使用 `server-port`，Minecraft 实际使用 `server-port + 1`。
