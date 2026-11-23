package com.offline.musicplayer

import android.app.Application
import android.app.Activity
import android.os.Handler
import android.os.Looper
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class MusicApplication : Application(), ImageLoaderFactory {
    @Volatile
    var isMainActivityVisible: Boolean = false
        private set

    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val markMainActivityHidden = Runnable {
        isMainActivityVisible = false
    }

    private val activityLifecycleCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is MainActivity) {
                lifecycleHandler.removeCallbacks(markMainActivityHidden)
                isMainActivityVisible = true
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity) {
                lifecycleHandler.removeCallbacks(markMainActivityHidden)
                lifecycleHandler.postDelayed(markMainActivityHidden, 350L)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB disk cache for album artwork
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
