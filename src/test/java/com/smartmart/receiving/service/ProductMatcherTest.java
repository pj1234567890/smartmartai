package com.smartmart.receiving.service;

import com.smartmart.product.entity.Product;
import com.smartmart.receiving.entity.MatchStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMatcherTest {

    private final ProductMatcher matcher = new ProductMatcher();

    private Product product(long id, String brand, String name) {
        return Product.builder().id(id).brand(brand).name(name).build();
    }

    @Test
    void matchesExactProductIdWithHighestPriority() {
        var catalogue = List.of(product(7L, "Amul", "Toned Milk 1L"));
        var match = matcher.byProductId(catalogue, 7L);
        assertThat(match).isNotNull();
        assertThat(match.product().getId()).isEqualTo(7L);
        assertThat(match.status()).isEqualTo(MatchStatus.PRODUCT_ID);
    }

    @Test
    void exactNameAndBrandNameMatchWithoutChangingTheCatalogueIdentity() {
        var catalogue = List.of(product(70L, "Amul", "Gold Milk 1L"));
        assertThat(matcher.bySkuOrName(catalogue, null, "Gold Milk 1L").product().getId()).isEqualTo(70L);
        assertThat(matcher.bySkuOrName(catalogue, null, "Amul Gold Milk 1L").product().getId()).isEqualTo(70L);
    }

    @Test
    void returnsNullWhenProductIdUnknown() {
        assertThat(matcher.byProductId(List.of(product(7L, "Amul", "Toned Milk 1L")), 99L)).isNull();
    }

    @Test
    void matchesExactSkuIgnoringCaseAndPunctuation() {
        var catalogue = List.of(product(3L, "Amul", "Toned Milk 1L"));
        var match = matcher.bySkuOrName(catalogue, "Amul Toned Milk 1L", null);
        assertThat(match.product()).isNotNull();
        assertThat(match.status()).isEqualTo(MatchStatus.SKU);
    }

    @Test
    void matchesNormalizedDescription() {
        var catalogue = List.of(product(5L, "Aashirvaad", "Wheat Flour 5kg"));
        var match = matcher.bySkuOrName(catalogue, null, "Wheat Flour 5KG");
        assertThat(match.product()).isNotNull();
        assertThat(match.product().getId()).isEqualTo(5L);
        assertThat(match.status()).isEqualTo(MatchStatus.NAME);
    }

    @Test
    void matchesSupplierPrefixedDescriptionToShorterCatalogueNameWithSameBrand() {
        var match = matcher.bySkuOrName(List.of(product(31L, "Amul", "Butter 500g")), null,
                "Amul Butter 500g");
        assertThat(match.product()).isNotNull();
        assertThat(match.product().getId()).isEqualTo(31L);
        assertThat(match.status()).isEqualTo(MatchStatus.NAME);
    }

    @Test
    void normalizesCaseWhitespaceAndHarmlessPunctuationWithoutLosingSize() {
        var match = matcher.bySkuOrName(List.of(product(32L, "Amul", "Gold Milk 1L")), null,
                "  AMUL   Gold-Milk   1 l  ");
        assertThat(match.product()).isNotNull();
        assertThat(match.product().getId()).isEqualTo(32L);
    }

    @Test
    void requiresTheSamePackageSizeAndDoesNotMatchGenericTokens() {
        var catalogue = List.of(product(33L, "Amul", "Butter 500g"));
        assertThat(matcher.bySkuOrName(catalogue, null, "Amul Butter 200g").product()).isNull();
        assertThat(matcher.bySkuOrName(catalogue, null, "Amul Butter").product()).isNull();
        assertThat(matcher.bySkuOrName(catalogue, null, "Amul Milk 500g").product()).isNull();
    }

    @Test
    void doesNotChooseBetweenConflictingPackageSizeCatalogueEntries() {
        var catalogue = List.of(product(34L, "Amul", "Butter 500g"), product(35L, "Amul", "Butter 1kg"));
        var noSize = matcher.bySkuOrName(catalogue, null, "Amul Butter");
        assertThat(noSize.product()).isNull();
        var conflictingSize = matcher.bySkuOrName(catalogue, null, "Amul Butter 200g");
        assertThat(conflictingSize.product()).isNull();
    }

    @Test
    void paneerMatchRequiresCatalogueBrandNameAndSizeToAgree() {
        var exactCatalogue = List.of(product(36L, "Amul", "Paneer 200g"));
        var valid = matcher.bySkuOrName(exactCatalogue, null, "Amul Paneer 200g");
        assertThat(valid.product()).isNotNull();
        assertThat(valid.product().getId()).isEqualTo(36L);

        var wrongSize = matcher.bySkuOrName(List.of(product(37L, "Amul", "Paneer 500g")), null,
                "Amul Paneer 200g");
        assertThat(wrongSize.product()).isNull();
    }

    @Test
    void doesNotMatchGoldMilkInvoiceToShorterMilkCatalogueEntry() {
        // This is the live catalogue shape checked for the uploaded receipt: brand Amul,
        // catalogue name "Milk 1L". The invoice adds the identity token "Gold".
        var match = matcher.bySkuOrName(List.of(product(38L, "Amul", "Milk 1L")), null,
                "Amul Gold Milk 1L");
        assertThat(match.product()).isNull();
        assertThat(match.ambiguous()).isFalse();
    }

    @Test
    void doesNotMergeSimilarButDistinctNames() {
        var catalogue = List.of(
                product(1L, "Amul", "Toned Milk 1L"),
                product(2L, "Amul", "Full Cream Milk 1L"));
        var match = matcher.bySkuOrName(catalogue, null, "Amul Milk 1L");
        assertThat(match.product()).isNull();
        assertThat(match.ambiguous()).isFalse(); // similar names are not merged silently
    }

    @Test
    void flagsAmbiguityWhenTwoCatalogueEntriesMatchExactly() {
        var catalogue = List.of(
                product(1L, "Amul", "Toned Milk"),
                product(2L, "Verka", "Toned Milk"));
        var match = matcher.bySkuOrName(catalogue, null, "Toned Milk");
        assertThat(match.product()).isNull();
        assertThat(match.ambiguous()).isTrue();
        assertThat(match.ambiguousIds()).containsExactly(1L, 2L);
    }

    @Test
    void unmatchedWhenNothingMatches() {
        var match = matcher.bySkuOrName(List.of(product(1L, "Amul", "Paneer 200g")), null, "Imported Olives");
        assertThat(match.product()).isNull();
        assertThat(match.ambiguous()).isFalse();
    }
}
