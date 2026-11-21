package dev.wadackel.limenty.docs

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * The finishing done to a raw screen capture before it goes into the README.
 *
 * Done here rather than by an image tool afterwards so the committed images are exactly what the
 * test produced, and a verify run compares the finished picture rather than an input to it.
 */
internal object DocsImages {
    private const val BORDER = 0xFF3A3F4B.toInt()

    /** Scales by halving first, since a single bilinear pass below half size drops detail. */
    fun scaleToWidth(source: Bitmap, width: Int): Bitmap {
        var current = source
        while (current.width / 2 >= width) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        val height = (current.height * width.toFloat() / current.width).toInt()
        return Bitmap.createScaledBitmap(current, width, height, true)
    }

    fun crop(source: Bitmap, rect: Rect): Bitmap = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())

    /** Widens [source] by [padding] on every side in the colour of its top-right pixel, its background. */
    fun pad(source: Bitmap, padding: Int): Bitmap {
        val out = Bitmap.createBitmap(source.width + padding * 2, source.height + padding * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(source.getPixel(source.width - 1, 0))
        canvas.drawBitmap(source, padding.toFloat(), padding.toFloat(), null)
        return out
    }

    /** Rounded corners and a hairline border, with transparency outside so it sits on any page. */
    fun frame(source: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        canvas.drawRoundRect(bounds, radius, radius, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = BORDER
        }
        bounds.inset(1.5f, 1.5f)
        canvas.drawRoundRect(bounds, radius, radius, stroke)
        return out
    }

    /**
     * Framed screens side by side on the app's ink-and-violet panel, the middle one larger.
     *
     * One composed image rather than three separate ones: GitHub lays out inline images by the
     * reader's window width, and on a narrow window three phone screens wrap onto three rows.
     */
    fun hero(left: Bitmap, middle: Bitmap, right: Bitmap): Bitmap {
        val width = 2400
        val height = 1320
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val panel = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(width / 2f, height / 2f, width * 0.55f, 0xFF3B2F6E.toInt(), 0xFF0D0F15.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(panel, 48f, 48f, glow)
        val sides = 980
        val centre = 1100
        val gap = 80f
        val l = scaleToHeight(left, sides)
        val m = scaleToHeight(middle, centre)
        val r = scaleToHeight(right, sides)
        val mx = (width - m.width) / 2f
        place(canvas, l, mx - gap - l.width, (height - l.height) / 2f + 30f)
        place(canvas, r, mx + m.width + gap, (height - r.height) / 2f + 30f)
        place(canvas, m, mx, (height - m.height) / 2f + 10f)
        return scaleToWidth(out, 1600)
    }

    private fun scaleToHeight(source: Bitmap, height: Int): Bitmap {
        val width = (source.width * height.toFloat() / source.height).toInt()
        return frame(scaleToWidth(source, width), radius = 44f)
    }

    private fun place(canvas: Canvas, image: Bitmap, x: Float, y: Float) {
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 0, 0, 0)
            maskFilter = BlurMaskFilter(36f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(RectF(x, y + 18f, x + image.width, y + image.height + 18f), 44f, 44f, shadow)
        canvas.drawBitmap(image, x, y, null)
    }
}
