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

package com.android.server.axdragonite

import android.util.Slog
import com.android.server.axdragonite.model.ClusterInfo
import com.android.server.axdragonite.model.ClusterPreset
import com.android.server.axdragonite.model.ClusterTopologyType
import com.android.server.axdragonite.model.Cpuset
import com.android.server.axdragonite.model.TopologyData
import java.io.File
import kotlin.math.max
import kotlin.math.min

object AxCpuClusterManager {
    private const val TAG = "AxCpuClusterManager"

    const val PATH_CPU_POSSIBLE = "/sys/devices/system/cpu/possible"
    const val PATH_CPU_SYSFS_PREFIX = "/sys/devices/system/cpu/cpu"
    const val PATH_CPUINFO_MAX_FREQ_SUFFIX = "/cpufreq/cpuinfo_max_freq"
    const val RANGE_DELIMITER = "-"

    const val DEFAULT_CORE_COUNT = 8
    const val SINGLE_CLUSTER = 1
    const val DUAL_CLUSTER = 2
    const val TRI_CLUSTER = 3
    const val QUAD_CLUSTER = 4
    const val FALLBACK_FREQ_BASE = 100000L

    const val CORES_LITTLE_4_4 = 4
    const val CORES_LITTLE_6_2 = 6
    const val CORES_LITTLE_3_4_1 = 3
    const val CORES_LITTLE_4_3_1 = 4
    const val CORES_LITTLE_FLAGSHIP_MAX = 2

    const val CORES_UI_4_4 = 4
    const val CORES_REST_BG_4_4 = 3

    const val CORES_UI_6_2 = 5
    const val CORES_REST_BG_6_2 = 2

    const val CORES_MID_3_4_1 = 4
    const val CORES_MID_4_3_1 = 3

    const val CORES_UI_LITTLE_DEFAULT = 2
    const val CORES_UI_BIG_DEFAULT = 2
    const val CORES_REST_BG_BIG_DEFAULT = 1
    const val CORES_DEX2OAT_RESTRICTED = 1
    const val CORES_LITTLE_MIN_DEFAULT = 2
    const val PRIME_CORE_COUNT_SINGULAR = 1
    const val CORES_DIVISOR_QUARTER = 4

    val topology: TopologyData = detectTopology()

    private fun detectTopology(): TopologyData {
        val cores = detectCoreCount()
        val clusters = buildClusters(cores)
        val allMask = clusters.fold(0L) { acc, c -> acc or c.mask }
        val topo = resolveTopology(cores, clusters, allMask)
        logTopology(topo)
        return topo
    }

    private fun buildClusters(cores: Int): List<ClusterInfo> =
        (0 until cores)
            .groupBy(::readCpuMaxFreq)
            .entries
            .sortedBy { it.key }
            .mapIndexed { index, (freq, cpus) ->
                ClusterInfo(index, freq, cpus, cpus.fold(0L) { mask, cpu -> mask or (1L shl cpu) })
            }

    private fun resolvePreset(cores: Int, clusters: List<ClusterInfo>, allMask: Long): ClusterPreset {
        fun c(i: Int): Long = clusters.getOrNull(i)?.mask ?: 0L
        val littleMask = c(0)
        val littleCount = littleMask.countOneBits()

        if (clusters.size <= SINGLE_CLUSTER) {
            val mask = littleMask.takeIf { it != 0L } ?: allMask
            return ClusterPreset.single(allMask, ClusterPreset.takeLowestBits(allMask, max(1, cores / CORES_DIVISOR_QUARTER)), mask)
        }

        if (clusters.size == DUAL_CLUSTER) {
            if (littleCount == CORES_LITTLE_6_2) {
                return ClusterPreset.of(
                    type = ClusterTopologyType.PRESET_DUAL_6_2,
                    smallMask = littleMask,
                    bigMask = 0L,
                    boostMask = c(1),
                    restrictedFgMask = littleMask,
                    uiSmall = CORES_UI_6_2,
                    uiBig = 0,
                    restSmall = CORES_REST_BG_6_2,
                    restBig = 0,
                )
            }
            if (littleCount == CORES_LITTLE_4_4) {
                return ClusterPreset.of(
                    type = ClusterTopologyType.PRESET_DUAL_4_4,
                    smallMask = littleMask,
                    bigMask = 0L,
                    boostMask = allMask and littleMask.inv(),
                    uiSmall = CORES_UI_4_4,
                    uiBig = 0,
                    restSmall = CORES_REST_BG_4_4,
                    restBig = 0,
                )
            }
        }

        if (clusters.size == TRI_CLUSTER) {
            if (littleCount == CORES_LITTLE_3_4_1 && c(1).countOneBits() == CORES_MID_3_4_1) {
                return ClusterPreset.of(
                    type = ClusterTopologyType.PRESET_TRI_3_4_1,
                    smallMask = littleMask,
                    bigMask = c(1),
                    boostMask = allMask and littleMask.inv(),
                    uiSmall = CORES_UI_LITTLE_DEFAULT,
                    uiBig = CORES_UI_BIG_DEFAULT,
                    restSmall = CORES_UI_LITTLE_DEFAULT,
                    restBig = CORES_REST_BG_BIG_DEFAULT,
                )
            }
            if (littleCount == CORES_LITTLE_4_3_1 && c(1).countOneBits() == CORES_MID_4_3_1) {
                return ClusterPreset.of(
                    type = ClusterTopologyType.PRESET_TRI_4_3_1,
                    smallMask = littleMask,
                    bigMask = c(1),
                    boostMask = allMask and littleMask.inv(),
                    uiSmall = CORES_UI_LITTLE_DEFAULT,
                    uiBig = CORES_UI_BIG_DEFAULT,
                    restSmall = CORES_REST_BG_4_4,
                    restBig = 0,
                )
            }
        }

        if (clusters.size >= QUAD_CLUSTER || littleCount <= CORES_LITTLE_FLAGSHIP_MAX) {
            return ClusterPreset.of(
                type = ClusterTopologyType.PRESET_QUAD_2_3_2_1,
                smallMask = c(0),
                bigMask = c(2),
                boostMask = c(1) or c(3),
            )
        }

        val big = allMask and littleMask.inv()
        return ClusterPreset.of(ClusterTopologyType.GENERIC_MULTI_CLUSTER, littleMask, big, big)
    }

    private fun resolveTopology(cores: Int, clusters: List<ClusterInfo>, allMask: Long): TopologyData {
        fun c(i: Int): Long = clusters.getOrNull(i)?.mask ?: 0L
        val preset = resolvePreset(cores, clusters, allMask)
        val fgMask =
            if (clusters.size >= TRI_CLUSTER && clusters.last().mask.countOneBits() == PRIME_CORE_COUNT_SINGULAR) {
                allMask and clusters.last().mask.inv()
            } else {
                allMask
            }

        return TopologyData(
            numCores = cores,
            type = preset.type,
            clusters = clusters,
            all = Cpuset(allMask),
            foreground = Cpuset(fgMask),
            ui = Cpuset(if (preset.uiMask != 0L) preset.uiMask else allMask),
            restrictedBackground = Cpuset(preset.restrictedBgMask),
            restrictedDex2oat = Cpuset(ClusterPreset.takeLowestBits(c(0), CORES_DEX2OAT_RESTRICTED)),
            littleMin = Cpuset(ClusterPreset.takeLowestBits(c(0), max(1, min(CORES_LITTLE_MIN_DEFAULT, cores / CORES_DIVISOR_QUARTER)))),
            boost = Cpuset(preset.boostMask),
            restrictedForeground = preset.restrictedFgMask?.let(::Cpuset),
        )
    }

    private fun logTopology(t: TopologyData) {
        Slog.i(
            TAG,
            "CPU Topology detected: ${t.numCores} cores, ${t.clusters.size} clusters, preset: ${t.type}. " +
                "Boost: 0x${t.boost.mask.toString(16)}, " +
                "Ui: 0x${t.ui.mask.toString(16)}, " +
                "RestrictedBg: 0x${t.restrictedBackground.mask.toString(16)}",
        )
    }

    private fun detectCoreCount(): Int {
        val runtimeCores = Runtime.getRuntime().availableProcessors().takeIf { it > 0 } ?: DEFAULT_CORE_COUNT
        val line = readFirstLine(File(PATH_CPU_POSSIBLE)) ?: return runtimeCores
        if (!line.contains(RANGE_DELIMITER)) return runtimeCores
        return runCatching { line.trim().substringAfter(RANGE_DELIMITER).toInt() + 1 }.getOrDefault(runtimeCores)
    }

    private fun readCpuMaxFreq(cpu: Int): Long =
        readFirstLine(File("$PATH_CPU_SYSFS_PREFIX$cpu$PATH_CPUINFO_MAX_FREQ_SUFFIX"))
            ?.trim()
            ?.toLongOrNull()
            ?: ((cpu + 1) * FALLBACK_FREQ_BASE)

    private fun readFirstLine(file: File): String? =
        runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()

    fun getLowestNBits(mask: Long, count: Int): Long =
        ClusterPreset.takeLowestBits(mask, count)
}
