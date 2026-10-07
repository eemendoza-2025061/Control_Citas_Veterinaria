package com.emendoza.veterinaria.service;

import com.emendoza.veterinaria.dto.CitaDto;
import com.emendoza.veterinaria.entity.CitaMedica;
import com.emendoza.veterinaria.entity.Mascota;
import com.emendoza.veterinaria.entity.Usuario;
import com.emendoza.veterinaria.exception.BusinessRuleException;
import com.emendoza.veterinaria.exception.ForbiddenOperationException;
import com.emendoza.veterinaria.exception.ResourceNotFoundException;
import com.emendoza.veterinaria.repository.CitaMedicaRepository;
import com.emendoza.veterinaria.repository.MascotaRepository;
import com.emendoza.veterinaria.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    //
    // Concurrencia: el clásico exists()+save() permite duplicados cuando dos
    // hilos verifican a la vez. Un lock sobre el rango no sirve si el rango
    // está vacío (no hay filas que bloquear -> phantom). Por eso se bloquean
    // con PESSIMISTIC_WRITE las filas del VETERINARIO y del CLIENTE dueño
    // (siempre en orden ascendente de id para evitar deadlocks): todas las
    // reservas concurrentes para el mismo vet/cliente se serializan dentro de
    // esta transacción, y la verificación + inserción se vuelven atómicas.
    // Como segunda barrera, la BD tiene UNIQUE(veterinario_id, fechaHora) para
    // el duplicado exacto -> DataIntegrityViolationException -> 409.
    @Transactional
    public CitaDto.Response agendarCita(CitaDto.Request req, String emailAutenticado) {
        Usuario auth = usuarioRepository.findByEmail(emailAutenticado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));

        Mascota mascota = mascotaRepository.findById(req.getMascotaId())
                .orElseThrow(() -> new ResourceNotFoundException("Mascota no encontrada"));

        if (auth.getRol() == Usuario.Rol.CLIENTE
                && !mascota.getCliente().getId().equals(auth.getId())) {
            throw new ForbiddenOperationException("Solo puede agendar citas para sus propias mascotas");
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

        Long duenoId = mascota.getCliente().getId();

        // Bloqueo pesimista en orden ascendente de id (anti-deadlock).
        Long primero = vet.getId() < duenoId ? vet.getId() : duenoId;
        Long segundo = vet.getId() < duenoId ? duenoId : vet.getId();
        usuarioRepository.findByIdForUpdate(primero)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (!segundo.equals(primero)) {
            usuarioRepository.findByIdForUpdate(segundo)
                    .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        }

        // Regla 1: duración fija 30 min, sin cruce (|diff| < 30 min solapa).
        // Desigualdad estricta: 10:00 y 10:30 solo se tocan -> se permiten;
        // 10:00 y 10:15 solapan -> se rechazan.
        LocalDateTime inicioVentana = fechaHora.minusMinutes(30);
        LocalDateTime finVentana = fechaHora.plusMinutes(30);
        boolean hayCruce = citaRepository.existsSolapamientoVet(
                vet.getId(), inicioVentana, finVentana, CitaMedica.Estado.CANCELADA);
        if (hayCruce) {
            throw new BusinessRuleException("El veterinario no tiene disponibilidad en ese horario.");
        }

        // Regla 2: máximo 2 PENDIENTES el mismo día para el CLIENTE dueño.
        LocalDateTime inicioDia = fechaHora.toLocalDate().atStartOfDay();
        LocalDateTime finDia = inicioDia.plusDays(1).minusNanos(1);
        long activas = citaRepository.countCitasPendientesPorClienteYFecha(
                duenoId, inicioDia, finDia, CitaMedica.Estado.PENDIENTE);
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
        try {
            return toResponse(citaRepository.saveAndFlush(cita));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessRuleException("El veterinario ya tiene una cita en ese horario.");
        }
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

    // Regla 3: solo se cancela si faltan MÁS de 2 horas (estricto: 120 min exactos NO bastan).
    // CLIENTE solo cancela lo suyo.
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
            throw new ForbiddenOperationException("Solo puede cancelar citas de sus propias mascotas");
        }
        Duration restante = Duration.between(LocalDateTime.now(), cita.getFechaHora());
        if (restante.compareTo(Duration.ofHours(2)) <= 0) {
            throw new BusinessRuleException("La cita solo puede cancelarse con más de 2 horas de anticipación");
        }
        cita.setEstado(CitaMedica.Estado.CANCELADA);
        return toResponse(citaRepository.save(cita));
    }

    // Reprogramar cita: CLIENTE solo las suyas; ADMIN cualquiera.
    // Solo PENDIENTE. Se revalidan TODAS las reglas (futuro, vet VET, cruce
    // 30 min excluyendo la propia cita, límite 2/día excluyendo la propia).
    // La mascota NO cambia (el DTO no la trae). Con bloqueo pesimista igual
    // que en agendarCita para evitar carreras concurrentes.
    @Transactional
    public CitaDto.Response actualizar(Long citaId, CitaDto.UpdateRequest req, String emailAutenticado) {
        Usuario auth = usuarioRepository.findByEmail(emailAutenticado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));
        CitaMedica cita = citaRepository.findById(citaId)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));

        if (cita.getEstado() != CitaMedica.Estado.PENDIENTE) {
            throw new BusinessRuleException("Solo se pueden reprogramar citas en estado PENDIENTE");
        }
        if (auth.getRol() == Usuario.Rol.CLIENTE
                && !cita.getMascota().getCliente().getId().equals(auth.getId())) {
            throw new ForbiddenOperationException("Solo puede modificar citas de sus propias mascotas");
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

        Long duenoId = cita.getMascota().getCliente().getId();
        Long primero = vet.getId() < duenoId ? vet.getId() : duenoId;
        Long segundo = vet.getId() < duenoId ? duenoId : vet.getId();
        usuarioRepository.findByIdForUpdate(primero)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (!segundo.equals(primero)) {
            usuarioRepository.findByIdForUpdate(segundo)
                    .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        }

        boolean hayCruce = citaRepository.existsSolapamientoVetExcluyendo(
                vet.getId(), fechaHora.minusMinutes(30), fechaHora.plusMinutes(30),
                CitaMedica.Estado.CANCELADA, citaId);
        if (hayCruce) {
            throw new BusinessRuleException("El veterinario no tiene disponibilidad en ese horario.");
        }

        LocalDateTime inicioDia = fechaHora.toLocalDate().atStartOfDay();
        LocalDateTime finDia = inicioDia.plusDays(1).minusNanos(1);
        long activas = citaRepository.countCitasPendientesPorClienteYFechaExcluyendo(
                duenoId, inicioDia, finDia, CitaMedica.Estado.PENDIENTE, citaId);
        if (activas >= 2) {
            throw new BusinessRuleException("El cliente ya posee el límite de 2 citas pendientes para este día.");
        }

        cita.setVeterinario(vet);
        cita.setFechaHora(fechaHora);
        cita.setMotivo(req.getMotivo());
        try {
            return toResponse(citaRepository.saveAndFlush(cita));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessRuleException("El veterinario ya tiene una cita en ese horario.");
        }
    }

    // Borrado físico: CLIENTE solo las suyas; ADMIN cualquiera.
    // Solo CANCELADA (una PENDIENTE debe cancelarse, no borrarse: así no se
    // evade la regla de las 2 horas; una COMPLETADA tiene historial clínico
    // que debe conservarse). Con expediente asociado tampoco se borra.
    @Transactional
    public void eliminar(Long citaId, String emailAutenticado) {
        Usuario auth = usuarioRepository.findByEmail(emailAutenticado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));
        CitaMedica cita = citaRepository.findById(citaId)
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));
        if (auth.getRol() == Usuario.Rol.CLIENTE
                && !cita.getMascota().getCliente().getId().equals(auth.getId())) {
            throw new ForbiddenOperationException("Solo puede eliminar citas de sus propias mascotas");
        }
        if (cita.getEstado() != CitaMedica.Estado.CANCELADA) {
            throw new BusinessRuleException("Solo se pueden eliminar citas CANCELADA (use cancelar para una PENDIENTE)");
        }
        if (cita.getExpediente() != null) {
            throw new BusinessRuleException("No se puede eliminar la cita porque tiene expediente clínico asociado");
        }
        citaRepository.delete(cita);
    }
}
