package com.smartmart.receiving.repository;

import com.smartmart.receiving.entity.ProductBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductBatchRepository extends JpaRepository<ProductBatch, Long> {

    Optional<ProductBatch> findByProductIdAndBatchCode(Long productId, String batchCode);

    List<ProductBatch> findByProductId(Long productId);

    /** Pessimistic lock so concurrent confirmations cannot lose stock updates. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ProductBatch b where b.id = :id")
    Optional<ProductBatch> findByIdForUpdate(@Param("id") Long id);
}
