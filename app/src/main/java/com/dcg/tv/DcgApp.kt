package com.dcg.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.dcg.tv.data.Net

/** Capas e logos usam a mesma conexão do app, com o DNS seguro. */
class DcgApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Net.client)
            .build()
}
