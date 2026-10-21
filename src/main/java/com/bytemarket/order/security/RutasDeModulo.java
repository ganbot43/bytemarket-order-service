package com.bytemarket.order.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;

/**
 * A qué módulo del panel pertenece cada ruta de /api/admin de ESTE servicio.
 *
 * Es el mapa más fino de los cuatro servicios, porque aquí viven juntas la
 * caja del cajero y la caja del jefe. Las reglas que había en el
 * SecurityConfig distinguían a mano, verbo por verbo, cosas como que abrir y
 * cerrar el turno son del cajero pero contarlo y cuadrarlo son de su jefe.
 * Ese reparto se conserva entero, solo que ahora expresado como módulos:
 *
 *   "caja"   lo que hace el cajero: vender, ver su turno, abrirlo y cerrarlo.
 *   "cajas"  lo que hace su jefe: entregar el fondo, mover efectivo, arquear
 *            y cuadrar. Quien vende no cuenta su propio cajón.
 *
 * Una regla puede declarar varios módulos y basta con tener uno: vender
 * necesita Caja, pero quien administra Pedidos también puede registrar una
 * venta sin que haya que concederle la caja del mostrador.
 *
 * Gana la coincidencia más específica: primero la ruta más larga, y a igual
 * longitud la que además fija el verbo o exige ruta exacta. Así el orden de
 * la lista no cambia el resultado, que es de donde salían los errores de
 * "esta ruta se la llevó por delante el comodín".
 */
public final class RutasDeModulo {

    private RutasDeModulo() {}

    private record Regla(String patron, boolean exacta, Set<String> metodos, List<String> modulos) {}

    private static final List<String> VENDER = List.of("caja", "pedidos");

    /**
     * Lo que alimenta la portada del panel.
     *
     * El módulo "dashboard" no tenía ninguna ruta: marcarlo en Roles y
     * permisos solo enseñaba la entrada del menú, y la pantalla seguía
     * pidiendo sus cifras a /reports y /automation, que son de Reportes.
     * Conceder Dashboard a alguien le dejaba una portada que respondía 403
     * en todo —el fallo de "tabla vacía" contra el que avisa este propio
     * servicio—, y quitárselo no le cerraba nada porque el permiso de
     * Reportes seguía abriendo las mismas rutas.
     *
     * Van los dos módulos y basta con uno: quien administra Reportes sigue
     * entrando igual, y quien solo tiene la portada ya ve sus cifras.
     */
    private static final List<String> PORTADA = List.of("reportes", "dashboard");

    private static final List<Regla> REGLAS = List.of(
            /* Registrar una venta. Ruta exacta: /api/admin/orders a secas es
               el alta, y /api/admin/orders/... es la consulta. */
            new Regla("/api/admin/orders", true, Set.of("POST"), VENDER),
            /* Las formas de pago y los canales. El cajero las necesita para
               cobrar y no traen ni un dato de ningún cliente. */
            new Regla("/api/admin/orders/sale-options", true, Set.of("GET"), VENDER),

            /* Las tres cosas que el cajero hace con su propia caja. Abrir es
               suyo porque esa confirmación es la que firma en el ticket de
               apertura; cerrar también, porque cerrar es "he acabado el
               turno", no "esto es lo que hay". */
            new Regla("/api/admin/caja/sesion", true, Set.of("GET"), List.of("caja")),
            new Regla("/api/admin/caja/abrir", true, Set.of("POST"), List.of("caja")),
            new Regla("/api/admin/caja/cerrar", true, Set.of("POST"), List.of("caja")),
            /* Fondos, movimientos, arqueo y cuadre: del jefe. */
            new Regla("/api/admin/caja", false, Set.of(), List.of("cajas")),

            /* El listado de pedidos trae nombre, teléfono, correo y dirección
               de cada cliente de la tienda, así que no es del mostrador. */
            new Regla("/api/admin/orders", false, Set.of(), List.of("pedidos")),

            new Regla("/api/admin/reports", false, Set.of(), List.of("reportes")),
            new Regla("/api/admin/automation", false, Set.of(), List.of("reportes")),
            /* Las tres consultas que pinta la portada. No llevan datos de
               clientes: son cifras del día, el aviso de stock bajo y el
               resumen de automatizaciones. */
            new Regla("/api/admin/reports/dashboard", true, Set.of("GET"), PORTADA),
            new Regla("/api/admin/automation/reports", true, Set.of("GET"), PORTADA),
            new Regla("/api/admin/automation/stock-alerts", true, Set.of("GET"), PORTADA),
            /* Los pedidos que llevan horas abiertos. Está bajo /automation y
               se pinta en la portada, pero devuelve customerName y
               customerPhone: es una vista de PEDIDOS, y dejarla en "reportes"
               filtraba por ahí justo lo que el módulo Pedidos protege —basta
               conceder Reportes a un cajero para darle nombres y teléfonos de
               clientes—. El reparto de hoy no cambia: quien tiene Reportes
               tiene también Pedidos. */
            new Regla("/api/admin/automation/follow-ups", true, Set.of("GET"), List.of("pedidos")),
            new Regla("/api/admin/coupons", false, Set.of(), List.of("cupones")),
            new Regla("/api/admin/payment-methods", false, Set.of(), List.of("metodos_pago")),
            /* El backfill del kardex: reconstruye movimientos perdidos. */
            new Regla("/api/admin/inventory", false, Set.of(), List.of("inventario")));

    /** Los módulos que habilitan esta petición; vacío si la ruta no está declarada. */
    public static List<String> modulos(HttpServletRequest request) {
        String path = request.getRequestURI();
        String metodo = request.getMethod();

        List<String> mejor = List.of();
        int mejorPuntaje = -1;
        for (Regla r : REGLAS) {
            if (!r.metodos().isEmpty() && !r.metodos().contains(metodo)) continue;
            boolean casa = r.exacta()
                    ? path.equals(r.patron())
                    : path.equals(r.patron()) || path.startsWith(r.patron() + "/");
            if (!casa) continue;

            /* La longitud manda, y a igualdad desempatan la ruta exacta y el
               verbo fijado, que son las que declaran un caso que la general
               no cubre. */
            int puntaje = r.patron().length() * 10
                    + (r.exacta() ? 2 : 0)
                    + (r.metodos().isEmpty() ? 0 : 1);
            if (puntaje > mejorPuntaje) {
                mejor = r.modulos();
                mejorPuntaje = puntaje;
            }
        }
        return mejor;
    }

    /**
     * Qué acción pide esta petición, según el verbo.
     *
     * GET y HEAD consultan, POST da de alta, PUT y PATCH modifican, DELETE da
     * de baja. Sustituye a tener una regla por verbo en el SecurityConfig,
     * que es de donde salían los errores de "esta ruta se la llevó por
     * delante el comodín".
     *
     * Un POST que no crea nada —abrir la caja, cuadrar un turno— cuenta como
     * crear, y es lo correcto: son actos que dejan un registro nuevo, no
     * consultas.
     */
    public static String accion(HttpServletRequest request) {
        return switch (request.getMethod()) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "edit";
            case "DELETE" -> "delete";
            default -> "view";
        };
    }
}
