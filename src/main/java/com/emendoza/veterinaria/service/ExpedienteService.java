package com.emendoza.veterinaria.service;

import com.emendoza.veterinaria.dto.ExpedienteDto;
import com.emendoza.veterinaria.entity.CitaMedica;
import com.emendoza.veterinaria.entity.ExpedienteClinico;
import com.emendoza.veterinaria.entity.Usuario;
import com.emendoza.veterinaria.exception.BusinessRuleException;
import com.emendoza.veterinaria.exception.ResourceNotFoundException;
import com.emendoza.veterinaria.repository.CitaMedicaRepository;
import com.emendoza.veterinaria.repository.ExpedienteClinicoRepository;
import com.emendoza.veterinaria.repository.MascotaRepository;
import com.emendoza.veterinaria.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
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
        return toResponse(expedienteRepository.save(exp));
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
            throw new BusinessRuleException("Solo puede ver el historial de sus propias mascotas");
        }
        return expedienteRepository.findByCitaMascotaIdOrderByFechaRegistroDesc(mascotaId)
                .stream().map(this::toResponse).toList();
    }
}
