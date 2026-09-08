package com.univoice.browser.js

/**
 * YouTubeウェブプレーヤー用のDOM音声ミュート強制および字幕インターセプトを行う高信頼性JavaScriptスクリプト集
 */
object YouTubeScriptInjector {

    /**
     * YouTube判定
     */
    fun isYouTubeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    /**
     * 音声抑制（ミュート強制）＆字幕インターセプト＆広告ブロックスクリプト本体を生成
     * @param enableAudioSuppression 元音声をミュートするかどうか
     * @param enableAdBlock 広告ブロック・動画広告自動スキップを有効にするかどうか
     */
    fun buildInjectionScript(enableAudioSuppression: Boolean = true, enableAdBlock: Boolean = true): String {
        return """
        (function() {
            if (window.__univoice_injected) {
                console.log("[UniVoiceBrowser] すでにスクリプトは注入済みです");
                return;
            }
            window.__univoice_injected = true;

            const bridge = window.UniVoiceBridge;
            function log(msg) {
                if (bridge && bridge.log) {
                    bridge.log(msg);
                } else {
                    console.log("[UniVoiceBrowser-JS] " + msg);
                }
            }

            log("UniVoice JavaScript コアインジェクション開始 (音声抑制=" + ${enableAudioSuppression} + ", 広告ブロック=" + ${enableAdBlock} + ")");

            // ==========================================
            // 0. 広告ブロック CSS インジェクション (Ad-Block CSS)
            // ==========================================
            const adBlockEnabled = ${enableAdBlock};
            if (adBlockEnabled) {
                try {
                    const style = document.createElement('style');
                    style.id = '__univoice_adblock_styles';
                    style.textContent = `
                        ytm-promoted-sparkles-web-renderer,
                        ytm-promoted-video-renderer,
                        ytm-companion-ad-renderer,
                        .ytp-ad-overlay-container,
                        .ytp-ad-message-container,
                        .ytp-ad-action-interstitial,
                        .ytp-ad-progress,
                        #player-ads,
                        ytm-statement-banner-renderer,
                        .sparkles-light-cta,
                        .promoted-item,
                        .ytp-ad-preview-container,
                        .open-in-app,
                        ytm-open-app-tool-bar-renderer {
                            display: none !important;
                            visibility: hidden !important;
                            height: 0 !important;
                            pointer-events: none !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                    log("広告ブロック用 CSS を注入しました");
                } catch(e) {
                    log("広告ブロック CSS 注入エラー: " + e.message);
                }
            }

            // ==========================================
            // 1. HTML5 <video> 音声の完全抑制 (Mute Enforcement)
            // ==========================================
            const audioSuppressionEnabled = ${enableAudioSuppression};

            function enforceMute(video) {
                if (!audioSuppressionEnabled || !video) return;
                try {
                    if (!video.muted) {
                        video.muted = true;
                    }
                    if (bridge && bridge.onAudioSuppressed) {
                        bridge.onAudioSuppressed(true);
                    }
                } catch (e) {
                    log("Mute適用エラー: " + e.message);
                }
            }

            function attachVideoListeners(video) {
                if (video.__univoice_attached) return;
                video.__univoice_attached = true;

                enforceMute(video);

                ['play', 'playing', 'volumechange', 'ratechange', 'loadedmetadata'].forEach(function(evt) {
                    video.addEventListener(evt, function() {
                        enforceMute(video);
                    }, true);
                });

                video.addEventListener('timeupdate', function() {
                    if (bridge && bridge.onVideoStateChanged) {
                        const isPlaying = !video.paused && !video.ended && video.readyState > 2;
                        const currentMs = Math.floor(video.currentTime * 1000);
                        bridge.onVideoStateChanged(isPlaying, currentMs);
                    }
                }, false);

                log("対象Video要素へのフック完了");
            }

            // ==========================================
            // 2. 動画広告の自動検知＆高速スキップ (Video Ad Auto-Skip)
            // ==========================================
            function isAdActive() {
                if (!adBlockEnabled) return false;
                const player = document.querySelector('#movie_player, .html5-video-player');
                if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {
                    return true;
                }
                const adShowingEl = document.querySelector('.ad-showing, .ad-interrupting');
                return adShowingEl !== null;
            }

            function handleVideoAds() {
                if (!adBlockEnabled) return;
                try {
                    // スキップボタン自動クリック（YouTubeモバイル & PC共通）
                    const skipSelectors = [
                        '.ytp-ad-skip-button',
                        '.ytp-ad-skip-button-modern',
                        '.videoAdUiSkipButton',
                        'button.ytp-ad-skip-button-icon',
                        '.ytp-ad-skip-button-container button',
                        '.ytp-ad-skip-button-slot button',
                        'button[class*="skip-button"]'
                    ];
                    let clickedSkip = false;
                    skipSelectors.forEach(function(sel) {
                        const btns = document.querySelectorAll(sel);
                        btns.forEach(function(b) {
                            if (b && typeof b.click === 'function') {
                                b.click();
                                clickedSkip = true;
                                log("動画広告スキップボタンを自動クリックしました");
                            }
                        });
                    });

                    // 広告がアクティブであると確実に判明している場合のみ、広告動画を早送りスキップ
                    if (isAdActive()) {
                        const videos = document.querySelectorAll('video');
                        videos.forEach(function(v) {
                            // 本編動画の誤スキップを防止するため、広告再生中かつ180秒以下の短尺動画のみ終端へジャンプ
                            if (isFinite(v.duration) && v.duration > 0 && v.duration <= 180 && v.currentTime < v.duration) {
                                v.currentTime = v.duration;
                                v.playbackRate = 16.0;
                                log("広告動画を終端へスキップしました");
                            }
                        });
                    }

                    // オーバーレイ広告閉じるボタン自動クリック
                    const closeBtns = document.querySelectorAll('.ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container, .ytp-ad-image-overlay .close-button');
                    closeBtns.forEach(function(cb) {
                        if (cb && typeof cb.click === 'function') cb.click();
                    });
                } catch (e) {
                    // スキップ試行例外の安全な処理
                }
            }

            // ==========================================
            // 3. 字幕インターセプト (Subtitle Interception)
            // ==========================================
            let lastEmittedText = "";
            let lastEmittedTime = 0;

            function processCaptionText(rawText) {
                if (!rawText) return;
                // 広告動画の再生中は字幕インターセプトを保留（翻訳エンジンの誤爆防止）
                if (isAdActive()) {
                    log("広告再生中のため字幕キャプチャをスキップ");
                    return;
                }

                const clean = rawText.replace(/\s+/g, ' ').trim();
                if (clean.length === 0) return;

                const video = document.querySelector('video');
                const nowMs = video ? Math.floor(video.currentTime * 1000) : Date.now();

                // 重複通知の抑止（同一テキストかつ2秒以内の再検知を無視）
                if (clean === lastEmittedText && (nowMs - lastEmittedTime) < 2000) {
                    return;
                }

                lastEmittedText = clean;
                lastEmittedTime = nowMs;

                // 推定表示時間（文字数に応じた3〜5秒のウィンドウ）
                const estimatedDurationMs = Math.max(2500, Math.min(6000, clean.length * 80));
                const endMs = nowMs + estimatedDurationMs;

                log("字幕キャプチャ成功: [" + nowMs + "ms] " + clean);
                if (bridge && bridge.onSubtitleReceived) {
                    bridge.onSubtitleReceived(nowMs, endMs, clean);
                }
            }

            // 字幕DOM要素の監視
            function observeCaptions() {
                const captionSelectors = [
                    '.ytp-caption-window-bottom',
                    '.ytp-caption-window-rollup',
                    '.caption-window',
                    '.ytp-caption-segment',
                    '#player-captions-container',
                    '.player-caption-window'
                ];

                const captionObserver = new MutationObserver(function(mutations) {
                    let combinedText = '';
                    const segments = document.querySelectorAll('.ytp-caption-segment, .caption-window, .player-caption-window');
                    if (segments && segments.length > 0) {
                        segments.forEach(function(el) {
                            if (el.innerText) {
                                combinedText += ' ' + el.innerText;
                            }
                        });
                    }

                    if (combinedText.trim().length > 0) {
                        processCaptionText(combinedText);
                    }
                });

                captionObserver.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true,
                    characterData: true
                });

                log("字幕MutationObserver設定完了");
            }

            // YouTubeの字幕ボタンを自動有効化（未ONの場合）
            function ensureCaptionsEnabled() {
                try {
                    const subButton = document.querySelector('.ytp-subtitles-button');
                    if (subButton && subButton.getAttribute('aria-pressed') === 'false') {
                        subButton.click();
                        log("YouTube字幕ボタンを自動クリックしました");
                    }
                } catch(e) {
                    log("字幕ボタンクリック試行失敗: " + e.message);
                }
            }

            // ==========================================
            // 4. ループ・DOMポーリングによる堅牢性確保
            // ==========================================
            setInterval(function() {
                const videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    attachVideoListeners(v);
                    enforceMute(v);
                });
                handleVideoAds();
                ensureCaptionsEnabled();
            }, 250);

            observeCaptions();
            log("UniVoice JavaScript 初期化完了");
        })();
        """.trimIndent()
    }
}
