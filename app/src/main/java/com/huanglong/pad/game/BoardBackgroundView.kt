package com.huanglong.pad.game

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.huanglong.pad.R

class BoardBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var perspective: Player = Player.A
        set(value) {
            field = value
            invalidate()
        }

    // 1. 公路普通线画笔
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8A29E") // 提亮的灰蓝色公路连接线，使整张路网清晰可见
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
    }

    // 2. 铁路轨道底层霓虹发光画笔
    private val railGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 24f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 90
    }

    // 3. 铁路轨道底黑色基座线画笔
    private val railBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A8276") // 暗黑背景底色，用作黑色轨枕背景
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 4. 铁路轨道核心枕木线画笔 (黑白相间效果)
    private val railTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8E6E1") // 象牙白相间轨枕线
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 15f), 0f) // 完美的黑白相间轨枕效果
    }

    // 5. 普通格子(兵站)边框画笔
    private val stationBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8A29E") // 幽暗的 Slate 800 边框色
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val stationFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A000000") // 极淡的圆角矩形半透明填充
        style = Paint.Style.FILL
    }

    // 6. 行营圆环画笔
    private val campPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f) // 虚线圆
    }

    // 7. 大本营加固外框画笔
    private val hqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }

    // 7.5 界河横带渐变画笔、山界圆圈画笔与棋盘外框画笔
    private val riverBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val mountainFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6D3D1") // 山界圆圈深蓝填充，区别于普通兵站
        style = Paint.Style.FILL
    }

    private val mountainStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8A29E")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val frameGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    // 8. 各种文字画笔
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#57534E") // 兵站、大本营背景文字颜色 (灰色)
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val riverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8A29E") // 山界圆圈印章文字颜色
        textSize = 26f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        letterSpacing = 0.05f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val cellW = w / GameEngine.COLS
        val cellH = h / GameEngine.ROWS

        // 颜色动态适配 (根据主副视角自适应霓虹色彩)
        val themeColor = if (perspective == Player.A) {
            ContextCompat.getColor(context, R.color.color_a)
        } else {
            ContextCompat.getColor(context, R.color.color_b)
        }
        val glowColor = if (perspective == Player.A) {
            Color.parseColor("#1A1D4ED8")
        } else {
            Color.parseColor("#1AD97706")
        }

        railGlowPaint.color = glowColor
        campPaint.color = themeColor
        hqPaint.color = themeColor

        // 1. 初始化 12x5 交互交点
        val points = Array(GameEngine.ROWS) { r ->
            Array(GameEngine.COLS) { c ->
                PointF(c * cellW + cellW / 2f, r * cellH + cellH / 2f)
            }
        }

        // 根据当前玩家视角对物理交点进行映射
        fun getPhysPoint(pos: Position): PointF {
            val drawPos = if (perspective == Player.A) pos else pos.reverse()
            return points[drawPos.row][drawPos.col]
        }

        // 2. 界河横带底色（物理上永远位于画布纵向正中第 5/6 行之间，先画垫底）
        val riverY = 6 * cellH
        val bandHalf = cellH * 0.46f
        riverBandPaint.shader = LinearGradient(
            0f, riverY - bandHalf, 0f, riverY + bandHalf,
            intArrayOf(0x001E293B, 0x4D24344F, 0x001E293B),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, riverY - bandHalf, w, riverY + bandHalf, riverBandPaint)

        // 3. 绘制公路普通连线 (斜线与直线)
        for (r in 0 until GameEngine.ROWS) {
            for (c in 0 until GameEngine.COLS) {
                val fromPos = Position(r, c)
                val targets = listOf(
                    Position(r + 1, c),
                    Position(r, c + 1),
                    Position(r + 1, c + 1),
                    Position(r + 1, c - 1)
                )
                for (toPos in targets) {
                    if (toPos.isValid() && GameEngine.isRoadConnected(fromPos, toPos)) {
                        val pFrom = getPhysPoint(fromPos)
                        val pTo = getPhysPoint(toPos)
                        canvas.drawLine(pFrom.x, pFrom.y, pTo.x, pTo.y, roadPaint)
                    }
                }
            }
        }

        // 4. 绘制黑白相间发光铁轨线
        val railwayLines = mutableListOf<Pair<Position, Position>>()
        // 纵向铁路：左右两侧边线 (Col 0/4) 纵贯 Row 1..10
        for (c in listOf(0, 4)) {
            for (r in 1 until 10) {
                railwayLines.add(Pair(Position(r, c), Position(r + 1, c)))
            }
        }
        // 中央 Col 2 铁路仅有跨越山界的一段 (Row 5 - Row 6)，半场内为公路
        railwayLines.add(Pair(Position(5, 2), Position(6, 2)))
        // 横向铁路 (Row 1, 5, 6, 10；Col 0..4)
        for (r in listOf(1, 5, 6, 10)) {
            for (c in 0 until 4) {
                railwayLines.add(Pair(Position(r, c), Position(r, c + 1)))
            }
        }

        for (pair in railwayLines) {
            val p1 = getPhysPoint(pair.first)
            val p2 = getPhysPoint(pair.second)
            // (1) 绘制发光霓虹外圈
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, railGlowPaint)
            // (2) 绘制铁路基座黑色实线
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, railBasePaint)
            // (3) 绘制相间象牙白枕木虚线 (DashPathEffect)
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, railTrackPaint)
        }

        // 5. 绘制 60 个棋盘网格单元的外框（兵站、大本营、行营底色）
        // 注意：框体必须小于棋子实体（棋子约占 0.85*cellW x 0.64*cellH），落子后框不外露
        val rectW = cellW * 0.78f
        val rectH = cellH * 0.56f

        for (r in 0 until GameEngine.ROWS) {
            for (c in 0 until GameEngine.COLS) {
                val pos = Position(r, c)
                val center = getPhysPoint(pos)

                // 物理坐标框范围
                val left = center.x - rectW / 2f
                val top = center.y - rectH / 2f
                val right = center.x + rectW / 2f
                val bottom = center.y + rectH / 2f
                val rect = RectF(left, top, right, bottom)

                if (GameEngine.isCamp(pos)) {
                    // 绘制行营：虚线圆圈，底色浅黑
                    val radius = Math.min(cellW, cellH) * 0.30f
                    canvas.drawCircle(center.x, center.y, radius, stationFillPaint)
                    canvas.drawCircle(center.x, center.y, radius, campPaint)
                    // canvas.drawText("行营", center.x, center.y + 10f, labelTextPaint)
                } else if (GameEngine.isHq(pos)) {
                    // 绘制大本营：双线盾形卡框
                    canvas.drawRoundRect(rect, 12f, 12f, stationFillPaint)
                    canvas.drawRoundRect(rect, 12f, 12f, hqPaint)
                    // 内层环线
                    val innerRect = RectF(left + 6f, top + 6f, right - 6f, bottom - 6f)
                    canvas.drawRoundRect(innerRect, 8f, 8f, hqPaint)
                    // canvas.drawText("大本营", center.x, center.y + 10f, labelTextPaint)
                } else {
                    // 绘制普通兵站：圆角矩形灰色框，底框写字
                    canvas.drawRoundRect(rect, 6f, 6f, stationFillPaint)
                    canvas.drawRoundRect(rect, 6f, 6f, stationBorderPaint)
                    // canvas.drawText("兵站", center.x, center.y + 10f, labelTextPaint)
                }
            }
        }

        // 6. 绘制山界圆圈（Col 1 与 Col 3 处山岭阻断，不可通行，对应标准棋盘的两个"山界"）
        /*
        val mountainR = Math.min(cellW, cellH) * 0.36f
        for (c in listOf(1, 3)) {
            val cx = c * cellW + cellW / 2f
            canvas.drawCircle(cx, riverY, mountainR, mountainFillPaint)
            canvas.drawCircle(cx, riverY, mountainR, mountainStrokePaint)
            // canvas.drawText("山界", cx, riverY + 9f, riverTextPaint)
        }
        */

        // 7. 在界河的 Col 0, Col 2, Col 4 处绘制三个“前线”特殊格子
        /*
        val frontW = cellW * 0.88f
        val frontH = cellH * 0.65f
        for (c in listOf(0, 2, 4)) {
            // 前线的物理X中心
            val frontX = c * cellW + cellW / 2f
            val fLeft = frontX - frontW / 2f
            val fTop = riverY - frontH / 2f
            val fRight = frontX + frontW / 2f
            val fBottom = riverY + frontH / 2f
            val fRect = RectF(fLeft, fTop, fRight, fBottom)

            // 先画黑底，再画公路框
            canvas.drawRect(fRect, railBasePaint)
            canvas.drawRoundRect(fRect, 6f, 6f, stationFillPaint)
            canvas.drawRoundRect(fRect, 6f, 6f, roadPaint)
            // canvas.drawText("前线", frontX, riverY + 10f, labelTextPaint)
        }
        */

        // 8. 棋盘霓虹圆角外框（按视角主题色淡淡描边收束整体）
        frameGlowPaint.color = (themeColor and 0x00FFFFFF) or 0x59000000
        canvas.drawRoundRect(4f, 4f, w - 4f, h - 4f, 20f, 20f, frameGlowPaint)
    }
}
