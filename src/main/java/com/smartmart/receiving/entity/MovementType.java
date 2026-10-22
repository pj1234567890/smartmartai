package com.smartmart.receiving.entity;

/** Audit category for stock movements. */
public enum MovementType {
    /** Positive stock added by a confirmed goods receipt. */
    RECEIPT,
    /** Manual inventory adjustment/restock. */
    ADJUSTMENT,
    /** Explicit reversal of a previous receipt or adjustment. */
    REVERSAL
}
