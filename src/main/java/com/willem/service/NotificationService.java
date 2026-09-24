package com.willem.service;

import com.willem.config.NotifyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 有号通知服务：macOS 本机通知 + Bark（iOS）推送，失败只记日志不影响查询主流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotifyProperties properties;
    private final HttpClient httpClient;

    /**
     * 推送一条有号通知
     *
     * @param title   通知标题，如：有号提醒
     * @param message 通知内容，如：医师: 贾艳菊 | 日期: 2026-08-21 | 可挂号源: 11
     */
    public void notifyAvailable(String title, String message) {
        if (properties.isMacosEnabled()) {
            macNotify(title, message);
        }
        if (properties.isDialogEnabled()) {
            // 弹窗会阻塞等待点击，放到独立线程避免卡住调度线程
            Thread.ofVirtual().name("notify-dialog").start(() -> dialogNotify(title, message));
        }
        String barkUrl = properties.getBarkUrl();
        if (barkUrl != null && !barkUrl.isBlank()) {
            barkNotify(barkUrl.trim(), title, message);
        }
    }

    /**
     * macOS 本机通知（osascript），带提示音
     */
    private void macNotify(String title, String message) {
        try {
            String script = String.format("display notification \"%s\" with title \"%s\" sound name \"Glass\"",
                    escape(message), escape(title));
            int exit = new ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true).start().waitFor();
            if (exit != 0) {
                log.warn("macOS 通知发送失败, exit={}", exit);
            }
        } catch (Exception e) {
            log.warn("macOS 通知发送异常: {}", e.getMessage());
        }
    }

    /**
     * Bark 推送（iOS）：GET {barkUrl}/{title}/{body}
     */
    private void barkNotify(String barkUrl, String title, String message) {
        try {
            String url = barkUrl + "/" + encode(title) + "/" + encode(message);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Bark 推送失败, status={}, body={}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Bark 推送异常: {}", e.getMessage());
        }
    }

    /**
     * macOS 模态对话框（osascript）：屏幕中央弹窗，必须点「好」或 120 秒后自动关闭
     */
    private void dialogNotify(String title, String message) {
        try {
            String script = String.format(
                    "display dialog \"%s\" with title \"%s\" buttons {\"好\"} default button 1 giving up after 120",
                    escape(message), escape(title));
            int exit = new ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true).start().waitFor();
            if (exit != 0) {
                log.warn("macOS 弹窗发送失败, exit={}", exit);
            }
        } catch (Exception e) {
            log.warn("macOS 弹窗发送异常: {}", e.getMessage());
        }
    }

    /**
     * macOS 确认弹窗：显示「抢号」和「取消」两个按钮，返回用户是否点击了「抢号」。
     * 阻塞当前线程直到用户点击或 120 秒超时。
     */
    public boolean confirmBooking(String title, String message) {
        try {
            String script = String.format(
                    "set result to display dialog \"%s\" with title \"%s\" buttons {\"取消\", \"抢号\"} default button \"抢号\" cancel button \"取消\" giving up after 120\n" +
                    "if button returned of result is \"抢号\" then\n" +
                    "    return \"YES\"\n" +
                    "else\n" +
                    "    return \"NO\"\n" +
                    "end if",
                    escape(message), escape(title));
            Process process = new ProcessBuilder("osascript", "-e", script)
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            log.info("确认弹窗结果: exit={}, output={}", exit, output);
            return exit == 0 && output.endsWith("YES");
        } catch (Exception e) {
            log.warn("确认弹窗异常: {}", e.getMessage());
            return false;
        }
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
