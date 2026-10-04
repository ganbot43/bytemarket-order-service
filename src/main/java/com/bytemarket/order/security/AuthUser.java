package com.bytemarket.order.security;

/**
 * Usuario autenticado extraído del JWT emitido por user-service.
 * El token lleva los claims "uid" y "role", así este servicio no necesita
 * consultar la base de usuarios (que vive en otro microservicio).
 */
public record AuthUser(Long id, String email, String role) {
    public boolean isStaff() {
        return "admin".equals(role) || "superadmin".equals(role);
    }
    public boolean isCustomer() {
        return "customer".equals(role);
    }
}
