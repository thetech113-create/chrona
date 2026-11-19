package com.vaishnava.alarm

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {
    private const val TAG = "AdManager"
    private const val AD_UNIT_ID = "ca-app-pub-7229295499236355/6591361135"
    private const val TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    @Volatile private var interstitialAd: InterstitialAd? = null
    @Volatile private var isLoading: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    fun preload(context: Context, useTestAd: Boolean = true) {
        if (interstitialAd != null || isLoading) return
        isLoading = true
        val adUnitId = if (useTestAd) TEST_AD_UNIT_ID else AD_UNIT_ID
        val request = AdRequest.Builder().build()
        
        InterstitialAd.load(
            context, adUnitId, request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoading = false
                    interstitialAd = ad
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitialAd = null
                            handler.postDelayed({ preload(context, useTestAd) }, 500)
                        }
                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            interstitialAd = null
                            handler.postDelayed({ preload(context, useTestAd) }, 1000)
                        }
                    }
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    interstitialAd = null
                    handler.postDelayed({ preload(context, useTestAd) }, 10000)
                }
            }
        )
    }

    fun showIfAvailable(activity: Activity) {
        val ad = interstitialAd
        if (ad != null) {
            try {
                ad.show(activity)
            } catch (t: Throwable) {
                interstitialAd = null
                preload(activity.applicationContext)
            }
        } else {
            preload(activity.applicationContext)
        }
    }

    fun showBeforeAction(activity: Activity, proceed: () -> Unit) {
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    try { proceed() } catch (t: Throwable) { Log.e(TAG, "proceed() threw: ${t.message}") }
                    handler.postDelayed({ preload(activity.applicationContext) }, 500)
                }
                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    try { proceed() } catch (t: Throwable) { Log.e(TAG, "proceed() threw: ${t.message}") }
                    handler.postDelayed({ preload(activity.applicationContext) }, 1000)
                }
            }
            try {
                ad.show(activity)
            } catch (t: Throwable) {
                interstitialAd = null
                try { proceed() } catch (t2: Throwable) { Log.e(TAG, "proceed() threw: ${t2.message}") }
                preload(activity.applicationContext)
            }
        } else {
            try { proceed() } catch (t: Throwable) { Log.e(TAG, "proceed() threw: ${t.message}") }
            preload(activity.applicationContext)
        }
    }

    fun getAdState(): String {
        return when {
            interstitialAd != null -> "Ad ready"
            isLoading -> "Loading..."
            else -> "No ad, not loading"
        }
    }
}
