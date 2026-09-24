package com.willem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时调度 HTTP 接口应用启动类
 */
@EnableScheduling
@SpringBootApplication
public class FetchInfoApplication {

    public static void main(String[] args) {
        SpringApplication.run(FetchInfoApplication.class, args);
    }
}
