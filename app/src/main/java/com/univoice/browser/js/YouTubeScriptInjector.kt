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
                        .ytp-ad-message-container,
                        .ytp-ad-action-interstitial,
                        .ytp-ad-progress,
                        #player-ads,
                        ytm-statement-banner-renderer,
                        .sparkles-light-cta,
                        .promoted-item,
                        .open-in-app,
                        ytm-open-app-tool-bar-renderer {
                            display: none !important;
                            visibility: hidden !important;
                            height: 0 !important;
                            pointer-events: none !important;
                        }
                        .caption-window,
                        .player-caption-window,
                        .ytp-caption-window-container {
                            pointer-events: none !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                    log("広告ブロック用 CSS を注入しました");
                } catch(e) {
                    log("広告ブロック CSS 注入エラー: " + e.message);
                }
            }

            // ネイティブの requestFullscreen 関数参照を保持（無限再帰防止）
            const nativeRequestFullscreen = Element.prototype.requestFullscreen || 
                                           Element.prototype.webkitRequestFullscreen || 
                                           Element.prototype.webkitRequestFullScreen;

            let isEnteringFullscreen = false;

            function enterVideoFullscreen() {
                if (isEnteringFullscreen) return true;
                const video = document.querySelector('video');
                if (!video) {
                    log("動画要素が見つかりません");
                    return false;
                }
                log("HTML5動画のネイティブ全画面表示（最大化）を実行します");
                isEnteringFullscreen = true;
                try {
                    if (nativeRequestFullscreen) {
                        try {
                            const res = nativeRequestFullscreen.call(video);
                            if (res && typeof res.then === 'function') {
                                res.catch(function(e) {
                                    log("nativeRequestFullscreen rejected: " + e.message);
                                });
                            }
                            log("nativeRequestFullscreen.call(video) 実行成功");
                            return true;
                        } catch(e) {
                            log("nativeRequestFullscreen.call(video) 例外: " + e.message);
                        }
                    }
                } finally {
                    setTimeout(function() { isEnteringFullscreen = false; }, 600);
                }
                return false;
            }

            // 全画面APIのフォールバック・保証 & Element.prototype.requestFullscreen のフック
            try {
                if (!document.fullscreenEnabled) {
                    Object.defineProperty(document, 'fullscreenEnabled', {
                        get: function() { return true; },
                        configurable: true
                    });
                }
                Element.prototype.requestFullscreen = function() {
                    log("Element.prototype.requestFullscreen 呼び出しを検知 -> enterVideoFullscreen() へ転送");
                    enterVideoFullscreen();
                    return Promise.resolve();
                };
                if (Element.prototype.webkitRequestFullscreen) {
                    Element.prototype.webkitRequestFullscreen = Element.prototype.requestFullscreen;
                }
                if (Element.prototype.webkitRequestFullScreen) {
                    Element.prototype.webkitRequestFullScreen = Element.prototype.requestFullscreen;
                }
            } catch(e) {
                log("全画面APIフック例外: " + e.message);
            }

            // ==========================================
            // 0.1 バックグラウンド再生保護 (Page Visibility API 偽装)
            // 画面消灯やバックグラウンド時でも YouTube の自動ポーズを防ぐ
            // ==========================================
            try {
                Object.defineProperty(document, 'hidden', {
                    get: function() { return false; },
                    configurable: true
                });
                Object.defineProperty(document, 'visibilityState', {
                    get: function() { return 'visible'; },
                    configurable: true
                });
                Object.defineProperty(document, 'webkitHidden', {
                    get: function() { return false; },
                    configurable: true
                });
                Object.defineProperty(document, 'webkitVisibilityState', {
                    get: function() { return 'visible'; },
                    configurable: true
                });
                // visibilitychange イベントの発火をインターセプトして動画停止を防止
                window.addEventListener('visibilitychange', function(e) {
                    e.stopImmediatePropagation();
                }, true);
                document.addEventListener('visibilitychange', function(e) {
                    e.stopImmediatePropagation();
                }, true);
                log("バックグラウンド再生保護（Page Visibility API 偽装）を適用しました");
            } catch(e) {
                log("Visibility APIフック例外: " + e.message);
            }

            // YouTube DOM上の全画面ボタンのタップを確実に捕捉するキャプチャフェーズ・イベントリスナー
            function handleFullscreenInteraction(e) {
                const target = e.target;
                if (!target) return;
                const fsBtn = target.closest && target.closest(
                    '.fullscreen-icon, .ytp-fullscreen-button, button[aria-label*="全画面"], button[aria-label*="フルスクリーン"], button[aria-label*="Fullscreen"], ytm-custom-control[aria-label*="全画面"]'
                );
                if (fsBtn) {
                    log("YouTube側の全画面ボタンタップをインターセプト -> enterVideoFullscreen()");
                    e.preventDefault();
                    e.stopPropagation();
                    enterVideoFullscreen();
                }
            }
            document.addEventListener('click', handleFullscreenInteraction, true);
            document.addEventListener('touchend', handleFullscreenInteraction, true);

            // ==========================================
            // 1. HTML5 <video> 音声の完全抑制 (Mute Enforcement)
            // ==========================================
            let audioSuppressionEnabled = ${enableAudioSuppression};

            window.__univoice_set_audio_suppression = function(enabled) {
                audioSuppressionEnabled = !!enabled;
                const videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    if (audioSuppressionEnabled) {
                        v.muted = true;
                        v.volume = 0;
                    } else {
                        v.muted = false;
                        v.volume = 1.0;
                    }
                });
                log("原音ミュート切替: audioSuppressionEnabled=" + audioSuppressionEnabled);
            };

            function enforceMute(video) {
                if (!audioSuppressionEnabled || !video) return;
                try {
                    if (!video.muted) {
                        video.muted = true;
                    }
                    if (video.volume > 0) {
                        video.volume = 0;
                    }
                } catch (e) {}
            }

            function tryAutoTranslateToJapanese() {
                try {
                    const player = document.querySelector('#movie_player, .html5-video-player');
                    if (player && typeof player.setOption === 'function') {
                        player.setOption('captions', 'track', { languageCode: 'ja' });
                        player.setOption('captions', 'translationLanguage', { languageCode: 'ja', displayName: 'Japanese' });
                    }
                } catch(_e) {}
            }

            // キャプショントラックの先行取得（一時停止中・再生中の長大先読み用）
            let lastFetchedTrackBaseUrl = "";
            let prefetchRunning = false;
            function prefetchCaptionTrack() {
                if (prefetchRunning) return;
                try {
                    const player = document.querySelector('#movie_player, .html5-video-player');
                    if (!player || typeof player.getPlayerResponse !== 'function') return;
                    const pr = player.getPlayerResponse();
                    const tracks = pr && pr.captions && pr.captions.playerCaptionsTracklistRenderer && pr.captions.playerCaptionsTracklistRenderer.captionTracks;
                    if (!tracks || tracks.length === 0) return;

                    // 英語または最初のキャプショントラックを選択
                    let targetTrack = tracks.find(function(t) { return t.languageCode === 'en' || (t.vssId && t.vssId.indexOf('.en') !== -1); }) || tracks[0];
                    if (!targetTrack || !targetTrack.baseUrl) return;
                    if (targetTrack.baseUrl === lastFetchedTrackBaseUrl) return;

                    lastFetchedTrackBaseUrl = targetTrack.baseUrl;
                    prefetchRunning = true;
                    log("YouTube 字幕トラック (timedtext) 先読みフェッチを開始: " + targetTrack.name?.simpleText || targetTrack.languageCode);

                    fetch(targetTrack.baseUrl + '&fmt=json3')
                        .then(function(res) { return res.json(); })
                        .then(function(data) {
                            prefetchRunning = false;
                            if (!data || !data.events) return;
                            const video = document.querySelector('video');
                            const currentMs = video ? Math.floor(video.currentTime * 1000) : 0;

                            let queuedCount = 0;
                            data.events.forEach(function(ev) {
                                if (!ev.segs || !ev.tStartMs) return;
                                const start = ev.tStartMs;
                                const dur = ev.dDurationMs || 3000;
                                const end = start + dur;

                                // 現在再生位置から先、または直前直後のみを先読み対象（最大30件）
                                if (end >= currentMs - 2000 && queuedCount < 30) {
                                    const text = ev.segs.map(function(s) { return s.utf8 || ''; }).join('').trim();
                                    const sanitized = sanitizeCaption(text);
                                    if (sanitized && sanitized.length >= 2) {
                                        queuedCount++;
                                        if (bridge && bridge.onSubtitleReceived) {
                                            bridge.onSubtitleReceived(start, end, sanitized);
                                        }
                                    }
                                }
                            });
                            log("字幕トラックから " + queuedCount + " 件の字幕を事前バッファリングしました");
                        })
                        .catch(function(err) {
                            prefetchRunning = false;
                            log("字幕トラック先行取得警告: " + err.message);
                        });
                } catch(e) {
                    prefetchRunning = false;
                }
            }

            // 外部（ネイティブトップバー等）から呼び出し可能な操作ヘルパー
            window.__univoice_toggle_play_pause = function() {
                try {
                    const video = document.querySelector('video');
                    if (video) {
                        if (video.paused) {
                            video.play();
                        } else {
                            video.pause();
                        }
                    }
                } catch(e) {
                    log("再生切替例外: " + e.message);
                }
            };

            window.__univoice_toggle_cc = function() {
                try {
                    const player = document.querySelector('#movie_player, .html5-video-player');
                    if (player && typeof player.toggleSubtitles === 'function') {
                        player.toggleSubtitles();
                    } else if (player && typeof player.toggleSubtitlesOn === 'function') {
                        player.toggleSubtitlesOn();
                    } else {
                        const btn = document.querySelector('.ytp-subtitles-button, button[aria-label*="字幕"], button[aria-label*="Captions"], ytm-custom-control[aria-label*="字幕"]');
                        if (btn) btn.click();
                    }
                } catch(e) {
                    log("CC切替例外: " + e.message);
                }
            };

            window.__univoice_toggle_fullscreen = function() {
                try {
                    if (document.fullscreenElement || document.webkitFullscreenElement) {
                        if (document.exitFullscreen) document.exitFullscreen();
                        else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
                    } else {
                        enterVideoFullscreen();
                    }
                } catch(e) {
                    log("全画面切替例外: " + e.message);
                }
            };

            function attachVideoListeners(video) {
                if (video.__univoice_attached) return;
                video.__univoice_attached = true;

                enforceMute(video);

                video.addEventListener('play', function() {
                    enforceMute(video);
                    tryAutoTranslateToJapanese();
                    if (bridge && bridge.onVideoStateChanged) {
                        const currentMs = Math.floor(video.currentTime * 1000);
                        bridge.onVideoStateChanged(true, currentMs);
                    }
                }, false);

                video.addEventListener('pause', function() {
                    prefetchCaptionTrack();
                    if (bridge && bridge.onVideoStateChanged) {
                        const currentMs = Math.floor(video.currentTime * 1000);
                        bridge.onVideoStateChanged(false, currentMs);
                    }
                }, false);

                video.addEventListener('volumechange', function() {
                    enforceMute(video);
                }, false);

                video.addEventListener('timeupdate', function() {
                    if (bridge && bridge.onVideoStateChanged) {
                        const isPlaying = !video.paused && !video.ended && video.readyState > 2;
                        const currentMs = Math.floor(video.currentTime * 1000);
                        bridge.onVideoStateChanged(isPlaying, currentMs);
                    }
                }, false);

                log("対象Video要素へのフック完了 (タッチ透過保護適用)");
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
            // ==========================================
            // 3. 字幕インターセプト (Subtitle Interception) & 賢い文単位デバウンス
            // ==========================================
            let lastEmittedSentence = "";
            let pendingCaptionText = "";
            let captionDebounceTimer = null;
            let debounceStartTime = 0;

            function sanitizeCaption(raw) {
                if (!raw) return "";
                // 1. YouTube UIのゴミ文字列を除去 (設定ボタン、言語ラベル等)
                let text = raw
                    .replace(/\[?(?:英語|日本語|English|Japanese)?\s*\(?(?:自動生成|auto-generated)\)?\s*(?:を?クリックして設定)?\]?/gi, '')
                    .replace(/を?クリックして設定/gi, '')
                    .replace(/\s+/g, ' ')
                    .trim();

                // 2. 2重重複（前半と後半が同一テキスト）の検知・解消
                const halfLen = Math.floor(text.length / 2);
                if (halfLen >= 4) {
                    for (let offset = -1; offset <= 1; offset++) {
                        const splitIdx = halfLen + offset;
                        if (splitIdx > 2 && splitIdx < text.length) {
                            const firstHalf = text.substring(0, splitIdx).trim();
                            const secondHalf = text.substring(splitIdx).trim();
                            if (firstHalf.length >= 4 && firstHalf === secondHalf) {
                                text = firstHalf;
                                break;
                            }
                        }
                    }
                }
                return text.trim();
            }

            function flushCaption() {
                if (!pendingCaptionText) return;
                let sentence = pendingCaptionText;
                pendingCaptionText = "";

                if (sentence === lastEmittedSentence) return;

                // 前回確定文と今回の文の間で、ローリング字幕特有の単語重複を除去
                if (lastEmittedSentence) {
                    const prevWords = lastEmittedSentence.split(/\s+/);
                    const cleanWords = sentence.split(/\s+/);
                    for (let n = Math.min(5, cleanWords.length - 1); n >= 1; n--) {
                        const headOfClean = cleanWords.slice(0, n).join(' ').toLowerCase().replace(/[.,?!]/g, '');
                        const tailOfPrev = prevWords.slice(-n).join(' ').toLowerCase().replace(/[.,?!]/g, '');
                        if (headOfClean === tailOfPrev && headOfClean.length >= 2) {
                            sentence = cleanWords.slice(n).join(' ');
                            break;
                        }
                    }
                }

                sentence = sentence.trim();
                if (sentence.length < 2) return;
                if (sentence === lastEmittedSentence) return;

                lastEmittedSentence = pendingCaptionText || sentence;
                const video = document.querySelector('video');
                const nowMs = video ? Math.floor(video.currentTime * 1000) : Date.now();
                const estimatedDurationMs = Math.max(2800, Math.min(7500, sentence.length * 85));
                const endMs = nowMs + estimatedDurationMs;

                log("字幕確定（文単位・重複解消済）: [" + nowMs + "ms] " + sentence);
                if (bridge && bridge.onSubtitleReceived) {
                    bridge.onSubtitleReceived(nowMs, endMs, sentence);
                }
            }

            function processCaptionText(rawText) {
                if (!rawText) return;
                if (isAdActive()) return;

                const clean = sanitizeCaption(rawText);
                if (!clean || clean.length < 2) return;
                if (clean === lastEmittedSentence) return;

                const now = Date.now();
                if (!pendingCaptionText) {
                    debounceStartTime = now;
                }
                pendingCaptionText = clean;

                // 文末記号で終わるか、一定時間経過した場合は短時間(350ms)で確定送信
                const isSentenceEnd = /[.?!。！？]$/.test(clean);
                const isOverMaxWait = (now - debounceStartTime) > 2200;
                const delay = (isSentenceEnd || isOverMaxWait) ? 350 : 650;

                if (captionDebounceTimer) {
                    clearTimeout(captionDebounceTimer);
                }
                captionDebounceTimer = setTimeout(function() {
                    debounceStartTime = 0;
                    flushCaption();
                }, delay);
            }

            // 字幕DOM要素の監視 (二重取得の防止)
            function observeCaptions() {
                const captionObserver = new MutationObserver(function(mutations) {
                    // 最も内側の字幕セグメントのみを取得（親要素との二重取得を完全防止）
                    const segments = document.querySelectorAll('.ytp-caption-segment');
                    let rawText = '';

                    if (segments && segments.length > 0) {
                        segments.forEach(function(el) {
                            if (el.innerText) {
                                rawText += ' ' + el.innerText;
                            }
                        });
                    } else {
                        // セグメントがない場合のフォールバック（ボタン要素等を除去して取得）
                        const windows = document.querySelectorAll('.caption-window, .player-caption-window');
                        windows.forEach(function(w) {
                            try {
                                const clone = w.cloneNode(true);
                                const buttons = clone.querySelectorAll('button, .ytp-button, [role="button"]');
                                buttons.forEach(function(b) { b.remove(); });
                                if (clone.innerText) {
                                    rawText += ' ' + clone.innerText;
                                }
                            } catch (_e) {}
                        });
                    }

                    if (rawText.trim().length > 0) {
                        processCaptionText(rawText);
                    }
                });

                captionObserver.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true,
                    characterData: true
                });

                log("字幕MutationObserver設定完了 (文単位デバウンス＆重複除去適用)");
            }

            // YouTubeの字幕状態の監視＆ネイティブ通知（ユーザー操作やタッチイベントを阻害しない受動的チェック）
            let lastReportedCaptionState = null;
            let autoCcAttemptedForVideo = false;

            function checkCaptionState() {
                try {
                    // 画面内に字幕セグメントが表示されているか確認
                    const activeSegments = document.querySelectorAll('.ytp-caption-segment, .caption-window, .player-caption-window');
                    let isCcActive = (activeSegments && activeSegments.length > 0);

                    const ccSelectors = [
                        '.ytp-subtitles-button',
                        'button[aria-label*="字幕"]',
                        'button[aria-label*="Captions"]',
                        'button[aria-label*="Subtitles"]',
                        'ytm-custom-control[aria-label*="字幕"]'
                    ];

                    let foundButton = null;
                    for (let i = 0; i < ccSelectors.length; i++) {
                        const btn = document.querySelector(ccSelectors[i]);
                        if (btn) {
                            foundButton = btn;
                            const pressed = btn.getAttribute('aria-pressed');
                            const label = (btn.getAttribute('aria-label') || '').toLowerCase();
                            if (pressed === 'true' || label.indexOf('オフ') !== -1 || label.indexOf('turn off') !== -1) {
                                isCcActive = true;
                                break;
                            }
                        }
                    }

                    // 初回動画再生時に未ONなら1度だけONを試行（ユーザーのタッチや画面操作を奪わない）
                    if (!isCcActive && !autoCcAttemptedForVideo) {
                        const video = document.querySelector('video');
                        if (video && !video.paused) {
                            autoCcAttemptedForVideo = true;
                            if (foundButton && typeof foundButton.click === 'function') {
                                foundButton.click();
                                isCcActive = true;
                            } else {
                                const player = document.querySelector('#movie_player, .html5-video-player');
                                if (player && typeof player.toggleSubtitlesOn === 'function') {
                                    player.toggleSubtitlesOn();
                                    isCcActive = true;
                                }
                            }
                            tryAutoTranslateToJapanese();
                        }
                    }

                    if (lastReportedCaptionState !== isCcActive) {
                        lastReportedCaptionState = isCcActive;
                        if (bridge && bridge.onCaptionStateChanged) {
                            bridge.onCaptionStateChanged(isCcActive);
                        }
                    }
                } catch(e) {
                    // silent
                }
            }

            // プレビュー再生時のミュート解除オーバーレイを自動解除し、操作コントロールを解放
            let previewMuteDismissed = false;
            function autoDismissPreviewMute() {
                if (previewMuteDismissed) return;
                try {
                    const previewMuteBtn = document.querySelector(
                        '.ytp-unmute, .ytp-unmute-button, .ytm-player-preview-mute-button, ' +
                        '.preview-mute-button, .ytm-player-preview-unmute-button, ' +
                        'button[aria-label*="ミュートを解除"], button[aria-label*="ミュート解除"], ' +
                        'button[aria-label*="tap to unmute" i], button[aria-label*="unmute" i], ' +
                        'ytm-player-preview-mute-button'
                    );
                    if (previewMuteBtn && previewMuteBtn.offsetParent !== null && typeof previewMuteBtn.click === 'function') {
                        log("プレビューのミュート解除ボタンを検知・自動タップしてプレイヤー操作を解放します");
                        previewMuteBtn.click();
                        previewMuteDismissed = true;
                    }
                } catch(e) {
                    // silent
                }
            }

            // ==========================================
            // 4. ループ・DOMポーリングによる堅牢性確保
            // ==========================================
            function handlePageNavigation() {
                log("YouTube ページ遷移を検知: " + window.location.href);
                autoCcAttemptedForVideo = false;
                previewMuteDismissed = false;
                lastFetchedTrackBaseUrl = "";
                lastReportedCaptionState = null;
                pendingCaptionText = "";
                lastEmittedSentence = "";
                tryAutoTranslateToJapanese();
                autoDismissPreviewMute();
                prefetchCaptionTrack();
                const videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    attachVideoListeners(v);
                    enforceMute(v);
                });
            }

            window.addEventListener('yt-navigate-finish', handlePageNavigation);
            window.addEventListener('popstate', handlePageNavigation);

            setInterval(function() {
                const videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    attachVideoListeners(v);
                    enforceMute(v);
                });
                autoDismissPreviewMute();
                handleVideoAds();
                checkCaptionState();
                prefetchCaptionTrack();
            }, 1500);

            observeCaptions();
            log("UniVoice JavaScript 初期化完了");
        })();
        """.trimIndent()
    }

    /**
     * 実行中の動画に対して動的に原音ミュートのON/OFFを切り替えるスクリプト
     */
    fun buildToggleMuteScript(muted: Boolean): String {
        return """
        (function() {
            if (typeof window.__univoice_set_audio_suppression === 'function') {
                window.__univoice_set_audio_suppression($muted);
            }
        })();
        """.trimIndent()
    }
}
