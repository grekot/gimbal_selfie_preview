package pl.photopreview.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable

private enum class Screen { RoleSelect, Camera, Viewer }

@Composable
fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.RoleSelect) }
    when (screen) {
        Screen.RoleSelect -> RoleSelectScreen(
            onCamera = { screen = Screen.Camera },
            onViewer = { screen = Screen.Viewer },
        )
        Screen.Camera -> CameraScreen(onBack = { screen = Screen.RoleSelect })
        Screen.Viewer -> ViewerScreen(onBack = { screen = Screen.RoleSelect })
    }
}
