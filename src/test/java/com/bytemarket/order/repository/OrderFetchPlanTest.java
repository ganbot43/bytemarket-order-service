package com.bytemarket.order.repository;

import com.bytemarket.order.model.Order;
import com.bytemarket.order.model.OrderItem;
import com.bytemarket.order.model.OrderStatusLog;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cuántas consultas cuesta pintar una página del panel.
 *
 * No prueba una regla de negocio: prueba que la forma de cargar el pedido
 * sigue siendo la que se decidió. `items` y `statusLogs` estaban en EAGER y
 * una tabla de diez filas costaba veintiuna consultas; con LAZY y
 * @BatchSize son tres. Como el número no se ve en ninguna pantalla, el día
 * que alguien quite el @BatchSize —o vuelva a poner EAGER para arreglar un
 * LazyInitializationException— nadie se enteraría hasta que el panel fuera
 * lento en producción. Este test se entera.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("Order — plan de carga de las colecciones")
class OrderFetchPlanTest {

    @Autowired
    private IOrderRepository orderRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManager em;

    private Statistics estadisticas;

    @BeforeEach
    void sembrarDiezPedidos() {
        for (int i = 0; i < 10; i++) {
            Order o = new Order();
            o.setOrderCode("BM-TEST-" + i);
            o.setCustomerName("Cliente " + i);
            o.setStatus("delivered");
            o.setPaymentStatus("paid");
            o.setSubtotal(100.0);
            o.setTotal(100.0);
            o.setCreatedAt(LocalDateTime.now().minusDays(i));
            entityManager.persist(o);

            // Dos líneas y un movimiento de estado por pedido: lo normal.
            for (int j = 0; j < 2; j++) {
                OrderItem it = new OrderItem();
                it.setOrder(o);
                it.setProductName("Producto " + j);
                it.setQuantity(1);
                it.setUnitPrice(50.0);
                it.setSubtotal(50.0);
                entityManager.persist(it);
            }
            OrderStatusLog log = new OrderStatusLog();
            log.setOrder(o);
            log.setStatus("delivered");
            log.setCreatedAt(LocalDateTime.now());
            entityManager.persist(log);
        }
        entityManager.flush();
        entityManager.clear();

        estadisticas = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        estadisticas.setStatisticsEnabled(true);
        estadisticas.clear();
    }

    @Test
    @DisplayName("una página de diez pedidos no pasa de cuatro consultas")
    void listarNoDisparaUnaConsultaPorPedido() {
        Page<Order> pagina = orderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10));

        // Tocar las colecciones es lo que hace Jackson al serializar; si el
        // plan de carga fuera el malo, cada vuelta de este bucle dispararía
        // su propia consulta.
        int lineas = 0;
        for (Order o : pagina.getContent()) {
            lineas += o.getItems().size();
            assertFalse(o.getStatusLogs().isEmpty(), "el historial debe cargar");
        }

        assertEquals(10, pagina.getContent().size());
        assertEquals(20, lineas, "diez pedidos de dos líneas");

        long consultas = estadisticas.getPrepareStatementCount();
        assertTrue(consultas <= 4,
                "pintar diez pedidos costó " + consultas + " consultas. "
                        + "Con EAGER o sin @BatchSize son 21: revisa el plan de carga de Order.");
    }
}
