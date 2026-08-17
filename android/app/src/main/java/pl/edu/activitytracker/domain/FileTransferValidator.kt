package pl.edu.activitytracker.domain

enum class FileFrameDecision {
    Accept,
    Duplicate,
    Gap,
    Overflow,
}

object FileTransferValidator {
    fun evaluate(
        expectedOffset: Long,
        expectedSize: Long,
        frame: FileDataFrame,
    ): FileFrameDecision {
        return when {
            frame.offset < expectedOffset -> FileFrameDecision.Duplicate
            frame.offset > expectedOffset -> FileFrameDecision.Gap
            expectedOffset + frame.data.size > expectedSize -> FileFrameDecision.Overflow
            else -> FileFrameDecision.Accept
        }
    }
}
