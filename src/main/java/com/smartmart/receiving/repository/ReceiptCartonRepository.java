package com.smartmart.receiving.repository;

import com.smartmart.receiving.entity.CartonStatus;
import com.smartmart.receiving.entity.ReceiptCarton;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReceiptCartonRepository extends JpaRepository<ReceiptCarton, Long> {

    /** The unique constraint on carton_id makes this lookup the single source of truth for carton reuse. */
    Optional<ReceiptCarton> findByCartonId(String cartonId);

    List<ReceiptCarton> findByReceiptIdOrderByIdAsc(Long receiptId);

    List<ReceiptCarton> findByLineIdOrderByIdAsc(Long lineId);

    List<ReceiptCarton> findByReceiptIdAndStatusIn(Long receiptId, List<CartonStatus> statuses);

    boolean existsByCartonId(String cartonId);
}
