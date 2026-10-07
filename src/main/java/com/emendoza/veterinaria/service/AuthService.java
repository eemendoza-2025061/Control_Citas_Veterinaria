package com.emendoza.veterinaria.service;

import com.emendoza.veterinaria.dto.AuthDto;
import com.emendoza.veterinaria.entity.Usuario;
import com.emendoza.veterinaria.exception.BusinessRuleException;
import com.emendoza.veterinaria.repository.UsuarioRepository;
import com.emendoza.veterinaria.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    // Registro público: siempre rol CLIENTE (según matriz del documento).
    @Transactional
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest req) {
        if (usuarioRepository.existsByEmail(req.getEmail())) {
            throw new BusinessRuleException("El email ya está registrado");
        }
        Usuario u = Usuario.builder()
                .nombre(req.getNombre())
                .telefono(req.getTelefono())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .rol(Usuario.Rol.CLIENTE)
                .build();
        try {
            usuarioRepository.saveAndFlush(u);
        } catch (DataIntegrityViolationException e) {
            // Carrera: dos registros simultáneos con el mismo email (unique email).
            throw new BusinessRuleException("El email ya está registrado");
        }
        String token = jwtUtil.generarToken(u.getEmail(), u.getRol().name());
        return new AuthDto.AuthResponse(token, u.getEmail(), u.getRol().name());
    }

    public AuthDto.AuthResponse login(AuthDto.LoginRequest req) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        Usuario u = usuarioRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new BusinessRuleException("Credenciales inválidas"));
        String token = jwtUtil.generarToken(u.getEmail(), u.getRol().name());
        return new AuthDto.AuthResponse(token, u.getEmail(), u.getRol().name());
    }
}
