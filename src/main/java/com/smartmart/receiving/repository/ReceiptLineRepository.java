package com.smartmart.receiving.repository;

import com.smartmart.receiving.entity.ReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReceiptLineRepository extends JpaRepository<ReceiptLine, Long> {

    List<ReceiptLine> findByReceiptIdOrderByIdAsc(Long receiptId);

    List<ReceiptLine> findByReceiptIdAndProductId(Long receiptId, Long productId);

    boolean existsByReceiptIdAndProductId(Long receiptId, Long productId);
}
