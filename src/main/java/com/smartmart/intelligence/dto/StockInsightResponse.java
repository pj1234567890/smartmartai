package com.smartmart.intelligence.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
public class StockInsightResponse {
    private Long productId; private String productName; private Integer currentStock; private Integer reorderLevel;
    private BigDecimal averageDailySales; private BigDecimal estimatedDaysRemaining; private String riskLevel;
    private Integer suggestedReorderQuantity; private Integer wishlistDemand; private String reason;
}
