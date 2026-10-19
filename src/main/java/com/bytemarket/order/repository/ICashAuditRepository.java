package com.bytemarket.order.repository;

import com.bytemarket.order.model.CashAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ICashAuditRepository extends JpaRepository<CashAudit, Integer> {

    /** Los arqueos de un turno, del primero al último. */
    List<CashAudit> findBySessionIdOrderByCreatedAtAsc(Integer sessionId);
}
