package com.android.example.depth_gpu

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {
    private val QUAD_COORDS = floatArrayOf(-1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f, 1.0f)
    private var quadCoords: FloatBuffer
    private var quadTexCoords: FloatBuffer

    private var program = 0
    private var cameraTextureId = -1
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var cameraTextureHandle = 0
    private var depthTextureHandle = 0

    init {
        val bb = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4).apply { order(ByteOrder.nativeOrder()) }
        quadCoords = bb.asFloatBuffer().apply { put(QUAD_COORDS); position(0) }
        val bb2 = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4).apply { order(ByteOrder.nativeOrder()) }
        quadTexCoords = bb2.asFloatBuffer().apply { position(0) }
    }

    fun getCameraTextureId(): Int = cameraTextureId

    fun createOnGlThread(context: Context) {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, "shaders/depth_visualization.vert", context)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, "shaders/depth_visualization.frag", context)

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        GLES30.glUseProgram(program)

        positionHandle = GLES30.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES30.glGetAttribLocation(program, "a_TexCoord")
        cameraTextureHandle = GLES30.glGetUniformLocation(program, "u_CameraTexture")
        depthTextureHandle = GLES30.glGetUniformLocation(program, "u_DepthTexture")

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    }

    fun draw(frame: Frame, depthTextureId: Int) { // 引数を2つに戻す
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(cameraTextureHandle, 0)

        if (depthTextureId > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
            GLES30.glUniform1i(depthTextureHandle, 1)
        }

        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, quadCoords)
        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun loadShader(type: Int, filename: String, context: Context): Int {
        val code = context.assets.open(filename).bufferedReader().use { it.readText() }
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        return shader
    }
}