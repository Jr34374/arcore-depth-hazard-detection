package com.android.example.depth_gpu

import android.media.Image
import android.opengl.GLES30 //GLES20->30に変更
import java.nio.ByteBuffer

/**
 * ARCoreから取得した深度画像(Image)を、OpenGLのテクスチャに変換・管理するクラス
 */
class DepthTextureHandler {
    private var depthTextureId = -1
    private var depthTextureWidth = -1
    private var depthTextureHeight = -1

    /**
     * 深度テクスチャをOpenGLコンテキスト上に作成
     * onSurfaceCreatedから呼び出す
     * OpenGLテクスチャを初期化
     */
    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        depthTextureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    }

    /**
     * 毎フレーム、ARCoreから取得した深度Imageをテクスチャにアップロード
     * onDrawFrameから呼び出す必要
     *
     * @param depthImage ARCoreのFrameから取得した深度画像
     */
    fun update(depthImage: Image) {
        val width = depthImage.width
        val height = depthImage.height
        val buffer: ByteBuffer = depthImage.planes[0].buffer

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId)

        // 深度データは1ピクセル16ビット(2バイト)の単一チャンネル(LUMINANCE)として扱う
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R16UI,        // 内部フォーマット
            width,
            height,
            0,
            GLES30.GL_RED_INTEGER,  //元データも整数形式
            GLES30.GL_UNSIGNED_SHORT, //16ビット符号なし整数
            buffer
        )

        depthTextureWidth = width
        depthTextureHeight = height
    }


    //作成した深度テクスチャのIDを返す
    fun getTextureId(): Int = depthTextureId
}