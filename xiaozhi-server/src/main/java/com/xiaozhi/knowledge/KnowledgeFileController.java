package com.xiaozhi.knowledge;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.xiaozhi.common.model.resp.KnowledgeFileUploadResp;
import com.xiaozhi.common.web.ApiResponse;
import com.xiaozhi.knowledge.service.KnowledgePendingFiles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库 —— 第一步：**纯文件传输**。
 *
 * <p>两步式上传的职责切分：
 * <pre>
 * ① POST /api/knowledge/files     只收文件、只落盘，返回 fileId      ← 本类
 * ② POST /api/knowledge/documents 校验权限/归属/scope → 解析→向量化→回写   ← KnowledgeController
 * </pre>
 *
 * <p>为什么拆开：
 * <ul>
 *   <li><b>鉴权与文件传输解耦</b>：上传文件本身无副作用，只要登录即可；
 *       真正有影响的是"入库"（消耗 embedding 额度、影响检索），权限判断集中在第二步和
 *       {@link com.xiaozhi.knowledge.service.KnowledgeAccessGuard}。</li>
 *   <li><b>可复用</b>：将来头像、聊天图片可以走同一条文件通路。</li>
 *   <li><b>失败不用重传</b>：文件已在磁盘，入库失败只需重提第二步。</li>
 * </ul>
 *
 * <p>本类**只要求登录**（{@code @SaCheckLogin}），不检查知识库权限码 —— 这是刻意的。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@Tag(name = "知识库-文件上传", description = "两步式上传的第一步：只落盘，不解析不入库")
public class KnowledgeFileController {

    @Resource
    private KnowledgePendingFiles pendingFileStore;

    /** 只用来取当前登录人 id —— 待入库文件要按归属人分目录，避免互相把对方的文件移走 */
    @Resource
    private com.xiaozhi.knowledge.service.KnowledgeAccessGuard knowledgeAccessGuard;

    /**
     * 上传文件到待入库区。
     *
     * @param file 文档文件（必填）。只支持 txt / md / markdown，单个不超过 20MB
     * @return fileId、文件名、大小、扩展名
     */
    @PostMapping("/files")
    @ResponseBody
    @SaCheckLogin
    @Operation(summary = "上传文件到待入库区",
            description = "只落盘，不解析不入库。返回的 fileId 交给 POST /api/knowledge/documents 完成入库")
    public ApiResponse<KnowledgeFileUploadResp> uploadFile(
            @Parameter(description = "文档文件（txt / md / markdown，≤ 20MB）")
            @RequestParam("file") MultipartFile file

    ) throws Exception {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String fileName = file.getOriginalFilename();
        // 扩展名与大小校验在 store 里统一做，避免本类与入库路径两处规则漂移
        KnowledgePendingFiles.PendingFile saved =
                pendingFileStore.save(fileName, file.getBytes(), knowledgeAccessGuard.currentUserId());

        KnowledgeFileUploadResp resp = new KnowledgeFileUploadResp();
        resp.setFileId(saved.fileId());
        resp.setFileName(saved.fileName());
        resp.setSize(saved.size());
        resp.setExtension(saved.extension());
        resp.setNext("POST /api/knowledge/documents 传 fileId 完成入库");
        return ApiResponse.success(resp);
    }
}