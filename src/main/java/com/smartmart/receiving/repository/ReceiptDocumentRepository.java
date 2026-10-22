package com.smartmart.receiving.repository;

import com.smartmart.receiving.entity.ReceiptDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReceiptDocumentRepository extends JpaRepository<ReceiptDocument, Long> {

    List<ReceiptDocument> findByReceiptIdOrderByIdDesc(Long receiptId);

    /** Most recent document for a receipt (managers may re-upload). */
    Optional<ReceiptDocument> findFirstByReceiptIdOrderByIdDesc(Long receiptId);
}
