/**
 * 图书馆座位系统 —— 登录守护进程
 *
 * 职责只有一件事：**维护一个可用的 token**。
 * 所有业务（查座位、以后的预约）都在 Java 进程里，这里不碰。
 *
 * 为什么需要它：图书馆用 CAS 统一身份认证，登录页有图形验证码、浏览器指纹、
 * MFA 字段，纯 HTTP 无法自动化；逆向学校核心认证系统也不合规。
 * 所以用真实浏览器登录一次，然后由本进程常驻维持会话。
 *
 * 对外接口（只监听 127.0.0.1）：
 *   GET  /token            → { token, expiresAt, loginRequired }
 *   POST /relogin          → 唤起有头浏览器（自动填账号密码 + 勾记住我，人只需输验证码）
 *   GET  /cookies/export   → 导出 cookie（迁移/备份用）
 *   POST /cookies/import   → 导入 cookie（无 GUI 服务器的启动方式）
 *   GET  /health           → 健康检查
 *
 * 会话维持：
 *   1. 登录时勾「记住我」→ CASTGC 变长效（Apereo CAS 默认 14 天）
 *   2. token 100 分钟过期，提前 10 分钟自动换新
 *   3. 每 15 分钟心跳访问一次 CAS 入口
 *   4. cookie 落盘，进程重启后自动恢复
 *
 * ⚠️ 安全：只绑 127.0.0.1；token 只在内存；账号密码从环境变量读，永不打印。
 */
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
/** 项目根目录：scripts/library-sidecar → 上两级 */
const PROJECT_ROOT = path.resolve(__dirname, '..', '..');

// ============ 配置（全部可被环境变量覆盖，代码里不留硬编码） ============
const PORT = Number(process.env.LIBRARY_SIDECAR_PORT || 8790);
const BASE = process.env.LIBRARY_BASE_URL || 'https://zwyy.nyist.edu.cn';
const SEAT_PAGE = `${BASE}/h5/#/seat/space`;

const DATA_DIR = process.env.LIBRARY_DATA_DIR || path.join(PROJECT_ROOT, 'data', 'library');
const PROFILE_DIR = process.env.LIBRARY_PROFILE || path.join(DATA_DIR, 'profile');
const COOKIE_FILE = process.env.LIBRARY_COOKIE_FILE || path.join(DATA_DIR, 'cookies.json');
const LOG_DIR = process.env.LIBRARY_LOG_DIR || path.join(PROJECT_ROOT, 'logs');
const LOG_FILE = path.join(LOG_DIR, 'library-sidecar.log');

const RENEW_BEFORE_MS = 10 * 60 * 1000;   // token 提前 10 分钟续期
const HEARTBEAT_MS = 15 * 60 * 1000;      // 心跳 15 分钟：CAS 的 TGT 空闲超时较短，30 分钟实测会掉线
const LOGIN_WAIT_MS = 10 * 60 * 1000;     // 人工登录最多等 10 分钟

const ACCOUNT = process.env.NY_ACCOUNT || process.env['ny-zhanghao'] || '';
const PASSWORD = process.env.NY_PASSWORD || process.env['ny-mima'] || '';

fs.mkdirSync(LOG_DIR, { recursive: true });
fs.mkdirSync(DATA_DIR, { recursive: true });

// ============ 日志 ============
function log(...args) {
  const line = `[${new Date().toISOString()}] ` +
    args.map((a) => (typeof a === 'string' ? a : JSON.stringify(a))).join(' ');
  console.log(line);
  try {
    fs.appendFileSync(LOG_FILE, line + '\n');
  } catch {}
}

// ============ 状态 ============
let context = null;
let page = null;
let token = null;
let tokenExpAt = 0;
let loginRequired = false;
/**
 * 人工登录保护期（epoch ms）。在这个时间点之前，心跳一律跳过。
 *
 * <p>为什么需要：心跳里会 {@code page.goto(CAS 入口)}（保活 TGT）或 {@code forceRelogin()}
 * （清 sessionStorage + 重走 CAS）。如果人正在窗口里输验证码，页面被跳走 = 前功尽弃。
 * 早期版本没有这个保护，实测会在填表途中被心跳打断。
 */
let humanLoginUntil = 0;
let lastRenewAt = 0;
let renewing = null;

// ============ 工具 ============
function readJwtExp(jwt) {
  try {
    const parts = String(jwt).split('.');
    if (parts.length < 2) return 0;
    let p = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    while (p.length % 4) p += '=';
    const obj = JSON.parse(Buffer.from(p, 'base64').toString('utf8'));
    return obj.exp ? Number(obj.exp) * 1000 : 0;
  } catch {
    return 0;
  }
}

async function readTokenFromPage() {
  try {
    return await page.evaluate(() => sessionStorage.getItem('token'));
  } catch {
    return null;
  }
}

/** 从文件恢复 cookie（迁移/重启的关键） */
async function restoreCookies() {
  if (!context) return false;
  if (!fs.existsSync(COOKIE_FILE)) return false;
  try {
    const list = JSON.parse(fs.readFileSync(COOKIE_FILE, 'utf8'));
    if (!Array.isArray(list) || list.length === 0) return false;
    await context.addCookies(list);
    log(`已从 ${path.relative(PROJECT_ROOT, COOKIE_FILE)} 恢复 ${list.length} 条 cookie`);
    return true;
  } catch (e) {
    log('恢复 cookie 失败:', e.message);
    return false;
  }
}

/** 把当前 cookie 落盘 */
async function persistCookies() {
  if (!context) return;
  try {
    const list = await context.cookies();
    fs.writeFileSync(COOKIE_FILE, JSON.stringify(list, null, 2), 'utf8');
  } catch (e) {
    log('保存 cookie 失败:', e.message);
  }
}

async function ensureBrowser({ headed = false } = {}) {
  if (context) return;
  log(`启动浏览器（${headed ? '有头' : '无头'}）profile=${path.relative(PROJECT_ROOT, PROFILE_DIR)}`);
  // 2026-11-18：支持复用系统已安装的 Chrome，免去下载 150MB Chromium。
  //   PLAYWRIGHT_CHANNEL=chrome  → 用系统 Chrome（需本机装了 Chrome）
  //   留空                       → 沿用默认行为（用 ms-playwright 缓存里的 Chromium）
  const launchOpts = {
    headless: !headed,
    viewport: { width: 1440, height: 900 },
    locale: 'zh-CN',
    args: ['--disable-blink-features=AutomationControlled'],
  };
  const channel = process.env.PLAYWRIGHT_CHANNEL;
  if (channel) {
    launchOpts.channel = channel;
    log(`使用系统浏览器 channel=${channel}`);
  }
  context = await chromium.launchPersistentContext(PROFILE_DIR, launchOpts);
  page = context.pages()[0] || (await context.newPage());
  await restoreCookies();
}

/**
 * 走一次 CAS 拿 token。
 *
 * ⚠️ 关键：必须访问 **CAS 入口** `/v4/login/cas`，不能直接开座位页。
 * 原因有两层：
 *   1. CAS 的 TGT 只有在访问 cas 域名时才会被续期，只打图书馆域名等于没保活；
 *   2. 座位页的前端不会主动去换 token，必须走完
 *      `/v4/login/cas → CAS → ?ticket=xxx → 前端 #/cas 页 → POST /v4/login/user` 这条链，
 *      token 才会被写回 sessionStorage。
 */
async function acquireToken({ headed = false, waitMs = 20000 } = {}) {
  await ensureBrowser({ headed });

  // 先看当前页是否已经有可用 token（同一个 page 对象里 sessionStorage 是共享的）
  let t = await readTokenFromPage();
  if (t && readJwtExp(t) > Date.now() + 60_000) {
    await persistCookies();
    return t;
  }

  log('走 CAS 入口换取 token…');
  await page
    .goto(`${BASE}/v4/login/cas`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    .catch((e) => log('打开 CAS 入口失败:', e.message));

  // CAS 会话还在时会一路 302 回到 h5 的 #/cas 页，前端在那边调 /v4/login/user 换 token
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    await page.waitForTimeout(1000);
    t = await readTokenFromPage();
    if (t && readJwtExp(t) > Date.now() + 60_000) {
      log('✅ 自动登录成功（CAS 会话仍有效）');
      await persistCookies();
      return t;
    }
  }

  // 兜底：万一前端没自动跳，直接开座位页再试一次
  t = await readTokenFromPage();
  if (!t) {
    await page.goto(SEAT_PAGE, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => {});
    await page.waitForTimeout(3000);
    t = await readTokenFromPage();
    if (t && readJwtExp(t) > Date.now() + 60_000) {
      log('✅ 从座位页取到 token');
      await persistCookies();
      return t;
    }
  }

  log('⚠️ 自动登录失败，当前页:', page.url());
  loginRequired = true;
  return null;
}

function renewToken({ headed = false, waitMs = 20000 } = {}) {
  if (renewing) return renewing;
  renewing = (async () => {
    try {
      const t = await acquireToken({ headed, waitMs });
      if (t) {
        token = t;
        tokenExpAt = readJwtExp(t) || Date.now() + 100 * 60 * 1000;
        loginRequired = false;
        lastRenewAt = Date.now();
        const leftMin = Math.round((tokenExpAt - Date.now()) / 60000);
        log(`token 已更新，剩余 ${leftMin} 分钟`);
      }
      return t;
    } catch (e) {
      log('续期异常:', e.message);
      return null;
    } finally {
      renewing = null;
    }
  })();
  return renewing;
}

async function getToken() {
  if (token && tokenExpAt - Date.now() > RENEW_BEFORE_MS) {
    return { token, expiresAt: tokenExpAt, loginRequired: false };
  }
  await renewToken();
  if (token && tokenExpAt - Date.now() > 0) {
    return { token, expiresAt: tokenExpAt, loginRequired: false };
  }
  return { token: null, expiresAt: 0, loginRequired: true };
}

/**
 * 心跳：**必须访问 CAS 域名**才能保住服务端的 TGT。
 *
 * 早期版本心跳只开座位页（图书馆域名），结果 TGT 从未被续期，
 * 空闲超时后直接掉线 —— 这就是"3 小时后登录没了"的原因。
 *
 * 现在的做法：每次心跳都走一次 CAS 入口，
 * 既刷了 TGT，又顺手把 token 换成新的（一举两得）。
 */
async function heartbeat() {
  try {
    await ensureBrowser();

    const leftMs = tokenExpAt - Date.now();

    // ── 情况 A：token 还很新 ──
    // 仍然要访问一次 CAS 域名。这一步是"保活"，跟 token 无关：
    // CAS 的 TGT 只有被访问才会刷新 idle timeout，不访问就会在服务端悄悄过期。
    // 早期版本的 bug 就是这里直接 return，导致 TGT 白白空闲 90 分钟然后失效。
    if (token && leftMs > RENEW_BEFORE_MS) {
      await page
        .goto(`${BASE}/v4/login/cas`, { waitUntil: 'domcontentloaded', timeout: 45000 })
        .catch((e) => log('保活访问 CAS 失败:', e.message));
      await page.waitForTimeout(2000);
      // 顺手看有没有意外拿到更新的 token（TGT 有效时 CAS 会带 ticket 回来）
      const t = await readTokenFromPage();
      if (t) {
        const exp = readJwtExp(t);
        if (exp > tokenExpAt + 60_000) {
          applyToken(t);
          await persistCookies();
          log(`保活顺带刷新了 token（剩 ${Math.round((tokenExpAt - Date.now()) / 60000)} 分钟）`);
          return;
        }
      }
      log(`保活完成：已访问 CAS，token 还剩 ${Math.round(leftMs / 60000)} 分钟`);
      return;
    }

    // ── 情况 B：token 快过期（或已过期）──
    // 必须清掉页面里的旧 token，否则前端认为已登录、不会走 CAS 换新的
    log(`心跳：token 剩 ${Math.max(0, Math.round(leftMs / 60000))} 分钟，开始强制续期…`);
    const t = await forceRelogin({ waitMs: 30000 });

    if (t) {
      applyToken(t);
      await persistCookies();
      loginRequired = false;
      log('✅ 心跳续期成功');
      return;
    }

    loginRequired = true;
    log('❌ 心跳续期失败：CAS 会话可能已失效');
    log('   若持续失败，请执行: start-library.ps1 relogin（在项目 .run 目录下）');
    await quickRetry();
  } catch (e) {
    log('心跳异常:', e.message);
  }
}

/**
 * 强制走一次完整 CAS 登录，换取**真正的新** token。
 *
 * ⚠️ 为什么不能只 goto `/v4/login/cas`：
 * 前端看到 sessionStorage 里还有 token（哪怕只剩几分钟），会认为已登录，
 * 不走 CAS 换新 —— 结果拿回来的还是同一个旧 token，看着"续期成功"实则没续。
 *
 * 实测证据（修复前）：
 *   [02:04] token 已更新，剩余 39 分钟
 *   [02:19] token 已更新，剩余 24 分钟   ← 每次减 15 分钟，说明拿到的是同一个 token
 *   [02:34] token 已更新，剩余 9 分钟
 *
 * 正确做法：
 *   ① 先把页面里的 token 清掉
 *   ② 再访问 CAS 入口 → 前端发现没 token → 走完整登录 → 存回新 token
 *   ③ 校验新 token 的 exp 确实比旧的大，否则视为失败
 */
async function forceRelogin({ waitMs = 30000 } = {}) {
  await ensureBrowser();
  const oldExp = tokenExpAt;

  // ① 清掉页面里的 token
  try {
    await page.goto(`${BASE}/h5/`, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    await page.evaluate(() => {
      try {
        sessionStorage.removeItem('token');
      } catch {}
    });
    log('已清除页面内 token，准备重走 CAS');
  } catch (e) {
    log('清除 token 失败:', e.message);
  }

  // ② 访问 CAS 入口
  await page
    .goto(`${BASE}/v4/login/cas`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    .catch((e) => log('打开 CAS 入口失败:', e.message));

  // ③ 等新 token，并确认它比旧的更新
  const deadline = Date.now() + waitMs;
  let t = null;
  while (Date.now() < deadline) {
    await page.waitForTimeout(1000);
    t = await readTokenFromPage();
    if (!t) continue;
    const exp = readJwtExp(t);
    if (exp <= Date.now() + 60_000) continue;
    if (exp > oldExp + 60_000) {
      log(`✅ 换到新 token（exp ${fmtTime(oldExp)} → ${fmtTime(exp)}）`);
      return t;
    }
  }

  // 兜底：没换新但当前 token 还有效
  if (t && readJwtExp(t) > Date.now() + 60_000) {
    log('⚠️ 没换到更新的 token，但当前 token 仍有效，继续用');
    return t;
  }

  log('⚠️ 强制重新登录失败，当前页:', page.url());
  loginRequired = true;
  return null;
}

function fmtTime(ms) {
  if (!ms) return '无';
  return new Date(ms).toLocaleTimeString('zh-CN', { hour12: false });
}

/**
 * 快速重试：心跳失败后不等 30 分钟，先连续试几次。
 * 网络抖动、CAS 短暂抽风都可能在几十秒内恢复。
 */
async function quickRetry() {
  for (let i = 1; i <= 3; i++) {
    await new Promise((r) => setTimeout(r, i * 60 * 1000));
    if (token && tokenExpAt > Date.now() + RENEW_BEFORE_MS) {
      return;
    }
    log(`续期重试第 ${i} 次…`);
    try {
      await renewToken({ waitMs: 25000 });
    } catch (e) {
      log(`重试 ${i} 失败:`, e.message);
    }
  }
}

/**
 * 人工登录：开有头浏览器，**自动填账号密码 + 自动勾「记住我」**，
 * 人只需要输 4 位验证码并点登录。
 *
 * ⚠️ 重要设计：**不阻塞、不超时放弃**。
 * 之前的实现等了 10 分钟就返回失败，但浏览器窗口还开着 ——
 * 用户晚几分钟登录就会白登。现在改成：
 *   ① 打开窗口、填好表单后**立即返回**
 *   ② 后台每 2 秒轮询一次，什么时候登录成功什么时候接住
 *   ③ 成功后自动落盘 cookie，无需任何后续操作
 */
/**
 * 把侧车的浏览器窗口"露出来"，停在 CAS 登录页，**不自动填表、不自动提交**。
 *
 * <p>与 {@link manualLogin} 的区别：
 * <ul>
 *   <li>manualLogin：自动填账号密码 + 勾记住我，人只输验证码</li>
 *   <li>openBrowserForManual：什么都不填，完全交给人操作 —— 适合想自己走一遍登录流程的场景</li>
 * </ul>
 *
 * <p>两者的共同点：打开后用轮询盯着 sessionStorage，一旦检测到 token 就自动保存，不用再管。
 */
async function openBrowserForManual({ watchMs = 180000 } = {}) {
  if (context) {
    await context.close().catch(() => {});
    context = null;
    page = null;
  }
  loginRequired = true;
  humanLoginUntil = Date.now() + watchMs;   // 让心跳在人工操作期间闭嘴
  await ensureBrowser({ headed: true });

  log('已打开有头浏览器，停在 CAS 登录页（不自动填表）');
  log(`心跳将在 ${Math.round(watchMs / 1000)} 秒内跳过，不会打扰你填表`);
  await page
    .goto(`${BASE}/v4/login/cas`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    .catch((e) => log('打开登录页失败:', e.message));

  // 后台盯着：用户自己登录成功后，前端会把 token 写进 sessionStorage
  (async () => {
    const deadline = Date.now() + watchMs;
    while (Date.now() < deadline) {
      await page.waitForTimeout(2000).catch(() => {});
      let t;
      try {
        t = await readTokenFromPage();
      } catch {
        t = null;
      }
      if (t && readJwtExp(t) > Date.now() + 60_000) {
        applyToken(t);
        await persistCookies();
        loginRequired = false;
        humanLoginUntil = 0;   // 登录完成，恢复心跳
        const leftMin = Math.round((tokenExpAt - Date.now()) / 60000);
        log(`✅ 检测到你已登录，token 已保存（剩余 ${leftMin} 分钟），心跳已恢复`);
        return;
      }
    }
    humanLoginUntil = 0;
    log('⚠️ 保护期内没检测到登录。心跳已恢复；如果还要登，请再点一次「打开浏览器窗口」');
  })();

  return {
    ok: true,
    message: '浏览器窗口已打开，停在学校的登录页。请自己完成登录（账号、密码、验证码都由你操作）。登录成功后侧车会自动抓取 token。',
  };
}

/**
 * 从**当前页面**主动读一次 token 并保存。
 *
 * <p>用途：人在浏览器窗口里自己登录完成后，调这个接口把 token 收进来。
 * 与 {@link openBrowserForManual} 的区别：那个是"开窗口 + 后台盯 3 分钟"，
 * 这个是"窗口已经登好了，立刻去读一次" —— 不会跳页面、不会重登、不打扰人操作。
 *
 * <p>为什么必须手动触发：轮询有时间窗（默认 3 分钟），人如果登得慢，
 * 窗口过期后就没人再读了，token 白拿。
 */
async function pickupToken() {
  if (!page) {
    return { ok: false, message: '浏览器还没启动。请先点「打开浏览器窗口」' };
  }
  const t = await readTokenFromPage().catch(() => null);
  if (!t) {
    return {
      ok: false,
      message: '当前页面没找到 token。可能还没登录成功，或者页面停在别的地方。请在窗口里完成登录后再点一次。',
    };
  }
  const exp = readJwtExp(t);
  if (!exp || exp <= Date.now() + 60_000) {
    return { ok: false, message: '页面里的 token 已过期或快过期了，请重新登录' };
  }
  applyToken(t);
  await persistCookies();
  loginRequired = false;
  humanLoginUntil = 0;
  const leftMin = Math.round((tokenExpAt - Date.now()) / 60000);
  log(`✅ 手动抓取成功，token 已保存（剩余 ${leftMin} 分钟），心跳已恢复`);
  return { ok: true, message: `token 已保存，剩余 ${leftMin} 分钟`, tokenLeftMin: leftMin };
}

async function manualLogin({ blocking = false } = {}) {
  if (context) {
    await context.close().catch(() => {});
    context = null;
    page = null;
  }
  loginRequired = true;
  await ensureBrowser({ headed: true });

  log('打开 CAS 登录页…');
  await page.goto(`${BASE}/v4/login/cas`, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => {});
  await page.waitForTimeout(3000);

  // 如果已经免密登录了（TGT 还在），直接返回
  let t = await acquireToken({ headed: true, waitMs: 5000 });
  if (t) {
    applyToken(t);
    return t;
  }

  // 自动填账号密码 + 勾记住我，省掉人工输入
  try {
    await page.fill('input[name="username"]', ACCOUNT, { timeout: 8000 });
    await page.fill('input[name="password"]', PASSWORD, { timeout: 8000 });
    log('已自动填入账号密码');
  } catch (e) {
    log('自动填表失败（可能需要人工填）:', e.message);
  }
  try {
    const cb = page.locator('#rememberMe');
    if ((await cb.count()) > 0) {
      const checked = await cb.isChecked().catch(() => false);
      if (!checked) {
        await cb.check({ timeout: 5000 });
      }
      log('✅ 已勾选「记住我」（CASTGC 变长效，约 14 天免密）');
    } else {
      log('⚠️ 没找到「记住我」勾选框，有效期可能较短');
    }
  } catch (e) {
    log('勾选记住我失败:', e.message);
  }

  log('>>> 浏览器窗口已就绪：请输入 4 位验证码并点登录。后台会一直等你，不设超时。');

  // 后台轮询：只要进程活着就一直等
  const waitLogin = async () => {
    while (true) {
      await new Promise((r) => setTimeout(r, 2000));
      if (!context || !page) {
        log('浏览器已关闭，停止等待登录');
        return;
      }
      const tok = await readTokenFromPage();
      if (tok && readJwtExp(tok) > Date.now() + 60_000) {
        applyToken(tok);
        await persistCookies();
        log('✅ 人工登录完成，cookie 已落盘');
        // 登录成功后切回无头，避免留一个窗口在桌面上
        try {
          await context.close();
          context = null;
          page = null;
          log('已关闭有头窗口（后续用无头维持）');
        } catch {}
        return;
      }
    }
  };

  if (blocking) {
    // 兼容旧行为：调用方显式要等结果
    const deadline = Date.now() + LOGIN_WAIT_MS;
    while (Date.now() < deadline) {
      await page.waitForTimeout(1500);
      const tok = await readTokenFromPage();
      if (tok && readJwtExp(tok) > Date.now() + 60_000) {
        applyToken(tok);
        await persistCookies();
        return tok;
      }
    }
    return null;
  }

  // 默认：立即返回，后台继续等
  waitLogin().catch((e) => log('等待登录异常:', e.message));
  return { pending: true };
}

/** 统一更新 token 状态 */
function applyToken(t) {
  token = t;
  tokenExpAt = readJwtExp(t) || Date.now() + 100 * 60 * 1000;
  loginRequired = false;
  lastRenewAt = Date.now();
  const leftMin = Math.round((tokenExpAt - Date.now()) / 60000);
  log(`token 已更新，剩余 ${leftMin} 分钟`);
}

// ============ HTTP ============
// ============ 本地网页控制台 ============
// 为什么要这个：用 pwsh 敲 start-library.ps1 relogin 不方便，也不直观。
// 浏览器打开 http://127.0.0.1:8790/ 就能看状态、点按钮登录，全在浏览器里操作。
const CONTROL_PANEL_HTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>图书馆侧车 · 控制台</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
         max-width: 780px; margin: 40px auto; padding: 0 20px; color: #222; background: #fafafa; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  .sub { color: #888; font-size: 13px; margin-bottom: 24px; }
  .card { background: #fff; border: 1px solid #e5e5e5; border-radius: 10px; padding: 20px; margin-bottom: 16px; }
  .row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px dashed #eee; font-size: 14px; }
  .row:last-child { border-bottom: none; }
  .ok { color: #1a7f37; font-weight: 600; }
  .bad { color: #cf222e; font-weight: 600; }
  .warn { color: #9a6700; font-weight: 600; }
  button { font-size: 15px; padding: 11px 20px; border-radius: 8px; border: 1px solid #d0d7de;
           background: #f6f8fa; cursor: pointer; margin-right: 10px; margin-bottom: 8px; }
  button:hover { background: #eef1f4; }
  button.primary { background: #1f883d; color: #fff; border-color: #1f883d; }
  button.primary:hover { background: #1a7f37; }
  button:disabled { opacity: .5; cursor: not-allowed; }
  pre { background: #0d1117; color: #c9d1d9; padding: 14px; border-radius: 8px;
        font-size: 12.5px; line-height: 1.6; max-height: 280px; overflow: auto; margin: 0; }
  .hint { font-size: 13px; color: #666; line-height: 1.7; }
  .hint code { background: #f0f0f0; padding: 1px 5px; border-radius: 3px; font-size: 12.5px; }
</style>
</head>
<body>

<h1>图书馆侧车控制台</h1>
<div class="sub">127.0.0.1:8790 · 只监听本机</div>

<div class="card">
  <div class="row"><span>登录状态</span><span id="state">—</span></div>
  <div class="row"><span>token 剩余</span><span id="left">—</span></div>
  <div class="row"><span>cookie 文件</span><span id="ck">—</span></div>
  <div class="row"><span>上次续期</span><span id="renew">—</span></div>
</div>

<div class="card">
  <button class="primary" id="btnLogin" onclick="doRelogin()">🤖 自动填表登录（只需输验证码）</button>
  <button id="btnBrowser" onclick="doOpenBrowser()">🌐 打开浏览器窗口（完全自己操作）</button>
  <button id="btnPickup" onclick="doPickup()">📥 抓取 token</button>
  <button onclick="refresh()">🔄 刷新状态</button>
  <button onclick="clearLog()">清空日志</button>
</div>

<div class="card">
  <div class="hint">
    <b>两种登录方式，任选一个</b><br>
    <span style="color:#1f883d">🤖 自动填表</span>：侧车把账号密码填好、勾上「记住我」，<b>你只输 4 位验证码</b>（推荐）<br>
    <span style="color:#0969da">🌐 自己操作</span>：弹出干净的登录页，账号密码验证码全由你自己来<br>
    <br>
    两种方式登录成功后，token 自动保存，<b>100 分钟内自动续期</b>，不用再管。<br>
    如果自动没抓到，点一下「📥 抓取 token」手动收一次。<br>
    <br>
    <b>什么时候需要重新登录</b><br>
    · 重启电脑后　· 关掉侧车的浏览器后<br>
    · 状态显示 <span class="bad">未登录</span> 或调用图书馆功能报「服务未登录」时
  </div>
</div>

<div class="card">
  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
    <b style="font-size:14px">操作日志</b>
    <span style="font-size:12px;color:#888" id="logTime"></span>
  </div>
  <pre id="log">等待操作…</pre>
</div>

<script>
function log(msg) {
  var el = document.getElementById('log');
  var t = new Date().toLocaleTimeString('zh-CN');
  if (el.textContent === '等待操作…') el.textContent = '';
  el.textContent += '[' + t + '] ' + msg + '\\n';
  el.scrollTop = el.scrollHeight;
  document.getElementById('logTime').textContent = '最后更新 ' + t;
}
function clearLog() {
  document.getElementById('log').textContent = '等待操作…';
}

function setState(id, text, cls) {
  var el = document.getElementById(id);
  el.textContent = text;
  el.className = cls || '';
}

async function refresh() {
  try {
    var r = await fetch('/health', { cache: 'no-store' });
    var h = await r.json();
    if (h.hasToken) {
      setState('state', '已登录 ✅', 'ok');
      setState('left', h.tokenLeftMin + ' 分钟', h.tokenLeftMin > 10 ? 'ok' : 'warn');
    } else {
      setState('state', h.loginRequired ? '需要登录 🔐' : '未登录', 'bad');
      setState('left', '—', '');
    }
    setState('ck', h.cookieFileExists ? '有' : '无', h.cookieFileExists ? 'ok' : 'warn');
    setState('renew', h.lastRenewAt ? new Date(h.lastRenewAt).toLocaleTimeString('zh-CN') : '从未');
    return h;
  } catch (e) {
    setState('state', '连不上侧车', 'bad');
    return null;
  }
}

// 人在窗口里自己登完了，点这个按钮让侧车去页面读一次 token
async function doPickup() {
  log('正在从浏览器页面抓取 token…');
  try {
    var r = await fetch('/token/pickup', { method: 'POST' });
    var j = await r.json();
    log((j.ok ? '✅ ' : '⚠️ ') + (j.message || ''));
    refresh();
  } catch (e) {
    log('❌ 抓取失败: ' + e.message);
  }
}

async function doOpenBrowser() {
  var btn = document.getElementById('btnBrowser');
  btn.disabled = true;
  log('正在打开浏览器窗口…');
  try {
    var r = await fetch('/browser', { method: 'POST' });
    var j = await r.json();
    log(j.message || (j.ok ? '已打开' : '失败'));
    log('👉 切到弹出的窗口，自己完成登录。登录成功后这里的状态会自动变成「已登录」');
    waitForToken(btn);
  } catch (e) {
    log('❌ 请求失败: ' + e.message);
    btn.disabled = false;
  }
}

// 轮询等 token 出现（登录成功就提示）
function waitForToken(btn) {
  var tries = 0;
  var timer = setInterval(async function () {
    tries++;
    var h = await refresh();
    if (h && h.hasToken) {
      clearInterval(timer);
      log('✅ 登录成功！token 剩余 ' + h.tokenLeftMin + ' 分钟');
      btn.disabled = false;
    } else if (tries > 90) {
      clearInterval(timer);
      log('⚠️ 3 分钟内没检测到登录。窗口还开着的话继续登；关了可以再点一次。');
      btn.disabled = false;
    }
  }, 3000);
}

async function doRelogin() {
  var btn = document.getElementById('btnLogin');
  btn.disabled = true;
  log('正在唤起登录窗口…');
  try {
    var r = await fetch('/relogin', { method: 'POST' });
    var j = await r.json();
    log(j.message || (j.ok ? '已触发' : '失败'));
    log('👉 请切到弹出的 Chrome 窗口，输入验证码后点登录');
    // 之后每 3 秒看一次状态，登录成功就提示
    var tries = 0;
    var timer = setInterval(async function () {
      tries++;
      var h = await refresh();
      if (h && h.hasToken) {
        clearInterval(timer);
        log('✅ 登录成功！token 剩余 ' + h.tokenLeftMin + ' 分钟');
        btn.disabled = false;
      } else if (tries > 60) {
        clearInterval(timer);
        log('⚠️ 2 分钟内没检测到登录成功。如果窗口已经关了，可以再点一次。');
        btn.disabled = false;
      }
    }, 3000);
  } catch (e) {
    log('❌ 请求失败: ' + e.message);
    btn.disabled = false;
  }
}

refresh();
setInterval(refresh, 5000);
log('控制台已就绪');
</script>
</body>
</html>`;
function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function html(res, code, body) {
  res.writeHead(code, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);

  // ── 本地网页控制台：浏览器打开 http://127.0.0.1:8790/ 点按钮操作，不用敲命令 ──
  if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
    return html(res, 200, CONTROL_PANEL_HTML);
  }

  // 兼容浏览器直接访问（GET 也能触发重新登录；本地服务，CSRF 风险可接受）
  if (url.pathname === '/relogin' && (req.method === 'GET' || req.method === 'POST')) {
    log('收到 /relogin，唤起人工登录');
    try {
      const r = await manualLogin();
      if (r === true || typeof r === 'string') {
        if (req.method === 'GET') {
          return html(res, 200, '<meta charset="utf-8"><h3>✅ 登录成功</h3><p>窗口可以关掉了。</p><p><a href="/">返回控制台</a></p>');
        }
        return json(res, 200, { ok: true, message: '登录成功' });
      }
      if (req.method === 'GET') {
        return html(res, 200, '<meta charset="utf-8"><h3>🪟 浏览器窗口已打开</h3><p>账号密码已填、记住我已勾，请输入验证码并登录。</p><p>后台会一直等你，不用重复操作。</p><p><a href="/">返回控制台</a></p>');
      }
      return json(res, 200, {
        ok: true,
        pending: true,
        message: '浏览器窗口已打开（账号密码已填、记住我已勾），请输入验证码并登录。后台会一直等你，不用重复调用。',
      });
    } catch (e) {
      if (req.method === 'GET') {
        return html(res, 500, `<meta charset="utf-8"><h3>❌ 登录失败</h3><pre>${String(e.message).replace(/[<>&]/g, (m) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[m]))}</pre><p><a href="/">返回控制台</a></p>`);
      }
      return json(res, 500, { ok: false, message: e.message });
    }
  }

  // 打开浏览器窗口让人自己登录（不自动填表），与 /relogin 的区别见 openBrowserForManual 注释
  if (url.pathname === '/browser' && (req.method === 'GET' || req.method === 'POST')) {
    log('收到 /browser，露出浏览器窗口供人工登录');
    // 不阻塞：启动 Chromium 要 4~5 秒，等它完成再响应会让调用方（curl/浏览器）干等。
    // 改成「立即回话 + 后台继续启动」，失败了写日志。
    const started = openBrowserForManual().catch((e) => log('打开浏览器失败:', e.message));
    const msg = '正在打开浏览器窗口（约 5 秒），请稍等后切到那个窗口。登录完成后点「我已登录，抓取 token」。';
    if (req.method === 'GET') {
      return html(res, 200, '<meta charset="utf-8"><meta http-equiv="refresh" content="3; url=/"><h3>🪟 ' + msg + '</h3><p>3 秒后自动返回控制台…</p>');
    }
    return json(res, 202, { ok: true, pending: true, message: msg });
  }

  // 人在窗口里登好了 → 立刻去页面读一次 token（不跳页面、不重登）
  if (url.pathname === '/token/pickup' && (req.method === 'GET' || req.method === 'POST')) {
    log('收到 /token/pickup，从当前页面抓取 token');
    try {
      const r = await pickupToken();
      if (req.method === 'GET') {
        return html(res, r.ok ? 200 : 400,
          '<meta charset="utf-8"><h3>' + (r.ok ? '✅ ' : '⚠️ ') + String(r.message).replace(/[<>&]/g, (m) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[m])) +
          '</h3><p><a href="/">返回控制台</a></p>');
      }
      return json(res, r.ok ? 200 : 400, r);
    } catch (e) {
      log('抓取 token 异常:', e.message);
      return json(res, 500, { ok: false, message: e.message });
    }
  }

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(res, 200, {
      ok: true,
      hasToken: !!token,
      tokenLeftMin: token ? Math.max(0, Math.round((tokenExpAt - Date.now()) / 60000)) : 0,
      loginRequired,
      lastRenewAt,
      cookieFileExists: fs.existsSync(COOKIE_FILE),
    });
  }

  if (req.method === 'GET' && url.pathname === '/token') {
    try {
      return json(res, 200, await getToken());
    } catch (e) {
      log('取 token 异常:', e.message);
      return json(res, 500, { token: null, expiresAt: 0, loginRequired: true, message: e.message });
    }
  }

  if (req.method === 'GET' && url.pathname === '/cookies/export') {
    try {
      await ensureBrowser();
      const list = await context.cookies();
      return json(res, 200, { ok: true, count: list.length, cookies: list });
    } catch (e) {
      return json(res, 500, { ok: false, message: e.message });
    }
  }

  if (req.method === 'POST' && url.pathname === '/cookies/import') {
    try {
      const raw = await readBody(req);
      const parsed = JSON.parse(raw);
      const list = Array.isArray(parsed) ? parsed : parsed.cookies;
      if (!Array.isArray(list) || list.length === 0) {
        return json(res, 400, { ok: false, message: 'cookies 必须是非空数组' });
      }
      await ensureBrowser();
      await context.addCookies(list);
      fs.writeFileSync(COOKIE_FILE, JSON.stringify(list, null, 2), 'utf8');
      log(`已导入 ${list.length} 条 cookie 并落盘`);
      // 导入后立刻试着换 token
      await renewToken({ waitMs: 15000 });
      return json(res, 200, {
        ok: true,
        imported: list.length,
        hasToken: !!token,
        message: token ? '导入成功且已取到 token' : '导入成功，但暂时没取到 token（cookie 可能已过期）',
      });
    } catch (e) {
      return json(res, 500, { ok: false, message: e.message });
    }
  }

  json(res, 404, { ok: false, message: 'not found' });
});

server.listen(PORT, '127.0.0.1', () => {
  log(`图书馆登录守护进程已启动 http://127.0.0.1:${PORT}`);
  log(`  数据目录: ${path.relative(PROJECT_ROOT, DATA_DIR)}`);
  log(`  cookie 文件: ${path.relative(PROJECT_ROOT, COOKIE_FILE)}`);
  log(`  账号: ${ACCOUNT ? '已配置' : '未配置（需要 NY_ACCOUNT 或 ny-zhanghao）'}`);
});

// 启动即尝试拿 token（cookie 恢复成功的话就是免密的）
(async () => {
  try {
    await renewToken({ waitMs: 20000 });
    if (!token) {
      log('⚠️ 启动时没取到 token。需要登录时调用 POST /relogin，或 POST /cookies/import');
    }
  } catch (e) {
    log('启动初始化异常:', e.message);
  }
})();

setInterval(heartbeat, HEARTBEAT_MS);

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => {
    log(`收到 ${sig}，保存 cookie 后退出`);
    try {
      await persistCookies();
      await context?.close();
    } catch {}
    process.exit(0);
  });
}
