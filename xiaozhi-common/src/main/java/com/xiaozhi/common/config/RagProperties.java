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

    private Rerank rerank = new Rerank();

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

        /** 相似度阈值，低于它的命中会被丢弃。实测：无关问题 0.39~0.49，正常问题 ≥0.65，取 0.50 分界。 */
        private double similarityThreshold = 0.50;

        /**
         * 同一篇文档最多贡献几条命中。
         *
         * <p>实测问题：一篇长文档的多个块在向量空间里彼此很近，问「图书馆使用规则」时
         * top5 全部来自同一篇，其他文档拿不到参考机会。
         *
         * <p>取值含义：
         * <ul>
         *   <li>1 → 严格去重，最大化多样性</li>
         *   <li>2 → 默认，兼顾多样性与此篇内信息完整</li>
         *   <li>≥ topK → 等于不去重（旧行为）</li>
         * </ul>
         */
        private int maxPerDoc = 2;
    }

    /**
     * 重排（UP-17 两段式检索的第二段：召回调宽 → gte-rerank-v2 精排 → 取前 N 条）。
     *
     * <p><b>红线</b>：拒答判定永远用 {@link Retrieval#similarityThreshold}（0.50），
     * 本段的 {@code recallThreshold} 只决定「过拒答门之后取哪些候选送重排」，
     * 绝不单独构成放行依据 —— 把 0.35 当拒答线会让 5 条垃圾问题全部穿透（实测分数 0.38~0.49）。
     */
    @Data
    public static class Rerank {

        /**
         * 总开关，默认关。
         *
         * <p>为什么默认 false：rerank 是检索链路里唯一引入<b>对话路径上外呼 API</b> 的环节，
         * 以关停状态合入 = 代码路径与历史基线逐位一致（零回归兜底），
         * 评估数据说话后再在运行侧 {@code RAG_RERANK_ENABLED=true} 打开做 A/B。
         */
        private boolean enabled = false;

        /**
         * 重排模型，走 DashScope 原生 rerank 端点（SDK {@code TextReRank}）。
         *
         * <p>2026-11-17 冒烟：{@code qwen3-rerank} 可用但在 E06 上把期望文档压出 top5，
         * 不优于默认 —— 留作 env 备选，不设为默认。
         */
        private String model = "gte-rerank-v2";

        /**
         * 召回条数（第一段）。计划口径 top20；受 {@code RetrievalService} 的 MAX_FETCH_K=50 封顶。
         *
         * <p>2026-11-17 实测：20 条 × ~600 字 chunk ≈ 3~4k token，远低于端点单请求 30k 上限。
         */
        private int recallK = 20;

        /**
         * 召回阈值（第一段），只在重排开启时使用 —— 目的是多给重排一些候选。
         * <b>不是拒答线</b>，拒答线见 {@link Retrieval#similarityThreshold}。
         */
        private double recallThreshold = 0.35;

        /**
         * 重排后注入的条数（计划口径 top3~5，默认与 {@code retrieval.topK} 对齐）。
         *
         * <p>与 topK 分开配：降到 3 做 A/B 时不用动基线段的 fetchK 公式。
         */
        private int topK = 5;

        /**
         * 单次重排调用超时，超时即放弃重排、退回基线路径（绝不让 DashScope 拖住对话）。
         *
         * <p>2026-11-17 实测端点时延 260~420ms（20 条候选），2s 是给足余量的硬上限。
         */
        private int timeoutMs = 2000;
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