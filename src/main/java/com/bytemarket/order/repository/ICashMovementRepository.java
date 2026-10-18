package com.bytemarket.order.repository;

import com.bytemarket.order.model.CashMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ICashMovementRepository extends JpaRepository<CashMovement, Integer> {

    List<CashMovement> findBySessionIdOrderByCreatedAtAsc(Integer sessionId);
}
