package com.smartmart.receiving.controller;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.receiving.dto.ReceiptRequests;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.entity.DiscrepancyStatus;
import com.smartmart.receiving.entity.GoodsReceipt;
import com.smartmart.receiving.entity.ProductBatch;
import com.smartmart.receiving.entity.ReceiptStatus;
import com.smartmart.receiving.service.CartonService;
import com.smartmart.receiving.service.InvoiceExtractionService;
import com.smartmart.receiving.service.ProductBatchService;
import com.smartmart.receiving.service.ReceiptService;
import com.smartmart.receiving.service.StockConfirmationService;
import com.smartmart.security.CurrentUserService;
import com.smartmart.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manager-only Goods Receiving API. The acting manager is always taken from Spring Security -
 * never from the request body or query string.
 */
@RestController
@RequestMapping("/api/receiving")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class ReceivingController {

    private final ReceiptService receipts;
    private final CartonService cartons;
    private final InvoiceExtractionService extraction;
    private final ProductBatchService batches;
    private final StockConfirmationService confirmation;
    private final CurrentUserService currentUser;

    /* ---------- receipt lifecycle ---------- */

    @PostMapping
    public ResponseEntity<ReceiptResponses.ReceiptSummary> create(@Valid @RequestBody ReceiptRequests.CreateReceipt request) {
        return ResponseEntity.status(201).body(receipts.create(request, user()));
    }

    @GetMapping
    public Page<ReceiptResponses.ReceiptSummary> list(
            @RequestParam(required = false) ReceiptStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return receipts.list(Optional.ofNullable(status), page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        GoodsReceipt receipt = receipts.require(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receipt", receipts.summary(receipt));
        body.put("document", receipts.documentInfo(id));
        body.put("reconciliation", receipts.detail(receipt));
        body.put("cartons", cartons.list(id));
        return body;
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable Long id, @Valid @RequestBody ReceiptRequests.UpdateReceipt request) {
        receipts.update(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReceiptResponses.ReceiptSummary> cancel(@PathVariable Long id) {
        receipts.cancel(id);
        return ResponseEntity.ok(receipts.summary(receipts.require(id)));
    }

    /* ---------- invoice document ---------- */

    @PostMapping(value = "/{id}/invoice", consumes = {"multipart/form-data"})
    public ResponseEntity<ReceiptResponses.DocumentInfo> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(201).body(extraction.upload(id, file));
    }

    @PostMapping("/{id}/extract")
    public ResponseEntity<ReceiptResponses.DocumentInfo> extract(@PathVariable Long id) {
        return ResponseEntity.ok(extraction.extract(id));
    }

    /* ---------- lines ---------- */

    @PostMapping("/{id}/lines")
    public ResponseEntity<ReceiptResponses.ReceiptLine> addLine(@PathVariable Long id, @Valid @RequestBody ReceiptRequests.AddLine request) {
        return ResponseEntity.status(201).body(receipts.addLine(id, request, user()));
    }

    @PatchMapping("/{id}/lines/{lineId}")
    public ResponseEntity<ReceiptResponses.ReceiptLine> updateLine(
            @PathVariable Long id, @PathVariable Long lineId, @Valid @RequestBody ReceiptRequests.UpdateLine request) {
        receipts.updateLine(id, lineId, request, user());
        return ResponseEntity.ok(receipts.mapLine(id, lineId));
    }

    @GetMapping("/{id}/discrepancies")
    public List<ReceiptResponses.ReceiptLine> discrepancies(@PathVariable Long id) {
        GoodsReceipt receipt = receipts.require(id);
        return receipts.detail(receipt).lines().stream()
                .filter(line -> line.discrepancyStatus() == DiscrepancyStatus.SHORTAGE
                        || line.discrepancyStatus() == DiscrepancyStatus.SURPLUS
                        || line.discrepancyStatus() == DiscrepancyStatus.UNRESOLVED)
                .toList();
    }

    /* ---------- cartons ---------- */

    @PostMapping("/{id}/cartons/labels")
    public ResponseEntity<List<ReceiptResponses.Label>> labels(@PathVariable Long id, @Valid @RequestBody ReceiptRequests.GenerateLabels request) {
        return ResponseEntity.status(201).body(cartons.generateLabels(id, request));
    }

    @PostMapping("/{id}/cartons/scan")
    public ResponseEntity<ReceiptResponses.ReceiptCarton> scan(@PathVariable Long id, @Valid @RequestBody ReceiptRequests.ScanCarton request) {
        return ResponseEntity.ok(cartons.scan(id, request.code()));
    }

    @PostMapping("/{id}/cartons/{cartonId}/verify")
    public ResponseEntity<ReceiptResponses.ReceiptCarton> verify(
            @PathVariable Long id, @PathVariable Long cartonId, @Valid @RequestBody ReceiptRequests.VerifyCarton request) {
        return ResponseEntity.ok(cartons.verify(id, cartonId, request.acceptedUnits(),
                Boolean.TRUE.equals(request.rejected()), request.reason()));
    }

    @GetMapping("/{id}/cartons")
    public List<ReceiptResponses.ReceiptCarton> listCartons(@PathVariable Long id) {
        return cartons.list(id);
    }

    @DeleteMapping("/{id}/cartons/{cartonId}")
    public ResponseEntity<Void> removeCarton(@PathVariable Long id, @PathVariable Long cartonId) {
        cartons.remove(id, cartonId);
        return ResponseEntity.noContent().build();
    }

    /* ---------- reconciliation & confirmation ---------- */

    @PostMapping("/{id}/reconcile")
    public ReceiptResponses.Reconciliation reconcile(@PathVariable Long id) {
        return receipts.recompute(id);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReceiptResponses.ConfirmResult> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(confirmation.confirm(id));
    }

    @GetMapping("/{id}/movements")
    public List<ReceiptResponses.CommittedStock> movements(@PathVariable Long id) {
        receipts.require(id);
        return confirmation.movementsFor(id);
    }

    @PostMapping("/{id}/batches")
    public ResponseEntity<Map<String, Object>> createBatch(@PathVariable Long id, @Valid @RequestBody ReceiptRequests.CreateBatch request) {
        receipts.require(id);
        ProductBatch batch = batches.getOrCreate(request.productId(), request.batchCode(), request.expiryDate(), request.unitCost());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", batch.getId());
        body.put("productId", batch.getProduct().getId());
        body.put("batchCode", batch.getBatchCode());
        body.put("expiryDate", batch.getExpiryDate());
        body.put("quantityOnHand", batch.getQuantityOnHand());
        return ResponseEntity.status(201).body(body);
    }

    private User user() {
        User user = currentUser.currentUser();
        if (user == null) throw BusinessException.unauthorized("Sign in as a manager to use goods receiving");
        return user;
    }
}
