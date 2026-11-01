import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * connectToServer 的成败判定与 stopAutoReconnect 的状态更新，都读 connectionStatus 这个字段。
 * 这组用例把「哪些状态算连接失败、哪些算重连中」钉死，改动状态机时不能悄悄漂移。
 */

interface CloseInfo {
  wasClean: boolean
  code: number
  reason: string
}

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3

  readyState = FakeWebSocket.CONNECTING
  binaryType = 'blob'
  onopen: (() => void) | null = null
  onclose: ((event: CloseInfo) => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((event: unknown) => void) | null = null
  send = vi.fn()
  close = vi.fn(() => {
    this.readyState = FakeWebSocket.CLOSED
  })

  constructor(public url: string) {
    FakeWebSocket.instances.push(this)
  }

  static get last() {
    return FakeWebSocket.instances[FakeWebSocket.instances.length - 1]!
  }

  emitOpen() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.()
  }

  emitError() {
    this.onerror?.()
  }

  emitClose(wasClean: boolean) {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.({ wasClean, code: wasClean ? 1000 : 1006, reason: '' })
  }
}

const config = { url: 'ws://localhost:8092/ws', deviceId: 'dev-1' }

async function loadModule() {
  vi.resetModules()
  return import('../websocket')
}

beforeEach(() => {
  FakeWebSocket.instances = []
  vi.useFakeTimers()
  vi.stubGlobal('WebSocket', FakeWebSocket)
  vi.spyOn(console, 'log').mockImplementation(() => {})
  vi.spyOn(console, 'warn').mockImplementation(() => {})
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.spyOn(console, 'debug').mockImplementation(() => {})
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('connectToServer 的成败判定', () => {
  it('连接建立后返回 true 并置为已连接', async () => {
    const ws = await loadModule()
    const pending = ws.connectToServer(config)

    FakeWebSocket.last.emitOpen()
    await vi.advanceTimersByTimeAsync(200)

    await expect(pending).resolves.toBe(true)
    expect(ws.getConnectionStatus().isConnected).toBe(true)
  })

  // onerror 之后必须尽快判失败，否则调用方要一直干等到 5 秒超时
  it('连接出错时不等超时就返回 false', async () => {
    const ws = await loadModule()
    const pending = ws.connectToServer(config)

    FakeWebSocket.last.emitError()
    await vi.advanceTimersByTimeAsync(300)

    await expect(pending).resolves.toBe(false)
    expect(ws.getConnectionStatus().isConnected).toBe(false)
  })

  it('一直没有响应时按超时返回 false', async () => {
    const ws = await loadModule()
    const pending = ws.connectToServer(config)

    await vi.advanceTimersByTimeAsync(5200)

    await expect(pending).resolves.toBe(false)
  })

  // 意外断开会进入重连流程，这一路不能被当成「连接失败」立刻判死，
  // 否则重连还没开始调用方就收到 false 了
  it('意外断开进入重连排队，不会立刻判成失败', async () => {
    const ws = await loadModule()
    let outcome: boolean | 'pending' = 'pending'
    const pending = ws.connectToServer(config).then((result) => {
      outcome = result
      return result
    })

    FakeWebSocket.last.emitClose(false)
    await vi.advanceTimersByTimeAsync(300)

    // 断开后进入 2 秒重连倒计时。这期间不能已经收口——
    // 一旦提前判死，调用方还没等到重连就拿到 false 了
    expect(outcome).toBe('pending')
    expect(ws.getConnectionStatus().connectionStatus).toBe('reconnecting')
    // 倒计时秒数要一并交出去，视图层的「N 秒后重连」靠它插值
    expect(ws.getConnectionStatus().reconnectSeconds).toBe(2)

    await vi.advanceTimersByTimeAsync(5200)
    await expect(pending).resolves.toBe(false)
  })
})

describe('stopAutoReconnect 的状态更新', () => {
  it('正在重连时停掉会切到已停止重连', async () => {
    const ws = await loadModule()
    const pending = ws.connectToServer(config)
    FakeWebSocket.last.emitClose(false)
    await vi.advanceTimersByTimeAsync(50)

    // 断开后进入重连排队，此刻的状态属于「重连中」
    expect(ws.stopAutoReconnect()).toBe(true)
    expect(ws.getConnectionStatus().connectionStatus).toBe('reconnectStopped')

    await vi.advanceTimersByTimeAsync(5200)
    await pending
  })

  it('没有在重连时停掉不会改写当前状态', async () => {
    const ws = await loadModule()
    const pending = ws.connectToServer(config)
    FakeWebSocket.last.emitOpen()
    await vi.advanceTimersByTimeAsync(200)
    await pending

    const before = ws.getConnectionStatus().connectionStatus
    ws.stopAutoReconnect()

    expect(ws.getConnectionStatus().connectionStatus).toBe(before)
    expect(before).not.toBe('reconnectStopped')
  })
})

describe('状态码不掺文案', () => {
  // 状态既显示给用户也参与控制流判定。一旦有人把中文写回这个字段，
  // TERMINAL_FAILURE_KEYS / RECONNECT_KEYS 的集合判定就会静默失配
  it('连接过程中的状态取值始终是英文状态码', async () => {
    const ws = await loadModule()
    const seen = new Set<string>()
    ws.registerStatusChangeCallback((status) => {
      seen.add(status.connectionStatus)
    })

    const pending = ws.connectToServer(config)
    FakeWebSocket.last.emitClose(false)
    await vi.advanceTimersByTimeAsync(5200)
    await pending

    expect(seen.size).toBeGreaterThan(0)
    for (const key of seen) {
      expect(key).toMatch(/^[a-zA-Z]+$/)
    }
  })
})

describe('服务端主动结束会话', () => {
  // 服务端结束会话有两个来源：用户说"拜拜"触发 exit_session，或静默超时
  // （InactiveSessionChecker 默认 60 秒）发完告别语后关闭。
  // 两种情况都必须让界面知道"是会话结束了"，而不是让状态悄悄变一下。
  //
  // ⚠️ 但【绝对不能】自动重连：立刻重连会建出新会话，静默超时一到又被关，
  // 形成「连上 → 60 秒后告别 → 关闭 → 重连」的死循环（2026-10-25 实测踩过，
  // 聊天窗被"会话已结束"刷屏）。用户不会因此卡住——发消息前会走 ensureConnection()。
  it('干净关闭且非本端发起时，通知会话结束但【不自动重连】', async () => {
    const ws = await loadModule()
    const statuses: string[] = []
    let endedCalls = 0
    ws.registerStatusChangeCallback((status) => statuses.push(status.connectionStatus))
    ws.registerSessionEndedHandler(() => {
      endedCalls++
    })

    const pending = ws.connectToServer(config)
    FakeWebSocket.last.emitOpen()
    await vi.advanceTimersByTimeAsync(200)
    await expect(pending).resolves.toBe(true)

    const connectionsBefore = FakeWebSocket.instances.length
    statuses.length = 0
    FakeWebSocket.last.emitClose(true)
    // 给足重连窗口：如果这条路上还留着 scheduleReconnect，这里就会建出新连接
    await vi.advanceTimersByTimeAsync(30000)

    expect(endedCalls).toBe(1)
    expect(statuses).toContain('sessionEnded')
    expect(statuses).not.toContain('reconnecting')
    expect(ws.getConnectionStatus().connectionStatus).toBe('sessionEnded')
    // 最硬的证据：一条新连接都不许建
    expect(FakeWebSocket.instances.length).toBe(connectionsBefore)
  })

  // 本端断开（用户点断开、切角色）不该提示"会话已结束"，也不该自动重连
  it('本端主动断开时只置为 closed，不通知会话结束、不重连', async () => {
    const ws = await loadModule()
    let endedCalls = 0
    ws.registerSessionEndedHandler(() => {
      endedCalls++
    })

    const pending = ws.connectToServer(config)
    FakeWebSocket.last.emitOpen()
    await vi.advanceTimersByTimeAsync(200)
    await expect(pending).resolves.toBe(true)

    ws.disconnectFromServer()
    FakeWebSocket.last.emitClose(true)
    await vi.advanceTimersByTimeAsync(10000)

    expect(endedCalls).toBe(0)
    expect(ws.getConnectionStatus().connectionStatus).toBe('closed')
  })

  // 退订要真的生效，否则组件反复挂载会攒下一串回调（每个都会弹一次提示）
  it('退订后不再收到会话结束通知', async () => {
    const ws = await loadModule()
    let endedCalls = 0
    const handler = () => {
      endedCalls++
    }
    ws.registerSessionEndedHandler(handler)
    ws.unregisterSessionEndedHandler(handler)

    const pending = ws.connectToServer(config)
    FakeWebSocket.last.emitOpen()
    await vi.advanceTimersByTimeAsync(200)
    await expect(pending).resolves.toBe(true)

    FakeWebSocket.last.emitClose(true)
    await vi.advanceTimersByTimeAsync(10)

    expect(endedCalls).toBe(0)
  })
})
