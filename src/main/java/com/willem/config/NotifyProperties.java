package com.willem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 有号通知配置，在 application.yml 中以 notify 前缀设置
 */
@Data
@ConfigurationProperties(prefix = "notify")
public class NotifyProperties {

    /** 是否弹 macOS 本机通知（默认开启，零配置） */
    private boolean macosEnabled = true;

    /** 是否弹 macOS 模态对话框（不依赖通知中心权限，120 秒后自动关闭） */
    private boolean dialogEnabled = false;

    /** 启动时是否发一条测试通知，用于验证推送通道（默认开启） */
    private boolean startupTest = true;

    /** Bark 推送地址（iOS），形如 https://api.day.app/你的key，留空则不推送 */
    private String barkUrl;
}
