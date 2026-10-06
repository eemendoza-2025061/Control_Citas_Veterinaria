package com.emendoza.veterinaria.service;

import com.emendoza.veterinaria.dto.MascotaDto;
import com.emendoza.veterinaria.entity.Mascota;
import com.emendoza.veterinaria.entity.Usuario;
import com.emendoza.veterinaria.exception.BusinessRuleException;
import com.emendoza.veterinaria.exception.ResourceNotFoundException;
import com.emendoza.veterinaria.repository.MascotaRepository;
import com.emendoza.veterinaria.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MascotaService {

    private final MascotaRepository mascotaRepository;
    private final UsuarioRepository usuarioRepository;

    private MascotaDto.Response toResponse(Mascota m) {
        return MascotaDto.Response.builder()
                .id(m.getId())
                .nombre(m.getNombre())
                .especie(m.getEspecie())
                .raza(m.getRaza())
                .edad(m.getEdad())
                .clienteId(m.getCliente() != null ? m.getCliente().getId() : null)
                .nombreCliente(m.getCliente() != null ? m.getCliente().getNombre() : null)
                .build();
    }

    // CLIENTE registra para sí mismo; ADMIN puede registrar para cualquier cliente.
    @Transactional
    public MascotaDto.Response registrar(MascotaDto.Request req, String emailAutenticado) {
        Usuario auth = usuarioRepository.findByEmail(emailAutenticado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));

        Usuario dueno;
        if (auth.getRol() == Usuario.Rol.ADMIN && req.getClienteId() != null) {
            dueno = usuarioRepository.findById(req.getClienteId())
                    .orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));
            if (dueno.getRol() != Usuario.Rol.CLIENTE) {
                throw new BusinessRuleException("El dueño debe tener rol CLIENTE");
            }
        } else {
            if (auth.getRol() != Usuario.Rol.CLIENTE && auth.getRol() != Usuario.Rol.ADMIN) {
                throw new BusinessRuleException("Solo CLIENTE o ADMIN pueden registrar mascotas");
            }
            dueno = auth;
        }

        Mascota m = Mascota.builder()
                .nombre(req.getNombre())
                .especie(req.getEspecie())
                .raza(req.getRaza())
                .edad(req.getEdad())
                .cliente(dueno)
                .build();
        return toResponse(mascotaRepository.save(m));
    }

    @Transactional(readOnly = true)
    public Page<MascotaDto.Response> misMascotas(String email, Pageable pageable) {
        Usuario auth = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        return mascotaRepository.findByClienteId(auth.getId(), pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public MascotaDto.Response ficha(Long id) {
        Mascota m = mascotaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mascota no encontrada"));
        // Fuerza carga del cliente dentro de la transacción (evita LazyInitialization).
        if (m.getCliente() != null) m.getCliente().getNombre();
        return toResponse(m);
    }
}
