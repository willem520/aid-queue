package com.willem.model;

/**
 * 医生查询信息：新增医生时在 HornQueryService 的 DOCTORS 列表中添加
 *
 * @param name         医生姓名，如：贾艳菊
 * @param inhospDrCodg 医生编码，如：0328
 * @param hornType     号别，如：主任医师
 */
public record Doctor(String name, String inhospDrCodg, String hornType) {
}
