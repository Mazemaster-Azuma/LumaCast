/**
 * LumaCast Official Google Cast Custom Web Receiver (CAF v3)
 *
 * Implements:
 * - AudioTracksManager: track discovery, metadata, language identification, live switching
 * - TextTracksManager: dynamic external WebVTT/TTML track injection, live switching, styling
 * - Bidirectional custom Cast namespace (`urn:x-cast:com.lumacast.tracks`) confirming every
 *   audio/subtitle state change back to the Android Sender (Sender never marks a track active
 *   until Receiver confirms it).
 * - Millisecond subtitle delay offset & TV-side timed bitmap cue rendering for PGS/VobSub.
 */
(function () {
  'use strict';

  const NAMESPACE = 'urn:x-cast:com.lumacast.tracks';
  const PROTOCOL_VERSION = '1.0';

  const context = cast.framework.CastReceiverContext.getInstance();
  const playerManager = context.getPlayerManager();

  const state = {
    currentSubtitleDelayMs: 0,
    activeBitmapManifest: null,
    activeBitmapTrackId: null,
    lastAppliedStyle: null
  };

  function broadcastToSenders(payload) {
    try {
      context.sendCustomMessage(NAMESPACE, undefined, payload);
    } catch (err) {
      console.error('[LumaCast Receiver] Failed to send custom message:', err);
    }
  }

  function sendError(code, message, senderId) {
    const payload = {
      type: 'TRACK_ERROR',
      code: code,
      message: message
    };
    try {
      context.sendCustomMessage(NAMESPACE, senderId, payload);
    } catch (err) {
      console.error('[LumaCast Receiver] Error broadcast failed:', err);
    }
  }

  function serializeTrackSnapshot() {
    const audioManager = playerManager.getAudioTracksManager();
    const textManager = playerManager.getTextTracksManager();

    const audioTracks = audioManager ? (audioManager.getTracks() || []) : [];
    const activeAudio = audioManager ? audioManager.getActiveTrack() : null;

    const textTracks = textManager ? (textManager.getTracks() || []) : [];
    const activeTextTracks = textManager ? (textManager.getActiveTracks() || []) : [];

    return {
      type: 'TRACK_STATE_SYNC',
      protocolVersion: PROTOCOL_VERSION,
      supportsAudioTrackSwitching: Boolean(audioManager),
      audioTracks: audioTracks.map(function (t) {
        return {
          trackId: t.trackId,
          language: t.language || 'und',
          name: t.name || t.language || ('Track ' + t.trackId),
          trackContentType: t.trackContentType || '',
          roles: t.roles || []
        };
      }),
      activeAudioTrackId: activeAudio ? activeAudio.trackId : null,
      textTracks: textTracks.map(function (t) {
        return {
          trackId: t.trackId,
          language: t.language || 'und',
          name: t.name || t.language || ('Subtitle ' + t.trackId),
          trackContentId: t.trackContentId || '',
          trackContentType: t.trackContentType || 'text/vtt'
        };
      }),
      activeTextTrackIds: activeTextTracks.map(function (t) { return t.trackId; }),
      activeBitmapTrackId: state.activeBitmapTrackId,
      subtitleDelayMs: state.currentSubtitleDelayMs
    };
  }

  function applySubtitleDelayToActiveTextTracks(delayMs) {
    const deltaSec = (delayMs - state.currentSubtitleDelayMs) / 1000.0;
    state.currentSubtitleDelayMs = delayMs;
    if (Math.abs(deltaSec) < 0.001) return;

    const mediaEl = document.querySelector('video');
    if (!mediaEl || !mediaEl.textTracks) return;

    for (let i = 0; i < mediaEl.textTracks.length; i++) {
      const track = mediaEl.textTracks[i];
      if (!track.cues) continue;
      for (let j = 0; j < track.cues.length; j++) {
        const cue = track.cues[j];
        cue.startTime = Math.max(0, cue.startTime + deltaSec);
        cue.endTime = Math.max(cue.startTime + 0.2, cue.endTime + deltaSec);
      }
    }
  }

  function applyTextTrackStyle(styleMsg) {
    const textManager = playerManager.getTextTracksManager();
    if (!textManager) return;

    const style = new cast.framework.messages.TextTrackStyle();
    if (typeof styleMsg.fontScale === 'number') {
      style.fontScale = styleMsg.fontScale;
    }
    if (styleMsg.foregroundColor) {
      style.foregroundColor = styleMsg.foregroundColor;
    }
    if (styleMsg.backgroundColor) {
      style.backgroundColor = styleMsg.backgroundColor;
    }
    if (styleMsg.edgeType) {
      style.edgeType = styleMsg.edgeType;
    }
    if (styleMsg.edgeColor) {
      style.edgeColor = styleMsg.edgeColor;
    }
    if (styleMsg.fontGenericFamily) {
      style.fontGenericFamily = styleMsg.fontGenericFamily;
    }

    textManager.setTextTrackStyle(style);
    state.lastAppliedStyle = styleMsg;

    // Also mirror style to bitmap overlay caption & position
    const layer = document.getElementById('bitmap-subtitle-layer');
    const caption = document.getElementById('bitmap-subtitle-fallback-caption');
    if (layer && typeof styleMsg.bottomOffsetPercent === 'number') {
      layer.style.bottom = styleMsg.bottomOffsetPercent + '%';
    }
    if (caption) {
      if (styleMsg.foregroundColor) caption.style.color = styleMsg.foregroundColor.slice(0, 7);
      if (styleMsg.backgroundCss) caption.style.backgroundColor = styleMsg.backgroundCss;
      if (styleMsg.textAlign) caption.style.textAlign = styleMsg.textAlign;
      if (typeof styleMsg.fontScale === 'number') {
        caption.style.fontSize = (2.6 * styleMsg.fontScale) + 'vw';
      }
    }

    broadcastToSenders({
      type: 'SUBTITLE_STYLE_CONFIRMED',
      applied: true
    });
  }

  function updateBitmapSubtitleOverlay(currentTimeSec) {
    const caption = document.getElementById('bitmap-subtitle-fallback-caption');
    if (!caption) return;
    if (!state.activeBitmapManifest || !Array.isArray(state.activeBitmapManifest.cues)) {
      caption.style.display = 'none';
      return;
    }
    const nowMs = Math.round(currentTimeSec * 1000) - state.currentSubtitleDelayMs;
    const match = state.activeBitmapManifest.cues.find(function (c) {
      return nowMs >= c.startMs && nowMs <= c.endMs;
    });
    if (match && match.ocrText) {
      caption.textContent = match.ocrText;
      caption.style.display = 'block';
    } else {
      caption.style.display = 'none';
    }
  }

  function clearBitmapSubtitles() {
    state.activeBitmapManifest = null;
    state.activeBitmapTrackId = null;
    const caption = document.getElementById('bitmap-subtitle-fallback-caption');
    if (caption) {
      caption.textContent = '';
      caption.style.display = 'none';
    }
  }

  // Handle Sender -> Receiver custom track management commands
  context.addCustomMessageListener(NAMESPACE, function (event) {
    const msg = event.data || {};
    const senderId = event.senderId;

    switch (msg.type) {
      case 'REQUEST_TRACK_STATE': {
        context.sendCustomMessage(NAMESPACE, senderId, serializeTrackSnapshot());
        break;
      }

      case 'SELECT_AUDIO_TRACK': {
        const audioManager = playerManager.getAudioTracksManager();
        if (!audioManager) {
          sendError('AUDIO_MANAGER_UNAVAILABLE', 'AudioTracksManager not ready on receiver.', senderId);
          return;
        }
        try {
          const tracks = audioManager.getTracks() || [];
          let target = null;
          if (typeof msg.castTrackId === 'number') {
            target = tracks.find(function (t) { return t.trackId === msg.castTrackId; });
          }
          if (!target && msg.language) {
            target = tracks.find(function (t) {
              return (t.language || '').toLowerCase().startsWith(msg.language.toLowerCase());
            });
          }
          if (!target) {
            sendError('AUDIO_TRACK_NOT_FOUND', 'Requested audio rendition is not exposed in active stream.', senderId);
            return;
          }
          audioManager.setActiveById(target.trackId);
          const confirmed = audioManager.getActiveTrack();
          broadcastToSenders({
            type: 'AUDIO_TRACK_CONFIRMED',
            trackUniqueId: msg.trackUniqueId || null,
            castTrackId: confirmed ? confirmed.trackId : target.trackId,
            language: confirmed ? confirmed.language : target.language,
            name: confirmed ? confirmed.name : target.name
          });
        } catch (err) {
          sendError('AUDIO_SWITCH_FAILED', String(err), senderId);
        }
        break;
      }

      case 'ADD_EXTERNAL_SUBTITLE': {
        const textManager = playerManager.getTextTracksManager();
        if (!textManager) {
          sendError('TEXT_MANAGER_UNAVAILABLE', 'TextTracksManager not ready.', senderId);
          return;
        }
        clearBitmapSubtitles();
        try {
          const existing = (textManager.getTracks() || []).find(function (t) {
            return t.trackContentId === msg.url;
          });
          let targetId;
          if (existing) {
            targetId = existing.trackId;
          } else {
            const newTrack = textManager.createTrack();
            newTrack.trackContentId = msg.url;
            newTrack.trackContentType = msg.mimeType || 'text/vtt';
            newTrack.language = msg.language || 'en';
            newTrack.name = msg.name || 'Subtitles';
            textManager.addTracks([newTrack]);
            targetId = newTrack.trackId;
          }
          textManager.setActiveByIds([targetId]);
          if (state.lastAppliedStyle) {
            applyTextTrackStyle(state.lastAppliedStyle);
          }
          broadcastToSenders({
            type: 'SUBTITLE_TRACK_CONFIRMED',
            subtitleTrackId: msg.subtitleTrackId || null,
            castTrackId: targetId,
            enabled: true
          });
        } catch (err) {
          sendError('SUBTITLE_ADD_FAILED', String(err), senderId);
        }
        break;
      }

      case 'SELECT_SUBTITLE_TRACK': {
        const textManager = playerManager.getTextTracksManager();
        if (!textManager) {
          sendError('TEXT_MANAGER_UNAVAILABLE', 'TextTracksManager not ready.', senderId);
          return;
        }
        clearBitmapSubtitles();
        try {
          if (msg.disable === true || msg.castTrackId == null) {
            textManager.setActiveByIds([]);
            broadcastToSenders({
              type: 'SUBTITLE_TRACK_CONFIRMED',
              subtitleTrackId: null,
              castTrackId: null,
              enabled: false
            });
          } else {
            textManager.setActiveByIds([msg.castTrackId]);
            broadcastToSenders({
              type: 'SUBTITLE_TRACK_CONFIRMED',
              subtitleTrackId: msg.subtitleTrackId || null,
              castTrackId: msg.castTrackId,
              enabled: true
            });
          }
        } catch (err) {
          sendError('SUBTITLE_SWITCH_FAILED', String(err), senderId);
        }
        break;
      }

      case 'LOAD_BITMAP_SUBTITLES': {
        const textManager = playerManager.getTextTracksManager();
        if (textManager) textManager.setActiveByIds([]);
        fetch(msg.manifestUrl)
          .then(function (resp) {
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            return resp.json();
          })
          .then(function (manifest) {
            state.activeBitmapManifest = manifest;
            state.activeBitmapTrackId = msg.subtitleTrackId || null;
            broadcastToSenders({
              type: 'SUBTITLE_TRACK_CONFIRMED',
              subtitleTrackId: msg.subtitleTrackId || null,
              castTrackId: null,
              enabled: true,
              isBitmap: true
            });
          })
          .catch(function (err) {
            sendError('BITMAP_SUBTITLE_LOAD_FAILED', String(err), senderId);
          });
        break;
      }

      case 'SET_SUBTITLE_DELAY': {
        if (typeof msg.delayMs === 'number') {
          applySubtitleDelayToActiveTextTracks(msg.delayMs);
          broadcastToSenders({
            type: 'SUBTITLE_DELAY_CONFIRMED',
            delayMs: state.currentSubtitleDelayMs
          });
        }
        break;
      }

      case 'SET_SUBTITLE_STYLE': {
        applyTextTrackStyle(msg);
        break;
      }
    }
  });

  // Intercept LOAD to preserve local LAN CORS access and HLS segment headers
  playerManager.setMessageInterceptor(
    cast.framework.messages.MessageType.LOAD,
    function (loadRequestData) {
      state.currentSubtitleDelayMs = 0;
      clearBitmapSubtitles();
      if (loadRequestData && loadRequestData.media) {
        const media = loadRequestData.media;
        if (media.contentType === 'application/vnd.apple.mpegurl') {
          media.hlsSegmentFormat = cast.framework.messages.HlsSegmentFormat.FMP4;
          media.hlsVideoSegmentFormat = cast.framework.messages.HlsVideoSegmentFormat.FMP4;
        }
      }
      return loadRequestData;
    }
  );

  // Sync discovered tracks to Sender as soon as playback loads & tracks become available
  playerManager.addEventListener(
    cast.framework.events.EventType.PLAYER_LOAD_COMPLETE,
    function () {
      broadcastToSenders(serializeTrackSnapshot());
    }
  );

  playerManager.addEventListener(
    cast.framework.events.EventType.TIME_UPDATE,
    function (event) {
      if (state.activeBitmapManifest && event && typeof event.currentMediaTime === 'number') {
        updateBitmapSubtitleOverlay(event.currentMediaTime);
      }
    }
  );

  playerManager.addEventListener(
    cast.framework.events.EventType.ERROR,
    function (event) {
      sendError(
        'RECEIVER_PLAYBACK_ERROR',
        'Detailed error code: ' + (event ? event.detailedErrorCode : 'unknown')
      );
    }
  );

  const options = new cast.framework.CastReceiverOptions();
  options.disableIdleTimeout = false;
  options.customNamespaces = {};
  options.customNamespaces[NAMESPACE] = cast.framework.system.MessageType.JSON;
  options.supportedCommands =
    cast.framework.messages.Command.ALL_BASIC_MEDIA |
    cast.framework.messages.Command.QUEUE_NEXT |
    cast.framework.messages.Command.QUEUE_PREV |
    cast.framework.messages.Command.EDIT_TRACKS;

  context.start(options);
})();
