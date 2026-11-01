import { http } from './request'
import api from './api'
import type {
  KnowledgeCreatePayload,
  KnowledgeCreateResult,
  KnowledgeDetail,
  KnowledgeDoc,
  KnowledgeQueryParams,
  KnowledgeStats,
  KnowledgeUploadResult,
} from '@/types/knowledge'

/**
 * 第一步：把文件传到后端"待入库区"，只落盘、不解析。
 *
 * 与第二步拆开的意义：上传本身无副作用（只需登录），
 * 真正的权限校验发生在 {@link createKnowledgeDoc}；入库失败也不必重新上传文件。
 */
export function uploadKnowledgeFile(file: File) {
  const form = new FormData()
  form.append('file', file)
  return http.postMultipart<KnowledgeUploadResult>(api.knowledge.files, form)
}

/**
 * 第二步：用 fileId 创建文档（后端解析 → 切块 → 向量化 → 回写账本）。
 *
 * `scope=shared` 只有管理员能设，普通用户传了会被后端 403 拒绝。
 */
export function createKnowledgeDoc(payload: KnowledgeCreatePayload) {
  return http.post<KnowledgeCreateResult>(api.knowledge.documents, payload)
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
