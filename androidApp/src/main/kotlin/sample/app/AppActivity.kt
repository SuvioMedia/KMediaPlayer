package sample.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        setContent { App() }
    }
}
