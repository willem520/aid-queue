package com.willem.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * quryOtpHorn 号源查询接口响应
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HornQueryResponse {

    /** 业务状态码，0=成功 */
    private Integer code;
    /** success / error */
    private String type;
    /** 提示信息 */
    private String message;
    /** 号源列表，失败时为 null */
    private List<HornSlot> data;

    /**
     * 单个号源时段
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HornSlot {
        /** 诊室+医生名称，如：产科诊室贾艳菊 */
        private String howlName;
        /** 就诊日期，如：2026-08-21 */
        private String mdtrtDate;
        /** 时段描述，如：南开上午 */
        private String prd;
        /** 开始时间，如：10:00 */
        private String prdBegntime;
        /** 结束时间，如：10:30 */
        private String prdEndtime;
        /** 号别，如：主任医师 */
        private String hornType;
        /** 诊查费（元） */
        private Integer medfeeSumamt;
        /** 医院放号总数，-3 表示未正式放号/待定 */
        private Integer hospHornNum;
        /** 剩余号源数，>0 表示可挂号 */
        private Integer blncHornNum;
        /** 预约挂号须知（HTML） */
        private String ordrRgstNotc;
        /** 当日挂号须知（HTML） */
        private String todayRgstNotc;
    }
}
