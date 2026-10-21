package com.bytemarket.order.security;

import com.bytemarket.order.security.PermissionsCache.Permiso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * El portero de /api/admin en pedidos, que es el reparto más fino.
 *
 * Aquí viven juntas la caja del cajero y la caja de su jefe, y el
 * SecurityConfig lo distinguía con once reglas escritas verbo por verbo. Lo
 * que se comprueba es que ese reparto siga intacto ahora que lo decide la
 * matriz: quien vende no cuenta su propio cajón.
 */
@DisplayName("Permisos — el portero de /api/admin en pedidos")
class ModuloAuthorizationManagerTest {

    private PermissionsCache cache;
    private ModuloAuthorizationManager portero;

    private static final Permiso NADA = new Permiso(false, false, false, false);
    private static final Permiso VER = new Permiso(true, false, false, false);
    private static final Permiso TODO = new Permiso(true, true, true, true);

    @BeforeEach
    void setUp() {
        cache = mock(PermissionsCache.class);
        when(cache.permiso(any(), any())).thenReturn(NADA);
        portero = new ModuloAuthorizationManager(cache);
    }

    private boolean permite(String rol, String metodo, String ruta) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "quien@bytemarket.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + rol.toUpperCase())));
        var decision = portero.check(() -> auth,
                new RequestAuthorizationContext(new MockHttpServletRequest(metodo, ruta)));
        return decision != null && decision.isGranted();
    }

    /** Un cajero con lo que trae de fábrica: solo su caja. */
    private void cajeroConSuCaja() {
        when(cache.permiso(eq("cajero"), eq("caja"))).thenReturn(TODO);
    }

    // ── Lo que hace el cajero ────────────────────────────────────

    @Test
    @DisplayName("el cajero vende, ve su turno, lo abre y lo cierra")
    void elCajeroHaceLoSuyo() {
        cajeroConSuCaja();

        assertTrue(permite("cajero", "POST", "/api/admin/orders"), "registrar la venta");
        assertTrue(permite("cajero", "GET", "/api/admin/orders/sale-options"), "formas de pago para cobrar");
        assertTrue(permite("cajero", "GET", "/api/admin/caja/sesion"), "ver su turno");
        assertTrue(permite("cajero", "POST", "/api/admin/caja/abrir"));
        assertTrue(permite("cajero", "POST", "/api/admin/caja/cerrar"));
    }

    @Test
    @DisplayName("el cajero NO cuenta ni cuadra su propio cajón")
    void elCajeroNoCuentaSuCajon() {
        cajeroConSuCaja();

        /* La separación de funciones que justifica todo el módulo de caja:
           el cajero cobra, su jefe le entrega el fondo y le cuadra. Esas
           rutas son del módulo "cajas", que el cajero no tiene. */
        assertFalse(permite("cajero", "POST", "/api/admin/caja/arqueo"));
        assertFalse(permite("cajero", "POST", "/api/admin/caja/cuadrar"));
        assertFalse(permite("cajero", "POST", "/api/admin/caja/movimiento"));
        assertFalse(permite("cajero", "GET", "/api/admin/caja/turnos"));
    }

    @Test
    @DisplayName("el cajero no lista pedidos: ahí van los datos de todos los clientes")
    void elCajeroNoListaPedidos() {
        cajeroConSuCaja();

        /* El alta es /api/admin/orders a secas y la consulta lleva más ruta.
           Si la regla del POST no fuera de ruta exacta, abrirle la venta le
           abriría el listado entero: nombre, teléfono, correo y dirección de
           cada cliente de la tienda. */
        assertFalse(permite("cajero", "GET", "/api/admin/orders"));
        assertFalse(permite("cajero", "GET", "/api/admin/orders/1234"));
        assertFalse(permite("cajero", "PUT", "/api/admin/orders/1234/status"));
    }

    // ── Lo que hace su jefe ──────────────────────────────────────

    @Test
    @DisplayName("el jefe entrega el fondo y cuadra, y consulta pedidos sin cambiarlos")
    void elJefeHaceLoSuyo() {
        when(cache.permiso(eq("jefe_ventas"), eq("caja"))).thenReturn(TODO);
        when(cache.permiso(eq("jefe_ventas"), eq("cajas"))).thenReturn(TODO);
        when(cache.permiso(eq("jefe_ventas"), eq("pedidos"))).thenReturn(VER);
        when(cache.permiso(eq("jefe_ventas"), eq("reportes"))).thenReturn(VER);

        assertTrue(permite("jefe_ventas", "POST", "/api/admin/caja/arqueo"));
        assertTrue(permite("jefe_ventas", "POST", "/api/admin/caja/cuadrar"));
        assertTrue(permite("jefe_ventas", "GET", "/api/admin/orders"));
        assertTrue(permite("jefe_ventas", "GET", "/api/admin/reports/dashboard"));
        assertTrue(permite("jefe_ventas", "GET", "/api/admin/automation/stock-alerts"));
        /* El seguimiento de pedidos abiertos está bajo /automation pero
           devuelve customerName y customerPhone, así que pide Pedidos. El
           jefe lo tiene, así que para él no cambia nada. */
        assertTrue(permite("jefe_ventas", "GET", "/api/admin/automation/follow-ups"));

        // Cambiar el estado de un pedido es gestión de tienda, no cuadre.
        assertFalse(permite("jefe_ventas", "PUT", "/api/admin/orders/1234/status"));
        assertFalse(permite("jefe_ventas", "GET", "/api/admin/coupons"));
    }

    @Test
    @DisplayName("quien administra Pedidos también puede registrar una venta")
    void pedidosTambienHabilitaVender() {
        /* La otra mitad: vender pide Caja, pero quien gestiona Pedidos no
           necesita que además le den la caja del mostrador para dar de alta
           una venta. */
        when(cache.permiso(eq("admin"), eq("pedidos"))).thenReturn(TODO);

        assertTrue(permite("admin", "POST", "/api/admin/orders"));
        assertTrue(permite("admin", "GET", "/api/admin/orders/sale-options"));
    }

    // ── Los de siempre ───────────────────────────────────────────

    @Test
    @DisplayName("el gerente entra a todo sin consultar la matriz")
    void elGerenteEntraATodo() {
        assertTrue(permite("superadmin", "POST", "/api/admin/caja/cuadrar"));
        assertTrue(permite("superadmin", "DELETE", "/api/admin/coupons/3"));
        verify(cache, never()).permiso(any(), any());
    }

    @Test
    @DisplayName("Reportes no abre los datos de contacto de los clientes")
    void reportesNoFiltraDatosDeClientes() {
        /* El caso que abre hacer configurable el permiso: si el gerente le
           concede Reportes a un cajero, no debe venir con los nombres y
           teléfonos que el módulo Pedidos protege. follow-ups los devuelve,
           así que pide Pedidos aunque viva bajo /automation. */
        when(cache.permiso(eq("cajero"), eq("caja"))).thenReturn(TODO);
        when(cache.permiso(eq("cajero"), eq("reportes"))).thenReturn(TODO);

        assertTrue(permite("cajero", "GET", "/api/admin/reports/dashboard"));
        assertFalse(permite("cajero", "GET", "/api/admin/automation/follow-ups"),
                "ahí van nombre y teléfono de cada cliente con un pedido abierto");
    }

    @Test
    @DisplayName("conceder Dashboard da de verdad las cifras de la portada")
    void elModuloDashboardConcedeLaPortada() {
        /* El módulo "dashboard" no tenía ninguna ruta: marcarlo en Roles y
           permisos solo enseñaba la entrada del menú, y la pantalla seguía
           pidiendo sus cifras a /reports y /automation, que son de Reportes.
           Quien recibía solo Dashboard se encontraba una portada que
           respondía 403 en todo. */
        when(cache.permiso(eq("cajero"), eq("dashboard"))).thenReturn(VER);

        assertTrue(permite("cajero", "GET", "/api/admin/reports/dashboard"));
        assertTrue(permite("cajero", "GET", "/api/admin/automation/reports"));
        assertTrue(permite("cajero", "GET", "/api/admin/automation/stock-alerts"));

        // Pero la portada no abre el resto de Reportes ni los datos de clientes.
        assertFalse(permite("cajero", "GET", "/api/admin/reports/sales"));
        assertFalse(permite("cajero", "GET", "/api/admin/reports/export"));
        assertFalse(permite("cajero", "GET", "/api/admin/automation/follow-ups"),
                "ahí van nombre y teléfono de cada cliente con un pedido abierto");
    }

    @Test
    @DisplayName("quien tiene Reportes sigue entrando a la portada sin Dashboard")
    void reportesSigueAbriendoLaPortada() {
        when(cache.permiso(eq("jefe_ventas"), eq("reportes"))).thenReturn(VER);
        assertTrue(permite("jefe_ventas", "GET", "/api/admin/reports/dashboard"));
        assertTrue(permite("jefe_ventas", "GET", "/api/admin/automation/stock-alerts"));
    }

    @Test
    @DisplayName("una ruta de /api/admin sin módulo declarado NO pasa")
    void deniegaPorOmision() {
        assertFalse(permite("admin", "GET", "/api/admin/modulo-que-nadie-declaro"));
    }

    @Test
    @DisplayName("un cliente de la tienda no entra, y sin sesión tampoco")
    void losDeFueraNoEntran() {
        assertFalse(permite("customer", "POST", "/api/admin/orders"));
        var decision = portero.check(() -> null,
                new RequestAuthorizationContext(new MockHttpServletRequest("POST", "/api/admin/orders")));
        assertFalse(decision.isGranted());
    }
}
