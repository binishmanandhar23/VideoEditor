package com.binish.videoeditor

import android.Manifest
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.binish.videoeditor.cts.DecodeEditEncodeTest
import com.binish.videoeditor.grafika.MoviePlayer
import com.binish.videoeditor.grafika.SpeedControlCallback
import com.binish.videoeditor.ui.theme.VideoEditorTheme
import com.binish.videoeditor.utils.AppUtils.toIntDp
import com.binish.videoeditor.utils.SurfaceUtils
import com.binish.videoeditor.viewmodel.MainActivityViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlin.math.min


class MainActivity : ComponentActivity(), SurfaceHolder.Callback, MoviePlayer.PlayerFeedback {
    companion object{
        const val TAG = "MainActivityLog"
    }

    private val mainActivityViewModel by viewModels<MainActivityViewModel>()

    lateinit var surfaceView: SurfaceView
    private val imageFromFile = registerForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) {
            val fileDescriptor = applicationContext.contentResolver.openAssetFileDescriptor(it, "r")
            /*val extractMedia = ExtractMedia().getExtractor(
                context = this,
                dataSource = it,
                size = fileDescriptor!!.length,
                surfaceTexture = textureView.surfaceTexture!!
            )*/
            /*val surface = surfaceView.holder.surface
            SurfaceUtils.clearSurface(surface)
            val player = MoviePlayer(context = this@MainActivity, mSourceFile = it, mOutputSurface = surface, mFrameCallback =  SpeedControlCallback())
            val mPlayTask = MoviePlayer.PlayTask(player, this)
            mainActivityViewModel.updateHeightWidth(player.videoHeight, player.videoWidth)
            mPlayTask.execute()*/
            DecodeEditEncodeTest().testVideoEditQCIF()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VideoEditorTheme {
                val heightWidth by mainActivityViewModel.heightWidth.collectAsState()
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .align(Alignment.Center)) {
                            AndroidView(modifier = Modifier
                                .size(
                                    height = heightWidth.first.toIntDp().dp,
                                    width = heightWidth.second.toIntDp().dp
                                )
                                .align(Alignment.Center),
                                factory = { SurfaceView(it) },
                                update = {
                                    surfaceView = it
                                })
                        }
                        
                        Selector(modifier = Modifier.align(Alignment.TopCenter), "Select File")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    fun Selector(modifier: Modifier = Modifier, name: String) {
        val hapticFeedback = LocalHapticFeedback.current
        val permission =
            rememberPermissionState(permission = Manifest.permission.READ_EXTERNAL_STORAGE)
        Button(modifier = modifier, onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            if (permission.status == PermissionStatus.Granted)
                imageFromFile.launch("video/*")
            else
                permission.launchPermissionRequest()
        }) {
            Text(name, style = TextStyle(fontWeight = FontWeight.Bold))
        }
    }


    override fun surfaceCreated(p0: SurfaceHolder) {

    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {

    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {

    }

    override fun playbackStopped() {

    }

    /*private fun drawColorBars(surface: Surface) {
        val canvas = surface.lockCanvas(null)
        try {
            // TODO: if the device is in portrait, draw the color bars horizontally.  Right
            // now this only looks good in landscape.
            val width = canvas.width
            val height = canvas.height
            val least = min(width, height)

            val textPaint = Paint()
            val typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
            textPaint.typeface = typeface
            textPaint.textSize = (least / 20).toFloat()
            textPaint.isAntiAlias = true
            val rectPaint = Paint()
            for (i in 0..7) {
                var color = -0x1000000
                if (i and 0x01 != 0) {
                    color = color or 0x00ff0000
                }
                if (i and 0x02 != 0) {
                    color = color or 0x0000ff00
                }
                if (i and 0x04 != 0) {
                    color = color or 0x000000ff
                }
                rectPaint.color = color
                val sliceWidth = (width / 8).toFloat()
                canvas.drawRect(
                    sliceWidth * i,
                    0f,
                    sliceWidth * (i + 1),
                    height.toFloat(),
                    rectPaint
                )
            }
            rectPaint.color = -0x7f7f7f80 // ARGB 50/50 grey (non-premul)
            val sliceHeight = (height / 8).toFloat()
            val posn = 6
            canvas.drawRect(
                0f,
                sliceHeight * posn,
                width.toFloat(),
                sliceHeight * (posn + 1),
                rectPaint
            )
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }*/
}

