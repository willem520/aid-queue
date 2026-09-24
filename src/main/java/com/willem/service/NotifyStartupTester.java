package com.willem.service;

import com.willem.config.NotifyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时发送一条测试通知，用于验证 macOS 本机推送通道是否正常；
 * 验证完成后可在 application.yml 中设置 notify.startup-test: false 关闭
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyStartupTester implements ApplicationRunner {

    private final NotifyProperties properties;
    private final NotificationService notificationService;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isStartupTest()) {
            return;
        }
        log.info("发送 macOS 通知测试（如需关闭：notify.startup-test: false）");
        notificationService.notifyAvailable("号源监控·通知测试",
                "看到这条通知说明 macOS 本机推送正常工作");
    }
}
