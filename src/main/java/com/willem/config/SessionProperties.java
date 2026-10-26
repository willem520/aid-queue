package com.willem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话凭据配置：从 iMazing 备份提取的 AES 密钥和用户信息
 */
@Data
@ConfigurationProperties(prefix = "session")
public class SessionProperties {

    /** AES-128 密钥（32 位 hex 风格字符串，UTF-8 编码后作为 AES key） */
    private String aesKey;

    /** AES CBC 初始化向量（16 位字符串，UTF-8 编码后作为 AES iv） */
    private String aesIv;

    /** 用户 ID */
    private String userId;

    /** 手机号 */
    private String mob;

    /**
     * {@code quryOtpHorn} 请求加密与响应解密用的 SM2 私钥（64 hex）。
     * 取自津医保 H5 bundle 内置的静态密钥对（{@code common/util/request/jytPwd}），
     * 不随登录轮换，无需设备 Keychain。
     */
    private String sm2PrivateKey;

    /**
     * 请求体 SM2 加密用的服务端公钥（130 hex，可选）。
     * 留空时用 {@code OtpHornApiService} 内置的津医保 H5 静态公钥。
     */
    private String sm2ServerPublicKey;
}
