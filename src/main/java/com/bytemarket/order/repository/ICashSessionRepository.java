package com.bytemarket.order.repository;

import com.bytemarket.order.model.CashSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ICashSessionRepository extends JpaRepository<CashSession, Integer> {

    /** La caja abierta de alguien. Como mucho hay una. */
    Optional<CashSession> findFirstByOpenedByAndStatus(Long openedBy, String status);

    /** El histórico, para el reporte: lo último primero. */
    Page<CashSession> findByStatusOrderByClosedAtDesc(String status, Pageable pageable);

    Page<CashSession> findAllByOrderByOpenedAtDesc(Pageable pageable);

    /**
     * Lo cobrado EN EFECTIVO durante un turno.
     *
     * Solo efectivo: lo de Yape, tarjeta y transferencia no pasó por el
     * cajón, y meterlo aquí haría que la caja descuadrara siempre por la
     * misma cantidad sin que nadie entendiera por qué.
     *
     * Y solo lo no cancelado: una venta anulada no dejó dinero.
     */
    @Query(value = """
            SELECT COALESCE(SUM(o.total), 0)
            FROM orders o
            WHERE o.cash_session_id = :sessionId
              AND o.status <> 'cancelled'
              AND o.payment_method_type = 'efectivo'
            """, nativeQuery = true)
    double cobradoEnEfectivo(@Param("sessionId") Integer sessionId);

    /** El resto de formas de pago, agrupadas: informan, no cuadran. */
    @Query(value = """
            SELECT COALESCE(o.payment_method_type, 'otro') AS forma,
                   COALESCE(SUM(o.total), 0)               AS importe,
                   COUNT(*)                                AS ventas
            FROM orders o
            WHERE o.cash_session_id = :sessionId AND o.status <> 'cancelled'
            GROUP BY COALESCE(o.payment_method_type, 'otro')
            """, nativeQuery = true)
    java.util.List<java.util.Map<String, Object>> ventasPorForma(@Param("sessionId") Integer sessionId);
}
