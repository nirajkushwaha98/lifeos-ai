package com.lifeos.ai.core.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.lifeos.ai.BuildConfig
import com.lifeos.ai.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdMob + Google User Messaging Platform (GDPR/consent) integration.
 *  - Ads are requested only after UMP says `canRequestAds()`.
 *  - Interstitials are frequency capped (min interval + every Nth natural transition).
 */
@Singleton
class AdsManager @Inject constructor(@ApplicationContext private val appContext: Context) {

    private val initialized = AtomicBoolean(false)
    private val _canShowAds = MutableStateFlow(false)
    val canShowAds: StateFlow<Boolean> = _canShowAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private var consentInformation: ConsentInformation? = null
    private var interstitial: InterstitialAd? = null
    private var interstitialLoading = false
    private var lastInterstitialAt = 0L
    private var transitionCount = 0

    val enabled: Boolean get() = BuildConfig.ADS_ENABLED

    /** Call from the Activity on start. Shows the consent form when required (e.g. EEA/UK). */
    fun gatherConsent(activity: Activity) {
        if (!enabled) return
        val info = UserMessagingPlatform.getConsentInformation(activity)
        consentInformation = info
        val params = ConsentRequestParameters.Builder().apply {
            if (BuildConfig.DEBUG) {
                setConsentDebugSettings(ConsentDebugSettings.Builder(activity).build())
            }
        }.build()
        info.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                if (error != null) Log.w(TAG, "Consent form error: ${error.message}")
                onConsentUpdated(info)
            }
        }, { error ->
            Log.w(TAG, "Consent info update failed: ${error.message}")
            onConsentUpdated(info)
        })
        // Consent from a previous session lets us start loading immediately.
        onConsentUpdated(info)
    }

    private fun onConsentUpdated(info: ConsentInformation) {
        _privacyOptionsRequired.value =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (info.canRequestAds()) initialize()
    }

    private fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        MobileAds.initialize(appContext) {
            _canShowAds.value = true
            preloadInterstitial()
        }
    }

    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "Privacy options error: ${error.message}")
            consentInformation?.let { onConsentUpdated(it) }
            onDone()
        }
    }

    private fun preloadInterstitial() {
        if (!_canShowAds.value || interstitial != null || interstitialLoading) return
        interstitialLoading = true
        InterstitialAd.load(appContext, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad; interstitialLoading = false }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    Log.d(TAG, "Interstitial failed: ${error.message}")
                }
            })
    }

    /**
     * Call only at natural break points (e.g. leaving a report screen). Shows at most once per
     * [MIN_INTERVAL_MS] and only every [EVERY_N_TRANSITIONS] eligible transitions.
     */
    fun onNaturalTransition(activity: Activity) {
        if (!enabled || !_canShowAds.value) return
        transitionCount++
        val now = System.currentTimeMillis()
        val ad = interstitial
        if (ad == null) { preloadInterstitial(); return }
        if (transitionCount % EVERY_N_TRANSITIONS != 0 || now - lastInterstitialAt < MIN_INTERVAL_MS) return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { interstitial = null; preloadInterstitial() }
            override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) {
                interstitial = null; preloadInterstitial()
            }
        }
        lastInterstitialAt = now
        ad.show(activity)
    }

    companion object {
        private const val TAG = "AdsManager"
        const val MIN_INTERVAL_MS = 5 * 60 * 1000L
        const val EVERY_N_TRANSITIONS = 3
    }
}

/** Anchored adaptive banner. Renders nothing until consent allows ads. */
@Composable
fun BannerAd(adsManager: AdsManager, modifier: Modifier = Modifier) {
    val canShow by adsManager.canShowAds.collectAsState()
    if (!adsManager.enabled || !canShow) return
    val widthDp = LocalConfiguration.current.screenWidthDp
    val context = LocalContext.current
    val adView = remember {
        AdView(context).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    AndroidView(factory = { adView }, modifier = modifier.fillMaxWidth().wrapContentHeight())
}

/** Native ad rendered as a clearly-labelled card inside content lists. */
@Composable
fun NativeAdCard(adsManager: AdsManager, modifier: Modifier = Modifier) {
    val canShow by adsManager.canShowAds.collectAsState()
    if (!adsManager.enabled || !canShow) return
    val context = LocalContext.current
    val nativeAd = remember { mutableStateOf<NativeAd?>(null) }
    DisposableEffect(Unit) {
        var destroyed = false
        val loader = AdLoader.Builder(context, BuildConfig.ADMOB_NATIVE_ID)
            .forNativeAd { ad -> if (destroyed) ad.destroy() else nativeAd.value = ad }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) { Log.d("NativeAd", error.message) }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT).build())
            .build()
        loader.loadAd(AdRequest.Builder().build())
        onDispose {
            destroyed = true
            nativeAd.value?.destroy()
        }
    }
    val ad = nativeAd.value ?: return
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx -> buildNativeAdView(ctx) },
        update = { view -> bindNativeAd(view, ad) },
    )
}

private fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

private fun buildNativeAdView(ctx: Context): NativeAdView {
    val adView = NativeAdView(ctx)
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(18).toFloat()
            setColor(Color.parseColor("#151F35"))
            setStroke(ctx.dp(1), Color.parseColor("#1F2A44"))
        }
    }
    val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)) }
    val texts = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(12) }
    }
    val label = TextView(ctx).apply {
        text = ctx.getString(R.string.ad_label)
        setTextColor(Color.parseColor("#22D3EE")); textSize = 10f
    }
    val headline = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 14f; maxLines = 1 }
    val body = TextView(ctx).apply { setTextColor(Color.parseColor("#9CA3AF")); textSize = 12f; maxLines = 2 }
    val cta = Button(ctx).apply {
        textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = ctx.dp(12).toFloat(); setColor(Color.parseColor("#3B82F6")) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ctx.dp(36)).apply { marginStart = ctx.dp(12) }
        setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
    }
    texts.addView(label); texts.addView(headline); texts.addView(body)
    root.addView(icon); root.addView(texts); root.addView(cta)
    adView.addView(root)
    adView.iconView = icon
    adView.headlineView = headline
    adView.bodyView = body
    adView.callToActionView = cta
    return adView
}

private fun bindNativeAd(view: NativeAdView, ad: NativeAd) {
    (view.headlineView as TextView).text = ad.headline
    (view.bodyView as TextView).apply { text = ad.body; visibility = if (ad.body == null) View.GONE else View.VISIBLE }
    (view.callToActionView as Button).apply {
        text = ad.callToAction; visibility = if (ad.callToAction == null) View.GONE else View.VISIBLE
    }
    (view.iconView as ImageView).apply {
        val drawable = ad.icon?.drawable
        setImageDrawable(drawable); visibility = if (drawable == null) View.GONE else View.VISIBLE
    }
    view.setNativeAd(ad)
}
