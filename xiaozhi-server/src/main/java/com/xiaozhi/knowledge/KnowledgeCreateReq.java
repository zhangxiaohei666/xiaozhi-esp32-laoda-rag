package com.xiaozhi.knowledge;

import lombok.Data;

/**
 * 创建文档（两步式上传的第二步）请求。
 *
 * <p>注意：**没有 userId 字段**。归属人一律从登录会话取（{@code StpUtil.getLoginIdAsInt()}），
 * 前端传什么都不认，否则可以伪造归属、把资料挂到别人名下。
 */
@Data
public class KnowledgeCreateReq {

    /** 第一步 POST /api/knowledge/files 返回的 fileId */
    private String fileId;

    /** 归类，可空；空则用文件头部解析出的「来源栏目」，再空则「未分类」 */
    private String category;

    /** 可见性：private（默认）/ shared（仅管理员可设） */
    private String scope;

    /** 原文链接，可空 */
    private String url;

    /** 发布日期 yyyy-MM-dd，可空；空则用文件头部解析出的「发布日期」 */
    private String publishDate;

    /**
     * 来源：upload（用户上传，默认）/ scrape（网页抓取）/ import（批量导入）。
     *
     * <p>不传就一律记成 upload —— 以前就是这么写死的，但那样抓取脚本和批量导入
     * 的资料也会被标成"用户上传"，统计口径就不准了。抓取脚本传 scrape 即可。
     */
    private String sourceType;
}