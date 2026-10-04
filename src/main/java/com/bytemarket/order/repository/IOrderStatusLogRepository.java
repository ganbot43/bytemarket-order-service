package com.bytemarket.order.repository;

import com.bytemarket.order.model.OrderStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IOrderStatusLogRepository extends JpaRepository<OrderStatusLog, Integer> {
}
