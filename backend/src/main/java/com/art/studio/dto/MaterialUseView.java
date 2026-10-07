package com.art.studio.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 领用 / 归还记录视图：记录本体 + 学员姓名快照与画材快照。
 * 学员姓名一律取台账上的快照（学员删除 / 改名后仍显示原姓名），
 * studentDeleted 标记该学员档案当前是否已不存在，供页面打「已删除」标。
 */
public class MaterialUseView {

    public Long id;
    public Long studentId;
    /** 姓名快照（落库时的学员姓名） */
    public String studentName;
    /** 学员档案是否已删除（删除后历史记录仍可追溯） */
    public boolean studentDeleted;
    public Long materialId;
    public String materialName;
    public String materialUnit;
    /** 领用 / 归还 */
    public String kind;
    public Integer qty;
    public LocalDate useDate;
    public LocalDateTime createdAt;
}
