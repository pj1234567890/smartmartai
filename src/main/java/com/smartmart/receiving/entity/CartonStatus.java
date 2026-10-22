package com.smartmart.receiving.entity;

/** State of a single carton that has a generated label. */
public enum CartonStatus {
    /** Label printed; carton not yet scanned into a receipt. */
    ISSUED,
    /** Scanned and registered as received (declared quantity = units per carton). */
    SCANNED,
    /** Physically verified short; manager recorded the verified accepted units and a reason. */
    VERIFIED_SHORT,
    /** Marked damaged/rejected; excluded from accepted stock. */
    REJECTED
}
