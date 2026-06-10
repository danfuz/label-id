package labelid

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import labelid.ui.LabelIdApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Label ID",
    ) {
        LabelIdApp()
    }
}
