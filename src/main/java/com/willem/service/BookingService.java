package com.willem.service;

import com.willem.config.BookingProperties;
import com.willem.config.HttpTaskProperties.Task;
import com.willem.config.SessionProperties;
import com.willem.model.Doctor;
import com.willem.model.HornQueryResponse.HornSlot;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 预约提交服务：构造 sbmtOrdrRgst 请求体，AES 加密后提交，解密响应
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final String BOOKING_URL = "https://jyt.ylbz.tj.gov.cn/app-prod/med/otp/ordr/sbmtOrdrRgst";
    private static final String BASE_URL = "https://jyt.ylbz.tj.gov.cn/app-prod";
    private static final String SALT = "jyb4443";
    private static final String NONCE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Set<String> SKIP_HEADERS = Set.of(
            "host", "content-length", "connection", "content-type",
            "jyb-timestamp", "jyb-nonce", "jyb-signature",
            "x-tif-timestamp", "x-tif-nonce", "x-tif-signature");

    private final AesCryptoService aesCryptoService;
    private final BookingProperties bookingProperties;
    private final SessionProperties sessionProperties;
    private final HttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 提交预约：构造请求体 → AES 加密 → 发送 → 解密响应
     */
    public String submitBooking(Task task, Doctor doctor, HornSlot slot) {
        try {
            Map<String, Object> body = buildBookingBody(doctor, slot);
            String plaintext = objectMapper.writeValueAsString(body);
            log.info("预约请求体（明文）: {}", plaintext);

            String encrypted = aesCryptoService.encrypt(plaintext);
            String ts = String.valueOf(System.currentTimeMillis() / 1000);
            String nc = generateNonce();
            String token = extractToken(task);
            String jybSig = md5(ts + nc + token + SALT);
            String xtSig = sha256(ts + nc + ts).toUpperCase();

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(BOOKING_URL))
                    .timeout(task.getTimeout())
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("jyb-timestamp", ts)
                    .header("jyb-nonce", nc)
                    .header("jyb-signature", jybSig)
                    .header("X-Tif-Timestamp", ts)
                    .header("X-Tif-Nonce", nc)
                    .header("X-Tif-Signature", xtSig)
                    .POST(HttpRequest.BodyPublishers.ofString(encrypted));

            for (Map.Entry<String, String> header : task.getHeaders().entrySet()) {
                String key = header.getKey().toLowerCase();
                if (SKIP_HEADERS.contains(key)) {
                    continue;
                }
                builder.header(header.getKey(), header.getValue());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String respBody = response.body();
            log.info("预约响应 HTTP {}: {}", response.statusCode(), respBody.substring(0, Math.min(200, respBody.length())));

            if (respBody.isBlank()) {
                log.warn("预约响应为空");
                return null;
            }

            String trimmed = respBody.trim();
            if (trimmed.startsWith("{")) {
                log.warn("预约响应为明文 JSON（未加密）: {}", trimmed);
                return trimmed;
            }

            String decrypted = aesCryptoService.decrypt(trimmed);
            log.info("预约响应（解密后）: {}", decrypted);
            return decrypted;
        } catch (Exception e) {
            log.error("预约提交失败", e);
            return null;
        }
    }

    /**
     * 查询就诊人列表：尝试多个 API 找到亲情账户绑定的就诊人
     */
    public String queryPatientList(Task task) {
        StringBuilder results = new StringBuilder();

        results.append("=== 1. /app/quryMainUserList (mdtrterId=").append(sessionProperties.getUserId()).append(") ===\n");
        results.append(callApi(task, "/app/quryMainUserList", Map.of("mdtrterId", sessionProperties.getUserId())));

        results.append("\n=== 2. /app/quryMainUserList (无参数) ===\n");
        results.append(callApi(task, "/app/quryMainUserList", Map.of()));

        results.append("\n=== 3. /base/user/quryUserDetl (mdtrterId=").append(sessionProperties.getUserId()).append(") ===\n");
        results.append(callApi(task, "/base/user/quryUserDetl", Map.of("mdtrterId", sessionProperties.getUserId())));

        results.append("\n=== 4. /ec/family/queryFamily (mdtrterId=").append(sessionProperties.getUserId()).append(") ===\n");
        results.append(callApi(task, "/ec/family/queryFamily", Map.of("mdtrterId", sessionProperties.getUserId())));

        results.append("\n=== 5. /idetCrtf/quryMdtrterUserDetl (无参数) ===\n");
        results.append(callApi(task, "/idetCrtf/quryMdtrterUserDetl", Map.of()));

        results.append("\n=== 6. /idetCrtf/quryMdtrterUserDetlNew (无参数) ===\n");
        results.append(callApi(task, "/idetCrtf/quryMdtrterUserDetlNew", Map.of()));

        results.append("\n=== 7. /idetCrtf/quryUserDetl (userId=").append(sessionProperties.getUserId()).append(") ===\n");
        results.append(callApi(task, "/idetCrtf/quryUserDetl", Map.of("userId", sessionProperties.getUserId())));

        return results.toString();
    }

    /**
     * 查询医院列表：调用 /med/hosp/quryHosp（明文请求，非 AES 加密）
     */
    public String queryHospitalList(Task task) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("admdvs", "");
        param.put("dataSouc", "1");
        param.put("efccNo", "MT02");
        param.put("fixmedinsLv", "");
        param.put("fixmedinsCode", "");
        param.put("fixmedinsName", "");
        param.put("intelFlag", "");
        param.put("isFav", "");
        param.put("isFmServ", "");
        param.put("isIptDpst", "");
        param.put("isMedcas", "");
        param.put("lat", "");
        param.put("lnt", "");
        param.put("pageNum", "");

        return callApiPlaintext(task, "/med/hosp/quryHosp", param);
    }

    /**
     * 通过号源查询接口反查医生编码：用空编码+姓名查询
     */
    public String probeDoctorCode(Task task, String doctorName) {
        StringBuilder results = new StringBuilder();
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String endDate = java.time.LocalDate.now().plusDays(30).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // 用 HttpFetchService 发送明文 JSON（和 HornQueryService 一样的方式）
        var fetchService = new HttpFetchService(httpClient);

        // 方案1: 和正常查询一样但用目标医生名+空编码
        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("caty", "000201");
        body1.put("enddate", endDate);
        body1.put("fixmedinsId", bookingProperties.getFixmedinsId());
        body1.put("hornType", "副主任医师");
        body1.put("inhospDrCodg", "");
        body1.put("drName", doctorName);
        body1.put("mdtrtDate", today);
        body1.put("opspItntExraFlag", 0);
        body1.put("rgstTypeCode", "2");
        body1.put("dataSouc", "1");
        try {
            String json1 = objectMapper.writeValueAsString(body1);
            results.append("=== 方案1: 空编码+副主任医师 (HttpFetchService) ===\n");
            String resp1 = fetchService.fetch(task, json1);
            if (resp1 != null && !resp1.isBlank() && !resp1.trim().startsWith("{")) {
                results.append(aesCryptoService.decrypt(resp1.trim()));
            } else {
                results.append(resp1);
            }
        } catch (Exception e) {
            results.append("错误: ").append(e.getMessage());
        }

        // 方案2: opspItntExraFlag=1, 不带 inhospDrCodg 和 hornType
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("caty", "000201");
        body2.put("enddate", endDate);
        body2.put("fixmedinsId", bookingProperties.getFixmedinsId());
        body2.put("drName", doctorName);
        body2.put("mdtrtDate", today);
        body2.put("opspItntExraFlag", 1);
        body2.put("rgstTypeCode", "2");
        body2.put("dataSouc", "1");
        try {
            String json2 = objectMapper.writeValueAsString(body2);
            results.append("\n\n=== 方案2: opspItntExraFlag=1 无编码 (HttpFetchService) ===\n");
            String resp2 = fetchService.fetch(task, json2);
            if (resp2 != null && !resp2.isBlank() && !resp2.trim().startsWith("{")) {
                results.append(aesCryptoService.decrypt(resp2.trim()));
            } else {
                results.append(resp2);
            }
        } catch (Exception e) {
            results.append("错误: ").append(e.getMessage());
        }

        // 方案3: 用一个已知有效的编码(贾艳菊 0328)验证接口是否通
        Map<String, Object> body3 = new LinkedHashMap<>();
        body3.put("caty", "000201");
        body3.put("enddate", endDate);
        body3.put("fixmedinsId", bookingProperties.getFixmedinsId());
        body3.put("hornType", "主任医师");
        body3.put("inhospDrCodg", "0328");
        body3.put("drName", "贾艳菊");
        body3.put("mdtrtDate", today);
        body3.put("opspItntExraFlag", 0);
        body3.put("rgstTypeCode", "2");
        body3.put("dataSouc", "1");
        try {
            String json3 = objectMapper.writeValueAsString(body3);
            results.append("\n\n=== 方案3: 已知有效编码 0328/贾艳菊 (对照组) ===\n");
            String resp3 = fetchService.fetch(task, json3);
            if (resp3 != null && !resp3.isBlank() && !resp3.trim().startsWith("{")) {
                String dec = aesCryptoService.decrypt(resp3.trim());
                results.append(dec.substring(0, Math.min(500, dec.length())));
            } else {
                results.append(resp3);
            }
        } catch (Exception e) {
            results.append("错误: ").append(e.getMessage());
        }

        return results.toString();
    }

    /**
     * 查询医院下的医生列表：调用 /med/dr/quryDrList（明文请求）
     */
    public String queryDoctorList(Task task) {
        StringBuilder results = new StringBuilder();

        Map<String, Object> baseParam = new LinkedHashMap<>();
        baseParam.put("caty", bookingProperties.getCaty());
        baseParam.put("dataSouc", bookingProperties.getDataSouc());
        baseParam.put("deptName", "");
        baseParam.put("drName", "");
        baseParam.put("efccNo", "MT02");
        baseParam.put("fixmedinsCode", bookingProperties.getFixmedinsCode());
        baseParam.put("fixmedinsId", bookingProperties.getFixmedinsId());
        baseParam.put("fixmedinsName", bookingProperties.getFixmedinsName());
        baseParam.put("pageNum", "1");
        baseParam.put("regnCodg", "2000000");
        baseParam.put("userId", sessionProperties.getUserId());

        results.append("=== 1. /med/dr/quryDrList (AES) ===\n");
        results.append(callApi(task, "/med/dr/quryDrList", baseParam));

        results.append("\n\n=== 2. /med/otp/dr/quryDrList (AES) ===\n");
        results.append(callApi(task, "/med/otp/dr/quryDrList", baseParam));

        results.append("\n\n=== 3. /med/dr/quryDrList (plaintext) ===\n");
        results.append(callApiPlaintext(task, "/med/dr/quryDrList", baseParam));

        results.append("\n\n=== 4. /med/otp/dr/quryDrList (plaintext) ===\n");
        results.append(callApiPlaintext(task, "/med/otp/dr/quryDrList", baseParam));

        return results.toString();
    }

    /**
     * 明文 API 调用：不加密请求体，但保留签名头；响应可能是明文或加密
     */
    public String callApiPlaintext(Task task, String path, Map<String, Object> param) {
        try {
            String plaintext = objectMapper.writeValueAsString(param);
            log.info("API {} 请求体(明文): {}", path, plaintext);

            String ts = String.valueOf(System.currentTimeMillis() / 1000);
            String nc = generateNonce();
            String token = extractToken(task);
            String jybSig = md5(ts + nc + token + SALT);
            String xtSig = sha256(ts + nc + ts).toUpperCase();

            String url = BASE_URL + path;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(task.getTimeout())
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("jyb-timestamp", ts)
                    .header("jyb-nonce", nc)
                    .header("jyb-signature", jybSig)
                    .header("X-Tif-Timestamp", ts)
                    .header("X-Tif-Nonce", nc)
                    .header("X-Tif-Signature", xtSig)
                    .POST(HttpRequest.BodyPublishers.ofString(plaintext));

            for (Map.Entry<String, String> header : task.getHeaders().entrySet()) {
                String key = header.getKey().toLowerCase();
                if (SKIP_HEADERS.contains(key)) {
                    continue;
                }
                builder.header(header.getKey(), header.getValue());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String respBody = response.body();
            log.info("API {} 响应 HTTP {}: {}", path, response.statusCode(), respBody.substring(0, Math.min(500, respBody.length())));

            if (respBody.isBlank()) {
                return "(空响应)";
            }

            String trimmed = respBody.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return "(明文) " + trimmed;
            }

            String decrypted = aesCryptoService.decrypt(trimmed);
            log.info("API {} 解密: {}", path, decrypted);
            return decrypted;
        } catch (Exception e) {
            log.error("API {} 调用失败", path, e);
            return "(错误) " + e.getMessage();
        }
    }

    /**
     * 通用 API 调用：加密请求 → 签名 → 发送 → 解密响应
     */
    public String callApi(Task task, String path, Map<String, Object> param) {
        try {
            String plaintext = objectMapper.writeValueAsString(param);
            log.info("API {} 请求体: {}", path, plaintext);

            String encrypted = aesCryptoService.encrypt(plaintext);
            String ts = String.valueOf(System.currentTimeMillis() / 1000);
            String nc = generateNonce();
            String token = extractToken(task);
            String jybSig = md5(ts + nc + token + SALT);
            String xtSig = sha256(ts + nc + ts).toUpperCase();

            String url = BASE_URL + path;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(task.getTimeout())
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("jyb-timestamp", ts)
                    .header("jyb-nonce", nc)
                    .header("jyb-signature", jybSig)
                    .header("X-Tif-Timestamp", ts)
                    .header("X-Tif-Nonce", nc)
                    .header("X-Tif-Signature", xtSig)
                    .POST(HttpRequest.BodyPublishers.ofString(encrypted));

            for (Map.Entry<String, String> header : task.getHeaders().entrySet()) {
                String key = header.getKey().toLowerCase();
                if (SKIP_HEADERS.contains(key)) {
                    continue;
                }
                builder.header(header.getKey(), header.getValue());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String respBody = response.body();
            log.info("API {} 响应 HTTP {}: {}", path, response.statusCode(), respBody.substring(0, Math.min(500, respBody.length())));

            if (respBody.isBlank()) {
                return "(空响应)";
            }

            String trimmed = respBody.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return "(明文) " + trimmed;
            }

            String decrypted = aesCryptoService.decrypt(trimmed);
            log.info("API {} 解密: {}", path, decrypted);
            return decrypted;
        } catch (Exception e) {
            log.error("API {} 调用失败", path, e);
            return "(错误) " + e.getMessage();
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

    /**
     * 构造 sbmtOrdrRgst 请求体（26 个字段）
     */
    private Map<String, Object> buildBookingBody(Doctor doctor, HornSlot slot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataSouc", bookingProperties.getDataSouc());
        body.put("caty", bookingProperties.getCaty());
        body.put("drName", doctor.name());
        body.put("fixmedinsCode", bookingProperties.getFixmedinsCode());
        body.put("fixmedinsId", bookingProperties.getFixmedinsId());
        body.put("fixmedinsName", bookingProperties.getFixmedinsName());
        body.put("deptName", slot.getHowlName() != null ? extractDeptName(slot.getHowlName()) : "");
        body.put("hornType", doctor.hornType());
        body.put("howlName", slot.getHowlName() != null ? slot.getHowlName() : "");
        body.put("drId", "");
        body.put("inhospDrCodg", doctor.inhospDrCodg());
        body.put("noDrHornType", "");
        body.put("mdtrtDate", slot.getMdtrtDate());
        body.put("mdtrterId", bookingProperties.getMdtrterId());
        body.put("medfeeSumamt", slot.getMedfeeSumamt() != null ? slot.getMedfeeSumamt() : 0);
        body.put("prd", slot.getPrd() != null ? slot.getPrd() : "");
        body.put("prdBegntime", slot.getPrdBegntime() != null ? slot.getPrdBegntime() : "");
        body.put("prdEndtime", slot.getPrdEndtime() != null ? slot.getPrdEndtime() : "");
        body.put("userId", sessionProperties.getUserId());
        body.put("epidSrvyRslt", "");
        body.put("natFlag", "");
        body.put("accUserId", "");
        body.put("disabilityLevel", "");
        body.put("disabilityCategory", "");
        body.put("deptDiseCode", "");
        body.put("needSms", bookingProperties.getNeedSms());
        return body;
    }

    /**
     * 从 howlName 中提取科室名称（格式如：产科诊室贾艳菊 → 产科诊室）
     */
    private String extractDeptName(String howlName) {
        if (howlName == null || howlName.isEmpty()) {
            return "";
        }
        for (int i = 0; i < howlName.length(); i++) {
            char c = howlName.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                continue;
            }
            if (Character.isUpperCase(c) || Character.isLowerCase(c) || Character.isDigit(c)) {
                return howlName.substring(0, i);
            }
        }
        return howlName;
    }
}
