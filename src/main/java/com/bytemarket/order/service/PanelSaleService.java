package com.bytemarket.order.service;

import com.bytemarket.order.messaging.PedidoEventPublisher;
import com.bytemarket.order.messaging.StockPublisher;
import com.bytemarket.order.model.Order;
import com.bytemarket.order.model.OrderItem;
import com.bytemarket.order.model.OrderStatusLog;
import com.bytemarket.order.model.PaymentMethod;
import com.bytemarket.order.repository.IOrderRepository;
import com.bytemarket.order.repository.IOrderStatusLogRepository;
import com.bytemarket.order.repository.IPaymentMethodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ventas registradas desde el panel: la caja del local y los pedidos que
 * llegan por WhatsApp, redes o teléfono.
 *
 * Qué la diferencia del checkout de la tienda, y por qué no es el mismo
 * servicio:
 *
 *   · El PRECIO lo pone quien vende. En la tienda eso sería un agujero —el
 *     navegador decidiendo cuánto paga—, pero en el mostrador se negocia: un
 *     cliente que compra diez unidades, un producto con la caja golpeada.
 *   · La caja COBRA Y ENTREGA en el acto, así que el pedido no nace
 *     "pendiente" esperando un comprobante: nace entregado y pagado.
 *   · Hay vuelto, que en una tienda web no existe.
 *
 * Qué NO cambia, y por eso se reutiliza:
 *
 *   · Que el producto exista, esté activo y tenga stock lo comprueba
 *     CartService.validarVendible, el mismo que usa el checkout.
 *   · El descuento de stock se publica en la MISMA cola de RabbitMQ. Una
 *     venta de mostrador mueve el inventario igual que una web, y
 *     StockService ya es idempotente.
 *   · El evento del pedido se publica en Kafka igual, así que el cliente de
 *     un pedido por WhatsApp también recibe su correo.
 */
@Service
@RequiredArgsConstructor
public class PanelSaleService {

    /** Canales por los que puede entrar una venta registrada a mano. */
    public static final Set<String> CANALES =
            Set.of("caja", "whatsapp", "instagram", "facebook", "telefono", "otro");

    /**
     * Formas de pago del panel.
     *
     * No salen de la tabla payment_methods porque esa tabla es lo que la
     * TIENDA ofrece: añadir "Efectivo" ahí lo pondría en el checkout web,
     * donde no significa nada. Cuando el tipo sí existe en la tabla —Yape,
     * Plin— se copian sus datos reales; para los que no, se usa esta
     * etiqueta.
     */
    public static final Map<String, String> FORMAS_DE_PAGO = Map.of(
            "efectivo", "Efectivo",
            "yape", "Yape",
            "plin", "Plin",
            "tarjeta", "Tarjeta",
            "bank_transfer", "Transferencia bancaria");

    /** Nombre con el que se guarda una venta de mostrador sin cliente. */
    private static final String CLIENTE_MOSTRADOR = "Cliente de mostrador";

    private final IOrderRepository orderRepository;
    private final IOrderStatusLogRepository statusLogRepository;
    private final IPaymentMethodRepository paymentMethodRepository;
    private final CartService cartService;
    private final StockPublisher stockPublisher;
    private final PedidoEventPublisher pedidoEventPublisher;

    /** Una línea tal como la manda el panel: producto, cantidad y SU precio. */
    public record LineaDeVenta(Integer productId, Integer quantity, Double unitPrice) {}

    /** Lo que el panel necesita para registrar la venta. */
    public record Peticion(
            String channel,
            String customerName,
            String customerPhone,
            String customerEmail,
            String customerAddress,
            String customerReference,
            String customerNotes,
            List<LineaDeVenta> items,
            Double discount,
            String couponCode,
            String paymentType,
            String paymentStatus,
            Double cashReceived) {}

    /** El resultado, con lo que la pantalla necesita para pintar el ticket. */
    public record Resultado(Order order, Double change) {}

    @Transactional
    public Resultado registrar(Peticion p, Long adminUserId) {
        String canal = texto(p.channel()) == null ? "caja" : p.channel().trim().toLowerCase();
        if (!CANALES.contains(canal)) {
            throw new CartService.CartException("Canal de venta no válido");
        }
        boolean esCaja = "caja".equals(canal);

        // En el mostrador el cliente está delante y muchas veces no deja sus
        // datos; en un pedido por WhatsApp hay que saber a quién se le manda.
        String nombre = texto(p.customerName());
        if (!esCaja && (nombre == null || nombre.length() < 2)) {
            throw new CartService.CartException("Escribe el nombre del cliente");
        }

        List<OrderItem> lineas = construirLineas(p.items());
        double subtotal = redondear(lineas.stream().mapToDouble(OrderItem::getSubtotal).sum());

        // El descuento a mano y el cupón se suman, y juntos no pueden pasar
        // del subtotal: un total negativo no significa nada.
        double aMano = redondear(Math.max(0d, p.discount() == null ? 0d : p.discount()));
        CartService.AppliedCoupon cupon = cartService.applyCoupon(texto(p.couponCode()), subtotal);
        double descuento = redondear(Math.min(aMano + cupon.discount(), subtotal));
        double total = redondear(subtotal - descuento);

        Order order = new Order();
        order.setOrderCode(generarCodigo());
        order.setUserId(null);
        order.setCreatedBy(adminUserId);
        order.setChannel(canal);
        order.setCustomerName(nombre == null ? CLIENTE_MOSTRADOR : nombre);
        order.setCustomerPhone(texto(p.customerPhone()));
        order.setCustomerEmail(texto(p.customerEmail()));
        order.setCustomerAddress(texto(p.customerAddress()));
        order.setCustomerReference(texto(p.customerReference()));
        order.setCustomerNotes(texto(p.customerNotes()));
        order.setSubtotal(subtotal);
        order.setCouponCode(cupon.code());
        order.setDiscountApplied(descuento);
        order.setTotal(total);

        aplicarFormaDePago(order, p.paymentType());
        aplicarEfectivo(order, p.paymentType(), p.cashReceived(), total);

        // La caja cobra y entrega en el acto. Para los demás canales lo dice
        // quien registra, porque un pedido por WhatsApp puede estar pagado y
        // sin enviar, o enviado y pendiente de cobro.
        boolean pagado = esCaja || "paid".equals(texto(p.paymentStatus()));
        order.setPaymentStatus(pagado ? "paid" : "pending");
        order.setStatus(esCaja ? "delivered" : (pagado ? "payment_validated" : "pending"));

        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        for (OrderItem item : lineas) item.setOrder(order);
        order.setItems(lineas);

        Order guardado = orderRepository.save(order);
        registrarLogs(guardado, canal, pagado, adminUserId);

        // Misma cola que el checkout: una venta de mostrador descuenta stock
        // igual que una web, y el consumidor ya es idempotente.
        stockPublisher.publicarSalida(
                guardado.getId().longValue(), guardado.getOrderCode(), itemsPayload(guardado));
        pedidoEventPublisher.publicarCreado(guardado);

        return new Resultado(guardado, guardado.getChangeGiven());
    }

    // ─── Partes ─────────────────────────────────────────────────

    /**
     * Agrupa por producto, comprueba contra el catálogo y construye las
     * líneas con el precio que escribió quien vende.
     */
    private List<OrderItem> construirLineas(List<LineaDeVenta> pedidas) {
        if (pedidas == null || pedidas.isEmpty()) {
            throw new CartService.CartException("Agrega al menos un producto");
        }

        // Dos líneas del mismo producto son una sola compra, y el stock hay
        // que validarlo contra la suma. El precio se queda el de la última:
        // si el vendedor lo corrigió, vale la corrección.
        Map<Integer, Integer> cantidades = new LinkedHashMap<>();
        Map<Integer, Double> precios = new LinkedHashMap<>();
        for (LineaDeVenta l : pedidas) {
            if (l.productId() == null) continue;
            int qty = l.quantity() == null ? 0 : l.quantity();
            if (qty <= 0) continue;
            double precio = l.unitPrice() == null ? 0d : l.unitPrice();
            if (precio < 0) throw new CartService.CartException("El precio no puede ser negativo");
            cantidades.merge(l.productId(), qty, Integer::sum);
            precios.put(l.productId(), precio);
        }
        if (cantidades.isEmpty()) {
            throw new CartService.CartException("Agrega al menos un producto");
        }

        Map<Integer, Map<String, Object>> catalogo = cartService.consultarCatalogo(cantidades.keySet());

        List<OrderItem> lineas = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : cantidades.entrySet()) {
            int qty = e.getValue();
            // El nombre lo pone el catálogo, no el panel: el pedido tiene que
            // decir qué se vendió de verdad.
            String nombre = cartService.validarVendible(catalogo.get(e.getKey()), qty);
            double precio = redondear(precios.get(e.getKey()));

            OrderItem item = new OrderItem();
            item.setProductId(e.getKey().longValue());
            item.setProductName(nombre);
            item.setUnitPrice(precio);
            item.setQuantity(qty);
            item.setSubtotal(redondear(precio * qty));
            lineas.add(item);
        }
        return lineas;
    }

    /**
     * Copia la forma de pago al pedido.
     *
     * Si el tipo existe en payment_methods se copian sus datos reales (la
     * cuenta de Yape del negocio, por ejemplo); si no —efectivo, tarjeta— se
     * usa la etiqueta del panel. En los dos casos los datos quedan COPIADOS
     * en el pedido y no referenciados, por lo mismo que en el checkout: si
     * mañana cambia la cuenta, el pedido antiguo debe seguir mostrando la que
     * se le dio al cliente.
     */
    private void aplicarFormaDePago(Order order, String tipo) {
        String forma = texto(tipo);
        if (forma == null || !FORMAS_DE_PAGO.containsKey(forma)) {
            throw new CartService.CartException("Elige una forma de pago");
        }

        order.setPaymentMethodType(forma);
        order.setPaymentMethodLabel(FORMAS_DE_PAGO.get(forma));

        paymentMethodRepository.findAll().stream()
                .filter(pm -> forma.equals(pm.getType()))
                .filter(pm -> pm.getIsActive() != null && pm.getIsActive() == 1)
                .findFirst()
                .ifPresent(pm -> {
                    order.setPaymentMethodId(pm.getId());
                    order.setPaymentMethodLabel(pm.getLabel());
                    order.setPaymentMethodQrUrl(pm.getQrUrl());
                    order.setPaymentMethodAccountNumber(pm.getAccountNumber());
                    order.setPaymentMethodAccountName(pm.getAccountName());
                });
    }

    /**
     * Guarda el efectivo recibido y calcula el vuelto.
     *
     * Se rechaza si no alcanza: es el error que de verdad ocurre en un
     * mostrador —teclear 50 cuando el cliente dio 100, o al revés— y
     * dejarlo pasar guardaría un vuelto negativo que nadie va a revisar.
     */
    private void aplicarEfectivo(Order order, String tipo, Double recibido, double total) {
        if (!"efectivo".equals(texto(tipo)) || recibido == null) return;

        double vuelto = redondear(recibido - total);
        if (vuelto < 0) {
            throw new CartService.CartException("El efectivo recibido no alcanza para el total");
        }
        order.setCashReceived(redondear(recibido));
        order.setChangeGiven(vuelto);
    }

    /**
     * Deja escrito en el historial de dónde salió el pedido y si se cobró.
     *
     * Importa para auditar: un pedido registrado a mano por un encargado no
     * se distingue de uno de la tienda si no queda dicho, y el panel muestra
     * esta línea de tiempo.
     */
    private void registrarLogs(Order order, String canal, boolean pagado, Long adminUserId) {
        List<OrderStatusLog> logs = new ArrayList<>();
        logs.add(log(order, order.getStatus(), adminUserId,
                "Registrado desde el panel (" + etiquetaCanal(canal) + ")"));
        if (pagado) {
            logs.add(log(order, order.getStatus(), adminUserId,
                    "Cobrado con " + order.getPaymentMethodLabel()));
        }
        statusLogRepository.saveAll(logs);
    }

    private OrderStatusLog log(Order order, String estado, Long userId, String comentario) {
        OrderStatusLog entrada = new OrderStatusLog();
        entrada.setOrder(order);
        entrada.setStatus(estado);
        entrada.setChangedByUserId(userId);
        entrada.setComments(comentario);
        entrada.setCreatedAt(LocalDateTime.now());
        return entrada;
    }

    private static String etiquetaCanal(String canal) {
        return switch (canal) {
            case "caja" -> "Caja";
            case "whatsapp" -> "WhatsApp";
            case "instagram" -> "Instagram";
            case "facebook" -> "Facebook";
            case "telefono" -> "Teléfono";
            default -> "Otro";
        };
    }

    /**
     * Mismo formato que el checkout (BM-aaaammdd-nnnn) a propósito: un
     * pedido de mostrador y uno web se buscan en la misma pantalla, así que
     * no deben parecer cosas distintas.
     */
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

    private List<Map<String, Object>> itemsPayload(Order order) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (order.getItems() == null) return payload;
        for (OrderItem item : order.getItems()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", item.getProductId());
            m.put("quantity", item.getQuantity());
            payload.add(m);
        }
        return payload;
    }

    private static String texto(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static double redondear(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
