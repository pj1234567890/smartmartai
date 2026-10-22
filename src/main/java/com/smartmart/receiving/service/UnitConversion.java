package com.smartmart.receiving.service;

/**
 * Converts invoice quantities to stock units - only when the conversion is explicit and
 * validated. Nothing is guessed: an unknown relationship (e.g. "2 kg" of flour) stays
 * unconverted and blocks reconciliation instead of inventing a number.
 */
public final class UnitConversion {

    private static final java.util.Set<String> PER_UNIT = java.util.Set.of(
            "UNIT", "UNITS", "PCS", "PC", "NOS", "NO", "EACH", "PIECE", "PIECES", "");
    private static final java.util.Set<String> PER_CARTON = java.util.Set.of("CARTON", "CARTONS", "CASE", "CASES", "BOX", "BOXES");
    private static final java.util.Set<String> UNCONVERTIBLE = java.util.Set.of(
            "KG", "KGS", "G", "GM", "GRAM", "GRAMS", "L", "LTR", "LITRE", "LITRES", "ML", "PACK", "PACKS", "PKT", "BOTTLE", "BOTTLES");

    private UnitConversion() {}

    public record Result(Integer expectedUnits, String error) {
        public boolean ok() { return expectedUnits != null; }
    }

    public static Result convert(Integer invoiceQuantity, String unitOfMeasure, Integer unitsPerCarton) {
        if (invoiceQuantity == null) return new Result(null, null); // quantity unknown; no conversion performed
        if (invoiceQuantity < 0) return new Result(null, "Invoice quantity cannot be negative");
        String uom = unitOfMeasure == null ? "" : unitOfMeasure.trim().toUpperCase();
        if (uom.isEmpty() || PER_UNIT.contains(uom)) {
            return new Result(invoiceQuantity, null);
        }
        if (PER_CARTON.contains(uom)) {
            if (unitsPerCarton == null || unitsPerCarton <= 0) {
                return new Result(null, "Unit of measure is " + uom + " but the units per carton is unknown - state it explicitly.");
            }
            long result = (long) invoiceQuantity * unitsPerCarton;
            if (result > Integer.MAX_VALUE) return new Result(null, "Converted quantity overflows");
            return new Result((int) result, null);
        }
        boolean packLike = uom.equals("PACK") || uom.equals("PACKS") || uom.equals("PKT");
        if (packLike && unitsPerCarton != null && unitsPerCarton > 0) {
            return new Result((int) Math.min((long) invoiceQuantity * unitsPerCarton, Integer.MAX_VALUE), null);
        }
        if (packLike) {
            return new Result(null, "No stock-unit conversion is configured for " + uom
                    + " - enter a verified stock-units-per-pack value or record the quantity in units.");
        }
        if (UNCONVERTIBLE.contains(uom)) {
            return new Result(null, "Unit '" + uom + "' cannot be converted to individual stock units - record the quantity in units instead.");
        }
        return new Result(null, "Unknown unit of measure '" + uom + "' - use units, cartons with units per carton, or leave it blank.");
    }
}
