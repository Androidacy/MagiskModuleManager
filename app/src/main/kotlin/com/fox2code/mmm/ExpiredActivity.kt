package com.fox2code.mmm

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.fox2code.foxcompat.app.FoxActivity
import com.google.android.material.button.MaterialButton

class ExpiredActivity : FoxActivity() {
    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expired)
        // download_button leads to the download page and uninstall_button leads to the uninstall dialog
        val downloadBtn = findViewById<MaterialButton>(R.id.download_button)
        val uninstallBtn = findViewById<MaterialButton>(R.id.uninstall_button)
        downloadBtn.setOnClickListener {
            // open https://www.androidacy.com/downloads/?view=FoxMMM
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.androidacy.com/downloads/?view=FoxMMM")
                )
            )
        }
        uninstallBtn.setOnClickListener {
            // open system uninstall dialog
            val intent = Intent(Intent.ACTION_DELETE)
            // discover our package name
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
}