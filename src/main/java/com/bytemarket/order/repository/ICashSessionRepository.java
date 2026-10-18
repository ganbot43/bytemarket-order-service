package com.bytemarket.order.repository;

import com.bytemarket.order.model.CashSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ICashSessionRepository extends JpaRepository<CashSession, Integer> {

    /**
     * La caja abierta de un cajero. Como mucho hay una. Solo para leer.
     *
     * Se busca por el cajero y no por quien aperturó: el turno es del cajón
     * con el que se cobra, y lo abre otra persona. Preguntando por
     * `openedBy`, el cajero no encontraba su propia caja y el jefe se
     * encontraba la del cajero como si fuera la suya.
     */
    Optional<CashSession> findFirstByCashierIdAndStatus(Long cashierId, String status);

    /** Las cajas abiertas ahora mismo: lo que el encargado tiene que cuadrar. */
    List<CashSession> findByStatusOrderByOpenedAtAsc(String status);

    /**
     * La caja abierta, bloqueando su fila hasta el fin de la transacción.
     *
     * Hace falta porque cobrar y cerrar se pisan. Cerrar lee el efectivo
     * esperado y después marca el turno cerrado; si entre esas dos cosas se
     * confirma una venta, esa venta se guarda con el id del turno pero no
     * entró en el esperado. El arqueo diría 70 con 120 en el cajón, y la
     * diferencia aparecería como un sobrante que nadie sabe explicar.
     *
     * Con el bloqueo, la venta y el cierre se ponen en fila: o la venta
     * entra antes y cuenta, o espera y encuentra el turno ya cerrado.
     *
     * Es dinero: aquí se prefiere que una de las dos espere a que las dos
     * pasen y cuadren mal.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM CashSession s
            WHERE s.cashierId = :cashierId AND s.status = :status
            """)
    Optional<CashSession> bloquearAbierta(@Param("cashierId") Long cashierId,
                                          @Param("status") String status);

    /**
     * Un turno concreto, con su fila bloqueada. Lo usa el encargado: él no
     * cierra "su" caja sino la de alguien, así que llega por id.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CashSession s WHERE s.id = :id")
    Optional<CashSession> bloquearPorId(@Param("id") Integer id);

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

    /**
     * Rellena el cajero de los turnos que se guardaron cuando abrir y
     * cobrar eran la misma persona.
     *
     * En esas filas `opened_by` significaba exactamente lo que ahora
     * significa `cashier_id`, así que copiarlo es la traducción fiel. Sin
     * esto, el histórico de arqueos aparecería sin cajero —y un arqueo sin
     * dueño no sirve para lo que existe—.
     */
    @Modifying
    @Query(value = """
            UPDATE cash_sessions
            SET cashier_id = opened_by, cashier_name = opened_by_name
            WHERE cashier_id IS NULL
            """, nativeQuery = true)
    int rellenarCajeroDeTurnosAntiguos();
}
