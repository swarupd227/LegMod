package com.envestnet.legacy.order.service;

import com.envestnet.legacy.order.domain.Order;
import com.envestnet.legacy.order.domain.OrderStatus;
import com.envestnet.legacy.order.naming.LegacyJndiLookup;
import com.envestnet.legacy.order.repo.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    @Autowired
    private OrderRepository repo;

    @PersistenceContext
    private EntityManager em;

    public List<Order> findAll(String tenant) {
        return repo.findAll();
    }

    public Order save(Order order) {
        if (order.getStatus() == null) order.setStatus(OrderStatus.PENDING);
        return repo.save(order);
    }

    public Optional<Order> cancel(Long id) {
        return repo.findById(id).map(o -> {
            o.setStatus(OrderStatus.CANCELLED);
            return repo.save(o);
        });
    }

    /**
     * Sweeper-like job. Uses an IBM-proprietary JNDI lookup that the Atlas
     * Migrate uplift agent must rewrite to a generic javax.naming lookup
     * (and after Jakarta migration, jakarta.naming where applicable).
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void sweep() {
        Object connFactory = LegacyJndiLookup.lookup("jms/orderQueueCF");
        // ... drain queued orders, process, requeue failures ...
    }
}
