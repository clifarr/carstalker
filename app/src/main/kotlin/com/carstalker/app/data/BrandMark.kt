package com.carstalker.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import androidx.core.graphics.toColorInt
import com.carstalker.app.R
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser

/**
 * The CarStalker mark parsed from [R.drawable.ic_brand_mark] and drawn as paths.
 *
 * [android.graphics.drawable.VectorDrawable] rasterizes itself onto a software canvas, which made
 * the PDF report embed a 42x42 bitmap of the logo. Drawing the parsed paths directly keeps the
 * logo vector in the generated PDF.
 */
internal class BrandMark private constructor(
    private val viewportWidth: Float,
    private val viewportHeight: Float,
    private val shapes: List<Shape>,
) {
    private class Shape(
        val path: Path,
        val fillColor: Int,
        val strokeColor: Int,
        val strokeWidth: Float,
        val strokeCap: Paint.Cap,
        val strokeJoin: Paint.Join,
    )

    fun draw(canvas: Canvas, left: Float, top: Float, size: Float) {
        if (viewportWidth <= 0f || viewportHeight <= 0f || shapes.isEmpty()) return
        val scale = size / viewportWidth
        val placement = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        for (shape in shapes) {
            val path = Path(shape.path).apply { transform(placement) }
            if (Color.alpha(shape.fillColor) > 0) {
                fillPaint.color = shape.fillColor
                canvas.drawPath(path, fillPaint)
            }
            if (Color.alpha(shape.strokeColor) > 0 && shape.strokeWidth > 0f) {
                strokePaint.color = shape.strokeColor
                strokePaint.strokeWidth = shape.strokeWidth * scale
                strokePaint.strokeCap = shape.strokeCap
                strokePaint.strokeJoin = shape.strokeJoin
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        @SuppressLint("ResourceType") // Vector drawables are XML, so getXml can read them.
        fun load(context: Context): BrandMark? = runCatching {
            val parser = context.resources.getXml(R.drawable.ic_brand_mark)
            var viewportWidth = 0f
            var viewportHeight = 0f
            var matrix = Matrix()
            val stack = mutableListOf<Matrix>()
            val shapes = mutableListOf<Shape>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "vector" -> {
                            viewportWidth = parser.getAttributeFloatValue(ANDROID_NS, "viewportWidth", 0f)
                            viewportHeight = parser.getAttributeFloatValue(ANDROID_NS, "viewportHeight", 0f)
                        }
                        "group" -> {
                            stack.add(Matrix(matrix))
                            applyGroup(parser, matrix)
                        }
                        "path" -> readShape(context, parser)?.let { shape ->
                            shape.path.transform(matrix)
                            shapes += shape
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "group" && stack.isNotEmpty()) {
                        matrix = stack.removeAt(stack.lastIndex)
                    }
                }
                parser.next()
            }
            BrandMark(viewportWidth, viewportHeight, shapes)
        }.getOrNull()

        private fun applyGroup(parser: XmlResourceParser, matrix: Matrix) {
            val translateX = parser.getAttributeFloatValue(ANDROID_NS, "translateX", 0f)
            val translateY = parser.getAttributeFloatValue(ANDROID_NS, "translateY", 0f)
            val scaleX = parser.getAttributeFloatValue(ANDROID_NS, "scaleX", 1f)
            val scaleY = parser.getAttributeFloatValue(ANDROID_NS, "scaleY", 1f)
            val rotation = parser.getAttributeFloatValue(ANDROID_NS, "rotation", 0f)
            val pivotX = parser.getAttributeFloatValue(ANDROID_NS, "pivotX", 0f)
            val pivotY = parser.getAttributeFloatValue(ANDROID_NS, "pivotY", 0f)
            matrix.postTranslate(-pivotX, -pivotY)
            matrix.postScale(scaleX, scaleY)
            matrix.postRotate(rotation)
            matrix.postTranslate(pivotX + translateX, pivotY + translateY)
        }

        private fun readShape(context: Context, parser: XmlResourceParser): Shape? {
            val data = parser.getAttributeValue(ANDROID_NS, "pathData") ?: return null
            val path = PathParser.createPathFromPathData(data) ?: return null
            return Shape(
                path = path,
                fillColor = resolveColor(parser.getAttributeValue(ANDROID_NS, "fillColor")),
                strokeColor = resolveColor(parser.getAttributeValue(ANDROID_NS, "strokeColor")),
                strokeWidth = parser.getAttributeValue(ANDROID_NS, "strokeWidth")?.toFloatOrNull() ?: 0f,
                strokeCap = when (parser.getAttributeValue(ANDROID_NS, "strokeLineCap")) {
                    "round" -> Paint.Cap.ROUND
                    "square" -> Paint.Cap.SQUARE
                    else -> Paint.Cap.BUTT
                },
                strokeJoin = when (parser.getAttributeValue(ANDROID_NS, "strokeLineJoin")) {
                    "round" -> Paint.Join.ROUND
                    "bevel" -> Paint.Join.BEVEL
                    else -> Paint.Join.MITER
                },
            )
        }

        private fun resolveColor(value: String?): Int {
            val raw = value?.trim().orEmpty()
            return when {
                raw.isEmpty() -> Color.TRANSPARENT
                raw.startsWith("#") -> runCatching { raw.toColorInt() }.getOrDefault(Color.TRANSPARENT)
                raw == "@android:color/transparent" -> Color.TRANSPARENT
                // The bundled mark only uses literal colours and the transparent resource above.
                else -> Color.TRANSPARENT
            }
        }
    }
}
