package com.bytemarket.order.service;

import com.bytemarket.order.client.CatalogClient;
import com.bytemarket.order.dto.CartItemDTO;
import com.bytemarket.order.model.Coupon;
import com.bytemarket.order.model.OrderItem;
import com.bytemarket.order.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Valoración del carrito.
 *
 * Lo que se protege aquí es la regla de negocio más delicada del checkout: el
 * navegador solo manda producto y cantidad, y el precio lo pone el catálogo.
 * Si esto se rompe, se puede comprar al precio que el cliente quiera.
 */
class CartServiceTest {

    private CatalogClient catalogClient;
    private CouponRepository couponRepository;
    private CartService cartService;

    @BeforeEach
    void setUp() {
        catalogClient = mock(CatalogClient.class);
        couponRepository = mock(CouponRepository.class);
        cartService = new CartService(catalogClient, couponRepository);
    }

    /** Respuesta del catálogo para un producto. */
    private static Map<String, Object> producto(int id, String nombre, double precio, int stock, boolean trackStock) {
        return Map.of("id", id, "name", nombre, "price", precio,
                "stock", stock, "trackStock", trackStock, "isActive", true);
    }

    // ─── El precio lo manda el catálogo ─────────────────────────

    @Test
    @DisplayName("el precio sale del catálogo, no del cuerpo que envía el cliente")
    void precioLoPoneElCatalogo() {
        when(catalogClient.resolve(anyList()))
                .thenReturn(List.of(producto(7, "Pantalla OLED", 49.46, 10, true)));

        CartService.PricedCart carrito = cartService.price(List.of(new CartItemDTO(7L, 3)));

        OrderItem linea = carrito.items().get(0);
        assertEquals(49.46, linea.getUnitPrice(), 0.001, "debe usar el precio del catálogo");
        assertEquals(3, linea.getQuantity());
        assertEquals(148.38, linea.getSubtotal(), 0.001);
        assertEquals(148.38, carrito.subtotal(), 0.001);
        assertEquals("Pantalla OLED", linea.getProductName(), "el nombre también viene del catálogo");
    }

    @Test
    @DisplayName("dos líneas del mismo producto se agrupan y el stock se valida sobre la suma")
    void agrupaLineasRepetidas() {
        when(catalogClient.resolve(anyList()))
                .thenReturn(List.of(producto(1, "Batería", 100.0, 5, true)));

        CartService.PricedCart carrito = cartService.price(
                List.of(new CartItemDTO(1L, 2), new CartItemDTO(1L, 3)));

        assertEquals(1, carrito.items().size(), "debe quedar una sola línea");
        assertEquals(5, carrito.items().get(0).getQuantity());
        assertEquals(500.0, carrito.subtotal(), 0.001);
    }

    // ─── Validaciones ───────────────────────────────────────────

    @Test
    @DisplayName("rechaza el carrito vacío")
    void carritoVacio() {
        assertThrows(CartService.CartException.class, () -> cartService.price(List.of()));
        assertThrows(CartService.CartException.class, () -> cartService.price(null));
        verifyNoInteractions(catalogClient);
    }

    @Test
    @DisplayName("ignora cantidades cero o negativas, y si no queda nada rechaza")
    void cantidadesInvalidas() {
        assertThrows(CartService.CartException.class,
                () -> cartService.price(List.of(new CartItemDTO(1L, 0), new CartItemDTO(2L, -5))));
    }

    @Test
    @DisplayName("no deja comprar más unidades de las que hay en stock")
    void stockInsuficiente() {
        when(catalogClient.resolve(anyList()))
                .thenReturn(List.of(producto(1, "Cargador 65W", 80.0, 2, true)));

        CartService.CartException e = assertThrows(CartService.CartException.class,
                () -> cartService.price(List.of(new CartItemDTO(1L, 5))));
        assertTrue(e.getMessage().contains("2"), "el mensaje debe decir cuántas quedan: " + e.getMessage());
    }

    @Test
    @DisplayName("sin control de stock se permite cualquier cantidad")
    void sinControlDeStock() {
        when(catalogClient.resolve(anyList()))
                .thenReturn(List.of(producto(1, "Servicio", 10.0, 0, false)));

        CartService.PricedCart carrito = cartService.price(List.of(new CartItemDTO(1L, 999)));
        assertEquals(9990.0, carrito.subtotal(), 0.001);
    }

    @Test
    @DisplayName("rechaza un producto que el catálogo ya no conoce")
    void productoInexistente() {
        when(catalogClient.resolve(anyList())).thenReturn(List.of());
        assertThrows(CartService.CartException.class,
                () -> cartService.price(List.of(new CartItemDTO(404L, 1))));
    }

    @Test
    @DisplayName("rechaza un producto desactivado")
    void productoInactivo() {
        when(catalogClient.resolve(anyList())).thenReturn(List.of(
                Map.of("id", 1, "name", "Descontinuado", "price", 10.0,
                       "stock", 50, "trackStock", true, "isActive", false)));

        CartService.CartException e = assertThrows(CartService.CartException.class,
                () -> cartService.price(List.of(new CartItemDTO(1L, 1))));
        assertTrue(e.getMessage().contains("Descontinuado"));
    }

    @Test
    @DisplayName("si el catálogo falla, da un mensaje al cliente y no revienta")
    void catalogoCaido() {
        when(catalogClient.resolve(anyList())).thenThrow(new RuntimeException("connection refused"));

        CartService.CartException e = assertThrows(CartService.CartException.class,
                () -> cartService.price(List.of(new CartItemDTO(1L, 1))));
        assertFalse(e.getMessage().contains("connection refused"),
                "no debe filtrar el error técnico al cliente");
    }

    // ─── Cupones ────────────────────────────────────────────────

    private static Coupon cupon(String code, String tipo, double valor) {
        Coupon c = new Coupon();
        c.setCode(code);
        c.setDiscountType(tipo);
        c.setDiscountValue(valor);
        c.setIsActive(1);
        c.setTimesUsed(0);
        return c;
    }

    @Test
    @DisplayName("sin cupón, descuento cero")
    void sinCupon() {
        assertEquals(0.0, cartService.applyCoupon(null, 100.0).discount(), 0.001);
        assertEquals(0.0, cartService.applyCoupon("   ", 100.0).discount(), 0.001);
        verifyNoInteractions(couponRepository);
    }

    @Test
    @DisplayName("cupón porcentual")
    void cuponPorcentual() {
        when(couponRepository.findByCode("BIENVENIDO10")).thenReturn(Optional.of(cupon("BIENVENIDO10", "PERCENT", 10.0)));

        CartService.AppliedCoupon aplicado = cartService.applyCoupon("bienvenido10", 200.0);

        assertEquals("BIENVENIDO10", aplicado.code(), "el código se normaliza a mayúsculas");
        assertEquals(20.0, aplicado.discount(), 0.001);
    }

    @Test
    @DisplayName("cupón de monto fijo")
    void cuponFijo() {
        when(couponRepository.findByCode("BYTE20")).thenReturn(Optional.of(cupon("BYTE20", "FIXED", 20.0)));
        assertEquals(20.0, cartService.applyCoupon("BYTE20", 200.0).discount(), 0.001);
    }

    @Test
    @DisplayName("el descuento nunca deja el total en negativo")
    void descuentoNoSuperaElSubtotal() {
        when(couponRepository.findByCode("BYTE20")).thenReturn(Optional.of(cupon("BYTE20", "FIXED", 500.0)));

        assertEquals(30.0, cartService.applyCoupon("BYTE20", 30.0).discount(), 0.001,
                "se recorta al subtotal, no puede pasar de ahí");
    }

    @Test
    @DisplayName("rechaza cupón inexistente, inactivo, vencido o agotado")
    void cuponesInvalidos() {
        when(couponRepository.findByCode("NOEXISTE")).thenReturn(Optional.empty());
        assertThrows(CartService.CartException.class, () -> cartService.applyCoupon("NOEXISTE", 100.0));

        Coupon inactivo = cupon("INACTIVO", "FIXED", 10.0);
        inactivo.setIsActive(0);
        when(couponRepository.findByCode("INACTIVO")).thenReturn(Optional.of(inactivo));
        assertThrows(CartService.CartException.class, () -> cartService.applyCoupon("INACTIVO", 100.0));

        Coupon vencido = cupon("VENCIDO", "FIXED", 10.0);
        vencido.setExpirationDate(LocalDate.now().minusDays(1));
        when(couponRepository.findByCode("VENCIDO")).thenReturn(Optional.of(vencido));
        assertThrows(CartService.CartException.class, () -> cartService.applyCoupon("VENCIDO", 100.0));

        Coupon agotado = cupon("AGOTADO", "FIXED", 10.0);
        agotado.setUsageLimit(5);
        agotado.setTimesUsed(5);
        when(couponRepository.findByCode("AGOTADO")).thenReturn(Optional.of(agotado));
        assertThrows(CartService.CartException.class, () -> cartService.applyCoupon("AGOTADO", 100.0));
    }

    @Test
    @DisplayName("usar un cupón incrementa su contador")
    void cuponCuentaUsos() {
        Coupon c = cupon("BYTE20", "FIXED", 20.0);
        when(couponRepository.findByCode("BYTE20")).thenReturn(Optional.of(c));

        cartService.applyCoupon("BYTE20", 100.0);

        assertEquals(1, c.getTimesUsed());
        verify(couponRepository).save(c);
    }

    // ─── preview: validar sin gastar ────────────────────────────

    @Test
    @DisplayName("validar un cupón NO lo gasta: el checkout puede mostrarlo sin consumirlo")
    void previewNoConsume() {
        Coupon c = cupon("BIENVENIDO10", "PERCENT", 10.0);
        when(couponRepository.findByCode("BIENVENIDO10")).thenReturn(Optional.of(c));

        CartService.AppliedCoupon aplicado = cartService.previewCoupon("BIENVENIDO10", 200.0);

        assertEquals(20.0, aplicado.discount(), 0.001, "debe calcular el mismo descuento");
        assertEquals(0, c.getTimesUsed(), "el contador NO debe moverse");
        verify(couponRepository, never()).save(any());
    }

    @Test
    @DisplayName("mirar un cupón de un solo uso diez veces no lo agota")
    void previewRepetidoNoAgota() {
        Coupon unico = cupon("UNICO", "FIXED", 15.0);
        unico.setUsageLimit(1);
        when(couponRepository.findByCode("UNICO")).thenReturn(Optional.of(unico));

        for (int i = 0; i < 10; i++) {
            assertEquals(15.0, cartService.previewCoupon("UNICO", 100.0).discount(), 0.001);
        }
        assertEquals(0, unico.getTimesUsed());

        // Y al confirmar la compra sí se consume, una sola vez.
        cartService.applyCoupon("UNICO", 100.0);
        assertEquals(1, unico.getTimesUsed());
    }

    @Test
    @DisplayName("preview aplica las mismas validaciones que el consumo")
    void previewValidaIgual() {
        Coupon vencido = cupon("VENCIDO", "FIXED", 10.0);
        vencido.setExpirationDate(LocalDate.now().minusDays(1));
        when(couponRepository.findByCode("VENCIDO")).thenReturn(Optional.of(vencido));

        assertThrows(CartService.CartException.class, () -> cartService.previewCoupon("VENCIDO", 100.0));
        assertEquals(0.0, cartService.previewCoupon(null, 100.0).discount(), 0.001);
    }
}
