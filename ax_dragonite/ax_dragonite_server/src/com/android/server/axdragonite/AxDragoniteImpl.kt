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

import android.content.Context
import android.os.Bundle
import android.os.FileUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.ServiceManager
import android.os.SystemClock
import android.os.SystemProperties
import android.os.Trace
import android.util.Slog
import com.android.internal.dragonite.AxDragoniteConstants
import com.android.internal.os.BackgroundThread
import com.android.server.am.ProcessList
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

private typealias SceneHandler =
    (
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) -> Unit

class AxDragoniteImpl(context: Context? = null) : IAxDragonite {
    companion object {
        const val TAG = "AxDragonite"

        private const val SF_SERVICE = "SurfaceFlinger"
        private const val ISURFACE_COMPOSER = "android.ui.ISurfaceComposer"
        private const val PARAMS1_KEY = AxSceneTable.KEY_PARAMS1
        private const val PROC_DIR = "/proc"
        private const val MAX_PID_QUERY = 1024
        private const val HANDLE_MESSAGE_OFFSET = 100
        private const val MISSING_DURATION = -1
        private const val TRACE_COOKIE = 64L
        private const val WORKER_THREAD_PRIORITY = -2
        private const val DRAGONITE_THREAD_NAME = "DragoniteThread"
        private const val DRAGONITE_MANAGER_THREAD_NAME = "DragoniteManagerThread"
        private const val TRACE_STATE_FROZEN = "frozen"
        private const val TRACE_STATE_THAWED = "unfrozen"
        private const val TRACE_PROCESSES_SUFFIX = "Processes"
        private const val RESTORE_DISABLED_FLAG = "false"
        private const val KSWAPD_COMM = "kswapd"
        private const val RESTORE_SCHED_POLICY = 0
        private const val RESTORE_SCHED_PRIORITY = 0
        private const val RESTORE_THREAD_PRIORITY = -10
        private const val FREEZE_ADJ_MIN = 250
        private const val FREEZE_ADJ_EXCLUDED = ProcessList.CACHED_APP_MIN_ADJ
        private const val FREEZE_ADJ_MAX = ProcessList.UNKNOWN_ADJ
        private const val INVALID_TID = -1
        private const val THREAD_RENDER = "RenderThread"
        private const val THREAD_HWUI_PREFIX = "hwui"
        private const val MAX_TASK_QUERY = 256
        private const val TASK_DIR_SUFFIX = "/task"
        private const val BACKGROUND_LIMIT_ENABLED = 1
        private const val BACKGROUND_LIMIT_DISABLED = 0
        private const val BOOST_MODE_ON = true
        private const val BOOST_MODE_OFF = false
        private const val RESTORE_HWUI_THREAD_PRIORITY = -2
        private const val BIND_ENABLED = 1
        private const val BIND_DISABLED = 0

        private const val CPUSET_CGROUP_PROCS_PATH =
            "${AxCmdTable.CPUSET_DIR}${AxCmdTable.PATH_SEP}${AxCmdTable.FILE_CGROUP_PROCS}"
        private const val CPUSET_TASKS_PATH =
            "${AxCmdTable.CPUSET_DIR}${AxCmdTable.PATH_SEP}${AxCmdTable.FILE_TASKS}"
        private const val CPUCTL_CGROUP_PROCS_PATH =
            "${AxCmdTable.CPUCTL_DIR}${AxCmdTable.PATH_SEP}${AxCmdTable.FILE_CGROUP_PROCS}"

        private const val GROUP_AX_FOREGROUND = "ax_foreground"

        private fun cpusetPath(group: String, file: String): String =
            "${AxCmdTable.CPUSET_DIR}${AxCmdTable.PATH_SEP}$group${AxCmdTable.PATH_SEP}$file"

        private val CPUSET_AX_FOREGROUND_CPUS =
            cpusetPath(GROUP_AX_FOREGROUND, AxCmdTable.FILE_CPUS)
        private val CPUSET_DEX2OAT_CPUS = cpusetPath(AxCmdTable.GROUP_DEX2OAT, AxCmdTable.FILE_CPUS)
        private val CPUSET_BACKGROUND_CPUS =
            cpusetPath(AxCmdTable.GROUP_BACKGROUND, AxCmdTable.FILE_CPUS)
        private const val PROP_DEX2OAT_THREADS = "dalvik.vm.dex2oat-threads"

        private const val SF_AFFINITY_BINDER_CODE = 2007
        private const val DEBUG_PROP = "persist.dragonite.debug"
        private const val GRACE_PERIOD_MS = 5000L
        private const val COLD_START_TIMEOUT_MS = 10000L
        private const val BINDER_DUMP_TIMEOUT_SEC = 2L
    }

    private val debug get() = SystemProperties.getBoolean(DEBUG_PROP, false)
    private val processPidCache = ConcurrentHashMap<String, Int>()
    @Volatile private var currentActiveHandle = AxDragoniteConstants.INVALID_HANDLE
    @Volatile var mBackgroundLoadLimit = BACKGROUND_LIMIT_ENABLED
    @Volatile var mLimitBoostDisabled = true

    private lateinit var mDragoniteThread: HandlerThread
    private lateinit var mManagerThread: HandlerThread
    lateinit var mManagerHandler: Handler
    lateinit var mDragoniteHandler: DragoniteHandler
    private var mProcessList: ProcessList? = null

    var mKswapdPids: IntArray? = null
    private var mKswapdAffinityBoost: IntArray? = null
    private var mDefaultKswapdAffinity: IntArray? = null

    val mDragoniteExt: AxDragoniteExt = AxDragoniteExt()
    val mPlatformConfig: AxPlatformConfig = AxPlatformConfig()
    private val mSessionRecords: SessionRecords = SessionRecords(this)
    private val mSceneReleaseRunnable: SceneReleaseRunnable = SceneReleaseRunnable(this)
    private val mInputBoostResetRunnable: InputBoostResetRunnable = InputBoostResetRunnable(this)

    private var sfBinder: IBinder? = null

    private val mCommandsMap = HashMap<Int, AxCommandExec>()
    private var mGameModeEnabled = false
    private var mGameModeHandle = AxDragoniteConstants.INVALID_HANDLE

    init {
        logDebug(TAG, "create AxDragonite")
        logDebug(TAG, AxCmdTable.getCommands().toString())
        mKswapdAffinityBoost =
            AxPlatformConfig.parseAffinityCores(mPlatformConfig.getSwapAffinityBoost())
        mDefaultKswapdAffinity = AxCpuSets.resolveCores(AxCpuSets.CPUSET_ALL)
        loadCommands()
        initHandlerThreads()
    }

    fun onSystemReady(context: Context? = null, processList: ProcessList? = null) {
        if (processList != null) mProcessList = processList
        Process.setThreadScheduler(
            mDragoniteThread.threadId,
            AxDragoniteConstants.BOOST_SCHED_POLICY,
            AxDragoniteConstants.BOOST_SCHED_PRIORITY,
        )
        Process.setThreadScheduler(
            mManagerThread.threadId,
            AxDragoniteConstants.BOOST_SCHED_POLICY,
            AxDragoniteConstants.BOOST_SCHED_PRIORITY,
        )
        mDragoniteExt.init()
        BackgroundThread.getHandler().removeCallbacks(mSceneReleaseRunnable)
        BackgroundThread.getHandler().post(mSceneReleaseRunnable)
        initKswapdPids()
    }

    private fun initHandlerThreads() {
        mDragoniteThread =
            HandlerThread(DRAGONITE_THREAD_NAME, WORKER_THREAD_PRIORITY).also { it.start() }
        mDragoniteHandler = DragoniteHandler(mDragoniteThread.looper)
        mManagerThread =
            HandlerThread(DRAGONITE_MANAGER_THREAD_NAME, WORKER_THREAD_PRIORITY).also { it.start() }
        mManagerHandler = Handler(mManagerThread.looper)
    }

    inner class DragoniteHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            val handle = msg.what - HANDLE_MESSAGE_OFFSET
            logDebug(TAG, "handleMessage: what = ${msg.what}, handle = $handle")
            release(handle)
        }
    }

    private fun registerCommands(ids: List<Int>, handler: SceneHandler) {
        for (id in ids) {
            mCommandsMap[id] = AxCommandExec(handler)
        }
    }

    private fun loadCommands() {
        val cpusetGroups =
            listOf(
                AxCmdTable.CMD_CPUSET_TA_PROCS to AxCmdTable.CMD_CPUSET_TA_CPUS,
                AxCmdTable.CMD_CPUSET_FG_PROCS to AxCmdTable.CMD_CPUSET_FG_CPUS,
                AxCmdTable.CMD_CPUSET_BG_PROCS to AxCmdTable.CMD_CPUSET_BG_CPUS,
                AxCmdTable.CMD_CPUSET_SBG_PROCS to AxCmdTable.CMD_CPUSET_SBG_CPUS,
                AxCmdTable.CMD_CPUSET_RESTRICTED_PROCS to AxCmdTable.CMD_CPUSET_RESTRICTED_CPUS,
                AxCmdTable.CMD_CPUSET_DEX2OAT_PROCS to AxCmdTable.CMD_CPUSET_DEX2OAT_CPUS,
                AxCmdTable.CMD_CPUSET_CAMERA_DAEMON_PROCS to AxCmdTable.CMD_CPUSET_CAMERA_DAEMON_CPUS,
            )
        registerCommands(cpusetGroups.map { it.first }, ::adjustCpusetProcs)
        registerCommands(cpusetGroups.map { it.second }, ::adjustCpusetCpus)
        registerCommands(
            listOf(
                AxCmdTable.CMD_CPUSET_TA_TASKS,
                AxCmdTable.CMD_CPUSET_FG_TASKS,
                AxCmdTable.CMD_CPUSET_BG_TASKS,
                AxCmdTable.CMD_CPUSET_SBG_TASKS,
                AxCmdTable.CMD_CPUSET_RESTRICTED_TASKS,
                AxCmdTable.CMD_CPUSET_DEX2OAT_TASKS,
                AxCmdTable.CMD_CPUSET_CAMERA_DAEMON_TASKS,
            ),
            ::adjustCpusetTasks,
        )
        registerCommands(
            listOf(
                AxCmdTable.CMD_CPUCTL_ROOT_PROCS,
                AxCmdTable.CMD_CPUCTL_SYS_PROCS,
                AxCmdTable.CMD_CPUCTL_SBG_PROCS,
                AxCmdTable.CMD_CPUCTL_TA_PROCS,
                AxCmdTable.CMD_CPUCTL_DEX_PROCS,
                AxCmdTable.CMD_CPUCTL_FG_PROCS,
                AxCmdTable.CMD_CPUCTL_RT_PROCS,
                AxCmdTable.CMD_CPUCTL_BG_PROCS,
                AxCmdTable.CMD_CPUCTL_RESTRICTED_PROCS,
            ),
            ::adjustCpuctlProcs,
        )
        registerCommands(
            listOf(
                AxCmdTable.CMD_CPUCTL_SYS_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_SBG_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_TA_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_DEX_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_FG_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_RT_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_BG_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_RESTRICTED_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_AXFG_CPU_SHARES,
                AxCmdTable.CMD_CPUCTL_FW_CPU_SHARES,
            ),
            ::adjustCpuctlShares,
        )
        registerCommands(
            (AxCmdTable.CMD_CPUCTL_SYS_UCLAMP_MIN..AxCmdTable.CMD_CPUCTL_RESTRICTED_UCLAMP_MAX)
                .toList(),
            ::adjustCpuctlUclamp,
        )
        registerCommands(listOf(AxCmdTable.CMD_THREAD_AFFINITY), ::threadAffinity)
        registerCommands(listOf(AxCmdTable.CMD_PROCESS_AFFINITY), ::processAffinity)
        registerCommands(listOf(AxCmdTable.CMD_AX_NAMED_THREAD_AFFINITY), ::setAxNameThreadAffinity)
        registerCommands(listOf(AxCmdTable.CMD_SF_CONTROL_AFFINITY), ::sfBindCoreControll)
        registerCommands(listOf(AxCmdTable.CMD_BACKGROUND_FREEZE), ::backgroundProcessFreeze)
        registerCommands(listOf(AxCmdTable.CMD_KSWAPD_CONTROLL_AFFINITY), ::kswapdBindCoreControll)
        registerCommands(listOf(AxCmdTable.CMD_ANIMATION_BOOST), ::animationBoost)
        registerCommands(listOf(AxCmdTable.CMD_BACKGROUND_LOAD_LIMIT), ::backgroundLoadLimit)
        registerCommands(listOf(AxCmdTable.CMD_PLATFORM_CONTROL), ::platformResourceControl)
        registerCommands(listOf(AxCmdTable.CMD_THREAD_BOOST), ::threadBoost)
    }

    fun logDebug(tag: String, msg: String) {
        if (debug) Slog.d(tag, msg)
    }

    fun hasBoostRecord(handle: Int): Boolean = mSessionRecords.getRecord(handle) != null

    fun attachSession(handle: Int, session: AxSceneBoostSession) {
        mSessionRecords.addSession(handle, session)
    }

    private fun bundlePid(bundle: Bundle?): Int = bundle?.getInt(AxDragoniteConstants.KEY_PID) ?: 0

    private fun bundlePackage(bundle: Bundle?): String? =
        bundle?.getString(AxDragoniteConstants.KEY_PACKAGE_NAME)
            ?: bundle?.getString(AxDragoniteConstants.KEY_PACKAGE)

    fun dispatchSceneCommands(sceneId: Int, bundle: Bundle?) {
        if (bundle != null) {
            val pkgName =
                bundle.getString(AxDragoniteConstants.KEY_PACKAGE_NAME)
                    ?: bundle.getString(AxDragoniteConstants.KEY_PACKAGE)
            Slog.d(
                TAG,
                "sceneId = $sceneId, pid = ${bundle.getInt(AxDragoniteConstants.KEY_PID)}, " +
                    "packageName = $pkgName, " +
                    "duration = ${bundle.getInt(AxDragoniteConstants.KEY_DURATION)}, " +
                    "params = ${bundle.getString(AxDragoniteConstants.KEY_PARAMS)}",
            )
        }
        val scenario = AxSceneTable.getScenario(sceneId) ?: return
        val handle =
            bundle?.getInt(AxDragoniteConstants.KEY_HANDLE) ?: AxDragoniteConstants.INVALID_HANDLE
        val pid = bundlePid(bundle)
        val pkg = bundlePackage(bundle)
        val params = bundle?.getString(AxDragoniteConstants.KEY_PARAMS)
        for (scenarioCmd in scenario.commandList) {
            try {
                val command = scenarioCmd.command ?: continue
                mCommandsMap[command.id]?.execute(handle, pid, pkg, scenarioCmd, params) ?: continue
            } catch (e: Exception) {
                Slog.e(TAG, "dispatchSceneCommands failed for scene=$sceneId", e)
            }
        }
    }

    internal fun isSessionDead(
        session: ActiveSession,
        resolveMissingPid: Boolean = true,
    ): Boolean {
        val sessionBundle = session.getBundle()
        val pkg = bundlePackage(sessionBundle)
        val pid = bundlePid(sessionBundle)
        val duration =
            sessionBundle?.getInt(AxDragoniteConstants.KEY_DURATION, MISSING_DURATION)
                ?: MISSING_DURATION
        val ageMs = SystemClock.elapsedRealtime() - session.createTime

        if (pkg.isNullOrEmpty()) {
            return if (pid > 0) {
                val cmdline = AxPlatformConfig.getProcessCmdLine(pid)
                cmdline.isEmpty()
            } else {
                if (duration > 0) ageMs > (duration + GRACE_PERIOD_MS) else false
            }
        }

        if (pid > 0) {
            val cmdline = AxPlatformConfig.getProcessCmdLine(pid)
            return cmdline.isEmpty() || !cmdline.startsWith(pkg)
        } else {
            if (resolveMissingPid) {
                val currentPid = getPidByProcessName(pkg)
                if (currentPid > 0) {
                    session.setResolvedPid(currentPid)
                    return false
                }
            }
            val timeout = if (duration > 0) (duration + GRACE_PERIOD_MS) else COLD_START_TIMEOUT_MS
            return ageMs > timeout
        }
    }

    fun checkSessionStatus(): Int {
        val activeRecords = mSessionRecords.getActiveRecords()
        logDebug(TAG, "checkSessionStatus: records count = ${activeRecords.size}")
        if (activeRecords.isEmpty()) return 0
        var cleanedCount = 0
        for (session in activeRecords) {
            if (isSessionDead(session)) {
                val pkg = bundlePackage(session.getBundle())
                val pid = bundlePid(session.getBundle())
                Slog.w(TAG, "process = $pkg, pid = $pid died or cleared, release stuck handle ${session.handle}")
                release(session.handle)
                cleanedCount++
            }
        }
        return cleanedCount
    }

    fun isProcessDied(pkg: String?, pid: Int): Boolean {
        if (pkg.isNullOrEmpty()) {
            if (pid <= 0) return false
            val cmdline = AxPlatformConfig.getProcessCmdLine(pid)
            return cmdline.isEmpty()
        }
        if (pid > 0) {
            val cmdline = AxPlatformConfig.getProcessCmdLine(pid)
            return cmdline.isEmpty() || !cmdline.startsWith(pkg)
        }
        val currentPid = getPidByProcessName(pkg)
        return currentPid <= 0
    }

    private fun backgroundLoadLimit(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        logDebug(TAG, "backgroundLoadLimit")
        mBackgroundLoadLimit = cmd.getParameter(PARAMS1_KEY)?.toIntOrNull() ?: 1
    }

    private fun platformResourceControl(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        if (debug) logDebug(TAG, "platformResourceControl")
        val resource = PlatformResource(this, cmd.getParameter(PARAMS1_KEY))
        PlatformResourceBoostSession(this, handle, cmd, resource, PlatformHandler(this, resource))
            .execute()
    }

    fun setFrozenForAll(processes: ArrayList<AxProcessInfo>?, frozen: Boolean) {
        if (processes == null) {
            logDebug(TAG, "frozenlist is null!")
            return
        }
        val action = if (frozen) TRACE_STATE_FROZEN else TRACE_STATE_THAWED
        logDebug(TAG, "$action processes start")
        traceBegin("$action$TRACE_PROCESSES_SUFFIX")
        for (info in processes) {
            setProcessFrozen(info.pid, info.uid, frozen)
        }
        traceEnd()
        logDebug(TAG, "$action processes end")
    }

    private fun processAffinity(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        logDebug(
            TAG,
            "processAffinity: pid=$adjustPid pkg=$packageName params=$params",
        )
        traceBegin("processAffinity")
        val targetProc = cmd.getProcess()
        val pid =
            if (!targetProc.isNullOrEmpty() && (adjustPid <= 0 || targetProc != packageName)) {
                getPidByProcessName(targetProc)
            } else {
                adjustPid
            }
        if (pid <= 0) {
            traceEnd()
            return
        }
        val renderTid = getRenderThreadTidByPid(pid)
        val bindcores = AxCpuSets.resolveCores(cmd.getParameter(PARAMS1_KEY))
        val cores = bindcores?.contentToString()
        logDebug(TAG, "processAffinity: pid = $pid, renderTid = $renderTid, cores = $cores")
        ProcessAffinityBoostSession(
                this,
                handle,
                cmd,
                ProcessAffinity(this, pid, renderTid, bindcores),
                ProcessAffinity(this, pid, renderTid, null),
            )
            .execute()
        traceEnd()
    }

    private fun setAxNameThreadAffinity(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        var resumedPid = AxSceneTable.getResumedAppPid(params, cmd)
        var resumedPkg = AxSceneTable.getResumedPackageName(params, cmd)
        if (resumedPid <= 0) {
            resumedPid = if (adjustPid > 0) adjustPid else (mProcessList?.axGetTopAppPid() ?: -1)
        }
        if (resumedPkg == AxSceneTable.UNKNOWN_PACKAGE) {
            resumedPkg = packageName ?: (mProcessList?.axGetTopAppPackageName() ?: AxSceneTable.UNKNOWN_PACKAGE)
        }
        logDebug(TAG, "setAxNameThreadAffinity: pid = $resumedPid, pkg = $resumedPkg")
        AxNameThreadAffinitySession(
                this,
                handle,
                cmd,
                AxNameThreadAffinity(resumedPid, resumedPkg, true),
                AxNameThreadAffinity(resumedPid, resumedPkg, false),
            )
            .execute()
    }

    private fun setProcessFrozen(pid: Int, uid: Int, frozen: Boolean) {
        logDebug(TAG, "setProcessFrozen: pid = $pid, uid = $uid, frozen = $frozen")
        try {
            Process.setProcessFrozen(pid, uid, frozen)
        } catch (e: Exception) {
            logDebug(TAG, "frozen: uid = $uid, pid = $pid, frozen = $frozen")
            logDebug(TAG, e.toString())
        }
    }

    private fun getSfBinder(): IBinder? {
        sfBinder?.let {
            return it
        }
        val binder = ServiceManager.getService(SF_SERVICE)
        if (binder == null) {
            Slog.e(TAG, "get sf service failed, can't set int array")
            return null
        }
        sfBinder = binder
        return binder
    }

    fun sfBindControllInternal(enabled: Boolean) {
        val binder = getSfBinder() ?: return
        val configuredAffinity = mPlatformConfig.get(AxPlatformConfig.KEY_SURFACE_AFFINITY)
        val bindEnable = if (enabled) BIND_ENABLED else BIND_DISABLED
        val bindAffinity =
            if (enabled) parseSfAffinity(configuredAffinity) else AxCpuSets.sfRestoreAffinity()
        val payload = intArrayOf(bindEnable, bindAffinity)
        val parcel = Parcel.obtain()
        parcel.writeInterfaceToken(ISURFACE_COMPOSER)
        for (value in payload) {
            parcel.writeInt(value)
        }
        try {
            binder.transact(SF_AFFINITY_BINDER_CODE, parcel, null, 0)
        } catch (e: RemoteException) {
            Slog.e(
                TAG,
                "SF transact $SF_AFFINITY_BINDER_CODE failed: ${payload.contentToString()}",
                e,
            )
        } finally {
            parcel.recycle()
        }
    }

    private fun sfBindCoreControll(
        handle: Int,
        callerPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        logDebug(TAG, "sfBindCoreControll")
        SfBoostSession(this, handle, cmd, SfBindControl(this, true), SfBindControl(this, false))
            .execute()
    }

    val frozenAppList: ArrayList<AxProcessInfo>?
        get() {
            logDebug(TAG, "get frozen app list and frozen start")
            val pl =
                mProcessList
                    ?: run {
                        logDebug(TAG, "mProcess is null, ignore!")
                        return null
                    }
            return pl.axGetFrozenProcesses(
                FREEZE_ADJ_MIN,
                FREEZE_ADJ_EXCLUDED,
                FREEZE_ADJ_MAX,
                AxCmdTable.CMD_BACKGROUND_FREEZE,
            )
        }

    private fun collectPidsByComm(comm: String): IntArray {
        val pids: IntArray? = Process.getPids(PROC_DIR, IntArray(MAX_PID_QUERY))
        if (pids == null) return IntArray(0)
        val list = ArrayList<Int>()
        for (pidValue in pids) {
            if (pidValue <= 0) break
            if (AxPlatformConfig.getProcessComm(pidValue).startsWith(comm)) {
                list.add(pidValue)
            }
        }
        return list.toIntArray()
    }

    private fun getKswapdPids(): IntArray = collectPidsByComm(KSWAPD_COMM)

    private fun userParamOrReturn(params: String?, cmd: AxSceneTable.ScenarioCommand): String? {
        val id = cmd.command?.id ?: return null
        return AxPlatformConfig.paramsFromUser(params, id)
            ?: run {
                logDebug(TAG, "need params, return!")
                null
            }
    }

    private fun threadAffinity(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        traceBegin("threadAffinity")
        val paramsFromUser =
            userParamOrReturn(params, cmd)
                ?: run {
                    traceEnd()
                    return
                }
        val tid =
            paramsFromUser.split(AxCommandExec.COMMA)[0].toIntOrNull()
                ?: run {
                    traceEnd()
                    return
                }
        val array = AxCpuSets.resolveCores(cmd.getParameter(PARAMS1_KEY))
        logDebug(TAG, "threadAffinity: tid = $tid, bindcores = ${array?.contentToString()}")
        ThreadAffinityBoostSession(
                this,
                handle,
                cmd,
                ThreadAffinity(this, tid, array),
                ThreadAffinity(this, tid, null),
            )
            .execute()
        traceEnd()
    }

    private fun threadBoost(
        handle: Int,
        pid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        traceBegin("threadBoost")
        val paramsFromUser =
            userParamOrReturn(params, cmd)
                ?: run {
                    traceEnd()
                    return
                }
        val tids =
            paramsFromUser.split(AxCommandExec.COMMA).mapNotNull { it.toIntOrNull() }.toIntArray()
        val isRestoreDisabled = RESTORE_DISABLED_FLAG.equals(cmd.restoreTag, ignoreCase = true)
        val restoreAction =
            if (!isRestoreDisabled) ThreadBoost(this, tids, BOOST_MODE_OFF) else null
        ThreadBoostSession(this, handle, cmd, ThreadBoost(this, tids, BOOST_MODE_ON), restoreAction)
            .execute()
        traceEnd()
    }

    private fun nodeCommandPath(cmd: AxSceneTable.ScenarioCommand): File? =
        (cmd.command as? AxCmdTable.NodeCommand)?.pathFile

    private fun adjustCpuctlUclamp(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        val path = nodeCommandPath(cmd) ?: return
        val uclamp = cmd.getParameter(PARAMS1_KEY) ?: return
        logDebug(TAG, "path = $path, uclamp = $uclamp")
        val restore =
            cmd.restoreTag.ifEmpty {
                AxPlatformConfig.getDefaultUclampRestoreValue(cmd.command?.id ?: return)
            }
        CpuctlBoostSession(
                this,
                handle,
                cmd,
                CpuctlUclamp(this, path, uclamp),
                CpuctlUclamp(this, path, restore),
            )
            .execute()
    }

    private fun boostThreadScheduler(tid: Int) {
        Process.setThreadScheduler(
            tid,
            AxDragoniteConstants.BOOST_SCHED_POLICY,
            AxDragoniteConstants.BOOST_SCHED_PRIORITY,
        )
    }

    private fun restoreThreadScheduler(tid: Int, priority: Int) {
        Process.setThreadScheduler(tid, RESTORE_SCHED_POLICY, RESTORE_SCHED_PRIORITY)
        Process.setThreadPriority(tid, priority)
    }

    fun threadBoostInternal(tid: Int, boost: Boolean) {
        logDebug(TAG, "threadboostinternal: tid = $tid, boost = $boost")
        traceBegin("thread_boost:${boost}_tid:$tid")
        try {
            val threadPriority = Process.getThreadPriority(tid)
            if (boost) {
                boostThreadScheduler(tid)
            } else {
                restoreThreadScheduler(tid, threadPriority)
            }
        } catch (e: Exception) {
            logDebug(TAG, "thread boost pid: $tid, boost: $boost failed!")
        }
        traceEnd()
    }

    fun traceBegin(traceName: String) {
        if (debug) Trace.traceBegin(TRACE_COOKIE, traceName)
    }

    private fun getProcessPids(): HashMap<String, Int> {
        val pids: IntArray? = Process.getPids(PROC_DIR, IntArray(MAX_PID_QUERY))
        if (pids == null) return HashMap()
        val map = HashMap<String, Int>()
        for (pid in pids) {
            if (pid <= 0) break
            val cmdLine = AxPlatformConfig.getProcessCmdLine(pid)
            if (cmdLine.isEmpty()) continue
            val cmdParts = cmdLine.split(AxCommandExec.SLASH)
            map[cmdParts[cmdParts.size - 1]] = pid
        }
        return map
    }

    fun traceEnd() {
        if (debug) Trace.traceEnd(TRACE_COOKIE)
    }

    private fun findThreadTid(pid: Int, match: (String) -> Boolean): Int {
        if (pid <= 0) return INVALID_TID
        val tids =
            Process.getPids(
                "$PROC_DIR${AxCmdTable.PATH_SEP}$pid$TASK_DIR_SUFFIX",
                IntArray(MAX_TASK_QUERY),
            ) ?: return INVALID_TID
        for (tid in tids) {
            if (tid <= 0) break
            if (match(AxPlatformConfig.getProcessComm(tid))) return tid
        }
        return INVALID_TID
    }

    private fun getRenderThreadTidByPid(pid: Int): Int = findThreadTid(pid) { it == THREAD_RENDER }

    private fun getHwuiTaskTidsByPid(pid: Int): IntArray? {
        if (pid <= 0) return null
        val tids =
            Process.getPids(
                "$PROC_DIR${AxCmdTable.PATH_SEP}$pid$TASK_DIR_SUFFIX",
                IntArray(MAX_TASK_QUERY),
            ) ?: return null
        val hwuiTids = ArrayList<Int>()
        for (tid in tids) {
            if (tid <= 0) break
            if (AxPlatformConfig.getProcessComm(tid).contains(THREAD_HWUI_PREFIX)) hwuiTids.add(tid)
        }
        return hwuiTids.takeIf { it.isNotEmpty() }?.toIntArray()
    }

    private fun adjustCpusetCpus(
        handle: Int,
        callerPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        val path = nodeCommandPath(cmd) ?: return
        val cpus = cmd.getParameter(PARAMS1_KEY) ?: return
        logDebug(TAG, "path = $path, cpus = $cpus")
        val restoreCpus =
            cmd.restoreTag.ifEmpty {
                mPlatformConfig.getRestoreCpusetCpus(cmd.command?.id ?: return)
            }
        CpusetBoostSession(
                this,
                handle,
                cmd,
                CpusetCpus(this, path, cpus),
                CpusetCpus(this, path, restoreCpus),
            )
            .execute()
    }

    private fun writeCpusetCpus(path: String, configKey: String, fallbackCpuset: String) {
        val file = File(path)
        if (!file.exists()) return
        val resolved = AxCpuSets.resolve(mPlatformConfig.getOrDefault(configKey, fallbackCpuset))
        try {
            FileUtils.stringToFile(file, resolved)
        } catch (e: IOException) {
            logDebug(TAG, "writeCpusetCpus failed for $path -> $resolved: ${e.message}")
        }
    }

    private fun restoreBackgroundCpus() {
        writeCpusetCpus(
            CPUSET_AX_FOREGROUND_CPUS,
            AxPlatformConfig.KEY_AXFG,
            AxCpuSets.CPUSET_FOREGROUND,
        )
        writeCpusetCpus(
            CPUSET_DEX2OAT_CPUS,
            AxPlatformConfig.KEY_DEX,
            AxCpuSets.CPUSET_FOREGROUND,
        )
        writeCpusetCpus(
            CPUSET_BACKGROUND_CPUS,
            AxPlatformConfig.KEY_BACKGROUND,
            AxCpuSets.CPUSET_UI,
        )
        SystemProperties.set(
            PROP_DEX2OAT_THREADS,
            mPlatformConfig.getOrDefault(
                AxPlatformConfig.KEY_DEX_RESTORE_THREADS,
                AxCpuSets.dexRestoreThreads(),
            ),
        )
    }

    private fun limitBackgroundCpus() {
        writeCpusetCpus(
            CPUSET_AX_FOREGROUND_CPUS,
            AxPlatformConfig.KEY_FOREGROUND_INPUT,
            AxCpuSets.CPUSET_UI,
        )
        writeCpusetCpus(
            CPUSET_DEX2OAT_CPUS,
            AxPlatformConfig.KEY_DEX_INPUT,
            AxCpuSets.CPUSET_UI,
        )
        writeCpusetCpus(
            CPUSET_BACKGROUND_CPUS,
            AxPlatformConfig.KEY_BACKGROUND_INPUT,
            AxCpuSets.CPUSET_RESTRICTED_BG,
        )
        SystemProperties.set(
            PROP_DEX2OAT_THREADS,
            mPlatformConfig.getOrDefault(
                AxPlatformConfig.KEY_DEX_THREADS,
                AxCpuSets.dexLimitThreads(),
            ),
        )
    }

    fun adjustBackgroundLimit(limit: Boolean) {
        try {
            if (!limit) {
                restoreBackgroundCpus()
                return
            }
            if (mBackgroundLoadLimit == BACKGROUND_LIMIT_DISABLED) {
                Slog.d(TAG, "disable BackgroundLimit!")
                return
            }
            limitBackgroundCpus()
        } catch (e: IOException) {
            logDebug(TAG, "adjust background limit failed! limit = $limit")
        }
    }

    private fun adjustCpuctlShares(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        val path = nodeCommandPath(cmd) ?: return
        val cpuShares = cmd.getParameter(PARAMS1_KEY) ?: return
        logDebug(TAG, "path = $path, cpuShares = $cpuShares")
        val restore =
            cmd.restoreTag.ifEmpty { mPlatformConfig.getCpushares(cmd.command?.id ?: return) }
        CpuctlBoostSession(
                this,
                handle,
                cmd,
                CpuctlCpuShares(this, path, cpuShares),
                CpuctlCpuShares(this, path, restore),
            )
            .execute()
    }

    private fun initKswapdPids() {
        mKswapdPids = getKswapdPids()
        logDebug(TAG, "initKswapdPids: pids = ${mKswapdPids?.contentToString()}")
    }

    private fun adjustCpusetProcs(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        val path = nodeCommandPath(cmd) ?: return
        logDebug(TAG, "path = $path, adjustPid = $adjustPid")
        if (adjustPid <= 0) return
        val procs =
            AxPlatformConfig.getCpusetProcs(cmd.restoreTag) ?: File(CPUSET_CGROUP_PROCS_PATH)
        CpusetBoostSession(
                this,
                handle,
                cmd,
                CpusetProc(this, path, adjustPid),
                CpusetProc(this, procs, adjustPid),
            )
            .execute()
    }

    private fun kswapdBindCoreControll(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        val bindCoreStr = cmd.getParameter(PARAMS1_KEY)
        val bindcores = AxCpuSets.resolveCores(bindCoreStr) ?: mKswapdAffinityBoost
        val cores = bindcores?.contentToString()
        logDebug(TAG, "kswapdBind: $bindCoreStr -> $cores")
        KswapdBoostSession(
                this,
                handle,
                cmd,
                KswapdAffinityControlRunnable(this, bindcores),
                KswapdAffinityControlRunnable(this, mDefaultKswapdAffinity),
            )
            .execute()
    }

    private fun adjustCpusetTasks(
        handle: Int,
        adjustTid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        val path = nodeCommandPath(cmd) ?: return
        val tidParam = AxPlatformConfig.paramsFromUser(params, cmd.command?.id ?: return)
        val targetTid = tidParam?.toIntOrNull() ?: adjustTid
        logDebug(TAG, "path = $path, adjustTid = $targetTid")
        if (targetTid <= 0) return
        val cpusetTaskPath =
            AxPlatformConfig.getCpusetTasks(cmd.restoreTag) ?: File(CPUSET_TASKS_PATH)
        CpusetTaskBoostSession(
                this,
                handle,
                cmd,
                CpusetProc(this, path, targetTid),
                CpusetProc(this, cpusetTaskPath, targetTid),
            )
            .execute()
    }

    private fun adjustCpuctlProcs(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        val path = nodeCommandPath(cmd) ?: return
        logDebug(TAG, "path = $path, adjustPid = $adjustPid")
        if (adjustPid <= 0) return
        val fileV2 =
            AxPlatformConfig.getCpuctlProcs(cmd.restoreTag) ?: File(CPUCTL_CGROUP_PROCS_PATH)
        CpuctlBoostSession(
                this,
                handle,
                cmd,
                CpuctlProcs(this, path, adjustPid),
                CpuctlProcs(this, fileV2, adjustPid),
            )
            .execute()
    }

    fun adjustKswapdAffinity(boost: Boolean) {
        adjustKswapdAffinityInternal(if (boost) mKswapdAffinityBoost else mDefaultKswapdAffinity)
    }

    fun adjustKswapdAffinityInternal(affinity: IntArray?) {
        val targetAffinity = affinity ?: mDefaultKswapdAffinity ?: return
        val kswapdPids = mKswapdPids
        if (targetAffinity.isEmpty() || kswapdPids == null || kswapdPids.isEmpty()) return
        val affinityStr = targetAffinity.contentToString()
        val pidsStr = kswapdPids.contentToString()
        logDebug(TAG, "adjustKswapdAffinity: $affinityStr, pids = $pidsStr")
        for (pid in kswapdPids.filter { it > 0 }) {
            try {
                logDebug(TAG, "adjustKswapdAffinity: set $pid -> $affinityStr")
                Process.setThreadAffinity(pid, targetAffinity)
            } catch (e: Exception) {
                logDebug(TAG, "set kswapd affinity failed for $pid: ${e.message}")
            }
        }
    }

    private fun backgroundProcessFreeze(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        logDebug(TAG, "backgroundProcessFreeze")
        BackgroundFreezeBoostSession(this, handle, cmd).execute()
    }

    private fun animationBoost(
        handle: Int,
        adjustPid: Int,
        packageName: String?,
        cmd: AxSceneTable.ScenarioCommand,
        params: String?,
    ) {
        logDebug(TAG, "animationBoost")
        val targetProc = cmd.getProcess()
        val pid =
            if (!targetProc.isNullOrEmpty() && (adjustPid <= 0 || targetProc != packageName)) {
                getPidByProcessName(targetProc)
            } else {
                adjustPid
            }
        if (pid <= 0) return
        val renderThread = getRenderThreadTidByPid(pid)
        AnimationBoostSession(
                this,
                handle,
                cmd,
                AnimationBoost(this, pid, renderThread, BOOST_MODE_ON),
                AnimationBoost(this, pid, renderThread, BOOST_MODE_OFF),
            )
            .execute()
    }

    private fun boostAnimationThread(tid: Int) {
        if (tid > 0) boostThreadScheduler(tid)
    }

    private fun restoreAnimationThread(tid: Int, priority: Int) {
        if (tid <= 0) return
        restoreThreadScheduler(tid, priority)
    }

    fun animationBoostInternal(pid: Int, renderTid: Int, boost: Boolean) {
        logDebug(TAG, "animationboost: pid = $pid, renderTid = $renderTid, boost = $boost")
        traceBegin("animamtionBoost_boost:${boost}_pid:${pid}_renderTid:$renderTid")
        try {
            val hwuiTaskTidsByPid = getHwuiTaskTidsByPid(pid)
            if (boost) {
                boostAnimationThread(pid)
                boostAnimationThread(renderTid)
                hwuiTaskTidsByPid?.forEach(::boostAnimationThread)
            } else {
                if (pid > 0) {
                    restoreThreadScheduler(pid, RESTORE_THREAD_PRIORITY)
                }
                restoreAnimationThread(renderTid, RESTORE_THREAD_PRIORITY)
                hwuiTaskTidsByPid?.forEach {
                    restoreAnimationThread(it, RESTORE_HWUI_THREAD_PRIORITY)
                }
            }
        } catch (unused: Exception) {
            logDebug(
                TAG,
                "animation boost pid: $pid, rendterTid: $renderTid, boost: $boost failed!",
            )
        }
        traceEnd()
    }

    private fun activateScene(handle: Int, sceneId: Int, bundle: Bundle?, duration: Int) {
        Slog.d(TAG, "acquire handle $handle")
        val sceneBundle =
            bundle?.deepCopy()?.apply { putInt(AxDragoniteConstants.KEY_HANDLE, handle) }
        if (duration > 0) {
            scheduleTimeout(handle, duration)
        }
        mManagerHandler.post {
            if (!mSessionRecords.isHandleAllocated(handle)) return@post
            val prevHandle = currentActiveHandle
            if (prevHandle != handle) {
                restoreHandle(prevHandle)
            }
            currentActiveHandle = handle
            dispatchSceneCommands(sceneId, sceneBundle)
        }
    }

    private fun scheduleTimeout(handle: Int, duration: Int) {
        val messageId = handle + HANDLE_MESSAGE_OFFSET
        mDragoniteHandler.removeMessages(messageId)
        mDragoniteHandler.sendEmptyMessageDelayed(messageId, duration.toLong())
    }

    private fun isShadeSceneActive(): Boolean {
        val active = currentActiveHandle
        if (active <= AxDragoniteConstants.INVALID_HANDLE) return false
        return mSessionRecords.getRecord(active)?.sceneId ==
            AxDragoniteConstants.SCENE_NOTIFICATION_EXPAND
    }

    override fun sceneBoostAcquire(sceneId: Int, bundle: Bundle?): Int {
        traceBegin("sceneBoostAquire")
        if (AxSceneTable.getScenario(sceneId) == null) {
            Slog.d(TAG, "Scene id: $sceneId is not exist!")
            traceEnd()
            return AxDragoniteConstants.INVALID_HANDLE
        }
        if (sceneId == AxDragoniteConstants.SCENE_FLING && isShadeSceneActive()) {
            traceEnd()
            return AxDragoniteConstants.INVALID_HANDLE
        }
        val actualBundle = bundle?.deepCopy() ?: Bundle()
        var handle =
            actualBundle.getInt(
                AxDragoniteConstants.KEY_HANDLE,
                AxDragoniteConstants.INVALID_HANDLE,
            )
        var duration = actualBundle.getInt(AxDragoniteConstants.KEY_DURATION, MISSING_DURATION)
        if (duration <= 0) {
            duration =
                when (sceneId) {
                    AxDragoniteConstants.SCENE_APP_START ->
                        AxDragoniteConstants.DURATION_APP_LAUNCH_MS
                    AxDragoniteConstants.SCENE_FLING -> AxDragoniteConstants.DURATION_FLING_MS
                    else -> MISSING_DURATION
                }
            if (duration > 0) {
                actualBundle.putInt(AxDragoniteConstants.KEY_DURATION, duration)
            }
        }
        val existingRecord =
            if (handle > AxDragoniteConstants.INVALID_HANDLE) {
                mSessionRecords.getRecord(handle)
            } else {
                null
            }
        if (existingRecord == null || existingRecord.sceneId != sceneId) {
            handle = mSessionRecords.acquireRecord(sceneId, actualBundle)
            if (handle == AxDragoniteConstants.INVALID_HANDLE) {
                traceEnd()
                return handle
            }
            activateScene(handle, sceneId, actualBundle, duration)
            traceEnd()
            return handle
        }
        Slog.d(TAG, "update handle $handle")
        actualBundle.putInt(AxDragoniteConstants.KEY_HANDLE, handle)
        mSessionRecords.bringToFront(handle)
        mSessionRecords.getRecord(handle)?.updateBundle(actualBundle)
        if (duration > 0) {
            scheduleTimeout(handle, duration)
        }
        val sceneBundle = actualBundle.deepCopy()
        mManagerHandler.post {
            val record = mSessionRecords.getRecord(handle) ?: return@post
            val prevHandle = currentActiveHandle
            if (prevHandle != handle || !record.hasActiveTasks()) {
                if (prevHandle != handle) {
                    restoreHandle(prevHandle)
                }
                currentActiveHandle = handle
                record.clearTasks()
                dispatchSceneCommands(sceneId, sceneBundle)
            }
        }
        traceEnd()
        return handle
    }

    override fun sceneBoostRelease(handle: Int) {
        release(handle)
    }

    fun release(handle: Int) {
        Slog.d(TAG, "release $handle")
        if (
            handle <= AxDragoniteConstants.INVALID_HANDLE ||
                !mSessionRecords.isHandleAllocated(handle)
        ) {
            Slog.d(TAG, "invalid handle, ignore!")
            return
        }
        mDragoniteHandler.removeMessages(handle + HANDLE_MESSAGE_OFFSET)
        mManagerHandler.post(SceneRelease(this, handle))
    }

    override fun inputBoost() {
        if (mGameModeEnabled) return
        mDragoniteHandler.removeCallbacks(mInputBoostResetRunnable)
        if (mLimitBoostDisabled) {
            mLimitBoostDisabled = false
            mDragoniteHandler.post {
                adjustBackgroundLimit(true)
                adjustKswapdAffinity(true)
            }
        }
        mDragoniteHandler.postDelayed(
            mInputBoostResetRunnable,
            mPlatformConfig.getDurationInput().toLong(),
        )
    }

    fun getPidByProcessName(processName: String?): Int {
        if (processName.isNullOrEmpty()) return -1
        try {
            logDebug(TAG, "get process pid by name, processName = $processName")
            processPidCache[processName]?.let { procPid ->
                val procCmdLine = AxPlatformConfig.getProcessCmdLine(procPid)
                if (procCmdLine.isNotEmpty()) {
                    val procName = procCmdLine.split(AxCommandExec.SLASH)
                    if (procName[procName.size - 1] == processName) {
                        return procPid
                    }
                }
                processPidCache.remove(processName)
            }
            traceBegin("getPidByProcessName")
            val pidsMap = getProcessPids()
            val rawPid = pidsMap[processName]
            traceEnd()
            if (rawPid != null && rawPid > 0) {
                processPidCache[processName] = rawPid
                logDebug(TAG, "process pid is $rawPid")
                return rawPid
            }
            return -1
        } catch (unused: Exception) {
            return -1
        }
    }

    private fun restoreHandle(handle: Int) {
        if (handle <= AxDragoniteConstants.INVALID_HANDLE) return
        mSessionRecords.getRecord(handle)?.restoreAll()
    }

    fun releaseScene(handle: Int) {
        restoreHandle(handle)
        mSessionRecords.releaseRecord(handle)
        if (handle != currentActiveHandle) return
        val activeRecord = mSessionRecords.lastActiveRecord
        if (activeRecord == null) {
            currentActiveHandle = AxDragoniteConstants.INVALID_HANDLE
            return
        }
        currentActiveHandle = activeRecord.handle
        dispatchSceneCommands(activeRecord.sceneId, activeRecord.getBundle())
    }

    private fun parseSfAffinity(configuredAffinity: String?): Int {
        if (configuredAffinity.isNullOrEmpty()) return AxCpuSets.sfBoostAffinity()
        return runCatching { AxCpuSets.resolveMask(configuredAffinity).toInt() }
            .getOrDefault(AxCpuSets.sfBoostAffinity())
    }

    override fun isSceneIdExist(sceneId: Int): Boolean = AxSceneTable.getScenario(sceneId) != null

    private fun benchSceneId(pkg: String?, component: String?): Int? {
        if (pkg == "com.antutu.benchmark.full") {
            return if (component?.contains("GameActivity") == true) {
                AxDragoniteConstants.SCENE_BENCH_GPU
            } else {
                AxDragoniteConstants.SCENE_BENCH_CPU
            }
        }
        if (pkg == "com.antutu.ABenchMark") return AxDragoniteConstants.SCENE_BENCH_CPU
        if (
            pkg == "com.google.android.apps.nbu.files" ||
                pkg == "com.google.android.packageinstaller"
        ) {
            return AxDragoniteConstants.SCENE_DISABLE_INPUT_BOOST
        }
        return null
    }

    override fun onActivityStart(
        pkg: String?,
        component: String?,
        uid: Int,
        isCold: Boolean,
        pid: Int,
    ) {
        val bundle = Bundle()
        bundle.putString(AxDragoniteConstants.KEY_PACKAGE_NAME, pkg)
        bundle.putBoolean(AxDragoniteConstants.KEY_IS_COLD, isCold)
        bundle.putInt(AxDragoniteConstants.KEY_UID, uid)
        bundle.putInt(AxDragoniteConstants.KEY_PID, pid)
        val duration = if (isCold) {
            AxDragoniteConstants.DURATION_APP_LAUNCH_COLD_MS
        } else {
            AxDragoniteConstants.DURATION_APP_LAUNCH_WARM_MS
        }
        bundle.putInt(AxDragoniteConstants.KEY_DURATION, duration)
        benchSceneId(pkg, component)?.let { sceneBoostAcquire(it, bundle.deepCopy()) }
        sceneBoostAcquire(AxDragoniteConstants.SCENE_APP_START, bundle)
    }

    override fun onSystemFling(duration: Int) {
        val bundle = Bundle()
        bundle.putInt(AxDragoniteConstants.KEY_DURATION, max(duration, 0))
        sceneBoostAcquire(AxDragoniteConstants.SCENE_FLING_LEVEL_1, bundle)
    }

    override fun updateGameModeBoost(enabled: Boolean) {
        mGameModeEnabled = enabled
        if (enabled) {
            mGameModeHandle = sceneBoostAcquire(AxDragoniteConstants.SCENE_GAME_1, null)
        } else {
            if (mGameModeHandle != AxDragoniteConstants.INVALID_HANDLE) {
                sceneBoostRelease(mGameModeHandle)
                mGameModeHandle = AxDragoniteConstants.INVALID_HANDLE
            }
        }
    }

    override fun adjustCpusetCpus(group: String, cpus: String?, durationMs: Long) {
        if (AxCmdTable.GROUP_DEX2OAT != group || cpus != null) return
        val targetCpus =
            if (durationMs == 0L) {
                AxCpuClusterManager.topology.restrictedDex2oat.toString()
            } else {
                AxCpuClusterManager.topology.ui.toString()
            }
        try {
            FileUtils.stringToFile(File(CPUSET_DEX2OAT_CPUS), targetCpus)
        } catch (e: IOException) {
            logDebug(TAG, "Failed to adjust dex2oat cpuset")
        }
    }

    fun resetAll() {
        Slog.w(TAG, "resetAll: releasing all boost handles and resetting pools")
        for (i in 0 until SessionRecords.MAX_HANDLES) {
            mDragoniteHandler.removeMessages(i + HANDLE_MESSAGE_OFFSET)
        }
        val activeRecords = mSessionRecords.getActiveRecords()
        for (session in activeRecords) {
            session.restoreAll()
        }
        mSessionRecords.resetAll()
        currentActiveHandle = AxDragoniteConstants.INVALID_HANDLE
        sfBindControllInternal(false)
        adjustBackgroundLimit(false)
        adjustKswapdAffinity(false)
        mLimitBoostDisabled = true
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>?) {
        val safeArgs = args ?: emptyArray()
        val cmd = safeArgs.firstOrNull()?.lowercase()
        when (cmd) {
            null, "", "status", "--status" -> dumpStatus(pw)
            "-h", "--help", "help" -> dumpHelp(pw)
            "--handles", "handles" -> dumpHandles(pw)
            "--scenes", "scenes" -> dumpScenes(pw)
            "--cleanup", "cleanup" -> dumpCleanup(pw)
            "--release", "release" -> dumpRelease(pw, safeArgs.getOrNull(1))
            "--reset", "reset" -> dumpReset(pw)
            "--acquire", "acquire" -> dumpAcquire(pw, safeArgs.getOrNull(1), safeArgs.getOrNull(2))
            "--debug", "debug" -> dumpDebug(pw, safeArgs.getOrNull(1))
            else -> {
                pw.println("Unknown command: $cmd")
                pw.println()
                dumpHelp(pw)
            }
        }
    }

    private fun dumpHelp(pw: PrintWriter) {
        pw.println("Axion Dragonite Subsystem (dumpsys activity dragonite)")
        pw.println("Usage: adb shell dumpsys activity dragonite [command]")
        pw.println()
        pw.println("Commands:")
        pw.println("  (no args)                          Dump overall status, active handles, and session health")
        pw.println("  handles, --handles                 Dump active handles and session details")
        pw.println("  scenes, --scenes                   Dump registered boost scenarios and commands")
        pw.println("  cleanup, --cleanup                 Detect and release stuck handles from dead processes")
        pw.println("  release, --release <handle>        Force-release a specific active handle")
        pw.println("  reset, --reset                     Emergency reset: release all handles & reset pool")
        pw.println("  acquire, --acquire <id> [dur_ms]   Trigger test boost acquisition for a scene")
        pw.println("  debug, --debug <on|off>            Enable or disable verbose debug logging")
        pw.println("  help, -h, --help                   Display this help message")
    }

    private fun dumpStatus(pw: PrintWriter) {
        pw.println("===============================================================================")
        pw.println("                    Axion Dragonite Subsystem Status")
        pw.println("===============================================================================")
        pw.println("Command: adb shell dumpsys activity dragonite")
        pw.println("Debug Logging Enabled: $debug ($DEBUG_PROP)")
        pw.println("Game Mode Active: $mGameModeEnabled")
        pw.println("Current Active Handle: $currentActiveHandle")
        pw.println("Background Load Limit: $mBackgroundLoadLimit")
        pw.println("Limit Boost Disabled: $mLimitBoostDisabled")
        pw.println("Kswapd PIDs: ${mKswapdPids?.contentToString() ?: "none"}")
        pw.println("Kswapd Boost Affinity: ${mKswapdAffinityBoost?.contentToString() ?: "none"}")
        pw.println("Default Kswapd Affinity: ${mDefaultKswapdAffinity?.contentToString() ?: "none"}")
        pw.println()
        dumpHandles(pw)
        pw.println()
        pw.println("Run 'adb shell dumpsys activity dragonite --help' to see diagnostic commands.")
        pw.println("===============================================================================")
    }

    private fun dumpHandles(pw: PrintWriter) {
        val allocatedHandles = mSessionRecords.getAllocatedHandles()
        val availableCount = mSessionRecords.getAvailableHandlesCount()
        val activeRecords = mSessionRecords.getActiveRecords()
        val now = SystemClock.elapsedRealtime()

        pw.println("-------------------------------------------------------------------------------")
        pw.println("Handles & Active Boost Sessions:")
        pw.println("-------------------------------------------------------------------------------")
        pw.println("Capacity: ${SessionRecords.MAX_HANDLES} handles (0..${SessionRecords.MAX_HANDLES - 1})")
        pw.println("Allocated Handles Count: ${allocatedHandles.size} (Allocated: $allocatedHandles)")
        pw.println("Available Handles Count: $availableCount")
        pw.println("Active Records Count: ${activeRecords.size}")

        if (allocatedHandles.size >= SessionRecords.MAX_HANDLES) {
            pw.println("!!! WARNING: HANDLE POOL EXHAUSTED (100/100)! ALL FUTURE BOOSTS WILL FAIL! !!!")
            pw.println("!!! Use 'dumpsys activity dragonite cleanup' or 'dumpsys activity dragonite reset' to recover.  !!!")
        } else if (allocatedHandles.size >= SessionRecords.MAX_HANDLES * 0.8) {
            pw.println("! WARNING: Handle pool high usage (${allocatedHandles.size}/${SessionRecords.MAX_HANDLES}). Potential leak!")
        }

        if (activeRecords.isEmpty()) {
            pw.println("No active boost sessions.")
            return
        }

        var deadProcessCount = 0
        for (session in activeRecords) {
            val handle = session.handle
            val isCurrent = handle == currentActiveHandle
            val bundle = session.getBundle()
            val pid = bundlePid(bundle)
            val pkg = bundlePackage(bundle) ?: "unknown"
            val duration = bundle?.getInt(AxDragoniteConstants.KEY_DURATION, MISSING_DURATION) ?: MISSING_DURATION
            val scenario = AxSceneTable.getScenario(session.sceneId)
            val scenarioName = scenario?.scenarioName ?: "UNKNOWN"
            val ageMs = now - session.createTime
            val processDead = isSessionDead(session, resolveMissingPid = false)

            if (processDead) deadProcessCount++

            val statusFlag = when {
                processDead -> " [DEAD PROCESS - STUCK BOOST!]"
                isCurrent -> " [CURRENT ACTIVE]"
                else -> ""
            }

            pw.println()
            pw.println("  [Handle #$handle]$statusFlag")
            pw.println("    Scene: ${session.sceneId} ($scenarioName)")
            pw.println("    Client: $pkg (PID: $pid) -> ${if (processDead) "DEAD / TERMINATED" else "ALIVE"}")
            pw.println("    Age: ${ageMs}ms (${ageMs / 1000}s)")
            pw.println("    Duration: ${if (duration > 0) "${duration}ms" else "No duration (manual release required)"}")
            val tasks = session.getTasks()
            pw.println("    Attached Tasks (${tasks.size}):")
            for (task in tasks) {
                val cmd = task.cmd
                pw.println("      - [Type ${task.sessionType}] cmd: ${cmd?.command?.name} (id: ${cmd?.command?.id}), hold: ${cmd?.holdTime}ms, delay: ${cmd?.delayTime}ms")
            }
        }

        if (deadProcessCount > 0) {
            pw.println()
            pw.println("-------------------------------------------------------------------------------")
            pw.println("!!! DETECTED $deadProcessCount STUCK HANDLE(S) WITH DEAD CLIENT PROCESSES! !!!")
            pw.println("Run 'adb shell dumpsys activity dragonite cleanup' to automatically release them.")
            pw.println("-------------------------------------------------------------------------------")
        }
    }

    private fun dumpScenes(pw: PrintWriter) {
        pw.println("-------------------------------------------------------------------------------")
        pw.println("Registered Dragonite Scenarios:")
        pw.println("-------------------------------------------------------------------------------")
        val scenarios = AxSceneTable.getAllScenarios().sortedBy { it.scenarioId }
        for (scenario in scenarios) {
            pw.println("Scene ID: ${scenario.scenarioId} -> \"${scenario.scenarioName}\" (${scenario.commandList.size} commands)")
            for (cmd in scenario.commandList) {
                val command = cmd.command
                pw.println("  Command: ${command?.name} (id=${command?.id}), holdTime=${cmd.holdTime}ms, delayTime=${cmd.delayTime}ms, process=${cmd.getProcess() ?: "any"}")
            }
        }
    }

    private fun dumpCleanup(pw: PrintWriter) {
        pw.println("Running dead process scan and cleanup...")
        val cleaned = checkSessionStatus()
        if (cleaned > 0) {
            pw.println("Cleanup complete: released $cleaned stuck handle(s) from dead processes.")
        } else {
            pw.println("Cleanup complete: no dead processes found holding active handles.")
        }
    }

    private fun dumpRelease(pw: PrintWriter, handleStr: String?) {
        if (handleStr.isNullOrEmpty()) {
            pw.println("Error: missing handle argument. Usage: dumpsys activity dragonite release <handle>")
            return
        }
        val handle = handleStr.toIntOrNull()
        if (handle == null) {
            pw.println("Error: invalid handle format '$handleStr'. Must be an integer.")
            return
        }
        if (!mSessionRecords.isHandleAllocated(handle)) {
            pw.println("Error: handle $handle is not currently allocated.")
            return
        }
        val latch = CountDownLatch(1)
        mDragoniteHandler.removeMessages(handle + HANDLE_MESSAGE_OFFSET)
        mManagerHandler.post {
            try {
                releaseScene(handle)
            } finally {
                latch.countDown()
            }
        }
        val completed = latch.await(BINDER_DUMP_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (completed) {
            pw.println("Successfully released handle $handle.")
        } else {
            pw.println("Release initiated for handle $handle (waiting for manager thread).")
        }
    }

    private fun dumpReset(pw: PrintWriter) {
        val latch = CountDownLatch(1)
        mManagerHandler.post {
            try {
                resetAll()
            } finally {
                latch.countDown()
            }
        }
        val completed = latch.await(BINDER_DUMP_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (completed) {
            pw.println("Dragonite emergency reset executed:")
            pw.println("  - All active sessions released and restored")
            pw.println("  - All pending timers cancelled")
            pw.println("  - Handle pools reset to full capacity (${SessionRecords.MAX_HANDLES})")
            pw.println("  - Affinities and background limits restored to default")
        } else {
            pw.println("Warning: Dragonite emergency reset timed out waiting for manager thread.")
        }
    }

    private fun dumpAcquire(pw: PrintWriter, sceneStr: String?, durationStr: String?) {
        if (sceneStr.isNullOrEmpty()) {
            pw.println("Error: missing sceneId argument. Usage: dumpsys activity dragonite acquire <sceneId> [durationMs]")
            return
        }
        val sceneId = sceneStr.toIntOrNull() ?: run {
            val match = AxSceneTable.getAllScenarios().firstOrNull {
                it.scenarioName.equals(sceneStr, ignoreCase = true)
            }
            match?.scenarioId
        }
        if (sceneId == null || AxSceneTable.getScenario(sceneId) == null) {
            pw.println("Error: unknown scene '$sceneStr'. Run 'dumpsys activity dragonite scenes' for a list.")
            return
        }
        val duration = durationStr?.toIntOrNull() ?: 1000
        val bundle = Bundle().apply {
            putInt(AxDragoniteConstants.KEY_DURATION, duration)
            putString(AxDragoniteConstants.KEY_PACKAGE_NAME, "com.android.shell")
            putInt(AxDragoniteConstants.KEY_PID, Process.myPid())
        }
        val handle = sceneBoostAcquire(sceneId, bundle)
        val scenarioName = AxSceneTable.getScenario(sceneId)?.scenarioName ?: "UNKNOWN"
        if (handle <= AxDragoniteConstants.INVALID_HANDLE) {
            pw.println("Failed to acquire boost for scene $sceneId ($scenarioName)! Handle allocation failed (all ${SessionRecords.MAX_HANDLES} handles may be in use).")
        } else {
            pw.println("Acquired scene $sceneId ($scenarioName) -> handle=$handle, duration=${duration}ms")
        }
    }

    private fun dumpDebug(pw: PrintWriter, modeStr: String?) {
        when (modeStr?.lowercase()) {
            "on", "true", "1" -> {
                try {
                    SystemProperties.set(DEBUG_PROP, "true")
                    pw.println("Dragonite debug logging enabled.")
                } catch (e: Exception) {
                    pw.println("Failed to set property $DEBUG_PROP: ${e.message}")
                }
            }
            "off", "false", "0" -> {
                try {
                    SystemProperties.set(DEBUG_PROP, "false")
                    pw.println("Dragonite debug logging disabled.")
                } catch (e: Exception) {
                    pw.println("Failed to set property $DEBUG_PROP: ${e.message}")
                }
            }
            else -> {
                pw.println("Current debug state: $debug ($DEBUG_PROP)")
                pw.println("Usage: dumpsys activity dragonite debug <on|off>")
            }
        }
    }
}
