package com.smartmart.order.service;

import com.smartmart.common.exception.BusinessException;
import com.smartmart.order.entity.CustomerOrder;
import com.smartmart.order.entity.OrderItem;
import com.smartmart.order.entity.OrderStatus;
import com.smartmart.order.repository.CustomerOrderRepository;
import com.smartmart.product.entity.Product;
import com.smartmart.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private CustomerOrderRepository orders;

    // other deps are not used in these tests
    @Mock
    private com.smartmart.order.repository.CartItemRepository carts;
    @Mock
    private com.smartmart.product.repository.ProductRepository products;
    @Mock
    private com.smartmart.inventory.repository.InventoryRepository inventory;

    @InjectMocks
    private OrderService service;

    private User user;

    @BeforeEach
    void setup() {
        user = User.builder().id(50L).name("User50").email("u50@example.com").password("x").build();
    }

    @Test
    void history_returns_mapped_orders() {
        CustomerOrder o = CustomerOrder.builder().id(1L).user(user).status(OrderStatus.CONFIRMED).totalAmount(new BigDecimal("5.00")).createdAt(LocalDateTime.now()).items(List.of()).build();
        when(orders.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of(o));

        var res = service.history(user);
        assertThat(res).hasSize(1);
        assertThat(res.get(0).getId()).isEqualTo(1L);
        assertThat(res.get(0).getTotalAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    void find_notFound_throws() {
        when(orders.findWithItemsById(99L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.find(user, 99L, false));
        assertThat(ex.getMessage()).contains("Order not found");
    }

    @Test
    void find_forbidden_for_other_user() {
        User other = User.builder().id(999L).name("Other").email("o@example.com").password("x").build();
        CustomerOrder o = CustomerOrder.builder().id(2L).user(other).status(OrderStatus.CONFIRMED).totalAmount(BigDecimal.ZERO).items(List.of()).createdAt(LocalDateTime.now()).build();
        when(orders.findWithItemsById(2L)).thenReturn(Optional.of(o));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.find(user, 2L, false));
        assertThat(ex.getMessage()).contains("This order belongs to another user");
    }

    @Test
    void find_allows_manager() {
        User other = User.builder().id(999L).name("Other").email("o@example.com").password("x").build();
        CustomerOrder o = CustomerOrder.builder().id(3L).user(other).status(OrderStatus.CONFIRMED).totalAmount(BigDecimal.ZERO).items(List.of()).createdAt(LocalDateTime.now()).build();
        when(orders.findWithItemsById(3L)).thenReturn(Optional.of(o));

        var res = service.find(user, 3L, true);
        assertThat(res).isNotNull();
        assertThat(res.getId()).isEqualTo(3L);
    }

    @Test
    void all_returns_mapped() {
        CustomerOrder o = CustomerOrder.builder().id(4L).user(user).status(OrderStatus.CONFIRMED).totalAmount(new BigDecimal("7.00")).createdAt(LocalDateTime.now()).items(List.of()).build();
        when(orders.findAllWithItems()).thenReturn(List.of(o));

        var res = service.all();
        assertThat(res).hasSize(1);
        assertThat(res.get(0).getId()).isEqualTo(4L);
    }
}
