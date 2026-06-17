package com.padosol.notification

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
	fromApplication<NotificationApplication>().with(TestcontainersConfiguration::class).run(*args)
}
