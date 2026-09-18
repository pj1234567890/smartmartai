package com.smartmart.inventory.entity;

import com.smartmart.product.entity.Product;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity @Table(name="inventory", uniqueConstraints=@UniqueConstraint(columnNames="product_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Inventory {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="product_id") private Product product;
 @Column(nullable=false) private Integer quantity;
 @Column(nullable=false) private Integer reorderLevel;
 private LocalDate expiryDate;
 private java.math.BigDecimal discountPercentage;
}
