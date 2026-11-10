# Windows 本地启动

用户、权限和团队模块已接入持久化 MySQL；识别算法相关工作台仍保留演示数据。完整功能、接口与维护说明见 [用户模块交付说明](docs/zh-CN/user-module-completion.md)，验收结果见 [测试报告](docs/zh-CN/user-module-test-report.md)。

在项目根目录打开 PowerShell：

```powershell
# 准备或重新启动项目专用 MySQL（默认 127.0.0.1:3307）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-mysql.ps1

# 启动应用，使用 dev,mysql 配置
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1
```

启动后访问 [用户与团队](http://localhost:8082/identity/) 或 [识别工作台](http://localhost:8082/)。健康检查为 [运行状态](http://localhost:9082/actuator/health)。接口现已鉴权，直接匿名访问 /api/users 返回401属于预期行为。

初始管理员账号和随机密码保存在本机 `.local/user-module.properties`，分别为 adminLogin 和 adminPassword。文件不进入 Git；不将实际密码复制到源码、说明文档或测试报告。后续通过页面修改密码后，以修改后的密码为准。

业务库为 direction_classifier，独立测试库为 direction_classifier_test；数据库文件位于 `.tools/mysql/data`。普通应用重启不会清空用户、授权和团队数据，但需要重新登录。数据库未运行时先执行 setup-mysql.ps1；无需启动机器上其他 MySQL 服务、Redis 或 Nacos。

修改源码后先停止项目应用，再构建，避免运行中的 JAR 被 Windows 占用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-users.ps1
. .\scripts\env.ps1
$env:MAVEN_OPTS = '-Duser.home=C:\Users\JHY'
mvn -B -ntp package '-Dmaven.javadoc.skip=true'
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1
```

独立测试环境使用另一终端：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-users.ps1 -TestDatabase -Port 8083 -ManagementPort 9083
python .\scripts\test-users.py
```

停止测试环境使用 `scripts/stop-users.ps1 -Port 8083`。停止两套环境后再重新打包。数据库与应用的本地配置和日志位置均见交付说明。
