package com.bytemarket.order.repository;

import com.bytemarket.order.model.Coupon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de la capa de acceso a datos del repositorio de cupones.
 *
 * @DataJpaTest levanta solo JPA contra una base en memoria y revierte la
 * transacción al final de cada prueba, de modo que no se toca MySQL ni
 * importa el orden de ejecución.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("CouponRepository — capa de acceso a datos")
class CouponRepositoryTest {

    @Autowired
    private CouponRepository repositorio;

    @Autowired
    private TestEntityManager em;

    private Coupon nuevoCupon(String codigo, String tipo, double valor) {
        Coupon c = new Coupon();
        c.setCode(codigo);
        c.setDiscountType(tipo);
        c.setDiscountValue(valor);
        c.setExpirationDate(LocalDate.of(2026, 12, 31));
        c.setUsageLimit(100);
        c.setTimesUsed(0);
        c.setIsActive(1);
        return c;
    }

    @BeforeEach
    void limpiar() {
        repositorio.deleteAll();
        em.flush();
    }

    // ── INSERTAR ─────────────────────────────────────────────────────

    @Test
    @DisplayName("insertar: persiste el cupón con id generado y fecha de creación")
    void insertar() {
        Coupon guardado = repositorio.save(nuevoCupon("BIENVENIDO10", "PERCENT", 10.0));
        em.flush();
        em.clear();

        assertNotNull(guardado.getId());

        Coupon desdeLaBase = em.find(Coupon.class, guardado.getId());
        assertNotNull(desdeLaBase);
        assertEquals("BIENVENIDO10", desdeLaBase.getCode());
        assertEquals("PERCENT", desdeLaBase.getDiscountType());
        assertEquals(10.0, desdeLaBase.getDiscountValue());
        assertEquals(0, desdeLaBase.getTimesUsed(), "un cupón nuevo arranca sin usos");
        assertNotNull(desdeLaBase.getCreatedAt());
    }

    @Test
    @DisplayName("insertar: el código es único y un duplicado no se persiste")
    void insertarCodigoDuplicadoFalla() {
        repositorio.save(nuevoCupon("VERANO", "FIXED", 20.0));
        em.flush();

        /* Dos cupones con el mismo código harían ambiguo el canje en el
           checkout; la base lo impide y esta prueba lo deja fijado. */
        assertThrows(DataIntegrityViolationException.class, () -> {
            repositorio.save(nuevoCupon("VERANO", "PERCENT", 15.0));
            em.flush();
        });
    }

    // ── ACTUALIZAR ───────────────────────────────────────────────────

    @Test
    @DisplayName("actualizar: incrementa los usos y desactiva sin crear fila nueva")
    void actualizar() {
        Coupon original = repositorio.save(nuevoCupon("CANJEABLE", "FIXED", 25.0));
        em.flush();
        Integer id = original.getId();

        original.setTimesUsed(original.getTimesUsed() + 1);
        original.setIsActive(0);
        repositorio.save(original);
        em.flush();
        em.clear();

        Coupon actualizado = em.find(Coupon.class, id);
        assertEquals(1, actualizado.getTimesUsed());
        assertEquals(0, actualizado.getIsActive());
        assertEquals(1, repositorio.count(), "actualizar no debe insertar una fila adicional");
    }

    // ── ELIMINAR ─────────────────────────────────────────────────────

    @Test
    @DisplayName("eliminar: borra la fila y deja de encontrarse por id ni por código")
    void eliminar() {
        Coupon c = repositorio.save(nuevoCupon("TEMPORAL", "FIXED", 5.0));
        em.flush();
        Integer id = c.getId();

        repositorio.deleteById(id);
        em.flush();
        em.clear();

        assertFalse(repositorio.findById(id).isPresent());
        assertTrue(repositorio.findByCode("TEMPORAL").isEmpty());
        assertEquals(0, repositorio.count());
    }

    // ── LISTAR ───────────────────────────────────────────────────────

    @Test
    @DisplayName("listar: devuelve todos los cupones persistidos")
    void listar() {
        repositorio.save(nuevoCupon("UNO", "FIXED", 5.0));
        repositorio.save(nuevoCupon("DOS", "PERCENT", 10.0));
        repositorio.save(nuevoCupon("TRES", "FIXED", 15.0));
        em.flush();
        em.clear();

        assertEquals(3, repositorio.findAll().size());
    }

    @Test
    @DisplayName("listar: findByCode recupera el cupón y distingue el inexistente")
    void listarPorCodigo() {
        repositorio.save(nuevoCupon("BUSCADO", "PERCENT", 30.0));
        em.flush();
        em.clear();

        Optional<Coupon> encontrado = repositorio.findByCode("BUSCADO");
        assertTrue(encontrado.isPresent());
        assertEquals(30.0, encontrado.get().getDiscountValue());

        assertTrue(repositorio.findByCode("NOEXISTE").isEmpty(),
                "un código ausente debe devolver vacío, no lanzar");
    }

    @Test
    @DisplayName("listar: la búsqueda del panel filtra por código ignorando mayúsculas y pagina")
    void listarBuscandoPaginado() {
        repositorio.save(nuevoCupon("VERANO2026", "PERCENT", 10.0));
        repositorio.save(nuevoCupon("verano-extra", "FIXED", 20.0));
        repositorio.save(nuevoCupon("INVIERNO", "FIXED", 30.0));
        em.flush();
        em.clear();

        Page<Coupon> pagina = repositorio
                .findByCodeContainingIgnoreCaseOrderByCreatedAtDesc("verano", PageRequest.of(0, 10));

        assertEquals(2, pagina.getTotalElements(), "debe encontrar ambos sin importar mayúsculas");

        Page<Coupon> primeraDeUna = repositorio
                .findByCodeContainingIgnoreCaseOrderByCreatedAtDesc("verano", PageRequest.of(0, 1));
        assertEquals(1, primeraDeUna.getContent().size());
        assertEquals(2, primeraDeUna.getTotalElements(), "el total no cambia con el tamaño de página");
    }
}
