<h1 align="center">Xiaozhi ESP32 Server Java · RAG 增强版</h1>

<p align="center">
  基于 <a href="https://github.com/joey-zhou/xiaozhi-esp32-server-java">joey-zhou/xiaozhi-esp32-server-java</a>（MIT）二次开发<br/>
  在原版之上实现 <b>用自己资料答题的 RAG 知识库</b> + <b>三层数据鉴权</b> + <b>前端权限修复</b>
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
**本仓库在原版之上加了 RAG 知识库问答**：把校园通知、办事指南这类资料切块入库，提问时先检索再注入，
让模型**按资料回答**而不是凭空编。

技术栈：Java 21 · Spring Boot 3.5.8 · Spring AI 1.1.4 · Sa-Token 1.39 · MyBatis-Plus · Qdrant · Redis · MySQL 8 · Vue 3

<p align="center">
  <img src="docs/images/architecture.png" alt="系统架构图" width="900" />
</p>

<h2 id="whatsnew">本仓库新增了什么</h2>

### 一、RAG 知识库全链路（核心增量）

| 环节 | 实现 |
|---|---|
| 切块 | 标题栈优先，600 字目标 / 800 硬上限 / 80 重叠（仅单段硬切时出现）|
| 向量化 | 云端 embedding 服务（1024 维），OpenAI 兼容接口 |
| 存储 | Qdrant，payload 带 15 个字段（doc_id/title/heading/category/publish_date/scope/user_id…）|
| 检索 | 余弦相似度 + **权限过滤下推到向量库**（`user_id` 或 `scope`）|
| 注入 | 清洗成可朗读文本 → 1500 字预算 → 边界标记 + "数据非指令"声明 |

### 二、检索质量优化

| 能力 | 效果 |
|---|---|
| 寒暄门控 | 61 词白名单，"你好/谢谢"直接跳过检索，省一次 embedding 外呼 |
| 查询缓存 | Redis，TTL 30 分钟；同一检索词 **2631ms → 9~17ms** |
| 多轮指代改写 | "那周日呢" 自动拼上轮问句（规则实现，不比 LLM 多花 1~2 秒）|
| 同义词扩展 | 饭卡 = 一卡通 = 校园卡 |
| 两段式 rerank | 召回 20 条 @0.35 → `gte-rerank-v2` 精排 → top5；**top1 正确率 86.7% → 90.0%**，p50 156ms；生产默认关，失败逐位回退基线 |

### 三、数据级鉴权（在上游两层之上补第三层）

上游已有：① Sa-Token 登录拦截（`/api/**`，fail-closed）＋ ② `@SaCheckPermission` 功能权限。
本仓库补的是 **③ 数据归属**：

| 层 | 归属 | 说明 |
|---|---|---|
| ① 登录 | 上游 | 沿用，未改 |
| ② 功能权限 | 上游机制 | 本仓库为知识库 8 个端点设计了权限码（`system:knowledge:api:*`）|
| ③ 数据归属 | **本仓库新增** | `KnowledgeAccessGuard`：本人 / 共享 / 管理员；**无权返回 404 而非 403**，不暴露资源存在性，避免被枚举 |
### 四、前端修复（原版可用性缺口）

- **权限键补 `:api:` 段 13 项** —— 原版普通角色被错藏「设备添加/账号编辑/对话记录」等入口（管理员有短路，长期无感知）
- 浮窗"点击重连"后无声 —— `reconnect()` 跳过音频初始化，Opus 帧全被丢弃
- 侧边栏知识库菜单无图标 —— 图标白名单漏登记

### 五、质量证据

| 指标 | 数值 |
|---|---|
| 单元测试 | 全仓 **1486 个全绿**（含上游自带的 12 个 ArchUnit 测试类 / 57 条断言，本仓库保持通过）|
| RAG 评估集 | 35 条（30 可答 + 5 应拒答）→ **Hit@5 100% / 拒答 100% / 平均分 0.7215** |
| 修复过的"假绿" | surefire 路径含空格导致 275 个 server 测试从未真正执行；dialogue 测试四竞态 |
| 端到端 | 实测：Qdrant 可达、82 点 / 1024 维、38 文档 82 块、检索 top1 命中 |

> 以上数字来自本地实测。**仓库不含知识库语料、评估集与测试脚本** —— 只公开结果，不公开资料与测试过程。

---

## 音色样本

两个角色都是云端复刻音色，样本由本项目的 TTS 链路直接生成（各 10 秒内，点击后浏览器可直接播放）：

| 角色 | 音色 | 试听 |
|---|---|---|
| 牢大 | cosyvoice-v2 复刻 | [laoda-1.mp3](samples/laoda-1.mp3) |
| 雪峰老师 | cosyvoice-v3-flash 复刻 | [xuefeng-1.mp3](samples/xuefeng-1.mp3) |

> 样本仅用于演示本项目的 TTS 链路，不含第三方媒体素材；如涉权益问题请联系删除。

---
## 与上游开源版的差异

本仓库相对**上游开源版**的增量：

| 能力 | 上游开源版 | 本仓库 |
|---|---|---|
| 知识库 / RAG | 无 | 全链路（切块 → 向量 → 检索 → 注入）|
| 检索优化 | 无 | 门控 / 缓存 / 改写 / 同义词 / rerank |
| 效果评估 | 无 | 35 条评估集 + 跑批脚本 |
| 数据级鉴权 | 无（仅登录+权限码）| 第三层：本人/共享/管理员，无权返回 404 |
| 上传大小边界 | 基础 | 三层（过滤器 413 / 不进堆 / 编排兜底）|
| 前端权限键 | 有缺口 | 修复 13 项 |
| 单元测试 | 部分 | 全仓 1486 个（架构断言为上游自带，保持通过）|

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