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

    /** 不建立到 student 的外键：学员物理删除后，台账与姓名快照仍必须保留。 */
    @Column(name = "student_id", nullable = false)
    public Long studentId;

    @Column(name = "student_name", nullable = false, length = 64)
    public String studentName;

    @Column(name = "material_id", nullable = false)
    public Long materialId;

    @Column(name = "record_type", nullable = false, length = 16)
    public String recordType;

    @Column(name = "qty", nullable = false)
    public Integer qty;

    @Column(name = "use_date", nullable = false)
    public LocalDate useDate;

    /** 归还记录指向原领用记录；领用记录为空。 */
    @Column(name = "related_use_id")
    public Long relatedUseId;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
