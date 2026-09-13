package com.example.foldingscreen

import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 万向折叠数学工具：折叠轴表示、圆柱曲面顶点变换、光影参数计算。
 *
 * 坐标约定：所有计算在 NDC（归一化设备坐标，x/y ∈ [-1,1]）中进行，输出的
 * 顶点坐标直接作为 gl_Position 使用（正交投影，无近大远小透视变形）。
 *
 * 折叠模型：
 *  1. 折叠轴：过屏幕中心、方向角 θ（弧度）的单位向量 a=(cosθ, sinθ)，可在
 *     屏幕平面内 0~360° 任意旋转（万向折叠）；法向 n=(-sinθ, cosθ)。
 *     顶点到轴的带符号垂直距离 d = dot(p, n)，沿轴坐标 t = dot(p, a)；
 *  2. 圆柱弯曲：整块屏幕弯曲成半径为 r 的圆柱面。屏幕沿法向的总弧长为 2L
 *     （L = NDC 半对角线，即 |d| 的最大可能值），总弯曲角 = 折叠角 φ（弧度），
 *     因此 r = 2L / φ；每个顶点按 α = d / r 的圆弧角弯曲 ——
 *     弧长 d 与沿轴坐标 t 均保持不变，即「画面不拉伸、不变形」；
 *  3. 顶点新坐标：p' = a·t + n·r·sinα + z·r·(1-cosα)，z 凸向观察者；
 *  4. 折痕圆角：α=0 附近 z 以 1-cosα ≈ α²/2 平滑过渡，无尖锐折痕，
 *     贴合柔性屏物理特性；
 *  5. 光影（随折叠角 φ 动态增强）：
 *     - 折痕高光带：d≈0 处高斯亮带 exp(-d²/2σ²)，模拟玻璃弯折反光；
 *     - 内侧阴影：1-cosα 随弯曲程度增大，弯折区域整体变暗增强立体层次。
 */
object FoldMath {

    /** 网格细分参数：屏幕纹理划分为 N×N 网格（顶点数 (N+1)×(N+1)，默认 32 平衡效果与性能） */
    const val DEFAULT_GRID = 32

    /** NDC 半对角线：顶点到折叠轴的最大可能距离 L */
    private val L = sqrt(2.0).toFloat()

    /** 折痕高光带宽（NDC 单位） */
    private const val HIGHLIGHT_SIGMA = 0.06f

    /** 折痕高光强度系数 */
    private const val HIGHLIGHT_STRENGTH = 0.38f

    /** 内侧阴影强度系数 */
    private const val SHADOW_STRENGTH = 0.32f

    /** 顶点布局：x, y, z, u, v, light —— 6 个 float，stride 24 字节 */
    const val FLOATS_PER_VERTEX = 6
    const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    /**
     * 构建折叠网格顶点与索引。
     *
     * @param axisRad    折叠轴方向角（弧度，0~2π；0 = 水平折痕，π/2 = 垂直折痕）
     * @param foldRad    折叠角（弧度，0~π：0 完全展开，π 完全对折）
     * @param grid       网格划分数（默认 32 → 33×33 顶点、32×32 面片）
     * @param outVertices 输出顶点缓冲（需 ≥ (grid+1)² × 6 个 float；写入后 flip）
     * @param outIndices  输出索引缓冲（需 ≥ grid² × 6 个 short；写入后 flip）
     */
    fun buildGrid(
        axisRad: Float,
        foldRad: Float,
        grid: Int,
        outVertices: FloatBuffer,
        outIndices: ShortBuffer
    ) {
        val n = grid + 1
        val cosA = cos(axisRad)
        val sinA = sin(axisRad)
        // 折叠轴法向（屏幕平面内垂直于折痕的向量）
        val nx = -sinA
        val ny = cosA

        val phi = foldRad.coerceIn(0f, PI.toFloat())
        val flat = phi < 0.001f
        // 圆柱半径：总弧长 2L 对应总弯曲角 φ → r = 2L / φ
        val r = if (flat) 0f else (2f * L / phi)

        // 光影基准强度：随折叠角线性增强（0~1）
        val lightFactor = phi / PI.toFloat()

        for (gy in 0..grid) {
            // 纹理 v：顶部(gy=grid) v=0 —— 与采集纹理方向一致（ImageReader 首行为屏幕顶部）
            val v = 1f - gy.toFloat() / grid
            val y = -1f + gy.toFloat() / grid * 2f
            for (gx in 0..grid) {
                // 纹理 u：左侧(gx=0) u=0
                val u = gx.toFloat() / grid
                val x = -1f + gx.toFloat() / grid * 2f

                // 顶点到折叠轴的带符号垂直距离（沿法向）
                val d = x * nx + y * ny
                // 沿折叠轴的坐标
                val t = x * cosA + y * sinA

                var ox = x
                var oy = y
                var oz = 0f
                var light = 1f

                if (!flat && d != 0f) {
                    // 圆弧角：弧长 d 在半径 r 的圆柱面上对应的圆心角
                    val alpha = d / r
                    val sinAlpha = sin(alpha)
                    val cosAlpha = cos(alpha)
                    // 圆柱面变换：弧长与沿轴坐标均不变 → 画面比例不失真
                    ox = cosA * t + nx * r * sinAlpha
                    oy = sinA * t + ny * r * sinAlpha
                    oz = r * (1f - cosAlpha)

                    // —— 光影参数 ——
                    // 内侧阴影：弯曲越大（|α| 大）越暗，随折叠角增强
                    val shadow = SHADOW_STRENGTH * lightFactor * (1f - cosAlpha)
                    // 折痕高光：d≈0 处的高斯亮带，模拟玻璃弯折反光，随折叠角增强
                    val highlight = HIGHLIGHT_STRENGTH * lightFactor *
                        exp(-(d * d) / (2f * HIGHLIGHT_SIGMA * HIGHLIGHT_SIGMA))
                    light = (1f - shadow + highlight).coerceIn(0f, 1.4f)
                }

                outVertices.put(ox)
                outVertices.put(oy)
                outVertices.put(oz)
                outVertices.put(u)
                outVertices.put(v)
                outVertices.put(light)
            }
        }
        outVertices.flip()

        // 三角带索引：每两个相邻行组成一排三角形（2 个三角形 / 面片）
        for (row in 0 until grid) {
            for (col in 0 until grid) {
                val a = (row * n + col).toShort()
                val b = (row * n + col + 1).toShort()
                val c = ((row + 1) * n + col).toShort()
                val d2 = ((row + 1) * n + col + 1).toShort()
                outIndices.put(a); outIndices.put(b); outIndices.put(c)
                outIndices.put(b); outIndices.put(d2); outIndices.put(c)
            }
        }
        outIndices.flip()
    }
}
