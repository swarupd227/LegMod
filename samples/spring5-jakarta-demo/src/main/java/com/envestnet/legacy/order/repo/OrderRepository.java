package com.envestnet.legacy.order.repo;

import com.envestnet.legacy.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {}
