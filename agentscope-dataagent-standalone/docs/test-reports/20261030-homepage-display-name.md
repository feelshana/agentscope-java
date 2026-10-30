# 2026-10-30 首页问候显示姓名

基线7db2cca3，用户要求把首页英文账号改为中文名。小范围显示修复，沿用现有用户资料接口，不新增spec/ADR，不改后端、数据库、登录或问数逻辑。

首页通过api/auth.ts的currentUserProfile读取/api/auth/profile，与左下角使用相同资料来源；优先displayName，空白或请求失败时回退账号。请求按当前token及取消信号保护，切换身份时不渲染旧token对应姓名。只在组件挂载/token变化时读取，不在每次问数或token流片段重复请求。

## 本轮验证

- Java test：1008项，0失败、0错误、32条件跳过；跳过项不算实际LLM/生产并发通过。
- 前端三部署前缀各57项通过；TypeScript/Vite按/dify/dataagent/构建通过，既有大bundle警告仍在。
- Spotless/package通过；生产静态资源重新生成。
- 本地Playwright合成接口验证：有姓名显示“下午好，王超”；姓名空字符串时回退demo；资料接口503时仍显示账号且首页可用，503对应预期浏览器网络错误。截图output/playwright/greeting-chinese-name-1440.png已人工查看。
- 本轮未远程连接或修改65/66；浏览器夹具不等于真实BI/模型验收。切账号取消/token保护经代码核对，未将其描述为本轮浏览器竞态实测。

源码、静态资源和记录尚未提交推送。线上仍是此前版本，需后续提交、push、66构建并upgrade才能显示新问候；数据库和Nginx无需为本次显示修复再次修改。原始证据output/greeting-display-name-20261030；仅本轮浏览器和夹具在完成后关闭。
