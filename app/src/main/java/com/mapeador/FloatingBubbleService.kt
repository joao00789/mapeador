package com.mapeador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast

class FloatingBubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: ImageView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        startAsForegroundService()
        if (Settings.canDrawOverlays(this)) showBubble()
        else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubble == null && Settings.canDrawOverlays(this)) showBubble()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bubble?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (error: IllegalArgumentException) {
                Log.w("NexoPlay", "A bolha já havia sido removida", error)
            }
        }
        bubble = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        val channelId = "nexoplay_bubble"
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "Botão flutuante", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_stat_nexoplay)
            .setContentTitle("NexoPlay ativo")
            .setContentText("Toque para abrir o mapeador. Segure a bolha para encerrar.")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showBubble() {
        if (bubble != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size
            y = resources.displayMetrics.heightPixels / 3
        }
        bubbleParams = params

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_nexoplay)
            contentDescription = "NexoPlay flutuante. Toque para abrir; segure para fechar."
            setPadding(size / 6, size / 6, size / 6, size / 6)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1A1F2B.toInt())
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF77E4C1.toInt())
            }
            clipToOutline = true
            elevation = 8 * resources.displayMetrics.density
        }

        icon.setOnTouchListener(BubbleTouchListener())
        try {
            manager.addView(icon, params)
            bubble = icon
        } catch (error: SecurityException) {
            Log.e("NexoPlay", "Permissão para exibir a bolha foi recusada", error)
            Toast.makeText(this, "Não foi possível exibir o botão flutuante.", Toast.LENGTH_LONG).show()
            stopSelf()
        } catch (error: WindowManager.BadTokenException) {
            Log.e("NexoPlay", "O Android recusou a janela flutuante", error)
            stopSelf()
        }
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var downTime = 0L

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    downTime = event.eventTime
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    if (moved) {
                        bubbleParams.x = startX + dx
                        bubbleParams.y = startY + dy
                        try {
                            windowManager?.updateViewLayout(view, bubbleParams)
                        } catch (error: IllegalArgumentException) {
                            Log.w("NexoPlay", "A bolha não está mais anexada à janela", error)
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved && event.eventTime - downTime >= LONG_PRESS_MS) {
                        stopSelf()
                    } else if (!moved) {
                        val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                    }
                    return true
                }
            }
            return true
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 7321
        const val LONG_PRESS_MS = 600L
    }
}
