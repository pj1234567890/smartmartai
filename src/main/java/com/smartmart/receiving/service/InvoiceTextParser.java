package com.smartmart.receiving.service;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modular parser that turns raw invoice text (from PDF text extraction or OCR) into a
 * structured draft invoice. It uses independent, identifiable field patterns rather than
 * brittle fixed line numbers or a single supplier regex. Unreadable or missing fields stay
 * missing and surface as warnings - they are never invented.
 */
@Component
public class InvoiceTextParser {

    private static final Pattern invoiceNoPattern = Pattern.compile(
            "(?i)invoice\\s*(?:no|number|num|#)?\\s*[:#.]?\\s*([a-z0-9][a-z0-9\\-/.]{1,19})");
    private static final Pattern poPattern = Pattern.compile(
            "(?i)(?:purchase\\s*order|p\\.?o\\.?|order\\s*ref)\\s*(?:no|number|#|ref)?\\s*[:#.]?\\s*([a-z0-9][a-z0-9\\-/.]{1,19})");
    private static final Pattern datePattern = Pattern.compile(
            "(?i)(?:invoice\\s*)?date\\s*[:#]?\\s*([0-9]{2,4}[-/.][0-9]{1,2}[-/.][0-9]{1,4}|[0-9]{1,2}\\s+[a-z]{3,9}\\s+[0-9]{2,4})");
    private static final Pattern batchPattern = Pattern.compile(
            "(?i)(?:batch|lot)\\s*(?:no|#)?\\s*[:#.]?\\s*([a-z0-9][a-z0-9\\-]{1,24})");
    private static final Pattern expiryPattern = Pattern.compile(
            "(?i)exp(?:iry)?(?:\\s*date)?\\s*[:#.]?\\s*([0-9]{2,4}[-/.][0-9]{1,2}[-/.][0-9]{1,4}|[0-9]{1,2}\\s+[a-z]{3,9}\\s+[0-9]{2,4})");
    private static final Pattern qtyKeywordPattern = Pattern.compile(
            "(?i)(\\d{1,5})\\s*(x|pcs|nos|units?|packs?|pkt|pack|cartons?|cases?|boxes?|bottles?|tins?|jars?)\\b");
    private static final Pattern priceAtPattern = Pattern.compile(
            "(?i)@\\s*(?:rs\\.?|inr|₹)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern moneyPattern = Pattern.compile("(?i)(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern plainDecimalPattern = Pattern.compile("\\b([0-9,]+\\.[0-9]{2})\\b");
    // Numbered table rows carry their own identity; keep the row number out of the product fields.
    private static final Pattern numberedProductCorePattern = Pattern.compile(
            "^\\s*\\d+\\s+(.+?)\\s+(\\d{1,5})\\s+(x|pcs?|nos|units?|packs?|pkt|cartons?|cases?|boxes?|bottles?|tins?|jars?)\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern numberedItemPattern = Pattern.compile(
            "^([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s+([0-9][0-9,]*(?:\\.[0-9]{1,2})?)$");
    private static final Pattern numberedRowPattern = Pattern.compile("^\\s*\\d+\\s+.*$");
    private static final Pattern summaryPattern = Pattern.compile(
            "(?i)^(?:(?:sub\\s*)?total|grand\\s*total|invoice\\s*total|net\\s*total|tax(?:able)?|gst|cgst|sgst|igst|vat|cess|amount\\s*(?:payable|due)|balance\\s*due|round(?:ing)?\\s*off)\\b.*$");
    private static final Pattern firstIntegerPattern = Pattern.compile("\\b(\\d{1,5})\\b(?!\\.\\d)");
    /** Leading uppercase SKU-style code, e.g. "SM-MILK-1L ". */
    private static final Pattern skuPattern = Pattern.compile("^([A-Z]{2,5}[A-Z0-9\\-/]{2,15})\\s+(.*)$");
    private static final Pattern leadingTextPattern = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9 .,\\-'&()/+]{3,80}?)\\s+(?:(?:rs\\.?|inr|₹)\\s*[0-9,]|[@*]|\\d)");
    /** Size suffix that belongs to the product description, e.g. "1L" in "Toned Milk 1L". */
    private static final Pattern sizeTokenPattern = Pattern.compile(
            "^(\\d{1,4}\\s?(?:l|kg|g|ml|ltr|gm))\\b", Pattern.CASE_INSENSITIVE);
    /** Lines that are table headers/totals/addresses and must not become items. */
    private static final Pattern noisePattern = Pattern.compile(
            "(?i)^(?:in\\s*words\\b|(?:bill|ship)\\s*(?:to|from)\\b|address\\b|phone\\b|email\\b|website\\b|thank\\s*you|terms\\b|page\\s*\\d|[uv]:\\s*).*$");
    private static final Pattern headerColPattern = Pattern.compile(
            "(?i)^(description|product|item|particulars)\\b.*\\b(qty|quantity|unit|rate|price|amount|total)\\b|^(qty|quantity|rate|price|amount)\\b.*\\b(description|product|item)\\b");

    /** One invoice line read from the document. */
    public record InvoiceItem(
            String description,
            String sku,
            Integer quantity,
            String unit,
            BigDecimal unitCost,
            BigDecimal lineTotal,
            String batchCode,
            LocalDate expiryDate
    ) {}

    /** Structured result of parsing one invoice document. */
    @Data
    public static class ParsedInvoice {
        private String supplierName;
        private String invoiceNumber;
        private LocalDate invoiceDate;
        private String purchaseOrderRef;
        private List<String> warnings = new ArrayList<>();
        private List<InvoiceItem> lines = new ArrayList<>();
    }

    public ParsedInvoice parse(String rawText) {
        ParsedInvoice invoice = new ParsedInvoice();
        List<InvoiceItem> items = new ArrayList<>();
        List<String> warnings = invoice.getWarnings();

        for (String line : normalizeLines(rawText)) {
            if (invoice.getSupplierName() == null && startsWith(line, "supplier", "vendor", "from")) {
                invoice.setSupplierName(clean(line.replaceFirst("(?i)^(supplier|vendor|from)\\s*[:#-]?", ""), 200));
                continue;
            }
            if (invoice.getInvoiceNumber() == null) {
                Matcher m = invoiceNoPattern.matcher(line);
                if (m.find()) { invoice.setInvoiceNumber(m.group(1).toUpperCase(Locale.ROOT).replace(" ", "")); continue; }
            }
            if (invoice.getPurchaseOrderRef() == null) {
                Matcher m = poPattern.matcher(line);
                if (m.find()) { invoice.setPurchaseOrderRef(m.group(1).toUpperCase(Locale.ROOT).replace(" ", "")); continue; }
            }
            if (invoice.getInvoiceDate() == null) {
                Matcher m = datePattern.matcher(line);
                if (m.find()) {
                    LocalDate parsed = parseFlexibleDate(m.group(1));
                    if (parsed != null) invoice.setInvoiceDate(parsed);
                    else warnings.add("Could not interpret the invoice date: " + m.group(1));
                    continue;
                }
            }
            if (isSummaryRow(line) || noisePattern.matcher(line).matches() || headerColPattern.matcher(line).matches()) continue;

            parseLine(line, items, warnings);
        }

        if (invoice.getSupplierName() == null) warnings.add("Supplier name not found - enter it manually.");
        if (invoice.getInvoiceNumber() == null) warnings.add("Invoice number not found - enter it manually.");
        if (invoice.getInvoiceDate() == null) warnings.add("Invoice date not found - enter it manually.");
        if (items.isEmpty()) warnings.add("No product line items could be read - add the invoice lines manually.");
        invoice.setLines(items);
        return invoice;
    }

    private void parseLine(String original, List<InvoiceItem> items, List<String> warnings) {
        String line = original.trim();
        if (line.length() < 4) return;

        Matcher numbered = numberedProductCorePattern.matcher(line);
        if (numbered.matches()) {
            BigDecimal unitCost = null;
            BigDecimal lineTotal = null;
            Matcher amounts = numberedItemPattern.matcher(numbered.group(4).trim());
            if (amounts.matches()) {
                unitCost = parseMoney(amounts.group(1));
                lineTotal = parseMoney(amounts.group(2));
            } else if (!numbered.group(4).isBlank()) {
                warnings.add("Could not confidently assign all price fields on the line: " + original);
            }
            items.add(new InvoiceItem(clean(numbered.group(1), 500), null,
                    Integer.parseInt(numbered.group(2)), normalizeUnit(numbered.group(3)),
                    unitCost, lineTotal, null, null));
            return;
        }
        // A numbered table row that does not satisfy the complete product-row shape is
        // ambiguous. Do not reinterpret its row number or amounts as product data.
        if (numberedRowPattern.matcher(line).matches()) {
            warnings.add("Could not confidently read the numbered invoice line: " + original);
            return;
        }

        String description = null;
        String sku = null;
        Integer quantity = null;
        String unit = null;
        BigDecimal unitCost = null;
        BigDecimal lineTotal = null;
        String batchCode = null;
        LocalDate expiryDate = null;

        Matcher skuMatcher = skuPattern.matcher(line);
        if (skuMatcher.matches() && skuMatcher.group(2).matches(".*[A-Za-z].*")) {
            sku = skuMatcher.group(1);
            line = skuMatcher.group(2).trim();
        }

        Matcher descMatcher = leadingTextPattern.matcher(line);
        if (descMatcher.lookingAt()) {
            description = descMatcher.group(1).replaceAll("\\s+", " ").trim();
            line = line.substring(descMatcher.group(1).length()).trim();

            // A size suffix ("1L", "5kg", "200g") is part of the product name, not a quantity.
            Matcher sizeMatcher = sizeTokenPattern.matcher(line);
            if (sizeMatcher.find()) {
                description = description + " " + sizeMatcher.group(1).replaceAll("\\s+", "");
                line = line.substring(sizeMatcher.end()).trim();
            }
        }

        Matcher qtyMatcher = qtyKeywordPattern.matcher(line);
        if (qtyMatcher.find()) {
            quantity = Integer.parseInt(qtyMatcher.group(1));
            unit = normalizeUnit(qtyMatcher.group(2));
            line = removeToken(line, qtyMatcher.group(0)).trim();
        }

        Matcher priceMatcher = priceAtPattern.matcher(line);
        if (priceMatcher.find()) {
            unitCost = parseMoney(priceMatcher.group(1));
            line = removeToken(line, priceMatcher.group(0)).trim();
        }

        Matcher matcher = batchPattern.matcher(line);
        if (matcher.find()) {
            batchCode = matcher.group(1).toUpperCase(Locale.ROOT);
            line = removeToken(line, matcher.group(0)).trim();
        } else {
            matcher = batchPattern.matcher(original);
            if (matcher.find()) batchCode = matcher.group(1).toUpperCase(Locale.ROOT);
        }

        matcher = expiryPattern.matcher(line);
        if (matcher.find()) {
            expiryDate = parseFlexibleDate(matcher.group(1));
            if (expiryDate == null) warnings.add("Could not read an expiry date on the line \"" + original + "\".");
            else line = removeToken(line, matcher.group(0)).trim();
        }

        // Remaining money tokens: prefer a currency-prefixed amount, else the last plain decimal.
        BigDecimal lastMoney = null;
        Matcher moneyMatcher = moneyPattern.matcher(line);
        while (moneyMatcher.find()) { BigDecimal value = parseMoney(moneyMatcher.group(1)); if (value != null) lastMoney = value; }
        if (lastMoney == null) {
            Matcher decimalMatcher = plainDecimalPattern.matcher(line);
            while (decimalMatcher.find()) { BigDecimal value = parseMoney(decimalMatcher.group(1)); if (value != null) lastMoney = value; }
        }
        if (lastMoney != null) {
            lineTotal = lastMoney;
            if (unitCost == null && quantity != null && quantity > 0) {
                unitCost = lastMoney.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
            }
        }

        if (quantity == null && description != null) {
            warnings.add("Quantity could not be read for \"" + description + "\" - fill it in manually.");
        }

        if (description == null || description.replaceAll("[^a-zA-Z]", "").length() < 3) {
            return; // no usable product text on this line
        }
        items.add(new InvoiceItem(description, sku, quantity, unit, unitCost, lineTotal, batchCode, expiryDate));
    }

    private boolean isSummaryRow(String line) {
        if (!summaryPattern.matcher(line).matches()) return false;
        // Summary words are ordinary product words too. A row with an explicit quantity
        // and unit (or the full numbered item shape) is a product row, even if its name
        // starts with "Total", "Tax", or another summary keyword.
        return !numberedProductCorePattern.matcher(line).matches() && !qtyKeywordPattern.matcher(line).find();
    }

    private String removeToken(String line, String token) {
        if (token == null || token.isBlank()) return line;
        return line.replaceFirst(Pattern.quote(token), " ");
    }

    private String normalizeUnit(String raw) {
        String u = raw.toLowerCase(Locale.ROOT).replaceFirst("s$", "");
        return switch (u) {
            case "x", "pcs", "pc", "no", "unit" -> "UNIT";
            case "pack", "pkt" -> "PACK";
            case "carton", "case", "box" -> "CARTON";
            case "bottle", "tin", "jar" -> "UNIT";
            default -> u.toUpperCase(Locale.ROOT);
        };
    }

    private BigDecimal parseMoney(String raw) {
        try {
            String cleaned = raw.replace(",", "").trim();
            return cleaned.isEmpty() ? null : new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean startsWith(String line, String... keys) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String key : keys) if (lower.startsWith(key)) return true;
        return false;
    }

    private String clean(String value, int max) {
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private LocalDate parseFlexibleDate(String raw) {
        String value = raw.trim();
        for (String format : new String[]{"yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy",
                "dd MMM yyyy", "dd MMMM yyyy", "MMM dd, yyyy"}) {
            try {
                LocalDate parsed = LocalDate.parse(value, DateTimeFormatter.ofPattern(format, Locale.ENGLISH));
                if (parsed.isAfter(LocalDate.of(2000, 1, 1)) && parsed.isBefore(LocalDate.of(2100, 1, 1))) return parsed;
            } catch (Exception ignored) { /* try the next format */ }
        }
        return null;
    }

    private List<String> normalizeLines(String rawText) {
        List<String> result = new ArrayList<>();
        if (rawText == null) return result;
        for (String line : rawText.split("\\r?\\n")) {
            String trimmed = line.replaceAll("[\\u00A0\\t]+", " ").trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
