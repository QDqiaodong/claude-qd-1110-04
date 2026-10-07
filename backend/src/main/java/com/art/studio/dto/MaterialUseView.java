package com.art.studio.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 画材领用 / 归还台账视图。学员姓名使用落库时的快照，学员档案被删除后仍可追溯；
 * 画材名称取当前台账，库存余额由画材表统一维护。
 */
public class MaterialUseView {

    public Long id;

    public Long studentId;
    public String studentName;
    public String studentStatus;
    public boolean studentDeleted;

    public Long materialId;
    public String materialName;
    public String materialCategory;
    public String materialUnit;

    /** 领用 / 归还 */
    public String recordType;
    public Integer qty;
    public LocalDate useDate;

    /** 是否属于查询日期范围；false 表示为了展开归还来源而附带的范围外原领用记录。 */
    public boolean inRange = true;

    /** 归还记录对应的原领用记录 id */
    public Long relatedUseId;
    public LocalDate sourceUseDate;

    /** 领用记录累计已归还数量与尚未归还数量（按全部历史归还计算，不受查询区间限制） */
    public Integer returnedQty;
    public Integer outstandingQty;

    public LocalDateTime createdAt;
}
