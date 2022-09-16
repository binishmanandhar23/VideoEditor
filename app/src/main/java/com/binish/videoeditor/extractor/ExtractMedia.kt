package com.binish.videoeditor.extractor

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.*
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import android.widget.VideoView
import java.nio.ByteBuffer
import kotlin.properties.Delegates

class ExtractMedia {
    fun getExtractor(context: Context, dataSource: Uri, size: Long, surfaceTexture: SurfaceTexture){
        Log.i("DataSource", "${dataSource.path}")
        val extractor = MediaExtractor()
        try {
            //extractor.setDataSource(dataSource)
            extractor.setDataSource(context, dataSource, null)
        } catch (e: Exception){
            e.printStackTrace()
        } finally {
            val numTracks = extractor.trackCount
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                Log.i("MimeType", "$i: $mime  TrackFormat: ${extractor.getTrackFormat(i)}")
                if (i == 0) {
                    extractor.selectTrack(i)
                    mediaCodec(size = size, format = extractor.getTrackFormat(i), surfaceTexture = surfaceTexture)
                }
            }
            val inputBuffer = ByteBuffer.allocate(size.toInt())
            while (extractor.readSampleData(inputBuffer, 0) >= 0) {
                val trackIndex = extractor.sampleTrackIndex
                val presentationTimeUs = extractor.sampleTime
                extractor.getTrackFormat(0)
                extractor.advance()
                Log.i(
                    "TrackInformation",
                    "TrackIndex: $trackIndex  PresentationTime: $presentationTimeUs"
                )
            }
            extractor.release()
        }
    }

    fun mediaCodec(size: Long, format: MediaFormat, surfaceTexture: SurfaceTexture){
        val mDecoder = MediaCodec.createDecoderByType(MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format).let{
            Log.i("CodecType","Type: $it")
            it
        })
        val mEncoder = MediaCodec.createEncoderByType(MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(format))
        var mOutputFormat = format



        mDecoder.setCallback(object: MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, inputBufferId: Int) {
                val inputBuffer = mDecoder.getInputBuffer(inputBufferId);

                mDecoder.queueInputBuffer(inputBufferId,0, size.toInt(),0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                outputBufferId: Int,
                p2: MediaCodec.BufferInfo
            ) {
                val outputBuffer = mDecoder.getOutputBuffer(outputBufferId);
                val bufferFormat = mDecoder.getOutputFormat(outputBufferId); // option A
                // bufferFormat is equivalent to mOutputFormat
                // outputBuffer is ready to be processed or rendered.
                mDecoder.releaseOutputBuffer(outputBufferId, true);
            }

            override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) {

            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                mOutputFormat = format
            }
        })
        mEncoder.configure(mOutputFormat, null,null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = mEncoder.createInputSurface()
        mEncoder.start()

        val outputSurface = Surface(surfaceTexture)
        mDecoder.configure(format, outputSurface,null, 0)
        mDecoder.start()

        mOutputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        mOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE,
            format.getInteger(MediaFormat.KEY_BIT_RATE))
        mOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE,
            format.getInteger(MediaFormat.KEY_FRAME_RATE))
        mOutputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,
            format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))

        inputSurface.release()
        outputSurface.release()
        mEncoder.release()
        mDecoder.release()
    }
}