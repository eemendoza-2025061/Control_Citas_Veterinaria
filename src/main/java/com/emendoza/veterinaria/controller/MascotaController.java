package com.emendoza.veterinaria.controller;

import com.emendoza.veterinaria.dto.MascotaDto;
import com.emendoza.veterinaria.service.MascotaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mascotas")
@RequiredArgsConstructor
public class MascotaController {

    private final MascotaService mascotaService;

    // CLIENTE: sus mascotas (contexto del JWT, paginado para estrés).
    @GetMapping("/mis-mascotas")
    @PreAuthorize("hasRole('CLIENTE')")
    public ResponseEntity<Page<MascotaDto.Response>> misMascotas(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int s = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, s, Sort.by("id").ascending());
        return ResponseEntity.ok(mascotaService.misMascotas(auth.getName(), pageable));
    }

    // CLIENTE (para sí) + ADMIN (para cualquier cliente vía clienteId).
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENTE','ADMIN')")
    public ResponseEntity<MascotaDto.Response> registrar(
            @Valid @RequestBody MascotaDto.Request req, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mascotaService.registrar(req, auth.getName()));
    }

    // VET + ADMIN: ficha completa.
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('VET','ADMIN')")
    public ResponseEntity<MascotaDto.Response> ficha(@PathVariable Long id) {
        return ResponseEntity.ok(mascotaService.ficha(id));
    }
}
