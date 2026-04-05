package com.codecatalyst.entity;

import com.codecatalyst.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscription extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false, unique = true)
    private Organization organization;

    @Column(name = "max_targets", nullable = false)
    @Builder.Default
    private Integer maxTargets = 10;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "subscription_status")
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.TRIAL;
}
