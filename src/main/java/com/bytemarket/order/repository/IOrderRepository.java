package com.bytemarket.order.repository;

import com.bytemarket.order.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface IOrderRepository extends JpaRepository<Order, Integer> {
    Optional<Order> findByOrderCode(String orderCode);
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Order> findByUserId(Long userId);

    @Query("SELECT o FROM Order o WHERE o.userId = :userId")
    List<Order> findOrdersByUserId(@Param("userId") Long userId);

    // ─── Panel de administración ────────────────────────────────

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<Order> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /** Listado del panel con estado y texto opcionales. Busca por código,
        nombre y teléfono: son los tres datos con los que llega un cliente
        preguntando por su pedido. */
    @Query("""
            SELECT o FROM Order o
            WHERE (:status IS NULL OR o.status = :status)
              AND (:patron IS NULL
                   OR LOWER(o.orderCode) LIKE :patron
                   OR LOWER(COALESCE(o.customerName, '')) LIKE :patron
                   OR LOWER(COALESCE(o.customerPhone, '')) LIKE :patron)
            ORDER BY o.createdAt DESC
            """)
    Page<Order> buscar(@Param("status") String status,
                       @Param("patron") String patron,
                       Pageable pageable);

    /** Pedidos abiertos, los más antiguos primero: son los que urge atender. */
    List<Order> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    /**
     * Total por estado, para las tarjetas del panel.
     *
     * El panel los contaba sobre la página devuelta, así que con el límite
     * por defecto nunca mostraba la cifra real.
     */
    long countByStatus(String status);

    // ─── Reportes ───────────────────────────────────────────────

    @Query("SELECT COUNT(o) FROM Order o WHERE o.createdAt >= :desde AND o.status <> 'cancelled'")
    long contarDesde(@Param("desde") LocalDateTime desde);

    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o WHERE o.createdAt >= :desde AND o.status <> 'cancelled'")
    double sumarDesde(@Param("desde") LocalDateTime desde);

    /** Ventas por día para la gráfica del panel. Los cancelados no cuentan. */
    @Query(value = """
            SELECT DATE(o.created_at)        AS date,
                   COALESCE(SUM(o.total), 0) AS totalSales
            FROM orders o
            WHERE o.status <> 'cancelled'
              AND o.created_at >= :desde
            GROUP BY DATE(o.created_at)
            ORDER BY DATE(o.created_at) ASC
            """, nativeQuery = true)
    List<Map<String, Object>> ventasPorDia(@Param("desde") LocalDateTime desde);

    // ─── Indicadores del panel ──────────────────────────────────
    //
    // OJO: estas consultas repiten en SQL la regla de Order.estadoDePago().
    // Los pedidos anteriores a la caja no tienen payment_status, y darlos
    // por pendientes inflaría "por cobrar" con pedidos de hace meses; darlos
    // por pagados inflaría las ventas. El COALESCE de abajo deduce lo mismo
    // que el método Java. Si una cambia, hay que cambiar la otra.

    /** Dinero efectivamente cobrado en el rango, y cuántos pedidos fueron. */
    @Query(value = """
            SELECT COALESCE(SUM(o.total), 0) AS amount,
                   COUNT(*)                  AS orders
            FROM orders o
            WHERE o.status <> 'cancelled'
              AND o.created_at >= :desde AND o.created_at < :hasta
              AND COALESCE(NULLIF(o.payment_status, ''),
                           CASE WHEN o.status IN ('payment_validated','in_preparation','delivered')
                                THEN 'paid' ELSE 'pending' END) = 'paid'
            """, nativeQuery = true)
    Map<String, Object> cobradoEntre(@Param("desde") LocalDateTime desde,
                                     @Param("hasta") LocalDateTime hasta);

    /** Pedidos vivos y sin cobrar: lo que la tienda tiene por recaudar. */
    @Query(value = """
            SELECT COUNT(*)                  AS orders,
                   COALESCE(SUM(o.total), 0) AS amount
            FROM orders o
            WHERE o.status <> 'cancelled'
              AND COALESCE(NULLIF(o.payment_status, ''),
                           CASE WHEN o.status IN ('payment_validated','in_preparation','delivered')
                                THEN 'paid' ELSE 'pending' END) = 'pending'
            """, nativeQuery = true)
    Map<String, Object> porCobrar();

    /** Cuántos pedidos esperan algo de la tienda ahora mismo. */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.status IN :estados")
    long contarPorEstados(@Param("estados") List<String> estados);

    // Las de abajo filtran por COBRADO, igual que cobradoEntre.
    //
    // Sin eso el reporte se contradecía: el titular decía 747.59 (solo
    // cobrado) y el desglose por canal sumaba 2549.15 (todo lo no
    // cancelado). Una gráfica que no cuadra con la cifra de arriba no sirve
    // para decidir nada.

    /** Ventas y pedidos por día del rango, para la gráfica del reporte. */
    @Query(value = """
            SELECT DATE(o.created_at)        AS day,
                   COALESCE(SUM(o.total), 0) AS amount,
                   COUNT(*)                  AS orders
            FROM orders o
            WHERE o.status <> 'cancelled'
              AND o.created_at >= :desde AND o.created_at < :hasta
              AND COALESCE(NULLIF(o.payment_status, ''),
                           CASE WHEN o.status IN ('payment_validated','in_preparation','delivered')
                                THEN 'paid' ELSE 'pending' END) = 'paid'
            GROUP BY DATE(o.created_at)
            ORDER BY DATE(o.created_at) ASC
            """, nativeQuery = true)
    List<Map<String, Object>> ventasPorDiaEntre(@Param("desde") LocalDateTime desde,
                                                @Param("hasta") LocalDateTime hasta);

    /**
     * Cuánto se vendió por cada canal.
     *
     * Es la consulta que da sentido a la caja: sin ella no se puede saber
     * cuánto entra por mostrador y cuánto por la tienda, que son dos
     * negocios con costes distintos.
     */
    @Query(value = """
            SELECT COALESCE(NULLIF(o.channel, ''), 'web') AS label,
                   COALESCE(SUM(o.total), 0)              AS amount,
                   COUNT(*)                               AS orders
            FROM orders o
            WHERE o.status <> 'cancelled'
              AND o.created_at >= :desde AND o.created_at < :hasta
              AND COALESCE(NULLIF(o.payment_status, ''),
                           CASE WHEN o.status IN ('payment_validated','in_preparation','delivered')
                                THEN 'paid' ELSE 'pending' END) = 'paid'
            GROUP BY COALESCE(NULLIF(o.channel, ''), 'web')
            ORDER BY amount DESC
            """, nativeQuery = true)
    List<Map<String, Object>> ventasPorCanal(@Param("desde") LocalDateTime desde,
                                             @Param("hasta") LocalDateTime hasta);

    /** Cuánto entró por cada forma de pago. */
    @Query(value = """
            SELECT COALESCE(NULLIF(o.payment_method_label, ''), 'Sin método') AS label,
                   COALESCE(SUM(o.total), 0)                                  AS amount,
                   COUNT(*)                                                   AS orders
            FROM orders o
            WHERE o.status <> 'cancelled'
              AND o.created_at >= :desde AND o.created_at < :hasta
              AND COALESCE(NULLIF(o.payment_status, ''),
                           CASE WHEN o.status IN ('payment_validated','in_preparation','delivered')
                                THEN 'paid' ELSE 'pending' END) = 'paid'
            GROUP BY COALESCE(NULLIF(o.payment_method_label, ''), 'Sin método')
            ORDER BY amount DESC
            """, nativeQuery = true)
    List<Map<String, Object>> ventasPorFormaDePago(@Param("desde") LocalDateTime desde,
                                                   @Param("hasta") LocalDateTime hasta);

    /** Cupones usados en el rango y cuánto descuento costaron. */
    @Query(value = """
            SELECT o.coupon_code                        AS label,
                   COALESCE(SUM(o.discount_applied), 0) AS amount,
                   COUNT(*)                             AS orders
            FROM orders o
            WHERE o.status <> 'cancelled'
              AND o.coupon_code IS NOT NULL AND o.coupon_code <> ''
              AND o.created_at >= :desde AND o.created_at < :hasta
              AND COALESCE(NULLIF(o.payment_status, ''),
                           CASE WHEN o.status IN ('payment_validated','in_preparation','delivered')
                                THEN 'paid' ELSE 'pending' END) = 'paid'
            GROUP BY o.coupon_code
            ORDER BY amount DESC
            """, nativeQuery = true)
    List<Map<String, Object>> cuponesUsados(@Param("desde") LocalDateTime desde,
                                            @Param("hasta") LocalDateTime hasta);

    /** Unidades vendidas en el rango. */
    @Query(value = """
            SELECT COALESCE(SUM(oi.quantity), 0) AS units
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.status <> 'cancelled'
              AND o.created_at >= :desde AND o.created_at < :hasta
              AND COALESCE(NULLIF(o.payment_status, ''),
                           CASE WHEN o.status IN ('payment_validated','in_preparation','delivered')
                                THEN 'paid' ELSE 'pending' END) = 'paid'
            """, nativeQuery = true)
    Long unidadesEntre(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);

    /** Productos más vendidos del rango. */
    @Query(value = """
            SELECT oi.product_name               AS label,
                   SUM(oi.quantity)              AS units,
                   COALESCE(SUM(oi.subtotal), 0) AS amount
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.status <> 'cancelled'
              AND o.created_at >= :desde AND o.created_at < :hasta
              AND COALESCE(NULLIF(o.payment_status, ''),
                           CASE WHEN o.status IN ('payment_validated','in_preparation','delivered')
                                THEN 'paid' ELSE 'pending' END) = 'paid'
            GROUP BY oi.product_name
            ORDER BY SUM(oi.quantity) DESC
            """, nativeQuery = true)
    List<Map<String, Object>> masVendidosEntre(@Param("desde") LocalDateTime desde,
                                               @Param("hasta") LocalDateTime hasta,
                                               Pageable pageable);

    /** Productos más vendidos, por unidades. */
    @Query(value = """
            SELECT oi.product_name           AS name,
                   SUM(oi.quantity)          AS quantity,
                   COALESCE(SUM(oi.subtotal), 0) AS revenue
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.status <> 'cancelled'
            GROUP BY oi.product_name
            ORDER BY SUM(oi.quantity) DESC
            """, nativeQuery = true)
    List<Map<String, Object>> productosMasVendidos(Pageable pageable);
}
