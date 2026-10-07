package com.art.studio.dto;

/** 看板汇总行：按「画材 + 学员」聚合，明细展开时仍复用 MaterialUseView 的同一口径。 */
public class MaterialBoardSummary {

    public Long materialId;
    public String materialName;
    public String materialCategory;
    public String materialUnit;

    public Long studentId;
    public String studentName;
    public boolean studentDeleted;

    public Integer issueQty;
    public Integer returnQty;
    public Integer netQty;
    public Integer currentStock;
    public Integer recordCount;
}
