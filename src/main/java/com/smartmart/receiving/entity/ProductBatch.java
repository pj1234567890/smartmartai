package com.smartmart.receiving.entity;

import com.smartmart.product.entity.Product;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Inventory belonging to a particular product and batch (lot). */
@Entity
@Table(name = "product_batches",
        uniqueConstraints = @UniqueConstraint(name = "uq_batch_product_code", columnNames = {"product_id", "batch_code"}),
        indexes = {@Index(name = "idx_batch_product", columnList = "product_id")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "batch_code", nullable = false, length = 100)
    private String batchCode;

    private LocalDate expiryDate;

    /** Never negative; committed only by confirmation of a validated receipt. */
    @Column(nullable = false)
    private Integer quantityOnHand;

    private BigDecimal unitCost;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public static String normalize(String batchCode) {
        return batchCode == null ? null : batchCode.trim();
    }
}
