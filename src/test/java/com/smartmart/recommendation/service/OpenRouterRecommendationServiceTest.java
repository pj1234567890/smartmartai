package com.smartmart.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmart.ai.service.AiProvider;
import com.smartmart.inventory.entity.Inventory;
import com.smartmart.inventory.repository.InventoryRepository;
import com.smartmart.order.repository.CustomerOrderRepository;
import com.smartmart.product.dto.ProductResponse;
import com.smartmart.product.entity.Product;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.user.entity.User;
import com.smartmart.wishlist.repository.WishlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies that the OpenRouter-backed recommendation flow validates LLM output against
 * real SmartMart products and falls back to the existing rule-based logic. No real API calls.
 */
@ExtendWith(MockitoExtension.class)
class OpenRouterRecommendationServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CustomerOrderRepository orderRepository;

    @Mock
    private WishlistRepository wishlistRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private RecommendationService fallbackRecommendationService;

    @Mock
    private AiProvider aiProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OpenRouterRecommendationService service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new OpenRouterRecommendationService(
                productRepository, orderRepository, wishlistRepository,
                inventoryRepository, fallbackRecommendationService, aiProvider, objectMapper);
        user = User.builder().id(1L).name("Test User").email("t@example.com").password("x").build();
    }

    private Product product(long id, String name, String category) {
        return Product.builder().id(id).name(name).brand("Brand" + id).category(category)
                .price(new BigDecimal("1.50")).aisle("A1").shelf("S1").description("d")
                .createdAt(LocalDateTime.now()).build();
    }

    private Inventory inventory(Product product, int quantity, String discount) {
        return Inventory.builder().product(product).quantity(quantity).reorderLevel(0)
                .discountPercentage(discount == null ? null : new BigDecimal(discount)).build();
    }

    @Test
    void shouldFallBackToRuleBasedWhenProviderNotConfigured() {
        when(aiProvider.isConfigured()).thenReturn(false);
        List<ProductResponse> fallback = List.of(ProductResponse.builder().id(5L).name("Fallback").build());
        when(fallbackRecommendationService.forUser(user)).thenReturn(fallback);

        List<ProductResponse> result = service.getRecommendationsForUser(user);

        assertThat(result).isEqualTo(fallback);
    }

    @Test
    void shouldReturnValidLlmRecommendations() {
        when(aiProvider.isConfigured()).thenReturn(true);
        Product p7 = product(7, "Granola", "Breakfast");
        Product p9 = product(9, "Milk", "Dairy");
        when(productRepository.findAll()).thenReturn(List.of(p7, p9));
        when(productRepository.findById(7L)).thenReturn(Optional.of(p7));
        when(productRepository.findById(9L)).thenReturn(Optional.of(p9));
        when(inventoryRepository.findByProductId(7L)).thenReturn(Optional.of(inventory(p7, 10, "10")));
        when(inventoryRepository.findByProductId(9L)).thenReturn(Optional.of(inventory(p9, 3, null)));
        lenient().when(orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        lenient().when(wishlistRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(aiProvider.answer(anyString(), anyString())).thenReturn(
                "{\"recommendations\":[" +
                        "{\"productId\":7,\"reason\":\"Complements breakfast purchases.\"}," +
                        "{\"productId\":9,\"reason\":\"Popular dairy item.\"}" +
                        "]}");

        List<ProductResponse> result = service.getRecommendationsForUser(user);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ProductResponse::getId).containsExactly(7L, 9L);
        assertThat(result.get(0).getAvailableQuantity()).isEqualTo(10);
        assertThat(result.get(0).getDiscountPercentage()).isEqualByComparingTo("10");
    }

    @Test
    void shouldRejectInvalidDuplicateAndUnavailableIds() {
        when(aiProvider.isConfigured()).thenReturn(true);
        Product p7 = product(7, "Granola", "Breakfast");
        when(productRepository.findAll()).thenReturn(List.of(p7));
        when(productRepository.findById(7L)).thenReturn(Optional.of(p7));
        when(inventoryRepository.findByProductId(7L)).thenReturn(Optional.of(inventory(p7, 4, null)));
        lenient().when(orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        lenient().when(wishlistRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        // 999 = nonexistent, 7 duplicated, 88 nonexistent again
        when(aiProvider.answer(anyString(), anyString())).thenReturn(
                "{\"recommendations\":[" +
                        "{\"productId\":999,\"reason\":\"bad id\"}," +
                        "{\"productId\":7,\"reason\":\"good\"}," +
                        "{\"productId\":7,\"reason\":\"duplicate\"}," +
                        "{\"productId\":88,\"reason\":\"also bad\"}" +
                        "]}");

        List<ProductResponse> result = service.getRecommendationsForUser(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(7L);
    }

    @Test
    void shouldFallBackWhenLlmResponseUnparseable() {
        when(aiProvider.isConfigured()).thenReturn(true);
        lenient().when(orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        lenient().when(wishlistRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(aiProvider.answer(anyString(), anyString())).thenReturn("not json at all");

        List<ProductResponse> fallback = List.of(ProductResponse.builder().id(3L).name("RuleBased").build());
        when(fallbackRecommendationService.forUser(user)).thenReturn(fallback);

        List<ProductResponse> result = service.getRecommendationsForUser(user);

        assertThat(result).isEqualTo(fallback);
    }

    @Test
    void shouldFallBackWhenProviderThrows() {
        when(aiProvider.isConfigured()).thenReturn(true);
        when(aiProvider.answer(anyString(), anyString())).thenThrow(new RuntimeException("timeout"));

        List<ProductResponse> fallback = List.of(ProductResponse.builder().id(4L).name("RuleBased").build());
        when(fallbackRecommendationService.forUser(user)).thenReturn(fallback);

        List<ProductResponse> result = service.getRecommendationsForUser(user);

        assertThat(result).isEqualTo(fallback);
    }

    @Test
    void shouldFallBackWhenNoValidRecommendationsReturned() {
        when(aiProvider.isConfigured()).thenReturn(true);
        Product p7 = product(7, "Granola", "Breakfast");
        when(productRepository.findAll()).thenReturn(List.of(p7));
        when(inventoryRepository.findByProductId(7L)).thenReturn(Optional.of(inventory(p7, 4, null)));
        lenient().when(orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        lenient().when(wishlistRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(aiProvider.answer(anyString(), anyString())).thenReturn(
                "{\"recommendations\":[{\"productId\":12345,\"reason\":\"does not exist\"}]}");

        List<ProductResponse> fallback = List.of(ProductResponse.builder().id(7L).name("RuleBased").build());
        when(fallbackRecommendationService.forUser(user)).thenReturn(fallback);

        List<ProductResponse> result = service.getRecommendationsForUser(user);

        assertThat(result).isEqualTo(fallback);
    }

    @Test
    void relatedProductsShouldDelegateToRuleBasedLogic() {
        when(fallbackRecommendationService.related(7L)).thenReturn(List.of());

        assertThat(service.getRelatedProducts(7L)).isEmpty();
    }
}
