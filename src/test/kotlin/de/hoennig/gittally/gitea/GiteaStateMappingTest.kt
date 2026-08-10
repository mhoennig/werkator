package de.hoennig.gittally.gitea

import de.hoennig.gittally.build.BuildStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class GiteaStateMappingTest :
    FunSpec({

        test("maps each build status to its Gitea state") {
            BuildStatus.SUCCESS.toGiteaState() shouldBe "success"
            BuildStatus.FAILED.toGiteaState() shouldBe "failure"
            BuildStatus.CANCELLED.toGiteaState() shouldBe "failure"
            BuildStatus.PENDING.toGiteaState() shouldBe "pending"
            BuildStatus.RUNNING.toGiteaState() shouldBe "pending"
            // interrupted builds are re-enqueued on startup, so the commit must not turn red
            BuildStatus.INTERRUPTED.toGiteaState() shouldBe "pending"
        }

        test("maps Gitea states back to build statuses") {
            buildStatusFromGiteaState("success") shouldBe BuildStatus.SUCCESS
            buildStatusFromGiteaState("failure") shouldBe BuildStatus.FAILED
            buildStatusFromGiteaState("error") shouldBe BuildStatus.FAILED
            buildStatusFromGiteaState("warning") shouldBe BuildStatus.FAILED
            buildStatusFromGiteaState("pending") shouldBe BuildStatus.RUNNING
        }

        test("maps unknown Gitea states to null") {
            buildStatusFromGiteaState("").shouldBeNull()
            buildStatusFromGiteaState("deleted").shouldBeNull()
            buildStatusFromGiteaState("SUCCESS").shouldBeNull()
        }
    })
