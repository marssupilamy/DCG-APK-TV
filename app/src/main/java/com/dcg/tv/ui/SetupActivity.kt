package com.dcg.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dcg.tv.R
import com.dcg.tv.data.PlaylistRepository
import java.net.URLEncoder

class SetupActivity : AppCompatActivity() {

    private lateinit var url: EditText
    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        url = findViewById(R.id.url)
        server = findViewById(R.id.server)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)

        PlaylistRepository.getUrl(this)?.let(::fill)

        findViewById<android.view.View>(R.id.saveUrl).setOnClickListener { save(url.text.toString().trim()) }
        findViewById<android.view.View>(R.id.saveXtream).setOnClickListener { saveXtream() }
        url.requestFocus()
    }

    /** Preenche os campos com o link atual. Links get.php também preenchem servidor, usuário e senha. */
    private fun fill(current: String) {
        url.setText(current)
        val uri = Uri.parse(current)
        val username = uri.getQueryParameter("username")
        val password = uri.getQueryParameter("password")
        if (uri.path?.endsWith("get.php") == true && username != null && password != null) {
            server.setText("${uri.scheme}://${uri.encodedAuthority}")
            user.setText(username)
            pass.setText(password)
        }
    }

    private fun saveXtream() {
        var host = server.text.toString().trim().trimEnd('/')
        val u = user.text.toString().trim()
        val p = pass.text.toString().trim()
        if (host.isEmpty() || u.isEmpty() || p.isEmpty()) {
            toast("Preencha servidor, usuário e senha.")
            return
        }
        if (!host.startsWith("http://", true) && !host.startsWith("https://", true)) host = "http://$host"
        save("$host/get.php?username=${enc(u)}&password=${enc(p)}&type=m3u_plus&output=ts")
    }

    private fun save(link: String) {
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
            toast("O link precisa começar com http:// ou https://")
            return
        }
        PlaylistRepository.setUrl(this, link)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
