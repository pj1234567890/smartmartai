package com.smartmart.receiving.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.receiving.config.ReceivingProperties;
import com.smartmart.receiving.dto.ReceiptResponses;
import com.smartmart.receiving.entity.*;
import com.smartmart.receiving.repository.GoodsReceiptRepository;
import com.smartmart.receiving.repository.ReceiptDocumentRepository;
import com.smartmart.receiving.repository.ReceiptLineRepository;
import com.smartmart.product.entity.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Invoice upload and text extraction pipeline:
 * 1. validate and store the file on disk (never in the DB, never publicly accessible),
 * 2. prefer native PDF text for searchable PDFs,
 * 3. render image-only PDF pages / photos and run local Tesseract OCR when installed,
 * 4. parse the text into a draft invoice and create receipt lines product matches.
 * OCR unavailability degrades gracefully: the draft is preserved and manual entry remains possible.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceExtractionService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final Set<String> PDF_EXTENSIONS = Set.of("pdf");

    private final ReceivingProperties properties;
    private final GoodsReceiptRepository receipts;
    private final ReceiptDocumentRepository documents;
    private final ReceiptLineRepository lines;
    private final ProductRepository products;
    private final InvoiceTextParser parser;
    private final ReceiptService receiptService;
    private final PlatformTransactionManager transactionManager;

    @Transactional
    public ReceiptResponses.DocumentInfo upload(Long receiptId, MultipartFile file) {
        GoodsReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> BusinessException.notFound("Receipt not found"));
        receiptService.requireEditable(receipt);
        validateUpload(file);

        String original = safeFileName(file.getOriginalFilename());
        String ext = extension(original);
        String sourceType = PDF_EXTENSIONS.contains(ext) ? "PDF" : "IMAGE";

        try {
            byte[] bytes = file.getBytes();
            verifyContent(bytes, sourceType);

            Path dir = storageDir();
            String storageRef = UUID.randomUUID() + "." + ext;
            Files.write(dir.resolve(storageRef), bytes);

            ReceiptDocument document = ReceiptDocument.builder()
                    .receipt(receipt)
                    .originalFilename(original)
                    .mimeType(String.valueOf(file.getContentType()))
                    .fileSize((long) bytes.length)
                    .storageRef(storageRef)
                    .sourceType(sourceType)
                    .processingStatus(ReceiptDocument.ProcessingStatus.UPLOADED)
                    .build();
            documents.save(document);
            log.info("Stored invoice ({} bytes) for receipt {}", bytes.length, receiptId);
            return receiptService.documentInfo(receiptId);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("Invoice upload failed for receipt {}", receiptId, e);
            throw BusinessException.badRequest("The invoice upload failed - please try again");
        }
    }

    /**
     * Runs the extraction pipeline. This method is deliberately NOT wrapped in one long
     * transaction: the document status is written in its own (REQUIRES_NEW) transaction so a
     * failure is recorded as FAILED instead of being rolled back with the exception. The
     * extracted lines and the completed status are written together in a single transaction.
     */
    public ReceiptResponses.DocumentInfo extract(Long receiptId) {
        GoodsReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> BusinessException.notFound("Receipt not found"));
        receiptService.requireEditable(receipt);
        ReceiptDocument document = documents.findFirstByReceiptIdOrderByIdDesc(receiptId)
                .orElseThrow(() -> BusinessException.badRequest("Upload a supplier invoice image or PDF before extraction"));

        markProcessing(document.getId());

        try {
            String text = extractText(document);
            InvoiceTextParser.ParsedInvoice parsed = parser.parse(text);
            storeExtraction(receipt.getId(), document.getId(), text, parsed);
            return receiptService.documentInfo(receiptId);
        } catch (IngestException e) {
            // Preserve the draft and leave a clear, non-ambiguous state; manual entry remains available.
            markFailed(document.getId(), e.getMessage());
            log.warn("Invoice extraction failed for receipt {}: {}", receiptId, e.getMessage());
            throw BusinessException.badRequest(e.getMessage());
        } catch (Exception e) {
            markFailed(document.getId(), "Unexpected processing error - please retry or enter the invoice manually");
            log.error("Invoice extraction crashed for receipt {}", receiptId, e);
            throw BusinessException.badRequest("Extraction failed unexpectedly - you can retry or enter the invoice manually");
        }
    }

    /** Status updates run in their own transaction so they survive a failed extraction. */
    private void markProcessing(Long documentId) {
        newTransaction().executeWithoutResult(status -> {
            ReceiptDocument document = documents.findById(documentId).orElseThrow();
            document.setProcessingStatus(ReceiptDocument.ProcessingStatus.PROCESSING);
            document.setError(null);
            document.setWarnings(null);
            documents.save(document);
        });
    }

    private void markFailed(Long documentId, String error) {
        try {
            newTransaction().executeWithoutResult(status -> {
                ReceiptDocument document = documents.findById(documentId).orElseThrow();
                document.setProcessingStatus(ReceiptDocument.ProcessingStatus.FAILED);
                document.setError(error);
                documents.save(document);
            });
        } catch (RuntimeException nested) {
            log.error("Could not record the failed extraction status for document {}", documentId, nested);
        }
    }

    /** Persists the extraction result (document + receipt lines + receipt status) atomically. */
    private void storeExtraction(Long receiptId, Long documentId, String text, InvoiceTextParser.ParsedInvoice parsed) {
        newTransaction().executeWithoutResult(status -> {
            ReceiptDocument document = documents.findById(documentId).orElseThrow();
            document.setExtractedText(abbreviate(text));
            document.setWarnings(parsed.getWarnings().isEmpty() ? null : String.join("\n", parsed.getWarnings()));
            document.setProcessingStatus(ReceiptDocument.ProcessingStatus.COMPLETED);
            document.setError(null);
            GoodsReceipt receipt = receipts.findById(receiptId).orElseThrow();
            replaceExtractedLines(receipt, parsed);
            document.setReceipt(receipt);
            documents.save(document);
            receipt.setStatus(ReceiptStatus.REVIEW_REQUIRED);
            receipts.save(receipt);
        });
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /* ---------- extraction ---------- */

    private String extractText(ReceiptDocument document) throws IOException {
        Path file = storageDir().resolve(document.getStorageRef());
        byte[] bytes = Files.readAllBytes(file);
        if ("PDF".equals(document.getSourceType())) {
            try (PDDocument pdf = Loader.loadPDF(bytes)) {
                String nativeText = new PDFTextStripper().getText(pdf);
                if (nativeText != null && nativeText.replaceAll("\\s+", "").length() >= 80) {
                    return nativeText; // searchable PDF: skip OCR entirely
                }
                // Image-only PDF: render pages and OCR them.
                PDFRenderer renderer = new PDFRenderer(pdf);
                StringBuilder sb = new StringBuilder();
                for (int page = 0; page < Math.min(pdf.getNumberOfPages(), 3); page++) {
                    BufferedImage image = renderer.renderImageWithDPI(page, 300);
                    String pageText = ocrEngine().read(image);
                    sb.append(pageText).append('\n');
                }
                String ocrText = sb.toString();
                if (ocrText.replaceAll("\\s+", "").length() < 20) {
                    throw new IngestException("The PDF contains no readable text - try a clearer photo or enter the invoice manually");
                }
                return ocrText;
            } catch (IOException e) {
                throw new IngestException("The PDF file could not be read - it may be corrupt or encrypted");
            } catch (RuntimeException e) {
                if (e instanceof IngestException ie) throw ie;
                throw new IngestException("The PDF file could not be read - it may be corrupt or encrypted");
            }
        }
        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (Exception e) {
            throw new IngestException("The image file could not be read - it may be corrupt or in an unsupported format");
        }
        if (image == null) {
            throw new IngestException("The image file could not be read - it may be corrupt or in an unsupported format");
        }
        String text = ocrEngine().read(image);
        if (text.replaceAll("\\s+", "").length() < 20) {
            throw new IngestException("Almost no text was recognised in the photo - try a sharper, straight-on photo, or enter the invoice manually");
        }
        return text;
    }

    @FunctionalInterface
    interface OcrEngine {
        String read(BufferedImage image);
    }

    private OcrEngine ocrEngine() {
        ReceivingProperties.Ocr config = properties.getOcr();
        if (!config.isEnabled()) {
            throw new IngestException("Local OCR is disabled by configuration - enter the invoice details manually");
        }
        try {
            net.sourceforge.tess4j.Tesseract tesseract = new net.sourceforge.tess4j.Tesseract();
            if (config.getDataPath() != null && !config.getDataPath().isBlank()) {
                java.io.File dataDir = Paths.get(config.getDataPath()).toFile();
                if (!dataDir.isDirectory() || !new java.io.File(dataDir, "eng.traineddata").exists()) {
                    throw new IngestException("Tessdata folder not found at the configured path - install Tesseract language data or configure smartmart.receiving.ocr.data-path");
                }
                tesseract.setDatapath(config.getDataPath());
            } else {
                // Fall through: Tess4J looks up TESSDATA_PREFIX automatically; a missing engine throws below.
                if (System.getenv("TESSDATA_PREFIX") == null) {
                    throw new IngestException("Local Tesseract OCR is not configured - set the SMARTMART_TESSDATA data path, or enter the invoice details manually");
                }
                tesseract.setDatapath(System.getenv("TESSDATA_PREFIX"));
            }
            tesseract.setLanguage(config.getLanguage() != null ? config.getLanguage() : "eng");
            return image -> {
                try {
                    return tesseract.doOCR(image);
                } catch (net.sourceforge.tess4j.TesseractException e) {
                    throw new IngestException("OCR failed on the invoice image - enter the invoice details manually");
                }
            };
        } catch (IngestException e) {
            throw e;
        } catch (Throwable t) {
            // Missing natives or DLLs degrade gracefully rather than breaking the receipt workflow.
            log.warn("OCR engine unavailable: {}", String.valueOf(t.getMessage()));
            throw new IngestException("Local OCR is unavailable on this machine - enter the invoice details manually");
        }
    }

    /* ---------- line persistence ---------- */

    private void replaceExtractedLines(GoodsReceipt receipt, InvoiceTextParser.ParsedInvoice parsed) {
        List<ReceiptLine> existing = lines.findByReceiptIdOrderByIdAsc(receipt.getId());
        // Retry: automatic re-extraction replaces earlier extracted lines but keeps manual ones.
        List<ReceiptLine> removable = existing.stream().filter(l -> l.getSource() == LineSource.EXTRACTED).toList();
        lines.deleteAll(removable);

        List<Product> catalogue = products.findAll();
        ProductMatcher matcher = new ProductMatcher();
        for (InvoiceTextParser.InvoiceItem item : parsed.getLines()) {
            ReceiptLine line = ReceiptLine.builder()
                    .receipt(receipt)
                    .description(item.description())
                    .sku(item.sku())
                    .unitOfMeasure(item.unit() != null ? item.unit() : "UNIT")
                    .invoiceQuantity(item.quantity())
                    .unitCost(item.unitCost())
                    .lineTotal(item.lineTotal())
                    .batchCode(item.batchCode())
                    .expiryDate(item.expiryDate())
                    .source(LineSource.EXTRACTED)
                    .matchStatus(MatchStatus.UNMATCHED)
                    .discrepancyStatus(DiscrepancyStatus.UNRESOLVED)
                    .build();
            line.setOriginalDescription(item.description());
            line.setOriginalQuantity(item.quantity());
            line.setOriginalUnitCost(item.unitCost());
            line.setOriginalExpiryDate(item.expiryDate());

            ReceiptLine saved = lines.save(line);
            matchAndApply(saved, matcher, catalogue);
            lines.save(saved);
        }
        receipts.findById(receipt.getId()).ifPresent(r -> {
            if (parsed.getSupplierName() != null && r.getSupplierName() == null) r.setSupplierName(parsed.getSupplierName());
            if (parsed.getInvoiceNumber() != null && r.getInvoiceNumber() == null) r.setInvoiceNumber(parsed.getInvoiceNumber());
            if (parsed.getInvoiceDate() != null && r.getInvoiceDate() == null) r.setInvoiceDate(parsed.getInvoiceDate());
        });
    }

    private void matchAndApply(ReceiptLine line, ProductMatcher matcher, List<Product> catalogue) {
        if (line.getSku() != null) {
            // A purely numeric SKU may be a SmartMart product id.
            String digits = line.getSku().replaceAll("\\D", "");
            if (!digits.isEmpty() && digits.length() <= 12) {
                try {
                    long id = Long.parseLong(digits);
                    Product byId = catalogue.stream().filter(p -> p.getId() != null && p.getId() == id).findFirst().orElse(null);
                    if (byId != null) {
                        line.setProduct(byId);
                        line.setMatchStatus(MatchStatus.PRODUCT_ID);
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    // not a valid product id, continue with name matching
                }
            }
        }
        ProductMatcher.Match match = matcher.bySkuOrName(catalogue, line.getSku(), line.getDescription());
        if (match.product() != null) {
            line.setProduct(match.product());
            line.setMatchStatus(match.status());
            line.setAutoMatched(true);
        } else {
            line.setAutoMatched(match.ambiguous());
        }
    }

    /* ---------- helpers ---------- */

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw BusinessException.badRequest("Upload a non-empty invoice file");
        long maxBytes = (long) properties.getMaxUploadMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) throw BusinessException.badRequest("Invoice files may be at most " + properties.getMaxUploadMb() + " MB");
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extension(original).toLowerCase(Locale.ROOT);
        if (!(IMAGE_EXTENSIONS.contains(ext) || PDF_EXTENSIONS.contains(ext))) {
            throw BusinessException.badRequest("Unsupported file type: uploads may be JPG, JPEG, PNG photographs or PDF invoices");
        }
        String claimed = String.valueOf(file.getContentType()).toLowerCase(Locale.ROOT);
        boolean typeOk = (IMAGE_EXTENSIONS.contains(ext) && claimed.startsWith("image/")) || (ext.equals("pdf") && (claimed.contains("pdf") || claimed.contains("octet-stream") || claimed.isBlank()));
        if (!typeOk) throw BusinessException.badRequest("The file content does not look like an invoice image or PDF");
    }

    private void verifyContent(byte[] bytes, String sourceType) {
        if (bytes == null || bytes.length < 4) throw BusinessException.badRequest("The uploaded file is empty or corrupt");
        if ("PDF".equals(sourceType)) {
            try (PDDocument pdf = Loader.loadPDF(bytes)) {
                if (pdf.getNumberOfPages() < 1) throw BusinessException.badRequest("The PDF has no pages");
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw BusinessException.badRequest("The PDF file appears to be corrupt or unreadable");
            }
        } else {
            try {
                java.io.File temp = File.createTempFile("smartmart-invoice", ".img");
                try {
                    Files.write(temp.toPath(), bytes);
                    BufferedImage image = ImageIO.read(temp);
                    if (image == null) throw BusinessException.badRequest("The image appears to be corrupt or in an unsupported format");
                } finally {
                    Files.deleteIfExists(temp.toPath());
                }
            } catch (BusinessException e) {
                throw e;
            } catch (IOException e) {
                throw BusinessException.badRequest("The image file could not be validated");
            }
        }
    }

    private Path storageDir() throws IOException {
        Path dir = Paths.get(properties.getUploadDir()).toAbsolutePath().normalize();
        if (!dir.startsWith(Paths.get(System.getProperty("user.home")).toAbsolutePath())
                && !dir.startsWith(Paths.get("").toAbsolutePath())) {
            // Only allow storage outside the home/project when explicitly configured.
            log.info("Using configured invoice storage directory");
        }
        Files.createDirectories(dir);
        return dir;
    }

    private String safeFileName(String original) {
        if (original == null || original.isBlank()) return "invoice";
        String cleaned = original.replaceAll("[^A-Za-z0-9._\\- ]", "_").trim();
        // Path traversal defence: no separators, no leading dots.
        cleaned = cleaned.replaceAll("[/.]{2,}", "_");
        if (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        if (cleaned.isBlank()) cleaned = "invoice";
        return cleaned.length() > 200 ? cleaned.substring(cleaned.length() - 200) : cleaned;
    }

    private String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String abbreviate(String text) {
        if (text == null) return null;
        return text.length() > 20000 ? text.substring(0, 20000) : text;
    }

    /** Local marker so upload/extract failures stay clear without leaking internals. */
    static class IngestException extends RuntimeException {
        IngestException(String message) { super(message); }
    }
}
