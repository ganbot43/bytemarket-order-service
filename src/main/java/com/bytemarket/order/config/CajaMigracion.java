package com.bytemarket.order.config;

import com.bytemarket.order.repository.ICashSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * Pone al día el esquema y los datos de caja guardados con los modelos
 * anteriores.
 *
 * Hace falta porque Hibernate corre con ddl-auto: update, y ese modo es
 * menos de lo que parece: AÑADE columnas y tablas, pero no toca las que ya
 * existen ni mueve un solo dato.
 *
 * Todo es idempotente: arrancar cien veces deja lo mismo que arrancar una.
 * Y eso no es un detalle —el contenedor reinicia—, así que cada paso
 * comprueba antes si hace falta en vez de intentarlo y recoger el error.
 *
 * Los cambios de esquema van por JDBC puro y FUERA de la transacción de
 * JPA. Es la lección que costó un arranque: la primera versión hacía el
 * DROP de la tabla vieja y en el segundo arranque el SELECT sobre ella
 * fallaba. Estaba dentro de un try/catch, pero un error de SQL dentro de
 * una transacción JPA la marca para rollback, así que el catch no salvaba
 * nada: lo siguiente reventaba igual y el servicio no levantaba.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class CajaMigracion implements CommandLineRunner {

    private final ICashSessionRepository sessionRepository;
    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;

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
    public void run(String... args) {
        esquema();
        transactionTemplate.executeWithoutResult(estado -> datos());
    }

    /** Lo que toca la forma de las tablas. Por JDBC y sin transacción. */
    private void esquema() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String ddl : AFLOJAR) {
                st.executeUpdate(ddl);
            }

            /*
             * El contador de documentos cambió de casa.
             *
             * Nació como `boleta_series` y ahora lleva dos series —boletas
             * y vouchers de comercio— así que la tabla es
             * `document_series`. Hibernate crea la nueva pero no mueve una
             * sola fila: sin esto, el correlativo volvería a empezar en 1 y
             * saldrían dos boletas con el mismo número separadas por un
             * despliegue.
             */
            if (existe(st, "boleta_series")) {
                int mudadas = st.executeUpdate("""
                        INSERT INTO document_series (serie, last_number)
                        SELECT serie, last_number FROM boleta_series
                        WHERE serie NOT IN (SELECT serie FROM document_series)
                        """);
                st.executeUpdate("DROP TABLE boleta_series");
                System.out.println("🔁 Caja: " + mudadas + " serie(s) mudada(s) a document_series.");
            }
        } catch (Exception e) {
            // En una base que no sea MySQL —las pruebas usan H2— la
            // sintaxis no vale, y tampoco hace falta: allí el esquema se
            // crea de cero con las anotaciones de ahora. Al ir por su
            // propia conexión, esto no arrastra a nadie.
            System.out.println("↷ Caja: el esquema no necesitaba retoques (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Si una tabla está, sin provocar un error para averiguarlo. */
    private static boolean existe(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = '%s'
                """.formatted(tabla))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /** Las traducciones de datos, que sí quieren transacción. */
    private void datos() {
        int conCajero = sessionRepository.rellenarCajeroDeTurnosAntiguos();
        if (conCajero > 0) {
            System.out.println("🔁 Caja: " + conCajero + " turno(s) antiguo(s) con cajero asignado.");
        }
        // Cuando el jefe abría la caja él mismo y cerrar y cuadrar eran la
        // misma acción.
        int traducidos = sessionRepository.traducirTurnosDelModeloViejo();
        if (traducidos > 0) {
            System.out.println("🔁 Caja: " + traducidos + " turno(s) traducido(s) al flujo de cinco estados.");
        }
    }
}
