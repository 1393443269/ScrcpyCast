package com.scrcpycast.receiver

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.os.Build
import android.util.Log

class BootJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Log.d("BootJobService", "onStartJob called, alreadyStarted=$alreadyStarted")
        if (alreadyStarted) {
            Log.d("BootJobService", "Skip, rescheduling for next boot")
            scheduleNextBoot(this)
            jobFinished(params, false)
            return false
        }
        alreadyStarted = true
        Log.d("BootJobService", "Starting BootService after boot")
        scheduleNextBoot(this)
        val intent = Intent(this, BootService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d("BootJobService", "onStopJob")
        return false
    }

    companion object {
        private var alreadyStarted = false
        private const val JOB_ID = 1001

        fun markStarted() {
            alreadyStarted = true
            Log.d("BootJobService", "markStarted")
        }

        fun scheduleNextBoot(ctx: Context) {
            val scheduler = ctx.getSystemService(JobScheduler::class.java)
            scheduler.cancel(JOB_ID)
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, BootJobService::class.java))
                .setPersisted(true)
                .setMinimumLatency(60_000)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build()
            val result = scheduler.schedule(job)
            Log.d("BootJobService", "scheduleNextBoot result=$result")
        }
    }
}
