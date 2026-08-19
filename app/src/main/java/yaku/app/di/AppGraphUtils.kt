package yaku.app.di

import android.content.Context
import yaku.core.metro.metroGraph

val Context.appGraph get() = metroGraph<AppGraph>()
