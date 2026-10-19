package com.bytemarket.order.config;

import com.bytemarket.order.repository.ICashSessionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pone al día los turnos de caja guardados con los modelos anteriores.
 *
 * Hace falta porque el esquema lo crea Hibernate con ddl-auto: update, y
 * ese modo es menos de lo que parece: AÑADE columnas y tablas, pero no
 * toca las que ya existen ni mueve un solo dato. Así que aquí van las dos
 * cosas que le faltan.
 *
 * Todo es idempotente: arrancar cien veces deja lo mismo que arrancar una.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class CajaMigracion implements CommandLineRunner {

    private final ICashSessionRepository sessionRepository;
    private final EntityManager entityManager;

    /**
     * Las columnas que dejaron de ser obligatorias.
     *
     * `opened_at` y `opened_by` se escribían al crear el turno, porque lo
     * creaba quien lo abría. Ahora el turno nace cuando el jefe asigna el
     * fondo y no se abre hasta que el cajero lo confirma, así que entre una
     * cosa y la otra están vacías.
     *
     * Hibernate no quita un NOT NULL que ya está puesto —solo añade—, así
     * que sin este ALTER la primera asignación de fondo revienta con
     * "Column 'opened_at' cannot be null" y un 500 sin explicación.
     */
    private static final List<String> AFLOJAR = List.of(
            "ALTER TABLE cash_sessions MODIFY COLUMN opened_at datetime(6) NULL",
            "ALTER TABLE cash_sessions MODIFY COLUMN opened_by bigint NULL");

    @Override
    @Transactional
    public void run(String... args) {
        for (String ddl : AFLOJAR) {
            try {
                entityManager.createNativeQuery(ddl).executeUpdate();
            } catch (Exception e) {
                // En una base que no sea MySQL —las pruebas usan H2— la
                // sintaxis no vale, y tampoco hace falta: allí el esquema
                // se crea de cero con las anotaciones de ahora.
                System.out.println("↷ Caja: no hizo falta aflojar la columna (" + e.getClass().getSimpleName() + ")");
            }
        }

        int conCajero = sessionRepository.rellenarCajeroDeTurnosAntiguos();
        if (conCajero > 0) {
            System.out.println("🔁 Caja: " + conCajero + " turno(s) antiguo(s) con cajero asignado.");
        }

        // Y la traducción de los datos: cuando el jefe abría la caja él
        // mismo y cerrar y cuadrar eran la misma acción.
        int traducidos = sessionRepository.traducirTurnosDelModeloViejo();
        if (traducidos > 0) {
            System.out.println("🔁 Caja: " + traducidos + " turno(s) traducido(s) al flujo de cinco estados.");
        }
    }
}
