package com.dcg.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.dcg.tv.data.Net
import com.dcg.tv.data.UserData

/** Capas e logos usam a mesma conexão do app, com o DNS seguro. */
class DcgApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        UserData.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Net.client)
            .build()
}
