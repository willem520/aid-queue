package com.willem.service;

import com.willem.config.SessionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES-CBC 加解密服务：用于预约请求体加密和响应解密
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AesCryptoService {

    private final SessionProperties sessionProperties;

    /**
     * AES-CBC 加密（PKCS5/PKCS7 padding）
     *
     * @param plaintext 明文
     * @return Base64 编码的密文
     */
    public String encrypt(String plaintext) {
        try {
            byte[] keyBytes = sessionProperties.getAesKey().getBytes(StandardCharsets.UTF_8);
            byte[] ivBytes = sessionProperties.getAesIv().getBytes(StandardCharsets.UTF_8);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("AES 加密失败", e);
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * AES-CBC 解密（PKCS5/PKCS7 padding）
     *
     * @param ciphertextBase64 Base64 编码的密文
     * @return 解密后的明文
     */
    public String decrypt(String ciphertextBase64) {
        try {
            byte[] keyBytes = sessionProperties.getAesKey().getBytes(StandardCharsets.UTF_8);
            byte[] ivBytes = sessionProperties.getAesIv().getBytes(StandardCharsets.UTF_8);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertextBase64));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("AES 解密失败", e);
            throw new RuntimeException("解密失败", e);
        }
    }
}
