package com.xiaozhi.ai.rag;

import java.util.List;
import java.util.Locale;

/**
 * 知识库接受的文档格式 —— <b>全项目唯一的事实来源</b>。
 *
 * <h3>为什么单独抽一个类</h3>
 * 格式白名单有两处要知道：<b>上传校验</b>（server 层）和<b>文本提取</b>（本模块的
 * {@link DocumentIngestService} 用 Tika 解析）。以前清单写在上传那侧，解析这侧另有一套
 * 判断，加格式时很容易只改一边 —— 表现是"上传通过了，解析出来却是空文本"，
 * 而且要到用户投诉才发现。放在 ai 层是因为"能不能解析"这件事本来就属于解析能力，
 * 不属于上传流程。
 *
 * <h3>加新格式的正确做法（给后续 PDF / Word / 图片铺的路）</h3>
 * <ol>
 *   <li>拿一份<b>真实中文样本</b>跑一遍解析与切块，确认：中文不乱码、标题层级没丢、
 *       表格没变成一串乱序字符</li>
 *   <li>验证通过后，把扩展名加进 {@link #VERIFIED}，并在注释里写明验证日期 + 样本出处</li>
 *   <li><b>上传侧一行都不用改</b>，白名单自动放开</li>
 * </ol>
 *
 * <h3>为什么不直接放开成"Tika 支持的格式全都收"</h3>
 * Tika 认得出 PDF/Word，但中文 PDF 常见编码错乱，扫描件根本抽不出字。抽不出来时
 * 入库会"成功但 0 块"，或者抛空文本异常 —— 用户看到的是莫名其妙的失败。
 * 所以只放**已用真实样本验证过**的格式，宁可给一句明确的不支持提示。
 */
public final class DocumentFormats {

    /**
     * 已用真实中文样本验证过、可以开放的扩展名（一律小写）。
     *
     * <ul>
     *   <li>{@code txt} / {@code md} / {@code markdown} —— 2026-10 起在用，
     *       库内 38 篇真实校园资料全部由这三种格式解析</li>
     * </ul>
     *
     * <p><b>PDF / Word 为什么还不在这里</b>：Tika 能抽文本，但中文提取质量没验证过。
     * 加进来之前必须先跑样本并把结论写进上面的注释，不能凭"Tika 支持"就放开。
     */
    public static final List<String> VERIFIED = List.of("txt", "md", "markdown");

    /** 单文件大小上限 20MB。上传校验与文档说明共用这一个值，避免两处漂移。 */
    public static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;

    private DocumentFormats() {
    }

    /** 取小写扩展名；没有扩展名（或以点结尾）返回空串。 */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    /** 这个文件名能不能入库。 */
    public static boolean isSupported(String fileName) {
        return VERIFIED.contains(extensionOf(fileName));
    }

    /**
     * 校验"这份文件能不能入库"。不合法直接抛异常，消息是给用户看的。
     *
     * <p>顺序刻意如此：先文件名、再格式、最后大小 —— 用户改错最省事的那项先报。
     *
     * @param byteLength 内容字节数（调用方已经从 MultipartFile 读出来了，不再重复取）
     * @throws IllegalArgumentException 缺文件名 / 格式不支持 / 内容为空 / 超过 20MB
     */
    public static void assertAcceptable(String fileName, long byteLength) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("缺少文件名");
        }
        String extension = extensionOf(fileName);
        if (!VERIFIED.contains(extension)) {
            throw new IllegalArgumentException("不支持的文件类型：." + extension
                    + "，目前只支持 " + String.join(" / ", VERIFIED));
        }
        if (byteLength <= 0) {
            throw new IllegalArgumentException("文件为空");
        }
        if (byteLength > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("文件过大：" + (byteLength / 1024) + " KB，上限 "
                    + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB");
        }
    }
}