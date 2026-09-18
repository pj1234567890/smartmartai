package com.smartmart.product.repository;

import com.smartmart.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("SELECT p FROM Product p WHERE " +
            "(:name IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
            "(:brand IS NULL OR LOWER(p.brand) LIKE LOWER(CONCAT('%', :brand, '%'))) AND " +
            "(:category IS NULL OR LOWER(p.category) LIKE LOWER(CONCAT('%', :category, '%')))")
    Page<Product> findWithFilters(
            @Param("name") String name,
            @Param("brand") String brand,
            @Param("category") String category,
            Pageable pageable);

    Optional<Product> findByName(String name);
}