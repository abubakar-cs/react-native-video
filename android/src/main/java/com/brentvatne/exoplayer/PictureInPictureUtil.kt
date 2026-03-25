package com.brentvatne.exoplayer

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.util.Rational
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.app.AppOpsManagerCompat
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.brentvatne.common.toolbox.DebugLog
import com.brentvatne.receiver.PictureInPictureReceiver
import com.facebook.react.uimanager.ThemedReactContext

internal fun Context.findActivity(): ComponentActivity {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    throw IllegalStateException("Picture in picture should be called in the context of an Activity")
}

object PictureInPictureUtil {
    private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000
    private const val TAG = "PictureInPictureUtil"
    /** Assumed height of 3-button navigation bar (dp). When gesture nav is on, we subtract this from screen height so PIP matches button-nav behavior. */
    private const val ASSUMED_BUTTON_NAV_BAR_HEIGHT_DP = 48f

    @JvmStatic
    fun addLifecycleEventListener(context: ThemedReactContext, view: ReactExoplayerView): Runnable {
        val activity = context.findActivity()

        val onPictureInPictureModeChanged = Consumer<PictureInPictureModeChangedInfo> { info ->
            view.setIsInPictureInPicture(info.isInPictureInPictureMode)
            // Android 12+: pinch-zoom / drag resize often delivers a non-null newConfig while still in PiP.
            if (info.isInPictureInPictureMode &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                info.newConfig != null
            ) {
                view.onPictureInPictureWindowSizedForBounce()
            }
            if (!info.isInPictureInPictureMode && activity.lifecycle.currentState == Lifecycle.State.CREATED) {
                // when user click close button of PIP
                if (!view.playInBackground) view.setPausedModifier(true)
            }
        }

        val onUserLeaveHintCallback = Runnable {
            if (view.enterPictureInPictureOnLeave) {
                view.enterPictureInPictureMode()
            }
        }

        activity.addOnPictureInPictureModeChangedListener(onPictureInPictureModeChanged)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            activity.addOnUserLeaveHintListener(onUserLeaveHintCallback)
        }

        // @TODO convert to lambda when ReactExoplayerView migrated
        return Runnable {
            with(activity) {
                removeOnPictureInPictureModeChangedListener(onPictureInPictureModeChanged)
                removeOnUserLeaveHintListener(onUserLeaveHintCallback)
            }
        }
    }

    @JvmStatic
    fun enterPictureInPictureMode(context: ThemedReactContext, pictureInPictureParams: PictureInPictureParams?) {
        if (!isSupportPictureInPicture(context)) return
        if (isSupportPictureInPictureAction() && pictureInPictureParams != null) {
            try {
                context.findActivity().enterPictureInPictureMode(pictureInPictureParams)
            } catch (e: IllegalStateException) {
                DebugLog.e(TAG, e.toString())
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                @Suppress("DEPRECATION")
                context.findActivity().enterPictureInPictureMode()
            } catch (e: IllegalStateException) {
                DebugLog.e(TAG, e.toString())
            }
        }
    }

    @JvmStatic
    fun applyPlayingStatus(
        context: ThemedReactContext,
        pipParamsBuilder: PictureInPictureParams.Builder?,
        receiver: PictureInPictureReceiver,
        isPaused: Boolean
    ) {
        if (pipParamsBuilder == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val actions = getPictureInPictureActions(context, isPaused, receiver)
        pipParamsBuilder.setActions(actions)
        updatePictureInPictureActions(context, pipParamsBuilder.build())
    }

    @JvmStatic
    fun applyAutoEnterEnabled(context: ThemedReactContext, pipParamsBuilder: PictureInPictureParams.Builder?, autoEnterEnabled: Boolean) {
        if (pipParamsBuilder == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        pipParamsBuilder.setAutoEnterEnabled(autoEnterEnabled)
        updatePictureInPictureActions(context, pipParamsBuilder.build())
    }

    @JvmStatic
    fun applySourceRectHint(context: ThemedReactContext, pipParamsBuilder: PictureInPictureParams.Builder?, playerView: ExoPlayerView) {
        applySourceRectHint(context, pipParamsBuilder, playerView, true)
    }

    /**
     * Updates source rect hint on the builder. When [pushToActivity] is true, applies params to the activity
     * (used for auto-enter PiP). Feed/list layouts must not push unless this is the actively playing cell,
     * or the last layout "wins" with a wrong rect / aspect → landscape-ish PiP and zoom for vertical video.
     */
    @JvmStatic
    fun applySourceRectHint(
        context: ThemedReactContext,
        pipParamsBuilder: PictureInPictureParams.Builder?,
        playerView: ExoPlayerView,
        pushToActivity: Boolean
    ) {
        if (pipParamsBuilder == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        pipParamsBuilder.setSourceRectHint(calcRectHint(playerView))
        if (pushToActivity) {
            updatePictureInPictureActions(context, pipParamsBuilder.build())
        }
    }

    @JvmStatic
    fun updatePictureInPictureActions(context: ThemedReactContext, pipParams: PictureInPictureParams) {
        if (!isSupportPictureInPictureAction()) return
        if (!isSupportPictureInPicture(context)) return
        try {
            context.findActivity().setPictureInPictureParams(pipParams)
        } catch (e: IllegalStateException) {
            DebugLog.e(TAG, e.toString())
        }
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun getPictureInPictureActions(context: ThemedReactContext, isPaused: Boolean, receiver: PictureInPictureReceiver): ArrayList<RemoteAction> {
        val intent = receiver.getPipActionIntent(isPaused)
        val resource =
            if (isPaused) androidx.media3.ui.R.drawable.exo_icon_play else androidx.media3.ui.R.drawable.exo_icon_pause
        val icon = Icon.createWithResource(context, resource)
        val title = if (isPaused) "play" else "pause"
        return arrayListOf(RemoteAction(icon, title, title, intent))
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    private fun calcRectHint(playerView: ExoPlayerView): Rect {
        val hint = Rect()
        // Use the PlayerView itself since surfaceView is private
        playerView.getGlobalVisibleRect(hint)
        val location = IntArray(2)
        playerView.getLocationOnScreen(location)

        val height = hint.bottom - hint.top
        hint.top = location[1]
        hint.bottom = hint.top + height
        return hint
    }

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun calcPictureInPictureAspectRatio(player: ExoPlayer): Rational? =
        calcPictureInPictureAspectRatio(null, player)

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun calcPictureInPictureAspectRatio(context: ThemedReactContext?, player: ExoPlayer): Rational? {
        val videoSize = player.videoSize
        var width = videoSize.width
        var height = videoSize.height

        // 🚨 INVALID STATES
        if (width <= 0 || height <= 0) return null

        // Use display dimensions: swap when rotation is 90° or 270° so PIP window matches rendered orientation
        val rotation = videoSize.unappliedRotationDegrees
        if (rotation == 90 || rotation == 270) {
            val tmp = width
            width = height
            height = tmp
        }

        // Account for non-square pixels (anamorphic)
        val pixelRatio = videoSize.pixelWidthHeightRatio
        val displayWidth = width * pixelRatio
        val displayHeight = height.toFloat()
        var ratio = displayWidth / displayHeight

        // With gesture navigation, PIP gets more height than with button nav, then adjusts → video looks zoomed.
        // When gesture nav is on: subtract assumed button nav bar height from screen height so PIP is sized like with button nav.
        var usedAdjustedRatio = false
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val activity = context.findActivity()
                val decorView = activity.window?.decorView
                val insets = decorView?.rootWindowInsets
                if (insets != null) {
                    val gestureLeft: Int
                    val gestureRight: Int
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val gesture = insets.getInsets(WindowInsets.Type.systemGestures())
                        gestureLeft = gesture.left
                        gestureRight = gesture.right
                    } else {
                        @Suppress("DEPRECATION")
                        val gesture = insets.systemGestureInsets
                        gestureLeft = gesture.left
                        gestureRight = gesture.right
                    }
                    val isGestureNav = gestureLeft > 0 || gestureRight > 0
                    if (isGestureNav) {
                        val screenHeight = decorView.height.coerceAtLeast(1)
                        val assumedNavBarPx = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            ASSUMED_BUTTON_NAV_BAR_HEIGHT_DP,
                            context.resources.displayMetrics
                        ).toInt()
                        val effectiveHeight = (screenHeight - assumedNavBarPx).coerceAtLeast(1)
                        if (effectiveHeight < screenHeight) {
                            ratio = ratio * screenHeight.toFloat() / effectiveHeight
                            usedAdjustedRatio = true
                        }
                    }
                }
            } catch (_: Exception) { /* use unadjusted ratio */ }
        }

        // Android PIP limits (system will reject outside this range)
        val MAX = 2.39f
        val MIN = 1f / 2.39f
        ratio = ratio.coerceIn(MIN, MAX)

        // Prefer exact Rational for common ratios only when we didn't apply gesture adjustment
        if (!usedAdjustedRatio) {
            val exact = toExactRational(ratio)
            if (exact != null) return exact
        }

        // Fallback: use Rational with good precision
        val num = (ratio * 1000).toInt().coerceIn(1, 2390)
        return Rational(num, 1000)
    }

    /**
     * When [ExoPlayer.videoSize] is not ready yet, derive PiP aspect ratio from the player view bounds
     * (portrait full-bleed feed → portrait PiP window).
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.O)
    fun aspectRatioFromViewDimensions(view: View): Rational? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        var ratio = w.toFloat() / h.toFloat()
        val MAX = 2.39f
        val MIN = 1f / 2.39f
        ratio = ratio.coerceIn(MIN, MAX)
        val exact = toExactRational(ratio)
        if (exact != null) return exact
        val num = (ratio * 1000).toInt().coerceIn(1, 2390)
        return Rational(num, 1000)
    }

    /**
     * Return an exact Rational for common aspect ratios to avoid floating-point drift
     * that can make the PIP window not match the video and cause zoomed/cropped appearance.
     */
    @JvmStatic
    private fun toExactRational(ratio: Float): Rational? {
        return when {
            ratio >= 0.55f && ratio <= 0.57f -> Rational(9, 16)  // 9:16 portrait
            ratio >= 1.32f && ratio <= 1.35f -> Rational(4, 3)   // 4:3
            ratio >= 0.74f && ratio <= 0.76f -> Rational(3, 4)   // 3:4 portrait
            ratio >= 0.98f && ratio <= 1.02f -> Rational(1, 1)   // 1:1
            else -> null
        }
    }

    private fun isSupportPictureInPicture(context: ThemedReactContext): Boolean =
        checkIsApiSupport() && checkIsSystemSupportPIP(context) && checkIsUserAllowPIP(context)

    private fun isSupportPictureInPictureAction(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.N)
    private fun checkIsApiSupport(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    @RequiresApi(Build.VERSION_CODES.N)
    private fun checkIsSystemSupportPIP(context: ThemedReactContext): Boolean {
        val activity = context.findActivity()

        val isActivitySupportPip = try {
            val activityInfo = activity.packageManager.getActivityInfo(activity.componentName, PackageManager.GET_META_DATA)
            // detect current activity's android:supportsPictureInPicture value defined within AndroidManifest.xml
            // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/ActivityInfo.java;l=1090-1093;drc=7651f0a4c059a98f32b0ba30cd64500bf135385f
            activityInfo.flags and FLAG_SUPPORTS_PICTURE_IN_PICTURE != 0
        } catch (e: kotlin.Exception) {
            false
        }

        // PIP might be disabled on devices that have low RAM.
        val isPipAvailable = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        return isActivitySupportPip && isPipAvailable
    }

    private fun checkIsUserAllowPIP(context: ThemedReactContext): Boolean {
        val activity = context.currentActivity ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @SuppressLint("InlinedApi")
            val result = AppOpsManagerCompat.noteOpNoThrow(
                activity,
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                Process.myUid(),
                activity.packageName
            )
            AppOpsManager.MODE_ALLOWED == result
        } else {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        }
    }
}
