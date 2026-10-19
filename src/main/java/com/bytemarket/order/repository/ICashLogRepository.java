package com.bytemarket.order.repository;

import com.bytemarket.order.model.CashLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ICashLogRepository extends JpaRepository<CashLog, Integer> {

    /** El historial de un turno, de lo primero a lo último. */
    List<CashLog> findBySessionIdOrderByCreatedAtAsc(Integer sessionId);
}
