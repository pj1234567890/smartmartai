package com.smartmart.order.dto; import jakarta.validation.constraints.*; import lombok.*; @Data public class CartRequest{@NotNull private Long productId; @NotNull @Min(1) private Integer quantity;}
