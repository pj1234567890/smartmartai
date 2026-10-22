package com.smartmart.receiving.entity;

/** Reconciliation state of an invoice line after comparing invoice vs scanned quantities. */
public enum DiscrepancyStatus {
    /** Quantities agree, and the line is valid. */
    MATCHED,
    /** Invoice declares more units than scanned cartons. */
    SHORTAGE,
    /** Scanned cartons declare more units than the invoice. */
    SURPLUS,
    /** Manual conflict (unmatched, invalid quantity, missing batch/expiry, etc.). */
    UNRESOLVED,
    /** Manager has reviewed and explicitly accepted the quantity. */
    RESOLVED,
    /** Quantity explicitly marked damaged/rejected and excluded from stock. */
    REJECTED
}
