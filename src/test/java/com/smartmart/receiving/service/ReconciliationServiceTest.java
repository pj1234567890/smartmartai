package com.smartmart.receiving.service;

import com.smartmart.product.entity.Product;
import com.smartmart.receiving.entity.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation math must be backend-owned: scanned/verified/rejected carton states decide
 * the quantities, never frontend hints. The repositories are unused by these pure methods.
 */
class ReconciliationServiceTest {

    private final ReconciliationService service = new ReconciliationService(null, null);

    private ReceiptLine line(ReceiptStatus unused, Integer invoiceQuantity, String uom, Integer unitsPerCarton,
                             Integer accepted, Integer rejected) {
        return ReceiptLine.builder()
                .product(Product.builder().id(1L).name("Toned Milk 1L").build())
                .description("Toned Milk 1L")
                .matchStatus(MatchStatus.SKU)
                .invoiceQuantity(invoiceQuantity)
                .unitOfMeasure(uom)
                .unitsPerCarton(unitsPerCarton)
                .acceptedQuantity(accepted)
                .rejectedQuantity(rejected)
                .build();
    }

    private ReceiptCarton carton(CartonStatus status, Integer declared, Integer accepted) {
        return ReceiptCarton.builder().status(status).declaredUnits(declared).acceptedUnits(accepted).build();
    }

    @Test
    void scannedCartonsContributeDeclaredUnits() {
        int total = service.effectiveUnits(line(null, 120, "UNIT", null, null, null), List.of(
                carton(CartonStatus.SCANNED, 12, null),
                carton(CartonStatus.SCANNED, 12, null)));
        assertThat(total).isEqualTo(24);
    }

    @Test
    void verifiedShortCartonsContributeTheirVerifiedUnitsOnly() {
        int total = service.effectiveUnits(null, List.of(
                carton(CartonStatus.VERIFIED_SHORT, 12, 8),
                carton(CartonStatus.SCANNED, 12, null)));
        assertThat(total).isEqualTo(20);
    }

    @Test
    void rejectedAndUnscannedCartonsNeverCount() {
        int total = service.effectiveUnits(null, List.of(
                carton(CartonStatus.REJECTED, 12, null),
                carton(CartonStatus.ISSUED, 12, null),
                carton(CartonStatus.SCANNED, 12, null)));
        assertThat(total).isEqualTo(12);
    }

    @Test
    void expectedUnitsConvertsExplictCartonUom() {
        ReceiptLine cartonLine = line(null, 2, "CARTON", 12, null, null);
        assertThat(service.expectedUnits(cartonLine)).isEqualTo(24);
    }

    @Test
    void packExpectedUnitsUseOnlyAnExplicitPerLineConversion() {
        ReceiptLine oneStockUnitPerPack = line(null, 6, "PACK", 1, null, null);
        assertThat(service.expectedUnits(oneStockUnitPerPack)).isEqualTo(6);
        assertThat(service.conversionError(oneStockUnitPerPack)).isNull();

        ReceiptLine unknownPackConversion = line(null, 6, "PACK", null, null, null);
        assertThat(service.expectedUnits(unknownPackConversion)).isNull();
        assertThat(service.conversionError(unknownPackConversion)).isNotBlank();
        assertThat(service.discrepancyStatusOf(unknownPackConversion)).isEqualTo(DiscrepancyStatus.UNRESOLVED);
    }

    @Test
    void milkInvoiceVsShortScanProducesShortage() {
        ReceiptLine shortage = line(null, 120, "UNIT", null, null, null);
        shortage.setExpectedUnits(120);
        shortage.setScannedQuantity(108); // nine cartons of 12 will be scanned
        assertThat(service.discrepancyStatusOf(shortage)).isEqualTo(DiscrepancyStatus.SHORTAGE);
    }

    @Test
    void equalQuantitiesAreMatched() {
        ReceiptLine matched = line(null, 120, "UNIT", null, null, null);
        matched.setExpectedUnits(120);
        matched.setScannedQuantity(120);
        assertThat(service.discrepancyStatusOf(matched)).isEqualTo(DiscrepancyStatus.MATCHED);
    }

    @Test
    void surplusIsFlaggedNotSilentlyAccepted() {
        ReceiptLine surplus = line(null, 100, "UNIT", null, null, null);
        surplus.setExpectedUnits(100);
        surplus.setScannedQuantity(110);
        assertThat(service.discrepancyStatusOf(surplus)).isEqualTo(DiscrepancyStatus.SURPLUS);
    }

    @Test
    void explicitAcceptedQuantityCountsAsResolved() {
        ReceiptLine resolved = line(null, 120, "UNIT", null, 108, null);
        resolved.setExpectedUnits(120);
        resolved.setScannedQuantity(108);
        assertThat(service.discrepancyStatusOf(resolved)).isEqualTo(DiscrepancyStatus.RESOLVED);
    }

    @Test
    void rejectedOnlyLineIsMarkedRejected() {
        assertThat(service.discrepancyStatusOf(line(null, 120, "UNIT", null, null, 12)))
                .isEqualTo(DiscrepancyStatus.REJECTED);
    }

    @Test
    void unknownUnitConversionBlocksReconciliation() {
        ReceiptLine bad = line(null, 4, "KG", null, null, null);
        assertThat(service.conversionError(bad)).isNotBlank();
        assertThat(service.discrepancyStatusOf(bad)).isEqualTo(DiscrepancyStatus.UNRESOLVED);
    }

    @Test
    void unmatchedLineBlocksReconciliation() {
        ReceiptLine unmatched = line(null, 120, "UNIT", null, null, null);
        unmatched.setProduct(null);
        unmatched.setMatchStatus(MatchStatus.UNMATCHED);
        assertThat(service.discrepancyStatusOf(unmatched)).isEqualTo(DiscrepancyStatus.UNRESOLVED);
    }

    @Test
    void blockersListUnmatchedLinesAndConversionProblems() {
        ReceiptLine unmatched = ReceiptLine.builder()
                .description("Imported Olives")
                .product(null)
                .matchStatus(MatchStatus.UNMATCHED)
                .unitOfMeasure("UNIT")
                .build();
        List<String> blockers = service.blockers(List.of(unmatched));
        // An unmatched line is blocked three ways: no product, no quantity, no accepted review yet.
        assertThat(blockers).hasSize(3);
        assertThat(blockers.get(0)).contains("not matched to a SmartMart product");
        assertThat(blockers.get(1)).contains("no invoice quantity");
        assertThat(blockers.get(2)).contains("explicitly accepted");
    }
}
