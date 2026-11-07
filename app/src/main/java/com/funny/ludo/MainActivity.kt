package com.funny.ludo

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var sfx: Sfx
    private lateinit var menu: MenuView
    private lateinit var game: LudoView
    private var lastConfig: GameConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        sfx = Sfx(this)
        menu = MenuView(this, sfx) { cfg -> startGame(cfg) }
        game = LudoView(this, sfx)
        game.onMenu = { showMenu() }
        game.onPlayAgain = { lastConfig?.let { startGame(it) } }
        game.visibility = View.GONE

        val root = FrameLayout(this)
        root.addView(menu, FrameLayout.LayoutParams(-1, -1))
        root.addView(game, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        goFullscreen()
    }

    private fun startGame(cfg: GameConfig) {
        lastConfig = cfg
        menu.visibility = View.GONE
        game.visibility = View.VISIBLE
        game.startGame(cfg)
    }

    private fun showMenu() {
        game.stop()
        game.visibility = View.GONE
        menu.visibility = View.VISIBLE
        menu.showMain()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (game.visibility == View.VISIBLE) showMenu() else super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    /** true full-screen: hides the status and navigation bars, swipe from the edge to peek at them */
    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    override fun onDestroy() {
        game.stop()
        sfx.release()
        super.onDestroy()
    }
}
