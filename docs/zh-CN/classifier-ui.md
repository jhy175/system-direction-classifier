# 学科交叉识别平台 Web 页面

原型 `ADE791BAE589AFE5ABB9E5B0_HTMLBAE781/interdisciplinary_ui` 的界面与交互已迁入现有 Maven 工程的 Web 模块。保留原型的配色、双分支流程图、配置面板、表格、弹窗和抽屉，无需 npm、CDN 或独立前端服务。

## 启动与入口

按仓库 `STARTUP-WINDOWS.md` 打包并启动后，打开 `http://localhost:8082/`。Spring Boot 自动提供 Web 模块的 `public/index.html`，资源随 Web JAR 和启动 JAR 一同打包。

| 页面 | 地址片段 |
| --- | --- |
| 总览 | `#overview` |
| 识别方法 | `#methods` |
| 方法流程及版本 | `#methodDetail?methodId=m1&methodVersion=v2.3&methodView=version&methodTab=flow&methodStage=router` |
| 学科知识库 | `#knowledge` |
| 知识库资源及版本 | `#kbDetail?kbId=kb1&kbVersion=v1.6&kbVersionMode=version&kbTab=profiles` |
| 单篇调试 | `#debug?dbgMethodId=m1&dbgVersion=v2.3` |
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
| `classifier/app.js` | 页面渲染、表单、流程配置、调试与任务交互 |
| `classifier/mock-data.js` | 方法、知识库、模型连接、任务的初始演示数据 |
| `classifier/mock-store.js` | mock 数据本地读写、独立存储命名空间、凭据字段排除 |
| `classifier/router.js` | 页面、对象 ID、版本、页签与阶段的 hash 路由 |

目前是 Web 层的 mock 演示，不增加无实际业务逻辑的 Service、Manager 或 DAO 占位实现，也不依赖相邻 Python 算法工程。后续接入时，将持久化与模拟识别动作替换为 Web Controller → Service → Manager/DAO 或算法客户端调用；页面的版本、资源、模型连接和运行快照仍保持各自边界。

## mock 行为

- 方法、知识库均通过独立草稿编辑，保存版本时复制快照并填写说明。
- 方法保存版本引用特定知识库版本，知识库更新不会自动改写旧方法。
- 单篇结果、人工干预、历史快照及重跑均为模拟，未执行真实模型推理。
- 知识库生成采用预览、确认写入草稿流程，编辑画像会标记摘要待更新。
- 批量任务支持 UTF-8 CSV、字段映射、异常行检查、明确跳过异常行、模拟执行与 CSV 导出；暂不支持 XLSX。
- 部分资源导入入口保留原型的说明弹窗，尚未接入实际文件写入；资源可通过表单编辑及 JSON 导出。
- 模型连通性测试为演示状态，API Key 输入禁用，不保存真实凭据。
- 本地存储键为 `direction-classifier.mock.v1`，与原型的 `ici-demo-v7` 隔离。删除这一键并刷新可恢复初始演示数据；不同浏览器之间不共享数据。

## 验证

在项目根目录运行 `node scripts/check-classifier-ui.cjs`，无须安装 npm 依赖。检查页面导航、书签恢复、版本不可变、未保存离开提示、人工调整与历史快照隔离、资源待更新、批量异常数据和连接编辑。

Maven 打包与原工程一致；页面资源通过标准 Spring Boot 静态资源机制发布，已有 `/api/*` 与 `/actuator/*` 不受页面路由影响。
