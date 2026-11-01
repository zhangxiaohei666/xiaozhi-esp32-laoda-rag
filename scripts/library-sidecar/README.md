# 图书馆登录守护进程

图书馆座位系统的登录依赖 **CAS 统一身份认证**（`cas.nyist.edu.cn`），登录页有：

- 图形验证码（4 位字母数字）
- 浏览器指纹字段 `fpVisitorId`
- 多因素认证字段 `mfaState`
- 密码前端加密

**纯 HTTP 无法自动化**，逆向学校核心认证系统也不合规。所以用一个真实浏览器登录一次，
再由本进程常驻维持会话，把 token 提供给 Java 侧的两个 Function Calling 工具。

**本进程不做任何业务查询**，只负责一件事：让 `GET /token` 总是返回一个可用的 token。

---

## 快速开始

```bash
cd scripts/library-sidecar
npm install
npx playwright install chromium      # 首次需要，约 150MB
```

设置账号密码（任选一组变量名）：

```bash
export NY_ACCOUNT=你的学工号
export NY_PASSWORD=你的密码
# 或者沿用旧变量名
export ny-zhanghao=你的学工号
export ny-mima=你的密码
```

启动：

```bash
npm start
```

第一次启动会尝试免密登录；如果没有登录态，日志会提示需要 `/relogin`：

```bash
curl -X POST http://127.0.0.1:8790/relogin
# 此时会弹出一个浏览器窗口：
#   - 账号密码已自动填好
#   - 「记住我」已自动勾选
#   - 你只需要输入 4 位验证码，点登录
```

登录成功后，token 会自动续期，**约 14 天内不用再登录**（取决于 CAS 的 rememberMe 策略）。

---

## 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/health` | 健康检查：是否有 token、剩余分钟数、是否需要登录 |
| GET | `/token` | 取 token（自动续期）|
| POST | `/relogin` | 唤起有头浏览器，人工完成一次 CAS 登录 |
| GET | `/cookies/export` | 导出 cookie（迁移/备份）|
| POST | `/cookies/import` | 导入 cookie（无 GUI 服务器的启动方式）|

### `/token` 返回示例

```json
{ "token": "eyJ0eX...", "expiresAt": 1793333322000, "loginRequired": false }
```

`loginRequired=true` 且 `token=null` 表示需要人工登录一次。

---

## 换服务器怎么迁（重点）

目标服务器通常是 **Linux 无桌面** 的 VPS，跑不了"有头浏览器人工登录"。
所以按下面的流程搬登录态：

```bash
# ① 在本地（有桌面的机器）登录一次
curl -X POST http://127.0.0.1:8790/relogin

# ② 导出 cookie
curl http://127.0.0.1:8790/cookies/export -o cookies.json

# ③ 拷到服务器
scp cookies.json user@server:/path/to/project/data/library/cookies.json

# ④ 在服务器上启动侧车，它会自动读这个文件
npm start

# ⑤ 验证
curl http://127.0.0.1:8790/health     # 期望 hasToken=true
```

也可以不用文件，直接调接口导入：

```bash
curl -X POST http://127.0.0.1:8790/cookies/import \
     -H 'Content-Type: application/json' -d @cookies.json
```

**为什么搬 cookie 而不是搬 profile 目录**：

| 物件 | 体积 | 跨平台 | 搬吗 |
|---|---|---|---|
| `data/library/profile/` | 几百 MB | ❌ | 不搬 |
| `data/library/cookies.json` | 几 KB | ✅ | **搬这个** |

---

## 配置（全部环境变量，代码里没有硬编码）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LIBRARY_SIDECAR_PORT` | `8790` | 监听端口（只绑 127.0.0.1）|
| `LIBRARY_BASE_URL` | `https://zwyy.nyist.edu.cn` | 图书馆系统地址，换学校改这里 |
| `LIBRARY_DATA_DIR` | `<项目根>/data/library` | 数据目录 |
| `LIBRARY_PROFILE` | `<DATA_DIR>/profile` | 浏览器 profile |
| `LIBRARY_COOKIE_FILE` | `<DATA_DIR>/cookies.json` | cookie 落盘位置 |
| `LIBRARY_LOG_DIR` | `<项目根>/logs` | 日志目录 |
| `NY_ACCOUNT` / `ny-zhanghao` | 空 | 学工号 |
| `NY_PASSWORD` / `ny-mima` | 空 | 密码 |

---

## 会话维持机制

```
CASTGC（CAS 票据）    ← 勾「记住我」后约 14 天
    │ 自动换
    ▼
token（图书馆 JWT）   ← 100 分钟

token 过期  → 打开座位页，CAS 自动 302 回来 → 新 token（零人工）
CASTGC 过期 → 需要 POST /relogin 人工登一次
```

- **提前 10 分钟续期**：不等 token 真过期
- **30 分钟心跳**：访问一次座位页，避免 CAS TGT 因空闲过期
- **cookie 落盘**：进程重启不用重新登录

---

## 安全约定

| 项 | 约定 |
|---|---|
| 监听地址 | **只绑 `127.0.0.1`**，不对局域网暴露 |
| token | 只在内存，不写日志、不落盘 |
| 账号密码 | 只从环境变量读，**永不打印**；自动填表后不再持有 |
| cookie 文件 | 落在 `data/library/`（已在 `.gitignore` 里），含登录态，不要提交 |
| 验证码 | **始终由人工输入**，不做 OCR 识别 |
| 使用范围 | 仅本人账号 |

---

## 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `/health` 里 `hasToken=false` 且 `loginRequired=true` | 登录态过期 | `POST /relogin` |
| 启动报 `Executable doesn't exist` | 没装浏览器 | `npx playwright install chromium` |
| 日志出现 `等待人工登录超时` | 10 分钟内没完成登录 | 重新 `/relogin` |
| Java 侧报「图书馆查询服务没启动」| 侧车没跑或端口不对 | 检查进程与 `LIBRARY_SIDECAR_URL` |
| 学校改版导致页面选择器失效 | 前端改版 | 更新 `manualLogin()` 里的选择器 |

## 依赖

- Node.js >= 20
- playwright ^1.64.1（`package.json` 已锁）
- Chromium（`npx playwright install chromium`）