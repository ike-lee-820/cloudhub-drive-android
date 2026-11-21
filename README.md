# 云枢网盘 Android 客户端

基于 Kotlin 与 Jetpack Compose 的手机端网盘应用，对接云枢网盘接口文档 1.0.0，默认服务基址 `https://cloud.iobit.de5.net/`。项目由 GitHub Actions 自动构建 **Release 签名 APK** 并发布到 GitHub Release。

## 版本号规则

`年.月.次数`，例如 `26.11.1` 表示 2026 年 11 月的第 1 次发布。

- `versionName` 由工作流自动计算：读取仓库中同月 `vYY.MM.N` 标签，取最大 N 加一
- `versionCode = 年 * 10000 + 月 * 100 + 次数`，例如 `26.11.1` 对应 `261101`
- 每次推送 `main` 自动生成一个新标签 `vYY.MM.N` 与同名 Release，并附带 APK

## Release 签名

CI 从仓库 Secrets 解码 `keystore.jks` 后签名，签名密钥长期固定，保证应用内下载的新包可以覆盖安装。本地无密钥时构建脚本回退到 debug 签名，仅用于开发调试，不用于分发。

涉及的 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。密钥文件本身不入库。

## 应用内自动更新

- 启动后自动请求 `https://api.github.com/repos/ike-lee-820/cloudhub-drive-android/releases/latest` 检查新版本；设置页提供手动检查入口
- 更新检查与安装包下载优先经 `https://v6.gh-proxy.com/` 前缀代理加速，代理不可用时自动回退直连
- 下载采用 **64 线程 Range 分段**并发，先以 1 字节 Range 探测 `206 Partial Content`；不支持分段的节点自动回退单线程流式下载
- 实时显示进度与速度，合并分片后校验 APK 完整性，再通过 FileProvider 拉起系统安装界面
- Android 8.0 及以上需要用户开启「允许安装未知来源应用」，设置页会给出跳转入口

## 设计说明

界面遵循 Apple Human Interface Guidelines 的核心原则（清晰、顺从、纵深），以 Android Compose 重新表达，不复制参考网页的布局：

- 大标题层级与副标题说明，充分留白，内容优先
- 圆角分组卡片、克制分隔线、连续圆角控件
- 可切换的系统蓝强调色，浅色与深色两套外观
- 全部图标使用矢量图形，应用内不使用表情符号
- 底部四栏导航：文件、分享、任务、设置
- 弹层采用卡片式底部表单与居中确认框，动效克制

## 功能覆盖

| 模块 | 已实现 |
| --- | --- |
| 认证 | 固定密码登录、Token 持久化、`auth/check` 校验、接口根地址可配置 |
| 文件树 | 全量树缓存、本地目录跳转与面包屑、列表与网格、搜索、排序、多选全选 |
| 文件操作 | 新建文件夹、批量建目录、新建文本、重命名、删除、复制、移动、详情查询、直链 |
| 写入 | D1 内联文本 `fs/write`、GitHub 节点重新直传后带 `id` 注册、旧仓库异步清理 |
| 上传 | 客户端直传 GitHub、50 MB 分片、首片串行建分支、全局 32 并发、8 文件队列、指数退避重试、主备账号切换、取消任务、速度计算、1.2 秒进度上报 |
| 分发 | 下载、预览、能力直链、分享分发，均走 Worker 域名，支持 Range 与文件名 |
| 在线预览 | 图片、视频、音频、PDF、Word、Excel、PPT、代码与文本；双 CDN 文档组件加载，失败降级为下载 |
| 在线编辑 | CodeMirror 5 语法高亮与行号，加载失败降级纯文本框，保存按节点类型分流 |
| 分享 | 创建、列表、编辑启停、删除、公开信息、验证码访问校验、分享页与分发链接 |
| 任务 | 任务列表、实时速度与进度、取消、删除、清除已完成、清空全部 |
| 设置 | 主题色、明暗、字体 CDN、本机字体、背景模式与值、账号探测、初始化状态、健康检查、开发者与 API 分区、保存与重置、软件更新 |
| 系统 | 健康检查、D1 初始化状态、上传配置、代理配置、账号可用性、手动删仓 |

所有写操作完成后均重新拉取 `/api/fs/tree` 做静默校准；目录跳转与面包屑在本地过滤，不重复请求。

## 构建

本地构建需要 JDK 17 与 Android SDK 34：

```bash
./gradlew assembleDebug
./gradlew assembleRelease -PappVersionName=26.11.1
```

推送至 `main` 或手动触发 `Android Release` 工作流时，CI 会计算版本号、构建 signed release APK、上传构建产物并创建 GitHub Release。

## 接口约定

- 所有 `/api/*` 接口同时支持 GET 与 POST，客户端按场景选择，参数与响应格式保持文档一致
- 除免凭证接口外统一携带 `X-Auth`
- 时间字段为毫秒时间戳，`ttl` 单位为秒
- 数组参数在 GET 中以逗号分隔，在 POST 中以 JSON 数组传递

## 安全提醒

接口文档 8.1 节中的 GitHub Personal Access Token 属于明文凭据，客户端不会把它们写入源码：应用运行时通过 `/api/config/upload` 动态取得账号与 Token。若这些凭据曾出现在公开对话、代码仓库或日志中，请立即在 GitHub Settings 中撤销并重新生成，并同步更新服务端 Secret。用于创建本仓库的凭证同样建议轮换；签名密钥只保存在仓库 Secrets 中。

## 运行说明

当前构建沙盒对 `cloud.iobit.de5.net` 的出网请求被网络策略拒绝（HTTP 403，策略默认拒绝），因此联调需在可访问该域名的手机或网络环境完成；客户端在「设置 - 接口根地址」中可切换基址。
