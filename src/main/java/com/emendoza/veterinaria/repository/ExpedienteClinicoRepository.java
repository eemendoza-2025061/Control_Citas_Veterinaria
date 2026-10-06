package com.emendoza.veterinaria.repository;

import com.emendoza.veterinaria.entity.ExpedienteClinico;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpedienteClinicoRepository extends JpaRepository<ExpedienteClinico, Long> {
    Optional<ExpedienteClinico> findByCitaId(Long citaId);
    boolean existsByCitaId(Long citaId);

    // Historial de una mascota (JOIN cita -> mascota), ordenado por fecha.
    List<ExpedienteClinico> findByCitaMascotaIdOrderByFechaRegistroDesc(Long mascotaId);
}
