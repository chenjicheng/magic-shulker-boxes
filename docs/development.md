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

## 配置版本与迁移

磁盘 JSON 当前使用 `configVersion: 2`；版本元数据不进入 `StorageConfig`、选项列表、GUI 草稿或网络 JSON。无标记的旧文件沿用 0.3.1 规则：先完整备份到 `.pre-0.3.1.bak`，再重置为默认配置或空个人覆盖项。版本 1 文件则先完整备份到 `.pre-0.3.2.bak`，验证字段后将 `enabled` 改为 `pickupStorageEnabled`，保留所有其他有效值并原子写入版本 2。相同备份可恢复中断的迁移；冲突备份、无效字段、未知版本和无法读取的文件均不覆盖。

统一配置、客户端偏好、服务端玩家偏好的写入分别通过带版本的 `writeServer/writePreferences`；普通 `write` 仅用于恢复标记等非设置文件。写入前也检查旧文件，避免首次读取前的保存跳过备份。客户端启动读取偏好；服务端 `SERVER_STARTED` 扫描现有 UUID 玩家文件，一个文件失败不阻止其他玩家迁移。新版修改保留稀疏继承，后续加载不重置。`pickupStorageEnabled` 默认 `false`，行为测试需要显式开启收纳；手动拾取测试前也需开启世界/服务端配置或个人设置。

`ConfigMigrationTest` 覆盖默认关闭、两代原字节备份、重置与改名、后续持久化、备份冲突/不可用、未来版本及离线玩家；`ConfigMigrationGameTests` 检查实际拾取、新 GUI 保存和旧设置接收器不再注册。迁移不接触物品或世界方块。

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

### 合成取料与真实客户端验证

`CraftingMenuMixin` 将原版 `AbstractCraftingMenu.handlePlacement` 包在一个 `CraftingRecipeSources` 事务中。`ServerPlaceRecipeMixin` 只在这一作用域补充材料统计和原版背包查找失败后的取物，继续复用原版配方选择、布局和批量数量。来源盒自身属于配方材料时不同时读取其内部。旧合成格返背包先按完整组件与容量预检；来源变化在副本中规划，整次放置成功才提交。组件合并或拆盒提交冲突回退库存和合成格，作用域通过 `finally` 清理。

`CraftingResultMixin` 捕获原版取出成品前的输入及配方余留物，原版先完成成品、消耗与余留物处理，然后 `CraftingRefill` 在副本中补足整套耗空格子。Shift 回调可能传入数量为零的旧结果堆叠，以有效原输入配方为依据。取出后再次校验玩家与菜单，避免给已关闭的菜单补货。

没有新增取料网络请求。原版请求校验配方与活动菜单，服务器读取真实物品。`CraftingInventoryClientMixin` 仅为兼容服务器的配方书增加可用材料统计；通过服务器默认值中 `craftRefill` 的存在判断能力，再应用有效设置。只读观察外层数量及容器组件引用变化，触发原版配方书重算。`craftRefill` 不依赖其他补货开关或 IPN，继承现有个人设置策略。

```powershell
.\gradlew.bat runClientGameTest -PcraftClient
```

`CraftingRefillTest` 覆盖多材料原子补货、组件和余留物、满背包与堆叠盒。`CraftingGameTests` 运行真实 2×2/3×3、配方批量、普通/Shift 合成、空间与组件回退、蛋糕空桶及个人覆盖。`CraftingClientGameTests` 使用原版配方点击和物品点击请求，验证配方书盒内统计/刷新、两种网格、连续补货和服务端确认的成品数量；客户端预测完成不代替服务端验收。

### Carpet 假人服务端补货

`magic_shulker_boxes.carpet.mixins.json` 使用 `@Pseudo` 接入可选的 Carpet 类，无编译或发行依赖。`CarpetFakePlayerMixin` 包围 `EntityPlayerMPFake.tick`，覆盖 `super.tick` 中的 Carpet 动作和 `doTick` 中的延迟饮用/进食完成。该覆盖方法在开发环境名为 `tick`、Carpet 发行包名为 `method_5773`，两个选择器共用一个实现。普通玩家没有此入口。

`FakePlayerRefill` 观察双手原始引用与组件副本，只在引用耗空后补货；快捷栏选择改变、未耗空的手持引用（交换双手）和 `CarpetActionPackMixin` 标记的显式 `drop` 都不取物。观察作用域通过 `finally` 清理。服务端在补货前后校验模式、存活、菜单/光标和有效 `carpetRefill`；无需新增补货请求。普通背包优先、盒子较空优先，严格匹配组件，损坏工具仅忽略 `DAMAGE`。复用 `CraftingMaterials` 的副本事务保存余留物和拆盒；规划时预留目标手持栏，避免把拆出的盒子覆盖掉。

新增选项仍使用磁盘 `configVersion: 2`，省略时默认开启，不重置已有配置。严格设置 JSON 字段集合增加 `carpetRefill`，因此个人策略/偏好与 GUI 协议改用 v4，旧 v1/v2/v3 设置接收器不注册，避免向旧客户端发送它无法解析的新选项。原理图 `refill_v3` 和 IPN `restock_v1` 保持各自请求版本；设置 GUI 与同步需要两端同协议构建，假人补货本身由服务端执行。

`FakePlayerRefillTest` 覆盖双手、组件/工具匹配、独立开关、普通背包优先、瓶子保存/回滚、堆叠盒额外空栏及副手来源。`FakePlayerRefillGameTests` 在有 Carpet 时运行发布 JAR 的真实假人及连续 USE：耗空雪球后继续使用、主手/副手、延迟喝药与空瓶保存；另校验真实工具损坏、丢弃/交换、切栏、个人覆盖和不安全状态。无 Carpet 时跳过这些可选场景，普通服务端测试继续执行。

```powershell
.\gradlew.bat build '-PcarpetJar=C:/path/to/fabric-carpet-1.21.11-1.4.194+v251223.jar'
```

### IPN 来源扩展与客户端验证

可选接入固定对照 IPN 2.2.6、libIPN 6.6.3（Fabric 1.21.11）。这两项及 Kotlin 均为编译/测试依赖，不打包进发行 JAR。`magic_shulker_boxes.ipn.mixins.json` 只在客户端安装 IPN 时应用；专用服务端和无 IPN 客户端保留原有行为。

`IpnMonitorMixin` 在 IPN 完成自身触发检查与等待 tick 后、调用 `handle()` 前接入。普通背包候选始终优先。`IpnCandidatesMixin` 仅在作用域受限的第二次查找中，将只读盒内物品交给候选列表；所有筛选与排序仍在 IPN 原始 `findCorrespondingSlot` 执行。虚拟候选编号不会进入点击协议或玩家背包。发行包省略嵌套类元数据，Java 适配器使用其实际二进制类名。

`restock_v1` 包含关联请求 ID、来源盒/内部栏位与数量、目标主手/副手栏位和数量、IPN 可用背包栏位的 27 位掩码，以及来源/目标各自最多 64 字符的组件指纹。空目标使用空指纹。服务端读取真实物品并检查指纹、数量、模式、菜单、光标、有效 `ipnRefill` 和限流；同一请求与每 10 tick 内的后续请求拒绝。取出事务只提交完整规划，腾栏和拆盒均限于掩码中的背包栏位。`restock_result_v1` 返回关联 ID 与结果，物品通过原版背包同步。客户端最长等待 5 秒，IPN 撤销触发、失败或超时后恢复其原有处理。

真实客户端验证（需要可用的图形环境）：

```powershell
.\gradlew.bat runClientGameTest -PwithIpn
.\gradlew.bat runClientGameTest -PclientSmoke
```

`src/ipnTest` 仅在 `withIpn` 时加入测试源，运行 IPN 发布 JAR 的原始匹配器及实际 Mixin。覆盖背包优先、药水效果、名称匹配开关、锁定来源、禁用补货栏位、主手/副手补货、空瓶保留、工具耐久阈值与同类工具替换；请求与背包同步经过真实单人客户端/服务端连接。`clientSmoke` 实际启动没有 IPN/Kotlin 的客户端并加入单人世界。`ShulkerRestockTest` 和 `RestockGameTests` 验证原子取物、保护栏位、堆叠盒、过期与重复请求、快捷栏切换取消及个人开关。

### 原理图取料协议

`BoxOrder` 对允许使用的盒子按每格数量/堆叠上限之和排序，同分时保留栏位顺序。收纳在原有盒子类别内部先填更满的；`RefillSearch` 和服务端 `RefillNetwork` 从较空的盒子开始取料，无法安全取出时尝试后续来源。27 个非满堆叠不视作满盒，非标准大容器仍跳过。

`RefillSearch` 只读查找组件完全匹配的材料。可选 Mixin 包围 Litematica `WorldUtils.doEasyPlaceAction` 和 `EasyPlaceUtils.handleEasyPlace`，仅在轻松放置调用 `InventoryUtils.schematicWorldPickBlock` 时触发取料，普通选取方块不受影响。`LitematicaMixinPlugin` 在未安装 Litematica 时跳过这些客户端目标；构建和专用服务端不依赖其 JAR。

`refill_v3` 请求包含盒子栏位、盒内栏位、有长度上限的物品 ID 和 64 字符 SHA-256 指纹。`ItemFingerprint` 使用原版 `HashOps`、物品编解码器及注册表上下文计算与数量无关的规范化指纹；无法编码或含临时组件时拒绝，不接收完整客户端物品数据。旧 v1/v2 通道不再注册。`RefillNetwork` 在服务端线程检查来源指纹、游戏模式、菜单/光标状态和玩家有效设置，并重新读取真实物品；每位玩家每 10 个服务端 tick 最多处理一次，重复请求遇到背包已有材料时直接停止。相同盒内组件和数量相同的候选只规划一次，数量不同仍分别尝试。`ShulkerRefill` 先在副本中规划取出、腾栏和拆盒，全部可行后才提交；失败不修改背包。成功只走原版背包同步，失败可发送限频快捷栏提示。

取料不会直接放置方块或绕过 Litematica 的快捷栏保护和放置校验。等待同步期间抑制的是本次缺料产生的通用轻松放置警告；继续按住放置键后，由原有流程选物并放置。

`RefillSearchTest` 对 36 个满盒（972 个格子）执行只读查找，并输出本机中位数/P95 采样；数值不作为跨机器性能阈值。它不代表多人服务器负载或网络延迟。`RefillGameTests` 覆盖实际服务端请求、模式/配置拒绝、服务端默认关闭时的个人取料和物品保护。`ShulkerRefillTest` 覆盖入盒/取料四种开关组合，配置测试覆盖分别继承与个人覆盖，`PickupGameTests` 验证取料关闭时真实拾取仍可入盒。

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

正常拾取仍通过原版背包同步机制更新客户端。可选的个人设置使用 `policy_v4` 和 `preferences_v4` 通道；发送前检查对端是否支持。Fabric 对象消息处理器在游戏主线程执行。消息只含最多 4096 字符的配置 JSON，不包含目标 UUID；身份由实际连接确定。服务端检查策略、字段白名单、类型、枚举和大小，每名玩家最多每 20 tick 接受一次网络更新。纯服务端玩家不需要这些通道。

有效配置先由 `allowPlayerSettings` 决定是否读取个人覆盖项。允许时，`ConfigFile.apply` 将玩家显式设置的字段覆盖到服务端默认值；`pickupStorageEnabled` 与 `schematicRefill` 相互独立，玩家可开启服务端默认关闭的任一项。关闭策略时直接使用统一配置。个人文件在存档中按 UUID 隔离并缓存，写入使用临时文件与原子替换；非法个人文件不被静默覆盖，读取失败时回退服务端配置并记录日志。重载时先验证新配置，成功后替换并清缓存。

测试覆盖服务端开关立即影响真实拾取、普通玩家只改自己、管理员实时开启/撤销策略、恶意策略字段拒绝、玩家间隔离与重启恢复、消息长度及中英文键/格式占位符一致。中英文资源位于 `assets/magic_shulker_boxes/lang`；`translatableWithFallback` 让没有安装客户端模组的玩家也能看到按其上报语言生成的文本。

其他模组若取消整个拾取流程，仍可阻止本模组；修改了容器结构或拾取路径的其他模组需要另行兼容验证。

## 可选 GUI 集成

`ModMenuIntegration` 提供配置入口，检查 YACL 是否加载后才引用 `SettingsGui`。GUI 库使用 `modCompileOnly`，不会打包进本模组；开发启动可加 `-PwithConfigGui`。默认 GameTest 不加载 GUI 依赖，以检查纯服务端兼容。

YACL 绑定只操作 `SettingsDraft` 的副本；个人布尔字段是三态，继承会删除键。`SettingsSession` 用连接与策略修订号隔离打开的编辑器和待确认保存；超时保留请求号并发起恢复查询，重复或旧连接回复不能写入。`PreferenceSync` 在发送保存前写入服务器地址/存档路径与玩家 UUID 对应的哈希文件名恢复标记，位于 `config/magic_shulker_boxes-recovery/`；标记不包含设置值或明文地址。收到确认后先更新内存快照，再写个人文件并清除标记。失败时内存仍跟随服务端，重连/进程重启遇到标记时先查询，不自动上传旧文件。`ClientSettings` 协调通知、超时和本地服务端提交。

`EditorNetwork` 使用 `editor_state_v4`（策略和默认值）、`editor_save_v4`（请求号和覆盖项）、`editor_query_v4`（只读恢复查询请求号）及 `editor_result_v4`（对应确认、快照或拒绝）。JSON 上限 4096 字符，身份只取连接玩家；服务端复核保存策略，对每位玩家的保存和查询分别按 20 tick 限流。查询只能读取本人偏好，即使策略已锁定也不修改数据。旧设置同步与 GUI v1/v2/v3 通道不再注册，避免旧客户端上传旧键；新版 GUI 保存要求对端支持 v4 查询。GUI 无管理员网络写入通道；本机房主的统一配置写入在集成服务端线程执行，先比较草稿基线以避免覆盖外部修改。

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
