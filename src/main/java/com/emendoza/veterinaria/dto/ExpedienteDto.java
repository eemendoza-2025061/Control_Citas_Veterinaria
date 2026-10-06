package com.emendoza.veterinaria.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.time.LocalDateTime;

public class ExpedienteDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Request {
        @NotNull(message = "citaId es obligatorio")
        private Long citaId;
        @NotBlank(message = "diagnostico es obligatorio")
        private String diagnostico;
        @NotBlank(message = "tratamiento es obligatorio")
        private String tratamiento;
        private Double pesoKg;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Response {
        private Long id;
        private Long citaId;
        private Long mascotaId;
        private String diagnostico;
        private String tratamiento;
        private Double pesoKg;
        private LocalDateTime fechaRegistro;
    }
}
