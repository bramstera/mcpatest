# Paper with WebSocket Proxy

基于 Paper 的 Minecraft 服务端，集成 VLESS/Trojan WebSocket 代理。

## 使用方法

1. 创建新的 GitHub 仓库
2. 将这些文件上传到仓库
3. 推送后 GitHub Actions 自动构建
4. 在 Actions 页面下载 `paper-ws-proxy.jar`

## 发布 Release

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 配置

```bash
# 可选：自定义 UUID
export UUID="your-uuid-here"

# 可选：自定义路径
export WSPATH="custom-path"

java -jar paper-ws-proxy.jar
```

## 端口说明

- WsProxy 占用 `server-port`（对外暴露）
- Minecraft 使用 `server-port + 1`（内部）

## 默认值

- UUID: `5efabea4-f6d4-91fd-b8f0-17e004c89c60`
- WSPATH: `api/v1/user?token=5efabea4&lang=en`
