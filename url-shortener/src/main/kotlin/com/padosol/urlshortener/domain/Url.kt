package com.padosol.urlshortener.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "url")
class Url(
    @Id
    val id: Long,

    @Column(name = "short_key", nullable = false, unique = true, length = 16)
    val shortKey: String,

    @Column(name = "long_url", nullable = false, length = 2048)
    val longUrl: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "expire_at")
    val expireAt: Instant? = null,
)
