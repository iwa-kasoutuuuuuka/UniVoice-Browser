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
                        .ad-container,
                        .ad-interrupting,
                        .ytp-ad-overlay-container,
                        .ytp-ad-message-container,
                        .ytp-ad-action-interstitial,
                        .ytp-ad-progress,
                        .video-ads,
                        #player-ads,
                        ad-slot-renderer,
                        ytm-statement-banner-renderer,
                        .sparkles-light-cta,
                        .promoted-item,
                        .ytp-ad-text,
                        .ytp-ad-preview-container {
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
                    if (!video.muted || video.volume > 0) {
                        video.muted = true;
                        video.volume = 0;
                        if (bridge && bridge.onAudioSuppressed) {
                            bridge.onAudioSuppressed(true);
                        }
                    }
                } catch (e) {
                    log("Mute適用エラー: " + e.message);
                }
            }

            // HTMLMediaElement プロトタイプの保護 (スクリプトからの自動ミュート解除を防止)
            if (audioSuppressionEnabled) {
                try {
                    const originalVolumeDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
                    const originalMutedDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'muted');

                    if (originalVolumeDescriptor && originalVolumeDescriptor.set) {
                        Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                            get: function() { return 0; },
                            set: function(val) {
                                originalVolumeDescriptor.set.call(this, 0);
                            },
                            configurable: true
                        });
                    }

                    if (originalMutedDescriptor && originalMutedDescriptor.set) {
                        Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
                            get: function() { return true; },
                            set: function(val) {
                                originalMutedDescriptor.set.call(this, true);
                            },
                            configurable: true
                        });
                    }
                } catch (e) {
                    log("プロトタイプオーバーライド注意: " + e.message);
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
                return document.querySelector('.ad-showing, .ad-interrupting, .video-ads, ad-slot-renderer') !== null ||
                       (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
            }

            function handleVideoAds() {
                if (!adBlockEnabled) return;
                try {
                    if (isAdActive()) {
                        const videos = document.querySelectorAll('video');
                        videos.forEach(function(v) {
                            // 広告動画の再生位置を終端に移動＆超高速再生で即終了
                            if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration) {
                                v.currentTime = v.duration;
                            }
                            v.playbackRate = 16.0;
                        });
                    }

                    // スキップボタン自動クリック
                    const skipSelectors = [
                        '.ytp-ad-skip-button',
                        '.ytp-ad-skip-button-modern',
                        '.videoAdUiSkipButton',
                        'button.ytp-ad-skip-button-icon',
                        '.ytp-ad-skip-button-container button'
                    ];
                    skipSelectors.forEach(function(sel) {
                        const btns = document.querySelectorAll(sel);
                        btns.forEach(function(b) {
                            if (b && typeof b.click === 'function') {
                                b.click();
                                log("動画広告スキップボタンを自動クリックしました");
                            }
                        });
                    });

                    // オーバーレイ広告閉じるボタン自動クリック
                    const closeBtns = document.querySelectorAll('.ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container');
                    closeBtns.forEach(function(cb) {
                        if (cb && typeof cb.click === 'function') cb.click();
                    });
                } catch (e) {
                    // スキップ試行例外の握りつぶし
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
