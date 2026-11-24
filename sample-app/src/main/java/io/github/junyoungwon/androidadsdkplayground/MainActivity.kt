package io.github.junyoungwon.androidadsdkplayground

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import io.github.junyoungwon.adsdk.BannerAdError
import io.github.junyoungwon.adsdk.BannerAdListener
import io.github.junyoungwon.adsdk.BannerAdRequest
import io.github.junyoungwon.adsdk.BannerAdView
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var banner: BannerAdView
    private lateinit var responseDelay: SeekBar
    private val screenId = UUID.randomUUID().toString().take(8)
    private var loadNumber = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        record("생성 · 저장 상태 복원=${savedInstanceState != null}")
        findViewById<TextView>(R.id.sample_screen_info).text = getString(R.string.sample_screen_info, screenId)
        responseDelay = findViewById(R.id.response_delay)
        val delayLabel = findViewById<TextView>(R.id.response_delay_label)
        responseDelay.progress = savedInstanceState?.getInt(RESPONSE_DELAY_KEY) ?: 0
        delayLabel.text = getString(R.string.response_delay_label, responseDelay.progress)
        responseDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                delayLabel.text = getString(R.string.response_delay_label, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        val content = findViewById<View>(R.id.sample_content)
        val left = content.paddingLeft
        val top = content.paddingTop
        val right = content.paddingRight
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                left + systemBars.left,
                top + systemBars.top,
                right + systemBars.right,
                bottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(content)

        val container = findViewById<FrameLayout>(R.id.banner_container)
        val status = findViewById<TextView>(R.id.sample_status)
        val load = findViewById<Button>(R.id.load_banner)
        val show = findViewById<Button>(R.id.show_banner)
        val destroy = findViewById<Button>(R.id.destroy_banner)
        fun createBanner() {
            container.removeAllViews()
            banner = BannerAdView(this)
            container.addView(banner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            load.isEnabled = true
            destroy.isEnabled = true
            show.isEnabled = false
            status.setText(R.string.sample_status)
        }
        createBanner()
        val scenarios = listOf("normal", "no-fill", "http-error", "invalid-response", "image-failure", "slow")
        load.setOnClickListener {
            status.setText(R.string.loading_banner)
            show.isEnabled = false
            // 광고를 요청할 서버 URL을 입력하는 EditText
            val endpoint = findViewById<EditText>(R.id.server_url).text.toString().trim()
            // 광고 표시 시나리오: 정상, 광고 없음, 서버 오류, 잘못된 응답, 이미지 준비 실패, 응답 지연 및 시간 초과
            val scenario = scenarios[findViewById<Spinner>(R.id.scenario).selectedItemPosition]
            // EditText에 입력된 URL 주소
            val delayMs = responseDelay.progress * 1000
            val url = endpoint.toUri().buildUpon()
                .appendQueryParameter("scenario", scenario)
                .appendQueryParameter("delayMs", delayMs.toString())
                .build()
            val requestNumber = ++loadNumber
            record("로드 시작 · 요청=$requestNumber 시나리오=$scenario 지연=${delayMs}ms")
            banner.load(BannerAdRequest("demo-banner", url.toString()), object : BannerAdListener {
                override fun onLoaded() {
                    record("로드 성공 · 요청=$requestNumber 종료된 화면=$isDestroyed")
                    status.setText(R.string.banner_ready)
                    show.isEnabled = true
                }

                override fun onFailed(error: BannerAdError) {
                    record("로드 실패 · 요청=$requestNumber 코드=${error.code} 종료된 화면=$isDestroyed")
                    status.text = getString(R.string.banner_failed, error.code.name, error.message)
                }
            })
        }
        show.setOnClickListener {
            status.setText(if (banner.show()) R.string.banner_bound else R.string.banner_unavailable)
        }
        destroy.setOnClickListener {
            banner.destroy()
            show.isEnabled = false
            load.isEnabled = false
            destroy.isEnabled = false
            status.setText(R.string.banner_destroyed)
        }
        findViewById<Button>(R.id.reset_banner).setOnClickListener {
            banner.destroy()
            createBanner()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(RESPONSE_DELAY_KEY, responseDelay.progress)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        record("종료 · 구성 변경=$isChangingConfigurations")
        banner.destroy()
        super.onDestroy()
    }

    private fun record(message: String) {
        Log.d("BannerSample", "화면=$screenId $message")
    }

    private companion object {
        const val RESPONSE_DELAY_KEY = "response-delay-seconds"
    }
}
