package com.emendoza.veterinaria.service;

import com.emendoza.veterinaria.dto.CitaDto;
import com.emendoza.veterinaria.entity.CitaMedica;
import com.emendoza.veterinaria.entity.Mascota;
import com.emendoza.veterinaria.entity.Usuario;
import com.emendoza.veterinaria.exception.BusinessRuleException;
import com.emendoza.veterinaria.exception.ResourceNotFoundException;
import com.emendoza.veterinaria.repository.CitaMedicaRepository;
import com.emendoza.veterinaria.repository.MascotaRepository;
import com.emendoza.veterinaria.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CitaService {

    private final CitaMedicaRepository citaRepository;
    private final MascotaRepository mascotaRepository;
    private final UsuarioRepository usuarioRepository;

    private CitaDto.Response toResponse(CitaMedica c) {
        return CitaDto.Response.builder()
                .id(c.getId())
                .mascotaId(c.getMascota() != null ? c.getMascota().getId() : null)
                .nombreMascota(c.getMascota() != null ? c.getMascota().getNombre() : null)
                .veterinarioId(c.getVeterinario() != null ? c.getVeterinario().getId() : null)
                .nombreVeterinario(c.getVeterinario() != null ? c.getVeterinario().getNombre() : null)
                .fechaHora(c.getFechaHora())
                .motivo(c.getMotivo())
                .estado(c.getEstado())
                .build();
    }

    // Reglas: 1) Vet sin cruce de 30 min. 2) Máx 2 PENDIENTES/día por CLIENTE.
    // 3) CLIENTE solo agenda para sus propias mascotas (ADMIN sí puede todo).
    @Transactional
    public CitaDto.Response agendarCita(CitaDto.Request req, String emailAutenticado) {
        Usuario auth = usuarioRepository.findByEmail(emailAutenticado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));

        Mascota mascota = mascotaRepository.findById(req.getMascotaId())
                .orElseThrow(() -> new ResourceNotFoundException("Mascota no encontrada"));

        if (auth.getRol() == Usuario.Rol.CLIENTE
                && !mascota.getCliente().getId().equals(auth.getId())) {
            throw new BusinessRuleException("Solo puede agendar citas para sus propias mascotas");
        }

        Usuario vet = usuarioRepository.findById(req.getVeterinarioId())
                .orElseThrow(() -> new ResourceNotFoundException("Veterinario no encontrado"));
        if (vet.getRol() != Usuario.Rol.VET) {
            throw new BusinessRuleException("El usuario indicado no es VET");
        }

        LocalDateTime fechaHora = req.getFechaHora();
        if (fechaHora.isBefore(LocalDateTime.now().plusMinutes(1))) {
            throw new BusinessRuleException("La cita debe programarse en el futuro");
        }

        // Regla 1: duración fija 30 min, sin cruce. Ventana (fechaHora-29min, fechaHora+30min).
        LocalDateTime finCita = fechaHora.plusMinutes(30);
        boolean hayCruce = citaRepository.existsByVeterinarioIdAndFechaHoraBetweenAndEstadoNot(
                vet.getId(), fechaHora.minusMinutes(29), finCita, CitaMedica.Estado.CANCELADA);
        if (hayCruce) {
            throw new BusinessRuleException("El veterinario no tiene disponibilidad en ese horario.");
        }

        // Regla 2: máximo 2 PENDIENTES el mismo día para el CLIENTE dueño.
        LocalDateTime inicioDia = fechaHora.toLocalDate().atStartOfDay();
        LocalDateTime finDia = inicioDia.plusDays(1).minusNanos(1);
        long activas = citaRepository.countCitasPendientesPorClienteYFecha(
                mascota.getCliente().getId(), inicioDia, finDia, CitaMedica.Estado.PENDIENTE);
        if (activas >= 2) {
            throw new BusinessRuleException("El cliente ya posee el límite de 2 citas pendientes para este día.");
        }

        CitaMedica cita = CitaMedica.builder()
                .mascota(mascota)
                .veterinario(vet)
                .fechaHora(fechaHora)
                .motivo(req.getMotivo())
                .estado(CitaMedica.Estado.PENDIENTE)
                .build();
        return toResponse(citaRepository.save(cita));
    }

    @Transactional(readOnly = true)
    public Page<CitaDto.Response> agenda(Long veterinarioId, LocalDateTime desde, LocalDateTime hasta, Pageable pageable) {
        LocalDateTime d = (desde != null) ? desde : LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime h = (hasta != null) ? hasta : d.plusDays(30);
        Page<CitaMedica> page = (veterinarioId != null)
                ? citaRepository.findByVeterinarioIdAndFechaHoraBetween(veterinarioId, d, h, pageable)
                : citaRepository.findByFechaHoraBetween(d, h, pageable);
        return page.map(this::toResponse);
    }

    // Regla 3: solo se cancela si faltan MÁS de 2 horas. CLIENTE solo cancela lo suyo.
    @Transactional
    public CitaDto.Response cancelar(Long citaId, String emailAutenticado) {
        Usuario auth = usuarioRepository.findByEmail(emailAutenticado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));
        CitaMedica cita = citaRepository.findById(citaId)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));

        if (cita.getEstado() != CitaMedica.Estado.PENDIENTE) {
            throw new BusinessRuleException("Solo se pueden cancelar citas en estado PENDIENTE");
        }
        if (auth.getRol() == Usuario.Rol.CLIENTE
                && !cita.getMascota().getCliente().getId().equals(auth.getId())) {
            throw new BusinessRuleException("Solo puede cancelar citas de sus propias mascotas");
        }
        long minutosRestantes = Duration.between(LocalDateTime.now(), cita.getFechaHora()).toMinutes();
        if (minutosRestantes < 120) {
            throw new BusinessRuleException("La cita solo puede cancelarse con más de 2 horas de anticipación");
        }
        cita.setEstado(CitaMedica.Estado.CANCELADA);
        return toResponse(citaRepository.save(cita));
    }
}
