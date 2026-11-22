package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.di.LumaCastContainer

class LumaCastApplication : Application(), ImageLoaderFactory {

    lateinit var container: LumaCastContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = LumaCastContainer(applicationContext)
        container.updateManager.triggerStartupCheckIfEligible()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("video_thumbs_disk"))
                    .maxSizeBytes(48L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
