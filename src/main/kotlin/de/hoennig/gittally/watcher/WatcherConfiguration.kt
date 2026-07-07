package de.hoennig.gittally.watcher

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class WatcherConfiguration {
    /** UTC clock, injectable in tests; auto-build slots are UTC `HH:MM` like legacy. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
