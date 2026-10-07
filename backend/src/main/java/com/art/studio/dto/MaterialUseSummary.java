package com.art.studio.dto;

/**
 * 画材消耗看板汇总行：按「画材 × 学员」分组，
 * 由与领用记录列表完全相同的一批记录在后端聚合而成（同一套状态口径），
 * 页面可从汇总行展开到对应的明细记录，两边数字必然对得上。
 */
public class MaterialUseSummary {

    public Long materialId;
    public String materialName;
    public String category;
    public String unit;
    /** 在库 / 停用 */
    public String materialStatus;
    /** 当前余量：画材台账的实时库存（与库存页同一数字，不随日期范围变化） */
    public Integer currentStock;

    public Long studentId;
    /** 学员姓名快照（学员已删除时仍显示原姓名） */
    public String studentName;
    /** 学员档案是否已删除 */
    public boolean studentDeleted;

    /** 领用数量（范围内 kind=领用 的合计） */
    public long checkoutQty;
    /** 归还数量（范围内 kind=归还 的合计） */
    public long returnQty;
    /** 净消耗 = 领用数量 - 归还数量 */
    public long netQty;
}
