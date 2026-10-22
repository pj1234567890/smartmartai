package com.smartmart.receiving.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.receiving.entity.ProductBatch;
import com.smartmart.receiving.repository.ProductBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Get-or-create semantics for product batches: the same batch arriving through several
 * deliveries reuses one record instead of creating duplicates. Concurrency is guarded by
 * the database unique constraint on (product_id, batch_code).
 */
@Service
@RequiredArgsConstructor
public class ProductBatchService {

    private final ProductBatchRepository batches;
    private final ProductRepository products;

    /** Pessimistic lock used by stock confirmation so concurrent commitments cannot lose updates. */
    @Transactional
    public ProductBatch lock(Long id) {
        return batches.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Product batch not found"));
    }

    @Transactional
    public ProductBatch getOrCreate(Long productId, String batchCode, LocalDate expiryDate, BigDecimal unitCost) {
        if (productId == null) throw BusinessException.badRequest("A product is required to resolve a batch");
        String code = ProductBatch.normalize(batchCode);
        if (code == null || code.isBlank()) throw BusinessException.badRequest("A batch code is required");
        if (code.length() > 100) throw BusinessException.badRequest("Batch code too long");
        return batches.findByProductIdAndBatchCode(productId, code).orElseGet(() -> {
            batches.flush();
            ProductBatch batch = ProductBatch.builder()
                    .product(products.getReferenceById(productId))
                    .batchCode(code)
                    .expiryDate(expiryDate)
                    .unitCost(unitCost)
                    .quantityOnHand(0)
                    .build();
            try {
                return batches.saveAndFlush(batch);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Another transaction created the same batch first - reuse it.
                return batches.findByProductIdAndBatchCode(productId, code).orElseThrow(() ->
                        BusinessException.conflict("Could not resolve batch " + code));
            }
        });
    }
}
