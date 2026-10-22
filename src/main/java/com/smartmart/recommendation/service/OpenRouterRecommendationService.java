package com.smartmart.recommendation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmart.ai.service.AiProvider;
import com.smartmart.inventory.repository.InventoryRepository;
import com.smartmart.order.entity.CustomerOrder;
import com.smartmart.order.repository.CustomerOrderRepository;
import com.smartmart.product.dto.ProductResponse;
import com.smartmart.product.entity.Product;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.user.entity.User;
import com.smartmart.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OpenRouter-powered recommendation service that enhances the existing rule-based logic
 * with LLM-generated recommendations while maintaining full backward compatibility.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenRouterRecommendationService {

    private final ProductRepository productRepository;
    private final CustomerOrderRepository orderRepository;
    private final WishlistRepository wishlistRepository;
    private final InventoryRepository inventoryRepository;
    private final RecommendationService fallbackRecommendationService;
    private final AiProvider aiProvider;
    private final ObjectMapper objectMapper;

    /**
     * Get recommendations for a user, using OpenRouter if configured, otherwise falling back to rule-based logic.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> getRecommendationsForUser(User user) {
        // If OpenRouter is not configured, use fallback immediately
        if (!aiProvider.isConfigured()) {
            log.debug("OpenRouter not configured, using fallback recommendations");
            return fallbackRecommendationService.forUser(user);
        }

        try {
            // Build context for the LLM
            String context = buildRecommendationContext(user);
            
            // Ask OpenRouter for recommendations
            String llmResponse = aiProvider.answer(
                    "You are a SmartMart shopping assistant. Recommend products from the provided catalog based on the shopper's history and preferences. Return ONLY valid JSON in the exact format specified.",
                    context
            );

            // Parse and validate the LLM response
            List<ProductResponse> llmRecommendations = parseAndValidateRecommendations(llmResponse, user);
            
            // If we got valid recommendations from LLM, return them (up to 8)
            if (!llmRecommendations.isEmpty()) {
                log.info("OpenRouter returned {} valid recommendations for user {}", llmRecommendations.size(), user.getId());
                return llmRecommendations.stream().limit(8).toList();
            }
            
            // LLM returned no valid recommendations, fall back
            log.warn("OpenRouter returned no valid recommendations, falling back to rule-based logic");
            return fallbackRecommendationService.forUser(user);
            
        } catch (Exception e) {
            // Any failure - fall back to rule-based logic
            log.warn("OpenRouter recommendation failed, falling back to rule-based logic: {}", e.getMessage());
            return fallbackRecommendationService.forUser(user);
        }
    }

    /**
     * Build the structured context for the LLM with shopper and product information.
     */
    private String buildRecommendationContext(User user) {
        // Get shopper's purchase history
        List<CustomerOrder> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        Set<Long> boughtProductIds = orders.stream()
                .flatMap(o -> o.getItems().stream())
                .map(i -> i.getProduct().getId())
                .collect(Collectors.toSet());

        // Get wishlist
        List<Long> wishlistProductIds = wishlistRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(w -> w.getProduct().getId())
                .toList();

        // Get category preferences from purchase history
        Map<String, Long> categoryCounts = orders.stream()
                .flatMap(o -> o.getItems().stream())
                .collect(Collectors.groupingBy(
                        i -> i.getProduct().getCategory(),
                        Collectors.counting()
                ));
        
        // Add wishlist categories with higher weight
        wishlistRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).forEach(w -> 
            categoryCounts.merge(w.getProduct().getCategory(), 2L, Long::sum)
        );

        // Get candidate products (available, not already bought)
        List<Product> candidateProducts = productRepository.findAll().stream()
                .filter(p -> !boughtProductIds.contains(p.getId()))
                .filter(this::isAvailable)
                .toList();

        // Build product catalog for LLM (limit to reasonable number)
        List<Map<String, Object>> productCatalog = candidateProducts.stream()
                .limit(50) // Limit to avoid token limits
                .map(this::productToMap)
                .toList();

        // Build context JSON
        Map<String, Object> context = Map.of(
                "shopperId", user.getId(),
                "purchasedProductIds", boughtProductIds,
                "wishlistProductIds", wishlistProductIds,
                "categoryPreferences", categoryCounts,
                "candidateProducts", productCatalog
        );

        try {
            String contextJson = objectMapper.writeValueAsString(context);
            return "Shopper context and product catalog:\n" + contextJson + "\n\n" +
                    "Return ONLY a JSON object with this exact structure:\n" +
                    "{\n" +
                    "  \"recommendations\": [\n" +
                    "    {\"productId\": 123, \"reason\": \"Relevant to shopper's breakfast purchases and currently discounted.\"},\n" +
                    "    {\"productId\": 456, \"reason\": \"Complements products frequently purchased by this shopper.\"}\n" +
                    "  ]\n" +
                    "}\n" +
                    "Rules:\n" +
                    "- Only recommend product IDs from the candidateProducts list\n" +
                    "- Return 5-8 recommendations maximum\n" +
                    "- Each reason should be specific to the shopper's context\n" +
                    "- Do not include any other text, only the JSON object";
        } catch (Exception e) {
            log.error("Failed to build recommendation context", e);
            throw new RuntimeException("Failed to build recommendation context", e);
        }
    }

    /**
     * Convert a Product to a Map for JSON serialization.
     */
    private Map<String, Object> productToMap(Product product) {
        var inventory = inventoryRepository.findByProductId(product.getId()).orElse(null);
        return Map.of(
                "productId", product.getId(),
                "name", product.getName(),
                "brand", product.getBrand(),
                "category", product.getCategory(),
                "price", product.getPrice(),
                "aisle", product.getAisle(),
                "shelf", product.getShelf(),
                "description", product.getDescription(),
                "availableQuantity", inventory != null ? inventory.getQuantity() : 0,
                "discountPercentage", inventory != null && inventory.getDiscountPercentage() != null 
                        ? inventory.getDiscountPercentage() : BigDecimal.ZERO
        );
    }

    /**
     * Check if a product is available (in stock).
     */
    private boolean isAvailable(Product product) {
        return inventoryRepository.findByProductId(product.getId())
                .map(i -> i.getQuantity() > 0)
                .orElse(false);
    }

    /**
     * Parse the LLM response and validate product IDs against actual products.
     */
    private List<ProductResponse> parseAndValidateRecommendations(String llmResponse, User user) {
        try {
            // Extract JSON from response (in case there's extra text)
            String json = extractJson(llmResponse);
            JsonNode root = objectMapper.readTree(json);
            JsonNode recommendationsNode = root.path("recommendations");
            
            if (!recommendationsNode.isArray()) {
                log.warn("LLM response missing 'recommendations' array");
                return List.of();
            }

            // Get all valid candidate product IDs for validation
            Set<Long> validProductIds = productRepository.findAll().stream()
                    .filter(this::isAvailable)
                    .map(Product::getId)
                    .collect(Collectors.toSet());

            List<ProductResponse> validRecommendations = new ArrayList<>();
            Set<Long> seenProductIds = new HashSet<>();

            for (JsonNode rec : recommendationsNode) {
                Long productId = rec.path("productId").asLong();
                String reason = rec.path("reason").asText();

                // Validate product ID
                if (productId == 0 || !validProductIds.contains(productId)) {
                    log.debug("LLM recommended invalid/unavailable product ID: {}", productId);
                    continue;
                }

                // Check for duplicates
                if (seenProductIds.contains(productId)) {
                    log.debug("LLM recommended duplicate product ID: {}", productId);
                    continue;
                }

                // Get the product and map to response
                Product product = productRepository.findById(productId).orElse(null);
                if (product == null) {
                    continue;
                }

                var inventory = inventoryRepository.findByProductId(productId).orElse(null);
                ProductResponse response = ProductResponse.builder()
                        .id(product.getId())
                        .name(product.getName())
                        .brand(product.getBrand())
                        .category(product.getCategory())
                        .price(product.getPrice())
                        .aisle(product.getAisle())
                        .shelf(product.getShelf())
                        .description(product.getDescription())
                        .createdAt(product.getCreatedAt())
                        .availableQuantity(inventory != null ? inventory.getQuantity() : 0)
                        .discountPercentage(inventory != null && inventory.getDiscountPercentage() != null
                                ? inventory.getDiscountPercentage() : BigDecimal.ZERO)
                        .build();

                validRecommendations.add(response);
                seenProductIds.add(productId);

                // Limit to 8 recommendations
                if (validRecommendations.size() >= 8) {
                    break;
                }
            }

            return validRecommendations;

        } catch (Exception e) {
            log.warn("Failed to parse LLM recommendations: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract JSON object from a string that might contain extra text.
     */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * Get related products for a given product ID using rule-based logic (fallback).
     * This is used when OpenRouter is not configured or fails.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> getRelatedProducts(Long productId) {
        return fallbackRecommendationService.related(productId);
    }
}