package de.hoennig.gittally.server

import de.hoennig.gittally.watcher.Watcher
import de.hoennig.gittally.watcher.WatcherState
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class WatcherApiController(
    private val watcher: Watcher,
) {
    /** Watcher health: whether the loop runs, last poll, last fetch/poll error. */
    @GetMapping("/api/watcher")
    fun watcher(): WatcherState = watcher.state()
}
