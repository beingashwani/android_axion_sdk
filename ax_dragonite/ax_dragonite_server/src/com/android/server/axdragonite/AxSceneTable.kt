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

import android.os.PowerManagerInternal as PM
import android.util.Slog
import com.android.internal.dragonite.AxDragoniteConstants
import com.android.server.axdragonite.model.ClusterTopologyType
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.atomic.AtomicReference

object AxSceneTable {
    private const val TAG = "AxSceneTable"
    const val KEY_PARAMS1 = "params1"
    private const val PARAM_VALUE_DISABLED = "0"
    const val UNKNOWN_PACKAGE = "unknown"
    private const val DURATION_BG_FREEZE_MS = 500L
    private const val DURATION_TA_HOLD_MS = 1000L
    private const val DURATION_TA_DELAY_MS = 50L
    private const val UCLAMP_MIN_APP_START_6_2 = "80"
    private const val UCLAMP_MIN_OVERLOAD_6_2 = "100"

    private const val SCENE_NAME_APP_START_EXIT = "APP_START_EXIT_ANIMATION"
    private const val SCENE_NAME_LAUNCHER_NORMAL = "LAUNCHER_NORMAL_ANIMATION"
    private const val SCENE_NAME_LAUNCHER_ITEM = "LAUNCHER_ITEM_LOADING"
    private const val SCENE_NAME_LAUNCHER_OVERLOAD = "LAUNCHER_OVERLOAD_ANIMATION"
    private const val SCENE_NAME_LAUNCHER_SCREEN_ON = "LAUNCHER_SCREEN_ON"
    private const val SCENE_NAME_QS_PULL_DOWN = "QS_PULL_DOWN"
    private const val SCENE_NAME_UNLOCK = "UNLOCK_ANIMATION"
    private const val SCENE_NAME_SYSTEMUI_NORMAL = "SYSTEMUI_NORMAL_ANIMATION"
    private const val SCENE_NAME_GAME_1 = "GAME1"
    private const val SCENE_NAME_GAME_2 = "GAME2"
    private const val SCENE_NAME_BENCH_GPU = "bench_gpu"
    private const val SCENE_NAME_BENCH_CPU = "bench_cpu"
    private const val SCENE_NAME_FLING_LEVEL_1 = "fling_level_1"
    private const val SCENE_NAME_DISABLE_INPUT_BOOST = "DISABLE_INPUT_BOOST"
    private const val SCENE_NAME_TEST = "test"

    const val RESUMED_PID_INDEX = 0
    const val RESUMED_PACKAGE_INDEX = 1

    const val NAME_APP_START = "app_start"
    const val NAME_ANIMATION = "animation"
    const val NAME_SYSTEMUI = "systemui"
    const val NAME_FLING = "fling"
    const val NAME_BENCH_CPU = "bench_cpu"
    const val NAME_BENCH_GPU = "bench_gpu"

    data class Scenario(
        val scenarioId: Int,
        val scenarioName: String,
        val commandList: MutableList<ScenarioCommand> = ArrayList(),
    ) {
        fun addCommand(command: ScenarioCommand) {
            commandList.add(command)
        }
    }

    internal data class CommandConfig(
        val holdTime: Long = -1L,
        val delayTime: Long = -1L,
        val targetProcess: String? = null,
        val restoreTag: String = "",
    )

    class ScenarioCommand
    internal constructor(
        val command: AxCmdTable.Command?,
        initialConfig: CommandConfig = CommandConfig(),
    ) {
        private val config = AtomicReference(initialConfig)
        private val parameterMap = HashMap<String, String>()

        val holdTime: Long
            get() = config.get().holdTime

        val delayTime: Long
            get() = config.get().delayTime

        val restoreTag: String
            get() = config.get().restoreTag

        fun setHoldTime(time: Long) {
            config.updateAndGet { it.copy(holdTime = time) }
        }

        fun setDelayTime(time: Long) {
            config.updateAndGet { it.copy(delayTime = time) }
        }

        fun getProcess(): String? = config.get().targetProcess

        fun putParameter(key: String, value: String) {
            parameterMap[key] = value
        }

        fun getParameter(key: String): String? = parameterMap[key]

        fun getParameterMap(): Map<String, String> = Collections.unmodifiableMap(parameterMap)
    }

    private fun Scenario.command(
        commandName: String,
        params1: String? = null,
        holdTime: Long = -1L,
        delayTime: Long = -1L,
        restore: String = "",
        process: String? = null,
    ): ScenarioCommand =
        ScenarioCommand(
            command = AxCmdTable.getCommand(commandName),
            initialConfig = CommandConfig(holdTime, delayTime, process, restore),
        ).apply {
            if (params1 != null) putParameter(KEY_PARAMS1, params1)
            commandList.add(this)
        }

    private val scenarioMap = HashMap<String, Scenario>()

    init {
        initializeScenarios()
    }

    private fun registerScenario(scenario: Scenario) {
        scenarioMap[scenario.scenarioId.toString()] = scenario
    }

    private fun initializeScenarios() {
        initAppScenarios()
        initSystemUiScenarios()
        initGameBenchScenarios()
        initMiscScenarios()
    }

    private fun initAppScenarios() {
        val topo = AxCpuClusterManager.topology
        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_APP_START, SCENE_NAME_APP_START_EXIT).apply {
                if (topo.type == ClusterTopologyType.PRESET_DUAL_6_2) {
                    command(
                        AxCmdTable.NAME_CMD_CPUSET_FG_CPUS,
                        params1 = AxCpuSets.CPUSET_RESTRICTED_FG,
                        holdTime = DURATION_BG_FREEZE_MS,
                    )
                }
                command(
                    AxCmdTable.NAME_CMD_CPUSET_BG_CPUS,
                    params1 = AxCpuSets.CPUSET_RESTRICTED_BG,
                )
                if (topo.type == ClusterTopologyType.PRESET_DUAL_6_2) {
                    command(AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_PROCS)
                    command(
                        AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_UCLAMP_MIN,
                        params1 = UCLAMP_MIN_APP_START_6_2,
                    )
                } else {
                    command(
                        AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_PROCS,
                        restore = AxPlatformConfig.GROUP_ROOT,
                    )
                }
                command(AxCmdTable.NAME_CMD_ANIMATION_BOOST)
                command(AxCmdTable.NAME_CMD_PROCESS_AFFINITY, params1 = AxCpuSets.CPUSET_BOOST)
                command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_APP_START)
                command(
                    AxCmdTable.NAME_CMD_CPUSET_CAMERA_DAEMON_CPUS,
                    params1 = AxCpuSets.CPUSET_LITTLE_MIN,
                    holdTime = DURATION_BG_FREEZE_MS,
                )
                command(AxCmdTable.NAME_CMD_BACKGROUND_FREEZE, holdTime = DURATION_BG_FREEZE_MS)
            }
        )

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_LAUNCHER_ANIMATION, SCENE_NAME_LAUNCHER_NORMAL)
                .apply {
                    command(AxCmdTable.NAME_CMD_ANIMATION_BOOST)
                    command(AxCmdTable.NAME_CMD_PROCESS_AFFINITY, params1 = AxCpuSets.CPUSET_BOOST)
                    command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_ANIMATION)
                    command(
                        AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_PROCS,
                        restore = AxPlatformConfig.GROUP_ROOT,
                    )
                }
        )

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_LAUNCHER_ITEM_LOADING, SCENE_NAME_LAUNCHER_ITEM)
                .apply { command(AxCmdTable.NAME_CMD_THREAD_BOOST) }
        )

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_LAUNCHER_OVERLOAD, SCENE_NAME_LAUNCHER_OVERLOAD)
                .apply {
                    command(AxCmdTable.NAME_CMD_ANIMATION_BOOST)
                    command(AxCmdTable.NAME_CMD_PROCESS_AFFINITY, params1 = AxCpuSets.CPUSET_BOOST)
                    command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_ANIMATION)
                    command(
                        AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_PROCS,
                        restore = AxPlatformConfig.GROUP_ROOT,
                    )
                    if (topo.type == ClusterTopologyType.PRESET_DUAL_6_2) {
                        command(
                            AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_UCLAMP_MIN,
                            params1 = UCLAMP_MIN_OVERLOAD_6_2,
                        )
                    }
                }
        )

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_LAUNCHER_SCREEN_ON, SCENE_NAME_LAUNCHER_SCREEN_ON)
                .apply {
                    command(AxCmdTable.NAME_CMD_ANIMATION_BOOST)
                    command(
                        AxCmdTable.NAME_CMD_ANIMATION_BOOST,
                        process = AxDragoniteConstants.PKG_SYSTEMUI,
                    )
                    command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_ANIMATION)
                    command(AxCmdTable.NAME_CMD_PROCESS_AFFINITY, params1 = AxCpuSets.CPUSET_BOOST)
                    command(
                        AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_PROCS,
                        restore = AxPlatformConfig.GROUP_ROOT,
                    )
                }
        )
    }

    private fun initSystemUiScenarios() {
        val topo = AxCpuClusterManager.topology
        val qsHoldTime = -1L
        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_QS_PULL_DOWN, SCENE_NAME_QS_PULL_DOWN).apply {
                command(
                    AxCmdTable.NAME_CMD_CPUSET_TA_CPUS,
                    params1 = AxCpuSets.CPUSET_UI,
                    delayTime = DURATION_TA_DELAY_MS,
                    holdTime = qsHoldTime,
                )
                command(AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_PROCS)
                command(AxCmdTable.NAME_CMD_ANIMATION_BOOST)
                command(
                    AxCmdTable.NAME_CMD_PROCESS_AFFINITY,
                    params1 = AxCpuSets.CPUSET_BOOST,
                    process = AxDragoniteConstants.PKG_SYSTEMUI,
                )
                command(
                    AxCmdTable.NAME_CMD_CPUSET_CAMERA_DAEMON_CPUS,
                    params1 = AxCpuSets.CPUSET_LITTLE_MIN,
                )
                command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_SYSTEMUI)
            }
        )

        val unlockAffinity =
            if (topo.type == ClusterTopologyType.PRESET_DUAL_6_2) AxCpuSets.CPUSET_RESTRICTED_FG
            else AxCpuSets.CPUSET_BOOST
        val unlockHoldTime = if (topo.type == ClusterTopologyType.PRESET_DUAL_6_2) -1L else DURATION_TA_HOLD_MS
        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_UNLOCK, SCENE_NAME_UNLOCK).apply {
                command(AxCmdTable.NAME_CMD_PROCESS_AFFINITY, params1 = unlockAffinity)
                command(AxCmdTable.NAME_CMD_ANIMATION_BOOST)
                command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_ANIMATION)
                command(AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_PROCS)
                command(
                    AxCmdTable.NAME_CMD_CPUSET_CAMERA_DAEMON_CPUS,
                    params1 = AxCpuSets.CPUSET_LITTLE_MIN,
                )
                command(
                    AxCmdTable.NAME_CMD_CPUSET_TA_CPUS,
                    params1 = AxCpuSets.CPUSET_UI,
                    holdTime = unlockHoldTime,
                )
            }
        )

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_SYSTEMUI_ANIMATION, SCENE_NAME_SYSTEMUI_NORMAL)
                .apply {
                    command(
                        AxCmdTable.NAME_CMD_ANIMATION_BOOST,
                        process = AxDragoniteConstants.PKG_SYSTEMUI,
                    )
                    command(
                        AxCmdTable.NAME_CMD_PROCESS_AFFINITY,
                        params1 = AxCpuSets.CPUSET_BOOST,
                        process = AxDragoniteConstants.PKG_SYSTEMUI,
                    )
                    command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_SYSTEMUI)
                    command(AxCmdTable.NAME_CMD_CPUCTL_RESTRICTED_PROCS)
                }
        )
    }

    private fun initGameBenchScenarios() {
        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_GAME_1, SCENE_NAME_GAME_1).apply {
                command(AxCmdTable.NAME_CMD_AX_NAMED_THREAD_AFFINITY)
            }
        )

        registerScenario(Scenario(AxDragoniteConstants.SCENE_GAME_2, SCENE_NAME_GAME_2))

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_BENCH_GPU, SCENE_NAME_BENCH_GPU).apply {
                command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_BENCH_GPU)
            }
        )

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_BENCH_CPU, SCENE_NAME_BENCH_CPU).apply {
                command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_BENCH_CPU)
            }
        )
    }

    private fun initMiscScenarios() {
        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_FLING_LEVEL_1, SCENE_NAME_FLING_LEVEL_1).apply {
                command(AxCmdTable.NAME_CMD_PLATFORM_CONTROL, params1 = NAME_FLING)
                command(AxCmdTable.NAME_CMD_ANIMATION_BOOST)
                command(AxCmdTable.NAME_CMD_PROCESS_AFFINITY, params1 = AxCpuSets.CPUSET_BOOST)
                command(AxCmdTable.NAME_CMD_BACKGROUND_LOAD_LIMIT)
            }
        )

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_DISABLE_INPUT_BOOST, SCENE_NAME_DISABLE_INPUT_BOOST)
                .apply {
                    command(
                        AxCmdTable.NAME_CMD_BACKGROUND_LOAD_LIMIT,
                        params1 = PARAM_VALUE_DISABLED,
                    )
                }
        )

        registerScenario(
            Scenario(AxDragoniteConstants.SCENE_TEST, SCENE_NAME_TEST).apply {
                command(
                    AxCmdTable.NAME_CMD_CPUSET_BG_CPUS,
                    params1 = AxCpuSets.CPUSET_LITTLE_MIN,
                    holdTime = DURATION_BG_FREEZE_MS,
                )
            }
        )
    }

    private fun parseResumedField(params: String?, cmd: ScenarioCommand?, index: Int): String? {
        if (params.isNullOrEmpty() || cmd == null) return null
        val userParams =
            AxPlatformConfig.paramsFromUser(params, cmd.command?.id ?: return null) ?: return null
        val segments = userParams.split(AxCommandExec.COMMA)
        if (segments.size != 2) return null
        return segments[index].split(AxCommandExec.SEMICOLON)[0]
    }

    @JvmStatic
    fun getResumedPackageName(params: String?, cmd: ScenarioCommand?): String {
        parseResumedField(params, cmd, RESUMED_PACKAGE_INDEX)
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                return it
            }
        Slog.d(TAG, "failed to parse resumed app package name")
        return UNKNOWN_PACKAGE
    }

    @JvmStatic
    fun getResumedAppPid(params: String?, cmd: ScenarioCommand?): Int {
        val pid = parseResumedField(params, cmd, RESUMED_PID_INDEX)
        if (pid != null) {
            pid.toIntOrNull()?.let {
                return it
            }
            Slog.d(TAG, "failed to parse resumed app pid")
        }
        Slog.d(TAG, "get unknown resumed app pid = $pid")
        return -1
    }

    @JvmStatic fun getScenario(id: Int): Scenario? = scenarioMap[id.toString()]

    @JvmStatic fun getAllScenarios(): Collection<Scenario> = scenarioMap.values

    data class PowerHints(
        val axPowerMode: Int,
        val fallbackBoosts: List<Int> = emptyList(),
        val fallbackModes: List<Int> = emptyList(),
    )

    private val powerHints =
        mapOf(
            NAME_APP_START to
                PowerHints(
                    PM.MODE_APP_START,
                    listOf(PM.BOOST_INTERACTION),
                    listOf(PM.MODE_LAUNCH, PM.MODE_EXPENSIVE_RENDERING),
                ),
            NAME_ANIMATION to
                PowerHints(
                    PM.MODE_UI_ANIMATION,
                    listOf(PM.BOOST_INTERACTION),
                    listOf(PM.MODE_EXPENSIVE_RENDERING),
                ),
            NAME_SYSTEMUI to
                PowerHints(
                    PM.MODE_SYSTEM_UI,
                    listOf(PM.BOOST_INTERACTION, PM.BOOST_DISPLAY_UPDATE_IMMINENT),
                ),
            NAME_FLING to
                PowerHints(
                    PM.MODE_FLING,
                    listOf(PM.BOOST_INTERACTION),
                    listOf(PM.MODE_EXPENSIVE_RENDERING),
                ),
            NAME_BENCH_CPU to
                PowerHints(
                    PM.MODE_CPU_BENCHMARK,
                    fallbackModes =
                        listOf(PM.MODE_SUSTAINED_PERFORMANCE, PM.MODE_EXPENSIVE_RENDERING),
                ),
            NAME_BENCH_GPU to
                PowerHints(
                    PM.MODE_GPU_BENCHMARK,
                    fallbackModes =
                        listOf(PM.MODE_SUSTAINED_PERFORMANCE, PM.MODE_EXPENSIVE_RENDERING),
                ),
        )

    @JvmStatic fun getPowerHints(spec: String): PowerHints? = powerHints[spec]
}
