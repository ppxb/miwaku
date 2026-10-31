# CHECKLIST · miwaku 与参考项目对比及借鉴清单

> 基线：`miwaku@487ad62`（2026-10-30）
> 参考：`komikku`（Kotlin/Compose 阅读器，JM 源在独立扩展）、`Breeze`（Flutter 阅读器 + QJS 插件宿主）、`Breeze-plugin-JmComic`（JM API 源）、`jm-boom/server`（Rust 实现，`AGENTS.md` 引用它）
> 用途：组件/性能对照 → 可抄清单 → 取舍决策 → 执行勾选

---

## 一、四个项目是什么

| | miwaku | komikku | Breeze | JM 插件 |
|---|---|---|---|---|
| 形态 | Android 原生 App | Android 原生 App | Flutter 多端 App | JS/TS 插件 |
| 内容源 | 只做 JM，写死在 App 里 | 扩展式，几百个源，JM 在独立 APK | 插件式，JM 是其中一个 | 就是 JM 本身 |
| 数据层 | Room | SQLDelight | ObjectBox | 无（交宿主） |
| 下载承载 | 自建队列 + 前台服务 | WorkManager | 自建队列 + 前台任务 | 无（交宿主） |
| 图片解码 | Coil 3 + 自研解扰 Decoder | Coil 3 + 自研 Decoder | 自研管线 + Rust | 不解扰 |
| 规模 | 主代码 10.7k 行 | 数十万行 | 数万行 Dart | 数千行 TS |

**结论先行**：我们和 komikku/Breeze 不是同一量级的产品，但在「JM + 本地优先 + 离线阅读」这条窄路上，几个关键决策更严谨；可抄的东西集中在「容错策略」与「资产布局」，不可抄的是它们的多源/多端包袱。

---

## 二、组件级对比

### 1. 网络层 / 端点容错

| | miwaku | komikku | Breeze | JM 插件 | jm-boom |
|---|---|---|---|---|---|
| 域名来源 | Bytepluses 3 源（c01/c02/c03）并发竞速，首个成功即取消 | 无（在扩展内） | 无（在插件内） | 2 源 + AES 解密；实现是 `allSettled`（等最慢源），与注释「先到先用」不符 | 同插件 |
| 兜底域名 | 5 个内置，首轮不参与；全灭才补一轮探测，运行期作为最后候选 | — | — | 1 个（`cdnhjk.net`），仅发布列表为空时用 | — |
| 探测 | 6 并发（独立 client，不共享业务 3/s 限速与重试），6s 超时，排序 ok→degraded | — | — | 6 并发、5s 超时、三态；走宿主 axios，无限速无重试 | 6 并发，无 degraded |
| 剔除策略 | Dead 出池；失败 / `Decode` / 403 只降权 + 指数冷却（瞬断 30s、可疑 2min 起，上限 10min），成功清零 | — | — | 探测期 degraded 留池；运行期网络错误即**永久移出缓存池**（无冷却），池剩 1 条不再切换，30min TTL 后重算 | `report_failure` 计数 + 冷却 |
| 刷新 | 30min TTL + 失败强制刷新 | — | — | 30min 被动 TTL | 2h 定时 + 手动锁定 |
| 图床 | 池 = 各线路 `img_host` + 历史池；5s 占位图探测，非 2xx/空 body 留池为 degraded（404 不剔除）；`nextImageHost` 降权轮换，运行期可落兜底 `cdn-msp3.jmdanjonproxy.vip`。实测今天 3 台 | — | — | 池仅取本轮 `img_host`；非 2xx 直接判死、运行期**永久移出**、剩 1 台不再切换；兜底只在 init 池空时用 | 同插件 |
| 拦截器 | 按 client 分工：API = 重试 + 限速（3/s）+ 指标，图片 = 仅重试，探测 = 全无；请求头统一由 `RequestHeaders` 构造 | DoH + Cloudflare WebView + 限速 | — | axios 请求/响应拦截器：注入 token/UA/Host/JWT、统一解码与 401；无重试/限速/指标 | — |
| UA | 首启随机机型 + 9 位 deviceId（seed 落 SharedPreferences），之后固定；API / 探测 / 图片都带 | 可配 | — | 随机机型 + 持久 deviceId；仅 API 请求带，图片请求只发 Host | — |

**差距**：我们是「能切换」，插件是「知道该切谁、切完还能回来」。但**取源这一环我们更快**：3 源内容同 ETag，最近端起 0.26–0.32s，插件必须等最慢源落地（≈5s 超时）才开始探测。

### 2. 响应解码 / 协议

| | miwaku | 插件 | jm-boom |
|---|---|---|---|
| 签名 | token/tokenparam ✅ | 同 | 同 + jwt |
| 解密 | 双 seed 轮试 + BOM/URL-safe base64 ✅（无 magic-byte gzip） | 双 seed + BOM 不剥 + 手动 gunzip | 同 |
| 错误分类 | 4 类（Network/Http/Business/Decode）；`Http` 现已带服务端文案 | HTTP vs body.code + 401 单列 | 5 类 + 401 |
| `/setting` 与 host 配置解析 | **JSON 单一实现**（`Codec.decodeEnvelope`，API 与探测共用；无正则兜底） | JSON | JSON |
| 契约文档 | 无 | 插件自带类型 | Rust 类型 + serde |

**差距**：401（登录态）未单列（登录暂不实现）；契约文档缺失。原先「`/setting` 走正则、与 ApiClient 两套实现」已在本轮消除。

### 3. 图片管线

| | miwaku | komikku | Breeze | jm-boom |
|---|---|---|---|---|
| 落盘内容 | 原图（解扰在解码时） | 原图（解码时解扰） | 已解扰图（可再 WebP/超分） | 原图 |
| 去重 | 单飞 flight + owner 计数 | Coil single-flight | 单飞（asset store） | KeyedLock + 锁内复查 |
| 并发 | 阅读 4 / 下载 2 / 总 6 传输；页解码 3、封面解码 4（分 loader）；预取窗口 10 页、批内 3 并发 | 读者线程 2，fetcher 8 / decoder 3 | 5（可被插件声明） | 网络 12 / 解码 4，双预算 |
| 优先级 | Retry / Visible / Nearby / Behind / Warmup / Download 六档，全部实际使用：重试最高、可见页其次、身后页低于前方、下载最低 | 三级（当前页 / 预取 / 重试，重试最高） | 无（顺序预热） | 三级 |
| 取消 | 最后一个使用者退出才取消 Call | 随 composable 销毁 | 取消信号 | watch + select，取消不换图床 |
| 完整性校验 | magic 判格式 + **JPEG EOI / PNG IEND / GIF 3B / WebP RIFF 长度**，不合格按可重试网络错误在提交前拒绝 | — | — | magic 判格式 + EOI/IEND + 读时自愈 |
| 尺寸缓存 | **追加式日志 `sizes.log`**（无版本：`[u16 len][key][w][h][split]`，2s 防抖只追加变更，超过 `max(64KB, 64B×条数)` 才压实重写；启动回放，末条为准；截断尾部丢弃） | Coil 内存 | zstd 二进制落盘 | — |
| 缓存上限 | 1GB（min 512MB），缓存目录 LRU | ChapterCache 可配 | 磁盘 LRU | `evict_if_needed` |

**差距**：三条已在本轮收口（完整性校验、尺寸落盘、下载直写）；剩余是「缓存与离线仍是两套路径」（同页最终只留一份，但缓存路径不参与离线寻址）。

### 4. 阅读器

| | miwaku | komikku | Breeze |
|---|---|---|---|
| 结构 | 1 个 1650 行 Screen + 357 行 VM | `ui/reader/**` 多文件 + `PageLoader` 抽象 | 8 个 controller + cubit |
| 预取 | 需求集 + 优先级 + 末位取消，ahead 10（可配 4–20）/ behind 2；可见页单独 await，后方页降为 Behind | 优先级队列，preload 10 | key 去重预热，不建 Widget |
| 进度写 | 1s 合并 + 跨章/退后台立即提交 | 每翻页同步写 | 500ms 定时写（不做差异判断） |
| 已读判定 | 末页成功显示才置位 | 到末页置位 | 宿主负责 |
| 跨章 | 窗口内邻章 + 过渡页 | 切章重建 | 无缝跨章 + 双页 + 音量键 + 自动阅读 |
| 位置恢复 | 文件名优先 + 页内偏移 | 页号 | global slot + 双页映射 |

**差距**：语义我们最好，结构我们最差。

### 5. 下载

| | miwaku | komikku | Breeze |
|---|---|---|---|
| 调度 | 3 worker 取同一队列（可配 1–5）+ 章节内页并发 1–5 | 源级并行 5 + 页级 5 | worker pool 5（插件可声明）+ 任务队列 |
| 持久化 | Room 任务表 + 页索引 | SharedPreferences JSON | ObjectBox + 后台 isolate 串行写 + `flushTaskWrites` |
| 落库粒度 | 每页一次事务 | 章节级 | 后台批量（checkpoint） |
| 重试 | 3 层（OkHttp/拦截器/页级 3 次） | 3 次 2/4/8s | 3 次 1s + 可选「失败持续重试」 |
| 404 | 不重试，计缺失（已修） | 计失败 | skip，不算失败 |
| 兜底 | 前台服务 + 启动恢复 | WorkManager（系统可重试） | 前台任务 |
| 存储策略 | 上限 + 回收站 | 阅读后自动删 | 手动删除 + 配额检查 |

**差距**：并发模型（扇出 vs pool）与落库粒度（每页 vs 批量）。

---

## 三、性能参数对照

| 参数 | miwaku | komikku | Breeze 插件 | jm-boom |
|---|---|---|---|---|
| 图片传输并发 | 阅读 4 / 下载 2（总上限 6） | 读者线程 2，fetcher 8 | 5 | 12（网络预算） |
| 解码并发 | 页 3、封面 4（两个 ImageLoader，共享内存/磁盘缓存） | 3 | 宿主决定 | 4 |
| 端点探测并发 | 6 | — | 6 | 6 |
| 请求超时 | API 20s / 图片 60s | 30/30/120s | 10s | — |
| 图片体积上限 | 64 MiB（双层） | — | — | 64 MiB（双层） |
| 缓存上限 | 1GB（可调 512–2048） | 可配 | — | 逐出策略 |
| 下载页并发 | 1–5（默认 5） | 5 | 5 | — |
| 下载章并行 | 1–5（默认 3） | 5 | 5（worker pool） | — |
| 阅读预取窗口 | 4–20 页（默认 10） | 10（可配 4–20） | 顺序预热 | — |
| 端点 TTL | 30min | — | 30min | 2h |
| 重试次数 | 最多 6 次传输/页 | 3 | 3（或无限） | 分类驱动 |

### 我们自己的实测（`decode-benchmark.json`，720×16000 条漫）

| 场景 | 旧实现 P50 | 新实现 P50 | Bitmap 峰值 |
|---|---|---|---|
| JPEG | 26.8 ms | 20.8 ms | 49 MB → 4.2 MB |
| PNG（bounded-png） | 75.3 ms | 78.4 ms | 49 MB（持平） |
| 1080p PNG | 7.3 ms | 7.9 ms | 6.2 MB（持平） |

→ JPEG 条漫收益明显，PNG 应走整图路径，不能一概而论「区域解码更快」。

---

## 四、能直接借鉴/抄的清单

### A. 直接抄（低成本、收益确定）

| # | 抄什么 | 来源 | 落到我们哪里 | 预期收益 |
|---|---|---|---|---|
| A1 | degraded 只降权、出池带冷却 | 插件 | `ApiClient.dropBase` → 计数 + `cooldownUntil` | 一次 WAF/解密抖动不再摘掉健康域名 |
| A2 | 401 / 登录态单列 | 插件、jm-boom | `ApiFailure.Unauthorized` + UI 提示 | 错误提示可行动 |
| A3 | 页面完整性校验（magic + EOI/IEND） | jm-boom | `PageImageStore.download` 提交前 | 截断图不再入库，成本约 10 行 |
| A4 | 页尺寸落盘 | Breeze | `PageImageStore._sizes` 增二进制快照（不必用 zstd） | 冷启动 webtoon 不抖 |
| A5 | 下载 404/410 = skip | Breeze | 已完成（`isPageMissing`） | — |
| A6 | 占位图探测图床池 | 插件 | `ApiClient.refreshImageHost` → 池 + 探测 | 图床切换从「再打 /setting」降到「换池内下一台」 |
| A7 | UA 随机化 + 持久 deviceId | 插件 | `ApiConfig.USER_AGENT` → `UserAgentProvider` | 降低设备指纹关联风险 |

### B. 借鉴改造（需要动结构，收益中高）

| # | 借鉴什么 | 来源 | 怎么改 | 成本 |
|---|---|---|---|---|
| B1 | `PageLoader` 抽象（在线/离线/压缩包同构） | komikku | 抽 `PageSource` 接口：`RemoteSource` / `OfflineSource`（将来 `ArchiveSource`） | 中；为 CBZ 导出铺路 |
| B2 | 下载 worker pool + 后台批量落库 | Breeze | `DownloadManager` 用 N worker 循环取任务；`recordPage` 批量提交 | 中；150 页章节 DB 写 150 → <30 |
| B3 | 阅读器 controller 拆分 | Breeze | `ReaderScreen` 拆 viewport / webtoon / paged / menu；VM 拆 session / prefetch / progress | 中高；最大回归源 |
| B4 | 统一资产布局（下载=缓存同一路径） | Breeze | `DownloadStore.pageFile` 与 `PageImageStore.fileFor` 合一；下载直接写目标 | 中；去掉「复制后删」 |
| B5 | 优先级图片队列 + 可配置线程 | komikku | 已有需求集，补「可配置并发」 | 低 |
| B6 | 端点手动锁定 + 状态可视化 | jm-boom / 插件 | 设置页展示端点/延迟/状态，可锁定 | 低中 |
| B7 | WorkManager 兜底 | komikku | 前台服务为主，WorkManager 做「被杀后重试」 | 中 |

### C. 明确不抄

| 不抄 | 理由 |
|---|---|
| komikku 扩展架构（`Source`/`ChildFirstPathClassLoader`） | 单源，抽象成本换不来收益 |
| Breeze 超分 + WebP 转码 | CPU/存储开销大，收益强依赖设备与内容 |
| Breeze 500ms 定时写历史 | 空转也写，写放大；我们的事件合并更好 |
| komikku 每页同步写进度 | 弱机写放大明显 |
| jm-boom 的 Rust 服务端形态 | 本地 App，无后端 |
| Cloudflare WebView 过盾 / HTML 兜底 | 项目定位 API-only |
| DoH（komikku） | 可选增强，非当前瓶颈 |

---

## 五、需要取舍的地方

| 取舍点 | 选项 A（现状） | 选项 B（参考） | 建议 |
|---|---|---|---|
| 解扰时机 | 读时解扰，磁盘存原图 | 写时解扰（Breeze），可再 WebP/超分 | 保持 A：原图是导出/换源资产；实测 8 页读时 40ms vs 写时 160ms（JPEG 产物 2.2× 原体积、WebP 编码 758ms） |
| 存储布局 | 缓存 + 离线两目录 | 统一 canonical path（Breeze） | 逐步转 B：先「下载直写目标」，再删缓存副本 |
| 下载承载 | 前台服务 | WorkManager（komikku） | A 为主 + B 兜底 |
| 图片调度 | 自研 flight/priority | 全交 Coil | 保持自研：Coil 取消是请求级；但缓存/解码继续交 Coil |
| 数据库 | Room | SQLDelight / ObjectBox | 保持 Room：编译期校验 + Flow；写性能用批量写补 |
| 进度写 | 事件合并 1s | 每页同步 / 定时 | 保持合并，保证退后台/杀进程路径都 flush |
| 分辨率策略 | 屏幕宽 + 4096 高 | 原图 / 双页 | 保持；若做双页需重算目标宽 |
| 图床来源 | 复探 `/setting` | 池 + 占位图 | 转 B（A6） |

---

## 六、执行清单

### P0（先做这三件）

- [x] A1 端点失败改为「计数 + 冷却」，`Decode` 失败只降权不出池
- [x] 配置源解析改为 JSON 优先 + 正则兜底（对象元素、BOM、CDN 前缀都覆盖）
- [x] 端点状态合并为一份带 `version` 的文档（API 池 + 图床池 + 选择 + 探测时间，迁移旧 `endpoint_pool`/`api_setting`）
- [ ] A2 新增 `ApiFailure.Unauthorized`，401/「請先登入會員」单独提示
- [x] A6 图床池 + 占位图 `/media/users/nopic-.gif` 探测（404 保留为 degraded，不剔除）
- [x] A7 UA 随机机型 + 持久 deviceId（首启生成 seed，`UserAgentStore` 持久化，三处请求共用）
- [ ] B2 下载改 worker pool，`recordPage` 批量提交（worker pool 已完成，章节并行 1–5 可配；批量落库待做）
- [x] B4 下载直写离线目录，取消「复制后删」（验收：同一页磁盘只有一份）

### P1（结构债）

- [ ] B3 拆 `ReaderScreen`（viewport / webtoon / paged / menu），`currentGlobal` 收敛单通道（验收：单文件 < 500 行，30 个设备用例全绿）
- [x] A3 下载提交前做图片完整性校验（magic + EOI/IEND）
- [x] A4 页尺寸二进制落盘（验收：冷启动 webtoon 不依赖 `medianPageRatio`）
- [ ] B1 抽 `PageSource` 接口（在线 / 离线），为压缩包与导出铺路
- [ ] 补 `docs/api-contract.md` + `Codec`/`Scramble`/`PageMapper` 单测（fixtures 驱动）

### P2（功能与体验）

- [ ] B5 图片并发可配置（1–5），与下载并发解耦
- [ ] B6 设置页展示端点列表 / 延迟 / 状态，支持锁定（列表 / 延迟 / 状态已完成，锁定待做）
- [ ] B7 WorkManager 兜底「进程被杀后重试」
- [ ] 搜索分页与排序（API `page`/`o`）
- [ ] 最新 / 每周更新入口（`latest()`、`/serialization`）
- [ ] 分享 / 深链（从文本提取 JM 号，加 intent-filter）
- [ ] 导出（CBZ/PDF，依赖 B1 与「保留原图」这一既有优势）
- [ ] 追更 / 阅读后自动删 / 下载过滤 / 应用锁

### 工程卫生（随手）

- [ ] `NetworkE2ETest.cancelledDownloadsReleaseSlots` 换本地阻塞服务器，去掉对真实网络时序的 `assumeTrue`
- [ ] `ProbeResult.probes` 接到设置页或删除
- [ ] `safe()` / 图片校验 / `md5Hex()` 去重
- [ ] core 层 19 处中文文案改为 `AppError` + 资源串
- [ ] `DownloadState` 字符串持久化改 `@TypeConverter`（需 v2 迁移，与既有迁移契约配套）

---

## 七、一句话

数据与阅读语义已经领先两个阅读器，图片解码有实测优势；落后的是网络容错（可抄）、资产布局与下载工程化（可抄）、阅读器结构（必须自己拆）与功能广度（产品决策）。

---

## 八、本轮已完成（网络层：降权 / 持久化 / 图床池 / JSON）

| 改动 | 文件 | 说明 |
|---|---|---|
| 降权模型 | `EndpointHealth` | 失败只降权 + 指数冷却（瞬断 30s 起、`Decode` 2min 起，上限 10min），成功即清零 |
| 状态文档 v2 | `EndpointState`、`EndpointStateStore` | 一份 SharedPreferences 文档装下 API 池、图床池、当前选择、探测时间；启动时从旧 `endpoint_pool` + `api_setting` 迁移，旧 key 保留 |
| 客户端 | `ApiClient` | 候选按「健康 → 失败次数 → 延迟」排序，冷却中的端点是最后兜底而不是被删除；`nextImageHost(failedHost)` 取代单点 `refreshImageHost` |
| 图床池 | `DomainResolver.probeImageHosts` | 汇总各可用线路的 `img_host`，用占位图探测；2xx 且非空=ok，其它响应=degraded，超时=dead |
| JSON 解析 | `Codec.parseHostList`、`DomainResolver` | 配置源与 `/setting` 信封都改走 `org.json`，正则仅作 CDN 前缀兜底 |
| 兜底二级探测 | `ApiClient.probeCandidates` | 首轮只探「发布列表 + 持久池」，全灭才补 5 个兜底；列表外且最慢的 `cdngwc.cc` 不再拖冷启动探测尾部 |
| 图床静态兜底 | `ApiConfig.FALLBACK_IMAGE_BASES`、`ApiClient` | 占位图探测全灭时把 `cdn-msp3.jmdanjonproxy.vip`（插件同源）写进池，`nextImageHost` 在池内无候选时回退到它；实测 200 / 1.0–1.3s |
| 探测专用传输 | `ApiClient`、`AppGraph.probeHttpClient` | 探测不再经过 `RateLimitInterceptor(3/s)`/`RetryInterceptor`：延迟不含排队、`dead` 只反映网络；真机 6 探针 369ms（独立）vs 1497ms（限速），差 1128ms |
| 403 归可疑 | `ApiFailure.isSuspicious`、`ApiClient.penalize` | 403 与 `Decode` 同档（2min 起冷却），5xx/超时仍 30s 起；`ApiFailureTest` 4 例覆盖 |
| 图床探测超时 | `DomainResolver.IMAGE_PROBE_TIMEOUT_MS` | 4s → 5s（与插件一致）：占位图探测的 `dead` 会直接出池，冷启动实测 1.0–2.0s，4s 余量偏薄；真机 4 台两轮全 200 |
| 请求头收敛 | `RequestHeaders` | token/tokenparam/UA/accept 只在这一处构造；`ApiClient`、`DomainResolver`、`PageImageStore` 三处调用点改用它 |
| UA 持久随机 | `UserAgent`、`UserAgentStore` | 首启随机 seed → Android 10–15 / Chrome 114–125 / 4 个 Build 之一 + 9 位 deviceId，seed 与 UA 持久化；真机生成值已验：`Android 12; l9c1kcynv`，两条线路 `/setting` 200 |
| 图床失败不阻塞 | `ApiClient.nextImageHost` | 不再同步探完整池：立即返回池内下一台 / 冷却项 / 静态兜底，刷新转后台（30s 节流）；`ImageFailoverTest` 钉住 <1s 返回 |
| 切换总预算 | `ApiClient.getJson` | 单请求 30s 总预算：首个候选 20s、后续 10s，均按剩余预算裁剪；预算耗尽不再触发重探。真机 5 个黑洞候选 30.0s 收敛（原为 20s × 候选数） |
| 端点状态页 | `SettingsScreen`、`AppGraph.endpointSummary` | 设置-调试新增「端点状态」：当前线路 / 协议版本 / 上次探测 + 逐条延迟与状态。状态合并两层：运行期优先（冷却 Ns / 降权 ×n），否则显示探测结果（正常 / 探测降级 / **探测失败，灰色列出**），当前线路标「当前」 |
| 探测记录 v3 | `EndpointState`、`EndpointStateStore` | schema 2 → 3：持久化 `apiProbes` / `imageProbes`（含 dead 与 reason），v2 文档读入时探测数组为空、其余字段照旧；`EndpointStateStoreTest` 覆盖往返与 v2 兼容 |
| 统一信封解码 | `Codec.decodeEnvelope` | 外层 JSON、`code`、`data` 解包只写一次：`ApiClient.requestJson` 与 `/setting` 探测共用；host 配置删掉正则兜底，只认 JSON |
| 非 2xx 文案 | `ApiFailure.Http`、`Codec.serverMessage` | 非 2xx 时读 body 并尽量解出 `errorMsg/msg/message`（含加密 `data` 里的），异常信息形如 `HTTP 520: 請稍後再試`，不再是裸状态码 |
| 采纳保留延迟 | `ApiClient.adoptBase` | 切到实际服务线路时复用原条目，不再把 `latencyMs` 重置为 0；`EndpointPoolE2ETest` 新增 1 例 |
| 图片完整性 | `ImageIntegrity`、`PageImageStore.download` | 提交前按容器尾判完整：JPEG `FFD9`、PNG `IEND`+CRC 位、GIF `3B`、WebP 比对 RIFF 长度、BMP 比对头内长度；未知容器交给解码器。不合格抛 `ApiFailure.Network`（可重试），temp 不提交 |
| 尺寸日志 | `PageSizeLog`、`PageImageStore` | 追加式 `sizes.log`（无版本、无兼容）：每页只追加一条 ~40B 记录，2s 防抖批量 append，日志超过 `max(64KB, 64B×条数)` 才压实重写；启动回放、末条为准、截断尾部丢弃；`clearCache` 删日志 |
| 解扰时机实测 | `PagePipelineBenchmarkTest` | 同 8 页：读时（Coil + GPU 变换，3 decoder）40ms；写时 decode 74ms + 重排 14ms + JPEG 编码 72ms = 160ms，产物 1810KB vs 原图 818KB（2.2×）；WebP 编码 758ms 才 788KB。结论：维持读时解扰 |
| 移除低内存模式 | `SettingsStore`、`SettingsScreen`、`AppGraph`、`ReaderViewModel`、`DetailViewModel` | 该开关只是把预取降到 2/1 并把缓存压到 256MB，与 4–20 预取设置重复且强制降级；删除设置项、参数、256MB 上限与相关分支 |
| 切块变更失效 | `PageImageStore` | 记录里带 `splitCount`；缓存命中但 `splitCount` 不一致时删文件重下，避免「旧字节 + 新切块」花屏；trim 后按文件存在性剔除尺寸记录并压实 |
| 下载直写 | `PageImageStore.prepareTo`、`DownloadManager` | 下载直接写离线目录（temp + rename），不再「缓存→复制→删」；若同一页有读者 flight 在跑则复用其结果再落目标。设备用例断言目标存在、缓存副本不存在 |
| 请求级单飞 | `SingleFlight`、`ApiClient.getJson` | 同 `path + 排序后的 params` 的并发调用合并为一次；失败即移除（不缓存错误）；**最后一个等待者取消即取消共享请求**，其他人仍在则不取消；`t` 不进键。设备用例 4 并发 `/album` 只发 1 次 HTTP |
| 取消不换图床 | `ImageRetry.canRotateImageHost` | 取消一律不换图床（调用方已走），只有 `ApiFailure` 可换源错误才切 host；`ImageRetryTest` 3 例钉住（取消 / 传输错误 / Decode 与业务错误） |
| 分离传输池 | `PageImageStore` | 阅读 4 / 下载 2 两套额度 + 总上限 6；`Flight` 按 owner 优先级判定是否下载，下载不再占满阅读额度 |
| 分离解码器 | `MiwakuApplication` | 页面 loader（decoder 3）与封面 loader（decoder 4）分开，共享同一 MemoryCache/DiskCache；阅读页不再拖慢封面 |
| 并发可配 | `SettingsStore`、`SettingsScreen`、`DownloadManager` | 预取 4–20（默认 10）、下载章并行 1–5（默认 3）、下载页并发默认 5；worker pool 从同一队列取任务，认领时先标记 Running 防重复 |
| 并发基准 | `PagePipelineBenchmarkTest`（设备，真网络 8 页） | 传输 3/2/5、4/2/6、6/3/9 两轮：首屏 2.2–12.0s、整批 5.8–18.1s 抖动巨大，只能确认高并发**无系统性失败**（0–1）；解码 5 轮均值 29/16/15ms（并发 1/2/3），2→3 仅再快 ~6%，PSS 采样受 GC 干扰不可比 |
| 优先级补全 | `PagePriority`、`ReaderViewModel`、`ReaderPrefetcher` | 新增 `Retry` 档；可见页 = Visible、前方 = Nearby、后方 = Behind；`loadPage(force = true)` 走 Retry。此前生产代码只用 Nearby，Visible / Behind 仅测试可见 |
| 启动基线 | `SharedEndpointStateStore.seedBootstrapIfEmpty`、`AppGraph` | 首次安装/清数据时把内置 API 池与图床兜底写成 `probedAt=0` 的初始状态，首屏不再等 `/setting`；后台刷新完成后替换。实测：分类页在后台刷新（`pool_refreshed available=3`）之前就已出图 |
| 探测超时 | `DomainResolver` | 试过 2.5s / 4s：同一链路下健康镜像被误判 dead（`ok=1/dead=4`、`ok=2/dead=3`，6s 时 `ok=3/dead=2`）。因 `/setting` 已移出关键路径，恢复 6s，精度优先 |
| 并发提升 | `PageImageStore`、`AppGraph` | 阅读 4→6 传输、总上限 6→8；OkHttp `maxRequestsPerHost` 显式提到 8（默认 5 会先卡住）。实测该链路单页 40–60KB/s，5 页并行仍需 6.6s——瓶颈是 50% 丢包，不是并发数 |
| 详情预热并行 | `DetailViewModel` | 取消“首页单独 await + 分批 2”，改为一次并发 4；实测 5 页仍 6.6s，同样受链路限制 |
| 首请求对冲 | `ApiClient.hedge` | `probedAt == 0`（首启基线）时同时打前两个候选，先成功者胜、另一个取消；失败各自降权。实测首个 `/album` **11.2s → 2.2s**（`api_hedge_winner` 可见胜者） |
| 跨图床分摊 | `PageImageStore.spreadUrl`、`ApiClient.imageHosts` | 按健康图床池轮转改写 host（缓存键仍是 `chapterId/fileName`，与 host 无关）；实测 11 页分发到 **4 台** host，预取 10.0s（≈110KB/s，此前 ≈75–90KB/s） |
| 历史语义修正 | `DetailViewModel`、`HistoryStore`、`LibraryStore` | 打开详情不再写历史：删掉 `history.record` / `recordVisit`（唯一写 `openedAt` 的地方）；历史只由阅读进度事件（`ProgressStore`）写入。`openedAt` 列保留给旧数据，首页查询仍兼容。模拟器验证：只看详情→首页仍空；读一页→出现记录 |
| 章节行简化 | `DetailScreen`、`DetailViewModel` | 删除每章的「操作」菜单（标记已读 / 从头阅读）与 `markRead` 调用链，只保留下载按钮；阅读状态仍由进度事件决定，`ProgressStore.markRead` 保留给测试与将来的批量操作 |
| 测试 | `EndpointHealthTest`、`EndpointStateTest`、`EndpointJsonTest`、`EndpointPoolE2ETest`、`ApiFailureTest`、`UserAgentTest`、`RequestHeadersTest`、`ImageFailoverTest`、`EndpointStateStoreTest`、`ImageIntegrityTest`、`PageImageStoreDeviceTest`、`SingleFlightTest`、`ImageRetryTest`、`PageSizeLogTest` | JVM 46 例 + 设备 43 例全绿（`CodecHostListTest` 随正则兜底一并删除，host 配置解析改由设备侧 `EndpointJsonTest` 覆盖） |

验证：`:app:lintDebug`、`:app:testDebugUnitTest`、`:core:network:testDebugUnitTest`、`:core:database:testDebugUnitTest` 全部通过；设备侧 `TEST-SM-A5360.xml` 记录 33/33 通过（AGP 任务本身仍报 exit code 1，见下）。兜底二级探测与图床静态兜底改动后各重跑一次 `:core:network:testDebugUnitTest`，编译 + 单测均通过；探测传输拆分后 `:core:network:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin` 通过，模拟器 `emulator-5554` 上 `EndpointPoolE2ETest` 3/3 通过；403 归类改动后 `:core:network:testDebugUnitTest` 25 例全绿（含新增 `ApiFailureTest` 4 例）；图床探测超时改 5s 后真机测 4 台 × 2 轮全 200 且均 < 5s；请求头收敛 + UA 持久随机后 `:core:network:testDebugUnitTest` 33 例全绿，真机 `UserAgentDeviceTest` 通过（UA 跨实例稳定、`cdnhjk`/`cdnutc` 的 `/setting` 均 200）；图床非阻塞 + 30s 预算 + 端点状态页后 `:core:network:testDebugUnitTest` 34 例全绿、设备 `EndpointPoolE2ETest` 4/4 通过、`FailoverBudgetDeviceTest` 实测 30.0s 收敛（临时用例已回收），模拟器上端点状态页实测有数据（当前线路 1179 ms、3 条线路、2 台图床）；探测记录 v3 后 `EndpointStateStoreTest` 2/2、`EndpointPoolE2ETest` 4/4、`EndpointJsonTest` 2/2 通过，模拟器实测 4 条线路全列出、`cdnhjk.net` 灰色「探测失败」、`cdngwc.club` 显示「探测降级 · 当前」、`cdngwc.net` 显示运行期「降权 ×1」；信封统一 + JSON-only 解析 + 非 2xx 文案后 `:core:network:testDebugUnitTest` 28 例全绿、设备 `EndpointJsonTest` 4/4 通过（含 `hostConfigIsJsonParsedAndNormalized`、`nonSuccessResponseKeepsTheServerMessage`；AGP 任务仍报 exit 1，按 XML 判定）；图片管线 A3/A4/B4 后 `:core:data:testDebugUnitTest` 4 例（`ImageIntegrityTest`）、`:core:network:testDebugUnitTest` 28 例全绿，设备 `PageImageStoreDeviceTest` 3/3 通过（直写目标、缓存副本不存在、尺寸快照跨实例恢复、截断体被拒；XML/HTML 均 100%，AGP 仍报 exit 1）；请求级单飞后 `:core:network:testDebugUnitTest` 32 例全绿（含 `SingleFlightTest` 4 例）、设备 `EndpointJsonTest` 5/5 通过（4 并发 `/album` 只发 1 次 HTTP）；并发改造（分离传输池 / 分拆 ImageLoader / 三项可配）后 `:core:network` 32 例、`:core:data` 4 例全绿，模拟器装机实测设置页显示「向前 10 页」「每章 5 张并发」「同时 3 章」；优先级补全后设备 `ImagePipelineTest` 4/4 通过（含「Visible 提升越过 Nearby」用例），模拟器阅读 1/149 正常；取消补全后 `:core:network` 34 例（`SingleFlightTest` 6 例，含「最后一个等待者取消即取消共享请求」「仍有等待者则不取消」）、`:core:data` 7 例（`ImageRetryTest` 3 例）全绿，最新构建已装模拟器；尺寸日志改造后 `:core:data` 12 例（新增 `PageSizeLogTest` 5 例）、设备 `PageImageStoreDeviceTest` 4/4 通过（含「切块变更使缓存失效」），构建已装模拟器；解扰时机基准（8 页）读时 40ms vs 写时 160ms（JPEG 产物 2.2×、WebP 编码 758ms），维持读时解扰；低内存模式已移除（编译 + 装机通过）；启动基线与并发改造后 `:core:network` 34 例、`:core:data` 12 例、设备 `EndpointPoolE2ETest` 4/4 与 `PageImageStoreDeviceTest` 4/4 通过，模拟器实测链路 RTT 8.7ms(基线)/365ms(API)/241ms+50%丢包(图床)，分类页在后台 `/setting` 完成前即出图；对冲与跨图床分摊后首个 `/album` 2.2s（原 11.2s）、11 页预取 10.0s 且分发到 4 台图床；历史语义修正后设备 `LibraryReadingTest` 7/7 通过，模拟器验证「只看详情→首页仍空、读一页→出现记录」；章节行简化后装机实测详情页无「操作」入口，仅保留标题、阅读状态与下载按钮。

### 域名来源实测（2026-10-30，直连 4 轮 + 系统代理 2 轮）

- 三源内容一致：`ETag=e15424d6cf9bc00273fe102d28e676e4`、`content-length=431`，6/6 轮 sha1 相同 → c03 是同一对象的北京副本，不是旧列表。
- 到达耗时：c03 260–319ms（6/6 轮第一个到）、c02 1.34–2.36s、c01 4.6–9.3s。
- 冷启动（取源 + 全候选探测）：我们 4.54s（直连均值）/ 3.35s（代理）；插件 `allSettled` 语义 9.85s / 7.48s，按插件 5s 超时折算仍 ≈8.4s。
- 5 个候选 `/setting` 全部 ok：`cdngwc.club` 1.3–3.2s、`cdnutc.cc` 1.6–2.8s、`cdnhjk.net` 1.9–2.9s、`cdngwc.net` 1.9–3.2s、`cdngwc.cc` 2.6–5.4s（最慢，从未测速夺冠）。
- 图床兜底 `cdn-msp3.jmdanjonproxy.vip` 实测 200：占位图 1.0s/3684B，封面 1.25s。
- 图床池实测：5 条线路通告 **3 台**不同 `img_host`（`tencent.jmdanjonproxy.xyz` 三条线路共享、`cdn-msp.jmapiproxy3.cc`、`cdn-msp.jmdanjonproxy.xyz`），占位图全部 200 / 3684B；模拟器上冷启 1.0–2.0s、热连接 0.25–0.28s，含兜底 4 台均 < 5s。
- 真机（`emulator-5554`）6 探针：独立 client 369ms，业务限速 client 1497ms（热连接两轮取最好），差 1128ms——拆分前这段排队时间被直接记进了 `latencyMs`。
- 结论：源清单/密钥/算法/文档结构已与插件对齐；并发策略是有意偏离（更快）；兜底语义已向插件靠拢（默认不参与，失败才用）。

已知工具问题：`:app:connectedDebugAndroidTest` 在结果 XML 全绿时仍返回失败（本机 AGP 9.4.1 + 模拟器 SM-A5360 的组合），判定以结果 XML 的 `tests/failures` 为准。

