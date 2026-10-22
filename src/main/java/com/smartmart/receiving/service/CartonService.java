package com.smartmart.receiving.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.receiving.dto.ReceiptRequests;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.entity.*;
import com.smartmart.receiving.repository.GoodsReceiptRepository;
import com.smartmart.receiving.repository.ReceiptCartonRepository;
import com.smartmart.receiving.repository.ReceiptLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Carton labels, scans and per-carton verification. Label generation creates persistent carton
 * records (status ISSUED); a scan validates the carton against the receipt through the globally
 * unique carton id plus the issued record - never through QR content alone.
 */
@Service
@RequiredArgsConstructor
public class CartonService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GoodsReceiptRepository receipts;
    private final ReceiptLineRepository lines;
    private final ReceiptCartonRepository cartons;
    private final ReceiptService receiptService;
    private final ReconciliationService reconciliation;
    private final QrLabelService qr;

    @Transactional
    public List<ReceiptResponses.Label> generateLabels(Long receiptId, ReceiptRequests.GenerateLabels request) {
        GoodsReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> BusinessException.notFound("Receipt not found"));
        receiptService.requireEditable(receipt);
        ReceiptLine line = lines.findById(request.lineId())
                .orElseThrow(() -> BusinessException.notFound("Receipt line not found"));
        if (!line.getReceipt().getId().equals(receiptId)) {
            throw BusinessException.badRequest("The line does not belong to this receipt");
        }
        if (line.getProduct() == null) {
            throw BusinessException.badRequest("Match the line to a SmartMart product before printing carton labels");
        }
        int count = request.count() != null ? request.count() : 1;

        List<ReceiptResponses.Label> labels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ReceiptCarton carton = ReceiptCarton.builder()
                    .cartonId(nextCartonId())
                    .receipt(receipt)
                    .line(line)
                    .batch(line.getBatch())
                    .declaredUnits(request.unitsPerCarton())
                    .status(CartonStatus.ISSUED)
                    .build();
            cartons.saveAndFlush(carton);
            QrLabelService.CartonQrPayload payload = qr.forCarton(line.getProduct(), carton.getCartonId(),
                    line.getBatchCode(), request.unitsPerCarton());
            labels.add(new ReceiptResponses.Label(
                    carton.getCartonId(),
                    Base64.getEncoder().encodeToString(qr.renderLabel(payload)),
                    request.unitsPerCarton()));
        }
        return labels;
    }

    @Transactional
    public ReceiptResponses.ReceiptCarton scan(Long receiptId, String code) {
        GoodsReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> BusinessException.notFound("Receipt not found"));
        receiptService.requireEditable(receipt);

        QrLabelService.CartonQrPayload payload = qr.parseScanned(code);
        ReceiptCarton carton = cartons.findByCartonId(payload.cartonId())
                .orElseThrow(() -> BusinessException.badRequest(
                        "Unknown carton code " + payload.cartonId() + " - only cartons labelled through this module can be received"));
        if (!carton.getReceipt().getId().equals(receiptId)) {
            throw BusinessException.conflict("Carton " + carton.getCartonId() + " was issued for a different receipt");
        }
        if (carton.getStatus() != CartonStatus.ISSUED) {
            throw BusinessException.conflict("Duplicate scan: carton " + carton.getCartonId()
                    + " is already registered on this receipt as " + carton.getStatus()
                    + " with " + carton.getDeclaredUnits() + " declared units");
        }
        if (carton.getDeclaredUnits() == null || carton.getDeclaredUnits() <= 0) {
            throw BusinessException.badRequest("Carton " + carton.getCartonId() + " has no declared units");
        }
        carton.setStatus(CartonStatus.SCANNED);
        carton.setScanTimestamp(LocalDateTime.now());
        reconciliation.recompute(receipt);
        return map(carton);
    }

    @Transactional
    public ReceiptResponses.ReceiptCarton verify(Long receiptId, Long cartonId, int acceptedUnits, boolean rejected, String reason) {
        GoodsReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> BusinessException.notFound("Receipt not found"));
        receiptService.requireEditable(receipt);
        ReceiptCarton carton = cartons.findById(cartonId).orElseThrow(() -> BusinessException.notFound("Carton not found"));
        if (!carton.getReceipt().getId().equals(receiptId)) {
            throw BusinessException.badRequest("The carton does not belong to this receipt");
        }
        if (acceptedUnits < 0 || acceptedUnits > carton.getDeclaredUnits()) {
            throw BusinessException.badRequest("Verified units must be between 0 and the declared " + carton.getDeclaredUnits());
        }
        if (!rejected && acceptedUnits > 0 && acceptedUnits < carton.getDeclaredUnits() && (reason == null || reason.isBlank())) {
            throw BusinessException.badRequest("Recording a verified shortage requires a reason");
        }
        if (carton.getStatus() == CartonStatus.ISSUED) {
            throw BusinessException.badRequest("Carton " + carton.getCartonId() + " must be scanned before it can be verified");
        }
        carton.setAcceptedUnits(rejected ? 0 : acceptedUnits);
        carton.setStatus(rejected ? CartonStatus.REJECTED : CartonStatus.VERIFIED_SHORT);
        carton.setReason(rejected || acceptedUnits != carton.getDeclaredUnits() ? reason : null);
        reconciliation.recompute(receipt);
        return map(carton);
    }

    @Transactional
    public void remove(Long receiptId, Long cartonId) {
        GoodsReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> BusinessException.notFound("Receipt not found"));
        ReceiptCarton carton = cartons.findById(cartonId).orElseThrow(() -> BusinessException.notFound("Carton not found"));
        if (!carton.getReceipt().getId().equals(receiptId)) {
            throw BusinessException.badRequest("The carton does not belong to this receipt");
        }
        receiptService.requireEditable(receipt);
        if (carton.getStatus() == CartonStatus.ISSUED) {
            throw BusinessException.badRequest("Carton " + carton.getCartonId() + " has not been scanned on this receipt");
        }
        // Reset rather than delete: the carton id stays bound to this receipt but the scan is undone.
        carton.setStatus(CartonStatus.ISSUED);
        carton.setScanTimestamp(null);
        carton.setAcceptedUnits(null);
        carton.setReason(null);
        reconciliation.recompute(receipt);
    }

    @Transactional(readOnly = true)
    public List<ReceiptResponses.ReceiptCarton> list(Long receiptId) {
        return cartons.findByReceiptIdOrderByIdAsc(receiptId).stream().map(this::map).toList();
    }

    private String nextCartonId() {
        for (int attempt = 0; attempt < 5; attempt++) {
            long number = 1_000_000_000L + (Math.abs(RANDOM.nextLong()) % 8_000_000_000L);
            String candidate = String.format("CTN-%010d", number);
            if (!cartons.existsByCartonId(candidate)) return candidate;
        }
        throw BusinessException.conflict("Could not allocate a unique carton id, please retry");
    }

    private ReceiptResponses.ReceiptCarton map(ReceiptCarton carton) {
        ReceiptLine line = carton.getLine();
        ProductBatch batch = carton.getBatch();
        return ReceiptResponses.ReceiptCarton.builder()
                .id(carton.getId())
                .cartonId(carton.getCartonId())
                .lineId(line.getId())
                .productName(line.getProduct() != null ? line.getProduct().getName() : line.getDescription())
                .batchCode(batch != null ? batch.getBatchCode() : null)
                .declaredUnits(carton.getDeclaredUnits())
                .acceptedUnits(carton.getAcceptedUnits())
                .status(carton.getStatus())
                .scanTimestamp(carton.getScanTimestamp())
                .reason(carton.getReason())
                .build();
    }
}
