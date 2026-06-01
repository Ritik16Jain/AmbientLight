package com.ambientlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UsbReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_USB_PERMISSION = "com.ambientlight.USB_PERMISSION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // USB device attached or permission granted — service will pick it up
        // on its next connectUsb() retry cycle automatically
    }
}
