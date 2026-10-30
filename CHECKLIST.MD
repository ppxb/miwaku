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
| 剔除策略 | Dead 出池；失败与 `Decode` 只降权 + 指数冷却（30s / 2min 起，上限 10min） | — | — | degraded 只降权，不出池 | `report_failure` 计数 + 冷却 |
| 刷新 | 30min TTL + 失败强制刷新 | — | — | 30min 被动 TTL | 2h 定时 + 手动锁定 |
| 图床 | 池 + 占位图探测，`nextImageHost` 轮换；池空/占位图全灭时落静态兜底 `cdn-msp3.jmdanjonproxy.vip` | — | — | 图床池 + 占位图 `/users/nopic-.gif` 探测 + 静态兜底 `JM_FALLBACK_IMAGE_BASE` | 同插件 |
| 拦截器 | 重试 + 限速（3/s）+ 指标 | DoH + Cloudflare WebView + 限速 | — | 无（宿主负责） | — |
| UA | 固定常量 | 可配 | — | 随机机型 + 持久 deviceId | — |

**差距**：我们是「能切换」，插件是「知道该切谁、切完还能回来」。但**取源这一环我们更快**：3 源内容同 ETag，最近端起 0.26–0.32s，插件必须等最慢源落地（≈5s 超时）才开始探测。

### 2. 响应解码 / 协议

| | miwaku | 插件 | jm-boom |
|---|---|---|---|
| 签名 | token/tokenparam ✅ | 同 | 同 + jwt |
| 解密 | 双 seed 轮试 + BOM/gzip/URL-safe base64 ✅ | 同 | 同 |
| 错误分类 | 5 类（Network/Http/Business/Decode…） | HTTP vs body.code + 401 单列 | 5 类 + 401 |
| `/setting` 解析 | 正则（DomainResolver） | JSON | JSON |
| 契约文档 | 无 | 插件自带类型 | Rust 类型 + serde |

**差距**：401（登录态）未单列；`/setting` 走正则，与 `ApiClient` 的 JSON 解析是两套实现。

### 3. 图片管线

| | miwaku | komikku | Breeze | jm-boom |
|---|---|---|---|---|
| 落盘内容 | 原图（解扰在解码时） | 原图（解码时解扰） | 已解扰图（可再 WebP/超分） | 原图 |
| 去重 | 单飞 flight + owner 计数 | Coil single-flight | 单飞（asset store） | KeyedLock + 锁内复查 |
| 并发 | 3 个传输 | 读者线程 2，fetcher 8 / decoder 3 | 5（可被插件声明） | 网络 12 / 解码 4，双预算 |
| 优先级 | Visible/Nearby/Behind/Warmup/Download | 三级（当前页/预取/重试） | 无（顺序预热） | 三级 |
| 取消 | 最后一个使用者退出才取消 Call | 随 composable 销毁 | 取消信号 | watch + select，取消不换图床 |
| 完整性校验 | 尺寸 > 0 | — | — | magic 判格式 + EOI/IEND + 读时自愈 |
| 尺寸缓存 | 仅内存（进程死即失） | Coil 内存 | zstd 二进制落盘 | — |
| 缓存上限 | 1GB（min 512MB），缓存目录 LRU | ChapterCache 可配 | 磁盘 LRU | `evict_if_needed` |

**差距**：①尺寸不落盘，冷启动 webtoon 先抖；②无写入完整性校验（截断图能入库）；③缓存与离线两套目录（已用 `releaseCachedCopy` 缓解，理想是统一 canonical path）。

### 4. 阅读器

| | miwaku | komikku | Breeze |
|---|---|---|---|
| 结构 | 1 个 1650 行 Screen + 357 行 VM | `ui/reader/**` 多文件 + `PageLoader` 抽象 | 8 个 controller + cubit |
| 预取 | 需求集 + 优先级 + 末位取消，ahead 6 / behind 2 | 优先级队列，preload 10 | key 去重预热，不建 Widget |
| 进度写 | 1s 合并 + 跨章/退后台立即提交 | 每翻页同步写 | 500ms 定时写（不做差异判断） |
| 已读判定 | 末页成功显示才置位 | 到末页置位 | 宿主负责 |
| 跨章 | 窗口内邻章 + 过渡页 | 切章重建 | 无缝跨章 + 双页 + 音量键 + 自动阅读 |
| 位置恢复 | 文件名优先 + 页内偏移 | 页号 | global slot + 双页映射 |

**差距**：语义我们最好，结构我们最差。

### 5. 下载

| | miwaku | komikku | Breeze |
|---|---|---|---|
| 调度 | 单 worker + 章节内页并发 1–5 | 源级并行 5 + 页级 5 | worker pool 5（插件可声明）+ 任务队列 |
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
| 图片传输并发 | 3 | 2（读者线程） | 5 | 12（网络预算） |
| 解码并发 | 1（全局串行） | 3 | 宿主决定 | 4 |
| 端点探测并发 | 6 | — | 6 | 6 |
| 请求超时 | API 20s / 图片 60s | 30/30/120s | 10s | — |
| 图片体积上限 | 64 MiB（双层） | — | — | 64 MiB（双层） |
| 缓存上限 | 1GB（可调 512–2048） | 可配 | — | 逐出策略 |
| 下载页并发 | 1–5（默认 3） | 5 | 5 | — |
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
| 解扰时机 | 读时解扰，磁盘存原图 | 写时解扰（Breeze），可再 WebP/超分 | 保持 A：原图是导出/换源资产；Coil 内存缓存兜底，JPEG 实测 20ms |
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
- [ ] A7 UA 随机机型 + 持久 deviceId
- [ ] B2 下载改 worker pool，`recordPage` 批量提交（验收：150 页 ≤ 30 次事务）
- [ ] B4 下载直写离线目录，取消「复制后删」（验收：同一页磁盘只有一份）

### P1（结构债）

- [ ] B3 拆 `ReaderScreen`（viewport / webtoon / paged / menu），`currentGlobal` 收敛单通道（验收：单文件 < 500 行，30 个设备用例全绿）
- [ ] A3 下载提交前做图片完整性校验（magic + EOI/IEND）
- [ ] A4 页尺寸二进制落盘（验收：冷启动 webtoon 不依赖 `medianPageRatio`）
- [ ] B1 抽 `PageSource` 接口（在线 / 离线），为压缩包与导出铺路
- [ ] 补 `docs/api-contract.md` + `Codec`/`Scramble`/`PageMapper` 单测（fixtures 驱动）

### P2（功能与体验）

- [ ] B5 图片并发可配置（1–5），与下载并发解耦
- [ ] B6 设置页展示端点列表 / 延迟 / 状态，支持锁定
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
| 测试 | `CodecHostListTest`、`EndpointHealthTest`、`EndpointStateTest`、`EndpointJsonTest`、`EndpointPoolE2ETest` | JVM 19 例 + 设备 33 例全绿 |

验证：`:app:lintDebug`、`:app:testDebugUnitTest`、`:core:network:testDebugUnitTest`、`:core:database:testDebugUnitTest` 全部通过；设备侧 `TEST-SM-A5360.xml` 记录 33/33 通过（AGP 任务本身仍报 exit code 1，见下）。兜底二级探测与图床静态兜底改动后各重跑一次 `:core:network:testDebugUnitTest`，编译 + 单测均通过；探测传输拆分后 `:core:network:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin` 通过，模拟器 `emulator-5554` 上 `EndpointPoolE2ETest` 3/3 通过。

### 域名来源实测（2026-10-30，直连 4 轮 + 系统代理 2 轮）

- 三源内容一致：`ETag=e15424d6cf9bc00273fe102d28e676e4`、`content-length=431`，6/6 轮 sha1 相同 → c03 是同一对象的北京副本，不是旧列表。
- 到达耗时：c03 260–319ms（6/6 轮第一个到）、c02 1.34–2.36s、c01 4.6–9.3s。
- 冷启动（取源 + 全候选探测）：我们 4.54s（直连均值）/ 3.35s（代理）；插件 `allSettled` 语义 9.85s / 7.48s，按插件 5s 超时折算仍 ≈8.4s。
- 5 个候选 `/setting` 全部 ok：`cdngwc.club` 1.3–3.2s、`cdnutc.cc` 1.6–2.8s、`cdnhjk.net` 1.9–2.9s、`cdngwc.net` 1.9–3.2s、`cdngwc.cc` 2.6–5.4s（最慢，从未测速夺冠）。
- 图床兜底 `cdn-msp3.jmdanjonproxy.vip` 实测 200：占位图 1.0s/3684B，封面 1.25s。
- 真机（`emulator-5554`）6 探针：独立 client 369ms，业务限速 client 1497ms（热连接两轮取最好），差 1128ms——拆分前这段排队时间被直接记进了 `latencyMs`。
- 结论：源清单/密钥/算法/文档结构已与插件对齐；并发策略是有意偏离（更快）；兜底语义已向插件靠拢（默认不参与，失败才用）。

已知工具问题：`:app:connectedDebugAndroidTest` 在结果 XML 全绿时仍返回失败（本机 AGP 9.4.1 + 模拟器 SM-A5360 的组合），判定以结果 XML 的 `tests/failures` 为准。

