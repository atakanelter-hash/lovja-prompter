package com.lovja.prompter

import android.content.Context
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Telefon açıkken arka kamera tarafındaki (dış) ekrana ayrı içerik gösterir.
 * Jetpack WindowManager "dual-screen / presentContentOnWindowArea".
 *
 * Sistem açılıp kapanırken bir anlığına yanlış durum bildirebiliyor; bu yüzden
 * bildirilen durum 300 ms değişmeden kalırsa ona güveniyoruz.
 */
@OptIn(ExperimentalWindowApi::class)
class RearDisplayHelper(
    private val activity: ComponentActivity,
    private val createContent: (Context) -> View,
    private val onStateChanged: (State, String) -> Unit,
) : WindowAreaPresentationSessionCallback {

    enum class State { CHECKING, UNSUPPORTED, UNAVAILABLE, AVAILABLE, ACTIVE }

    private val controller = WindowAreaController.getOrCreate()
    private var rearArea: WindowAreaInfo? = null
    private var session: WindowAreaSessionPresenter? = null
    private var lastStatus: WindowAreaCapability.Status? = null

    /** true ise dış ekran uygun olur olmaz kendiliğinden açılır. */
    var autoStart = true

    var state: State = State.CHECKING
        private set

    fun startObserving() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.windowAreaInfos.collectLatest { infos ->
                    // 300 ms bekle; bu sırada yeni bir durum gelirse bu blok iptal olur.
                    delay(300)
                    apply(infos)
                }
            }
        }
    }

    private fun apply(infos: List<WindowAreaInfo>) {
        val rear = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
        rearArea = rear
        val status = rear?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status
        lastStatus = status
        val detail = "alan sayısı=${infos.size}, " +
            infos.joinToString { "${it.type}:" +
                it.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status }
        Log.i(TAG, "window areas: $detail")

        val newState = when {
            session != null -> State.ACTIVE
            rear == null || status == null -> State.UNSUPPORTED
            status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> State.ACTIVE
            status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> State.AVAILABLE
            status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED -> State.UNSUPPORTED
            else -> State.UNAVAILABLE
        }
        setState(newState, detail)
        if (newState == State.AVAILABLE && autoStart) start()
    }

    fun start() {
        val area = rearArea ?: return
        if (session != null) return
        if (lastStatus != WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE) return
        try {
            controller.presentContentOnWindowArea(
                area.token,
                activity,
                ContextCompat.getMainExecutor(activity),
                this,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "presentContentOnWindowArea failed", t)
            setState(State.UNAVAILABLE, "Hata: ${t.message}")
        }
    }

    fun stop() {
        autoStart = false
        session?.close()
    }

    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        this.session = session
        session.setContentView(createContent(session.context))
        setState(State.ACTIVE, "Dış ekranda gösteriliyor")
    }

    override fun onSessionEnded(t: Throwable?) {
        session = null
        if (t != null) Log.e(TAG, "session ended with error", t)
        setState(State.UNAVAILABLE, if (t != null) "Oturum hata ile bitti: ${t.message}" else "Dış ekran oturumu kapandı")
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {
        Log.i(TAG, "rear container visible=$isVisible")
    }

    private fun setState(s: State, detail: String) {
        state = s
        onStateChanged(s, detail)
    }

    companion object { private const val TAG = "RearDisplay" }
}
