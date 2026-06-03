package com.padosol.urlshortener.repository

import com.padosol.urlshortener.domain.Url
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UrlRepository : JpaRepository<Url, Long> {

    fun findByShortKey(shortKey: String): Url?

    /** 전역 카운터에서 다음 id를 받아온다. */
    @Query(value = "SELECT nextval('url_id_seq')", nativeQuery = true)
    fun nextId(): Long
}
