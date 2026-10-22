package com.smartmart.receiving;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.inventory.repository.InventoryRepository;
import com.smartmart.product.entity.Product;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.receiving.dto.ReceiptRequests;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.entity.GoodsReceipt;
import com.smartmart.receiving.entity.ReceiptDocument;
import com.smartmart.receiving.entity.ReceiptStatus;
import com.smartmart.receiving.repository.GoodsReceiptRepository;
import com.smartmart.receiving.repository.ReceiptCartonRepository;
import com.smartmart.receiving.repository.ReceiptDocumentRepository;
import com.smartmart.receiving.repository.ReceiptLineRepository;
import com.smartmart.receiving.repository.StockMovementRepository;
import com.smartmart.receiving.service.InvoiceExtractionService;
import com.smartmart.receiving.service.ReceiptService;
import com.smartmart.user.entity.Role;
import com.smartmart.user.entity.User;
import com.smartmart.user.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invoice upload and extraction behaviour that can be verified without a local OCR engine:
 * searchable PDFs are read with native PDF text extraction, invalid uploads are rejected, and
 * a missing OCR engine degrades to a clear error with the draft still editable.
 *
 * The test profile disables OCR (smartmart.receiving.ocr.enabled=false), which is the same
 * "OCR not installed" path a developer hits on a machine without Tesseract.
 */
@SpringBootTest
class InvoiceExtractionIntegrationTest {

    @Autowired InvoiceExtractionService extractionService;
    @Autowired ReceiptService receiptService;
    @Autowired GoodsReceiptRepository receipts;
    @Autowired ReceiptDocumentRepository documents;
    @Autowired ReceiptLineRepository lines;
    @Autowired ReceiptCartonRepository cartons;
    @Autowired StockMovementRepository movements;
    @Autowired InventoryRepository inventories;
    @Autowired ProductRepository products;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;

    private User manager;
    private GoodsReceipt receipt;

    @BeforeEach
    void setUp() {
        manager = users.save(User.builder()
                .name("Priya").email("extract-manager@test.local")
                .password(passwordEncoder.encode("pw")).role(Role.MANAGER).build());
        var summary = receiptService.create(new ReceiptRequests.CreateReceipt(
                "Amul Dairy Distributors", null, null, null, null), manager);
        receipt = receiptService.require(summary.id());
    }

    @AfterEach
    void cleanup() {
        movements.deleteAll();
        cartons.deleteAll();
        documents.deleteAll();
        lines.deleteAll();
        receipts.deleteAll();
        products.deleteAll();
        users.deleteAll();
    }

    /** Builds a one-page PDF with selectable text (no OCR needed). */
    private byte[] searchableInvoicePdf() throws Exception {
        return searchableInvoicePdf(String.join("\n",
                "Supplier: Amul Dairy Distributors",
                "Invoice No: INV-2026-014",
                "Invoice Date: 2026-10-18",
                "",
                "Toned Milk 1L 10 pcs @64.00 640.00",
                "Paneer 200g 4 nos @280.00 1120.00",
                "Subtotal 1760.00",
                "Grand Total 1760.00"));
    }

    private byte[] searchableInvoicePdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                stream.setLeading(15f);
                stream.newLineAtOffset(40, 750);
                for (String line : text.split("\n")) {
                    stream.showText(line);
                    stream.newLine();
                }
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void searchablePdfIsExtractedWithNativeTextAndCreatesMatchedLines() throws Exception {
        products.save(Product.builder().name("Toned Milk 1L").brand("Amul").category("Dairy")
                .price(new BigDecimal("64.00")).build());
        products.save(Product.builder().name("Paneer 200g").brand("Amul").category("Dairy")
                .price(new BigDecimal("280.00")).build());

        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", searchableInvoicePdf());
        ReceiptResponses.DocumentInfo uploaded = extractionService.upload(receipt.getId(), file);
        assertThat(uploaded.processingStatus()).isEqualTo(ReceiptDocument.ProcessingStatus.UPLOADED);

        ReceiptResponses.DocumentInfo extracted = extractionService.extract(receipt.getId());
        assertThat(extracted.processingStatus()).isEqualTo(ReceiptDocument.ProcessingStatus.COMPLETED);
        assertThat(extracted.error()).isNull();

        var receiptLines = lines.findByReceiptIdOrderByIdAsc(receipt.getId());
        assertThat(receiptLines).hasSize(2);
        assertThat(receiptLines.get(0).getDescription()).containsIgnoringCase("milk");
        assertThat(receiptLines.get(0).getInvoiceQuantity()).isEqualTo(10);
        assertThat(receiptLines.get(0).getProduct()).isNotNull(); // matched against the catalogue
        assertThat(receiptLines.get(0).getUnitCost()).isEqualByComparingTo("64.00");
        assertThat(receiptLines.get(1).getInvoiceQuantity()).isEqualTo(4);

        // header fields are filled in from the document when the manager left them blank
        GoodsReceipt updated = receipts.findById(receipt.getId()).orElseThrow();
        assertThat(updated.getInvoiceNumber()).isEqualTo("INV-2026-014");
        assertThat(updated.getStatus()).isEqualTo(ReceiptStatus.REVIEW_REQUIRED);

        // uploading and extracting must never touch stock
        assertThat(inventories.findAll()).isEmpty();
        assertThat(movements.findAll()).isEmpty();
    }

    @Test
    void searchableNumberedAmulPdfPersistsFourCorrectReviewableLines() throws Exception {
        String text = String.join("\n", "Supplier: Amul Dairy Distributors", "Invoice No: INV-2026-014",
                "Invoice Date: 2026-10-18", "Invoice Items",
                "# Product Description Qty Unit Unit Price (INR) Line Total (INR)",
                "1 Amul Gold Milk 1L 10 pcs 64.00 640.00",
                "2 Amul Butter 500g 6 packs 275.00 1,650.00",
                "3 Amul Paneer 200g 12 packs 90.00 1,080.00",
                "4 Amul Cheese Slices 200g 8 packs 125.00 1,000.00",
                "Subtotal 4,370.00", "Demo GST (5%) 218.50", "Invoice Total (INR) 4,588.50");
        var file = new MockMultipartFile("file", "amul-invoice.pdf", "application/pdf", searchableInvoicePdf(text));
        extractionService.upload(receipt.getId(), file);
        var extracted = extractionService.extract(receipt.getId());
        assertThat(extracted.processingStatus()).isEqualTo(ReceiptDocument.ProcessingStatus.COMPLETED);

        var persisted = lines.findByReceiptIdOrderByIdAsc(receipt.getId());
        assertThat(persisted).hasSize(4);
        assertThat(persisted).extracting(com.smartmart.receiving.entity.ReceiptLine::getDescription)
                .containsExactly("Amul Gold Milk 1L", "Amul Butter 500g", "Amul Paneer 200g", "Amul Cheese Slices 200g");
        assertThat(persisted).extracting(com.smartmart.receiving.entity.ReceiptLine::getInvoiceQuantity)
                .containsExactly(10, 6, 12, 8);
        assertThat(persisted).extracting(com.smartmart.receiving.entity.ReceiptLine::getUnitOfMeasure)
                .containsExactly("UNIT", "PACK", "PACK", "PACK");
        assertThat(persisted).extracting(com.smartmart.receiving.entity.ReceiptLine::getUnitCost)
                .containsExactly(new BigDecimal("64.00"), new BigDecimal("275.00"), new BigDecimal("90.00"), new BigDecimal("125.00"));
        assertThat(persisted).extracting(com.smartmart.receiving.entity.ReceiptLine::getLineTotal)
                .containsExactly(new BigDecimal("640.00"), new BigDecimal("1650.00"), new BigDecimal("1080.00"), new BigDecimal("1000.00"));
        assertThat(persisted).allMatch(line -> line.getMatchStatus() == com.smartmart.receiving.entity.MatchStatus.UNMATCHED);
        assertThat(receiptService.detail(receipts.findById(receipt.getId()).orElseThrow()).lines()).hasSize(4);
        assertThat(inventories.findAll()).isEmpty();
        assertThat(movements.findAll()).isEmpty();
    }

    @Test
    void unsupportedFileTypeIsRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.exe", "application/octet-stream",
                "MZ not an invoice".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> extractionService.upload(receipt.getId(), file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported file type");
        assertThat(documents.findAll()).isEmpty();
    }

    @Test
    void emptyUploadIsRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> extractionService.upload(receipt.getId(), file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void corruptPdfIsRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf",
                "%PDF-1.4 this is not a real pdf body".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> extractionService.upload(receipt.getId(), file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("corrupt");
        assertThat(documents.findAll()).isEmpty();
    }

    @Test
    void oversizedUploadIsRejected() {
        byte[] big = new byte[6 * 1024 * 1024]; // 6 MB, above the 5 MB test limit
        java.util.Arrays.fill(big, (byte) 'A');
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", big);
        assertThatThrownBy(() -> extractionService.upload(receipt.getId(), file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void imageWithoutOcrEngineFailsGracefullyAndKeepsTheDraftEditable() {
        // A tiny but valid PNG (1x1 pixel) - upload succeeds, extraction reports a clear error.
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        MockMultipartFile file = new MockMultipartFile("file", "invoice.png", "image/png", png);
        extractionService.upload(receipt.getId(), file);

        assertThatThrownBy(() -> extractionService.extract(receipt.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("manually");

        // The document is marked failed (not stuck in PROCESSING) and the receipt stays editable.
        var document = documents.findFirstByReceiptIdOrderByIdDesc(receipt.getId()).orElseThrow();
        assertThat(document.getProcessingStatus()).isEqualTo(ReceiptDocument.ProcessingStatus.FAILED);
        assertThat(document.getError()).isNotBlank();
        assertThat(receipts.findById(receipt.getId()).orElseThrow().getStatus())
                .isEqualTo(ReceiptStatus.DRAFT);
        assertThat(lines.findByReceiptIdOrderByIdAsc(receipt.getId())).isEmpty();
        assertThat(inventories.findAll()).isEmpty();
    }

    @Test
    void extractionWithoutAnUploadedDocumentIsRejected() {
        assertThatThrownBy(() -> extractionService.extract(receipt.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Upload a supplier invoice");
    }

    @Test
    void uploadIsRejectedForAConfirmedReceipt() {
        // A confirmed receipt is no longer editable: uploading must fail instead of silently working.
        GoodsReceipt confirmed = receipts.findById(receipt.getId()).orElseThrow();
        confirmed.setStatus(ReceiptStatus.CONFIRMED);
        receipts.save(confirmed);
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", new byte[]{1, 2, 3, 4});
        assertThatThrownBy(() -> extractionService.upload(receipt.getId(), file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no longer be edited");
    }
}
