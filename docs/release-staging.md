# 定时下载与暂存

**简体中文** | [English](en/release-staging.md)

`scripts/stage_releases.py` 是独立的服务器工具，为 **Magic Shulker Boxes** 和纯客户端的 **Chest Count Overlay** 共用。systemd 每五分钟检查两个仓库的最新稳定 GitHub Release，下载并验证发行包，在独立目录保留可安装 JAR、校验文件和旧版备份。实际启用版本与重启由管理员另外控制。

## 运行边界

- 仅接受 `v主版本.次版本.修订版本` 的公开稳定 Release；草稿和预发布版本拒绝。
- 默认固定 Minecraft **1.21.11**，核对 JAR 的模组 ID、完整版本、运行侧和 Minecraft 约束。版本低于已安装或已缓存的版本时跳过，不自动降级。
- 校验 SHA256 校验文件，并在 GitHub 提供 `digest` 时同时核对它；来源限于配置仓库的 HTTPS 发布资产，重定向限于 GitHub 的资产域名。
- 支持 `SHA256SUMS`，以及 CCO 1.0.1 的 `<JAR 文件名去掉 .jar>.sha256`。旧校验条目可带固定的 `release-artifacts/` 前缀；该前缀只用于识别条目，不作为落盘路径。
- 原服务端 `mods`、AutoModpack 在线分发文件、配置和存档均为只读来源。潜影盒模组可有服务端与客户端来源；CCO 只配置客户端来源。
- 一个模组失败不会阻止另一个检查，服务最终返回非零退出码并记录 journal；下一次定时执行重试。没有用户通知或外部消息发送。

## 安装

需要 Linux、systemd 和 Python **3.11 或更新版**，无需额外 Python 包。公开 Release 下载不需要 GitHub Token。先阅读并按实际目录调整 `deploy/sources.example.conf`，再安装共享配置和单元。

示例映射三个只读来源：`mc-server`、`cmc-server` 和 `mc-client`。源目录挂载到服务私有命名空间中的 `/run/minecraft-mod-stager-sources/<名称>`。这样即使真实来源在受保护的用户家目录中，也可保留原目录权限。来源目录须已存在，内部 JAR 须允许服务用户读取；不为缺少 AutoModpack 的服务器创建虚假的客户端来源。

以下是首次安装命令。已有文件时，先检查并备份当前配置、脚本及单元，保留本机目录映射，避免覆盖其他安装的内容。

```sh
sudo install -d -m 755 /usr/local/lib/minecraft-mod-stager
sudo install -d -m 755 /etc/systemd/system/minecraft-mod-stager.service.d
sudo install -m 644 scripts/stage_releases.py /usr/local/lib/minecraft-mod-stager/stage_releases.py
sudo install -m 644 deploy/minecraft-mod-stager.example.json /etc/minecraft-mod-stager.json
# 安装你已调整为实际来源目录的副本。
sudo install -m 644 deploy/sources.example.conf /etc/systemd/system/minecraft-mod-stager.service.d/sources.conf
sudo install -m 644 deploy/minecraft-mod-stager.service deploy/minecraft-mod-stager.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemd-analyze verify /etc/systemd/system/minecraft-mod-stager.service /etc/systemd/system/minecraft-mod-stager.timer
sudo systemctl start minecraft-mod-stager.service
sudo journalctl -u minecraft-mod-stager.service -n 20 --no-pager
# 首次实际检查成功后再开启定时执行。
sudo systemctl enable --now minecraft-mod-stager.timer
```

服务使用 `DynamicUser`，仅有状态目录的写入权限，并通过 `BindReadOnlyPaths` 读取指定来源。它执行下载与文件检查，不执行下载包中的代码或 Minecraft 管理命令。`OnCalendar=*-*-* *:00/5:00` 在每个五分钟边界触发，`AccuracySec=15s` 允许少量调度偏差，`Persistent=yes` 补一次关机期间错过的检查。

## 配置与产物

`/etc/minecraft-mod-stager.json` 的格式以 `deploy/minecraft-mod-stager.example.json` 为准：

| 字段 | 含义 |
| --- | --- |
| `cache_dir` | 独立、绝对路径的状态目录；systemd 单元固定使用 `/var/lib/minecraft-mod-stager` |
| `minecraft_version` | 目标 Minecraft 版本 |
| `modules[].id` / `environment` | 预期 Fabric 模组 ID 与运行侧；MSB 为 `*`，CCO 为 `client` |
| `repository` | 固定的 GitHub `owner/name` |
| `archive_prefix` / `version_suffix` | 根据 Release 标签组成唯一正式 JAR 文件名；源码 JAR 不被选取 |
| `targets[].name` / `installed_dir` | 目标名称与服务命名空间中的只读来源目录；同一模组可有多个目标 |

每次检查每个模组查询一次 Release API；只有新资产需要下载。API JSON、JAR 和校验文件分别限于 2 MiB、32 MiB、64 KiB。失败不寻找较旧版本兜底；最新 Release 不支持目标游戏时，保留已有暂存包并记录错误。

状态目录包含：

```text
bundles/<mod-id>/<tag>/              # 一次下载、完整校验的原始资产
targets/<target>/<mod-id>/<tag>/     # 每个目标的 JAR、校验文件、manifest.json、backups/
latest/<mod-id>.json                # 全部配置目标暂存成功后发布的指针
```

`manifest.json` 记录仓库、Release ID、版本、运行侧、SHA256、校验文件及每个文件的摘要；目标包另记录原始备份来源。备份反映首次暂存时的旧版本，后续重复检查不会覆盖它。一个目标暂存失败时，成功目标保留，下次从已校验缓存继续。临时目录完整写入后才改名发布；系统文件锁阻止并发检查，进程退出后锁自动释放。

`DynamicUser` 下 systemd 通常将持久目录放在 `/var/lib/private`，`/var/lib/minecraft-mod-stager` 是面向管理员的访问入口；查询和手动取用使用 `sudo`。服务的 `/run` 来源挂载仅在运行期间存在，其名称对应 `sources.conf` 中的实际目录。

## 查询、故障与启用

```sh
systemctl list-timers minecraft-mod-stager.timer
sudo systemctl show minecraft-mod-stager.service -p Result -p ExecMainStatus
sudo journalctl -u minecraft-mod-stager.service -n 30 --no-pager
sudo cat /var/lib/minecraft-mod-stager/latest/magic_shulker_boxes.json
sudo cat /var/lib/minecraft-mod-stager/latest/chest_count_overlay.json
sudo systemctl start minecraft-mod-stager.service
```

日志状态为 `staged`、`unchanged`、`skipped` 或 `error`。缓存或已发布资产出现冲突/损坏时不覆盖原文件；先核对 GitHub 的原始资产和来源，备份并移走确实损坏的暂存目录后再检查。保留已有旧版备份，不清理共享缓存或正式存档。

停止定时检查可执行 `sudo systemctl disable --now minecraft-mod-stager.timer`。它不删除已验证的包，也不改变已安装模组。若服务当前仍在检查，需要另外停止 `minecraft-mod-stager.service`。

暂存成功不会启用新版本。下面的手动命令将已选定的服务端与客户端 JAR 一起应用，再重启需要启用的服务端、核对实际加载版本并生成客户端清单。CCO 只更新客户端来源。packwiz 更新由管理员另行执行。

## 手动一键部署

`scripts/deploy_releases.py` 和 `deploy/minecraft-mod-deploy` 提供管理员手动调用的 Docker 部署命令，配置示例为 `deploy/minecraft-mod-deployer.example.json`。先调整真实模组、日志、MSB 配置/个人设置以及 AutoModpack 清单路径。配置文件必须由 root 拥有，不允许其他用户写入。

```sh
sudo install -m 644 scripts/deploy_releases.py /usr/local/lib/minecraft-mod-stager/deploy_releases.py
sudo install -m 755 deploy/minecraft-mod-deploy /usr/local/bin/minecraft-mod-deploy
sudo install -m 600 deploy/minecraft-mod-deployer.example.json /etc/minecraft-mod-deployer.json
```

在管理员自己的 `~/.bash_aliases` 添加：

```sh
alias mod-deploy='sudo /usr/local/bin/minecraft-mod-deploy'
```

新终端会加载 alias；当前终端可执行 `. ~/.bash_aliases`。调用方式：

```sh
mod-deploy --dry-run   # 只读校验与计划，不停服、替换、备份或生成清单
mod-deploy            # 正式部署两个模组
mod-deploy msb        # 仅 MSB
mod-deploy cco        # 仅 CCO 客户端分发，无服务端模组重启
```

正式执行先固定 `latest` 指向的每个模组版本，核对完整暂存包、SHA256 和 JAR 元数据，再暂停检查器。对需要更新或启用的 MSB 服务端正常停服，重新备份操作前的 JAR、MSB 配置/个人设置与客户端清单；然后替换服务端及客户端对应 JAR。旧版全部备份后移出当前目录，新包保持来源目录的用户/组以及可读权限。服务端重启后，用 RCON `list` 和当前启动日志中的完整 MSB 版本确认就绪，再执行 `automodpack generate`，等待清单的文件名、大小和 SHA1 与已安装客户端字节匹配。没有任何更新且运行版本已经匹配时，命令返回 `unchanged`。

需要部署的服务端以及客户端分发宿主应处于运行状态。正常停服使用无限等待，不以超时强制杀进程；管理员中断或启动/清单失败时，尝试恢复本次修改的 JAR、配置、用户/组、客户端来源与原先运行状态，并恢复此前启用的检查 timer。备份及回执保存到 `/var/lib/minecraft-mod-deploy/backups/<时间-唯一编号>/`。恢复失败会明确报错并保留回执，不宣称成功。

回滚恢复的是操作前磁盘文件和进程运行状态，不回滚世界。若此前磁盘 JAR 与已加载版本不同，恢复后启动加载的是操作前磁盘版本。维护时仍应使用既有世界备份安排。部署命令不会推送 Git、发布应用或更新 packwiz。

## 验证

```sh
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 -m py_compile scripts/stage_releases.py
```

测试用构造的真实 JAR/校验文本和隔离文件系统覆盖两个模组、旧 CCO 校验格式、完整备份、模组元数据拒绝、SHA/大小拒绝、幂等、低版本跳过、模块独立失败、部分目标恢复及文件锁。真实 symlink 逃逸测试仅在 POSIX 执行，需要在 Linux 上确认无跳过。服务安装后还须实际查询两个公开 Release，核对暂存 SHA256、timer 下一次触发时间，以及现用文件和 Minecraft 启动时间保持一致。
