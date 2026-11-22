package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.ui.LumaCastApp
import com.example.ui.LumaCastViewModel
import com.example.ui.theme.LumaCastTheme

class MainActivity : AppCompatActivity() {

    private val viewModel: LumaCastViewModel by viewModels {
        val app = application as LumaCastApplication
        LumaCastViewModel.Factory(applicationContext, app.container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LumaCastTheme {
                LumaCastApp(viewModel = viewModel)
            }
        }
    }
}
