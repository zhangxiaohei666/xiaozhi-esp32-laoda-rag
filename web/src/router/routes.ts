/**
 * 路由路径常量
 * 集中管理所有路由路径，避免硬编码字符串分散在各处
 */
export const ROUTES = {
  LOGIN: '/login',
  REGISTER: '/register',
  FORGET: '/forget',
  DASHBOARD: '/dashboard',
  DEVICE: '/device',
  ROLE: '/role',
  TEMPLATE: '/template',
  CHAT: '/chat',
  CONFIG_MODEL: '/config/model',
  MEMORY_CHAT: '/memory/chat',
  AUTH_ROLE: '/auth-role',
  SETTING_ACCOUNT: '/setting/account',
  KNOWLEDGE: '/knowledge',
  ERROR_403: '/403',
  ERROR_404: '/404',
} as const

/**
 * 登录后落地页的候选表，按优先级从高到低排列。
 *
 * 这里把「路径 → 该页要求的权限码」写在一起，去掉"哪种角色该进哪页"的硬假设：
 * 角色是数据（sys_auth_role_permission），随时可能在后台被改，代码里写死
 * 「普通用户进 /device」会在角色没有 device 权限时把用户直接送进 403。
 */
const LANDING_CANDIDATES: readonly { path: string; permission: string }[] = [
  { path: ROUTES.DASHBOARD, permission: 'system:dashboard' },
  { path: ROUTES.CHAT, permission: 'system:chat' },
  { path: ROUTES.DEVICE, permission: 'system:device' },
  { path: ROUTES.CONFIG_MODEL, permission: 'system:config' },
  { path: ROUTES.SETTING_ACCOUNT, permission: 'system:setting' },
]

/**
 * 登录后的默认落地路由。
 *
 * - 传了 `hasPermission`：按候选表取**第一个当前账号确实有权限**的页面；
 *   一个都没有则退到 403（403 页不挂任何权限，是唯一保证可达的兜底）。
 * - 没传 `hasPermission`：退化为旧行为（管理员进仪表盘、其余进设备页），
 *   只为兼容尚未改造的调用方，新代码不要再依赖这条分支。
 *
 * @param isAdmin       是否管理员（仅在没有 hasPermission 时使用）
 * @param hasPermission 权限判断函数，通常传 `userStore.hasPermission`
 */
export function defaultRouteFor(
  isAdmin: boolean,
  hasPermission?: (permissionKey: string) => boolean,
): string {
  if (!hasPermission) {
    return isAdmin ? ROUTES.DASHBOARD : ROUTES.DEVICE
  }
  const hit = LANDING_CANDIDATES.find((candidate) => hasPermission(candidate.permission))
  return hit ? hit.path : ROUTES.ERROR_403
}
