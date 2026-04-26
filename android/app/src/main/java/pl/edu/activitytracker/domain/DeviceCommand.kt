package pl.edu.activitytracker.domain

enum class DeviceCommand(val payload: String) {
    Start("start"),
    Stop("stop"),
    Status("status"),
    ResetSession("reset_session"),
    ModeDataset("mode_dataset"),
    ModeInference("mode_inference"),
}
