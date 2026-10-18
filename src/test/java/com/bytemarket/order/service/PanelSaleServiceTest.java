package com.bytemarket.order.service;

import com.bytemarket.order.messaging.PedidoEventPublisher;
import com.bytemarket.order.messaging.StockPublisher;
import com.bytemarket.order.model.CashSession;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.model.OrderItem;
import com.bytemarket.order.repository.IOrderRepository;
import com.bytemarket.order.repository.IOrderStatusLogRepository;
import com.bytemarket.order.repository.IPaymentMethodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Reglas de la venta registrada desde el panel.
 *
 * Lo que se comprueba aquí es justo lo que distingue la caja del checkout:
 * que el precio lo pone quien vende, que una venta de mostrador nace
 * entregada y pagada, y que el vuelto no puede ser negativo.
 */
class PanelSaleServiceTest {

    private IOrderRepository orderRepository;
    private IOrderStatusLogRepository statusLogRepository;
    private IPaymentMethodRepository paymentMethodRepository;
    private CartService cartService;
    private CashSessionService cashSessionService;
    private StockPublisher stockPublisher;
    private PedidoEventPublisher pedidoEventPublisher;
    private PanelSaleService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(IOrderRepository.class);
        statusLogRepository = mock(IOrderStatusLogRepository.class);
        paymentMethodRepository = mock(IPaymentMethodRepository.class);
        cartService = mock(CartService.class);
        stockPublisher = mock(StockPublisher.class);
        pedidoEventPublisher = mock(PedidoEventPublisher.class);

        cashSessionService = mock(CashSessionService.class);
        // Las ventas de caja exigen turno abierto; aquí se da por abierto
        // para poder probar el resto. Que lo exija tiene su propia prueba
        // en CashSessionServiceTest.
        CashSession turno = new CashSession();
        turno.setId(1);
        when(cashSessionService.exigirAbierta(any())).thenReturn(turno);

        service = new PanelSaleService(orderRepository, statusLogRepository, paymentMethodRepository,
                cartService, cashSessionService, stockPublisher, pedidoEventPublisher);

        when(orderRepository.findByOrderCode(anyString())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> {
            Order o = i.getArgument(0);
            if (o.getId() == null) o.setId(77);
            return o;
        });
        when(paymentMethodRepository.findAll()).thenReturn(List.of());
        // El catálogo dice que el producto 4 existe, está activo y hay 10.
        when(cartService.consultarCatalogo(any())).thenReturn(Map.of(4, Map.of(
                "id", 4, "name", "Batería Samsung A52", "price", 60.0,
                "isActive", true, "trackStock", true, "stock", 10)));
        when(cartService.validarVendible(any(), anyInt())).thenReturn("Batería Samsung A52");
        when(cartService.applyCoupon(any(), anyDouble())).thenReturn(new CartService.AppliedCoupon(null, 0d));
    }

    private static double anyDoubleValue() { return 0d; }

    private PanelSaleService.Peticion caja(double precio, int cantidad, Double efectivo) {
        return new PanelSaleService.Peticion("caja", null, null, null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, cantidad, precio)),
                0d, null, "efectivo", null, efectivo);
    }

    /** La misma venta, pero quien cobra es el cajero. */
    private PanelSaleService.Peticion conDescuento(double aMano) {
        return new PanelSaleService.Peticion("caja", null, null, null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, 1, 60.0)),
                aMano, null, "efectivo", null, null);
    }

    @Test
    @DisplayName("el cajero cobra el precio del catálogo y no otro")
    void elCajeroNoPoneElPrecio() {
        // El catálogo dice 60. Si pudiera cobrar 10, el arqueo cuadraría
        // perfecto al final del turno —el sistema esperaba 10— y los 50
        // restantes no aparecerían en ningún sitio. No es un descuadre que
        // se pueda ver contando dinero.
        CartService.CartException e = assertThrows(CartService.CartException.class,
                () -> service.registrar(caja(10.0, 1, null), 9L, "cajero"));

        assertTrue(e.getMessage().contains("60"), "dice cuánto cuesta: " + e.getMessage());
        assertTrue(e.getMessage().contains("jefe"), "y a quién pedírselo: " + e.getMessage());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("tampoco cobra de más")
    void elCajeroTampocoSube() {
        assertThrows(CartService.CartException.class,
                () -> service.registrar(caja(90.0, 1, null), 9L, "cajero"));
    }

    @Test
    @DisplayName("al precio de catálogo, el cajero vende sin problema")
    void elCajeroVendeAlPrecioDeLista() {
        Order o = service.registrar(caja(60.0, 2, null), 9L, "cajero").order();
        assertEquals(120.0, o.getTotal());
    }

    @Test
    @DisplayName("un descuento a mano lo autoriza el jefe, no el cajero")
    void elDescuentoEsDelJefe() {
        CartService.CartException e = assertThrows(CartService.CartException.class,
                () -> service.registrar(conDescuento(15), 9L, "cajero"));
        assertTrue(e.getMessage().contains("jefe"), e.getMessage());

        // Al jefe de ventas sí se lo deja: en el mostrador se regatea.
        Order o = service.registrar(conDescuento(15), 3L, "jefe_ventas").order();
        assertEquals(45.0, o.getTotal());
    }

    @Test
    @DisplayName("el precio lo pone quien vende, no el catálogo")
    void usaElPrecioDelVendedor() {
        // El catálogo dice 60.0; el vendedor cobra 45.
        Order o = service.registrar(caja(45.0, 2, null), 3L, "admin").order();

        assertEquals(90.0, o.getSubtotal());
        assertEquals(90.0, o.getTotal());
        assertEquals(45.0, o.getItems().get(0).getUnitPrice());
    }

    @Test
    @DisplayName("el nombre del producto lo pone el catálogo, no el panel")
    void elNombreVieneDelCatalogo() {
        Order o = service.registrar(caja(45.0, 1, null), 3L, "admin").order();
        assertEquals("Batería Samsung A52", o.getItems().get(0).getProductName());
    }

    @Test
    @DisplayName("una venta de caja nace entregada y pagada")
    void laCajaCobraYEntrega() {
        Order o = service.registrar(caja(50.0, 1, null), 3L, "admin").order();

        assertEquals("delivered", o.getStatus());
        assertEquals("paid", o.estadoDePago());
        assertEquals("caja", o.canal());
        assertEquals("Cliente de mostrador", o.getCustomerName());
        assertEquals(3L, o.getCreatedBy());
    }

    @Test
    @DisplayName("calcula el vuelto del efectivo recibido")
    void calculaElVuelto() {
        PanelSaleService.Resultado r = service.registrar(caja(30.0, 1, 50.0), 3L, "admin");

        assertEquals(50.0, r.order().getCashReceived());
        assertEquals(20.0, r.change());
    }

    @Test
    @DisplayName("si el efectivo no alcanza, se rechaza la venta")
    void rechazaEfectivoInsuficiente() {
        CartService.CartException e = assertThrows(CartService.CartException.class,
                () -> service.registrar(caja(100.0, 1, 50.0), 3L, "admin"));

        assertTrue(e.getMessage().toLowerCase().contains("no alcanza"), e.getMessage());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("descuenta stock por la MISMA cola que el checkout")
    void publicaElDescuentoDeStock() {
        service.registrar(caja(50.0, 3, null), 3L, "admin");

        verify(stockPublisher).publicarSalida(eq(77L), anyString(), any());
        verify(pedidoEventPublisher).publicarCreado(any(Order.class));
    }

    @Test
    @DisplayName("un pedido por WhatsApp exige nombre del cliente")
    void elPedidoExigeCliente() {
        PanelSaleService.Peticion sinNombre = new PanelSaleService.Peticion(
                "whatsapp", null, "987654321", null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, 1, 50.0)),
                0d, null, "yape", "pending", null);

        CartService.CartException e = assertThrows(CartService.CartException.class,
                () -> service.registrar(sinNombre, 3L, "admin"));
        assertTrue(e.getMessage().toLowerCase().contains("nombre"), e.getMessage());
    }

    @Test
    @DisplayName("un pedido sin cobrar queda pendiente, no entregado")
    void elPedidoSinCobrarQuedaPendiente() {
        PanelSaleService.Peticion porCobrar = new PanelSaleService.Peticion(
                "whatsapp", "Ana Quispe", "987654321", null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, 1, 50.0)),
                0d, null, "yape", "pending", null);

        Order o = service.registrar(porCobrar, 3L, "admin").order();

        assertEquals("pending", o.getStatus());
        assertEquals("pending", o.estadoDePago());
        assertEquals("whatsapp", o.canal());
        assertEquals("Ana Quispe", o.getCustomerName());
    }

    @Test
    @DisplayName("el descuento a mano no puede dejar el total en negativo")
    void elDescuentoNoPasaDelSubtotal() {
        PanelSaleService.Peticion regalado = new PanelSaleService.Peticion(
                "caja", null, null, null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, 1, 50.0)),
                999d, null, "efectivo", null, null);

        Order o = service.registrar(regalado, 3L, "admin").order();

        assertEquals(0.0, o.getTotal());
        assertEquals(50.0, o.getDiscountApplied());
    }

    @Test
    @DisplayName("el descuento a mano y el cupón se suman")
    void sumaDescuentoYCupon() {
        when(cartService.applyCoupon(eq("VERANO"), anyDouble()))
                .thenReturn(new CartService.AppliedCoupon("VERANO", 10d));

        PanelSaleService.Peticion conCupon = new PanelSaleService.Peticion(
                "caja", null, null, null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, 1, 100.0)),
                5d, "VERANO", "efectivo", null, null);

        Order o = service.registrar(conCupon, 3L, "admin").order();

        assertEquals(15.0, o.getDiscountApplied());
        assertEquals(85.0, o.getTotal());
        assertEquals("VERANO", o.getCouponCode());
    }

    @Test
    @DisplayName("dos líneas del mismo producto se agrupan en una")
    void agrupaLineasRepetidas() {
        PanelSaleService.Peticion repetido = new PanelSaleService.Peticion(
                "caja", null, null, null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, 2, 50.0),
                        new PanelSaleService.LineaDeVenta(4, 3, 50.0)),
                0d, null, "efectivo", null, null);

        Order o = service.registrar(repetido, 3L, "admin").order();

        assertEquals(1, o.getItems().size());
        assertEquals(5, o.getItems().get(0).getQuantity());
        // El stock se valida contra la suma, no contra cada línea.
        verify(cartService).validarVendible(any(), eq(5));
    }

    @Test
    @DisplayName("sin productos no se registra nada")
    void exigeAlMenosUnProducto() {
        PanelSaleService.Peticion vacia = new PanelSaleService.Peticion(
                "caja", null, null, null, null, null, null, List.of(),
                0d, null, "efectivo", null, null);

        assertThrows(CartService.CartException.class, () -> service.registrar(vacia, 3L, "admin"));
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("una forma de pago desconocida se rechaza")
    void rechazaFormaDePagoInvalida() {
        PanelSaleService.Peticion rara = new PanelSaleService.Peticion(
                "caja", null, null, null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, 1, 50.0)),
                0d, null, "trueque", null, null);

        assertThrows(CartService.CartException.class, () -> service.registrar(rara, 3L, "admin"));
    }

    @Test
    @DisplayName("un canal que no existe se rechaza")
    void rechazaCanalInvalido() {
        PanelSaleService.Peticion rara = new PanelSaleService.Peticion(
                "paloma-mensajera", "Ana", null, null, null, null, null,
                List.of(new PanelSaleService.LineaDeVenta(4, 1, 50.0)),
                0d, null, "efectivo", null, null);

        assertThrows(CartService.CartException.class, () -> service.registrar(rara, 3L, "admin"));
    }

    @Test
    @DisplayName("queda escrito en el historial de dónde salió la venta")
    void dejaRastroEnElHistorial() {
        service.registrar(caja(50.0, 1, 50.0), 3L, "admin");
        verify(statusLogRepository).saveAll(argThat(logs -> {
            var lista = new java.util.ArrayList<com.bytemarket.order.model.OrderStatusLog>();
            logs.forEach(lista::add);
            return lista.size() == 2
                    && lista.get(0).getComments().contains("Caja")
                    && lista.get(1).getComments().contains("Cobrado");
        }));
    }
}
