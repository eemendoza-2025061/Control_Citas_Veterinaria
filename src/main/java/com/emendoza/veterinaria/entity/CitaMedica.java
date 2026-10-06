package com.emendoza.veterinaria.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "citas_medicas", indexes = {
        @Index(name = "idx_cita_vet_fecha", columnList = "veterinario_id, fechaHora"),
        @Index(name = "idx_cita_mascota", columnList = "mascota_id"),
        @Index(name = "idx_cita_estado", columnList = "estado")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CitaMedica {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mascota_id", nullable = false)
    @ToString.Exclude
    private Mascota mascota;

    // FK real hacia usuarios con rol VET. LAZY siempre.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "veterinario_id", nullable = false)
    @ToString.Exclude
    private Usuario veterinario;

    @Column(nullable = false)
    private LocalDateTime fechaHora;

    @Column(length = 500)
    private String motivo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Estado estado = Estado.PENDIENTE;

    @OneToOne(mappedBy = "cita", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    private ExpedienteClinico expediente;

    public enum Estado { PENDIENTE, COMPLETADA, CANCELADA }
}
