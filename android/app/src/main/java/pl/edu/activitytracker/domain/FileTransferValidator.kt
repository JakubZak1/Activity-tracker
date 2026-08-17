package pl.edu.activitytracker.domain

enum class FileFrameDecision {
    Accept,
    Duplicate,
    Gap,
    Overlap,
    Overflow,
    Empty,
}

object FileTransferValidator {
    fun evaluate(
        expectedOffset: Long,
        expectedSize: Long,
        frame: FileDataFrame,
    ): FileFrameDecision {
        if (frame.data.isEmpty()) return FileFrameDecision.Empty
        val frameEnd = frame.offset + frame.data.size.toLong()
        return when {
            frame.offset > expectedOffset -> FileFrameDecision.Gap
            frame.offset < expectedOffset && frameEnd <= expectedOffset -> FileFrameDecision.Duplicate
            frame.offset < expectedOffset -> FileFrameDecision.Overlap
            frameEnd > expectedSize || frameEnd < frame.offset -> FileFrameDecision.Overflow
            else -> FileFrameDecision.Accept
        }
    }
}
