package com.smartmart.receiving.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class ReceiptRequests {
    private ReceiptRequests() {}

    public record CreateReceipt(
            @Size(max = 255) String supplierName,
            @Size(max = 100) String invoiceNumber,
            LocalDate invoiceDate,
            @Size(max = 100) String purchaseOrderRef,
            @Size(max = 1000) String notes
    ) {}

    public record UpdateReceipt(
            @Size(max = 255) String supplierName,
            @Size(max = 100) String invoiceNumber,
            LocalDate invoiceDate,
            @Size(max = 100) String purchaseOrderRef,
            @Size(max = 1000) String notes
    ) {}

    public record AddLine(
            @NotBlank @Size(max = 500) String description,
            @Positive Long productId,
            @Size(max = 100) String sku,
            @PositiveOrZero Integer invoiceQuantity,
            @Size(max = 30) String unitOfMeasure,
            @Positive Integer unitsPerCarton,
            @PositiveOrZero BigDecimal unitCost,
            @PositiveOrZero BigDecimal lineTotal,
            @Size(max = 100) String batchCode,
            LocalDate expiryDate
    ) {}

    public record UpdateLine(
            @Positive Long productId,
            @PositiveOrZero Integer invoiceQuantity,
            @Size(max = 30) String unitOfMeasure,
            @Positive Integer unitsPerCarton,
            @PositiveOrZero BigDecimal unitCost,
            @PositiveOrZero BigDecimal lineTotal,
            @Size(max = 100) String batchCode,
            LocalDate expiryDate,
            /** Manager-approved stock quantity; must be set (or the line removed) before confirmation. */
            @PositiveOrZero Integer acceptedQuantity,
            /** Damaged/rejected units, excluded from available stock. */
            @PositiveOrZero Integer rejectedQuantity,
            @Size(max = 500) String resolutionReason
    ) {}

    public record ScanCarton(@NotBlank @Size(max = 4000) String code) {}

    /** Physically verified carton result: often equal to the declared units, otherwise an audited shortage/rejection. */
    public record VerifyCarton(
            @NotNull @PositiveOrZero Integer acceptedUnits,
            Boolean rejected,
            @Size(max = 500) String reason
    ) {}

    public record GenerateLabels(
            @NotNull @Positive Long lineId,
            @NotNull @Positive Integer unitsPerCarton,
            @Min(1) @Max(20) Integer count
    ) {}

    public record CreateBatch(
            @NotNull @Positive Long productId,
            @NotBlank @Size(max = 100) String batchCode,
            LocalDate expiryDate,
            @PositiveOrZero BigDecimal unitCost
    ) {}
}
