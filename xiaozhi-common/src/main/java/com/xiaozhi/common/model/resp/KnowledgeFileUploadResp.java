package com.xiaozhi.common.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 待入库文件上传结果（web 出参）。
 *
 * <p>两步式上传的第一步产物：拿到 {@code fileId} 后才去调
 * {@code POST /api/knowledge/documents} 完成入库。
 */
@Data
@Schema(description = "待入库文件")
public class KnowledgeFileUploadResp {

    @Schema(description = "文件ID（内容哈希），入库时回传")
    private String fileId;

    @Schema(description = "安全化后的文件名")
    private String fileName;

    @Schema(description = "文件大小（字节）")
    private Long size;

    @Schema(description = "扩展名（txt / md / markdown）")
    private String extension;

    @Schema(description = "下一步提示")
    private String next;
}