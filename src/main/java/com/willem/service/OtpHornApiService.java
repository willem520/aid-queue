package com.willem.service;

import com.willem.config.HttpTaskProperties.Task;
import com.willem.config.SessionProperties;
import com.willem.util.Sm2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * 号源详情接口 {@code /med/otp/comm/quryOtpHorn}：网关要求 SM2 加密请求体并返回 SM2 密文，
 * 与会话 AES（预约 {@code sbmtOrdrRgst} 等）不同。加解密由 {@link Sm2} 在本进程内完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpHornApiService {

    /** 津医保服务端 SM2 公钥（H5 bundle 内置），未配置 {@code session.sm2-server-public-key} 时使用 */
    private static final String DEFAULT_SERVER_PUBLIC_KEY =
            "04650dd64bda01cd29a03c09792080ea855d783c79e774508ed8bf0a1618aed2"
                    + "0638966f2095bb8e9ff1f083f4cba1ccc60c32a34e95af3e6cf5ef397760470461";

    private final SessionProperties sessionProperties;
    private final HttpFetchService httpFetchService;

    /**
     * 查询号源：SM2 加密请求体发起 POST，响应解密后返回明文 JSON。
     *
     * @return 明文 JSON；网络异常时返回 null（{@link HttpFetchService} 已记日志）
     */
    public String queryOtpHorn(Task task, String plaintextJson) {
        if (!isSm2PrivateKeyConfigured()) {
            throw new IllegalStateException(
                    "quryOtpHorn 需要 SM2 全链路，请先配置 session.sm2-private-key"
                            + "（H5 bundle 内置静态私钥，不随登录轮换）");
        }
        BigInteger qx = Sm2.publicKeyX(serverPublicKey());
        BigInteger qy = Sm2.publicKeyY(serverPublicKey());
        String cipherBody = Sm2.encrypt(plaintextJson.getBytes(StandardCharsets.UTF_8), qx, qy);

        String response = httpFetchService.fetch(task, cipherBody);
        if (response == null) {
            return null;
        }
        return decryptResponse(response);
    }

    /** 是否配置了号源接口用的 SM2 私钥。 */
    public boolean isSm2PrivateKeyConfigured() {
        String key = sessionProperties.getSm2PrivateKey();
        return key != null && !key.isBlank();
    }

    /**
     * 响应可能是 SM2 密文 hex（含业务错误包），个别环境下也直接回明文 JSON
     */
    private String decryptResponse(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        String hex = trimmed.startsWith("\"") ? trimmed.substring(1, trimmed.length() - 1).trim() : trimmed;
        if (!isHex(hex) || !hex.startsWith("04")) {
            throw new IllegalStateException("quryOtpHorn 响应不是 SM2 密文，前缀: "
                    + trimmed.substring(0, Math.min(48, trimmed.length())));
        }
        return new String(Sm2.decrypt(hex, sessionProperties.getSm2PrivateKey().trim()),
                StandardCharsets.UTF_8);
    }

    private String serverPublicKey() {
        String configured = sessionProperties.getSm2ServerPublicKey();
        return configured == null || configured.isBlank() ? DEFAULT_SERVER_PUBLIC_KEY : configured.trim();
    }

    private static boolean isHex(String s) {
        if (s.length() < 130 || (s.length() & 1) == 1) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) {
                return false;
            }
        }
        return true;
    }
}
