package com.codecatalyst.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "organizations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Organization extends BaseEntity {
    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String slug;
}
