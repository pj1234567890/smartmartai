package com.smartmart.receiving.repository;

import com.smartmart.receiving.entity.GoodsReceipt;
import com.smartmart.receiving.entity.ReceiptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    Page<GoodsReceipt> findByStatusOrderByIdDesc(ReceiptStatus status, Pageable pageable);

    Page<GoodsReceipt> findAllByOrderByIdDesc(Pageable pageable);

    /** Pessimistic lock used to serialise confirmations and prevent double stock commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from GoodsReceipt r where r.id = :id")
    Optional<GoodsReceipt> findByIdForUpdate(Long id);
}
