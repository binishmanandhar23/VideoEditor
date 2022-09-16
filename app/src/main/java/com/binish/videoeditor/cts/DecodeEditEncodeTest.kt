/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.binish.videoeditor.cts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.GLES20
import android.os.Environment
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import javax.microedition.khronos.opengles.GL10
import kotlin.experimental.and

/**
 * This test has three steps:
 *
 *  1. Generate a video test stream.
 *  1. Decode the video from the stream, rendering frames into a SurfaceTexture.
 * Render the texture onto a Surface that feeds a video encoder, modifying
 * the output with a fragment shader.
 *  1. Decode the second video and compare it to the expected result.
 *
 *
 * The second step is a typical scenario for video editing.  We could do all this in one
 * step, feeding data through multiple stages of MediaCodec, but at some point we're
 * no longer exercising the code in the way we expect it to be used (and the code
 * gets a bit unwieldy).
 */
class DecodeEditEncodeTest {
    // size of a frame, in pixels
    private var mWidth = -1
    private var mHeight = -1

    // bit rate, in bits per second
    private var mBitRate = -1


    // largest color component delta seen (i.e. actual vs. expected)
    private var mLargestColorDelta = 0
    @Throws(Throwable::class)
    fun testVideoEditQCIF() {
        setParameters(176, 144, 1000000)
        VideoEditWrapper.runTest(this)
    }

    @Throws(Throwable::class)
    fun testVideoEditQVGA() {
        setParameters(320, 240, 2000000)
        VideoEditWrapper.runTest(this)
    }

    @Throws(Throwable::class)
    fun testVideoEdit720p() {
        setParameters(1280, 720, 6000000)
        VideoEditWrapper.runTest(this)
    }

    /**
     * Wraps testEditVideo, running it in a new thread.  Required because of the way
     * SurfaceTexture.OnFrameAvailableListener works when the current thread has a Looper
     * configured.
     */
    private class VideoEditWrapper private constructor(private val mTest: DecodeEditEncodeTest) :
        Runnable {
        private var mThrowable: Throwable? = null
        override fun run() {
            try {
                mTest.videoEditTest()
            } catch (th: Throwable) {
                mThrowable = th
            }
        }

        companion object {
            /**
             * Entry point.
             */
            @Throws(Throwable::class)
            fun runTest(obj: DecodeEditEncodeTest) {
                val wrapper = VideoEditWrapper(obj)
                val th = Thread(wrapper, "codec test")
                th.start()
                th.join()
                if (wrapper.mThrowable != null) {
                    throw wrapper.mThrowable!!
                }
            }
        }
    }

    /**
     * Sets the desired frame size and bit rate.
     */
    private fun setParameters(width: Int, height: Int, bitRate: Int) {
        if (width % 16 != 0 || height % 16 != 0) {
            Log.w(TAG, "WARNING: width or height not multiple of 16")
        }
        mWidth = width
        mHeight = height
        mBitRate = bitRate
    }

    /**
     * Tests editing of a video file with GL.
     */
    private fun videoEditTest() {
        val sourceChunks = VideoChunks()
        try {
            if (!generateVideoFile(sourceChunks)) {
                // No AVC codec?  Fail silently.
                return
            }
            if (DEBUG_SAVE_FILE) {
                // Save a copy to a file.  We call it ".mp4", but it's actually just an elementary
                // stream, so not all video players will know what to do with it.
                val dirName = Environment.getDownloadCacheDirectory()
                val fileName = "vedit1_" + mWidth + "x" + mHeight + ".mp4"
                sourceChunks.saveToFile(File(dirName, fileName))
            }
            val destChunks: VideoChunks?
            destChunks = editVideoFile(sourceChunks)
            if (DEBUG_SAVE_FILE) {
                val dirName = Environment.getDownloadCacheDirectory()
                val fileName = "vedit2_" + mWidth + "x" + mHeight + ".mp4"
                destChunks.saveToFile(File(dirName, fileName))
            }
            checkVideoFile(destChunks)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Generates a test video file, saving it as VideoChunks.  We generate frames with GL to
     * avoid having to deal with multiple YUV formats.
     *
     * @return true on success, false on "soft" failure
     */
    @Throws(IOException::class)
    private fun generateVideoFile(output: VideoChunks): Boolean {
        if (VERBOSE) Log.d(TAG, "generateVideoFile " + mWidth + "x" + mHeight)
        var encoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        try {
            val codecInfo = selectCodec(MIME_TYPE)
            if (codecInfo == null) {
                // Don't fail CTS if they don't have an AVC codec (not here, anyway).
                Log.e(TAG, "Unable to find an appropriate codec for $MIME_TYPE")
                return false
            }
            if (VERBOSE) Log.d(TAG, "found codec: " + codecInfo.name)
            // We avoid the device-specific limitations on width and height by using values that
            // are multiples of 16, which all tested devices seem to be able to handle.
            val format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight)
            // Set some properties.  Failing to specify some of these can cause the MediaCodec
            // configure() call to throw an unhelpful exception.
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            if (VERBOSE) Log.d(TAG, "format: $format")
            output.mediaFormat = format
            // Create a MediaCodec for the desired codec, then configure it as an encoder with
            // our desired properties.
            encoder = MediaCodec.createByCodecName(codecInfo.name)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()
            generateVideoData(encoder, inputSurface, output)
        } finally {
            if (encoder != null) {
                if (VERBOSE) Log.d(TAG, "releasing encoder")
                encoder.stop()
                encoder.release()
                if (VERBOSE) Log.d(TAG, "released encoder")
            }
            inputSurface?.release()
        }
        return true
    }

    /**
     * Generates video frames, feeds them into the encoder, and writes the output to the
     * VideoChunks instance.
     */
    private fun generateVideoData(
        encoder: MediaCodec, inputSurface: InputSurface,
        output: VideoChunks
    ) {
        val TIMEOUT_USEC = 10000
        var encoderOutputBuffers = encoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        var generateIndex = 0
        var outputCount = 0
        // Loop until the output side is done.
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "gen loop")
            // If we're not done submitting frames, generate a new one and submit it.  The
            // eglSwapBuffers call will block if the input is full.
            if (!inputDone) {
                if (generateIndex == NUM_FRAMES) {
                    // Send an empty frame with the end-of-stream flag set.
                    if (VERBOSE) Log.d(TAG, "signaling input EOS")
                    if (WORK_AROUND_BUGS) {
                        // Might drop a frame, but at least we won't crash mediaserver.
                        try {
                            Thread.sleep(500)
                        } catch (ie: InterruptedException) {
                        }
                        outputDone = true
                    } else {
                        encoder.signalEndOfInputStream()
                    }
                    inputDone = true
                } else {
                    generateSurfaceFrame(generateIndex)
                    inputSurface.setPresentationTime(computePresentationTime(generateIndex) * 1000)
                    if (VERBOSE) Log.d(TAG, "inputSurface swapBuffers")
                    inputSurface.swapBuffers()
                }
                generateIndex++
            }
            // Check for output from the encoder.  If there's no output yet, we either need to
            // provide more input, or we need to wait for the encoder to work its magic.  We
            // can't actually tell which is the case, so if we can't get an output buffer right
            // away we loop around and see if it wants more input.
            //
            // If we do find output, drain it all before supplying more input.
            while (true) {
                val encoderStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from encoder available")
                    break // out of while
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    encoderOutputBuffers = encoder.outputBuffers
                    if (VERBOSE) Log.d(TAG, "encoder output buffers changed")
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // not expected for an encoder
                    val newFormat = encoder.outputFormat
                    if (VERBOSE) Log.d(TAG, "encoder output format changed: $newFormat")
                } else if (encoderStatus < 0) {
                    //fail("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0
                    val encodedData = encoderOutputBuffers[encoderStatus]
                    if (encodedData == null) {
                        //fail("encoderOutputBuffer " + encoderStatus + " was null");
                    }
                    // Codec config flag must be set iff this is the first chunk of output.  This
                    // may not hold for all codecs, but it appears to be the case for video/avc.
                    //assertTrue((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ||outputCount != 0);
                    if (info.size != 0) {
                        // Adjust the ByteBuffer values to match BufferInfo.
                        encodedData!!.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        output.addChunk(encodedData, info.flags, info.presentationTimeUs)
                        outputCount++
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break // out of while
                    }
                }
            }
        }
        // One chunk per frame, plus one for the config data.
        //assertEquals("Frame count", NUM_FRAMES + 1, outputCount);
    }

    /**
     * Generates a frame of data using GL commands.
     *
     *
     * We have an 8-frame animation sequence that wraps around.  It looks like this:
     * <pre>
     * 0 1 2 3
     * 7 6 5 4
    </pre> *
     * We draw one of the eight rectangles and leave the rest set to the zero-fill color.
     */
    private fun generateSurfaceFrame(frameIndex: Int) {
        var frameIndex = frameIndex
        frameIndex %= 8
        val startX: Int
        val startY: Int
        if (frameIndex < 4) {
            // (0,0) is bottom-left in GL
            startX = frameIndex * (mWidth / 4)
            startY = mHeight / 2
        } else {
            startX = (7 - frameIndex) * (mWidth / 4)
            startY = 0
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(TEST_R0 / 255.0f, TEST_G0 / 255.0f, TEST_B0 / 255.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(startX, startY, mWidth / 4, mHeight / 2)
        GLES20.glClearColor(TEST_R1 / 255.0f, TEST_G1 / 255.0f, TEST_B1 / 255.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Edits a video file, saving the contents to a new file.  This involves decoding and
     * re-encoding, not to mention conversions between YUV and RGB, and so may be lossy.
     *
     *
     * If we recognize the decoded format we can do this in Java code using the ByteBuffer[]
     * output, but it's not practical to support all OEM formats.  By using a SurfaceTexture
     * for output and a Surface for input, we can avoid issues with obscure formats and can
     * use a fragment shader to do transformations.
     */
    @Throws(IOException::class)
    private fun editVideoFile(inputData: VideoChunks): VideoChunks {
        if (VERBOSE) Log.d(TAG, "editVideoFile " + mWidth + "x" + mHeight)
        val outputData = VideoChunks()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        try {
            val inputFormat = inputData.mediaFormat
            // Create an encoder format that matches the input format.  (Might be able to just
            // re-use the format used to generate the video, since we want it to be the same.)
            val outputFormat = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight)
            outputFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            outputFormat.setInteger(
                MediaFormat.KEY_BIT_RATE,
                inputFormat!!.getInteger(MediaFormat.KEY_BIT_RATE)
            )
            outputFormat.setInteger(
                MediaFormat.KEY_FRAME_RATE,
                inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            )
            outputFormat.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                inputFormat.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL)
            )
            outputData.mediaFormat = outputFormat
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()
            // OutputSurface uses the EGL context created by InputSurface.
            decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            outputSurface = OutputSurface()
            outputSurface.changeFragmentShader(FRAGMENT_SHADER)
            decoder.configure(inputFormat, outputSurface.surface, null, 0)
            decoder.start()
            editVideoData(inputData, decoder, outputSurface, inputSurface, encoder, outputData)
        } finally {
            if (VERBOSE) Log.d(TAG, "shutting down encoder, decoder")
            outputSurface?.release()
            inputSurface?.release()
            if (encoder != null) {
                encoder.stop()
                encoder.release()
            }
            if (decoder != null) {
                decoder.stop()
                decoder.release()
            }
        }
        return outputData
    }

    /**
     * Edits a stream of video data.
     */
    private fun editVideoData(
        inputData: VideoChunks, decoder: MediaCodec,
        outputSurface: OutputSurface, inputSurface: InputSurface, encoder: MediaCodec,
        outputData: VideoChunks
    ) {
        val TIMEOUT_USEC = 10000
        val decoderInputBuffers = decoder.inputBuffers
        var encoderOutputBuffers = encoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        var inputChunk = 0
        var outputCount = 0
        var outputDone = false
        var inputDone = false
        val decoderDone = false
        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "edit loop")
            // Feed more data to the decoder.
            if (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                if (inputBufIndex >= 0) {
                    if (inputChunk == inputData.numChunks) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                        if (VERBOSE) Log.d(TAG, "sent input EOS (with zero-length frame)")
                    } else {
                        // Copy a chunk of input to the decoder.  The first chunk should have
                        // the BUFFER_FLAG_CODEC_CONFIG flag set.
                        val inputBuf = decoderInputBuffers[inputBufIndex]
                        inputBuf.clear()
                        inputData.getChunkData(inputChunk, inputBuf)
                        val flags = inputData.getChunkFlags(inputChunk)
                        val time = inputData.getChunkTime(inputChunk)
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, inputBuf.position(),
                            time, flags
                        )
                        if (VERBOSE) {
                            Log.d(
                                TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                        inputBuf.position() + " flags=" + flags
                            )
                        }
                        inputChunk++
                    }
                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available")
                }
            }
            // Assume output is available.  Loop until both assumptions are false.
            var decoderOutputAvailable = !decoderDone
            var encoderOutputAvailable = true
            while (decoderOutputAvailable || encoderOutputAvailable) {
                // Start by draining any pending output from the encoder.  It's important to
                // do this before we try to stuff any more data in.
                val encoderStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from encoder available")
                    encoderOutputAvailable = false
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = encoder.outputBuffers
                    if (VERBOSE) Log.d(TAG, "encoder output buffers changed")
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    if (VERBOSE) Log.d(TAG, "encoder output format changed: $newFormat")
                } else if (encoderStatus < 0) {
                    //fail("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0
                    val encodedData = encoderOutputBuffers[encoderStatus]
                    if (encodedData == null) {
                        //fail("encoderOutputBuffer " + encoderStatus + " was null");
                    }
                    // Write the data to the output "file".
                    if (info.size != 0) {
                        encodedData!!.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        outputData.addChunk(encodedData, info.flags, info.presentationTimeUs)
                        outputCount++
                        if (VERBOSE) Log.d(TAG, "encoder output " + info.size + " bytes")
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(encoderStatus, false)
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Continue attempts to drain output.
                    continue
                }
                // Encoder is drained, check to see if we've got a new frame of output from
                // the decoder.  (The output is going to a Surface, rather than a ByteBuffer,
                // but we still get information through BufferInfo.)
                if (!decoderDone) {
                    val decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no output available yet
                        if (VERBOSE) Log.d(TAG, "no output from decoder available")
                        decoderOutputAvailable = false
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        //decoderOutputBuffers = decoder.getOutputBuffers();
                        if (VERBOSE) Log.d(TAG, "decoder output buffers changed (we don't care)")
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // expected before first buffer of data
                        val newFormat = decoder.outputFormat
                        if (VERBOSE) Log.d(TAG, "decoder output format changed: $newFormat")
                    } else if (decoderStatus < 0) {
                        //fail("unexpected result from decoder.dequeueOutputBuffer: "+decoderStatus);
                    } else { // decoderStatus >= 0
                        if (VERBOSE) Log.d(
                            TAG, "surface decoder given buffer "
                                    + decoderStatus + " (size=" + info.size + ")"
                        )
                        // The ByteBuffers are null references, but we still get a nonzero
                        // size for the decoded data.
                        val doRender = info.size != 0
                        // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                        // to SurfaceTexture to convert to a texture.  The API doesn't
                        // guarantee that the texture will be available before the call
                        // returns, so we need to wait for the onFrameAvailable callback to
                        // fire.  If we don't wait, we risk rendering from the previous frame.
                        decoder.releaseOutputBuffer(decoderStatus, doRender)
                        if (doRender) {
                            // This waits for the image and renders it after it arrives.
                            if (VERBOSE) Log.d(TAG, "awaiting frame")
                            //outputSurface.awaitNewImage()
                            outputSurface.drawImage()
                            // Send it to the encoder.
                            inputSurface.setPresentationTime(info.presentationTimeUs * 1000)
                            if (VERBOSE) Log.d(TAG, "swapBuffers")
                            inputSurface.swapBuffers()
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            // forward decoder EOS to encoder
                            if (VERBOSE) Log.d(TAG, "signaling input EOS")
                            if (WORK_AROUND_BUGS) {
                                // Bail early, possibly dropping a frame.
                                return
                            } else {
                                encoder.signalEndOfInputStream()
                            }
                        }
                    }
                }
            }
        }
        if (inputChunk != outputCount) {
            throw RuntimeException(
                "frame lost: " + inputChunk + " in, " +
                        outputCount + " out"
            )
        }
    }

    /**
     * Checks the video file to see if the contents match our expectations.  We decode the
     * video to a Surface and check the pixels with GL.
     */
    @Throws(IOException::class)
    private fun checkVideoFile(inputData: VideoChunks?) {
        var surface: OutputSurface? = null
        var decoder: MediaCodec? = null
        mLargestColorDelta = -1
        if (VERBOSE) Log.d(TAG, "checkVideoFile")
        try {
            surface = OutputSurface(mWidth, mHeight)
            val format = inputData!!.mediaFormat
            decoder = MediaCodec.createDecoderByType(MIME_TYPE)
            decoder.configure(format, surface.surface, null, 0)
            decoder.start()
            val badFrames = checkVideoData(inputData, decoder, surface)
            if (badFrames != 0) {
                //fail("Found " + badFrames + " bad frames");
            }
        } finally {
            surface?.release()
            if (decoder != null) {
                decoder.stop()
                decoder.release()
            }
            Log.i(TAG, "Largest color delta: $mLargestColorDelta")
        }
    }

    /**
     * Checks the video data.
     *
     * @return the number of bad frames
     */
    private fun checkVideoData(
        inputData: VideoChunks?,
        decoder: MediaCodec,
        surface: OutputSurface
    ): Int {
        val TIMEOUT_USEC = 1000
        val decoderInputBuffers = decoder.inputBuffers
        var decoderOutputBuffers = decoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        var inputChunk = 0
        var checkIndex = 0
        var badFrames = 0
        var outputDone = false
        var inputDone = false
        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "check loop")
            // Feed more data to the decoder.
            if (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                if (inputBufIndex >= 0) {
                    if (inputChunk == inputData!!.numChunks) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                        if (VERBOSE) Log.d(TAG, "sent input EOS")
                    } else {
                        // Copy a chunk of input to the decoder.  The first chunk should have
                        // the BUFFER_FLAG_CODEC_CONFIG flag set.
                        val inputBuf = decoderInputBuffers[inputBufIndex]
                        inputBuf.clear()
                        inputData.getChunkData(inputChunk, inputBuf)
                        val flags = inputData.getChunkFlags(inputChunk)
                        val time = inputData.getChunkTime(inputChunk)
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, inputBuf.position(),
                            time, flags
                        )
                        if (VERBOSE) {
                            Log.d(
                                TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                        inputBuf.position() + " flags=" + flags
                            )
                        }
                        inputChunk++
                    }
                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available")
                }
            }
            if (!outputDone) {
                val decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from decoder available")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    decoderOutputBuffers = decoder.outputBuffers
                    if (VERBOSE) Log.d(TAG, "decoder output buffers changed")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (VERBOSE) Log.d(TAG, "decoder output format changed: $newFormat")
                } else if (decoderStatus < 0) {
                    //fail("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                } else { // decoderStatus >= 0
                    val decodedData = decoderOutputBuffers[decoderStatus]
                    if (VERBOSE) Log.d(
                        TAG, "surface decoder given buffer " + decoderStatus +
                                " (size=" + info.size + ")"
                    )
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (VERBOSE) Log.d(TAG, "output EOS")
                        outputDone = true
                    }
                    val doRender = info.size != 0
                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                    // that the texture will be available before the call returns, so we
                    // need to wait for the onFrameAvailable callback to fire.
                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                    if (doRender) {
                        if (VERBOSE) Log.d(TAG, "awaiting frame $checkIndex")
                        //assertEquals("Wrong time stamp", computePresentationTime(checkIndex),info.presentationTimeUs);
                        //surface.awaitNewImage()
                        surface.drawImage()
                        if (!checkSurfaceFrame(checkIndex++)) {
                            badFrames++
                        }
                    }
                }
            }
        }
        return badFrames
    }

    /**
     * Checks the frame for correctness, using GL to check RGB values.
     *
     * @return true if the frame looks good
     */
    private fun checkSurfaceFrame(frameIndex: Int): Boolean {
        val pixelBuf = ByteBuffer.allocateDirect(4) // TODO - reuse this
        var frameFailed = false
        for (i in 0..7) {
            // Note the coordinates are inverted on the Y-axis in GL.
            var x: Int
            var y: Int
            if (i < 4) {
                x = i * (mWidth / 4) + mWidth / 8
                y = mHeight * 3 / 4
            } else {
                x = (7 - i) * (mWidth / 4) + mWidth / 8
                y = mHeight / 4
            }
            GLES20.glReadPixels(x, y, 1, 1, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, pixelBuf)
            val r: Int = (pixelBuf[0] and 0xff.toByte()).toInt()
            val g: Int = (pixelBuf[1] and 0xff.toByte()).toInt()
            val b: Int = (pixelBuf[2] and 0xff.toByte()).toInt()
            //Log.d(TAG, "GOT(" + frameIndex + "/" + i + "): r=" + r + " g=" + g + " b=" + b);
            var expR: Int
            var expG: Int
            var expB: Int
            if (i == frameIndex % 8) {
                // colored rect (green/blue swapped)
                expR = TEST_R1
                expG = TEST_B1
                expB = TEST_G1
            } else {
                // zero background color (green/blue swapped)
                expR = TEST_R0
                expG = TEST_B0
                expB = TEST_G0
            }
            if (!isColorClose(r, expR) ||
                !isColorClose(g, expG) ||
                !isColorClose(b, expB)
            ) {
                Log.w(
                    TAG, "Bad frame " + frameIndex + " (rect=" + i + ": rgb=" + r +
                            "," + g + "," + b + " vs. expected " + expR + "," + expG +
                            "," + expB + ")"
                )
                frameFailed = true
            }
        }
        return !frameFailed
    }

    /**
     * Returns true if the actual color value is close to the expected color value.  Updates
     * mLargestColorDelta.
     */
    fun isColorClose(actual: Int, expected: Int): Boolean {
        val MAX_DELTA = 8
        val delta = Math.abs(actual - expected)
        if (delta > mLargestColorDelta) {
            mLargestColorDelta = delta
        }
        return delta <= MAX_DELTA
    }

    /**
     * The elementary stream coming out of the "video/avc" encoder needs to be fed back into
     * the decoder one chunk at a time.  If we just wrote the data to a file, we would lose
     * the information about chunk boundaries.  This class stores the encoded data in memory,
     * retaining the chunk organization.
     */
    private class VideoChunks {
        /**
         * Gets the MediaFormat that was used by the encoder.
         */
        /**
         * Sets the MediaFormat, for the benefit of a future decoder.
         */
        var mediaFormat: MediaFormat? = null
        private val mChunks = ArrayList<ByteArray>()
        private val mFlags = ArrayList<Int>()
        private val mTimes = ArrayList<Long>()

        /**
         * Adds a new chunk.  Advances buf.position to buf.limit.
         */
        fun addChunk(buf: ByteBuffer?, flags: Int, time: Long) {
            val data = ByteArray(buf!!.remaining())
            buf[data]
            mChunks.add(data)
            mFlags.add(flags)
            mTimes.add(time)
        }

        /**
         * Returns the number of chunks currently held.
         */
        val numChunks: Int
            get() = mChunks.size

        /**
         * Copies the data from chunk N into "dest".  Advances dest.position.
         */
        fun getChunkData(chunk: Int, dest: ByteBuffer) {
            val data = mChunks[chunk]
            dest.put(data)
        }

        /**
         * Returns the flags associated with chunk N.
         */
        fun getChunkFlags(chunk: Int): Int {
            return mFlags[chunk]
        }

        /**
         * Returns the timestamp associated with chunk N.
         */
        fun getChunkTime(chunk: Int): Long {
            return mTimes[chunk]
        }

        /**
         * Writes the chunks to a file as a contiguous stream.  Useful for debugging.
         */
        fun saveToFile(file: File) {
            Log.d(TAG, "saving chunk data to file $file")
            var fos: FileOutputStream? = null
            var bos: BufferedOutputStream? = null
            try {
                fos = FileOutputStream(file)
                bos = BufferedOutputStream(fos)
                fos = null // closing bos will also close fos
                val numChunks = numChunks
                for (i in 0 until numChunks) {
                    val chunk = mChunks[i]
                    bos.write(chunk)
                }
            } catch (ioe: IOException) {
                throw RuntimeException(ioe)
            } finally {
                try {
                    bos?.close()
                    fos?.close()
                } catch (ioe: IOException) {
                    throw RuntimeException(ioe)
                }
            }
        }
    }

    companion object {
        private const val TAG = "DecodeEditEncode"
        private const val WORK_AROUND_BUGS = false // avoid fatal codec bugs
        private const val VERBOSE = false // lots of logging
        private const val DEBUG_SAVE_FILE = false // save copy of encoded movie
        private const val DEBUG_FILE_NAME_BASE = "/sdcard/test."

        // parameters for the encoder
        private const val MIME_TYPE = "video/avc" // H.264 Advanced Video Coding
        private const val FRAME_RATE = 15 // 15fps
        private const val IFRAME_INTERVAL = 10 // 10 seconds between I-frames

        // movie length, in frames
        private const val NUM_FRAMES = 30 // two seconds of video
        private const val TEST_R0 = 0 // dull green background
        private const val TEST_G0 = 136
        private const val TEST_B0 = 0
        private const val TEST_R1 = 236 // pink; BT.601 YUV {120,160,200}
        private const val TEST_G1 = 50
        private const val TEST_B1 = 186

        // Replaces TextureRender.FRAGMENT_SHADER during edit; swaps green and blue channels.
        private const val FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord).rbga;\n" +
                "}\n"

        /**
         * Returns the first codec capable of encoding the specified MIME type, or null if no
         * match was found.
         */
        private fun selectCodec(mimeType: String): MediaCodecInfo? {
            val numCodecs = MediaCodecList.getCodecCount()
            for (i in 0 until numCodecs) {
                val codecInfo = MediaCodecList.getCodecInfoAt(i)
                if (!codecInfo.isEncoder) {
                    continue
                }
                val types = codecInfo.supportedTypes
                for (j in types.indices) {
                    if (types[j].equals(mimeType, ignoreCase = true)) {
                        return codecInfo
                    }
                }
            }
            return null
        }

        /**
         * Generates the presentation time for frame N, in microseconds.
         */
        private fun computePresentationTime(frameIndex: Int): Long {
            return (123 + frameIndex * 1000000 / FRAME_RATE).toLong()
        }
    }
}