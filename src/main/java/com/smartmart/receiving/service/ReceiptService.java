package com.smartmart.receiving.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.product.entity.Product;
import com.smartmart.product.dto.ProductRequest;
import com.smartmart.product.service.ProductService;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.receiving.config.ReceivingProperties;
import com.smartmart.receiving.dto.ReceiptRequests;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.entity.*;
import com.smartmart.receiving.repository.*;
import com.smartmart.security.CurrentUserService;
import com.smartmart.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Receipt lifecycle and invoice line management. */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private static final Set<ReceiptStatus> EDITABLE = Set.of(ReceiptStatus.DRAFT, ReceiptStatus.PROCESSING, ReceiptStatus.REVIEW_REQUIRED, ReceiptStatus.READY_TO_CONFIRM);

    private final GoodsReceiptRepository receipts;
    private final ReceiptLineRepository lines;
    private final ReceiptCartonRepository cartons;
    private final ReceiptDocumentRepository documents;
    private final ProductRepository products;
    private final ProductService productService;
    private final ProductBatchService batchService;
    private final ReconciliationService reconciliation;
    private final ReceivingProperties properties;

    @Transactional
    public ReceiptResponses.ReceiptSummary create(ReceiptRequests.CreateReceipt request, User manager) {
        GoodsReceipt receipt = GoodsReceipt.builder()
                .supplierName(trim(request.supplierName(), 255))
                .invoiceNumber(trim(request.invoiceNumber(), 100))
                .invoiceDate(request.invoiceDate())
                .purchaseOrderRef(trim(request.purchaseOrderRef(), 100))
                .notes(trim(request.notes(), 1000))
                .status(ReceiptStatus.DRAFT)
                .createdBy(manager)
                .build();
        return summary(receipts.save(receipt));
    }

    @Transactional(readOnly = true)
    public Page<ReceiptResponses.ReceiptSummary> list(Optional<ReceiptStatus> status, int page, int size) {
        Page<GoodsReceipt> result = status
                .map(s -> receipts.findByStatusOrderByIdDesc(s, PageRequest.of(page, Math.min(size, 100))))
                .orElseGet(() -> receipts.findAllByOrderByIdDesc(PageRequest.of(page, Math.min(size, 100))));
        return result.map(this::summary);
    }

    @Transactional
    public GoodsReceipt require(Long id) {
        return receipts.findById(id).orElseThrow(() -> BusinessException.notFound("Receipt not found"));
    }

    @Transactional(readOnly = true)
    public ReceiptResponses.Reconciliation detail(GoodsReceipt receipt) {
        return reconciliation.evaluate(receipt);
    }

    /** Persisted recompute used by the reconcile endpoint. */
    @Transactional
    public ReceiptResponses.Reconciliation recompute(Long id) {
        GoodsReceipt receipt = require(id);
        requireEditable(receipt);
        reconciliation.recompute(receipt);
        return detail(receipt);
    }

    @Transactional
    public void update(Long id, ReceiptRequests.UpdateReceipt request) {
        GoodsReceipt receipt = require(id);
        requireEditable(receipt);
        if (request.supplierName() != null) receipt.setSupplierName(trim(request.supplierName(), 255));
        if (request.invoiceNumber() != null) receipt.setInvoiceNumber(trim(request.invoiceNumber(), 100));
        if (request.invoiceDate() != null) receipt.setInvoiceDate(request.invoiceDate());
        if (request.purchaseOrderRef() != null) receipt.setPurchaseOrderRef(trim(request.purchaseOrderRef(), 100));
        if (request.notes() != null) receipt.setNotes(trim(request.notes(), 1000));
    }

    @Transactional
    public void cancel(Long id) {
        GoodsReceipt receipt = require(id);
        if (receipt.getStatus() == ReceiptStatus.CONFIRMED) {
            throw BusinessException.conflict("A confirmed receipt cannot be cancelled - record an explicit reversal instead");
        }
        receipt.setStatus(ReceiptStatus.CANCELLED);
    }

    @Transactional
    public ReceiptResponses.ReceiptLine addLine(Long receiptId, ReceiptRequests.AddLine request, User manager) {
        GoodsReceipt receipt = require(receiptId);
        requireEditable(receipt);

        ReceiptLine line = ReceiptLine.builder()
                .receipt(receipt)
                .description(trim(request.description(), 500))
                .sku(trim(request.sku(), 100))
                .unitOfMeasure(request.unitOfMeasure() != null ? request.unitOfMeasure().toUpperCase() : "UNIT")
                .source(LineSource.MANUAL)
                .matchStatus(MatchStatus.UNMATCHED)
                .discrepancyStatus(DiscrepancyStatus.UNRESOLVED)
                .build();
        applyCommonFields(line, request.productId(), request.invoiceQuantity(), request.unitOfMeasure(),
                request.unitsPerCarton(), request.unitCost(), request.lineTotal(), request.batchCode(), request.expiryDate());
        // Original values for the audit trail (a manually entered line is its own original).
        line.setOriginalDescription(line.getDescription());
        line.setOriginalQuantity(line.getInvoiceQuantity());
        line.setOriginalUnitCost(line.getUnitCost());
        line.setOriginalExpiryDate(line.getExpiryDate());
        line.setMatchStatus(resolveMatchStatus(line, request.productId()));
        line.setBatch(resolveBatch(line));
        reconciliation.recompute(receipt);
        return mapLine(lines.save(line));
    }

    @Transactional
    public void updateLine(Long receiptId, Long lineId, ReceiptRequests.UpdateLine request, User manager) {
        GoodsReceipt receipt = require(receiptId);
        requireEditable(receipt);
        ReceiptLine line = lines.findById(lineId).orElseThrow(() -> BusinessException.notFound("Receipt line not found"));
        if (!line.getReceipt().getId().equals(receiptId)) {
            throw BusinessException.badRequest("The line does not belong to this receipt");
        }
        if (request.productId() != null) {
            Product product = products.findById(request.productId())
                    .orElseThrow(() -> BusinessException.notFound("Product not found: " + request.productId()));
            line.setProduct(product);
            line.setMatchStatus(MatchStatus.MANUAL);
        }
        if (request.invoiceQuantity() != null) line.setInvoiceQuantity(request.invoiceQuantity());
        if (request.unitOfMeasure() != null) line.setUnitOfMeasure(request.unitOfMeasure().toUpperCase());
        if (request.unitsPerCarton() != null) line.setUnitsPerCarton(request.unitsPerCarton());
        if (request.unitCost() != null) line.setUnitCost(request.unitCost());
        if (request.lineTotal() != null) line.setLineTotal(request.lineTotal());
        if (request.batchCode() != null) line.setBatchCode(trim(request.batchCode(), 100));
        if (request.expiryDate() != null) line.setExpiryDate(request.expiryDate());
        if (request.acceptedQuantity() != null) {
            if (request.acceptedQuantity() == 0 && (request.rejectedQuantity() == null || request.rejectedQuantity() == 0)) {
                throw BusinessException.badRequest("Accept zero units only together with rejected units or an explicit reversal");
            }
            line.setAcceptedQuantity(request.acceptedQuantity());
        }
        if (request.rejectedQuantity() != null) line.setRejectedQuantity(request.rejectedQuantity());
        if (request.resolutionReason() != null) line.setResolutionReason(trim(request.resolutionReason(), 500));
        line.setBatch(resolveBatch(line));
        reconciliation.recompute(receipt);
    }

    /** Creates a new catalogue product and attaches it to an unmatched invoice line atomically. */
    @Transactional
    public void createProductForLine(Long receiptId, Long lineId, ProductRequest request) {
        GoodsReceipt receipt = require(receiptId);
        requireEditable(receipt);
        ReceiptLine line = lines.findById(lineId).orElseThrow(() -> BusinessException.notFound("Receipt line not found"));
        if (!line.getReceipt().getId().equals(receiptId)) {
            throw BusinessException.badRequest("The line does not belong to this receipt");
        }
        if (line.getProduct() != null) {
            throw BusinessException.conflict("This invoice line is already linked to a catalogue product");
        }

        Product product = productService.createEntity(request);
        line.setProduct(product);
        line.setMatchStatus(MatchStatus.MANUAL);
        line.setAutoMatched(false);
        line.setExpectedUnits(reconciliation.expectedUnits(line));
        line.setBatch(resolveBatch(line));
        reconciliation.recompute(receipt);
    }

    public void requireEditable(GoodsReceipt receipt) {
        if (!EDITABLE.contains(receipt.getStatus())) {
            throw BusinessException.conflict("Receipt " + receipt.getId() + " is " + receipt.getStatus() + " and can no longer be edited");
        }
    }

    /** Product matching follows priority: explicit id, then normalized description; no silent merges. */
    private MatchStatus resolveMatchStatus(ReceiptLine line, Long explicitProductId) {
        if (explicitProductId != null) return MatchStatus.MANUAL;
        List<Product> all = products.findAll();
        ProductMatcher.Match match = new ProductMatcher().bySkuOrName(all, line.getSku(), line.getDescription());
        if (match.product() != null) {
            line.setProduct(match.product());
            return match.status();
        }
        return MatchStatus.UNMATCHED;
    }

    private void applyCommonFields(ReceiptLine line, Long productId, Integer invoiceQuantity, String unitOfMeasure,
                                   Integer unitsPerCarton, java.math.BigDecimal unitCost, java.math.BigDecimal lineTotal,
                                   String batchCode, java.time.LocalDate expiryDate) {
        if (invoiceQuantity != null && invoiceQuantity < 0) throw BusinessException.badRequest("Invoice quantity cannot be negative");
        if (invoiceQuantity != null) line.setInvoiceQuantity(invoiceQuantity);
        if (unitOfMeasure != null && !unitOfMeasure.isBlank()) line.setUnitOfMeasure(unitOfMeasure.toUpperCase());
        if (unitsPerCarton != null) {
            if (unitsPerCarton <= 0) throw BusinessException.badRequest("Units per carton must be positive");
            line.setUnitsPerCarton(unitsPerCarton);
        }
        if (unitCost != null) line.setUnitCost(unitCost);
        if (lineTotal != null) line.setLineTotal(lineTotal);
        if (batchCode != null) line.setBatchCode(trim(batchCode, 100));
        if (expiryDate != null) line.setExpiryDate(expiryDate);
        if (productId != null) {
            Product product = products.findById(productId)
                    .orElseThrow(() -> BusinessException.notFound("Product not found: " + productId));
            line.setProduct(product);
        }
        line.setExpectedUnits(reconciliation.expectedUnits(line));
    }

    private ProductBatch resolveBatch(ReceiptLine line) {
        if (line.getBatchCode() == null || line.getBatchCode().isBlank() || line.getProduct() == null) return null;
        return batchService.getOrCreate(line.getProduct().getId(), line.getBatchCode(), line.getExpiryDate(), line.getUnitCost());
    }

    /* ---------- mapping ---------- */

    public ReceiptResponses.ReceiptSummary summary(GoodsReceipt receipt) {
        List<ReceiptLine> receiptLines = lines.findByReceiptIdOrderByIdAsc(receipt.getId());
        List<ReceiptCarton> receiptCartons = cartons.findByReceiptIdOrderByIdAsc(receipt.getId());
        long scanned = receiptCartons.stream().filter(c -> c.getStatus() == CartonStatus.SCANNED || c.getStatus() == CartonStatus.VERIFIED_SHORT).count();
        long unresolved = receiptLines.stream().filter(l ->
                l.getDiscrepancyStatus() == DiscrepancyStatus.SHORTAGE
                        || l.getDiscrepancyStatus() == DiscrepancyStatus.SURPLUS
                        || l.getDiscrepancyStatus() == DiscrepancyStatus.UNRESOLVED
                        || l.getMatchStatus() == MatchStatus.UNMATCHED).count();
        return ReceiptResponses.ReceiptSummary.builder()
                .id(receipt.getId())
                .supplierName(receipt.getSupplierName())
                .invoiceNumber(receipt.getInvoiceNumber())
                .invoiceDate(receipt.getInvoiceDate())
                .purchaseOrderRef(receipt.getPurchaseOrderRef())
                .status(receipt.getStatus())
                .createdBy(receipt.getCreatedBy().getName())
                .createdAt(receipt.getCreatedAt())
                .updatedAt(receipt.getUpdatedAt())
                .cartonCount(receiptCartons.size())
                .scannedCartonCount((int) scanned)
                .unresolvedDiscrepancies((int) unresolved)
                .build();
    }

    public ReceiptResponses.DocumentInfo documentInfo(Long receiptId) {
        Optional<ReceiptDocument> latest = documents.findFirstByReceiptIdOrderByIdDesc(receiptId);
        return latest.map(d -> ReceiptResponses.DocumentInfo.builder()
                .id(d.getId())
                .originalFilename(d.getOriginalFilename())
                .sourceType(d.getSourceType())
                .processingStatus(d.getProcessingStatus())
                .warnings(d.getWarnings())
                .error(d.getError())
                .uploadedAt(d.getUploadedAt())
                .build()).orElse(null);
    }

    public ReceiptResponses.ReceiptLine mapLine(ReceiptLine line) {
        return reconciliation.mapLines(List.of(line), reconciliation.cartonsByLine(line.getReceipt().getId())).get(0);
    }

    @Transactional(readOnly = true)
    public ReceiptResponses.ReceiptLine mapLine(Long receiptId, Long lineId) {
        ReceiptLine line = lines.findById(lineId).orElseThrow(() -> BusinessException.notFound("Receipt line not found"));
        if (!line.getReceipt().getId().equals(receiptId)) {
            throw BusinessException.badRequest("The line does not belong to this receipt");
        }
        return mapLine(line);
    }

    public ReceivingProperties properties() {
        return properties;
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }
}
