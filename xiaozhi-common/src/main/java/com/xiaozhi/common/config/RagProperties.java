package com.xiaozhi.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库配置（{@code xiaozhi.rag.*}）。
 *
 * <p><b>为什么有这个类</b>：早期实现直接在各处 {@code env.getProperty("QDRANT_HOST", ...)}，
 * 带来三个问题：
 * <ol>
 *   <li>yml 里查不到这些项，看不出系统有哪些可配置点（违反项目惯例——
 *       项目其余配置都是先在 yml 登记 {@code ${ENV:default}}，再由 {@code @ConfigurationProperties} 收口）</li>
 *   <li>默认值散落在代码里，其中最要命的是把 Windows 盘符路径写成默认值，
 *       在 Linux 上会被当成<b>单个目录名</b>创建（反斜杠在 Linux 是合法文件名字符）</li>
 *   <li>换机器/换系统要改代码</li>
 * </ol>
 *
 * <p><b>约定</b>：本类只承载配置，不含任何默认硬件地址。全部默认值都在
 * {@code application-common.yml} 的 {@code xiaozhi.rag} 段登记，环境变量只负责填充。
 */
@Data
@Component
@ConfigurationProperties(prefix = "xiaozhi.rag")
public class RagProperties {

    /** 总开关。关掉后不建 Qdrant 客户端、检索直接返回空，对话链路完全不受影响。 */
    private boolean enabled = true;

    private Qdrant qdrant = new Qdrant();

    private Retrieval retrieval = new Retrieval();

    private Knowledge knowledge = new Knowledge();

    /** 向量库连接参数。 */
    @Data
    public static class Qdrant {

        /**
         * Qdrant 主机名。
         *
         * <p>默认 {@code localhost}：Qdrant 用 Docker 跟本进程跑在同一台机器是最常见的部署方式。
         * 装在别的机器上时必须显式配置，否则启动后会连着连不上并报可读错误。
         */
        private String host = "localhost";

        /** gRPC 端口。6333 是 HTTP 与控制台，Spring AI 走 gRPC 所以必须用 6334。 */
        private int port = 6334;

        /** 集合名。换集合相当于换一套独立的向量空间，改它之前先确认旧数据要不要保留。 */
        private String collection = "xiaozhi_knowledge";
    }

    /** 检索参数。 */
    @Data
    public static class Retrieval {

        /** 每次检索取多少条送进 prompt。 */
        private int topK = 5;

        /** 相似度阈值，低于它的命中会被丢弃。 */
        private double similarityThreshold = 0.35;
    }

    /** 知识库文件与调试相关。 */
    @Data
    public static class Knowledge {

        /**
         * 原文落盘目录。
         *
         * <p>三种写法（由 {@code RuntimePathConfig.resolveStorageKey} 统一解析）：
         * <ul>
         *   <li>留空 → {@code ${xiaozhi.runtime.data-dir}/knowledge}（推荐，一处配置管全部文件）</li>
         *   <li>相对路径 → 相对 data-dir</li>
         *   <li>绝对路径 → 原样使用</li>
         * </ul>
         */
        private String uploadDir = "";

        /** 入库追踪日志开关（打印解析预览、切块原因、每块字数、payload 示例）。平时关。 */
        private boolean debugTrace = false;
    }
}