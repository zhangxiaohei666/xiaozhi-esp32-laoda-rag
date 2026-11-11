/**
 * 知识库（RAG）相关类型。
 *
 * 与后端 `KnowledgeController` / `sys_knowledge_doc` 一一对应；
 * 分块表只存目录（heading / 字数），**不含正文** —— 正文在向量库与磁盘原文里。
 */

/** 可见性：private 仅本人可见，shared 所有人可见（只有管理员能设 shared） */
export type KnowledgeScope = 'private' | 'shared'

/** 处理状态 */
export type KnowledgeStatus = 'processing' | 'success' | 'failed'

/** 文档（一份文件一行） */
export interface KnowledgeDoc {
  /** 账本自增主键（身份，永不变） */
  id: number
  /** 内容指纹（去重标识，跨环境同步用它匹配） */
  contentHash?: string
  title: string
  category?: string
  scope: KnowledgeScope
  sourceType: string
  fileName?: string
  filePath?: string
  url?: string
  publishDate?: string
  charCount: number
  chunkCount: number
  status: KnowledgeStatus
  errorMsg?: string
  userId: number
  createTime?: string
  updateTime?: string
}

/** 分块目录（不含正文） */
export interface KnowledgeChunk {
  /** 分块自增主键 */
  id: number
  /** 所属文档 id */
  docId: number
  chunkIndex: number
  heading?: string
  charCount: number
}

/**
 * 上传并入库的入参（**不含 userId**，归属由后端从会话取）。
 *
 * 文件本体和元信息一起走同一个 multipart 请求 —— 不再有"先上传拿 fileId"那一步。
 */
export interface KnowledgeCreatePayload {
  file: File
  category?: string
  scope?: KnowledgeScope
  url?: string
  /** yyyy-MM-dd */
  publishDate?: string
}

/** 创建成功后返回 */
export interface KnowledgeCreateResult {
  /** 账本自增主键 */
  id: number
  title: string
  charCount: number
  chunkCount: number
  scope: KnowledgeScope
  status: KnowledgeStatus
}

/** 统计 */
export interface KnowledgeStats {
  docCount: number
  successCount: number
  failedCount: number
  processingCount: number
  chunkCount: number
  charCount: number
}

/** 列表查询参数 */
export interface KnowledgeQueryParams {
  pageNo?: number
  pageSize?: number
  category?: string
  status?: KnowledgeStatus | ''
  scope?: KnowledgeScope | ''
  keyword?: string
}

/** 详情返回（账本 + 分块目录，不含正文） */
export interface KnowledgeDetail {
  doc: KnowledgeDoc
  chunks: KnowledgeChunk[]
}
