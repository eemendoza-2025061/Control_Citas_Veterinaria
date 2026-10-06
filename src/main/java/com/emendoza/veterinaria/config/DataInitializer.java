package com.emendoza.veterinaria.config;

import com.emendoza.veterinaria.entity.Usuario;
import com.emendoza.veterinaria.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    // Carga inicial exigida: 1 ADMIN + 1 VET + 1 CLIENTE (sin SQL manual).
    @Bean
    public CommandLineRunner initData(UsuarioRepository repo, PasswordEncoder encoder) {
        return args -> {
            if (repo.count() == 0) {
                repo.save(Usuario.builder().nombre("Admin").telefono("10000000")
                        .email("admin@vet.com").password(encoder.encode("1234"))
                        .rol(Usuario.Rol.ADMIN).build());
                repo.save(Usuario.builder().nombre("Dr. Vet").telefono("20000000")
                        .email("vet@vet.com").password(encoder.encode("1234"))
                        .rol(Usuario.Rol.VET).build());
                repo.save(Usuario.builder().nombre("Cliente 1").telefono("30000000")
                        .email("cliente@mail.com").password(encoder.encode("1234"))
                        .rol(Usuario.Rol.CLIENTE).build());
            }
        };
    }
}
