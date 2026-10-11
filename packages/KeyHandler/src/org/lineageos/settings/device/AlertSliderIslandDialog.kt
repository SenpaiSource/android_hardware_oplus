/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.animation.ObjectAnimator
import android.app.Dialog
import android.app.StatusBarManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.DisplayCutout
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

class AlertSliderIslandDialog(
    private val context: Context,
    private val sysuiContext: Context,
) : Dialog(context, R.style.alert_slider_theme) {

    private val dialogView by lazy { findViewById<FrameLayout>(R.id.alert_slider_island_dialog)!! }
    private val frameView by lazy { findViewById<LinearLayout>(R.id.alert_slider_island_view)!! }
    private val iconContainer by lazy { findViewById<FrameLayout>(R.id.alert_slider_island_icon_container)!! }
    private val iconView by lazy { findViewById<ImageView>(R.id.alert_slider_island_icon)!! }
    private val gapView by lazy { findViewById<Space>(R.id.alert_slider_island_cutout_gap)!! }
    private val textView by lazy { findViewById<TextView>(R.id.alert_slider_island_text)!! }

    private val statusBarManager by lazy {
        sysuiContext.getSystemService(StatusBarManager::class.java)
            ?: context.getSystemService(StatusBarManager::class.java)
    }

    var onDismissRequested: (() -> Unit)? = null

    private val powerManager by lazy {
        context.getSystemService(PowerManager::class.java)
    }

    private var activeShakeAnim: ObjectAnimator? = null
    // True during the 170 ms dismiss exit-animation so show() can cancel it instead of bailing.
    private var isDismissing = false
    private var currentRingerMode = -1

    private var cutoutPivotX = 0f
    private var cutoutPivotY = 0f
    private var startScaleX = 0.28f
    private var startScaleY = 0.6f

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isHorizontalSwipe = false
    private var velocityTracker: VelocityTracker? = null

    private fun isInteractive(): Boolean {
        return try {
            powerManager?.isInteractive ?: true
        } catch (e: Throwable) {
            true
        }
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val a = (Color.alpha(from) * inverseRatio + Color.alpha(to) * ratio).toInt()
        val r = (Color.red(from) * inverseRatio + Color.red(to) * ratio).toInt()
        val g = (Color.green(from) * inverseRatio + Color.green(to) * ratio).toInt()
        val b = (Color.blue(from) * inverseRatio + Color.blue(to) * ratio).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun applyCutoutPivot() {
        val pX = if (cutoutPivotX > 0f) cutoutPivotX else (frameView.width / 2f).coerceAtLeast(0f)
        val pY = if (cutoutPivotY > 0f) cutoutPivotY else (frameView.height / 2f).coerceAtLeast(0f)
        frameView.pivotX = pX
        frameView.pivotY = pY
    }

    private fun setSystemInfoDisabled(disabled: Boolean) {
        if (disabled && !isInteractive()) {
            return
        }
        try {
            val flags = if (disabled) {
                StatusBarManager.DISABLE_SYSTEM_INFO or
                    StatusBarManager.DISABLE_NOTIFICATION_ICONS or
                    StatusBarManager.DISABLE_CLOCK
            } else {
                StatusBarManager.DISABLE_NONE
            }
            statusBarManager?.disable(flags)
        } catch (e: Throwable) {
            // Ignore if service unavailable
        }
    }

    private fun handleTouchSwipe(event: MotionEvent): Boolean {
        if (isDismissing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.rawX
                touchStartY = event.rawY
                isHorizontalSwipe = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val deltaX = event.rawX - touchStartX
                val deltaY = event.rawY - touchStartY

                if (!isHorizontalSwipe) {
                    if (Math.abs(deltaX) > touchSlop && Math.abs(deltaX) > Math.abs(deltaY)) {
                        isHorizontalSwipe = true
                        applyCutoutPivot()
                    }
                }

                if (isHorizontalSwipe) {
                    val maxDrag = 120f * context.resources.displayMetrics.density
                    val dragFraction = (Math.abs(deltaX) / maxDrag).coerceIn(0f, 1f)
                    val dragScaleX = 1f - (dragFraction * (1f - startScaleX) * 0.75f)
                    val dragScaleY = 1f - (dragFraction * (1f - startScaleY) * 0.4f)
                    frameView.scaleX = dragScaleX
                    frameView.scaleY = dragScaleY

                    val contentAlpha = (1f - dragFraction * 0.6f).coerceIn(0f, 1f)
                    val contentScale = (1f - dragFraction * 0.35f).coerceIn(0.65f, 1f)
                    iconView.alpha = contentAlpha
                    textView.alpha = contentAlpha
                    iconView.scaleX = contentScale
                    iconView.scaleY = contentScale
                    textView.scaleX = contentScale
                    textView.scaleY = contentScale
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velX = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (isHorizontalSwipe) {
                    val deltaX = event.rawX - touchStartX
                    val density = context.resources.displayMetrics.density
                    val thresholdPx = 22f * density
                    val minVelocity = 400f * density

                    if (Math.abs(deltaX) > thresholdPx || Math.abs(velX) > minVelocity) {
                        dismissWithSwipe()
                    } else {
                        snapBackFromSwipe()
                    }
                    isHorizontalSwipe = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                if (isHorizontalSwipe) {
                    snapBackFromSwipe()
                    isHorizontalSwipe = false
                    return true
                }
            }
        }
        return false
    }

    private fun snapBackFromSwipe() {
        applyCutoutPivot()
        frameView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(160)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        iconView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(160)
            .start()

        textView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(160)
            .start()

        dialogView.animate()
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(160)
            .start()
    }

    private fun dismissWithSwipe() {
        if (!isShowing || isDismissing) return
        isDismissing = true
        activeShakeAnim?.cancel()
        activeShakeAnim = null
        applyCutoutPivot()
        setSystemInfoDisabled(false)

        iconView.animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(110)
            .setInterpolator(AccelerateInterpolator())
            .start()

        textView.animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(110)
            .setInterpolator(AccelerateInterpolator())
            .start()

        dialogView.animate()
            .translationX(0f)
            .alpha(0f)
            .setDuration(140)
            .start()

        frameView.animate()
            .scaleX(startScaleX)
            .scaleY(startScaleY)
            .alpha(0f)
            .setDuration(140)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction {
                isDismissing = false
                try {
                    super.dismiss()
                } catch (e: Throwable) {
                    // Ignored if window already detached
                }
                onDismissRequested?.invoke()
            }
            .start()
    }

    init {
        window?.let {
            it.requestFeature(Window.FEATURE_NO_TITLE)
            it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            it.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            it.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            it.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            it.attributes =
                it.attributes.apply {
                    format = PixelFormat.TRANSLUCENT
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    fitInsetsTypes = 0
                    title = TAG
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
        }

        setCanceledOnTouchOutside(false)
        setContentView(R.layout.alert_slider_island_dialog)

        frameView.setOnTouchListener { _, event ->
            handleTouchSwipe(event)
        }

        updateLayout()
    }

    private fun getSafeDisplay(): Display? {
        return (sysuiContext.getSystemService(DisplayManager::class.java)
            ?: context.getSystemService(DisplayManager::class.java))?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: window?.decorView?.display
    }

    private fun getDisplayCutout(): DisplayCutout? {
        return getSafeDisplay()?.cutout
    }

    @Suppress("DEPRECATION")
    private fun hasCenteredCutout(): Boolean {
        return try {
            val display = getSafeDisplay() ?: return false
            val cutout = getDisplayCutout() ?: return false
            val realSize = Point()
            display.getRealSize(realSize)

            when (display.rotation) {
                Surface.ROTATION_0 -> {
                    val rect = cutout.boundingRectTop
                    !rect.isEmpty && !(rect.left <= 0 || rect.right >= realSize.x)
                        && Math.abs(rect.exactCenterX() - (realSize.x / 2f)) < (realSize.x * 0.25f)
                }
                Surface.ROTATION_90 -> {
                    val rect = cutout.boundingRectLeft
                    !rect.isEmpty && !(rect.top <= 0 || rect.bottom >= realSize.y)
                        && Math.abs(rect.exactCenterY() - (realSize.y / 2f)) < (realSize.y * 0.25f)
                }
                Surface.ROTATION_180 -> {
                    val rect = cutout.boundingRectBottom
                    !rect.isEmpty && !(rect.left <= 0 || rect.right >= realSize.x)
                        && Math.abs(rect.exactCenterX() - (realSize.x / 2f)) < (realSize.x * 0.25f)
                }
                Surface.ROTATION_270 -> {
                    val rect = cutout.boundingRectRight
                    !rect.isEmpty && !(rect.top <= 0 || rect.bottom >= realSize.y)
                        && Math.abs(rect.exactCenterY() - (realSize.y / 2f)) < (realSize.y * 0.25f)
                }
                else -> false
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun getStatusBarHeight(): Int {
        val insetsHeight = window?.decorView?.rootWindowInsets
            ?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        if (insetsHeight > 0) {
            return insetsHeight
        }
        val res = sysuiContext.resources
        val resId = try {
            res.getIdentifier("status_bar_height", "dimen", "android")
        } catch (e: Throwable) {
            0
        }
        if (resId > 0) {
            try {
                return res.getDimensionPixelSize(resId)
            } catch (e: Throwable) {
                // Ignore and try fallback
            }
        }
        val cRes = context.resources
        val cResId = try {
            cRes.getIdentifier("status_bar_height", "dimen", "android")
        } catch (e: Throwable) {
            0
        }
        return if (cResId > 0) {
            try {
                cRes.getDimensionPixelSize(cResId)
            } catch (e: Throwable) {
                (cRes.displayMetrics.density * 40).toInt()
            }
        } else {
            (cRes.displayMetrics.density * 40).toInt()
        }
    }

    private fun getIslandScale(): Pair<Float, Float> {
        val widthScale = (try {
            Settings.System.getInt(context.contentResolver, "status_bar_dynamic_island_width_scale")
        } catch (e: Throwable) { 100 }).coerceIn(70, 140) / 100f

        val heightScale = (try {
            Settings.System.getInt(context.contentResolver, "status_bar_dynamic_island_height_scale")
        } catch (e: Throwable) { 100 }).coerceIn(70, 140) / 100f
        return Pair(widthScale, heightScale)
    }

    @Suppress("DEPRECATION")
    private fun updateLayout() {
        val display = getSafeDisplay()
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

        val cutout = getDisplayCutout()
        val topCutout = cutout?.boundingRectTop?.takeUnless { it.isEmpty }
        val density = context.resources.displayMetrics.density

        val (widthScale, heightScale) = getIslandScale()

        val basePillHeight = context.resources.getDimensionPixelSize(R.dimen.alert_slider_island_height)
        val pillHeight = (basePillHeight * heightScale).toInt()

        val basePadding = context.resources.getDimensionPixelSize(R.dimen.alert_slider_island_padding_horizontal)
        val horizontalPadding = (basePadding * widthScale).toInt()

        val isCenter = !isLandscape && hasCenteredCutout()

        // Embedded gap matching Singularity DynamicIslandCutoutSpec
        val minGap = (34 * density * widthScale).toInt()
        val maxGap = (88 * density * widthScale).toInt()
        val sideGapPadding = (10 * density * widthScale).toInt()
        val fallbackGap = (38 * density * widthScale).toInt()

        val gapWidth = if (isCenter && topCutout != null) {
            (topCutout.width() + (sideGapPadding * 2)).coerceIn(minGap, maxGap)
        } else if (isLandscape) {
            (8 * density).toInt()
        } else {
            fallbackGap
        }

        gapView.layoutParams?.let {
            it.width = gapWidth
            gapView.layoutParams = it
        }

        frameView.layoutParams?.let {
            it.height = pillHeight
            frameView.layoutParams = it
        }
        frameView.setPaddingRelative(horizontalPadding, 0, horizontalPadding, 0)

        // Real screen dimensions for alignment
        val realSize = Point()
        display?.getRealSize(realSize)
        val screenWidth = if (realSize.x > 0) realSize.x else context.resources.displayMetrics.widthPixels

        // Horizontal offset to center the cutout gap directly on the punch hole (Singularity spec)
        val iconSize = context.resources.getDimensionPixelSize(R.dimen.alert_slider_island_icon_size)
        val wingWidth = if (hasCenteredCutout()) {
            val maxTextWidth = (context.resources.getDimensionPixelSize(R.dimen.alert_slider_island_text_max_width) * widthScale).toInt()
            val textString = textView.text?.toString() ?: ""
            val textWidth = if (textString.isNotEmpty()) minOf(Math.ceil(textView.paint.measureText(textString).toDouble()).toInt(), maxTextWidth) else 0
            maxOf(iconSize, textWidth)
        } else {
            iconSize
        }

        if (hasCenteredCutout()) {
            iconContainer.minimumWidth = wingWidth
            textView.minWidth = wingWidth
        } else {
            iconContainer.minimumWidth = 0
            textView.minWidth = 0
        }

        val xPos = if (isCenter && topCutout != null) {
            (topCutout.exactCenterX() - (screenWidth / 2f)).toInt()
        } else {
            0
        }

        val approxPillWidth = (horizontalPadding * 2) + (wingWidth * 2) + gapWidth
        if (isCenter && topCutout != null) {
            cutoutPivotX = horizontalPadding + wingWidth + (gapWidth / 2f)
            cutoutPivotY = pillHeight / 2f
            startScaleX = if (approxPillWidth > 0) (gapWidth.toFloat() / approxPillWidth).coerceIn(0.18f, 0.45f) else 0.28f
            startScaleY = if (pillHeight > 0) (topCutout.height().toFloat() / pillHeight).coerceIn(0.45f, 0.75f) else 0.6f
        } else {
            cutoutPivotX = if (approxPillWidth > 0) approxPillWidth / 2f else -1f
            cutoutPivotY = pillHeight / 2f
            startScaleX = 0.5f
            startScaleY = 0.6f
        }
        applyCutoutPivot()

        // Y position in absolute screen coordinates (FLAG_LAYOUT_IN_SCREEN + fitInsetsTypes = 0)
        val statusBarHeight = getStatusBarHeight()
        val yPos = if (isCenter && topCutout != null) {
            val cutoutCenterY = (topCutout.top + topCutout.bottom) / 2
            maxOf((2 * density).toInt(), cutoutCenterY - pillHeight / 2)
        } else if (!isLandscape) {
            maxOf((2 * density).toInt(), (statusBarHeight - pillHeight) / 2)
        } else {
            (4 * density).toInt()
        }

        window?.let {
            try {
                it.setLayout(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                )
                it.attributes =
                    it.attributes.apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        x = xPos
                        y = yPos
                    }
            } catch (e: Throwable) {
                // Ignore if window not attached
            }
        }
    }

    fun setState(position: Int, ringerMode: Int, invertColors: Boolean) {
        currentRingerMode = ringerMode
        if (isShowing) {
            animateStateChange(ringerMode, invertColors)
        } else {
            applyUiMode(ringerMode, invertColors)
            updateLayout()
        }
    }

    private fun animateStateChange(ringerMode: Int, invertColors: Boolean) {
        val density = context.resources.displayMetrics.density
        val slideOffset = 6f * density

        activeShakeAnim?.cancel()
        activeShakeAnim = null
        iconView.animate().cancel()
        textView.animate().cancel()
        frameView.animate().cancel()
        dialogView.animate().cancel()

        isDismissing = false
        dialogView.alpha = 1f
        dialogView.scaleX = 1f
        dialogView.scaleY = 1f
        dialogView.translationX = 0f
        dialogView.translationY = 0f
        isHorizontalSwipe = false
        setSystemInfoDisabled(true)

        iconView.alpha = 1f
        iconView.scaleX = 1f
        iconView.scaleY = 1f
        iconView.translationX = 0f
        iconView.translationY = 0f

        textView.alpha = 1f
        textView.scaleX = 1f
        textView.scaleY = 1f
        textView.translationX = 0f
        textView.translationY = 0f

        frameView.scaleX = 1f
        frameView.scaleY = 1f
        frameView.translationX = 0f
        frameView.translationY = 0f

        // Subtle fluid horizontal capsule pulse (like Singularity)
        frameView.animate()
            .scaleX(1.04f)
            .setDuration(70)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                frameView.animate()
                    .scaleX(1.0f)
                    .setDuration(130)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            .start()

        // Icon slide and crossfade
        iconView.animate()
            .translationY(-slideOffset)
            .alpha(0f)
            .setDuration(65)
            .setInterpolator(AccelerateInterpolator())
            .start()

        // Text smooth crossfade and slide
        textView.animate()
            .translationY(-slideOffset)
            .alpha(0f)
            .setDuration(65)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                applyUiMode(ringerMode, invertColors)
                updateLayout()

                iconView.translationY = slideOffset
                iconView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(120)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                            playVibrateShakeAnimation()
                        }
                    }
                    .start()

                textView.translationY = slideOffset
                textView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(120)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun playVibrateShakeAnimation() {
        val px = 2.5f * context.resources.displayMetrics.density
        activeShakeAnim?.cancel()
        activeShakeAnim = ObjectAnimator.ofFloat(
            iconView,
            "translationX",
            0f, -px, px, -px * 0.7f, px * 0.7f, -px * 0.3f, px * 0.3f, 0f
        ).apply {
            duration = 300
            start()
        }
    }

    private fun applyUiMode(ringerMode: Int, invertColors: Boolean) {
        val (iconRes, textRes, lunaAccentColor) =
            when (ringerMode) {
                AudioManager.RINGER_MODE_SILENT ->
                    Triple(
                        R.drawable.ic_volume_ringer_mute,
                        R.string.alert_slider_mode_silent,
                        Color.parseColor("#FF453A"), // Luna Red Accent
                    )
                AudioManager.RINGER_MODE_VIBRATE ->
                    Triple(
                        R.drawable.ic_volume_ringer_vibrate,
                        R.string.alert_slider_mode_vibration,
                        Color.parseColor("#FF9F0A"), // Luna Orange/Amber Accent
                    )
                AudioManager.RINGER_MODE_NORMAL ->
                    Triple(
                        R.drawable.ic_volume_ringer,
                        R.string.alert_slider_mode_normal,
                        Color.parseColor("#0A84FF"), // Luna Blue Accent
                    )
                KeyHandler.ZEN_PRIORITY_ONLY ->
                    Triple(
                        R.drawable.ic_notifications_alert,
                        R.string.alert_slider_mode_dnd_priority_only,
                        Color.parseColor("#BF5AF2"), // Luna Purple Accent
                    )
                KeyHandler.ZEN_TOTAL_SILENCE ->
                    Triple(
                        R.drawable.ic_notifications_silence,
                        R.string.alert_slider_mode_dnd_total_silence,
                        Color.parseColor("#BF5AF2"), // Luna Purple Accent
                    )
                KeyHandler.ZEN_ALARMS_ONLY ->
                    Triple(
                        R.drawable.ic_alarm,
                        R.string.alert_slider_mode_dnd_alarms_only,
                        Color.parseColor("#BF5AF2"), // Luna Purple Accent
                    )
                KeyHandler.TORCH_ON ->
                    Triple(
                        R.drawable.ic_torch_on,
                        R.string.alert_slider_mode_torch_on,
                        Color.parseColor("#FFD60A"), // Luna Yellow Accent
                    )
                KeyHandler.TORCH_OFF ->
                    Triple(
                        R.drawable.ic_torch_off,
                        R.string.alert_slider_mode_torch_off,
                        Color.parseColor("#8E8E93"), // Luna Muted Gray Accent
                    )
                else ->
                    Triple(
                        R.drawable.ic_info,
                        R.string.alert_slider_mode_none,
                        Color.WHITE,
                    )
            }

        val textString = context.getString(textRes)
        iconView.setImageResource(iconRes)
        textView.text = textString
        textView.isSelected = true

        if (hasCenteredCutout()) {
            val (widthScale, _) = getIslandScale()
            val textWidth = Math.ceil(textView.paint.measureText(textString).toDouble()).toInt()
            val maxTextWidth = (context.resources.getDimensionPixelSize(R.dimen.alert_slider_island_text_max_width) * widthScale).toInt()
            val iconSize = context.resources.getDimensionPixelSize(R.dimen.alert_slider_island_icon_size)
            val wingWidth = maxOf(iconSize, minOf(textWidth, maxTextWidth))
            iconContainer.minimumWidth = wingWidth
            textView.minWidth = wingWidth
        } else {
            iconContainer.minimumWidth = 0
            textView.minWidth = 0
        }

        val bgDrawable = frameView.background?.mutate() as? GradientDrawable
        val strokeWidth = (1 * context.resources.displayMetrics.density).toInt()

        val effectiveInvert = invertColors && isInteractive()

        if (effectiveInvert) {
            // Material 3 / Monet dynamic color mode (toggled via button_panel invert colors)
            val currentUiMode = sysuiContext.resources.configuration.uiMode
            val isDark =
                (currentUiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

            val bgResId =
                if (isDark) {
                    android.R.color.system_accent1_100
                } else {
                    android.R.color.system_accent1_600
                }

            val fgResId =
                if (isDark) {
                    android.R.color.system_neutral1_900
                } else {
                    android.R.color.system_neutral1_50
                }

            val activeBg = sysuiContext.getColor(bgResId)
            val activeFg = sysuiContext.getColor(fgResId)

            frameView.backgroundTintList = null
            bgDrawable?.let {
                it.setColor(activeBg)
                it.setStroke(strokeWidth, activeBg)
            } ?: run {
                frameView.backgroundTintList = ColorStateList.valueOf(activeBg)
            }

            iconView.imageTintList = ColorStateList.valueOf(activeFg)
            textView.setTextColor(activeFg)
        } else {
            // Native Luna Dynamic Island mode (Solid black capsule + Luna style vibrant accents)
            val pillBg = Color.BLACK
            // Adaptive accent border glow: subtle harmonious blend of titanium base with active mode accent
            val pillBorder = blendColors(Color.parseColor("#262626"), lunaAccentColor, 0.42f)
            val adaptiveStrokeWidth = (1.25f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

            frameView.backgroundTintList = null
            bgDrawable?.let {
                it.setColor(pillBg)
                it.setStroke(adaptiveStrokeWidth, pillBorder)
            } ?: run {
                frameView.backgroundTintList = ColorStateList.valueOf(pillBg)
            }

            iconView.imageTintList = ColorStateList.valueOf(lunaAccentColor)
            textView.setTextColor(Color.WHITE)
        }
    }

    private fun resetAnimation() {
        dialogView.animate().cancel()
        activeShakeAnim?.cancel()
        activeShakeAnim = null
        iconView.animate().cancel()
        textView.animate().cancel()
        frameView.animate().cancel()

        dialogView.alpha = 1f
        dialogView.scaleX = 1f
        dialogView.scaleY = 1f
        dialogView.translationX = 0f
        dialogView.translationY = 0f

        frameView.alpha = 1f
        frameView.scaleX = 1f
        frameView.scaleY = 1f
        frameView.translationX = 0f
        frameView.translationY = 0f

        iconView.alpha = 1f
        iconView.scaleX = 1f
        iconView.scaleY = 1f
        iconView.translationX = 0f
        iconView.translationY = 0f

        textView.alpha = 1f
        textView.scaleX = 1f
        textView.scaleY = 1f
        textView.translationX = 0f
        textView.translationY = 0f
        isHorizontalSwipe = false
    }

    override fun show() {
        setSystemInfoDisabled(true)
        updateLayout()
        applyCutoutPivot()
        textView.isSelected = true

        if (isShowing) {
            if (!isDismissing) return  // Already fully visible, nothing to do.
            // A dismiss animation is in-flight — cancel it and re-enter.
            isDismissing = false
            resetAnimation()
            applyCutoutPivot()
            dialogView.alpha = 1f
            dialogView.scaleX = 1f
            dialogView.scaleY = 1f
            frameView.alpha = 0f
            frameView.scaleX = startScaleX
            frameView.scaleY = startScaleY
            iconView.alpha = 0f
            iconView.scaleX = 0.7f
            iconView.scaleY = 0.7f
            textView.alpha = 0f
            textView.scaleX = 0.7f
            textView.scaleY = 0.7f

            frameView
                .animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.08f))
                .start()

            iconView
                .animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(190)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (currentRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
                        playVibrateShakeAnimation()
                    }
                }
                .start()

            textView
                .animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(190)
                .setInterpolator(DecelerateInterpolator())
                .start()
            return
        }

        resetAnimation()
        applyCutoutPivot()
        dialogView.alpha = 1f
        dialogView.scaleX = 1f
        dialogView.scaleY = 1f
        frameView.alpha = 0f
        frameView.scaleX = startScaleX
        frameView.scaleY = startScaleY
        iconView.alpha = 0f
        iconView.scaleX = 0.7f
        iconView.scaleY = 0.7f
        textView.alpha = 0f
        textView.scaleX = 0.7f
        textView.scaleY = 0.7f

        try {
            super.show()
        } catch (e: Throwable) {
            setSystemInfoDisabled(false)
            return
        }

        frameView
            .animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.08f))
            .start()

        iconView
            .animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (currentRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
                    playVibrateShakeAnimation()
                }
            }
            .start()

        textView
            .animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun dismissImmediate() {
        isDismissing = false
        setSystemInfoDisabled(false)
        resetAnimation()
        try {
            if (isShowing) {
                super.dismiss()
            }
        } catch (e: Throwable) {
            // Ignored if window already detached
        }
    }

    override fun dismiss() {
        if (!isShowing || isDismissing) {
            return
        }
        isDismissing = true
        activeShakeAnim?.cancel()
        activeShakeAnim = null
        iconView.translationX = 0f
        applyCutoutPivot()
        setSystemInfoDisabled(false)

        iconView
            .animate()
            .alpha(0f)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(110)
            .setInterpolator(AccelerateInterpolator())
            .start()

        textView
            .animate()
            .alpha(0f)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(110)
            .setInterpolator(AccelerateInterpolator())
            .start()

        frameView
            .animate()
            .alpha(0f)
            .scaleX(startScaleX)
            .scaleY(startScaleY)
            .setDuration(170)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction {
                isDismissing = false
                try {
                    super.dismiss()
                } catch (e: Throwable) {
                    // Ignored if window already detached
                }
                onDismissRequested?.invoke()
            }
            .start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setSystemInfoDisabled(false)
    }

    override fun onStop() {
        super.onStop()
        setSystemInfoDisabled(false)
    }

    companion object {
        private const val TAG = "AlertSliderIslandDialog"
    }
}
