package com.willem.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;

/**
 * 基础 Bean 配置：HttpClient 与调度线程池
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({HttpTaskProperties.class, HornQueryProperties.class, NotifyProperties.class, SessionProperties.class, BookingProperties.class})
public class AppConfig {

    /** 自定义信任库：系统 cacerts + Proxyman CA，解决 Proxyman 抓包时 JVM TLS 校验失败 */
    private static final Path TRUST_STORE = Path.of("runtime/truststore.jks");
    private static final char[] TRUST_STORE_PASSWORD = "changeit".toCharArray();

    @Bean
    public HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);
        sslContext().ifPresent(builder::sslContext);
        configureProxy(builder);
        return builder.build();
    }

    /**
     * JDK HttpClient 默认不走 macOS 系统代理；run-proxyman.sh 会设置 https.proxyHost/Port。
     */
    private void configureProxy(HttpClient.Builder builder) {
        String host = System.getProperty("https.proxyHost");
        String portStr = System.getProperty("https.proxyPort");
        if (host != null && !host.isBlank() && portStr != null && !portStr.isBlank()) {
            int port = Integer.parseInt(portStr.trim());
            builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(host.trim(), port)));
            log.info("HttpClient HTTPS 代理: {}:{}", host.trim(), port);
            return;
        }
        if (Boolean.parseBoolean(System.getProperty("java.net.useSystemProxies", "false"))) {
            builder.proxy(ProxySelector.getDefault());
            log.info("HttpClient 使用 ProxySelector.getDefault()（需 macOS 系统代理已开启）");
        }
    }

    /**
     * 从 runtime/truststore.jks 加载信任库构建 SSLContext；文件不存在时返回空（用 JVM 默认信任链）
     */
    private java.util.Optional<SSLContext> sslContext() {
        if (!Files.exists(TRUST_STORE)) {
            log.info("未找到自定义信任库 {}，使用 JVM 默认信任链", TRUST_STORE.toAbsolutePath());
            return java.util.Optional.empty();
        }
        try (InputStream in = Files.newInputStream(TRUST_STORE)) {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(in, TRUST_STORE_PASSWORD);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            log.info("已加载自定义信任库 {}", TRUST_STORE.toAbsolutePath());
            return java.util.Optional.of(sslContext);
        } catch (Exception e) {
            log.warn("加载信任库 {} 失败，回退 JVM 默认信任链: {}", TRUST_STORE.toAbsolutePath(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("http-task-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
