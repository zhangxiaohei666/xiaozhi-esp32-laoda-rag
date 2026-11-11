import { http } from './request'
import api from './api'
import type {
  KnowledgeCreatePayload,
  KnowledgeCreateResult,
  KnowledgeDetail,
  KnowledgeDoc,
  KnowledgeQueryParams,
  KnowledgeStats,
} from '@/types/knowledge'

/**
 * 上传并入库：文件本体与元信息走同一个 multipart 请求。
 *
 * 以前拆成"先传文件拿 fileId、再提交入库"两步，但实测那三步好处一个都没兑现
 * （上传照样写磁盘且只校验登录、复用没落地、失败后原文件已被搬走必须重传），
 * 反而多出一套暂存目录与孤儿文件。合并后后端在一次请求内完成校验 → 解析 → 入库。
 *
 * `scope=shared` 只有管理员能设，普通用户传了会被后端 403 拒绝。
 */
export function createKnowledgeDoc(payload: KnowledgeCreatePayload) {
  const form = new FormData()
  form.append('file', payload.file)
  // 可选字段：有值才带，避免后端收到一堆空串（空串会和"没传"语义不同）
  if (payload.category) {
    form.append('category', payload.category)
  }
  if (payload.scope) {
    form.append('scope', payload.scope)
  }
  if (payload.url) {
    form.append('url', payload.url)
  }
  if (payload.publishDate) {
    form.append('publishDate', payload.publishDate)
  }
  return http.postMultipart<KnowledgeCreateResult>(api.knowledge.documents, form)
}

/** 文档列表（非管理员只返回「自己的 + 共享的」） */
export function queryKnowledgeDocs(params: Partial<KnowledgeQueryParams>) {
  return http.getPage<KnowledgeDoc>(api.knowledge.documents, params)
}

/** 文档详情（含分块目录，不含分块正文） */
export function queryKnowledgeDetail(id: number) {
  return http.get<KnowledgeDetail>(`${api.knowledge.documents}/${id}`)
}

/** 删除文档：后端会同步删向量、磁盘原文与账本 */
export function deleteKnowledgeDoc(id: number) {
  return http.delete<void>(`${api.knowledge.documents}/${id}`)
}

/** 统计 */
export function queryKnowledgeStats() {
  return http.get<KnowledgeStats>(api.knowledge.stats)
}

/** 已出现过的归类，供筛选下拉 */
export function queryKnowledgeCategories() {
  return http.get<string[]>(api.knowledge.categories)
}
