package com.emendoza.veterinaria.dto;

import com.emendoza.veterinaria.entity.CitaMedica;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.time.LocalDateTime;

public class CitaDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Request {
        @NotNull(message = "mascotaId es obligatorio")
        private Long mascotaId;
        @NotNull(message = "veterinarioId es obligatorio")
        private Long veterinarioId;
        @NotNull(message = "fechaHora es obligatoria")
        @Future(message = "fechaHora debe ser futura")
        private LocalDateTime fechaHora;
        private String motivo;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Response {
        private Long id;
        private Long mascotaId;
        private String nombreMascota;
        private Long veterinarioId;
        private String nombreVeterinario;
        private LocalDateTime fechaHora;
        private String motivo;
        private CitaMedica.Estado estado;
    }
}
