package com.polefm.radio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.polefm.radio.ui.AppRoot
import com.polefm.radio.ui.PoleFmTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PoleFmTheme {
                AppRoot(vm = viewModel)
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri = intent.data ?: return
        if (uri.scheme == "polefm" && uri.host == "station") {
            val url = uri.getQueryParameter("url") ?: return
            viewModel.playStationByUrl(url)
        }
    }
}
