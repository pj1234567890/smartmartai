package com.smartmart.receiving.service;

import com.smartmart.product.entity.Product;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.entity.*;
import com.smartmart.receiving.repository.ReceiptCartonRepository;
import com.smartmart.receiving.repository.ReceiptLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backend-owned quantity reconciliation. All numbers are recomputed from persisted carton
 * records - frontend values are display hints only.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final ReceiptLineRepository lines;
    private final ReceiptCartonRepository cartons;

    /** Effective stock units represented by a line's cartons: declared for scanned, verified for audited, zero for rejected. */
    public int effectiveUnits(ReceiptLine line, Collection<ReceiptCarton> lineCartons) {
        int total = 0;
        for (ReceiptCarton carton : lineCartons) {
            switch (carton.getStatus()) {
                case SCANNED -> total += carton.getAcceptedUnits() != null ? carton.getAcceptedUnits() : carton.getDeclaredUnits();
                case VERIFIED_SHORT -> total += carton.getAcceptedUnits() != null ? carton.getAcceptedUnits() : 0;
                case REJECTED, ISSUED -> { /* not counted */ }
            }
        }
        return total;
    }

    /** Recomputes the expected stock units from the invoice quantity, unit and validated conversion. */
    public Integer expectedUnits(ReceiptLine line) {
        return UnitConversion.convert(line.getInvoiceQuantity(), line.getUnitOfMeasure(), line.getUnitsPerCarton()).expectedUnits();
    }

    public String conversionError(ReceiptLine line) {
        return UnitConversion.convert(line.getInvoiceQuantity(), line.getUnitOfMeasure(), line.getUnitsPerCarton()).error();
    }

    /**
     * Persists recomputed state: expected/scanned totals, discrepancy statuses and the receipt
     * status transition DRAFT/REVIEW_REQUIRED -> READY_TO_CONFIRM. Confirmed and cancelled
     * receipts are left untouched.
     */
    @Transactional
    public void recompute(GoodsReceipt receipt) {
        List<ReceiptLine> receiptLines = lines.findByReceiptIdOrderByIdAsc(receipt.getId());
        Map<Long, List<ReceiptCarton>> byLine = cartonsByLine(receipt.getId());
        for (int i = 0; i < receiptLines.size(); i++) {
            ReceiptLine line = receiptLines.get(i);
            line.setExpectedUnits(expectedUnits(line));
            line.setScannedQuantity(effectiveUnits(line, byLine.getOrDefault(line.getId(), List.of())));
            line.setDiscrepancyStatus(discrepancyStatusOf(line));
        }
        lines.saveAll(receiptLines);

        if (receipt.getStatus() == ReceiptStatus.CONFIRMED || receipt.getStatus() == ReceiptStatus.CANCELLED) return;
        receipt.setStatus(blockers(receiptLines).isEmpty() ? ReceiptStatus.READY_TO_CONFIRM : ReceiptStatus.REVIEW_REQUIRED);
    }

    public DiscrepancyStatus discrepancyStatusOf(ReceiptLine line) {
        if (line.getRejectedQuantity() != null && line.getRejectedQuantity() > 0) return DiscrepancyStatus.REJECTED;
        if (line.getAcceptedQuantity() != null) return DiscrepancyStatus.RESOLVED;
        if (line.getMatchStatus() == MatchStatus.UNMATCHED || line.getProduct() == null) return DiscrepancyStatus.UNRESOLVED;
        if (conversionError(line) != null) return DiscrepancyStatus.UNRESOLVED;
        if (line.getInvoiceQuantity() == null || line.getExpectedUnits() == null || line.getScannedQuantity() == null) {
            return DiscrepancyStatus.UNRESOLVED;
        }
        int diff = line.getExpectedUnits() - line.getScannedQuantity();
        if (diff == 0) {
            // Quantities agree and the line is valid: the accepted quantity defaults to the scanned
            // units; an explicit manager decision is still required whenever the amounts differ.
            return DiscrepancyStatus.MATCHED;
        }
        return diff > 0 ? DiscrepancyStatus.SHORTAGE : DiscrepancyStatus.SURPLUS;
    }

    /** Mandatory exceptions the manager must resolve before the receipt can be confirmed. */
    public List<String> blockers(List<ReceiptLine> receiptLines) {
        List<String> blockers = new ArrayList<>();
        if (receiptLines.isEmpty()) blockers.add("The receipt has no invoice line items yet");
        for (int i = 0; i < receiptLines.size(); i++) {
            ReceiptLine line = receiptLines.get(i);
            String label = "Line " + (i + 1) + " (" + line.getDescription() + ")";
            if (line.getProduct() == null) blockers.add(label + " is not matched to a SmartMart product");
            if (line.getInvoiceQuantity() != null) {
                String conversionError = conversionError(line);
                if (conversionError != null) blockers.add(label + ": " + conversionError);
            } else {
                blockers.add(label + " has no invoice quantity");
            }
            if (line.getAcceptedQuantity() == null) {
                blockers.add(label + " needs an explicitly accepted quantity review");
            } else {
                if (line.getAcceptedQuantity() > 0 && (line.getBatchCode() == null || line.getBatchCode().isBlank())) {
                    blockers.add(label + " accepts stock but has no batch code - enter the verified batch");
                }
                if (line.getAcceptedQuantity() > 0
                        && line.getExpectedUnits() != null
                        && line.getAcceptedQuantity() != line.getExpectedUnits()
                        && (line.getResolutionReason() == null || line.getResolutionReason().isBlank())) {
                    blockers.add(label + " accepts " + line.getAcceptedQuantity() + " units of " + line.getExpectedUnits()
                            + " invoiced - record the reason for the variance");
                }
            }
        }
        return blockers;
    }

    /** Read-only evaluation used by the detail endpoint; derives everything from persisted rows. */
    @Transactional(readOnly = true)
    public ReceiptResponses.Reconciliation evaluate(GoodsReceipt receipt) {
        List<ReceiptLine> receiptLines = lines.findByReceiptIdOrderByIdAsc(receipt.getId());
        Map<Long, List<ReceiptCarton>> byLine = cartonsByLine(receipt.getId());
        recalculateTransient(receiptLines, byLine);
        return summarize(receiptLines, byLine);
    }

    /** Applies expected/scanned updates in memory without saving (for detail views). */
    public void recalculateTransient(List<ReceiptLine> receiptLines, Map<Long, List<ReceiptCarton>> byLine) {
        for (ReceiptLine line : receiptLines) {
            line.setExpectedUnits(expectedUnits(line));
            line.setScannedQuantity(effectiveUnits(line, byLine.getOrDefault(line.getId(), List.of())));
        }
    }

    private ReceiptResponses.Reconciliation summarize(List<ReceiptLine> receiptLines, Map<Long, List<ReceiptCarton>> byLine) {
        int scannedCartons = 0;
        int declaredUnits = 0;
        int expectedUnitsTotal = 0;
        for (ReceiptLine line : receiptLines) {
            for (ReceiptCarton carton : byLine.getOrDefault(line.getId(), List.of())) {
                if (carton.getStatus() == CartonStatus.SCANNED || carton.getStatus() == CartonStatus.VERIFIED_SHORT) scannedCartons++;
            }
            declaredUnits += line.getScannedQuantity() != null ? line.getScannedQuantity() : 0;
            expectedUnitsTotal += line.getExpectedUnits() != null ? line.getExpectedUnits() : 0;
        }
        List<String> blockers = blockers(receiptLines);
        return ReceiptResponses.Reconciliation.builder()
                .scannedCartons(scannedCartons)
                .declaredUnits(declaredUnits)
                .expectedUnits(expectedUnitsTotal)
                .readyToConfirm(blockers.isEmpty())
                .blockers(blockers)
                .lines(mapLines(receiptLines, byLine))
                .build();
    }

    public Map<Long, List<ReceiptCarton>> cartonsByLine(Long receiptId) {
        Map<Long, List<ReceiptCarton>> map = new LinkedHashMap<>();
        for (ReceiptCarton carton : cartons.findByReceiptIdOrderByIdAsc(receiptId)) {
            map.computeIfAbsent(carton.getLine().getId(), k -> new ArrayList<>()).add(carton);
        }
        return map;
    }

    public List<ReceiptResponses.ReceiptLine> mapLines(List<ReceiptLine> receiptLines, Map<Long, List<ReceiptCarton>> byLine) {
        List<ReceiptResponses.ReceiptLine> result = new ArrayList<>();
        for (int i = 0; i < receiptLines.size(); i++) {
            ReceiptLine line = receiptLines.get(i);
            List<ReceiptCarton> lineCartons = byLine.getOrDefault(line.getId(), List.of());
            Product product = line.getProduct();
            int cartonsScanned = (int) lineCartons.stream().filter(c -> c.getStatus() == CartonStatus.SCANNED || c.getStatus() == CartonStatus.VERIFIED_SHORT).count();
            int declared = 0;
            for (ReceiptCarton c : lineCartons) {
                if (c.getStatus() == CartonStatus.SCANNED) declared += c.getDeclaredUnits();
            }
            result.add(ReceiptResponses.ReceiptLine.builder()
                    .id(line.getId())
                    .description(line.getDescription())
                    .originalDescription(line.getOriginalDescription())
                    .sku(line.getSku())
                    .productId(product != null ? product.getId() : null)
                    .productName(product != null ? product.getName() : null)
                    .matchStatus(line.getMatchStatus())
                    .invoiceQuantity(line.getInvoiceQuantity())
                    .originalQuantity(line.getOriginalQuantity())
                    .unitOfMeasure(line.getUnitOfMeasure())
                    .unitsPerCarton(line.getUnitsPerCarton())
                    .expectedUnits(line.getExpectedUnits())
                    .conversionError(conversionError(line))
                    .scannedQuantity(line.getScannedQuantity())
                    .acceptedQuantity(line.getAcceptedQuantity())
                    .rejectedQuantity(line.getRejectedQuantity())
                    .unitCost(line.getUnitCost())
                    .lineTotal(line.getLineTotal())
                    .batchCode(line.getBatchCode())
                    .batchId(line.getBatch() != null ? line.getBatch().getId() : null)
                    .expiryDate(line.getExpiryDate())
                    .originalExpiryDate(line.getOriginalExpiryDate())
                    .discrepancyStatus(line.getDiscrepancyStatus())
                    .resolutionReason(line.getResolutionReason())
                    .cartonsScanned(cartonsScanned)
                    .cartonDeclaredUnits(declared)
                    .build());
        }
        return result;
    }

    public String discrepancyLabel(DiscrepancyStatus status) {
        return switch (status) {
            case MATCHED -> "Quantities match";
            case SHORTAGE -> "Scanned less than invoiced";
            case SURPLUS -> "Scanned more than invoiced";
            case UNRESOLVED -> "Needs manager review";
            case RESOLVED -> "Accepted with reason";
            case REJECTED -> "Rejected/damaged units only";
        };
    }
}
