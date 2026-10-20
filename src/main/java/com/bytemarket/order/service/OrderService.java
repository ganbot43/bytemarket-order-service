package com.bytemarket.order.service;

import com.bytemarket.order.client.CatalogInventoryClient;
import com.bytemarket.order.client.CatalogNoDisponibleException;
import feign.FeignException;
import com.bytemarket.order.messaging.PedidoEventPublisher;
import com.bytemarket.order.messaging.StockPublisher;
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
    private final StockPublisher stockPublisher;
    private final CatalogInventoryClient catalogInventoryClient;
    private final PedidoEventPublisher pedidoEventPublisher;
    private final NumeracionService numeracionService;

    /** Estados por los que puede pasar un pedido, en orden de avance. */
    public static final List<String> ESTADOS = List.of(
            "pending", "voucher_sent", "payment_validated", "in_preparation", "delivered", "cancelled");

    /** Estados de los que ya no se sale. El porqué, en changeStatus. */
    public static final List<String> TERMINALES = List.of("delivered", "cancelled");

    /**
     * Los estados en los que el pedido ya está cobrado.
     *
     * Es cuando se emite la boleta. `payment_validated` es el momento en
     * que alguien dio por bueno el pago; de ahí en adelante el pedido ya
     * no vuelve atrás, así que emitir después sería emitir tarde.
     */
    public static final List<String> COBRADO =
            List.of("payment_validated", "in_preparation", "delivered");

    // ─── Creación ───────────────────────────────────────────────

    @Transactional
    public Order create(Map<String, Object> form, Long userId, String userEmail, List<CartItemDTO> items) {
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
        // Del token, no del formulario: ver el javadoc de Order.customerEmail.
        order.setCustomerEmail(texto(userEmail));
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

        /*
         * Apartar ANTES de guardar, no después.
         *
         * La validación del carrito miró el stock hace un instante, pero
         * mirar no es coger: entre esa mirada y este punto cabe otra compra
         * del mismo producto. Reservar sí es coger —la comprobación y el
         * apartado son la misma operación en catalog—, así que si vuelve
         * sin unidades, aquí se corta y el pedido no llega a existir.
         *
         * Al revés sería tarde: un pedido guardado con unidades que no hay
         * es una promesa que alguien tendrá que deshacer a mano.
         */
        order.setReservationToken(java.util.UUID.randomUUID().toString());
        reservarStock(order);

        Order guardado;
        try {
            guardado = orderRepository.save(order);
            registrarLog(guardado, "pending", userId, "Pedido recibido");
        } catch (RuntimeException e) {
            // Las unidades ya están apartadas y el pedido no existe: se
            // devuelven ahora en vez de esperar a que caduquen, para que el
            // producto no figure agotado por algo que ya se sabe fallido.
            liberarStock(order.getReservationToken());
            throw e;
        }

        descontarStock(guardado);
        // Después de guardar y de encolar el stock: solo se anuncia lo que ya
        // es cierto en base. Al revés, un fallo al guardar dejaría publicado
        // un pedido que no existe, y eso no se puede retirar de un topic.
        pedidoEventPublisher.publicarCreado(guardado);
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
        stockPublisher.publicarSalida(order.getId().longValue(), order.getOrderCode(),
                order.getReservationToken(), itemsPayload(order));
    }

    /**
     * Aparta las unidades del carrito a nombre de este pedido.
     *
     * El código del pedido ya existe aquí —se genera antes de guardar—, que
     * es lo que permite reservar antes de tener id.
     */
    private void reservarStock(Order order) {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("token", order.getReservationToken());
        cuerpo.put("orderCode", order.getOrderCode());
        cuerpo.put("items", itemsPayload(order));
        try {
            catalogInventoryClient.reserveStock(cuerpo);
        } catch (CatalogNoDisponibleException e) {
            throw new CartService.CartException(e.getMessage());
        } catch (FeignException.Conflict e) {
            // 409: catalog dice que no alcanza, y su mensaje nombra el
            // producto y las unidades que quedaban. Es mejor que cualquier
            // cosa que pudiéramos escribir aquí.
            throw new CartService.CartException(mensajeDe(e, "Uno de los productos se agotó mientras comprabas"));
        }
    }

    private void liberarStock(String token) {
        if (token == null) return;
        try {
            catalogInventoryClient.releaseStock(Map.of("token", token));
        } catch (Exception e) {
            // No se insiste: las reservas caducan solas. Peor sería tapar el
            // error original del pedido con este.
            log.warn("Reserva {}: no se pudieron devolver las unidades apartadas: {}. "
                    + "Caducarán solas.", token, e.getMessage());
        }
    }

    /** El `message` que mande catalog, si viene; si no, uno genérico. */
    private static String mensajeDe(FeignException e, String porDefecto) {
        try {
            String cuerpo = e.contentUTF8();
            int i = cuerpo.indexOf("\"message\"");
            if (i < 0) return porDefecto;
            int ini = cuerpo.indexOf('"', cuerpo.indexOf(':', i)) + 1;
            int fin = cuerpo.indexOf('"', ini);
            return (ini > 0 && fin > ini) ? cuerpo.substring(ini, fin) : porDefecto;
        } catch (Exception ignored) {
            return porDefecto;
        }
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
        // El día del código es el de Lima: una venta de las 20:00 salía con
        // el código de mañana, y el comprobante que se imprime lleva ese
        // código. Ver HoraDeLima.
        String dia = HoraDeLima.hoy().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
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

        /*
         * De entregado y de cancelado ya no se sale.
         *
         * Las dos cosas son finales porque las dos ya movieron el stock, y
         * deshacerlas lo movería otra vez en el sentido que no toca:
         *
         *  · Entregado significa que las unidades salieron por la puerta.
         *    Cancelarlo publica una ENTRADA y las devuelve al inventario
         *    sin que nadie las haya traído.
         *  · Cancelado ya devolvió el stock. Sacarlo de ahí —a entregado,
         *    por ejemplo— NO lo vuelve a descontar, porque la salida se
         *    publica al crear el pedido y eso no se repite. El inventario
         *    se queda inflado, y por el mismo sitio.
         *
         * Y aunque el stock no entrara en juego, seguiría estando mal:
         * devolver un entregado a "pendiente" dice que un pedido que el
         * cliente tiene en casa está por preparar. El histórico de estados
         * es una auditoría, no un borrador.
         *
         * Lo que se hace con una entrega que vuelve es registrar la
         * devolución —una entrada con su motivo, desde Inventario—, que
         * deja rastro de las dos cosas: que se vendió y que volvió.
         *
         * Se comprueba aquí, en el servicio, y no en el panel: el panel es
         * una de las formas de llegar, y la API admin está abierta a
         * cualquiera con un token de administrador.
         */
        if (TERMINALES.contains(anterior)) {
            throw new CartService.CartException(
                    "delivered".equals(anterior)
                            ? "Un pedido entregado ya no cambia de estado. Si la mercadería volvió, "
                              + "regístralo como una entrada en Inventario para dejar rastro de la devolución."
                            : "Un pedido cancelado ya no cambia de estado. Su stock se devolvió al "
                              + "cancelarlo; para venderlo otra vez, registra un pedido nuevo.");
        }

        order.setStatus(nuevoEstado);
        order.setUpdatedAt(LocalDateTime.now());

        /*
         * La boleta se emite cuando el pago queda validado.
         *
         * Hasta ahora solo la llevaban las ventas de mostrador, y eso
         * dejaba al cliente de la web sin comprobante: el del local se iba
         * con su número y el de internet no tenía nada que descargar.
         *
         * Se emite aquí y no al crear el pedido porque un pedido web nace
         * por cobrar —el cliente sube su voucher y alguien lo valida—, y
         * el comprobante acompaña al dinero, no al apunte. Es la misma
         * regla que en caja, donde la venta nace cobrada y por eso sale
         * con su número en el acto.
         *
         * Un pedido que se anula conserva el suyo: lo que corresponde es
         * una nota de crédito, no reciclar el correlativo.
         */
        if (COBRADO.contains(nuevoEstado) && order.getBoletaNumero() == null) {
            numeracionService.asignarBoleta(order);
        }

        Order guardado = orderRepository.save(order);
        registrarLog(guardado, nuevoEstado, adminUserId, nota);

        // Cancelar devuelve el stock; solo una vez, no al recancelar.
        if ("cancelled".equals(nuevoEstado) && !"cancelled".equals(anterior)) {
            stockPublisher.publicarEntrada(
                    guardado.getId().longValue(), guardado.getOrderCode(), itemsPayload(guardado));
        }

        // Quién avisa al cliente de esto no se decide aquí: se publica el
        // hecho y support-service, que es el que sabe de correos, lo recoge.
        pedidoEventPublisher.publicarCambioDeEstado(guardado, anterior);
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
