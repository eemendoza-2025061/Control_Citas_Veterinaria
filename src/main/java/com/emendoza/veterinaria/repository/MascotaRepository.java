package com.emendoza.veterinaria.repository;

import com.emendoza.veterinaria.entity.Mascota;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MascotaRepository extends JpaRepository<Mascota, Long> {
    // /mis-mascotas: solo las del cliente autenticado (paginado para estrés)
    Page<Mascota> findByClienteId(Long clienteId, Pageable pageable);

    List<Mascota> findByClienteId(Long clienteId);
}
