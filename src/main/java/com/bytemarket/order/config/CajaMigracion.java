package com.bytemarket.order.config;

import com.bytemarket.order.repository.ICashSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pone al día los turnos de caja guardados antes de separar al cajero de
 * quien apertura.
 *
 * El esquema lo crea Hibernate con ddl-auto: update, que añade las columnas
 * nuevas vacías; los datos no los toca nadie. Las filas viejas quedarían
 * con `cashier_id` a null, y entonces el histórico de arqueos saldría sin
 * cajero: un arqueo sin dueño no sirve para lo único que hace falta, saber
 * a quién preguntar por un faltante.
 *
 * Es idempotente —solo toca lo que está a null—, así que arrancar cien
 * veces da el mismo resultado que arrancar una.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class CajaMigracion implements CommandLineRunner {

    private final ICashSessionRepository sessionRepository;

    @Override
    @Transactional
    public void run(String... args) {
        int filas = sessionRepository.rellenarCajeroDeTurnosAntiguos();
        if (filas > 0) {
            System.out.println("🔁 Caja: " + filas + " turno(s) antiguo(s) con cajero asignado.");
        }
    }
}
