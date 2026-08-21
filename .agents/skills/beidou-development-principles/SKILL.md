---
name: beidou-development-principles
description: Mandatory development principles, strict requirement compliance rules, and workflow standards for all coding agents in the BeiDou project. Use whenever designing, modifying, or testing server logic, scripts, NPC interactions, or deploying changes.
---

# BeiDou 研发核心工作准则与规范

本技能定义了 BeiDou 项目中所有编码 Agent 必须无条件严格遵守的核心工作准则、需求合规底线及工程规范。

---

## 一、需求严格遵从准则（核心红线）

### 1. 严禁擅自脑补需求与添加暗箱特权
- **零暗箱特权**：严禁私自在玩家业务代码（如 NPC 交互、任务辅助、副本门槛、经济消耗、掉落获取等）中添加任何未在用户需求中明确声明的“GM 特权豁免”（如 `if (player.isGM()) return true;`）；
- **业务与管理严格隔离**：常规玩法与业务逻辑必须 100% 保持严谨与纯粹，面向所有角色一视同仁。GM 管理与调试有服务端原生的指令系统（如 `!warp`, `!item` 等）支撑，绝不可在业务逻辑层强塞特权旁路；
- **真实还原普通玩家环境**：任何功能的限制、门槛与扣费规则，必须确保管理员在通过常规 UI 交互时也能准确验证其受限行为与错误拦截，避免因私设特权导致测试失真。

### 2. 零假设与主动确认原则
- **不擅作主张**：若遇到需求中未明确提及的边界情况、异常分支或设计抉择，**严禁自行推测并直接写进代码**；
- **主动请示**：必须第一时间将疑问与备选方案呈现给用户，在获得用户明确确认后再行实施。

---

## 二、Git 工作流与分支规范（全局强制）

1. **禁止直接修改主干**：
   - 任何代码、脚本或配置修改，**严禁直接在 `main` 或 `master` 分支上操作**；
2. **标准工作流程**：
   - ① 从最新主干创建分支：`git checkout -b <feat/fix/chore-branch-name>`
   - ② 提交并推送到远端：`git commit` $\rightarrow$ `git push -u origin <branch>`
   - ③ 创建 Pull Request：`gh pr create`
   - ④ 确认与 Rebase 合并：经用户确认后使用 `gh pr merge --rebase --delete-branch` 合并
3. **PR 合并权限与确认红线（严禁擅自合并）**：
   - **除非经过用户确认功能已完善或者明确需要直接合并，任何功能类与 bug 修复类 PR 不得擅自合并**；
   - 提交 PR 后，必须等待用户实测验证、明确反馈功能符合预期并同意合并，或明确收到用户直接合并的指令后，方可执行合并操作；
   - 严禁在 PR 创建后自动、直接执行 `gh pr merge`，必须保留 PR 供用户审查与确认；
4. **主干线性历史维护**：
   - 保持 Git 提交历史清晰线性，合并后及时同步本地 `master`。

---

## 三、编译验证与双环境部署规范

1. **本地先行验证**：
   - 所有的 Java 核心改动必须先在本地通过 Docker Maven 容器执行完整的单元测试（`mvn test`）；
   - 确认 100% 测试通过并打包出包含依赖的 `BeiDou.jar`，在本地 Research 环境（`beidou-research-nightly-server`）验证无误后，方可向正式服推进。
2. **Synology NAS 正式服安全部署（遵照 `beidou-nas-deployment`）**：
   - 必须通过 SSH 在 NAS 上先完成历史 jar 的带时间戳备份；
   - 必须使用 `scp -O` 参数向 NAS 传输构建产物与脚本，杜绝 SFTP 协议兼容问题；
   - 重启容器（`beidou-server-all`）后，必须通过 `docker logs` 追踪确认大区启动就绪、登录端口 `8484` 及所有频道端口（7575~7577）正常开放监听。

---

## 四、编码与架构规范

1. **类型导入规范**：
   - 统一在文件头部使用 `import` 显式引入类，**严禁**在方法签名或属性声明中使用 `java.util.List<...>` 等包名内联写法（仅同名类冲突时例外）；
2. **文本与日志 i18n 约束**：
   - 面向用户或运维输出的文本、提示弹窗、错误日志等，必须通过 i18n 资源文件规范化管理（`I18nUtil`），避免硬编码中文或英文；
3. **保持文档与注释完整**：
   - 遗留 OdinMS/Cosmic 核心代码改动时，必须保留原有版权头与周边注释风格，不随意删除不相关注释。
