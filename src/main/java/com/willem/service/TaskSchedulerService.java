package com.willem.service;

import com.willem.config.HttpTaskProperties;
import com.willem.config.HttpTaskProperties.Task;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务调度服务：在保证整5分钟必查的前提下，最大化随机性以规避爬虫检测
 * <ul>
 *   <li><b>整5分钟强制检查</b>（:00/:05/:10/...）— 不错过放号高峰</li>
 *   <li>两次强制检查之间插入 0-2 次随机抽查，间隔和次数均随机</li>
 *   <li>20% 概率跳过非强制查询，降低总频率</li>
 *   <li>每次请求前随机延迟 2-15 秒，模拟人工操作</li>
 *   <li>每小时最多 12 次查询（含强制+随机），超出则只保留整点检查</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSchedulerService {

    private final HttpTaskProperties properties;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final HornQueryService hornQueryService;

    /** 每个任务的查询计数器和最后查询时间 */
    private static class TaskState {
        final AtomicInteger hourlyCount = new AtomicInteger(0);
        volatile Instant lastQueryTime = Instant.EPOCH;
    }
    private final ConcurrentHashMap<String, TaskState> taskStates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (Task task : properties.getTasks()) {
            if (!task.isEnabled()) {
                log.info("任务[{}] 未启用，跳过", task.getName());
                continue;
            }
            taskStates.putIfAbsent(task.getName(), new TaskState());
            // 整5分钟强制检查（cron）
            taskScheduler.schedule(() -> runMandatoryCheck(task), new CronTrigger("0 */5 * * * *"));
            // 启动时立即跑一轮
            taskScheduler.schedule(() -> runMandatoryCheck(task), Instant.now());
            // 随机抽查调度（在两次强制检查之间插入）
            scheduleRandomProbe(task);
            // 每小时重置计数器
            scheduleHourlyReset(task);
            log.info("任务[{}] 已启动: 整5分必查 + 随机抽查(0-2次/5min) + 限流12次/h, url={}",
                    task.getName(), task.getUrl());
        }
    }

    /**
     * 强制检查：整5分钟触发，不受速率限制（但受每小时上限保护）
     */
    private void runMandatoryCheck(Task task) {
        TaskState state = taskStates.get(task.getName());
        if (state == null) return;

        // 每小时上限保护：即使强制检查，超过12次也跳过
        if (state.hourlyCount.get() >= 12) {
            log.warn("任务[{}] 已达每小时上限(12次)，跳过本次整5分检查", task.getName());
            return;
        }

        executeQuery(task, state, "整5分强制");
    }

    /**
     * 随机抽查：在两次强制检查之间插入 0-2 次，20% 概率跳过
     */
    private void scheduleRandomProbe(Task task) {
        TaskState state = taskStates.get(task.getName());
        if (state == null) return;

        // 随机决定本次间隔内是否插入抽查（60% 概率插入）
        if (ThreadLocalRandom.current().nextInt(100) >= 60) {
            // 不插入，直接排下一轮（5分钟后再次决策）
            taskScheduler.schedule(() -> scheduleRandomProbe(task), Instant.now().plusSeconds(300));
            return;
        }

        // 随机决定插入几次（1-2 次）
        int probes = ThreadLocalRandom.current().nextInt(1, 3);
        for (int i = 0; i < probes; i++) {
            // 随机延迟 60-240 秒后执行
            int delaySec = ThreadLocalRandom.current().nextInt(60, 241);
            Instant runAt = Instant.now().plusSeconds(delaySec);
            taskScheduler.schedule(() -> {
                if (state.hourlyCount.get() >= 12) {
                    log.debug("任务[{}] 随机抽查因达限流而跳过", task.getName());
                    return;
                }
                // 20% 概率跳过
                if (ThreadLocalRandom.current().nextInt(100) < 20) {
                    log.debug("任务[{}] 随机抽查被随机跳过", task.getName());
                    return;
                }
                executeQuery(task, state, "随机抽查");
            }, runAt);
        }

        // 5 分钟后重新决策下一轮
        taskScheduler.schedule(() -> scheduleRandomProbe(task), Instant.now().plusSeconds(300));
    }

    /**
     * 执行查询的统一入口：加随机延迟 + 更新计数
     */
    private void executeQuery(Task task, TaskState state, String source) {
        // 可变延迟 2-15 秒，模拟人工操作
        int delaySec = ThreadLocalRandom.current().nextInt(2, 16);
        try {
            Thread.sleep(delaySec * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        hornQueryService.queryAll(task);
        state.lastQueryTime = Instant.now();
        state.hourlyCount.incrementAndGet();
        log.debug("任务[{}] {}完成，本小时累计{}/12次，延迟{}秒",
                task.getName(), source, state.hourlyCount.get(), delaySec);
    }

    /**
     * 每小时重置查询计数器（在每小时的 :00:30 执行）
     */
    private void scheduleHourlyReset(Task task) {
        Instant now = Instant.now();
        long nextHourMillis = now.plusSeconds(3600 - now.getEpochSecond() % 3600 + 30).toEpochMilli();
        taskScheduler.schedule(() -> {
            TaskState state = taskStates.get(task.getName());
            if (state != null) {
                int lastCount = state.hourlyCount.getAndSet(0);
                log.info("任务[{}] 小时计数器重置，上小时共查询{}次", task.getName(), lastCount);
            }
            scheduleHourlyReset(task);
        }, Instant.ofEpochMilli(nextHourMillis));
    }
}
