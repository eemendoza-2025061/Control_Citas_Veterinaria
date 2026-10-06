package com.emendoza.veterinaria.dto;

import com.emendoza.veterinaria.entity.Mascota;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

public class MascotaDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Request {
        @NotBlank(message = "nombre es obligatorio")
        private String nombre;
        @NotNull(message = "especie es obligatoria")
        private Mascota.Especie especie;
        private String raza;
        private Integer edad;
        // Solo ADMIN lo envía para registrar a nombre de un cliente.
        // Si es CLIENTE, se ignora y se usa el id del JWT.
        private Long clienteId;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Response {
        private Long id;
        private String nombre;
        private Mascota.Especie especie;
        private String raza;
        private Integer edad;
        private Long clienteId;
        private String nombreCliente;
    }
}
