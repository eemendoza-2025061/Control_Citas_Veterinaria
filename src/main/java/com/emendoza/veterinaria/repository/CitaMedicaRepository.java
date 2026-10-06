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
    // Existe cruce si hay cita del mismo vet en [inicio, fin) con estado != CANCELADA.
    boolean existsByVeterinarioIdAndFechaHoraBetweenAndEstadoNot(
            Long veterinarioId, LocalDateTime inicio, LocalDateTime fin, CitaMedica.Estado estado);

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

    // Agenda: por veterinario + rango (paginado para estrés).
    Page<CitaMedica> findByVeterinarioIdAndFechaHoraBetween(
            Long veterinarioId, LocalDateTime inicio, LocalDateTime fin, Pageable pageable);

    // Agenda: solo por rango de fechas (ADMIN).
    Page<CitaMedica> findByFechaHoraBetween(LocalDateTime inicio, LocalDateTime fin, Pageable pageable);

    List<CitaMedica> findByVeterinarioIdAndFechaHoraBetween(
            Long veterinarioId, LocalDateTime inicio, LocalDateTime fin);
}
