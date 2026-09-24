package com.willem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 预约配置：自动抢号时使用的就诊人和医院信息
 */
@Data
@ConfigurationProperties(prefix = "booking")
public class BookingProperties {

    /** 是否启用自动预约 */
    private boolean autoEnabled = false;

    /** 就诊人 ID */
    private String mdtrterId;

    /** 定点医疗机构 ID */
    private String fixmedinsId;

    /** 定点医疗机构编码 */
    private String fixmedinsCode;

    /** 定点医疗机构名称 */
    private String fixmedinsName;

    /** 数据源 */
    private String dataSouc = "1";

    /** 类别 */
    private String caty = "000201";

    /** 是否接收短信通知（0=否，1=是） */
    private String needSms = "0";
}
