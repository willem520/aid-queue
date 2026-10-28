package com.willem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.willem.config.HornQueryProperties;
import com.willem.config.HttpTaskProperties.Task;
import com.willem.model.Doctor;
import com.willem.model.HornQueryResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 号源查询服务：医生在代码中配置，查询起止日期在 application.yml 的 horn 配置中设置，
 * 每轮调度对每位医生发起一次跨度查询，响应内号源自带日期，按日期分组输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HornQueryService {

    /** 定点医疗机构编号（所有医生统一） */
    private static final String FIX_MEDINS_ID = "H120101000041";

    /** 未配置查询日期时的默认跨度：从当天起往后推 30 天 */
    private static final int DEFAULT_DAYS_AHEAD = 30;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** ===== 要查询的医生列表，新增医生在这里加一行 ===== */
    private static final List<Doctor> DOCTORS = List.of(
            new Doctor("崔洪艳", "0116", "主任医师"),
            new Doctor("贾艳菊", "0328", "主任医师"),
            new Doctor("韩林飞", "0108", "主任医师"),
            new Doctor("孙桢", "0122", "副主任医师"),
            new Doctor("李丹", "8181", "副主任医师"),
            new Doctor("吉卉", "1003", "主任医师"),
            new Doctor("张玉红", "0132", "主任医师"),
            new Doctor("李丽军", "0716", "主任医师"),
            new Doctor("邱晓媛", "2702", "主任医师"),
            new Doctor("裘瑾", "0119", "主任医师")
    );

    private final OtpHornApiService otpHornApiService;
    private final ObjectMapper objectMapper;
    private final HornQueryProperties hornProperties;
    private final NotificationService notificationService;
    private final BookingService bookingService;
    private final com.willem.config.BookingProperties bookingProperties;

    /** 并发保护：常规轮询与整点加查可能撞车，同一时刻只允许一轮在跑 */
    private final AtomicBoolean querying = new AtomicBoolean(false);

    /** 连续失败轮数：达到阈值后提示用户更新凭据，避免刷屏 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 本轮已解析出的时段总数，用于区分「接口正常但确实无号」和「接口挂了」 */
    private final AtomicInteger roundSlots = new AtomicInteger();

    @PostConstruct
    void logHornQueryCapability() {
        if (!hornProperties.isEnabled()) {
            log.info("号源轮询已关闭（horn.enabled=false）");
        } else if (!otpHornApiService.isSm2PrivateKeyConfigured()) {
            log.warn("号源查询不可用：未配置 session.sm2-private-key。"
                    + "quryOtpHorn 请求体必须是 SM2 密文、响应也是 SM2 密文，会话 AES 两头都对不上；"
                    + "私钥是 H5 bundle（common/util/request/jytPwd）内置静态密钥，不随登录轮换。");
        }
    }

    /**
     * 执行一轮查询：每位医生一次请求，起止日期取自配置（未配置时默认今天起 30 天）。
     * 单轮异常只记录并跳过本轮，绝不向上抛出——否则会带走启动线程或整个应用。
     */
    public void queryAll(Task task) {
        if (!querying.compareAndSet(false, true)) {
            log.info("上一轮查询尚未结束，跳过本次触发");
            return;
        }
        try {
            doQueryAll(task);
            consecutiveFailures.set(0);
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures == 1 || failures % 10 == 0) {
                log.warn("本轮号源查询失败（连续第 {} 轮）: {}", failures, e.getMessage());
            } else {
                log.debug("本轮号源查询失败: {}", e.getMessage());
            }
        } finally {
            querying.set(false);
        }
    }

    private void doQueryAll(Task task) {
        if (!hornProperties.isEnabled()) {
            return;
        }
        String startDate = resolveStartDate();
        String endDate = resolveEndDate(startDate);
        roundSlots.set(0);
        Map<String, String> roundAvailable = new LinkedHashMap<>();
        for (Doctor doctor : DOCTORS) {
            log.info("发起查询: 医师={} 时间跨度={} ~ {}", doctor.name(), startDate, endDate);
            String plaintextBody = buildBody(doctor, startDate, endDate);
            String response = otpHornApiService.queryOtpHorn(task, plaintextBody);
            if (response == null) {
                return; // 网络异常，已记录日志，本轮结束
            }
            if (!parseAndPrint(task, doctor, response, roundAvailable)) {
                return; // 业务失败（如 token 过期），本轮后续医生不再查询
            }
        }
        if (roundAvailable.isEmpty()) {
            log.info("本轮 {} 位医师共 {} 个时段均无余号（接口正常，非查询失败）",
                    DOCTORS.size(), roundSlots.get());
        }
        // 自动预约启用时 confirmBooking 弹窗已包含有号信息，不重复弹通知
        if (!bookingProperties.isAutoEnabled()) {
            for (String message : roundAvailable.values()) {
                notificationService.notifyAvailable("医院挂号有号提醒", message);
            }
        }
    }

    /**
     * 查询起始日期：优先用配置 horn.start-date，未配置时取当天
     */
    private String resolveStartDate() {
        String configured = hornProperties.getStartDate();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return LocalDate.now().format(DATE_FMT);
    }

    /**
     * 查询结束日期：优先用配置 horn.end-date，未配置时取起始日起第 30 天
     */
    private String resolveEndDate(String startDate) {
        String configured = hornProperties.getEndDate();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return LocalDate.parse(startDate, DATE_FMT).plusDays(DEFAULT_DAYS_AHEAD - 1).format(DATE_FMT);
    }

    /**
     * 按医生和日期构造请求体 JSON
     */
    private String buildBody(Doctor doctor, String mdtrtDate, String endDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caty", "000201");
        body.put("enddate", endDate);
        body.put("fixmedinsId", FIX_MEDINS_ID);
        body.put("hornType", doctor.hornType());
        body.put("inhospDrCodg", doctor.inhospDrCodg());
        body.put("drName", doctor.name());
        body.put("noDrHornType", "");
        body.put("mdtrtDate", mdtrtDate);
        body.put("opspItntExraFlag", 0);
        body.put("rgstTypeCode", "2");
        body.put("dataSouc", "1");
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("构造请求体失败", e);
        }
    }

    /**
     * 判断时段是否有号：剩余号源数 > 0 且医院放号数 > 0
     * （hospHornNum 为 -3 等负值表示尚未正式放号/待定，不算有号）
     */
    private boolean isAvailable(HornQueryResponse.HornSlot slot) {
        return slot.getBlncHornNum() != null && slot.getBlncHornNum() > 0
                && slot.getHospHornNum() != null && slot.getHospHornNum() > 0;
    }

    /** native/H5 可能返回 {@code {code,data}} 或直接返回号源数组 */
    private HornQueryResponse parseHornResponse(String json) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);
        if (root.isArray()) {
            HornQueryResponse wrapper = new HornQueryResponse();
            wrapper.setCode(0);
            wrapper.setType("success");
            wrapper.setMessage("成功");
            wrapper.setData(objectMapper.convertValue(root, new TypeReference<List<HornQueryResponse.HornSlot>>() {}));
            return wrapper;
        }
        return objectMapper.treeToValue(root, HornQueryResponse.class);
    }

    private boolean parseAndPrint(Task task, Doctor doctor, String response, Map<String, String> roundAvailable) {
        try {
            HornQueryResponse result = parseHornResponse(response.trim());
            if (result.getCode() == null || result.getCode() != 0) {
                log.warn("业务失败: code={}, message={}（本轮查询中止，请更新 token 等请求头）",
                        result.getCode(), result.getMessage());
                return false;
            }
            List<HornQueryResponse.HornSlot> slots = result.getData();
            if (slots == null || slots.isEmpty()) {
                return true; // 无号源数据，不打印
            }
            roundSlots.addAndGet(slots.size());
            // 有号时段按日期分组（TreeMap 保证日期升序）
            Map<String, List<HornQueryResponse.HornSlot>> byDate = slots.stream()
                    .filter(this::isAvailable)
                    .collect(Collectors.groupingBy(HornQueryResponse.HornSlot::getMdtrtDate,
                            java.util.TreeMap::new, Collectors.toList()));
            for (Map.Entry<String, List<HornQueryResponse.HornSlot>> entry : byDate.entrySet()) {
                int total = entry.getValue().stream()
                        .mapToInt(HornQueryResponse.HornSlot::getBlncHornNum)
                        .sum();
                String detail = entry.getValue().stream()
                        .map(s -> s.getPrdBegntime() + "-" + s.getPrdEndtime() + " 余" + s.getBlncHornNum())
                        .collect(Collectors.joining(", "));
                log.info("★★★ 有号！医师: {} | 日期: {} | 可挂号源: {} [{}]",
                        doctor.name(), entry.getKey(), total, detail);
                roundAvailable.put(doctor.name() + "|" + entry.getKey(),
                        String.format("医师: %s | 日期: %s | 可挂号源: %d [%s]",
                                doctor.name(), entry.getKey(), total, detail));

                // 自动预约：启用时弹窗确认，用户点击「抢号」后提交预约
                if (bookingProperties.isAutoEnabled() && !entry.getValue().isEmpty()) {
                    HornQueryResponse.HornSlot firstSlot = entry.getValue().get(0);
                    String confirmMsg = String.format("医师: %s\n日期: %s\n时段: %s-%s\n余号: %d\n\n是否立即抢号？",
                            doctor.name(), entry.getKey(),
                            firstSlot.getPrdBegntime(), firstSlot.getPrdEndtime(),
                            firstSlot.getBlncHornNum());
                    log.info("自动预约已启用，弹窗确认: 医师={} 日期={} 时段={}-{}",
                            doctor.name(), entry.getKey(), firstSlot.getPrdBegntime(), firstSlot.getPrdEndtime());
                    boolean confirmed = notificationService.confirmBooking("发现号源 - 确认抢号", confirmMsg);
                    if (confirmed) {
                        log.info("用户确认抢号，提交预约: 医师={} 日期={}", doctor.name(), entry.getKey());
                        String bookingResult = bookingService.submitBooking(task, doctor, firstSlot);
                        if (bookingResult != null) {
                            log.info("预约结果: {}", bookingResult);
                            notificationService.notifyAvailable("自动预约结果",
                                    String.format("医师: %s | 日期: %s | 结果: %s",
                                            doctor.name(), entry.getKey(), bookingResult));
                        }
                    } else {
                        log.info("用户取消抢号: 医师={} 日期={}", doctor.name(), entry.getKey());
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("响应解析失败: {}（响应前缀: {}）", e.getMessage(),
                    response == null ? "null" : response.trim().substring(0, Math.min(48, response.trim().length())));
            return false;
        }
    }
}
