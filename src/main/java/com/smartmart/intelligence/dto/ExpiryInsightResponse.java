package com.smartmart.intelligence.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
public class ExpiryInsightResponse {
    private Long productId; private String productName; private Integer quantity; private Integer daysUntilExpiry;
    private String status; private String recommendedAction; private BigDecimal recommendedDiscount; private String reason;
}
