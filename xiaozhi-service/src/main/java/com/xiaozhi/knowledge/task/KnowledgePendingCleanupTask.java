package com.xiaozhi.knowledge.task;

import com.xiaozhi.common.config.RagProperties;
import com.xiaozhi.knowledge.service.KnowledgePendingFiles;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 待入库文件清理任务：每天凌晨删掉 {@code .pending/} 下过期没人管的文件。
 *
 * <h3>为什么需要这个任务</h3>
 * 两步式上传的第一步（{@code POST /api/knowledge/files}）只把文件落进
 * {@code .pending/{ownerId}/}，等第二步（{@code POST /api/knowledge/documents}）才解析入库。
 * 用户若没继续第二步 —— 关页面、断网、解析报错 —— 文件就<b>没有任何出口</b>：
 * <ul>
 *   <li>{@link KnowledgePendingFiles#promote} 只在入库成功时把它搬去正式目录</li>
 *   <li>{@link KnowledgePendingFiles#discard} 只在明确失败时删掉它</li>
 * </ul>
 * 而第一步只要求登录（{@code @SaCheckLogin}），<b>不校验</b>
 * {@code system:knowledge:api:upload} 权限码 —— 这是第一版刻意的设计。
 * 两者叠加的后果：任何登录用户都能往磁盘写最多 20MB 的文件，且永不清理。
 *
 * <h3>为什么不用分布式锁</h3>
 * {@code com.xiaozhi.task} 里那几个任务（{@code AudioCleanupTask} 等）必须持 Redisson 锁，
 * 是因为那个包被 {@code XiaozhiApplication} 与 {@code DialogueApplication} <b>同时扫描</b>，
 * 两个进程会各跑一遍。
 *
 * <p>本类在 {@code com.xiaozhi.knowledge.task}，而 {@code com.xiaozhi.knowledge}
 * <b>只被 server 入口扫描</b>（dialogue 里它登记在
 * {@code ComponentScanCoverageArchTest.NOT_SCANNED_BY_DESIGN}，因为对话进程不需要入库功能）。
 * 所以这里天然只有一个进程在跑，不需要锁。
 *
 * <p>即便将来真的并发跑了，清理本身也是幂等的：按时间删文件，抢着删最多是
 * 一边删成功、另一边 {@code deleteIfExists} 返回 false，不会误删新文件。
 *
 * <h3>关闭方式</h3>
 * 把 {@code xiaozhi.rag.knowledge.pending-ttl-hours}（环境变量
 * {@code KNOWLEDGE_PENDING_TTL_HOURS}）设成 {@code 0} 或负数即可，任务会直接跳过。
 */
@Slf4j
@Component
public class KnowledgePendingCleanupTask {

    @Resource
    private KnowledgePendingFiles pendingFiles;

    @Resource
    private RagProperties ragProperties;

    /**
     * 每天 03:15 执行一次。
     *
     * <p>错开其它清理任务：录音 01:00 / 消息音频 01:30 / 验证码 03:00 / 操作日志 03:30。
     */
    @Scheduled(cron = "0 15 3 * * ?")
    public void purgeExpiredPendingFiles() {
        int ttlHours = ragProperties.getKnowledge().getPendingTtlHours();
        if (ttlHours <= 0) {
            log.debug("待入库文件清理已关闭（pending-ttl-hours={}）", ttlHours);
            return;
        }

        log.info("========== 开始清理待入库文件（保留 {} 小时）==========", ttlHours);
        KnowledgePendingFiles.PurgeResult result =
                pendingFiles.purgeOlderThan(Duration.ofHours(ttlHours));
        if (result.files() == 0) {
            log.info("========== 待入库文件清理完成：没有过期文件 ==========");
        } else {
            log.info("========== 待入库文件清理完成：删除 {} 个，释放 {} 字节 ==========",
                    result.files(), result.bytes());
        }
    }
}