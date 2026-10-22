package com.smartmart.receiving.entity;

/** How an invoice line was matched to a SmartMart product. */
public enum MatchStatus {
    /** Matched by exact SKU/product code from the invoice. */
    SKU,
    /** Matched by the product identifier encoded on the invoice/QR data. */
    PRODUCT_ID,
    /** Matched by normalized product description. */
    NAME,
    /** Explicitly chosen by the manager. */
    MANUAL,
    /** No confident match; requires manager selection. */
    UNMATCHED
}
