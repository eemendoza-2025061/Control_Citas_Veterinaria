package com.emendoza.veterinaria.controller;

import com.emendoza.veterinaria.dto.CitaDto;
import com.emendoza.veterinaria.service.CitaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/citas")
@RequiredArgsConstructor
public class CitaController {

    private final CitaService citaService;

    // CLIENTE + ADMIN: agenda cita (valida cruces y límite diario).
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENTE','ADMIN')")
    public ResponseEntity<CitaDto.Response> agendar(
            @Valid @RequestBody CitaDto.Request req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(citaService.agendarCita(req, auth.getName()));
    }

    // VET + ADMIN: agenda por fecha y/o veterinario (paginada para estrés).
    @GetMapping("/agenda")
    @PreAuthorize("hasAnyRole('VET','ADMIN')")
    public ResponseEntity<Page<CitaDto.Response>> agenda(
            @RequestParam(required = false) Long veterinarioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int s = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, s, Sort.by("fechaHora").ascending());
        return ResponseEntity.ok(citaService.agenda(veterinarioId, desde, hasta, pageable));
    }

    // CLIENTE + ADMIN: cancela si faltan > 2 horas.
    @PatchMapping("/{id}/cancelar")
    @PreAuthorize("hasAnyRole('CLIENTE','ADMIN')")
    public ResponseEntity<CitaDto.Response> cancelar(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(citaService.cancelar(id, auth.getName()));
    }

    // CLIENTE (suya) + ADMIN (cualquiera): reprograma vet/fecha/motivo.
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENTE','ADMIN')")
    public ResponseEntity<CitaDto.Response> actualizar(
            @PathVariable Long id,
            @Valid @RequestBody CitaDto.UpdateRequest req,
            Authentication auth) {
        return ResponseEntity.ok(citaService.actualizar(id, req, auth.getName()));
    }

    // CLIENTE (suya) + ADMIN (cualquiera): borrado físico solo CANCELADA.
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENTE','ADMIN')")
    public ResponseEntity<Void> eliminar(@PathVariable Long id, Authentication auth) {
        citaService.eliminar(id, auth.getName());
        return ResponseEntity.noContent().build();
    }
}
