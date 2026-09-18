package com.smartmart.product.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {

    private Long id;
    private String name;
    private String brand;
    private String category;
    private BigDecimal price;
    private String aisle;
    private String shelf;
    private String description;
    private LocalDateTime createdAt;
    // Populated when catalogue results are enriched with live inventory.
    private Integer availableQuantity;
    private BigDecimal discountPercentage;
}
