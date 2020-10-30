package org.mozilla.fenix.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.style.ClickableSpan
import android.view.View

class MultiClickableSpan(var pos: Int, var context: Context) :
    ClickableSpan() {
    override fun onClick(widget: View) {
        val webviewIntent = Intent(context, WebViewActivity::class.java)
        webviewIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val addr = Bundle()
        when (pos) {
            1 -> addr.putString("url", "https://www.mozilla.org/en-US/MPL/")
            2 -> addr.putString(
                "url",
                "https://www.mozilla.org/en-US/foundation/trademarks/policy/"
            )
            3 -> addr.putString("url","https://www.mozilla.org/zh-CN/privacy/firefox/");
        }
        webviewIntent.putExtras(addr)
        context.startActivity(webviewIntent)
    }
}
