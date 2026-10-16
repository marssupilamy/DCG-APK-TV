package com.dcg.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dcg.tv.R
import com.dcg.tv.data.PlaylistRepository
import com.dcg.tv.data.Source
import com.dcg.tv.data.Xtream
import com.dcg.tv.data.XtreamAccount

class SetupActivity : AppCompatActivity() {

    private lateinit var url: EditText
    private lateinit var servers: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        url = findViewById(R.id.url)
        servers = findViewById(R.id.server)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)

        when (val source = PlaylistRepository.getSource(this)) {
            is Source.M3u -> url.setText(source.url)
            is Source.Xtream -> {
                servers.setText(source.account.servers.joinToString(" "))
                user.setText(source.account.user)
                pass.setText(source.account.pass)
            }
            null -> Unit
        }

        findViewById<View>(R.id.saveUrl).setOnClickListener { saveUrl() }
        findViewById<View>(R.id.saveXtream).setOnClickListener { saveXtream() }
        servers.requestFocus()
    }

    private fun saveUrl() {
        val link = url.text.toString().trim()
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
            toast("O link precisa começar com http:// ou https://")
            return
        }
        // Links get.php usam o acesso por usuário e senha, que é mais estável.
        save(PlaylistRepository.asXtream(link) ?: Source.M3u(link))
    }

    private fun saveXtream() {
        val list = Xtream.parseServers(servers.text.toString())
        val u = user.text.toString().trim()
        val p = pass.text.toString().trim()
        if (list.isEmpty() || u.isEmpty() || p.isEmpty()) {
            toast("Preencha servidor, usuário e senha.")
            return
        }
        save(Source.Xtream(XtreamAccount(list, u, p)))
    }

    private fun save(source: Source) {
        PlaylistRepository.setSource(this, source)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
