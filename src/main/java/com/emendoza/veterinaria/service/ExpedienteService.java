package com.emendoza.veterinaria.service;

import com.emendoza.veterinaria.dto.ExpedienteDto;
import com.emendoza.veterinaria.entity.CitaMedica;
import com.emendoza.veterinaria.entity.ExpedienteClinico;
import com.emendoza.veterinaria.entity.Usuario;
import com.emendoza.veterinaria.exception.BusinessRuleException;
import com.emendoza.veterinaria.exception.ForbiddenOperationException;
import com.emendoza.veterinaria.exception.ResourceNotFoundException;
import com.emendoza.veterinaria.repository.CitaMedicaRepository;
import com.emendoza.veterinaria.repository.ExpedienteClinicoRepository;
import com.emendoza.veterinaria.repository.MascotaRepository;
import com.emendoza.veterinaria.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExpedienteService {

    private final ExpedienteClinicoRepository expedienteRepository;
    private final CitaMedicaRepository citaRepository;
    private final MascotaRepository mascotaRepository;
    private final UsuarioRepository usuarioRepository;

    private ExpedienteDto.Response toResponse(ExpedienteClinico e) {
        return ExpedienteDto.Response.builder()
                .id(e.getId())
                .citaId(e.getCita() != null ? e.getCita().getId() : null)
                .mascotaId((e.getCita() != null && e.getCita().getMascota() != null)
                        ? e.getCita().getMascota().getId() : null)
                .diagnostico(e.getDiagnostico())
                .tratamiento(e.getTratamiento())
                .pesoKg(e.getPesoKg())
                .fechaRegistro(e.getFechaRegistro())
                .build();
    }

    // VET/ADMIN: registra diagnóstico y marca la cita COMPLETADA (transaccional).
    @Transactional
    public ExpedienteDto.Response registrar(ExpedienteDto.Request req) {
        CitaMedica cita = citaRepository.findById(req.getCitaId())
                .orElseThrow(() -> new ResourceNotFoundException("Cita no encontrada"));
        if (cita.getEstado() == CitaMedica.Estado.CANCELADA) {
            throw new BusinessRuleException("No se puede registrar expediente de una cita CANCELADA");
        }
        if (expedienteRepository.existsByCitaId(cita.getId())) {
            throw new BusinessRuleException("La cita ya tiene un expediente registrado");
        }
        ExpedienteClinico exp = ExpedienteClinico.builder()
                .cita(cita)
                .diagnostico(req.getDiagnostico())
                .tratamiento(req.getTratamiento())
                .pesoKg(req.getPesoKg())
                .fechaRegistro(LocalDateTime.now())
                .build();
        cita.setEstado(CitaMedica.Estado.COMPLETADA);
        citaRepository.save(cita);
        try {
            return toResponse(expedienteRepository.saveAndFlush(exp));
        } catch (DataIntegrityViolationException e) {
            // Carrera: dos registros simultáneos para la misma cita (unique cita_id).
            throw new BusinessRuleException("La cita ya tiene un expediente registrado");
        }
    }

    // Historial por mascota. CLIENTE solo ve sus mascotas; VET/ADMIN todo.
    @Transactional(readOnly = true)
    public List<ExpedienteDto.Response> historialPorMascota(Long mascotaId, String emailAutenticado) {
        Usuario auth = usuarioRepository.findByEmail(emailAutenticado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));
        var mascota = mascotaRepository.findById(mascotaId)
                .orElseThrow(() -> new ResourceNotFoundException("Mascota no encontrada"));
        if (auth.getRol() == Usuario.Rol.CLIENTE
                && !mascota.getCliente().getId().equals(auth.getId())) {
            throw new ForbiddenOperationException("Solo puede ver el historial de sus propias mascotas");
        }
        return expedienteRepository.findByCitaMascotaIdOrderByFechaRegistroDesc(mascotaId)
                .stream().map(this::toResponse).toList();
    }

    // VET/ADMIN: corrige diagnóstico/tratamiento/peso. La cita NO cambia
    // (el DTO no trae citaId).
    @Transactional
    public ExpedienteDto.Response actualizar(Long id, ExpedienteDto.UpdateRequest req) {
        ExpedienteClinico exp = expedienteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expediente no encontrado"));
        exp.setDiagnostico(req.getDiagnostico());
        exp.setTratamiento(req.getTratamiento());
        exp.setPesoKg(req.getPesoKg());
        return toResponse(expedienteRepository.save(exp));
    }

    // Solo ADMIN: borra el expediente. Si su cita había quedado COMPLETADA
    // por este expediente, vuelve a PENDIENTE para no dejar una cita
    // "completada" sin historial clínico (consistencia del dominio).
    @Transactional
    public void eliminar(Long id) {
        ExpedienteClinico exp = expedienteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expediente no encontrado"));
        CitaMedica cita = exp.getCita();
        expedienteRepository.delete(exp);
        if (cita != null && cita.getEstado() == CitaMedica.Estado.COMPLETADA) {
            cita.setEstado(CitaMedica.Estado.PENDIENTE);
            citaRepository.save(cita);
        }
    }
}
