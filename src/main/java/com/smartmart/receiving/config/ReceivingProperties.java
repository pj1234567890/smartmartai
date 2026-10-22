package com.smartmart.receiving.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration for the Goods Receiving module (upload storage limits, invoice file storage, OCR settings). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "smartmart.receiving")
public class ReceivingProperties {
    /** Directory for uploaded supplier invoice files, kept outside the database and the webapp root. */
    private String uploadDir = System.getProperty("user.home") + "/.smartmart/invoices";
    /** Business-level upload limit (the servlet layer has its own hard cap). */
    private int maxUploadMb = 10;
    private final Ocr ocr = new Ocr();

    @Getter
    @Setter
    public static class Ocr {
        /** Local Tesseract OCR. When unavailable, extraction falls back to manual entry. */
        private boolean enabled = true;
        /** Optional path to the tessdata folder (e.g. C:/Program Files/Tesseract-OCR/tessdata). Empty = auto-detect. */
        private String dataPath = "";
        private String language = "eng";
    }
}
