package com.art.studio.repository;

import java.util.List;
import java.util.Optional;

import com.art.studio.entity.ArtMaterial;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface ArtMaterialRepository extends JpaRepository<ArtMaterial, Long> {

    java.util.Optional<ArtMaterial> findByName(String name);

    List<ArtMaterial> findByCategory(String category);

    List<ArtMaterial> findByStatus(String status);

    List<ArtMaterial> findByCategoryAndStatus(String category, String status);

    /** 领用扣减 / 归还入库时锁画材行，串行化同一种画材的库存变化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT m FROM ArtMaterial m WHERE m.id = :id")
    Optional<ArtMaterial> findByIdForUpdate(@Param("id") Long id);
}
