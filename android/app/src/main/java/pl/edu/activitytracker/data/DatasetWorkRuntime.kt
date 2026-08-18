package pl.edu.activitytracker.data

fun interface DatasetWorkRuntime {
    fun setActive(active: Boolean)

    companion object {
        val NoOp = DatasetWorkRuntime { }
    }
}
