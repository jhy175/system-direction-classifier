# Windows 本地启动

在本项目根目录 `system-direction-classifier` 打开 PowerShell，每次启动运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1
```

脚本使用项目本地 JDK 8，启用 `dev` 配置，将日志与临时文件放在 `logs` 下。在终端看到 `Started SystemDirectionClassifierApplication` 后，检查：

```powershell
Invoke-RestMethod http://localhost:9082/actuator/health
Invoke-RestMethod 'http://localhost:8082/api/users?pageIndex=1&pageSize=10'
```

业务服务端口为 8082，监控端口为 9082。启动后访问 [学科交叉识别平台](http://localhost:8082/)，即可使用工作台及五个业务入口。前端暂时使用 mock 数据，操作记录保存在当前浏览器的 localStorage 中，不调用算法或模型服务。现有后端 API 保持可用。后端默认使用 H2 内存数据库，重启后数据库数据重置，无需另启 MySQL、Redis、Nacos。

页面迁移说明、文件分工与验证方式见 [Web 页面说明](docs/zh-CN/classifier-ui.md)。

前台运行时按 Ctrl+C 停止；已有实例运行时不要重复启动。

修改源码后，在项目根目录重新打包，再启动：

```powershell
. .\scripts\env.ps1
$env:MAVEN_OPTS = '-Duser.home=C:\Users\JHY'
mvn -B -ntp package '-DskipTests' '-Dmaven.javadoc.skip=true'
```

仅普通重启无需重新构建。构建命令跳过单元测试，启动验证以实际健康检查和接口响应为准。
