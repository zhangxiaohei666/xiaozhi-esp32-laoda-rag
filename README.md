<h1 align="center">Xiaozhi ESP32 Server Java · RAG 增强版</h1>

<p align="center">
  基于 <a href="https://github.com/joey-zhou/xiaozhi-esp32-server-java">joey-zhou/xiaozhi-esp32-server-java</a>（MIT）二次开发<br/>
  在原版之上实现 <b>自研 RAG 检索链路（V1 固定注入 → V2 Agentic 工具调用）</b> + <b>校园工具集合（图书馆 9 工具）</b> + <b>三层数据鉴权</b>
</p>

<p align="center">
  <a href="https://github.com/zhangxiaohei666/xiaozhi-esp32-laoda-rag/issues">反馈问题</a>
  · <a href="#quickstart">快速开始</a>
  · <a href="#whatsnew">本仓库新增了什么</a>
</p>

<p align="center">
  <a href="https://github.com/zhangxiaohei666/xiaozhi-esp32-laoda-rag">
    <img alt="stars" src="https://img.shields.io/github/stars/zhangxiaohei666/xiaozhi-esp32-laoda-rag" /></a>
  <a href="https://github.com/zhangxiaohei666/xiaozhi-esp32-laoda-rag/blob/main/LICENSE">
    <img alt="License" src="https://img.shields.io/badge/license-MIT-white?labelColor=black" /></a>
</p>

---

## 这个仓库是什么

硬件侧用 ESP32 成品设备当"耳朵和嘴"，服务端做"大脑"。原版提供设备管理、角色/音色、对话记录等后台能力；
**本仓库在原版之上加了 RAG 与工具调用**：把校园通知、办事指南这类资料切块入库，检索经 V1 固定注入
→ V2 Agentic 工具化两步演进，目前由主 LLM **自主决定是否检索**；另接入了图书馆座位 9 个工具
（查座 / 预约 / 取消），写操作全部两段式确认 + 幂等。

技术栈：Java 21 · Spring Boot 3.5.8 · Spring AI 1.1.4 · Sa-Token 1.39 · MyBatis-Plus · Qdrant · Redis · MySQL 8 · Vue 3

<p align="center">
  <img src="docs/images/architecture.png" alt="系统架构图" width="900" />
</p>

<h2 id="whatsnew">本仓库新增了什么</h2>

### 一、RAG 检索链路（两步演进）

#### V1：自研固定注入（已下线，保留为对照基线）

把切块/检索/重排的结果按 1500 字符预算组装后**每轮无条件注入** system prompt。
配合寒暄门控（61 词白名单跳过无意义问候）、多轮指代改写（"那周日呢"拼上轮）、
同义词扩展（饭卡=一卡通=校园卡）提升命中率。35 条评估集 **Hit@5 100% / 拒答 100%**，
重排后 **Top1 86.7%→90.0%**。

#### V2：Agentic 工具调用（当前生产路径）

将检索注册为全局工具 `knowledge_search`，由主 LLM **自主决定是否检索与检索词**，
删除代码侧的寒暄门控与查询改写（与模型自身决策重复）。实测指标：

| 指标 | 改前 | 改后 |
|---|---|---|
| 错锚点识别（回答能归因到资料的比例） | 20% | **80%** |
| 无检索直接作答（不该查的也查了） | 4 次 | **0 次** |
| 检索内核（权限/缓存/重排/限额） | — | **零回归** |

#### 贯穿两阶段的检索基础

无论注入路径还是工具路径，底层都是同一套 `doSearch`：

| 环节 | 实现 |
|---|---|
| 切块 | 标题感知（标题路径两级截断 45/62 字）+ 600 字目标 / 800 硬上限 / 80 重叠 |
| 向量化 | 云端 embedding（1024 维），OpenAI 兼容接口 |
| 存储 | Qdrant，payload 含 15 字段（doc_id/title/heading/category/publish_date/scope/user_id…）|
| 检索 | 余弦相似度 + **权限过滤下推到向量库**（`user_id`=我 OR `scope`=shared；`userId` 缺失只查共享，fail-closed）|
| 阈值 | **0.50**（35 条评测集五档扫描校准：垃圾天花板 0.49 < 0.50 < 正确地板 0.61）|
| 缓存 | Redis，SHA-256(查询|用户|重排指纹)，TTL 30 分钟，只存非空结果 |
| 重排 | 可选 `gte-rerank-v2`：召回 20 路@0.35 → 拒答门仍 0.50 → 精排取 top5；失败回退到与基线逐位等价的路径 |
| 限额 | 每篇文档最多贡献 2 条（在重排后套用）|
| 权限语义 | 越权统一返回空结果（工具路径）或 404（注入路径），不暴露资源存在性 |


### 三、校园工具：图书馆座位预约

9 个工具覆盖查座 → 预约 → 取消 → 暂离 → 签退全流程：

| 分类 | 工具 | 说明 |
|---|---|---|
| 读（4 个） | `seat_overview` / `area_seats` / `my_bookings` / `my_favorites` | 查座位总览、区域可约时段、我的预约、我的收藏 |
| 准备（4 个） | `prepare_reserve` / `prepare_cancel` / `prepare_leave` / `prepare_checkout` | **不调用写接口**，只生成待确认快照与复述文案 |
| 执行（1 个） | `library_confirm_action` | **唯一写入口**：读 Redis 快照 → SETNX 幂等 → 调校园接口 |

**设计要点**

- **prepare→confirm 两段式**：LLM 可能重复调用工具或提前执行，prepare 不碰真实数据，
  用户说"确认"才由 confirm 真正写库
- **幂等**：Redis SETNX，预约按 用户+日期+座位+时段，其余按记录 id，窗口 10 分钟
- **待确认状态键用 userId 而非 sessionId**：语音链路断线重连会换会话，但用户是同一个人
- **凭据加密**：校园账号密码经 `LibraryCrypto` 加密存储，不进 prompt 与日志
- **工具身份**：实现 `ToolsGlobalRegistry.GlobalFunction` 接口 + JSON Schema 自描述参数，
  会话身份在注册期注入（模型拿不到 userId），新增工具不改对话内核
### 四、数据级鉴权（在上游两层之上补第三层）

上游已有：① Sa-Token 登录拦截（`/api/**`，fail-closed）＋ ② `@SaCheckPermission` 功能权限。
本仓库补的是 **③ 数据归属**：

| 层 | 归属 | 说明 |
|---|---|---|
| ① 登录 | 上游 | 沿用，未改 |
| ② 功能权限 | 上游机制 | 本仓库为知识库 8 个端点设计了权限码（`system:knowledge:api:*`）|
| ③ 数据归属 | **本仓库新增** | `KnowledgeAccessGuard`：本人 / 共享 / 管理员；**无权返回 404 而非 403**，不暴露资源存在性，避免被枚举 |
### 五、前端修复（原版可用性缺口）

- **权限键补 `:api:` 段 13 项** —— 原版普通角色被错藏「设备添加/账号编辑/对话记录」等入口（管理员有短路，长期无感知）
- 浮窗"点击重连"后无声 —— `reconnect()` 跳过音频初始化，Opus 帧全被丢弃
- 侧边栏知识库菜单无图标 —— 图标白名单漏登记

### 六、质量证据

| 指标 | 数值 |
|---|---|
| 单元测试 | 全仓 **1540 个全绿**（含上游自带的 12 个 ArchUnit 测试类 / 57 条断言 + RAG/工具新增 4 个测试类 / 41 例）|
| RAG 评估集 | 35 条（30 可答 + 5 应拒答）→ **Hit@5 100% / 拒答 100% / 平均分 0.7215** |
| 修复过的"假绿" | surefire 路径含空格导致 275 个 server 测试从未真正执行；dialogue 测试四竞态 |
| 端到端 | 实测：Qdrant 可达、82 点 / 1024 维、38 文档 82 块 |

> 以上为 V1 固定注入路径的实测数据。Agentic 切换后按新口径复测（错锚点/白查），结果见上文 V2 段。
>
> 以上数字来自本地实测。**仓库不含知识库语料、评估集与测试脚本** —— 只公开结果，不公开资料与测试过程。

---

## 音色样本

两个角色都是云端复刻音色，样本由本项目的 TTS 链路直接生成（各 10 秒内，点击后浏览器可直接播放）：

| 角色 | 音色 | 试听 |
|---|---|---|
| 牢大 | cosyvoice-v2 复刻 | [laoda-1.mp3](samples/laoda-1.mp3) |
| 雪峰老师 | cosyvoice-v3-flash 复刻 | [xuefeng-1.mp3](samples/xuefeng-1.mp3) |

> 样本仅用于演示本项目的 TTS 链路，不含第三方媒体素材；如涉权益问题请联系删除。
**想自己训练音色？** 见 [`samples/reference/`](samples/reference/README.md) —— 参考音频的处理配方（时长、去 BGM、采样率、GPT-SoVITS 参数）与清洗脚本 `prepare-reference.ps1`。
两份材料都要求**自己的**音频；本仓库不提供任何他人声音素材。

---
## 与上游开源版的差异

本仓库相对**上游开源版**的增量：

| 能力 | 上游开源版 | 本仓库 |
|---|---|---|
| 知识库 / RAG | 无 | 自研全链路，固定注入 → Agentic 工具化两步演进，当前由 LLM 自主检索 |
| 检索优化 | 无 | 缓存 + 两段式 rerank（gte-rerank-v2）+ 权限过滤下推 + 阈值校准 |
| 校园工具 | 无 | 图书馆 9 工具（查座/预约/取消/暂离/签退），两段式确认 + 幂等 |
| 数据级鉴权 | 无（仅登录+权限码）| 第三层：本人/共享/管理员，无权返回 404 |
| 上传大小边界 | 基础 | 三层（过滤器 413 / 不进堆 / 编排兜底）|
| 前端权限键 | 有缺口 | 修复 13 项 |
| 单元测试 | 部分 | 全仓 **1540** 个（架构断言为上游自带，保持通过；RAG/工具新增 4 类 / 41 例）|

<h2 id="quickstart">快速开始</h2>

> 本仓库只含**代码**；启动脚本与部署文档在个人工作区（未公开）。以下是最小启动路径。

```bash
# 1) 中间件：仓库自带 docker-compose.yml（MySQL 8 / Redis 7 / Qdrant）
docker compose up -d

# 2) 环境变量（均不进仓库）
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/xiaozhi?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8&useSSL=false'
export SPRING_DATASOURCE_USERNAME=xiaozhi
export SPRING_DATASOURCE_PASSWORD='<你的库密码>'
export SPRING_DATA_REDIS_HOST=127.0.0.1
export QDRANT_HOST=127.0.0.1
export JWT_SECRET_KEY='<至少32位随机串>'
export aliqwen-api='<阿里云百炼 Key>'       # 变量名沿用项目约定
# 注意：不要设置 SPRING_DATA_REDIS_PASSWORD（Redis 无密码时会使 Redisson 握手失败）

# 3) 构建 + 起服务（两个进程：8091 后台 API / 8092 设备 WebSocket）
mvn -B clean package -DskipTests
java -jar xiaozhi-server/target/xiaozhi-server-*.jar      # 8091
java -jar xiaozhi-dialogue/target/xiaozhi-dialogue-*.jar  # 8092
```

首次启动由 Flyway 建表。前端在 `web/`（`npm i && npm run dev`，默认 8084），后台账号 `admin / 123456`。
更详细的部署说明见仓库 `docs/` 与上游文档。

---

## 不做什么

- 不提供任何媒体/音色内容，不打包第三方素材
- 仓库内**不含**知识库语料、聊天记录、API Key（全部走环境变量与本地目录）
- `db/` 下是上游自带的建表与权限种子 SQL，**没有任何业务数据**；其中库账号的默认口令属开发值，生产务必修改
- 不做多租户 SaaS，定位是个人/小团队自用

---

## 与上游的关系

- 基于 [`joey-zhou/xiaozhi-esp32-server-java`](https://github.com/joey-zhou/xiaozhi-esp32-server-java)（MIT）二次开发，**保留原许可**
- 定期 `git merge origin/main` 跟随上游（已合并至上游 `1d19fdd`）
- 原项目的商业版、微信群、QQ 群与本仓库无关

---

## 免责声明

本项目仅提供技术实现代码，不提供任何媒体内容。用户在使用相关功能时应确保拥有合法的使用权或版权许可，并遵守所在地区的版权法律法规。

项目中可能涉及的示例内容或资源均来自网络或由用户投稿提供，仅用于功能演示和技术测试。如有任何内容侵犯了您的权益，请联系我们，将在核实后立即删除。