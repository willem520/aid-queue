package com.willem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时 HTTP 任务配置，对应 application.yml 中 fetch.tasks 列表
 */
@Data
@ConfigurationProperties(prefix = "fetch")
public class HttpTaskProperties {

    private List<Task> tasks = new ArrayList<>();

    @Data
    public static class Task {
        /** 任务名称，唯一标识 */
        private String name;
        /** 目标接口地址 */
        private String url;
        /** HTTP 方法：GET / POST / PUT / DELETE */
        private String method = "GET";
        /** 请求头 */
        private Map<String, String> headers = new LinkedHashMap<>();
        /** 单次请求超时时间 */
        private Duration timeout = Duration.ofSeconds(10);
        /** 是否启用 */
        private boolean enabled = true;
    }
}
