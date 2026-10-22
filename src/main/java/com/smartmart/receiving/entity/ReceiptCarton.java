package com.smartmart.receiving.entity;

import com.smartmart.product.entity.Product;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One physical carton, created when the manager prints its QR label, scanned once per receipt.
 * The globally unique carton id enforces the single-use policy: a carton consumed by a confirmed
 * receipt can never be counted again, and a carton label belongs to exactly one receipt.
 */
@Entity
@Table(name = "receipt_cartons",
        uniqueConstraints = @UniqueConstraint(name = "uq_carton_id", columnNames = "carton_id"),
        indexes = {
                @Index(name = "idx_carton_receipt", columnList = "receipt_id"),
                @Index(name = "idx_carton_line", columnList = "line_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptCarton {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Globally unique carton identifier, e.g. CTN-0001042. */
    @Column(name = "carton_id", nullable = false, unique = true, length = 50)
    private String cartonId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private GoodsReceipt receipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id", nullable = false)
    private ReceiptLine line;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ProductBatch batch;

    /** Declared units per carton at label generation time (from the QR payload). */
    @Column(name = "declared_units", nullable = false)
    private Integer declaredUnits;

    /** Physically verified units (null = unverified, declared quantity stands). */
    @Column(name = "accepted_units")
    private Integer acceptedUnits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CartonStatus status;

    private LocalDateTime scanTimestamp;

    /** Discrepancy/damage reason recorded by the manager. */
    @Column(length = 500)
    private String reason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
