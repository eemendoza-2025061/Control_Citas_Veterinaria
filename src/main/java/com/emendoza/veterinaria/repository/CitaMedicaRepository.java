package com.emendoza.veterinaria.repository;

import com.emendoza.veterinaria.entity.CitaMedica;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CitaMedicaRepository extends JpaRepository<CitaMedica, Long> {

    // Regla 1: cruce de horario del VET (cita dura 30 min). Excluye CANCELADA.
    // Solapan si |nueva - existente| < 30 min, es decir, la existente está en
    // (nueva-30min, nueva+30min) con desigualdad ESTRICTA: citas que solo se
    // tocan en el borde (p.ej. 10:00 y 10:30) NO solapan y se permiten.
    @Query("select case when count(c) > 0 then true else false end from CitaMedica c " +
           "where c.veterinario.id = :veterinarioId " +
           "and c.fechaHora > :inicio and c.fechaHora < :fin " +
           "and c.estado <> :estado")
    boolean existsSolapamientoVet(
            @Param("veterinarioId") Long veterinarioId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("estado") CitaMedica.Estado estado);

    // Variante para reprogramar: igual que la anterior pero excluye la propia
    // cita (si no, cualquier update detectaría "cruce" consigo misma).
    @Query("select case when count(c) > 0 then true else false end from CitaMedica c " +
           "where c.veterinario.id = :veterinarioId " +
           "and c.fechaHora > :inicio and c.fechaHora < :fin " +
           "and c.estado <> :estado and c.id <> :excluirId")
    boolean existsSolapamientoVetExcluyendo(
            @Param("veterinarioId") Long veterinarioId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("estado") CitaMedica.Estado estado,
            @Param("excluirId") Long excluirId);

    // Regla 2: conteo de PENDIENTES del cliente en un día (JOIN a mascota.cliente).
    @Query("SELECT COUNT(c) FROM CitaMedica c " +
           "WHERE c.mascota.cliente.id = :clienteId " +
           "AND c.estado = :estado " +
           "AND c.fechaHora BETWEEN :inicio AND :fin")
    long countCitasPendientesPorClienteYFecha(
            @Param("clienteId") Long clienteId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("estado") CitaMedica.Estado estado);

    // Variante para reprogramar: no cuenta la propia cita.
    @Query("SELECT COUNT(c) FROM CitaMedica c " +
           "WHERE c.mascota.cliente.id = :clienteId " +
           "AND c.estado = :estado " +
           "AND c.fechaHora BETWEEN :inicio AND :fin " +
           "AND c.id <> :excluirId")
    long countCitasPendientesPorClienteYFechaExcluyendo(
            @Param("clienteId") Long clienteId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("estado") CitaMedica.Estado estado,
            @Param("excluirId") Long excluirId);

    // Para impedir el borrado físico de mascotas/citas con historial activo.
    boolean existsByMascotaId(Long mascotaId);

    // Agenda: por veterinario + rango (paginado para estrés).
    Page<CitaMedica> findByVeterinarioIdAndFechaHoraBetween(
            Long veterinarioId, LocalDateTime inicio, LocalDateTime fin, Pageable pageable);

    // Agenda: solo por rango de fechas (ADMIN).
    Page<CitaMedica> findByFechaHoraBetween(LocalDateTime inicio, LocalDateTime fin, Pageable pageable);

    List<CitaMedica> findByVeterinarioIdAndFechaHoraBetween(
            Long veterinarioId, LocalDateTime inicio, LocalDateTime fin);
}
