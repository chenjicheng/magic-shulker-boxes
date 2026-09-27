# 构建与测试

**简体中文** | [English](en/development.md)

本项目是独立的 Java / Fabric 模组，目标版本为 Minecraft 1.21.11。实际收纳发生在逻辑服务端，同一 JAR 可安装到独立服务端或单人客户端。个人设置同步通过独立的客户端入口点可选加载，专用服务端不会解析客户端类。

## 构建

安装 JDK 21 并配置 `JAVA_HOME`，然后在项目根目录执行：

```powershell
.\gradlew.bat build
```

Linux / macOS：

```sh
sh gradlew build
```

Gradle Wrapper 固定为 9.2.1，带有发行包 SHA256 校验。Minecraft、Fabric Loader、Fabric API、Loom 版本固定在 `gradle.properties`。首次构建需要联网下载依赖。

`build` 会执行单元测试和 Minecraft 服务端 GameTest，并在 `build/libs` 输出可安装 JAR 与源码 JAR。测试世界位于 `build` 下的隔离运行目录，不使用现有存档。

## GitHub 构建、发布与文档站

模组名为 **Magic Shulker Boxes**，固定 ID 为 `magic_shulker_boxes`，仓库为 `chenjicheng/magic-shulker-boxes`。ID 符合 [Fabric 元数据规范](https://wiki.fabricmc.net/documentation:fabric_mod_json_spec)。JAR 使用常见的“名称－加载器－版本＋游戏版本”形式：`magic-shulker-boxes-fabric-0.1.0-alpha+mc1.21.11.jar`；这是本项目的命名约定，并非 Fabric 强制文件名。

`gradle.properties` 中的 `mod_version` 是发行版本，首发 `0.1.0-alpha`；Gradle 产物和模组元数据追加 `+mc1.21.11`。`v0.1.0-alpha` 是对应 Git 标签。版本中的预发布标识会使 GitHub Release 标记为 prerelease。项目采用 MIT 许可证。

- **CI**：分支推送、Pull Request 和手动运行，复用 `build.yml`。Java 21 下分别在无 Carpet 和 Carpet 1.4.194 环境构建并运行单元测试、专用服务端 GameTest；Carpet 下载验证固定 SHA256。
- **Release**：仅由 `v*` 标签推送触发，先核对标签与 `mod_version` 一致并通过两个测试环境，再发布测试过的 JAR、源码 JAR 和 `SHA256SUMS`。发行说明读取 `docs/releases/<mod_version>.md`。构建任务只有读取权限，单独的发布任务才有写权限。
- **Documentation**：VitePress 1.6.4、Node 24 与 npm 锁文件。底层 Vite 固定到 6.4.3 以包含安全修复，升级时需复验构建、搜索和预览。PR 只构建校验，`main` 推送将 `docs/.vitepress/dist` 部署到 GitHub Pages，站点基路径为 `/magic-shulker-boxes/`。

发布步骤：修改 `mod_version` 并添加对应双语发行说明，通过本地检查后提交到 `main`。确认 CI 成功，再创建匹配标签并推送，例如：

```sh
git tag -a v0.1.0-alpha -m "Magic Shulker Boxes 0.1.0-alpha"
git push origin v0.1.0-alpha
gh run list --workflow release.yml
```

Release 由 Actions 内的 `gh` 创建，先上传到草稿，资产齐全后公开。不要手工上传本机旧产物；已公开版本不覆盖，用新版本修复。首次仓库设置需将 Pages 的发布来源设为 **GitHub Actions**。无需额外 PAT 或模组站点令牌。

```sh
python -m unittest discover -s scripts -p 'test_*.py' -v
npm ci
npm run docs:build
npm run docs:preview
```

发行校验脚本 `scripts/release.py` 检查标签、元数据、许可证、可选依赖和测试类未被打包，并只选择当前版本的两个产物。VitePress 使用 `docs` 中的公开文档，保留死链接检查；缓存、日志、本地存档和构建目录不提交。完整使用说明以 `docs/guide.md` 和 `docs/en/guide.md` 为单一来源。

## 测试命令

### 原理图取料协议

`BoxOrder` 对允许使用的盒子按每格数量/堆叠上限之和排序，同分时保留栏位顺序。收纳在原有盒子类别内部先填更满的；`RefillSearch` 和服务端 `RefillNetwork` 从较空的盒子开始取料，无法安全取出时尝试后续来源。27 个非满堆叠不视作满盒，非标准大容器仍跳过。

`RefillSearch` 只读查找组件完全匹配的材料。可选 Mixin 包围 Litematica `WorldUtils.doEasyPlaceAction` 和 `EasyPlaceUtils.handleEasyPlace`，仅在轻松放置调用 `InventoryUtils.schematicWorldPickBlock` 时触发取料，普通选取方块不受影响。`LitematicaMixinPlugin` 在未安装 Litematica 时跳过这些客户端目标；构建和专用服务端不依赖其 JAR。

`refill_v2` 请求包含盒子栏位、盒内栏位、有长度上限的物品 ID 和 64 字符 SHA-256 指纹。`ItemFingerprint` 使用原版 `HashOps`、物品编解码器及注册表上下文计算与数量无关的规范化指纹；无法编码或含临时组件时拒绝，不接收完整客户端物品数据。旧 `refill_v1` 不再注册。`RefillNetwork` 在服务端线程检查来源指纹、游戏模式、菜单/光标状态、服务器开关和玩家设置，并重新读取真实物品；每位玩家每 10 个服务端 tick 最多处理一次，重复请求遇到背包已有材料时直接停止。相同盒内组件和数量相同的候选只规划一次，数量不同仍分别尝试。`ShulkerRefill` 先在副本中规划取出、腾栏和拆盒，全部可行后才提交；失败不修改背包。成功只走原版背包同步，失败可发送限频快捷栏提示。

取料不会直接放置方块或绕过 Litematica 的快捷栏保护和放置校验。等待同步期间抑制的是本次缺料产生的通用轻松放置警告；继续按住放置键后，由原有流程选物并放置。

`RefillSearchTest` 对 36 个满盒（972 个格子）执行只读查找，并输出本机中位数/P95 采样；数值不作为跨机器性能阈值。它不代表多人服务器负载或网络延迟。`RefillGameTests` 覆盖实际服务端请求、模式/配置拒绝、入盒关闭时独立取料和物品保护。`ShulkerRefillTest` 覆盖入盒/取料四种开关组合，配置测试覆盖分别继承与服务端限制，`PickupGameTests` 验证取料关闭时真实拾取仍可入盒。

原理图夹具 `tests/schematics/MSB-Refill.litematic` 包含圆石、橡木木板和玻璃。将其复制到测试实例 `schematics`，加载后把放置原点设为 `100,101,100`。使用下表中的取料场景，先观察满背包取料，再验证放置后数量减一；命令创建的镐、16 个空盒及未选中材料均应保留。

仅执行单元测试：

```powershell
.\gradlew.bat test -x runGameTest
```

执行真实服务端拾取测试：

```powershell
.\gradlew.bat runGameTest
```

加载 Carpet 1.21.11 发布 JAR 进行兼容测试：

```powershell
.\gradlew.bat build -PcarpetJar="C:/path/to/fabric-carpet-1.21.11-1.4.194+v251223.jar"
```

`carpetJar` 只追加本地测试/开发运行依赖，不将 Carpet 打包进模组。游戏测试在每个用例执行期间将 Carpet 的实际堆叠上限设为 64，并验证其 Mixin 已生效；用例结束后恢复原设置。

单元测试报告：`build/reports/tests/test/index.html`。GameTest 的执行结果会输出到控制台和测试运行目录日志。每个拾取用例使用实际的 Minecraft 服务端玩家、背包和物品实体；覆盖原版优先、部分拾取、拾取归属/延迟、堆叠无空栏回退、主动收纳拆分、禁用配置等路径。

### 游戏界面复测

`tests/manual-datapack` 是 1.21.11 的测试数据包。将其内容复制到**专门新建的测试存档**的 `datapacks/msb-manual-tests` 下，进入世界后执行 `/reload`。需要允许命令、安装 Carpet。场景会清空当前玩家背包、删除附近掉落物并设置和平难度，请勿用于正式存档。

| 命令 | 场景与预期 |
| --- | --- |
| `/function msb_test:refill` | 满背包、16 个 Carpet 空盒与材料盒；把测试原理图放在 `100,101,100`，轻松放置应取料、腾栏并保留镐和堆叠盒 |
| `/function msb_test:refill_blocked` | 重置取料场景，禁止腾栏；应提示失败且物品不变，需先允许个人设置 |
| `/function msb_test:refill_silent` | 在失败场景关闭提示；再次尝试不出现取料提示 |
| `/function msb_test:refill_disabled` | 关闭取料并开启提示；尝试时说明功能已禁用 |
| `/function msb_test:refill_enabled` | 清除个人覆盖并恢复取料场景 |
| `/function msb_test:matching` | 同类蓝盒 63 个圆石变成 64＋4，前面的空盒和杂物盒不变 |
| `/function msb_test:mixed` | 默认优先复用已有红色杂物盒，16 个堆叠空盒不变 |
| `/function msb_test:partial` | 蓝盒只剩 1 个容量，拾取 5 个后地面留下 4 个 |
| `/function msb_test:split` | 先设置 `onlyWhenInventoryFull=false` 并重启；16 个命名蓝盒变成 15 个空盒与 1 个装有 5 个圆石的盒子 |
| `/function msb_test:auto_space` | 无空栏，3 个石头腾栏后与圆石、砂砾一起进入一个新盒；盒子数量为 15＋1 |
| `/function msb_test:fallback` | `allowOtherSingleTypeBoxes=false` 时圆石留地；设为 `true` 并重启后可进入仅含泥土的盒子 |

`auto_space` 可分别在 `MOVE_TO_BOX`、`DROP_AND_PICKUP` 下运行；默认设置下只拆一个盒子，主背包第一栏最终包含石头 3、圆石 5、砂砾 7。`DISABLED` 时盒子保持 16，掉落物留地。测试配置修改后须重启，结束后恢复所需配置。

## 代码结构与约束

| 文件 | 职责 |
| --- | --- |
| `src/main/java/dev/magicshulkerboxes/MagicShulkerBoxes.java` | 加载配置；失败时同时禁用自动入盒与取料 |
| `src/main/java/dev/magicshulkerboxes/ConfigFile.java` | 严格验证与首次创建 JSON 配置 |
| `src/main/java/dev/magicshulkerboxes/StorageConfig.java` | 配置字段与默认值 |
| `src/main/java/dev/magicshulkerboxes/ShulkerStorage.java` | 盒子分类、顺序、容量和拆分事务 |
| `src/main/java/dev/magicshulkerboxes/PickupRelocation.java` | 丢出后立即回收的目标预留与防递归处理 |
| `src/main/java/dev/magicshulkerboxes/ServerConfig.java` | 服务端个人设置策略，默认禁止个人覆盖 |
| `src/main/java/dev/magicshulkerboxes/PlayerSettingsStore.java` | 各存档按 UUID 保存个人覆盖项，继承服务端默认 |
| `src/main/java/dev/magicshulkerboxes/SettingsCommands.java` | 玩家命令与管理员权限检查 |
| `src/main/java/dev/magicshulkerboxes/SettingsNetwork.java` | 策略通知、个人设置校验和可选同步 |
| `src/main/java/dev/magicshulkerboxes/client/MagicShulkerBoxesClient.java` | 仅物理客户端加载的本地配置同步 |
| `src/main/java/dev/magicshulkerboxes/Messages.java` | 中英文资源与无客户端模组时的文本回退 |
| `src/main/java/dev/magicshulkerboxes/mixin/ItemEntityMixin.java` | 包装 `ItemEntity.playerTouch` 中的 `Inventory.add` 调用 |
| `src/test/java/dev/magicshulkerboxes` | Minecraft 注册表环境下的配置与收纳单元测试 |
| `src/gametest/java/dev/magicshulkerboxes` | 实际服务端拾取路径与可选 Carpet 兼容测试 |

Mixin 注入点位于原版服务端、拾取延迟及所有者检查之后。它保留原版拾取动画、统计和实体移除流程，不拦截通用 `Inventory.add`，因此不会意外影响合成或容器交互。

收纳先在内容副本上计算实际可接收数量，成功后才提交一个盒子的内容并扣除输入数量。堆叠源盒只减 1，其余盒子的内容不变；没有可接收容量时不拆盒、不占空栏。超过原版 27 栏的非标准潜影盒数据会整体跳过，以免截断其他模组的数据。

腾栏事务先为被移动栏位的全部物品预留容量，再计算可接收的掉落物数量。丢出模式只有世界接纳了掉落实体才提交背包变更；同步回收只允许写入刚预留的那个单盒，目标被替换就保留掉落物。临时预留在 `finally` 清理；未收回的实体带有禁止再次腾栏的持久标记。真实 GameTest 覆盖两种模式、非满组、连续杂物收纳及重复触碰不循环腾栏。

正常拾取仍通过原版背包同步机制更新客户端。可选的个人设置使用 `policy_v1` 和 `preferences_v1` 通道；发送前检查对端是否支持。Fabric 对象消息处理器在游戏主线程执行。消息只含最多 4096 字符的配置 JSON，不包含目标 UUID；身份由实际连接确定。服务端检查开关、字段白名单、类型、枚举和大小，每名玩家最多每 20 tick 接受一次网络更新。纯服务端玩家不需要这些通道。

有效配置先根据 `allowPlayerSettings` 选择统一设置或玩家逐字段覆盖，然后分别应用服务端的入盒（`enabled`）与取料（`schematicRefill`）限制。`ConfigFile.apply` 为两端共用的合并逻辑；关闭入盒不会跳过玩家取料偏好，任何一项服务端限制都不能被个人开启覆盖。个人文件在存档中按 UUID 隔离并缓存，写入使用临时文件与原子替换；非法个人文件不被静默覆盖，读取失败时回退服务端配置并记录日志。重载时先验证新配置，成功后替换并清缓存。

测试覆盖服务端开关立即影响真实拾取、普通玩家只改自己、管理员实时开启/撤销策略、恶意策略字段拒绝、玩家间隔离与重启恢复、消息长度及中英文键/格式占位符一致。中英文资源位于 `assets/magic_shulker_boxes/lang`；`translatableWithFallback` 让没有安装客户端模组的玩家也能看到按其上报语言生成的文本。

其他模组若取消整个拾取流程，仍可阻止本模组；修改了容器结构或拾取路径的其他模组需要另行兼容验证。

## 可选 GUI 集成

`ModMenuIntegration` 提供配置入口，检查 YACL 是否加载后才引用 `SettingsGui`。GUI 库使用 `modCompileOnly`，不会打包进本模组；开发启动可加 `-PwithConfigGui`。默认 GameTest 不加载 GUI 依赖，以检查纯服务端兼容。

YACL 绑定只操作 `SettingsDraft` 的副本；个人布尔字段是三态，继承会删除键。`SettingsSession` 用连接与策略修订号隔离打开的编辑器和待确认保存；超时保留请求号并发起恢复查询，重复或旧连接回复不能写入。`PreferenceSync` 在发送保存前写入服务器地址/存档路径与玩家 UUID 对应的哈希文件名恢复标记，位于 `config/magic_shulker_boxes-recovery/`；标记不包含设置值或明文地址。收到确认后先更新内存快照，再写个人文件并清除标记。失败时内存仍跟随服务端，重连/进程重启遇到标记时先查询，不自动上传旧文件。`ClientSettings` 协调通知、超时和本地服务端提交。

`EditorNetwork` 使用 `editor_state_v1`（策略和默认值）、`editor_save_v1`（请求号和覆盖项）、`editor_query_v1`（只读恢复查询请求号）及 `editor_result_v1`（对应确认、快照或拒绝）。JSON 上限 4096 字符，身份只取连接玩家；服务端复核保存策略，对每位玩家的保存和查询分别按 20 tick 限流。查询只能读取本人偏好，即使策略已锁定也不修改数据。旧同步通道保留；新版 GUI 保存要求对端支持查询。GUI 无管理员网络写入通道；本机房主的统一配置写入在集成服务端线程执行，先比较草稿基线以避免覆盖外部修改。

`RefillRegressionGameTests` 覆盖请求途中改名、未变化的改名材料、保存超时后查询、玩家隔离及限流，以及满背包只取一个时的重复失败规划。测试在支持线程分配计数的 JVM 上限制该夹具每请求分配低于 4 MiB，同时记录耗时；不使用机器相关的耗时阈值。`PreferenceSyncTest` 注入本地文件替换失败并验证内存状态、恢复标记和重启行为。

验收除单元测试和 Carpet GameTest 外，还应检查 Mod Menu 主菜单入口、YACL 中英文布局、保存/取消/继承、服务端锁定与撤销、单人主机统一配置。`SettingsEditorTest` 覆盖草稿隔离、继承和旧确认拒绝；GUI 保存 GameTest 验证实际玩家有效配置、策略字段拒绝和明确限流。

## 官方参考

- [Mod Menu 官方集成接口](https://github.com/TerraformersMC/ModMenu#java-api)
- [YACL 官方文档](https://docs.isxander.dev/yet-another-config-lib)

- [Fabric 1.21.11 开发环境与 Java 21](https://docs.fabricmc.net/1.21.11/develop/getting-started/setting-up)
- [Fabric 1.21.11 版本说明](https://fabricmc.net/2025/12/05/12111.html)
- [Fabric Loader JUnit 与 GameTest](https://docs.fabricmc.net/1.21.11/develop/automatic-testing)
- [Fabric 1.21.11 命令与权限](https://docs.fabricmc.net/1.21.11/develop/commands/basics)
- [Fabric 1.21.11 网络同步](https://docs.fabricmc.net/1.21.11/develop/networking)
- [Carpet 1.21.11 潜影盒堆叠实现](https://github.com/gnembon/fabric-carpet/blob/1.21.11/src/main/java/carpet/mixins/ItemStack_stackableShulkerBoxesMixin.java)
- [Carpet 1.4.194 官方发布](https://github.com/gnembon/fabric-carpet/releases/tag/1.4.194)
