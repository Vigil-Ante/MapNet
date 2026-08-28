package com.mapnet.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ProblemSolverModelsTest {
    @Test
    fun `wifi failure takes priority`() {
        assertEquals(
            ProblemDiagnosis.NOT_ON_WIFI,
            classifyProblem(listOf(check(ProblemCheckId.WIFI, ProblemCheckStatus.FAILED)))
        )
    }

    @Test
    fun `router failure takes priority over later failures`() {
        assertEquals(
            ProblemDiagnosis.ROUTER_UNREACHABLE,
            classifyProblem(
                listOf(
                    check(ProblemCheckId.WIFI, ProblemCheckStatus.PASSED),
                    check(ProblemCheckId.GATEWAY, ProblemCheckStatus.FAILED),
                    check(ProblemCheckId.DNS, ProblemCheckStatus.FAILED)
                )
            )
        )
    }

    @Test
    fun `dns internet and device failures are classified`() {
        assertEquals(ProblemDiagnosis.DNS_FAILURE, classifyProblem(listOf(check(ProblemCheckId.DNS, ProblemCheckStatus.FAILED))))
        assertEquals(ProblemDiagnosis.INTERNET_LIMITED, classifyProblem(listOf(check(ProblemCheckId.INTERNET, ProblemCheckStatus.FAILED))))
        assertEquals(ProblemDiagnosis.DEVICE_UNREACHABLE, classifyProblem(listOf(check(ProblemCheckId.DEVICE, ProblemCheckStatus.FAILED))))
    }

    @Test
    fun `unknown result is incomplete and successful checks are clear`() {
        assertEquals(ProblemDiagnosis.INCOMPLETE, classifyProblem(listOf(check(ProblemCheckId.GATEWAY, ProblemCheckStatus.UNKNOWN))))
        assertEquals(
            ProblemDiagnosis.ALL_CLEAR,
            classifyProblem(
                ProblemCheckId.entries.map { id ->
                    check(id, if (id == ProblemCheckId.DEVICE) ProblemCheckStatus.SKIPPED else ProblemCheckStatus.PASSED)
                }
            )
        )
    }

    private fun check(id: ProblemCheckId, status: ProblemCheckStatus) =
        ProblemSolverCheck(id, id.name, status, "test")
}
