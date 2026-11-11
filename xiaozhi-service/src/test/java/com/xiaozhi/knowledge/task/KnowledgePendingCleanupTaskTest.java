package com.xiaozhi.knowledge.task;

import com.xiaozhi.common.config.RagProperties;
import com.xiaozhi.knowledge.service.KnowledgePendingFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 钉住清理任务的两个开关行为：{@code ttl <= 0} 必须完全不碰目录、{@code ttl > 0} 必须按配置的小时数委托。
 *
 * <p>为什么值得测：这行配置是"磁盘占用"的唯一闸门。写错成
 * {@code Duration.ofMillis(ttlHours)} 之类的单位错误，或者把 {@code <= 0} 当成
 * "清得更干净"而没有短路，后果都是把用户刚上传、还没入库的文件删掉。
 *
 * <p>清理逻辑本身（按 mtime 判断、只扫两层、不跟符号链接、不碰父目录）在
 * {@code KnowledgePendingFilesTest} 里测，这里只测任务这一层的接线。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgePendingCleanupTaskTest {

    @Mock
    private KnowledgePendingFiles pendingFiles;

    private KnowledgePendingCleanupTask taskWith(int ttlHours) {
        KnowledgePendingCleanupTask task = new KnowledgePendingCleanupTask();
        RagProperties props = new RagProperties();
        props.getKnowledge().setPendingTtlHours(ttlHours);
        ReflectionTestUtils.setField(task, "pendingFiles", pendingFiles);
        ReflectionTestUtils.setField(task, "ragProperties", props);
        return task;
    }

    @Test
    @DisplayName("ttl = 0 表示关闭清理，连目录都不碰")
    void skipsWhenTtlIsZero() {
        taskWith(0).purgeExpiredPendingFiles();

        verifyNoInteractions(pendingFiles);
    }

    @Test
    @DisplayName("ttl 为负数也视为关闭清理")
    void skipsWhenTtlIsNegative() {
        taskWith(-1).purgeExpiredPendingFiles();

        verifyNoInteractions(pendingFiles);
    }

    @Test
    @DisplayName("ttl > 0 时按「小时」换算成 Duration 并委托清理")
    void delegatesWithHoursAsDuration() {
        when(pendingFiles.purgeOlderThan(Duration.ofHours(24)))
                .thenReturn(KnowledgePendingFiles.PurgeResult.EMPTY);

        taskWith(24).purgeExpiredPendingFiles();

        // 单位必须是小时：若误写成 ofMillis/ofSeconds，这里的 verify 会匹配不上
        verify(pendingFiles).purgeOlderThan(Duration.ofHours(24));
    }
}