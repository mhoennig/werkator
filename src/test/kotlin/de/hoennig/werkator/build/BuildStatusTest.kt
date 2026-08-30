package de.hoennig.werkator.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuildStatusTest : FunSpec() {
    init {
        test("terminal statuses are all but pending and running") {
            BuildStatus.entries.filter { it.isTerminal } shouldBe
                listOf(BuildStatus.SUCCESS, BuildStatus.FAILED, BuildStatus.INTERRUPTED, BuildStatus.CANCELLED)
        }

        test("restartable statuses are pending, running, and interrupted") {
            BuildStatus.entries.filter { it.isRestartable } shouldBe
                listOf(BuildStatus.PENDING, BuildStatus.RUNNING, BuildStatus.INTERRUPTED)
        }
    }
}
