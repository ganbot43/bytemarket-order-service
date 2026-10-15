package com.bytemarket.order.service;

import com.bytemarket.order.client.CatalogClient;
import com.bytemarket.order.messaging.StockPublisher;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.model.OrderItem;
import com.bytemarket.order.model.OrderStatusLog;
import com.bytemarket.order.repository.IOrderRepository;
import com.bytemarket.order.repository.IOrderStatusLogRepository;
import com.bytemarket.order.repository.IPaymentMethodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Cambios de estado del pedido y devolución de stock al cancelar. */
class OrderServiceTest {

    private IOrderRepository orderRepository;
    private IOrderStatusLogRepository statusLogRepository;
    private CatalogClient catalogClient;
    private StockPublisher stockPublisher;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(IOrderRepository.class);
        statusLogRepository = mock(IOrderStatusLogRepository.class);
        catalogClient = mock(CatalogClient.class);
        stockPublisher = mock(StockPublisher.class);
        orderService = new OrderService(orderRepository, statusLogRepository,
                mock(IPaymentMethodRepository.class), mock(CartService.class), catalogClient,
                stockPublisher);

        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Order pedido(String estado) {
        Order o = new Order();
        o.setId(1);
        o.setOrderCode("BM-20261004-1234");
        o.setStatus(estado);
        OrderItem item = new OrderItem();
        item.setProductId(7L);
        item.setQuantity(3);
        o.setItems(List.of(item));
        return o;
    }

    @Test
    @DisplayName("avanza el estado y deja el registro en el historial")
    void avanzaEstado() {
        Order o = pedido("pending");

        Order guardado = orderService.changeStatus(o, "payment_validated", "Pago verificado", 99L);

        assertEquals("payment_validated", guardado.getStatus());
        assertNotNull(guardado.getUpdatedAt());

        ArgumentCaptor<OrderStatusLog> log = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(statusLogRepository).save(log.capture());
        assertEquals("payment_validated", log.getValue().getStatus());
        assertEquals("Pago verificado", log.getValue().getComments());
        assertEquals(99L, log.getValue().getChangedByUserId());
    }

    @Test
    @DisplayName("rechaza un estado que no existe")
    void estadoInvalido() {
        assertThrows(CartService.CartException.class,
                () -> orderService.changeStatus(pedido("pending"), "entregado_ayer", null, 1L));
        assertThrows(CartService.CartException.class,
                () -> orderService.changeStatus(pedido("pending"), null, null, 1L));
        verifyNoInteractions(statusLogRepository);
    }

    @Test
    @DisplayName("repetir el mismo estado no genera registro duplicado")
    void mismoEstadoNoHaceNada() {
        Order o = pedido("pending");

        Order resultado = orderService.changeStatus(o, "pending", "otra vez", 1L);

        assertSame(o, resultado);
        verifyNoInteractions(statusLogRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelar devuelve el stock al catálogo")
    void cancelarDevuelveStock() {
        orderService.changeStatus(pedido("payment_validated"), "cancelled", "Cliente desistió", 5L);

        // Ya no se llama a catalog directamente: se publica en la cola, para
        // que la devolución no se pierda si catalog está caído.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> items = ArgumentCaptor.forClass(List.class);
        verify(stockPublisher).publicarEntrada(eq(1L), eq("BM-20261004-1234"), items.capture());
        verifyNoInteractions(catalogClient);

        assertEquals(1, items.getValue().size());
        assertEquals(7L, items.getValue().get(0).get("productId"));
        assertEquals(3, items.getValue().get(0).get("quantity"));
    }

    @Test
    @DisplayName("un pedido ya cancelado no devuelve el stock dos veces")
    void noDevuelveStockDosVeces() {
        // De "cancelled" a "cancelled" no hay cambio; y desde otro estado
        // hacia uno que no sea cancelado tampoco debe tocar el catálogo.
        orderService.changeStatus(pedido("cancelled"), "cancelled", null, 1L);
        orderService.changeStatus(pedido("pending"), "delivered", null, 1L);

        verify(stockPublisher, never()).publicarEntrada(any(), any(), any());
    }

    @Test
    @DisplayName("la cancelación se guarda antes de publicar el movimiento de stock")
    void cancelaAunqueLaColaTarde() {
        // StockPublisher absorbe sus propios fallos (si RabbitMQ no está, lo
        // registra y sigue), así que cancelar nunca depende de la mensajería.
        // Lo que sí debe cumplirse es el orden: primero se guarda el pedido.
        Order guardado = orderService.changeStatus(pedido("pending"), "cancelled", null, 1L);

        assertEquals("cancelled", guardado.getStatus());

        InOrder orden = inOrder(orderRepository, stockPublisher);
        orden.verify(orderRepository).save(any(Order.class));
        orden.verify(stockPublisher).publicarEntrada(any(), any(), any());
    }

    @Test
    @DisplayName("una nota vacía se guarda como null, no como cadena en blanco")
    void notaVacia() {
        orderService.changeStatus(pedido("pending"), "delivered", "   ", 1L);

        ArgumentCaptor<OrderStatusLog> log = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(statusLogRepository).save(log.capture());
        assertNull(log.getValue().getComments());
    }

    @Test
    @DisplayName("los seis estados del flujo son válidos")
    void estadosDelFlujo() {
        assertEquals(List.of("pending", "voucher_sent", "payment_validated",
                             "in_preparation", "delivered", "cancelled"), OrderService.ESTADOS);
    }

    @Test
    @DisplayName("texto() limpia espacios y convierte vacío en null")
    void utilidadTexto() {
        assertEquals("hola", OrderService.texto("  hola  "));
        assertNull(OrderService.texto("   "));
        assertNull(OrderService.texto(null));
    }
}
