package com.smartmart.inventory.repository;
import com.smartmart.inventory.entity.Inventory; import org.springframework.data.jpa.repository.*; import java.time.LocalDate; import java.util.*;
public interface InventoryRepository extends JpaRepository<Inventory,Long> {
 Optional<Inventory> findByProductId(Long productId);
 @Query("select i from Inventory i where i.quantity <= i.reorderLevel") List<Inventory> findLowStock();
 List<Inventory> findByExpiryDateBetween(LocalDate from, LocalDate to);
 List<Inventory> findByExpiryDateBefore(LocalDate date);
 List<Inventory> findByDiscountPercentageGreaterThan(java.math.BigDecimal amount);
}
