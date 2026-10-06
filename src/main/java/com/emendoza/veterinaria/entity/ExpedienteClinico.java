package com.emendoza.veterinaria.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "expedientes_clinicos", indexes = {
        @Index(name = "idx_exp_cita", columnList = "cita_id", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExpedienteClinico {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Una cita genera máximo un expediente (unique).
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cita_id", nullable = false, unique = true)
    @ToString.Exclude
    private CitaMedica cita;

    @Column(nullable = false, length = 1000)
    private String diagnostico;

    @Column(nullable = false, length = 1000)
    private String tratamiento;

    private Double pesoKg;

    @Column(nullable = false)
    private LocalDateTime fechaRegistro;
}
