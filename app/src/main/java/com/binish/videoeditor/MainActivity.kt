package com.binish.videoeditor

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.binish.videoeditor.extractor.ExtractMedia
import com.binish.videoeditor.ui.theme.VideoEditorTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState


class MainActivity : ComponentActivity() {
    private val imageFromFile = registerForActivityResult(ActivityResultContracts.GetContent()) {
        if(it != null) {
            val fileDescriptor = applicationContext.contentResolver.openAssetFileDescriptor(it, "r")
            val extractMedia = ExtractMedia().getExtractor(context = this, dataSource = it, size = fileDescriptor!!.length)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VideoEditorTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                       Selector(modifier = Modifier.align(Alignment.Center),"Select File")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    fun Selector(modifier: Modifier= Modifier, name: String) {
        val hapticFeedback = LocalHapticFeedback.current
        val permission = rememberPermissionState(permission = Manifest.permission.READ_EXTERNAL_STORAGE)
        Button(modifier = modifier, onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            if(permission.status == PermissionStatus.Granted)
                imageFromFile.launch("video/*")
            else
                permission.launchPermissionRequest()
        }){
            Text(name, style = TextStyle(fontWeight = FontWeight.Bold))
        }
    }
}

