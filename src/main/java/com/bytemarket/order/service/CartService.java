package com.bytemarket.order.service;

import com.bytemarket.order.client.CatalogPricingClient;
import com.bytemarket.order.dto.CartItemDTO;
import com.bytemarket.order.model.Coupon;
import com.bytemarket.order.model.OrderItem;
import com.bytemarket.order.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Valora el carrito contra catalog-service. El navegador solo manda
 * producto y cantidad: precio, nombre y stock salen del catálogo, así que
 * no se puede comprar a un precio inventado.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private final CatalogPricingClient catalogPricingClient;
    private final CouponRepository couponRepository;

    /** Carrito valorado: las líneas ya con precio y el subtotal. */
    public record PricedCart(List<OrderItem> items, double subtotal) {}

    /** Error de negocio con el mensaje que ve el cliente. */
    public static class CartException extends RuntimeException {
        public CartException(String message) { super(message); }
    }

    public PricedCart price(List<CartItemDTO> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new CartException("Tu carrito está vacío");
        }

        // Agrupar por producto: dos líneas del mismo producto son una sola
        // compra, y el stock hay que validarlo contra la suma.
        Map<Integer, Integer> cantidades = new LinkedHashMap<>();
        for (CartItemDTO item : requested) {
            if (item.getProductId() == null) continue;
            int qty = item.getQuantity() == null ? 0 : item.getQuantity();
            if (qty <= 0) continue;
            cantidades.merge(item.getProductId().intValue(), qty, Integer::sum);
        }
        if (cantidades.isEmpty()) {
            throw new CartException("Tu carrito está vacío");
        }

        Map<Integer, Map<String, Object>> porId = consultarCatalogo(cantidades.keySet());

        List<OrderItem> items = new ArrayList<>();
        double subtotal = 0d;

        for (Map.Entry<Integer, Integer> e : cantidades.entrySet()) {
            Map<String, Object> p = porId.get(e.getKey());
            int qty = e.getValue();
            String nombre = validarVendible(p, qty);

            double precio = Double.parseDouble(p.get("price").toString());
            double lineaSubtotal = redondear(precio * qty);

            OrderItem item = new OrderItem();
            item.setProductId(e.getKey().longValue());
            item.setProductName(nombre);
            item.setUnitPrice(precio);
            item.setQuantity(qty);
            item.setSubtotal(lineaSubtotal);
            items.add(item);

            subtotal += lineaSubtotal;
        }

        return new PricedCart(items, redondear(subtotal));
    }

    /**
     * Pregunta al catálogo por estos productos y los devuelve indexados.
     *
     * Está separado de price() porque hay DOS formas de valorar un carrito y
     * las dos necesitan preguntar lo mismo:
     *
     *   · La tienda (price()) toma el precio del catálogo, porque el
     *     navegador no puede decidir cuánto paga.
     *   · La caja del panel (PanelSaleService) toma el precio que escribe
     *     quien vende, porque en el mostrador se negocia: un cliente que
     *     compra diez unidades, un producto con la caja abierta.
     *
     * Lo que NO cambia entre las dos es la comprobación de que el producto
     * existe, está activo y tiene stock. Eso vive aquí para que no haya dos
     * versiones que puedan divergir.
     */
    public Map<Integer, Map<String, Object>> consultarCatalogo(Collection<Integer> productIds) {
        if (productIds == null || productIds.isEmpty()) return Map.of();

        List<Map<String, Object>> catalogo;
        try {
            catalogo = catalogPricingClient.resolve(new ArrayList<>(productIds));
        } catch (Exception e) {
            throw new CartException("No pudimos verificar los productos. Inténtalo en un momento.");
        }

        Map<Integer, Map<String, Object>> porId = new LinkedHashMap<>();
        for (Map<String, Object> p : catalogo) {
            porId.put(Integer.valueOf(p.get("id").toString()), p);
        }
        return porId;
    }

    /**
     * Comprueba que el producto se puede vender, y en qué cantidad.
     *
     * Devuelve el nombre con el que se guarda la línea: el del catálogo, no
     * el que mande el cliente. Un pedido tiene que decir qué se vendió de
     * verdad, aunque el producto se renombre después.
     */
    public String validarVendible(Map<String, Object> p, int cantidad) {
        if (p == null) {
            throw new CartException("Un producto de tu carrito ya no está disponible");
        }
        if (!Boolean.TRUE.equals(p.get("isActive"))) {
            throw new CartException("\"" + p.get("name") + "\" ya no está disponible");
        }
        if (Boolean.TRUE.equals(p.get("trackStock"))) {
            int stock = p.get("stock") == null ? 0 : Integer.parseInt(p.get("stock").toString());
            if (stock < cantidad) {
                throw new CartException("Solo quedan " + stock + " unidades de \"" + p.get("name") + "\"");
            }
        }
        return String.valueOf(p.get("name"));
    }

    /** Descuento del cupón, o 0 si no se envió ninguno. */
    public record AppliedCoupon(String code, double discount) {}

    /**
     * Comprueba el cupón y calcula el descuento SIN gastarlo.
     *
     * Separado de applyCoupon porque el checkout necesita mostrar el
     * descuento antes de confirmar: si validar consumiera el cupón, con solo
     * mirarlo se gastaría, y uno de un único uso se perdería sin comprar.
     */
    public AppliedCoupon previewCoupon(String code, double subtotal) {
        Coupon c = validar(code);
        if (c == null) return new AppliedCoupon(null, 0d);
        return new AppliedCoupon(c.getCode(), calcularDescuento(c, subtotal));
    }

    /** Comprueba el cupón, calcula el descuento y lo marca como usado. */
    public AppliedCoupon applyCoupon(String code, double subtotal) {
        Coupon c = validar(code);
        if (c == null) return new AppliedCoupon(null, 0d);

        double descuento = calcularDescuento(c, subtotal);
        c.setTimesUsed((c.getTimesUsed() == null ? 0 : c.getTimesUsed()) + 1);
        couponRepository.save(c);

        return new AppliedCoupon(c.getCode(), descuento);
    }

    /** El cupón si es usable, null si no se envió ninguno, o lanza si no vale. */
    private Coupon validar(String code) {
        if (code == null || code.isBlank()) return null;

        Coupon c = couponRepository.findByCode(code.trim().toUpperCase()).orElse(null);
        if (c == null || c.getIsActive() == null || c.getIsActive() != 1) {
            throw new CartException("El cupón no es válido");
        }
        if (c.getExpirationDate() != null && c.getExpirationDate().isBefore(LocalDate.now())) {
            throw new CartException("El cupón ya venció");
        }
        if (c.getUsageLimit() != null && c.getTimesUsed() != null && c.getTimesUsed() >= c.getUsageLimit()) {
            throw new CartException("El cupón alcanzó su límite de usos");
        }
        return c;
    }

    private double calcularDescuento(Coupon c, double subtotal) {
        double descuento = "PERCENT".equalsIgnoreCase(c.getDiscountType())
                ? subtotal * (c.getDiscountValue() / 100d)
                : c.getDiscountValue();
        // Un cupón no puede dejar el total en negativo.
        return Math.min(redondear(descuento), subtotal);
    }

    private static double redondear(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
