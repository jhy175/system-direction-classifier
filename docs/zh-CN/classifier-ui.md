# 学科交叉识别平台 Web 页面

工作台由现有 Maven 工程的 Web 模块提供。识别方法、知识库和模型连接读取 MySQL 业务库。页面沿用原型的配色、流程配置面板、表格和弹窗，无需 npm、CDN 或独立前端服务。

## 启动与入口

按仓库 `STARTUP-WINDOWS.md` 打包并启动后，打开 `http://localhost:8082/`。Spring Boot 自动提供 Web 模块的 `public/index.html`，资源随 Web JAR 和启动 JAR 一同打包。

| 页面 | 地址片段 |
| --- | --- |
| 总览 | `#overview` |
| 识别方法 | `#methods` |
| 方法流程及版本 | `#methodDetail?methodId=1&methodVersion=1&methodView=version&methodTab=flow&methodStage=router`，参数使用数据库中的方法 ID 和版本 ID |
| 学科知识库 | `#knowledge` |
| 知识库资源及版本 | `#kbDetail?kbId=kb1&kbVersion=v1.6&kbVersionMode=version&kbTab=profiles` |
| 单篇调试 | `#debug?dbgMethodId=1&dbgVersion=v1` |
| 批量任务 | `#batch` |
| 新建批量任务 | `#batchWizard` |
| 任务详情 | `#taskDetail?batchTaskId=t1` |
| 模型连接 | `#settings` |

支持刷新恢复对象及版本、浏览器前进后退、无效链接回退。新建任务的上传文件和未保存调试输入只在当前页面内存中保留，刷新后需重新输入。离开方法或知识库的未保存编辑时提供保存草稿、放弃或取消。

## 代码分工

文件均位于 `system-direction-classifier-web/src/main/resources/public/`：

| 文件 | 职责 |
| --- | --- |
| `index.html` | 系统首页及脚本装配 |
| `classifier/style.css` | 原型样式和响应式布局 |
| `classifier/app.js` | 工作区导航、总览及单篇、批量演示交互 |
| `classifier/methods.js` | 通过 `/api/methods` 读取和维护方法、草稿与版本 |
| `classifier/mock-data.js` | 批量任务的初始演示数据 |
| `classifier/mock-store.js` | 单篇调试历史与批量演示记录的本地读写 |
| `classifier/router.js` | 页面、对象 ID、版本、页签与阶段的 hash 路由 |

方法新建从已导入的内置方法初始化六阶段草稿。保存新版本时固定知识版本和阶段模型修订，并校验提示词变量与参数。方法列表和详情支持筛选、复制已保存版本、归档与恢复、默认版本设置和从历史版本创建草稿。

## 单篇与批量演示

- 方法和版本选择来自业务库。单篇结果、人工干预、历史快照及重跑使用模拟数据。
- 批量任务支持 UTF-8 CSV、字段映射、异常行检查、明确跳过异常行、模拟执行与 CSV 导出；暂不支持 XLSX。
- 本地存储键 `direction-classifier.mock.v1` 保存单篇和批量演示记录。新版首次读取时会清除该键下的旧方法 mock 数据。

## 验证

在项目根目录运行 `node scripts/check-classifier-ui.cjs`，检查路由、业务库方法引用、本地 mock 清理及单篇和批量演示。对连接 `direction_classifier_test` 且已导入内置方法的 8083 应用，运行 `python scripts/test-methods.py` 验证新建、保存草稿、版本发布、授权与归档。

Maven 打包与原工程一致；页面资源通过标准 Spring Boot 静态资源机制发布，已有 `/api/*` 与 `/actuator/*` 不受页面路由影响。
