package io.github.namanwtf.atom.agent.voice

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.graphics.Bitmap
import android.service.voice.VoiceInteractionSession
import android.view.View

/**
 * Entry bridge for the system digital assistant.
 *
 * The system creates a TYPE_VOICE_INTERACTION window for this class, but Atom's actual UI is hosted in its own
 * TYPE_APPLICATION_OVERLAY window, so the vendor assistant animation and input layering are not bound to the system session window.
 */
internal class AtomVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var screenContext: AtomAssistantScreenContext? = null
    private var shown = false
    private var coalescedShow = false
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_HIDE_FOR_FOREGROUND_OPERATION) {
                hide()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        context.registerReceiver(
            controlReceiver,
            IntentFilter(ACTION_HIDE_FOR_FOREGROUND_OPERATION),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreateContentView(): View = View(context)

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // When the overlay is still in the freshly-awakened initial idle state, merge duplicate wakes and keep the initially captured underlying-screen context and screenshot.
        if (AtomAssistantOverlayService.hasFreshVisibleEntry()) {
            coalescedShow = true
            return
        }
        coalescedShow = false
        shown = false
        screenContext = AtomAssistantScreenContexts.begin(
            showFlags,
            args?.getParcelableArrayList(KEY_FOREGROUND_ACTIVITIES, ComponentName::class.java).orEmpty(),
        )
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shown = true
        if (coalescedShow) return
        AtomAssistantOverlayService.show(context, screenContext?.id)
    }

    override fun onHandleAssist(state: AssistState) {
        if (coalescedShow) return
        screenContext?.acceptAssist(context.applicationContext, state)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (coalescedShow) {
            screenshot?.recycle()
            return
        }
        val capture = screenContext
        if (capture == null) screenshot?.recycle() else capture.acceptScreenshot(screenshot)
    }

    override fun onHide() {
        coalescedShow = false
        AtomAssistantScreenContexts.release(screenContext?.id)
        screenContext = null
        shown = false
        super.onHide()
    }

    override fun onBackPressed() {
        AtomAssistantOverlayService.dismiss(context)
        hide()
    }

    override fun onCloseSystemDialogs() {
        AtomAssistantOverlayService.dismiss(context)
        hide()
    }

    override fun onDestroy() {
        coalescedShow = false
        // The overlay has its own lifecycle; the system session being rebuilt does not mean the user closed the assistant.
        if (!shown) AtomAssistantScreenContexts.release(screenContext?.id)
        screenContext = null
        context.unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    internal companion object {
        private const val ACTION_HIDE_FOR_FOREGROUND_OPERATION =
            "io.github.namanwtf.atom.agent.voice.HIDE_FOR_FOREGROUND_OPERATION"

        fun requestHideForForegroundOperation(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_HIDE_FOR_FOREGROUND_OPERATION)
                    .setPackage(context.packageName),
            )
        }
    }
}
