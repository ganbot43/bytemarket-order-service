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
