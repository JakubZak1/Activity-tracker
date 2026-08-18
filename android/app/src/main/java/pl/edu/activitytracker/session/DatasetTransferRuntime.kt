package pl.edu.activitytracker.session

import android.content.Context
import pl.edu.activitytracker.data.DatasetWorkRuntime

class DatasetTransferRuntime(context: Context) : DatasetWorkRuntime {
    private val appContext = context.applicationContext

    override fun setActive(active: Boolean) {
        if (active) {
            runCatching { DatasetTransferService.start(appContext) }
        } else {
            DatasetTransferService.stop(appContext)
        }
    }
}
