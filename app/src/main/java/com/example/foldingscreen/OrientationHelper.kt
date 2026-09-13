package com.example.foldingscreen

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * 姿态解算工具（空间锚定模式专用）：基于系统 TYPE_ROTATION_VECTOR 旋转矢量传感器。
 *
 * 该传感器融合陀螺仪 / 加速度计 / 磁力计，输出无漂移的稳定三维姿态（旋转矩阵）。
 *
 * 核心逻辑：
 *  1. start() 后首个事件记录【基准姿态】R0（模式开启瞬间的设备朝向）；
 *  2. 之后每个事件计算【当前姿态】R1；
 *  3. 相对旋转量 R_rel = R1^T * R0 —— 表示「基准坐标系下的画面」应如何映射到
 *     「当前设备坐标系」；
 *  4. 将 R_rel 转为 OpenGL 列主序 4x4 矩阵，渲染层直接作为模型矩阵使用，
 *     画面即被「锚定」在开启瞬间的物理朝向：转动手机 → 画面反向补偿。
 *
 * 约束说明：仅处理旋转姿态变化，不响应任何位移（平移）—— 本传感器只输出
 * 朝向信息，位移天然被忽略，满足「仅响应旋转、不处理位移」的需求。
 *
 * 采样率：SENSOR_DELAY_GAME（≈50Hz），与 60Hz 渲染节奏基本同步。
 */
class OrientationHelper(context: Context) {

    companion object {
        private const val TAG = "OrientationHelper"
        private const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 传感器专用线程：避免阻塞主线程 */
    private val sensorThread = HandlerThread("orientation-sensor").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    /** 基准姿态矩阵 R0（3x3 行主序）；模式开启瞬间记录，此后不再变化 */
    @Volatile
    private var baseMatrix: FloatArray? = null

    /** 当前相对旋转矩阵 R_rel（4x4 列主序，OpenGL 可直接使用），默认单位矩阵 */
    @Volatile
    private var relativeMatrix: FloatArray = identity4x4()

    @Volatile
    private var started = false

    /** 设备是否支持旋转矢量传感器 */
    val isSupported: Boolean get() = sensor != null

    /** 渲染线程每帧读取的最新相对旋转矩阵（引用读取，无锁） */
    fun latestMatrix(): FloatArray = relativeMatrix

    /** 启动监听：记录基准姿态，随后持续输出相对旋转矩阵 */
    fun start() {
        if (sensor == null) {
            Log.w(TAG, "设备不支持 TYPE_ROTATION_VECTOR 传感器")
            return
        }
        baseMatrix = null          // 下一个事件即作为基准姿态
        started = true
        sensorManager.registerListener(listener, sensor, SENSOR_DELAY, sensorHandler)
        Log.i(TAG, "姿态传感器已启动")
    }

    /** 停止监听（不销毁线程，资源释放由 release() 完成） */
    fun stop() {
        started = false
        sensorManager.unregisterListener(listener)
        Log.i(TAG, "姿态传感器已停止")
    }

    /** 释放全部资源（服务销毁 / 模式关闭时调用） */
    fun release() {
        stop()
        sensorThread.quitSafely()
    }

    private val listener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            if (!started) return

            // 旋转矢量 → 3x3 旋转矩阵（语义：world = R * device）
            val r = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(r, event.values)

            val base = baseMatrix
            if (base == null) {
                // 模式开启瞬间：记录基准姿态
                baseMatrix = r.copyOf()
                relativeMatrix = identity4x4()
                return
            }

            // 计算相对旋转：R_rel = R1^T * R0
            // 推导：世界坐标 p_world = R0 * p_base = R1 * p_cur
            //      => p_cur = R1^T * R0 * p_base = R_rel * p_base
            // 渲染层把模型矩阵设为 R_rel，即可让画面固定在基准（世界）方向。
            val rel = FloatArray(9)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    var sum = 0f
                    for (k in 0 until 3) {
                        sum += r[k * 3 + row] * base[k * 3 + col]
                    }
                    rel[row * 3 + col] = sum
                }
            }
            relativeMatrix = toColumnMajor4x4(rel)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // 精度变化无需特殊处理（旋转矢量自带融合校正）
        }
    }

    /** 单位矩阵（4x4 列主序） */
    private fun identity4x4(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    /** 3x3 行主序旋转矩阵 → OpenGL 列主序 4x4 矩阵 */
    private fun toColumnMajor4x4(r: FloatArray): FloatArray = floatArrayOf(
        r[0], r[3], r[6], 0f,   // 第 0 列
        r[1], r[4], r[7], 0f,   // 第 1 列
        r[2], r[5], r[8], 0f,   // 第 2 列
        0f, 0f, 0f, 1f          // 第 3 列
    )
}
