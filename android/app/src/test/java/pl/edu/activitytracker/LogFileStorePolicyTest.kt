package pl.edu.activitytracker

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.edu.activitytracker.storage.PartialArtifactAction
import pl.edu.activitytracker.storage.partialArtifactAction

class LogFileStorePolicyTest {
    @Test
    fun createsOnlyWhenBothArtifactsAreAbsent() {
        assertEquals(
            PartialArtifactAction.Create,
            partialArtifactAction(false, false, false),
        )
    }

    @Test
    fun resumesOnlyACompleteMatchingPair() {
        assertEquals(
            PartialArtifactAction.Resume,
            partialArtifactAction(true, true, true),
        )
    }

    @Test
    fun quarantinesIncompleteOrMismatchedArtifacts() {
        assertEquals(PartialArtifactAction.Quarantine, partialArtifactAction(true, false, false))
        assertEquals(PartialArtifactAction.Quarantine, partialArtifactAction(false, true, false))
        assertEquals(PartialArtifactAction.Quarantine, partialArtifactAction(true, true, false))
    }
}
