package com.art.studio.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "material_use")
public class MaterialUse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "student_id", nullable = false)
    public Long studentId;

    /** 学员姓名快照：学员被删除或改名后，历史台账仍显示领用时的原姓名 */
    @Column(name = "student_name", length = 64)
    public String studentName;

    @Column(name = "material_id", nullable = false)
    public Long materialId;

    /** 领用 / 归还 */
    @Column(name = "kind", nullable = false, length = 16)
    public String kind;

    @Column(name = "qty", nullable = false)
    public Integer qty;

    @Column(name = "use_date", nullable = false)
    public LocalDate useDate;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
