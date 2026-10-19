package com.bytemarket.order.repository;

import com.bytemarket.order.model.SerieDocumento;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ISerieDocumentoRepository extends JpaRepository<SerieDocumento, String> {

    /**
     * El contador de una serie, bloqueando su fila hasta el commit.
     *
     * Es lo que impide que dos ventas simultáneas se lleven el mismo
     * número. Sin el bloqueo, las dos leerían el mismo "último emitido" y
     * saldrían dos boletas B001-00000123: dos comprobantes distintos para
     * el mismo número, que es justo lo que un correlativo existe para
     * evitar.
     *
     * Es un comprobante: aquí se prefiere que una de las dos ventas espere
     * unos milisegundos a que las dos pasen y rompan la numeración.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM SerieDocumento b WHERE b.serie = :serie")
    Optional<SerieDocumento> bloquear(@Param("serie") String serie);
}
