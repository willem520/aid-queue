package com.willem.service;

import com.willem.config.HttpTaskProperties.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 调用执行器，基于 JDK 内置 HttpClient 实现。
 * 签名相关请求头（jyb 与 x-tif 系列）在运行时基于当前 token 动态计算，
 * 配置里的静态签名值会被忽略，避免 token 轮换后签名失配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpFetchService {

    private static final String SALT = "jyb4443";
    private static final String NONCE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * 需跳过的请求头：JDK HttpClient 禁止手动设置的受限头（host/content-length/connection 等），
     * accept-encoding（手动设置会关闭自动解压，导致响应体乱码），
     * 以及签名相关头（由本类动态重算，忽略配置里的静态值）
     */
    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "host", "content-length", "connection", "accept-encoding", "expect", "upgrade",
            "jyb-timestamp", "jyb-nonce", "jyb-signature",
            "x-tif-timestamp", "x-tif-nonce", "x-tif-signature");

    private final HttpClient httpClient;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 按任务配置发起一次请求，返回响应体字符串；异常时记录日志并返回 null
     *
     * @param task 任务配置（url/method/headers/timeout）
     * @param body 请求体（由代码动态生成）
     */
    public String fetch(Task task, String body) {
        try {
            // 动态计算签名：token 轮换后签名自动跟随，无需改配置
            String ts = String.valueOf(System.currentTimeMillis() / 1000);
            String nc = generateNonce();
            String token = extractToken(task);
            String jybSig = md5(ts + nc + token + SALT);
            String xtSig = sha256(ts + nc + ts).toUpperCase();

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(task.getUrl()))
                    .timeout(task.getTimeout())
                    .header("jyb-timestamp", ts)
                    .header("jyb-nonce", nc)
                    .header("jyb-signature", jybSig)
                    .header("X-Tif-Timestamp", ts)
                    .header("X-Tif-Nonce", nc)
                    .header("X-Tif-Signature", xtSig);
            for (Map.Entry<String, String> header : task.getHeaders().entrySet()) {
                if (SKIPPED_HEADERS.contains(header.getKey().toLowerCase())) {
                    continue;
                }
                builder.header(header.getKey(), header.getValue());
            }
            builder.method(task.getMethod().toUpperCase(),
                    body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            log.error("任务[{}] 请求失败: {}", task.getName(), e.getMessage());
            return null;
        }
    }

    private String extractToken(Task task) {
        String token = task.getHeaders().get("token");
        return token != null ? token : "";
    }

    private String generateNonce() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(NONCE_CHARS.charAt(secureRandom.nextInt(NONCE_CHARS.length())));
        }
        return sb.toString();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return String.format("%032x", new BigInteger(1, digest));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return String.format("%064x", new BigInteger(1, digest));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
