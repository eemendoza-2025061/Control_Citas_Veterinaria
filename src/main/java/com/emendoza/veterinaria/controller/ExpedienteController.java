package com.emendoza.veterinaria.controller;

import com.emendoza.veterinaria.dto.ExpedienteDto;
import com.emendoza.veterinaria.service.ExpedienteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/expedientes")
@RequiredArgsConstructor
public class ExpedienteController {

    private final ExpedienteService expedienteService;

    // VET + ADMIN: registra diagnóstico y completa la cita.
    @PostMapping
    @PreAuthorize("hasAnyRole('VET','ADMIN')")
    public ResponseEntity<ExpedienteDto.Response> registrar(@Valid @RequestBody ExpedienteDto.Request req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(expedienteService.registrar(req));
    }

    // VET + CLIENTE + ADMIN: historial de la mascota.
    @GetMapping("/mascota/{mascotaId}")
    @PreAuthorize("hasAnyRole('VET','CLIENTE','ADMIN')")
    public ResponseEntity<List<ExpedienteDto.Response>> historial(
            @PathVariable Long mascotaId, Authentication auth) {
        return ResponseEntity.ok(expedienteService.historialPorMascota(mascotaId, auth.getName()));
    }

    // VET + ADMIN: corrige diagnóstico/tratamiento/peso (la cita no cambia).
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('VET','ADMIN')")
    public ResponseEntity<ExpedienteDto.Response> actualizar(
            @PathVariable Long id,
            @Valid @RequestBody ExpedienteDto.UpdateRequest req) {
        return ResponseEntity.ok(expedienteService.actualizar(id, req));
    }

    // Solo ADMIN: borra el expediente (su cita COMPLETADA vuelve a PENDIENTE).
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        expedienteService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
