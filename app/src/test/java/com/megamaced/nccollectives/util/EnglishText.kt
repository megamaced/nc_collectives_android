package com.megamaced.nccollectives.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider

/** This text as the app shows it in English, from the app's own resources. Needs Robolectric. */
fun UiText.english(): String = resolve(ApplicationProvider.getApplicationContext<Context>().resources)
