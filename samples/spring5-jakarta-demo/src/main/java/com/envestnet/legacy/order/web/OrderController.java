package com.envestnet.legacy.order.web;

import com.envestnet.legacy.order.domain.Order;
import com.envestnet.legacy.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;

/**
 * Pre-Jakarta controller. javax.servlet and javax.validation imports must
 * become jakarta.* — and field injection should ideally become constructor
 * injection (a separate, optional uplift recipe).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @GetMapping
    public List<Order> list(HttpServletRequest req) {
        // Reads request attribute set by a legacy filter — pattern detected by heatmap
        Object tenant = req.getAttribute("X-Tenant");
        return orderService.findAll(String.valueOf(tenant));
    }

    @PostMapping
    public ResponseEntity<Order> create(@Valid @RequestBody Order order) {
        return ResponseEntity.ok(orderService.save(order));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Order> cancel(@PathVariable Long id) {
        return orderService.cancel(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
