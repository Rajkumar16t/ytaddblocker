package com.example.ytplayer

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Request-level blocking plus the DOM-level CSS/JS injected after page load. */
object AdBlocker {

    // Substring matches against the full, lower-cased URL.
    private val blockedFragments = listOf(
        "googleads",
        "pagead",
        "doubleclick",
        "googlesyndication",
        "googleadservices",
        "adservice.google",
        "google-analytics.com",
        "googletagmanager.com",
        "/api/stats/ads",
        "/ptracking",
        "/pagead/",
        "get_midroll_info",
        "ad_break",
        "&adformat=",
        "/youtubei/v1/player/ad_break",
        "imasdk.googleapis.com",
        "static.doubleclick.net",
        "2mdn.net",
    )

    fun isBlocked(url: String): Boolean {
        val u = url.lowercase()
        return blockedFragments.any { u.contains(it) }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private const val HIDE_SELECTORS = """
        .ad-container,
        ytd-promoted-video-renderer,
        ytm-promoted-video-renderer,
        ytm-promoted-sparkles-web-renderer,
        ytm-companion-ad-renderer,
        ytd-ad-slot-renderer,
        ad-slot-renderer,
        ytd-display-ad-renderer,
        ytd-banner-promo-renderer,
        ytm-statement-banner-renderer,
        #player-ads,
        .video-ads,
        .ytp-ad-module,
        .ytp-ad-overlay-container,
        .ytp-ad-image-overlay,
        .ytp-ad-text-overlay
    """

    /** Safe to call repeatedly: guarded by window.__ytAdBlock so only one loop ever runs. */
    val injectionScript: String = """
        (function () {
          if (window.__ytAdBlock) return;
          window.__ytAdBlock = true;

          var style = document.createElement('style');
          style.id = '__yt_adblock_css';
          style.textContent = `${HIDE_SELECTORS.trimIndent().replace("\n", " ")} { display: none !important; }`;
          (document.head || document.documentElement).appendChild(style);

          var mutedByUs = false;

          function click(sel) {
            var nodes = document.querySelectorAll(sel);
            for (var i = 0; i < nodes.length; i++) { try { nodes[i].click(); } catch (e) {} }
          }

          setInterval(function () {
            var player = document.querySelector('.html5-video-player');
            var video = document.querySelector('video');

            var adActive =
              (player && (player.classList.contains('ad-showing') ||
                          player.classList.contains('ad-interrupting'))) ||
              !!document.querySelector('.ytp-ad-player-overlay, .ytp-ad-text, .ytp-ad-preview-container');

            // Skip buttons + overlay close buttons
            click('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
            click('.ytp-ad-overlay-close-button');

            if (video) {
              if (adActive) {
                if (!video.muted) { video.muted = true; mutedByUs = true; }
                if (isFinite(video.duration) && video.duration > 0) {
                  try { video.playbackRate = 16; } catch (e) {}
                  video.currentTime = video.duration;
                }
              } else if (mutedByUs) {
                video.muted = false;
                try { video.playbackRate = 1; } catch (e) {}
                mutedByUs = false;
              }
            }
          }, 250);
        })();
    """.trimIndent()
}
