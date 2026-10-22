package com.smartmart.product.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.product.dto.ProductRequest;
import com.smartmart.product.dto.ProductResponse;
import com.smartmart.product.entity.Product;
import com.smartmart.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public ProductResponse create(ProductRequest request) {
        return toResponse(createEntity(request));
    }

    /** Creates a catalogue entry for a caller that must associate it in the same transaction. */
    @Transactional
    public Product createEntity(ProductRequest request) {
        if (productRepository.findByName(request.getName()).isPresent()) {
            throw BusinessException.conflict("Product with name '" + request.getName() + "' already exists");
        }

        Product product = Product.builder()
                .name(request.getName())
                .brand(request.getBrand())
                .category(request.getCategory())
                .price(request.getPrice())
                .aisle(request.getAisle())
                .shelf(request.getShelf())
                .description(request.getDescription())
                .build();

        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> findAll(String name, String brand, String category, Pageable pageable) {
        // Avoid nullable JPQL parameters here: PostgreSQL cannot infer a type for
        // expressions such as :name IS NULL in all driver/Hibernate combinations.
        // The catalogue is intentionally small; keep paging metadata accurate after filtering.
        var filtered = productRepository.findAll().stream()
                .filter(product -> matches(product.getName(), name))
                .filter(product -> matches(product.getBrand(), brand))
                .filter(product -> matches(product.getCategory(), category))
                .sorted(java.util.Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();
        int start = (int) Math.min(pageable.getOffset(), filtered.size());
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(start, end), pageable, filtered.size());
    }

    private boolean matches(String value, String filter) {
        return filter == null || filter.isBlank() || value.toLowerCase().contains(filter.toLowerCase());
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Product not found with id: " + id));
        return toResponse(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Product not found with id: " + id));

        if (!product.getName().equals(request.getName()) &&
                productRepository.findByName(request.getName()).isPresent()) {
            throw BusinessException.conflict("Product with name '" + request.getName() + "' already exists");
        }

        product.setName(request.getName());
        product.setBrand(request.getBrand());
        product.setCategory(request.getCategory());
        product.setPrice(request.getPrice());
        product.setAisle(request.getAisle());
        product.setShelf(request.getShelf());
        product.setDescription(request.getDescription());

        Product saved = productRepository.save(product);
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw BusinessException.notFound("Product not found with id: " + id);
        }
        productRepository.deleteById(id);
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .brand(product.getBrand())
                .category(product.getCategory())
                .price(product.getPrice())
                .aisle(product.getAisle())
                .shelf(product.getShelf())
                .description(product.getDescription())
                .createdAt(product.getCreatedAt())
                .build();
    }
}
