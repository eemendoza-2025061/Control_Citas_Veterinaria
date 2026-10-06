package com.emendoza.veterinaria.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "mascotas", indexes = {
        @Index(name = "idx_mascota_cliente", columnList = "cliente_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Mascota {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Especie especie;

    @Column(length = 100)
    private String raza;

    private Integer edad;

    // FK real hacia usuarios (dueño CLIENTE). LAZY para evitar EAGER y N+1.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cliente_id", nullable = false)
    @ToString.Exclude
    private Usuario cliente;

    public enum Especie { PERRO, GATO, AVE, OTRO }
}
