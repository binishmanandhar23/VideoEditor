package com.binish.videoeditor.extractor

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.widget.VideoView
import java.nio.ByteBuffer
import kotlin.properties.Delegates

class ExtractMedia {
    fun getExtractor(context: Context, dataSource: Uri, size: Long){
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
                Log.i("MimeType", "$i: $mime")
                if (i == 0) {
                    extractor.selectTrack(i)
                    mediaCodec(size = size, format = extractor.getTrackFormat(i))
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

    fun mediaCodec(size: Long, format: MediaFormat){
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        val mDecoder = MediaCodec.createDecoderByType(codecName)
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
        mDecoder.configure(format,)

        mOutputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        mOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE,
            format.getInteger(MediaFormat.KEY_BIT_RATE))
        mOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE,
            format.getInteger(MediaFormat.KEY_FRAME_RATE))
        mOutputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,
            format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))


    }
}