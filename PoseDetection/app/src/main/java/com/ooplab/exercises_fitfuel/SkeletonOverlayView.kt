package com.ooplab.exercises_fitfuel

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class SkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // ── Paints ────────────────────────────────────────────────────────────────

    /** Glowing cyan bone lines */
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00D4FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }

    /** Slightly thicker, more opaque line beneath the glow for readability */
    private val boneCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B300D4FF")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Glowing joint dot */
    private val jointGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D00D4FF")
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }

    /** Solid joint core */
    private val jointCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00D4FF")
        style = Paint.Style.FILL
    }

    /** White dot highlight inside joint */
    private val jointHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 180
    }

    // ── Skeleton connections ──────────────────────────────────────────────────
    // MediaPipe BlazePose 33-landmark indices
    companion object {
        private val POSE_CONNECTIONS = listOf(
            // Face outline
            0 to 1, 1 to 2, 2 to 3, 3 to 7,
            0 to 4, 4 to 5, 5 to 6, 6 to 8,
            // Shoulders
            11 to 12,
            // Left arm
            11 to 13, 13 to 15, 15 to 17, 17 to 19, 19 to 15,
            15 to 21,
            // Right arm
            12 to 14, 14 to 16, 16 to 18, 18 to 20, 20 to 16,
            16 to 22,
            // Torso
            11 to 23, 12 to 24, 23 to 24,
            // Left leg
            23 to 25, 25 to 27, 27 to 29, 29 to 31, 31 to 27,
            // Right leg
            24 to 26, 26 to 28, 28 to 30, 30 to 32, 32 to 28
        )

        /** Key joints to render as visible dots (skip tiny face landmarks) */
        private val KEY_JOINTS = setOf(
            0,           // nose
            11, 12,      // shoulders
            13, 14,      // elbows
            15, 16,      // wrists
            23, 24,      // hips
            25, 26,      // knees
            27, 28,      // ankles
            29, 30,      // heels
            31, 32       // foot index
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call this from the PoseLandmarker result listener (runs on background thread).
     * [imgW] / [imgH] are the pixel dimensions of the image fed to MediaPipe.
     */
    fun updateLandmarks(result: PoseLandmarkerResult, imgW: Int, imgH: Int) {
        val firstPerson = result.landmarks().firstOrNull() ?: emptyList()
        landmarks = firstPerson
        imageWidth = imgW
        imageHeight = imgH
        postInvalidate() // triggers onDraw on the UI thread
    }

    /** Call this to clear the overlay (e.g. when no person is detected) */
    fun clear() {
        landmarks = emptyList()
        postInvalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty()) return

        val scaleX = width.toFloat()
        val scaleY = height.toFloat()

        fun lm(index: Int): PointF? {
            val lm = landmarks.getOrNull(index) ?: return null
            return PointF(lm.x() * scaleX, lm.y() * scaleY)
        }

        // 1. Draw bone connections (glow pass, then core pass)
        for ((startIdx, endIdx) in POSE_CONNECTIONS) {
            val start = lm(startIdx) ?: continue
            val end   = lm(endIdx)   ?: continue
            canvas.drawLine(start.x, start.y, end.x, end.y, bonePaint)
            canvas.drawLine(start.x, start.y, end.x, end.y, boneCorePaint)
        }

        // 2. Draw joints
        for (idx in KEY_JOINTS) {
            val pt = lm(idx) ?: continue
            canvas.drawCircle(pt.x, pt.y, 12f, jointGlowPaint)   // outer glow
            canvas.drawCircle(pt.x, pt.y, 5f,  jointCorePaint)   // solid core
            canvas.drawCircle(pt.x, pt.y, 2f,  jointHighlightPaint) // highlight
        }
    }
}