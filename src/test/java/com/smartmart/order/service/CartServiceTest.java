package com.smartmart.order.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.inventory.entity.Inventory;
import com.smartmart.inventory.repository.InventoryRepository;
import com.smartmart.order.dto.CartRequest;
import com.smartmart.order.dto.CartResponse;
import com.smartmart.order.dto.DeliveryDetailsRequest;
import com.smartmart.order.entity.CartItem;
import com.smartmart.order.entity.CustomerOrder;
import com.smartmart.order.entity.OrderItem;
import com.smartmart.order.entity.OrderStatus;
import com.smartmart.order.entity.DeliveryStatus;
import com.smartmart.order.repository.CartItemRepository;
import com.smartmart.order.repository.CustomerOrderRepository;
import com.smartmart.product.entity.Product;
import com.smartmart.product.repository.ProductRepository;
import com.smartmart.user.entity.User;
import com.smartmart.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartItemRepository carts;

    @Mock
    private CustomerOrderRepository orders;

    @Mock
    private ProductRepository products;

    @Mock
    private InventoryRepository inventory;

    @Mock
    private UserRepository users;

    @InjectMocks
    private OrderService service;

    private User user;

    @BeforeEach
    void setup() {
        user = User.builder().id(10L).name("U").email("u@example.com").password("x").build();
    }

    @Test
    void add_success() {
        Product p = Product.builder().id(1L).name("Apple").price(new BigDecimal("2.50")).build();
        Inventory inv = Inventory.builder().id(1L).product(p).quantity(10).reorderLevel(1).build();

        when(products.findById(1L)).thenReturn(Optional.of(p));
        when(inventory.findByProductId(1L)).thenReturn(Optional.of(inv));
        when(carts.findByUserIdAndProductId(user.getId(), 1L)).thenReturn(Optional.empty());

        CartItem saved = CartItem.builder().id(5L).user(user).product(p).quantity(2).build();
        when(carts.save(any())).thenReturn(saved);
        when(carts.findByUserId(user.getId())).thenReturn(List.of(saved));

        CartResponse res = service.add(user, new CartRequest(){ { setProductId(1L); setQuantity(2); } });

        assertThat(res).isNotNull();
        assertThat(res.getItems()).hasSize(1);
        CartResponse.Item it = res.getItems().get(0);
        assertThat(it.getProductId()).isEqualTo(1L);
        assertThat(it.getName()).isEqualTo("Apple");
        assertThat(it.getQuantity()).isEqualTo(2);
        assertThat(it.getAvailable()).isEqualTo(10);
        assertThat(it.getUnitPrice()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(res.getTotal()).isEqualByComparingTo(new BigDecimal("5.00"));

        verify(carts).save(any(CartItem.class));
    }

    @Test
    void add_productNotFound_throws() {
        when(products.findById(2L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.add(user, new CartRequest(){ { setProductId(2L); setQuantity(1); } }));
        assertThat(ex.getMessage()).contains("Product not found");
    }

    @Test
    void add_quantityExceedsInventory_throws() {
        Product p = Product.builder().id(3L).name("Banana").price(new BigDecimal("1.00")).build();
        Inventory inv = Inventory.builder().id(3L).product(p).quantity(3).reorderLevel(1).build();

        when(products.findById(3L)).thenReturn(Optional.of(p));
        when(inventory.findByProductId(3L)).thenReturn(Optional.of(inv));
        when(carts.findByUserIdAndProductId(user.getId(), 3L)).thenReturn(Optional.of(CartItem.builder().user(user).product(p).quantity(1).build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.add(user, new CartRequest(){ { setProductId(3L); setQuantity(3); } }));

        assertThat(ex.getMessage()).contains("Only 3 units of Banana are currently available");
    }

    @Test
    void update_quantityLessThanOne_removesItem() {
        doNothing().when(carts).deleteByUserIdAndProductId(user.getId(), 4L);
        when(carts.findByUserId(user.getId())).thenReturn(List.of());

        CartResponse res = service.update(user, 4L, 0);
        assertThat(res.getItems()).isEmpty();

        verify(carts).deleteByUserIdAndProductId(user.getId(), 4L);
    }

    @Test
    void checkout_emptyCart_throws() {
        when(carts.findByUserId(user.getId())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkout(user, delivery()));
        assertThat(ex.getMessage()).contains("Cart is empty");
    }

    @Test
    void checkout_insufficientInventory_throws() {
        Product p = Product.builder().id(6L).name("Milk").price(new BigDecimal("3.00")).build();
        Inventory inv = Inventory.builder().id(6L).product(p).quantity(1).reorderLevel(0).build();
        CartItem ci = CartItem.builder().id(7L).user(user).product(p).quantity(2).build();

        when(carts.findByUserId(user.getId())).thenReturn(List.of(ci));
        when(inventory.findByProductId(p.getId())).thenReturn(Optional.of(inv));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkout(user, delivery()));
        assertThat(ex.getMessage()).contains("Insufficient inventory for Milk");
    }

    @Test
    void checkout_success_savesOrder_and_clearsCart() {
        Product p = Product.builder().id(8L).name("Eggs").price(new BigDecimal("0.50")).build();
        Inventory inv = Inventory.builder().id(8L).product(p).quantity(10).reorderLevel(0).build();
        CartItem ci = CartItem.builder().id(9L).user(user).product(p).quantity(4).build();

        when(carts.findByUserId(user.getId())).thenReturn(List.of(ci));
        when(inventory.findByProductId(p.getId())).thenReturn(Optional.of(inv));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        ArgumentCaptor<CustomerOrder> captor = ArgumentCaptor.forClass(CustomerOrder.class);
        when(orders.save(captor.capture())).thenAnswer(i -> {
            CustomerOrder o = i.getArgument(0);
            o.setId(99L);
            return o;
        });

        var resp = service.checkout(user, delivery());

        assertThat(resp).isNotNull();
        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getSubtotalAmount()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(resp.getDeliveryFee()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(resp.getTotalAmount()).isEqualByComparingTo(new BigDecimal("52.00"));
        assertThat(resp.getDeliveryStatus()).isEqualTo(DeliveryStatus.PLACED);
        assertThat(resp.getPaymentStatus().name()).isEqualTo("PENDING");

        verify(orders).save(any(CustomerOrder.class));
        verify(carts).deleteByUserId(user.getId());

        CustomerOrder saved = captor.getValue();
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getDeliveryName()).isEqualTo("Test Shopper");
        assertThat(saved.getDeliveryMobile()).isEqualTo("9876543210");
        OrderItem oi = saved.getItems().get(0);
        assertThat(oi.getQuantity()).isEqualTo(4);
        assertThat(oi.getSubtotal()).isEqualByComparingTo(new BigDecimal("2.00"));
        // inventory quantity reduced
        assertThat(inv.getQuantity()).isEqualTo(6);
    }

    private DeliveryDetailsRequest delivery() {
        return new DeliveryDetailsRequest("Test Shopper", "9876543210", "12 Market Road", "Mumbai", "400001", null);
    }

    @Test
    void deliveryStatus_onlyAllowsNextStage() {
        CustomerOrder order = CustomerOrder.builder().id(12L).user(user).status(OrderStatus.CONFIRMED)
                .deliveryStatus(DeliveryStatus.PLACED).items(List.of()).build();
        when(orders.findWithItemsById(12L)).thenReturn(Optional.of(order));

        var updated = service.updateDeliveryStatus(12L, DeliveryStatus.PREPARING);
        assertThat(updated.getDeliveryStatus()).isEqualTo(DeliveryStatus.PREPARING);
        assertThrows(BusinessException.class, () -> service.updateDeliveryStatus(12L, DeliveryStatus.DELIVERED));
    }

    @Test
    void managerCanMarkOutForDeliveryOrderDeliveredWithoutMarkingPaymentPaid() {
        CustomerOrder order = CustomerOrder.builder().id(13L).user(user).status(OrderStatus.CONFIRMED)
                .deliveryStatus(DeliveryStatus.OUT_FOR_DELIVERY)
                .paymentStatus(com.smartmart.order.entity.PaymentStatus.PENDING).items(List.of()).build();
        when(orders.findWithItemsById(13L)).thenReturn(Optional.of(order));

        var updated = service.updateDeliveryStatus(13L, DeliveryStatus.DELIVERED);

        assertThat(updated.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(updated.getPaymentStatus()).isEqualTo(com.smartmart.order.entity.PaymentStatus.PENDING);
    }
}
