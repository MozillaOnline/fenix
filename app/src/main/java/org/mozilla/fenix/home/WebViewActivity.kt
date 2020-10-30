package org.mozilla.fenix.home

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import org.mozilla.fenix.R

class WebViewActivity : Activity() {
    private var webView: WebView? = null
    private var closeButton: ImageButton? = null
    private var url: String? = ""
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.webview)
        val addr = intent.extras
        if (addr != null) {
            url = addr.getString("url")
        }
        webView = findViewById<View>(R.id.webView) as WebView
        closeButton = findViewById<View>(R.id.closeButton) as ImageButton
        webView!!.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url1: String): Boolean {
                view.loadUrl(url1)
                return false
            }
        }
        closeButton!!.setOnClickListener { finish() }
        webView!!.settings.javaScriptEnabled = true
        webView!!.loadUrl(url)
    }
}
