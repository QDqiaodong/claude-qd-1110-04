package com.art.studio.dto;

import java.util.List;

/**
 * 画材消耗看板响应。汇总与明细来自同一查询结果，避免接口刷新时两个口径短暂不一致；
 * emptyRange=true 表示开始日期晚于结束日期，此时固定返回空结果。
 */
public class MaterialBoardView {

    public List<MaterialBoardSummary> summaries;
    public List<MaterialUseView> records;
    public boolean emptyRange;
}
