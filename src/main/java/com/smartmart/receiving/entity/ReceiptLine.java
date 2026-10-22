package com.smartmart.receiving.entity;

import com.smartmart.product.entity.Product;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One invoice line on a goods receipt, plus its reconciliation state. */
@Entity
@Table(name = "receipt_lines", indexes = {
        @Index(name = "idx_receipt_line_receipt", columnList = "receipt_id"),
        @Index(name = "idx_receipt_line_product", columnList = "product_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private GoodsReceipt receipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /** Description exactly as printed on the invoice. */
    @Column(nullable = false, length = 500)
    private String description;

    @Size(max = 100)
    @Column(length = 100)
    private String sku;

    /** Invoice quantity as extracted or corrected by the manager (null = unreadable/missing). */
    private Integer invoiceQuantity;

    @Size(max = 30)
    @Column(length = 30)
    private String unitOfMeasure;

    /** Explicitly verified stock units per invoice package/carton; null means conversion is unresolved. */
    @Column(name = "units_per_carton")
    private Integer unitsPerCarton;

    /** Invoice units converted to stock units (only when conversion is explicit and valid); null when unknown. */
    @Column(name = "expected_units")
    private Integer expectedUnits;

    /** Sum of declared units of all scanned (+verified) cartons; recomputed only by the backend. */
    @Column(name = "scanned_quantity")
    private Integer scannedQuantity;

    /** Manager-approved stock quantity (defaults to accepted scanned units when confirmed). */
    @Column(name = "accepted_quantity")
    private Integer acceptedQuantity;

    /** Quantity explicitly marked damaged/rejected by the manager; never entered available stock. */
    @Column(name = "rejected_quantity")
    private Integer rejectedQuantity;

    private BigDecimal unitCost;

    private BigDecimal lineTotal;

    @Size(max = 100)
    @Column(name = "batch_code", length = 100)
    private String batchCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ProductBatch batch;

    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchStatus matchStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscrepancyStatus discrepancyStatus;

    /** Where the line came from: automated invoice extraction or manual manager entry. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LineSource source;

    /** Reason the manager recorded when accepting a discrepancy or shortage. */
    @Column(name = "resolution_reason", length = 500)
    private String resolutionReason;

    // ---- original extracted values, preserved for audit when the manager edits fields ----
    @Column(name = "original_description", length = 500)
    private String originalDescription;

    @Column(name = "original_quantity")
    private Integer originalQuantity;

    @Column(name = "original_unit_cost")
    private BigDecimal originalUnitCost;

    @Column(name = "original_expiry_date")
    private LocalDate originalExpiryDate;

    @Column(name = "auto_matched")
    private Boolean autoMatched;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
