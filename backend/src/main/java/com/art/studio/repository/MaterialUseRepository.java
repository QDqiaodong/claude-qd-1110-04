package com.art.studio.repository;

import java.time.LocalDate;
import java.util.List;

import com.art.studio.entity.MaterialUse;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialUseRepository extends JpaRepository<MaterialUse, Long> {

    List<MaterialUse> findByStudentId(Long studentId);

    /**
     * 消耗看板与领用记录页共用的同一套筛选口径：
     * 日期按 use_date（业务日期，本地日历日）闭区间 [from, to] 匹配，
     * 结束日期当天整条计入 —— 跨月查询不会漏掉月末记录；
     * 材料 / 学员为 null 时不限。调用方负责把空日期边界展开为全开区间。
     */
    @Query("select u from MaterialUse u"
            + " where u.useDate >= :from and u.useDate <= :to"
            + " and (:materialId is null or u.materialId = :materialId)"
            + " and (:studentId is null or u.studentId = :studentId)"
            + " order by u.useDate asc, u.id asc")
    List<MaterialUse> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("materialId") Long materialId, @Param("studentId") Long studentId);
}
