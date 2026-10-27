/*
 * Copyright 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.ax.boost

import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.SystemProperties
import android.util.Slog
import com.android.internal.dragonite.AxDragoniteConstants
import com.android.internal.dragonite.AxDragoniteInternal
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@SysUISingleton
class AxBoosterController @Inject constructor(@Main private val mainHandler: Handler) {
    companion object {
        private const val TAG = "AxBoosterController"
        private val DEBUG = SystemProperties.getBoolean("persist.dragonite.debug", false)

        const val BOOST_SCENE_ID_NOTIFICATION_EXPAND =
            AxDragoniteConstants.SCENE_NOTIFICATION_EXPAND
        const val BOOST_SCENE_ID_UNLOCK = AxDragoniteConstants.SCENE_UNLOCK
        const val BOOST_SCENE_ID_ANIMATION = AxDragoniteConstants.SCENE_SYSTEMUI_ANIMATION

        private const val DURATION_UNLOCK_MS = 5000
        private const val DURATION_ANIMATION_MS = 5000
        private const val KEYGUARD_GONE_RELEASE_DELAY_MS = 800L
        private const val PACKAGE_SYSTEMUI = "com.android.systemui"
        private const val SHADE_REASON_TRACKING = "tracking"
        private const val SHADE_REASON_EXPANDING = "expanding"
    }

    private val isBoostingMap = ConcurrentHashMap<Int, Boolean>()
    private val boostHandleMap = ConcurrentHashMap<Int, Int>()
    private val timeoutRunnables =
        ConcurrentHashMap<Int, Runnable>()
    private val releaseKeyguardGoneRunnable = Runnable { releaseUnlockBoost() }

    private fun scheduleSceneTimeout(sceneId: Int, durationMs: Long) {
        cancelSceneTimeout(sceneId)
        val runnable = Runnable { setBoosting(sceneId, false, "timeout") }
        timeoutRunnables[sceneId] = runnable
        mainHandler.postDelayed(runnable, durationMs)
    }

    private fun cancelSceneTimeout(sceneId: Int) {
        timeoutRunnables.remove(sceneId)?.let { mainHandler.removeCallbacks(it) }
    }
    private fun getDataBundle(sceneId: Int): Bundle {
        return Bundle().apply {
            putInt(AxDragoniteConstants.KEY_PID, Process.myPid())
            putString(AxDragoniteConstants.KEY_PACKAGE, PACKAGE_SYSTEMUI)
            putString(AxDragoniteConstants.KEY_PACKAGE_NAME, PACKAGE_SYSTEMUI)
            when (sceneId) {
                BOOST_SCENE_ID_NOTIFICATION_EXPAND,
                BOOST_SCENE_ID_UNLOCK ->
                    putInt(AxDragoniteConstants.KEY_DURATION, DURATION_UNLOCK_MS)
                BOOST_SCENE_ID_ANIMATION -> {
                    boostHandleMap[BOOST_SCENE_ID_ANIMATION]
                        ?.takeIf { it >= 0 }
                        ?.let { putInt(AxDragoniteConstants.KEY_HANDLE, it) }
                    putInt(AxDragoniteConstants.KEY_DURATION, DURATION_ANIMATION_MS)
                }
            }
        }
    }

    private val shadeReasons = HashSet<String>()

    private fun setShadeBoost(reason: String, boosting: Boolean) =
        synchronized(shadeReasons) {
            if (boosting) shadeReasons.add(reason) else shadeReasons.remove(reason)
            setBoosting(
                BOOST_SCENE_ID_NOTIFICATION_EXPAND,
                shadeReasons.isNotEmpty(),
                "shade:$reason",
            )
        }

    fun isBoosting(sceneId: Int): Boolean = isBoostingMap[sceneId] == true

    private fun setBoosting(
        sceneId: Int,
        boosting: Boolean,
        reason: String = "",
        force: Boolean = false,
    ) {
        if (isBoosting(sceneId) == boosting && !force) {
            if (DEBUG) {
                Slog.d(TAG, "skip setBoosting: id=$sceneId, boosting=$boosting, reason=$reason")
            }
            return
        }
        isBoostingMap[sceneId] = boosting
        if (boosting) sceneBoostAcquire(sceneId) else sceneBoostRelease(sceneId)
    }

    private fun sceneBoostAcquire(sceneId: Int) {
        try {
            val bundle = getDataBundle(sceneId)
            val durationMs =
                bundle.getInt(AxDragoniteConstants.KEY_DURATION, DURATION_ANIMATION_MS).toLong()
            val newHandle = AxDragoniteInternal.sceneBoostAcquire(sceneId, bundle)
            if (newHandle >= 0) {
                val oldHandle = boostHandleMap.put(sceneId, newHandle)
                if (durationMs > 0L) {
                    scheduleSceneTimeout(sceneId, durationMs)
                }
                if (DEBUG) Slog.d(TAG, "sceneBoostAcquire id=$sceneId new=$newHandle old=$oldHandle")
            } else {
                cancelSceneTimeout(sceneId)
                isBoostingMap[sceneId] = false
            }
        } catch (e: Exception) {
            Slog.w(TAG, "acquireBoost() id=$sceneId Exception: ${e.message}")
            isBoostingMap[sceneId] = false
        }
    }

    private fun sceneBoostRelease(sceneId: Int) {
        cancelSceneTimeout(sceneId)
        try {
            val handle = boostHandleMap.remove(sceneId) ?: return
            AxDragoniteInternal.sceneBoostRelease(handle)
            if (DEBUG) Slog.d(TAG, "sceneBoostRelease id=$sceneId handle=$handle")
        } catch (e: Exception) {
            Slog.w(TAG, "sceneBoostRelease id=$sceneId Exception: ${e.message}")
            boostHandleMap.remove(sceneId)
        }
    }

    fun acquireNotificationExpandBoost() =
        setBoosting(BOOST_SCENE_ID_NOTIFICATION_EXPAND, true, "acquireNotificationExpandBoost")

    fun releaseNotificationExpandBoost() =
        setBoosting(BOOST_SCENE_ID_NOTIFICATION_EXPAND, false, "releaseNotificationExpandBoost")

    private fun unscheduleUnlockRelease() = mainHandler.removeCallbacks(releaseKeyguardGoneRunnable)

    fun acquireUnlockBoost() {
        unscheduleUnlockRelease()
        setBoosting(BOOST_SCENE_ID_UNLOCK, true, "acquireUnlockBoost")
    }

    fun releaseUnlockBoost() {
        unscheduleUnlockRelease()
        setBoosting(BOOST_SCENE_ID_UNLOCK, false, "releaseUnlockBoost")
    }

    fun releaseKeyguardGoneAnimationBoost() {
        unscheduleUnlockRelease()
        mainHandler.postDelayed(releaseKeyguardGoneRunnable, KEYGUARD_GONE_RELEASE_DELAY_MS)
    }

    fun acquireAnimationBoost() {
        if (isBoosting(BOOST_SCENE_ID_NOTIFICATION_EXPAND) || isBoosting(BOOST_SCENE_ID_UNLOCK)) {
            if (DEBUG) Slog.d(TAG, "acquireAnimationBoost skip by active expand/unlock")
            return
        }
        setBoosting(BOOST_SCENE_ID_ANIMATION, true, "acquireAnimationBoost", true)
    }

    fun releaseAnimationBoost() =
        setBoosting(BOOST_SCENE_ID_ANIMATION, false, "releaseAnimationBoost")

    fun acquireNotificationStackBoost() = acquireAnimationBoost()

    fun releaseNotificationStackBoost() = releaseAnimationBoost()

    fun acquireNPVFlingBoost() = acquireAnimationBoost()

    fun releaseNPVFlingBoost() = releaseAnimationBoost()

    fun acquireNPVTrackingBoost() = setShadeBoost(SHADE_REASON_TRACKING, true)

    fun releaseNPVTrackingBoost() = setShadeBoost(SHADE_REASON_TRACKING, false)

    fun acquireSwipeDownNotificationBoost() = acquireAnimationBoost()

    fun releaseSwipeDownNotificationBoost() = releaseAnimationBoost()

    fun acquireSpeedUpNPVExpanded() = acquireAnimationBoost()

    fun releaseSpeedUpNPVExpanded() = releaseAnimationBoost()

    fun acquireNPVExpandingBoost() = setShadeBoost(SHADE_REASON_EXPANDING, true)

    fun releaseNPVExpandingBoost() = setShadeBoost(SHADE_REASON_EXPANDING, false)

    fun acquireExpansionAnimationBoost() = acquireAnimationBoost()

    fun releaseExpansionAnimationBoost() = releaseAnimationBoost()

    fun acquireUnlockAnimationBoost() = acquireUnlockBoost()

    fun acquireRippleAnimationBoost() = acquireAnimationBoost()

    fun releaseRippleAnimationBoost() = releaseAnimationBoost()

    fun acquireUnlockedScreenAnimationOffBoost() = acquireAnimationBoost()

    fun releaseUnlockedScreenAnimationOffBoost() = releaseAnimationBoost()

    fun acquireDozeAnimationBoost() = acquireAnimationBoost()

    fun releaseDozeAnimationBoost() = releaseAnimationBoost()

    fun acquireBouncerDisappearBoost() = acquireAnimationBoost()

    fun releaseBouncerDisappearBoost() = releaseAnimationBoost()

    fun acquireVolumeDialogShowBoost() = acquireAnimationBoost()

    fun releaseVolumeDialogShowBoost() = releaseAnimationBoost()

    fun acquireBPDialogShowBoost() = acquireAnimationBoost()

    fun releaseBPDialogShowBoost() = releaseAnimationBoost()
}
