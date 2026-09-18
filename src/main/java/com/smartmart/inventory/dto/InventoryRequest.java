package com.smartmart.inventory.dto;
import jakarta.validation.constraints.*; import lombok.*; import java.math.BigDecimal; import java.time.LocalDate;
@Data public class InventoryRequest { @NotNull private Long productId; @NotNull @Min(0) private Integer quantity; @NotNull @Min(0) private Integer reorderLevel; private LocalDate expiryDate; @DecimalMin("0.0") @DecimalMax("100.0") private BigDecimal discountPercentage=BigDecimal.ZERO; }
