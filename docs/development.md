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

## 管理玩家设置的命令

管理员目标玩家命令位于 `SettingsCommands`：`/msb admin player <name|UUID> show|set|reset [option]` 继承 `COMMANDS_ADMIN` 权限。在线名字先查玩家名单，离线名字使用原版身份缓存/解析器，UUID 可直接访问离线存储。管理编辑先读取完整个人覆盖，再使用 `PlayerSettingsStore.save` 修改或重置；普通玩家仍使用 `saveAllowed`。运行时 `resolve` 的策略过滤不变。在线目标通过 `SettingsNetwork.acknowledge` 更新客户端个人文件，现有编辑会话因偏好变化而失效；离线目标不改变客户端入服上传规则。

`AdminSettingsGameTests` 覆盖目标隔离、离线名字/UUID、个人值与生效值、权限、合法/非法值、单项及全部重置、策略锁定、缓存重载和损坏文件保护。`DedicatedClientGameTests` 通过真实 TCP 连接检查在线名字修改、UUID 重置和客户端文件同步。

0.6.0 的服务端 `admin show/set/reset/permission/permissions` 使用 `ServerSettingsEdit` 先验证完整替换，再通过 `replaceConfig` 原子保存并广播策略。`SettingsChat` 只生成原版 `ClickEvent.SuggestCommand` 和命令悬停提示，点击不会提交；管理员目标玩家按钮绑定 UUID。`CommandChatGameTests` 检查全部设置/权限的按钮、无提前修改、单项重置、值校验和 OP3 权限。TCP 客户端验收实际点击聊天按钮、检查输入框完整命令和服务端尚未改变，再按 Enter 验证持久化。

`SettingsKeybindings` 动态注册全部布尔选项的原版客户端按键，默认未绑定；`options.txt` 由 Minecraft 管理，绑定不在网络载荷中。`PreferenceToggle` 从显式偏好或当前默认值翻转一个布尔项，保留其他选择；`ClientSettings` 在保存前检查会话、逐项权限和待确认状态，并复用 GUI 的确认/恢复协议。TCP 验收绑定 F8、两次切换 `craftRefill`，再撤权验证值被锁定而按键仍绑定。`craftRefill=true` 默认的测试同时覆盖已有显式 `false` 保留，行为夹具显式开启并恢复配置。

## 配置版本与迁移

磁盘 JSON 当前使用 `configVersion: 2`；版本元数据不进入 `StorageConfig`、选项列表、GUI 草稿或网络 JSON。无标记的旧文件沿用 0.3.1 规则：先完整备份到 `.pre-0.3.1.bak`，再重置为默认配置或空个人覆盖项。版本 1 文件则先完整备份到 `.pre-0.3.2.bak`，验证字段后将 `enabled` 改为 `pickupStorageEnabled`，保留所有其他有效值并原子写入版本 2。相同备份可恢复中断的迁移；冲突备份、无效字段、未知版本和无法读取的文件均不覆盖。

统一配置、客户端偏好、服务端玩家偏好的写入分别通过带版本的 `writeServer/writePreferences`；普通 `write` 仅用于恢复标记等非设置文件。写入前也检查旧文件，避免首次读取前的保存跳过备份。客户端启动读取偏好；服务端 `SERVER_STARTED` 扫描现有 UUID 玩家文件，一个文件失败不阻止其他玩家迁移。新版修改保留稀疏继承，后续加载不重置。`pickupStorageEnabled` 默认 `false`，行为测试需要显式开启收纳；手动拾取测试前也需开启世界/服务端配置或个人设置。

`ConfigMigrationTest` 覆盖默认关闭、两代原字节备份、重置与改名、后续持久化、备份冲突/不可用、未来版本及离线玩家；`ConfigMigrationGameTests` 检查实际拾取、新 GUI 保存和旧设置接收器不再注册。迁移不接触物品或世界方块。

## GitHub 构建、发布与文档站

模组名为 **Magic Shulker Boxes**，固定 ID 为 `magic_shulker_boxes`，仓库为 `chenjicheng/magic-shulker-boxes`。ID 符合 [Fabric 元数据规范](https://wiki.fabricmc.net/documentation:fabric_mod_json_spec)。JAR 使用常见的“名称－加载器－版本＋游戏版本”形式：`magic-shulker-boxes-fabric-0.1.0-alpha+mc1.21.11.jar`；这是本项目的命名约定，并非 Fabric 强制文件名。

`gradle.properties` 中的 `mod_version` 是发行版本，首发 `0.1.0-alpha`；Gradle 产物和模组元数据追加 `+mc1.21.11`。`v0.1.0-alpha` 是对应 Git 标签。版本中的预发布标识会使 GitHub Release 标记为 prerelease。项目采用 MIT 许可证。

- **CI**：分支推送、Pull Request 和手动运行，复用 `build.yml`。Java 21 下分别在无 Carpet 和 Carpet 1.4.194 环境构建并运行单元测试、专用服务端 GameTest；Carpet 下载验证固定 SHA256。
- **Release**：仅由 `v*` 标签推送触发，先核对标签与 `mod_version` 一致并通过两个测试环境，再由独立任务发布到 GitHub 和 Modrinth。GitHub 发布测试过的 JAR、源码 JAR 和 `SHA256SUMS`；Modrinth 发布同一正式 JAR。发行说明读取 `docs/releases/<mod_version>.md`。构建任务只有读取权限，GitHub 发布任务才有仓库写权限；Modrinth Token 仅注入其上传步骤。
- **Documentation**：VitePress 1.6.4、Node 24 与 npm 锁文件。底层 Vite 固定到 6.4.3 以包含安全修复，升级时需复验构建、搜索和预览。PR 只构建校验，`main` 推送将 `docs/.vitepress/dist` 部署到 GitHub Pages，站点基路径为 `/magic-shulker-boxes/`。

设置选项或分组变化时，发布前运行 `.\gradlew.bat runClientGameTest -PclientSmoke -PwithConfigGui`，实际打开个人/世界设置并验证字段权限保存；构建、服务端 GameTest、IPN 补货和 TCP 命令验证都不能替代这个 GUI 入口。

发布步骤：修改 `mod_version` 并添加对应双语发行说明，通过本地检查后提交到 `main`。确认 CI 成功，再创建匹配标签并推送，例如：

```sh
git tag -a v0.1.0-alpha -m "Magic Shulker Boxes 0.1.0-alpha"
git push origin v0.1.0-alpha
gh run list --workflow release.yml
```

GitHub Release 由 Actions 内的 `gh` 创建，先上传到草稿，资产齐全后公开。不要手工上传本机旧产物；已公开版本不覆盖，用新版本修复。首次仓库设置需将 Pages 的发布来源设为 **GitHub Actions**。GitHub 发布使用内置 `GITHUB_TOKEN`，无需额外 GitHub PAT。

### Modrinth 自动发布

项目为 [Magic Shulker Boxes](https://modrinth.com/mod/magic-shulker-boxes)。在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 设置：

- Variable `MODRINTH_PROJECT_ID`：Modrinth 项目 ID `omzSygsa`。
- Secret `MODRINTH_TOKEN`：单独用于 CI 的 Modrinth PAT，权限为读取项目、读取版本和创建版本。项目创建/编辑使用另一个 Token，不放入 CI；Token 不写入仓库或命令行参数。

`publish-modrinth` 与 GitHub 发布都依赖 `verify`，互不依赖。`scripts/modrinth.py` 再次核对标签和正式 JAR 的 SHA256，仅上传当前版本的可安装文件。Minecraft 版本与发行版本从 `gradle.properties` 读取；稳定版标记为 `release`，`-alpha` 标记为 `alpha`，其他预发布版标记为 `beta`。发行说明中的相对文档链接转换为公开文档链接。Fabric API 为必需依赖，Mod Menu、YACL、IPN 和 Litematica 为可选依赖；安装环境为服务端必需、客户端可选，客户端增强功能仍要求两端安装。

上传后读回版本并核对主文件 SHA512、版本信息、说明及依赖。重复运行遇到内容完全相同的版本会跳过上传；同版本文件或元数据冲突会失败，绝不覆盖。网络写入失败不会自动重发 POST，重新运行时先查询远端版本。缺少 Token、项目 ID、产物或校验不通过均会明确失败。

若只有 Modrinth 任务失败，可在 Actions 中选择 **Re-run failed jobs**，保留已成功的 GitHub 发布。首次项目需要另行提交 Modrinth 审核；创建草稿和上传版本不表示已经公开。

```sh
python -m unittest discover -s scripts -p 'test_*.py' -v
npm ci
npm run docs:build
npm run docs:preview
```

发行校验脚本 `scripts/release.py` 检查标签、元数据、许可证、可选依赖和测试类未被打包，并只选择当前版本的两个产物。VitePress 使用 `docs` 中的公开文档，保留死链接检查；缓存、日志、本地存档和构建目录不提交。完整使用说明以 `docs/guide.md` 和 `docs/en/guide.md` 为单一来源。

## 容器、来源与字段权限

`MenuStorageMixin` 只包装服务端菜单的 `clicked`，不在 `moveItemStackTo` 内收纳。先让整次原版点击及来源回写、交易回调完成，再由 `MenuStorage.collectDeposited` 处理实际入包的数量。Shift、来源热键和跟踪到的光标放入共用此路径；背包内部整理、交易选择自动退款和合成菜单不接入。数量同时受槽位增量及完整组件对应的全背包净增量约束，原版移动旧堆叠不构成新来源。规划只移动已存在的物品，提交前核对实时库存，来源未被原版接收时不复制快照补发。

`ContainerOwnership` 按真实所有权检查菜单的外部容器。方块实体必须是当前世界该位置的实际对象；实体必须仍在当前世界；大箱子的两部分都须独立，交易容器须属于当前真实商人。本人末影箱与原版合成临时缓冲区有明确所有者。未知或随身物品菜单保留原版行为，打开期间不进行 MSB 随身盒收纳写入。比较内容或判断 `slot.container != inventory` 都不能证明来源与目标独立。

`ItemEntityMixin` 仅在原版通过拾取延迟和归属检查、调用 `Inventory.add` 时记录接收前库存及真实余量，不改变该调用的返回值；整次 `playerTouch` 返回后才交给 `PickupStorage` 整理已收到的物品。原版会恢复已删除实体的堆叠数量用于回调，该堆叠不是可再次使用的来源。仅仍存活、引用和数量都与原版余量相符的实体可继续收纳真实余量；直接扣减实体物品，不从快照创建物品。原版未触发拾取反馈时，额外接收的实际数量才产生一次对应反馈。

`ItemBackedMenuGameTests` 用保留原盒对象的通用菜单复现旧实现 5→10 的服务端复制，并覆盖序列化重读、重开菜单、光标、热键、打开时地面拾取、来源及拾取回调顺序、旧物品移动和关闭 MSB 的对照。普通容器测试使用 `TestContainers` 创建有真实世界所有者的箱子；`SimpleContainer` 自身不构成独立所有权证明。真实客户端 smoke 也通过原版网络点击验证随身盒取出的五个物品仅留在背包。

`RefillSources.View` 将真实玩家物品栏与本人末影箱组合，末影箱编码槽位固定为 100–126，内部槽位 -1 表示直接物品。该范围避开 1.21.11 的 41/42 身体装备和鞍槽；空隙不可写入物品。`CraftingMaterials.copy` 保留来源区域，配方放置与连续补货在整体副本中规划并提交。来源顺序为原版/IPN 背包、随身盒、末影箱直接物品、末影箱盒；堆叠盒只在对应区域找拆分空栏。

`EnderSourcesNetwork` 仅发送 S2C `ender_sources_v1`，固定 27 格，不接受客户端存储写入。启用 `enderChestRefill` 时每 10 tick 比较当前玩家真实末影箱与上次发送的快照，只在变化时发送；关闭时发送空投影。客户端投影只用于候选和配方书统计，服务端请求仍重新读取自己的真实存储。

`restock_v2` 增加整个来源盒指纹，仍校验数量、目标、掩码及请求 ID。工具候选使用 `swapToolForRestock`，把来源内原格和主手/副手一起交换；来源盒变化或无法安全拆分时拒绝。客户端观察实际装备同步后结束等待，不再执行第二次 IPN 换手。消耗品保留原有取到背包再换手的流程。

`playerEditableSettings` 只在 `ServerConfig` 存在。`PlayerSettingsStore.resolve` 每次按当前字段权限过滤既存覆盖，`saveAllowed` 拒绝未授权字段，只替换可编辑部分。命令、Preferences 与 Editor 保存共享该规则。`editor_state_v7` 除默认值外带字段许可数组；GUI 显示锁定字段的服务器值，策略修订使旧草稿失效。旧 v3 设置/取料和 v1 IPN 接收器不注册；两端需使用 0.9.0。磁盘格式仍为版本 2，新字段是可选新增。

`MenuStorageGameTests` 覆盖真实容器与交易、光标、满背包、部分容量、拒绝取物、整份交易的扣款/次数/经验。`EnderSourcesGameTests` 覆盖本人隔离、请求重复/过期、来源优先级、禁用与无空间、真实配方书和连续补货。`SettingPermissionsGameTests` 检查命令、恶意网络/GUI、既存文件和运行时撤权。客户端的 `clientSmoke` 覆盖真实容器点击，`withIpn` 覆盖末影箱直接药水与原格工具回存；`craftClient` 检查末影箱配方统计和实际请求。加 `-PwithConfigGui -PclientSmoke` 检查逐项锁定页面。

### 杂物槽位状态与协议（开发分支，未发布）

`JunkSlots` 用 36 位掩码描述实际背包/快捷栏索引0–35，不使用菜单slot ID，不允许装备、副手或末影箱格。`JunkSlotStore` 在 `world/data/magic_shulker_boxes/junk-slots/<UUID>.json` 写入独立的 `{schemaVersion:1, slots:[...]}` 文档。当前运行修订用于CAS；写入前重新核对磁盘内容，未知版本、重复/非法索引及损坏文件保留原样。原子替换成功后才确认选择。运行时 `StorageConfig.junkBoxSlots` 是transient，仅由当前玩家的服务端数据注入副本，不进入设置schema2、选项/许可或v7载荷，不改变其他玩家与共享默认值。

`junk_slots_save_v1` 携带正request ID、预期revision和mask，`junk_slots_query_v1` 查询状态；`junk_slots_state_v1` 回传request、状态、revision与mask。身份只取认证连接。SAVED/SNAPSHOT确认服务器数据，STALE拒绝覆盖已变化选择，INVALID拒绝越界，BUSY保留客户端待发选择并合并重试，FAILED保留原选择；revision=-1表示数据不可读取。客户端断开即清空会话，不跨服务器上传旧位置。`JunkSlotSession` 仅保留一项在途保存及更新后的待发选择，过期回复不能发布角色，超时先查询，不盲目回放旧快照。

`JunkSlotsClient` 使用Fabric屏幕键盘/鼠标事件和afterRender，`JunkSlotGesture` 在一轮中固定添加/移除模式并去重，屏幕移除时结束手势。保存只改变角色文件，不能改变物品。角标原生绘制在左上，提示追加到原版物品tooltip；空格使用独立tooltip。角色有待确认/生效/无盒三种状态，取消确认前也显示待确认标记。

`ShulkerStorage` 和 `BoxRelocation` 将指定格独立分类为JUNK，排在匹配盒与空盒之间。堆叠盒拆分保持指定格的单盒位置，其他盒与全部组件/数量保留。被保护的格不被腾栏或自动拆盒/取料占用。丢出回收先执行原版拾取；新收到的实际数量再路由到同步预留盒，仍不使用删除实体恢复的旧count。`IpnJunkSlots` 仅在IPN自己的整理计算作用域扩展只读锁定集合，退出即清理，不修改配置或普通补货候选。

新增回归包括 `JunkStorageTest/JunkSlotStoreTest/JunkSlotGestureTest/JunkSlotSessionTest`、`JunkSlotsGameTests` 和真实 `JunkSlotsClientGameTests`：

```powershell
.\gradlew.bat runClientGameTest -PjunkClient
.\gradlew.bat runClientGameTest -PjunkClient -PwithIpn
```

真实客户端检查键盘/鼠标按住多选、重复经过、清除、服务器保存、空格与单盒角标、中英文、窗口尺寸变化和IPN整理；服务端检查原版优先、两种腾栏模式、组件/数量守恒、未知菜单保护与玩家隔离。该功能使用独立通道，客户端未安装或服务端不支持时不发送槽位编辑。

## 测试命令

服务器每五分钟检查 GitHub Release、校验并保留两模组暂存包的安装、配置与运行边界见[定时下载与暂存](release-staging.md)。共享更新器位于 `scripts/stage_releases.py`，systemd 单元和示例配置位于 `deploy/`；其回归已纳入前文的 Python 测试命令。
### 本地专用服务器 TCP 验收

```powershell
.\gradlew.bat runClientGameTest -PwithIpn -PdedicatedClient -PacceptMinecraftEula
```

`-PacceptMinecraftEula` 表示同意 [Minecraft EULA](https://aka.ms/MinecraftEULA)，仅为隔离的测试目录写入 `eula=true`；未同意时不要传入该参数。

`DedicatedClientGameTests` 使用 Fabric 测试框架启动监听 127.0.0.1 的真实 `DedicatedServer`，客户端通过本机 TCP 加入。测试检查客户端没有内置服务器，复用 IPN 全部真实触发/匹配路径，再检查容器 Shift、连续村民交易的输入/次数/经验、末影箱请求、配方点击与耗空格补货，以及网络保存字段许可和撤权。测试结束关闭连接与服务器；存档只在 `build/run/clientGameTest/msb-dedicated-test-world`。无需操作生产存档或服务器。这里的独立专用服务器实现由测试框架在测试 JVM 内运行，和原版/Carpet 的纯服务端 GameTest 进程一起覆盖连接同步与物理服务端加载。

### 合成取料与真实客户端验证

`CraftingMenuMixin` 将原版 `AbstractCraftingMenu.handlePlacement` 包在一个 `CraftingRecipeSources` 事务中。`ServerPlaceRecipeMixin` 只在这一作用域补充材料统计和原版背包查找失败后的取物，继续复用原版配方选择、布局和批量数量。来源盒自身属于配方材料时不同时读取其内部。旧合成格返背包先按完整组件与容量预检；来源变化在副本中规划，整次放置成功才提交。组件合并或拆盒提交冲突回退库存和合成格，作用域通过 `finally` 清理。

`CraftingResultMixin` 捕获原版取出成品前的输入及配方余留物，原版先完成成品、消耗与余留物处理，然后 `CraftingRefill` 在副本中补足整套耗空格子。Shift 回调可能传入数量为零的旧结果堆叠，以有效原输入配方为依据。取出后再次校验玩家与菜单，避免给已关闭的菜单补货。

没有新增取料网络请求。原版请求校验配方与活动菜单，服务器读取真实物品。`CraftingInventoryClientMixin` 仅为兼容服务器的配方书增加可用材料统计；通过服务器默认值中 `craftRefill` 的存在判断能力，再应用有效设置。只读观察外层数量及容器组件引用变化，触发原版配方书重算。`craftRefill` 不依赖其他补货开关或 IPN，继承现有个人设置策略。

```powershell
.\gradlew.bat runClientGameTest -PcraftClient
```

`CraftingRefillTest` 覆盖多材料原子补货、组件和余留物、满背包与堆叠盒。`CraftingGameTests` 运行真实 2×2/3×3、配方批量、普通/Shift 合成、空间与组件回退、蛋糕空桶及个人覆盖。`CraftingClientGameTests` 使用原版配方点击和物品点击请求，验证配方书盒内统计/刷新、两种网格、连续补货和服务端确认的成品数量；客户端预测完成不代替服务端验收。

### Carpet 与 GCA 兼容

0.8.0 删除假人 tick/drop Mixin，以及玩法、字段许可、命令、GUI 和快捷键中的 `carpetRefill`。Carpet 仍为堆叠盒兼容的可选共存模组，MSB 不再实现假人手持补货。`CarpetCompatibilityGameTests` 使用发行版真实 Carpet 假人及连续 USE，验证关闭 GCA 时手持耗空、来源盒不变；开启 GCA 后，从内栏 25 有剩余物品的未满盒和第二盒连续放置恰好 7 个铁砧，补货完全由 GCA 负责。

`ConfigFile.migrateRemovedSettings` 仅在版本 2 磁盘文件兼容旧字段，验证布尔类型、所有剩余设置和许可后，仅涉及旧 Carpet 项时保存原字节 `.pre-0.8.0.bak`；同时迁移旧收纳设置时使用 `.pre-single-type.bak`，再原子移除字段及同名许可。服务端、客户端和 UUID 文件共用此入口；命令与网络输入拒绝旧字段。相同备份允许恢复中断，冲突/不可读备份和无效值保留原文件。磁盘版本保持 2，策略/偏好和 GUI 改为 v7，取料协议保持各自格式。

```powershell
.\gradlew.bat build '-PcarpetJar=C:/path/to/fabric-carpet-1.21.11-1.4.194+v251223.jar' '-PgcaJar=C:/path/to/gugle-carpet-addition-mc1.21.11-v2.12.8+build.97.jar'
```

这些依赖只用于测试，不进入发行 JAR。没有 GCA 时跳过其专属用例；Carpet 用例仍验证 MSB 不补货。`RemovedCarpetSettingsTest` 覆盖其他设置保留、稀疏许可、离线 UUID、旧网络输入拒绝、无效文件、原字节备份和中断恢复。

### IPN 来源扩展与客户端验证

可选接入固定对照 IPN 2.2.6、libIPN 6.6.3（Fabric 1.21.11）。这两项及 Kotlin 均为编译/测试依赖，不打包进发行 JAR。`magic_shulker_boxes.ipn.mixins.json` 只在客户端安装 IPN 时应用；专用服务端和无 IPN 客户端保留原有行为。

`IpnMonitorMixin` 在 IPN 完成自身触发检查与等待 tick 后、调用 `handle()` 前接入。普通背包候选始终优先。`IpnCandidatesMixin` 仅在作用域受限的第二次查找中，将只读盒内物品交给候选列表；所有筛选与排序仍在 IPN 原始 `findCorrespondingSlot` 执行。虚拟候选编号不会进入点击协议或玩家背包。发行包省略嵌套类元数据，Java 适配器使用其实际二进制类名。

`restock_v2` 包含关联请求 ID、来源盒/内部栏位与数量、目标主手/副手栏位和数量、IPN 可用背包栏位的 27 位掩码，以及来源物品/整个来源盒/目标各自最多 64 字符的组件指纹。空目标使用空指纹。服务端读取真实物品并检查指纹、数量、模式、菜单、光标、有效 `ipnRefill` 和限流；同一请求与每 10 tick 内的后续请求拒绝。取出事务只提交完整规划，腾栏和拆盒均限于掩码中的背包栏位。`restock_result_v2` 返回关联 ID 与结果，物品通过原版背包同步。客户端最长等待 5 秒，IPN 撤销触发、失败或超时后恢复其原有处理。

真实客户端验证（需要可用的图形环境）：

```powershell
.\gradlew.bat runClientGameTest -PwithIpn
.\gradlew.bat runClientGameTest -PclientSmoke
```

`src/ipnTest` 仅在 `withIpn` 时加入测试源，运行 IPN 发布 JAR 的原始匹配器及实际 Mixin。覆盖背包优先、药水效果、名称匹配开关、锁定来源、禁用补货栏位、主手/副手补货、空瓶保留、工具耐久阈值与同类工具替换；请求与背包同步经过真实单人客户端/服务端连接。`clientSmoke` 实际启动没有 IPN/Kotlin 的客户端并加入单人世界。`ShulkerRestockTest` 和 `RestockGameTests` 验证原子取物、保护栏位、堆叠盒、过期与重复请求、快捷栏切换取消及个人开关。

### 原理图取料协议

`BoxOrder` 对允许使用的盒子按每格数量/堆叠上限之和排序，同分时保留栏位顺序。收纳在原有盒子类别内部先填更满的；`RefillSearch` 和服务端 `RefillNetwork` 从较空的盒子开始取料，无法安全取出时尝试后续来源。27 个非满堆叠不视作满盒，非标准大容器仍跳过。

`RefillSearch` 只读查找组件完全匹配的材料。可选 Mixin 包围 Litematica `WorldUtils.doEasyPlaceAction` 和 `EasyPlaceUtils.handleEasyPlace`，仅在轻松放置调用 `InventoryUtils.schematicWorldPickBlock` 时触发取料，普通选取方块不受影响。`LitematicaMixinPlugin` 在未安装 Litematica 时跳过这些客户端目标；构建和专用服务端不依赖其 JAR。

`refill_v4` 请求包含盒子栏位、盒内栏位、有长度上限的物品 ID 和 64 字符 SHA-256 指纹。`ItemFingerprint` 使用原版 `HashOps`、物品编解码器及注册表上下文计算与数量无关的规范化指纹；无法编码或含临时组件时拒绝，不接收完整客户端物品数据。旧 v1/v2/v3 通道不再注册。`RefillNetwork` 在服务端线程检查来源指纹、游戏模式、菜单/光标状态和玩家有效设置，并重新读取真实物品；每位玩家每 10 个服务端 tick 最多处理一次，重复请求遇到背包已有材料时直接停止。相同盒内组件和数量相同的候选只规划一次，数量不同仍分别尝试。`ShulkerRefill` 先在副本中规划取出、同类收纳腾栏和拆盒，全部可行后才提交；失败不修改背包。成功只走原版背包同步，失败可发送限频快捷栏提示。

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

单元测试报告：`build/reports/tests/test/index.html`。GameTest 的执行结果会输出到控制台和测试运行目录日志。每个拾取用例使用实际的 Minecraft 服务端玩家、背包和物品实体；覆盖同类盒优先与无同类盒时的背包/空盒优先设置、部分拾取、拾取归属/延迟、堆叠无空栏回退、主动收纳拆分、禁用配置等路径。

### 游戏界面复测

`tests/manual-datapack` 是 1.21.11 的测试数据包。将其内容复制到**专门新建的测试存档**的 `datapacks/msb-manual-tests` 下，进入世界后执行 `/reload`。需要允许命令、安装 Carpet。场景会清空当前玩家背包、删除附近掉落物并设置和平难度，请勿用于正式存档。

| 命令 | 场景与预期 |
| --- | --- |
| `/function msb_test:refill` | 满背包、16 个 Carpet 空盒与材料盒；把测试原理图放在 `100,101,100`，轻松放置应取料、腾栏并保留镐和堆叠盒 |
| `/function msb_test:refill_blocked` | 重置取料场景，禁止腾栏；应提示失败且物品不变，需先允许个人设置 |
| `/function msb_test:refill_silent` | 在失败场景同时关闭通用补货与空间不足提示；再次尝试不出现提示，两字段须获准修改 |
| `/function msb_test:refill_disabled` | 关闭取料并开启提示；尝试时说明功能已禁用 |
| `/function msb_test:refill_enabled` | 清除个人覆盖并恢复取料场景 |
| `/function msb_test:matching` | 同类蓝盒 63 个圆石变成 64＋4，前面的空盒和含无关物品的盒子不变 |
| `/function msb_test:partial` | 蓝盒只剩 1 个容量，拾取 5 个后地面留下 4 个 |
| `/function msb_test:split` | 先设置 `preferEmptyBoxesOverInventory=true` 并重启；16 个命名蓝盒变成 15 个空盒与 1 个装有 5 个圆石的盒子 |
| `/function msb_test:auto_space` | 无空栏，将同类石头合并后拆出独立盒；最终 13 个空盒与石头、圆石、砂砾各一个盒 |

`auto_space` 可分别在 `MOVE_TO_BOX`、`DROP_AND_PICKUP` 下运行；默认设置下拆出三个单类盒，分别包含石头 131、圆石 5、砂砾 7；空盒剩余 13 个。`DISABLED` 时盒子保持 16，掉落物留地。测试配置修改后须重启，结束后恢复所需配置。

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

Mixin 注入点位于原版服务端、拾取延迟及所有者检查之后。它保留原版拾取动画、统计和实体移除流程，不拦截通用 `Inventory.add`；容器收纳由独立的菜单入口处理。

收纳先在内容副本上计算实际可接收数量，成功后才提交一个盒子的内容并扣除输入数量。堆叠源盒只减 1，其余盒子的内容不变；没有可接收容量时不拆盒、不占空栏。超过原版 27 栏的非标准潜影盒数据会整体跳过，以免截断其他模组的数据。

腾栏事务先为被移动栏位的全部物品预留容量，再计算可接收的掉落物数量。丢出模式只有世界接纳了全部掉落实体才提交背包变更；同步回收只允许写入刚预留的那个单盒，目标被替换就保留掉落物。临时预留在 `finally` 清理；未收回的实体带有禁止再次腾栏的持久标记。真实 GameTest 覆盖两种模式、非满组、连续不同物品的独立收纳及重复触碰不循环腾栏。

正常拾取仍通过原版背包同步机制更新客户端。可选的个人设置使用 `policy_v7` 和 `preferences_v7` 通道；发送前检查对端是否支持。Fabric 对象消息处理器在游戏主线程执行。消息只含最多 4096 字符的配置 JSON，不包含目标 UUID；身份由实际连接确定。服务端检查策略、字段白名单、类型、枚举和大小，每名玩家最多每 20 tick 接受一次网络更新。纯服务端玩家不需要这些通道。

有效配置先由 `allowPlayerSettings` 决定是否读取个人覆盖项。允许时，先按 `playerEditableSettings` 过滤，再由 `ConfigFile.apply` 将获授权的显式字段覆盖到服务端默认值；`pickupStorageEnabled` 与 `schematicRefill` 相互独立，玩家可开启服务端默认关闭的任一项。关闭策略时直接使用统一配置。个人文件在存档中按 UUID 隔离并缓存，写入使用临时文件与原子替换；非法个人文件不被静默覆盖，读取失败时回退服务端配置并记录日志。重载时先验证新配置，成功后替换并清缓存。

测试覆盖服务端开关立即影响真实拾取、普通玩家只改自己、管理员实时开启/撤销策略、恶意策略字段拒绝、玩家间隔离与重启恢复、消息长度及中英文键/格式占位符一致。中英文资源位于 `assets/magic_shulker_boxes/lang`；`translatableWithFallback` 让没有安装客户端模组的玩家也能看到按其上报语言生成的文本。

其他模组若取消整个拾取流程，仍可阻止本模组；修改了容器结构或拾取路径的其他模组需要另行兼容验证。

## 可选 GUI 集成

`ModMenuIntegration` 提供配置入口，检查 YACL 是否加载后才引用 `SettingsGui`。GUI 库使用 `modCompileOnly`，不会打包进本模组；开发启动可加 `-PwithConfigGui`；该可选运行会从同版本 YACL 发布包读取自带 Java 库补齐开发 classpath，仍不打包到本模组。默认 GameTest 不加载 GUI 依赖，以检查纯服务端兼容。

YACL 绑定只操作 `SettingsDraft` 的副本；个人布尔字段是三态，继承会删除键。`SettingsSession` 用连接与策略修订号隔离打开的编辑器和待确认保存；超时保留请求号并发起恢复查询，重复或旧连接回复不能写入。`PreferenceSync` 在发送保存前写入服务器地址/存档路径与玩家 UUID 对应的哈希文件名恢复标记，位于 `config/magic_shulker_boxes-recovery/`；标记不包含设置值或明文地址。收到确认后先更新内存快照，再写个人文件并清除标记。失败时内存仍跟随服务端，重连/进程重启遇到标记时先查询，不自动上传旧文件。`ClientSettings` 协调通知、超时和本地服务端提交。

`EditorNetwork` 使用 `editor_state_v7`（策略和默认值）、`editor_save_v7`（请求号和覆盖项）、`editor_query_v7`（只读恢复查询请求号）及 `editor_result_v7`（对应确认、快照或拒绝）。JSON 上限 4096 字符，身份只取连接玩家；服务端复核保存策略，对每位玩家的保存和查询分别按 20 tick 限流。查询只能读取本人偏好，即使策略已锁定也不修改数据。旧设置同步与 GUI v1/v2/v3/v4/v5/v6 通道不再注册，避免旧客户端上传旧键；新版 GUI 保存要求对端支持 v7 查询。GUI 无管理员网络写入通道；本机房主的统一配置写入在集成服务端线程执行，先比较草稿基线以避免覆盖外部修改。

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

`BoxRelocation` 在背包副本上规划被移动物品，优先使用同类单盒，再使用空盒。必要时可将多栏同类物品合并到单独拆出的盒子，腾出一个栏位。拾取规划只有确认能接收新物品后才提交，取料和合成则要求整个操作成功。IPN 掩码同时保护被移动栏位和随身目的盒。含无关物品的盒子即使有容量也不作为自动收纳目的地。

`BoxRelocation.sameType` 是收纳和腾栏的类型比较入口。默认比较物品 ID，以及 `POTION_CONTENTS`、`POTION_DURATION_SCALE`、`SUSPICIOUS_STEW_EFFECTS`、`STORED_ENCHANTMENTS`、`MAP_ID`、`FIREWORKS`、`FIREWORK_EXPLOSION`、`INSTRUMENT`、`OMINOUS_BOTTLE_AMPLIFIER` 的完整值；这些内容定义物品类型，不受 `matchItemComponents=false` 放宽。开启该项改用原版全部组件比较，普通装备的 `ENCHANTMENTS`/`DAMAGE`、自定义名称等额外差异仍受此开关控制。规则没有新增配置字段或改变网络载荷。

`PickupRelocation.collectReserved` 和 `ShulkerStorage.collectRelocated` 显式接收本次有效配置，回收前用同一 `acceptsType` 复核预留盒内容，防止其他拾取钩子修改盒内内容后绕过药水类型或严格组件保护。实际堆叠合并始终用 `ItemStack.isSameItemSameComponents`。

`PotionClassificationTest` 覆盖药水/药水箭、基础/延长/增强类型、自定义效果、时长倍率、混合盒拒绝、空盒回退、腾栏原子回滚及预留回收。`StorageTypeGameTests` 使用真实物品实体拾取与箱子 Shift 点击，覆盖全部关键内容类型、附魔书等级、烟花时长/内容，以及普通工具附魔仍受严格开关控制。对应真实客户端路径由 `ClientSmokeGameTests` 验证；无 IPN/YACL 设置页入口仍需按前文复测。

`ConfigFile` 仅在磁盘读取时移除旧混装选项和权限，在验证剩余字段后完整备份到 `.pre-single-type.bak`，保留其他稀疏偏好。命令、GUI、按键和网络输入不再包含这些字段。版本 1 文件在原有 `.pre-0.3.2.bak` 迁移中同时移除旧项；无效文件和备份冲突保持原样。历史发行说明仍对应各自发布时的行为。

`ItemEntityMixin` 与菜单转移先执行 `ShulkerStorage.storeMatching`，默认再走原版背包，最后使用空盒收纳余量；`preferEmptyBoxesOverInventory=true` 将空盒阶段提前到背包前。旧优先设置按反值迁移并重命名权限，不接受同时含新旧字段的冲突文件。`StorageFailure` 对可确认的空间失败统一发送服务端语言回退的快捷栏提示，按玩家限频 40 tick；空间失败由独立的 `spaceFailureMessages` 控制，不受 `refillFailureMessages` 影响。合成规划仅在来源拆盒或余留物安置失败时触发此提示，材料缺失和配方变化保持原有处理。

设置界面仅构建含选项的分组，避免已移除选项留下空分组导致 YACL 打开失败。`ClientSmokeGameTests` 实际打开个人与世界设置、检查字段权限并生成截图；新增优先设置沿用同一编辑与确认流程。
