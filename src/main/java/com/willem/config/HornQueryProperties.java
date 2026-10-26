package com.willem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 号源查询时间范围配置，在 application.yml 中以 horn 前缀设置
 */
@Data
@ConfigurationProperties(prefix = "horn")
public class HornQueryProperties {

    /**
     * 是否轮询号源。关闭后调度线程不再发起 {@code quryOtpHorn} 查询。
     */
    private boolean enabled = true;

    /** 查询起始日期（yyyy-MM-dd），留空时默认当天 */
    private String startDate;

    /** 查询结束日期（yyyy-MM-dd），留空时默认起始日起第 30 天 */
    private String endDate;
}
