package com.smartmart.receiving;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.inventory.entity.Inventory;
import com.smartmart.inventory.repository.InventoryRepository;
import com.smartmart.product.entity.Product;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.receiving.dto.ReceiptRequests;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.entity.GoodsReceipt;
import com.smartmart.receiving.entity.MovementType;
import com.smartmart.receiving.entity.ReceiptStatus;
import com.smartmart.receiving.entity.StockMovement;
import com.smartmart.receiving.repository.GoodsReceiptRepository;
import com.smartmart.receiving.repository.ProductBatchRepository;
import com.smartmart.receiving.repository.ReceiptCartonRepository;
import com.smartmart.receiving.repository.ReceiptDocumentRepository;
import com.smartmart.receiving.repository.ReceiptLineRepository;
import com.smartmart.receiving.repository.StockMovementRepository;
import com.smartmart.receiving.service.CartonService;
import com.smartmart.receiving.service.ProductBatchService;
import com.smartmart.receiving.service.ReceiptService;
import com.smartmart.receiving.service.StockConfirmationService;
import com.smartmart.user.entity.Role;
import com.smartmart.user.entity.User;
import com.smartmart.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Critical inventory guarantees of the receiving module, run against real services and an
 * in-memory database: exactly-once stock commit via receipt confirmation, rollback on
 * failure, rejection of duplicate carton scans, and audited stock movements.
 */
@SpringBootTest
class StockConfirmationIntegrationTest {

    @Autowired ReceiptService receiptService;
    @Autowired CartonService cartonService;
    @Autowired StockConfirmationService confirmationService;
    @Autowired ProductRepository products;
    @Autowired InventoryRepository inventories;
    @Autowired StockMovementRepository movements;
    @Autowired ProductBatchRepository batches;
    @Autowired GoodsReceiptRepository receipts;
    @Autowired ReceiptLineRepository lines;
    @Autowired ReceiptCartonRepository cartons;
    @Autowired ReceiptDocumentRepository documents;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    /** Spy lets the rollback test make the second line's batch lock fail mid-confirmation. */
    @SpyBean ProductBatchService batchService;

    @AfterEach
    void cleanup() {
        // Delete children before parents so foreign keys are respected between tests.
        movements.deleteAll();
        cartons.deleteAll();
        documents.deleteAll();
        lines.deleteAll();
        batches.deleteAll();
        inventories.deleteAll();
        receipts.deleteAll();
        products.deleteAll();
        users.deleteAll();
    }

    private Product product(String name) {
        return products.save(Product.builder()
                .name(name).brand("Amul").category("Dairy")
                .price(new BigDecimal("64.00"))
                .build());
    }

    private User manager(String email) {
        return users.save(User.builder()
                .name("Priya").email(email)
                .password(passwordEncoder.encode("pw"))
                .role(Role.MANAGER)
                .build());
    }

    private Product milkOf(Setup setup) {
        return products.findById(setup.line().productId()).orElseThrow();
    }

    private Inventory inventoryOf(Product product) {
        return inventories.findAll().stream()
                .filter(i -> i.getProduct().getId().equals(product.getId())).findFirst().orElseThrow();
    }

    @Test
    void managerCanManuallyMatchProductAndResolvePackUnitsOnlyWithExplicitConversion() {
        Product cheese = product("Cheese Slices 200g");
        User mgr = manager("manual-match-manager@test.local");
        var receiptSummary = receiptService.create(new ReceiptRequests.CreateReceipt(
                "Amul Dairy Distributors", "INV-MANUAL-1", null, null, null), mgr);
        ReceiptResponses.ReceiptLine line = receiptService.addLine(receiptSummary.id(),
                new ReceiptRequests.AddLine("Supplier cheese assortment 200g", null, null, 8, "PACK", null,
                        BigDecimal.valueOf(125), BigDecimal.valueOf(1000), null, null), mgr);
        assertThat(line.matchStatus()).isEqualTo(com.smartmart.receiving.entity.MatchStatus.UNMATCHED);
        assertThat(line.productId()).isNull();

        receiptService.updateLine(receiptSummary.id(), line.id(), new ReceiptRequests.UpdateLine(
                cheese.getId(), null, null, null, null, null, null, null, null, null, null), mgr);
        var manuallyMatched = receiptService.mapLine(receiptSummary.id(), line.id());
        assertThat(manuallyMatched.matchStatus()).isEqualTo(com.smartmart.receiving.entity.MatchStatus.MANUAL);
        assertThat(manuallyMatched.expectedUnits()).isNull();
        assertThat(manuallyMatched.conversionError()).isNotBlank();

        receiptService.updateLine(receiptSummary.id(), line.id(), new ReceiptRequests.UpdateLine(
                null, null, null, 1, null, null, null, null, null, null, null), mgr);
        var converted = receiptService.mapLine(receiptSummary.id(), line.id());
        assertThat(converted.expectedUnits()).isEqualTo(8);
        assertThat(converted.conversionError()).isNull();
        assertThat(inventories.findAll()).isEmpty(); // review edits never commit stock
        assertThat(movements.findAll()).isEmpty();
    }

    record Setup(GoodsReceipt receipt, ReceiptResponses.ReceiptLine line, java.util.List<String> codes) {}

    /** Delivery of 120 invoiced milk units; 9 uniquely-scanned cartons of 12 = 108 units accepted. */
    private Setup readyReceiptWithNineScannedCartons() {
        Product milk = product("Toned Milk 1L");
        User mgr = manager("priya-manager@test.local");

        ReceiptResponses.ReceiptSummary summary = receiptService.create(new ReceiptRequests.CreateReceipt(
                "Amul Dairy Distributors", "INV-2026-014", LocalDate.of(2026, 10, 18),
                "PO-8891", null), mgr);
        GoodsReceipt receipt = receiptService.require(summary.id());

        ReceiptResponses.ReceiptLine line = receiptService.addLine(receipt.getId(),
                new ReceiptRequests.AddLine("Toned Milk 1L", milk.getId(), null, 120, "UNIT",
                        null, BigDecimal.valueOf(64.00), BigDecimal.valueOf(7680.00),
                        "MILK-2026-10-B02", LocalDate.of(2026, 10, 25)), mgr);

        var labels = cartonService.generateLabels(receipt.getId(),
                new ReceiptRequests.GenerateLabels(line.id(), 12, 9));
        var codes = labels.stream().map(ReceiptResponses.Label::cartonId).toList();

        for (String code : codes) cartonService.scan(receipt.getId(), code);
        receiptService.recompute(receipt.getId());

        // Manager accepts the 108 scanned units of 120 invoiced, with an explicit reason.
        receiptService.updateLine(receipt.getId(), line.id(),
                new ReceiptRequests.UpdateLine(null, 120, null, null, null, null, null, null,
                        108, null, "Short delivery verified on floor"), mgr);
        receiptService.recompute(receipt.getId());
        return new Setup(receiptService.require(receipt.getId()), line, codes);
    }

    @Test
    void confirmingAddsAcceptedUnitsExactlyOnce_withAuditedMovement() {
        Setup setup = readyReceiptWithNineScannedCartons();

        ReceiptResponses.ConfirmResult result = confirmationService.confirm(setup.receipt().getId());

        assertThat(receipts.findById(setup.receipt().getId()).orElseThrow().getStatus())
                .isEqualTo(ReceiptStatus.CONFIRMED);
        assertThat(result.committedStock()).hasSize(1);
        assertThat(result.committedStock().get(0).acceptedQuantity()).isEqualTo(108);
        assertThat(result.committedStock().get(0).newInventoryQuantity()).isEqualTo(108);
        assertThat(result.committedStock().get(0).newBatchQuantity()).isEqualTo(108);

        // batch quantity and the existing aggregate inventory agree, both exactly +108
        var batch = batches.findAll().stream()
                .filter(b -> "MILK-2026-10-B02".equalsIgnoreCase(b.getBatchCode())).findFirst().orElseThrow();
        assertThat(batch.getQuantityOnHand()).isEqualTo(108);
        assertThat(inventoryOf(milkOf(setup)).getQuantity()).isEqualTo(108);

        // one positive audited stock movement referencing the receipt
        var historic = movements.findByReferenceTypeAndReferenceIdOrderByIdAsc("GOODS_RECEIPT", setup.receipt().getId());
        assertThat(historic).hasSize(1);
        assertThat(historic.get(0).getQuantityDelta()).isEqualTo(108);
        assertThat(historic.get(0).getType()).isEqualTo(MovementType.RECEIPT);
        assertThat(historic.get(0).getReferenceId()).isEqualTo(setup.receipt().getId());
    }

    @Test
    void repeatedConfirmationIsIdempotent_stockNeverDoubles() {
        Setup setup = readyReceiptWithNineScannedCartons();
        confirmationService.confirm(setup.receipt().getId());

        ReceiptResponses.ConfirmResult second = confirmationService.confirm(setup.receipt().getId());

        assertThat(receipts.findById(setup.receipt().getId()).orElseThrow().getStatus())
                .isEqualTo(ReceiptStatus.CONFIRMED);
        assertThat(second.committedStock()).isNotEmpty(); // replay of the earlier movement, not a new commit
        assertThat(inventoryOf(milkOf(setup)).getQuantity()).isEqualTo(108); // still 108, not 216
        assertThat(movements.findAll()).hasSize(1); // still a single audited movement
    }

    @Test
    void duplicateCartonScanIsRejectedByBackend() {
        Setup setup = readyReceiptWithNineScannedCartons();

        // A second scan of an already-registered carton must be refused.
        String firstCode = setup.codes().get(0);
        assertThatThrownBy(() -> cartonService.scan(setup.receipt().getId(), firstCode))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate scan");

        // An unknown carton code must be refused: QR data alone is never trusted.
        assertThatThrownBy(() -> cartonService.scan(setup.receipt().getId(), "CTN-99999999"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown carton code");

        // A carton issued for another receipt must be refused.
        assertThatThrownBy(() -> cartonService.scan(123456L, setup.codes().get(1)))
                .isInstanceOf(Exception.class);

        assertThat(cartons.findByCartonId(firstCode).orElseThrow().getStatus())
                .isEqualTo(com.smartmart.receiving.entity.CartonStatus.SCANNED);
        assertThat(movements.findAll()).isEmpty(); // no inventory side effects from scans
    }

    @Test
    void confirmationWithUnresolvedBlockersIsRejected_stockUnchanged() {
        Product milk = product("Toned Milk 1L");
        User mgr = manager("blocked-manager@test.local");
        ReceiptResponses.ReceiptSummary summary = receiptService.create(new ReceiptRequests.CreateReceipt(
                "Fresh Fields Produce", "INV-77", null, null, null), mgr);
        GoodsReceipt receipt = receiptService.require(summary.id());

        // Invoice says 100 units; only one carton of 10 was scanned; no manager decision yet.
        ReceiptResponses.ReceiptLine line = receiptService.addLine(receipt.getId(),
                new ReceiptRequests.AddLine("Tomatoes Grade A", milk.getId(), null, 100, "UNIT", null,
                        BigDecimal.valueOf(45), BigDecimal.valueOf(4500), "TOM-1",
                        LocalDate.of(2026, 10, 30)), mgr);
        var labels = cartonService.generateLabels(receipt.getId(),
                new ReceiptRequests.GenerateLabels(line.id(), 10, 10));
        cartonService.scan(receipt.getId(), labels.get(0).cartonId());
        receiptService.recompute(receipt.getId());

        assertThatThrownBy(() -> confirmationService.confirm(receipt.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("resolve the open blockers");

        assertThat(inventories.findAll()).isEmpty(); // no stock moved
        assertThat(movements.findAll()).isEmpty();
        assertThat(batches.findAll().stream().noneMatch(b -> b.getQuantityOnHand() > 0)).isTrue();
    }

    @Test
    void failedConfirmationRollsBackEveryStockChange() {
        Setup setup = readyReceiptWithNineScannedCartons();
        GoodsReceipt receipt = setup.receipt();
        User mgr = receipt.getCreatedBy();
        Product paneer = product("Paneer 200g");

        // A second, valid line on the same receipt.
        ReceiptResponses.ReceiptLine paneerLine = receiptService.addLine(receipt.getId(),
                new ReceiptRequests.AddLine("Paneer 200g", paneer.getId(), null, 10, "UNIT", null,
                        BigDecimal.valueOf(12), BigDecimal.valueOf(120), "PANEER-1",
                        LocalDate.of(2026, 10, 26)), mgr);
        receiptService.updateLine(receipt.getId(), paneerLine.id(),
                new ReceiptRequests.UpdateLine(null, 10, null, null, null, null, null, null,
                        10, null, null), mgr);

        // The second line's batch lock fails mid-transaction, after line 1 already committed work.
        AtomicInteger lockCalls = new AtomicInteger();
        doAnswer(inv -> {
            if (lockCalls.incrementAndGet() == 2) throw new RuntimeException("simulated mid-confirmation failure");
            return inv.callRealMethod();
        }).when(batchService).lock(any());

        assertThatThrownBy(() -> confirmationService.confirm(receipt.getId()))
                .isInstanceOf(Exception.class);

        // Line 1 already did its work inside the transaction - everything must roll back together.
        assertThat(inventories.findAll()).isEmpty();
        assertThat(movements.findAll()).isEmpty();
        assertThat(batches.findAll().stream().allMatch(b -> b.getQuantityOnHand() == 0)).isTrue();
        assertThat(receipts.findById(receipt.getId()).orElseThrow().getStatus())
                .isEqualTo(ReceiptStatus.READY_TO_CONFIRM);

        // The manager can retry safely on the next attempt.
        ReceiptResponses.ConfirmResult retry = confirmationService.confirm(receipt.getId());
        assertThat(retry.committedStock()).hasSize(2);
        assertThat(inventoryOf(milkOf(setup)).getQuantity()).isEqualTo(108);
        assertThat(inventoryOf(paneer).getQuantity()).isEqualTo(10);
        assertThat(receipts.findById(receipt.getId()).orElseThrow().getStatus()).isEqualTo(ReceiptStatus.CONFIRMED);
    }

    @Test
    void concurrentConfirmationsCannotDoubleAddStock() throws Exception {
        Setup setup = readyReceiptWithNineScannedCartons();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ReceiptResponses.ConfirmResult> confirm = () -> {
            start.await();
            return confirmationService.confirm(setup.receipt().getId());
        };
        Future<ReceiptResponses.ConfirmResult> a = pool.submit(confirm);
        Future<ReceiptResponses.ConfirmResult> b = pool.submit(confirm);
        start.countDown();
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // The strong invariant: no double-add even when two managers click at the same time.
        assertThat(receipts.findById(setup.receipt().getId()).orElseThrow().getStatus())
                .isEqualTo(ReceiptStatus.CONFIRMED);
        assertThat(inventoryOf(milkOf(setup)).getQuantity()).isEqualTo(108); // never 216
        assertThat(movements.findAll()).hasSize(1); // exactly one audited movement
        assertThat(movements.findAll().get(0).getQuantityDelta()).isEqualTo(108);
    }
}
