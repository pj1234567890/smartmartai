package com.smartmart.receiving.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceTextParserTest {

    private final InvoiceTextParser parser = new InvoiceTextParser();

    private String standardInvoice() {
        return String.join("\n",
                "Supplier: Amul Dairy Distributors",
                "Invoice No: INV-2026-014",
                "Invoice Date: 2026-10-18",
                "PO Ref: PO-8891",
                "",
                "Qty Item                                       Rate       Amount",
                "--------------------------------------------------------------",
                "Toned Milk 1L 10 pcs @64.00 640.00 Batch MILK-2026-10-B02 Exp 2026-10-25",
                "Pintola Peanut Butter 400g 6 packs @299.00 1794.00",
                "Aashirvaad Wheat Flour 5kg 4 nos",
                "Subtotal 2434.00",
                "Tax GST 437.00",
                "Grand Total 2871.00",
                "Thank you for your business"
        );
    }

    @Test
    void extractsHeaderFields() {
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(standardInvoice());
        assertThat(invoice.getSupplierName()).isEqualTo("Amul Dairy Distributors");
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-2026-014");
        assertThat(invoice.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(invoice.getPurchaseOrderRef()).isEqualTo("PO-8891");
    }

    @Test
    void extractsLineItemsWithQuantitiesAndUnits() {
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(standardInvoice());
        List<InvoiceTextParser.InvoiceItem> items = invoice.getLines();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).description()).containsIgnoringCase("milk");
        assertThat(items.get(0).quantity()).isEqualTo(10);
        assertThat(items.get(0).unit()).isEqualTo("UNIT");
        assertThat(items.get(0).unitCost()).isEqualByComparingTo("64.00");
        assertThat(items.get(0).lineTotal()).isEqualByComparingTo("640.00");
        assertThat(items.get(1).quantity()).isEqualTo(6);
        assertThat(items.get(1).unit()).isEqualTo("PACK");
    }

    @Test
    void extractsBatchAndExpiryWhenPrinted() {
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(standardInvoice());
        InvoiceTextParser.InvoiceItem milk = invoice.getLines().get(0);
        assertThat(milk.batchCode()).isEqualTo("MILK-2026-10-B02");
        assertThat(milk.expiryDate()).isEqualTo(LocalDate.of(2026, 10, 25));
    }

    @Test
    void sizeSuffixStaysInTheDescription() {
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(standardInvoice());
        List<InvoiceTextParser.InvoiceItem> items = invoice.getLines();
        // The size is part of the product name ("Toned Milk 1L"), not the quantity. Dropping it
        // would break product matching against the catalogue ("Toned Milk 1L" != "Toned Milk").
        assertThat(items.get(0).description()).containsIgnoringCase("1L");
        assertThat(items.get(1).description()).containsIgnoringCase("400g");
        assertThat(items.get(2).description()).containsIgnoringCase("5kg");
    }

    @Test
    void totalsAndNoiseLinesDoNotBecomeItems() {
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(standardInvoice());
        boolean anyTotal = invoice.getLines().stream()
                .anyMatch(i -> i.description() != null
                        && (i.description().toLowerCase().contains("total") || i.description().toLowerCase().contains("tax")));
        assertThat(anyTotal).isFalse();
    }

    @Test
    void missingQuantityStaysMissingWithWarning() {
        String text = String.join("\n",
                "Supplier: Fresh Fields Produce",
                "Invoice No: INV-77",
                "Invoice Date: 12/09/2026",
                "Tomatoes Grade A @45.00 900.00"
        );
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(text);
        assertThat(invoice.getLines()).hasSize(1);
        assertThat(invoice.getLines().get(0).quantity()).isNull();
        assertThat(invoice.getWarnings()).anyMatch(w -> w.contains("Quantity could not be read"));
    }

    @Test
    void unreadableHeaderFieldsRemainMissingWithWarnings() {
        String text = "Some decorative text\nAnother line without fields";
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(text);
        assertThat(invoice.getSupplierName()).isNull();
        assertThat(invoice.getInvoiceNumber()).isNull();
        assertThat(invoice.getInvoiceDate()).isNull();
        assertThat(invoice.getWarnings())
                .anyMatch(w -> w.contains("Supplier name not found"))
                .anyMatch(w -> w.contains("Invoice number not found"))
                .anyMatch(w -> w.contains("No product line items"));
    }

    @Test
    void parsesEuropeanDateFormats() {
        String text = String.join("\n",
                "Supplier: Nestle India Ltd",
                "Invoice No: N-42",
                "Date: 2026-10-18",
                "Maggi Noodles 2 pack"
        );
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(text);
        assertThat(invoice.getSupplierName()).isEqualTo("Nestle India Ltd");
        assertThat(invoice.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 18));
    }

    @Test
    void skuStyleLeadingCodeIsCaptured() {
        String text = String.join("\n",
                "Supplier: Cadbury Distributors",
                "Invoice No: C-7",
                "SM-DARK-400 Dark Chocolate 70% 3 pcs @110.00 330.00"
        );
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(text);
        assertThat(invoice.getLines()).hasSize(1);
        assertThat(invoice.getLines().get(0).sku()).isEqualTo("SM-DARK-400");
        assertThat(invoice.getLines().get(0).quantity()).isEqualTo(3);
    }

    @Test
    void emptyOrNullTextIsHandledGracefully() {
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(null);
        assertThat(invoice.getLines()).isEmpty();
        assertThat(invoice.getWarnings()).isNotEmpty();
        assertThat(parser.parse("").getLines()).isEmpty();
    }

    @Test
    void unitCostDerivedFromLineTotalWhenMissing() {
        String text = String.join("\n",
                "Supplier: Tata Sampann",
                "Invoice No: T-11",
                "Toor Dal 1kg 4 nos 840.00"
        );
        InvoiceTextParser.ParsedInvoice invoice = parser.parse(text);
        InvoiceTextParser.InvoiceItem dal = invoice.getLines().get(0);
        assertThat(dal.quantity()).isEqualTo(4);
        assertThat(dal.lineTotal()).isEqualByComparingTo(new BigDecimal("840.00"));
        assertThat(dal.unitCost()).isEqualByComparingTo(new BigDecimal("210.00"));
    }

    private String numberedAmulInvoice() {
        return String.join("\n", "Invoice Items",
                "# Product Description Qty Unit Unit Price (INR) Line Total (INR)",
                "1 Amul Gold Milk 1L 10 pcs 64.00 640.00",
                "2 Amul Butter 500g 6 packs 275.00 1,650.00",
                "3 Amul Paneer 200g 12 packs 90.00 1,080.00",
                "4 Amul Cheese Slices 200g 8 packs 125.00 1,000.00",
                "Subtotal 4,370.00", "Demo GST (5%) 218.50", "Invoice Total (INR) 4,588.50");
    }

    @Test
    void parsesNumberedAmulRowsWithoutTurningInvoiceTotalIntoAProduct() {
        var invoice = parser.parse(numberedAmulInvoice());
        assertThat(invoice.getLines()).hasSize(4);
        assertLine(invoice.getLines().get(0), "Amul Gold Milk 1L", 10, "UNIT", "64.00", "640.00");
        assertLine(invoice.getLines().get(1), "Amul Butter 500g", 6, "PACK", "275.00", "1650.00");
        assertLine(invoice.getLines().get(2), "Amul Paneer 200g", 12, "PACK", "90.00", "1080.00");
        assertLine(invoice.getLines().get(3), "Amul Cheese Slices 200g", 8, "PACK", "125.00", "1000.00");
        assertThat(invoice.getLines()).extracting(InvoiceTextParser.InvoiceItem::description)
                .noneMatch(d -> d.matches("(?i).*(Subtotal|GST|Grand Total|Invoice Total|Amount Payable|Balance Due).*"));
        // This parser result model has no invoice-summary amount; financial totals stay out of lines.
    }

    @Test
    void excludesSummaryRowsAndRetainsProductsWhoseNamesContainOrdinarySummaryWords() {
        String text = String.join("\n", "Total Care Soap 2 pcs 10.00 20.00",
                "Tax Saver Biscuits 3 packs 15.00 45.00", "Subtotal 4,370.00", "Tax 5% 218.50",
                "GST (5%) 218.50", "Grand Total 4,588.50", "Invoice Total (INR) 4,588.50",
                "Amount Payable 4,588.50", "Balance Due 0.00");
        var invoice = parser.parse(text);
        assertThat(invoice.getLines()).hasSize(2);
        assertThat(invoice.getLines()).extracting(InvoiceTextParser.InvoiceItem::description)
                .containsExactly("Total Care Soap", "Tax Saver Biscuits");
    }

    @Test
    void malformedNumberedRowsAndUnrelatedTextAreReportedWithoutInventedFields() {
        var invoice = parser.parse("1 Amul Butter 500g 275.00\nInvoice Total (INR) 4,588.50\nDecorative text");
        assertThat(invoice.getLines()).isEmpty();
        assertThat(invoice.getWarnings()).anyMatch(w -> w.contains("Could not confidently read the numbered invoice line"));
        assertThat(invoice.getWarnings()).anyMatch(w -> w.contains("No product line items"));
    }

    @Test
    void keepsConfidentNumberedProductFieldsWhenOptionalAmountsAreIncomplete() {
        var invoice = parser.parse("1 Amul Butter 500g 6 packs 275.00");
        assertThat(invoice.getLines()).hasSize(1);
        assertThat(invoice.getLines().get(0).description()).isEqualTo("Amul Butter 500g");
        assertThat(invoice.getLines().get(0).quantity()).isEqualTo(6);
        assertThat(invoice.getLines().get(0).unit()).isEqualTo("PACK");
        assertThat(invoice.getLines().get(0).unitCost()).isNull();
        assertThat(invoice.getLines().get(0).lineTotal()).isNull();
        assertThat(invoice.getWarnings()).anyMatch(w -> w.contains("Could not confidently assign all price fields"));
    }

    private void assertLine(InvoiceTextParser.InvoiceItem item, String description, int quantity, String unit,
                            String unitCost, String lineTotal) {
        assertThat(item.description()).isEqualTo(description);
        assertThat(item.quantity()).isEqualTo(quantity);
        assertThat(item.unit()).isEqualTo(unit);
        assertThat(item.unitCost()).isEqualByComparingTo(unitCost);
        assertThat(item.lineTotal()).isEqualByComparingTo(lineTotal);
    }
}
