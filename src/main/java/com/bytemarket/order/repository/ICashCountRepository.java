package com.bytemarket.order.repository;

import com.bytemarket.order.model.CashCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ICashCountRepository extends JpaRepository<CashCount, Integer> {

    /** Las líneas del arqueo de un turno, en orden estable para el papel. */
    List<CashCount> findBySessionIdOrderByMethodAsc(Integer sessionId);
}
