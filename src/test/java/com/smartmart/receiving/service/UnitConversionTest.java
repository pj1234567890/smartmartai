package com.smartmart.receiving.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnitConversionTest {

    @Test
    void unitQuantitiesPassThroughUnchanged() {
        assertThat(UnitConversion.convert(12, "UNIT", null).expectedUnits()).isEqualTo(12);
        assertThat(UnitConversion.convert(10, "pcs", null).expectedUnits()).isEqualTo(10);
        assertThat(UnitConversion.convert(6, "NOS", null).expectedUnits()).isEqualTo(6);
        assertThat(UnitConversion.convert(5, null, null).expectedUnits()).isEqualTo(5);
    }

    @Test
    void cartonsRequireExplicitUnitsPerCarton() {
        var missing = UnitConversion.convert(2, "CARTON", null);
        assertThat(missing.ok()).isFalse();
        assertThat(missing.error()).contains("units per carton");

        var known = UnitConversion.convert(2, "CARTON", 12);
        assertThat(known.expectedUnits()).isEqualTo(24);
    }

    @Test
    void packsConvertOnlyWhenUnitsPerCartonIsExplicit() {
        assertThat(UnitConversion.convert(6, "PACK", 20).expectedUnits()).isEqualTo(120);
        assertThat(UnitConversion.convert(6, "PACK", 1).expectedUnits()).isEqualTo(6);
        assertThat(UnitConversion.convert(6, "PACK", null).ok()).isFalse();
        assertThat(UnitConversion.convert(6, "PACK", null).error()).contains("stock-units-per-pack");
    }

    @Test
    void weightAndVolumeUnitsAreNeverGuessed() {
        var kg = UnitConversion.convert(4, "KG", null);
        assertThat(kg.ok()).isFalse();
        assertThat(kg.error()).containsIgnoringCase("cannot be converted");

        var litres = UnitConversion.convert(3, "L", 12);
        assertThat(litres.ok()).isFalse();
    }

    @Test
    void unknownUnitStaysUnconvertedWithError() {
        var unknown = UnitConversion.convert(9, "TON", null);
        assertThat(unknown.ok()).isFalse();
        assertThat(unknown.error()).containsIgnoringCase("unknown unit");
    }

    @Test
    void negativeQuantityIsRejected() {
        var negative = UnitConversion.convert(-1, "UNIT", null);
        assertThat(negative.ok()).isFalse();
        assertThat(negative.error()).containsIgnoringCase("negative");
    }

    @Test
    void unknownQuantityNeedsNoConversionYet() {
        // A missing quantity is a manager-review problem, not an invented conversion.
        var missing = UnitConversion.convert(null, "CARTON", 12);
        assertThat(missing.expectedUnits()).isNull();
        assertThat(missing.error()).isNull();
    }

    @Test
    void overflowIsGuarded() {
        var big = UnitConversion.convert(2000000000, "CARTON", 2);
        assertThat(big.ok()).isFalse();
    }
}
