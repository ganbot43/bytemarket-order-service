package com.bytemarket.order.repository;

import com.bytemarket.order.model.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IPaymentMethodRepository extends JpaRepository<PaymentMethod, Integer> {
    List<PaymentMethod> findByIsActiveOrderBySortOrderAsc(Integer isActive);
}
