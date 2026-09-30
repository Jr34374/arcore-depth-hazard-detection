package com.android.example.depth_gpu

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.Image
import android.opengl.GLES30 // GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.TextView

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException

import com.google.ar.core.exceptions.NotYetAvailableException
import org.opencv.android.OpenCVLoader

import org.opencv.core.Core // 回転処理用
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint // 追加
import org.opencv.core.Point      // 追加

import org.opencv.core.Size // リサイズ用
import org.opencv.imgproc.Imgproc // リサイズ用
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileWriter
import java.nio.ByteOrder // エンディアン指定用

import java.nio.ShortBuffer
import java.util.ArrayList // 追加

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// --- ★ JSON保存用の追加インポート ---
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private val TAG = "MainActivity" //デバッグログ用のTAGを追加

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var infoText: TextView //UIのTextView用変数

    private var frameCounter = 0 //処理間引き用のフレームカウンター
    private val PROCESSING_INTERVAL = 10 // 10フレームに1回処理
    private var isOpenCVInitialized = false // OpenCVが初期化されたかどうかのフラグ
    private val mainHandler = Handler(Looper.getMainLooper())// UIスレッドでTextViewを更新するためのHandler

    private var focalLengthY: Float = -1.0f //カメラパラメータ (焦点距離 fy)
    private var cameraImageWidth: Int = 0 // スケーリング計算用

    private lateinit var currentTrialDir: File // 試行ごとのディレクトリ管理用変数
    private val trialDataArray = JSONArray() // セッション中の全検知データを保持

    private var cameraRealHeightMeters: Float = 1.3f

    private val PREFS_NAME = "DepthAppPrefs"
    private val KEY_CAMERA_HEIGHT = "camera_height_cm"

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val depthTextureHandler = DepthTextureHandler()
    // confidenceTextureHandler を削除

    private var installRequested = false

    // 天井基準スキャン用のROI設定

    // 閾値計算用ROI (CALC_ROI): 天井付近
    private val CALC_ROI_X_PERCENT = 0
    private val CALC_ROI_Y_PERCENT = 0
    private val CALC_ROI_W_PERCENT = 100
    private val CALC_ROI_H_PERCENT = 20

    // 検出用ROI (DETECT_ROI): 天井と足元をカット
    private val DETECT_ROI_X_PERCENT = 5
    private val DETECT_ROI_Y_PERCENT = 20
    private val DETECT_ROI_W_PERCENT = 90
    private val DETECT_ROI_H_PERCENT = 75


    // 閾値のパーセンタイル指定(障害物の領域の切り離しに適切な値を指定する)
    private val THRESHOLD_PERCENTILE = 0.50

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                setupSession()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        infoText = findViewById(R.id.infoText) // XMLのTextViewと変数を紐付け

        // OpenCVの初期化処理
        // アプリ起動時にOpenCVライブラリを読み込む
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV initialized successfully.")
            isOpenCVInitialized = true
        } else {
            Log.e(TAG, "OpenCV initialization failed.")
            Toast.makeText(this, "OpenCV initialization failed", Toast.LENGTH_LONG).show()
        }

        // --- 試行ディレクトリの作成 (毎回新しいフォルダを作る) ---
        val rootDir = getExternalFilesDir(null)
        val trialsBaseDir = File(rootDir, "depth_trials")
        if (!trialsBaseDir.exists()) trialsBaseDir.mkdirs()

        val trialFolderName = "trial_${System.currentTimeMillis()}"
        currentTrialDir = File(trialsBaseDir, trialFolderName)
        currentTrialDir.mkdirs()
        Log.d(TAG, "New trial directory: ${currentTrialDir.absolutePath}")

        clearDepthCapturesDirectory() // アプリ起動時に前回の保存画像をクリアする

        showHeightInputDialog() // アプリ起動時に高さ入力ダイアログを表示


        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(3) // 3を維持
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showHeightInputDialog() {
        // 保存された設定の読み込み
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHeightCm = prefs.getFloat(KEY_CAMERA_HEIGHT, 130.0f) // 初期値130cm

        // 入力欄の作成
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(savedHeightCm.toString())

        // ダイアログの作成
        AlertDialog.Builder(this)
            .setTitle("カメラ高さ設定")
            .setMessage("床からカメラまでの高さを入力してください (cm)")
            .setView(input)
            .setPositiveButton("設定") { _, _ ->
                val text = input.text.toString()
                val heightCm = text.toFloatOrNull()

                if (heightCm != null && heightCm > 0) {
                    // cm -> m に変換して変数にセット
                    cameraRealHeightMeters = heightCm / 100.0f

                    // 設定を保存
                    prefs.edit().putFloat(KEY_CAMERA_HEIGHT, heightCm).apply()

                    Toast.makeText(this, "高さを ${cameraRealHeightMeters}m に設定しました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "無効な値です。初期値(1.3m)を使用します", Toast.LENGTH_SHORT).show()
                }
            }
            .setCancelable(false) // 必ず入力させる（キャンセル不可）
            .show()
    }


    override fun onResume() {
        super.onResume()
        if (session == null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                setupSession()
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_LONG).show()
            session = null
            return
        }
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        session?.pause()
        // --- アプリ停止時にJSONを書き出す ---
        saveTrialDataToJson()
    }

    private fun saveTrialDataToJson() {
        if (trialDataArray.length() == 0) return
        try {
            val jsonFile = File(currentTrialDir, "session_data.json")
            val writer = FileWriter(jsonFile)
            writer.write(trialDataArray.toString(4)) // インデント4で見やすく
            writer.close()
            Log.d(TAG, "Trial data saved to: ${jsonFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save JSON", e)
        }
    }

    private fun setupSession() {
        if (session != null) return
        try {
            session = Session(this)
            val config = Config(session)
            if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            }
            else {
                Log.w(TAG, "Depth Mode not supported") // Logcatデバッグ追加
            }
            config.focusMode = Config.FocusMode.AUTO
            session?.configure(config)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to create AR session: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread(this)
        depthTextureHandler.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayRotation = displayManager.getDisplay(0).rotation
        session?.setDisplayGeometry(displayRotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val session = this.session ?: return// 一度取得
        session.setCameraTextureName(backgroundRenderer.getCameraTextureId())

        var depthImage:Image? = null //'android.media.Image' を明示
        var depthTextureId = -1

        try {
            val frame = session.update() ?: return

            // 深度画像を取得 (NotYetAvailableExceptionをキャッチ)
            depthImage = try {
                frame.acquireDepthImage16Bits()
            } catch (e: NotYetAvailableException) {
                null // 深度がまだ利用できないため、null
            }
            // 深度画像が取得できた時だけ処理を実行
            if (depthImage != null) {
                // 深度画像をテクスチャとしてGPUにアップロード
                depthTextureHandler.update(depthImage)
                depthTextureId = depthTextureHandler.getTextureId()

                // フレームカウンターをインクリメント
                frameCounter++

                // カウンターが10に達し、かつOpenCVが初期化済みかチェック
                if (frameCounter >= PROCESSING_INTERVAL && isOpenCVInitialized) {
                    frameCounter = 0
                    // nullチェック後に!!で渡す
                    processDepthImageOnCpu(depthImage!!, frame)
                }
            }

            backgroundRenderer.draw(frame, depthTextureId)

        } catch (e: CameraNotAvailableException) {
            // No-op (カメラが利用不可)
        } catch (e: Exception) {
            // その他の予期せぬエラー
            Log.e(TAG, "Exception onDrawFrame", e)
        } finally {
            // メモリリーク対策
            depthImage?.close()
        }
    }

    // depth_captures フォルダの中身を全て削除する
    private fun clearDepthCapturesDirectory() {
        val externalDir = getExternalFilesDir(null) ?: return
        val captureDir = File(externalDir, "depth_captures")

        if (captureDir.exists() && captureDir.isDirectory) {
            val files = captureDir.listFiles()
            if (files != null) {
                for (file in files) {
                    // ファイルのみ削除
                    if (file.isFile) {
                        file.delete()
                    }
                }
                Log.d(TAG, "Cleared depth_captures directory.")
            }
        }
    }


    //CPU処理（焦点距離取得、Mat変換）を行う関数 じゅうよう！！
    /**
     * 10フレームに1回呼び出される、CPUでの深度画像処理
     * @param depthImage ARCoreから取得した深度画像 (16bit)
     * @param frame 現在のARフレーム (焦点距離の取得などに使用)
     */

    private fun processDepthImageOnCpu(depthImage: Image, frame: Frame) {

        val depthWidth = depthImage.width
        val depthHeight = depthImage.height
        val currentTime = System.currentTimeMillis()

        // --- 焦点距離のスケーリング (高さ推定用) ---
        var scaledFy = 0.0f
        if (focalLengthY <= 0) {
            val intrinsics = frame.camera.imageIntrinsics
            val dimensions = intrinsics.imageDimensions
            val rawFy = intrinsics.focalLength[1]
            val rawWidth = dimensions[0]
            focalLengthY = rawFy
            cameraImageWidth = rawWidth
            Log.d(TAG, "Intrinsics: fy=$rawFy, width=$rawWidth")
        }

        if (focalLengthY > 0 && cameraImageWidth > 0) {
            val scale = depthWidth.toFloat() / cameraImageWidth.toFloat()
            scaledFy = focalLengthY * scale
        } else {
            scaledFy = depthWidth.toFloat()
        }

        val plane = depthImage.planes[0]
        val buffer = plane.buffer
        // エンディアンの設定
        buffer.order(ByteOrder.nativeOrder())
        val depthBuffer: ShortBuffer = buffer.asShortBuffer()

        // Matの宣言
        val depthMat = Mat(depthHeight, depthWidth, CvType.CV_16UC1)
        val saveMat = Mat()
        val colorMat = Mat()
        val debugMat = Mat()
        val rotatedMat = Mat()
        val calcRoiMat = Mat() // 計算用ROI
        val binaryMat = Mat()
        val maskMat = Mat() // 検出エリア用マスク
        val contours = ArrayList<MatOfPoint>() // 輪郭抽出用
        val hierarchy = Mat() // 輪郭抽出用

        // カメラ画像処理用のMat宣言
        val cameraMat = Mat()
        val rotatedCameraMat = Mat()
        val resizedCameraMat = Mat()
        val edgeMat = Mat() // エッジ検出結果

        try {
            // --- カメラ画像の取得とエッジ検出 (足元補正用) ---
            try {
                // カメラ画像の取得
                val cameraImage = frame.acquireCameraImage()

                // --- カメラ画像のカラー保存処理 ---
                saveColorCameraImage(cameraImage, currentTime)

                val yBuffer = cameraImage.planes[0].buffer // Y平面(輝度)のみ取得
                val yBytes = ByteArray(yBuffer.remaining())
                yBuffer.get(yBytes)

                // グレースケールMat作成
                cameraMat.create(cameraImage.height, cameraImage.width, CvType.CV_8UC1)
                cameraMat.put(0, 0, yBytes)

                // 回転 (横長 -> 縦長)
                Core.rotate(cameraMat, rotatedCameraMat, Core.ROTATE_90_CLOCKWISE)

                // 深度画像と同じサイズにリサイズ (90x160)
                // これによりピクセル座標を一致させる
                val rotatedDepthSize = Size(depthHeight.toDouble(), depthWidth.toDouble()) // 90x160
                Imgproc.resize(rotatedCameraMat, resizedCameraMat, rotatedDepthSize, 0.0, 0.0, Imgproc.INTER_AREA)

                // Cannyエッジ検出
                // 一般的な低50, 高150
                Imgproc.Canny(resizedCameraMat, edgeMat, 50.0, 150.0)

                cameraImage.close()
            } catch (e: Exception) {
                Log.w(TAG, "Camera image processing failed", e)
            }
            // ----------------------------------------------------

            val depthData = ShortArray(depthBuffer.remaining())
            depthBuffer.get(depthData)
            depthMat.put(0, 0, depthData)

            // depthMat(横長: 160x90) -> rotatedDepthMat(縦長: 90x160):回転
            Core.rotate(depthMat, rotatedMat, Core.ROTATE_90_CLOCKWISE)

            // ROIの設定と切り出し
            // ROIのサイズ計算を回転後のrotatedMatのサイズで行う
            val rotatedWidth = rotatedMat.cols() // 90
            val rotatedHeight = rotatedMat.rows() // 160

            // --- 閾値計算用ROI (CALC_ROI: 天井エリア) ---
            val calcRoiX = rotatedWidth * CALC_ROI_X_PERCENT / 100
            val calcRoiY = rotatedHeight * CALC_ROI_Y_PERCENT / 100
            val calcRoiWidth = rotatedWidth * CALC_ROI_W_PERCENT / 100
            val calcRoiHeight = rotatedHeight * CALC_ROI_H_PERCENT / 100

            // 基準MatをrotatedMatに変更
            val calcRoiRect = Rect(calcRoiX, calcRoiY, calcRoiWidth, calcRoiHeight)
            val calcRoiSubMat = Mat(rotatedMat, calcRoiRect)

            // ROI内の基準深度(D_base)の算出percentile
            val roiPixelsShort = ShortArray(calcRoiSubMat.rows() * calcRoiSubMat.cols())
            calcRoiSubMat.get(0, 0, roiPixelsShort)

            // ノイズの可能性がある"0"を除外
            val validDepths = roiPixelsShort.filter { it.toInt() > 0 }

            if (validDepths.isEmpty()) {
                calcRoiSubMat.release()
                return
            }

            val sortedDepths = validDepths.sorted()

            // percentileを計算
            val percentileIndex = (sortedDepths.size * THRESHOLD_PERCENTILE).toInt().coerceAtMost(sortedDepths.size - 1)
            // percentileによるD_base(基準深度)
            val baseDepthMm = sortedDepths[percentileIndex].toInt()


            Log.d(TAG, "Base Depth (${THRESHOLD_PERCENTILE * 100}%ile): ${baseDepthMm} mm")

            // 障害物領域の抽出(二値化)

            // 下限: 100mm (10cm) より近いものはノイズとして無視
            val minThreshold = 100
            // 上限: 基準深度の深度
            val maxThreshold = baseDepthMm

            // 二値化の入力MatをrotatedMatに変更
            // minThreshold 〜 maxThreshold の間の深度のピクセルを抽出
            Core.inRange(rotatedMat, org.opencv.core.Scalar(minThreshold.toDouble()), org.opencv.core.Scalar(maxThreshold.toDouble()), binaryMat)

            // 融合した障害物を分離するためのモルフォロジー処理
            // 収縮(ERODE)を適用し、壁とのわずかな接点を切断
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.erode(binaryMat, binaryMat, kernel, Point(-1.0, -1.0), 1) // 1回収縮

            // ノイズ除去のための膨張(DILATE)を続行して、切断後の分離を維持しつつ穴埋め
            // Imgproc.dilate(binaryMat, binaryMat, kernel, Point(-1.0, -1.0), 1)　// ←分離・切断優先のため、膨張処理はしない

            // 検出用ROI (DETECT_ROI) でマスク処理
            maskMat.create(rotatedMat.size(), CvType.CV_8UC1)
            maskMat.setTo(Scalar(0.0)) // 黒で初期化

            val detectRoiX = rotatedWidth * DETECT_ROI_X_PERCENT / 100
            val detectRoiY = rotatedHeight * DETECT_ROI_Y_PERCENT / 100
            val detectRoiWidth = rotatedWidth * DETECT_ROI_W_PERCENT / 100
            val detectRoiHeight = rotatedHeight * DETECT_ROI_H_PERCENT / 100

            val detectRoiRect = Rect(detectRoiX, detectRoiY, detectRoiWidth, detectRoiHeight)

            // 検出したい領域だけ白
            Imgproc.rectangle(maskMat, detectRoiRect, Scalar(255.0), Core.FILLED)

            // AND演算でマスク適用 (天井・四隅をカット
            Core.bitwise_and(binaryMat, maskMat, binaryMat)

            // 障害物の高さ推定と可視化
            // 可視化用カラー画像
            val scaleFactor = 255.0 / 8000.0
            rotatedMat.convertTo(saveMat, CvType.CV_8U, scaleFactor)
            Imgproc.applyColorMap(saveMat, colorMat, Imgproc.COLORMAP_JET)

            // 輪郭抽出
            Imgproc.findContours(binaryMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            // 消失点のY座標 (画像中央)
            val cy = rotatedHeight / 2.0f

            // 検出された中で最も低い下端の高さ
            var minBottomHeight = 100.0f // 初期値
            var obstacleDetected = false

            // --- JSONデータ構築用 ---
            val frameLog = JSONObject()
            val resultImageName = "result_$currentTime.png"
            frameLog.put("timestamp", currentTime)
            frameLog.put("image_name", resultImageName)
            val detections = JSONArray()

            var count = 0
            for (contour in contours) {
                // 輪郭が大きすぎる場合は無視(40%)
                if (Imgproc.contourArea(contour) > (rotatedWidth * rotatedHeight * 0.4)) {
                    continue
                }

                // ある程度大きい領域のみ計測、最大3つまで
                if (Imgproc.contourArea(contour) > 50 && count < 3) {
                    val rect = Imgproc.boundingRect(contour)

                    // 障害物領域内の深度の中央値を取得する
                    val contourDepths = ArrayList<Int>()

                    // 矩形内をスキャンし、binaryMatが白(255)の場所(=障害物)の深度を収集
                    for (y in rect.y until (rect.y + rect.height)) {
                        for (x in rect.x until (rect.x + rect.width)) {
                            // 範囲チェック
                            if (x >= 0 && x < rotatedWidth && y >= 0 && y < rotatedHeight) {
                                // 抽出されたマスク上での値を確認
                                val maskVal = binaryMat.get(y, x)[0].toInt()
                                if (maskVal > 0) { // 障害物である
                                    val d = rotatedMat.get(y, x)[0].toInt()
                                    if (d > 0) {
                                        contourDepths.add(d)
                                    }
                                }
                            }
                        }
                    }

                    // 中央値(Median)の計算
                    var distanceMm = 0
                    if (contourDepths.isNotEmpty()) {
                        contourDepths.sort()
                        distanceMm = contourDepths[contourDepths.size / 2]
                    } else {
                        // 万が一ピクセルが取れなかった場合は矩形中心を使う(フォールバック)
                        distanceMm = rotatedMat.get(rect.y + rect.height / 2, rect.x + rect.width / 2)[0].toInt()
                    }

                    if (distanceMm > 0 && scaledFy > 0) {
                        val distanceM = distanceMm / 1000.0f

                        // --- エッジによる足元補正 (Refinement) ---
                        // カメラ画像のエッジ(edgeMat)を使って、矩形内部で最も下にある「強いエッジ」を探す
                        var refinedBottomY = (rect.y + rect.height).toFloat() // 初期値は深度の矩形下端

                        if (!edgeMat.empty()) {
                            // 探索範囲: 矩形の下半分
                            val searchStartY = rect.y + rect.height / 2
                            val searchEndY = rect.y + rect.height

                            // 下から上に向かってスキャンし、エッジを見つける
                            var foundEdgeY = -1
                            // Yループ (下から上へ)
                            for (y in searchEndY - 1 downTo searchStartY) {
                                var edgePixelCount = 0
                                // Xループ (矩形の幅)
                                for (x in rect.x until (rect.x + rect.width)) {
                                    if (x >= 0 && x < rotatedWidth && y >= 0 && y < rotatedHeight) {
                                        val edgeVal = edgeMat.get(y, x)[0].toInt()
                                        if (edgeVal > 0) edgePixelCount++
                                    }
                                }
                                // 幅の20%以上のピクセルがエッジなら「境界線」とみなす
                                if (edgePixelCount > (rect.width * 0.2)) {
                                    foundEdgeY = y
                                    break // 一番下の強いエッジが見つかったら終了
                                }
                            }

                            if (foundEdgeY != -1) {
                                refinedBottomY = foundEdgeY.toFloat()
                                // デバッグ: 補正したラインを描画
                                Imgproc.line(colorMat, Point(rect.x.toDouble(), refinedBottomY.toDouble()),
                                    Point((rect.x + rect.width).toDouble(), refinedBottomY.toDouble()), Scalar(255.0, 255.0, 255.0), 1)
                            }
                        }

                        // 下端の絶対高さ (H_bottom) の計算
                        // yBottom は補正後の位置を使用
                        val yBottom = refinedBottomY

                        // yBottom > cy (中央より下/床側) なら (yBottom - cy) はプラス
                        // hBottom = 1.3m - (距離 * 角度補正)
                        val hBottom =   cameraRealHeightMeters - (distanceM * (yBottom - cy)) / scaledFy

                        obstacleDetected = true
                        if (hBottom < minBottomHeight) {
                            minBottomHeight = hBottom
                        }

                        // --- ★ JSONへ追加 (高さが0.2m以上の場合のみ保存) ★ ---
                        if ((3.0 >= hBottom) && (hBottom >= 0.2)) {
                            val obj = JSONObject()
                            obj.put("height", hBottom.toDouble())
                            obj.put("distance", distanceM.toDouble())
                            detections.put(obj)

                            // JSONに保存した（有効な障害物とみなした）場合のみカウント
                            count++
                        }

                        // 描画 (UI表示はそのまま継続)
                        Imgproc.rectangle(colorMat, rect, Scalar(0.0, 255.0, 0.0), 1)

                        // 表示: 下端高さ (Hb) と 距離 (D)
                        val labelH = String.format("Hb:%.2fm", hBottom)
                        val labelD = String.format("D:%.1fm", distanceM)


                        Imgproc.putText(colorMat, labelH, Point(rect.x.toDouble(), rect.y.toDouble() - 15),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, Scalar(0.0, 0.0, 0.0), 2) // 太字の黒
                        Imgproc.putText(colorMat, labelH, Point(rect.x.toDouble(), rect.y.toDouble() - 15),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, Scalar(255.0, 255.0, 255.0), 1) // 白

                        Imgproc.putText(colorMat, labelD, Point(rect.x.toDouble(), rect.y.toDouble() - 5),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, Scalar(255.0, 255.0, 255.0), 1)
                    }
                }
            }

            // 検出データをフレームログに追加し、試行全体の配列へ保存
            frameLog.put("detections", detections)
            trialDataArray.put(frameLog)


            // 各種データの保存処理 (試行ディレクトリへ)
            val currentTimeStr = currentTime.toString()
            Imgcodecs.imwrite(File(currentTrialDir, "mask_$currentTimeStr.png").absolutePath, binaryMat)
            Imgcodecs.imwrite(File(currentTrialDir, resultImageName).absolutePath, colorMat)
            Imgcodecs.imwrite(File(currentTrialDir, "depth_raw_$currentTimeStr.png").absolutePath, rotatedMat)
            if (!edgeMat.empty()) {
                Imgcodecs.imwrite(File(currentTrialDir, "edge_$currentTimeStr.png").absolutePath, edgeMat)
            }

            // デバッグ表示
            mainHandler.post {
                if (obstacleDetected) {
                    // 検出された中で最も床に近い（低い）物体の高さを表示
                    infoText.text = String.format("Btm Height: %.2f m (Base: %.2fm)", minBottomHeight, baseDepthMm/1000.0)
                } else {
                    infoText.text = String.format("Scanning... (Base: %.2fm)", baseDepthMm/1000.0)
                }
            }

            // --- ログ表示 ---
            val debugWidth = 16.0
            val debugHeight = 9.0 * 16.0 / 90.0
            Imgproc.resize(rotatedMat, debugMat, Size(debugWidth, debugHeight), 0.0, 0.0, Imgproc.INTER_NEAREST)

            val sb = StringBuilder()
            sb.append("\n--- Depth Matrix (Rotated) ---\n")
            for (row in 0 until debugMat.rows()) {
                sb.append("[")
                for (col in 0 until debugMat.cols()) {
                    val depthValue = debugMat.get(row, col)[0].toInt()
                    sb.append(String.format("%4d", depthValue))
                    if (col < debugMat.cols() - 1) sb.append(",")
                }
                sb.append("]\n")
            }
            Log.d(TAG, sb.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process depth", e)
        } finally {
            // メモリ解放
            depthMat.release()
            saveMat.release()
            colorMat.release()
            debugMat.release()
            rotatedMat.release()
            calcRoiMat.release()
            binaryMat.release()
            maskMat.release()
            hierarchy.release()

            cameraMat.release()
            rotatedCameraMat.release()
            resizedCameraMat.release()
            edgeMat.release()

            for (c in contours) c.release()
        }
    }

    /**
     * カラーカメラ画像をBGR形式に変換して保存するヘルパー関数
     */
    private fun saveColorCameraImage(image: Image, timestamp: Long) {
        try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val yuvBytes = ByteArray(ySize + uSize + vSize)
            yBuffer.get(yuvBytes, 0, ySize)
            vBuffer.get(yuvBytes, ySize, vSize)
            uBuffer.get(yuvBytes, ySize + vSize, uSize)

            val yuvMat = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
            yuvMat.put(0, 0, yuvBytes)

            val bgrMat = Mat()
            Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21)

            val rotatedCMat = Mat()
            Core.rotate(bgrMat, rotatedCMat, Core.ROTATE_90_CLOCKWISE)

            val filename = "camera_$timestamp.png"
            Imgcodecs.imwrite(File(currentTrialDir, filename).absolutePath, rotatedCMat)

            yuvMat.release()
            bgrMat.release()
            rotatedCMat.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save color camera image", e)
        }
    }
}