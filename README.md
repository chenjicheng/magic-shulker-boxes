# Magic Shulker Boxes / 魔法潜影盒

**简体中文** | [English](README.en.md)

适用于 **Minecraft Java 1.21.11 · Fabric · Java 21** 的潜影盒自动收纳模组，采用 MIT 许可证。当前版本为 **0.3.1**。

开启拾取自动入盒后，背包满了时把拾取余量依次放入同类专用盒、空盒、杂物盒；可开启其他单类盒兜底。此功能默认关闭。兼容 Carpet 堆叠潜影盒，支持安全拆盒、自动腾栏、零散物品和杂物盒复用。

- 多人服仅服务端安装即可收纳；单人和局域网由房主执行。
- 自动入盒与原理图取料各自独立开关；服务端可开放玩家个人设置，并分别限制这两项功能。
- 可选 Mod Menu + YACL 中英文图形设置。
- 0.2.0-alpha 新增 Litematica 轻松放置自动取料：缺料时从潜影盒补到背包，支持自动腾栏及可关闭的失败提示，需要客户端与服务端都更新。

[下载版本](https://github.com/chenjicheng/magic-shulker-boxes/releases) · [在线使用文档](https://chenjicheng.github.io/magic-shulker-boxes/guide.html) · [开发文档](https://chenjicheng.github.io/magic-shulker-boxes/development.html)

## 安装

安装 `magic-shulker-boxes-fabric-0.3.1+mc1.21.11.jar` 和 Fabric API，使用 Fabric Loader 0.18.4 或更新版。不要安装 `-sources.jar`。升级前移走旧版 JAR，避免重复加载。

升级到 0.3.1 会先备份，再重置所有无版本标记的服务端、玩家及客户端旧配置。拾取入盒默认 `enabled=false`，个人覆盖项清空并继承服务端；后续修改由 `configVersion: 1` 标记保护，不重复重置。使用个人设置时两端都需升级。完整变更和备份位置见 [0.3.1 发布说明](docs/releases/0.3.1.md)。

客户端设置界面另需 [Mod Menu 17.0.1](https://modrinth.com/mod/modmenu/version/17.0.1) 和 [YACL 3.8.2](https://modrinth.com/mod/yacl/version/3.8.2+1.21.11-fabric)；专用服务端无需这两项。Carpet 可选，其堆叠规则需自行开启。

完整配置、收纳顺序和注意事项见[使用文档](docs/guide.md)。开发、测试和 GitHub Actions 发布方式见[开发文档](docs/development.md)。

## 构建

JDK 21 下运行 `./gradlew build`（Windows：`.\gradlew.bat build`）。文档使用 VitePress：`npm ci && npm run docs:build`。

CI 同时检查普通环境和 Carpet 环境；推送 `v*` 标签由 Release 工作流验证、构建并发布。`main` 文档由 Actions 部署到 GitHub Pages。
