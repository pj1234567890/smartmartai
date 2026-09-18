package com.smartmart.intelligence.service;

import com.smartmart.intelligence.dto.ExpiryInsightResponse;
import com.smartmart.intelligence.dto.StockInsightResponse;
import com.smartmart.inventory.entity.Inventory;
import com.smartmart.inventory.repository.InventoryRepository;
import com.smartmart.order.repository.CustomerOrderRepository;
import com.smartmart.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service @RequiredArgsConstructor
public class InventoryIntelligenceService {
    private final InventoryRepository inventory; private final CustomerOrderRepository orders; private final WishlistRepository wishlists;

    @Transactional(readOnly = true) public List<StockInsightResponse> stockOutRisks() { return inventory.findAll().stream().map(this::stock).sorted(Comparator.comparingInt((StockInsightResponse x) -> riskPriority(x.getRiskLevel())).thenComparing(StockInsightResponse::getEstimatedDaysRemaining, Comparator.nullsLast(Comparator.naturalOrder()))).toList(); }
    @Transactional(readOnly = true) public List<StockInsightResponse> reorderRecommendations() { return stockOutRisks().stream().filter(x -> x.getSuggestedReorderQuantity() > 0).toList(); }
    @Transactional(readOnly = true) public List<ExpiryInsightResponse> expiryInsights() { return inventory.findAll().stream().filter(i -> i.getExpiryDate()!=null).map(this::expiry).sorted(Comparator.comparing(ExpiryInsightResponse::getDaysUntilExpiry)).toList(); }
    private StockInsightResponse stock(Inventory i) {
        LocalDateTime from=LocalDateTime.now().minusDays(30); int sold=orders.findAllWithItems().stream().filter(o->o.getCreatedAt()!=null && o.getCreatedAt().isAfter(from)).flatMap(o->o.getItems().stream()).filter(x->x.getProduct().getId().equals(i.getProduct().getId())).mapToInt(x->x.getQuantity()).sum();
        BigDecimal velocity=BigDecimal.valueOf(sold).divide(BigDecimal.valueOf(30),2,RoundingMode.HALF_UP); BigDecimal days=velocity.signum()>0?BigDecimal.valueOf(i.getQuantity()).divide(velocity,1,RoundingMode.HALF_UP):null;
        int demand=(int) wishlists.findAll().stream().filter(w->w.getProduct().getId().equals(i.getProduct().getId())).count(); String risk;
        if(i.getQuantity()==0) risk="OUT_OF_STOCK"; else if(days!=null && days.compareTo(BigDecimal.valueOf(7))<=0) risk="HIGH"; else if(i.getQuantity()<=i.getReorderLevel() || demand>=3) risk="MEDIUM"; else risk="LOW";
        int target=Math.max(i.getReorderLevel()*3, velocity.multiply(BigDecimal.valueOf(21)).setScale(0,RoundingMode.CEILING).intValue()+demand); int reorder=(risk.equals("LOW"))?0:Math.max(0,target-i.getQuantity());
        String reason=velocity.signum()>0?"Based on the last 30 days of sales and current stock.":"No recent sales history; based on stock, reorder level, and wishlist demand.";
        return StockInsightResponse.builder().productId(i.getProduct().getId()).productName(i.getProduct().getName()).currentStock(i.getQuantity()).reorderLevel(i.getReorderLevel()).averageDailySales(velocity).estimatedDaysRemaining(days).riskLevel(risk).suggestedReorderQuantity(reorder).wishlistDemand(demand).reason(reason).build();
    }
    private ExpiryInsightResponse expiry(Inventory i) { int days=(int)ChronoUnit.DAYS.between(LocalDate.now(),i.getExpiryDate()); String status=days<0?"EXPIRED":days<=3?"URGENT":days<=14?"EXPIRING_SOON":"SAFE"; BigDecimal velocity=stock(i).getAverageDailySales(); BigDecimal recommended=BigDecimal.ZERO; String action="MONITOR";
        if(days<0){action="REMOVE_FROM_SALE";} else if(days<=3 && i.getQuantity()>0){action="PRIORITIZE_SALE"; recommended=new BigDecimal("25");} else if(days<=14 && i.getQuantity()>Math.max(1, velocity.intValue()*Math.max(days,1))){action="DISCOUNT";recommended=new BigDecimal("15");}
        if(i.getQuantity()<=i.getReorderLevel()) { recommended=BigDecimal.ZERO; if(!status.equals("EXPIRED")) action="WARN"; }
        return ExpiryInsightResponse.builder().productId(i.getProduct().getId()).productName(i.getProduct().getName()).quantity(i.getQuantity()).daysUntilExpiry(days).status(status).recommendedAction(action).recommendedDiscount(recommended).reason("Calculated from expiry date, on-hand quantity, recent sales velocity, and reorder level.").build(); }

    private int riskPriority(String riskLevel) {
        if ("OUT_OF_STOCK".equals(riskLevel)) return 0;
        if ("HIGH".equals(riskLevel)) return 1;
        if ("MEDIUM".equals(riskLevel)) return 2;
        if ("LOW".equals(riskLevel)) return 3;
        return 99;
    }
}
