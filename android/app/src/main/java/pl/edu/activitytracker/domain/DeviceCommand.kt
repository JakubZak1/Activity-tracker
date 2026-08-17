package pl.edu.activitytracker.domain

sealed class DeviceCommand(val payload: String) {
    data object Start : DeviceCommand("start")
    data object Stop : DeviceCommand("stop")
    data object Status : DeviceCommand("status")
    data object ListLogs : DeviceCommand("list")
    data object Cancel : DeviceCommand("cancel")
    data object ResetSession : DeviceCommand("reset_session")
    data object ModeDataset : DeviceCommand("mode_dataset")
    data object ModeInference : DeviceCommand("mode_inference")

    data class SetLabel(val label: String) : DeviceCommand("label $label")
    data class Download(val fileName: String, val offset: Long) :
        DeviceCommand("download $fileName $offset")
    data class Delete(val fileName: String) : DeviceCommand("delete $fileName")
}
