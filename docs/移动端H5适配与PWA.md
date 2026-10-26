# 移动端（H5）适配与 PWA

前端原本按桌面管理台设计，手机浏览器打开会出现：侧栏占半屏、弹窗超出屏幕、表单一列被挤成竖排、宽表格看不到操作列。本页说明现在的适配约定，改页面时请遵守。

## 断点

统一用 **768px**：

- CSS：`@media (max-width: 768px)`（见 `src/styles/index.css`）
- JS：`useIsMobile()`（`src/composables/useMediaQuery.ts`），首屏即取真实值，不会先按桌面渲染一帧

## 已做的全局适配

| 位置 | 行为 |
| --- | --- |
| `BasicLayout.vue` | 顶栏加汉堡按钮，侧栏变成滑出抽屉（路由切换自动收起）；隐藏全局搜索/语言/租户选择；`100dvh` 适配 iOS 地址栏 |
| `ChatView.vue` | 会话列表变抽屉；气泡宽度 88%；触屏常显「⋯」；输入区贴底 + `safe-area-inset-bottom` |
| `styles/index.css` | 弹窗/MessageBox 宽度改为 `calc(100vw - 24px)`；表单 label 上下排列；行内 `width: 240px` 的输入框加 `max-width: 100%`；`.toolbar` 允许换行 |
| 登录 / SSO | 卡片宽度 `max-width: 100%` |
| 观测 / 指标 / 评测 / 用量 | 指标卡 2 列、图表 1 列 |
| Agent 编辑 | 操作按钮 3 列网格；配置面板与调试面板上下排列 |
| 知识库详情 | 工具栏换行；文档区 / 检索测试上下排列；时间范围占满行 |

## 宽表格 → 卡片列表

`src/utils/mobileTableCards.ts` 在移动端自动把 `el-table` 变成一行一张卡片（`data-label` 取自表头），无需改页面代码。**不适用**并保持横向滚动的表：

- 固定列（`fixed="right"` 的操作列，Element Plus 2.8 用 `el-table-fixed-column--*`）
- 选择列、展开行、树形表格
- 只有一列的表格

新页面如果用了这些特性，无需特殊处理，会自动跳过。

## PWA

| 文件 | 说明 |
| --- | --- |
| `public/manifest.webmanifest` | 名称、图标、`display: standalone`、快捷方式 |
| `public/sw.js` | 导航网络优先（离线回退外壳）；静态资源缓存优先；`/api/` 不缓存 |
| `public/icons/*` | 192/512/maskable/apple-touch 图标 |
| `src/main.ts` | **仅生产构建**注册 SW（`import.meta.env.PROD`），开发环境不注册，避免干扰 HMR |

### 手机安装的前提：必须是 HTTPS

Service Worker / 「添加到主屏幕」只在安全上下文生效：

- `http://localhost` ✅
- `https://你的域名` ✅
- `http://100.x.x.x`（Tailscale 内网 IP）❌ 不会注册 SW，也不能安装

用 Tailscale 在手机上访问时，建议走 HTTPS：

```bash
# 需要先在 Tailscale 管理台开启 HTTPS 证书（MagicDNS）
tailscale serve --bg 5173        # https://mac-studio.<tailnet>.ts.net/ → 本机 5173
tailscale serve status
tailscale serve off              # 关闭
```

正式部署（nginx + 域名 + 证书）后，Chrome/Edge 地址栏会出现「安装」，iOS Safari 用「分享 → 添加到主屏幕」，图标/名称/独立窗口都来自 manifest。

## 验证方式

```bash
cd frontend
npm run build && npx vite preview --host 0.0.0.0 --port 4173
# 手机或 DevTools 设备模式打开 http://<ip>:4173
```

注意：生产构建不再给 `codeSplitting.groups` 设置 `maxSize`。Rolldown 按体积再切分共享模块会导致 chunk 初始化顺序错乱（表现为生产环境白屏、`undefined is not a function`），**不要再加回来**。
