package io.keepagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.keepagent.app.ui.KeepAgentShell
import io.keepagent.app.ui.theme.KeepAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeepAgentTheme {
                KeepAgentShell()
            }
        }
    }
}
