# 用户、权限与团队模块交付说明

适用项目：学科交叉算法调试系统。交付日期：2026-11-11（北京时间）。本文说明实际实现的用户模块、运行方式、接口约定和验收范围；不将其他业务页面的演示功能算作已实现功能。

## 1. 已实现功能范围

| 模块 | 已实现内容 |
| --- | --- |
| 本地 MySQL | 项目独立数据目录；专用数据库账号；业务库与测试库；重复启动与凭据复用 |
| 数据结构 | 已确认的九张业务表；Liquibase 建表与迁移记录；普通字段允许 NULL |
| 登录与退出 | 账号密码登录、退出、30分钟空闲会话、会话固定攻击防护、CSRF 校验、同源校验、HttpOnly 与 SameSite=Strict Cookie |
| 密码 | 带随机盐的 PBKDF2-SHA256 哈希；个人修改密码；管理员设置／重设密码；密码变更后旧会话失效 |
| 个人资料 | 显示名称、邮箱、电话、头像地址、单位、部门、语言、备注和界面偏好；显式清空可选字段 |
| 用户管理 | 创建、详情、搜索、分页、资料编辑、启用、停用、锁定、解锁；默认创建为待启用状态 |
| 系统角色 | 创建、编辑、详情、搜索、分页、启停、删除；用户角色分配与撤销 |
| 功能权限 | 权限目录、树、创建、编辑、删除、角色权限分配；有效权限实时聚合 |
| 导航菜单 | 菜单目录、树、创建、编辑、删除、显隐、角色菜单分配；父级循环和非空子树删除保护 |
| 团队 | 创建、列表、搜索、详情、编辑、停用／启用；创建时自动建立负责人成员关系 |
| 团队成员 | 添加、角色设置、停用／移除、主动退出、重新加入、负责人转移；团队管理员与普通成员的权限区分 |
| 操作边界 | 账户停用与锁定即时阻止请求；撤权即时生效；最后一名系统管理员和团队负责人保护 |
| 管理页面 | `/identity/` 下的登录、个人资料、用户、角色、权限、菜单和团队页面；表单、错误提示、分页及授权选择 |

第一期仅通过 HTTP 接口建立登录身份。保留原 Java 服务接口作为兼容适配层，其调用同样进入统一鉴权逻辑；不把尚未实现可信身份传递的 Dubbo 远程登录／鉴权列为已交付能力。当前 dev,mysql 配置关闭远程 Dubbo 依赖。

## 2. 环境、库与文件位置

| 项目 | 配置 |
| --- | --- |
| Java | 项目 `.tools` 下的 JDK 8 |
| MySQL | 使用本机已安装的 MySQL Server 8.4，脚本默认定位 `C:\Program Files\MySQL\MySQL Server 8.4` |
| 数据库监听 | `127.0.0.1:3307`，不修改机器已有 MySQL Windows 服务 |
| MySQL 数据目录 | `.tools/mysql/data`，位于项目内且不进入版本库 |
| 业务库 | `direction_classifier` |
| 测试库 | `direction_classifier_test` |
| 应用数据库账号 | `classifier_app`，仅授予这两个库的权限；应用不使用 root |
| 本地凭据 | `.local/user-module.properties`，生成随机密码、限制本地文件访问、Git 忽略 |
| 默认管理员账号 | 由本地凭据文件的 `adminLogin` 确定，初始化默认为 `admin` |
| 管理员密码 | 本地凭据文件的 `adminPassword`；文档、脚本源码和测试报告均不写入实际密码 |
| 应用／监控 | 正常环境 `127.0.0.1:8082`／`127.0.0.1:9082`；测试环境 8083／9083 |
| 应用配置 | `dev,mysql`：复用 dev 中关闭外部基础设施的配置，并覆盖数据源为 MySQL |

数据库进程由本地脚本启动，不注册系统服务。MySQL 启动使用相对 basedir、datadir，并单独指定安装目录中的插件和消息文件路径，解决当前中文项目路径下的命令参数解析问题。

## 3. 初始化与正常使用

在项目根目录执行：

```powershell
# 首次准备数据库；之后电脑重启或 MySQL 停止时也可重新执行
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-mysql.ps1

# 停止旧应用后构建，避免 Windows 下运行中的 JAR 被占用
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-users.ps1
. .\scripts\env.ps1
$env:MAVEN_OPTS = '-Duser.home=C:\Users\JHY'
mvn -B -ntp package '-Dmaven.javadoc.skip=true'

# 启动正常环境
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1
```

访问 `http://localhost:8082/identity/`，使用本地凭据文件中的管理员账号和密码登录。识别工作台侧边栏也提供“用户与团队”入口。

首次启动时 Liquibase 创建九张表，随后在同一事务中初始化 ADMIN、USER、权限目录、导航菜单和管理员。只有用户表为空时才初始化账户；重启不会重置已有密码、账户资料和授权。后续管理员若修改了自己的密码，应使用新密码登录，本地最初生成的 adminPassword 不会覆盖数据库中的新密码。

首次初始化发生中断时可重新运行数据库脚本；脚本首先使用已保存凭据连接，只在本项目尚未完成初始化时尝试新实例的空密码，且在变更前核实服务器 UUID 与本项目数据目录的 auto.cnf 一致。UUID 校验避免依赖当前 MySQL 对中文数据目录名称的错误编码。

`setup-mysql.ps1` 支持 `-MysqlHome` 指定已安装的 MySQL 目录，支持首次初始化时用 `-Port` 选择端口。已有凭据文件中的端口在后续启动中保持固定。不删除 `.local` 或 `.tools/mysql/data` 以实现普通重启。

Windows 文件权限以实际使用者为准：当前明确授权的账号为 `LAPTOP-BDKBMPCD\JHY`。项目目录对该账号设置可继承的完全控制权限；独立保护的凭据文件也保留该账号的完全控制权限。初始化脚本对新文件和已有文件均应用此规则，避免仅沙盒账号可读。另一台机器部署时，可用 `-CredentialOwner '计算机名\用户名'` 指定实际用户。

其他功能若新建受保护的本地文件，应复用 `scripts/set-local-access.ps1 -Path <项目内路径> -Private`；该脚本只管理项目内路径，并核验实际用户权限。项目根目录 `AGENTS.md` 已记录这一持续要求。

## 4. 数据表与架构落点

九张业务表：`demo_user`、`demo_role`、`demo_permission`、`demo_menu`、`demo_user_role`、`demo_role_permission`、`demo_role_menu`、`demo_team`、`demo_team_member`。另有 Liquibase 自身的迁移记录与锁表。

主键、登录账号、角色／权限编码与名称、菜单名称、团队名称和负责人，以及关系双方 ID 必填；其余字段允许 NULL。数据库默认值不阻止显式写入 NULL。账号、邮箱、角色编码、权限编码、非空菜单路径具有相应唯一约束。邮箱和可选字符串的空字符串统一转 NULL。

| 层 | 实现位置与职责 |
| --- | --- |
| API | `api/identity/IdentityService` 定义用户模块契约；`CurrentActor` 保存当前请求身份；`IdentityException` 表达业务错误 |
| DAO | `dao/IdentityDAO` 使用参数化 JDBC 访问九张表，支持显式 SQL NULL 和必要的行锁；表名与列名受内部白名单约束 |
| Manager | `manager/identity/IdentityManager` 统一字段校验、权限、关联、管理员保护、团队状态和负责人事务规则；`PasswordHasher` 负责哈希与验证 |
| Service | `service/impl/IdentityServiceImpl` 提供事务边界；原四个 Java 服务实现通过适配器复用同一业务规则 |
| Web | `web/identity` 中的认证、通用资源、团队控制器、异常处理与会话拦截器；Undertow 定制 SameSite Cookie |
| 启动 | `config/IdentityBootstrap` 在迁移后初始化空用户库；MySQL 配置从环境变量读取凭据 |
| 前端 | `public/identity` 独立管理页面，使用真实 HTTP 接口；主工作台加载已登录用户的高级配置展示偏好 |
| 迁移 | `db/changelog/db.changelog-users.yaml` 与 `users/2.0.0-schema.sql`；不改写已执行的旧 1.0.0 变更集 |

本次是专用新 MySQL 库落地，不是对未知存量数据库的无损升级。已统一用户的 password_hash、created_at、updated_at 等映射，去除重复的 activated／locked 状态字段；原 H2 内存演示数据不迁入新库。

## 5. HTTP 接口约定

所有下列路径以 `/api` 为前缀。成功返回 `{ "success": true, "data": ... }`，业务错误返回 `{ "success": false, "message": "..." }` 并使用对应 HTTP 状态码。

- 400：字段或请求格式错误。
- 401：未登录、会话失效或账户不可用。
- 403：没有权限、CSRF 不匹配或请求来源不符。
- 404：目标记录不存在。
- 409：唯一约束冲突、状态或关系约束冲突。
- 429：登录失败频率超过限制。

分页列表 data 为 `{ items, total, pageIndex, pageSize }`，默认每页20条，最大100条。可使用 `q` 搜索账号或名称，使用 `status` 筛选已知状态。树接口返回带 children 的数组。

PUT／PATCH 资料更新均采用字段增量语义：未提交字段保持原值；显式传入 NULL 清空可选字段。不能清空关键标识。设置密码使用 `password` 字符串，不接收 passwordHash 字段；留空且不提交 password 表示保留原密码。

### 5.1 登录与个人资料

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/auth/csrf` | 建立或读取匿名／当前会话的 csrfToken |
| POST | `/auth/login` | `{login,password}`，成功返回安全用户信息和新的 csrfToken |
| POST | `/auth/logout` | 注销当前会话 |
| GET | `/auth/me` | 当前用户资料、有效角色、有效权限和可见菜单 |
| PATCH / PUT | `/auth/me` | 更新个人资料；不允许自行修改账号、状态或角色 |
| PUT | `/auth/password` | `{oldPassword,newPassword}`；成功后需重新登录 |

客户端需保存 Cookie，并在所有写请求中携带 `X-CSRF-Token`。登录前先读取 `/auth/csrf`；登录成功后用响应中的新令牌替换旧令牌。密码长度10至128字符。密码为 NULL 的账户不能使用本地密码登录。连续登录失败达到每来源 IP 20次／5分钟后，后续登录请求返回429；该计数属于当前应用进程。

### 5.2 账户、角色、权限、菜单与团队

对 `users`、`roles`、`permissions`、`menus`、`teams` 均支持：

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| GET | `/{resource}` | 搜索和分页 |
| POST | `/{resource}` | 创建，返回新记录 |
| GET | `/{resource}/{id}` | 详情 |
| PATCH / PUT | `/{resource}/{id}` | 增量修改 |
| DELETE | `/{resource}/{id}` | 用户／团队改为停用；其余资源按关系约束删除 |

其他接口：

| 方法 | 路径 | 内容 |
| --- | --- | --- |
| PUT | `/users/{id}/activate`、`disable`、`lock`、`unlock` | 账户状态操作 |
| GET / PUT | `/users/{id}/roles` | 读取／替换用户角色；写入为整数 ID 数组，空数组表示撤销全部角色 |
| GET | `/users/{id}/permissions`、`menus` | 聚合用户权限／菜单 |
| PUT | `/roles/{id}/enable`、`disable` | 角色启停 |
| GET / PUT | `/roles/{id}/permissions`、`menus` | 读取／替换角色授权，写入为整数 ID 数组 |
| GET | `/permissions/tree`、`/menus/tree` | 目录树 |
| PUT | `/menus/{id}/show`、`hide` | 菜单显隐 |
| PUT | `/teams/{id}/enable`、`disable` | 团队启停 |
| GET | `/teams/{id}/members` | 当前及已停用成员列表 |
| PUT | `/teams/{id}/members/{userId}` | 添加／更新成员，支持 memberRole、status；空对象新增普通有效成员 |
| DELETE | `/teams/{id}/members/{userId}` | 移除成员或本人退出，保留关系记录 |
| PUT | `/teams/{id}/owner` | `{userId}` 转交负责人，目标必须是有效成员 |

创建示例：

```json
{
  "login": "researcher01",
  "displayName": "研究用户",
  "password": "replace-with-your-own-password",
  "status": 1,
  "email": null
}
```

```json
{
  "name": "学科识别研究组",
  "description": null
}
```

普通用户创建团队时负责人默认为自己。具备 team:manage 的用户可在创建时明确指定 ownerUserId；后续只能通过专用转交接口更换负责人。

## 6. 权限与状态规则

系统功能权限与团队身份分别保存。ADMIN 是初始化的管理角色，其能力来自明确的 role_permission 记录，不通过角色名称跳过所有授权。USER 默认拥有个人使用、创建团队等能力；应用客户与算法开发者没有固定的页面能力隔离。

管理接口按 user:manage、role:manage、permission:manage、menu:manage、team:manage 等权限校验。普通用户可查看自己的资料与有效授权，但不能读取其他账户的私有资料或调用管理接口。团队列表只返回本人拥有／有效加入的团队；team:manage 授予显式的全团队管理能力。

团队普通成员可读取有效团队及其成员；团队管理员可维护普通成员，不能自行提权、处理其他管理员或转交负责人；负责人可管理团队和成员、分配团队管理员、转交团队。团队管理员身份不授予系统管理权限。

NULL 状态或未知成员角色不参与授权。账户停用、锁定、密码变更后，已有会话将在后续请求失效；角色停用和权限撤销在后续请求立即生效，不等待退出重登。系统角色与菜单的关联只影响菜单显示，不替代接口鉴权。权限目录父子关系也不自动产生权限继承。

事务约束包括：至少保留一名启用的系统管理员；内置 ADMIN／USER 编码和内置管理权限受到保护；仍负责启用团队的用户不能被停用；负责人必须保持有效成员身份；转交负责人必须选择有效且启用的成员；退出后重新加入不自动恢复历史管理员身份。目录修改与团队修改采用必要的行锁防止关系不一致。

## 7. 验收与复现

以下为本部分的验收入口；具体本次结果见同目录 `user-module-test-report.md`。

```powershell
# 运行单元测试并构建
. .\scripts\env.ps1
$env:MAVEN_OPTS = '-Duser.home=C:\Users\JHY'
mvn -B -ntp package '-Dmaven.javadoc.skip=true'

# 新终端启动独立测试环境，不覆盖业务库
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-users.ps1 -TestDatabase -Port 8083 -ManagementPort 9083

# 使用已安装 Python 3 运行 HTTP 验收
python .\scripts\test-users.py

# 重启应用或数据库后，验证同一批用户与团队仍存在
python .\scripts\test-users.py --persistence-only
```

验收脚本固定只允许8083端口，使用测试库，不清空或删除数据库；每轮创建唯一名称的测试账户和团队。详细 HTTP 断言结果写入 `logs/users-test-results.json`；重启结果写入 `logs/users-persistence-results.json`。凭据不会写入报告，持久化验证对象 ID 保存在 `.local/user-test-fixture.json`。

实际库结构可用 `scripts/UsersDatabaseCheck.java` 检查，它验证九张表全部84列的可空约束、已保存密码的哈希格式、迁移仅执行一次及负责人成员关系。三个 PasswordHasher 单元测试位于 manager 模块。

## 8. 运行维护与本次边界

停止应用使用 `scripts/stop-users.ps1`，测试环境加 `-Port 8083`；脚本核实端口和项目 JDK 后仅停止本项目进程。停止前不删除账户、团队或数据库。打包前应先停应用，完成打包后再启动。

用户模块数据由 MySQL 持久化；登录会话、登录限频计数保存在应用内存，应用重启后需重新登录。启动脚本按端口设置 CLASSIFIER_SESSION_8082／CLASSIFIER_SESSION_8083，避免正常与测试环境的 Cookie 相互覆盖。此版本按单机本地运行验收，不包含多实例共享会话、互联网 HTTPS 部署、SSO、注册邀请、邮箱验证、找回密码或完整事件审计流水。

识别方法、学科知识库、单篇调试、批量识别与模型连接的业务存储仍未在本部分实现，相关工作台保持原有演示状态。本部分没有实现实际算法调用、交叉度计算或研究要素识别。后续资源通过稳定的 user_id、team_id 接入，具体资源所有权与共享授权将在相应模块实现。
