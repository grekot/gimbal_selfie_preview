package pl.photopreview.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private val DarkColors = darkColorScheme()

@Composable
fun PhotoPreviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors) {
        // Wrap in a Surface so default text uses the (light) on-background colour — otherwise
        // plain Text() defaults to black, which is invisible on the dark window background.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
