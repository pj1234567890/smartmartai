package com.smartmart.inventory.dto;
import lombok.*; import java.math.*; import java.time.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor public class InventoryResponse { private Long id, productId; private String productName; private Integer quantity, reorderLevel; private LocalDate expiryDate; private BigDecimal discountPercentage; private String aisle, shelf; }
