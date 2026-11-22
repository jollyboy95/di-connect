// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Static content for verifying display launch; contains no simulated vehicle readings. */
class GwmDisplayTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            addView(TextView(this@GwmDisplayTestActivity).apply {
                text = "DISPLAY TEST\nPARKED ONLY\nNO VEHICLE DATA\nTap to close"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                setOnClickListener { finish() }
            })
        })
    }
}