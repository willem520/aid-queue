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
}
