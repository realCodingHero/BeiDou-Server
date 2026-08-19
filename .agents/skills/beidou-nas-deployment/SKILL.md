---
name: beidou-nas-deployment
description: Standard and reliable workflow for deploying and updating BeiDou services (game server, scripts, WZ, and Docker Compose configurations) on the Synology NAS environment (192.168.1.57). Use whenever deploying code changes, Java binaries, scripts, or compose updates to the NAS formal server.
---

# BeiDou NAS Deployment & Synchronization Skill

Use this skill when deploying or updating the BeiDou server, NPC/event scripts, WZ data, or Docker Compose configurations on the Synology NAS formal server (`192.168.1.57`).

---

## 1. 核心架构与职责边界（严禁跨界）

| 节点 / 角色 | 职能定义 | 绝对禁止事项 |
| :--- | :--- | :--- |
| **本地开发机 (Windows)** | **唯一的研发、编译与产物构建中心**<br>- 编写 Java / 脚本 / 配置代码<br>- 本地 Docker Maven 容器打包 `BeiDou.jar`<br>- 管理 GitHub 仓库、PR 及分支 | 严禁在未经本地编译与 Research 环境验证前盲目部署到 NAS |
| **Synology NAS (`192.168.1.57`)** | **纯运行节点 (Runtime Host)**<br>- 运行 `beidou-server-all`, `beidou-ui`, `beidou-db`<br>- 通过 `/volume3/docker/BeiDou-docker` (Git) 维护 Compose 配置<br>- 挂载 `/volume3/docker/BeiDou-docker/beidou-server-release` 运行服务端 | **严禁在 NAS 上克隆 `BeiDou-Server` 源码仓库**<br>**严禁在 NAS 上运行 Maven 编译容器** |

---

## 2. 标准部署操作流程

### 场景 A：更新 Docker 容器编排或 NAS 基础设施配置

适用于修改 `docker-compose-nas.yml`、环境变量或网络别名等：

1. **本地修改与 PR 合并**（遵守全局 Git 规范）：
   ```powershell
   cd c:\Game\BeiDou-docker
   git checkout -b feat/your-nas-compose-update
   # 修改文件...
   git add docker-compose-nas.yml
   git commit -m "feat(docker): update nas compose config"
   git push -u origin feat/your-nas-compose-update
   gh pr create --title "feat: update nas compose config" --body "..."
   gh pr merge --rebase --delete-branch
   git checkout master
   git pull origin master
   ```
2. **NAS 端拉取最新配置并应用**：
   ```powershell
   ssh -o BatchMode=yes jywang@192.168.1.57 'cd /volume3/docker/BeiDou-docker && git pull origin master'
   ssh -o BatchMode=yes jywang@192.168.1.57 'export PATH=$PATH:/usr/local/bin; cd /volume3/docker/BeiDou-docker && docker compose -f docker-compose-nas.yml up -d'
   ```

---

### 场景 B：更新游戏服务端 Java 逻辑、NPC / 任务脚本或 WZ 资源

适用于修改 `QuestHelpService.java`、`EquipShopService.java`、`questHelper.js` 等业务逻辑：

1. **本地研发与 PR 合并**：
   在 `c:\Game\BeiDou-Server` 中开发，通过特性分支、PR 及 Rebase 合并至 `master`。
2. **本地编译打包产物**：
   使用本地 Docker Maven 容器打包生成完整嵌入依赖的 `BeiDou.jar`：
   ```powershell
   docker run --rm -v "c:/Game/BeiDou-Server:/app" -v "c:/Game/BeiDou-docker/maven-repo:/root/.m2/repository" -w /app/gms-server maven:3.9.6-eclipse-temurin-21 mvn package -DskipTests
   ```
3. **同步本地 Release 副本**：
   ```powershell
   Copy-Item "c:\Game\BeiDou-Server\gms-server\target\BeiDou.jar" "c:\Game\BeiDou-docker\beidou-server-release\BeiDou.jar" -Force
   Copy-Item "c:\Game\BeiDou-Server\gms-server\scripts-zh-CN\npc\questHelper.js" "c:\Game\BeiDou-docker\beidou-server-release\scripts-zh-CN\npc\questHelper.js" -Force
   Copy-Item "c:\Game\BeiDou-Server\gms-server\scripts\npc\questHelper.js" "c:\Game\BeiDou-docker\beidou-server-release\scripts\npc\questHelper.js" -Force
   ```
4. **NAS 端备份与 SCP 传输**（**必须使用 `-O` 参数**）：
   ```powershell
   # 1. 备份 NAS 当前 jar
   ssh -o BatchMode=yes jywang@192.168.1.57 'cp /volume3/docker/BeiDou-docker/beidou-server-release/BeiDou.jar /volume3/docker/BeiDou-docker/beidou-server-release/BeiDou.jar.bak_$(date +%Y%m%d%H%M%S)'
   
   # 2. 传输产物与脚本（必须带 -O 参数以兼容群晖 OpenSSH）
   scp -O "c:\Game\BeiDou-Server\gms-server\target\BeiDou.jar" jywang@192.168.1.57:/volume3/docker/BeiDou-docker/beidou-server-release/BeiDou.jar
   scp -O "c:\Game\BeiDou-Server\gms-server\scripts-zh-CN\npc\questHelper.js" jywang@192.168.1.57:/volume3/docker/BeiDou-docker/beidou-server-release/scripts-zh-CN/npc/questHelper.js
   scp -O "c:\Game\BeiDou-Server\gms-server\scripts\npc\questHelper.js" jywang@192.168.1.57:/volume3/docker/BeiDou-docker/beidou-server-release/scripts/npc/questHelper.js
   ```
5. **平滑重启并验证日志**：
   ```powershell
   # 重启容器
   ssh -o BatchMode=yes jywang@192.168.1.57 'export PATH=$PATH:/usr/local/bin; docker restart beidou-server-all'
   
   # 检查日志与端口
   ssh -o BatchMode=yes jywang@192.168.1.57 'export PATH=$PATH:/usr/local/bin; docker logs --tail 30 beidou-server-all'
   ```

---

## 3. 关键规则与避坑指南 (Critical Rules)

1. **SCP 传输群晖 NAS 必须加 `-O` 参数**：
   群晖 DSM 的 OpenSSH 默认不支持新版 SFTP 协议握手，不加 `-O` 会报 `dest open ... No such file or directory` 错误。
2. **非交互式 SSH 命令必须加 `-n` 参数并补充 PATH**：
   - 使用 `ssh -n -o BatchMode=yes` 防止 Windows OpenSSH 挂起维持输入管道。
   - 群晖非登录 Shell 默认 `PATH` 不包含 `/usr/local/bin`，执行 Docker 命令前必须添加 `export PATH=$PATH:/usr/local/bin`。
3. **重建容器避免遗留 Compose Replace 标签**：
   - 若需重建容器，推荐使用 `docker compose -f docker-compose-nas.yml rm -sf <service>` 再 `up -d`，避免触发 Docker Compose 就地替换导致群晖 Container Manager UI 显示 `[ID]_[name]` 前缀。
4. **严格分离本地构建与远程运行**：
   NAS 设备侧重稳定性和存储，切忌在 NAS 上克隆几十万行 Java 源码并执行 Maven 依赖下载与编译。
5. **Git 分支安全规范**：
   任何涉及 `BeiDou-docker` 或 `BeiDou-Server` 的代码修改，严禁在 `main`/`master` 直接提交，必须遵循 `Feature Branch -> Push -> PR -> Rebase Merge` 规则。
