package com.smartmart.receiving.repository;

import com.smartmart.receiving.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByReferenceTypeAndReferenceIdOrderByIdAsc(String referenceType, Long referenceId);

    List<StockMovement> findTop50ByOrderByCreatedAtDesc();
}
