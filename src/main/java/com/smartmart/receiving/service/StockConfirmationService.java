package com.smartmart.receiving.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.inventory.entity.Inventory;
import com.smartmart.inventory.repository.InventoryRepository;
import com.smartmart.product.entity.Product;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.entity.*;
import com.smartmart.receiving.repository.GoodsReceiptRepository;
import com.smartmart.receiving.repository.ReceiptLineRepository;
import com.smartmart.receiving.repository.StockMovementRepository;
import com.smartmart.security.CurrentUserService;
import com.smartmart.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Commits accepted stock for a confirmed-eligible receipt, atomically and exactly once.
 *
 * The receipt row is locked pessimistically inside one transaction, so a second concurrent
 * confirm (or a retry) observes CONFIRMED and adds nothing. Inventory rows and batch rows are
 * locked before their quantities are changed, blocking concurrent adjustments. Stock movements
 * are created in the same transaction so they can never disagree with the quantities.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockConfirmationService {

    static final int DEFAULT_REORDER_LEVEL = 10;

    private final GoodsReceiptRepository receipts;
    private final ReceiptLineRepository lines;
    private final StockMovementRepository movements;
    private final InventoryRepository inventories;
    private final ProductRepository products;
    private final ProductBatchService batchService;
    private final ReconciliationService reconciliation;
    private final CurrentUserService currentUser;

    @Transactional
    public ReceiptResponses.ConfirmResult confirm(Long receiptId) {
        try {
            GoodsReceipt receipt = receipts.findByIdForUpdate(receiptId)
                    .orElseThrow(() -> BusinessException.notFound("Receipt not found"));

            // Idempotency: repeating a completed confirmation never adds stock again.
            if (receipt.getStatus() == ReceiptStatus.CONFIRMED) {
                return buildResult(receipt, List.of());
            }
            if (receipt.getStatus() != ReceiptStatus.READY_TO_CONFIRM) {
                throw BusinessException.conflict("Receipt " + receiptId + " is " + receipt.getStatus()
                        + " - resolve the open blockers before confirming");
            }

            List<ReceiptLine> receiptLines = lines.findByReceiptIdOrderByIdAsc(receiptId);
            List<String> blockers = reconciliation.blockers(receiptLines);
            if (!blockers.isEmpty()) {
                throw BusinessException.conflict("Receipt " + receiptId + " cannot be confirmed yet: " + blockers.get(0));
            }

            List<ReceiptResponses.CommittedStock> committed = new ArrayList<>();
            for (int i = 0; i < receiptLines.size(); i++) {
                final int lineNo = i + 1;
                ReceiptLine line = receiptLines.get(i);
                int accepted = line.getAcceptedQuantity() != null ? line.getAcceptedQuantity() : 0;
                if (accepted <= 0) continue; // rejected-only or zero-acceptance lines change no stock
                Product product = products.findById(line.getProduct().getId())
                        .orElseThrow(() -> BusinessException.badRequest("Unknown product on line " + lineNo
                                + " - it may have been deleted since the receipt was opened"));
                ProductBatch batch = batchService.getOrCreate(
                        product.getId(), line.getBatchCode(), line.getExpiryDate(), line.getUnitCost());
                ProductBatch locked = batchService.lock(batch.getId());

                Inventory inventory = inventories.findByProductIdForUpdate(product.getId()).orElse(null);
                if (inventory == null) {
                    // First stock for this product: create the aggregate so every existing feature keeps working.
                    inventory = inventories.save(Inventory.builder()
                            .product(product)
                            .quantity(0)
                            .reorderLevel(DEFAULT_REORDER_LEVEL)
                            .build());
                }
                inventory.setQuantity(inventory.getQuantity() + accepted);
                locked.setQuantityOnHand(locked.getQuantityOnHand() + accepted);
                if (line.getExpiryDate() != null && locked.getExpiryDate() == null) locked.setExpiryDate(line.getExpiryDate());
                if (line.getUnitCost() != null && locked.getUnitCost() == null) locked.setUnitCost(line.getUnitCost());

                User actor = currentUser.currentUser();
                movements.save(StockMovement.builder()
                        .product(product)
                        .batch(locked)
                        .type(MovementType.RECEIPT)
                        .quantityDelta(accepted)
                        .referenceType("GOODS_RECEIPT")
                        .referenceId(receipt.getId())
                        .actorUserId(actor != null ? actor.getId() : null)
                        .actorName(actor != null ? actor.getName() : null)
                        .reason("Confirmed receipt " + receipt.getId()
                                + (line.getResolutionReason() != null ? ": " + line.getResolutionReason() : ""))
                        .build());

                committed.add(ReceiptResponses.CommittedStock.builder()
                        .productId(product.getId())
                        .productName(product.getName())
                        .batchCode(locked.getBatchCode())
                        .expiryDate(locked.getExpiryDate())
                        .acceptedQuantity(accepted)
                        .newBatchQuantity(locked.getQuantityOnHand())
                        .newInventoryQuantity(inventory.getQuantity())
                        .build());
            }

            User confirmUser = currentUser.currentUser();
            receipt.setStatus(ReceiptStatus.CONFIRMED);
            receipt.setConfirmedByUserId(confirmUser != null ? confirmUser.getId() : null);
            receipt.setConfirmedByName(confirmUser != null ? confirmUser.getName() : null);
            receipt.setConfirmedAt(LocalDateTime.now());
            receipts.save(receipt);
            log.info("Confirmed receipt {} committing {} stock lines", receipt.getId(), committed.size());
            return buildResult(receipt, committed);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Everything rolls back together; the manager sees a clean failure and can retry safely.
            log.error("Receipt confirmation failed and rolled back", e);
            throw BusinessException.conflict("Could not confirm the receipt - the transaction was rolled back. Please retry.");
        }
    }

    @Transactional
    public List<ReceiptResponses.CommittedStock> movementsFor(Long receiptId) {
        return movements.findByReferenceTypeAndReferenceIdOrderByIdAsc("GOODS_RECEIPT", receiptId).stream()
                .map(m -> ReceiptResponses.CommittedStock.builder()
                        .productId(m.getProduct().getId())
                        .productName(m.getProduct().getName())
                        .batchCode(m.getBatch() != null ? m.getBatch().getBatchCode() : null)
                        .expiryDate(m.getBatch() != null ? m.getBatch().getExpiryDate() : null)
                        .acceptedQuantity(m.getQuantityDelta())
                        .build())
                .toList();
    }

    private ReceiptResponses.ConfirmResult buildResult(GoodsReceipt receipt, List<ReceiptResponses.CommittedStock> committed) {
        List<ReceiptResponses.CommittedStock> result = committed;
        if (receipt.getStatus() == ReceiptStatus.CONFIRMED && result.isEmpty()) {
            // Idempotent replay: surface the movements already committed earlier.
            try {
                result = movementsFor(receipt.getId());
            } catch (BusinessException ignored) { /* keep the raw replay empty */ }
        }
        return ReceiptResponses.ConfirmResult.builder()
                .receiptId(receipt.getId())
                .reference(receipt.getSupplierName() != null
                        ? "RCPT-" + receipt.getId() + " (" + receipt.getSupplierName() + ")"
                        : "RCPT-" + receipt.getId())
                .confirmedAt(receipt.getConfirmedAt())
                .committedStock(result)
                .build();
    }
}
