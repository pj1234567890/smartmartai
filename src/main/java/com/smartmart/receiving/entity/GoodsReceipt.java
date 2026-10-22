package com.smartmart.receiving.entity;

import com.smartmart.product.entity.Product;
import com.smartmart.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One supplier delivery being received. */
@Entity
@Table(name = "goods_receipts", indexes = {
        @Index(name = "idx_receipt_status", columnList = "status"),
        @Index(name = "idx_receipt_created_by", columnList = "created_by")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoodsReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Size(max = 255)
    private String supplierName;

    @Size(max = 100)
    private String invoiceNumber;

    private LocalDate invoiceDate;

    @Size(max = 100)
    @Column(name = "purchase_order_ref")
    private String purchaseOrderRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReceiptStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "confirmed_by")
    private Long confirmedByUserId;

    @Column(name = "confirmed_by_name", length = 255)
    private String confirmedByName;

    private LocalDateTime confirmedAt;

    @Column(length = 1000)
    private String notes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
