package com.smartmart.receiving.service;

import com.smartmart.product.entity.Product;
import com.smartmart.receiving.entity.MatchStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Invoice-line to catalogue matching. Matching is deliberately conservative: punctuation,
 * spacing, case and a matching catalogue brand prefix may differ, but meaningful name tokens
 * and package-size tokens must agree. Similar or ambiguous names are left for manager review.
 */
@Component
public class ProductMatcher {

    public record Match(Product product, MatchStatus status, boolean ambiguous, List<Long> ambiguousIds) {}

    public Match byProductId(List<Product> products, Long productId) {
        return products.stream().filter(p -> p.getId() != null && p.getId().equals(productId)).findFirst()
                .map(p -> new Match(p, MatchStatus.PRODUCT_ID, false, null)).orElse(null);
    }

    /** Exact normalized name or brand+name match, allowing the catalogue brand as an invoice prefix. */
    public Match bySkuOrName(List<Product> products, String sku, String description) {
        if (sku != null && !sku.isBlank()) {
            List<Product> skuMatches = distinct(products.stream().filter(p ->
                    normalize(p.getName()).equals(normalize(sku)) ||
                            normalize(join(p.getBrand(), p.getName())).equals(normalize(sku))).toList());
            if (skuMatches.size() == 1) return new Match(skuMatches.get(0), MatchStatus.SKU, false, null);
            if (skuMatches.size() > 1) return ambiguous(skuMatches);
        }
        if (description == null || description.isBlank()) {
            return new Match(null, MatchStatus.UNMATCHED, false, null);
        }

        String invoiceName = normalize(description);
        List<Product> candidates = distinct(products.stream().filter(p -> matches(p, invoiceName)).toList());
        if (candidates.size() == 1) return new Match(candidates.get(0), MatchStatus.NAME, false, null);
        if (candidates.size() > 1) return ambiguous(candidates);
        return new Match(null, MatchStatus.UNMATCHED, false, null);
    }

    private boolean matches(Product product, String invoiceName) {
        String name = normalize(product.getName());
        String brand = normalize(product.getBrand());
        if (name.isEmpty()) return false;
        if (name.equals(invoiceName) || normalize(join(brand, name)).equals(invoiceName)) return true;
        // Only drop a leading supplier token when it exactly matches the catalogue brand.
        // The remainder still has to match the complete catalogue name, including its size.
        return !brand.isEmpty() && invoiceName.startsWith(brand + " ")
                && invoiceName.substring(brand.length() + 1).equals(name);
    }

    private Match ambiguous(List<Product> candidates) {
        return new Match(null, MatchStatus.UNMATCHED, true,
                candidates.stream().map(Product::getId).filter(id -> id != null).distinct().toList());
    }

    private List<Product> distinct(List<Product> products) {
        Map<Long, Product> byId = new LinkedHashMap<>();
        List<Product> withoutId = new ArrayList<>();
        for (Product product : products) {
            if (product.getId() == null) withoutId.add(product);
            else byId.putIfAbsent(product.getId(), product);
        }
        List<Product> result = new ArrayList<>(byId.values());
        result.addAll(withoutId);
        return result;
    }

    private String join(String first, String second) {
        return ((first == null ? "" : first) + " " + (second == null ? "" : second)).trim();
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String sized = value.toLowerCase(Locale.ROOT)
                .replaceAll("(\\d+(?:\\.\\d+)?)\\s*[-.]?\\s*(kg|gm|g|ml|ltr|litres?|l)\\b", "$1$2");
        String cleaned = sized.replaceAll("[^a-z0-9 ]", " ").trim();
        return cleaned.replaceAll("\\s+", " ");
    }
}
