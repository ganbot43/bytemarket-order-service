package com.bytemarket.order.service;

import com.bytemarket.order.client.CatalogClient;
import com.bytemarket.order.dto.CartItemDTO;
import com.bytemarket.order.model.Coupon;
import com.bytemarket.order.model.OrderItem;
import com.bytemarket.order.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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

    private final CatalogClient catalogClient;
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

        List<Map<String, Object>> catalogo;
        try {
            catalogo = catalogClient.resolve(new ArrayList<>(cantidades.keySet()));
        } catch (Exception e) {
            throw new CartException("No pudimos verificar los productos. Inténtalo en un momento.");
        }

        Map<Integer, Map<String, Object>> porId = new LinkedHashMap<>();
        for (Map<String, Object> p : catalogo) {
            porId.put(Integer.valueOf(p.get("id").toString()), p);
        }

        List<OrderItem> items = new ArrayList<>();
        double subtotal = 0d;

        for (Map.Entry<Integer, Integer> e : cantidades.entrySet()) {
            Map<String, Object> p = porId.get(e.getKey());
            if (p == null) {
                throw new CartException("Un producto de tu carrito ya no está disponible");
            }
            if (!Boolean.TRUE.equals(p.get("isActive"))) {
                throw new CartException("\"" + p.get("name") + "\" ya no está disponible");
            }

            int qty = e.getValue();
            if (Boolean.TRUE.equals(p.get("trackStock"))) {
                int stock = p.get("stock") == null ? 0 : Integer.parseInt(p.get("stock").toString());
                if (stock < qty) {
                    throw new CartException("Solo quedan " + stock + " unidades de \"" + p.get("name") + "\"");
                }
            }

            double precio = Double.parseDouble(p.get("price").toString());
            double lineaSubtotal = redondear(precio * qty);

            OrderItem item = new OrderItem();
            item.setProductId(e.getKey().longValue());
            item.setProductName(String.valueOf(p.get("name")));
            item.setUnitPrice(precio);
            item.setQuantity(qty);
            item.setSubtotal(lineaSubtotal);
            items.add(item);

            subtotal += lineaSubtotal;
        }

        return new PricedCart(items, redondear(subtotal));
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
