package com.smartmart.product.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.product.dto.ProductRequest;
import com.smartmart.product.dto.ProductResponse;
import com.smartmart.product.entity.Product;
import com.smartmart.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository);
    }

    @Test
    void shouldFetchAllProducts() {
        // Arrange
        Product product1 = Product.builder()
                .id(1L)
                .name("Apple")
                .brand("FreshFarm")
                .category("Fruit")
                .price(new BigDecimal("1.50"))
                .aisle("A1")
                .shelf("S1")
                .description("Fresh apples")
                .createdAt(LocalDateTime.now())
                .build();
        Product product2 = Product.builder()
                .id(2L)
                .name("Banana")
                .brand("Tropical")
                .category("Fruit")
                .price(new BigDecimal("0.75"))
                .aisle("A1")
                .shelf("S2")
                .description("Ripe bananas")
                .createdAt(LocalDateTime.now())
                .build();
        when(productRepository.findAll()).thenReturn(List.of(product1, product2));
        Pageable pageable = PageRequest.of(0, 10);

        // Act
        Page<ProductResponse> result = productService.findAll(null, null, null, pageable);

        // Assert
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getName()).isEqualTo("Apple");
        assertThat(result.getContent().get(1).getName()).isEqualTo("Banana");
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void shouldFetchProductByValidId() {
        // Arrange
        Product product = Product.builder()
                .id(1L)
                .name("Apple")
                .brand("FreshFarm")
                .category("Fruit")
                .price(new BigDecimal("1.50"))
                .aisle("A1")
                .shelf("S1")
                .description("Fresh apples")
                .createdAt(LocalDateTime.now())
                .build();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        // Act
        ProductResponse response = productService.findById(1L);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Apple");
        assertThat(response.getPrice()).isEqualByComparingTo("1.50");
    }

    @Test
    void shouldThrowNotFoundWhenFetchingNonexistentProduct() {
        // Arrange
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> productService.findById(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Product not found with id: 999");
    }

    @Test
    void shouldCreateValidProduct() {
        // Arrange
        ProductRequest request = new ProductRequest();
        request.setName("Orange");
        request.setBrand("CitrusCo");
        request.setCategory("Fruit");
        request.setPrice(new BigDecimal("2.00"));
        request.setAisle("A2");
        request.setShelf("S1");
        request.setDescription("Sweet oranges");

        Product savedProduct = Product.builder()
                .id(3L)
                .name("Orange")
                .brand("CitrusCo")
                .category("Fruit")
                .price(new BigDecimal("2.00"))
                .aisle("A2")
                .shelf("S1")
                .description("Sweet oranges")
                .createdAt(LocalDateTime.now())
                .build();
        when(productRepository.findByName("Orange")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        // Act
        ProductResponse response = productService.create(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(3L);
        assertThat(response.getName()).isEqualTo("Orange");
        assertThat(response.getBrand()).isEqualTo("CitrusCo");
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void shouldUpdateExistingProduct() {
        // Arrange
        Product existingProduct = Product.builder()
                .id(1L)
                .name("Apple")
                .brand("FreshFarm")
                .category("Fruit")
                .price(new BigDecimal("1.50"))
                .aisle("A1")
                .shelf("S1")
                .description("Fresh apples")
                .createdAt(LocalDateTime.now())
                .build();

        ProductRequest request = new ProductRequest();
        request.setName("Green Apple");
        request.setBrand("FreshFarm");
        request.setCategory("Fruit");
        request.setPrice(new BigDecimal("1.75"));
        request.setAisle("A1");
        request.setShelf("S1");
        request.setDescription("Crisp green apples");

        Product updatedProduct = Product.builder()
                .id(1L)
                .name("Green Apple")
                .brand("FreshFarm")
                .category("Fruit")
                .price(new BigDecimal("1.75"))
                .aisle("A1")
                .shelf("S1")
                .description("Crisp green apples")
                .createdAt(LocalDateTime.now())
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(existingProduct));
        when(productRepository.findByName("Green Apple")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class))).thenReturn(updatedProduct);

        // Act
        ProductResponse response = productService.update(1L, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getName()).isEqualTo("Green Apple");
        assertThat(response.getPrice()).isEqualByComparingTo("1.75");
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void shouldThrowConflictWhenCreatingProductWithDuplicateName() {
        // Arrange
        ProductRequest request = new ProductRequest();
        request.setName("Apple");
        request.setBrand("FreshFarm");
        request.setCategory("Fruit");
        request.setPrice(new BigDecimal("1.50"));

        when(productRepository.findByName("Apple")).thenReturn(Optional.of(new Product()));

        // Act & Assert
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void shouldThrowConflictWhenUpdatingProductToDuplicateName() {
        // Arrange
        Product existingProduct = Product.builder()
                .id(1L)
                .name("Apple")
                .build();
        Product anotherProduct = Product.builder()
                .id(2L)
                .name("Banana")
                .build();

        ProductRequest request = new ProductRequest();
        request.setName("Banana");

        when(productRepository.findById(1L)).thenReturn(Optional.of(existingProduct));
        when(productRepository.findByName("Banana")).thenReturn(Optional.of(anotherProduct));

        // Act & Assert
        assertThatThrownBy(() -> productService.update(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }
}