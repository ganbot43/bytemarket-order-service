package com.bytemarket.order.service;

import com.bytemarket.order.client.CatalogClient;
import com.bytemarket.order.dto.CartItemDTO;
import com.bytemarket.order.model.*;
import com.bytemarket.order.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final IOrderRepository orderRepository;
    private final IOrderStatusLogRepository statusLogRepository;
    private final IPaymentMethodRepository paymentMethodRepository;
    private final CartService cartService;
    private final CatalogClient catalogClient;
    private final com.bytemarket.order.messaging.StockPublisher stockPublisher;

    /** Estados por los que puede pasar un pedido, en orden de avance. */
    public static final List<String> ESTADOS = List.of(
            "pending", "voucher_sent", "payment_validated", "in_preparation", "delivered", "cancelled");

    // ─── Creación ───────────────────────────────────────────────

    @Transactional
    public Order create(Map<String, Object> form, Long userId, List<CartItemDTO> items) {
        CartService.PricedCart carrito = cartService.price(items);

        String nombre = texto(form.get("customerName"));
        if (nombre == null) throw new CartService.CartException("Necesitamos tu nombre para el envío");
        String telefono = texto(form.get("customerPhone"));
        if (telefono == null) throw new CartService.CartException("Necesitamos un teléfono de contacto");
        String direccion = texto(form.get("customerAddress"));
        if (direccion == null) throw new CartService.CartException("Necesitamos la dirección de entrega");

        Order order = new Order();
        order.setOrderCode(generarCodigo());
        order.setUserId(userId);
        order.setCustomerName(nombre);
        order.setCustomerPhone(telefono);
        order.setCustomerAddress(direccion);
        order.setCustomerReference(texto(form.get("customerReference")));
        order.setCustomerNotes(texto(form.get("customerNotes")));
        order.setVoucherUrl(texto(form.get("voucherUrl")));
        order.setStatus("pending");
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        aplicarMetodoPago(order, form.get("paymentMethodId"));

        order.setSubtotal(carrito.subtotal());
        CartService.AppliedCoupon cupon = cartService.applyCoupon(texto(form.get("couponCode")), carrito.subtotal());
        order.setCouponCode(cupon.code());
        order.setDiscountApplied(cupon.discount());
        order.setTotal(Math.round((carrito.subtotal() - cupon.discount()) * 100d) / 100d);

        // Las líneas se guardan en cascada desde el pedido.
        for (OrderItem item : carrito.items()) {
            item.setOrder(order);
        }
        order.setItems(carrito.items());

        Order guardado = orderRepository.save(order);
        registrarLog(guardado, "pending", userId, "Pedido recibido");

        descontarStock(guardado);
        return guardado;
    }

    /**
     * Se publica en la cola en vez de llamar a catalog directamente.
     *
     * Antes era una llamada Feign dentro de un try/catch: si catalog estaba
     * caído el pedido seguía en pie pero el descuento se perdía, y había que
     * repararlo con /api/admin/inventory/backfill. Ahora el mensaje espera en
     * RabbitMQ y catalog lo aplica al volver, así que ya no hay que elegir
     * entre perder la venta o perder el descuento.
     */
    private void descontarStock(Order order) {
        stockPublisher.publicarSalida(order.getId().longValue(), order.getOrderCode(), itemsPayload(order));
    }

    private void aplicarMetodoPago(Order order, Object rawId) {
        if (rawId == null) throw new CartService.CartException("Elige un método de pago");
        PaymentMethod pm = paymentMethodRepository.findById(Integer.valueOf(rawId.toString()))
                .orElseThrow(() -> new CartService.CartException("El método de pago elegido no existe"));
        if (pm.getIsActive() == null || pm.getIsActive() != 1) {
            throw new CartService.CartException("Ese método de pago ya no está disponible");
        }
        // Se copian los datos al pedido: si mañana cambia la cuenta de Yape,
        // el pedido antiguo debe seguir mostrando la que se le dio al cliente.
        order.setPaymentMethodId(pm.getId());
        order.setPaymentMethodType(pm.getType());
        order.setPaymentMethodLabel(pm.getLabel());
        order.setPaymentMethodQrUrl(pm.getQrUrl());
        order.setPaymentMethodAccountNumber(pm.getAccountNumber());
        order.setPaymentMethodAccountName(pm.getAccountName());
    }

    private String generarCodigo() {
        String dia = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        for (int intento = 0; intento < 20; intento++) {
            String codigo = "BM-" + dia + "-" + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (orderRepository.findByOrderCode(codigo).isEmpty()) return codigo;
        }
        return "BM-" + dia + "-" + System.currentTimeMillis();
    }

    // ─── Consultas ──────────────────────────────────────────────

    public Optional<Order> findByCode(String code) {
        return orderRepository.findByOrderCode(code);
    }

    public Optional<Order> findById(Integer id) {
        return orderRepository.findById(id);
    }

    public List<Order> findForUser(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<Order> findAllSorted() {
        List<Order> todos = new ArrayList<>(orderRepository.findAll());
        todos.sort((a, b) -> {
            LocalDateTime x = a.getCreatedAt(), y = b.getCreatedAt();
            if (x == null && y == null) return 0;
            if (x == null) return 1;
            if (y == null) return -1;
            return y.compareTo(x);
        });
        return todos;
    }

    // ─── Cambio de estado ───────────────────────────────────────

    @Transactional
    public Order changeStatus(Order order, String nuevoEstado, String nota, Long adminUserId) {
        if (nuevoEstado == null || !ESTADOS.contains(nuevoEstado)) {
            throw new CartService.CartException("Estado no válido");
        }
        String anterior = order.getStatus();
        if (nuevoEstado.equals(anterior)) return order;

        order.setStatus(nuevoEstado);
        order.setUpdatedAt(LocalDateTime.now());
        Order guardado = orderRepository.save(order);
        registrarLog(guardado, nuevoEstado, adminUserId, nota);

        // Cancelar devuelve el stock; solo una vez, no al recancelar.
        if ("cancelled".equals(nuevoEstado) && !"cancelled".equals(anterior)) {
            stockPublisher.publicarEntrada(
                    guardado.getId().longValue(), guardado.getOrderCode(), itemsPayload(guardado));
        }
        return guardado;
    }

    private void registrarLog(Order order, String estado, Long userId, String comentario) {
        OrderStatusLog entrada = new OrderStatusLog();
        entrada.setOrder(order);
        entrada.setStatus(estado);
        entrada.setChangedByUserId(userId);
        entrada.setComments(comentario == null || comentario.isBlank() ? null : comentario.trim());
        entrada.setCreatedAt(LocalDateTime.now());
        statusLogRepository.save(entrada);
    }

    private List<Map<String, Object>> itemsPayload(Order order) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (order.getItems() == null) return payload;
        for (OrderItem item : order.getItems()) {
            Map<String, Object> m = new HashMap<>();
            m.put("productId", item.getProductId());
            m.put("quantity", item.getQuantity());
            payload.add(m);
        }
        return payload;
    }

    public static String texto(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
