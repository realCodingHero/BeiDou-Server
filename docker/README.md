# BeiDou Docker 容器化与运维中心

本目录包含了 BeiDou 服务的全套 Docker 构建、跨架构打包配置及部署编排。

---

## 目录索引

- `build/`
  - `backend.Dockerfile`: 后端服务多阶段构建 Dockerfile（直接基于本地/CI 源码构建）
  - `frontend.Dockerfile`: 前端 Web 控制台多阶段构建 Dockerfile
  - `release.Dockerfile`: Release 单体一体化容器构建 Dockerfile
  - `entrypoint-nightly.sh`: Nightly 容器启动引导与初始化脚本
  - `entrypoint-release.sh`: Release 容器启动引导脚本
  - `nginx-ui.conf`: 前端 Nginx 与 API 反向代理配置
- `compose/`
  - `docker-compose-research.yml`: 本地 Research 测试与开发集群（`127.0.0.2`，直接挂载 `gms-server` 源码目录与产物）
  - `docker-compose-nas.yml`: 群晖 NAS 正式服部署编排（`192.168.1.57`）
  - `docker-compose-release.yml`: Release 单体容器部署编排
- `docker-bake.hcl`: 多架构（amd64 / arm64）与多运行时（Temurin / OpenJ9）构建配置

---

## 快速使用

### 1. 本地 Research 开发与测试环境启动

在 `BeiDou-Server` 根目录下执行：

```bash
docker compose -f docker/compose/docker-compose-research.yml up -d
```

- 登录服务器：`127.0.0.2:8484`
- 频道 1~3：`127.0.0.2:7575 ~ 7577`
- 前端 Web：`http://127.0.0.2:8686`
- MySQL 数据库：`127.0.0.2:3306`（root / root）

### 2. 编译并热更新服务端

```bash
# 1. 快速编译后端产物
docker run --rm \
  -v beidou-m2-cache:/root/.m2/repository \
  -v "${PWD}:/app:ro" \
  -v /app/gms-server/target \
  -v "${PWD}/gms-server/target:/out" \
  -w /app/gms-server \
  -e MAVEN_OPTS="-Xmx2048m" \
  maven:3.9.6-eclipse-temurin-21 \
  sh -c "mvn package -DskipTests && cp target/BeiDou.jar /out/BeiDou.jar"

# 2. 重启 Research 容器
docker restart beidou-research-nightly-server
```
