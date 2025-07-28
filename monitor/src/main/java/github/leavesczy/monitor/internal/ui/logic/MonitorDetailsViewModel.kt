package github.leavesczy.monitor.internal.ui.logic

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import github.leavesczy.monitor.R
import github.leavesczy.monitor.internal.db.Monitor
import github.leavesczy.monitor.internal.db.MonitorDatabase
import github.leavesczy.monitor.internal.db.MonitorPair
import github.leavesczy.monitor.internal.db.MonitorStatus
import github.leavesczy.monitor.internal.db.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @Author: leavesCZY
 * @Date: 2024/3/1 22:49
 * @Desc:
 */
internal class MonitorDetailsViewModel(
    private val application: Application,
    private val monitorId: Long
) : ViewModel() {

    var mainPageViewState by mutableStateOf(
        value = MonitorDetailPageViewState(
            title = "",
            tabTagList = emptyList()
        )
    )
        private set

    var overviewPageViewState by mutableStateOf(
        value = MonitorDetailOverviewPageViewState(
            overview = emptyList()
        )
    )
        private set

    var requestPageViewState by mutableStateOf(
        value = MonitorDetailRequestPageViewState(
            headers = emptyList(),
            bodyFormatted = ""
        )
    )
        private set

    var responsePageViewState by mutableStateOf(
        value = MonitorDetailResponsePageViewState(
            headers = emptyList(),
            bodyFormatted = ""
        )
    )
        private set

    init {
        viewModelScope.launch {
            MonitorDatabase.instance.monitorDao.queryMonitorAsFlow(id = monitorId)
                .distinctUntilChanged()
                .collectLatest {
                    mainPageViewState = MonitorDetailPageViewState(
                        title = it.method + " " + it.pathWithQuery,
                        tabTagList = listOf(
                            application.getString(R.string.monitor_overview),
                            application.getString(R.string.monitor_request),
                            application.getString(R.string.monitor_response),
                        )
                    )
                    overviewPageViewState = MonitorDetailOverviewPageViewState(
                        overview = it.buildOverview()
                    )
                    requestPageViewState = MonitorDetailRequestPageViewState(
                        headers = it.requestHeaders,
                        bodyFormatted = it.requestBodyFormatted
                    )
                    responsePageViewState = MonitorDetailResponsePageViewState(
                        headers = it.responseHeaders,
                        bodyFormatted = it.responseBodyFormatted
                    )
                }
        }
    }

    fun copyText() {
        viewModelScope.launch(context = Dispatchers.Default) {
            try {
                val shareText = queryMonitorShareText()
                val monitor = application.getString(R.string.monitor_monitor)
                val clipboardManager =
                    application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText(monitor, shareText)
                clipboardManager.setPrimaryClip(clipData)
                showToast(resId = R.string.monitor_copied)
            } catch (e: Throwable) {
                e.printStackTrace()
                showToast(msg = e.toString())
            }
        }
    }

    fun shareAsText() {
        viewModelScope.launch(context = Dispatchers.Default) {
            try {
                val shareText = queryMonitorShareText()
                val monitor = application.getString(R.string.monitor_monitor)
                val shareIntent = ShareCompat.IntentBuilder(application)
                    .setText(shareText)
                    .setType("text/plain")
                    .setChooserTitle(monitor)
                    .setSubject(monitor)
                    .createChooserIntent()
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                application.startActivity(shareIntent)
            } catch (e: Throwable) {
                e.printStackTrace()
                showToast(msg = e.toString())
            }
        }
    }

    fun shareAsFile() {
        viewModelScope.launch(context = Dispatchers.Default) {
            try {
                val shareText = queryMonitorShareText()
                val shareFile = createShareFile()
                shareFile.writeText(text = shareText, charset = Charsets.UTF_8)
                val authority = application.applicationInfo.packageName + ".monitorFileProvider"
                val shareFileUri =
                    FileProvider.getUriForFile(application, authority, shareFile)
                val monitor = application.getString(R.string.monitor_monitor)
                val shareIntent = ShareCompat.IntentBuilder(application)
                    .setStream(shareFileUri)
                    .setType(application.contentResolver.getType(shareFileUri))
                    .setChooserTitle(monitor)
                    .setSubject(monitor)
                    .intent
                shareIntent.apply {
                    clipData = ClipData.newRawUri(monitor, shareFileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooserIntent = Intent.createChooser(shareIntent, monitor)
                chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                application.startActivity(chooserIntent)
            } catch (e: Throwable) {
                e.printStackTrace()
                showToast(msg = e.toString())
            }
        }
    }

    private fun createShareFile(): File {
        val cacheRootDir = File(application.cacheDir, "monitor")
        cacheRootDir.mkdirs()
        val currentTime =
            SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault()).format(Date())
        val shareFile = File(cacheRootDir, "monitor_$currentTime.txt")
        shareFile.createNewFile()
        return shareFile
    }

    private suspend fun queryMonitorShareText(): String {
        return MonitorDatabase.instance.monitorDao.queryMonitor(id = monitorId).buildShareText()
    }

    private fun Monitor.buildShareText(): String {
        return buildString {
            append(buildOverview().format())
            append("\n\n")
            append("----------Request----------")
            append("\n\n")
            append(requestHeaders.format())
            if (requestBodyFormatted.isNotBlank()) {
                append("\n\n")
                append(requestBodyFormatted)
            }
            append("\n\n")
            append("----------Response----------")
            append("\n\n")
            append(responseHeaders.format())
            append("\n\n")
            append(responseBodyFormatted)
        }
    }

    private fun Monitor.buildOverview(): List<MonitorPair> {
        val responseSummaryText = when (httpStatus) {
            MonitorStatus.Requesting -> {
                ""
            }

            MonitorStatus.Complete -> {
                "$responseCode $responseMessage"
            }

            MonitorStatus.Failed -> {
                error ?: ""
            }
        }
        return buildList {
            add(MonitorPair(name = "Url", value = urlFormatted))
            add(MonitorPair(name = "Method", value = method))
            add(MonitorPair(name = "Protocol", value = protocol))
            add(MonitorPair(name = "State", value = httpStatus.toString()))
            add(MonitorPair(name = "Response", value = responseSummaryText))
            add(MonitorPair(name = "TlsVersion", value = responseTlsVersion))
            add(MonitorPair(name = "CipherSuite", value = responseCipherSuite))
            add(MonitorPair(name = "Request Time", value = getDateYMDHMSS(date = requestTime)))
            add(MonitorPair(name = "Response Time", value = getDateYMDHMSS(date = responseTime)))
            add(MonitorPair(name = "Duration", value = requestDurationFormatted))
            add(
                MonitorPair(
                    name = "Request Size",
                    value = formatBytes(bytes = requestContentLength)
                )
            )
            add(
                MonitorPair(
                    name = "Response Size",
                    value = formatBytes(bytes = responseContentLength)
                )
            )
            add(MonitorPair(name = "Total Size", value = totalSizeFormatted))
        }
    }

    private fun getDateYMDHMSS(date: Long): String {
        val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss SSS", Locale.getDefault())
        return simpleDateFormat.format(Date(date))
    }

    private fun List<MonitorPair>.format(): String {
        return buildString {
            this@format.forEachIndexed { index, pair ->
                append(pair.name)
                append(" : ")
                append(pair.value)
                if (index != this@format.size - 1) {
                    append("\n")
                }
            }
        }
    }

    private suspend fun showToast(@StringRes resId: Int) {
        showToast(msg = application.getString(resId))
    }

    private suspend fun showToast(msg: String) {
        withContext(context = Dispatchers.Main.immediate) {
            Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
        }
    }

}