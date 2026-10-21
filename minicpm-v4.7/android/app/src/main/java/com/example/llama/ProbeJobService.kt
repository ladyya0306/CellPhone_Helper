package com.example.llama

import android.app.job.JobParameters
import android.app.job.JobService
import java.io.File

/**
 * 仅用于实验 E2：JobScheduler 的定时任务在 vivo 冻结应用后，系统会不会把它唤醒执行。
 * 触发后只往 files/e2e-probe.txt 写一行时间；正式功能不使用。
 */
class ProbeJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        try {
            File(filesDir, "e2e-probe.txt").appendText(
                "E2 job ran at " + java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()) + "\n"
            )
        } catch (_: Exception) {
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
