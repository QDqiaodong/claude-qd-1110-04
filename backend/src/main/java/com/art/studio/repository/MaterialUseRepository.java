package com.art.studio.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.art.studio.entity.MaterialUse;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface MaterialUseRepository extends JpaRepository<MaterialUse, Long>, JpaSpecificationExecutor<MaterialUse> {

    List<MaterialUse> findByStudentIdOrderByUseDateDescIdDesc(Long studentId);

    List<MaterialUse> findByRelatedUseIdInAndRecordType(Collection<Long> ids, String recordType);

    /** 归还扣库存时先锁原领用记录，防止同一笔领用被并发超额归还。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT u FROM MaterialUse u WHERE u.id = :id AND u.recordType = '领用'")
    Optional<MaterialUse> findIssueByIdForUpdate(@Param("id") Long id);
}
