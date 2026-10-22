package com.smartmart.receiving.entity;

/** Lifecycle of a goods receipt. */
public enum ReceiptStatus {
    /** Created; metadata, invoice lines and cartons may be edited. */
    DRAFT,
    /** Invoice extraction is in progress. */
    PROCESSING,
    /** Extracted or entered data needs the manager's review. */
    REVIEW_REQUIRED,
    /** All mandatory validations passed; the manager may confirm stock commit. */
    READY_TO_CONFIRM,
    /** Accepted stock committed exactly once; the receipt is immutable afterwards. */
    CONFIRMED,
    /** Abandoned before confirmation. */
    CANCELLED
}
