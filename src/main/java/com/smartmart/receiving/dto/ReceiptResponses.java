package com.smartmart.receiving.dto;

import com.smartmart.receiving.entity.*;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ReceiptResponses {
    private ReceiptResponses() {}

    @Builder
    public record ReceiptSummary(
            Long id,
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate,
            String purchaseOrderRef,
            ReceiptStatus status,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Integer cartonCount,
            Integer scannedCartonCount,
            Integer unresolvedDiscrepancies
    ) {}

    @Builder
    public record DocumentInfo(
            Long id,
            String originalFilename,
            String sourceType,
            ReceiptDocument.ProcessingStatus processingStatus,
            String warnings,
            String error,
            LocalDateTime uploadedAt
    ) {}

    @Builder
    public record ReceiptLine(
            Long id,
            String description,
            String originalDescription,
            String sku,
            Long productId,
            String productName,
            MatchStatus matchStatus,
            Integer invoiceQuantity,
            Integer originalQuantity,
            String unitOfMeasure,
            Integer unitsPerCarton,
            Integer expectedUnits,
            String conversionError,
            Integer scannedQuantity,
            Integer acceptedQuantity,
            Integer rejectedQuantity,
            BigDecimal unitCost,
            BigDecimal lineTotal,
            String batchCode,
            Long batchId,
            LocalDate expiryDate,
            LocalDate originalExpiryDate,
            DiscrepancyStatus discrepancyStatus,
            String resolutionReason,
            Integer cartonsScanned,
            Integer cartonDeclaredUnits
    ) {}

    @Builder
    public record ReceiptCarton(
            Long id,
            String cartonId,
            Long lineId,
            String productName,
            String batchCode,
            Integer declaredUnits,
            Integer acceptedUnits,
            CartonStatus status,
            LocalDateTime scanTimestamp,
            String reason
    ) {}

    @Builder
    public record Label(
            String cartonId,
            String pngBase64,
            Integer unitsPerCarton
    ) {}

    @Builder
    public record Reconciliation(
            Integer scannedCartons,
            Integer declaredUnits,
            Integer expectedUnits,
            Boolean readyToConfirm,
            List<String> blockers,
            List<ReceiptLine> lines
    ) {}

    @Builder
    public record ConfirmResult(
            Long receiptId,
            String reference,
            LocalDateTime confirmedAt,
            List<CommittedStock> committedStock
    ) {}

    @Builder
    public record CommittedStock(
            Long productId,
            String productName,
            String batchCode,
            LocalDate expiryDate,
            Integer acceptedQuantity,
            Integer newBatchQuantity,
            Integer newInventoryQuantity
    ) {}
}
