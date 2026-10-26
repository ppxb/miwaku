# Android 原生客户端：产出、API 契约与工程规约

> 状态：草案 v0.1（2026-10-26）  
> 范围：Android 首版，Kotlin + Jetpack Compose，仅支持 JM。  
> 目标：本地优先、零后端依赖、API-only；后端未来只作为可选传输实现。  
> 非目标：iOS、HTML 抓取、多内容源、插件系统、首版云端同步。

## 0. 结论

- **只做 JM API，不做 HTML 爬虫。** 不引入 HTML 客户端、Cookie 浏览器模拟、Cloudflare 页面解析。
- **不做多内容源抽象。** 不建 `ComicSource`、`SourceCapabilities`、多源注册表；只保留一个 `ComicRepository` 边界。
- **命名收敛。** 因为 App 只支持 JM，领域模型、模块、数据库表、资源名不再带 `Jm` 前缀；协议边界由 `network` 包和 DTO 表达。
- **本地优先。** 首版不依赖后端；后端未来作为可选 `ApiClient` / Repository 实现接入，不改变 UI 和领域模型。
- **第一步先确认 API 契约。** 在写 UI 前完成协议、域名、版本、图片管线和真机 TLS 验证。

## 1. 命名收敛规约

### 1.1 原则

- 类名、文件名、模块名、数据库表名、DataStore key、资源名**不出现 `Jm` 前缀**。
- 用**包和模块**表达协议边界，不用类名前缀表达。
- 网络 DTO 统一以 `Dto` 结尾；领域模型使用产品通用名。
- 只有以下场景保留 JM 字样：
  - `applicationId`、品牌名、README、法律文案；
  - 外部 URL、User-Agent、API 常量中的外部字段名；
  - `@SerialName` 映射的 wire 字段；
  - 第三方协议文档中的专有名称。

### 1.2 命名对照

| 参考项目命名 | Android 统一命名 | 说明 |
|---|---|---|
| `JmAlbumDetail` | `ComicDetail` | 本子详情 |
| `JmPhotoDetail` | `Chapter` | 章节 |
| `JmImageDetail` | `Page` | 页 |
| `JmPageContent` | `PagedResult<T>` | 分页结果 |
| `JmFavoritePage` | `FavoritePage` | 收藏分页 |
| `JmApiClient` | `ApiClient` | JM API 客户端 |
| `JmRepository` | `ComicRepository` | 业务数据边界 |
| `JmModuleConfig` | `ApiConfig` | 域名/版本/常量 |
| `JmOption` | `AppSettings` / `ReaderSettings` | 配置模型 |
| `JmcomicException` | `AppException` / `ApiException` | 异常体系 |
| `JmImageTool` | `ImagePipeline` | 解码/解扰/编码 |
| `JmCryptoTool` | `Codec` / `Crypto` | 签名/解密 |
| `JmPageTool` | `PageMapper` | 响应到领域模型 |
| `jm_favorites` | `favorites` | 数据库表 |
| `JM_VERSION` | `API_VERSION` | 协议版本 |
| `JM_SECRET` | `API_SECRET` | 协议密钥 |
| `JM_HOST_CONFIG_URLS` | `DOMAIN_CONFIG_URLS` | 域名配置源 |
| `JM_FALLBACK_API_BASE` | `FALLBACK_API_BASES` | API 兜底 |
| `JM_FALLBACK_IMAGE_BASE` | `FALLBACK_IMAGE_BASES` | 图床兜底 |
| `JM_SETTING_AES_SEEDS` | `SETTING_AES_SEEDS` | `/setting` 解密 seed |
| `JmAlbumPayload` | `AlbumDto` | 网络 DTO |

### 1.3 领域模型

```text
model/
  Comic.kt
  ComicDetail.kt
  Chapter.kt
  Page.kt
  Comment.kt
  Favorite.kt
  ReadingProgress.kt
  DownloadTask.kt
  PagedResult.kt
  HomeFeed.kt
  SearchResult.kt
```

不使用 `JmComic`、`JmChapter`、`JmPage` 这类命名。

### 1.4 网络 DTO

```text
network/dto/
  SettingDto.kt
  AlbumDto.kt
  ChapterDto.kt
  SearchDto.kt
  CommentDto.kt
  FavoriteDto.kt
  DailyDto.kt
  LoginDto.kt
```

- DTO 只在 `network` 包内使用，不进入 UI。
- wire 字段用 `@SerialName` 映射，例如：

```kotlin
@Serializable
data class AlbumDto(
    @SerialName("related_list") val related: List<RelatedDto> = emptyList(),
    @SerialName("total_photos") val totalPhotos: Int = 0,
)
```

### 1.5 模块与包

```text
app
core:model
core:network
core:database
core:data
core:image
feature:home
feature:search
feature:detail
feature:reader
feature:library
feature:downloads
feature:settings
```

- 模块名不写 `jm`。
- `core:network` 内部包：`network`、`network.dto`、`network.codec`、`network.domain`。
- 领域包：`model`。
- 数据包：`data`、`database`。
- 图片包：`image`。

### 1.6 命名检查

提交前可执行：

```bash
rg -n "Jm[A-Z]|jm_" android/ --glob '!**/build/**'
```

预期只命中：品牌/URL/User-Agent/`@SerialName`/法律文案等白名单。

## 2. 架构

```text
Compose UI
   ↓
ViewModel (StateFlow)
   ↓
ComicRepository / LibraryRepository / DownloadRepository
   ↓
ApiClient / DAO / FileStore / ImagePipeline
   ↓
OkHttp / Room / DataStore / 文件系统
```

规则：

- UI 不直接调用 `ApiClient`、OkHttp 或数据库。
- `core:model` 不依赖 Android。
- `core:network` 不依赖 Compose/UI。
- `feature` 模块不互相依赖；共享逻辑下沉到 `core`。
- 首版只有一个 `ComicRepository` 实现：`DefaultComicRepository`。
- 不建 `ComicSource`；后端未来作为另一个 `ApiClient` 或 Repository 实现，不改 UI。
- 依赖注入使用 Hilt 或 Koin，二选一，不混用。
- 协程 + `Flow`；禁止 `GlobalScope`；长任务用 `SupervisorJob` 并有明确生命周期。

## 3. 具体产出

### 3.1 P0：协议与基础设施

- [ ] `docs/api-contract.md`：完整 API 契约、端点表、字段、错误码、fixtures。
- [ ] `core:network`
  - `ApiClient`：OkHttp、cookies、超时、重试、域名切换。
  - `ApiConfig`：种子域名、域名配置源、版本、图床兜底。
  - `Codec`：token/tokenparam、AES-ECB/PKCS7、Base64、BOM、gzip。
  - `DomainResolver`：域名配置源并发、`/setting` 探测、失败切换。
  - `CookieStore`：cookies 持久化，登录/匿名共享。
  - DTO 与 `PageMapper`/`ComicMapper`。
- [ ] `core:model`：通用领域模型。
- [ ] `core:database`：Room schema、DAO、迁移。
- [ ] `core:data`：`ComicRepository`、缓存、收藏、历史。
- [ ] `core:image`：下载、格式识别、解码、解扰、编码、缓存。
- [ ] `app`：Single Activity + Compose Navigation + 主题 + DI。
- [ ] 协议 smoke CLI 或 debug 页面：实际请求 10 个核心端点，输出脱敏 fixture。
- [ ] 真机 TLS/Cloudflare 报告：Wi-Fi + 蜂窝，OkHttp 默认 TLS 是否可用。

### 3.2 P1：MVP 功能

- [ ] 首页：推荐、每周、排行、最新。
- [ ] 搜索：关键词、JM 号、排序、分页、搜索历史。
- [ ] 详情：封面、标题、作者、标签、作品、角色、统计、章节、相关推荐。
- [ ] 阅读器：分页/条漫、缩放、章节切换、进度保存、预取、离线优先。
- [ ] 书架/历史：继续阅读、排序、删除。
- [ ] 收藏：本地收藏、详情状态、收藏列表。
- [ ] 下载：单章下载、后台任务、暂停/恢复/取消、通知进度、离线阅读。
- [ ] 深链/分享：从文本提取 JM 号，支持 Share Intent。
- [ ] 设置：域名/代理、并发、缓存、主题、阅读方向、应用锁。
- [ ] 错误/空态/加载态统一；大图 OOM 防护。

### 3.3 P2：增强

- [ ] 下载队列：多任务、仅 Wi-Fi、存储上限、失败重试、manifest、断点恢复。
- [ ] 过滤：跳过图少章节、只下新章、图片后缀、重复检测。
- [ ] 追更：最后章节水位、周期检查、本地通知。
- [ ] 账号：登录、cookies、签到；本地收藏/历史不依赖登录。
- [ ] 评论：只读分页、回复展开；发评论暂不做。
- [ ] 导出/分享：CBZ/ZIP、PDF、长图、收藏备份。
- [ ] 可选后端：`BackendApiClient` 或 `BackendComicRepository`，不改 UI。
- [ ] 平板/折叠屏、无障碍、国际化。

## 4. API 契约（首版）

### 4.1 来源与版本

- **线上 API 是最终权威**；Breeze/Python/Rust 只作为参考。
- **App 版本必须从 `/setting` 的 `jm3_version` 读取并更新**，不要长期写死。
  - 当前参考差异：Breeze/Rust 写死 `2.0.20`，Python 为 `2.1.12`。
  - 首版可用 `2.1.12` 作为种子，启动后以 `/setting` 返回值为准。
- 域名/图床以实测可用性为准，不盲抄任一项目的列表。

### 4.2 域名与图床

- 种子 API 域名。
- 加密域名配置源：Breeze/Rust 两个 bytepluses URL；Python 第三个作为候选。
- 并发获取域名配置；首个成功解密且包含有效 HTTPS 端点的结果胜出，其余请求取消。
- 启动后并发探测 `/setting`，限制并发（建议 6），验证：
  - HTTP 成功；
  - 业务 `code == 200`；
  - 解密成功；
  - `img_host` 非空。
- 图床候选池：使用 `/setting` 返回的 `img_host`，用真实占位图探测并校验格式；失败切换，404 不剔除。
- 所有源失败时保留两个内置兜底端点。

### 4.3 请求头、签名、解码

```text
token      = md5(ts + API_SECRET)
tokenparam = "{ts},{API_VERSION}"
user-agent = 移动端 UA
Host       = 当前域名
cookies    = CookieStore
```

- API 客户端需要 cookies；`/setting` 可初始化，登录后保存 `AVS`。
- 响应 `data` 字段：
  - Base64（含 URL-safe 变体）→ AES-ECB/PKCS7 → JSON；
  - 两套 seed：`185Hcomic3PAPP7R`、`18comicAPPContent`；
  - 兼容 BOM、gzip、纯 JSON、非 JSON 错误页。
- `code != 200` 保留 `errorMsg`/`msg`/`message`。
- 401/403/500/520/524、空响应、缺 `data`、解密失败分别归类。
- 仅可重试错误进入重试；业务错误直接抛出。

### 4.4 端点矩阵

> 路径来自 Breeze/Python/Rust 对照，最终以 live smoke 为准；标注“待确认”的端点必须在 P0 验证。

| 功能 | 方法/路径 | 关键参数 | 备注 |
|---|---|---|---|
| 配置 | `GET /setting` | - | 图床、版本、cookies |
| 首页推荐 | `GET /promote` | `page=0` | 分区列表 |
| 推荐分区 | `GET /promote_list` | `id`, `page` | 分页 |
| 最新 | `GET /latest` | `page` | 分页 |
| 排行/分类 | `GET /categories/filter` | `page`, `order`, `c`, `o` | 取代网页分类搜索 |
| 搜索 | `GET /search` | `search_query`, `page`, `main_tag`, `o`, `t` | API 不支持网页 category/sub_category |
| 详情 | `GET /album` | `id` | 章节 ID 可能返回 `series_id`，需重定向 |
| 章节 | `GET /chapter` | `skip`, `id` | 图片列表 |
| 解扰参数 | `GET /chapter_view_template` | `id`, `mode=vertical`, `page=0`, `app_img_shunt=1`, `express=off`, `v` | 特殊密钥 |
| 评论读取 | `GET /forum` | `mode=all`, `page`, `aid` | API 只读 |
| 登录 | `POST /login` | form `username`, `password` | 保存 cookies/AVS |
| 收藏列表 | `GET /favorite` | `page`, `folder_id`, `o` | 需登录 |
| 收藏 toggle | `POST /favorite` | form `aid` | 非幂等，需回滚保护 |
| 收藏夹 | `POST /favorite_folder` | form `type`, `folder_id`, `folder_name`, `aid` | 新建/移动 |
| 签到信息 | `GET /daily` | `user_id` | 需登录 |
| 签到 | `POST /daily_chk` | form `user_id`, `daily_id` | 重复签到按成功处理 |
| 签到变体 | `POST /daily_list/filter` | form `data=年份` | 待确认，Breeze 使用 |
| 每周更新 | `GET /serialization` | `date`, `type`, `page` | 待确认 |
| 图片 | CDN `/media/photos/{photo_id}/{index:05}{suffix}` | `data_original` | 图床 host 来自 `/setting` |
| 封面 | CDN `/media/albums/{id}_3x4.jpg` | - | 列表/详情封面 |

### 4.5 API-only 能力边界

首版明确不做：

- 发评论、回复评论（当前 API 参考实现未提供，HTML 才有）。
- HTML 搜索的 category/sub_category 参数。
- HTML 登录、Cloudflare 页面、网页端域名发布页解析。
- HTML 兜底。

首版保留：

- API 登录、cookies、签到。
- API 收藏（toggle + 回滚保护）。
- API 评论读取。
- API 排行、每周、最新、搜索、详情、章节、图片。

### 4.6 图片规约

- 无需解扰的 JPEG/PNG/WebP/GIF：原样缓存，不解码。
- GIF：不参与解扰。
- 需要解扰：
  - 解码 → 按 `get_num(scramble_id, aid, filename)` 切块 → 保存；
  - 默认保留原格式或用户配置格式；
  - 不强制统一 WebP；
  - 若启用 WebP，使用 Android 平台编码能力，并提供 JPEG/PNG 回退。
- 限制：
  - 单图响应硬限制（建议 64 MiB）；
  - 像素数/解码内存预算；
  - 解码并发建议 2–4；
  - 捕获 OOM，不崩溃。
- 缓存：
  - 原子写入；
  - `Room` 记录尺寸/格式/路径/大小；
  - LRU 清理；
  - 读取时校验完整性。

## 5. 工程规约

### 5.1 Kotlin

- Kotlin + Jetpack Compose，不使用 XML 布局。
- Kotlin Serialization；统一 `Json` 配置：
  - `ignoreUnknownKeys = true`
  - `isLenient = true`
  - 明确默认值，不静默吞掉关键字段
- 协程 + `Flow`；IO 使用 `Dispatchers.IO`。
- 错误使用 sealed class，不跨层抛裸 `Exception`。
- 代码风格：ktlint + detekt；Gradle version catalog。
- 依赖：Compose BOM、Navigation、Hilt/Koin、Room、DataStore、WorkManager、OkHttp、Coil、Paging 3、kotlinx.serialization。

### 5.2 网络

- OkHttp 单例 + CookieJar；连接池按并发配置。
- 超时：连接/读取/写入分别配置；重试带退避。
- 域名切换：每个域名重试 N 次后换下一个；记录失败原因。
- 不打印 cookie/token/完整图片 URL 查询参数。
- 仅在 debug 构建输出请求摘要。
- 代理走系统代理优先，支持手动配置。

### 5.3 数据

- Room 表：`comics`、`chapters`、`pages`、`favorites`、`history`、`download_tasks`、`download_pages`。
- ID 直接使用 JM 原始 ID（String），不加前缀。
- 时间统一存 UTC 毫秒，UI 格式化。
- 收藏/历史采用乐观更新 + 失败回滚。
- 数据库迁移必须写测试。

### 5.4 下载

- 任务状态：`Queued`、`Running`、`Paused`、`Completed`、`Failed`、`Cancelled`。
- 全局有界队列；任务级和页级并发分别限制。
- 取消要可协作，保存已完成页面和未完成 ID。
- 前台服务 + 通知；Android 14 使用 `dataSync` 类型。
- 进程被杀后可恢复；下载页原子写入。
- 仅 Wi-Fi、存储上限、失败重试、manifest。
- 删除任务同步删除文件和索引。

### 5.5 安全与隐私

- cookies/凭据使用 EncryptedSharedPreferences 或 Keystore。
- 应用锁：BiometricPrompt/PIN；可选 `FLAG_SECURE`。
- 关闭敏感数据库自动备份。
- 日志脱敏；不接入第三方分析 SDK。
- 导出文件走 MediaStore/SAF，不申请全盘存储权限。

### 5.6 测试

- 单元测试：签名、解密、BOM、域名选择、`get_num`、DTO 映射。
- MockWebServer：fixture 驱动，覆盖成功/错误/非 JSON/超时/重定向。
- Room/WorkManager 测试。
- Compose UI 测试：首页、详情、阅读器、下载状态。
- 大图/OOM/进程死亡/离线/弱网测试。
- 真机 smoke：Wi-Fi + 蜂窝 + 代理。

### 5.7 Git 与文档

- 提交使用 conventional commits：`feat(android): ...`、`fix(android): ...`、`test(android): ...`。
- API 契约变更必须同时更新：
  - `docs/api-contract.md`
  - fixtures
  - 相关单元测试
- 不提交 cookie、token、真实账号、未脱敏响应。

## 6. 里程碑与验收

### P0 门槛

- `/setting`、`/album`、`/chapter`、`/chapter_view_template`、一张图片解扰在 Android 真机跑通。
- Wi-Fi + 蜂窝均通过；TLS/Cloudflare 结论明确。
- API 契约文档和 fixtures 完成。
- 图片解扰无 OOM；大图有上限。

### P1 MVP 验收

- 新装 App 不接后端可完成：首页 → 搜索 → 详情 → 阅读 → 收藏/历史 → 下载单章 → 离线阅读 → 分享文本打开。
- 域名失效能切换；错误提示可理解。
- 设置可清缓存、改并发、开应用锁。

### P2 验收

- 多任务下载、后台/通知、暂停恢复取消、过滤、追更、导出。
- 可选后端接入不修改 UI/领域模型。

## 7. 风险与待决

- **TLS/Cloudflare**：OkHttp 指纹是否被拦；失败则评估嵌入 Rust core 或可选后端。
- **App 版本/密钥**：`/setting` 版本、API secret、域名配置可能变化；需要远程配置或发版。
- **API-only 功能缺口**：发评论、部分搜索分类不可用；产品是否接受。
- **后台限制**：Android 14/15 前台服务、电池优化、下载恢复。
- **分发**：Google Play 对成人内容限制严格，可能走 GitHub Releases/Obtainium/侧载。
- **图片内存**：大图/条漫 OOM；需要严格预算。
- **命名收敛白名单**：品牌/URL/UA/`@SerialName` 例外需要评审。

## 8. 参考资料

- Breeze 客户端行为：
  - [client.ts](../../Breeze-plugin-JmComic/src/client.ts)
  - [codec.ts](../../Breeze-plugin-JmComic/src/codec.ts)
  - [endpoint-pool.ts](../../Breeze-plugin-JmComic/src/endpoint-pool.ts)
  - [constants.ts](../../Breeze-plugin-JmComic/src/constants.ts)
- Python 端点与下载器：
  - [jm_client_impl.py](../../JMComic-Crawler-Python/src/jmcomic/jm_client_impl.py)
  - [jm_config.py](../../JMComic-Crawler-Python/src/jmcomic/jm_config.py)
  - [jm_toolkit.py](../../JMComic-Crawler-Python/src/jmcomic/jm_toolkit.py)
- 现有 Rust 实现：
  - [client.rs](../server/src/jm/client.rs)
  - [setting.rs](../server/src/jm/setting.rs)
  - [endpoint/mod.rs](../server/src/endpoint/mod.rs)
  - [page_materializer.rs](../server/src/page_materializer.rs)
